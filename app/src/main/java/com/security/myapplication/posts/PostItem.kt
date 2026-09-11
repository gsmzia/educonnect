package com.security.myapplication.posts

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp


/**
 * Visual Post Card Component faithfully matching the reference image.
 */
@Composable
fun PostItem(
    post: Post,
    currentUserId: Int,
    currentUserName: String,
    currentUserRole: String,
    currentUserPic: String?,
    modifier: Modifier = Modifier,
    isVisibleOnScreen: Boolean = true,
    isAutoPlayVisible: Boolean = false,
    onCommentClick: () -> Unit = {},
    onReplyComment: (PostComment) -> Unit = {},
    onDeletePost: (postId: String) -> Unit = {},
    onRetryPost: (Post) -> Unit = {}
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }

    // Decode profile pictures off the main thread
    val authorAvatarBitmap by rememberPostAvatarBitmap(post.authorProfilePic)

    // Like button bounce animation
    val isLiked = post.likedByMe
    val likeScale by animateFloatAsState(
        targetValue = if (isLiked) 1.2f else 1.0f,
        animationSpec = spring(dampingRatio = 0.4f, stiffness = 400f),
        label = "like_scale"
    )

    // Guard: while post is still being uploaded to server (optimistic, non-numeric ID),
    // disable Like / Comment / Save / double-tap-like so actions don't silently revert
    // when the server-confirmed post replaces the optimistic entry.
    val actionsEnabled = !post.isPosting && !post.isFailed

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF16132D)),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (post.isFailed) Color(0xFFFF5252).copy(alpha = 0.6f) else Color(0xFF26224A)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // ── 1. Post Header (Avatar + Name + Badge + Subtitle + Menu) ─────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Author Avatar — real profile pic when available, letter initial fallback
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (authorAvatarBitmap != null) {
                        Image(
                            bitmap = authorAvatarBitmap!!,
                            contentDescription = post.authorName,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    if (post.authorRole.equals("Teacher", ignoreCase = true))
                                        Brush.radialGradient(listOf(Color(0xFF8B6BFF), Color(0xFF5B3FCC)))
                                    else
                                        Brush.radialGradient(listOf(Color(0xFF4C6FFF), Color(0xFF2E46B8)))
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = post.authorName.take(1).uppercase(),
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }

                Spacer(Modifier.width(10.dp))

                // Name, Role Badge, and Subtitle
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = post.authorName,
                            color = Color.White,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(Modifier.width(6.dp))

                        // Role Badge — same style as Admin badge in GroupInfo/ChatTab
                        val isTeacher = post.authorRole.equals("Teacher", ignoreCase = true)
                        val badgeColor = if (isTeacher) Color(0xFF8B6BFF) else Color(0xFF4C6FFF)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(badgeColor.copy(alpha = 0.2f))
                                .border(1.dp, badgeColor, RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = post.authorRole,
                                color = if (isTeacher) Color(0xFF9D80FF) else Color(0xFF7FA2FF),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                    }

                    Spacer(Modifier.height(2.dp))

                    Text(
                        text = post.subtitleInfo,
                        color = Color(0xFF8B88B2),
                        fontSize = 11.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Status or Timestamp
                when {
                    post.isPosting -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                color = Color(0xFF8B6BFF),
                                strokeWidth = 1.8.dp,
                                modifier = Modifier.size(11.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "Posting…",
                                color = Color(0xFF8B6BFF),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    post.isFailed -> {
                        Text(
                            text = "Failed",
                            color = Color(0xFFFF5252),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    else -> {
                        RelativeTimeText(
                            timestamp = post.timestamp,
                            color = Color(0xFF7A76A3),
                            fontSize = 11.5.sp
                        )
                    }
                }

                Spacer(Modifier.width(4.dp))

                // Three Dots Menu
                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Options",
                            tint = Color(0xFF8B88B2),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.background(Color(0xFF1E193C))
                    ) {
                        if (post.isFailed) {
                            DropdownMenuItem(
                                text = { Text("Retry Post", color = Color(0xFF1DE9B6), fontWeight = FontWeight.Bold) },
                                onClick = {
                                    showMenu = false
                                    onRetryPost(post)
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (post.savedByMe) "Remove from Saved" else "Save Post",
                                    color = if (actionsEnabled) Color.White else Color(0xFF5A5680)
                                )
                            },
                            onClick = {
                                showMenu = false
                                if (actionsEnabled) {
                                    val saved = PostRepository.toggleSave(context, post.id, currentUserId)
                                    Toast.makeText(context, if (saved) "Post saved to Saved Posts" else "Removed from Saved Posts", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Share Post", color = Color.White) },
                            onClick = {
                                showMenu = false
                                sharePost(context, post)
                            }
                        )
                        if (post.authorId == currentUserId) {
                            DropdownMenuItem(
                                text = { Text("Delete Post", color = Color(0xFFFF5252)) },
                                onClick = {
                                    showMenu = false
                                    onDeletePost(post.id)
                                    Toast.makeText(context, "Post deleted", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                }
            }

            // ── Failed to Post Warning Banner (WhatsApp / FB style) ───────────────────
            if (post.isFailed) {
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF321313))
                        .border(1.dp, Color(0xFFFF5252).copy(alpha = 0.7f), RoundedCornerShape(10.dp))
                        .clickable { onRetryPost(post) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                                imageVector = Icons.Outlined.ErrorOutline,
                                contentDescription = null,
                                tint = Color(0xFFFF5252),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Failed to post — Tap to retry",
                                color = Color(0xFFFF8A80),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Retry",
                                color = Color(0xFF1DE9B6),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF1DE9B6).copy(alpha = 0.15f))
                                    .clickable { onRetryPost(post) }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            IconButton(
                                onClick = {
                                    onDeletePost(post.id)
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.DeleteOutline,
                                    contentDescription = "Delete failed post",
                                    tint = Color(0xFFC7C5DD),
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }
                }
            }

            // ── 2. Post Content Text ──────────────────────────────────────────────────
            if (post.text.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = post.text,
                    color = Color(0xFFEDEDF8),
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            }

            // ── 3. Attached Media ─────────────────────────────────────────────────────
            if (post.media != null && post.media.type != PostMediaType.NONE) {
                PostMediaContent(
                    media = post.media,
                    postId = post.id,
                    currentUserId = currentUserId,
                    isVisibleOnScreen = isVisibleOnScreen,
                    isAutoPlayVisible = isAutoPlayVisible,
                    onVotePoll = { optionId ->
                        if (actionsEnabled) PostRepository.votePoll(context, post.id, optionId, currentUserId)
                    },
                    onDoubleTapLike = {
                        if (actionsEnabled && !post.likedByMe) {
                            PostRepository.toggleLike(context, post.id, currentUserId)
                        }
                    }
                )
            }

            // ── 4. Reactions & Comments Count Row ────────────────────────────────────
            val hasReactions = post.likeCount > 0 || post.commentCount > 0
            if (hasReactions) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Like Count
                    if (post.likeCount > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF8B6BFF)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ThumbUp,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(10.dp)
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "${post.likeCount} ${if (post.likeCount == 1) "like" else "likes"}",
                                color = Color(0xFF8B88B2),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    // Comments Count
                    if (post.commentCount > 0) {
                        Text(
                            text = "${post.commentCount} ${if (post.commentCount == 1) "Comment" else "Comments"}",
                            color = Color(0xFF8B88B2),
                            fontSize = 12.sp,
                            modifier = Modifier.clickable { onCommentClick() }
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = Color(0xFF221C42), thickness = 1.dp)
            Spacer(Modifier.height(4.dp))

            // ── 5. Action Buttons Bar (Like, Comment, Share, Save) ────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Like Button — disabled while post is being uploaded (avoids silent revert on confirm)
                val likeColor = when {
                    !actionsEnabled -> Color(0xFF3D3A5C)
                    isLiked         -> Color(0xFF8B6BFF)
                    else            -> Color(0xFF8B88B2)
                }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .then(
                            if (isLiked && actionsEnabled) {
                                Modifier
                                    .background(Color(0xFF8B6BFF).copy(alpha = 0.22f))
                                    .border(1.dp, Color(0xFF8B6BFF).copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                            } else {
                                Modifier.background(Color.Transparent)
                            }
                        )
                        .clickable(enabled = actionsEnabled) {
                            PostRepository.toggleLike(context, post.id, currentUserId)
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isLiked) Icons.Default.ThumbUp else Icons.Outlined.ThumbUp,
                        contentDescription = "Like",
                        tint = likeColor,
                        modifier = Modifier
                            .size(17.dp)
                            .scale(likeScale)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Like",
                        color = likeColor,
                        fontSize = 12.5.sp,
                        fontWeight = if (isLiked && actionsEnabled) FontWeight.Bold else FontWeight.Medium
                    )
                }

                // Comment Button — disabled while posting
                val commentColor = if (actionsEnabled) Color(0xFF8B88B2) else Color(0xFF3D3A5C)
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = actionsEnabled) { onCommentClick() }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ChatBubbleOutline,
                        contentDescription = "Comment",
                        tint = commentColor,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Comment",
                        color = commentColor,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Share Button — always enabled (no server ID needed to share text)
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { sharePost(context, post) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Share,
                        contentDescription = "Share",
                        tint = Color(0xFF8B88B2),
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Share",
                        color = Color(0xFF8B88B2),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Save / Bookmark Button — disabled while posting
                val isSaved = post.savedByMe
                val saveColor = when {
                    !actionsEnabled -> Color(0xFF3D3A5C)
                    isSaved         -> Color(0xFF8B6BFF)
                    else            -> Color(0xFF8B88B2)
                }
                IconButton(
                    onClick = {
                        if (actionsEnabled) {
                            val saved = PostRepository.toggleSave(context, post.id, currentUserId)
                            Toast.makeText(
                                context,
                                if (saved) "Post saved to Saved Posts" else "Removed from Saved Posts",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (isSaved) Icons.Default.Bookmark else Icons.Outlined.BookmarkBorder,
                        contentDescription = "Save",
                        tint = saveColor,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }
    }
}

/**
 * Trigger system share intent for a post.
 */
private fun sharePost(context: Context, post: Post) {
    try {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Post by ${post.authorName}")
            putExtra(
                Intent.EXTRA_TEXT,
                "${post.authorName} (${post.subtitleInfo}):\n\n${post.text}\n\nShared via EduConnect"
            )
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share Post via"))
    } catch (_: Exception) {
        Toast.makeText(context, "Could not open share menu", Toast.LENGTH_SHORT).show()
    }
}
