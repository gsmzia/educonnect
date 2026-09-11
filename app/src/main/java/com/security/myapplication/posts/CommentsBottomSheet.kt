package com.security.myapplication.posts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import com.security.myapplication.ui.theme.pumpBounceScroll
import com.security.myapplication.ui.theme.NoNativeOverscroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * Interactive Comments Bottom Sheet.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CommentsBottomSheet(
    post: Post,
    currentUserId: Int,
    currentUserName: String,
    currentUserRole: String,
    currentUserPic: String?,
    initialReplyTo: PostComment? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var commentText by rememberSaveable(currentUserId, post.id) {
        mutableStateOf(SessionDraftStore.getComment(context, currentUserId, post.id))
    }
    var replyingToComment by remember(initialReplyTo) { mutableStateOf<PostComment?>(initialReplyTo) }
    // Updated synchronously inside the click handler so a second fast tap cannot
    // reuse a stale enabled state before Compose recomposes.
    // This is intentionally not saveable: an in-flight UI callback belongs to the
    // current sheet instance, so a recreated sheet must not remain permanently locked.
    var isSendingComment by remember(currentUserId, post.id) { mutableStateOf(false) }
    val currentUserAvatarBitmap by rememberPostAvatarBitmap(currentUserPic)
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val allPosts by PostRepository.postsFlow.collectAsState()
    val livePost = allPosts.firstOrNull { it.id == post.id } ?: post

    LaunchedEffect(post.id) {
        PostRepository.fetchComments(post.id, currentUserId)
    }

    // First page contains the newest comments.  When the reader intentionally
    // scrolls to its top, request just one older keyset page; the repository
    // serialises duplicate callbacks and keeps the UI thread free.
    LaunchedEffect(post.id) {
        var initialObservation = true
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { firstVisibleIndex ->
                if (initialObservation) {
                    initialObservation = false
                } else if (firstVisibleIndex == 0) {
                    val current = PostRepository.postsFlow.value.firstOrNull { it.id == post.id }
                    if (current != null && current.comments.size < current.commentCount) {
                        PostRepository.loadMoreComments(post.id, currentUserId)
                    }
                }
            }
    }

    // Immediate keyboard autofocus on opening sheet
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(180)
        try {
            focusRequester.requestFocus()
            keyboardController?.show()
        } catch (_: Exception) {}
    }

    LaunchedEffect(replyingToComment) {
        if (replyingToComment != null) {
            kotlinx.coroutines.delay(120)
            try {
                focusRequester.requestFocus()
                keyboardController?.show()
            } catch (_: Exception) {}
        }
    }

    var userSentCommentTrigger by remember { mutableIntStateOf(0) }
    var lastSentParentId by remember { mutableStateOf<String?>(null) }

    // Auto-scroll when user posts a comment or reply (handles 0 -> 1 comment transition seamlessly)
    LaunchedEffect(userSentCommentTrigger) {
        if (userSentCommentTrigger > 0) {
            // Small delay to allow LazyColumn to compose and lay out if transitioning from empty state
            kotlinx.coroutines.delay(60)
            val freshComments = PostRepository.postsFlow.value.firstOrNull { it.id == post.id }?.comments
                ?: livePost.comments
            if (freshComments.isNotEmpty()) {
                val targetIndex = if (lastSentParentId != null) {
                    val idx = freshComments.indexOfFirst { it.id == lastSentParentId }
                    if (idx >= 0) idx else freshComments.size - 1
                } else {
                    freshComments.size - 1
                }
                try {
                    listState.animateScrollToItem(targetIndex)
                } catch (_: Exception) {}
            }
        }
    }

    // Auto scroll list upward when keyboard opens so comments and typing remain visible above keyboard without bouncing
    val isImeVisible = WindowInsets.isImeVisible
    LaunchedEffect(isImeVisible) {
        val currentComments = PostRepository.postsFlow.value.firstOrNull { it.id == post.id }?.comments ?: livePost.comments
        if (isImeVisible && currentComments.isNotEmpty()) {
            try {
                listState.scrollToItem(currentComments.size - 1)
            } catch (_: Exception) {}
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF131028),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF3B3560))
            )
        },
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .imePadding()
        ) {
            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Comments (${livePost.commentCount.coerceAtLeast(livePost.comments.size)})",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color(0xFF8B88B2),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            HorizontalDivider(color = Color(0xFF221C42), thickness = 1.dp)

            // Comments List
            if (livePost.comments.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("💬", fontSize = 42.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("No comments yet", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Spacer(Modifier.height(4.dp))
                        Text("Be the first to share your thoughts!", color = Color(0xFF7A76A3), fontSize = 12.5.sp)
                    }
                }
            } else {
                NoNativeOverscroll {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(livePost.comments, key = { it.id }) { comment ->
                            CommentItemRow(
                                comment = comment,
                                currentUserId = currentUserId,
                                // Optimistic comments have ids like "c_<timestamp>_<uuid>".
                                // Their reply button is disabled until the server confirms a
                                // real numeric id (happens after fetchComments returns).
                                isOptimistic = comment.id.startsWith("c_"),
                                onReplyClick = { target ->
                                    replyingToComment = target
                                },
                                onLikeClick = { target ->
                                    PostRepository.toggleCommentLike(context, post.id, target.id, currentUserId)
                                }
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = Color(0xFF221C42), thickness = 1.dp)

            // Target parent resolution & optimistic check
            val targetParent = replyingToComment
            val isTargetOptimistic = targetParent != null && (
                targetParent.id.startsWith("c_") || targetParent.id.toIntOrNull() == null ||
                (targetParent.parentCommentId?.startsWith("c_") == true || (targetParent.parentCommentId != null && targetParent.parentCommentId.toIntOrNull() == null))
            )

            // Replying to banner
            if (replyingToComment != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1D173F))
                        .padding(horizontal = 16.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "↳ Replying to ",
                        color = Color(0xFF9D9ABF),
                        fontSize = 12.sp
                    )
                    Text(
                        text = "@${replyingToComment?.authorName}",
                        color = Color(0xFF8B6BFF),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (isTargetOptimistic) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "(Syncing…)",
                            color = Color(0xFF8B88B2),
                            fontSize = 11.sp,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = { replyingToComment = null },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel reply",
                            tint = Color(0xFF8B88B2),
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
                HorizontalDivider(color = Color(0xFF2B2554), thickness = 0.8.dp)
            }

            // Send Comment Action — disabled if typing empty, syncing a parent, or
            // while this exact submission is in flight.
            val canSend = commentText.isNotBlank() && !isTargetOptimistic && !isSendingComment
            val onSendComment: () -> Unit = {
                if (canSend && !isSendingComment) {
                    isSendingComment = true
                    val targetParent = replyingToComment
                    val parentId = targetParent?.parentCommentId ?: targetParent?.id
                    val newComment = PostComment(
                        id = "c_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}",
                        postId = post.id,
                        parentCommentId = parentId,
                        authorId = currentUserId,
                        authorName = currentUserName,
                        authorRole = currentUserRole,
                        authorProfilePic = currentUserPic,
                        text = commentText.trim(),
                        timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date()),
                        likesCount = 0,
                        likedByMe = false,
                        repliesCount = 0
                    )
                    PostRepository.addComment(
                        context = context,
                        postId = post.id,
                        comment = newComment,
                        currentUserId = currentUserId,
                        parentCommentId = parentId,
                        onSyncComplete = {
                            // Repository sync runs on Dispatchers.IO; restore the
                            // Compose state through this Main-backed scope.
                            coroutineScope.launch { isSendingComment = false }
                        }
                    )
                    commentText = ""
                    SessionDraftStore.clearComment(context, currentUserId, post.id)
                    replyingToComment = null
                    lastSentParentId = parentId
                    userSentCommentTrigger++
                }
            }

            // Bottom Input Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF161230))
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // User Avatar — real profile pic or letter fallback
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (currentUserAvatarBitmap != null) {
                        Image(
                            bitmap = currentUserAvatarBitmap!!,
                            contentDescription = currentUserName,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Brush.radialGradient(listOf(Color(0xFF8B6BFF), Color(0xFF5B3FCC)))),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = currentUserName.take(1).uppercase(),
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }


                Spacer(Modifier.width(10.dp))

                // Input Box
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color(0xFF201B42))
                        .border(1.dp, Color(0xFF332B66), RoundedCornerShape(22.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    BasicTextField(
                        value = commentText,
                        onValueChange = {
                            commentText = it
                            SessionDraftStore.saveComment(context, currentUserId, post.id, it)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        singleLine = false,
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Send
                        ),
                        keyboardActions = KeyboardActions(
                            onSend = { onSendComment() }
                        ),
                        cursorBrush = SolidColor(Color(0xFF8B6BFF)),
                        textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                        decorationBox = { innerTextField ->
                            if (commentText.isEmpty()) {
                                Text(
                                    text = if (replyingToComment != null) "Reply to @${replyingToComment?.authorName}..." else "Write a comment...",
                                    color = Color(0xFF7A76A3),
                                    fontSize = 13.5.sp
                                )
                            }
                            innerTextField()
                        }
                    )
                }

                Spacer(Modifier.width(8.dp))

                // Send Button
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            if (canSend) Brush.linearGradient(listOf(Color(0xFF8B6BFF), Color(0xFF5B3FCC)))
                            else Brush.linearGradient(listOf(Color(0xFF2A2350), Color(0xFF2A2350)))
                        )
                        .clickable(enabled = canSend) {
                            onSendComment()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send Comment",
                        tint = if (canSend) Color.White else Color(0xFF6B6699),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * Individual Comment Row Component.
 */
@Composable
private fun CommentItemRow(
    comment: PostComment,
    currentUserId: Int,
    modifier: Modifier = Modifier,
    isOptimistic: Boolean = false,
    onReplyClick: (PostComment) -> Unit,
    onLikeClick: (PostComment) -> Unit
) {
    var showReplies by remember { mutableStateOf(true) }
    val commentAvatarBitmap by rememberPostAvatarBitmap(comment.authorProfilePic)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            // Avatar — real profile pic or letter fallback
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (commentAvatarBitmap != null) {
                    Image(
                        bitmap = commentAvatarBitmap!!,
                        contentDescription = comment.authorName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Brush.radialGradient(listOf(Color(0xFF8B6BFF), Color(0xFF5B3FCC)))),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = comment.authorName.take(1).uppercase(),
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(Modifier.width(10.dp))


            // Comment Box
            Column(modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF1E193C))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = comment.authorName,
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Spacer(Modifier.width(6.dp))

                            // Role Badge (Teacher / Student / Simple User)
                            CommentRoleBadge(role = comment.authorRole)

                            Spacer(Modifier.width(6.dp))

                            RelativeTimeText(
                                timestamp = comment.timestamp,
                                color = Color(0xFF7A76A3),
                                fontSize = 10.5.sp
                            )
                        }

                        Spacer(Modifier.height(4.dp))

                        Text(
                            text = comment.text,
                            color = Color(0xFFE2E0F5),
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                }

                // Actions row under comment
                Row(
                    modifier = Modifier.padding(start = 2.dp, top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isOptimistic) "Posting…" else "Reply",
                        color = if (isOptimistic) Color(0xFF6A678A) else Color(0xFF8B6BFF),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable(enabled = !isOptimistic) { onReplyClick(comment) }
                            .padding(horizontal = 6.dp, vertical = 6.dp)
                    )

                    val replyCount = comment.replies.size.coerceAtLeast(comment.repliesCount)
                    if (replyCount > 0) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (showReplies) "Hide replies" else "View $replyCount ${if (replyCount == 1) "reply" else "replies"}",
                            color = Color(0xFF8B88B2),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { showReplies = !showReplies }
                                .padding(horizontal = 6.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.width(6.dp))

            // Like Heart Icon + Count
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onLikeClick(comment) }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = if (comment.likedByMe) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = "Like Comment",
                    tint = if (comment.likedByMe) Color(0xFFFF4B6E) else Color(0xFF7A76A3),
                    modifier = Modifier.size(16.dp)
                )
                if (comment.likesCount > 0) {
                    Text(
                        text = "${comment.likesCount}",
                        color = if (comment.likedByMe) Color(0xFFFF4B6E) else Color(0xFF7A76A3),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Nested Replies List with smooth expand/collapse
        AnimatedVisibility(
            visible = showReplies && comment.replies.isNotEmpty(),
            enter = expandVertically(animationSpec = tween(220)) + fadeIn(animationSpec = tween(220)),
            exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(animationSpec = tween(180))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 38.dp, top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                comment.replies.forEach { reply ->
                    ReplyItemRow(
                        reply = reply,
                        currentUserId = currentUserId,
                        isOptimistic = isOptimistic || reply.id.startsWith("c_") || reply.id.toIntOrNull() == null,
                        onReplyClick = { onReplyClick(comment) },
                        onLikeClick = { onLikeClick(reply) }
                    )
                }
            }
        }
    }
}

