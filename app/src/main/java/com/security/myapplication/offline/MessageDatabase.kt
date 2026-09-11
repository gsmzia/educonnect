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
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** One durable row per server-confirmed message, isolated by account/conversation. */
@Entity(tableName = "cached_messages", primaryKeys = ["conversationKey", "messageId"])
data class CachedMessageEntity(
    val conversationKey: String,
    val messageId: Int,
    val payload: String,
    val updatedAt: Long
)

@Dao
interface MessageDao {
    @Query(
        "SELECT payload FROM cached_messages WHERE conversationKey = :conversationKey " +
            "ORDER BY messageId DESC LIMIT :limit"
    )
    fun observeRecentPayloads(conversationKey: String, limit: Int): Flow<List<String>>

    @Query(
        "SELECT payload FROM cached_messages WHERE conversationKey = :conversationKey " +
            "AND messageId < :beforeMessageId ORDER BY messageId DESC LIMIT :limit"
    )
    suspend fun readOlderPayloads(conversationKey: String, beforeMessageId: Int, limit: Int): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<CachedMessageEntity>)

    @Transaction
    suspend fun mergeConversation(messages: List<CachedMessageEntity>) {
        if (messages.isEmpty()) return
        upsertAll(messages)
    }

    @Query("DELETE FROM cached_messages WHERE conversationKey LIKE :prefix || '%'")
    suspend fun deleteByConversationPrefix(prefix: String)

    @Query("DELETE FROM cached_messages WHERE conversationKey = :conversationKey")
    suspend fun deleteConversation(conversationKey: String)
}

@Database(entities = [CachedMessageEntity::class], version = 1, exportSchema = false)
abstract class MessageDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile private var instance: MessageDatabase? = null

        fun getInstance(context: Context): MessageDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MessageDatabase::class.java,
                    "educonnect_messages.db"
                ).build().also { instance = it }
            }
    }
}
