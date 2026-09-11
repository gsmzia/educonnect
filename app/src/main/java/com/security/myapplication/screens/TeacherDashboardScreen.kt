package com.security.myapplication.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import com.security.myapplication.ui.theme.AppMotion
import com.security.myapplication.ui.theme.pumpBounceScroll
import com.security.myapplication.audio.MiniAudioPlayerBar
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration // used: suppress Android glow on all scrollables
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Architecture
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Biotech
import androidx.compose.material.icons.outlined.BusinessCenter
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.ElectricBolt
import androidx.compose.material.icons.outlined.ExitToApp
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.HistoryEdu
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import androidx.navigation.NavController
import com.security.myapplication.models.*
import com.security.myapplication.network.ApiClient
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import retrofit2.HttpException
import androidx.compose.material.icons.outlined.CoPresent
import androidx.compose.material.icons.outlined.School

// ── Dashboard Colour Tokens & Shared Keys ───────────────────────────────────
private val DashBgTop    = Color(0xFF131244)
private val DashBgBottom = Color(0xFF08081E)
private val DashPurple   = Color(0xFF7B5CF5)
private val DashTabSel   = Color(0xFF8B6BFF)

object PrefsKeys {
    const val APP_PREFS = "app_prefs"
    const val CACHED_GROUPS = "cached_groups"
}

object DegreeTheme {
    fun color(degree: String?): Color = when (degree) {
        "MS" -> Color(0xFF1DE9B6)
        "PhD" -> Color(0xFFFFB300)
        else -> Color(0xFF8B6BFF)
    }

    fun iconBg(degree: String?): Color = when (degree) {
        "MS" -> Color(0xFF053331)
        "PhD" -> Color(0xFF3A2D0D)
        else -> Color(0xFF2A214A)
    }

    fun listBorder(degree: String?): Color = when (degree) {
        "MS" -> Color(0xFF0A4A48)
        "PhD" -> Color(0xFF5C4410)
        else -> Color(0xFF26264C)
    }

    fun cardGradient(degree: String?): List<Color> = when (degree) {
        "MS" -> listOf(Color(0xFF065956), Color(0xFF022B2A))
        "PhD" -> listOf(Color(0xFF8C6D1F), Color(0xFF382905))
        else -> listOf(Color(0xFF37127E), Color(0xFF160538))
    }

    fun cardBorder(degree: String?): Color = when (degree) {
        "MS" -> Color(0xFF0C827E)
        "PhD" -> Color(0xFFB38B22)
        else -> Color(0xFF5628B5)
    }
}

