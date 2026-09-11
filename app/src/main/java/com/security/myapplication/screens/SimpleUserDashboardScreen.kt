package com.security.myapplication.screens

import android.content.Context
import android.widget.Toast
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.google.gson.Gson
import com.security.myapplication.audio.MiniAudioPlayerBar
import com.security.myapplication.models.*
import com.security.myapplication.network.ApiClient
import com.security.myapplication.posts.PostsScreen
import com.security.myapplication.ui.theme.AppMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val DashPurple = Color(0xFF8B6BFF)
private val DashPurpleDark = Color(0xFF5A3DBD)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SimpleUserDashboardScreen(
    userId: Int,
    role: String,
    navController: NavController
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val sharedPrefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    val accountRole = sharedPrefs.getString("role", "") ?: ""
    val canReturnToAcademic = !accountRole.trim().lowercase().let {
        it == "simple user" || it == "simple_user" || it == "simpleuser" || it == "user"
    }
    val gson = remember { Gson() }

    // Re-check every dashboard open. Missing folders are recreated, while
    // existing files are never deleted or moved.
    LaunchedEffect(userId) {
        com.security.myapplication.storage.EduConnectStorageManager
            .ensureFoldersExist(context.applicationContext, userId)
    }

    var user by remember { mutableStateOf<User?>(null) }
    var selectedTab by remember { mutableStateOf(0) } // 0 = Chats, 1 = Posts

    // Chat navigation targets
    var selectedChatUser by remember { mutableStateOf<PersonalChatUser?>(null) }
    var selectedGroup by remember { mutableStateOf<Group?>(null) }
    var pendingHighlightMsgId by remember { mutableStateOf<Int?>(null) }

    // Overlays & Sheets
    var showCreateGroup by remember { mutableStateOf(false) }
    var showFullProfileScreen by remember { mutableStateOf(false) }
    var showMenuDropdown by remember { mutableStateOf(false) }
    var showProfileSheet by remember { mutableStateOf(false) }
    var showEditProfileDialog by remember { mutableStateOf(false) }
    var editNameText by remember { mutableStateOf("") }
    var isSavingProfile by remember { mutableStateOf(false) }

    // Sync helper
    fun syncData() {
        coroutineScope.launch {
            try {
                val freshUser = ApiClient.apiService.getUser(userId)
                user = freshUser
                sharedPrefs.edit().putString("cached_user_$userId", gson.toJson(freshUser)).apply()
            } catch (_: Exception) {}
        }
    }

    // Deep link navigation from NotificationRouter
    val navTarget by com.security.myapplication.notifications.NotificationRouter.pendingNavTarget
    LaunchedEffect(navTarget) {
        navTarget?.let { target ->
            if (target.currentUserId != userId) {
                com.security.myapplication.notifications.NotificationRouter.clearPendingTarget()
                return@let
            }
            pendingHighlightMsgId = target.messageId
            if (target.type == "personal" && target.otherUserId > 0) {
                selectedGroup = null
                selectedChatUser = PersonalChatUser(
                    id = target.otherUserId,
                    name = target.userName,
                    username = null,
                    role = target.userRole,
                    profile_pic = target.userProfilePic
                )
                selectedTab = 0
                com.security.myapplication.notifications.NotificationRouter.clearPendingTarget()
            } else if (target.type == "group" && target.groupId > 0) {
                selectedChatUser = null
                selectedGroup = Group(
                    id = target.groupId,
                    name = target.groupName
                )
                selectedTab = 0
                com.security.myapplication.notifications.NotificationRouter.clearPendingTarget()
            }
        }
    }

    // Online presence heartbeat
    LaunchedEffect(userId) {
        while (true) {
            try { ApiClient.apiService.pingOnline(userId) } catch (_: Exception) {}
            delay(3000)
        }
    }

    // Load initial user data from cache and fresh server
    LaunchedEffect(userId) {
        val cachedUserStr = sharedPrefs.getString("cached_user_$userId", null)
        if (cachedUserStr != null) {
            try {
                user = gson.fromJson(cachedUserStr, User::class.java)
            } catch (_: Exception) {}
        }
        syncData()
    }

    // Auto sync whenever screen resumes
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                syncData()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Back button handling
    BackHandler(
        enabled = selectedChatUser != null || selectedGroup != null || showCreateGroup || showFullProfileScreen || selectedTab != 0
    ) {
        when {
            showCreateGroup -> showCreateGroup = false
            selectedChatUser != null -> selectedChatUser = null
            selectedGroup != null -> selectedGroup = null
            showFullProfileScreen -> showFullProfileScreen = false
            selectedTab != 0 -> selectedTab = 0
        }
    }

    // Disable native overscroll so pump bounce feels 100% natural and identical to Teacher/Student
    CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0D0A22))
        ) {
            // Background radial glow matching Teacher/Student
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color(0xFF6B4EFF).copy(alpha = 0.35f),
                                Color(0xFF3B2A8A).copy(alpha = 0.15f),
                                Color.Transparent
                            ),
                            center = androidx.compose.ui.geometry.Offset(0.5f, 0f),
                            radius = 900f
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                // ── Top Bar: Matching Teacher & Student Dashboard ─────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Title: EDU (White) + Connect (Purple)
                    Text(
                        text = buildAnnotatedString {
                            withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)) {
                                append("EDU ")
                            }
                            withStyle(SpanStyle(color = Color(0xFF8B6BFF), fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)) {
                                append("Connect")
                            }
                        }
                    )

                    // Right Actions: Create Group Button + Camera + 3-Dots Menu
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Quick Create Group Action Button
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    Brush.horizontalGradient(listOf(DashPurple, DashPurpleDark))
                                )
                                .clickable { showCreateGroup = true }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    text = "Group",
                                    color = Color.White,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Camera Icon Button
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1D1A3A))
                                .border(1.dp, Color(0xFF2A2650), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.PhotoCamera,
                                contentDescription = "Camera",
                                tint = Color(0xFF8B6BFF),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // 3-Dots Menu with Gorgeous Floating Gradient Popup
                        Box {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF1D1A3A))
                                    .border(1.dp, Color(0xFF2A2650), CircleShape)
                                    .clickable { showMenuDropdown = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Outlined.MoreVert,
                                    contentDescription = "More",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            DropdownMenu(
                                expanded = showMenuDropdown,
                                onDismissRequest = { showMenuDropdown = false },
                                modifier = Modifier.background(Color.Transparent),
                                containerColor = Color.Transparent,
                                shadowElevation = 0.dp,
                                tonalElevation = 0.dp,
                                border = null
                            ) {
                                SimpleUserMenuDropdownContent(
                                    user = user,
                                    onProfileClick = {
                                        showMenuDropdown = false
                                        showFullProfileScreen = true
                                    },
                                    onSettingsClick = {
                                        showMenuDropdown = false
                                        showFullProfileScreen = true
                                    },
                                    onSwitchToAcademicClick = if (canReturnToAcademic) {
                                        {
                                            showMenuDropdown = false
                                            coroutineScope.launch {
                                                try {
                                                    com.security.myapplication.storage.SessionModeManager.switchMode(
                                                        context.applicationContext, userId,
                                                        com.security.myapplication.storage.SessionModeManager.ACADEMIC
                                                    )
                                                    navController.navigate("dashboard/$userId/$accountRole") {
                                                        // The current destination keeps the account's
                                                        // original role in its route (Teacher/Student),
                                                        // even though this screen is rendered as Simple.
                                                        popUpTo("dashboard/$userId/$accountRole") { inclusive = true }
                                                    }
                                                } catch (_: Exception) {
                                                    Toast.makeText(context, "Could not switch mode. Check your connection.", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    } else null,
                                    onSignOutClick = {
                                        showMenuDropdown = false
                                        val sp = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                                        com.security.myapplication.models.clearUserSessionData(context, userId)
                                        sp.edit().clear().apply()
                                        navController.navigate("login") { popUpTo(0) { inclusive = true } }
                                    }
                                )
                            }
                        }
                    }
                }

                // ── Floating Mini Audio Player Bar ───────────────────────────
                MiniAudioPlayerBar(modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))

                // ── Sleek Segmented Pill Control (Tabs: Chats & Posts) ────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50.dp))
                            .background(Color(0xFF171433))
                            .border(1.dp, Color(0xFF26224A), RoundedCornerShape(50.dp))
                            .padding(3.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val tabLabels = listOf("Chats", "Posts")
                            val tabIcons = listOf(Icons.Outlined.Forum, Icons.Outlined.Campaign)

                            tabLabels.forEachIndexed { index, label ->
                                val isSelected = selectedTab == index
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50.dp))
                                        .then(
                                            if (isSelected) Modifier.background(
                                                Brush.linearGradient(listOf(Color(0xFF8B6BFF), Color(0xFF5B3FCC)))
                                            ) else Modifier
                                        )
                                        .clickable { selectedTab = index }
                                        .padding(horizontal = 24.dp, vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = tabIcons[index],
                                            contentDescription = null,
                                            tint = if (isSelected) Color.White else Color(0xFF7A7899),
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = label,
                                            color = if (isSelected) Color.White else Color(0xFF7A7899),
                                            fontSize = 12.5.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ── Tab Content with smooth horizontal slide & fade in/out transitions ────
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    AnimatedContent(
                        targetState = selectedTab,
                        transitionSpec = {
                            if (targetState > initialState) {
                                (slideInHorizontally(animationSpec = AppMotion.tweenEnter(300)) { it } +
                                 fadeIn(animationSpec = tween(220, easing = LinearEasing), initialAlpha = 0f))
                                    .togetherWith(
                                        slideOutHorizontally(animationSpec = AppMotion.tweenExit(250)) { -(it / 4) } +
                                        fadeOut(animationSpec = tween(180, easing = LinearEasing), targetAlpha = 0f)
                                    )
                            } else {
                                (slideInHorizontally(animationSpec = AppMotion.tweenEnter(300)) { -(it / 4) } +
                                 fadeIn(animationSpec = tween(220, easing = LinearEasing), initialAlpha = 0f))
                                    .togetherWith(
                                        slideOutHorizontally(animationSpec = AppMotion.tweenExit(260)) { it } +
                                        fadeOut(animationSpec = tween(180, easing = LinearEasing), targetAlpha = 0f)
                                    )
                            }
                        },
                        label = "simple_user_tab_content"
                    ) { tab ->
                        when (tab) {
                            0 -> UnifiedHomeScreen(
                                userId = userId,
                                onGroupSelected = { selectedGroup = it },
                                onDirectSelected = { selectedChatUser = it }
                            )
                            1 -> PostsScreen(
                                userId = userId,
                                userRole = role,
                                user = user,
                                onMenuClick = { showProfileSheet = true }
                            )
                        }
                    }
                }
            }

            // ── Profile Sheet Dialog ─────────────────────────────────────────
            if (showProfileSheet) {
                androidx.compose.ui.window.Dialog(
                    onDismissRequest = { showProfileSheet = false }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF12122A))
                            .padding(24.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            // Close button top-right
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                IconButton(onClick = { showProfileSheet = false }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Outlined.Close, contentDescription = "Close", tint = Color(0xFF6868A0), modifier = Modifier.size(18.dp))
                                }
                            }
                            Spacer(Modifier.height(4.dp))

                            // DP Circle — clickable to edit profile
                            Box(
                                modifier = Modifier
                                    .size(80.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.radialGradient(
                                            listOf(Color(0xFF8B6BFF), Color(0xFF5628B5))
                                        )
                                    )
                                    .clickable {
                                        editNameText = user?.name ?: ""
                                        showEditProfileDialog = true
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    (user?.name?.firstOrNull()?.uppercaseChar() ?: "U").toString(),
                                    color = Color.White,
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                            Spacer(Modifier.height(2.dp))
                            Text("Tap to edit", color = Color(0xFF6868A0), fontSize = 11.sp)
                            Spacer(Modifier.height(12.dp))

                            // Name
                            Text(
                                user?.name ?: "-",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                            Spacer(Modifier.height(4.dp))
                            // Username
                            Text(
                                "@${user?.username ?: "-"}",
                                color = Color(0xFF8B6BFF),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(4.dp))
                            // Email
                            Text(
                                user?.email ?: "-",
                                color = Color(0xFF6868A0),
                                fontSize = 12.sp
                            )
                            Spacer(Modifier.height(10.dp))
                            // Role Badge for Simple User
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(Color(0xFF1A1040))
                                    .border(1.dp, Color(0xFF8B6BFF).copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                                    .padding(horizontal = 14.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    "Simple User",
                                    color = Color(0xFF8B6BFF),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(Modifier.height(20.dp))

                            // Divider
                            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1E1E50)))
                            Spacer(Modifier.height(16.dp))

                            // Logout button
                            Button(
                                onClick = {
                                    showProfileSheet = false
                                    val sp = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                                    com.security.myapplication.models.clearUserSessionData(context, userId)
                                    sp.edit().clear().apply()
                                    navController.navigate("login") { popUpTo(0) { inclusive = true } }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A0A0A)),
                                modifier = Modifier.fillMaxWidth().height(46.dp),
                                shape = RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF6B6B).copy(alpha = 0.5f))
                            ) {
                                Icon(Icons.Outlined.Logout, contentDescription = null, tint = Color(0xFFFF6B6B), modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Logout", color = Color(0xFFFF6B6B), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }

            // ── Edit Profile Dialog ──────────────────────────────────────────
            if (showEditProfileDialog) {
                AlertDialog(
                    onDismissRequest = { if (!isSavingProfile) showEditProfileDialog = false },
                    containerColor = Color(0xFF12122A),
                    tonalElevation = 0.dp,
                    title = { Text("Edit Profile", color = Color.White, fontWeight = FontWeight.Bold) },
                    text = {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            // DP preview
                            Box(
                                modifier = Modifier.size(64.dp).clip(CircleShape)
                                    .background(Brush.radialGradient(listOf(Color(0xFF8B6BFF), Color(0xFF5628B5))))
                                    .align(Alignment.CenterHorizontally),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    (editNameText.firstOrNull()?.uppercaseChar() ?: "U").toString(),
                                    color = Color.White,
                                    fontSize = 26.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                            Spacer(Modifier.height(16.dp))
                            Text("Display Name", color = Color(0xFF6868A0), fontSize = 12.sp)
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(
                                value = editNameText,
                                onValueChange = { editNameText = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFF8B6BFF),
                                    unfocusedBorderColor = Color(0xFF2A2A5A),
                                    cursorColor = Color(0xFF8B6BFF),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedContainerColor = Color(0xFF0D0D2B),
                                    unfocusedContainerColor = Color(0xFF0D0D2B)
                                ),
                                shape = RoundedCornerShape(10.dp),
                                placeholder = { Text("Enter your name", color = Color(0xFF6868A0)) }
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Note: Only your display name can be changed.",
                                color = Color(0xFF6868A0),
                                fontSize = 11.sp
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                if (editNameText.isNotBlank()) {
                                    coroutineScope.launch {
                                        isSavingProfile = true
                                        try {
                                            val updated = ApiClient.apiService.updateUserProfile(
                                                userId,
                                                UserProfileUpdateRequest(name = editNameText.trim())
                                            )
                                            user = updated
                                            sharedPrefs.edit().putString("cached_user_$userId", gson.toJson(updated)).apply()
                                            showEditProfileDialog = false
                                        } catch (_: Exception) {}
                                        isSavingProfile = false
                                    }
                                }
                            },
                            enabled = !isSavingProfile && editNameText.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B6BFF))
                        ) {
                            if (isSavingProfile) CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                            else Text("Save", fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { if (!isSavingProfile) showEditProfileDialog = false }) {
                            Text("Cancel", color = Color(0xFF6868A0))
                        }
                    }
                )
            }

            // ── Direct Chat Overlay ──────────────────────────────────────────
            AnimatedVisibility(
                visible = selectedChatUser != null,
                enter = AppMotion.chatEnter,
                exit = AppMotion.chatExit,
                modifier = Modifier.fillMaxSize().zIndex(11f)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0D0A22))
                ) {
                    selectedChatUser?.let { chatUser ->
                        PersonalChatScreen(
                            currentUserId = userId,
                            otherUser = chatUser,
                            initialMessage = chatUser.autoMessage ?: "",
                            highlightMessageId = pendingHighlightMsgId,
                            onBack = {
                                selectedChatUser = null
                                pendingHighlightMsgId = null
                            }
                        )
                    }
                }
            }

            // ── Group Chat Overlay ───────────────────────────────────────────
            AnimatedVisibility(
                visible = selectedGroup != null,
                enter = AppMotion.chatEnter,
                exit = AppMotion.chatExit,
                modifier = Modifier.fillMaxSize().zIndex(12f)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0D0A22))
                ) {
                    selectedGroup?.let { group ->
                        ChatTab(
                            groupId = group.id,
                            userId = userId,
                            groupName = group.name,
                            highlightMessageId = pendingHighlightMsgId,
                            onBack = {
                                selectedGroup = null
                                pendingHighlightMsgId = null
                            },
                            onOpenDirectChat = { directUser ->
                                selectedGroup = null
                                selectedChatUser = directUser
                            }
                        )
                    }
                }
            }

            // ── Create Group Overlay ─────────────────────────────────────────
            AnimatedVisibility(
                visible = showCreateGroup,
                enter = AppMotion.chatEnter,
                exit = AppMotion.chatExit,
                modifier = Modifier.fillMaxSize().zIndex(13f)
            ) {
                CreateGroupScreen(
                    currentUserId = userId,
                    currentUserName = user?.name ?: "User",
                    onBack = { showCreateGroup = false },
                    onGroupCreated = { newGroup ->
                        showCreateGroup = false
                        selectedGroup = newGroup
                    }
                )
            }

            // ── Full Profile Screen Overlay ──────────────────────────────────
            AnimatedVisibility(
                visible = showFullProfileScreen,
                enter = AppMotion.screenEnter,
                exit = AppMotion.screenExit,
                modifier = Modifier.fillMaxSize().zIndex(14f)
            ) {
                ProfileScreen(
                    userId = userId,
                    onBack = { showFullProfileScreen = false },
                    onUserUpdated = { updated ->
                        user = updated
                        sharedPrefs.edit().putString("cached_user_$userId", gson.toJson(updated)).apply()
                    }
                )
            }
        }
    }
}

