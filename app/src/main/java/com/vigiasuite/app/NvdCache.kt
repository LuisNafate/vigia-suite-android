package com.vigiasuite.app

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert

/**
 * Caché local de respuestas de NVD. Es una base Room propia («vigia_nvd.db»): no comparte tablas ni versión con el
 * historial de evaluaciones. Sólo guarda respuestas públicas de NVD, la URL consultada y la fecha de consulta.
 */
@Entity(tableName = "nvd_responses")
data class NvdResponseEntity(
    @PrimaryKey val url: String,
    val body: String,
    val fetchedAt: Long,
)

@Dao
interface NvdCacheDao {
    @Query("SELECT * FROM nvd_responses WHERE url = :url")
    suspend fun get(url: String): NvdResponseEntity?

    @Upsert
    suspend fun put(entry: NvdResponseEntity)

    /** Conserva sólo las :max respuestas consultadas más recientemente. */
    @Query("DELETE FROM nvd_responses WHERE url NOT IN (SELECT url FROM nvd_responses ORDER BY fetchedAt DESC LIMIT :max)")
    suspend fun prune(max: Int)

    @Query("SELECT COUNT(*) FROM nvd_responses")
    suspend fun count(): Int

    @Query("DELETE FROM nvd_responses")
    suspend fun clear()
}

@Database(entities = [NvdResponseEntity::class], version = 1, exportSchema = true)
abstract class NvdCacheDatabase : RoomDatabase() {
    abstract fun cacheDao(): NvdCacheDao

    companion object {
        const val NAME = "vigia_nvd.db"
        fun open(context: Context): NvdCacheDatabase = Room.databaseBuilder(context.applicationContext, NvdCacheDatabase::class.java, NAME).build()
    }
}

/** NvdStore sobre Room. Un error de lectura (p. ej., una respuesta demasiado grande) se trata como «sin copia». */
class RoomNvdStore(private val dao: NvdCacheDao, private val maxEntries: Int = MAX_ENTRIES) : NvdStore {
    override suspend fun get(url: String): NvdCachedEntry? = runCatching { dao.get(url) }.getOrNull()?.let { NvdCachedEntry(it.url, it.body, it.fetchedAt) }
    override suspend fun put(entry: NvdCachedEntry) {
        dao.put(NvdResponseEntity(entry.url, entry.body, entry.fetchedAt))
        dao.prune(maxEntries)
    }

    companion object {
        const val MAX_ENTRIES = 60
    }
}
