package app.panaru.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val baseUrl: String,
    val isDefault: Boolean,
    val keyCipher: ByteArray,
    val keyIv: ByteArray,
    val createdAt: Long,
) {
    override fun equals(other: Any?) = other is ServerEntity && other.id == id && other.name == name &&
        other.baseUrl == baseUrl && other.isDefault == isDefault && other.keyCipher.contentEquals(keyCipher)
    override fun hashCode() = id.hashCode()
}

enum class UploadState { QUEUED, UPLOADING, COMPLETED, FAILED, CANCELLED }

/** Categoria dell'errore, tradotta in un messaggio comprensibile dalla UI. */
enum class ErrorKind { UNAUTHORIZED, NOT_FOUND, TOO_LARGE, RATE_LIMITED, SERVER, NETWORK, TLS, SOURCE, PROTOCOL, INTERRUPTED, NO_SERVER }

@Entity(tableName = "uploads", indices = [Index("state"), Index("createdAt"), Index("serverId")])
data class UploadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: Long,
    /** Copia del nome del server: la cronologia resta leggibile anche se il server viene rimosso. */
    val serverName: String,
    val sourceUri: String,
    val fileName: String,
    val sizeBytes: Long,
    val mimeType: String?,
    val expiryDays: Int,
    val allowedDownloads: Int,
    /** La password non viene mai salvata: solo il fatto che sia stata impostata. */
    val hasPassword: Boolean,
    val state: UploadState,
    val bytesSent: Long = 0,
    val errorKind: ErrorKind? = null,
    val errorDetail: String? = null,
    val fileId: String? = null,
    val url: String? = null,
    val hotlinkUrl: String? = null,
    val expiresAt: Long? = null,
    val unlimitedTime: Boolean = false,
    val unlimitedDownloads: Boolean = false,
    val downloadsRemaining: Int? = null,
    val removedFromServer: Boolean = false,
    /** Rimosso dalla cronologia dall'utente: resta nel database per non essere riaggiunto dalla sincronizzazione. */
    val hidden: Boolean = false,
    val createdAt: Long,
    val completedAt: Long? = null,
) {
    val isActive: Boolean get() = state == UploadState.QUEUED || state == UploadState.UPLOADING
    /** Voce importata dal server: caricata da un altro dispositivo o dall'interfaccia web di Gokapi. */
    val fromOtherDevice: Boolean get() = sourceUri.isEmpty()
    fun isExpired(nowSec: Long): Boolean =
        removedFromServer || (!unlimitedTime && expiresAt != null && expiresAt in 1..nowSec)
}

class Converters {
    @TypeConverter fun stateToString(s: UploadState) = s.name
    @TypeConverter fun stringToState(s: String) = UploadState.valueOf(s)
    @TypeConverter fun kindToString(k: ErrorKind?) = k?.name
    @TypeConverter fun stringToKind(s: String?) = s?.let { runCatching { ErrorKind.valueOf(it) }.getOrNull() }
}

@Dao
interface ServerDao {
    @Query("SELECT * FROM servers ORDER BY isDefault DESC, name COLLATE NOCASE")
    fun observeAll(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers ORDER BY isDefault DESC, name COLLATE NOCASE")
    suspend fun all(): List<ServerEntity>

    @Query("SELECT * FROM servers WHERE id = :id")
    suspend fun byId(id: Long): ServerEntity?

    @Query("SELECT * FROM servers WHERE isDefault = 1 LIMIT 1")
    suspend fun defaultServer(): ServerEntity?

    @Insert suspend fun insert(server: ServerEntity): Long
    @Update suspend fun update(server: ServerEntity)

    @Query("DELETE FROM servers WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE servers SET isDefault = (id = :id)")
    suspend fun setDefault(id: Long)

    @Query("SELECT COUNT(*) FROM servers")
    suspend fun count(): Int

    @Transaction
    suspend fun insertAsDefaultIfFirst(server: ServerEntity): Long {
        val first = count() == 0
        val id = insert(server.copy(isDefault = first || server.isDefault))
        if (server.isDefault && !first) setDefault(id)
        return id
    }
}

@Dao
interface UploadDao {
    @Query("SELECT * FROM uploads WHERE hidden = 0 ORDER BY createdAt DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int = 500): Flow<List<UploadEntity>>

    @Query("SELECT * FROM uploads WHERE state IN ('QUEUED','UPLOADING') ORDER BY createdAt, id")
    fun observeActive(): Flow<List<UploadEntity>>

    @Query("SELECT * FROM uploads WHERE id = :id")
    suspend fun byId(id: Long): UploadEntity?

    @Query("SELECT * FROM uploads WHERE id IN (:ids)")
    fun observeByIds(ids: List<Long>): Flow<List<UploadEntity>>

    @Query("SELECT * FROM uploads WHERE state = 'QUEUED' ORDER BY createdAt, id LIMIT 1")
    suspend fun nextQueued(): UploadEntity?

    /** Tutte le voci (anche nascoste) che corrispondono a un file sul server. */
    @Query("SELECT * FROM uploads WHERE serverId = :serverId AND fileId IS NOT NULL")
    suspend fun withFileIdOn(serverId: Long): List<UploadEntity>

    @Insert suspend fun insertAll(items: List<UploadEntity>): List<Long>
    @Update suspend fun update(item: UploadEntity)

    @Query("UPDATE uploads SET state = :state WHERE id = :id AND state = :expected")
    suspend fun transition(id: Long, expected: UploadState, state: UploadState): Int

    @Query("UPDATE uploads SET state = 'FAILED', errorKind = 'INTERRUPTED' WHERE state = 'UPLOADING'")
    suspend fun markInterrupted(): Int

    @Query("UPDATE uploads SET hidden = 1 WHERE id = :id AND fileId IS NOT NULL AND state = 'COMPLETED'")
    suspend fun hide(id: Long)

    @Query("DELETE FROM uploads WHERE id = :id AND state NOT IN ('QUEUED','UPLOADING') AND (fileId IS NULL OR state != 'COMPLETED')")
    suspend fun deleteUnsynced(id: Long)

    @Query("UPDATE uploads SET hidden = 1 WHERE state = 'COMPLETED' AND fileId IS NOT NULL")
    suspend fun hideAllCompleted()

    @Query("DELETE FROM uploads WHERE state NOT IN ('QUEUED','UPLOADING') AND (fileId IS NULL OR state != 'COMPLETED')")
    suspend fun deleteAllUnsynced()

    /** Rimuove dalla cronologia: i file ancora sincronizzabili vengono nascosti, il resto eliminato. */
    @Transaction
    suspend fun delete(id: Long) {
        hide(id)
        deleteUnsynced(id)
    }

    @Transaction
    suspend fun clearHistory() {
        hideAllCompleted()
        deleteAllUnsynced()
    }
}

@Database(entities = [ServerEntity::class, UploadEntity::class], version = 2, exportSchema = true)
@TypeConverters(Converters::class)
abstract class PanaruDatabase : RoomDatabase() {
    abstract fun servers(): ServerDao
    abstract fun uploads(): UploadDao

    companion object {
        /** v2: colonna `hidden` per la sincronizzazione con il server. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE uploads ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun create(context: Context): PanaruDatabase =
            Room.databaseBuilder(context, PanaruDatabase::class.java, "panaru.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
