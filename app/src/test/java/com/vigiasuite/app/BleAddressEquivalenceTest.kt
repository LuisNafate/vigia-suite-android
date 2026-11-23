package com.vigiasuite.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Casos de app/src/test/resources/ble-address-cases.json, copia idéntica de vigia-suite-web/tests/fixtures/: la versión
 * Web los verifica con ble-address.js y formulaWarning() de history-core.js. La puntuación de las mismas observaciones
 * se comprueba con scoring-cases.json en RiskScoreEquivalenceTest.
 */
@RunWith(RobolectricTestRunner::class)
class BleAddressEquivalenceTest {
    private val fixture: JSONObject by lazy { JSONObject(javaClass.classLoader!!.getResource("ble-address-cases.json")!!.readText()) }
    private fun JSONObject.text(key: String): String? = opt(key) as? String

    private fun devices(array: JSONArray) = (0 until array.length()).map { index ->
        val item = array.getJSONObject(index)
        BleDevice("", item.getInt("rssi"), item.getString("address"), item.getString("address_type"), item.getBoolean("connectable"), -1, "")
    }

    private fun build(side: JSONObject, id: String, createdAt: Long): AssessmentDetail = if (side.optBoolean("legacy")) {
        val nearby = side.getInt("bleNearby")
        AssessmentDetail(HistoryLogic.legacyAssessment(AnalysisRecord(createdAt, 10, "BAJO", 0, 0, nearby, nearby)).copy(id = id), emptyList(), emptyList())
    } else {
        val version = if (side.isNull("scoreFormula")) null else side.getInt("scoreFormula")
        HistoryLogic.buildAssessment(id, createdAt, HistoryStatus.COMPLETE, SensorIdentity(), 10, emptyList(), devices(side.getJSONArray("ble")), false, false, false, version)
    }

