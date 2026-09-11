package com.security.myapplication.posts

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.security.myapplication.models.*
import com.security.myapplication.network.ApiClient
import com.security.myapplication.offline.AppConnectivity
import com.security.myapplication.offline.OfflineFirstRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import android.provider.OpenableColumns
import java.io.IOException
import java.net.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import retrofit2.HttpException

/**
 * Repository & Manager for Posts / Announcements.
 * Real FastAPI backend sync with local disk cache and optimistic 0ms UI updates.
 */
object PostRepository {

    private const val TAG = "PostRepository"
    private const val PREFS_NAME = "educonnect_posts_prefs"
    private const val PENDING_MUTATIONS_PREFS = "educonnect_pending_post_mutations"
    private const val PENDING_MUTATIONS_KEY_PREFIX = "mutations_user_"
    private val gson = Gson()

    private val _postsFlow = MutableStateFlow<List<Post>>(emptyList())
    val postsFlow: StateFlow<List<Post>> = _postsFlow.asStateFlow()
    private val _isPostCacheReady = MutableStateFlow(false)
    val isPostCacheReady: StateFlow<Boolean> = _isPostCacheReady.asStateFlow()
    private const val POSTS_PAGE_SIZE = 20
    private var nextPostsCursorTimestamp: String? = null
    private var nextPostsCursorId: Int? = null
    private val postsPageGeneration = AtomicLong(0)
    private val pageLoadInProgress = AtomicBoolean(false)
    private val _hasMorePosts = MutableStateFlow(false)
    val hasMorePosts: StateFlow<Boolean> = _hasMorePosts.asStateFlow()
    private val _isLoadingMorePosts = MutableStateFlow(false)
    val isLoadingMorePosts: StateFlow<Boolean> = _isLoadingMorePosts.asStateFlow()
    private const val COMMENTS_PAGE_SIZE = 20
    private data class CommentCursor(val timestamp: String, val id: Int)
    /** Cursor state is deliberately per post and per active account. */
    private val nextCommentCursors = ConcurrentHashMap<String, CommentCursor>()
    private val commentPageLoadInProgress = ConcurrentHashMap<String, AtomicBoolean>()
    private val commentFetchGenerations = ConcurrentHashMap<String, AtomicLong>()
    private var commentPaginationUserId: Int = -1

    /** Track which userId is currently initialised so account-switching resets properly. */
    private var initializedForUserId: Int = -1
    private var currentUserRole: String? = null

    /**
     * Guard against duplicate parallel network calls:
     * if a refresh is already in-flight, any subsequent refreshPosts() call registers
     * its callback and returns immediately — no second GET /posts is fired.
     */
    private var refreshInProgress: Boolean = false
    private val pendingRefreshCallbacks = mutableListOf<((Boolean) -> Unit)>()

    /** Per-target debounce & serialisation jobs to absorb rapid multi-taps and guarantee single in-flight sync. */
    private val likeJobs = ConcurrentHashMap<String, Job>()
    private val saveJobs = ConcurrentHashMap<String, Job>()
    private val pollJobs = ConcurrentHashMap<String, Job>()
    private val commentLikeJobs = ConcurrentHashMap<String, Job>()
    private val autoRetryInProgress = AtomicBoolean(false)
    private val pendingMutationSyncInProgress = AtomicBoolean(false)
    private val pendingMutationRetryJobs = ConcurrentHashMap<Int, Job>()
    private val pendingMutationLock = Any()
    /** Map of optimistic comment ID ("c_...") to server-confirmed numeric ID to prevent broken reply threads. */
    private val optimisticCommentIdMap = ConcurrentHashMap<String, Int>()
    /** A reply waits for its optimistic parent to receive a real server ID. */
    private val pendingCommentServerIds = ConcurrentHashMap<String, CompletableDeferred<Int>>()

    /** An idempotent desired server state, retained until the server confirms it. */
    private data class PendingPostMutation(
        val kind: String, // like | save | comment_like
        val postId: String,
        val commentId: String? = null,
        val desired: Boolean
    )

    /** Per-user cache key — each account gets its own isolated slot. */
    private fun cacheKey(userId: Int) = "cached_posts_v2_user_$userId"

    private fun pendingMutationKey(userId: Int) = "$PENDING_MUTATIONS_KEY_PREFIX$userId"