// ── Dropdown Menu Content matching Teacher/Student exact aesthetic ────────────
@Composable
fun SimpleUserMenuDropdownContent(
    user: User?,
    onProfileClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSwitchToAcademicClick: (() -> Unit)? = null,
    onSignOutClick: () -> Unit
) {
    var menuBitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(user?.profile_pic) {
        menuBitmap = withContext(Dispatchers.Default) {
            user?.profile_pic?.let { b64 ->
                try {
                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)
                    var sampleSize = 1
                    val targetSize = 128 // 64dp avatar @ 2x density
                    while (boundsOptions.outWidth / (sampleSize * 2) >= targetSize && boundsOptions.outHeight / (sampleSize * 2) >= targetSize) {
                        sampleSize *= 2
                    }
                    val decodeOptions = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
                } catch (_: Exception) { null }
            }
        }
    }

    Box(
        modifier = Modifier
            .width(230.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF1C1638), Color(0xFF130F2E))
                )
            )
            .border(
                1.dp,
                Brush.linearGradient(
                    listOf(
                        Color(0xFF7B5CF5).copy(alpha = 0.8f),
                        Color(0xFF3A2490).copy(alpha = 0.3f),
                        Color(0xFF7B5CF5).copy(alpha = 0.6f)
                    )
                ),
                RoundedCornerShape(22.dp)
            )
            .padding(vertical = 20.dp, horizontal = 18.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            // Profile Avatar + Label
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onProfileClick() }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(Color(0xFF5B3FCC), Color(0xFF2A1470))
                            )
                        )
                        .border(
                            2.dp,
                            Brush.linearGradient(
                                listOf(Color(0xFFBD9FFF), Color(0xFF7B5CF5))
                            ),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (menuBitmap != null) {
                        Image(
                            bitmap = menuBitmap!!.asImageBitmap(),
                            contentDescription = "DP",
                            modifier = Modifier.fillMaxSize().clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Text(
                            (user?.name?.firstOrNull()?.uppercaseChar() ?: "U").toString(),
                            color = Color.White,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Profile",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier.fillMaxWidth().height(0.8.dp)
                    .background(
                        Brush.linearGradient(
                            listOf(Color.Transparent, Color(0xFF6A50D3).copy(alpha = 0.7f), Color.Transparent)
                        )
                    )
            )
            Spacer(Modifier.height(8.dp))

            // Account Settings
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSettingsClick() }
                    .padding(vertical = 11.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(34.dp).clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF2A1A5E).copy(alpha = 0.8f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.Settings, null, tint = Color(0xFFAA88FF), modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text("Account Settings", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }

            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier.fillMaxWidth().height(0.8.dp)
                    .background(
                        Brush.linearGradient(
                            listOf(Color.Transparent, Color(0xFF6A50D3).copy(alpha = 0.7f), Color.Transparent)
                        )
                    )
            )
            Spacer(Modifier.height(4.dp))

            if (onSwitchToAcademicClick != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSwitchToAcademicClick() }
                        .padding(vertical = 11.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(Modifier.width(4.dp))
                    Text("Return to Academic Mode", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }

                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier.fillMaxWidth().height(0.8.dp)
                        .background(
                            Brush.linearGradient(
                                listOf(Color.Transparent, Color(0xFF6A50D3).copy(alpha = 0.7f), Color.Transparent)
                            )
                        )
                )
                Spacer(Modifier.height(4.dp))
            }

            // Sign Out
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSignOutClick() }
                    .padding(vertical = 11.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(34.dp).clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF2A0A0A).copy(alpha = 0.8f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.ExitToApp, null, tint = Color(0xFFFF6B6B), modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text("Sign Out", color = Color(0xFFFF9090), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}
