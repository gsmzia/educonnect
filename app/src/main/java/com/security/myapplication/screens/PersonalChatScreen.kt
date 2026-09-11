package com.security.myapplication.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.util.Base64
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import com.security.myapplication.audio.VoiceNoteRecorder
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.security.myapplication.models.*
import com.security.myapplication.network.ApiClient
import com.security.myapplication.offline.AppConnectivity
import com.security.myapplication.offline.ChatOfflineCache
import com.security.myapplication.posts.cloudinaryAdaptiveHlsUrl
import com.security.myapplication.transfer.TransferManager
import com.security.myapplication.transfer.TransferStateHolder
import com.security.myapplication.transfer.TransferStatus
import com.security.myapplication.audio.GlobalAudioPlayer
import com.security.myapplication.ui.theme.AppMotion
import com.security.myapplication.ui.theme.NoNativeOverscroll
import com.security.myapplication.ui.theme.pumpBounceScroll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.absoluteValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

// -- Theme ---------------------------------------------------------------------
private val PCBg        = Color(0xFF0D0A22)
private val PCCardBg    = Color(0xFF16132D)
private val PCBorder    = Color(0xFF26224A)
private val PCPurple    = Color(0xFF8B6BFF)
private val PCPurpleDk  = Color(0xFF5B3FCC)
private val PCPurpleLt  = Color(0xFF9D80FF)
private val PCSubText   = Color(0xFF7A7899)
private val PCTickBlue  = Color(0xFF9D80FF)
private val PCGreen     = Color(0xFF30C96B)
private val PCInputBg   = Color(0xFF171433)
private val AcceptGreen = Color(0xFF1DE9B6)
private val DeclineRed  = Color(0xFFFF5252)
private const val PERSONAL_CHAT_PAGE_SIZE = 50

