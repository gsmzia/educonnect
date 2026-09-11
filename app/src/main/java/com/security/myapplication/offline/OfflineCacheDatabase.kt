package com.security.myapplication.offline

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Small, account-scoped cache for screen payloads.
 *
 * It is intentionally separate from the transfer database: a feed-cache change
 * must never require a migration of resumable uploads/downloads, which are more
 * sensitive user data.
 */
@Entity(tableName = "offline_payloads", primaryKeys = ["scope", "cacheKey"])
data class OfflinePayload(
    val scope: String,
    val cacheKey: String,
    val payload: String,
    val updatedAt: Long
)

@Dao
interface OfflineCacheDao {
    @Query("SELECT payload FROM offline_payloads WHERE scope = :scope AND cacheKey = :cacheKey LIMIT 1")
    suspend fun read(scope: String, cacheKey: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(payload: OfflinePayload)

    @Query("DELETE FROM offline_payloads WHERE scope = :scope AND cacheKey = :cacheKey")
    suspend fun delete(scope: String, cacheKey: String)

    @Query("DELETE FROM offline_payloads WHERE scope = :scope AND cacheKey LIKE :keyPrefix || '%'")
    suspend fun deleteByPrefix(scope: String, keyPrefix: String)
}

@Database(entities = [OfflinePayload::class], version = 1, exportSchema = false)
abstract class OfflineCacheDatabase : RoomDatabase() {
    abstract fun offlineCacheDao(): OfflineCacheDao

    companion object {
        @Volatile
        private var instance: OfflineCacheDatabase? = null

        fun getInstance(context: Context): OfflineCacheDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    OfflineCacheDatabase::class.java,
                    "educonnect_offline_cache.db"
                ).build().also { instance = it }
            }
    }
}
