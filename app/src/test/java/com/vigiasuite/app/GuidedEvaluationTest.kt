package com.vigiasuite.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.lang.reflect.Proxy
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Evaluación guiada con un puerto simulado: comandos enviados, orden e identidad asociada a cada evaluación. */
@RunWith(RobolectricTestRunner::class)
class GuidedEvaluationTest {
    private lateinit var database: HistoryDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var controller: UsbSerialController
    private lateinit var repository: HistoryRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(HistoryRepository.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        repository = HistoryRepository(context, database, scope)
        controller = UsbSerialController(context, repository)
        // La primera consulta carga SQLite nativo en Robolectric y puede tardar varios segundos: se hace antes de medir.
        runBlocking { database.historyDao().assessments() }
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
    }

    /** Puerto USB simulado que sólo registra las líneas escritas. */
    private fun fakePort(written: MutableList<String>): UsbSerialPort =
        Proxy.newProxyInstance(UsbSerialPort::class.java.classLoader, arrayOf(UsbSerialPort::class.java)) { _, method, args ->
            if (method.name == "write" && args?.firstOrNull() is ByteArray) written += String(args[0] as ByteArray).trim()
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                Integer.TYPE -> 0
                else -> null
            }
        } as UsbSerialPort

    private fun feed(vararg lines: String) = controller.onNewData(lines.joinToString("\n", postfix = "\n").toByteArray())

    private fun awaitAssessments(count: Int) = runBlocking {
        withTimeout(20_000) { while (database.historyDao().assessments().size < count) delay(20) }
        database.historyDao().assessments().sortedBy { it.createdAt }
    }

    private fun identity(mac: String) =
        """{"type":"identity","protocol":2,"board":"nodemcu-esp32s","chip":"ESP32","mac":"$mac","wifi":true,"ble":true}"""

    private fun scanLines(bssid: String) = arrayOf(
        """{"type":"wifi_scan_started"}""",
        """{"type":"wifi_ap","ssid":"Casa","rssi":-60,"channel":6,"auth":3,"bssid":"$bssid"}""",
        """{"type":"wifi_scan_finished","count":1,"truncated":false}""",
    )

    private val bleLines = arrayOf(
        """{"type":"ble_scan_started","duration_s":5}""",
        """{"type":"ble_scan_finished","count":0,"observed":0,"truncated":false}""",
    )

    @Test
    fun guidedEvaluationRequestsInfoBeforeWifiAndThenBle() {
        val written = mutableListOf<String>()
        controller.attachPortForTest(fakePort(written))

        controller.runFullAnalysis()
        assertEquals(listOf("INFO", "SCAN_WIFI"), written)

        feed(identity("04:B2:47:82:60:1C"), *scanLines("AA:AA:AA:AA:AA:01"))
        assertEquals(listOf("INFO", "SCAN_WIFI", "SCAN_BLE"), written)

        feed(*bleLines)
        val stored = awaitAssessments(1).single()
        assertEquals("nodemcu-esp32s", stored.sensorBoard)
        assertEquals("ESP32", stored.sensorChip)
        assertEquals("04:B2:47:82:60:1C", stored.sensorMac)
        assertEquals(2, stored.sensorProtocol)
        assertEquals(HistoryStatus.COMPLETE, stored.status)
    }

    @Test
    fun anotherSensorNeverInheritsThePreviousIdentity() {
        controller.attachPortForTest(fakePort(mutableListOf()))
        feed("""{"type":"ready","protocol":2,"firmware":"0.5.0-arduino","ble_ready":true}""")
        controller.runFullAnalysis()
        feed(identity("04:B2:47:82:60:1C"), *scanLines("AA:AA:AA:AA:AA:01"), *bleLines)
        awaitAssessments(1)

        // Segundo sensor que no responde a INFO: la evaluación se guarda igual, con identidad desconocida.
        Thread.sleep(5)
        val written = mutableListOf<String>()
        controller.attachPortForTest(fakePort(written))
        controller.runFullAnalysis()
        assertEquals(listOf("INFO", "SCAN_WIFI"), written)
        feed(*scanLines("BB:BB:BB:BB:BB:01"), *bleLines)
        val second = awaitAssessments(2).last()
        assertNull(second.sensorBoard)
        assertNull(second.sensorChip)
        assertNull(second.sensorMac)
        assertNull("el firmware del sensor anterior no se arrastra", second.sensorFirmware)
        assertEquals(HistoryStatus.COMPLETE, second.status)

        // Tercer sensor que sí responde: se guarda su propia MAC.
        Thread.sleep(5)
        controller.attachPortForTest(fakePort(mutableListOf()))
        controller.runFullAnalysis()
        feed(identity("0C:0C:0C:0C:0C:0C"), *scanLines("CC:CC:CC:CC:CC:01"), *bleLines)
        assertEquals("0C:0C:0C:0C:0C:0C", awaitAssessments(3).last().sensorMac)
    }
}