// -- Local Reply Metadata holder -----------------------------------------------
private data class PCReplyInfo(val targetId: Int?, val senderName: String, val type: String, val preview: String)

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PersonalChatScreen(
    currentUserId: Int,
    otherUser: PersonalChatUser,
    initialMessage: String = "",
    highlightMessageId: Int? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    // Keep opening instant without putting an entire long conversation in memory.
    var messages by remember(currentUserId, otherUser.id) {
        mutableStateOf(MessageCache.getPersonal(currentUserId, otherUser.id).filter { it.id > 0 }.takeLast(PERSONAL_CHAT_PAGE_SIZE))
    }
    // Local cutoff also protects the UI from an in-flight pre-clear poll.
    // The server persists the authoritative cutoff per account/conversation.
    var clearedThroughMessageId by remember(currentUserId, otherUser.id) { mutableIntStateOf(0) }

    // Process-restored history is read from Room.  Do not replace a newer
    // network result if polling wins this small race.
    LaunchedEffect(currentUserId, otherUser.id) {
        val cached = ChatOfflineCache.loadPersonal(context, currentUserId, otherUser.id)
        if (messages.none { it.id > 0 } && cached.isNotEmpty()) {
            messages = cached
            MessageCache.savePersonal(currentUserId, otherUser.id, cached)
        }
    }
    LaunchedEffect(currentUserId, otherUser.id) {
        ChatOfflineCache.observePersonal(context, currentUserId, otherUser.id).collect { cached ->
            if (cached.isEmpty()) return@collect
            val currentConfirmed = messages.filter { it.id > 0 }
            val existingIds = currentConfirmed.asSequence().map { it.id }.toHashSet()
            // Preserve any older page already visible; Room contributes only IDs
            // absent from this in-memory snapshot, then IDs restore chronology.
            val ordered = (currentConfirmed + cached.filter { existingIds.add(it.id) })
                .sortedBy { it.id }
            val pending = messages.filter { it.id < 0 }
            val merged = ordered + pending
            if (merged != messages) messages = merged
        }
    }
    // Every confirmed message path (text, media, retry, forward, or poll) flows
    // through this one debounced Room writer.  Pending transfer bubbles are
    // intentionally excluded by the repository until the server acknowledges them.
    LaunchedEffect(messages, currentUserId, otherUser.id, clearedThroughMessageId) {
        delay(250)
        ChatOfflineCache.savePersonal(
            context, currentUserId, otherUser.id,
            messages.filter { it.id < 0 || it.id > clearedThroughMessageId }
        )
    }
    var isLoadingOlderMessages by remember(currentUserId, otherUser.id) { mutableStateOf(false) }
    var hasOlderMessages by remember(currentUserId, otherUser.id) { mutableStateOf(true) }
    var hasCompletedInitialServerSync by remember(currentUserId, otherUser.id) { mutableStateOf(false) }
    var messageText by remember { mutableStateOf(initialMessage) }
    var isSending by remember { mutableStateOf(false) }
    var showAttachmentMenu by remember { mutableStateOf(false) }

    // Voice Recording State
    var isRecording by remember { mutableStateOf(false) }
    var isPaused by remember { mutableStateOf(false) }
    var recordTimeSeconds by remember { mutableStateOf(0) }
    var liveAmplitudes by remember { mutableStateOf<List<Int>>(emptyList()) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingFile by remember { mutableStateOf<File?>(null) }

    // Fullscreen image & video player state
    var fullscreenBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var activeVideoUrl by remember { mutableStateOf<String?>(null) }

    // Auto-Play Consecutive Voice Message State
    var autoPlayVoiceMsgId by remember { mutableStateOf<Int?>(null) }

    // Image / Video Caption Preview State
    var pendingMediaUri by remember { mutableStateOf<Uri?>(null) }
    var pendingIsVideo by remember { mutableStateOf(false) }
    var imageCaptionText by remember { mutableStateOf("") }
    var isUploadingImage by remember { mutableStateOf(false) }

    // CameraX in-app camera overlay
    var showCameraScreen by remember { mutableStateOf(false) }

    // Failed upload tracking (tempId → original Uri for retry)
    var failedMessageIds by remember { mutableStateOf(setOf<Int>()) }
    var retryUriMap by remember { mutableStateOf(mapOf<Int, Uri>()) }

    // Message selection / action state (WhatsApp style Multi-selection Pin & Delete & Forward)
    var selectedMessages by remember { mutableStateOf<Set<PersonalMessage>>(emptySet()) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showForwardDialog by remember { mutableStateOf(false) }
    var failedActionMsg by remember { mutableStateOf<PersonalMessage?>(null) }
    var deletedForMeIds by remember { mutableStateOf(setOf<Int>()) }
    var currentPinnedIndex by remember { mutableIntStateOf(0) }
    var showUserInfoSheet by remember { mutableStateOf(false) }
    var showChatOverflowMenu by remember { mutableStateOf(false) }
    var showClearChatConfirmation by remember { mutableStateOf(false) }
    var blockStatus by remember(currentUserId, otherUser.id) { mutableStateOf<PersonalBlockStatus?>(null) }
    val isChatBlocked = blockStatus?.is_blocked == true

    val chatPrefs = remember { context.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE) }
    LaunchedEffect(currentUserId, otherUser.id) {
        val savedDeleted = chatPrefs.getStringSet("deleted_for_me_${currentUserId}_p_${otherUser.id}", emptySet()) ?: emptySet()
        deletedForMeIds = savedDeleted.mapNotNull { it.toIntOrNull() }.toSet()
    }

    DisposableEffect(otherUser.id) {
        com.security.myapplication.notifications.AppMessageNotificationWatcher.setActiveChat("direct", otherUser.id)
        com.security.myapplication.notifications.AppNotificationManager.let { notifications ->
            notifications.cancelNotification(
                context,
                notifications.getMessageNotifId(false, otherUser.id)
            )
            notifications.cancelNotification(
                context,
                notifications.getSummaryNotifId(false, otherUser.id)
            )
            notifications.clearConversationHistory(context, false, otherUser.id)
        }
        onDispose {
            com.security.myapplication.notifications.AppMessageNotificationWatcher.setActiveChat(null, null)
            if (isRecording || recorder != null) {
                try { recorder?.stop() } catch (_: Exception) {}
                try { recorder?.release() } catch (_: Exception) {}
                recorder = null
                recordingFile?.delete()
                recordingFile = null
                isRecording = false
                isPaused = false
                recordTimeSeconds = 0
                liveAmplitudes = emptyList()
            }
        }
    }

    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var showEmojiBar by remember { mutableStateOf(false) }
    var replyingToMsg by remember { mutableStateOf<PersonalMessage?>(null) }

    BackHandler(enabled = true) {
        when {
            isRecording -> {
                try { recorder?.stop() } catch (_: Exception) {}
                try { recorder?.release() } catch (_: Exception) {}
                recorder = null
                recordingFile?.delete()
                recordingFile = null
                isRecording = false
                isPaused = false
                recordTimeSeconds = 0
                liveAmplitudes = emptyList()
            }
            selectedMessages.isNotEmpty() -> selectedMessages = emptySet()
            fullscreenBitmap != null -> fullscreenBitmap = null
            activeVideoUrl != null -> activeVideoUrl = null
            showCameraScreen -> showCameraScreen = false
            showAttachmentMenu -> showAttachmentMenu = false
            showEmojiBar -> showEmojiBar = false
            pendingMediaUri != null -> {
                pendingMediaUri = null
                imageCaptionText = ""
            }
            replyingToMsg != null -> replyingToMsg = null
            showDeleteDialog -> showDeleteDialog = false
            showForwardDialog -> showForwardDialog = false
            showUserInfoSheet -> showUserInfoSheet = false
            failedActionMsg != null -> failedActionMsg = null
            else -> onBack()
        }
    }

    // Swipe to Reply & Highlight state
    var highlightedMsgId by remember { mutableStateOf<Int?>(highlightMessageId) }

    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var isInitialScrollDone by remember { mutableStateOf(false) }

    if (showClearChatConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearChatConfirmation = false },
            title = { Text("Clear this chat?") },
            text = {
                Text("Messages will disappear only from your account. The other person's messages and shared media will not be deleted.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showClearChatConfirmation = false
                    coroutineScope.launch {
                        try {
                            ApiClient.apiService.clearPersonalChat(otherUser.id)
                            clearedThroughMessageId = maxOf(
                                clearedThroughMessageId,
                                messages.filter { it.id > 0 }.maxOfOrNull { it.id } ?: 0
                            )
                            messages = messages.filter { it.id < 0 }
                            MessageCache.clearPersonal(currentUserId, otherUser.id)
                            ChatOfflineCache.clearPersonal(context, currentUserId, otherUser.id)
                            Toast.makeText(context, "Chat cleared for you", Toast.LENGTH_SHORT).show()
                        } catch (_: Exception) {
                            Toast.makeText(context, "Could not clear chat. Please try again.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { showClearChatConfirmation = false }) { Text("Cancel") }
            }
        )
    }

    // Scroll to highlighted message when opened from notification
    LaunchedEffect(highlightedMsgId, messages.size) {
        val targetId = highlightedMsgId
        if (targetId != null && targetId > 0) {
            val idx = messages.indexOfFirst { it.id == targetId }
            if (idx >= 0) {
                listState.animateScrollToItem(idx)
                kotlinx.coroutines.delay(2500)
                highlightedMsgId = null
            }
        }
    }

    // Instant position at bottom on chat opening (NO top-to-bottom scroll animation)
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            if (!isInitialScrollDone) {
                listState.scrollToItem(0)
                isInitialScrollDone = true
            } else if (highlightedMsgId == null && listState.firstVisibleItemIndex <= 2) {
                // Automatic positioning must not animate: IME and navigation-bar
                // resizing otherwise makes the entire conversation visibly jump.
                listState.scrollToItem(0)
            }
        }
    }

    // Restore pending transfers from Room DB + re-add temp bubbles for any upload still in-flight
    LaunchedEffect(Unit) {
        val dao = com.security.myapplication.transfer.TransferDatabase.getInstance(context).transferDao()
        val pending = dao.getIncompleteForChat("personal", otherUser.id, currentUserId)
        if (pending.isNotEmpty()) {
            val restoredBubbles = pending.map { rec ->
                val tempId = -kotlin.math.abs(rec.transferId.hashCode()).coerceAtLeast(1)
                val isText = rec.mediaType == "text"
                val bubbleText = if (isText) rec.caption else if (rec.caption.isNotBlank()) rec.caption else rec.fileName
                val mediaUrl = if (isText) null else "transfer://${rec.transferId}"
                if (rec.statusEnum == com.security.myapplication.transfer.TransferStatus.FAILED) {
                    failedMessageIds = failedMessageIds + tempId
                }
                val msgTimestamp = getIsoUtcTimestamp(if (rec.createdAt > 0) rec.createdAt else System.currentTimeMillis())
                com.security.myapplication.models.PersonalMessage(
                    id           = tempId,
                    sender_id    = currentUserId,
                    receiver_id  = otherUser.id,
                    text         = bubbleText,
                    timestamp    = msgTimestamp,
                    message_type = rec.mediaType,
                    media_url    = mediaUrl,
                    idempotency_key = rec.transferId,
                    is_delivered = false,
                    is_read      = false,
                    duration_sec = rec.durationSec
                )
            }
            val existingIds = messages.map { it.id }.toSet()
            val newBubbles = restoredBubbles.filter { it.id !in existingIds }
            if (newBubbles.isNotEmpty()) messages = messages + newBubbles
        }
    }

    // Fast, responsive polling for real-time 1-on-1 personal chat (2.5s interval)
    LaunchedEffect(currentUserId, otherUser.id) {
        if (AppConnectivity.refresh(context)) {
            try {
                blockStatus = ApiClient.apiService.getPersonalBlockStatus(currentUserId, otherUser.id)
                ApiClient.apiService.markPersonalChatRead(currentUserId, otherUser.id)
            } catch (_: Exception) {}
        }
        while (isActive) {
            val online = AppConnectivity.refresh(context)
            if (online) try {
                blockStatus = ApiClient.apiService.getPersonalBlockStatus(currentUserId, otherUser.id)
                val afterId = if (hasCompletedInitialServerSync) {
                    messages.asSequence().filter { it.id > 0 }.maxOfOrNull { it.id }
                } else null
                val fetched = ApiClient.apiService.getPersonalMessages(
                    currentUserId,
                    otherUser.id,
                    afterId = afterId,
                    limit = PERSONAL_CHAT_PAGE_SIZE
                )
                hasCompletedInitialServerSync = true
                // Preserve in-flight temp messages (negative IDs)
                val pending = messages.filter { m ->
                    if (m.id >= 0) return@filter false
                    val tid = m.idempotency_key?.takeIf { it.isNotBlank() }
                        ?: m.media_url?.takeIf { it.startsWith("transfer://") }?.removePrefix("transfer://")
                    if (tid != null) {
                        TransferStateHolder.statusFor(tid) != TransferStatus.COMPLETED &&
                            !fetched.any { f -> f.idempotency_key == tid }
                    } else {
                        !fetched.any { f -> f.sender_id == currentUserId && f.text == m.text && f.timestamp.orEmpty() >= m.timestamp.orEmpty() }
                    }
                }
                val newlyFailed = pending.filter { m ->
                    val tid = m.idempotency_key?.takeIf { it.isNotBlank() }
                        ?: m.media_url?.takeIf { it.startsWith("transfer://") }?.removePrefix("transfer://")
                    tid != null && TransferStateHolder.statusFor(tid) == TransferStatus.FAILED
                }.map { it.id }.toSet()
                if (newlyFailed.isNotEmpty() && !failedMessageIds.containsAll(newlyFailed)) {
                    failedMessageIds = failedMessageIds + newlyFailed
                }
                val fetchedIds = fetched.mapTo(mutableSetOf()) { it.id }
                val retainedOlder = messages.filter {
                    it.id > clearedThroughMessageId && it.id > 0 && it.id !in fetchedIds
                }
                val merged = (retainedOlder + fetched + pending).distinctBy { it.id }
                if (merged != messages) {
                    messages = merged
                    // Only cache verified server messages (id > 0) to avoid ghost bubbles
                    MessageCache.savePersonal(currentUserId, otherUser.id, merged.filter { it.id > 0 })
                    try { ApiClient.apiService.markPersonalChatRead(currentUserId, otherUser.id) } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
            kotlinx.coroutines.delay(if (online) 2500 else 5000)
        }
    }

    // Online heartbeat - tells server this user is active (every 5s while in chat)
    LaunchedEffect(currentUserId) {
        while (isActive) {
            if (AppConnectivity.refresh(context)) {
                try { ApiClient.apiService.pingOnline(currentUserId) } catch (_: Exception) {}
            }
            kotlinx.coroutines.delay(5000)
        }
    }

    // Live wave & timer coroutines
    LaunchedEffect(isRecording, isPaused) {
        if (isRecording && !isPaused) {
            while (isRecording && !isPaused) {
                kotlinx.coroutines.delay(80)
                val amp = try { recorder?.maxAmplitude ?: 0 } catch (_: Exception) { 0 }
                val scaledHeight = if (amp < 800) 4 else (5 + (amp.coerceAtMost(16000) / 16000f * 23)).toInt()
                liveAmplitudes = (liveAmplitudes + scaledHeight).takeLast(26)
            }
        }
    }

    LaunchedEffect(isRecording, isPaused) {
        if (isRecording && !isPaused) {
            while (isRecording && !isPaused) {
                kotlinx.coroutines.delay(1000)
                recordTimeSeconds++
            }
        }
    }

    // Runtime permission launcher for RECORD_AUDIO
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startPCVoiceRecording(context) { rec, file ->
                recorder = rec
                recordingFile = file
                isRecording = true
            }
        } else {
            Toast.makeText(context, "Microphone permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    // Gallery picker - opens GALLERY (photos/videos), not file manager
    val mediaLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        uri?.let {
            val mime = context.contentResolver.getType(it) ?: ""
            if (!mime.startsWith("video/")) {
                pendingIsVideo = false
                pendingMediaUri = it
                imageCaptionText = ""
            } else {
                // Metadata lookup stays off the picker callback's Main thread.
                coroutineScope.launch(Dispatchers.IO) {
                    val size = TransferManager.sourceSizeBytes(context, uri = it)
                    withContext(Dispatchers.Main) {
                        if (size > TransferManager.MAX_VIDEO_UPLOAD_BYTES) {
                            Toast.makeText(context, TransferManager.VIDEO_UPLOAD_LIMIT_MESSAGE, Toast.LENGTH_LONG).show()
                        } else {
                            pendingIsVideo = true
                            pendingMediaUri = it
                            imageCaptionText = ""
                        }
                    }
                }
            }
        }
    }

    // Camera permission launcher (for CameraX runtime permission)
    val cameraPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) showCameraScreen = true
        else Toast.makeText(context, "Camera permission required", Toast.LENGTH_SHORT).show()
    }

    fun launchCamera() {
        val hasPerm = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasPerm) {
            cameraPermLauncher.launch(android.Manifest.permission.CAMERA)
        } else {
            showCameraScreen = true
        }
    }

    // File picker strictly filtered for documents, PDFs, Office files, and Archives
    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { pickedUri ->
            val cursor = context.contentResolver.query(pickedUri, null, null, null, null)
            val originalName = cursor?.use {
                val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                it.moveToFirst()
                if (idx >= 0) it.getString(idx) else null
            } ?: "document_${System.currentTimeMillis()}.pdf"
            cursor?.close()

            val ext = originalName.substringAfterLast(".", "").lowercase()
            val isAudio = ext in listOf("mp3", "wav", "m4a", "aac", "ogg", "opus", "flac", "wma", "amr", "3gp")
            val msgType = if (isAudio) "audio" else "file"
            val nowIso = getIsoUtcTimestamp()

            var transferId = ""
            transferId = TransferManager.enqueueUpload(
                context   = context,
                chatType  = "personal",
                senderId  = currentUserId,
                peerId    = otherUser.id,
                mediaType = msgType,
                uri       = pickedUri,
                fileName  = originalName,
                caption   = originalName,
                onSuccess = { newMsg ->
                    messages = messages.mapNotNull { m ->
                        if (m.media_url == "transfer://$transferId" || m.id == -kotlin.math.abs(transferId.hashCode())) {
                            if (messages.any { it.id == newMsg.id }) null else newMsg
                        } else m
                    }
                }
            )

            val tempMsg = PersonalMessage(
                id           = -kotlin.math.abs(transferId.hashCode()),
                sender_id    = currentUserId,
                receiver_id  = otherUser.id,
                text         = originalName,
                timestamp    = nowIso,
                message_type = msgType,
                media_url    = "transfer://$transferId",
                is_delivered = false,
                is_read      = false
            )
            messages = messages + tempMsg
            coroutineScope.launch { listState.scrollToItem(0) }
        }
    }

    // Dedicated native Audio/Music picker - strictly opens system File Manager Audio Browser
    val audioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { pickedUri ->
            val cursor = context.contentResolver.query(pickedUri, null, null, null, null)
            val originalName = cursor?.use {
                val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                it.moveToFirst()
                if (idx >= 0) it.getString(idx) else null
            } ?: "audio_${System.currentTimeMillis()}.mp3"
            cursor?.close()

            val nowIso = getIsoUtcTimestamp()

            var transferId = ""
            transferId = TransferManager.enqueueUpload(
                context   = context,
                chatType  = "personal",
                senderId  = currentUserId,
                peerId    = otherUser.id,
                mediaType = "audio",
                uri       = pickedUri,
                fileName  = originalName,
                caption   = originalName,
                onSuccess = { newMsg ->
                    messages = messages.mapNotNull { m ->
                        if (m.media_url == "transfer://$transferId" || m.id == -kotlin.math.abs(transferId.hashCode())) {
                            if (messages.any { it.id == newMsg.id }) null else newMsg
                        } else m
                    }
                }
            )

            val tempMsg = PersonalMessage(
                id           = -kotlin.math.abs(transferId.hashCode()),
                sender_id    = currentUserId,
                receiver_id  = otherUser.id,
                text         = originalName,
                timestamp    = nowIso,
                message_type = "audio",
                media_url    = "transfer://$transferId",
                is_delivered = false,
                is_read      = false
            )
            messages = messages + tempMsg
            coroutineScope.launch { listState.scrollToItem(0) }
        }
    }

    fun launchPCAudioPicker() {
        val audioMimes = arrayOf(
            "audio/*", "audio/mpeg", "audio/mp3", "audio/wav", "audio/x-wav",
            "audio/m4a", "audio/x-m4a", "audio/mp4", "audio/aac", "audio/ogg",
            "audio/opus", "audio/flac", "audio/x-flac", "audio/amr", "audio/3gpp",
            "audio/midi", "audio/x-ms-wma", "audio/webm"
        )
        try {
            audioLauncher.launch(audioMimes)
        } catch (_: Exception) {}
    }

    fun sendMessage() {
        if (isSending || isChatBlocked) return
        val text = messageText.trim()
        if (text.isEmpty()) return
        isSending = true
        val replyPrefix = replyingToMsg?.let { r ->
            val preview = when (r.message_type) {
                "image" -> "Photo"
                "voice" -> "Voice Note"
                "file"  -> "Document"
                else    -> r.text.lines().first().take(40)
            }
            "[REPLY:${r.id}|${if (r.sender_id == currentUserId) "You" else otherUser.name}|${r.message_type}|$preview]\n"
        } ?: ""
        val fullText = replyPrefix + text
        messageText = ""
        replyingToMsg = null
        val nowIso = getIsoUtcTimestamp()
        val transferId = java.util.UUID.randomUUID().toString()
        val txtTempId = -kotlin.math.abs(transferId.hashCode()).coerceAtLeast(1)
        val tempMsg = PersonalMessage(
            id = txtTempId,
            sender_id = currentUserId,
            receiver_id = otherUser.id,
            text = fullText,
            timestamp = nowIso,
            message_type = "text",
            media_url = null,
            idempotency_key = transferId,
            is_delivered = false,
            is_read = false
        )
        messages = messages + tempMsg
        coroutineScope.launch {
            listState.scrollToItem(0)
        }
        com.security.myapplication.transfer.TransferManager.enqueueTextUpload(
            context = context,
            chatType = "personal",
            senderId = currentUserId,
            peerId = otherUser.id,
            text = fullText,
            customTransferId = transferId,
            onSuccess = { sent ->
                messages = messages.map { if (it.id == txtTempId) sent else it }
                failedMessageIds = failedMessageIds - txtTempId
                isSending = false
            }
        )
    }

    // -- CameraX In-App Camera Screen Overlay ---------------------------------
    if (showCameraScreen) {
        CameraScreen(
            onPhotoCaptured = { file ->
                showCameraScreen = false
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", file
                )
                pendingIsVideo = false
                pendingMediaUri = uri
                imageCaptionText = ""
            },
            onVideoCaptured = { file ->
                showCameraScreen = false
                if (file.length() > TransferManager.MAX_VIDEO_UPLOAD_BYTES) {
                    Toast.makeText(context, TransferManager.VIDEO_UPLOAD_LIMIT_MESSAGE, Toast.LENGTH_LONG).show()
                } else {
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", file
                    )
                    pendingIsVideo = true
                    pendingMediaUri = uri
                    imageCaptionText = ""
                }
            },
            onClose = { showCameraScreen = false }
        )
        return
    }

    // Fullscreen Image Viewer (100% Screen Edge-to-Edge with Back Button & Pinch Zoom)
    fullscreenBitmap?.let { bmp ->
        Dialog(
            onDismissRequest = { fullscreenBitmap = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            var scale by remember { mutableStateOf(1f) }
            var offsetX by remember { mutableStateOf(0f) }
            var offsetY by remember { mutableStateOf(0f) }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Full Image",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 50.dp)
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                if (scale > 1f) {
                                    offsetX += pan.x
                                    offsetY += pan.y
                                } else {
                                    offsetX = 0f
                                    offsetY = 0f
                                }
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = {
                                    if (scale > 1f) {
                                        scale = 1f
                                        offsetX = 0f
                                        offsetY = 0f
                                    } else {
                                        scale = 2.5f
                                    }
                                }
                            )
                        }
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY
                        ),
                    contentScale = ContentScale.Fit
                )

                // Top Header Bar with Back Button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { fullscreenBitmap = null }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Photo",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    // In-App Video Player Dialog
    activeVideoUrl?.let { url ->
        FullscreenInAppVideoPlayer(videoUrlStr = url, onClose = { activeVideoUrl = null })
    }

    // -- WhatsApp-Style Attachment Menu (Bottom Sheet) ------------------------
    if (showAttachmentMenu) {
        ModalBottomSheet(
            onDismissRequest = { showAttachmentMenu = false },
            containerColor = Color(0xFF14112E),
            dragHandle = {
                Box(
                    modifier = Modifier
                        .padding(top = 10.dp, bottom = 6.dp)
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.2f))
                )
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                Text(
                    text = "Share Content",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp, start = 4.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    // 1. Documents & Files ONLY
                    PCAttachmentItem(
                        icon = Icons.Outlined.Description,
                        label = "Document",
                        gradient = listOf(Color(0xFF5E35B1), Color(0xFF7E57C2)),
                        onClick = {
                            showAttachmentMenu = false
                            fileLauncher.launch(
                                arrayOf(
                                    "application/pdf",
                                    "application/msword",
                                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                    "application/vnd.ms-excel",
                                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                    "application/vnd.ms-powerpoint",
                                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                                    "text/plain",
                                    "application/zip",
                                    "application/x-zip-compressed",
                                    "application/x-rar-compressed",
                                    "application/octet-stream"
                                )
                            )
                        }
                    )

                    // 2. Audio & Music ONLY (Dedicated Audio Picker)
                    PCAttachmentItem(
                        icon = Icons.Outlined.Headphones,
                        label = "Audio",
                        gradient = listOf(Color(0xFFE65100), Color(0xFFFF9800)),
                        onClick = {
                            showAttachmentMenu = false
                            launchPCAudioPicker()
                        }
                    )

                    // 3. Gallery (Photos & Videos ONLY)
                    PCAttachmentItem(
                        icon = Icons.Outlined.Collections,
                        label = "Gallery",
                        gradient = listOf(Color(0xFFC2185B), Color(0xFFE91E63)),
                        onClick = {
                            showAttachmentMenu = false
                            val intent = android.content.Intent(android.content.Intent.ACTION_PICK).apply {
                                type = "*/*"
                                putExtra(android.content.Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                            }
                            mediaLauncher.launch(intent)
                        }
                    )

                    // 4. Camera (Photo / Video Capture)
                    PCAttachmentItem(
                        icon = Icons.Outlined.PhotoCamera,
                        label = "Camera",
                        gradient = listOf(Color(0xFF00897B), Color(0xFF26A69A)),
                        onClick = {
                            showAttachmentMenu = false
                            launchCamera()
                        }
                    )
                }
                Spacer(Modifier.height(14.dp))
            }
        }
    }

    // -- Universal Media Caption / Send Preview Dialog (WhatsApp Style) -----
    pendingMediaUri?.let { uri ->
        val isVideoPreview = pendingIsVideo
        Dialog(
            onDismissRequest = { if (!isUploadingImage) pendingMediaUri = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .imePadding()  // Shrink entire dialog when keyboard opens
            ) {
                Column(modifier = Modifier.fillMaxSize()) {

                    // -- Top Bar ----------------------------------------------
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { pendingMediaUri = null },
                            enabled = !isUploadingImage,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (isVideoPreview) "Send Video" else "Send Photo",
                            color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold
                        )
                    }

                    // -- Preview Area -----------------------------------------
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isVideoPreview) {
                            // -- Real video player (TextureView + MediaPlayer) ----
                            var player by remember { mutableStateOf<MediaPlayer?>(null) }
                            var isVidPlaying by remember { mutableStateOf(true) }

                            var videoAspectRatio by remember { mutableFloatStateOf(9f / 16f) }
                            LaunchedEffect(uri) {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    try {
                                        val retriever = android.media.MediaMetadataRetriever()
                                        retriever.setDataSource(context, uri)
                                        val wStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                                        val hStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                                        val rotStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                                        retriever.release()
                                        val w = wStr?.toFloatOrNull() ?: 16f
                                        val h = hStr?.toFloatOrNull() ?: 9f
                                        val rot = rotStr?.toIntOrNull() ?: 0
                                        val ratio = if (rot == 90 || rot == 270) h / w else w / h
                                        if (ratio > 0f) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { videoAspectRatio = ratio }
                                    } catch (_: Exception) {}
                                }
                            }

                            DisposableEffect(uri) {
                                onDispose {
                                    try {
                                        player?.stop()
                                        player?.release()
                                    } catch (_: Exception) {}
                                    player = null
                                }
                            }

                            val isFrontVid = remember(uri) { uri.toString().contains("front_vid_") || uri.toString().contains("front_") }

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .aspectRatio(videoAspectRatio, matchHeightConstraintsFirst = true),
                                contentAlignment = Alignment.Center
                            ) {
                                AndroidView(
                                    factory = { ctx ->
                                        TextureView(ctx).apply {
                                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                                    try {
                                                        val mp = MediaPlayer().apply {
                                                            setDataSource(ctx, uri)
                                                            setSurface(Surface(st))
                                                            isLooping = false
                                                            setOnPreparedListener {
                                                                try { it.start() } catch (_: Exception) {}
                                                            }
                                                            prepareAsync()
                                                        }
                                                        player = mp
                                                    } catch (_: Exception) {}
                                                }
                                                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                                                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                                    try {
                                                        player?.stop()
                                                        player?.release()
                                                    } catch (_: Exception) {}
                                                    player = null
                                                    return true
                                                }
                                                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )

                                // Play/Pause tap overlay
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clickable(
                                            indication = null,
                                            interactionSource = remember { MutableInteractionSource() }
                                        ) {
                                            player?.let { mp ->
                                                try {
                                                    if (mp.isPlaying) { mp.pause(); isVidPlaying = false }
                                                    else { mp.start(); isVidPlaying = true }
                                                } catch (_: Exception) {}
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    androidx.compose.animation.AnimatedVisibility(
                                        visible = !isVidPlaying,
                                        enter = fadeIn(),
                                        exit = fadeOut()
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(68.dp)
                                                .clip(CircleShape)
                                                .background(Color.Black.copy(alpha = 0.55f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.PlayArrow,
                                                contentDescription = "Play",
                                                tint = Color.White,
                                                modifier = Modifier.size(38.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            // -- Photo preview (Asynchronous Background Decoding, Zero UI Freeze) --
                            var previewBitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
                            var isDecodingBitmap by remember(uri) { mutableStateOf(true) }

                            LaunchedEffect(uri) {
                                isDecodingBitmap = true
                                val bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    MediaManager.decodeHighQualityBitmap(context, uri)
                                }
                                previewBitmap = bmp
                                isDecodingBitmap = false
                            }

                            if (previewBitmap != null) {
                                Image(
                                    bitmap = previewBitmap!!.asImageBitmap(),
                                    contentDescription = "Preview",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                            } else if (isDecodingBitmap) {
                                CircularProgressIndicator(color = PCPurple, modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
                            }
                        }
                    }

                    // -- Caption Input & Send Row -----------------------------
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF100E26))
                            .navigationBarsPadding()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(24.dp))
                                .background(PCInputBg)
                                .border(1.dp, PCBorder, RoundedCornerShape(24.dp))
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            BasicTextField(
                                value = imageCaptionText,
                                onValueChange = { imageCaptionText = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = false,
                                maxLines = 3,
                                cursorBrush = SolidColor(PCPurple),
                                textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                                decorationBox = { inner ->
                                    if (imageCaptionText.isEmpty()) Text(
                                        if (isVideoPreview) "Add a caption..." else "Add a caption...",
                                        color = PCSubText, fontSize = 14.sp
                                    )
                                    inner()
                                }
                            )
                        }

                        Spacer(Modifier.width(10.dp))

                        Box(
                            modifier = Modifier
                                .size(50.dp)
                                .clip(CircleShape)
                                .background(Brush.linearGradient(listOf(PCPurple, PCPurpleDk)))
                                .clickable(enabled = !isUploadingImage) {
                                    if (isUploadingImage) return@clickable
                                    isUploadingImage = true
                                    try {
                                        val caption = imageCaptionText
                                        val nowIso = getIsoUtcTimestamp()
                                        val msgType = if (isVideoPreview) "video" else "image"
                                        val fallbackName = if (isVideoPreview) "video_${System.currentTimeMillis()}.mp4" else "image_${System.currentTimeMillis()}.jpg"

                                        // Pre-cache thumbnail/bitmap immediately so preview NEVER disappears during upload
                                        if (isVideoPreview) {
                                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                                try {
                                                    val retriever = android.media.MediaMetadataRetriever()
                                                    retriever.setDataSource(context, uri)
                                                    val frame = retriever.getFrameAtTime(0)
                                                    retriever.release()
                                                    if (frame != null) {
                                                        MediaBitmapCache.put("thumb_$uri", frame)
                                                        MediaBitmapCache.put("thumb_$fallbackName", frame)
                                                    }
                                                } catch (_: Exception) {}
                                            }
                                        } else {
                                            // Keep picker callbacks lightweight; decode on IO.
                                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                                val bmp = MediaBitmapCache.get(uri.toString())
                                                    ?: MediaManager.decodeHighQualityBitmap(context, uri)
                                                if (bmp != null) {
                                                    MediaBitmapCache.put(uri.toString(), bmp)
                                                    MediaBitmapCache.put(fallbackName, bmp)
                                                }
                                            }
                                        }

                                        var transferId = ""
                                        transferId = TransferManager.enqueueUpload(
                                            context   = context,
                                            chatType  = "personal",
                                            senderId  = currentUserId,
                                            peerId    = otherUser.id,
                                            mediaType = msgType,
                                            uri       = uri,
                                            fileName  = fallbackName,
                                            caption   = caption,
                                            onSuccess = { newMsg ->
                                                if (isVideoPreview) {
                                                    MediaBitmapCache.get("thumb_$uri")?.let {
                                                        MediaBitmapCache.put("thumb_${newMsg.media_url}", it)
                                                    }
                                                } else {
                                                    MediaBitmapCache.get(uri.toString())?.let {
                                                        MediaBitmapCache.put(newMsg.media_url ?: "", it)
                                                    }
                                                }
                                                messages = messages.mapNotNull { m ->
                                                    if (m.media_url == "transfer://$transferId" || m.id == -kotlin.math.abs(transferId.hashCode())) {
                                                        if (messages.any { it.id == newMsg.id }) null else newMsg
                                                    } else m
                                                }
                                            }
                                        )

                                        if (isVideoPreview) {
                                            MediaBitmapCache.get("thumb_$uri")?.let {
                                                MediaBitmapCache.put("thumb_transfer://$transferId", it)
                                            }
                                        } else {
                                            MediaBitmapCache.get(uri.toString())?.let {
                                                MediaBitmapCache.put("transfer://$transferId", it)
                                            }
                                        }

                                        val tempMsg = PersonalMessage(
                                            id           = -kotlin.math.abs(transferId.hashCode()),
                                            sender_id    = currentUserId,
                                            receiver_id  = otherUser.id,
                                            text         = if (caption.isNotBlank()) caption else if (isVideoPreview) "Video" else "Photo",
                                            timestamp    = nowIso,
                                            message_type = msgType,
                                            media_url    = "transfer://$transferId",
                                            is_delivered = false,
                                            is_read      = false
                                        )
                                        messages = messages + tempMsg
                                        pendingMediaUri = null
                                        imageCaptionText = ""
                                    } finally {
                                        isUploadingImage = false
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isUploadingImage) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                            } else {
                                Icon(Icons.Default.Send, contentDescription = "Send", tint = Color.White, modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    // -- Main Screen Layout --------------------------------------------
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PCBg)
    ) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {

            // -- TopBar (Normal or Contextual Multi-Selection Bar) ---------
            if (selectedMessages.isNotEmpty()) {
                val targets = selectedMessages.toList()
                val allPinned = targets.all { it.is_pinned }
                val singleSelected = targets.singleOrNull()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1B163B))
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { selectedMessages = emptySet() }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }

                    Spacer(Modifier.width(8.dp))

                    Text(
                        text = "${selectedMessages.size}",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(Modifier.weight(1f))

                    // Pin / Unpin Button (bulk or single)
                    IconButton(
                        onClick = {
                            val toProcess = targets
                            val shouldUnpin = allPinned
                            selectedMessages = emptySet()
                            coroutineScope.launch {
                                for (msg in toProcess) {
                                    try {
                                        if (shouldUnpin) {
                                            ApiClient.apiService.unpinPersonalMessage(msg.id)
                                            messages = messages.map { if (it.id == msg.id) it.copy(is_pinned = false) else it }
                                        } else {
                                            ApiClient.apiService.pinPersonalMessage(msg.id)
                                            messages = messages.map { if (it.id == msg.id) it.copy(is_pinned = true) else it }
                                        }
                                    } catch (_: Exception) {
                                        messages = messages.map { if (it.id == msg.id) it.copy(is_pinned = !shouldUnpin) else it }
                                    }
                                }
                                Toast.makeText(context, if (shouldUnpin) "Message(s) unpinned" else "Message(s) pinned", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PushPin,
                            contentDescription = if (allPinned) "Unpin" else "Pin",
                            tint = if (allPinned) Color(0xFFFFB300) else Color.White
                        )
                    }

                    // Delete Button (Bulk)
                    IconButton(
                        onClick = { showDeleteDialog = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = "Delete",
                            tint = Color(0xFFFF5252)
                        )
                    }

                    // Forward Button (WhatsApp style right forward arrow) - supports all types of messages
                    IconButton(
                        onClick = { showForwardDialog = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Forward,
                            contentDescription = "Forward",
                            tint = Color.White
                        )
                    }

                    // Copy Button (for text messages)
                    val textMsgs = targets.filter { it.message_type == "text" && it.text.isNotBlank() && !it.is_deleted }
                    if (textMsgs.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                val combinedText = textMsgs.joinToString("\n") { 
                                    it.text.replace("[FORWARDED]\n", "").replace("[FORWARDED]", "").trim() 
                                }
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("Copied Text", combinedText)
                                clipboard?.setPrimaryClip(clip)
                                Toast.makeText(context, if (textMsgs.size > 1) "${textMsgs.size} messages copied" else "Message copied", Toast.LENGTH_SHORT).show()
                                selectedMessages = emptySet()
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ContentCopy,
                                contentDescription = "Copy",
                                tint = Color.White
                            )
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF100E26))
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }

                    Spacer(Modifier.width(4.dp))

                    // Clickable area: avatar + name (opens user info sheet)
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(
                                indication = null,
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                            ) { showUserInfoSheet = true },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                    Box(modifier = Modifier.size(42.dp)) {
                        if (!otherUser.profile_pic.isNullOrEmpty()) {
                            val bmp by produceState<Bitmap?>(null, otherUser.profile_pic) {
                                value = withContext(Dispatchers.Default) {
                                    val pic = otherUser.profile_pic
                                    val cacheKey = "avatar_" + pic.hashCode()
                                    MediaBitmapCache.get(cacheKey) ?: run {
                                        try {
                                            val bytes = Base64.decode(pic, Base64.DEFAULT)
                                            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                            if (decoded != null) MediaBitmapCache.put(cacheKey, decoded)
                                            decoded
                                        } catch (_: Exception) { null }
                                    }
                                }
                            }
                            val avatar = bmp
                            if (avatar != null) {
                                Image(
                                    bitmap = avatar.asImageBitmap(),
                                    contentDescription = otherUser.name,
                                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                PCDefaultAvatar(otherUser.name)
                            }
                        } else {
                            PCDefaultAvatar(otherUser.name)
                        }
                        Box(
                            modifier = Modifier
                                .size(11.dp)
                                .align(Alignment.BottomEnd)
                                .clip(CircleShape)
                                .background(PCGreen)
                                .border(1.5.dp, PCBg, CircleShape)
                        )
                    }

                    Spacer(Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            otherUser.name,
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (otherUser.role == "Teacher") "Faculty • Online" else "Student • Online",
                            color = PCPurpleLt,
                            fontSize = 12.sp
                        )
                    }
                    } // end clickable Row

                    // -- Right-side action icons (circular containers like reference image)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1B1740))
                                .clickable {
                                    Toast.makeText(context, "Audio call feature coming soon", Toast.LENGTH_SHORT).show()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Outlined.Call, contentDescription = "Call", tint = Color.White, modifier = Modifier.size(18.dp))
                        }

                        Spacer(Modifier.width(6.dp))

                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1B1740))
                                .clickable {
                                    Toast.makeText(context, "Video call feature coming soon", Toast.LENGTH_SHORT).show()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Outlined.Videocam, contentDescription = "Video", tint = Color.White, modifier = Modifier.size(19.dp))
                        }

                        Spacer(Modifier.width(6.dp))

                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1B1740))
                                .clickable { showChatOverflowMenu = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = Color.White, modifier = Modifier.size(18.dp))
                            DropdownMenu(
                                expanded = showChatOverflowMenu,
                                onDismissRequest = { showChatOverflowMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Clear chat") },
                                    onClick = {
                                        showChatOverflowMenu = false
                                        showClearChatConfirmation = true
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // -- Pinned Messages Banner (WhatsApp Style + Direct Unpin Button) ---
            val visibleMessages = remember(messages, deletedForMeIds, clearedThroughMessageId) {
                messages.filter {
                    it.id !in deletedForMeIds && (it.id < 0 || it.id > clearedThroughMessageId)
                }
            }
            val pinnedMessages = remember(visibleMessages) {
                visibleMessages.filter { it.is_pinned && !it.is_deleted }.sortedByDescending { it.id }
            }

            // -- Messages (Deterministic Canonical Chronological Order) -----------------------
            val chronologicallySortedMessages = remember(visibleMessages) {
                visibleMessages.distinctBy { it.id }.sortedWith(
                    compareBy<PersonalMessage> { parseUniversalTimestampMillis(it.timestamp) }
                        .thenBy { if (it.id > 0) it.id.toLong() else Long.MAX_VALUE - kotlin.math.abs(it.id.toLong()) }
                )
            }
            val reversedMessages = remember(chronologicallySortedMessages) { chronologicallySortedMessages.reversed() }

            if (pinnedMessages.isNotEmpty()) {
                val safePinnedIdx = currentPinnedIndex % pinnedMessages.size
                val activePinned = pinnedMessages[safePinnedIdx]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF191436))
                        .border(0.8.dp, Color(0xFF2C235A), RoundedCornerShape(10.dp))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF2E1F60)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PushPin,
                            contentDescription = "Pinned",
                            tint = PCPurpleLt,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                val targetIdx = reversedMessages.indexOfFirst { it.id == activePinned.id }
                                if (targetIdx >= 0) {
                                    coroutineScope.launch {
                                        listState.animateScrollToItem(targetIdx)
                                        highlightedMsgId = activePinned.id
                                        kotlinx.coroutines.delay(1600)
                                        if (highlightedMsgId == activePinned.id) highlightedMsgId = null
                                    }
                                }
                                if (pinnedMessages.size > 1) {
                                    currentPinnedIndex = (currentPinnedIndex + 1) % pinnedMessages.size
                                }
                            }
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Pinned message",
                                color = PCPurpleLt,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (pinnedMessages.size > 1) {
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "(${safePinnedIdx + 1}/${pinnedMessages.size})",
                                    color = PCSubText,
                                    fontSize = 10.5.sp
                                )
                            }
                        }
                        val previewSnippet = when (activePinned.message_type) {
                            "image" -> "Photo"
                            "video" -> "Video"
                            "voice" -> "Voice Note"
                            "audio" -> "Audio"
                            "file"  -> "Document"
                            else    -> if (activePinned.is_deleted) "This message was deleted" else (activePinned.text.lines().firstOrNull() ?: activePinned.text)
                        }
                        val sName = if (activePinned.sender_id == currentUserId) "You" else otherUser.name
                        Text(
                            text = "$sName: $previewSnippet",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 11.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Direct Unpin Button on the banner (Cross Icon)
                    IconButton(
                        onClick = {
                            val msgToUnpin = activePinned
                            coroutineScope.launch {
                                try {
                                    ApiClient.apiService.unpinPersonalMessage(msgToUnpin.id)
                                    messages = messages.map { if (it.id == msgToUnpin.id) it.copy(is_pinned = false) else it }
                                    Toast.makeText(context, "Message unpinned", Toast.LENGTH_SHORT).show()
                                } catch (_: Exception) {
                                    messages = messages.map { if (it.id == msgToUnpin.id) it.copy(is_pinned = false) else it }
                                    Toast.makeText(context, "Message unpinned", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Unpin",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // -- Delete Confirmation Dialog (WhatsApp Style - Multi-Delete) -------
            if (showDeleteDialog && selectedMessages.isNotEmpty()) {
                val targets = selectedMessages.toList()
                val eligibleForEveryone = targets.filter { it.sender_id == currentUserId && !it.is_deleted }
                val canDeleteForEveryone = eligibleForEveryone.isNotEmpty()

                AlertDialog(
                    onDismissRequest = {
                        showDeleteDialog = false
                        selectedMessages = emptySet()
                    },
                    containerColor = Color(0xFF181436),
                    title = {
                        Text(
                            text = if (targets.size > 1) "Delete ${targets.size} messages?" else "Delete message?",
                            color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold
                        )
                    },
                    text = {
                        Text(
                            text = if (canDeleteForEveryone) 
                                "You can delete selected message(s) for everyone or only for yourself." 
                            else 
                                "Delete selected message(s) from your chat history?",
                            color = PCSubText,
                            fontSize = 13.5.sp
                        )
                    },
                    confirmButton = {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.End
                        ) {
                            if (canDeleteForEveryone) {
                                TextButton(
                                    onClick = {
                                        showDeleteDialog = false
                                        val toDelete = eligibleForEveryone
                                        selectedMessages = emptySet()
                                        coroutineScope.launch {
                                            for (msgToDelete in toDelete) {
                                                try {
                                                    ApiClient.apiService.deletePersonalMessageForEveryone(
                                                        msgToDelete.id,
                                                        DeleteEveryoneRequest(currentUserId)
                                                    )
                                                } catch (_: Exception) {}
                                            }
                                            val deletedIds = toDelete.map { it.id }.toSet()
                                            messages = messages.map {
                                                if (it.id in deletedIds) it.copy(
                                                    is_deleted = true,
                                                    text = "This message was deleted",
                                                    media_url = null,
                                                    is_pinned = false
                                                ) else it
                                            }
                                            Toast.makeText(context, "Deleted for everyone", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                ) {
                                    Text("Delete for everyone", color = Color(0xFFFF5252), fontWeight = FontWeight.Bold)
                                }
                            }

                            TextButton(
                                onClick = {
                                    showDeleteDialog = false
                                    val toDeleteIds = targets.map { it.id }.toSet()
                                    selectedMessages = emptySet()
                                    targets.forEach { msg ->
                                        val tid = msg.media_url?.takeIf { it.startsWith("transfer://") }?.removePrefix("transfer://")
                                            ?: msg.idempotency_key
                                        if (tid != null) {
                                            com.security.myapplication.transfer.TransferManager.deleteTransfer(context, tid)
                                        }
                                    }
                                    val newSet = deletedForMeIds + toDeleteIds
                                    deletedForMeIds = newSet
                                    messages = messages.filter { it.id !in toDeleteIds }
                                    failedMessageIds = failedMessageIds - toDeleteIds
                                    chatPrefs.edit().putStringSet("deleted_for_me_${currentUserId}_p_${otherUser.id}", newSet.map { it.toString() }.toSet()).apply()
                                    Toast.makeText(context, if (toDeleteIds.size > 1) "${toDeleteIds.size} messages deleted for you" else "Deleted for you", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Text("Delete for me", color = PCPurpleLt, fontWeight = FontWeight.SemiBold)
                            }

                            TextButton(
                                onClick = {
                                    showDeleteDialog = false
                                    selectedMessages = emptySet()
                                }
                            ) {
                                Text("Cancel", color = PCSubText)
                            }
                        }
                    },
                    dismissButton = null
                )
            }

            // Failed Message Action Dialog (Retry or Delete/Dismiss Ghost Bubble)
            failedActionMsg?.let { fMsg ->
                AlertDialog(
                    onDismissRequest = { failedActionMsg = null },
                    containerColor = Color(0xFF181436),
                    title = {
                        Text(
                            text = "Message Not Sent",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    text = {
                        Text(
                            text = "\"${fMsg.text}\"\n\nWould you like to retry sending or delete this message?",
                            color = PCSubText,
                            fontSize = 13.5.sp
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                failedActionMsg = null
                                failedMessageIds = failedMessageIds - fMsg.id
                                val tid = fMsg.media_url?.takeIf { it.startsWith("transfer://") }?.removePrefix("transfer://")
                                    ?: fMsg.idempotency_key
                                if (tid != null) {
                                    com.security.myapplication.transfer.TransferManager.retry(context, tid)
                                } else {
                                    coroutineScope.launch {
                                        try {
                                            val retryKey = fMsg.idempotency_key ?: "pm_${currentUserId}_${otherUser.id}_${fMsg.id}"
                                            val sent = ApiClient.apiService.sendPersonalMessage(
                                                idempotencyKey = retryKey,
                                                request = PersonalMessageCreateRequest(
                                                    sender_id = currentUserId,
                                                    receiver_id = otherUser.id,
                                                    text = fMsg.text,
                                                    idempotency_key = retryKey
                                                )
                                            )
                                            messages = messages.map { if (it.id == fMsg.id) sent else it }
                                        } catch (_: Exception) {
                                            failedMessageIds = failedMessageIds + fMsg.id
                                        }
                                    }
                                }
                            }
                        ) {
                            Text("Retry", color = PCPurpleLt, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                failedActionMsg = null
                                val tid = fMsg.media_url?.takeIf { it.startsWith("transfer://") }?.removePrefix("transfer://")
                                    ?: fMsg.idempotency_key
                                if (tid != null) {
                                    com.security.myapplication.transfer.TransferManager.deleteTransfer(context, tid)
                                }
                                messages = messages.filter { it.id != fMsg.id }
                                failedMessageIds = failedMessageIds - fMsg.id
                            }
                        ) {
                            Text("Delete", color = Color(0xFFFF5252), fontWeight = FontWeight.Bold)
                        }
                    }
                )
            }

            // -- Forward Message Bottom Sheet (WhatsApp Style - Any Media / Text) ---
            if (showForwardDialog && selectedMessages.isNotEmpty()) {
                val msgsToForward = selectedMessages.toList()
                ForwardMessageBottomSheet(
                    currentUserId = currentUserId,
                    onDismiss = {
                        showForwardDialog = false
                        selectedMessages = emptySet()
                    },
                    onForwardToTargets = { chosenTargets: List<ForwardTargetItem> ->
                        showForwardDialog = false
                        selectedMessages = emptySet()
                        val targetCount = chosenTargets.size
                        Toast.makeText(
                            context,
                            if (targetCount > 1) "Forwarding to $targetCount chats..." else "Forwarding...",
                            Toast.LENGTH_SHORT
                        ).show()

                        coroutineScope.launch(Dispatchers.IO) {
                            for (target in chosenTargets) {
                                launch {
                                    for (msg in msgsToForward) {
                                        try {
                                            val clean = msg.text.replace(Regex("\\[APPROVAL_(ACCEPTED|DECLINED):\\d+\\]"), "").trim()
                                            val forwardText = if (clean.startsWith("[FORWARDED]")) clean else "[FORWARDED]\n$clean"
                                            val nowIso = getIsoUtcTimestamp()
                                            val forwardKey = "fwd_${java.util.UUID.randomUUID()}"

                                             if (target.isGroup) {
                                                if (msg.message_type == "text") {
                                                    ApiClient.apiService.sendMessage(
                                                        groupId = target.id,
                                                        idempotencyKey = forwardKey,
                                                        msg = ChatRequest(group_id = target.id, sender_id = currentUserId, text = forwardText, idempotency_key = forwardKey)
                                                    )
                                                } else {
                                                    val mediaUrl = msg.media_url
                                                    if (mediaUrl.isNullOrBlank()) {
                                                        withContext(Dispatchers.Main) {
                                                            Toast.makeText(context, "Please download this file first before forwarding.", Toast.LENGTH_SHORT).show()
                                                        }
                                                    } else {
                                                        ApiClient.apiService.forwardGroupMessage(
                                                            groupId = target.id,
                                                            idempotencyKey = forwardKey,
                                                            req = ForwardGroupRequest(
                                                                sender_id = currentUserId,
                                                                media_url = mediaUrl,
                                                                message_type = msg.message_type,
                                                                text = forwardText,
                                                                idempotency_key = forwardKey
                                                            )
                                                        )
                                                    }
                                                }
                                            } else {
                                                // Direct personal chat
                                                if (msg.message_type == "text") {
                                                    val tempTxtId = -kotlin.math.abs(System.currentTimeMillis().hashCode() + target.id)
                                                    if (target.id == otherUser.id) {
                                                        val tempMsg = PersonalMessage(
                                                            id           = tempTxtId,
                                                            sender_id    = currentUserId,
                                                            receiver_id  = otherUser.id,
                                                            text         = forwardText,
                                                            timestamp    = nowIso,
                                                            message_type = "text",
                                                            is_delivered = false,
                                                            is_read      = false
                                                        )
                                                        withContext(Dispatchers.Main) {
                                                            messages = messages + tempMsg
                                                            listState.animateScrollToItem(0)
                                                        }
                                                    }
                                                    val sent = ApiClient.apiService.sendPersonalMessage(
                                                        idempotencyKey = forwardKey,
                                                        request = PersonalMessageCreateRequest(sender_id = currentUserId, receiver_id = target.id, text = forwardText, idempotency_key = forwardKey)
                                                    )
                                                    if (target.id == otherUser.id) {
                                                        withContext(Dispatchers.Main) {
                                                            messages = messages.map { if (it.id == tempTxtId) sent else it }
                                                        }
                                                    }
                                                } else {
                                                    val mediaUrl = msg.media_url
                                                    if (mediaUrl.isNullOrBlank()) {
                                                        withContext(Dispatchers.Main) {
                                                            Toast.makeText(context, "Please download this file first before forwarding.", Toast.LENGTH_SHORT).show()
                                                        }
                                                    } else {
                                                        val newMsg = ApiClient.apiService.forwardPersonalMessage(
                                                            idempotencyKey = forwardKey,
                                                            req = ForwardPersonalRequest(
                                                                sender_id = currentUserId,
                                                                receiver_id = target.id,
                                                                media_url = mediaUrl,
                                                                message_type = msg.message_type,
                                                                text = forwardText,
                                                                idempotency_key = forwardKey
                                                            )
                                                        )
                                                        if (target.id == otherUser.id) {
                                                            withContext(Dispatchers.Main) {
                                                                if (messages.none { it.id == newMsg.id }) {
                                                                    messages = messages + newMsg
                                                                    listState.animateScrollToItem(0)
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        } catch (_: Exception) {}
                                    }
                                }
                            }
                        }
                    }
                )
            }

            // With reverseLayout, the largest visible index is the oldest message
            // currently on screen. Load one older page only when it is approached.
            val reachedHistoryStart by remember(listState, reversedMessages.size) {
                derivedStateOf {
                    val lastVisible = listState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: 0
                    reversedMessages.isNotEmpty() && lastVisible >= reversedMessages.lastIndex - 4
                }
            }
            LaunchedEffect(reachedHistoryStart, hasOlderMessages, currentUserId, otherUser.id) {
                if (!reachedHistoryStart || !hasOlderMessages || isLoadingOlderMessages) return@LaunchedEffect
                val oldestId = messages.asSequence().filter { it.id > 0 }.minOfOrNull { it.id }
                    ?: return@LaunchedEffect
                isLoadingOlderMessages = true
                try {
                    val online = AppConnectivity.refresh(context)
                    val olderPage = if (online) {
                        ApiClient.apiService.getPersonalMessages(
                            userId = currentUserId,
                            otherUserId = otherUser.id,
                            beforeId = oldestId,
                            limit = PERSONAL_CHAT_PAGE_SIZE
                        )
                    } else {
                        ChatOfflineCache.loadOlderPersonal(
                            context, currentUserId, otherUser.id, oldestId, PERSONAL_CHAT_PAGE_SIZE
                        )
                    }
                    if (olderPage.isEmpty()) {
                        // Empty local cache cannot prove that the server has no
                        // earlier history.  Preserve the ability to continue once
                        // connectivity returns; only a server page is authoritative.
                        if (online) hasOlderMessages = false
                    } else {
                        messages = (olderPage + messages).distinctBy { it.id }
                        hasOlderMessages = olderPage.size == PERSONAL_CHAT_PAGE_SIZE
                    }
                } catch (_: Exception) {
                    // Keep the current messages; another scroll can retry safely.
                } finally {
                    isLoadingOlderMessages = false
                }
            }

            NoNativeOverscroll {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.weight(1f).fillMaxWidth().pumpBounceScroll(),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                items(reversedMessages, key = { msg -> "${msg.id}_${msg.timestamp.orEmpty()}" }) { msg ->
                    val isMine = msg.sender_id == currentUserId
                    val isSelected = selectedMessages.any { it.id == msg.id }
                    val isSelectionMode = selectedMessages.isNotEmpty()

                    val toggleSelection = {
                        selectedMessages = if (selectedMessages.any { it.id == msg.id }) {
                            selectedMessages.filter { it.id != msg.id }.toSet()
                        } else {
                            selectedMessages + msg
                        }
                    }

                    PCSwipeToReplyBox(
                        modifier = Modifier.animateItem(
                            fadeInSpec = androidx.compose.animation.core.tween(
                                durationMillis = AppMotion.SHORT4,
                                easing = AppMotion.EmphasizedEasing
                            ),
                            fadeOutSpec = androidx.compose.animation.core.tween(
                                durationMillis = AppMotion.SHORT3,
                                easing = AppMotion.EmphasizedAccelEasing
                            ),
                            placementSpec = null
                        ),
                        onSwipeToReply = { replyingToMsg = msg }
                    ) {
                        if (msg.text.startsWith("[APPROVAL_REQUEST:")) {
                            PCApprovalCard(
                                msg = msg,
                                currentUserId = currentUserId,
                                onStatusChanged = {
                                    coroutineScope.launch {
                                        try {
                                            val latest = ApiClient.apiService.getPersonalMessages(
                                                currentUserId,
                                                otherUser.id,
                                                limit = PERSONAL_CHAT_PAGE_SIZE
                                            )
                                            val latestIds = latest.mapTo(mutableSetOf()) { it.id }
                                            messages = (messages.filter { it.id > 0 && it.id !in latestIds } + latest)
                                                .distinctBy { it.id }
                                        } catch (_: Exception) {}
                                    }
                                }
                            )
                        } else {
                            PCMessageBubble(
                                msg = msg,
                                isMine = isMine,
                                isSelected = isSelected,
                                isSelectionMode = isSelectionMode,
                                onLongClick = toggleSelection,
                                onClick = {
                                    if (isSelectionMode) toggleSelection()
                                },
                                senderName = if (isMine) "" else otherUser.name,
                                highlightedMsgId = highlightedMsgId,
                                onReplyQuoteClick = { targetId, senderName, preview ->
                                    val idx = if (targetId != null && targetId > 0) {
                                        reversedMessages.indexOfFirst { it.id == targetId }
                                    } else {
                                        val previewSnippet = preview.take(15)
                                        reversedMessages.indexOfFirst { m ->
                                            previewSnippet.isNotBlank() && m.text.contains(previewSnippet)
                                        }.takeIf { it != -1 } ?: -1
                                    }
                                    if (idx >= 0) {
                                        coroutineScope.launch {
                                            listState.animateScrollToItem(idx)
                                            highlightedMsgId = reversedMessages[idx].id
                                            kotlinx.coroutines.delay(1600)
                                            if (highlightedMsgId == reversedMessages[idx].id) highlightedMsgId = null
                                        }
                                    }
                                },
                                autoPlayVoiceMsgId = autoPlayVoiceMsgId,
                                onImageClick = { bmp ->
                                    if (isSelectionMode) toggleSelection()
                                    else fullscreenBitmap = bmp
                                },
                                onVoiceCompleted = { completedMsgId ->
                                    val nextVoiceMsg = messages
                                        .dropWhile { it.id != completedMsgId }
                                        .drop(1)
                                        .firstOrNull { it.message_type == "voice" }
                                    autoPlayVoiceMsgId = nextVoiceMsg?.id
                                },
                                onAutoPlayHandled = { autoPlayVoiceMsgId = null },
                                isFailed = failedMessageIds.contains(msg.id),
                                onRetry = {
                                    // -- Text message retry / dismiss dialog --
                                    if (msg.message_type == "text") {
                                        failedActionMsg = msg
                                        return@PCMessageBubble
                                    }
                                    // -- TransferManager resume (all types: audio/file/voice/image/video) --
                                    val transferUrl = msg.media_url ?: ""
                                    if (transferUrl.startsWith("transfer://")) {
                                        val tid = transferUrl.removePrefix("transfer://")
                                        TransferManager.resume(context, tid)
                                        return@PCMessageBubble
                                    }
                                    // -- Legacy: Image/Video retry via stored URI -----------------------
                                    val retryUri = retryUriMap[msg.id] ?: return@PCMessageBubble
                                    failedMessageIds = failedMessageIds - msg.id
                                    val captionText = if (msg.text == "Photo" || msg.text == "Video") "" else msg.text
                                    val isVideo = msg.message_type == "video"
                                    val msgType = if (isVideo) "video" else "image"
                                    val newTransferId = TransferManager.enqueueUpload(
                                        context   = context,
                                        chatType  = "personal",
                                        senderId  = currentUserId,
                                        peerId    = otherUser.id,
                                        mediaType = msgType,
                                        uri       = retryUri,
                                        fileName  = if (isVideo) "video.mp4" else "image.jpg",
                                        caption   = captionText,
                                        onSuccess = { newMsg ->
                                            messages = messages.map { if (it.id == msg.id) newMsg else it }
                                            failedMessageIds = failedMessageIds - msg.id
                                            retryUriMap = retryUriMap - msg.id
                                        }
                                    )
                                    // Update bubble's media_url to new transferId for live tracking
                                    messages = messages.map { m ->
                                        if (m.id == msg.id) m.copy(media_url = "transfer://$newTransferId") else m
                                    }
                                },
                                onVideoClick = { url ->
                                    if (isSelectionMode) toggleSelection()
                                    else activeVideoUrl = url
                                }
                            )
                        }
                    }
                }

                // Encryption notice (oldest, at very bottom)
                item {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color(0xFF1B173B))
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                        ) {
                            Text("Today", color = PCSubText, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                if (isLoadingOlderMessages) {
                    item(key = "loading_older_personal_messages") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                color = PCPurple,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
                }
            }

            // -- Quoted Reply Preview Bar -----------------------------------------
            replyingToMsg?.let { rMsg ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF16132D))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .width(4.dp)
                            .height(34.dp)
                            .background(Color(0xFF8B6BFF), RoundedCornerShape(2.dp))
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (rMsg.sender_id == currentUserId) "You" else otherUser.name,
                            color = Color(0xFF8B6BFF),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = when (rMsg.message_type) {
                                "image" -> "Photo"
                                "voice" -> "Voice Note"
                                "file"  -> "Document"
                                else    -> rMsg.text
                            },
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = { replyingToMsg = null }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel Reply", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }

            // -- Input / Recording Bar (WhatsApp / iOS Style) ----------------------
            if (isChatBlocked) {
                val blockedNotice = if (blockStatus?.is_blocked_by_me == true) {
                    "You blocked ${otherUser.name}. Unblock this contact from their profile to send a message."
                } else {
                    "You can't send messages in this chat."
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF100E26))
                        .padding(horizontal = 16.dp, vertical = 18.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = blockedNotice,
                        color = PCSubText,
                        fontSize = 13.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else if (isRecording) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF100E26))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Trash / Cancel Button
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF381515))
                            .clickable {
                                try { recorder?.stop() } catch (_: Exception) {}
                                try { recorder?.release() } catch (_: Exception) {}
                                recorder = null
                                recordingFile?.delete()
                                recordingFile = null
                                isRecording = false
                                isPaused = false
                                recordTimeSeconds = 0
                                liveAmplitudes = emptyList()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFFF5252), modifier = Modifier.size(20.dp))
                    }

                    Spacer(Modifier.width(8.dp))

                    // Recording Info Capsule (Red dot, Timer, Dynamic Waves, Pause/Resume)
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color(0xFF1B163B))
                            .border(1.dp, Color(0xFF322B68), RoundedCornerShape(24.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (isPaused) Color.Gray else Color.Red)
                        )

                        Spacer(Modifier.width(8.dp))

                        val m = recordTimeSeconds / 60
                        val s = recordTimeSeconds % 60
                        Text(
                            text = String.format("%02d:%02d", m, s),
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(Modifier.width(10.dp))

                        // Dynamic Animated Waves (Filling 100% of available space)
                        Row(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            val displayBars = remember(liveAmplitudes) {
                                if (liveAmplitudes.size >= 26) liveAmplitudes.takeLast(26)
                                else liveAmplitudes + List(26 - liveAmplitudes.size) { 4 }
                            }
                            displayBars.forEach { h ->
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height(h.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(if (isPaused) Color.Gray else PCPurpleLt)
                                )
                            }
                        }

                        Spacer(Modifier.width(6.dp))

                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                            Icon(
                                imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = "Pause/Resume",
                                tint = PCPurpleLt,
                                modifier = Modifier
                                    .size(24.dp)
                                    .clickable {
                                        if (isPaused) {
                                            try { recorder?.resume(); isPaused = false } catch (_: Exception) {}
                                        } else {
                                            try { recorder?.pause(); isPaused = true } catch (_: Exception) {}
                                        }
                                    }
                            )
                        }
                    }

                    Spacer(Modifier.width(8.dp))

                    // Send Button
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(PCPurple, PCPurpleDk)))
                            .clickable {
                                val fileToUpload = recordingFile
                                val waveData = liveAmplitudes.joinToString(",")
                                val capturedDuration = recordTimeSeconds   // capture BEFORE reset
                                try { recorder?.stop() } catch (_: Exception) {}
                                try { recorder?.release() } catch (_: Exception) {}
                                recorder = null
                                isRecording = false
                                isPaused = false
                                recordTimeSeconds = 0
                                liveAmplitudes = emptyList()

                                fileToUpload?.let { audioFile ->
                                    val nowIso = getIsoUtcTimestamp()
                                    val textContent = if (waveData.isNotBlank()) "Voice Note|$waveData" else "Voice Note"

                                    var transferId = ""
                                    transferId = TransferManager.enqueueUpload(
                                        context      = context,
                                        chatType     = "personal",
                                        senderId     = currentUserId,
                                        peerId       = otherUser.id,
                                        mediaType    = "voice",
                                        file         = audioFile,
                                        fileName     = audioFile.name,
                                        caption      = textContent,
                                        durationSec  = capturedDuration,
                                        onSuccess = { newMsg ->
                                            messages = messages.mapNotNull { m ->
                                                if (m.media_url == "transfer://$transferId" || m.id == -kotlin.math.abs(transferId.hashCode())) {
                                                    if (messages.any { it.id == newMsg.id }) null else newMsg
                                                } else m
                                            }
                                        }
                                    )

                                    val tempMsg = PersonalMessage(
                                        id           = -kotlin.math.abs(transferId.hashCode()),
                                        sender_id    = currentUserId,
                                        receiver_id  = otherUser.id,
                                        text         = textContent,
                                        timestamp    = nowIso,
                                        message_type = "voice",
                                        media_url    = "transfer://$transferId",
                                        is_delivered = false,
                                        is_read      = false
                                    )
                                    messages = messages + tempMsg
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Send Voice", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // ── Quick Emoji Bar (WhatsApp-style Quick Emoji Picker) ─────────────
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showEmojiBar,
                        enter = androidx.compose.animation.expandVertically() + fadeIn(),
                        exit = androidx.compose.animation.shrinkVertically() + fadeOut()
                    ) {
                        val popularEmojis = remember {
                            listOf("😀", "😂", "😍", "🥰", "😎", "👍", "❤️", "🔥", "🎉", "🙏", "😭", "🥳", "👏", "🤔", "💯", "✨", "🙌", "🤩", "🤝", "💪", "💐", "💖", "👀", "🚀")
                        }
                        androidx.compose.foundation.lazy.LazyRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF14112E))
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            items(popularEmojis) { emoji ->
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF1F1B40))
                                        .clickable {
                                            messageText += emoji
                                            focusRequester.requestFocus()
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(text = emoji, fontSize = 20.sp)
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF100E26))
                            .navigationBarsPadding()
                            .imePadding()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 46.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .background(PCInputBg)
                                .border(1.dp, PCBorder, RoundedCornerShape(24.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Outlined.SentimentSatisfiedAlt,
                                    contentDescription = "Emoji",
                                    tint = if (showEmojiBar) Color(0xFFFFD54F) else PCPurpleLt,
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clickable {
                                            showEmojiBar = !showEmojiBar
                                            focusRequester.requestFocus()
                                            keyboardController?.show()
                                        }
                                )

                                Spacer(Modifier.width(8.dp))

                                BasicTextField(
                                    value = messageText,
                                    onValueChange = { messageText = it },
                                    modifier = Modifier
                                        .weight(1f)
                                        .focusRequester(focusRequester),
                                    singleLine = false,
                                    maxLines = 4,
                                    cursorBrush = SolidColor(PCPurple),
                                    textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                                    decorationBox = { inner ->
                                        Box(contentAlignment = Alignment.CenterStart) {
                                            if (messageText.isEmpty()) Text("Type a message...", color = PCSubText, fontSize = 14.sp)
                                            inner()
                                        }
                                    }
                                )

                                Spacer(Modifier.width(6.dp))

                                Icon(
                                    Icons.Outlined.AttachFile,
                                    contentDescription = "Attach Content",
                                    tint = if (showAttachmentMenu) PCPurpleLt else PCSubText,
                                    modifier = Modifier.size(22.dp).clickable {
                                        showAttachmentMenu = !showAttachmentMenu
                                    }
                                )

                                Spacer(Modifier.width(8.dp))

                                Icon(
                                    Icons.Outlined.PhotoCamera,
                                    contentDescription = "Camera",
                                    tint = PCSubText,
                                    modifier = Modifier.size(22.dp).clickable { launchCamera() }
                                )
                            } // end inner Row
                        } // end Box (text field container)

                        Spacer(Modifier.width(8.dp))

                        // Send/Mic Button
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(Brush.linearGradient(listOf(PCPurple, PCPurpleDk)))
                                .clickable(enabled = !(messageText.isNotBlank() && isSending)) {
                                    if (messageText.isNotBlank()) {
                                        sendMessage()
                                    } else {
                                        val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                                            context, android.Manifest.permission.RECORD_AUDIO
                                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                        if (hasPermission) {
                                            if (!isRecording) {
                                                startPCVoiceRecording(context) { rec, file ->
                                                    recorder = rec
                                                    recordingFile = file
                                                    isRecording = true
                                                    isPaused = false
                                                }
                                            }
                                        } else {
                                            audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (messageText.isNotBlank()) Icons.Default.Send else Icons.Default.Mic,
                                contentDescription = "Send/Mic",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    } // end outer Row
                } // end Column (emoji bar + input row)
            } // end else
        } // end main Column

        // ── Personal User Info Screen Overlay (Group Info Style) ──────
        AnimatedVisibility(
            visible = showUserInfoSheet,
            enter = AppMotion.chatEnter,
            exit = AppMotion.chatExit,
            modifier = Modifier.fillMaxSize().zIndex(25f)
        ) {
            PersonalUserInfoScreen(
                currentUserId = currentUserId,
                otherUser = otherUser,
                onBack = { showUserInfoSheet = false },
                onImageClick = { bmp ->
                    fullscreenBitmap = bmp
                },
                onBlockStatusChanged = { updated -> blockStatus = updated }
            )
        }
    }
}

// -- Single Personal Message Bubble (Text/Image/Voice/File) --------------------
@Composable
private fun PCMessageBubble(
    msg: PersonalMessage,
    isMine: Boolean,
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false,
    onLongClick: () -> Unit = {},
    onClick: () -> Unit = {},
    senderName: String,
    highlightedMsgId: Int? = null,
    onReplyQuoteClick: (Int?, String, String) -> Unit = { _, _, _ -> },
    autoPlayVoiceMsgId: Int?,
    onImageClick: (Bitmap) -> Unit,
    onVoiceCompleted: (Int) -> Unit,
    onAutoPlayHandled: () -> Unit,
    isFailed: Boolean = false,
    onRetry: () -> Unit = {},
    onVideoClick: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val time = formatPCTime(msg.timestamp)
    val cleanText = msg.text
        .replace(Regex("\\[APPROVAL_(ACCEPTED|DECLINED):\\d+\\]"), "")
        .trim()
    val isForwarded = cleanText.startsWith("[FORWARDED]\n") || cleanText.startsWith("[FORWARDED]")
    val cleanTextWithoutForward = if (isForwarded) {
        cleanText.removePrefix("[FORWARDED]\n").removePrefix("[FORWARDED]").trim()
    } else cleanText

    val nameColor = Color(0xFF9D80FF)
    val isHighlighted = msg.id == highlightedMsgId
    val animatedBgColor by androidx.compose.animation.animateColorAsState(
        targetValue   = when {
            isSelected -> Color(0xFF8B6BFF).copy(alpha = 0.35f)
            isHighlighted -> Color(0xFF8B6BFF).copy(alpha = 0.5f)
            else -> Color.Transparent
        },
        animationSpec = AppMotion.tweenStandard(AppMotion.MEDIUM2)
    )

    var showExpiredMediaNotice by remember(msg.id) { mutableStateOf(false) }
    val expiredMediaUnavailable by produceState(
        initialValue = false,
        key1 = "${msg.id}|${msg.media_url}|${msg.is_media_expired}|${msg.message_type}",
    ) {
        value = msg.is_media_expired && withContext(Dispatchers.IO) {
            MediaManager.findLocalFile(
                context, msg.id, msg.media_url,
                MediaManager.typeForMessage(msg.message_type, msg.media_url),
                section = "chat"
            )?.exists() != true
        }
    }

    if (msg.is_deleted || msg.text == "This message was deleted") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = if (isMine) 40.dp else 14.dp,
                    end   = if (isMine) 14.dp else 40.dp,
                    top   = 2.dp,
                    bottom = 2.dp
                ),
            horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isSelected) Color(0xFF8B6BFF).copy(alpha = 0.35f) else (if (isMine) Color(0xFF221A47) else Color(0xFF161230)))
                    .border(0.8.dp, if (isSelected) PCPurple else Color(0xFF332B5E), RoundedCornerShape(12.dp))
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = onLongClick
                    )
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Block,
                        contentDescription = "Deleted",
                        tint = PCSubText,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "This message was deleted",
                        color = PCSubText,
                        fontSize = 13.sp,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = time,
                        color = PCSubText.copy(alpha = 0.65f),
                        fontSize = 10.sp
                    )
                }
            }
        }
        return
    }

    if (expiredMediaUnavailable) {
        if (showExpiredMediaNotice) {
            AlertDialog(
                onDismissRequest = { showExpiredMediaNotice = false },
                title = { Text("Media unavailable") },
                text = { Text("This attachment is no longer on the cloud and is not in this phone's EDUConnect folder. It may have been cleared, removed from storage, or lost after a device reset.") },
                confirmButton = { TextButton(onClick = { showExpiredMediaNotice = false }) { Text("OK") } }
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(
                start = if (isMine) 40.dp else 14.dp,
                end = if (isMine) 14.dp else 40.dp,
                top = 2.dp, bottom = 2.dp
            ),
            horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF24203B))
                    .border(1.dp, PCBorder, RoundedCornerShape(12.dp))
                    .combinedClickable(onClick = { showExpiredMediaNotice = true }, onLongClick = onLongClick)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.ErrorOutline, contentDescription = "Media unavailable", tint = PCSubText, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Column {
                    Text("Media unavailable", color = Color.White, fontSize = 13.sp)
                    Text("Not saved on this phone", color = PCSubText, fontSize = 11.sp)
                }
            }
        }
        return
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(animatedBgColor)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(
                start = if (isMine) 40.dp else 14.dp,
                end   = if (isMine) 14.dp else 40.dp,
                top   = 2.dp,
                bottom = 2.dp
            ),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        Box(modifier = Modifier.wrapContentWidth()) {
            Column(horizontalAlignment = if (isMine) Alignment.End else Alignment.Start) {
                // -- WhatsApp-Style "Forwarded" Header ------------------------
                if (isForwarded) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Forward,
                            contentDescription = "Forwarded",
                            tint = Color.White.copy(alpha = 0.65f),
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = "Forwarded",
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 11.sp,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                        )
                    }
                }

                val cleanMsg = if (isForwarded) msg.copy(text = cleanTextWithoutForward) else msg

                when (cleanMsg.message_type) {
                    "voice" -> PCVoiceBubble(
                        msg = cleanMsg,
                        isMine = isMine,
                        time = time,
                        senderName = senderName,
                        nameColor = nameColor,
                        isSelectionMode = isSelectionMode,
                        onClick = onClick,
                        onLongClick = onLongClick,
                        autoPlayVoiceMsgId = autoPlayVoiceMsgId,
                        onVoiceCompleted = onVoiceCompleted,
                        onAutoPlayHandled = onAutoPlayHandled,
                        isFailed = isFailed,
                        onRetry = onRetry
                    )
                    "audio" -> PCAudioBubble(
                        msg = cleanMsg,
                        isMine = isMine,
                        time = time,
                        senderName = senderName,
                        nameColor = nameColor,
                        isSelectionMode = isSelectionMode,
                        onClick = onClick,
                        onLongClick = onLongClick,
                        isFailed = isFailed,
                        onRetry = onRetry
                    )
                    "file"  -> {
                        val fName = cleanMsg.media_url?.substringAfterLast("/") ?: cleanMsg.text
                        val fExt = fName.substringAfterLast(".", "").lowercase()
                        val isAudio = fExt in listOf("mp3", "wav", "m4a", "aac", "ogg", "opus", "flac", "wma", "amr", "3gp")
                        if (isAudio) {
                            PCAudioBubble(
                                msg = cleanMsg,
                                isMine = isMine,
                                time = time,
                                senderName = senderName,
                                nameColor = nameColor,
                                isSelectionMode = isSelectionMode,
                                onClick = onClick,
                                onLongClick = onLongClick,
                                isFailed = isFailed,
                                onRetry = onRetry
                            )
                        } else {
                            PCFileBubble(
                                msg = cleanMsg,
                                isMine = isMine,
                                time = time,
                                senderName = senderName,
                                nameColor = nameColor,
                                isSelectionMode = isSelectionMode,
                                onClick = onClick,
                                onLongClick = onLongClick,
                                isFailed = isFailed,
                                onRetry = onRetry
                            )
                        }
                    }
                    "image" -> PCImageBubble(
                        msg = cleanMsg, isMine = isMine, time = time, senderName = senderName, nameColor = nameColor,
                        isSelectionMode = isSelectionMode,
                        onClick = onClick,
                        onLongClick = onLongClick,
                        onImageClick = onImageClick,
                        isFailed = isFailed,
                        onRetry = onRetry
                    )
                    "video" -> PCVideoBubble(
                        msg = cleanMsg, isMine = isMine, time = time, senderName = senderName, nameColor = nameColor,
                        isSelectionMode = isSelectionMode,
                        onClick = onClick,
                        onLongClick = onLongClick,
                        isFailed = isFailed,
                        onRetry = onRetry,
                        onVideoClick = onVideoClick
                    )
                    else    -> {
                        // Parse embedded reply metadata: [REPLY:targetId|senderName|type|preview]
                        val replyData: PCReplyInfo? = run {
                            if (cleanTextWithoutForward.startsWith("[REPLY:")) {
                                val end = cleanTextWithoutForward.indexOf("]")
                                if (end > 0) {
                                    val meta = cleanTextWithoutForward.substring(7, end)
                                    val parts = meta.split("|")
                                    if (parts.size >= 4) {
                                        PCReplyInfo(parts[0].toIntOrNull(), parts[1], parts[2], parts.drop(3).joinToString("|"))
                                    } else if (parts.size >= 3) {
                                        PCReplyInfo(null, parts[0], parts[1], parts.drop(2).joinToString("|"))
                                    } else null
                                } else null
                            } else null
                        }
                        val displayText = if (replyData != null) cleanTextWithoutForward.substringAfter("\n") else cleanTextWithoutForward

                        // Standard Text Bubble
                        Box(
                            modifier = Modifier
                                .widthIn(min = 60.dp, max = 280.dp)
                                .clip(
                                    RoundedCornerShape(
                                        topStart = 16.dp, topEnd = 16.dp,
                                        bottomStart = if (isMine) 16.dp else 4.dp,
                                        bottomEnd = if (isMine) 4.dp else 16.dp
                                    )
                                )
                                .then(
                                    if (isMine) Modifier.background(Brush.linearGradient(listOf(PCPurple, PCPurpleDk)))
                                    else Modifier.background(PCCardBg).border(1.dp, Color(0xFF8B6BFF).copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                                )
                                .combinedClickable(
                                    onClick = onClick,
                                    onLongClick = onLongClick
                                )
                                .padding(horizontal = 9.dp, vertical = 3.dp)
                        ) {
                        Column {
                            // Reply preview box (Clicking scrolls to quoted message)
                            replyData?.let { (rId, rSender, _, rPreview) ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.Black.copy(alpha = 0.25f))
                                        .clickable { onReplyQuoteClick(rId, rSender, rPreview) }
                                ) {
                                    Row {
                                        Box(
                                            modifier = Modifier
                                                .width(3.dp)
                                                .fillMaxHeight()
                                                .background(Color(0xFF8B6BFF))
                                        )
                                        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)) {
                                            Text(
                                                text = rSender,
                                                color = Color(0xFF9D80FF),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = rPreview,
                                                color = Color.White.copy(alpha = 0.7f),
                                                fontSize = 11.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(3.dp))
                            }

                            // Msg text + timestamp + ticks all in ONE bottom-aligned Row (WhatsApp style)
                            Row(
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.End,
                                modifier = Modifier.wrapContentWidth()
                            ) {
                                Text(
                                    text = displayText,
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    lineHeight = 19.sp,
                                    modifier = Modifier
                                        .weight(1f, fill = false)
                                )
                                Spacer(Modifier.width(5.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .padding(bottom = 1.dp)
                                ) {
                                    Text(
                                        text = time,
                                        color = Color.White.copy(alpha = 0.72f),
                                        fontSize = 9.5.sp,
                                        lineHeight = 9.5.sp
                                    )
                                    if (isMine) {
                                        Spacer(Modifier.width(2.dp))
                                        if (isFailed) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.clickable { onRetry() }
                                            ) {
                                                Text("Failed", color = Color(0xFFFF5252), fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold)
                                                Spacer(Modifier.width(2.dp))
                                                Icon(
                                                    imageVector = Icons.Default.Refresh,
                                                    contentDescription = "Retry",
                                                    tint = Color(0xFFFF5252),
                                                    modifier = Modifier.size(11.dp)
                                                )
                                            }
                                        } else {
                                            val isPending = msg.id < 0 || MediaManager.mediaProgressMap[msg.id] != null
                                            if (isPending) {
                                                Icon(
                                                    imageVector = Icons.Outlined.AccessTime,
                                                    contentDescription = "Pending / Sending",
                                                    tint = Color.White.copy(alpha = 0.7f),
                                                    modifier = Modifier.size(11.dp)
                                                )
                                            } else {
                                                PCTickMarks(
                                                    isRead = msg.is_read,
                                                    isDelivered = msg.is_delivered || msg.is_read
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
}

@Composable
private fun PCTickMarks(
    isRead: Boolean,
    isDelivered: Boolean,
    modifier: Modifier = Modifier,
    readColor: Color = Color(0xFF53BDEB),
    unreadColor: Color = Color.White.copy(alpha = 0.65f),
    size: androidx.compose.ui.unit.TextUnit = 10.5.sp
) {
    val tickColor = if (isRead) readColor else unreadColor
    val isDouble = isDelivered || isRead

    androidx.compose.foundation.Canvas(
        modifier = modifier
            .padding(start = 2.dp)
            .size(if (isDouble) 15.dp else 11.dp, 10.dp)
    ) {
        val strokeW = 1.4.dp.toPx()
        val cap = androidx.compose.ui.graphics.StrokeCap.Round
        val join = androidx.compose.ui.graphics.StrokeJoin.Round

        if (isDouble) {
            // First tick (left)
            val path1 = androidx.compose.ui.graphics.Path().apply {
                moveTo(1.2.dp.toPx(), 5.2.dp.toPx())
                lineTo(4.2.dp.toPx(), 8.2.dp.toPx())
                lineTo(9.8.dp.toPx(), 1.8.dp.toPx())
            }
            drawPath(
                path = path1,
                color = tickColor,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeW, cap = cap, join = join)
            )

            // Second tick (right)
            val path2 = androidx.compose.ui.graphics.Path().apply {
                moveTo(5.8.dp.toPx(), 5.2.dp.toPx())
                lineTo(8.8.dp.toPx(), 8.2.dp.toPx())
                lineTo(14.4.dp.toPx(), 1.8.dp.toPx())
            }
            drawPath(
                path = path2,
                color = tickColor,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeW, cap = cap, join = join)
            )
        } else {
            // Single tick
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(1.2.dp.toPx(), 5.2.dp.toPx())
                lineTo(4.2.dp.toPx(), 8.2.dp.toPx())
                lineTo(9.8.dp.toPx(), 1.8.dp.toPx())
            }
            drawPath(
                path = path,
                color = tickColor,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeW, cap = cap, join = join)
            )
        }
    }
}

// Voice Bubble (With Real Android MediaPlayer & WhatsApp-style Download)
@Composable
private fun PCVoiceBubble(
    msg: PersonalMessage,
    isMine: Boolean,
    time: String,
    senderName: String,
    nameColor: Color,
    isSelectionMode: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
    autoPlayVoiceMsgId: Int?,
    onVoiceCompleted: (Int) -> Unit,
    onAutoPlayHandled: () -> Unit,
    isFailed: Boolean = false,
    onRetry: () -> Unit = {}
) {
    val context = LocalContext.current
    val isCurrentActive = GlobalAudioPlayer.currentPlayingMsgId.value == msg.id
    val isPlaying = isCurrentActive && GlobalAudioPlayer.isPlaying.value
    val progress = if (isCurrentActive) GlobalAudioPlayer.currentProgress.value else 0f
    val currentPosMs = if (isCurrentActive) GlobalAudioPlayer.currentPositionMs.value else 0
    val totalDurationMs = if (isCurrentActive) GlobalAudioPlayer.totalDurationMs.value else 0

    var downloadProgress by remember(msg.media_url, msg.id) { mutableStateOf<Float?>(null) }
    var isDownloadCancelled by remember(msg.media_url, msg.id) { mutableStateOf(false) }
    var downloadedFile by remember(msg.media_url, msg.id) {
        mutableStateOf<File?>(MediaManager.findLocalFile(context, msg.id, msg.media_url, AppMediaType.AUDIO))
    }

    val localFile = remember(msg.media_url, msg.id) {
        MediaManager.findLocalFile(context, msg.id, msg.media_url, AppMediaType.AUDIO)
    }
    val effectiveFile = downloadedFile ?: localFile
    val isDownloaded = isMine || (effectiveFile != null && effectiveFile.exists() && effectiveFile.length() > 0) || (msg.media_url != null && (msg.media_url!!.startsWith("content://") || msg.media_url!!.startsWith("file://")))
    val downloadTransferId = "${msg.id}-download"
    val liveDownloadProg: Float? = TransferStateHolder.progressFor(downloadTransferId).takeIf { it > 0f }
        ?: MediaManager.mediaProgressMap[msg.id]
        ?: downloadProgress
    val liveDownloadStatus = TransferStateHolder.statusFor(downloadTransferId)
    val isActivelyDownloading = !isDownloaded && !isMine && (
        liveDownloadStatus == TransferStatus.DOWNLOADING ||
        liveDownloadStatus == TransferStatus.QUEUED ||
        (liveDownloadProg != null && liveDownloadProg < 1f && liveDownloadProg > 0f)
    )

    fun togglePlay() {
        val url = msg.media_url ?: return
        val fullUrl = resolveFullMediaUrl(url)
        val fname = url.substringAfterLast("/").ifBlank { "voice_${msg.id}.mp4" }

        if (!isDownloaded && !isMine) {
            isDownloadCancelled = false
            MediaManager.downloadMediaWithProgress(
                context, msg.id, fullUrl, fname, AppMediaType.AUDIO,
                onProgress = { p -> downloadProgress = p },
                onComplete = { file ->
                    downloadedFile = file
                    downloadProgress = null
                    PersonalMediaReceiptReporter.reportLocalSave(context, msg.id, file, AppMediaType.AUDIO, currentUserId)
                },
                onError = { downloadProgress = null }
            )
            return
        }

        GlobalAudioPlayer.togglePlayPause(
            context = context,
            msgId = msg.id,
            mediaUrl = fullUrl,
            senderName = senderName,
            title = "Voice Note",
            onCompleted = onVoiceCompleted
        )
    }

    // Auto-play listener for consecutive playback
    LaunchedEffect(autoPlayVoiceMsgId) {
        if (autoPlayVoiceMsgId == msg.id && !isPlaying && isDownloaded) {
            onAutoPlayHandled()
            togglePlay()
        }
    }

    val bars = remember(msg.text, msg.media_url) {
        pcExtractOrGenerateWaveform(msg.text, msg.media_url, 45)
    }

    val transferId = remember(msg.media_url) {
        if (msg.media_url?.startsWith("transfer://") == true) msg.media_url!!.removePrefix("transfer://") else ""
    }
    val transferProg = if (transferId.isNotBlank()) TransferStateHolder.progressFor(transferId) else null
    val transferStatus = if (transferId.isNotBlank()) TransferStateHolder.statusFor(transferId) else null

    val uploadProg = transferProg
        ?: MediaManager.mediaProgressMap[msg.id]
        ?: UploadStateManager.get(context, msg.id)?.let { if (it.totalBytes > 0) it.bytesSent.toFloat() / it.totalBytes else null }
    val uploadStatus = transferStatus?.let {
        when (it) {
            TransferStatus.QUEUED -> UploadStatus.QUEUED
            TransferStatus.UPLOADING, TransferStatus.RESUMING -> UploadStatus.UPLOADING
            TransferStatus.PAUSED -> UploadStatus.PAUSED
            TransferStatus.FAILED -> UploadStatus.FAILED
            TransferStatus.COMPLETED -> UploadStatus.COMPLETED
            else -> null
        }
    } ?: UploadStateManager.mediaStatusMap[msg.id]

    val isUp = (transferStatus?.isActive == true || msg.id < 0 || uploadProg != null || uploadStatus == UploadStatus.UPLOADING) && isMine && !msg.is_delivered
    val hasSaved = isMine && !msg.is_delivered && UploadStateManager.get(context, msg.id) != null
    val isFail = isFailed || transferStatus == TransferStatus.PAUSED || transferStatus == TransferStatus.FAILED || uploadStatus == UploadStatus.CANCELLED || uploadStatus == UploadStatus.FAILED || (hasSaved && !isUp)

    val displayTimeMs = if (isPlaying || progress > 0f) currentPosMs else totalDurationMs
    val displaySec    = (displayTimeMs / 1000) % 60
    val displayMin    = (displayTimeMs / 1000) / 60
    val durationText  = String.format("%d:%02d", displayMin, displaySec)

    val uploadPct = ((uploadProg ?: 0f) * 100).toInt().coerceIn(0, 100)
    val downloadPct = ((liveDownloadProg ?: 0f) * 100).toInt().coerceIn(0, 100)
    val displaySubtitle = when {
        isUp -> if (uploadPct > 0) "$uploadPct% • Uploading..." else "Uploading..."
        isFail -> "Paused • Tap to retry"
        isActivelyDownloading -> "$downloadPct% • Downloading..."
        !isDownloaded && !isMine -> "Tap to download"
        else -> durationText
    }

    Box(
        modifier = Modifier
            .widthIn(min = 220.dp, max = 285.dp)
            .clip(RoundedCornerShape(16.dp))
            .then(
                if (isMine) Modifier.background(Brush.linearGradient(listOf(PCPurple, PCPurpleDk)))
                else Modifier.background(PCCardBg).border(1.dp, Color(0xFF8B6BFF).copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Column {
            // TOP ROW: [Play/Pause / Download Button] -- [Waveform] -- [Mic Icon]
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isUp || isFail) {
                    UniversalUploadProgressBadge(
                        tempId = msg.id,
                        transferId = transferId,
                        isUploading = isUp,
                        isCancelledOrFailed = isFail,
                        status = uploadStatus,
                        progress = uploadProg ?: 0.05f,
                        size = 34.dp,
                        backgroundColor = if (isMine) Color(0xFF9D80FF) else PCPurple,
                        onCancel = {
                            if (transferId.isNotBlank()) TransferManager.cancel(context, transferId)
                            else UniversalUploader.cancel(context, msg.id)
                        },
                        onRetry = {
                            if (transferId.isNotBlank()) TransferManager.resume(context, transferId)
                            else onRetry()
                        }
                    )
                } else if (isActivelyDownloading) {
                    // Receiver Downloading Ring + Cancel (X)
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            progress = liveDownloadProg ?: 0.05f,
                            color = Color.White,
                            trackColor = Color.White.copy(alpha = 0.25f),
                            modifier = Modifier.size(30.dp),
                            strokeWidth = 2.5.dp
                        )
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel Download",
                            tint = Color.White,
                            modifier = Modifier
                                .size(16.dp)
                                .combinedClickable(
                                    onClick = {
                                        if (isSelectionMode) onClick()
                                        else {
                                            MediaManager.cancelDownload(msg.id)
                                            downloadProgress = null
                                            isDownloadCancelled = true
                                        }
                                    },
                                    onLongClick = onLongClick
                                )
                        )
                    }
                } else if (!isDownloaded && !isMine) {
                    // Receiver Tap to Download Button
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(PCPurple)
                            .combinedClickable(
                                onClick = {
                                    if (isSelectionMode) onClick()
                                    else {
                                        isDownloadCancelled = false
                                        val url = msg.media_url ?: return@combinedClickable
                                        val fullUrl = resolveFullMediaUrl(url)
                                        val fname = url.substringAfterLast("/").ifBlank { "voice_${msg.id}.mp4" }
                                        MediaManager.downloadMediaWithProgress(
                                            context, msg.id, fullUrl, fname, AppMediaType.AUDIO,
                                            onProgress = { p -> downloadProgress = p },
                                            onComplete = { file ->
                                                downloadedFile = file
                                                downloadProgress = null
                                                PersonalMediaReceiptReporter.reportLocalSave(context, msg.id, file, AppMediaType.AUDIO, currentUserId)
                                            },
                                            onError = { downloadProgress = null }
                                        )
                                    }
                                },
                                onLongClick = onLongClick
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FileDownload,
                            contentDescription = "Download Voice",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                } else {
                    // Normal Play / Pause
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(if (isMine) Color(0xFF9D80FF) else PCPurple)
                            .combinedClickable(
                                onClick = {
                                    if (isSelectionMode) onClick()
                                    else togglePlay()
                                },
                                onLongClick = onLongClick
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                // Waveform Row (fills 100% of width up to Mic icon)
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(26.dp)
                        .pointerInput(isDownloaded) {
                            if (!isDownloaded) return@pointerInput
                            detectTapGestures { offset ->
                                val fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                GlobalAudioPlayer.seekTo(fraction)
                            }
                        }
                        .pointerInput(isDownloaded) {
                            if (!isDownloaded) return@pointerInput
                            detectDragGestures(
                                onDrag = { change, _ ->
                                    change.consume()
                                    val fraction = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                                    GlobalAudioPlayer.seekTo(fraction)
                                }
                            )
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    bars.forEachIndexed { index, h ->
                        val isActive = progress > 0f && (index.toFloat() / bars.size) <= progress
                        val activeColor   = if (isMine) Color.White else PCPurple
                        val inactiveColor = if (isMine) Color.White.copy(alpha = 0.35f) else PCSubText.copy(alpha = 0.4f)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(h.dp)
                                .clip(RoundedCornerShape(1.dp))
                                .background(if (isActive) activeColor else inactiveColor)
                        )
                    }
                }

                Spacer(Modifier.width(6.dp))

                // Mic icon
                Icon(Icons.Outlined.Mic, null, tint = if (isMine) Color.White.copy(alpha = 0.7f) else PCSubText, modifier = Modifier.size(15.dp))
            }

            Spacer(Modifier.height(2.dp))

            // BOTTOM ROW: [Duration / Download Status] -- [Time + Ticks]
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = displaySubtitle,
                    color = if (isActivelyDownloading || isUp) Color(0xFF9D80FF) else Color.White.copy(alpha = 0.82f),
                    fontSize = 10.sp,
                    fontWeight = if (isActivelyDownloading || isUp) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.padding(start = 38.dp)
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(time, color = Color.White.copy(alpha = 0.82f), fontSize = 9.5.sp)
                    if (isMine) {
                        Spacer(Modifier.width(2.dp))
                        val isPending = msg.id < 0 || transferId.isNotBlank() || isUp || MediaManager.mediaProgressMap[msg.id] != null
                        if (isPending) {
                            Icon(
                                imageVector = Icons.Outlined.AccessTime,
                                contentDescription = "Pending",
                                tint = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.size(11.dp)
                            )
                        } else {
                            PCTickMarks(
                                isRead = msg.is_read,
                                isDelivered = msg.is_delivered || msg.is_read
                            )
                        }
                    }
                }
            }
        }
    }
}

// -- Audio Bubble (WhatsApp-style with format badge, smooth seekbar, dynamic duration & download progress) -
@Composable
private fun PCAudioBubble(
    msg: PersonalMessage,
    isMine: Boolean,
    time: String,
    senderName: String,
    nameColor: Color,
    isSelectionMode: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
    isFailed: Boolean = false,
    onRetry: () -> Unit = {}
) {
    val context = LocalContext.current
    val fileName = remember(msg.media_url, msg.text) {
        val rawName = msg.media_url?.substringAfterLast("/") ?: msg.text
        if (rawName.isBlank() || rawName.startsWith("Voice Note") || rawName.startsWith("Audio") || rawName.startsWith("File")) "Audio.mp3" else rawName
    }
    val ext = remember(fileName) { fileName.substringAfterLast(".", "").lowercase().ifEmpty { "mp3" } }
    val formatName = remember(ext) { ext.uppercase() }

    val badgeColor = remember(ext) {
        when (ext) {
            "mp3", "mp2", "mp1", "mpga"     -> Color(0xFFFF9800) // Vibrant Orange
            "wav", "pcm", "snd", "au"       -> Color(0xFF00BCD4) // Cyan
            "m4a", "m4b", "m4p", "alac"     -> Color(0xFF00E676) // Emerald Green
            "aac", "ac3", "eac3"            -> Color(0xFFFF5722) // Coral
            "ogg", "opus", "oga"            -> Color(0xFF7E57C2) // Deep Purple
            "flac", "aiff", "caf"           -> Color(0xFFFFD600) // Hi-Res Gold
            "amr", "3gp", "3gpp"            -> Color(0xFF26A69A) // Teal
            "wma", "mid", "midi", "weba"    -> Color(0xFF0288D1) // Blue
            else                            -> Color(0xFF8B6BFF) // Purple Theme
        }
    }

    val isCurrentActive = GlobalAudioPlayer.currentPlayingMsgId.value == msg.id
    val isPlaying = isCurrentActive && GlobalAudioPlayer.isPlaying.value
    val progress = if (isCurrentActive) GlobalAudioPlayer.currentProgress.value else 0f
    val currentPosMs = if (isCurrentActive) GlobalAudioPlayer.currentPositionMs.value else 0
    val totalDurationMs = if (isCurrentActive) GlobalAudioPlayer.totalDurationMs.value else 0
    var isUserSeeking by remember { mutableStateOf(false) }
    var seekPosMs by remember { mutableIntStateOf(0) }

    var downloadProgress by remember(msg.media_url, msg.id) { mutableStateOf<Float?>(null) }
    var isDownloadCancelled by remember(msg.media_url, msg.id) { mutableStateOf(false) }
    var downloadedFile by remember(msg.media_url, msg.id) {
        mutableStateOf<File?>(MediaManager.findLocalFile(context, msg.id, msg.media_url, AppMediaType.AUDIO))
    }

    val localFile = remember(msg.media_url, fileName, msg.id) {
        MediaManager.findLocalFile(context, msg.id, msg.media_url, AppMediaType.AUDIO)
    }
    val effectiveFile = downloadedFile ?: localFile
    val isDownloaded = isMine || (effectiveFile != null && effectiveFile.exists() && effectiveFile.length() > 0) || (msg.media_url != null && (msg.media_url!!.startsWith("content://") || msg.media_url!!.startsWith("file://")))
    val downloadTransferId = "${msg.id}-download"
    val liveDownloadProg: Float? = TransferStateHolder.progressFor(downloadTransferId).takeIf { it > 0f }
        ?: MediaManager.mediaProgressMap[msg.id]
        ?: downloadProgress
    val liveDownloadStatus = TransferStateHolder.statusFor(downloadTransferId)
    val isActivelyDownloading = !isDownloaded && !isMine && (
        liveDownloadStatus == TransferStatus.DOWNLOADING ||
        liveDownloadStatus == TransferStatus.QUEUED ||
        (liveDownloadProg != null && liveDownloadProg < 1f && liveDownloadProg > 0f)
    )

    fun togglePlay() {
        val url = msg.media_url ?: return
        val fullUrl = resolveFullMediaUrl(url)
        val fName = fileName.ifBlank { "audio_${msg.id}.$ext" }

        if (!isDownloaded && !isMine) {
            isDownloadCancelled = false
            MediaManager.downloadMediaWithProgress(
                context, msg.id, fullUrl, fName, AppMediaType.AUDIO,
                onProgress = { p -> downloadProgress = p },
                onComplete = { file ->
                    downloadedFile = file
                    downloadProgress = null
                    PersonalMediaReceiptReporter.reportLocalSave(context, msg.id, file, AppMediaType.AUDIO, currentUserId)
                    Toast.makeText(context, "✓ Saved to your EDUConnect Chats folder", Toast.LENGTH_SHORT).show()
                },
                onError = { downloadProgress = null }
            )
            return
        }

        GlobalAudioPlayer.togglePlayPause(
            context = context,
            msgId = msg.id,
            mediaUrl = fullUrl,
            senderName = senderName,
            title = fileName
        )
    }

    val displayTimeMs = if (isPlaying || progress > 0f) currentPosMs else totalDurationMs
    val displaySec = (displayTimeMs / 1000) % 60
    val displayMin = (displayTimeMs / 1000) / 60
    val durationText = String.format("%d:%02d", displayMin, displaySec)

    val totalSec = (totalDurationMs / 1000) % 60
    val totalMin = (totalDurationMs / 1000) / 60
    val totalDurationText = if (totalDurationMs > 0) String.format("%d:%02d", totalMin, totalSec) else ""

    val transferId = remember(msg.media_url) {
        if (msg.media_url?.startsWith("transfer://") == true) msg.media_url!!.removePrefix("transfer://") else ""
    }
    val transferProg = if (transferId.isNotBlank()) TransferStateHolder.progressFor(transferId) else null
    val transferStatus = if (transferId.isNotBlank()) TransferStateHolder.statusFor(transferId) else null

    val uploadProg = transferProg
        ?: MediaManager.mediaProgressMap[msg.id]
        ?: UploadStateManager.get(context, msg.id)?.let { if (it.totalBytes > 0) it.bytesSent.toFloat() / it.totalBytes else null }
    val uploadStatus = transferStatus?.let {
        when (it) {
            TransferStatus.QUEUED -> UploadStatus.QUEUED
            TransferStatus.UPLOADING, TransferStatus.RESUMING -> UploadStatus.UPLOADING
            TransferStatus.PAUSED -> UploadStatus.PAUSED
            TransferStatus.FAILED -> UploadStatus.FAILED
            TransferStatus.COMPLETED -> UploadStatus.COMPLETED
            else -> null
        }
    } ?: UploadStateManager.mediaStatusMap[msg.id]

    val isUp = (transferStatus?.isActive == true || msg.id < 0 || uploadProg != null || uploadStatus == UploadStatus.UPLOADING) && isMine && !msg.is_delivered
    val hasSaved = isMine && !msg.is_delivered && UploadStateManager.get(context, msg.id) != null
    val isExplicitPaused = transferStatus == TransferStatus.PAUSED || uploadStatus == UploadStatus.PAUSED || uploadStatus == UploadStatus.CANCELLED
    val isExplicitFailed = isFailed || transferStatus == TransferStatus.FAILED || uploadStatus == UploadStatus.FAILED || (hasSaved && !isUp)
    val isFail = isExplicitPaused || isExplicitFailed

    val uploadPct = ((uploadProg ?: 0f) * 100).toInt().coerceIn(0, 100)
    val downloadPct = ((liveDownloadProg ?: 0f) * 100).toInt().coerceIn(0, 100)
    val displayAudioSubtitle = when {
        isUp -> if (uploadPct > 0) "$uploadPct% • Uploading..." else "Uploading..."
        isExplicitPaused -> "Paused • Tap to resume"
        isExplicitFailed -> "Failed • Tap to retry"
        isActivelyDownloading -> "$downloadPct% • Downloading..."
        !isDownloaded && !isMine -> "Tap to download • ${ext.uppercase()} Audio"
        else -> if (totalDurationText.isNotEmpty()) "$durationText / $totalDurationText" else durationText
    }

    Box(
        modifier = Modifier
            .widthIn(min = 250.dp, max = 310.dp)
            .clip(RoundedCornerShape(16.dp))
            .then(
                if (isMine) Modifier.background(Brush.linearGradient(listOf(PCPurple, PCPurpleDk)))
                else Modifier.background(PCCardBg).border(1.dp, Color(0xFF8B6BFF).copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Column {
            // Header Row: [Badge] [File Name]
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Dynamic Format Badge (WhatsApp style, color-coded per format)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(badgeColor)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = formatName,
                        color = Color.Black,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.5.sp
                    )
                }

                Spacer(Modifier.width(8.dp))

                Text(
                    text = fileName,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(8.dp))

            // Player Row: [Play/Pause / Download Badge] -- [Slider / Progress]
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isUp || isFail) {
                    UniversalUploadProgressBadge(
                        tempId = msg.id,
                        transferId = transferId,
                        isUploading = isUp,
                        isCancelledOrFailed = isFail,
                        status = uploadStatus,
                        progress = uploadProg ?: 0.05f,
                        size = 40.dp,
                        backgroundColor = if (isMine) Color(0xFF9D80FF) else PCPurple,
                        onCancel = {
                            if (transferId.isNotBlank()) TransferManager.cancel(context, transferId)
                            else UniversalUploader.cancel(context, msg.id)
                        },
                        onRetry = {
                            if (transferId.isNotBlank()) TransferManager.resume(context, transferId)
                            else onRetry()
                        }
                    )
                } else if (isActivelyDownloading) {
                    // Receiver Download Ring + Cancel (X)
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            progress = downloadProgress ?: 0.05f,
                            color = Color.White,
                            trackColor = Color.White.copy(alpha = 0.25f),
                            modifier = Modifier.size(34.dp),
                            strokeWidth = 2.5.dp
                        )
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel Download",
                            tint = Color.White,
                            modifier = Modifier
                                .size(18.dp)
                                .combinedClickable(
                                    onClick = {
                                        if (isSelectionMode) onClick()
                                        else {
                                            MediaManager.cancelDownload(msg.id)
                                            downloadProgress = null
                                            isDownloadCancelled = true
                                        }
                                    },
                                    onLongClick = onLongClick
                                )
                        )
                    }
                } else if (!isDownloaded && !isMine) {
                    // Receiver Tap to Download Button
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(PCPurple)
                            .combinedClickable(
                                onClick = {
                                    if (isSelectionMode) onClick()
                                    else {
                                        isDownloadCancelled = false
                                        val url = msg.media_url ?: return@combinedClickable
                                        val fullUrl = resolveFullMediaUrl(url)
                                        val fName = fileName.ifBlank { "audio_${msg.id}.$ext" }
                                        MediaManager.downloadMediaWithProgress(
                                            context, msg.id, fullUrl, fName, AppMediaType.AUDIO,
                                            onProgress = { p -> downloadProgress = p },
                                            onComplete = { file ->
                                                downloadedFile = file
                                                downloadProgress = null
                                                PersonalMediaReceiptReporter.reportLocalSave(context, msg.id, file, AppMediaType.AUDIO, currentUserId)
                                                Toast.makeText(context, "Saved to your EDUConnect Chats folder", Toast.LENGTH_SHORT).show()
                                            },
                                            onError = { downloadProgress = null }
                                        )
                                    }
                                },
                                onLongClick = onLongClick
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FileDownload,
                            contentDescription = "Download Audio",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                } else {
                    // Normal Play / Pause
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(if (isMine) Color(0xFF9D80FF) else PCPurple)
                            .combinedClickable(
                                onClick = {
                                    if (isSelectionMode) onClick()
                                    else togglePlay()
                                },
                                onLongClick = onLongClick
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                // Progress line (WhatsApp styled seekbar line)
                Column(modifier = Modifier.weight(1f)) {
                    Slider(
                        value = if (isUserSeeking) seekPosMs.toFloat() else (if (totalDurationMs > 0) currentPosMs.toFloat() else 0f),
                        enabled = isDownloaded,
                        onValueChange = { v ->
                            isUserSeeking = true
                            seekPosMs = v.toInt()
                        },
                        onValueChangeFinished = {
                            GlobalAudioPlayer.seekToMs(seekPosMs)
                            isUserSeeking = false
                        },
                        valueRange = 0f..totalDurationMs.toFloat().coerceAtLeast(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = if (isMine) Color.White else Color(0xFF9D80FF),
                            activeTrackColor = if (isMine) Color.White else Color(0xFF8B6BFF),
                            inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(24.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = displayAudioSubtitle,
                            color = if (isActivelyDownloading || isUp) Color(0xFF9D80FF) else Color.White.copy(alpha = 0.85f),
                            fontSize = 10.5.sp,
                            fontWeight = if (isActivelyDownloading || isUp) FontWeight.Bold else FontWeight.Medium
                        )
                        Text(
                            text = "${ext.uppercase()} Audio",
                            color = PCSubText,
                            fontSize = 10.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(3.dp))

            // Bottom Timestamp & Read/Delivered Ticks
            Row(
                modifier = Modifier.align(Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isFailed) {
                    Text("Failed", color = Color(0xFFFF5252), fontSize = 10.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(4.dp))
                }
                Text(time, color = Color.White.copy(alpha = 0.85f), fontSize = 10.sp)
                if (isMine) {
                    Spacer(Modifier.width(3.dp))
                    val isPending = msg.id < 0 || transferId.isNotBlank() || isUp || MediaManager.mediaProgressMap[msg.id] != null
                    if (isPending) {
                        Icon(
                            imageVector = Icons.Outlined.AccessTime,
                            contentDescription = "Pending",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(11.dp)
                        )
                    } else {
                        PCTickMarks(
                            isRead = msg.is_read,
                            isDelivered = msg.is_delivered || msg.is_read,
                            size = 11.sp
                        )
                    }
                }
            }
        }
    }
}

// File Bubble (Extension-specific icons: PDF=red, DOC=blue, ZIP=orange, etc)
@Composable
private fun PCFileBubble(
    msg: PersonalMessage,
    isMine: Boolean,
    time: String,
    senderName: String,
    nameColor: Color,
    isSelectionMode: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
    isFailed: Boolean = false,
    onRetry: () -> Unit = {}
) {
    val context = LocalContext.current
    val fileName = remember(msg.media_url, msg.text) {
        val raw = msg.media_url?.substringAfterLast("/") ?: msg.text
        if (raw.isBlank() || raw.startsWith("Document") || raw.startsWith("File")) "Document.pdf" else raw
    }
    val ext = remember(fileName) { fileName.substringAfterLast(".", "").lowercase().ifEmpty { "pdf" } }

    val badgeColor = when (ext) {
        "pdf"                                               -> Color(0xFFE53935) // Red
        "doc", "docx", "rtf", "odt", "dot", "dotx"          -> Color(0xFF1E88E5) // Blue
        "xls", "xlsx", "csv", "ods", "xlt", "xltx"          -> Color(0xFF43A047) // Green
        "ppt", "pptx", "odp", "pot", "potx", "pps"          -> Color(0xFFFF7043) // Deep Orange
        "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso" -> Color(0xFFFB8C00) // Amber
        "txt", "log", "json", "xml", "html", "css", "js",
        "py", "kt", "java", "cpp", "c", "h", "cs", "sql"    -> Color(0xFF8E24AA) // Purple
        "apk", "xapk", "apks"                               -> Color(0xFF2E7D32) // Forest Green
        else                                                -> Color(0xFF00897B) // Teal
    }

    val localFile = remember(msg.media_url, fileName, msg.id) {
        MediaManager.findLocalFile(context, msg.id, msg.media_url, AppMediaType.DOCUMENT)
    }
    var downloadedFile by remember(msg.media_url, msg.id) {
        mutableStateOf<File?>(MediaManager.findLocalFile(context, msg.id, msg.media_url, AppMediaType.DOCUMENT))
    }
    val effectiveFile = downloadedFile ?: localFile
    val isDownloaded = effectiveFile != null && effectiveFile.exists() && effectiveFile.length() > 0

    // Detect if this bubble is a live upload tracked by TransferManager
    val transferId = remember(msg.media_url) {
        msg.media_url?.takeIf { it.startsWith("transfer://") }?.removePrefix("transfer://")
    }
    val liveProgress: Float? = if (transferId != null) {
        val st = TransferStateHolder.statusFor(transferId)
        val prog = TransferStateHolder.progressFor(transferId)
        if (st != com.security.myapplication.transfer.TransferStatus.COMPLETED) prog else null
    } else null
    val liveStatus: com.security.myapplication.transfer.TransferStatus? =
        if (transferId != null) TransferStateHolder.statusFor(transferId) else null

    val legacyProgress = MediaManager.mediaProgressMap[msg.id]
        ?: UploadStateManager.get(context, msg.id)?.let { if (it.totalBytes > 0) it.bytesSent.toFloat() / it.totalBytes else null }
    val legacyStatus = UploadStateManager.mediaStatusMap[msg.id]

    val mediaProgress = liveProgress ?: legacyProgress
    val isUploading = isMine && !msg.is_delivered && (
        transferId != null && liveStatus != null &&
        liveStatus != com.security.myapplication.transfer.TransferStatus.COMPLETED &&
        liveStatus != com.security.myapplication.transfer.TransferStatus.FAILED ||
        legacyProgress != null || legacyStatus == UploadStatus.UPLOADING
    )
    val isExplicitPaused = isMine && !msg.is_delivered && (
        liveStatus == com.security.myapplication.transfer.TransferStatus.PAUSED ||
        legacyStatus == UploadStatus.PAUSED || legacyStatus == UploadStatus.CANCELLED
    )
    val isExplicitFailed = isFailed || (isMine && !msg.is_delivered && (
        liveStatus == com.security.myapplication.transfer.TransferStatus.FAILED ||
        legacyStatus == UploadStatus.FAILED
    ))
    val isCancelledOrFailed = isExplicitPaused || isExplicitFailed
    val isDownloading = mediaProgress != null && !isMine

    fun handleFileAction() {
        if (isUploading) {
            if (transferId != null) TransferManager.pause(context, transferId)
            else UniversalUploader.cancel(context, msg.id)
            return
        }
        if (isCancelledOrFailed) {
            onRetry()
            return
        }
        if (isMine) {
            val fileToOpen = effectiveFile
                ?: MediaManager.findLocalFile(context, msg.id, msg.media_url, AppMediaType.DOCUMENT)
                ?: MediaManager.findLocalFile(context, msg.id, fileName, AppMediaType.DOCUMENT)
            if (fileToOpen != null && fileToOpen.exists() && fileToOpen.length() > 0) {
                openDocumentFile(context, fileToOpen)
                return
            }
            if (!msg.media_url.isNullOrEmpty() && !msg.media_url!!.startsWith("transfer://")) {
                val url = msg.media_url ?: ""
                val fName = fileName.ifBlank { "document_${msg.id}.$ext" }
                Toast.makeText(context, "Opening $fName...", Toast.LENGTH_SHORT).show()
                MediaManager.downloadMediaWithProgress(
                    context, msg.id, url, fName, AppMediaType.DOCUMENT,
                    onComplete = { file ->
                        downloadedFile = file
                        openDocumentFile(context, file)
                    },
                    onError = { Toast.makeText(context, "Could not open file", Toast.LENGTH_SHORT).show() }
                )
                return
            }
        }
        if (!isMine && !msg.media_url.isNullOrEmpty()) {
            val existing = effectiveFile ?: MediaManager.findLocalFile(context, msg.id, msg.media_url, AppMediaType.DOCUMENT)
            if (existing != null && existing.exists() && existing.length() > 0) {
                downloadedFile = existing
                openDocumentFile(context, existing)
                return
            }
            val url = msg.media_url ?: ""
            val fName = fileName.ifBlank { "document_${msg.id}.$ext" }
            Toast.makeText(context, "Downloading $fName...", Toast.LENGTH_SHORT).show()
            MediaManager.downloadMediaWithProgress(
                context, msg.id, url, fName, AppMediaType.DOCUMENT,
                onComplete = { file ->
                    downloadedFile = file
                    PersonalMediaReceiptReporter.reportLocalSave(context, msg.id, file, AppMediaType.DOCUMENT, currentUserId)
                    Toast.makeText(context, "Saved to your EDUConnect Chats folder", Toast.LENGTH_SHORT).show()
                    openDocumentFile(context, file)
                },
                onError = { Toast.makeText(context, "Download failed", Toast.LENGTH_SHORT).show() }
            )
        }
    }

    Box(
        modifier = Modifier
            .widthIn(min = 220.dp, max = 280.dp)
            .clip(RoundedCornerShape(16.dp))
            .then(
                if (isMine) Modifier.background(Brush.linearGradient(listOf(PCPurple, PCPurpleDk)))
                else Modifier.background(PCCardBg).border(1.dp, Color(0xFF8B6BFF).copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            )
            .combinedClickable(
                onClick = {
                    if (isSelectionMode) onClick()
                    else handleFileAction()
                },
                onLongClick = onLongClick
            )
            .padding(10.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF120F2B))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Format Badge (e.g. PDF, ZIP, RAR, DOCX, TXT)
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(badgeColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = ext.uppercase().take(4).ifEmpty { "FILE" },
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                Spacer(Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = fileName,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    val statusSubtitle = when {
                        isUploading -> {
                            val pct = ((mediaProgress ?: 0.05f) * 100).toInt().coerceIn(0, 100)
                            "$pct% • Uploading..."
                        }
                        isExplicitPaused -> "Paused • Tap to resume"
                        isExplicitFailed -> "Failed • Tap to retry"
                        isDownloading -> {
                            val pct = ((mediaProgress ?: 0.05f) * 100).toInt().coerceIn(0, 100)
                            "$pct% • Downloading..."
                        }
                        isDownloaded -> "${ext.uppercase()} Document • Tap to open"
                        isMine -> "${ext.uppercase()} Document • Tap to open"
                        else -> "${ext.uppercase()} Document • Tap to download"
                    }
                    Text(
                        text = statusSubtitle,
                        color = if (isExplicitFailed) Color(0xFFFF5252) else if (isExplicitPaused) Color(0xFFFFB74D) else PCSubText,
                        fontSize = 10.5.sp
                    )
                }

                Spacer(Modifier.width(6.dp))

                // Action Indicator
                when {
                    isUploading || isCancelledOrFailed -> {
                        UniversalUploadProgressBadge(
                            tempId = msg.id,
                            transferId = transferId ?: "",
                            isUploading = isUploading,
                            isCancelledOrFailed = isCancelledOrFailed,
                            status = legacyStatus,
                            progress = mediaProgress ?: 0.05f,
                            size = 34.dp,
                            backgroundColor = PCPurple.copy(alpha = 0.4f),
                            onCancel = {
                                if (transferId != null) TransferManager.pause(context, transferId)
                                else UniversalUploader.cancel(context, msg.id)
                            },
                            onRetry = onRetry
                        )
                    }
                    isDownloading -> {
                        Box(
                            modifier = Modifier.size(34.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                progress = { mediaProgress ?: 0.05f },
                                color = PCPurpleLt,
                                trackColor = Color.White.copy(alpha = 0.2f),
                                modifier = Modifier.size(32.dp),
                                strokeWidth = 2.5.dp
                            )
                        }
                    }
                    isDownloaded || isMine -> {
                        // Sender always sees Open icon; receiver sees Open once downloaded
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(PCPurple.copy(alpha = 0.35f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.InsertDriveFile,
                                contentDescription = "Open File",
                                tint = Color.White,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                    else -> {
                        // Receiver: not yet downloaded
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(PCPurple.copy(alpha = 0.3f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.FileDownload,
                                contentDescription = "Download File",
                                tint = Color.White,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }
            }

            if (msg.text.isNotBlank() && msg.text != fileName && !msg.text.startsWith("Document") && !msg.text.startsWith("File")) {
                Spacer(Modifier.height(4.dp))
                Text(msg.text, color = Color.White, fontSize = 13.5.sp)
            }

            Spacer(Modifier.height(3.dp))

            // Bottom row: time + ticks
            Row(
                modifier = Modifier.align(Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isFailed) {
                    Text("Failed", color = Color(0xFFFF5252), fontSize = 10.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(4.dp))
                }
                Text(time, color = Color.White.copy(alpha = 0.85f), fontSize = 10.sp)
                if (isMine) {
                    Spacer(Modifier.width(3.dp))
                    val isPending = msg.id < 0 || isUploading
                    if (isPending) {
                        Icon(
                            imageVector = Icons.Outlined.AccessTime,
                            contentDescription = "Pending",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(11.dp)
                        )
                    } else {
                        PCTickMarks(
                            isRead = msg.is_read,
                            isDelivered = msg.is_delivered || msg.is_read,
                            size = 11.sp
                        )
                    }
                }
            }
        }
    }
}


@Composable
private fun PCImageBubble(
    msg: PersonalMessage,
    isMine: Boolean,
    time: String,
    senderName: String,
    nameColor: Color,
    isSelectionMode: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
    onImageClick: (Bitmap) -> Unit,
    isFailed: Boolean = false,
    onRetry: () -> Unit = {}
) {
    val context = LocalContext.current
    var hasError by remember(msg.media_url) { mutableStateOf(false) }
    var isLoading by remember(msg.media_url) { mutableStateOf(true) }
    var bitmap by remember(msg.media_url) { mutableStateOf<Bitmap?>(null) }

    // -- Live progress and upload state (available throughout the whole bubble) --
    val imgTransferId = remember(msg.media_url) {
        msg.media_url?.takeIf { it.startsWith("transfer://") }?.removePrefix("transfer://")
    }
    val imgLiveProg: Float? = if (imgTransferId != null) {
        val st = TransferStateHolder.statusFor(imgTransferId)
        val pg = TransferStateHolder.progressFor(imgTransferId)
        if (st != com.security.myapplication.transfer.TransferStatus.COMPLETED) pg else null
    } else null
    val imgLiveStatus = if (imgTransferId != null) TransferStateHolder.statusFor(imgTransferId) else null
    val imgLegacyProg = MediaManager.mediaProgressMap[msg.id]
        ?: UploadStateManager.get(context, msg.id)?.let { if (it.totalBytes > 0) it.bytesSent.toFloat() / it.totalBytes else null }
    val imgLegacyStatus = UploadStateManager.mediaStatusMap[msg.id]
    val mediaProgress = imgLiveProg ?: imgLegacyProg
    val isUploading = isMine && !msg.is_delivered && (
        imgTransferId != null && imgLiveStatus != null &&
        imgLiveStatus != com.security.myapplication.transfer.TransferStatus.COMPLETED &&
        imgLiveStatus != com.security.myapplication.transfer.TransferStatus.FAILED ||
        imgLegacyProg != null || imgLegacyStatus == UploadStatus.UPLOADING
    )
    val isExplicitPaused = isMine && !msg.is_delivered && (
        imgLiveStatus == com.security.myapplication.transfer.TransferStatus.PAUSED ||
        imgLegacyStatus == UploadStatus.PAUSED || imgLegacyStatus == UploadStatus.CANCELLED
    )
    val isExplicitFailed = isFailed || (isMine && !msg.is_delivered && (
        imgLiveStatus == com.security.myapplication.transfer.TransferStatus.FAILED ||
        imgLegacyStatus == UploadStatus.FAILED
    ))
    val isCancelledOrFailed = isExplicitPaused || isExplicitFailed

    LaunchedEffect(msg.media_url) {
        val data = msg.media_url
        if (data.isNullOrEmpty()) { isLoading = false; return@LaunchedEffect }

        val cachedMem = MediaBitmapCache.get(data)
        if (cachedMem != null) { bitmap = cachedMem; isLoading = false; return@LaunchedEffect }

        val localFile = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            MediaManager.findLocalFile(context, msg.id, data, AppMediaType.IMAGE)
        }
        if (localFile != null) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                // Thumbnail decode (640px, RGB_565) — 60x less RAM than full-res, prevents OOM & scroll lag
                val bmp = MediaManager.decodeThumbnailBitmap(localFile)
                if (bmp != null) {
                    MediaBitmapCache.put(data, bmp)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        bitmap = bmp; isLoading = false
                    }
                }
            }
            if (bitmap != null) return@LaunchedEffect
        }

        if (data.startsWith("/uploads/") || data.startsWith("uploads/") || data.startsWith("http")) {
            val fullUrl = resolveFullMediaUrl(data)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val connection = (java.net.URL(fullUrl).openConnection() as java.net.HttpURLConnection).apply {
                        connectTimeout = 15_000
                        readTimeout = 20_000
                        instanceFollowRedirects = true
                    }
                    val cachedFile = try {
                        connection.connect()
                        if (connection.responseCode !in 200..299) {
                            throw java.io.IOException("Image request failed: HTTP ${connection.responseCode}")
                        }
                        connection.inputStream.use { input -> MediaManager.saveDiskCache(context, data, input) }
                    } finally {
                        connection.disconnect()
                    }
                    if (cachedFile != null && cachedFile.length() > 0) {
                        val fileName = data.substringAfterLast("/")
                        if (fileName.isNotBlank()) {
                            cachedFile.inputStream().use { MediaManager.saveDiskCache(context, fileName, it) }
                            cachedFile.inputStream().use { MediaManager.saveDiskCache(context, msg.id, fileName, it) }
                        }
                        // Thumbnail decode — small & fast for chat bubbles
                        val bmp = MediaManager.decodeThumbnailBitmap(cachedFile)
                        if (bmp != null) {
                            MediaBitmapCache.put(data, bmp)
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                bitmap = bmp; isLoading = false
                            }
                        } else {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { hasError = true; isLoading = false }
                        }
                    } else {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { hasError = true; isLoading = false }
                    }
                } catch (_: Exception) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { hasError = true; isLoading = false }
                }
            }
            return@LaunchedEffect
        }

        if (data.startsWith("content://") || data.startsWith("file://")) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val bmp = MediaManager.decodeThumbnailBitmap(context, Uri.parse(data))
                    if (bmp != null) {
                        MediaBitmapCache.put(data, bmp)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            bitmap = bmp; isLoading = false
                        }
                    } else {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { hasError = true; isLoading = false }
                    }
                } catch (_: Exception) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { hasError = true; isLoading = false }
                }
            }
            return@LaunchedEffect
        }

        if (data.length > 100) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val bytes = Base64.decode(data.substringAfter("base64,"), Base64.DEFAULT)
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        if (bmp != null) { MediaBitmapCache.put(data, bmp); bitmap = bmp }
                        isLoading = false
                    }
                } catch (_: Exception) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { hasError = true; isLoading = false }
                }
            }
            return@LaunchedEffect
        }

        isLoading = false
    }

    Box(
        modifier = Modifier
            .widthIn(min = 160.dp, max = 250.dp)
            .clip(RoundedCornerShape(16.dp))
            .then(
                if (isMine) Modifier.background(Brush.linearGradient(listOf(PCPurple, PCPurpleDk)))
                else Modifier.background(PCCardBg).border(1.dp, Color(0xFF8B6BFF).copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(5.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(155.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF1D1A3D))
                    .combinedClickable(
                        onClick = {
                            if (isSelectionMode) onClick()
                            else bitmap?.let { onImageClick(it) }
                        },
                        onLongClick = onLongClick
                    ),
                contentAlignment = Alignment.Center
            ) {
                val bmp = bitmap

                if (bmp != null && !hasError) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Image",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }

                when {
                    isUploading || (isCancelledOrFailed && isMine) -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.55f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                UniversalUploadProgressBadge(
                                    tempId = msg.id,
                                    transferId = imgTransferId ?: "",
                                    isUploading = isUploading,
                                    isCancelledOrFailed = isCancelledOrFailed,
                                    status = imgLegacyStatus,
                                    progress = mediaProgress ?: 0.05f,
                                    size = 46.dp,
                                    backgroundColor = PCPurple.copy(alpha = 0.4f),
                                    onCancel = {
                                        if (imgTransferId != null) TransferManager.pause(context, imgTransferId)
                                        else UniversalUploader.cancel(context, msg.id)
                                    },
                                    onRetry = onRetry
                                )
                                val pct = ((mediaProgress ?: 0.05f) * 100).toInt().coerceIn(0, 100)
                                Spacer(Modifier.height(5.dp))
                                Text(
                                    text = if (isUploading) "$pct%" else if (isExplicitPaused) "Paused" else "Retry",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    bmp == null && isLoading -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = PCPurple, modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
                            Spacer(Modifier.height(6.dp))
                            Text("Loading...", color = PCSubText, fontSize = 11.sp)
                        }
                    }
                    bmp == null && hasError -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.combinedClickable(
                                onClick = {
                                    if (isSelectionMode) onClick()
                                    else { hasError = false; isLoading = true }
                                },
                                onLongClick = onLongClick
                            )
                        ) {
                            Icon(imageVector = Icons.Outlined.Refresh, contentDescription = "Reload", tint = PCPurple, modifier = Modifier.size(34.dp))
                            Spacer(Modifier.height(4.dp))
                            Text("Tap to reload", color = PCSubText, fontSize = 11.sp)
                        }
                    }
                    !isMine && bmp != null -> {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.55f))
                                .combinedClickable(
                                    onClick = {
                                        if (isSelectionMode) onClick()
                                        else {
                                            val url = msg.media_url ?: return@combinedClickable
                                            val fullUrl = resolveFullMediaUrl(url)
                                            val fileName = url.substringAfterLast("/").ifBlank { "image_${msg.id}.jpg" }
                                            Toast.makeText(context, "Saving image...", Toast.LENGTH_SHORT).show()
                                            MediaManager.downloadMediaWithProgress(
                                                context = context,
                                                msgId = msg.id,
                                                urlStr = fullUrl,
                                                defaultFileName = fileName,
                                                type = AppMediaType.IMAGE,
                                                onComplete = { file ->
                                                    PersonalMediaReceiptReporter.reportLocalSave(context, msg.id, file, AppMediaType.IMAGE, currentUserId)
                                                    Toast.makeText(context, "Saved to your EDUConnect Chats folder", Toast.LENGTH_SHORT).show()
                                                },
                                                onError = { err ->
                                                    Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                                }
                                            )
                                        }
                                    },
                                    onLongClick = onLongClick
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Download,
                                contentDescription = "Download",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            if (msg.text.isNotBlank() && !msg.text.equals("Photo", ignoreCase = true) && !msg.text.startsWith("Sent an image")) {
                Spacer(Modifier.height(4.dp))
                Text(msg.text, color = Color.White, fontSize = 13.5.sp, modifier = Modifier.padding(horizontal = 4.dp))
            }

            Spacer(Modifier.height(3.dp))

            Row(
                modifier = Modifier.align(Alignment.End).padding(end = 4.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(time, color = Color.White.copy(alpha = 0.85f), fontSize = 10.sp)
                if (isMine) {
                    Spacer(Modifier.width(3.dp))
                    val isPending = isUploading || msg.id < 0
                    if (isPending) {
                        Icon(
                            imageVector = Icons.Outlined.AccessTime,
                            contentDescription = "Pending",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(11.dp)
                        )
                    } else {
                        PCTickMarks(
                            isRead = msg.is_read,
                            isDelivered = msg.is_delivered || msg.is_read,
                            size = 11.sp
                        )
                    }
                }
            }
        }
    }
}

