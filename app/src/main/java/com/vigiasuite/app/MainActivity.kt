package com.vigiasuite.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    private lateinit var serialController: UsbSerialController
    private lateinit var history: HistoryRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        history = HistoryRepository.create(this)
        history.start()
        serialController = UsbSerialController(this, history)
        serialController.register()
        setContent {
            VigiaTheme {
                VigiaApp(serialController, history)
            }
        }
    }

    override fun onDestroy() {
        serialController.unregister()
        super.onDestroy()
    }
}

internal val Night = Color(0xFFE9E9E7)
internal val Panel = Color(0xFFF9F9F7)
internal val PanelSoft = Color(0xFFE1E1DE)
internal val Cyan = Color(0xFF202020)
internal val Amber = Color(0xFF75613A)
internal val Muted = Color(0xFF6D6D6D)

@Composable
private fun VigiaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = androidx.compose.material3.lightColorScheme(
            primary = Cyan,
            secondary = Amber,
            background = Night,
            surface = Panel,
            onPrimary = Color.White,
            onBackground = Color(0xFF191919),
            onSurface = Color(0xFF191919),
        ),
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VigiaApp(controller: UsbSerialController, history: HistoryRepository) {
    val state by controller.state.collectAsState()
    val assessments by history.assessments.collectAsState()
    var selectedSection by remember { mutableIntStateOf(0) }
    val sections = listOf(
        Triple("Inicio", Icons.Default.Home, "Conectar y evaluar"),
        Triple("Resumen", Icons.Default.Assessment, "Riesgos y acciones"),
        Triple("Wi-Fi", Icons.Default.Router, "Redes detectadas"),
        Triple("Bluetooth", Icons.Default.Bluetooth, "Dispositivos cercanos"),
        Triple("Historial", Icons.Default.History, "Evaluaciones guardadas"),
        Triple("Sistema", Icons.Default.Settings, "Diagnóstico técnico"),
    )

    Scaffold(
        containerColor = Night,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("VIGÍA SUITE", fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                        Text(sections[selectedSection].third, color = Muted, fontSize = 12.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Night, titleContentColor = Color(0xFF191919)),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Panel) {
                sections.forEachIndexed { index, section ->
                    NavigationBarItem(
                        selected = selectedSection == index,
                        onClick = { selectedSection = index },
                        icon = { Icon(section.second, null) },
                        label = { Text(section.first, fontSize = 9.sp) },
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (selectedSection) {
                0 -> HomeSection(state, controller) { selectedSection = 1 }
                1 -> { AnalysisCard(state, controller); HistoryCard(assessments, history) { selectedSection = 4 } }
                2 -> { SectionAction("Redes Wi-Fi", "Revisa redes abiertas, protección y canales.", "Actualizar redes", state.status == ConnectionStatus.CONNECTED) { controller.send("SCAN_WIFI") }; WifiResultsCard(state.wifiNetworks) }
                3 -> { SectionAction("Dispositivos Bluetooth", "Observa cercanía y privacidad sin conectarte a terceros.", "Actualizar dispositivos", state.status == ConnectionStatus.CONNECTED) { controller.send("SCAN_BLE") }; BleResultsCard(state.bleDevices) }
                4 -> HistorySection(history, assessments)
                else -> { Text("HERRAMIENTAS DEL SENSOR", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold); ModuleGrid(state.status == ConnectionStatus.CONNECTED, controller); ConsoleCard(state.console) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun HomeSection(state: SerialUiState, controller: UsbSerialController, openSummary: () -> Unit) {
    SensorCard(state, controller)
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Siguiente paso", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(if (state.status == ConnectionStatus.CONNECTED) "Evalúa tu entorno" else "Conecta el sensor", fontWeight = FontWeight.Black, fontSize = 20.sp)
            Text(
                if (state.status == ConnectionStatus.CONNECTED) "Vigía medirá Wi-Fi y Bluetooth en orden. No intentará conectarse a otros equipos."
                else "Conecta el ESP32 por OTG, pulsa Conectar y acepta el permiso USB.",
                color = Muted, fontSize = 13.sp,
            )
            Button(
                enabled = state.status == ConnectionStatus.CONNECTED && !state.isAnalyzing,
                onClick = { controller.runFullAnalysis(); openSummary() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Night),
            ) { Text(if (state.isAnalyzing) "Evaluando…" else "Evaluar mi entorno") }
        }
    }
    if (state.wifiNetworks.isNotEmpty() || state.bleDevices.isNotEmpty()) {
        Button(onClick = openSummary, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = PanelSoft, contentColor = Cyan)) { Text("Ver último resumen") }
    }
}

@Composable
private fun SectionAction(title: String, description: String, action: String, enabled: Boolean, onClick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Black, fontSize = 18.sp)
            Text(description, color = Muted, fontSize = 12.sp)
            Button(enabled = enabled, onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Night)) { Text(action) }
        }
    }
}

