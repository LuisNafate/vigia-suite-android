package com.vigiasuite.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Casos de app/src/test/resources/auth-change-cases.json, copia idéntica de vigia-suite-web/tests/fixtures/: la versión
 * Web los verifica con authProtectionChange() de history-core.js. Cubre la lógica, la persistencia en Room y el texto
 * que muestra la comparación del historial.
 */
@RunWith(RobolectricTestRunner::class)
class AuthChangeEquivalenceTest {
    private val fixture: JSONObject by lazy { JSONObject(javaClass.classLoader!!.getResource("auth-change-cases.json")!!.readText()) }
    private val codes = mapOf('W' to AuthProtection.WEAKER, 'S' to AuthProtection.STRONGER, '=' to AuthProtection.EQUIVALENT, '?' to AuthProtection.UNKNOWN)
    private val opposite = mapOf(
        AuthProtection.WEAKER to AuthProtection.STRONGER, AuthProtection.STRONGER to AuthProtection.WEAKER,
        AuthProtection.EQUIVALENT to AuthProtection.EQUIVALENT, AuthProtection.UNKNOWN to AuthProtection.UNKNOWN,
    )

    private fun ap(bssid: String, auth: Int) = WifiNetwork("Casa", -60, 6, auth, bssid)
    private fun detail(id: String, createdAt: Long, wifi: List<WifiNetwork>) =
        HistoryLogic.buildAssessment(id, createdAt, HistoryStatus.COMPLETE, SensorIdentity(), 10, wifi, emptyList(), false, false, false)
    private fun JSONObject.mode(key: String): Int? = if (isNull(key)) null else getInt(key)

    @Test
    fun everySharedCaseMatchesTheWebClassification() {
        val cases = fixture.getJSONArray("cases")
        assertTrue("faltan casos compartidos", cases.length() >= 30)
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val before = case.mode("before")
            val after = case.mode("after")
            val expected = case.getString("expected")
            assertEquals("$before → $after: ${case.getString("why")}", expected, HistoryLogic.authProtectionChange(before, after).code)
            assertEquals(expected == "weaker", HistoryLogic.isAuthDowngrade(before, after))
        }
    }

    @Test
    fun sharedMatrixMatchesForEveryPairOfModes0To16() {
        val rows = fixture.getJSONObject("matrix").getJSONArray("rows")
        assertEquals(17, rows.length())
        for (before in 0..16) {
            val row = rows.getString(before)
            assertEquals(17, row.length)
            row.forEachIndexed { after, code -> assertEquals("$before → $after", codes.getValue(code), HistoryLogic.authProtectionChange(before, after)) }
        }
    }

    @Test
    fun classificationIsConsistentBothWaysAndDoesNotFollowTheModeNumber() {
        for (before in 0..16) for (after in 0..16) {
            assertEquals("$before ↔ $after", opposite.getValue(HistoryLogic.authProtectionChange(before, after)), HistoryLogic.authProtectionChange(after, before))
        }
        assertEquals(AuthProtection.WEAKER, HistoryLogic.authProtectionChange(5, 16))
        listOf(-1, 17, 255, null).forEach {
            assertEquals(AuthProtection.UNKNOWN, HistoryLogic.authProtectionChange(it, 0))
            assertEquals(AuthProtection.UNKNOWN, HistoryLogic.authProtectionChange(3, it))
        }
    }

    @Test
    fun comparisonSeparatesWeakerStrongerAndUnratedChangesLikeWeb() {
        val before = detail("a", 1000, listOf(ap("AA:AA:AA:AA:AA:01", 5), ap("AA:AA:AA:AA:AA:02", 3), ap("AA:AA:AA:AA:AA:03", 3), ap("AA:AA:AA:AA:AA:04", 8), ap("AA:AA:AA:AA:AA:05", 6)))
        val after = detail("b", 2000, listOf(ap("AA:AA:AA:AA:AA:01", 16), ap("AA:AA:AA:AA:AA:02", 5), ap("AA:AA:AA:AA:AA:03", 6), ap("AA:AA:AA:AA:AA:04", 3), ap("AA:AA:AA:AA:AA:05", 11)))
        val result = HistoryLogic.compare(before, after)
        assertEquals(
            listOf("01" to "weaker", "02" to "unknown", "03" to "stronger", "04" to "unknown", "05" to "equivalent"),
            result.wifi.authChanged.map { it.bssid.takeLast(2) to it.protection.code },
        )
        val auth = HistoryLogic.findings(result).filter { "autenticación" in it.title || "protección" in it.title }.map { it.severity to it.title }
        assertEquals(
            listOf("high" to "1 red(es) redujeron su protección", "info" to "2 cambio(s) de autenticación sin pérdida de protección", "info" to "2 cambio(s) de autenticación sin calificar"),
            auth,
        )
        val onlyUnrated = HistoryLogic.findings(HistoryLogic.compare(detail("c", 1000, listOf(ap("AA:AA:AA:AA:AA:01", 14))), detail("d", 2000, listOf(ap("AA:AA:AA:AA:AA:01", 6)))))
        assertEquals(listOf("1 cambio(s) de autenticación sin calificar"), onlyUnrated.map { it.title })
    }

    @Test
    fun historyRowShowsTheModeNamesAndTheRating() {
        fun change(from: Int, to: Int) = HistoryLogic.compare(detail("a", 1, listOf(ap("AA:AA:AA:AA:AA:01", from))), detail("b", 2, listOf(ap("AA:AA:AA:AA:AA:01", to)))).wifi.authChanged.single()
        assertEquals("AA:AA:AA:AA:AA:01 · protección más débil", authChangeDetail(change(5, 16)))
        assertEquals("WPA2 Enterprise → WPA Enterprise", authChangeValue(change(5, 16)))
        assertEquals("AA:AA:AA:AA:AA:01 · cambio sin calificar", authChangeDetail(change(14, 6)))
        assertEquals("WPA3 Enterprise → WPA3-PSK", authChangeValue(change(14, 6)))
        assertEquals("AA:AA:AA:AA:AA:01", authChangeDetail(change(6, 11)))
        assertEquals("WPA2-PSK → OWE", authChangeValue(change(3, 9)))
    }

    @Test
    fun storedEvaluationsWithModes9To16KeepTheirValuesAndCompareTheSame() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java).allowMainThreadQueries().build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = HistoryRepository(context, database, scope)
            val modes = (9..16).toList()
            val older = detail("old", 1000, modes.mapIndexed { index, mode -> ap("AA:AA:AA:AA:AA:%02X".format(index), mode) })
            // 9→3 más fuerte, 10→14 más débil, 11→6 equivalente, 12→3 más débil, 13→6 sin calificar, 14→15 más débil, 15→3 sin calificar, 16→5 más fuerte.
            val newer = detail("new", 2000, listOf(3, 14, 6, 3, 6, 15, 3, 5).mapIndexed { index, mode -> ap("AA:AA:AA:AA:AA:%02X".format(index), mode) })
            runBlocking {
                database.historyDao().insertDetail(older)
                database.historyDao().insertDetail(newer)
                assertEquals(modes, database.historyDao().detail("old")!!.wifi.map { it.auth })
                val result = repository.compare("new", "old")!!
                assertEquals(
                    listOf("stronger", "weaker", "equivalent", "weaker", "unknown", "weaker", "unknown", "stronger"),
                    result.wifi.authChanged.map { it.protection.code },
                )
                assertFalse("la puntuación guardada no se recalcula", result.before.score != 10 || result.after.score != 10)
            }
        } finally {
            scope.cancel()
            database.close()
        }
    }
}