// -- Video Bubble (Upload progress, Download progress, Retry, Caption, Ticks) --
@Composable
private fun PCVideoBubble(
    msg: PersonalMessage,
    isMine: Boolean,
    time: String,
    senderName: String,
    nameColor: Color,
    isSelectionMode: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
    isFailed: Boolean = false,
    onRetry: () -> Unit = {},
    onVideoClick: (String) -> Unit = {}
) {
    val context = LocalContext.current
    var downloadedFile by remember(msg.media_url, msg.id) {
        mutableStateOf<File?>(MediaManager.findLocalFile(context, msg.id, msg.media_url, AppMediaType.VIDEO))
    }
    var thumbBitmap by remember(msg.media_url) { mutableStateOf<Bitmap?>(null) }
    var thumbLoading by remember(msg.media_url) { mutableStateOf(false) }

    // Check if already downloaded
    LaunchedEffect(msg.media_url, msg.id) {
        val data = msg.media_url ?: return@LaunchedEffect
        val local = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            MediaManager.findLocalFile(context, msg.id, data, AppMediaType.VIDEO)
        }
        if (local != null && local.exists() && local.length() > 0) {
            downloadedFile = local
        }
    }

    // Load thumbnail (hardware downsampled to max 480px, saves 90%+ RAM)
    LaunchedEffect(msg.media_url) {
        val data = msg.media_url
        if (data.isNullOrEmpty()) { thumbLoading = false; return@LaunchedEffect }
        val cacheKey = "thumb_$data"
        val cached = MediaBitmapCache.get(cacheKey)
        if (cached != null) { thumbBitmap = cached; thumbLoading = false; return@LaunchedEffect }
        thumbLoading = true; thumbBitmap = null
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val frame = MediaManager.extractVideoThumbnail(context, data, maxDim = 480)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (frame != null) {
                    MediaBitmapCache.put(cacheKey, frame)
                    thumbBitmap = frame
                }
                thumbLoading = false
            }
        }
    }

    // -- Live progress from TransferStateHolder (transfer:// prefix)
    val transferId = remember(msg.media_url) {
        msg.media_url?.takeIf { it.startsWith("transfer://") }?.removePrefix("transfer://")
    }
    val liveProgress: Float? = if (transferId != null) {
        val st = TransferStateHolder.statusFor(transferId)
        val prog = TransferStateHolder.progressFor(transferId)
        if (st != com.security.myapplication.transfer.TransferStatus.COMPLETED) prog else null
    } else null
    val liveStatus: com.security.myapplication.transfer.TransferStatus? =
        if (transferId != null) TransferStateHolder.statusFor(transferId) else null
    val legacyProgress = MediaManager.mediaProgressMap[msg.id]
        ?: UploadStateManager.get(context, msg.id)?.let { if (it.totalBytes > 0) it.bytesSent.toFloat() / it.totalBytes else null }
    val legacyStatus = UploadStateManager.mediaStatusMap[msg.id]
    val mediaProgress = liveProgress ?: legacyProgress
    val isUploading = isMine && !msg.is_delivered && (
        transferId != null && liveStatus != null &&
        liveStatus != com.security.myapplication.transfer.TransferStatus.COMPLETED &&
        liveStatus != com.security.myapplication.transfer.TransferStatus.FAILED ||
        legacyProgress != null || legacyStatus == UploadStatus.UPLOADING
    )
    val isExplicitPaused = isMine && !msg.is_delivered && (
        liveStatus == com.security.myapplication.transfer.TransferStatus.PAUSED ||
        legacyStatus == UploadStatus.PAUSED || legacyStatus == UploadStatus.CANCELLED
    )
    val isExplicitFailed = isFailed || (isMine && !msg.is_delivered && (
        liveStatus == com.security.myapplication.transfer.TransferStatus.FAILED ||
        legacyStatus == UploadStatus.FAILED
    ))
    val isCancelledOrFailed = isExplicitPaused || isExplicitFailed

    // -- WhatsApp-style download state for receiver -----------------------------
    var downloadProgress by remember { mutableStateOf<Float?>(null) }
    var isDownloadCancelled by remember { mutableStateOf(false) }
    val downloadTransferId = "${msg.id}-download"
    val liveDownloadProg: Float? = TransferStateHolder.progressFor(downloadTransferId).takeIf { it > 0f }
        ?: MediaManager.mediaProgressMap[msg.id]
        ?: downloadProgress
    val liveDownloadStatus = TransferStateHolder.statusFor(downloadTransferId)
    val isDownloaded = isMine || (downloadedFile != null && downloadedFile!!.exists() && downloadedFile!!.length() > 0) || (msg.media_url != null && (msg.media_url!!.startsWith("content://") || msg.media_url!!.startsWith("file://")))
    val isActivelyDownloading = !isDownloaded && !isMine && (
        liveDownloadStatus == TransferStatus.DOWNLOADING ||
        liveDownloadStatus == TransferStatus.QUEUED ||
        (liveDownloadProg != null && liveDownloadProg < 1f && liveDownloadProg > 0f)
    )

    Box(
        modifier = Modifier
            .widthIn(min = 160.dp, max = 250.dp)
            .clip(RoundedCornerShape(16.dp))
            .then(
                if (isMine) Modifier.background(Brush.linearGradient(listOf(PCPurple, PCPurpleDk)))
                else Modifier.background(PCCardBg).border(1.dp, Color(0xFF8B6BFF).copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(5.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(155.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF191635))
                    .combinedClickable(
                        onClick = {
                            if (isSelectionMode) onClick()
                            else if (isDownloaded || (isMine && !isUploading && !isCancelledOrFailed)) {
                                val f = downloadedFile
                                val url = msg.media_url ?: ""
                                val target = when {
                                    f != null && f.exists() -> Uri.fromFile(f).toString()
                                    url.startsWith("content://") || url.startsWith("file://") -> url
                                    url.startsWith("/uploads/") -> ApiClient.BASE_URL.removeSuffix("/") + url
                                    else -> url
                                }
                                if (target.isNotBlank()) onVideoClick(target)
                            }
                        },
                        onLongClick = onLongClick
                    ),
                contentAlignment = Alignment.Center
            ) {
                val thumb = thumbBitmap
                if (thumb != null) {
                    Image(
                        bitmap = thumb.asImageBitmap(),
                        contentDescription = "Video thumbnail",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else if (thumbLoading) {
                    CircularProgressIndicator(color = PCPurple, modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
                } else {
                    Icon(Icons.Default.VideoFile, contentDescription = null, tint = PCPurple, modifier = Modifier.size(42.dp))
                }

                // -- SENDER: Upload progress overlay (0-100%) --------------------
                if (isUploading || (isCancelledOrFailed && isMine)) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.60f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            UniversalUploadProgressBadge(
                                tempId = msg.id,
                                transferId = transferId ?: "",
                                isUploading = isUploading,
                                isCancelledOrFailed = isCancelledOrFailed,
                                status = legacyStatus,
                                progress = mediaProgress ?: 0.05f,
                                size = 46.dp,
                                backgroundColor = PCPurple.copy(alpha = 0.4f),
                                onCancel = {
                                    if (transferId != null) TransferManager.pause(context, transferId)
                                    else UniversalUploader.cancel(context, msg.id)
                                },
                                onRetry = onRetry
                            )
                            val pct = ((mediaProgress ?: 0.05f) * 100).toInt().coerceIn(0, 100)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = if (isUploading) "$pct%" else if (isExplicitPaused) "Paused • Tap to resume" else "Failed • Tap to retry",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                // -- RECEIVER: Download in progress (circular progress + X to cancel) ---
                else if (!isMine && isActivelyDownloading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(
                                    progress = { liveDownloadProg ?: 0.05f },
                                    color = Color.White,
                                    trackColor = Color.White.copy(alpha = 0.2f),
                                    modifier = Modifier.size(44.dp),
                                    strokeWidth = 3.dp
                                )
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Cancel",
                                    tint = Color.White,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .combinedClickable(
                                            onClick = {
                                                if (isSelectionMode) onClick()
                                                else {
                                                    MediaManager.cancelDownload(msg.id)
                                                    downloadProgress = null
                                                    isDownloadCancelled = true
                                                }
                                            },
                                            onLongClick = onLongClick
                                        )
                                )
                            }
                            val pct = ((liveDownloadProg ?: 0f) * 100).toInt().coerceIn(0, 100)
                            Spacer(Modifier.height(6.dp))
                            Text("$pct% • Tap X to cancel", color = Color.White, fontSize = 11.sp)
                        }
                    }
                }
                // RECEIVER: Not yet downloaded — show Tap to download button
                else if (!isMine && !isDownloaded) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.combinedClickable(
                                onClick = {
                                    if (isSelectionMode) onClick()
                                    else {
                                        val url = msg.media_url ?: return@combinedClickable
                                        val fullUrl = resolveFullMediaUrl(url)
                                        val fname = url.substringAfterLast("/").ifBlank { "video_${msg.id}.mp4" }
                                        Toast.makeText(context, "Saving video...", Toast.LENGTH_SHORT).show()
                                        MediaManager.downloadMediaWithProgress(
                                            context = context,
                                            msgId = msg.id,
                                            urlStr = fullUrl,
                                            defaultFileName = fname,
                                            type = AppMediaType.VIDEO,
                                            onProgress = { p -> downloadProgress = p },
                                            onComplete = { file ->
                                                downloadedFile = file
                                                downloadProgress = null
                                                PersonalMediaReceiptReporter.reportLocalSave(context, msg.id, file, AppMediaType.VIDEO, currentUserId)
                                                Toast.makeText(context, "Saved to your EDUConnect Chats folder", Toast.LENGTH_SHORT).show()
                                            },
                                            onError = { err ->
                                                downloadProgress = null
                                                Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                            }
                                        )
                                    }
                                },
                                onLongClick = onLongClick
                            )
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(PCPurple),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Outlined.Download, null, tint = Color.White, modifier = Modifier.size(24.dp))
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("Tap to download", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                // RECEIVER: Fully downloaded — show Play button
                // SENDER: Always show Play (local file available)
                else if (isDownloaded || isMine && !isUploading && !isCancelledOrFailed) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f))
                            .combinedClickable(
                                onClick = {
                                    if (isSelectionMode) onClick()
                                    else {
                                        val f = downloadedFile
                                        val url = msg.media_url ?: ""
                                        val target = when {
                                            f != null && f.exists() -> Uri.fromFile(f).toString()
                                            url.startsWith("content://") || url.startsWith("file://") -> url
                                            url.startsWith("/uploads/") -> ApiClient.BASE_URL.removeSuffix("/") + url
                                            else -> url
                                        }
                                        if (target.isNotBlank()) onVideoClick(target)
                                    }
                                },
                                onLongClick = onLongClick
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                }
            }

            if (msg.text.isNotBlank() && !msg.text.equals("Video", ignoreCase = true) && !msg.text.startsWith("Sent a video")) {
                Spacer(Modifier.height(4.dp))
                Text(msg.text, color = Color.White, fontSize = 13.5.sp, modifier = Modifier.padding(horizontal = 4.dp))
            }

            Spacer(Modifier.height(3.dp))

            Row(
                modifier = Modifier.align(Alignment.End).padding(end = 4.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(time, color = Color.White.copy(alpha = 0.85f), fontSize = 10.sp)
                if (isMine) {
                    Spacer(Modifier.width(3.dp))
                    val isPending = isUploading || msg.id < 0
                    if (isPending) {
                        Icon(
                            imageVector = Icons.Outlined.AccessTime,
                            contentDescription = "Pending",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(11.dp)
                        )
                    } else {
                        PCTickMarks(
                            isRead = msg.is_read,
                            isDelivered = msg.is_delivered || msg.is_read,
                            size = 11.sp
                        )
                    }
                }
            }
        }
    }
}


