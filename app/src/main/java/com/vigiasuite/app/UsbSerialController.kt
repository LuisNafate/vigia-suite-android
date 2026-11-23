package com.vigiasuite.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.annotation.VisibleForTesting
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import org.json.JSONObject

enum class ConnectionStatus {
    DISCONNECTED,
    DEVICE_FOUND,
    PERMISSION_REQUIRED,
    CONNECTING,
    CONNECTED,
    ERROR,
}

data class SerialUiState(
    val status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val deviceName: String = "Sin dispositivo",
    val chipset: String = "—",
    val message: String = "Conecta el NodeMCU ESP-32S mediante un cable OTG de datos.",
    val console: List<String> = emptyList(),
    val wifiNetworks: List<WifiNetwork> = emptyList(),
    val bleDevices: List<BleDevice> = emptyList(),
    val isAnalyzing: Boolean = false,
)

/** Resumen que guardaba la versión 0.7.0 en SharedPreferences. Se conserva para migrarlo a Room. */
data class AnalysisRecord(
    val createdAt: Long,
    val score: Int,
    val level: String,
    val wifiTotal: Int,
    val wifiOpen: Int,
    val bleTotal: Int,
    val bleNearby: Int,
)

/** rssi, channel y auth son null cuando el sensor no los envió o no son un entero válido: nunca se inventan. */
data class WifiNetwork(
    val ssid: String,
    val rssi: Int?,
    val channel: Int?,
    val auth: Int?,
    val bssid: String,
)

data class BleDevice(
    val name: String,
    val rssi: Int?,
    val address: String,
    val addressType: String,
    val connectable: Boolean,
    val manufacturerId: Int,
    val services: String,
)

/** Rangos válidos de los campos numéricos de VIGIA/2. Son los mismos que aplica el historial. */
internal object VigiaFields {
    val RSSI = -127..20
    val CHANNEL = 1..196
    val AUTH = 0..255
}

/**
 * Entero del evento sólo si el campo existe, es numérico, entero y está en el rango. optInt() devolvía 0 para un
 * campo ausente o inválido, y 0 significa «red abierta» y «señal máxima».
 */
internal fun JSONObject.intIn(name: String, range: IntRange): Int? {
    val value = opt(name) as? Number ?: return null
    val number = value.toDouble()
    if (number % 1.0 != 0.0) return null
    return number.toInt().takeIf { number in range.first.toDouble()..range.last.toDouble() }
}

class UsbSerialController(context: Context, private val history: HistoryRepository) : SerialInputOutputManager.Listener {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val _state = MutableStateFlow(SerialUiState())
    val state: StateFlow<SerialUiState> = _state.asStateFlow()

    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private var pendingDevice: UsbDevice? = null
    private var receiverRegistered = false
    private var fullAnalysisPending = false
    // El error de BLE desactiva fullAnalysisPending antes de ble_scan_finished: esto recuerda que la evaluación guiada esperaba BLE.
    private var analysisAwaitingBle = false
    private val receiveBuffer = StringBuilder()