internal data class MobileAnalysis(
    val score: Int,
    val level: String,
    val recommendations: List<String>,
)

internal fun analyze(state: SerialUiState): MobileAnalysis {
    val wifi = state.wifiNetworks
    val ble = state.bleDevices
    // Misma función que usa el historial: la pantalla y la evaluación guardada no pueden diferir.
    val risk = RiskScore.compute(wifi, ble)
    val open = risk.open
    val weak = risk.weak
    val stableNearby = risk.stableNearby
    val recommendations = buildList {
        if (weak > 0) add("Migra $weak red(es) con WEP/WPA a WPA2-AES o WPA3.")
        if (open > 0) add("Valida $open red(es) abiertas y separa invitados de equipos internos.")
        if (stableNearby > 0) add("Revisa privacidad BLE en $stableNearby dispositivo(s) cercano(s) con identificador estable.")
        if (wifi.isNotEmpty()) add("Repite la medición en otro horario y compara canales, BSSID y seguridad.")
        if (isEmpty() && ble.isNotEmpty()) add("Mantén firmware actualizado y desactiva emparejamiento BLE cuando no se use.")
    }
    return MobileAnalysis(risk.score, risk.level, recommendations)
}

private fun reportText(state: SerialUiState): String {
    val result = analyze(state)
    return buildString {
        appendLine("VIGÍA SUITE · INFORME LOCAL")
        appendLine("Fecha: ${DateFormat.getDateTimeInstance().format(Date())}")
        appendLine("Riesgo observado: ${result.score}/100 · ${result.level}")
        appendLine("Wi-Fi: ${state.wifiNetworks.size} redes; ${state.wifiNetworks.count { it.auth == 0 }} abiertas")
        appendLine("Bluetooth LE: ${state.bleDevices.size} dispositivos; ${state.bleDevices.count { it.rssi != null && it.rssi >= -65 }} cercanos")
        appendLine("\nRecomendaciones:")
        result.recommendations.forEach { appendLine("- $it") }
        appendLine("\nInventario Wi-Fi:")
        state.wifiNetworks.sortedByDescending { it.rssi }.forEach { appendLine("- ${it.ssid.ifBlank { "[oculta]" }} | ${dbmLabel(it.rssi)} | canal ${it.channel ?: "no informado"} | ${authLabel(it.auth)} | ${it.bssid}") }
        appendLine("\nInventario Bluetooth:")
        state.bleDevices.sortedByDescending { it.rssi }.forEach { appendLine("- ${it.name.ifBlank { "[sin nombre]" }} | ${dbmLabel(it.rssi)} | ${it.address} | ${bleAddressLabel(it.addressType)} | ${if (it.connectable) "conectable" else "solo anuncio"}") }
        appendLine("\nObservación pasiva local: un hallazgo no demuestra por sí mismo una vulnerabilidad explotable.")
    }
}

@Composable
private fun AnalysisCard(state: SerialUiState, controller: UsbSerialController) {
    val context = LocalContext.current
    val result = analyze(state)
    val hasData = state.wifiNetworks.isNotEmpty() || state.bleDevices.isNotEmpty()
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column { Text("Análisis de exposición", fontWeight = FontWeight.Bold); Text("Wi-Fi + Bluetooth", color = Muted, fontSize = 11.sp) }
                Text(if (hasData) "${result.score}/100 · ${result.level}" else "SIN DATOS", color = if (result.score >= 60) Color(0xFF9C3131) else if (result.score >= 30) Amber else Cyan, fontWeight = FontWeight.Black)
            }
            if (!hasData) EmptyResult("Ejecuta Radar Wi-Fi y después Radar Bluetooth para generar el informe.")
            else result.recommendations.forEach { recommendation -> Text("• $recommendation", color = Muted, fontSize = 12.sp) }
            Button(
                enabled = hasData,
                onClick = {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "Informe Vigía Suite"); putExtra(Intent.EXTRA_TEXT, reportText(state))
                    }
                    context.startActivity(Intent.createChooser(intent, "Guardar o compartir informe"))
                },
                colors = ButtonDefaults.buttonColors(containerColor = PanelSoft, contentColor = Cyan),
            ) { Icon(Icons.Default.Share, null); Text("  Guardar o compartir") }
        }
    }
}