// -- Approval / Verification Card (Premium Luxury Design matching screenshot) ---
@Composable
private fun PCApprovalCard(
    msg: PersonalMessage,
    currentUserId: Int,
    onStatusChanged: () -> Unit
) {
    val reqIdMatch = Regex("\\[APPROVAL_REQUEST:(\\d+)\\]").find(msg.text)
    val requestId = reqIdMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
    val displayText = msg.text.replace(Regex("\\[APPROVAL_REQUEST:\\d+\\]"), "").trim()

    var statusState by remember { mutableStateOf<String?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(requestId) {
        if (requestId > 0) {
            try {
                val detail = ApiClient.apiService.getApprovalRequest(requestId)
                statusState = detail.status
            } catch (_: Exception) { statusState = "PENDING" }
        }
    }

    val isOwner = msg.receiver_id == currentUserId

    Box(
        modifier = Modifier
            .fillMaxWidth(0.88f)
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF1A1638), Color(0xFF201B45))
                )
            )
            .border(
                1.dp,
                Brush.linearGradient(listOf(PCPurple.copy(alpha = 0.6f), PCPurpleDk.copy(alpha = 0.4f))),
                RoundedCornerShape(18.dp)
            )
            .padding(16.dp)
    ) {
        Column {
            // Header: Shield icon + Title
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(listOf(PCPurple.copy(alpha = 0.3f), PCPurpleDk.copy(alpha = 0.2f)))
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.Shield,
                        null,
                        tint = PCPurpleLt,
                        modifier = Modifier.size(24.dp)
                    )
                    Icon(
                        Icons.Default.Check,
                        null,
                        tint = PCPurpleLt,
                        modifier = Modifier.size(13.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "Verification Request",
                        color = PCPurpleLt,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Subject Approval Needed",
                        color = PCSubText,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Divider line
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(PCBorder)
            )

            Spacer(Modifier.height(12.dp))

            // Display text
            Text(
                displayText,
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 13.5.sp,
                lineHeight = 19.sp
            )

            Spacer(Modifier.height(16.dp))

            // Status / Action buttons
            when {
                statusState == "ACCEPTED" -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF0D3B27))
                            .border(1.dp, AcceptGreen.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, null, tint = AcceptGreen, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("APPROVED - Active", color = AcceptGreen, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                }
                statusState == "DECLINED" -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF3B0D0D))
                            .border(1.dp, DeclineRed.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Cancel, null, tint = DeclineRed, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("DECLINED - Rejected", color = DeclineRed, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                }
                isOwner && statusState != null -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Accept button - premium green pill
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(Color(0xFF00C853), Color(0xFF00BFA5))
                                    )
                                )
                                .clickable(enabled = !isProcessing) {
                                    coroutineScope.launch {
                                        isProcessing = true
                                        try {
                                            ApiClient.apiService.respondApprovalRequest(
                                                requestId,
                                                ApprovalRespondRequest(user_id = currentUserId, status = "ACCEPTED")
                                            )
                                            statusState = "ACCEPTED"
                                            onStatusChanged()
                                        } catch (_: Exception) {}
                                        isProcessing = false
                                    }
                                }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isProcessing) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(17.dp))
                                    Spacer(Modifier.width(5.dp))
                                    Text("Accept", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                }
                            }
                        }

                        // Decline button - premium red pill
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(Color(0xFFFF1744), Color(0xFFFF6D00))
                                    )
                                )
                                .clickable(enabled = !isProcessing) {
                                    coroutineScope.launch {
                                        isProcessing = true
                                        try {
                                            ApiClient.apiService.respondApprovalRequest(
                                                requestId,
                                                ApprovalRespondRequest(user_id = currentUserId, status = "DECLINED")
                                            )
                                            statusState = "DECLINED"
                                            onStatusChanged()
                                        } catch (_: Exception) {}
                                        isProcessing = false
                                    }
                                }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Close, null, tint = Color.White, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(5.dp))
                                Text("Decline", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                    }
                }
                else -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF1E1A40))
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (statusState == null) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), color = PCPurple, strokeWidth = 2.dp)
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.HourglassEmpty, null, tint = Color(0xFFFFB300), modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Pending approval...", color = Color(0xFFFFB300), fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}