// ── Debounced Clickable Helper to Prevent Double-Tap Stutter ─────────────────
@Composable
fun Modifier.debouncedClickable(debounceTime: Long = 400L, onClick: () -> Unit): Modifier {
    var lastClickTime by remember { mutableStateOf(0L) }
    return this.clickable {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastClickTime >= debounceTime) {
            lastClickTime = currentTime
            onClick()
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Teacher Dashboard Entry Point
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TeacherDashboardScreen(userId: Int, navController: NavController) {
    var selectedTab by remember { mutableStateOf(0) }
    var groups by remember { mutableStateOf<List<Group>>(emptyList()) }
    var selectedGroup by remember { mutableStateOf<Group?>(null) }
    var selectedChatUser by remember { mutableStateOf<PersonalChatUser?>(null) }
    var pendingHighlightMsgId by remember { mutableStateOf<Int?>(null) }
    var activeDegree by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf("") }
    var showMenuDropdown by remember { mutableStateOf(false) }
    var showProfileSheet by remember { mutableStateOf(false) }
    var showEditProfileDialog by remember { mutableStateOf(false) }
    var showFullProfileScreen by remember { mutableStateOf(false) }
    var userProfile by remember { mutableStateOf<User?>(null) }
    var editNameText by remember { mutableStateOf("") }
    var isSavingProfile by remember { mutableStateOf(false) }
    var unclaimedSubjects by remember { mutableStateOf<List<com.security.myapplication.academic.UnclaimedSubjectResponse>>(emptyList()) }
    var subjectToClaim by remember { mutableStateOf<com.security.myapplication.academic.UnclaimedSubjectResponse?>(null) }
    var isClaimingSubject by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    // Handle deep link navigation from NotificationRouter
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
                selectedTab = 1
                com.security.myapplication.notifications.NotificationRouter.clearPendingTarget()
            } else if (target.type == "group" && target.groupId > 0) {
                selectedChatUser = null
                selectedGroup = Group(
                    id = target.groupId,
                    name = target.groupName,
                    degree = "",
                    major = "",
                    academic_year = ""
                )
                selectedTab = 1
                com.security.myapplication.notifications.NotificationRouter.clearPendingTarget()
            }
        }
    }

    val topThemeColor = DegreeTheme.color(activeDegree)

    val sharedPrefs = context.getSharedPreferences(PrefsKeys.APP_PREFS, android.content.Context.MODE_PRIVATE)
    val gson = Gson()

    // Global online presence heartbeat (exponential backoff on failure)
    LaunchedEffect(userId) {
        var failCount = 0
        while (true) {
            try {
                ApiClient.apiService.pingOnline(userId)
                failCount = 0
            } catch (e: Exception) {
                Log.w("TeacherDashboard", "Ping online failed: ${e.message}")
                failCount = (failCount + 1).coerceAtMost(5)
            }
            kotlinx.coroutines.delay(3000L * (1 shl failCount))
        }
    }


    fun syncData(isInitialLoad: Boolean = false) {
        coroutineScope.launch {
            if (isInitialLoad || groups.isEmpty()) {
                isLoading = true
            }
            errorMessage = ""
            try {
                val freshGroups = ApiClient.apiService.getUserGroups(userId)
                groups = freshGroups
                // Fetch any unclaimed new subjects in the university
                val cachedKey = sharedPrefs.getString("cached_uni_key_$userId", "") ?: ""
                if (cachedKey.isNotBlank() && cachedKey != "none") {
                    try {
                        val unclaimedResp = ApiClient.apiService.getUnclaimedSubjects(cachedKey)
                        unclaimedSubjects = unclaimedResp.unclaimed_subjects
                    } catch (_: Exception) {}
                }
                // Strip profile_pic blobs before caching — SharedPreferences is XML-backed
                // and large Base64 strings cause slow cold-start disk I/O
                val groupsWithoutBlobs = freshGroups.map { it.copy() }
                sharedPrefs.edit().putString("${PrefsKeys.CACHED_GROUPS}_$userId", gson.toJson(groupsWithoutBlobs)).apply()
            } catch (e: Exception) {
                Log.e("TeacherDashboard", "syncData failed: ${e.message}", e)
                errorMessage = "Connection error: ${e.message}"
            }
            isLoading = false
        }
    }

    // Load cache first then immediately sync — sequential so fresh data never overwrites cache
    LaunchedEffect(userId) {
        com.security.myapplication.storage.EduConnectStorageManager.ensureFoldersExist(context, userId)
        // Step 1: Show cached data instantly
        val cachedGroupsStr = sharedPrefs.getString("${PrefsKeys.CACHED_GROUPS}_$userId", null)
        if (cachedGroupsStr != null) {
            try {
                val type = object : TypeToken<List<Group>>() {}.type
                groups = gson.fromJson(cachedGroupsStr, type)
            } catch (e: Exception) {
                Log.e("TeacherDashboard", "Failed to deserialize cached groups: ${e.message}", e)
            }
        }
        // Step 2: Fetch fresh data (guaranteed to run after cache is set)
        syncData(isInitialLoad = groups.isEmpty())
    }

    // Fetch user profile
    LaunchedEffect(userId) {
        try {
            userProfile = ApiClient.apiService.getUser(userId)
        } catch (e: Exception) {
            Log.e("TeacherDashboard", "Failed to fetch user profile: ${e.message}", e)
        }
    }

    // ── On subsequent resumes (back from chat etc.), refresh groups ────────
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

    // ── Handle system back button for overlays & tabs ───────────────────────
    BackHandler(enabled = selectedChatUser != null || selectedGroup != null || showFullProfileScreen || activeDegree != null || selectedTab != 0) {
        when {
            selectedChatUser != null -> selectedChatUser = null
            selectedGroup != null -> selectedGroup = null
            showFullProfileScreen -> showFullProfileScreen = false
            activeDegree != null -> activeDegree = null
            selectedTab != 0 -> selectedTab = 0
        }
    }

    CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color(0xFF0D0A22))
        ) {
        // Top smooth radial lighting glow for entire screen
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

        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            // ── Custom TopBar ─────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // EDU (White) + Connect (Purple)
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)) { append("EDU ") }
                        withStyle(SpanStyle(color = Color(0xFF8B6BFF), fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)) { append("Connect") }
                    }
                )

                // Camera + 3-dot
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier.size(36.dp).clip(CircleShape)
                            .background(Color(0xFF1D1A3A))
                            .border(1.dp, Color(0xFF2A2650), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Outlined.PhotoCamera, null, tint = Color(0xFF8B6BFF), modifier = Modifier.size(18.dp))
                    }

                    Box {
                        Box(
                            modifier = Modifier
                                .size(36.dp).clip(CircleShape)
                                .background(Color(0xFF1D1A3A))
                                .border(1.dp, Color(0xFF2A2650), CircleShape)
                                .clickable { showMenuDropdown = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Outlined.MoreVert, null, tint = Color.White, modifier = Modifier.size(20.dp))
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
                        TeacherMenuDropdownContent(
                            userProfile = userProfile,
                            onProfileClick = {
                                showMenuDropdown = false
                                showFullProfileScreen = true
                            },
                            onSettingsClick = {
                                showMenuDropdown = false
                                showFullProfileScreen = true
                            },
                            onSwitchToSimpleClick = {
                                showMenuDropdown = false
                                coroutineScope.launch {
                                    try {
                                        com.security.myapplication.storage.SessionModeManager.switchMode(
                                            context.applicationContext, userId,
                                            com.security.myapplication.storage.SessionModeManager.SIMPLE
                                        )
                                        navController.navigate("dashboard/$userId/Teacher") {
                                            popUpTo("dashboard/$userId/Teacher") { inclusive = true }
                                        }
                                    } catch (_: Exception) {
                                        Toast.makeText(context, "Could not switch mode. Check your connection.", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            onSignOutClick = {
                                showMenuDropdown = false
                                val sp = context.getSharedPreferences(PrefsKeys.APP_PREFS, android.content.Context.MODE_PRIVATE)
                                com.security.myapplication.models.clearUserSessionData(context, userId)
                                sp.edit().clear().apply()
                                navController.navigate("login") { popUpTo(0) }
                            }
                        )
                    }
                } // 3-dot Box
            } // Camera + 3-dot Row
            } // TopBar Row

            // ── Floating Mini Audio Player Bar ───────────────────────────────
            MiniAudioPlayerBar(modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))

            // ── Profile Sheet ──────────────────────────────────────────────────
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
                                        editNameText = userProfile?.name ?: ""
                                        showEditProfileDialog = true
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    (userProfile?.name?.firstOrNull()?.uppercaseChar() ?: "T").toString(),
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
                                userProfile?.name ?: "-",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                            Spacer(Modifier.height(4.dp))
                            // Username
                            Text(
                                "@${userProfile?.username ?: "-"}",
                                color = Color(0xFF8B6BFF),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(4.dp))
                            // Email
                            Text(
                                userProfile?.email ?: "-",
                                color = Color(0xFF6868A0),
                                fontSize = 12.sp
                            )
                            Spacer(Modifier.height(10.dp))
                            // Role Badge
                            val role = userProfile?.role ?: "Teacher"
                            val roleColor = if (role == "Teacher") Color(0xFF1DE9B6) else Color(0xFF8B6BFF)
                            val roleBg = if (role == "Teacher") Color(0xFF0F2E26) else Color(0xFF1A1040)
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(roleBg)
                                    .border(1.dp, roleColor.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                                    .padding(horizontal = 14.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    role,
                                    color = roleColor,
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
                                    val sharedPrefs2 = context.getSharedPreferences(PrefsKeys.APP_PREFS, android.content.Context.MODE_PRIVATE)
                                    com.security.myapplication.models.clearUserSessionData(context, userId)
                                    sharedPrefs2.edit().clear().apply()
                                    navController.navigate("login") { popUpTo(0) }
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

            // ── Edit Profile Dialog ────────────────────────────────────────────
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
                                    (editNameText.firstOrNull()?.uppercaseChar() ?: "T").toString(),
                                    color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold
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
                                color = Color(0xFF6868A0), fontSize = 11.sp
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
                                            userProfile = updated
                                            showEditProfileDialog = false
                                        } catch (e: Exception) {
                                            Log.e("TeacherDashboard", "Failed to update profile: ${e.message}", e)
                                        }
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

            // ── Sleek Segmented Pill Control (No line break) ───────────────────
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
                        val tabLabels = listOf("Degrees", "Chats", "Posts")
                        val tabIcons  = listOf(Icons.Outlined.Layers, Icons.Outlined.Forum, Icons.Outlined.Campaign)
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
                                    .padding(horizontal = 18.dp, vertical = 6.dp),
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

            // ── Tab Content ───────────────────────────────────────────────────
            when (selectedTab) {
                0 -> {
                    // Smooth crossfade between spinner, error, and content states
                    val uiState = when {
                        isLoading && groups.isEmpty() -> "loading"
                        errorMessage.isNotEmpty() && groups.isEmpty() -> "error"
                        else -> "content"
                    }
                    Crossfade(targetState = uiState, animationSpec = androidx.compose.animation.core.tween(300), label = "dashboard_state") { state ->
                        when (state) {
                            "loading" -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = DashPurple)
                                    Spacer(Modifier.height(12.dp))
                                    Text("Syncing your dashboard...", color = Color(0xFF6868A0), fontSize = 13.sp)
                                }
                            }
                            "error" -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(20.dp)) {
                                    Text("⚠️", fontSize = 48.sp)
                                    Spacer(Modifier.height(16.dp))
                                    Text("Connection Problem", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                    Text("We couldn't reach the server.", color = Color(0xFF6868A0))
                                    Spacer(Modifier.height(24.dp))
                                    Button(onClick = { syncData() }, colors = ButtonDefaults.buttonColors(containerColor = topThemeColor)) {
                                        Text("Try Again")
                                    }
                                }
                            }
                            else -> Column(modifier = Modifier.fillMaxSize()) {
                                // ── Broadcast Banner for Unclaimed Subjects ──────────────────────────
                                if (unclaimedSubjects.isNotEmpty()) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 4.dp)
                                    ) {
                                        unclaimedSubjects.forEach { subj ->
                                            Card(
                                                colors = CardDefaults.cardColors(containerColor = Color(0xFF221644)),
                                                border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFF97316).copy(alpha = 0.8f)),
                                                shape = RoundedCornerShape(14.dp),
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                            ) {
                                                Column(modifier = Modifier.padding(14.dp)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Box(
                                                            modifier = Modifier
                                                                .size(36.dp)
                                                                .clip(CircleShape)
                                                                .background(Color(0xFFF97316).copy(alpha = 0.18f)),
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            Icon(Icons.Outlined.Campaign, null, tint = Color(0xFFF97316), modifier = Modifier.size(20.dp))
                                                        }
                                                        Spacer(Modifier.width(10.dp))
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                                Text("New Subject Added!", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                                                Spacer(Modifier.width(6.dp))
                                                                Box(
                                                                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFFF97316).copy(alpha = 0.2f)).padding(horizontal = 6.dp, vertical = 2.dp)
                                                                ) {
                                                                    Text("UNASSIGNED", color = Color(0xFFF97316), fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
                                                                }
                                                            }
                                                            Text(
                                                                "${subj.subject_name} • ${subj.program_name} ${subj.department_name} (Sem ${subj.semester_num})",
                                                                color = Color(0xFFCBD5E1),
                                                                fontSize = 12.sp,
                                                                fontWeight = FontWeight.SemiBold
                                                            )
                                                        }
                                                    }
                                                    Spacer(Modifier.height(8.dp))
                                                    Text(
                                                        "Are you the teacher for this subject? Tap below to claim and become group admin.",
                                                        color = Color(0xFF8B88A6),
                                                        fontSize = 11.5.sp
                                                    )
                                                    Spacer(Modifier.height(10.dp))
                                                    Button(
                                                        onClick = { subjectToClaim = subj },
                                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF97316)),
                                                        shape = RoundedCornerShape(10.dp),
                                                        modifier = Modifier.fillMaxWidth().height(38.dp)
                                                    ) {
                                                        Icon(Icons.Filled.Add, null, tint = Color.White, modifier = Modifier.size(16.dp))
                                                        Spacer(Modifier.width(6.dp))
                                                        Text("Claim This Subject (Become Admin)", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161338)),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2C2468)),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(0xFF281E5E)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(Icons.Outlined.AccountBalance, null, tint = Color(0xFF8B6BFF), modifier = Modifier.size(20.dp))
                                            }
                                            Spacer(Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text("University Teaching Portal", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                                Text("Assign yourself to official class subjects", color = Color(0xFF8B88A6), fontSize = 11.sp)
                                            }
                                        }
                                        Spacer(Modifier.height(10.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            val cachedKey = context.getSharedPreferences(PrefsKeys.APP_PREFS, Context.MODE_PRIVATE).getString("cached_uni_key_$userId", "") ?: "none"
                                            val passKey = if (cachedKey.isNotBlank()) cachedKey else "none"
                                            Button(
                                                onClick = { navController.navigate("teacher-subject-picker/$userId/$passKey") },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7B5CF5)),
                                                shape = RoundedCornerShape(10.dp),
                                                modifier = Modifier.weight(1f).height(38.dp),
                                                contentPadding = PaddingValues(horizontal = 8.dp)
                                            ) {
                                                Icon(Icons.Outlined.CoPresent, null, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(6.dp))
                                                Text("Pick Subjects", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            }
                                            OutlinedButton(
                                                onClick = { navController.navigate("university-verification") },
                                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3E367A)),
                                                shape = RoundedCornerShape(10.dp),
                                                modifier = Modifier.weight(1f).height(38.dp),
                                                contentPadding = PaddingValues(horizontal = 8.dp)
                                            ) {
                                                Text("Campus Founder", color = Color(0xFFA78BFA), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                            }
                                        }
                                    }
                                }
                                Box(modifier = Modifier.weight(1f)) {
                                    if (groups.isEmpty()) {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                modifier = Modifier.padding(24.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(64.dp)
                                                        .clip(CircleShape)
                                                        .background(Color(0xFF22194D)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(Icons.Outlined.CoPresent, null, tint = Color(0xFFA78BFA), modifier = Modifier.size(32.dp))
                                                }
                                                Spacer(Modifier.height(16.dp))
                                                Text("No Teaching Classes Yet", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                                Spacer(Modifier.height(6.dp))
                                                Text(
                                                    "Claim your assigned university subjects to create official student chat groups.",
                                                    color = Color(0xFF8B88A6),
                                                    fontSize = 13.sp,
                                                    textAlign = TextAlign.Center
                                                )
                                                Spacer(Modifier.height(20.dp))
                                                val cachedKey = sharedPrefs.getString("cached_uni_key_$userId", "") ?: "none"
                                                val passKey = if (cachedKey.isNotBlank()) cachedKey else "none"
                                                Button(
                                                    onClick = { navController.navigate("teacher-subject-picker/$userId/$passKey") },
                                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7B5CF5)),
                                                    shape = RoundedCornerShape(12.dp)
                                                ) {
                                                    Icon(Icons.Outlined.CoPresent, null, modifier = Modifier.size(18.dp))
                                                    Spacer(Modifier.width(8.dp))
                                                    Text("Pick Subjects Now", fontWeight = FontWeight.SemiBold)
                                                }
                                            }
                                        }
                                    } else {
                                        TeacherGroupsTab(
                                            groups = groups,
                                            onGroupSelected = { g ->
                                                selectedGroup = g
                                                selectedTab = 1
                                            }
                                        )
                                    }
                                }
                            }
                        } // else (content)
                    } // Crossfade
                }
                1 -> UnifiedHomeScreen(
                    userId = userId,
                    onGroupSelected = { selectedGroup = it },
                    onDirectSelected = { selectedChatUser = it }
                )
                2 -> com.security.myapplication.posts.PostsScreen(
                    userId = userId,
                    userRole = "Teacher",
                    user = userProfile,
                    onMenuClick = { showProfileSheet = true }
                )
            }
        }

        // ── Full Profile Screen Overlay ───────────────────────────────────────
        AnimatedVisibility(
            visible = showFullProfileScreen,
            enter   = AppMotion.chatEnter,
            exit    = AppMotion.chatExit,
            modifier = Modifier.fillMaxSize().zIndex(10f)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0D0A22))
            ) {
                ProfileScreen(
                    userId = userId,
                    onBack = { showFullProfileScreen = false },
                    onUserUpdated = { updatedUser ->
                        userProfile = updatedUser
                    }
                )
            }
        }

        // ── Personal Chat Overlay ─────────────────────────────────────────────
        AnimatedVisibility(
            visible = selectedChatUser != null,
            enter   = AppMotion.chatEnter,
            exit    = AppMotion.chatExit,
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

        // ── Group Chat Overlay ────────────────────────────────────────────────
        AnimatedVisibility(
            visible = selectedGroup != null,
            enter   = AppMotion.chatEnter,
            exit    = AppMotion.chatExit,
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
    }
} // CompositionLocalProvider
} // TeacherDashboardScreen


