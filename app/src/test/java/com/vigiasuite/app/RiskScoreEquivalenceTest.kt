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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Casos de app/src/test/resources/scoring-cases.json, copia idéntica de vigia-suite-web/tests/fixtures/: la versión
 * Web los verifica con renderAnalysis(). Cada caso entra por el parser VIGIA/2 como líneas del puerto USB, y se
 * comprueba que la pantalla (analyze) y la evaluación guardada en el historial dan la puntuación esperada.
 */
@RunWith(RobolectricTestRunner::class)
class RiskScoreEquivalenceTest {
    private lateinit var database: HistoryDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var controller: UsbSerialController
    private val cases: List<JSONObject> by lazy {
        val text = javaClass.classLoader!!.getResource("scoring-cases.json")!!.readText()
        JSONObject(text).getJSONArray("cases").let { array -> (0 until array.length()).map(array::getJSONObject) }
    }

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

    private fun events(array: JSONArray, type: String) =
        (0 until array.length()).map { JSONObject(array.getJSONObject(it).toString()).put("type", type).toString() }

    @Test
    fun everySharedCaseScoresTheSameOnScreenAndInHistory() {
        assertTrue("faltan casos compartidos", cases.size >= 10)
        var stored = 0
        cases.forEach { case ->
            val name = case.getString("name")
            val expected = case.getJSONObject("expected")
            val wifi = case.getJSONArray("wifi")
            val ble = case.getJSONArray("ble")
            val lines = listOf("""{"type":"wifi_scan_started"}""") + events(wifi, "wifi_ap") +
                """{"type":"wifi_scan_finished","count":${wifi.length()},"truncated":false}""" +
                """{"type":"ble_scan_started","duration_s":5}""" + events(ble, "ble_device") +
                """{"type":"ble_scan_finished","count":${ble.length()},"observed":${ble.length()},"truncated":false}"""
            controller.onNewData(lines.joinToString("\n", postfix = "\n").toByteArray())

            val state = controller.state.value
            val screen = analyze(state)
            assertEquals("$name (pantalla)", expected.getInt("score"), screen.score)
            assertEquals("$name (nivel)", expected.getString("level"), screen.level)
            assertEquals("$name (RiskScore)", expected.getInt("score"), RiskScore.compute(state.wifiNetworks, state.bleDevices).score)

            // Una evaluación sin observaciones no se guarda, igual que antes.
            if (wifi.length() == 0 && ble.length() == 0) return@forEach
            stored += 1
            val saved = runBlocking {
                withTimeout(20_000) { while (database.historyDao().assessments().size < stored) delay(20) }
                database.historyDao().assessments().maxBy { it.createdAt }
            }
            assertEquals("$name (historial)", screen.score, saved.score)
            assertEquals("$name (nivel guardado)", screen.level, saved.level)
            assertEquals("$name (observaciones)", wifi.length(), saved.wifiCount)
            Thread.sleep(2)
        }
    }

    @Test
    fun congestionRuleUsesOnlyKnownChannels() {
        val networks = List(12) { WifiNetwork("Red$it", -60, null, 3, "AA:AA:AA:AA:AA:%02X".format(it)) }
        assertEquals("12 redes sin canal informado no forman un canal congestionado", 0, RiskScore.compute(networks, emptyList()).score)
        val congested = networks.map { it.copy(channel = 6) }
        val result = RiskScore.compute(congested, emptyList())
        assertEquals(5, result.score)
        assertEquals(6, result.busiestChannel)
        assertEquals(12, result.busiestCount)
    }
}