@Composable
private fun HistoryCard(history: List<AssessmentEntity>, repository: HistoryRepository, openHistory: () -> Unit) {
    var confirmClear by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column { Text("Registro local", fontWeight = FontWeight.Bold); Text("${history.size} evaluaciones guardadas", color = Muted, fontSize = 11.sp) }
                Button(enabled = history.isNotEmpty(), onClick = { confirmClear = true }, colors = ButtonDefaults.buttonColors(containerColor = PanelSoft, contentColor = Cyan)) { Text("Limpiar") }
            }
            if (history.isEmpty()) EmptyResult("Las evaluaciones completadas se guardarán aquí.")
            history.take(5).forEach { item ->
                HorizontalDivider(color = PanelSoft)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column { Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.createdAt)), fontSize = 12.sp); Text("${item.wifiCount ?: "—"} Wi-Fi · ${item.bleCount ?: "—"} BLE", color = Muted, fontSize = 11.sp) }
                    Text("${item.score}/100 · ${item.level}", color = Cyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
            if (history.isNotEmpty()) Button(onClick = openHistory, colors = ButtonDefaults.buttonColors(containerColor = PanelSoft, contentColor = Cyan)) { Text("Ver historial completo") }
        }
    }
    if (confirmClear) ConfirmDialog(
        title = "Eliminar todo el historial",
        text = "Se eliminarán las ${history.size} evaluaciones guardadas en este teléfono, con todas sus observaciones. No se puede deshacer.",
        confirm = "Eliminar todo",
        onConfirm = { confirmClear = false; repository.launch { deleteAll() } },
        onDismiss = { confirmClear = false },
    )
}

@Composable
private fun SensorCard(state: SerialUiState, controller: UsbSerialController) {
    val statusColor = when (state.status) {
        ConnectionStatus.CONNECTED -> Cyan
        ConnectionStatus.ERROR -> Color(0xFF9C3131)
        ConnectionStatus.DEVICE_FOUND, ConnectionStatus.PERMISSION_REQUIRED -> Amber
        else -> Muted
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(44.dp)
                        .background(statusColor.copy(alpha = .15f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Cable, null, tint = statusColor)
                }
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(state.deviceName, fontWeight = FontWeight.Bold)
                    Text(state.chipset, color = Muted, fontSize = 13.sp)
                }
                Text(
                    state.status.name.replace('_', ' '),
                    color = statusColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                )
            }
            Text(state.message, color = Muted, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = controller::discover,
                    colors = ButtonDefaults.buttonColors(containerColor = PanelSoft, contentColor = Cyan),
                ) {
                    Text("Buscar")
                }
                Button(
                    onClick = {
                        if (state.status == ConnectionStatus.CONNECTED) controller.disconnect()
                        else controller.connect()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Night),
                ) {
                    Text(if (state.status == ConnectionStatus.CONNECTED) "Desconectar" else "Conectar")
                }
            }
        }
    }
}