    private fun pendingMutationPrefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PENDING_MUTATIONS_PREFS, Context.MODE_PRIVATE)

    private fun loadPendingMutations(context: Context, userId: Int): MutableList<PendingPostMutation> {
        val raw = pendingMutationPrefs(context).getString(pendingMutationKey(userId), null) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<List<PendingPostMutation>>() {}.type
            (gson.fromJson<List<PendingPostMutation>>(raw, type) ?: emptyList()).toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun savePendingMutations(context: Context, userId: Int, mutations: List<PendingPostMutation>) {
        pendingMutationPrefs(context).edit()
            .putString(pendingMutationKey(userId), gson.toJson(mutations))
            .apply()
    }

    private fun enqueuePendingMutation(context: Context, userId: Int, mutation: PendingPostMutation) {
        synchronized(pendingMutationLock) {
            val pending = loadPendingMutations(context, userId)
            pending.removeAll { it.kind == mutation.kind && it.postId == mutation.postId && it.commentId == mutation.commentId }
            pending += mutation
            savePendingMutations(context, userId, pending)
        }
    }

    private fun acknowledgePendingMutation(context: Context, userId: Int, mutation: PendingPostMutation): Boolean {
        return synchronized(pendingMutationLock) {
            val pending = loadPendingMutations(context, userId)
            val removed = pending.removeAll {
                it.kind == mutation.kind && it.postId == mutation.postId &&
                    it.commentId == mutation.commentId && it.desired == mutation.desired
            }
            if (removed) savePendingMutations(context, userId, pending)
            removed
        }
    }

    private fun isRetryableMutationError(error: Throwable): Boolean = when (error) {
        is SocketTimeoutException, is UnknownHostException, is InterruptedIOException -> true
        is HttpException -> error.code() == 429 || error.code() in 500..599
        is IOException -> true
        else -> false
    }

    /**
     * Replays durable desired states after a refresh or reconnect.  Every API
     * request is a set-to-state operation, so replay is safe and cannot turn a
     * failed "like" into an accidental "unlike".
     */
    private fun syncPendingPostMutations(context: Context, userId: Int) {
        if (userId <= 0 || !pendingMutationSyncInProgress.compareAndSet(false, true)) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                for (mutation in loadPendingMutations(context, userId)) {
                    try {
                        when (mutation.kind) {
                            "like" -> mutation.postId.toIntOrNull()?.let { id ->
                                val response = ApiClient.apiService.togglePostLike(id, LikeToggleRequest(userId, mutation.desired))
                                if (acknowledgePendingMutation(context, userId, mutation)) {
                                    applyServerPostLike(mutation.postId, userId, response.liked, response.likeCount)
                                    saveToDisk(context, _postsFlow.value)
                                }
                            }
                            "save" -> mutation.postId.toIntOrNull()?.let { id ->
                                val response = ApiClient.apiService.togglePostSave(id, SaveToggleRequest(userId, mutation.desired))
                                if (acknowledgePendingMutation(context, userId, mutation)) {
                                    applyServerPostSave(mutation.postId, userId, response.saved)
                                    saveToDisk(context, _postsFlow.value)
                                }
                            }
                            "comment_like" -> mutation.commentId?.toIntOrNull()?.let { id ->
                                val response = ApiClient.apiService.toggleCommentLike(id, LikeToggleRequest(userId, mutation.desired))
                                if (acknowledgePendingMutation(context, userId, mutation)) {
                                    applyServerCommentLike(mutation.postId, mutation.commentId, userId, response.liked, response.likeCount)
                                    saveToDisk(context, _postsFlow.value)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (!isRetryableMutationError(e)) {
                            Log.w(TAG, "Keeping ${mutation.kind} pending for reconciliation: ${e.message}")
                        }
                        // Keep the mutation. A future refresh/reconnect may run after
                        // temporary authentication/server state has recovered.
                    }
                }
            } finally {
                pendingMutationSyncInProgress.set(false)
            }
        }
    }

    /** Bounded automatic retries complement refresh-time reconciliation. */
    private fun schedulePendingMutationRetries(context: Context, userId: Int) {
        pendingMutationRetryJobs[userId]?.cancel()
        pendingMutationRetryJobs[userId] = CoroutineScope(Dispatchers.IO).launch {
            for (delayMs in longArrayOf(2_000L, 5_000L, 10_000L)) {
                delay(delayMs)
                if (loadPendingMutations(context, userId).isEmpty()) break
                syncPendingPostMutations(context, userId)
            }
        }
    }

    private fun applyServerPostLike(postId: String, userId: Int, liked: Boolean, likeCount: Int) {
        _postsFlow.value = _postsFlow.value.map { post ->
            if (post.id == postId) post.copy(
                likedByMe = liked,
                likedUserIds = if (liked) post.likedUserIds + userId else post.likedUserIds - userId,
                likeCount = likeCount.coerceAtLeast(0)
            ) else post
        }
    }

    private fun applyServerPostSave(postId: String, userId: Int, saved: Boolean) {
        _postsFlow.value = _postsFlow.value.map { post ->
            if (post.id == postId) post.copy(
                savedByMe = saved,
                savedUserIds = if (saved) post.savedUserIds + userId else post.savedUserIds - userId
            ) else post
        }
    }

    private fun applyServerCommentLike(postId: String, commentId: String?, userId: Int, liked: Boolean, likesCount: Int) {
        if (commentId == null) return
        fun update(comment: PostComment): PostComment {
            if (comment.id == commentId) return comment.copy(
                likedByMe = liked,
                likedUserIds = if (liked) comment.likedUserIds + userId else comment.likedUserIds - userId,
                likesCount = likesCount.coerceAtLeast(0)
            )
            val replies = comment.replies.map(::update)
            return if (replies != comment.replies) comment.copy(replies = replies) else comment
        }
        _postsFlow.value = _postsFlow.value.map { post ->
            if (post.id == postId) post.copy(comments = post.comments.map(::update)) else post
        }
    }

    private fun isSimpleRole(role: String?): Boolean {
        if (role == null) return false
        val r = role.trim().lowercase()
        return r == "simple user" || r == "simple_user" || r == "simpleuser" || r == "user"
    }

    private fun isAcademicRole(role: String?): Boolean {
        if (role == null) return false
        val r = role.trim().lowercase()
        return r == "teacher" || r == "student" || r == "faculty" || r == "admin"
    }

    private fun filterPostsByRole(posts: List<Post>, userRole: String?, userId: Int): List<Post> {
        val isSimple = isSimpleRole(userRole)
        return posts.filter { post ->
            // Always allow user's own posts
            if (post.authorId == userId) return@filter true

            // Defense-in-depth: check explicit targetAudience and authorRole enum values,
            // never heuristic string-sniffing on subtitle or post text!
            val audience = post.targetAudience.trim().uppercase()
            if (isSimple) {
                // Simple user: can only see posts for SIMPLE or ALL, from simple user authors
                when (audience) {
                    "SIMPLE" -> true
                    "ALL" -> isSimpleRole(post.authorRole)
                    "ACADEMIC" -> false
                    else -> isSimpleRole(post.authorRole)
                }
            } else {
                // Academic user (Teacher / Student / Admin): can only see posts for ACADEMIC or ALL, from academic authors
                when (audience) {
                    "ACADEMIC", "ALL" -> !isSimpleRole(post.authorRole)
                    "SIMPLE" -> false
                    else -> !isSimpleRole(post.authorRole)
                }
            }
        }
    }

    /**
     * Initialize the repository with Context, restoring posts from local cache instantly,
     * then fetching fresh posts from backend in background.
     *
     * Safe to call on every screen entry — detects user switches and resets automatically.
     */
    fun init(context: Context, currentUserId: Int, userRole: String? = null) {
        AppConnectivity.start(context)
        val effectiveRole = userRole ?: currentUserRole
        currentUserRole = effectiveRole
        if (initializedForUserId != currentUserId) {
            // New user (fresh login or account switch) → clear in-memory flow first
            _postsFlow.value = emptyList()
            _isPostCacheReady.value = false
            nextPostsCursorTimestamp = null
            nextPostsCursorId = null
            postsPageGeneration.incrementAndGet()
            _hasMorePosts.value = false
            pageLoadInProgress.set(false)
            _isLoadingMorePosts.value = false
            nextCommentCursors.clear()
            commentPageLoadInProgress.clear()
            commentFetchGenerations.clear()
            commentPaginationUserId = -1
            initializedForUserId = currentUserId

            // Room read + Gson parsing stay off the UI thread.  The cached feed is
            // emitted before any network call, so an offline user simply sees the
            // last known feed instead of a spinner or an error state.
            CoroutineScope(Dispatchers.IO).launch {
                val saved = loadFromDisk(context, currentUserId)
                if (initializedForUserId != currentUserId) return@launch
                val realPosts = saved.filter { it.authorId !in listOf(9991, 9992, 9993) }
                val filtered = filterPostsByRole(realPosts, effectiveRole, currentUserId)
                if (filtered.isNotEmpty()) {
                    _postsFlow.value = filtered.map { enrichPostForUser(it, currentUserId) }
                }
                _isPostCacheReady.value = true
                startSilentSync(context, currentUserId, effectiveRole)
            }
        } else {
            val filtered = filterPostsByRole(_postsFlow.value, effectiveRole, currentUserId)
            _postsFlow.value = filtered.map { enrichPostForUser(it, currentUserId) }
            _isPostCacheReady.value = true
            startSilentSync(context, currentUserId, effectiveRole)
        }
    }

    /** Network is optional: cached UI remains untouched while the device is offline. */
    private fun startSilentSync(context: Context, userId: Int, role: String?) {
        if (userId <= 0 || !AppConnectivity.refresh(context)) return
        refreshPosts(context, userId, role)
        retryFailedPostsOnNetworkAvailable(context)
        syncPendingPostMutations(context, userId)
    }

    /** Called by the shared reconnect watcher; does nothing unless a feed is active. */
    fun refreshActiveFeedOnNetworkAvailable(context: Context) {
        val userId = initializedForUserId
        if (userId <= 0 || !AppConnectivity.refresh(context)) return
        startSilentSync(context.applicationContext, userId, currentUserRole)
    }

    /**
     * Clear cached posts for a specific user — call this on logout so the next
     * user on this device cannot see the previous user's data.
     */
    fun clearForUser(context: Context, userId: Int) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(cacheKey(userId)).apply()
        } catch (_: Exception) {}
        // Reset in-memory state only if this was the active user
        if (initializedForUserId == userId) {
            _postsFlow.value = emptyList()
            _isPostCacheReady.value = false
            initializedForUserId = -1
            currentUserRole = null
            refreshInProgress = false
            pendingRefreshCallbacks.clear()
            likeJobs.values.forEach { it.cancel() }
            likeJobs.clear()
            saveJobs.values.forEach { it.cancel() }
            saveJobs.clear()
            pollJobs.values.forEach { it.cancel() }
            pollJobs.clear()
            commentLikeJobs.values.forEach { it.cancel() }
            commentLikeJobs.clear()
            pendingMutationRetryJobs.remove(userId)?.cancel()
            optimisticCommentIdMap.clear()
            pendingCommentServerIds.values.forEach { it.cancel() }
            pendingCommentServerIds.clear()
            autoRetryInProgress.set(false)
            nextPostsCursorTimestamp = null
            nextPostsCursorId = null
            postsPageGeneration.incrementAndGet()
            _hasMorePosts.value = false
            pageLoadInProgress.set(false)
            _isLoadingMorePosts.value = false
            nextCommentCursors.clear()
            commentPageLoadInProgress.clear()
            commentFetchGenerations.clear()
            commentPaginationUserId = -1
        }
        // Serialise cache deletion with pending writes.  A write captured before
        // logout must not recreate the just-removed account cache afterwards.
        CoroutineScope(Dispatchers.IO).launch {
            diskWriteMutex.withLock {
                try {
                    OfflineFirstRepository.remove(context.applicationContext, "posts", cacheKey(userId))
                } catch (_: Exception) {
                    // The legacy cache is already cleared above; do not make logout fail
                    // if local storage is temporarily unavailable.
                }
            }
        }
    }

    /**
     * Fetch fresh posts from FastAPI backend and update local cache + StateFlow.
     */
    fun refreshPosts(context: Context, userId: Int, role: String? = null, onComplete: ((Boolean) -> Unit)? = null) {
        val effectiveRole = role ?: currentUserRole
        if (role != null) currentUserRole = role

        // Offline is a normal cache-first state, not a request failure.  Avoid
        // creating a doomed Retrofit call (and any UI error/flicker it could cause).
        if (!AppConnectivity.refresh(context)) {
            onComplete?.let { callback ->
                CoroutineScope(Dispatchers.Main).launch { callback(false) }
            }
            return
        }

        // Deduplication: if a refresh is already in-flight, just register the callback
        // and skip firing a second identical GET /posts network request.
        if (refreshInProgress) {
            Log.d(TAG, "refreshPosts: already in-flight — queuing callback, skipping duplicate network call")
            if (onComplete != null) pendingRefreshCallbacks.add(onComplete)
            return
        }
        refreshInProgress = true
        // Invalidate any older page request before replacing the feed's first page.
        postsPageGeneration.incrementAndGet()
        _hasMorePosts.value = false

        CoroutineScope(Dispatchers.IO).launch {
            var success = false
            try {
                val response = ApiClient.apiService.getPosts(
                    userId = userId,
                    role = effectiveRole,
                    limit = POSTS_PAGE_SIZE
                )
                val serverPosts = response.posts
                val filtered = filterPostsByRole(serverPosts, effectiveRole, userId)
                val enriched = filtered.map { enrichPostForUser(it, userId) }

                // Preserve local posts that are currently being posted or failed so they don't silently vanish
                val localUnsynced = _postsFlow.value.filter { it.isPosting || it.isFailed }
                val merged = localUnsynced + enriched.filter { serverPost -> localUnsynced.none { it.id == serverPost.id } }

                // A same-content refresh must be invisible: do not emit a new list
                // or write the cache unless the server actually changed the feed.
                if (merged != _postsFlow.value) {
                    _postsFlow.value = merged
                    saveToDisk(context, merged)
                }
                nextPostsCursorTimestamp = response.nextCursorTimestamp
                nextPostsCursorId = response.nextCursorId
                _hasMorePosts.value = response.nextCursorTimestamp != null && response.nextCursorId != null
                Log.d(TAG, "Successfully loaded ${serverPosts.size} posts from server (filtered to ${enriched.size} for role=$effectiveRole, preserved ${localUnsynced.size} local)")
                // Feed data is authoritative, then replay only mutations that
                // have not yet received a server acknowledgement.
                syncPendingPostMutations(context, userId)
                success = true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to refresh posts from server: ${e.message}")
            }

            // Notify primary caller and all queued callers with the same result
            refreshInProgress = false
            val pending = pendingRefreshCallbacks.toList()
            pendingRefreshCallbacks.clear()
            // Callers commonly update Compose state (pull-to-refresh indicator),
            // so completion is always delivered on Main, never Retrofit's I/O job.
            CoroutineScope(Dispatchers.Main).launch {
                onComplete?.invoke(success)
                pending.forEach { it.invoke(success) }
            }
        }
    }

    /**
     * Fetch the next immutable keyset page for the All Posts feed. This appends
     * only unseen server posts, preserving any local optimistic posts in place.
     */
    fun loadMorePosts(context: Context, userId: Int, role: String? = null) {
        if (initializedForUserId != userId || !_hasMorePosts.value || !AppConnectivity.refresh(context)) return
        val cursorTimestamp = nextPostsCursorTimestamp ?: return
        val cursorId = nextPostsCursorId ?: return
        if (!pageLoadInProgress.compareAndSet(false, true)) return
        val pageGeneration = postsPageGeneration.get()
        _isLoadingMorePosts.value = true
        val effectiveRole = role ?: currentUserRole

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = ApiClient.apiService.getPosts(
                    userId = userId,
                    role = effectiveRole,
                    limit = POSTS_PAGE_SIZE,
                    beforeTimestamp = cursorTimestamp,
                    beforeId = cursorId
                )
                // Ignore a late response if the account changed while it was in flight.
                if (initializedForUserId != userId || pageGeneration != postsPageGeneration.get()) return@launch
                val nextPage = filterPostsByRole(response.posts, effectiveRole, userId)
                    .map { enrichPostForUser(it, userId) }
                val current = _postsFlow.value
                val existingIds = current.asSequence().map { it.id }.toHashSet()
                val merged = current + nextPage.filter { existingIds.add(it.id) }
                if (merged != current) {
                    _postsFlow.value = merged
                    saveToDisk(context, merged)
                }
                nextPostsCursorTimestamp = response.nextCursorTimestamp
                nextPostsCursorId = response.nextCursorId
                _hasMorePosts.value = response.nextCursorTimestamp != null && response.nextCursorId != null
                Log.d(TAG, "Loaded ${nextPage.size} older post(s); hasMore=${_hasMorePosts.value}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load more posts: ${e.message}")
            } finally {
                pageLoadInProgress.set(false)
                _isLoadingMorePosts.value = false
            }
        }
    }

    /**
     * Get all posts with current user's like/saved state applied.
     */
    fun getAllPosts(userId: Int): List<Post> {
        return _postsFlow.value.map { enrichPostForUser(it, userId) }
    }

    /**
     * Get posts created by the current user.
     */
    fun getMyPosts(userId: Int): List<Post> {
        return _postsFlow.value.filter { it.authorId == userId }.map { enrichPostForUser(it, userId) }
    }

    /**
     * Get posts bookmarked / saved by the current user.
     */
    fun getSavedPosts(userId: Int): List<Post> {
        return _postsFlow.value.filter { it.savedByMe }.map { enrichPostForUser(it, userId) }
    }

    /**
     * Create a new Post:
     * 1. Optimistically updates local UI immediately with isPosting = true (0ms latency).
     * 2. Uploads media (if any) or sends text/poll payload to FastAPI backend.
     * 3. On success: replaces optimistic post with confirmed server post (isPosting = false).
     * 4. On failure: marks post with isFailed = true so user sees a retry badge instead of losing the post.
     */
    fun createPost(context: Context, post: Post, currentUserId: Int, onComplete: ((Boolean) -> Unit)? = null) {
        val idempotencyKey = idempotencyKeyForPost(post, currentUserId)
        val optimisticPost = enrichPostForUser(post.copy(isPosting = true, isFailed = false), currentUserId)
        val currentList = _postsFlow.value.toMutableList()
        val existingIndex = currentList.indexOfFirst { it.id == post.id }
        if (existingIndex >= 0) {
            currentList[existingIndex] = optimisticPost
        } else {
            currentList.add(0, optimisticPost)
        }
        _postsFlow.value = currentList
        saveToDisk(context, currentList)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val mediaUrlStr = post.media?.mediaUrl
                val localUriStr = post.media?.localUri
                val createdPost: Post = if (!mediaUrlStr.isNullOrBlank()) {
                    // Media already uploaded to server: create post directly with server URL
                    val req = CreatePostNetworkRequest(
                        author_id = currentUserId,
                        text = post.text,
                        media_type = post.media?.type?.name,
                        media_url = mediaUrlStr,
                        media_filename = post.media?.fileName,
                        poll_question = post.media?.pollData?.question,
                        poll_options = post.media?.pollData?.options?.map { it.text },
                        target_audience = post.targetAudience
                    )
                    ApiClient.apiService.createPost(idempotencyKey = idempotencyKey, request = req)
                } else if (!localUriStr.isNullOrBlank()) {
                    // The composer must first complete its persistent,
                    // acknowledged TransferManager upload.  Falling back to a
                    // one-shot multipart request here would silently lose the
                    // retry/resume guarantees on a slow or interrupted network.
                    throw IOException("Media is still uploading. Please wait for it to finish.")
                } else {
                    // Plain text or poll
                    val req = CreatePostNetworkRequest(
                        author_id = currentUserId,
                        text = post.text,
                        media_type = if (post.media != null && post.media.type != PostMediaType.NONE) post.media.type.name else null,
                        media_url = post.media?.mediaUrl,
                        media_filename = post.media?.fileName,
                        poll_question = post.media?.pollData?.question,
                        poll_options = post.media?.pollData?.options?.map { it.text },
                        target_audience = post.targetAudience
                    )
                    ApiClient.apiService.createPost(idempotencyKey = idempotencyKey, request = req)
                }

                // Replace optimistic post with confirmed server post
                val updatedList = _postsFlow.value.map {
                    if (it.id == post.id) enrichPostForUser(createdPost.copy(isPosting = false, isFailed = false), currentUserId) else it
                }
                _postsFlow.value = updatedList
                saveToDisk(context, updatedList)
                Log.d(TAG, "Post created on server: ${createdPost.id}")
                onComplete?.invoke(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create post on server: ${e.message}", e)
                // Mark optimistic post as failed so the user gets a retry badge
                val failedList = _postsFlow.value.map {
                    if (it.id == post.id) it.copy(isPosting = false, isFailed = true) else it
                }
                _postsFlow.value = failedList
                saveToDisk(context, failedList)
                onComplete?.invoke(false)
            }
        }
    }

    /**
     * Retry publishing a failed post.
     */
    fun retryPost(context: Context, post: Post, currentUserId: Int, onComplete: ((Boolean) -> Unit)? = null) {
        createPost(context, post, currentUserId, onComplete)
    }

    /**
     * Retries locally persisted failed posts after Android reports a validated network.
     * The server receives a stable idempotency key for every attempt, so a response
     * lost after a successful write cannot create a duplicate post. Retries are
     * sequential and stop after the first failure to avoid a reconnect request storm.
     */
    fun retryFailedPostsOnNetworkAvailable(context: Context) {
        val userId = initializedForUserId
        if (userId <= 0 || !hasValidatedNetwork(context)) return
        if (!autoRetryInProgress.compareAndSet(false, true)) return

        val pendingIds = _postsFlow.value
            .filter { it.isFailed && !it.isPosting && it.authorId == userId }
            .map { it.id }

        if (pendingIds.isEmpty()) {
            autoRetryInProgress.set(false)
            return
        }

        Log.d(TAG, "Network restored; auto-retrying ${pendingIds.size} failed post(s) sequentially")

        fun retryNext(index: Int) {
            if (index >= pendingIds.size || initializedForUserId != userId) {
                autoRetryInProgress.set(false)
                return
            }

            val post = _postsFlow.value.firstOrNull {
                it.id == pendingIds[index] && it.isFailed && !it.isPosting && it.authorId == userId
            }
            if (post == null) {
                retryNext(index + 1)
                return
            }

            retryPost(context.applicationContext, post, userId) { success ->
                if (success) retryNext(index + 1) else autoRetryInProgress.set(false)
            }
        }

        retryNext(0)
    }

    private fun hasValidatedNetwork(context: Context): Boolean {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun idempotencyKeyForPost(post: Post, currentUserId: Int): String {
        val raw = "post_${currentUserId}_${post.id}"
        return raw.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(128)
    }

    /**
     * Toggle Like on a Post:
     * 1. Optimistic instant local update.
     * 2. Background sync to backend POST /posts/{id}/like.
     */
    fun toggleLike(context: Context, postId: String, userId: Int) {
        var targetLikedState = false
        val currentList = _postsFlow.value.map { post ->
            if (post.id == postId) {
                val isLiked = post.likedByMe
                targetLikedState = !isLiked
                val newLikedIds = if (isLiked) {
                    post.likedUserIds - userId
                } else {
                    post.likedUserIds + userId
                }
                val newCount = (post.likeCount + if (isLiked) -1 else 1).coerceAtLeast(0)
                post.copy(
                    likeCount = newCount,
                    likedUserIds = newLikedIds,
                    likedByMe = !isLiked
                )
            } else post
        }
        _postsFlow.value = currentList
        saveToDisk(context, currentList)

        // Backend sync with debounce & cancellation of superseded rapid taps
        postId.toIntOrNull()?.let { pid ->
            val mutation = PendingPostMutation(kind = "like", postId = postId, desired = targetLikedState)
            enqueuePendingMutation(context, userId, mutation)
            likeJobs[postId]?.cancel()
            val job = CoroutineScope(Dispatchers.IO).launch {
                delay(250)
                try {
                    val resp = ApiClient.apiService.togglePostLike(pid, LikeToggleRequest(userId, liked = targetLikedState))
                    Log.d(TAG, "Post $pid like synced on server: liked=${resp.liked}, count=${resp.likeCount}")
                    if (acknowledgePendingMutation(context, userId, mutation)) {
                        applyServerPostLike(postId, userId, resp.liked, resp.likeCount)
                        saveToDisk(context, _postsFlow.value)
                    }
                } catch (e: Exception) {
                    if (e !is kotlinx.coroutines.CancellationException) {
                        Log.w(TAG, "Like retained for retry/reconciliation: ${e.message}")
                        schedulePendingMutationRetries(context, userId)
                    }
                } finally {
                    likeJobs.remove(postId)
                }
            }
            likeJobs[postId] = job
        }
    }

    /**
     * Toggle Save / Bookmark on a Post:
     * 1. Optimistic instant local update.
     * 2. Background sync to backend POST /posts/{id}/save.
     */
    fun toggleSave(context: Context, postId: String, userId: Int): Boolean {
        var willBeSaved = false
        val currentList = _postsFlow.value.map { post ->
            if (post.id == postId) {
                val isSaved = post.savedByMe
                willBeSaved = !isSaved
                val newSavedIds = if (isSaved) {
                    post.savedUserIds - userId
                } else {
                    post.savedUserIds + userId
                }
                post.copy(
                    savedUserIds = newSavedIds,
                    savedByMe = !isSaved
                )
            } else post
        }
        _postsFlow.value = currentList
        saveToDisk(context, currentList)

        // Backend sync with debounce & cancellation of superseded rapid taps
        postId.toIntOrNull()?.let { pid ->
            val mutation = PendingPostMutation(kind = "save", postId = postId, desired = willBeSaved)
            enqueuePendingMutation(context, userId, mutation)
            saveJobs[postId]?.cancel()
            val job = CoroutineScope(Dispatchers.IO).launch {
                delay(250)
                try {
                    val resp = ApiClient.apiService.togglePostSave(pid, SaveToggleRequest(userId, saved = willBeSaved))
                    Log.d(TAG, "Post $pid save synced on server: saved=${resp.saved}")
                    if (acknowledgePendingMutation(context, userId, mutation)) {
                        applyServerPostSave(postId, userId, resp.saved)
                        saveToDisk(context, _postsFlow.value)
                    }
                } catch (e: Exception) {
                    if (e !is kotlinx.coroutines.CancellationException) {
                        Log.w(TAG, "Save retained for retry/reconciliation: ${e.message}")
                        schedulePendingMutationRetries(context, userId)
                    }
                } finally {
                    saveJobs.remove(postId)
                }
            }
            saveJobs[postId] = job
        }
        return willBeSaved
    }

    /**
     * Add a comment (or reply) to a Post:
     * 1. Optimistic instant local update (supports nested replies under parentCommentId).
     * 2. Background sync to backend POST /posts/{id}/comments with parent_id.
     */
    fun addComment(
        context: Context,
        postId: String,
        comment: PostComment,
        currentUserId: Int,
        parentCommentId: String? = null,
        onSyncComplete: (Boolean) -> Unit = {}
    ) {
        // Register before launching the network coroutine. A rapid reply can now
        // await this exact parent rather than polling the UI cache.
        val serverIdConfirmation = if (comment.id.toIntOrNull() == null) {
            pendingCommentServerIds.getOrPut(comment.id) { CompletableDeferred() }
        } else {
            null
        }
        val currentList = _postsFlow.value.map { post ->
            if (post.id == postId) {
                val updatedComments = if (parentCommentId != null) {
                    post.comments.map { parent ->
                        if (parent.id == parentCommentId) {
                            val newReplies = parent.replies + comment
                            parent.copy(
                                replies = newReplies,
                                repliesCount = newReplies.size
                            )
                        } else parent
                    }
                } else {
                    post.comments + comment
                }
                val totalComments = updatedComments.sumOf { 1 + it.replies.size }
                post.copy(
                    comments = updatedComments,
                    commentCount = totalComments
                )
            } else post
        }
        _postsFlow.value = currentList
        saveToDisk(context, currentList)

        // Backend sync
        val pid = postId.toIntOrNull()
        if (pid == null) {
            serverIdConfirmation?.completeExceptionally(IllegalArgumentException("Invalid post ID"))
            if (serverIdConfirmation != null) {
                pendingCommentServerIds.remove(comment.id, serverIdConfirmation)
            }
            onSyncComplete(false)
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
                try {
                    val resolvedParentId = when {
                        parentCommentId == null -> null
                        parentCommentId.toIntOrNull() != null -> parentCommentId.toInt()
                        optimisticCommentIdMap[parentCommentId] != null -> optimisticCommentIdMap[parentCommentId]
                        else -> {
                            val parentConfirmation = pendingCommentServerIds[parentCommentId]
                                ?: throw IllegalStateException("Reply parent is not server-confirmed")
                            withTimeoutOrNull(30_000L) { parentConfirmation.await() }
                                ?: throw IOException("Timed out waiting for parent comment confirmation")
                        }
                    }

                    val serverComment = ApiClient.apiService.addPostComment(
                        pid,
                        CommentCreateRequest(
                            author_id = currentUserId,
                            text = comment.text,
                            parent_id = resolvedParentId
                        )
                    )
                    Log.d(TAG, "Comment added on server: ${serverComment.id}, resolvedParentId=$resolvedParentId")

                    // Register this comment's server ID so any subsequent replies can resolve it
                    serverComment.id.toIntOrNull()?.let { realId ->
                        optimisticCommentIdMap[comment.id] = realId
                        serverIdConfirmation?.complete(realId)
                    }

                    // Refresh comments from server so optimistic IDs match DB IDs
                    fetchComments(postId, currentUserId)
                    onSyncComplete(true)
                } catch (e: Exception) {
                    serverIdConfirmation?.completeExceptionally(e)
                    Log.w(TAG, "Failed to sync comment on server: ${e.message}")
                    onSyncComplete(false)
                } finally {
                    if (serverIdConfirmation != null) {
                        pendingCommentServerIds.remove(comment.id, serverIdConfirmation)
                    }
                }
            }
    }

    /**
     * Toggle Like on a Comment (or nested reply):
     * 1. Optimistic instant local update for top-level or reply comments.
     * 2. Background sync to backend POST /posts/comments/{id}/like.
     */
    fun toggleCommentLike(context: Context, postId: String, commentId: String, userId: Int) {
        var targetCommentLiked = false
        fun updateCommentLike(c: PostComment): PostComment {
            if (c.id == commentId) {
                val isLiked = c.likedByMe
                targetCommentLiked = !isLiked
                val newLikedIds = if (isLiked) c.likedUserIds - userId else c.likedUserIds + userId
                val newCount = (c.likesCount + if (isLiked) -1 else 1).coerceAtLeast(0)
                return c.copy(
                    likesCount = newCount,
                    likedUserIds = newLikedIds,
                    likedByMe = !isLiked
                )
            }
            if (c.replies.isNotEmpty()) {
                val updatedReplies = c.replies.map { updateCommentLike(it) }
                if (updatedReplies != c.replies) {
                    return c.copy(replies = updatedReplies)
                }
            }
            return c
        }

        val currentList = _postsFlow.value.map { post ->
            if (post.id == postId) {
                val updatedComments = post.comments.map { updateCommentLike(it) }
                post.copy(comments = updatedComments)
            } else post
        }
        _postsFlow.value = currentList
        saveToDisk(context, currentList)

        // Backend sync with debounce & cancellation of superseded rapid taps
        commentId.toIntOrNull()?.let { cid ->
            val mutation = PendingPostMutation(
                kind = "comment_like", postId = postId, commentId = commentId, desired = targetCommentLiked
            )
            enqueuePendingMutation(context, userId, mutation)
            commentLikeJobs[commentId]?.cancel()
            val job = CoroutineScope(Dispatchers.IO).launch {
                delay(250)
                try {
                    val resp = ApiClient.apiService.toggleCommentLike(cid, LikeToggleRequest(userId, liked = targetCommentLiked))
                    Log.d(TAG, "Comment $cid like synced on server: liked=${resp.liked}, count=${resp.likeCount}")
                    if (acknowledgePendingMutation(context, userId, mutation)) {
                        applyServerCommentLike(postId, commentId, userId, resp.liked, resp.likeCount)
                        saveToDisk(context, _postsFlow.value)
                    }
                } catch (e: Exception) {
                    if (e !is kotlinx.coroutines.CancellationException) {
                        Log.w(TAG, "Comment like retained for retry/reconciliation: ${e.message}")
                        schedulePendingMutationRetries(context, userId)
                    }
                } finally {
                    commentLikeJobs.remove(commentId)
                }
            }
            commentLikeJobs[commentId] = job
        }
    }

    /**
     * Load comments for a post from backend.
     */
    fun fetchComments(postId: String, userId: Int, onResult: ((List<PostComment>) -> Unit)? = null) {
        val pid = postId.toIntOrNull() ?: return
        val generation = commentFetchGenerations.getOrPut(postId) { AtomicLong(0) }.incrementAndGet()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = ApiClient.apiService.getPostComments(
                    postId = pid,
                    userId = userId,
                    limit = COMMENTS_PAGE_SIZE
                )
                val serverComments = response.comments
                // The user may have switched accounts, or a newer refresh for the
                // same sheet may have won the race. Never overwrite newer state.
                if (initializedForUserId != userId ||
                    commentFetchGenerations[postId]?.get() != generation) return@launch
                commentPaginationUserId = userId
                val cursor = response.nextCursorTimestamp?.let { timestamp ->
                    response.nextCursorId?.let { id -> CommentCursor(timestamp, id) }
                }
                if (cursor != null) nextCommentCursors[postId] = cursor else nextCommentCursors.remove(postId)
                val updated = _postsFlow.value.map {
                    if (it.id == postId) it.copy(
                        comments = serverComments,
                        // The page intentionally has fewer rows than the post's complete
                        // thread. Keep the server's exact total for the header/loading UI.
                        commentCount = response.totalCount
                    ) else it
                }
                _postsFlow.value = updated
                onResult?.invoke(serverComments)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load comments from server: ${e.message}")
            }
        }
    }

    /**
     * Load older top-level comments only after the reader reaches the top of the
     * current page.  A per-post atomic guard prevents repeated scroll callbacks
     * from issuing duplicate page requests.
     */
    fun loadMoreComments(postId: String, userId: Int) {
        val pid = postId.toIntOrNull() ?: return
        if (commentPaginationUserId != userId) return
        val cursor = nextCommentCursors[postId] ?: return
        val inProgress = commentPageLoadInProgress.getOrPut(postId) { AtomicBoolean(false) }
        if (!inProgress.compareAndSet(false, true)) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = ApiClient.apiService.getPostComments(
                    postId = pid,
                    userId = userId,
                    limit = COMMENTS_PAGE_SIZE,
                    beforeTimestamp = cursor.timestamp,
                    beforeId = cursor.id
                )
                // A refresh/account switch while this request was in flight makes
                // its response stale. Discard it rather than merging wrong likes.
                if (commentPaginationUserId != userId || nextCommentCursors[postId] != cursor) return@launch

                val updated = _postsFlow.value.map { post ->
                    if (post.id != postId) return@map post
                    val existingIds = post.comments.asSequence().map { it.id }.toHashSet()
                    val olderUnique = response.comments.filter { it.id !in existingIds }
                    post.copy(
                        // Responses are chronological; older pages go before the
                        // currently visible recent page, preserving reply nesting.
                        comments = olderUnique + post.comments,
                        commentCount = response.totalCount
                    )
                }
                _postsFlow.value = updated

                val nextCursor = response.nextCursorTimestamp?.let { timestamp ->
                    response.nextCursorId?.let { id -> CommentCursor(timestamp, id) }
                }
                if (nextCursor != null) nextCommentCursors[postId] = nextCursor else nextCommentCursors.remove(postId)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load more comments: ${e.message}")
            } finally {
                inProgress.set(false)
            }
        }
    }

    /**
     * Vote on a poll option in a Post:
     * 1. Optimistic instant local update.
     * 2. Background sync to backend POST /posts/{id}/poll/vote.
     */
    fun votePoll(context: Context, postId: String, optionId: String, userId: Int) {
        val currentList = _postsFlow.value.map { post ->
            if (post.id == postId && post.media?.pollData != null) {
                val poll = post.media.pollData
                val existingVotedOptionId = poll.userVotedOptionId
                if (existingVotedOptionId == optionId) {
                    return@map post
                }

                val updatedOptions = poll.options.map { opt ->
                    if (opt.id == optionId) {
                        opt.copy(
                            voteCount = opt.voteCount + 1,
                            voterUserIds = opt.voterUserIds + userId
                        )
                    } else if (opt.id == existingVotedOptionId) {
                        opt.copy(
                            voteCount = (opt.voteCount - 1).coerceAtLeast(0),
                            voterUserIds = opt.voterUserIds - userId
                        )
                    } else {
                        opt
                    }
                }

                val newTotal = updatedOptions.sumOf { it.voteCount }
                val updatedPoll = poll.copy(
                    options = updatedOptions,
                    totalVotes = newTotal,
                    userVotedOptionId = optionId
                )

                post.copy(media = post.media.copy(pollData = updatedPoll))
            } else post
        }
        _postsFlow.value = currentList
        saveToDisk(context, currentList)

        // Backend sync with debounce & cancellation of superseded rapid votes
        postId.toIntOrNull()?.let { pid ->
            pollJobs[postId]?.cancel()
            val job = CoroutineScope(Dispatchers.IO).launch {
                delay(250)
                try {
                    val updatedPost = ApiClient.apiService.votePostPoll(pid, PollVoteRequest(userId, optionId))
                    val list = _postsFlow.value.map { if (it.id == postId) enrichPostForUser(updatedPost, userId) else it }
                    _postsFlow.value = list
                    saveToDisk(context, list)
                    Log.d(TAG, "Poll vote recorded on server for post $pid")
                } catch (e: Exception) {
                    if (e !is kotlinx.coroutines.CancellationException) {
                        Log.w(TAG, "Failed to sync poll vote: ${e.message}")
                    }
                } finally {
                    pollJobs.remove(postId)
                }
            }
            pollJobs[postId] = job
        }
    }

    /**
     * Delete a Post if author matches:
     * 1. Optimistic removal.
     * 2. Backend call DELETE /posts/{id}.
     */
    fun deletePost(context: Context, postId: String, userId: Int): Boolean {
        val post = _postsFlow.value.firstOrNull { it.id == postId } ?: return false
        if (post.authorId != userId) return false
        val currentList = _postsFlow.value.filter { it.id != postId }
        _postsFlow.value = currentList
        saveToDisk(context, currentList)

        postId.toIntOrNull()?.let { pid ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    ApiClient.apiService.deletePost(pid, userId)
                    Log.d(TAG, "Post $pid deleted on server")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to delete post on server: ${e.message}")
                }
            }
        }
        return true
    }

    private fun enrichPostForUser(post: Post, userId: Int): Post {
        // Feed responses intentionally no longer ship every liker/saver/voter
        // ID.  These booleans are the server-authoritative state for this user;
        // the lists remain only as backward-compatible local optimistic state.
        val liked = post.likedByMe
        val saved = post.savedByMe
        val votedPollOptId = post.media?.pollData?.userVotedOptionId
        val updatedPollData = post.media?.pollData?.copy(userVotedOptionId = votedPollOptId)
        val updatedMedia = if (updatedPollData != null) post.media.copy(pollData = updatedPollData) else post.media
        return post.copy(
            likedByMe = liked,
            savedByMe = saved,
            media = updatedMedia
        )
    }

    private const val AVATARS_PREFS_NAME = "educonnect_avatars_cache"
    private val diskWriteMutex = Mutex()

    private fun saveUserAvatar(context: Context, userId: Int, base64: String?) {
        if (userId <= 0 || base64.isNullOrBlank()) return
        try {
            val prefs = context.getSharedPreferences(AVATARS_PREFS_NAME, Context.MODE_PRIVATE)
            val current = prefs.getString("avatar_$userId", null)
            // Skip rewrite if avatar has not changed — saves redundant I/O on every like/comment
            if (current == base64) return
            prefs.edit().putString("avatar_$userId", base64).apply()
        } catch (_: Exception) {}
    }

    private fun getUserAvatar(context: Context, userId: Int): String? {
        if (userId <= 0) return null
        return try {
            val prefs = context.getSharedPreferences(AVATARS_PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getString("avatar_$userId", null)
        } catch (_: Exception) { null }
    }

    private suspend fun loadFromDisk(context: Context, userId: Int): List<Post> {
        return try {
            val key = cacheKey(userId)
            // Room is the primary source.  The old preference is read only once
            // as a migration fallback so existing users keep their feed cache on
            // upgrade; all subsequent writes go exclusively to Room.
            val roomJson = OfflineFirstRepository.read(context, "posts", key)
            val json = roomJson ?: context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(key, null)
            if (roomJson == null && !json.isNullOrBlank()) {
                OfflineFirstRepository.write(context, "posts", key, json)
            }
            if (!json.isNullOrBlank()) {
                val type = object : TypeToken<List<Post>>() {}.type
                val rawList: List<Post> = gson.fromJson(json, type) ?: emptyList()
                // Re-hydrate author avatars from dedicated single-entry avatar cache
                rawList.map { post ->
                    val pic = post.authorProfilePic ?: getUserAvatar(context, post.authorId)
                    post.copy(
                        authorProfilePic = pic,
                        comments = post.comments.map { comment ->
                            val cPic = comment.authorProfilePic ?: getUserAvatar(context, comment.authorId)
                            comment.copy(
                                authorProfilePic = cPic,
                                replies = comment.replies.map { reply ->
                                    val rPic = reply.authorProfilePic ?: getUserAvatar(context, reply.authorId)
                                    reply.copy(authorProfilePic = rPic)
                                }
                            )
                        }
                    )
                }
            } else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Save posts to disk asynchronously on Dispatchers.IO (never blocks UI main thread).
     * Protected by diskWriteMutex to guarantee strictly serialized in-order writes even
     * on rapid consecutive user interactions (e.g. liking 3 posts in a row).
     * Strips repeated multi-megabyte base64 strings from posts JSON before it enters
     * the Room payload cache, keeping local storage bounded and fast to restore.
     */
    private fun saveToDisk(context: Context, posts: List<Post>) {
        val uid = initializedForUserId
        if (uid < 0) return        // no active user — don't write to disk
        CoroutineScope(Dispatchers.IO).launch {
            diskWriteMutex.withLock {
                if (initializedForUserId != uid) return@withLock
                try {
                    // 1. Cache each unique user's avatar in dedicated single-instance storage
                    posts.forEach { post ->
                        if (!post.authorProfilePic.isNullOrBlank()) {
                            saveUserAvatar(context, post.authorId, post.authorProfilePic)
                        }
                        post.comments.forEach { c ->
                            if (!c.authorProfilePic.isNullOrBlank()) {
                                saveUserAvatar(context, c.authorId, c.authorProfilePic)
                            }
                            c.replies.forEach { r ->
                                if (!r.authorProfilePic.isNullOrBlank()) {
                                    saveUserAvatar(context, r.authorId, r.authorProfilePic)
                                }
                            }
                        }
                    }

                    // 2. Strip repeated base64 blobs from posts JSON so file is mere KBs instead of MBs
                    val lightweightPosts = posts.map { post ->
                        post.copy(
                            authorProfilePic = null,
                            comments = post.comments.map { comment ->
                                comment.copy(
                                    authorProfilePic = null,
                                    replies = comment.replies.map { reply ->
                                        reply.copy(authorProfilePic = null)
                                    }
                                )
                            }
                        )
                    }

                    val json = gson.toJson(lightweightPosts)
                    OfflineFirstRepository.write(context, "posts", cacheKey(uid), json)
                } catch (_: Exception) {}
            }
        }
    }
}