    @Test
    fun everySharedAddressClassifiesLikeWeb() {
        val cases = fixture.getJSONArray("classification")
        assertTrue(cases.length() >= 20)
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            assertEquals(case.getString("why"), case.getString("expected"), BleAddress.classify(case.text("addressType"), case.text("address")).code)
        }
        assertEquals(fixture.getJSONObject("classes").keys().asSequence().toList(), BleAddress.Kind.entries.map { it.code })
    }

    @Test
    fun onlyPublicAndStaticAreStableAndUnknownIsNotPrivate() {
        assertEquals(listOf(BleAddress.Kind.PUBLIC, BleAddress.Kind.STATIC), BleAddress.Kind.entries.filter(BleAddress::isStable))
        assertEquals(listOf(BleAddress.Kind.RESOLVABLE, BleAddress.Kind.NON_RESOLVABLE, BleAddress.Kind.RESOLVED_IDENTITY), BleAddress.Kind.entries.filter(BleAddress::isPrivate))
        assertFalse(BleAddress.isPrivate(BleAddress.Kind.UNKNOWN))
    }

    @Test
    fun everySharedFormulaComparisonWarnsLikeWeb() {
        val cases = fixture.getJSONArray("formulaComparisons")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val name = case.getString("name")
            val expected = case.getJSONObject("expected")
            val before = build(case.getJSONObject("before"), "a", 1000)
            val after = build(case.getJSONObject("after"), "b", 2000)
            val warning = HistoryLogic.formulaWarning(before, after)
            assertEquals(name, expected.getString("kind"), warning?.kind ?: "none")
            assertEquals(name, if (expected.isNull("delta")) null else expected.getInt("delta"), if (warning == null) 0 else warning.delta)
            val texts = HistoryLogic.compare(after, before).warnings.filter { "fórmula" in it }
            assertEquals(name, if (warning == null) 0 else 1, texts.size)
            if (warning?.kind == "possible") assertFalse(name, "comprobado con" in texts.single())
            if (warning?.kind == "confirmed") assertTrue(name, "comprobado con sus observaciones guardadas" in texts.single())
            assertEquals("la comparación no recalcula puntuaciones", listOf(10, 10), listOf(before.assessment.score, after.assessment.score))
        }
    }

    @Test
    fun labelsUseTheSubtypeAndDoNotClaimAStaticAddressNeverChanges() {
        assertEquals("pública", bleAddressLabel("public", "88:8e:68:12:34:56"))
        assertEquals("aleatoria estática", bleAddressLabel("random", "c4:12:34:56:78:9a"))
        assertEquals("privada resoluble", bleAddressLabel("random", "7a:12:34:56:78:9a"))
        assertEquals("privada no resoluble", bleAddressLabel("random", "3f:12:34:56:78:9a"))
        assertEquals("identidad resuelta", bleAddressLabel("public_identity", "88:8e:68:12:34:56"))
        assertEquals("no determinada", bleAddressLabel("random", "80:12:34:56:78:9a"))
        assertEquals("no determinada", bleAddressLabel(null, null))
        assertFalse(BleAddress.Kind.entries.any { "nunca" in it.label || "permanente" in it.label })
        assertEquals("Versión 3", formulaLabel(3))
        assertEquals("No registrada", formulaLabel(null))
    }

    @Test
    fun analysisUsesTheCorrectedClassificationAndMode16OnlyAddsAnInformativeNote() {
        val ble = listOf(
            BleDevice("", -50, "c4:12:34:56:78:9a", "random", true, -1, ""),
            BleDevice("", -50, "7a:12:34:56:78:9a", "random", true, -1, ""),
            BleDevice("", -50, "88:8e:68:12:34:56", "public_identity", true, -1, ""),
        )
        val wifi = listOf(WifiNetwork("Corp", -40, 1, 16, "AA:AA:AA:AA:AA:01"), WifiNetwork("Corp2", -40, 6, 16, "AA:AA:AA:AA:AA:02"))
        val result = analyze(SerialUiState(wifiNetworks = wifi, bleDevices = ble))
        assertEquals("sólo la aleatoria estática suma 2; el modo 16 no suma", 2, result.score)
        assertTrue(result.recommendations.any { "1 dispositivo(s) cercano(s) con dirección pública o aleatoria estática" in it })
        val note = result.recommendations.single { "WPA-Enterprise heredado" in it }
        assertTrue("2 red(es)" in note && "no conoce su cifrado" in note)
        assertFalse("TKIP" in note || "vulnerab" in note)
        assertEquals(12, analyze(SerialUiState(wifiNetworks = wifi + WifiNetwork("Viejo", -80, 11, 2, "AA:AA:AA:AA:AA:03"))).score)
    }

    @Test
    fun roomKeepsVersion3NewRowsAndUnrecordedOldRowsWithoutRecalculating() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(HistoryRepository.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        val database = Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java).allowMainThreadQueries().build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = HistoryRepository(context, database, scope, clock = { 3000L })
            val static = BleDevice("", -50, "c4:12:34:56:78:9a", "random", true, -1, "")
            runBlocking {
                // Una evaluación guardada por una compilación de la versión 2 (puntuada sin la estática) y otra sin versión.
                database.historyDao().insertDetail(HistoryLogic.buildAssessment("v2", 1000, HistoryStatus.COMPLETE, SensorIdentity(), 0, emptyList(), listOf(static), false, false, false, 2))
                database.historyDao().insertDetail(HistoryLogic.buildAssessment("old", 500, HistoryStatus.COMPLETE, SensorIdentity(), 0, emptyList(), listOf(static), false, false, false, null))
                val id = repository.save(HistoryStatus.COMPLETE, SensorIdentity(), RiskScore.compute(emptyList(), listOf(static)).score, emptyList(), listOf(static), false, false, false)
                val stored = database.historyDao().assessments().associateBy { it.id }
                assertEquals(3, stored.getValue(id).scoreFormula)
                assertEquals(2, stored.getValue(id).score)
                assertEquals(2, stored.getValue("v2").scoreFormula)
                assertNull("no se inventa una versión", stored.getValue("old").scoreFormula)
                assertEquals("las puntuaciones guardadas no cambian", listOf(0, 0), listOf(stored.getValue("v2").score, stored.getValue("old").score))
                val confirmed = repository.compare("v2", id)!!.warnings.single { "fórmula" in it }
                assertTrue(confirmed.startsWith("Una de las evaluaciones se puntuó con la fórmula 2") && "+2 punto(s)" in confirmed)
                val possible = repository.compare("old", id)!!.warnings.single { "fórmula" in it }
                assertTrue(possible.startsWith("Una de las evaluaciones no registra su versión de fórmula"))
                assertTrue("v2 y sin versión usan la misma regla BLE", repository.compare("old", "v2")!!.warnings.none { "fórmula" in it })
            }
        } finally {
            scope.cancel()
            database.close()
        }
    }
}
