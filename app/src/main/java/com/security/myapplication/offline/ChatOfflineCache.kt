package com.security.myapplication.offline

import android.content.Context
import com.google.gson.Gson
import com.security.myapplication.models.ChatMessage
import com.security.myapplication.models.PersonalMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Durable chat history cache.  It retains confirmed messages on disk while an
 * open chat observes only a bounded recent window.  TransferDatabase remains
 * the single source for pending outbox work.
 */
object ChatOfflineCache {
    /** Only this many newest messages reach an open composable at once. */
    private const val RECENT_UI_LIMIT = 200
    private val gson = Gson()
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cacheLifecycleMutex = Mutex()
    private val userGenerations = ConcurrentHashMap<Int, AtomicLong>()
    private val conversationGenerations = ConcurrentHashMap<String, AtomicLong>()

    private fun personalKey(currentUserId: Int, otherUserId: Int) = "p_${currentUserId}_$otherUserId"
    private fun groupKey(currentUserId: Int, groupId: Int) = "g_${currentUserId}_$groupId"

    suspend fun loadPersonal(context: Context, currentUserId: Int, otherUserId: Int): List<PersonalMessage> =
        observePersonal(context, currentUserId, otherUserId).first()

    suspend fun loadGroup(context: Context, currentUserId: Int, groupId: Int): List<ChatMessage> =
        observeGroup(context, currentUserId, groupId).first()

    fun observePersonal(context: Context, currentUserId: Int, otherUserId: Int): Flow<List<PersonalMessage>> =
        MessageDatabase.getInstance(context).messageDao()
            .observeRecentPayloads(personalKey(currentUserId, otherUserId), RECENT_UI_LIMIT)
            .map { decodePersonal(it).sortedBy { message -> message.id } }

    fun observeGroup(context: Context, currentUserId: Int, groupId: Int): Flow<List<ChatMessage>> =
        MessageDatabase.getInstance(context).messageDao()
            .observeRecentPayloads(groupKey(currentUserId, groupId), RECENT_UI_LIMIT)
            .map { decodeGroup(it).sortedBy { message -> message.id } }

    suspend fun loadOlderPersonal(
        context: Context,
        currentUserId: Int,
        otherUserId: Int,
        beforeMessageId: Int,
        limit: Int
    ): List<PersonalMessage> = decodePersonal(
        MessageDatabase.getInstance(context).messageDao()
            .readOlderPayloads(personalKey(currentUserId, otherUserId), beforeMessageId, limit)
    ).sortedBy { it.id }

    suspend fun loadOlderGroup(
        context: Context,
        currentUserId: Int,
        groupId: Int,
        beforeMessageId: Int,
        limit: Int
    ): List<ChatMessage> = decodeGroup(
        MessageDatabase.getInstance(context).messageDao()
            .readOlderPayloads(groupKey(currentUserId, groupId), beforeMessageId, limit)
    ).sortedBy { it.id }

    fun savePersonal(context: Context, currentUserId: Int, otherUserId: Int, messages: List<PersonalMessage>) {
        val key = personalKey(currentUserId, otherUserId)
        persist(context, key, currentUserId, generationOf(currentUserId), nextConversationGeneration(key)) {
            val snapshot = messages.filter { it.id > 0 }
            val now = System.currentTimeMillis()
            MessageDatabase.getInstance(context).messageDao().mergeConversation(
                snapshot.map { CachedMessageEntity(key, it.id, gson.toJson(it), now) }
            )
        }
    }

    fun saveGroup(context: Context, currentUserId: Int, groupId: Int, messages: List<ChatMessage>) {
        val key = groupKey(currentUserId, groupId)
        persist(context, key, currentUserId, generationOf(currentUserId), nextConversationGeneration(key)) {
            val snapshot = messages.filter { it.id > 0 }
                .map { it.copy(sender_profile_pic = null) }
            val now = System.currentTimeMillis()
            MessageDatabase.getInstance(context).messageDao().mergeConversation(
                snapshot.map { CachedMessageEntity(key, it.id, gson.toJson(it), now) }
            )
        }
    }

    /** Logout removes this account-scoped chat cache asynchronously. */
    fun clearForUser(context: Context, userId: Int) {
        if (userId <= 0) return
        // Invalidates any write queued by a closing chat composable before the
        // database deletion acquired its per-key mutex.
        userGenerations.getOrPut(userId) { AtomicLong(0) }.incrementAndGet()
        writeScope.launch {
            cacheLifecycleMutex.withLock {
                try {
                    MessageDatabase.getInstance(context).messageDao().deleteByConversationPrefix("p_${userId}_")
                    MessageDatabase.getInstance(context).messageDao().deleteByConversationPrefix("g_${userId}_")
                } catch (_: Exception) {
                    // Logout is still allowed if local storage is unavailable.
                }
            }
        }
    }

    /** Clears exactly one locally cached conversation, never another chat. */
    fun clearPersonal(context: Context, currentUserId: Int, otherUserId: Int) =
        clearConversation(context, currentUserId, personalKey(currentUserId, otherUserId))

    /** Clears exactly one locally cached group conversation, never another group. */
    fun clearGroup(context: Context, currentUserId: Int, groupId: Int) =
        clearConversation(context, currentUserId, groupKey(currentUserId, groupId))

    private fun clearConversation(context: Context, userId: Int, key: String) {
        if (userId <= 0) return
        // A scheduled writer from before Clear must not resurrect old messages.
        conversationGenerations.getOrPut(key) { AtomicLong(0) }.incrementAndGet()
        writeScope.launch {
            cacheLifecycleMutex.withLock {
                runCatching { MessageDatabase.getInstance(context).messageDao().deleteConversation(key) }
            }
        }
    }

    private fun generationOf(userId: Int): Long =
        userGenerations.getOrPut(userId) { AtomicLong(0) }.get()

    private fun nextConversationGeneration(key: String): Long =
        conversationGenerations.getOrPut(key) { AtomicLong(0) }.incrementAndGet()

    private fun isCurrentConversationGeneration(key: String, generation: Long): Boolean =
        conversationGenerations[key]?.get() == generation

    private fun persist(
        context: Context,
        key: String,
        userId: Int,
        userGeneration: Long,
        conversationGeneration: Long,
        write: suspend () -> Unit
    ) {
        writeScope.launch {
            cacheLifecycleMutex.withLock {
                if (generationOf(userId) != userGeneration ||
                    !isCurrentConversationGeneration(key, conversationGeneration)
                ) return@withLock
                write()
            }
        }
    }

    private suspend fun decodePersonal(payloads: List<String>): List<PersonalMessage> =
        withContext(Dispatchers.Default) { payloads.mapNotNull { runCatching { gson.fromJson(it, PersonalMessage::class.java) }.getOrNull() } }

    private suspend fun decodeGroup(payloads: List<String>): List<ChatMessage> =
        withContext(Dispatchers.Default) { payloads.mapNotNull { runCatching { gson.fromJson(it, ChatMessage::class.java) }.getOrNull() } }
}
