package com.vigiasuite.app

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

// Esquema Room v1 del historial. Modelo lógico equivalente a IndexedDB en Vigía Suite Web.

@Entity(tableName = "assessments", indices = [Index("createdAt")])
data class AssessmentEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val origin: String,
    /** live = capturada con observaciones; legacy = resumen migrado desde SharedPreferences. */
    val source: String,
    /** complete, partial o legacy (ver HistoryStatus). */
    val status: String,
    val sensorBoard: String?,
    val sensorChip: String?,
    val sensorMac: String?,
    val sensorFirmware: String?,
    val sensorProtocol: Int?,
    val score: Int,
    val level: String,
    val wifiCount: Int?,
    val bleCount: Int?,
    val wifiOpen: Int?,
    val bleNearby: Int?,
    val wifiTruncated: Boolean?,
    val bleTruncated: Boolean?,
    val bleFailed: Boolean?,
    val hasObservations: Boolean,
)

@Entity(
    tableName = "wifi_observations",
    primaryKeys = ["assessmentId", "seq"],
    foreignKeys = [ForeignKey(entity = AssessmentEntity::class, parentColumns = ["id"], childColumns = ["assessmentId"], onDelete = ForeignKey.CASCADE)],
)
data class WifiObservationEntity(
    val assessmentId: String,
    val seq: Int,
    /** "" = SSID oculto; null = no informado. */
    val ssid: String?,
    val bssid: String?,
    val channel: Int?,
    val rssi: Int?,
    val auth: Int?,
)

@Entity(
    tableName = "ble_observations",
    primaryKeys = ["assessmentId", "seq"],
    foreignKeys = [ForeignKey(entity = AssessmentEntity::class, parentColumns = ["id"], childColumns = ["assessmentId"], onDelete = ForeignKey.CASCADE)],
)
data class BleObservationEntity(
    val assessmentId: String,
    val seq: Int,
    val name: String?,
    val address: String?,
    val addressType: String,
    val rssi: Int?,
    val connectable: Boolean?,
    val manufacturerId: Int?,
    val services: String?,
)

@Dao
abstract class HistoryDao {
    @Query("SELECT * FROM assessments ORDER BY createdAt DESC")
    abstract fun observeAssessments(): Flow<List<AssessmentEntity>>

    @Query("SELECT * FROM assessments ORDER BY createdAt DESC")
    abstract suspend fun assessments(): List<AssessmentEntity>

    @Query("SELECT * FROM assessments WHERE id = :id")
    abstract suspend fun assessment(id: String): AssessmentEntity?

    @Query("SELECT * FROM assessments WHERE id IN (:ids)")
    abstract suspend fun assessments(ids: List<String>): List<AssessmentEntity>

    @Query("SELECT * FROM wifi_observations WHERE assessmentId = :id ORDER BY seq")
    abstract suspend fun wifi(id: String): List<WifiObservationEntity>

    @Query("SELECT * FROM ble_observations WHERE assessmentId = :id ORDER BY seq")
    abstract suspend fun ble(id: String): List<BleObservationEntity>

    @Query("SELECT COUNT(*) FROM wifi_observations")
    abstract suspend fun wifiCount(): Int

    @Query("SELECT COUNT(*) FROM ble_observations")
    abstract suspend fun bleCount(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertAssessment(assessment: AssessmentEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertWifi(items: List<WifiObservationEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertBle(items: List<BleObservationEntity>)

    /** Devuelve -1 por cada registro que ya existía (no se sobrescribe). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIfAbsent(items: List<AssessmentEntity>): List<Long>

    @Query("DELETE FROM wifi_observations WHERE assessmentId IN (:ids)")
    protected abstract suspend fun deleteWifi(ids: List<String>)

    @Query("DELETE FROM ble_observations WHERE assessmentId IN (:ids)")
    protected abstract suspend fun deleteBle(ids: List<String>)

    @Query("DELETE FROM assessments WHERE id IN (:ids)")
    protected abstract suspend fun deleteAssessmentRows(ids: List<String>)

    @Query("DELETE FROM wifi_observations")
    protected abstract suspend fun clearWifi()

    @Query("DELETE FROM ble_observations")
    protected abstract suspend fun clearBle()

    @Query("DELETE FROM assessments")
    protected abstract suspend fun clearAssessments()

    /** La evaluación y sus observaciones se guardan juntas o no se guarda nada. */
    @Transaction
    open suspend fun insertDetail(detail: AssessmentDetail) {
        insertAssessment(detail.assessment)
        if (detail.wifi.isNotEmpty()) insertWifi(detail.wifi)
        if (detail.ble.isNotEmpty()) insertBle(detail.ble)
    }

    // Se borran explícitamente las observaciones además de la cascada, por si el dispositivo desactiva las claves foráneas.
    @Transaction
    open suspend fun delete(ids: List<String>) {
        if (ids.isEmpty()) return
        deleteWifi(ids); deleteBle(ids); deleteAssessmentRows(ids)
    }

    @Transaction
    open suspend fun deleteAll() {
        clearWifi(); clearBle(); clearAssessments()
    }

    @Transaction
    open suspend fun detail(id: String): AssessmentDetail? {
        val assessment = assessment(id) ?: return null
        return AssessmentDetail(assessment, wifi(id), ble(id))
    }
}

@Database(
    entities = [AssessmentEntity::class, WifiObservationEntity::class, BleObservationEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao

    companion object {
        const val NAME = "vigia_history.db"

        fun open(context: Context): HistoryDatabase =
            Room.databaseBuilder(context.applicationContext, HistoryDatabase::class.java, NAME).build()
    }
}
