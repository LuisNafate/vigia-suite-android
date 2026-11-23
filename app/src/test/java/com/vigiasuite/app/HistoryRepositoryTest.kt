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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HistoryRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: HistoryDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var repository: HistoryRepository
    private var now = 1_800_000_000_000L
    private val prefs get() = context.getSharedPreferences(HistoryRepository.PREFERENCES, Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs.edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        repository = HistoryRepository(context, database, scope) { now }
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
    }

    private fun legacyJson(vararg records: AnalysisRecord) = JSONArray().apply {
        records.forEach { put(JSONObject().put("createdAt", it.createdAt).put("score", it.score).put("level", it.level).put("wifiTotal", it.wifiTotal).put("wifiOpen", it.wifiOpen).put("bleTotal", it.bleTotal).put("bleNearby", it.bleNearby)) }
    }.toString()

    private val wifi = listOf(WifiNetwork("Casa", -50, 6, 3, "AA:AA:AA:AA:AA:01"), WifiNetwork("", -80, 11, 0, "AA:AA:AA:AA:AA:02"))
    private val ble = listOf(BleDevice("Reloj", -50, "11:22:33:44:55:66", "public", true, 76, "180d"))

    private suspend fun save(score: Int = 20, status: String = HistoryStatus.COMPLETE, w: List<WifiNetwork> = wifi, b: List<BleDevice> = ble): String {
        now += 1_000
        return repository.save(status, SensorIdentity(board = "nodemcu-esp32s", protocol = 2), score, w, b, false, false, status == HistoryStatus.PARTIAL)
    }

    @Test
    fun savesAndReadsAssessmentsWithTheirOwnObservations() = runBlocking {
        val first = save(20)
        val second = save(40, w = listOf(WifiNetwork("Otra", -60, 1, 4, "BB:BB:BB:BB:BB:01")), b = emptyList())
        val list = database.historyDao().assessments()
        assertEquals("la más reciente primero", listOf(second, first), list.map { it.id })
        val detail = repository.detail(first)!!
        assertEquals(listOf("AA:AA:AA:AA:AA:01", "AA:AA:AA:AA:AA:02"), detail.wifi.map { it.bssid })
        assertEquals("", detail.wifi[1].ssid)
        assertEquals("Reloj", detail.ble.single().name)
        assertEquals("nodemcu-esp32s", detail.assessment.sensorBoard)
        assertNull(detail.assessment.sensorFirmware)
        val other = repository.detail(second)!!
        assertEquals(listOf("BB:BB:BB:BB:BB:01"), other.wifi.map { it.bssid })
        assertTrue(other.ble.isEmpty())
        assertNull(repository.detail("no-existe"))
    }

    @Test
    fun duplicateIdFailsWithoutLeavingOrphanObservations() = runBlocking {
        val id = save()
        val dao = database.historyDao()
        val existing = repository.detail(id)!!
        try {
            dao.insertDetail(existing.copy(wifi = existing.wifi.map { it.copy(seq = it.seq + 10) }))
            fail("un identificador repetido debe rechazarse")
        } catch (_: Exception) { }
        assertEquals("la transacción fallida no deja filas", 2, dao.wifiCount())
    }

    @Test
    fun deletesOneAssessmentOrEverything() = runBlocking {
        val first = save()
        val second = save()
        repository.delete(listOf(first))
        val dao = database.historyDao()
        assertEquals(listOf(second), dao.assessments().map { it.id })
        assertEquals(2, dao.wifiCount())
        assertEquals(1, dao.bleCount())
        repository.deleteAll()
        assertTrue(dao.assessments().isEmpty())
        assertEquals(0, dao.wifiCount())
        assertEquals(0, dao.bleCount())
    }

    @Test
    fun migratesSharedPreferencesWithoutDuplicatesAndKeepsTheOriginal() = runBlocking {
        val records = arrayOf(AnalysisRecord(1_700_000_000_000, 33, "MEDIO", 7, 2, 4, 1), AnalysisRecord(1_700_000_100_000, 5, "BAJO", 2, 0, 0, 0))
        val original = legacyJson(*records)
        prefs.edit().putString(HistoryRepository.LEGACY_KEY, original).commit()
        val first = repository.migrateLegacy()
        assertEquals(MigrationReport(2, 2, 0, emptyList()), first)
        val second = repository.migrateLegacy()
        assertEquals("repetir la migración no duplica", MigrationReport(2, 0, 2, emptyList()), second)
        val stored = database.historyDao().assessments()
        assertEquals(2, stored.size)
        assertTrue(stored.all { it.status == HistoryStatus.LEGACY && !it.hasObservations })
        assertEquals(setOf(7, 2), stored.map { it.wifiCount }.toSet())
        assertEquals("el original se conserva", original, prefs.getString(HistoryRepository.LEGACY_KEY, null))
        assertEquals(0, database.historyDao().wifiCount())
    }

    @Test
    fun migrationKeepsDamagedRecordsAndReportsThem() = runBlocking {
        val json = JSONArray(legacyJson(AnalysisRecord(1_700_000_000_000, 33, "MEDIO", 7, 2, 4, 1))).put("texto").put(JSONObject().put("score", 5)).toString()
        prefs.edit().putString(HistoryRepository.LEGACY_KEY, json).commit()
        val report = repository.migrateLegacy()
        assertEquals(1, report.imported)
        assertEquals(2, report.rejected.size)
        assertEquals(json, prefs.getString(HistoryRepository.LEGACY_KEY, null))
    }

    @Test
    fun migrationFailureLeavesTheOriginalIntact() = runBlocking {
        prefs.edit().putString(HistoryRepository.LEGACY_KEY, "{esto no es JSON").commit()
        val report = repository.migrateLegacy()
        assertNotNull(report.error)
        assertEquals(0, report.imported)
        assertEquals("{esto no es JSON", prefs.getString(HistoryRepository.LEGACY_KEY, null))
        assertTrue(database.historyDao().assessments().isEmpty())
    }

    @Test
    fun deletingLegacyAssessmentsAlsoRemovesThemFromTheBackup() = runBlocking {
        val kept = AnalysisRecord(1_700_000_000_000, 33, "MEDIO", 7, 2, 4, 1)
        val removed = AnalysisRecord(1_700_000_100_000, 5, "BAJO", 2, 0, 0, 0)
        prefs.edit().putString(HistoryRepository.LEGACY_KEY, legacyJson(kept, removed)).commit()
        repository.migrateLegacy()
        repository.delete(listOf(HistoryLogic.legacyId(removed)))
        assertEquals(1, JSONArray(prefs.getString(HistoryRepository.LEGACY_KEY, "[]")).length())
        assertEquals("no reaparece al migrar otra vez", 0, repository.migrateLegacy().imported)
        assertEquals(1, database.historyDao().assessments().size)
        repository.deleteAll()
        assertFalse(prefs.contains(HistoryRepository.LEGACY_KEY))
    }

    @Test
    fun retentionPolicyIsAppliedAndPersisted() = runBlocking {
        repeat(25) { save() }
        assertEquals(RetentionPolicy.DEFAULT, repository.retention())
        assertEquals(5, repository.expiredWith(RetentionPolicy(20, 0)).size)
        assertEquals(5, repository.updateRetention(RetentionPolicy(20, 0)))
        assertEquals(RetentionPolicy(20, 0), repository.retention())
        assertEquals(20, database.historyDao().assessments().size)
        save()
        assertEquals("se aplica al guardar", 20, database.historyDao().assessments().size)
    }

    @Test
    fun startMigratesInTheBackgroundAndPublishesTheList() = runBlocking {
        prefs.edit().putString(HistoryRepository.LEGACY_KEY, legacyJson(AnalysisRecord(1_700_000_000_000, 33, "MEDIO", 7, 2, 4, 1))).commit()
        repository.start().join()
        withTimeout(5_000) { while (repository.assessments.value.isEmpty()) delay(20) }
        assertEquals(1, repository.assessments.value.size)
        assertTrue(repository.message.value!!.contains("1 resumen(es) anterior(es) migrado(s)"))
    }

    @Test
    fun formulaVersionIsStoredWithEachEvaluationAndSurvivesLosingPreferences() = runBlocking {
        prefs.edit().putString(HistoryRepository.LEGACY_KEY, legacyJson(AnalysisRecord(1_700_000_000_000, 33, "MEDIO", 7, 2, 4, 1))).commit()
        repository.migrateLegacy()
        val congested = (1..12).map { WifiNetwork("Red$it", -60, 6, 3, "AA:AA:AA:AA:AA:%02X".format(it)) }
        val first = save(score = 5, w = congested, b = emptyList())
        val second = save(score = 5, w = congested, b = emptyList())
        val stored = database.historyDao().assessments().associateBy { it.id }
        assertEquals(RiskScore.FORMULA_VERSION, stored.getValue(first).scoreFormula)
        assertNull("el resumen migrado no inventa versión", stored.values.single { it.source == HistorySource.LEGACY }.scoreFormula)

        // Se borran todas las preferencias (y con ellas el respaldo anterior) pero la base de datos sigue.
        prefs.edit().clear().commit()
        now -= 10L * 365 * 24 * 60 * 60 * 1000 // además, el reloj del teléfono retrocede diez años
        val reopened = HistoryRepository(context, database, scope) { now }
        assertFalse(reopened.compare(first, second)!!.warnings.any { "regla de congestión de Android" in it })
        val third = reopened.save(HistoryStatus.COMPLETE, SensorIdentity(), 5, congested, emptyList(), false, false, false)
        assertFalse("una evaluación nueva con fecha anterior no se confunde con la fórmula previa",
            reopened.compare(first, third)!!.warnings.any { "regla de congestión de Android" in it })
        assertEquals(listOf(5, 5, 5), listOf(first, second, third).map { database.historyDao().assessments(listOf(it)).single().score })
    }
}