/**
 * Nested Reply Row Component.
 */
@Composable
private fun ReplyItemRow(
    reply: PostComment,
    currentUserId: Int,
    isOptimistic: Boolean = false,
    onReplyClick: () -> Unit,
    onLikeClick: () -> Unit
) {
    val replyAvatarBitmap by rememberPostAvatarBitmap(reply.authorProfilePic)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        // Mini Avatar — real profile pic or letter fallback
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (replyAvatarBitmap != null) {
                Image(
                    bitmap = replyAvatarBitmap!!,
                    contentDescription = reply.authorName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.radialGradient(listOf(Color(0xFF7254E8), Color(0xFF4730A8)))),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = reply.authorName.take(1).uppercase(),
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(Modifier.width(8.dp))


        // Reply Box
        Column(modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF191433))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = reply.authorName,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(Modifier.width(6.dp))

                        // Role Badge (Teacher / Student / Simple User)
                        CommentRoleBadge(role = reply.authorRole)

                        Spacer(Modifier.width(6.dp))

                        RelativeTimeText(
                            timestamp = reply.timestamp,
                            color = Color(0xFF7A76A3),
                            fontSize = 9.5.sp
                        )
                    }

                    Spacer(Modifier.height(3.dp))

                    Text(
                        text = reply.text,
                        color = Color(0xFFD8D6EC),
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
            }

            // Reply action
            Text(
                text = if (isOptimistic) "Posting…" else "Reply",
                color = if (isOptimistic) Color(0xFF6A678A) else Color(0xFF8B6BFF),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(enabled = !isOptimistic) { onReplyClick() }
                    .padding(horizontal = 6.dp, vertical = 6.dp)
            )
        }

        Spacer(Modifier.width(4.dp))

        // Mini Like Heart
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { onLikeClick() }
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Icon(
                imageVector = if (reply.likedByMe) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = "Like Reply",
                tint = if (reply.likedByMe) Color(0xFFFF4B6E) else Color(0xFF7A76A3),
                modifier = Modifier.size(14.dp)
            )
            if (reply.likesCount > 0) {
                Text(
                    text = "${reply.likesCount}",
                    color = if (reply.likedByMe) Color(0xFFFF4B6E) else Color(0xFF7A76A3),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * Compact Role Badge for Comments and Replies.
 * Supports Teacher, Student, and Simple User with thin border and clean styling.
 */
@Composable
fun CommentRoleBadge(
    role: String,
    modifier: Modifier = Modifier
) {
    val cleanRole = when {
        role.contains("teacher", ignoreCase = true) -> "Teacher"
        role.contains("student", ignoreCase = true) -> "Student"
        role.contains("simple", ignoreCase = true) || role.contains("user", ignoreCase = true) -> "Simple User"
        else -> role.trim()
    }

    val (bgTint, borderColor, textColor) = when (cleanRole) {
        "Teacher" -> Triple(
            Color(0xFF8B6BFF).copy(alpha = 0.15f),
            Color(0xFF8B6BFF).copy(alpha = 0.45f),
            Color(0xFFA288FF)
        )
        "Student" -> Triple(
            Color(0xFF4C6FFF).copy(alpha = 0.15f),
            Color(0xFF4C6FFF).copy(alpha = 0.45f),
            Color(0xFF7FA2FF)
        )
        "Simple User" -> Triple(
            Color(0xFF14B8A6).copy(alpha = 0.15f),
            Color(0xFF14B8A6).copy(alpha = 0.45f),
            Color(0xFF2DD4BF)
        )
        else -> Triple(
            Color(0xFF8B6BFF).copy(alpha = 0.15f),
            Color(0xFF8B6BFF).copy(alpha = 0.45f),
            Color(0xFFA288FF)
        )
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(3.dp))
            .background(bgTint)
            .border(0.6.dp, borderColor, RoundedCornerShape(3.dp))
            .padding(horizontal = 4.5.dp, vertical = 0.5.dp)
    ) {
        Text(
            text = cleanRole,
            color = textColor,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 11.sp
        )
    }
}
