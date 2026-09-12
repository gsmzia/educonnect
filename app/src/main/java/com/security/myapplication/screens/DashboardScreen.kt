package com.security.myapplication.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.activity.compose.BackHandler
import com.security.myapplication.ui.theme.AppMotion
import com.security.myapplication.ui.theme.pumpBounceScroll
import com.security.myapplication.audio.MiniAudioPlayerBar
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import androidx.navigation.NavController
import com.security.myapplication.models.*
import com.security.myapplication.network.ApiClient
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import androidx.compose.material.icons.outlined.School

// ── Dashboard Colour Tokens ───────────────────────────────────────────────────
private val DashBgTop    = Color(0xFF131244)
private val DashBgBottom = Color(0xFF08081E)
private val DashPurple   = Color(0xFF7B5CF5)
private val DashTabSel   = Color(0xFF8B6BFF)

// ─────────────────────────────────────────────────────────────────────────────
// Program Data
// ─────────────────────────────────────────────────────────────────────────────

// Programs are now fetched dynamically from the backend, so we don't need hardcoded lists here.

private fun getYears(degree: String) = when (degree) {
    "BS"  -> listOf("Year 1 – Freshman", "Year 2 – Sophomore", "Year 3 – Junior", "Year 4 – Senior")
    "MS"  -> listOf("Year 1", "Year 2")
    "PhD" -> listOf("Year 1", "Year 2", "Year 3", "Year 4")
    else  -> listOf("Year 1")
}

