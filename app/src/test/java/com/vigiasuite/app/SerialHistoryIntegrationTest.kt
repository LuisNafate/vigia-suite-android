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