    // Datos que el protocolo VIGIA/2 ya entrega y que el historial conserva; lo no recibido queda en null.
    private var sensorIdentity = SensorIdentity()
    private var wifiTruncated: Boolean? = null
    private var bleTruncated: Boolean? = null

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val device = intent.usbDeviceExtra()
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) {
                openDevice(device)
            } else {
                update(
                    status = ConnectionStatus.ERROR,
                    message = "Permiso USB rechazado. Pulsa conectar para intentarlo otra vez.",
                )
                log("Permiso USB rechazado")
            }
        }
    }

    fun register() {
        if (receiverRegistered) return
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(permissionReceiver, filter)
        }
        receiverRegistered = true
        discover()
    }

    fun unregister() {
        disconnect()
        if (receiverRegistered) {
            appContext.unregisterReceiver(permissionReceiver)
            receiverRegistered = false
        }
    }

    fun discover() {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        val driver = drivers.firstOrNull()
        if (driver == null) {
            pendingDevice = null
            update(
                status = ConnectionStatus.DISCONNECTED,
                deviceName = "Sin dispositivo",
                chipset = "—",
                message = "No se encontró un adaptador CP210x o CH340. Revisa el cable OTG.",
            )
            log("Búsqueda finalizada: no se encontró un puerto serial compatible")
            return
        }

        pendingDevice = driver.device
        update(
            status = ConnectionStatus.DEVICE_FOUND,
            deviceName = driver.device.productName ?: driver.device.deviceName,
            chipset = chipsetName(driver.device),
            message = "Dispositivo serial encontrado. Ya puedes solicitar la conexión.",
        )
        log("Dispositivo encontrado VID=%04X PID=%04X".format(driver.device.vendorId, driver.device.productId))
    }

    fun connect() {
        val device = pendingDevice ?: run {
            discover()
            pendingDevice
        }
        if (device == null) return

        if (!usbManager.hasPermission(device)) {
            val permissionIntent = PendingIntent.getBroadcast(
                appContext,
                0,
                Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            update(
                status = ConnectionStatus.PERMISSION_REQUIRED,
                message = "Android solicitará permiso para usar el puerto USB.",
            )
            usbManager.requestPermission(device, permissionIntent)
            return
        }
        openDevice(device)
    }

    private fun openDevice(device: UsbDevice) {
        disconnect(updateUi = false)
        beginSession()
        update(status = ConnectionStatus.CONNECTING, message = "Abriendo puerto a 115200 baudios…")

        try {
            val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
                ?: error("No existe un controlador compatible para el dispositivo")
            val connection = usbManager.openDevice(device)
                ?: error("Android no pudo abrir el dispositivo USB")
            val selectedPort = driver.ports.firstOrNull()
                ?: error("El dispositivo no expone puertos seriales")

            selectedPort.open(connection)
            selectedPort.setParameters(
                BAUD_RATE,
                8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE,
            )
            selectedPort.dtr = true
            selectedPort.rts = true

            port = selectedPort
            ioManager = SerialInputOutputManager(selectedPort, this).also { it.start() }
            update(
                status = ConnectionStatus.CONNECTED,
                deviceName = device.productName ?: device.deviceName,
                chipset = chipsetName(device),
                message = "NodeMCU conectado. Envía PING para validar el firmware.",
            )
            log("Puerto abierto: 115200 8N1")
            send("PING")
        } catch (error: Exception) {
            disconnect(updateUi = false)
            update(status = ConnectionStatus.ERROR, message = error.message ?: "Error USB desconocido")
            log("Error de conexión: ${error.message}")
        }
    }

    /** Cada conexión empieza sin identidad: nada del sensor anterior puede asociarse a las evaluaciones nuevas. */
    private fun beginSession() {
        sensorIdentity = SensorIdentity()
    }

    /** Sólo para pruebas: usa un puerto simulado con el mismo inicio de sesión que openDevice(). */
    @VisibleForTesting
    internal fun attachPortForTest(testPort: UsbSerialPort) {
        disconnect(updateUi = false)
        beginSession()
        port = testPort
        update(status = ConnectionStatus.CONNECTED)
    }

    fun send(command: String) {
        val activePort = port
        if (activePort == null) {
            log("No se envió $command: el sensor no está conectado")
            return
        }

        try {
            val payload = "$command\n".toByteArray(StandardCharsets.UTF_8)
            activePort.write(payload, WRITE_TIMEOUT_MS)
            log("> $command")
        } catch (error: IOException) {
            update(status = ConnectionStatus.ERROR, message = "Se perdió la comunicación USB")
            log("Error al escribir: ${error.message}")
        }
    }

    fun runFullAnalysis() {
        if (port == null || _state.value.isAnalyzing) return
        fullAnalysisPending = true
        _state.value = _state.value.copy(isAnalyzing = true, wifiNetworks = emptyList(), bleDevices = emptyList(), message = "Validando identidad del sensor…")
        // Igual que la versión Web: la identidad se pide en cada evaluación. El firmware procesa los comandos en orden,
        // así que el evento identity llega antes de wifi_scan_started y queda asociado a esta evaluación.
        send("INFO")
        send("SCAN_WIFI")
    }

    fun disconnect(updateUi: Boolean = true) {
        ioManager?.listener = null
        ioManager?.stop()
        ioManager = null
        try {
            port?.close()
        } catch (_: IOException) {
            // The USB device may already have been detached.
        }
        port = null
        fullAnalysisPending = false
        analysisAwaitingBle = false
        if (updateUi) {
            _state.value = _state.value.copy(status = ConnectionStatus.DISCONNECTED, message = "Sensor desconectado.", isAnalyzing = false)
            log("Conexión cerrada")
        }
    }

    override fun onNewData(data: ByteArray) {
        val completeLines = mutableListOf<String>()
        synchronized(receiveBuffer) {
            receiveBuffer.append(data.toString(StandardCharsets.UTF_8).replace("\r", ""))
            while (true) {
                val newline = receiveBuffer.indexOf("\n")
                if (newline < 0) break
                completeLines += receiveBuffer.substring(0, newline)
                receiveBuffer.delete(0, newline + 1)
            }
        }
        completeLines.filter { it.isNotBlank() }.forEach(::handleLine)
    }

    override fun onRunError(error: Exception) {
        update(status = ConnectionStatus.ERROR, message = "Lectura USB detenida: ${error.message}")
        log("Error de lectura: ${error.message}")
    }

    private fun chipsetName(device: UsbDevice): String = when (device.vendorId) {
        0x10C4 -> "Silicon Labs CP210x"
        0x1A86 -> "QinHeng CH340/CH341"
        0x303A -> "Espressif USB"
        else -> "VID %04X / PID %04X".format(device.vendorId, device.productId)
    }

    private fun handleLine(line: String) {
        log("< $line")
        try {
            val event = JSONObject(line)
            if (event.has("protocol")) sensorIdentity = sensorIdentity.copy(protocol = event.optInt("protocol").takeIf { it > 0 })
            when (event.optString("type")) {
                "ready" -> {
                    sensorIdentity = sensorIdentity.copy(firmware = event.optString("firmware").ifBlank { null })
                    update(message = "Firmware ${event.optString("firmware")} listo.")
                }
                "pong" -> update(message = "Enlace validado: el ESP32 respondió correctamente.")
                "identity" -> {
                    sensorIdentity = sensorIdentity.copy(
                        board = event.optString("board").ifBlank { null },
                        chip = event.optString("chip").ifBlank { null },
                        mac = event.optString("mac").ifBlank { null },
                    )
                    update(message = "${event.optString("board")} · ${event.optString("chip")} · ${event.optString("mac")}")
                }
                "status" -> update(
                    message = "Memoria libre: ${event.optLong("heap_free")} bytes · Activo: ${event.optLong("uptime_ms") / 1000} s",
                )
                "wifi_scan_started" -> {
                    wifiTruncated = null
                    _state.value = _state.value.copy(
                        wifiNetworks = emptyList(),
                        message = "Analizando redes Wi-Fi…",
                    )
                }
                "wifi_ap" -> {
                    val network = WifiNetwork(
                        ssid = event.optString("ssid"),
                        rssi = event.intIn("rssi", VigiaFields.RSSI),
                        channel = event.intIn("channel", VigiaFields.CHANNEL),
                        auth = event.intIn("auth", VigiaFields.AUTH),
                        bssid = event.optString("bssid"),
                    )
                    _state.value = _state.value.copy(wifiNetworks = _state.value.wifiNetworks + network)
                }
                "wifi_scan_finished" -> {
                    wifiTruncated = if (event.has("truncated")) event.optBoolean("truncated") else null
                    update(message = "Wi-Fi finalizado: ${event.optInt("count")} redes detectadas.")
                    if (fullAnalysisPending) { analysisAwaitingBle = true; send("SCAN_BLE") }
                }
                "ble_scan_started" -> {
                    bleTruncated = null
                    _state.value = _state.value.copy(
                        bleDevices = emptyList(),
                        message = "Analizando Bluetooth LE durante 5 segundos…",
                    )
                }
                "ble_device" -> {
                    val device = BleDevice(
                        name = event.optString("name"),
                        rssi = event.intIn("rssi", VigiaFields.RSSI),
                        address = event.optString("address"),
                        addressType = event.optString("address_type", "unknown"),
                        connectable = event.optBoolean("connectable"),
                        manufacturerId = event.optInt("manufacturer_id", -1),
                        services = event.optString("services"),
                    )
                    val devices = _state.value.bleDevices.toMutableList()
                    val existing = devices.indexOfFirst { it.address == device.address }
                    if (existing >= 0) devices[existing] = device else devices += device
                    _state.value = _state.value.copy(bleDevices = devices)
                }
                "ble_scan_finished" -> {
                    val failed = event.optBoolean("failed")
                    val partOfAnalysis = analysisAwaitingBle
                    bleTruncated = if (event.has("truncated")) event.optBoolean("truncated") else null
                    _state.value = _state.value.copy(
                        message = if (failed) "El inventario Bluetooth no pudo completarse."
                        else "Evaluación terminada: ${event.optInt("count")} dispositivos Bluetooth detectados.",
                        isAnalyzing = false,
                    )
                    fullAnalysisPending = false
                    analysisAwaitingBle = false
                    // Igual que antes, un escaneo BLE correcto registra la evaluación. Si falló dentro de la
                    // evaluación guiada, ahora se conserva como incompleta en lugar de descartarse.
                    if (!failed) recordAnalysis(HistoryStatus.COMPLETE, bleFailed = false)
                    else if (partOfAnalysis) recordAnalysis(HistoryStatus.PARTIAL, bleFailed = true)
                }
                "ble_status" -> update(
                    message = if (event.optBoolean("ready")) "Bluetooth LE disponible y listo."
                    else "Bluetooth LE no pudo inicializarse.",
                )
                "error" -> {
                    fullAnalysisPending = false
                    _state.value = _state.value.copy(message = "El sensor reportó: ${event.optString("code")}", isAnalyzing = false)
                }
            }
        } catch (_: Exception) {
            // El cargador de arranque también puede escribir mensajes que no son JSON.
        }
    }

    private fun update(
        status: ConnectionStatus = _state.value.status,
        deviceName: String = _state.value.deviceName,
        chipset: String = _state.value.chipset,
        message: String = _state.value.message,
    ) {
        _state.value = _state.value.copy(
            status = status,
            deviceName = deviceName,
            chipset = chipset,
            message = message,
        )
    }

    private fun log(message: String) {
        val timestamp = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        _state.value = _state.value.copy(
            console = (_state.value.console + "[$timestamp] $message").takeLast(MAX_LOG_LINES),
        )
    }

    private fun recordAnalysis(status: String, bleFailed: Boolean) {
        val wifi = _state.value.wifiNetworks
        val ble = _state.value.bleDevices
        if (wifi.isEmpty() && ble.isEmpty()) return
        // La misma función que muestra la pantalla (MainActivity.analyze).
        val score = RiskScore.compute(wifi, ble).score
        val sensor = sensorIdentity
        val truncatedWifi = wifiTruncated
        val truncatedBle = bleTruncated
        history.launch { save(status, sensor, score, wifi, ble, truncatedWifi, truncatedBle, bleFailed) }
    }

    @Suppress("DEPRECATION")
    private fun Intent.usbDeviceExtra(): UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }

    private companion object {
        const val ACTION_USB_PERMISSION = "com.vigiasuite.app.USB_PERMISSION"
        const val BAUD_RATE = 115200
        const val WRITE_TIMEOUT_MS = 1_000
        const val MAX_LOG_LINES = 120
    }
}

