package com.vigiasuite.app

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class MigrationReport(val found: Int, val imported: Int, val existing: Int, val rejected: List<String>, val error: String? = null)

/**
 * Historial local en Room. Sustituye al registro de resúmenes en SharedPreferences ("vigia_history"), que se conserva
 * como respaldo de sólo lectura: sus entradas se importan como evaluaciones históricas y sólo se retiran del respaldo
 * cuando el usuario las elimina del historial.
 */
class HistoryRepository(
    context: Context,
    private val database: HistoryDatabase,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.historyDao()
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val _message = MutableStateFlow<String?>(null)

    val assessments: StateFlow<List<AssessmentEntity>> = dao.observeAssessments().stateIn(scope, SharingStarted.Eagerly, emptyList())
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        // Primer inicio con la fórmula actual: lo guardado antes conserva su puntuación y la comparación lo advierte.
        // Se registra al crear el repositorio, antes de que pueda guardarse ninguna evaluación nueva.
        // apply() actualiza la copia en memoria al instante: las lecturas posteriores ya ven el valor.
        if (!preferences.contains(SCORING_REVISION_KEY)) preferences.edit().putLong(SCORING_REVISION_KEY, clock()).apply()
    }

    /** Desde cuándo las evaluaciones de este teléfono usan RiskScore.FORMULA_VERSION. */
    val scoringRevisionSince: Long get() = preferences.getLong(SCORING_REVISION_KEY, 0L)

    /** Migra el historial anterior y aplica la política de conservación. Se llama una vez al iniciar la app. */
    fun start() = scope.launch {
        val report = migrateLegacy()
        val removed = runCatching { applyRetention(retention()) }.getOrDefault(0)
        _message.value = listOfNotNull(
            report.error?.let { "La migración del historial anterior falló ($it). Los datos originales siguen intactos." },
            if (report.imported > 0) "${report.imported} resumen(es) anterior(es) migrado(s) como evaluaciones históricas." else null,
            if (report.rejected.isNotEmpty()) "${report.rejected.size} registro(s) con formato no válido se conservan sin migrar." else null,
            if (removed > 0) "La política de conservación retiró $removed evaluación(es) antigua(s)." else null,
        ).joinToString(" ").ifBlank { null }
    }

    fun launch(block: suspend HistoryRepository.() -> Unit) = scope.launch {
        try { block() } catch (error: Exception) { _message.value = "Error del historial: ${error.message}" }
    }

    /** Lee los resúmenes de la versión anterior con el mismo formato que usaba UsbSerialController. */
    fun readLegacyRecords(): List<AnalysisRecord> {
        val array = JSONArray(preferences.getString(LEGACY_KEY, "[]"))
        // Una entrada que no es un objeto se convierte en un registro inválido: se rechaza y se informa, sin perderse.
        return (0 until array.length()).map { index -> legacyRecord(array.optJSONObject(index)) }
    }

    private fun legacyRecord(item: JSONObject?): AnalysisRecord = if (item == null) AnalysisRecord(0, -1, "", 0, 0, 0, 0) else AnalysisRecord(
        item.optLong("createdAt"), item.optInt("score", -1), item.optString("level"),
        item.optInt("wifiTotal"), item.optInt("wifiOpen"), item.optInt("bleTotal"), item.optInt("bleNearby"),
    )

    /** Idempotente: los registros ya importados no se duplican. Si falla, el original queda intacto. */
    suspend fun migrateLegacy(): MigrationReport {
        if (!preferences.contains(LEGACY_KEY)) return MigrationReport(0, 0, 0, emptyList())
        return try {
            val legacy = readLegacyRecords()
            val plan = HistoryLogic.prepareLegacyMigration(legacy)
            val results = dao.insertIfAbsent(plan.records)
            val stored = dao.assessments(plan.records.map { it.id }).associateBy { it.id }
            val mismatched = plan.records.filter { expected ->
                val actual = stored[expected.id]
                actual == null || actual.createdAt != expected.createdAt || actual.score != expected.score || actual.level != expected.level ||
                    actual.wifiCount != expected.wifiCount || actual.bleCount != expected.bleCount || actual.source != expected.source
            }
            if (mismatched.isNotEmpty()) MigrationReport(legacy.size, 0, 0, plan.rejected, "la verificación no coincide en ${mismatched.size} registro(s)")
            else MigrationReport(legacy.size, results.count { it != -1L }, results.count { it == -1L }, plan.rejected)
        } catch (error: Exception) {
            MigrationReport(0, 0, 0, emptyList(), error.message ?: error.javaClass.simpleName)
        }
    }

    suspend fun save(
        status: String,
        sensor: SensorIdentity,
        score: Int,
        wifiNetworks: List<WifiNetwork>,
        bleDevices: List<BleDevice>,
        wifiTruncated: Boolean?,
        bleTruncated: Boolean?,
        bleFailed: Boolean?,
    ): String {
        val detail = HistoryLogic.buildAssessment(
            UUID.randomUUID().toString(), clock(), status, sensor, score, wifiNetworks, bleDevices, wifiTruncated, bleTruncated, bleFailed,
        )
        dao.insertDetail(detail)
        val removed = applyRetention(retention())
        _message.value = "Evaluación guardada con ${detail.wifi.size} redes y ${detail.ble.size} dispositivos BLE." +
            if (removed > 0) " La política de conservación retiró $removed evaluación(es) antigua(s)." else ""
        return detail.assessment.id
    }

    suspend fun detail(id: String): AssessmentDetail? = dao.detail(id)

    suspend fun compare(firstId: String, secondId: String): ComparisonResult? {
        val first = dao.detail(firstId) ?: return null
        val second = dao.detail(secondId) ?: return null
        return HistoryLogic.compare(first, second, scoringRevisionSince)
    }

    suspend fun delete(ids: List<String>) {
        dao.delete(ids)
        val legacyIds = ids.filter { it.startsWith("legacy-android-") }.toSet()
        if (legacyIds.isEmpty() || !preferences.contains(LEGACY_KEY)) return
        // Lo que el usuario elimina tampoco debe quedar en el respaldo de la versión anterior.
        val remaining = JSONArray()
        val original = JSONArray(preferences.getString(LEGACY_KEY, "[]"))
        for (index in 0 until original.length()) {
            val entry = original.opt(index)
            if (HistoryLogic.legacyId(legacyRecord(entry as? JSONObject)) !in legacyIds) remaining.put(entry)
        }
        preferences.edit().apply { if (remaining.length() == 0) remove(LEGACY_KEY) else putString(LEGACY_KEY, remaining.toString()) }.apply()
    }

    suspend fun deleteAll() {
        dao.deleteAll()
        preferences.edit().remove(LEGACY_KEY).apply()
        _message.value = "Se eliminó todo el historial de este teléfono."
    }

    fun retention(): RetentionPolicy = RetentionPolicy.normalized(
        preferences.getInt(RETENTION_COUNT_KEY, RetentionPolicy.DEFAULT.maxCount),
        preferences.getInt(RETENTION_AGE_KEY, RetentionPolicy.DEFAULT.maxAgeDays),
    )

    suspend fun expiredWith(policy: RetentionPolicy): List<String> = HistoryLogic.selectExpired(dao.assessments(), policy, clock())

    /** Aplica la política y devuelve cuántas evaluaciones eliminó. */
    suspend fun applyRetention(policy: RetentionPolicy): Int {
        val expired = expiredWith(policy)
        if (expired.isNotEmpty()) delete(expired)
        return expired.size
    }

    suspend fun updateRetention(policy: RetentionPolicy): Int {
        val removed = applyRetention(policy)
        preferences.edit().putInt(RETENTION_COUNT_KEY, policy.maxCount).putInt(RETENTION_AGE_KEY, policy.maxAgeDays).apply()
        _message.value = if (removed > 0) "Política aplicada: se eliminaron $removed evaluación(es)." else "Política guardada. No fue necesario eliminar evaluaciones."
        return removed
    }

    companion object {
        const val PREFERENCES = "vigia_history"
        const val LEGACY_KEY = "analysis_records_v1"
        const val RETENTION_COUNT_KEY = "retention_max_count"
        const val RETENTION_AGE_KEY = "retention_max_age_days"
        const val SCORING_REVISION_KEY = "scoring_formula_v${RiskScore.FORMULA_VERSION}_since"

        fun create(context: Context) = HistoryRepository(context, HistoryDatabase.open(context))
    }
}
