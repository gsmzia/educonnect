package com.security.myapplication.posts

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.security.myapplication.network.ApiClient
import com.security.myapplication.transfer.TransferManager
import com.security.myapplication.transfer.TransferStateHolder
import com.security.myapplication.transfer.TransferStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.IOException
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.*

enum class CreatePostAction {
    NONE,
    PICK_PHOTO,
    PICK_VIDEO,
    PICK_AUDIO,
    PICK_FILE,
    OPEN_POLL
}

/**
 * Dedicated Create Post Screen / Composer.
 * Supports Text-Only, Media-Only, or Text + Media (Image, Video, Audio, Document, Poll).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatePostScreen(
    currentUserId: Int,
    currentUserName: String,
    currentUserRole: String,
    currentUserPic: String? = null,
    currentUserDegree: String? = null,
    initialAction: CreatePostAction = CreatePostAction.NONE,
    onPostCreated: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var postText by rememberSaveable(currentUserId) {
        mutableStateOf(SessionDraftStore.getPost(context, currentUserId))
    }
    var selectedMedia by remember { mutableStateOf<PostMedia?>(null) }
    var isPosting by remember { mutableStateOf(false) }
    val authorAvatarBitmap by rememberPostAvatarBitmap(currentUserPic)

    // Poll creation state
    var showPollDialog by rememberSaveable { mutableStateOf(false) }
    var pollQuestion by rememberSaveable { mutableStateOf("") }
    var pollOptions by rememberSaveable { mutableStateOf(listOf("", "")) }

    val coroutineScope = rememberCoroutineScope()
    var isMediaUploading by remember { mutableStateOf(false) }
    var mediaUploadProgress by remember { mutableFloatStateOf(0f) }
    var mediaUploadError by remember { mutableStateOf<String?>(null) }
    var mediaTransferId by remember { mutableStateOf<String?>(null) }
    val persistedUploadProgress = mediaTransferId?.let { TransferStateHolder.progressMap[it] } ?: 0f
    val persistedUploadStatus = mediaTransferId?.let { TransferStateHolder.statusMap[it] }

    LaunchedEffect(mediaTransferId, persistedUploadProgress, persistedUploadStatus) {
        if (!isMediaUploading) return@LaunchedEffect
        if (persistedUploadProgress > 0f) mediaUploadProgress = persistedUploadProgress
        if (persistedUploadStatus == TransferStatus.FAILED) {
            isMediaUploading = false
            mediaUploadError = "Upload failed. Tap retry to continue from the saved position."
        }
    }

    var showDiscardDialog by rememberSaveable { mutableStateOf(false) }

    val handleBack = {
        if (postText.isNotBlank() || selectedMedia != null) {
            showDiscardDialog = true
        } else {
            onBack()
        }
    }

    BackHandler(enabled = true) {
        handleBack()
    }

    fun uploadMediaFile(uri: Uri, type: PostMediaType, defaultFileName: String, fileSizeText: String? = null) {
        // Persist URI access permission so files remain accessible across sessions
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {}

        val isRetryOfSameFile = selectedMedia?.localUri == uri.toString()
        if (!isRetryOfSameFile) mediaTransferId = UUID.randomUUID().toString()
        val transferIdForUpload = mediaTransferId ?: UUID.randomUUID().toString().also { mediaTransferId = it }
        val fileName = getFileNameFromUri(context, uri) ?: defaultFileName
        selectedMedia = PostMedia(
            type = type,
            localUri = uri.toString(),
            fileName = fileName,
            fileSizeText = fileSizeText
        )

        isMediaUploading = true
        mediaUploadProgress = 0f
        mediaUploadError = null

        coroutineScope.launch(Dispatchers.IO) {
            try {
                // Determine total file size in bytes via metadata (0ms I/O)
                // If unknown (-1L), stream directly with HTTP chunked transfer without 2x file read
                val resolvedTotalBytes: Long = try {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
                        ?.takeIf { it > 0L }
                        ?: context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                            val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (idx != -1 && cursor.moveToFirst()) cursor.getLong(idx) else null
                        }?.takeIf { it > 0L }
                        ?: -1L
                } catch (_: Exception) { -1L }

                if (type == PostMediaType.VIDEO && resolvedTotalBytes > TransferManager.MAX_VIDEO_UPLOAD_BYTES) {
                    withContext(Dispatchers.Main) {
                        selectedMedia = null
                        isMediaUploading = false
                        mediaUploadError = TransferManager.VIDEO_UPLOAD_LIMIT_MESSAGE
                        Toast.makeText(context, TransferManager.VIDEO_UPLOAD_LIMIT_MESSAGE, Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                val onUploadComplete: (String) -> Unit = { mediaUrl ->
                    selectedMedia = selectedMedia?.copy(mediaUrl = mediaUrl, fileName = fileName)
                    mediaUploadProgress = 1f
                    isMediaUploading = false
                    mediaUploadError = null
                }
                if (isRetryOfSameFile) {
                    // The Room record already owns the source file and exact
                    // server-acknowledged offset. Reuse it rather than writing
                    // a new record that would reset progress to zero.
                    TransferManager.registerPostMediaCallback(transferIdForUpload, onUploadComplete)
                    TransferManager.resume(context, transferIdForUpload)
                } else {
                    TransferManager.enqueueUpload(
                        context = context,
                        chatType = "post",
                        senderId = currentUserId,
                        peerId = 0,
                        mediaType = type.name.lowercase(),
                        uri = uri,
                        fileName = fileName,
                        mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream",
                        customTransferId = transferIdForUpload,
                        onPostMediaSuccess = onUploadComplete
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isMediaUploading = false
                    mediaUploadError = "Upload failed: ${e.message}"
                    Toast.makeText(context, "Upload failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Pickers
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            uploadMediaFile(uri, PostMediaType.IMAGE, "image_${System.currentTimeMillis()}.jpg")
        }
    }

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            uploadMediaFile(uri, PostMediaType.VIDEO, "video_${System.currentTimeMillis()}.mp4")
        }
    }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            uploadMediaFile(uri, PostMediaType.AUDIO, "audio_${System.currentTimeMillis()}.mp3")
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            uploadMediaFile(uri, PostMediaType.DOCUMENT, "document_${System.currentTimeMillis()}.pdf", getFileSizeText(context, uri))
        }
    }

    // Auto-trigger direct action (Photo, Video, Audio, File, Poll) when launched from quick action bar
    var initialActionHandled by rememberSaveable(initialAction) { mutableStateOf(false) }
    LaunchedEffect(initialAction) {
        if (!initialActionHandled && initialAction != CreatePostAction.NONE) {
            initialActionHandled = true
            // Small delay to allow screen enter animation to initiate smoothly
            kotlinx.coroutines.delay(120)
            when (initialAction) {
                CreatePostAction.PICK_PHOTO -> {
                    try { imagePicker.launch("image/*") } catch (_: Exception) {}
                }
                CreatePostAction.PICK_VIDEO -> {
                    try { videoPicker.launch("video/*") } catch (_: Exception) {}
                }
                CreatePostAction.PICK_AUDIO -> {
                    try { audioPicker.launch("audio/*") } catch (_: Exception) {}
                }
                CreatePostAction.PICK_FILE -> {
                    try { filePicker.launch("*/*") } catch (_: Exception) {}
                }
                CreatePostAction.OPEN_POLL -> {
                    showPollDialog = true
                }
                CreatePostAction.NONE -> {}
            }
        }
    }

    val canSend = (postText.isNotBlank() || selectedMedia != null) && !isMediaUploading

    Scaffold(
        containerColor = Color(0xFF0C0A20),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Create Post",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { handleBack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    Button(
                        onClick = {
                            if (canSend && !isPosting && !isMediaUploading) {
                                isPosting = true
                                coroutineScope.launch {
                                    try {
                                        val now = System.currentTimeMillis()
                                        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                                            timeZone = TimeZone.getTimeZone("UTC")
                                        }
                                        val subtitle = currentUserDegree?.ifBlank { "University Member" }
                                            ?: if (currentUserRole.equals("Teacher", ignoreCase = true)) "Faculty Member" else "BS Computer Science – Year 1"

                                        val roleClean = currentUserRole.trim().lowercase()
                                        val audience = if (roleClean in listOf("simple user", "simple_user", "simpleuser", "user")) "SIMPLE" else "ACADEMIC"

                                        val newPost = Post(
                                            id = "post_${now}_${UUID.randomUUID().toString().take(6)}",
                                            authorId = currentUserId,
                                            authorName = currentUserName,
                                            authorRole = currentUserRole,
                                            authorProfilePic = currentUserPic,
                                            subtitleInfo = subtitle,
                                            text = postText.trim(),
                                            media = selectedMedia,
                                            timestamp = isoFormat.format(Date(now)),
                                            formattedTime = "Just now",
                                            likeCount = 0,
                                            commentCount = 0,
                                            targetAudience = audience
                                        )

                                        PostRepository.createPost(context, newPost, currentUserId) { success ->
                                            coroutineScope.launch(Dispatchers.Main) {
                                                if (success) {
                                                    SessionDraftStore.clearPost(context, currentUserId)
                                                    Toast.makeText(context, "✓ Post shared successfully", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    Toast.makeText(context, "⚠️ Could not publish post. It will retry when connection returns, or tap retry.", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        }

                                        Toast.makeText(context, "Sharing post…", Toast.LENGTH_SHORT).show()
                                        // Give the UI smooth visual confirmation with spinner and prevent double-clicks
                                        delay(450)
                                        onPostCreated()
                                    } finally {
                                        isPosting = false
                                    }
                                }
                            }
                        },
                        enabled = canSend && !isPosting && !isMediaUploading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF8B6BFF),
                            disabledContainerColor = Color(0xFF2C2454)
                        ),
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        if (isPosting) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "Posting…",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.5.sp
                                )
                            }
                        } else if (isMediaUploading) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = if (mediaUploadProgress > 0f) "${(mediaUploadProgress * 100).toInt()}%" else "Uploading…",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.5.sp
                                )
                            }
                        } else {
                            Text(
                                text = "Post",
                                color = if (canSend) Color.White else Color(0xFF7A76A3),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.5.sp
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0C0A20))
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // ── 1. Author Info Row ──────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (authorAvatarBitmap != null) {
                        Image(
                            bitmap = authorAvatarBitmap!!,
                            contentDescription = currentUserName,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    if (currentUserRole.equals("Teacher", ignoreCase = true))
                                        Brush.radialGradient(listOf(Color(0xFF8B6BFF), Color(0xFF5B3FCC)))
                                    else
                                        Brush.radialGradient(listOf(Color(0xFF4C6FFF), Color(0xFF2E46B8)))
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = currentUserName.take(1).uppercase(),
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }


                Spacer(Modifier.width(12.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = currentUserName,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF8B6BFF).copy(alpha = 0.25f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = currentUserRole,
                                color = Color(0xFF8B6BFF),
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(Modifier.height(3.dp))

                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF1E193C))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Public,
                            contentDescription = null,
                            tint = Color(0xFF8B88B2),
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Public to College",
                            color = Color(0xFF8B88B2),
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── 2. Text Input Area ──────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF161330))
                    .border(1.dp, Color(0xFF2B2252), RoundedCornerShape(14.dp))
                    .padding(14.dp)
            ) {
                BasicTextField(
                    value = postText,
                    onValueChange = {
                        postText = it
                        SessionDraftStore.savePost(context, currentUserId, it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    cursorBrush = SolidColor(Color(0xFF8B6BFF)),
                    textStyle = TextStyle(
                        color = Color.White,
                        fontSize = 15.sp,
                        lineHeight = 22.sp
                    ),
                    decorationBox = { innerTextField ->
                        if (postText.isEmpty()) {
                            Text(
                                text = "What's on your mind? Share an update or announcement with your class...",
                                color = Color(0xFF7A76A3),
                                fontSize = 14.5.sp,
                                lineHeight = 21.sp
                            )
                        }
                        innerTextField()
                    }
                )
            }

            // ── 3. Selected Media Preview Box ───────────────────────────────
            AnimatedVisibility(visible = selectedMedia != null) {
                selectedMedia?.let { media ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp)
                    ) {
                        PostMediaContent(
                            media = media,
                            postId = "preview",
                            currentUserId = currentUserId,
                            isUploading = isMediaUploading,
                            uploadProgress = mediaUploadProgress,
                            uploadError = mediaUploadError,
                            onRemoveMedia = {
                                selectedMedia = null
                                isMediaUploading = false
                                mediaUploadError = null
                            },
                            onRetryUpload = {
                                // Retry with same URI — re-runs full upload from scratch
                                val retryUri = media.localUri?.let { Uri.parse(it) }
                                val retryType = media.type
                                val retryName = media.fileName
                                    ?: "media_${System.currentTimeMillis()}"
                                val retrySizeText = media.fileSizeText
                                if (retryUri != null) {
                                    mediaUploadError = null
                                    uploadMediaFile(retryUri, retryType, retryName, retrySizeText)
                                }
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── 4. Add Media Attachment Section ─────────────────────────────
            Text(
                text = "Add to your post",
                color = Color(0xFF8B88B2),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF161330))
                    .border(1.dp, Color(0xFF2B2252), RoundedCornerShape(14.dp))
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MediaAttachmentButton(
                    icon = Icons.Outlined.PhotoCamera,
                    label = "Photo",
                    tint = Color(0xFF43A047),
                    onClick = { imagePicker.launch("image/*") }
                )

                MediaAttachmentButton(
                    icon = Icons.Outlined.Videocam,
                    label = "Video",
                    tint = Color(0xFFE53935),
                    onClick = { videoPicker.launch("video/*") }
                )

                MediaAttachmentButton(
                    icon = Icons.Outlined.Mic,
                    label = "Audio",
                    tint = Color(0xFFFF9800),
                    onClick = { audioPicker.launch("audio/*") }
                )

                MediaAttachmentButton(
                    icon = Icons.Outlined.Description,
                    label = "File",
                    tint = Color(0xFF1E88E5),
                    onClick = { filePicker.launch("*/*") }
                )

                MediaAttachmentButton(
                    icon = Icons.Outlined.Poll,
                    label = "Poll",
                    tint = Color(0xFF8B6BFF),
                    onClick = { showPollDialog = true }
                )
            }
        }
    }

    // ── Poll Creation Dialog ────────────────────────────────────────────
    if (showPollDialog) {
        AlertDialog(
            onDismissRequest = { showPollDialog = false },
            containerColor = Color(0xFF181438),
            title = {
                Text(
                    text = "Create a Poll",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    OutlinedTextField(
                        value = pollQuestion,
                        onValueChange = { pollQuestion = it },
                        label = { Text("Poll Question", color = Color(0xFF8B88B2)) },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF8B6BFF),
                            unfocusedBorderColor = Color(0xFF332A60)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(14.dp))

                    Text("Options", color = Color(0xFF8B88B2), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))

                    pollOptions.forEachIndexed { index, optionText ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = optionText,
                                onValueChange = { newText ->
                                    val updated = pollOptions.toMutableList()
                                    updated[index] = newText
                                    pollOptions = updated
                                },
                                label = { Text("Option ${index + 1}", color = Color(0xFF8B88B2)) },
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFF8B6BFF),
                                    unfocusedBorderColor = Color(0xFF332A60)
                                ),
                                modifier = Modifier.weight(1f)
                            )

                            if (pollOptions.size > 2) {
                                IconButton(
                                    onClick = {
                                        pollOptions = pollOptions.filterIndexed { i, _ -> i != index }
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFFF5252))
                                }
                            }
                        }
                    }

                    if (pollOptions.size < 5) {
                        TextButton(
                            onClick = { pollOptions = pollOptions + "" },
                            modifier = Modifier.align(Alignment.Start)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = Color(0xFF8B6BFF), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Add Option", color = Color(0xFF8B6BFF))
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val validOptions = pollOptions.filter { it.isNotBlank() }
                        if (pollQuestion.isNotBlank() && validOptions.size >= 2) {
                            selectedMedia = PostMedia(
                                type = PostMediaType.POLL,
                                pollData = PollData(
                                    question = pollQuestion.trim(),
                                    options = validOptions.mapIndexed { idx, opt ->
                                        PollOption(id = "opt_${idx + 1}", text = opt.trim())
                                    },
                                    totalVotes = 0
                                )
                            )
                            showPollDialog = false
                        } else {
                            Toast.makeText(context, "Please provide question & at least 2 options", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B6BFF))
                ) {
                    Text("Add Poll", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPollDialog = false }) {
                    Text("Cancel", color = Color(0xFF8B88B2))
                }
            }
        )
    }

    // Discard Post Confirmation Dialog
    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            containerColor = Color(0xFF1E1A3A),
            title = {
                Text(
                    text = "Discard post?",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            },
            text = {
                Text(
                    text = "If you go back now, your post draft will be discarded.",
                    color = Color(0xFFB0ACD0),
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDiscardDialog = false
                        SessionDraftStore.clearPost(context, currentUserId)
                        onBack()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4D4D))
                ) {
                    Text("Discard", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text("Keep Editing", color = Color(0xFF8B6BFF), fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }
}

/**
 * Individual Media Attachment action button inside composer.
 */
@Composable
private fun MediaAttachmentButton(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            color = Color(0xFFDCDAF0),
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun getFileNameFromUri(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                cursor.getString(nameIndex)
            } else null
        }
    } catch (_: Exception) { null }
}

private fun getFileSizeText(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIndex != -1 && cursor.moveToFirst()) {
                val bytes = cursor.getLong(sizeIndex)
                when {
                    bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024f * 1024f))
                    bytes >= 1024 -> String.format("%.1f KB", bytes / 1024f)
                    else -> "$bytes B"
                }
            } else null
        }
    } catch (_: Exception) { null }
}