// ─────────────────────────────────────────────────────────────────────────────
// Main Student Dashboard Screen
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(userId: Int, role: String, navController: NavController) {
    var user by remember { mutableStateOf<User?>(null) }
    var selectedGroup by remember { mutableStateOf<Group?>(null) }
    var groups by remember { mutableStateOf<List<Group>>(emptyList()) }
    var selectedTab by remember { mutableStateOf(1) }
    var chatTabIndex by remember { mutableStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var activeDegree by remember { mutableStateOf<String?>(null) }
    var selectedChatUser by remember { mutableStateOf<PersonalChatUser?>(null) }
    var pendingHighlightMsgId by remember { mutableStateOf<Int?>(null) }
    var statusMessage by remember { mutableStateOf("") }
    var showMenuDropdown by remember { mutableStateOf(false) }
    var showProfileSheet by remember { mutableStateOf(false) }
    var showEditProfileDialog by remember { mutableStateOf(false) }
    var showFullProfileScreen by remember { mutableStateOf(false) }
    var editNameText by remember { mutableStateOf("") }
    var isSavingProfile by remember { mutableStateOf(false) }
    var showQuickAddSubjectDialog by remember { mutableStateOf(false) }
    var newSubjProg by remember { mutableStateOf("BS") }
    var newSubjDept by remember { mutableStateOf("") }
    var newSubjSem by remember { mutableIntStateOf(1) }
    var newSubjName by remember { mutableStateOf("") }
    var isAddingSubject by remember { mutableStateOf(false) }
    var quickAddError by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

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

    val topThemeColor = when (activeDegree) {
        "MS" -> Color(0xFF1DE9B6)
        "PhD" -> Color(0xFFFFB300)
        else -> Color(0xFF8B6BFF)
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    val sharedPrefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
    val cachedKey = sharedPrefs.getString("cached_uni_key_$userId", "") ?: "none"
    val gson = Gson()

    // Global online presence heartbeat (every 3s)
    LaunchedEffect(userId) {
        while (true) {
            try { ApiClient.apiService.pingOnline(userId) } catch (_: Exception) {}
            kotlinx.coroutines.delay(3000)
        }
    }

    // Load cached data initially
    LaunchedEffect(userId) {
        com.security.myapplication.storage.EduConnectStorageManager.ensureFoldersExist(context, userId)
        val cachedUserStr = sharedPrefs.getString("cached_user_$userId", null)
        if (cachedUserStr != null) {
            try {
                val cachedUser = gson.fromJson(cachedUserStr, User::class.java)
                user = cachedUser
                if (cachedUser.degree != null) selectedTab = 1
            } catch(e: Exception) {}
        }
        
        val cachedGroupsStr = sharedPrefs.getString("cached_groups_$userId", null)
        if (cachedGroupsStr != null) {
            try {
                val type = object : TypeToken<List<Group>>() {}.type
                groups = gson.fromJson(cachedGroupsStr, type)
            } catch(e: Exception) {}
        }
    }

    fun syncData() {
        coroutineScope.launch {
            if (user == null || groups.isEmpty()) {
                isLoading = true
            }
            try {
                val userData = ApiClient.apiService.getUser(userId)
                user = userData
                sharedPrefs.edit().putString("cached_user_$userId", gson.toJson(userData)).apply()

                // If student has a university key or groups already exist, show Chat tab by default
                val cachedUniKey = sharedPrefs.getString("cached_uni_key_$userId", null)
                if ((userData.university_id != null || !cachedUniKey.isNullOrBlank()) && groups.isNotEmpty()) {
                    if (selectedTab == 0) selectedTab = 1
                }

                groups = ApiClient.apiService.getUserGroups(userId)
                sharedPrefs.edit().putString("cached_groups_$userId", gson.toJson(groups)).apply()
                statusMessage = ""
            } catch (e: Exception) {
                statusMessage = "Sync failed: ${e.message}"
            }
            isLoading = false
        }
    }

    // ── Every time the screen is entered or app resumes, auto-sync ────────
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
    BackHandler(enabled = selectedChatUser != null || selectedGroup != null || showFullProfileScreen || selectedTab != 0) {
        when {
            selectedChatUser != null -> selectedChatUser = null
            selectedGroup != null -> selectedGroup = null
            showFullProfileScreen -> showFullProfileScreen = false
            selectedTab != 0 -> selectedTab = 0
        }
    }

    // ── Main Dashboard ────────────────────────────────────────────────────────
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

                    if (showMenuDropdown) {
                        androidx.compose.ui.window.Popup(
                            alignment = Alignment.TopEnd,
                            offset = androidx.compose.ui.unit.IntOffset(0, 130),
                            onDismissRequest = { showMenuDropdown = false }
                        ) {
                            // Decode DP bitmap
                            val menuBitmap by produceState<android.graphics.Bitmap?>(null, user?.profile_pic) {
                                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                    user?.profile_pic?.let { b64 ->
                                        try {
                                            val bytes = Base64.decode(b64, Base64.DEFAULT)
                                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
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
                                    // Profile Avatar
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(14.dp))
                                            .clickable {
                                                showMenuDropdown = false
                                                showFullProfileScreen = true
                                            }
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
                            val avatar = menuBitmap
                            if (avatar != null) {
                                Image(
                                    bitmap = avatar.asImageBitmap(),
                                                    contentDescription = "DP",
                                                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                                                    contentScale = ContentScale.Crop
                                                )
                                            } else {
                                                Text(
                                                    (user?.name?.firstOrNull()?.uppercaseChar() ?: "S").toString(),
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
                                            .clickable {
                                                showMenuDropdown = false
                                                showFullProfileScreen = true
                                            }
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

                                    // Campus Classes
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable {
                                                showMenuDropdown = false
                                                val cachedKey = sharedPrefs.getString("cached_uni_key_$userId", "") ?: "none"
                                                val passKey = if (cachedKey.isNotBlank()) cachedKey else "none"
                                                navController.navigate("student-enrollment/$userId/$passKey")
                                            }
                                            .padding(vertical = 11.dp, horizontal = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier.size(34.dp).clip(RoundedCornerShape(10.dp))
                                                .background(Color(0xFF2A1A5E).copy(alpha = 0.8f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Outlined.School, null, tint = Color(0xFFAA88FF), modifier = Modifier.size(18.dp))
                                        }
                                        Spacer(Modifier.width(12.dp))
                                        Text("Campus Classes", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    }

                                    Spacer(Modifier.height(4.dp))

                                    // The server persists this choice before the
                                    // navigation changes.  It keeps academic
                                    // posts/media separated from Simple mode.
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable {
                                                showMenuDropdown = false
                                                coroutineScope.launch {
                                                    try {
                                                        com.security.myapplication.storage.SessionModeManager.switchMode(
                                                            context.applicationContext,
                                                            userId,
                                                            com.security.myapplication.storage.SessionModeManager.SIMPLE
                                                        )
                                                        navController.navigate("dashboard/$userId/$role") {
                                                            popUpTo("dashboard/$userId/$role") { inclusive = true }
                                                        }
                                                    } catch (_: Exception) {
                                                        Toast.makeText(context, "Could not switch mode. Check your connection.", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            }
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
                                            .clickable {
                                                showMenuDropdown = false
                                                val sp = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                                                clearUserSessionData(context, userId)
                                                sp.edit().clear().apply()
                                                navController.navigate("login") { popUpTo(0) }
                                            }
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
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                IconButton(onClick = { showProfileSheet = false }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Outlined.Close, contentDescription = "Close", tint = Color(0xFF6868A0), modifier = Modifier.size(18.dp))
                                }
                            }
                            Spacer(Modifier.height(4.dp))

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
                                    (user?.name?.firstOrNull()?.uppercaseChar() ?: "S").toString(),
                                    color = Color.White,
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                            Spacer(Modifier.height(2.dp))
                            Text("Tap to edit", color = Color(0xFF6868A0), fontSize = 11.sp)
                            Spacer(Modifier.height(12.dp))

                            Text(
                                user?.name ?: "-",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "@${user?.username ?: "-"}",
                                color = Color(0xFF8B6BFF),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                user?.email ?: "-",
                                color = Color(0xFF6868A0),
                                fontSize = 12.sp
                            )
                            Spacer(Modifier.height(10.dp))
                            // Role Badge for Student
                            val studentRole = user?.role ?: "Student"
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(Color(0xFF1A1040))
                                    .border(1.dp, Color(0xFF8B6BFF).copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                                    .padding(horizontal = 14.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    studentRole,
                                    color = Color(0xFF8B6BFF),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(Modifier.height(20.dp))

                            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1E1E50)))
                            Spacer(Modifier.height(16.dp))

                            Button(
                                onClick = {
                                    showProfileSheet = false
                                    val sharedPrefs2 = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                                    clearUserSessionData(context, userId)
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
                            Box(
                                modifier = Modifier.size(64.dp).clip(CircleShape)
                                    .background(Brush.radialGradient(listOf(Color(0xFF8B6BFF), Color(0xFF5628B5))))
                                    .align(Alignment.CenterHorizontally),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    (editNameText.firstOrNull()?.uppercaseChar() ?: "S").toString(),
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
                                            user = updated
                                            showEditProfileDialog = false
                                        } catch (e: Exception) {}
                                        isSavingProfile = false
                                    }
                                }
                            },
                            enabled = !isSavingProfile && editNameText.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF8B6BFF),
                                disabledContainerColor = Color(0xFF8B6BFF).copy(alpha = 0.5f)
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            if (isSavingProfile) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text("Save", fontWeight = FontWeight.Bold, color = Color.White)
                            }
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
                    if (isLoading && user == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = DashPurple)
                                Spacer(Modifier.height(12.dp))
                                Text("Syncing your groups...", color = Color(0xFF6868A0), fontSize = 13.sp)
                            }
                        }
                    } else if (user == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(20.dp)) {
                                Text("⚠️", fontSize = 48.sp)
                                Spacer(Modifier.height(16.dp))
                                Text("Connection Problem", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                Text("Couldn't fetch your data.", color = Color(0xFF6868A0))
                                Spacer(Modifier.height(24.dp))
                                Button(onClick = { syncData() }, colors = ButtonDefaults.buttonColors(containerColor = topThemeColor)) {
                                    Text("Try Again")
                                }
                            }
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            val isFounderUser = (user?.is_founder == true) || role.equals("Founder", ignoreCase = true)
                            val cachedKey = sharedPrefs.getString("cached_uni_key_$userId", "") ?: "none"
                            val passKey = if (cachedKey.isNotBlank()) cachedKey else "none"
                            val cachedUniName = sharedPrefs.getString("cached_uni_name_$userId", null) ?: "Your Campus"

                            if (isFounderUser) {
                                // ── Exclusive Campus Founder Portal Card ───────────────
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1642)),
                                    border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF8B6BFF).copy(alpha = 0.6f)),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                                ) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .size(38.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(0xFF3B2A8A)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(Icons.Outlined.AccountBalance, null, tint = Color(0xFFA78BFA), modifier = Modifier.size(20.dp))
                                            }
                                            Spacer(Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text("Campus Founder Portal", color = Color.White, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                                                    Spacer(Modifier.width(6.dp))
                                                    Box(
                                                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF30C96B).copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 2.dp)
                                                    ) {
                                                        Text("FOUNDER", color = Color(0xFF30C96B), fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
                                                    }
                                                }
                                                Text("Add subjects & programs • Enrolled students auto-join", color = Color(0xFF8B88A6), fontSize = 11.5.sp)
                                            }
                                        }
                                        Spacer(Modifier.height(12.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Button(
                                                onClick = {
                                                    val encodedName = java.net.URLEncoder.encode(cachedUniName, "UTF-8")
                                                    navController.navigate("founder-curriculum-wizard/$passKey/$encodedName")
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7B5CF5)),
                                                shape = RoundedCornerShape(10.dp),
                                                modifier = Modifier.weight(1f).height(40.dp),
                                                contentPadding = PaddingValues(horizontal = 6.dp)
                                            ) {
                                                Icon(Icons.Outlined.Layers, null, modifier = Modifier.size(15.dp))
                                                Spacer(Modifier.width(5.dp))
                                                Text("Curriculum Wizard", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            }
                                            Button(
                                                onClick = {
                                                    quickAddError = null
                                                    newSubjName = ""
                                                    showQuickAddSubjectDialog = true
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E246A)),
                                                shape = RoundedCornerShape(10.dp),
                                                modifier = Modifier.weight(1f).height(40.dp),
                                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF8B6BFF).copy(alpha = 0.5f)),
                                                contentPadding = PaddingValues(horizontal = 6.dp)
                                            ) {
                                                Icon(Icons.Filled.Add, null, tint = Color(0xFFA78BFA), modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text("+ Add Subject", color = Color(0xFFA78BFA), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            } else {
                                // ── Standard Student Classes Card ──────────────────────
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
                                                Icon(Icons.Outlined.School, null, tint = Color(0xFF8B6BFF), modifier = Modifier.size(20.dp))
                                            }
                                            Spacer(Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text("Official Campus Classes", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                                Text("Auto-enroll in your semester subject groups", color = Color(0xFF8B88A6), fontSize = 11.sp)
                                            }
                                        }
                                        Spacer(Modifier.height(10.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Button(
                                                onClick = { navController.navigate("student-enrollment/$userId/$passKey") },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7B5CF5)),
                                                shape = RoundedCornerShape(10.dp),
                                                modifier = Modifier.weight(1f).height(38.dp),
                                                contentPadding = PaddingValues(horizontal = 8.dp)
                                            ) {
                                                Icon(Icons.Outlined.School, null, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(6.dp))
                                                Text("Join Classes", fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                                                Icon(Icons.Outlined.School, null, tint = Color(0xFFA78BFA), modifier = Modifier.size(32.dp))
                                            }
                                            Spacer(Modifier.height(16.dp))
                                            Text("No Campus Classes Joined", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                            Spacer(Modifier.height(6.dp))
                                            Text(
                                                "Tap 'Join Classes' above with your University Key to auto-enroll in all your semester subject groups.",
                                                color = Color(0xFF8B88A6),
                                                fontSize = 13.sp,
                                                textAlign = TextAlign.Center
                                            )
                                            Spacer(Modifier.height(20.dp))
                                            val cachedKey = sharedPrefs.getString("cached_uni_key_$userId", "") ?: "none"
                                            val passKey = if (cachedKey.isNotBlank()) cachedKey else "none"
                                            Button(
                                                onClick = { navController.navigate("student-enrollment/$userId/$passKey") },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7B5CF5)),
                                                shape = RoundedCornerShape(12.dp)
                                            ) {
                                                Icon(Icons.Outlined.School, null, modifier = Modifier.size(18.dp))
                                                Spacer(Modifier.width(8.dp))
                                                Text("Enroll in Classes Now", fontWeight = FontWeight.SemiBold)
                                            }
                                        }
                                    }
                                } else {
                                    StudentGroupsList(
                                        user = user ?: User(id = userId, email = "", username = "", name = "", role = "student", degree = null, major = null, academic_year = null),
                                        groups = groups,
                                        onGroupSelected = { g ->
                                            selectedGroup = g
                                            selectedTab = 1
                                        },
                                        onRefresh = { syncData() },
                                        onResetProfile = { }
                                    )
                                }
                            }
                        }
                    }
                }
                1 -> UnifiedHomeScreen(
                    userId = userId,
                    onGroupSelected = { selectedGroup = it },
                    onDirectSelected = { selectedChatUser = it }
                )
                2 -> com.security.myapplication.posts.PostsScreen(
                    userId = userId,
                    userRole = role,
                    user = user,
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
                        user = updatedUser
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

        // ── Founder Quick Add Subject Dialog ──────────────────────────────────
        if (showQuickAddSubjectDialog) {
            AlertDialog(
                onDismissRequest = { if (!isAddingSubject) showQuickAddSubjectDialog = false },
                containerColor = Color(0xFF141130),
                tonalElevation = 0.dp,
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Add, null, tint = Color(0xFF8B6BFF), modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Add New Subject", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    }
                },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Adding a subject automatically creates the class group and auto-enrolls all students currently in this semester.",
                            color = Color(0xFF8B88A6),
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                        Spacer(Modifier.height(14.dp))

                        // Program selector
                        Text("Degree Program", color = Color(0xFFA78BFA), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("BS", "MS", "PhD").forEach { p ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (newSubjProg == p) Color(0xFF8B6BFF) else Color(0xFF1F1A44))
                                        .clickable { newSubjProg = p }
                                        .padding(horizontal = 14.dp, vertical = 6.dp)
                                ) {
                                    Text(p, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        // Department
                        Text("Department", color = Color(0xFFA78BFA), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = newSubjDept,
                            onValueChange = { newSubjDept = it; quickAddError = null },
                            placeholder = { Text("e.g. Computer Science", color = Color(0xFF6868A0), fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                focusedContainerColor = Color(0xFF1A153A), unfocusedContainerColor = Color(0xFF1A153A),
                                focusedBorderColor = Color(0xFF8B6BFF), unfocusedBorderColor = Color(0xFF2C2468)
                            )
                        )

                        Spacer(Modifier.height(10.dp))

                        // Semester (1 to 8)
                        Text("Semester ($newSubjSem)", color = Color(0xFFA78BFA), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            (1..8).forEach { s ->
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (newSubjSem == s) Color(0xFF8B6BFF) else Color(0xFF1F1A44))
                                        .clickable { newSubjSem = s }
                                        .padding(vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("$s", color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        // Subject Name
                        Text("Subject Name", color = Color(0xFFA78BFA), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = newSubjName,
                            onValueChange = { newSubjName = it; quickAddError = null },
                            placeholder = { Text("e.g. Artificial Intelligence", color = Color(0xFF6868A0), fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                focusedContainerColor = Color(0xFF1A153A), unfocusedContainerColor = Color(0xFF1A153A),
                                focusedBorderColor = Color(0xFF8B6BFF), unfocusedBorderColor = Color(0xFF2C2468)
                            )
                        )

                        if (quickAddError != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(quickAddError!!, color = Color(0xFFFF4D6D), fontSize = 11.5.sp)
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val cachedKey = sharedPrefs.getString("cached_uni_key_$userId", "") ?: ""
                            if (cachedKey.isBlank() || cachedKey == "none") {
                                quickAddError = "University key not found. Please re-open from campus screen."
                                return@Button
                            }
                            if (newSubjDept.isBlank() || newSubjName.isBlank()) {
                                quickAddError = "Please enter both department and subject name."
                                return@Button
                            }
                            isAddingSubject = true
                            quickAddError = null
                            coroutineScope.launch {
                                try {
                                    val res = withContext(Dispatchers.IO) {
                                        ApiClient.apiService.addNewSubject(
                                            com.security.myapplication.academic.AddNewSubjectRequest(
                                                uni_key = cachedKey,
                                                program_name = newSubjProg.trim(),
                                                department_name = newSubjDept.trim(),
                                                semester_num = newSubjSem,
                                                subject_name = newSubjName.trim()
                                            )
                                        )
                                    }
                                    Toast.makeText(context, "${res.group_name} created! ${res.students_auto_enrolled_count} student(s) auto-enrolled.", Toast.LENGTH_LONG).show()
                                    showQuickAddSubjectDialog = false
                                    syncData()
                                } catch (e: Exception) {
                                    quickAddError = e.message ?: "Failed to add subject."
                                } finally {
                                    isAddingSubject = false
                                }
                            }
                        },
                        enabled = !isAddingSubject,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B6BFF))
                    ) {
                        if (isAddingSubject) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Text("Create & Auto-Enroll", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { if (!isAddingSubject) showQuickAddSubjectDialog = false }) {
                        Text("Cancel", color = Color(0xFF8B88A6))
                    }
                }
            )
        }
    }
}


// ─────────────────────────────────────────────────────────────────────────────
// Student Groups List (WhatsApp style)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun StudentGroupsList(
    user: User,
    groups: List<Group>,
    onGroupSelected: (Group) -> Unit,
    onRefresh: () -> Unit,
    onResetProfile: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {



        // ── Groups Header ────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Your Academic Groups",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            TextButton(onClick = onRefresh) { Text("🔄 Sync") }
        }

        HorizontalDivider()

        // ── Groups List ──────────────────────────────────────────────────────
        if (groups.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Text("📭", fontSize = 64.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("No Groups Yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "No teacher has created subjects for ${user.major} – ${user.academic_year} yet.\n\nTap '🔄 Sync' when your teacher adds groups.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(groups) { group ->
                    StudentGroupListItem(group = group, onClick = { onGroupSelected(group) })
                    HorizontalDivider(modifier = Modifier.padding(start = 80.dp))
                }
            }
        }
    }
}

@Composable
fun StudentGroupListItem(group: Group, onClick: () -> Unit) {
    val avatarColors = listOf(
        Color(0xFF6200EE), Color(0xFF00897B), Color(0xFFE53935),
        Color(0xFF1565C0), Color(0xFF6D4C41), Color(0xFF558B2F)
    )
    val avatarColor = avatarColors[group.id % avatarColors.size]

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(54.dp).clip(CircleShape).background(avatarColor),
            contentAlignment = Alignment.Center
        ) {
            Text(group.degree, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(group.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(
                "${group.major} • ${group.academic_year}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        Text("›", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

}
