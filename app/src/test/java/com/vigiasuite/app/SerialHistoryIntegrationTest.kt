package com.vigiasuite.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Integra el parser VIGIA/2 existente con el historial: las líneas llegan por onNewData igual que desde el puerto USB.
 * Comprueba que el escaneo, la puntuación y la identidad no cambiaron y que la evaluación se guarda completa.
 */
@RunWith(RobolectricTestRunner::class)
class SerialHistoryIntegrationTest {
    private lateinit var database: HistoryDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var controller: UsbSerialController

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(HistoryRepository.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        controller = UsbSerialController(context, HistoryRepository(context, database, scope))
        // La primera consulta carga SQLite nativo en Robolectric y puede tardar varios segundos: se hace antes de medir.
        runBlocking { database.historyDao().assessments() }
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
    }

    private fun feed(vararg lines: String) {
        // Se parte una línea en dos trozos para comprobar el armado del búfer serial.
        val text = lines.joinToString("\n", postfix = "\n")
        val cut = text.length / 2
        controller.onNewData(text.substring(0, cut).toByteArray())
        controller.onNewData(text.substring(cut).toByteArray())
    }

    private fun awaitAssessments(count: Int) = runBlocking {
        withTimeout(5_000) { while (database.historyDao().assessments().size < count) delay(20) }
        database.historyDao().assessments()
    }

    @Test
    fun protocolLinesProduceTheSameScoreAndAStoredAssessment() {
        feed(
            """{"type":"ready","protocol":2,"firmware":"0.5.0-arduino","ble_ready":true}""",
            """{"type":"identity","protocol":2,"board":"nodemcu-esp32s","chip":"ESP32","mac":"04:B2:47:82:60:1C","wifi":true,"ble":true}""",
            """{"type":"wifi_scan_started"}""",
            """{"type":"wifi_ap","ssid":"Cafe","rssi":-50,"channel":1,"auth":0,"bssid":"AA:AA:AA:AA:AA:01"}""",
            """{"type":"wifi_ap","ssid":"Casa","rssi":-60,"channel":6,"auth":3,"bssid":"AA:AA:AA:AA:AA:02"}""",
            """{"type":"wifi_ap","ssid":"","rssi":-80,"channel":11,"auth":1,"bssid":"AA:AA:AA:AA:AA:03"}""",
            "texto del cargador de arranque que no es JSON",
            """{"type":"wifi_scan_finished","count":3,"truncated":true}""",
            """{"type":"ble_scan_started","duration_s":5}""",
            """{"type":"ble_device","name":"Reloj","address":"11:22:33:44:55:66","address_type":"public","rssi":-50,"connectable":true,"scannable":true,"manufacturer_id":76,"manufacturer_data":"4C00","services":"180d","tx_power":127}""",
            """{"type":"ble_scan_finished","count":1,"observed":1,"truncated":false}""",
        )
        val state = controller.state.value
        assertEquals(3, state.wifiNetworks.size)
        assertEquals(1, state.bleDevices.size)
        assertEquals("Evaluación terminada: 1 dispositivos Bluetooth detectados.", state.message)

        val stored = awaitAssessments(1).single()
        // Fórmula Android existente: 3 + 5 + 12 + 10 (33 % abiertas) + 2 (BLE público conectable cercano) = 32.
        assertEquals(32, stored.score)
        assertEquals("MEDIO", stored.level)
        assertEquals(HistoryStatus.COMPLETE, stored.status)
        assertEquals("nodemcu-esp32s", stored.sensorBoard)
        assertEquals("04:B2:47:82:60:1C", stored.sensorMac)
        assertEquals("0.5.0-arduino", stored.sensorFirmware)
        assertEquals(2, stored.sensorProtocol)
        assertEquals(true, stored.wifiTruncated)
        assertEquals(false, stored.bleTruncated)
        val detail = runBlocking { database.historyDao().detail(stored.id)!! }
        assertEquals(listOf("AA:AA:AA:AA:AA:01", "AA:AA:AA:AA:AA:02", "AA:AA:AA:AA:AA:03"), detail.wifi.map { it.bssid })
        assertEquals("", detail.wifi[2].ssid)
        assertEquals(76, detail.ble.single().manufacturerId)
    }

