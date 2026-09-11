package com.security.myapplication.posts

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.security.myapplication.models.User
import com.security.myapplication.offline.AppConnectivity
import com.security.myapplication.ui.theme.AppMotion
import com.security.myapplication.ui.theme.NoNativeOverscroll
import com.security.myapplication.ui.theme.pumpBounceScroll
import kotlinx.coroutines.launch

/**
 * Main Posts / Announcements Screen.
 * Replicates the reference image visual design and hierarchy with All Posts, My Posts, and Saved Posts tabs.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PostsScreen(
    userId: Int,
    userRole: String,
    user: User? = null,
    onMenuClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val allPosts by PostRepository.postsFlow.collectAsState()
    val isPostCacheReady by PostRepository.isPostCacheReady.collectAsState()
    val hasMorePosts by PostRepository.hasMorePosts.collectAsState()
    val isLoadingMorePosts by PostRepository.isLoadingMorePosts.collectAsState()
    val isOnline by AppConnectivity.isOnline.collectAsState()
    var isRefreshing by remember { mutableStateOf(false) }
    val pullRefreshState = rememberPullToRefreshState()

    // Initialize repository with current user.
    // init() itself fires a background refreshPosts() — no second call needed here.
    LaunchedEffect(userId, userRole) {
        PostRepository.init(context, userId, userRole)
    }

    // Keep the lower threshold for pausing so a playing video is not stopped by
    // a tiny scroll, while autoplay requires a more deliberate 50% visibility.
    val visiblePostIds by remember(listState) {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val vStart = layoutInfo.viewportStartOffset
            val vEnd = layoutInfo.viewportEndOffset
            layoutInfo.visibleItemsInfo.mapNotNull { item ->
                val id = item.key as? String ?: return@mapNotNull null
                val top = item.offset
                val bottom = item.offset + item.size
                val visibleTop = maxOf(top, vStart)
                val visibleBottom = minOf(bottom, vEnd)
                val visibleHeight = maxOf(0, visibleBottom - visibleTop)
                val fraction = if (item.size > 0) visibleHeight.toFloat() / item.size.toFloat() else 0f
                if (fraction >= 0.30f) id else null
            }.toSet()
        }
    }

    val autoplayPostIds by remember(listState) {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val viewportStart = layoutInfo.viewportStartOffset
            val viewportEnd = layoutInfo.viewportEndOffset
            layoutInfo.visibleItemsInfo.mapNotNull { item ->
                val id = item.key as? String ?: return@mapNotNull null
                val visibleHeight = maxOf(
                    0,
                    minOf(item.offset + item.size, viewportEnd) - maxOf(item.offset, viewportStart)
                )
                val fraction = if (item.size > 0) visibleHeight.toFloat() / item.size else 0f
                if (fraction >= 0.50f) id else null
            }.toSet()
        }
    }

    val triggerRefresh: () -> Unit = refresh@{
        if (!isOnline) {
            // Offline is expected: retain the visible cache without a toast.
            isRefreshing = false
            return@refresh
        }
        if (!isRefreshing) {
            isRefreshing = true
            PostRepository.refreshPosts(context, userId, userRole) { success ->
                isRefreshing = false
                if (!success && isOnline) {
                    Toast.makeText(
                        context,
                        "Couldn't refresh feed. Check your connection.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    // Tab state: 0 -> All Posts, 1 -> My Posts, 2 -> Saved Posts
    var selectedFeedTab by remember { mutableIntStateOf(0) }
    val tabTitles = listOf("All Posts", "My Posts", "Saved Posts")

    // Filtered posts based on active tab
    val currentFeedPosts = remember(allPosts, selectedFeedTab, userId) {
        when (selectedFeedTab) {
            0 -> allPosts
            1 -> allPosts.filter { it.authorId == userId }
            2 -> allPosts.filter { it.savedByMe }
            else -> allPosts
        }
    }

    // Modal Sheet / Fullscreen Dialog for Create Post
    var showCreatePostScreen by remember { mutableStateOf(false) }
    var createPostInitialAction by remember { mutableStateOf(CreatePostAction.NONE) }

    // Active post for comments bottom sheet
    var activeCommentPost by remember { mutableStateOf<Post?>(null) }
    var activeInitialReplyTo by remember { mutableStateOf<PostComment?>(null) }

    val authorName = user?.name ?: if (userRole.equals("Teacher", ignoreCase = true)) "Teacher" else "Student"
    val authorPic = user?.profile_pic
    val composerAvatarBitmap by rememberPostAvatarBitmap(authorPic)
    val authorDegree = if (!user?.degree.isNullOrBlank() && !user?.major.isNullOrBlank()) {
        "${user?.degree} ${user?.major} – ${user?.academic_year ?: "Year 1"}"
    } else {
        user?.degree ?: if (userRole.equals("Teacher", ignoreCase = true)) "Faculty of Computer Science" else "BS Computer Science – Year 1"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── 1. Top Bar (Hamburger Menu, Announcements Title, + Create Post) ───────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onMenuClick,
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = "Menu",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(Modifier.width(8.dp))

                Text(
                    text = "Announcements",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                // Refresh Button
                IconButton(
                    onClick = { triggerRefresh() },
                    modifier = Modifier.size(36.dp)
                ) {
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color(0xFF8B6BFF),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = "Refresh Feed",
                            tint = Color(0xFF8B88B2),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(Modifier.width(4.dp))

                // + Create Post Button
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .clickable {
                            createPostInitialAction = CreatePostAction.NONE
                            showCreatePostScreen = true
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.AddCircleOutline,
                        contentDescription = null,
                        tint = Color(0xFF8B6BFF),
                        modifier = Modifier.size(19.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Create Post",
                        color = Color(0xFF8B6BFF),
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // ── 2. Top Sub-Header Tabs (All Posts, My Posts, Saved Posts) ────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
            ) {
                tabTitles.forEachIndexed { index, title ->
                    val isSelected = selectedFeedTab == index
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { selectedFeedTab = index }
                            .padding(top = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = title,
                            color = if (isSelected) Color(0xFF8B6BFF) else Color(0xFF8B88B2),
                            fontSize = 13.5.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )

                        Spacer(Modifier.height(8.dp))

                        // Active Tab Underline Indicator
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.5.dp)
                                .background(
                                    if (isSelected) Color(0xFF8B6BFF)
                                    else Color.Transparent
                                )
                        )
                    }
                }
            }

            HorizontalDivider(color = Color(0xFF1E193C), thickness = 1.dp)

            // ── 3. Scrollable Feed with "What's on your mind?" and Post Items ────────
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = { triggerRefresh() },
                state = pullRefreshState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                indicator = {
                    PullToRefreshDefaults.Indicator(
                        state = pullRefreshState,
                        isRefreshing = isRefreshing,
                        color = Color(0xFF8B6BFF),
                        containerColor = Color(0xFF1B163B),
                        modifier = Modifier.align(Alignment.TopCenter)
                    )
                }
            ) {
                NoNativeOverscroll {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().pumpBounceScroll(),
                        contentPadding = PaddingValues(vertical = 10.dp)
                    ) {
                // Item 0: "What's on your mind?" Quick Card (matching reference image)
                item(key = "quick_composer_card") {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF16132D)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF26224A))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp)
                        ) {
                            // Top Row: Avatar + Placeholder
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        createPostInitialAction = CreatePostAction.NONE
                                        showCreatePostScreen = true
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (composerAvatarBitmap != null) {
                                        Image(
                                            bitmap = composerAvatarBitmap!!,
                                            contentDescription = authorName,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(
                                                    if (userRole.equals("Teacher", ignoreCase = true))
                                                        Brush.radialGradient(listOf(Color(0xFF8B6BFF), Color(0xFF5B3FCC)))
                                                    else
                                                        Brush.radialGradient(listOf(Color(0xFF4C6FFF), Color(0xFF2E46B8)))
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = authorName.take(1).uppercase(),
                                                color = Color.White,
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }


                                Spacer(Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "What's on your mind?",
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = "Share an update with your class...",
                                        color = Color(0xFF7A76A3),
                                        fontSize = 12.sp
                                    )
                                }
                            }

                            Spacer(Modifier.height(14.dp))
                            HorizontalDivider(color = Color(0xFF221C42), thickness = 1.dp)
                            Spacer(Modifier.height(10.dp))

                            // Bottom Action Chips + "Post" Button Row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier
                                        .clickable {
                                            createPostInitialAction = CreatePostAction.PICK_PHOTO
                                            showCreatePostScreen = true
                                        }
                                        .padding(horizontal = 6.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Outlined.Photo, contentDescription = null, tint = Color(0xFF8B6BFF), modifier = Modifier.size(17.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Photo", color = Color(0xFFDCDAF0), fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                                }

                                Spacer(Modifier.width(14.dp))

                                Row(
                                    modifier = Modifier
                                        .clickable {
                                            createPostInitialAction = CreatePostAction.PICK_FILE
                                            showCreatePostScreen = true
                                        }
                                        .padding(horizontal = 6.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Outlined.Description, contentDescription = null, tint = Color(0xFF8B6BFF), modifier = Modifier.size(17.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("File", color = Color(0xFFDCDAF0), fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                                }

                                Spacer(Modifier.width(14.dp))

                                Row(
                                    modifier = Modifier
                                        .clickable {
                                            createPostInitialAction = CreatePostAction.OPEN_POLL
                                            showCreatePostScreen = true
                                        }
                                        .padding(horizontal = 6.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Outlined.Poll, contentDescription = null, tint = Color(0xFF8B6BFF), modifier = Modifier.size(17.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Poll", color = Color(0xFFDCDAF0), fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                                }

                                Spacer(Modifier.weight(1f))

                                // Purple "Post" pill button
                                Button(
                                    onClick = {
                                        createPostInitialAction = CreatePostAction.NONE
                                        showCreatePostScreen = true
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B6BFF)),
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Text("Post", fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                                }
                            }
                        }
                    }
                }

                // Feed Items
                if (!isPostCacheReady && currentFeedPosts.isEmpty()) {
                    // Room hydration is in progress.  Keep the content quiet rather
                    // than flashing a false "no posts" message before the cache lands.
                    item(key = "cache_hydration_placeholder") {
                        Spacer(Modifier.height(1.dp))
                    }
                } else if (currentFeedPosts.isEmpty()) {
                    item(key = "empty_feed_placeholder") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp, horizontal = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = when (selectedFeedTab) {
                                        1 -> "📝"
                                        2 -> "🔖"
                                        else -> "📢"
                                    },
                                    fontSize = 44.sp
                                )
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    text = when (selectedFeedTab) {
                                        1 -> "You haven't posted yet"
                                        2 -> "No saved posts"
                                        else -> "No announcements available"
                                    },
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = when (selectedFeedTab) {
                                        1 -> "Create your first post by tapping the '+ Create Post' button above."
                                        2 -> "Save interesting posts by tapping the bookmark icon on any post."
                                        else -> "Check back later for university news, assignments, and notes."
                                    },
                                    color = Color(0xFF7A76A3),
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    }
                } else {
                    items(currentFeedPosts, key = { it.id }) { post ->
                        PostItem(
                            post = post,
                            currentUserId = userId,
                            currentUserName = authorName,
                            currentUserRole = userRole,
                            currentUserPic = authorPic,
                            isVisibleOnScreen = post.id in visiblePostIds,
                            isAutoPlayVisible = post.id in autoplayPostIds,
                            modifier = Modifier.animateItem(),
                            onCommentClick = {
                                activeInitialReplyTo = null
                                activeCommentPost = post
                            },
                            onReplyComment = { targetComment ->
                                activeInitialReplyTo = targetComment
                                activeCommentPost = post
                            },
                            onDeletePost = { postId ->
                                PostRepository.deletePost(context, postId, userId)
                            },
                            onRetryPost = { failedPost ->
                                PostRepository.retryPost(context, failedPost, userId)
                            }
                        )
                    }
                    // This item is only composed when the user reaches the
                    // bottom of All Posts, which makes it a lightweight cursor
                    // pagination trigger rather than a scroll-pixel listener.
                    if (selectedFeedTab == 0 && hasMorePosts) {
                        item(key = "feed_load_more") {
                            LaunchedEffect(currentFeedPosts.lastOrNull()?.id) {
                                PostRepository.loadMorePosts(context, userId, userRole)
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 20.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isLoadingMorePosts) {
                                    CircularProgressIndicator(
                                        color = Color(0xFF8B6BFF),
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(24.dp)
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

        // ── 4. Fullscreen Create Post Screen Overlay ─────────────────────────
        AnimatedVisibility(
            visible = showCreatePostScreen,
            enter = AppMotion.screenEnter,
            exit = AppMotion.screenExit
        ) {
            CreatePostScreen(
                currentUserId = userId,
                currentUserName = authorName,
                currentUserRole = userRole,
                currentUserPic = authorPic,
                currentUserDegree = authorDegree,
                initialAction = createPostInitialAction,
                onPostCreated = {
                    showCreatePostScreen = false
                    createPostInitialAction = CreatePostAction.NONE
                    coroutineScope.launch {
                        listState.animateScrollToItem(0)
                    }
                },
                onBack = {
                    showCreatePostScreen = false
                    createPostInitialAction = CreatePostAction.NONE
                }
            )
        }

        // ── 5. Comments Bottom Sheet ─────────────────────────────────────────
        activeCommentPost?.let { post ->
            CommentsBottomSheet(
                post = post,
                currentUserId = userId,
                currentUserName = authorName,
                currentUserRole = userRole,
                currentUserPic = authorPic,
                initialReplyTo = activeInitialReplyTo,
                onDismiss = {
                    activeCommentPost = null
                    activeInitialReplyTo = null
                }
            )
        }
    }
}