// -- Helpers -------------------------------------------------------------------
@Composable
fun PCDefaultAvatar(name: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(PCPurple, PCPurpleDk))),
        contentAlignment = Alignment.Center
    ) {
        Text(
            name.firstOrNull()?.uppercaseChar()?.toString() ?: "U",
            color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold
        )
    }
}

private fun startPCVoiceRecording(context: Context, onStarted: (MediaRecorder, File) -> Unit) {
    try {
        val active = VoiceNoteRecorder.start(context, "voice_")
        onStarted(active.recorder, active.file)
    } catch (e: Exception) {
        Toast.makeText(context, "Could not start voice recording", Toast.LENGTH_SHORT).show()
    }
}

private fun stopPCVoiceRecording(rec: MediaRecorder?, audioFile: File?, onDone: (String?) -> Unit) {
    try {
        rec?.stop()
        rec?.release()
        audioFile?.let { file ->
            // Keep legacy callback semantics, but never read the complete
            // recording on the recorder/UI thread.
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    val b64 = FileInputStream(file).use { Base64.encodeToString(it.readBytes(), Base64.DEFAULT) }
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onDone(b64) }
                } catch (_: Exception) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onDone(null) }
                }
            }
        } ?: onDone(null)
    } catch (_: Exception) { onDone(null) }
}