    @Test
    fun missingOrInvalidFieldsAreUnknownAndNeverRaiseOpenNetworkAlerts() {
        feed(
            """{"type":"wifi_scan_started"}""",
            """{"type":"wifi_ap","ssid":"SinCampos","bssid":"AA:AA:AA:AA:AA:10"}""",
            """{"type":"wifi_ap","ssid":"Texto","rssi":"fuerte","channel":"6","auth":"0","bssid":"AA:AA:AA:AA:AA:11"}""",
            """{"type":"wifi_ap","ssid":"Nulos","rssi":null,"channel":null,"auth":null,"bssid":"AA:AA:AA:AA:AA:12"}""",
            """{"type":"wifi_ap","ssid":"Fuera","rssi":-200,"channel":0,"auth":-1,"bssid":"AA:AA:AA:AA:AA:13"}""",
            """{"type":"wifi_ap","ssid":"Decimal","rssi":-50.5,"channel":6.5,"auth":0.5,"bssid":"AA:AA:AA:AA:AA:14"}""",
            """{"type":"wifi_ap","ssid":"Booleano","rssi":true,"channel":false,"auth":false,"bssid":"AA:AA:AA:AA:AA:15"}""",
            """{"type":"wifi_scan_finished","count":6,"truncated":false}""",
            """{"type":"ble_scan_started","duration_s":5}""",
            """{"type":"ble_device","name":"SinRssi","address":"11:22:33:44:55:66","address_type":"public","connectable":true}""",
            """{"type":"ble_scan_finished","count":1,"observed":1,"truncated":false}""",
        )
        val state = controller.state.value
        assertEquals(6, state.wifiNetworks.size)
        state.wifiNetworks.forEach {
            assertNull("auth de ${it.ssid}", it.auth)
            assertNull("rssi de ${it.ssid}", it.rssi)
            assertNull("canal de ${it.ssid}", it.channel)
        }
        assertNull(state.bleDevices.single().rssi)
        assertEquals("No informada", authLabel(null))
        assertEquals("RSSI no informado", dbmLabel(null))

        val stored = awaitAssessments(1).single()
        // Antes, optInt convertía cada campo ausente en 0: seis redes «abiertas y cercanas» y un BLE «cercano».
        assertEquals(0, stored.score)
        assertEquals("BAJO", stored.level)
        assertEquals(0, stored.wifiOpen)
        assertEquals(0, stored.bleNearby)
        val detail = runBlocking { database.historyDao().detail(stored.id)!! }
        assertTrue(detail.wifi.all { it.auth == null && it.rssi == null && it.channel == null })
        assertNull(detail.ble.single().rssi)
    }

    @Test
    fun validFirmwareValuesAreParsedExactlyAsBefore() {
        feed(
            """{"type":"wifi_scan_started"}""",
            """{"type":"wifi_ap","ssid":"Abierta","rssi":-40,"channel":1,"auth":0,"bssid":"AA:AA:AA:AA:AA:20"}""",
            """{"type":"wifi_ap","ssid":"Limite","rssi":-127,"channel":196,"auth":16,"bssid":"AA:AA:AA:AA:AA:21"}""",
            """{"type":"wifi_scan_finished","count":2,"truncated":false}""",
        )
        val networks = controller.state.value.wifiNetworks
        assertEquals(listOf(-40, -127), networks.map { it.rssi })
        assertEquals(listOf(1, 196), networks.map { it.channel })
        assertEquals(listOf(0, 16), networks.map { it.auth })
    }

    @Test
    fun failedStandaloneBleScanIsNotRecordedAsBefore() {
        feed(
            """{"type":"ble_scan_started","duration_s":5}""",
            """{"type":"error","module":"ble","code":"ble_scan_failed"}""",
            """{"type":"ble_scan_finished","count":0,"failed":true}""",
        )
        assertEquals("El inventario Bluetooth no pudo completarse.", controller.state.value.message)
        Thread.sleep(300)
        assertTrue(runBlocking { database.historyDao().assessments() }.isEmpty())
    }
}