@Composable
fun TeacherGroupsTab(groups: List<Group>, onGroupSelected: (Group) -> Unit) {
    if (groups.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("💬", fontSize = 64.sp)
                Spacer(modifier = Modifier.height(16.dp))
                Text("No groups yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, color = Color.White)
                Text("Go to Programs tab to create your first group", style = MaterialTheme.typography.bodySmall, color = Color(0xFF6868A0))
            }
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(groups, key = { it.id }) { group ->
            GroupListItem(group = group, onClick = { onGroupSelected(group) })
            HorizontalDivider(modifier = Modifier.padding(start = 72.dp), color = Color(0xFF1E1E50))
        }
    }
}

@Composable
fun GroupListItem(group: Group, onClick: () -> Unit) {
    val avatarColors = listOf(Color(0xFF6200EE), Color(0xFF00897B), Color(0xFFE53935), Color(0xFF1565C0), Color(0xFF6D4C41), Color(0xFF558B2F))
    val avatarIndex = (Math.abs(group.id)) % avatarColors.size
    val avatarColor = avatarColors[avatarIndex]

    Row(modifier = Modifier.fillMaxWidth().debouncedClickable { onClick() }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(52.dp).clip(CircleShape).background(avatarColor), contentAlignment = Alignment.Center) { Text(group.degree, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(group.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, color = Color.White)
            Text("${group.major} • ${group.academic_year}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF6868A0), maxLines = 1)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 3-Dot Top-Right Menu Dropdown Content (Extracted for Clean Nesting)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun TeacherMenuDropdownContent(
    userProfile: User?,
    onProfileClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSwitchToSimpleClick: () -> Unit,
    onSignOutClick: () -> Unit
) {
    var menuBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(userProfile?.profile_pic) {
        menuBitmap = withContext(Dispatchers.Default) {
            userProfile?.profile_pic?.let { b64 ->
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
                        inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
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
                            (userProfile?.name?.firstOrNull()?.uppercaseChar() ?: "P").toString(),
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

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSwitchToSimpleClick() }
                    .padding(vertical = 11.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(Modifier.width(4.dp))
                Text("Switch to Simple User", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
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

        // ── Teacher Claim Confirmation Dialog ─────────────────────────────────
        if (subjectToClaim != null) {
            val subj = subjectToClaim!!
            AlertDialog(
                onDismissRequest = { if (!isClaimingSubject) subjectToClaim = null },
                containerColor = Color(0xFF141130),
                tonalElevation = 0.dp,
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Campaign, null, tint = Color(0xFFF97316), modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Confirm Instructor Role", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    }
                },
                text = {
                    Column {
                        Text(
                            "Are you sure you are the instructor for:",
                            color = Color(0xFF8B88A6),
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF1F1A44))
                                .padding(12.dp)
                        ) {
                            Column {
                                Text(subj.subject_name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                Text("${subj.program_name} ${subj.department_name} • Semester ${subj.semester_num}", color = Color(0xFFA78BFA), fontSize = 12.sp)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "By confirming, you will be assigned as the official teacher and become the Admin of this class group.",
                            color = Color(0xFFCBD5E1),
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val cachedKey = sharedPrefs.getString("cached_uni_key_$userId", "") ?: ""
                            isClaimingSubject = true
                            coroutineScope.launch {
                                try {
                                    val res = withContext(Dispatchers.IO) {
                                        ApiClient.apiService.claimSubject(
                                            com.security.myapplication.academic.ClaimSubjectRequest(
                                                uni_key = cachedKey,
                                                subject_id = subj.subject_id,
                                                teacher_id = userId
                                            )
                                        )
                                    }
                                    Toast.makeText(context, res.message, Toast.LENGTH_LONG).show()
                                    subjectToClaim = null
                                    syncData()
                                } catch (e: Exception) {
                                    Toast.makeText(context, e.message ?: "Failed to claim subject.", Toast.LENGTH_LONG).show()
                                } finally {
                                    isClaimingSubject = false
                                }
                            }
                        },
                        enabled = !isClaimingSubject,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF30C96B))
                    ) {
                        if (isClaimingSubject) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Text("Yes, I'm the Teacher", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { if (!isClaimingSubject) subjectToClaim = null }) {
                        Text("Cancel", color = Color(0xFF8B88A6))
                    }
                }
            )
        }

}