private fun pcExtractOrGenerateWaveform(text: String, mediaUrl: String?, targetBarCount: Int = 54): List<Int> {
    if (text.contains("|")) {
        val rawStr = text.substringAfter("|").trim()
        val parsed = rawStr.split(",").mapNotNull { it.toIntOrNull() }
        if (parsed.isNotEmpty()) {
            val result = mutableListOf<Int>()
            for (i in 0 until targetBarCount) {
                val index = (i.toFloat() / targetBarCount * parsed.size).toInt().coerceIn(0, parsed.size - 1)
                result.add(parsed[index].coerceIn(4, 28))
            }
            return result
        }
    }
    val seed = kotlin.math.abs((mediaUrl ?: text).hashCode())
    val random = kotlin.random.Random(seed.toLong())
    val bars = mutableListOf<Int>()
    var currentVal = 14
    for (i in 0 until targetBarCount) {
        val delta = random.nextInt(-8, 9)
        currentVal = (currentVal + delta).coerceIn(5, 28)
        val posFactor = if (i < 3 || i > targetBarCount - 4) 0.5f else 1.0f
        val finalH = (currentVal * posFactor).toInt().coerceIn(4, 28)
        bars.add(finalH)
    }
    return bars
}

// In-App Video Player Dialog Component — WhatsApp-style smooth video player
@Composable
private fun FullscreenInAppVideoPlayer(videoUrlStr: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // -- Resolve URL ---------------------------------------------------------
    val fullUrl = remember(videoUrlStr) {
        when {
            videoUrlStr.startsWith("content://") -> videoUrlStr
            videoUrlStr.startsWith("file:///")   -> videoUrlStr
            videoUrlStr.startsWith("file://")    -> videoUrlStr
            videoUrlStr.startsWith("/") && !videoUrlStr.startsWith("/uploads/") -> "file://$videoUrlStr"
            videoUrlStr.startsWith("/uploads/")  -> ApiClient.BASE_URL.removeSuffix("/") + videoUrlStr
            videoUrlStr.startsWith("uploads/")   -> ApiClient.BASE_URL.removeSuffix("/") + "/" + videoUrlStr
            videoUrlStr.startsWith("http://") || videoUrlStr.startsWith("https://") -> videoUrlStr
            else -> ApiClient.BASE_URL.removeSuffix("/") + "/" + videoUrlStr.removePrefix("/")
        }
    }
    // Prefer Cloudinary's adaptive HLS playlist for remote video.  The
    // existing error path downloads the original fullUrl if HLS is still being
    // processed, so playback remains available during the transition.
    val playbackUrl = remember(fullUrl) { cloudinaryAdaptiveHlsUrl(fullUrl) ?: fullUrl }

    // -- Aspect ratio via metadata retriever (background thread) -------------
    var videoAspectRatio by remember { mutableFloatStateOf(9f / 16f) }
    LaunchedEffect(fullUrl) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val retriever = android.media.MediaMetadataRetriever()
                if (fullUrl.startsWith("http://") || fullUrl.startsWith("https://")) {
                    retriever.setDataSource(fullUrl, HashMap())
                } else if (fullUrl.startsWith("content://")) {
                    retriever.setDataSource(context, Uri.parse(fullUrl))
                } else {
                    val path = fullUrl.removePrefix("file://").let { if (!it.startsWith("/")) "/$it" else it }
                    val f = File(path)
                    if (f.exists()) retriever.setDataSource(f.absolutePath)
                    else retriever.setDataSource(context, Uri.parse(fullUrl))
                }
                val w = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toFloatOrNull() ?: 16f
                val h = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toFloatOrNull() ?: 9f
                val rot = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                retriever.release()
                val ratio = if (rot == 90 || rot == 270) h / w else w / h
                if (ratio > 0f) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        videoAspectRatio = ratio
                    }
                }
            } catch (_: Exception) {}
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {

        // -- Player state --------------------------------------------------
        var player        by remember { mutableStateOf<MediaPlayer?>(null) }
        var isPlaying     by remember { mutableStateOf(false) }
        var isBuffering   by remember { mutableStateOf(true) }
        var isError       by remember { mutableStateOf(false) }
        var currentPosMs  by remember { mutableStateOf(0) }
        var totalDurMs    by remember { mutableStateOf(1) }
        var isUserSeeking by remember { mutableStateOf(false) }
        var showControls  by remember { mutableStateOf(true) }
        val isFrontAuto   = remember(fullUrl) { fullUrl.contains("front_vid_") || fullUrl.contains("front_") }
        var isMirrored    by remember(fullUrl) { mutableStateOf(isFrontAuto) }
        var hideJob       by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
        var seekJob       by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

        // Auto-hide controls after 3 seconds
        fun scheduleHide() {
            hideJob?.cancel()
            hideJob = scope.launch {
                delay(3000)
                showControls = false
            }
        }
        fun showAndScheduleHide() {
            showControls = true
            scheduleHide()
        }

        fun togglePlayPause() {
            player?.let { mp ->
                try {
                    if (mp.isPlaying) {
                        mp.pause()
                        isPlaying = false
                    } else {
                        mp.start()
                        isPlaying = true
                    }
                } catch (_: Exception) {}
            }
            showAndScheduleHide()
        }

        // -- Cleanup --------------------------------------------------------
        DisposableEffect(fullUrl) {
            onDispose {
                hideJob?.cancel()
                seekJob?.cancel()
                try { player?.stop(); player?.release() } catch (_: Exception) {}
                player = null
            }
        }

        // -- Progress poller ------------------------------------------------
        LaunchedEffect(Unit) {
            while (isActive) {
                val mp = player
                if (mp != null && !isUserSeeking) {
                    try {
                        val dur = mp.duration
                        if (dur > 0) totalDurMs = dur
                        currentPosMs = mp.currentPosition
                    } catch (_: Exception) {}
                }
                delay(100)
            }
        }

        // -- Show controls on start -----------------------------------------
        LaunchedEffect(Unit) { scheduleHide() }

        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            // -- Video surface ---------------------------------------------
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .aspectRatio(videoAspectRatio, matchHeightConstraintsFirst = true),
                contentAlignment = Alignment.Center
            ) {
                AndroidView(
                    factory = { ctx ->
                        TextureView(ctx).apply {
                            fun startMedia(st: SurfaceTexture) {
                                if (player != null) return
                                try {
                                    val mp = MediaPlayer()
                                    mp.setAudioAttributes(
                                        android.media.AudioAttributes.Builder()
                                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                            .build()
                                    )
                                    fun configureAndPrepare(source: String, isUri: Boolean = false) {
                                        try {
                                            mp.reset()
                                            mp.setAudioAttributes(
                                                android.media.AudioAttributes.Builder()
                                                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                                                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                                    .build()
                                            )
                                            if (isUri) {
                                                val uri = Uri.parse(source)
                                                if (source.startsWith("content://")) {
                                                    ctx.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                                                        mp.setDataSource(pfd.fileDescriptor)
                                                    } ?: mp.setDataSource(ctx, uri)
                                                } else {
                                                    mp.setDataSource(ctx, uri)
                                                }
                                            } else {
                                                val f = File(source)
                                                if (f.exists()) mp.setDataSource(f.absolutePath)
                                                else mp.setDataSource(source)
                                            }
                                            mp.setSurface(Surface(st))
                                            mp.isLooping = false
                                            mp.setOnCompletionListener {
                                                isPlaying = false
                                                currentPosMs = totalDurMs
                                            }
                                            mp.setOnInfoListener { _, what, _ ->
                                                when (what) {
                                                    MediaPlayer.MEDIA_INFO_BUFFERING_START -> isBuffering = true
                                                    MediaPlayer.MEDIA_INFO_BUFFERING_END   -> isBuffering = false
                                                    MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> isBuffering = false
                                                }
                                                true
                                            }
                                            mp.setOnErrorListener { _, _, _ ->
                                                if (!isPlaying) { isError = true; isBuffering = false }
                                                true
                                            }
                                            mp.setOnPreparedListener { p ->
                                                try {
                                                    totalDurMs = p.duration.takeIf { it > 0 } ?: 1
                                                    p.start()
                                                    isPlaying   = true
                                                    isBuffering = false
                                                    isError     = false
                                                } catch (_: Exception) {}
                                            }
                                            mp.prepareAsync()
                                        } catch (_: Exception) {
                                            isError = true
                                            isBuffering = false
                                        }
                                    }

                                    // 1. Check disk cache first
                                    val cached = MediaManager.getDiskCachedFile(ctx, fullUrl)
                                        ?: MediaManager.getDiskCachedFile(ctx, fullUrl.substringAfterLast("/"))
                                    if (cached != null && cached.exists() && cached.length() > 0) {
                                        configureAndPrepare(cached.absolutePath, false)
                                    } else if (fullUrl.startsWith("content://") || fullUrl.startsWith("file://")) {
                                        configureAndPrepare(fullUrl, true)
                                    } else {
                                        // Remote HTTP URL: try streaming with automatic download fallback
                                        try {
                                            mp.setDataSource(playbackUrl)
                                            mp.setSurface(Surface(st))
                                            mp.isLooping = false
                                            mp.setOnCompletionListener {
                                                isPlaying = false
                                                currentPosMs = totalDurMs
                                            }
                                            mp.setOnErrorListener { _, what, extra ->
                                                // Fallback to background download & play from cache
                                                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                                    try {
                                                        val u = java.net.URL(fullUrl)
                                                        val conn = u.openConnection() as java.net.HttpURLConnection
                                                        conn.connectTimeout = 10000
                                                        conn.readTimeout = 30000
                                                        try {
                                                            conn.connect()
                                                            if (conn.responseCode == 200) {
                                                                val saved = conn.inputStream.use { input -> MediaManager.saveDiskCache(ctx, fullUrl, input) }
                                                                if (saved != null && saved.exists() && saved.length() > 0) {
                                                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                                        configureAndPrepare(saved.absolutePath, false)
                                                                    }
                                                                    return@launch
                                                                }
                                                            }
                                                        } finally {
                                                            conn.disconnect()
                                                        }
                                                    } catch (_: Exception) {}
                                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                        if (!isPlaying) { isError = true; isBuffering = false }
                                                    }
                                                }
                                                true
                                            }
                                            mp.setOnPreparedListener { p ->
                                                try {
                                                    totalDurMs = p.duration.takeIf { it > 0 } ?: 1
                                                    p.start()
                                                    isPlaying   = true
                                                    isBuffering = false
                                                    isError     = false
                                                } catch (_: Exception) {}
                                            }
                                            mp.prepareAsync()
                                        } catch (e: Exception) {
                                            // Stream initiation failed -> download & play
                                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                                try {
                                                    val u = java.net.URL(fullUrl)
                                                    val conn = u.openConnection() as java.net.HttpURLConnection
                                                    conn.connectTimeout = 10000
                                                    conn.readTimeout = 30000
                                                    try {
                                                        conn.connect()
                                                        if (conn.responseCode == 200) {
                                                            val saved = conn.inputStream.use { input -> MediaManager.saveDiskCache(ctx, fullUrl, input) }
                                                            if (saved != null && saved.exists() && saved.length() > 0) {
                                                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                                    configureAndPrepare(saved.absolutePath, false)
                                                                }
                                                                return@launch
                                                            }
                                                        }
                                                    } finally {
                                                        conn.disconnect()
                                                    }
                                                } catch (_: Exception) {}
                                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                    isError = true; isBuffering = false
                                                }
                                            }
                                        }
                                    }
                                    player = mp
                                } catch (e: Exception) {
                                    isError = true
                                    isBuffering = false
                                }
                            }

                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = startMedia(st)
                                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                    try {
                                        val p = player; player = null
                                        p?.setOnPreparedListener(null); p?.setOnErrorListener(null)
                                        if (p?.isPlaying == true) p.stop(); p?.release()
                                    } catch (_: Exception) {}
                                    return true
                                }
                                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                            }
                            if (isAvailable && surfaceTexture != null) startMedia(surfaceTexture!!)
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    if (showControls) {
                                        showControls = false
                                        hideJob?.cancel()
                                    } else {
                                        showAndScheduleHide()
                                    }
                                },
                                onDoubleTap = { offset ->
                                    val isLeft = offset.x < size.width / 2
                                    player?.let { mp ->
                                        try {
                                            val newPos = (mp.currentPosition + if (isLeft) -10000 else 10000)
                                                .coerceIn(0, totalDurMs)
                                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                                mp.seekTo(newPos.toLong(), MediaPlayer.SEEK_CLOSEST)
                                            } else {
                                                mp.seekTo(newPos)
                                            }
                                            currentPosMs = newPos
                                        } catch (_: Exception) {}
                                    }
                                    showAndScheduleHide()
                                }
                            )
                        }
                )
            }

            // -- Buffering spinner ------------------------------------------
            if (isBuffering && !isError) {
                CircularProgressIndicator(
                    color = Color(0xFF8B6BFF),
                    modifier = Modifier.size(52.dp),
                    strokeWidth = 3.dp
                )
            }

            // -- Error state ------------------------------------------------
            if (isError) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.ErrorOutline, null, tint = Color.White.copy(0.7f), modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("Video could not be played", color = Color.White.copy(0.7f), fontSize = 14.sp)
                }
            }

            // -- Controls overlay (auto-hide) -------------------------------
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(Modifier.fillMaxSize()) {

                    // Top gradient + back button + mirror flip toggle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopStart)
                            .background(Brush.verticalGradient(listOf(Color.Black.copy(0.75f), Color.Transparent)))
                            .statusBarsPadding()
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Default.ArrowBack, null, tint = Color.White)
                        }
                        Spacer(Modifier.width(4.dp))
                        Text("Video", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))

                        IconButton(
                            onClick = {
                                isMirrored = !isMirrored
                                showAndScheduleHide()
                            },
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(if (isMirrored) Color(0xFF8B6BFF).copy(alpha = 0.4f) else Color.Black.copy(alpha = 0.35f))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Flip,
                                contentDescription = "Mirror Video",
                                tint = if (isMirrored) Color(0xFF9D80FF) else Color.White
                            )
                        }
                    }

                    // Centre play/pause button
                    Box(
                        modifier = Modifier.align(Alignment.Center)
                            .size(68.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(0.60f))
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) {
                                togglePlayPause()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(38.dp)
                        )
                    }

                    // Bottom gradient + seekbar + time
                    val cSec = (currentPosMs / 1000) % 60
                    val cMin = (currentPosMs / 1000) / 60
                    val tSec = (totalDurMs   / 1000) % 60
                    val tMin = (totalDurMs   / 1000) / 60
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.85f))))
                            .navigationBarsPadding()
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Slider(
                            value = currentPosMs.toFloat().coerceIn(0f, totalDurMs.toFloat().coerceAtLeast(1f)),
                            onValueChange = { v ->
                                isUserSeeking = true
                                currentPosMs  = v.toInt()
                                showAndScheduleHide()
                                seekJob?.cancel()
                                seekJob = scope.launch {
                                    delay(25)
                                    player?.let { mp ->
                                        try {
                                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                                mp.seekTo(v.toLong(), MediaPlayer.SEEK_CLOSEST)
                                            } else {
                                                mp.seekTo(v.toInt())
                                            }
                                        } catch (_: Exception) {}
                                    }
                                }
                            },
                            onValueChangeFinished = {
                                seekJob?.cancel()
                                player?.let { mp ->
                                    try {
                                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                            mp.seekTo(currentPosMs.toLong(), MediaPlayer.SEEK_CLOSEST)
                                        } else {
                                            mp.seekTo(currentPosMs)
                                        }
                                    } catch (_: Exception) {}
                                }
                                isUserSeeking = false
                            },
                            valueRange = 0f..totalDurMs.toFloat().coerceAtLeast(1f),
                            colors = SliderDefaults.colors(
                                thumbColor        = Color(0xFF8B6BFF),
                                activeTrackColor  = Color(0xFF8B6BFF),
                                inactiveTrackColor = Color.White.copy(0.25f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "%d:%02d".format(cMin, cSec),
                                color = Color.White.copy(0.9f), fontSize = 12.5.sp, fontWeight = FontWeight.Medium
                            )
                            Text(
                                "%d:%02d".format(tMin, tSec),
                                color = Color.White.copy(0.6f), fontSize = 12.5.sp, fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    // Double-tap hint overlays (left / right)
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Box(Modifier.fillMaxHeight().weight(1f))  // left tap zone
                        Box(Modifier.fillMaxHeight().weight(1f))  // right tap zone
                    }
                }
            }
        }
    }
}

