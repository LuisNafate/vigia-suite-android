package com.vigiasuite.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Migración v1 -> v2 con una base creada exactamente con el esquema exportado de la versión 1
 * (app/schemas/.../1.json) y abierta con HistoryDatabase.open(), igual que al actualizar la app.
 */
@RunWith(RobolectricTestRunner::class)
class HistoryMigrationTest {
    private lateinit var context: Context
    private var database: HistoryDatabase? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(HistoryDatabase.NAME)
    }

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(HistoryDatabase.NAME)
    }

    private fun createVersion1Database() {
        val schema = JSONObject(File("schemas/com.vigiasuite.app.HistoryDatabase/1.json").readText()).getJSONObject("database")
        assertEquals(1, schema.getInt("version"))
        val file = context.getDatabasePath(HistoryDatabase.NAME).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices")
                for (i in 0 until (indices?.length() ?: 0)) db.execSQL(indices!!.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (index in 0 until setup.length()) db.execSQL(setup.getString(index))
            // Una evaluación con observaciones (puntuada antes de la regla de congestión) y un resumen histórico.
            db.execSQL(
                "INSERT INTO assessments (id, createdAt, origin, source, status, sensorBoard, sensorChip, sensorMac, sensorFirmware, sensorProtocol, " +
                    "score, level, wifiCount, bleCount, wifiOpen, bleNearby, wifiTruncated, bleTruncated, bleFailed, hasObservations) VALUES " +
                    "('v1-live', 1000, 'android', 'live', 'complete', 'nodemcu-esp32s', 'ESP32', '04:B2:47:82:60:1C', '0.5.0-arduino', 2, 27, 'BAJO', 12, 0, 0, 0, 0, 0, 0, 1)," +
                    "('legacy-android-500-33', 500, 'android', 'legacy', 'legacy', NULL, NULL, NULL, NULL, NULL, 33, 'MEDIO', 7, 4, 2, 1, NULL, NULL, NULL, 0)",
            )
            for (seq in 0 until 12) {
                db.execSQL("INSERT INTO wifi_observations (assessmentId, seq, ssid, bssid, channel, rssi, auth) VALUES ('v1-live', $seq, 'Red$seq', 'AA:AA:AA:AA:AA:%02X', 6, -60, 3)".format(seq))
            }
            db.version = 1
        }
    }

    @Test
    fun version1HistoryIsKeptIntactAndItsFormulaStaysUnrecorded() = runBlocking {
        createVersion1Database()
        val opened = HistoryDatabase.open(context).also { database = it }
        val dao = opened.historyDao()

        val stored = dao.assessments().associateBy { it.id }
        assertEquals(setOf("v1-live", "legacy-android-500-33"), stored.keys)
        val live = stored.getValue("v1-live")
        assertEquals("la puntuación no se recalcula", 27, live.score)
        assertEquals("BAJO", live.level)
        assertEquals("04:B2:47:82:60:1C", live.sensorMac)
        assertNull("no se inventa una versión para registros antiguos", live.scoreFormula)
        assertNull(stored.getValue("legacy-android-500-33").scoreFormula)
        assertEquals(33, stored.getValue("legacy-android-500-33").score)
        val detail = dao.detail("v1-live")!!
        assertEquals(12, detail.wifi.size)
        assertTrue(detail.wifi.all { it.channel == 6 && it.auth == 3 })

        // Una evaluación nueva guarda su versión; al compararla con la de v1 (12 redes en un canal) se advierte.
        val now = HistoryLogic.buildAssessment("v2-live", 2000, HistoryStatus.COMPLETE, SensorIdentity(), 32, detail.wifi.map {
            WifiNetwork(it.ssid ?: "", it.rssi, it.channel, it.auth, it.bssid ?: "")
        }, emptyList(), false, false, false)
        dao.insertDetail(now)
        assertEquals(RiskScore.FORMULA_VERSION, dao.assessments(listOf("v2-live")).single().scoreFormula)
        val warnings = HistoryLogic.compare(detail, dao.detail("v2-live")!!).warnings
        assertTrue(warnings.any { "regla de congestión de Android" in it })
        assertFalse(HistoryLogic.compare(dao.detail("v2-live")!!, dao.detail("v2-live")!!).warnings.any { "regla de congestión de Android" in it })
    }
}
