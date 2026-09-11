package com.security.myapplication.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.security.myapplication.models.Group
import com.security.myapplication.models.HomeFeedItem
import com.security.myapplication.models.PersonalChatUser
import com.security.myapplication.models.ReadStatusManager
import com.security.myapplication.models.formatUniversalLocalTime
import com.security.myapplication.network.ApiClient
import com.security.myapplication.notifications.BatteryOptimizationNotice
import com.security.myapplication.ui.theme.AppMotion
import com.security.myapplication.ui.theme.pumpBounceScroll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// ── Shared Theme Colors ───────────────────────────────────────────────────────
private val BgCard        = Color(0xFF16132D)
private val BgCardBorder  = Color(0xFF26224A)
private val SearchBg      = Color(0xFF171433)
private val Purple        = Color(0xFF8B6BFF)
private val PurpleDark    = Color(0xFF5B3FCC)
private val SubText       = Color(0xFF7A7899)
private val SenderPurple  = Color(0xFF9D80FF)
private val SenderTeal    = Color(0xFF4DD9AC)
private val TickBlue      = Color(0xFF7B8FCC)

private val avatarPalettes = listOf(
    listOf(Color(0xFF3B2A8A), Color(0xFF6B4EFF)),
    listOf(Color(0xFF0F4C2A), Color(0xFF1A8C4E)),
    listOf(Color(0xFF7A1C1C), Color(0xFFB53A3A)),
    listOf(Color(0xFF1A2E5C), Color(0xFF2E5490)),
    listOf(Color(0xFF5C3A00), Color(0xFFA06800)),
    listOf(Color(0xFF4A2070), Color(0xFF8040C0)),
    listOf(Color(0xFF1C3A4A), Color(0xFF2E7090)),
    listOf(Color(0xFF4A1040), Color(0xFF882070)),
)
private fun avatarGrad(i: Int) = avatarPalettes[i % avatarPalettes.size]

private fun groupIcon(name: String): ImageVector = when {
    name.contains("Announcement", ignoreCase = true) -> Icons.Outlined.Campaign
    name.contains("Teacher",      ignoreCase = true) -> Icons.Outlined.SupervisorAccount
    name.contains("Study",        ignoreCase = true) -> Icons.Outlined.Groups
    else                                             -> Icons.Outlined.School
}

// Filter Options: All, Unread, Groups (Classes and Direct removed as requested)
private enum class HFilter(val label: String) {
    All("All"), Unread("Unread"), Groups("Groups")
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UnifiedHomeScreen(
    userId: Int,
    onGroupSelected: (Group) -> Unit,
    onDirectSelected: (PersonalChatUser) -> Unit,
    onAuthExpired: (() -> Unit)? = null
) {
    var feedItems  by remember { mutableStateOf<List<HomeFeedItem>>(emptyList()) }
    var allDirectContacts by remember { mutableStateOf<List<PersonalChatUser>>(emptyList()) }
    var allUserGroups by remember { mutableStateOf<List<Group>>(emptyList()) }
    var isLoading  by remember { mutableStateOf(true) }
    var query      by remember { mutableStateOf("") }
    var filter     by remember { mutableStateOf(HFilter.All) }
    var showNewChatDialog by remember { mutableStateOf(false) }
    val listState  = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // ✅ BUG #2 FIX: Replace unbounded mutableStateMapOf with a size-limited LruCache.
    val bitmapCache = remember {
        object : android.util.LruCache<String, Bitmap>(
            minOf((Runtime.getRuntime().maxMemory() / 1024 / 8).toInt(), 16 * 1024)
        ) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
        }
    }
    var bitmapCacheVersion by remember { mutableStateOf(0) }

    // Load full roster of direct contacts and groups for instant multi-field search and New Chat lookup
    LaunchedEffect(userId) {
        try {
            allDirectContacts = ApiClient.apiService.getPersonalChats(userId)
        } catch (_: Exception) {}
        try {
            allUserGroups = ApiClient.apiService.getUserGroups(userId)
        } catch (_: Exception) {}
    }