/** Resume a personal transfer by UUID transferId via TransferManager. */
fun resumePersonalUpload(context: Context, transferId: String) {
    TransferManager.resume(context, transferId)
}

/** Resume a personal transfer by legacy Int tempId. */
fun resumePersonalUpload(
    context: Context,
    tempId: Int,
    onSuccess: (PersonalMessage) -> Unit = {}
) {
    UniversalUploader.resume(
        context = context,
        tempId = tempId,
        onPersonalSuccess = onSuccess
    )
}

/** Pause a personal transfer by UUID transferId via TransferManager. */
fun pausePersonalUpload(context: Context, transferId: String) {
    TransferManager.pause(context, transferId)
}



private fun getIsoUtcTimestamp(timeMillis: Long = System.currentTimeMillis()): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    sdf.timeZone = TimeZone.getTimeZone("UTC")
    return sdf.format(Date(timeMillis))
}

private fun resolveFullMediaUrl(url: String?): String {
    if (url.isNullOrBlank()) return ""
    return when {
        url.startsWith("http://") || url.startsWith("https://") -> url
        url.startsWith("content://") || url.startsWith("file://") -> url
        url.startsWith("/uploads/") -> ApiClient.BASE_URL.removeSuffix("/") + url
        url.startsWith("uploads/") -> ApiClient.BASE_URL.removeSuffix("/") + "/" + url
        !url.contains("://") && !url.startsWith("/") -> ApiClient.BASE_URL.removeSuffix("/") + "/" + url
        else -> url
    }
}