@Composable
private fun ModuleGrid(connected: Boolean, controller: UsbSerialController) {
    val modules = listOf(
        Triple("Validar enlace", "PING", Icons.Default.Cable),
        Triple("Identidad", "INFO", Icons.Default.Security),
        Triple("Estado del sensor", "STATUS", Icons.Default.Radar),
        Triple("Estado Bluetooth", "BLE_STATUS", Icons.Default.Bluetooth),
    )

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        modules.chunked(2).forEach { rowModules ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowModules.forEach { (label, command, icon) ->
                    ModuleCard(
                        label = label,
                        icon = icon,
                        enabled = connected,
                        modifier = Modifier.weight(1f),
                        onClick = { controller.send(command) },
                    )
                }
                if (rowModules.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun WifiResultsCard(networks: List<WifiNetwork>) {
    ResultCard(title = "Redes Wi-Fi", count = networks.size) {
        if (networks.isEmpty()) {
            EmptyResult("Ejecuta Radar Wi-Fi para ver resultados.")
        } else {
            networks.sortedByDescending { it.rssi }.take(24).forEach { network ->
                ResultRow(
                    title = network.ssid.ifBlank { "[oculta]" },
                    detail = "Canal ${network.channel ?: "no informado"} · ${authLabel(network.auth)}",
                    signal = dbmLabel(network.rssi),
                )
            }
        }
    }
}

@Composable
private fun BleResultsCard(devices: List<BleDevice>) {
    ResultCard(title = "Dispositivos Bluetooth LE", count = devices.size) {
        if (devices.isEmpty()) {
            EmptyResult("Ejecuta Radar Bluetooth para ver resultados.")
        } else {
            devices.sortedByDescending { it.rssi }.take(24).forEach { device ->
                ResultRow(
                    title = device.name.ifBlank { "[sin nombre]" },
                    detail = "${device.address} · ${bleAddressLabel(device.addressType)} · ${manufacturerLabel(device.manufacturerId)} · ${if (device.connectable) "conectable" else "solo anuncio"}",
                    signal = "${dbmLabel(device.rssi)} · ${proximityLabel(device.rssi)}",
                )
            }
        }
    }
}

@Composable
internal fun ResultCard(title: String, count: Int, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, fontWeight = FontWeight.Bold)
                Text("$count resultados", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            HorizontalDivider(color = PanelSoft)
            content()
        }
    }
}

@Composable
internal fun EmptyResult(message: String) {
    Text(message, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 10.dp))
}

@Composable
internal fun ResultRow(title: String, detail: String, signal: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(detail, color = Muted, fontSize = 11.sp)
        }
        Text(signal, color = Cyan, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }
}

/** Etiqueta de wifi_auth_mode_t. null = el sensor no informó un modo válido: no se presenta como red abierta. */
internal fun authLabel(value: Int?): String = when (value) {
    null -> "No informada"
    0 -> "Abierta"
    1 -> "WEP"
    2 -> "WPA-PSK"
    3 -> "WPA2-PSK"
    4 -> "WPA/WPA2"
    5 -> "WPA2 Enterprise"
    6 -> "WPA3-PSK"
    7 -> "WPA2/WPA3"
    8 -> "WAPI"
    // Modos 9-16 de wifi_auth_mode_t en el núcleo esp32 3.3.12 (firmware/build-config.json del repositorio Web).
    9 -> "OWE"
    10 -> "WPA3 Enterprise 192 bits"
    11 -> "WPA3-PSK (EXT)"
    12 -> "WPA3-PSK mixto (EXT)"
    13 -> "DPP"
    14 -> "WPA3 Enterprise"
    15 -> "WPA2/WPA3 Enterprise"
    16 -> "WPA Enterprise"
    else -> "Modo $value no reconocido"
}

internal fun dbmLabel(rssi: Int?): String = rssi?.let { "$it dBm" } ?: "RSSI no informado"

internal fun proximityLabel(rssi: Int?): String = when {
    rssi == null -> "proximidad desconocida"
    rssi >= -55 -> "muy cerca"
    rssi >= -65 -> "cerca"
    rssi >= -80 -> "media"
    else -> "lejana"
}

internal fun bleAddressLabel(value: String): String = when (value) {
    "public" -> "pública"
    "random" -> "aleatoria"
    "public_identity" -> "identidad pública"
    "random_identity" -> "identidad aleatoria"
    else -> "dirección no informada"
}

internal fun manufacturerLabel(value: Int): String = when (value) {
    6 -> "Microsoft"
    76 -> "Apple"
    117 -> "Samsung"
    224 -> "Google"
    in 0..65535 -> "fabricante 0x%04X".format(value)
    else -> "sin fabricante"
}

@Composable
private fun ModuleCard(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = Panel,
            disabledContainerColor = Panel.copy(alpha = .55f),
        ),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, tint = if (enabled) Cyan else Muted)
            Text(label, fontWeight = FontWeight.Bold)
            Text(if (enabled) "Listo" else "Requiere sensor", color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ConsoleCard(lines: List<String>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF050A11)),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column {
            Row(
                Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.Terminal, null, tint = Color.White)
                Text("Consola del sensor", color = Color.White, fontWeight = FontWeight.Bold)
            }
            HorizontalDivider(color = PanelSoft)
            if (lines.isEmpty()) {
                Text(
                    "Los eventos USB aparecerán aquí.",
                    color = Muted,
                    modifier = Modifier.padding(16.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(190.dp).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(lines) { line ->
                        Text(line, color = Color(0xFFC9F7EE), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