    // Polling loop: refresh home feed every 3 seconds (WhatsApp-style live updates)
    LaunchedEffect(userId, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try {
                    try { ApiClient.apiService.pingOnline(userId) } catch (_: Exception) {}
                    val items = ApiClient.apiService.getHomeFeed(userId)
                    val processed = items.map { item ->
                        if (ReadStatusManager.isRead(item.type ?: "direct", item.id, item.last_message)) {
                            item.copy(unread_count = 0)
                        } else item
                    }
                    try {
                        val freshDirect = ApiClient.apiService.getPersonalChats(userId)
                        if (freshDirect.isNotEmpty()) {
                            allDirectContacts = freshDirect
                        }
                    } catch (_: Exception) {}
                    if (processed != feedItems) {
                        feedItems = processed
                    }
                    items.forEach { item ->
                        val cacheKey = "${item.type}_${item.id}"
                        if (item.profile_pic != null && bitmapCache.get(cacheKey) == null) {
                            val b64 = item.profile_pic
                            launch(Dispatchers.Default) {
                                val bmp = try {
                                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                } catch (_: Exception) { null }
                                withContext(Dispatchers.Main) {
                                    if (bmp != null) {
                                        bitmapCache.put(cacheKey, bmp)
                                        bitmapCacheVersion++
                                    }
                                }
                            }
                        }
                    }
                } catch (e: retrofit2.HttpException) {
                    if (e.code() == 401 || e.code() == 403) {
                        onAuthExpired?.invoke()
                        delay(10000)
                    }
                } catch (_: Exception) {}
                if (isLoading) isLoading = false
                delay(3000)
            }
        }
    }

    val cleanQuery = remember(query) { query.trim().removePrefix("@") }

    // ── Instant WhatsApp-Style Universal Search Filter ──────────────────────────
    // Matches ANY single letter or substring across: name, username, last message, sender,
    // role, degree, major, group titles, and the entire contacts roster!
    val displayed = remember(feedItems, allDirectContacts, allUserGroups, cleanQuery, filter) {
        val result = mutableListOf<HomeFeedItem>()
        val seenKeys = mutableSetOf<String>()

        // 1. First process and filter items from recent Home Feed
        feedItems.forEach { item ->
            val isGrp = (item.type ?: "direct").equals("group", ignoreCase = true)
            val nameMatches = (item.name ?: "").contains(cleanQuery, ignoreCase = true)
            val unameMatches = (item.username ?: "").contains(cleanQuery, ignoreCase = true)
            val msgMatches = (item.last_message ?: "").contains(cleanQuery, ignoreCase = true)
            val senderMatches = (item.last_sender ?: "").contains(cleanQuery, ignoreCase = true)
            val roleMatches = (item.role ?: "").contains(cleanQuery, ignoreCase = true)
            val degMatches = (item.degree ?: "").contains(cleanQuery, ignoreCase = true)
            val majorMatches = (item.major ?: "").contains(cleanQuery, ignoreCase = true)

            val matchesQuery = cleanQuery.isEmpty() || nameMatches || unameMatches || msgMatches || senderMatches || roleMatches || degMatches || majorMatches
            val matchesFilter = when (filter) {
                HFilter.All    -> true
                HFilter.Unread -> item.unread_count > 0
                HFilter.Groups -> isGrp
            }

            if (matchesQuery && matchesFilter) {
                val key = (if (isGrp) "group_" else "direct_") + item.id
                if (key !in seenKeys) {
                    seenKeys.add(key)
                    result.add(item)
                }
            }
        }

        // 2. Ensure all direct personal chats are displayed so newly contacted or existing chats NEVER disappear
        if (filter != HFilter.Groups && filter != HFilter.Unread) {
            allDirectContacts.forEach { user ->
                val key = "direct_${user.id}"
                if (key !in seenKeys && user.id != userId) {
                    val nameMatches = user.name.contains(cleanQuery, ignoreCase = true)
                    val unameMatches = (user.username ?: "").contains(cleanQuery, ignoreCase = true)
                    val roleMatches = user.role.contains(cleanQuery, ignoreCase = true)
                    val matchesQuery = cleanQuery.isEmpty() || nameMatches || unameMatches || roleMatches
                    if (matchesQuery) {
                        seenKeys.add(key)
                        result.add(
                            HomeFeedItem(
                                type = "direct",
                                id = user.id,
                                name = user.name,
                                username = user.username,
                                role = user.role,
                                profile_pic = user.profile_pic,
                                last_message = if (user.role.equals("Teacher", ignoreCase = true)) "Faculty Member • Direct Chat" else "${user.role.replaceFirstChar { it.uppercase() }} • Direct Chat",
                                unread_count = 0
                            )
                        )
                    }
                }
            }
        }

        // 3. If searching, also search full groups list
        if (cleanQuery.isNotEmpty() && filter != HFilter.Unread) {
            allUserGroups.forEach { grp ->
                val key = "group_${grp.id}"
                if (key !in seenKeys) {
                    val nameMatches = grp.name.contains(cleanQuery, ignoreCase = true)
                    val degMatches = grp.degree.contains(cleanQuery, ignoreCase = true)
                    val majorMatches = grp.major.contains(cleanQuery, ignoreCase = true)
                    if (nameMatches || degMatches || majorMatches) {
                        seenKeys.add(key)
                        result.add(
                            HomeFeedItem(
                                type = "group",
                                id = grp.id,
                                name = grp.name,
                                degree = grp.degree,
                                major = grp.major,
                                academic_year = grp.academic_year,
                                subject_id = grp.subject_id,
                                teacher_id = grp.teacher_id,
                                last_message = "Group Chat",
                                unread_count = 0
                            )
                        )
                    }
                }
            }
        }

        result
    }

    var isRefreshing by remember { mutableStateOf(false) }

    fun triggerRefresh() {
        coroutineScope.launch {
            isRefreshing = true
            try {
                try { ApiClient.apiService.pingOnline(userId) } catch (_: Exception) {}
                val items = ApiClient.apiService.getHomeFeed(userId)
                val processed = items.map { item ->
                    if (ReadStatusManager.isRead(item.type ?: "direct", item.id, item.last_message)) {
                        item.copy(unread_count = 0)
                    } else item
                }
                try {
                    val freshChats = ApiClient.apiService.getPersonalChats(userId)
                    if (freshChats.isNotEmpty()) {
                        allDirectContacts = freshChats
                    }
                } catch (_: Exception) {}
                if (processed != feedItems) {
                    feedItems = processed
                }
            } catch (_: Exception) {}
            isRefreshing = false
        }
    }

    // ✅ LOW #15 FIX: Wrap entire screen in LocalOverscrollConfiguration provides null
    // so both filter chips (LazyRow) and chat list (LazyColumn) share the same clean, glow-free scroll feel.
    CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {

                Spacer(Modifier.height(4.dp))

                // Single canonical placement: every role reaches this shared chat home.
                BatteryOptimizationNotice()

                // ── Search bar ─────────────────────────────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(SearchBg)
                        .border(1.dp, BgCardBorder, RoundedCornerShape(16.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Search, null, tint = SubText, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            cursorBrush = SolidColor(Purple),
                            textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                            decorationBox = { inner ->
                                if (query.isEmpty()) Text("Search chats, groups or users...", color = SubText, fontSize = 14.sp)
                                inner()
                            }
                        )
                        if (query.isNotEmpty()) {
                            Icon(
                                Icons.Outlined.Close, null, tint = SubText,
                                modifier = Modifier.size(16.dp).clickable { query = "" }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // ── Filter Chips (All, Unread, Groups only) ────────────────────
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(HFilter.values()) { f ->
                        val sel = filter == f
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50.dp))
                                .then(
                                    if (sel) Modifier.background(
                                        Brush.linearGradient(listOf(Purple, PurpleDark))
                                    ) else Modifier
                                        .background(SearchBg)
                                        .border(1.dp, BgCardBorder, RoundedCornerShape(50.dp))
                                )
                                .clickable { filter = f }
                                .padding(
                                    horizontal = if (f == HFilter.All) 22.dp else 18.dp,
                                    vertical = 7.dp
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                f.label,
                                color = if (sel) Color.White else SubText,
                                fontSize = if (sel) 12.5.sp else 12.sp,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // ── List View (Fast & Ultra-Smooth) ────────────────────────────
                if (isLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Purple, strokeWidth = 2.dp, modifier = Modifier.size(32.dp))
                    }
                } else if (displayed.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("💬", fontSize = 40.sp)
                            Spacer(Modifier.height(10.dp))
                            Text("No conversations found", color = Color.White, fontWeight = FontWeight.Bold)
                            Text("Your chats will appear here", color = SubText, fontSize = 13.sp)
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().pumpBounceScroll(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(
                            start = 16.dp, end = 16.dp,
                            top = 2.dp, bottom = 100.dp
                        )
                    ) {
                        itemsIndexed(
                            items = displayed,
                            key = { _, item -> "${item.type}_${item.id}" }
                        ) { idx, item ->
                            // Read version so this item recomposes when a new bitmap is stored
                            @Suppress("UNUSED_EXPRESSION") bitmapCacheVersion
                            val decodedBmp = if (item.profile_pic != null)
                                bitmapCache.get("${item.type}_${item.id}") else null

                            FeedCardRow(
                                item   = item,
                                index  = idx,
                                bitmap = decodedBmp,
                                // ✅ BUG #4 & #17 FIX: animateItem() wired with AppMotion tokens for buttery Material 3 transitions.
                                modifier = Modifier.animateItem(
                                    fadeInSpec = androidx.compose.animation.core.tween(
                                        durationMillis = AppMotion.MEDIUM2,
                                        easing = AppMotion.EmphasizedEasing
                                    ),
                                    fadeOutSpec = androidx.compose.animation.core.tween(
                                        durationMillis = AppMotion.MEDIUM1,
                                        easing = AppMotion.EmphasizedAccelEasing
                                    ),
                                    placementSpec = androidx.compose.animation.core.spring(
                                        dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
                                        stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
                                    )
                                ),
                                onTap  = {
                                    if (item.last_message_id != null) {
                                        ReadStatusManager.markAsRead(item.type, item.id, item.last_message_id)
                                    } else {
                                        ReadStatusManager.markAsRead(item.type, item.id, item.last_message)
                                    }
                                    feedItems = feedItems.map { f ->
                                        if (f.type == item.type && f.id == item.id)
                                            f.copy(unread_count = 0)
                                        else f
                                    }
                                    if (item.type == "group") {
                                        coroutineScope.launch {
                                            try { ApiClient.apiService.markGroupChatRead(item.id, userId) } catch (_: Exception) {}
                                        }
                                        onGroupSelected(
                                            Group(
                                                id = item.id,
                                                name = item.name,
                                                degree = item.degree ?: "",
                                                major = item.major ?: "",
                                                academic_year = item.academic_year ?: "",
                                                subject_id = item.subject_id ?: 0,
                                                teacher_id = item.teacher_id
                                            )
                                        )
                                    } else {
                                        coroutineScope.launch {
                                            try { ApiClient.apiService.markPersonalChatRead(userId, item.id) } catch (_: Exception) {}
                                        }
                                        onDirectSelected(
                                            PersonalChatUser(
                                                id = item.id,
                                                name = item.name,
                                                username = item.username,
                                                role = item.role ?: "Student",
                                                profile_pic = item.profile_pic
                                            )
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }

            // Pull to refresh animated top indicator
            if (isRefreshing) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                    color = Purple,
                    trackColor = Color.Transparent
                )
            }

            // ── Squircle FAB (New Chat / Add Contact by Username) ───────────────
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = 24.dp)
                    .size(56.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Brush.linearGradient(listOf(Purple, PurpleDark)))
                    .clickable { showNewChatDialog = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "New Chat",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }

            // ── New Chat by Username Modal Dialog ─────────────────────────────────
            if (showNewChatDialog) {
                NewChatByUsernameDialog(
                    currentUserId = userId,
                    contactsList = allDirectContacts,
                    onDismiss = { showNewChatDialog = false },
                    onDirectSelected = { chatUser ->
                        showNewChatDialog = false
                        // Prepend newly initiated chat into allDirectContacts and feedItems immediately so it never disappears
                        if (allDirectContacts.none { it.id == chatUser.id }) {
                            allDirectContacts = listOf(chatUser) + allDirectContacts
                        }
                        if (feedItems.none { it.type == "direct" && it.id == chatUser.id }) {
                            val initialItem = HomeFeedItem(
                                type = "direct",
                                id = chatUser.id,
                                name = chatUser.name,
                                username = chatUser.username,
                                role = chatUser.role,
                                profile_pic = chatUser.profile_pic,
                                last_message = "Direct Chat",
                                unread_count = 0
                            )
                            feedItems = listOf(initialItem) + feedItems
                        }
                        onDirectSelected(chatUser)
                    }
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Single Card Row (Rendered with zero UI thread blocking)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun FeedCardRow(
    item: HomeFeedItem,
    index: Int,
    bitmap: Bitmap?,
    modifier: Modifier = Modifier,
    onTap: () -> Unit
) {
    val safeSender = item.last_sender ?: ""
    val safeType = item.type ?: "direct"
    val isSentByMe = if (item.is_left) false else (safeSender == "You" || (safeSender.isBlank() && safeType == "direct"))
    val effectiveUnread = if (isSentByMe || item.is_left) 0 else item.unread_count
    val hasUnread = effectiveUnread > 0
    val safeName = item.name?.ifBlank { "Chat" } ?: "Chat"

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(BgCard)
            .border(1.dp, BgCardBorder, RoundedCornerShape(16.dp))
            .clickable(onClick = onTap)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {

            // Avatar
            Box(modifier = Modifier.size(48.dp)) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = safeName,
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else if (safeType == "direct") {
                    val grad = avatarGrad(item.id.hashCode())
                    Box(
                        modifier = Modifier.fillMaxSize().clip(CircleShape)
                            .background(Brush.linearGradient(colors = listOf(grad[1], grad[0]))),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            (safeName.firstOrNull()?.uppercaseChar() ?: 'U').toString(),
                            color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    val grad = avatarGrad(item.id.hashCode())
                    Box(
                        modifier = Modifier.fillMaxSize().clip(CircleShape)
                            .background(Brush.linearGradient(colors = listOf(grad[0], grad[1]))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = groupIcon(safeName),
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.95f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            // Details
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    safeName,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))

                val (previewText, previewIcon, isVoiceMsg) = remember(
                    item.last_message,
                    item.last_message_type,
                    item.last_message_duration_sec,
                    item.is_left
                ) {
                    if (item.is_left) {
                        return@remember Triple("You left", "", false)
                    }

                    val lastMsg = item.last_message ?: ""
                    val rawMsg = if (lastMsg.contains("[REPLY:") && lastMsg.contains("]")) {
                        val afterNewline = lastMsg.substringAfter("\n", "").trim()
                        if (afterNewline.isNotBlank()) {
                            afterNewline
                        } else {
                            val afterBracket = lastMsg.substringAfter("]").trim()
                            if (afterBracket.isNotBlank()) afterBracket else "Reply"
                        }
                    } else {
                        lastMsg
                    }.ifEmpty { "No messages yet" }

                    val msgType = (item.last_message_type ?: "").lowercase()
                    val isVoice = msgType == "voice" ||
                        (msgType.isBlank() && (rawMsg.startsWith("🎤") || rawMsg == "voice"))
                    val isImage = msgType == "image" ||
                        (msgType.isBlank() && (rawMsg.startsWith("📷") || rawMsg == "image"))
                    val isFile  = msgType == "file" ||
                        (msgType.isBlank() && (rawMsg.startsWith("📄") || rawMsg == "file"))
                    val isVideo = msgType == "video" ||
                        (msgType.isBlank() && (rawMsg.startsWith("🎥") || rawMsg == "video"))
                    val isAudio = msgType == "audio" ||
                        (msgType.isBlank() && (rawMsg.startsWith("🎵") || rawMsg == "audio"))

                    val voiceDurationText = if (item.last_message_duration_sec >= 0) {
                        val m = item.last_message_duration_sec / 60
                        val s = item.last_message_duration_sec % 60
                        "$m:${s.toString().padStart(2, '0')}"
                    } else "•••"

                    val pText: String
                    val pIcon: String
                    when {
                        isImage -> {
                            pText = "Photo"
                            pIcon = "📷 "
                        }
                        isVoice -> {
                            pText = "Voice message • $voiceDurationText"
                            pIcon = "🎤 "
                        }
                        isFile -> {
                            pText = rawMsg.removePrefix("📄").trim().ifBlank { "Document" }
                            pIcon = "📄 "
                        }
                        isVideo -> {
                            pText = "Video"
                            pIcon = "🎥 "
                        }
                        isAudio -> {
                            pText = "Audio"
                            pIcon = "🎵 "
                        }
                        else -> {
                            pText = rawMsg
                            pIcon = ""
                        }
                    }
                    Triple(pText, pIcon, isVoice)
                }

                // Tick + Preview Row (WhatsApp style)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isSentByMe) {
                        val isRead = item.last_message_is_read
                        val isDelivered = item.last_message_is_delivered || isRead
                        val tickColor = if (isRead) Purple else Color.White.copy(alpha = 0.58f)
                        Text(
                            "✓",
                            color = tickColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 11.sp
                        )
                        if (isDelivered) {
                            Text(
                                "✓",
                                color = tickColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                lineHeight = 11.sp,
                                modifier = Modifier.offset(x = (-4).dp)
                            )
                        }
                        Spacer(Modifier.width(3.dp))
                    }

                    // Media icon
                    if (previewIcon.isNotEmpty()) {
                        val iconColor = if (isVoiceMsg && !isSentByMe && effectiveUnread > 0) Purple
                                        else Color.White.copy(alpha = 0.85f)
                        Text(
                            previewIcon,
                            color = iconColor,
                            fontSize = 13.sp,
                            maxLines = 1
                        )
                    }

                    // Message text preview
                    val textCol = if (isVoiceMsg && !isSentByMe && effectiveUnread > 0) Purple
                                  else if (hasUnread && !isSentByMe) Color.White.copy(alpha = 0.9f)
                                  else SubText
                    Text(
                        text = previewText,
                        color = textCol,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            }

            Spacer(Modifier.width(10.dp))

            // Timestamp + Prominent Unread Badge on the Right
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.Center
            ) {
                val rawTime = item.timestamp_raw?.ifBlank { null } ?: item.timestamp?.ifBlank { null }
                val displayTime = if (!rawTime.isNullOrBlank()) {
                    formatUniversalLocalTime(rawTime).ifBlank { item.timestamp ?: "" }
                } else ""

                if (displayTime.isNotBlank()) {
                    Text(
                        displayTime,
                        color = if (hasUnread) Purple else SubText,
                        fontSize = 11.sp,
                        fontWeight = if (hasUnread) FontWeight.SemiBold else FontWeight.Normal
                    )
                    Spacer(Modifier.height(5.dp))
                }

                if (hasUnread) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(Purple, PurpleDark))),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (effectiveUnread > 99) "99+" else effectiveUnread.toString(),
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 11.sp
                        )
                    }
                }
            }
        }
    }
}

// ── WhatsApp-Style New Chat by Username Modal Dialog ───────────────────────────
@Composable
private fun NewChatByUsernameDialog(
    currentUserId: Int,
    contactsList: List<PersonalChatUser>,
    onDismiss: () -> Unit,
    onDirectSelected: (PersonalChatUser) -> Unit
) {
    var usernameInput by remember { mutableStateOf("") }
    var searchStatus by remember { mutableStateOf<String?>(null) }
    var isSearching by remember { mutableStateOf(false) }
    var foundUser by remember { mutableStateOf<PersonalChatUser?>(null) }
    val coroutineScope = rememberCoroutineScope()

    fun performSearch() {
        val raw = usernameInput.trim().removePrefix("@")
        if (raw.isBlank()) {
            searchStatus = "Please enter a username"
            foundUser = null
            return
        }

        isSearching = true
        searchStatus = null
        foundUser = null

        coroutineScope.launch(Dispatchers.IO) {
            // 1. Search in local contacts cache first
            val localMatch = contactsList.firstOrNull { 
                it.id != currentUserId && (
                    it.username?.equals(raw, ignoreCase = true) == true || 
                    it.name.equals(raw, ignoreCase = true)
                )
            }

            if (localMatch != null) {
                withContext(Dispatchers.Main) {
                    foundUser = localMatch
                    isSearching = false
                }
                return@launch
            }

            // 2. Fetch fresh personal chats from API to search the existing roster
            try {
                val freshList = ApiClient.apiService.getPersonalChats(currentUserId)
                val remoteMatch = freshList.firstOrNull {
                    it.id != currentUserId && (
                        it.username?.equals(raw, ignoreCase = true) == true || 
                        it.name.equals(raw, ignoreCase = true)
                    )
                }
                if (remoteMatch != null) {
                    withContext(Dispatchers.Main) {
                        foundUser = remoteMatch
                        isSearching = false
                    }
                    return@launch
                }
            } catch (_: Exception) {}

            // 3. Search entire platform database by exact case-insensitive username or friendly name
            try {
                val dbUser = com.security.myapplication.network.lookupUserRobust(raw, currentUserId)
                if (dbUser != null) {
                    if (dbUser.id == currentUserId) {
                        withContext(Dispatchers.Main) {
                            searchStatus = "You cannot start a chat with yourself."
                            isSearching = false
                        }
                        return@launch
                    }
                    val chatUser = PersonalChatUser(
                        id = dbUser.id,
                        name = dbUser.name,
                        username = dbUser.username,
                        role = dbUser.role,
                        profile_pic = dbUser.profile_pic
                    )
                    withContext(Dispatchers.Main) {
                        foundUser = chatUser
                        isSearching = false
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        searchStatus = "User '$raw' not found. Please verify the username or name."
                        isSearching = false
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    searchStatus = "Could not find user '$raw'. Please check connection."
                    isSearching = false
                }
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF16132D))
                .border(1.2.dp, Color(0xFF26224A), RoundedCornerShape(24.dp))
                .padding(22.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header Icon
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(Purple, PurpleDark))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.PersonSearch,
                        contentDescription = "Find User",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }

                Spacer(Modifier.height(14.dp))

                Text(
                    text = "New Direct Chat",
                    color = Color.White,
                    fontSize = 18.5.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(6.dp))

                Text(
                    text = "Enter the username of the user you want to contact:",
                    color = SubText,
                    fontSize = 13.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    lineHeight = 17.sp
                )

                Spacer(Modifier.height(16.dp))

                // Username Input Field
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF1D193E))
                        .border(1.dp, if (searchStatus != null) Color(0xFFFF4D6D) else Color(0xFF2E275C), RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "@",
                            color = Purple,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.width(8.dp))
                        BasicTextField(
                            value = usernameInput,
                            onValueChange = {
                                usernameInput = it
                                searchStatus = null
                            },
                            singleLine = true,
                            cursorBrush = SolidColor(Purple),
                            textStyle = TextStyle(color = Color.White, fontSize = 14.5.sp),
                            modifier = Modifier.weight(1f),
                            decorationBox = { inner ->
                                if (usernameInput.isEmpty()) {
                                    Text("Enter username (e.g. prof_ali)", color = SubText, fontSize = 13.5.sp)
                                }
                                inner()
                            }
                        )
                        if (usernameInput.isNotEmpty()) {
                            IconButton(
                                onClick = { usernameInput = ""; searchStatus = null; foundUser = null },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Outlined.Close, contentDescription = "Clear", tint = SubText, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                // Error Message Display
                if (searchStatus != null) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFFFF4D6D).copy(alpha = 0.12f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ErrorOutline,
                            contentDescription = "Error",
                            tint = Color(0xFFFF4D6D),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = searchStatus ?: "",
                            color = Color(0xFFFF4D6D),
                            fontSize = 12.sp,
                            lineHeight = 15.sp
                        )
                    }
                }

                // Found User Candidate Card
                if (foundUser != null) {
                    Spacer(Modifier.height(12.dp))
                    val candidate = foundUser!!
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF231C4D))
                            .border(1.dp, Color(0xFF3B2E78), RoundedCornerShape(14.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(Brush.linearGradient(listOf(Color(0xFF3B2A8A), Color(0xFF6B4EFF)))),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(candidate.name.take(1).uppercase(), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(candidate.name, color = Color.White, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                            Text("@${candidate.username ?: "user"}", color = SubText, fontSize = 12.sp)
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF30C96B).copy(alpha = 0.2f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(candidate.role.replaceFirstChar { it.uppercase() }, color = Color(0xFF30C96B), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                // Action Buttons (Cancel / Next / Start Chat)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).height(46.dp),
                        shape = RoundedCornerShape(23.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = SubText),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2E275C))
                    ) {
                        Text("Cancel", fontSize = 14.sp, color = SubText)
                    }

                    Button(
                        onClick = {
                            if (foundUser != null) {
                                onDirectSelected(foundUser!!)
                                onDismiss()
                            } else {
                                performSearch()
                            }
                        },
                        modifier = Modifier.weight(1f).height(46.dp),
                        shape = RoundedCornerShape(23.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (foundUser != null) Color(0xFF30C96B) else Purple
                        ),
                        enabled = !isSearching
                    ) {
                        if (isSearching) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text(
                                text = if (foundUser != null) "Start Chat" else "Next",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