private fun formatPCTime(timestamp: String?): String {
    if (timestamp.isNullOrEmpty()) return ""
    val clean = timestamp.trim()
    val patterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss.SSSSSS",
        "yyyy-MM-dd'T'HH:mm:ss.SSS",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss",
        "HH:mm:ss",
        "HH:mm"
    )
    for (pat in patterns) {
        try {
            val sdf = SimpleDateFormat(pat, Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            val date = sdf.parse(clean) ?: continue
            val out = SimpleDateFormat("h:mm a", Locale.getDefault())
            out.timeZone = TimeZone.getDefault()  // device local timezone (PAK, CHN, etc)
            return out.format(date)
        } catch (_: Exception) {}
    }
    return ""
}

private val PCNameColors = listOf(
    Color(0xFF9D80FF), Color(0xFF4DD9AC), Color(0xFFFF8A65),
    Color(0xFF4FC3F7), Color(0xFFFFD54F), Color(0xFFBA68C8)
)

private fun pcGenerateNameColor(userId: Int): Color {
    val hash = kotlin.math.abs(userId.hashCode())
    return PCNameColors[hash % PCNameColors.size]
}

@Composable
private fun PCSwipeToReplyBox(
    modifier: Modifier = Modifier,
    onSwipeToReply: () -> Unit,
    content: @Composable () -> Unit
) {
    var offsetX by remember { mutableStateOf(0f) }
    val animatedOffset by androidx.compose.animation.core.animateFloatAsState(
        targetValue   = offsetX,
        animationSpec = AppMotion.springSnappy
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (offsetX > 70f) {
                            onSwipeToReply()
                        }
                        offsetX = 0f
                    },
                    onDragCancel = { offsetX = 0f },
                    onHorizontalDrag = { _, dragAmount ->
                        if (dragAmount > 0 || offsetX > 0) {
                            offsetX = (offsetX + dragAmount * 0.5f).coerceIn(0f, 130f)
                        }
                    }
                )
            }
    ) {
        if (animatedOffset > 15f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 8.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF8B6BFF).copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.Reply,
                    contentDescription = "Reply",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Box(
            modifier = Modifier.graphicsLayer {
                translationX = animatedOffset
            }
        ) {
            content()
        }
    }
}

@Composable
private fun PCAttachmentItem(
    icon: ImageVector,
    label: String,
    gradient: List<Color>,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
            .padding(vertical = 4.dp, horizontal = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(gradient)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun openDocumentFile(context: Context, file: File, mimeType: String = "") {
    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val ext = file.extension.lowercase()
        val resolvedMime = if (mimeType.isNotBlank() && mimeType != "*/*" && mimeType != "application/octet-stream") mimeType else {
            when (ext) {
                "pdf" -> "application/pdf"
                "doc", "docx" -> "application/msword"
                "xls", "xlsx" -> "application/vnd.ms-excel"
                "ppt", "pptx" -> "application/vnd.ms-powerpoint"
                "zip" -> "application/zip"
                "rar" -> "application/x-rar-compressed"
                "7z" -> "application/x-7z-compressed"
                "tar" -> "application/x-tar"
                "gz" -> "application/gzip"
                "txt", "log" -> "text/plain"
                "csv" -> "text/csv"
                "json" -> "application/json"
                "html", "htm" -> "text/html"
                "xml" -> "text/xml"
                "apk" -> "application/vnd.android.package-archive"
                else -> "*/*"
            }
        }
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, resolvedMime)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            val genericIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "*/*")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(genericIntent)
        } catch (_: Exception) {
            Toast.makeText(context, "No app found to open this file", Toast.LENGTH_SHORT).show()
        }
    }
}
