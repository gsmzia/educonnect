package com.security.myapplication.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.security.myapplication.models.LoginRequest
import com.security.myapplication.network.ApiClient
import com.security.myapplication.ui.theme.pumpBounceScroll
import kotlinx.coroutines.launch
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.ChevronRight

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// COLOUR TOKENS — matched from SignupScreen
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
private val BgTop       = Color(0xFF131244)
private val BgBottom    = Color(0xFF08081E)
private val BgField     = Color(0xFF0F0F2C)
private val FieldBorder = Color(0xFF1E1E50)
private val BgIconBox   = Color(0xFF191940)
private val PurpleBtn   = Color(0xFF7B5CF5)
private val BlueBtn     = Color(0xFF4FA3F8)
private val IconPurple  = Color(0xFF7B5CF5)
private val GhostColor  = Color(0xFF272768)
private val TextWhite   = Color(0xFFFFFFFF)
private val TextGray    = Color(0xFF6868A0)
private val TitlePurple = Color(0xFF8B6BFF)

// Compiled once per class-load instead of once per button tap.
private val EMAIL_REGEX = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\$".toRegex()

private const val MAX_EMAIL_LEN = 254
private const val MAX_PASSWORD_LEN = 128

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LoginScreen(navController: NavController) {
    var email        by remember { mutableStateOf("") }
    var password     by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var isLoading    by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    val scope        = rememberCoroutineScope()
    val context      = androidx.compose.ui.platform.LocalContext.current
    val scrollState  = rememberScrollState()

    // ── ROOT: vertical gradient background ──────────────────────────────
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.00f to BgTop,
                        0.30f to Color(0xFF0E0D36),
                        0.60f to Color(0xFF0A0A26),
                        1.00f to BgBottom
                    )
                )
            )
    ) {
        // ── GHOST ICONS ─────────────────────────────────────────────────
        Icon(
            Icons.Outlined.MenuBook, null,
            tint = GhostColor,
            modifier = Modifier
                .size(46.dp).offset(x = 20.dp, y = 60.dp)
                .graphicsLayer { rotationZ = -20f }.alpha(0.85f)
        )
        Icon(
            Icons.Outlined.AccountBalance, null,
            tint = GhostColor,
            modifier = Modifier
                .size(40.dp).offset(x = 298.dp, y = 76.dp)
                .graphicsLayer { rotationZ = 10f }.alpha(0.80f)
        )
        Icon(
            Icons.Outlined.BarChart, null,
            tint = GhostColor,
            modifier = Modifier
                .size(38.dp).offset(x = 6.dp, y = 206.dp)
                .graphicsLayer { rotationZ = -18f }.alpha(0.75f)
        )

        // ── SPARKLE STARS ───────────────────────────────────────────────
        Text("✦", color = Color(0xFF2E2E88), fontSize = 9.sp, modifier = Modifier.offset(x = 190.dp, y = 46.dp).alpha(0.90f))
        Text("✦", color = Color(0xFF2E2E88), fontSize = 7.sp, modifier = Modifier.offset(x = 270.dp, y = 56.dp).alpha(0.65f))
        Text("✦", color = Color(0xFF2E2E88), fontSize = 6.sp, modifier = Modifier.offset(x = 48.dp, y = 116.dp).alpha(0.50f))
        Text("✦", color = Color(0xFF2E2E88), fontSize = 6.sp, modifier = Modifier.offset(x = 14.dp, y = 164.dp).alpha(0.40f))

        // ── DOT GRID ───────────────────────────────────────────────────
        Column(modifier = Modifier.align(Alignment.TopEnd).padding(top = 220.dp, end = 8.dp).alpha(0.30f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(7) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(5) { Box(Modifier.size(3.dp).clip(CircleShape).background(Color(0xFF5055BB))) }
                }
            }
        }
        Column(modifier = Modifier.align(Alignment.TopStart).padding(top = 290.dp, start = 4.dp).alpha(0.16f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(5) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(4) { Box(Modifier.size(3.dp).clip(CircleShape).background(Color(0xFF5055BB))) }
                }
            }
        }

        // ── SCROLLABLE CONTENT WITH PUMP BOUNCE ───────────────────────────
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .pumpBounceScroll()
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(16.dp))

            // Graduation Cap (Balanced size, moved closer to text)
            Box(modifier = Modifier.height(180.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(modifier = Modifier.requiredSize(220.dp).offset(y = 15.dp).background(Brush.radialGradient(listOf(Color(0x407B5CF5), Color.Transparent))).alpha(0.6f))
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(id = com.security.myapplication.R.drawable.degree),
                    contentDescription = "Graduation Cap",
                    modifier = Modifier.requiredSize(280.dp).offset(y = 15.dp)
                )
            }

            Spacer(Modifier.height(4.dp))

            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = TextWhite, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp, letterSpacing = 0.5.sp)) { append("EDU ") }
                    withStyle(SpanStyle(color = Color(0xFF8B6BFF), fontWeight = FontWeight.ExtraBold, fontSize = 32.sp, letterSpacing = 0.5.sp)) { append("Connect") }
                }
            )

            Spacer(Modifier.height(10.dp))

            Text(
                "Your smart way to learn,\nteach and grow together.",
                color = TextGray, fontSize = 14.sp,
                textAlign = TextAlign.Center, lineHeight = 22.sp
            )

            Spacer(Modifier.height(20.dp))

            // ── EMAIL ────────────────────────────────────────────────────
            CustomInputField(
                label = "Email",
                value = email,
                onValueChange = { email = it.take(MAX_EMAIL_LEN) },
                placeholder = "Enter your Email address",
                keyboardType = KeyboardType.Email,
                icon = { Icon(Icons.Outlined.Email, null, tint = IconPurple, modifier = Modifier.size(24.dp)) }
            )

            Spacer(Modifier.height(14.dp))

            // ── PASSWORD ─────────────────────────────────────────────────
            CustomInputField(
                label = "Password",
                value = password,
                onValueChange = { password = it.take(MAX_PASSWORD_LEN) },
                placeholder = "Enter your password",
                isPassword = true,
                showPassword = showPassword,
                onTogglePassword = { showPassword = !showPassword },
                keyboardType = KeyboardType.Password,
                icon = { Icon(Icons.Outlined.Lock, null, tint = IconPurple, modifier = Modifier.size(24.dp)) }
            )

            Spacer(Modifier.height(10.dp))

            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                Text(
                    "Forgot Password?",
                    color = Color(0xFF7B7BFF), fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { navController.navigate("forgot-password") }
                )
            }

            if (errorMessage.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(errorMessage, color = Color(0xFFFF6B6B), fontSize = 13.sp, textAlign = TextAlign.Center)
            }

            Spacer(Modifier.height(16.dp))

            // ── LOGIN BUTTON ─────────────────────────────────────────────
            PrimaryButton(
                text = "Login",
                isLoading = isLoading,
                onClick = {
                    // Defense-in-depth: the button is already disabled while
                    // isLoading is true, but this closes the tiny race window
                    // where a very fast double-tap could fire twice before
                    // recomposition, which would otherwise send a duplicate
                    // login request to the backend.
                    if (isLoading) return@PrimaryButton

                    val trimmedEmail = email.trim()
                    if (trimmedEmail.isBlank() || password.isBlank()) {
                        errorMessage = "Please fill in all fields."
                        return@PrimaryButton
                    }
                    if (!trimmedEmail.matches(EMAIL_REGEX)) {
                        errorMessage = "Please enter a valid email address."
                        return@PrimaryButton
                    }
                    scope.launch {
                        isLoading = true; errorMessage = ""
                        try {
                            val res = ApiClient.apiService.login(LoginRequest(trimmedEmail, password))
                            val sp = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                            val editor = sp.edit()
                                .putInt("userId", res.user.id)
                                .putString("role", res.user.role)
                                .putString("name", res.user.name)
                                .putString("username", res.user.username ?: "")
                                .putString("email", res.user.email)

                            res.access_token?.let { token ->
                                editor.putString("authToken", token)
                                com.security.myapplication.network.AuthManager.setToken(token)
                            }
                            editor.apply()

                            // The backend is authoritative for the active
                            // content space.  A local preference alone must
                            // never make an academic account appear Simple.
                            com.security.myapplication.storage.SessionModeManager.persist(
                                context.applicationContext,
                                res.user.id,
                                res.active_session_mode ?: res.user.active_session_mode ?: "academic"
                            )

                            // SharedPreferences are erased on uninstall.  The
                            // authenticated login response restores this user's
                            // exact university tenant before media lookup, so we
                            // never guess from another folder on the device.
                            res.university_name?.takeIf { it.isNotBlank() }?.let { universityName ->
                                com.security.myapplication.storage.EduConnectStorageManager
                                    .setCachedUniversityName(context.applicationContext, res.user.id, universityName)
                            }
                            com.security.myapplication.storage.EduConnectStorageManager
                                .ensureFoldersExist(context.applicationContext, res.user.id)

                            // Long-lived work (notification watcher, async FCM
                            // callback below) should never hold an Activity
                            // Context — use the application context so nothing
                            // leaks if the user navigates away quickly.
                            val appContext = context.applicationContext

                            com.security.myapplication.notifications.AppMessageNotificationWatcher.start(appContext, res.user.id)

                            // Refresh the FCM token after login so push works immediately
                            // even when a previous account cleared local session storage.
                            // We only force a server sync when the token actually changed —
                            // syncing an unchanged token on every single login wastes backend
                            // capacity once this runs across a large, active user base.
                            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                                .addOnSuccessListener { token ->
                                    if (!token.isNullOrBlank()) {
                                        val previousToken = sp.getString(
                                            com.security.myapplication.notifications.EduConnectFCMService.KEY_FCM_TOKEN, null
                                        )
                                        val tokenChanged = token != previousToken
                                        if (tokenChanged) {
                                            sp.edit()
                                                .putString(com.security.myapplication.notifications.EduConnectFCMService.KEY_FCM_TOKEN, token)
                                                .apply()
                                        }
                                        com.security.myapplication.notifications.EduConnectFCMService
                                            .syncTokenToServer(appContext, res.user.id, token, force = tokenChanged)
                                    }
                                }
                                .addOnFailureListener { e ->
                                    android.util.Log.w("LoginScreen", "Failed to fetch FCM token", e)
                                }

                            navController.navigate("dashboard/${res.user.id}/${res.user.role}") {
                                popUpTo("login") { inclusive = true }
                            }
                        } catch (e: Exception) {
                            errorMessage = friendlyErrorMessage(e, "Invalid email or password.")
                        } finally { isLoading = false }
                    }
                }
            )

            Spacer(Modifier.height(24.dp))

            // ── DIVIDER ──────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                HorizontalDivider(modifier = Modifier.weight(1f), color = FieldBorder)
                Text("  or continue with  ", color = TextGray, fontSize = 12.sp)
                HorizontalDivider(modifier = Modifier.weight(1f), color = FieldBorder)
            }

            Spacer(Modifier.height(16.dp))

            // ── SOCIAL BUTTONS ───────────────────────────────────────────
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {

                // Google
                Box(
                    modifier = Modifier
                        .weight(1f).height(48.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(BgField)
                        .border(1.dp, FieldBorder, RoundedCornerShape(16.dp))
                        .clickable { },
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        androidx.compose.foundation.Image(
                            painter = androidx.compose.ui.res.painterResource(id = com.security.myapplication.R.drawable.ic_google_g),
                            contentDescription = "Google Logo",
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Google", color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }

                // Microsoft
                Box(
                    modifier = Modifier
                        .weight(1f).height(48.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(BgField)
                        .border(1.dp, FieldBorder, RoundedCornerShape(16.dp))
                        .clickable { },
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Column(
                            modifier = Modifier.size(20.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFFF25022)))
                                Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFF7FBA00)))
                            }
                            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFF00A4EF)))
                                Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFFFFB900)))
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("Microsoft", color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // ── SIGN UP ROW ──────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(BgField)
                    .border(1.dp, FieldBorder, RoundedCornerShape(16.dp))
                    .clickable { navController.navigate("signup") }
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = buildAnnotatedString {
                            withStyle(SpanStyle(color = TextGray, fontSize = 14.sp)) {
                                append("Don't have an account?  ")
                            }
                            withStyle(SpanStyle(color = Color(0xFF7878FF), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)) {
                                append("Sign up")
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(50))
                            .background(BgIconBox)
                            .border(1.dp, FieldBorder, RoundedCornerShape(50)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Outlined.ArrowForward, null, tint = TextGray, modifier = Modifier.size(15.dp))
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // ── CAMPUS FOUNDER REGISTER CTA ──────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF141334))
                    .border(1.dp, Color(0xFF28235C), RoundedCornerShape(12.dp))
                    .clickable { navController.navigate("university-verification") }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF251F56)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.AccountBalance, null, tint = TitlePurple, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Campus Founder / Administrator?", color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("Register University & Curriculum", color = TextGray, fontSize = 11.5.sp)
                }
                Icon(Icons.Outlined.ChevronRight, null, tint = TextGray, modifier = Modifier.size(18.dp))
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// ERROR HANDLING
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

/**
 * Turns a network/API exception into a user-facing message.
 * - Reads the backend's `detail` field out of the HTTP error body when present.
 * - Falls back to clear, specific text for connectivity issues.
 * - Never leaks a raw stack trace or exception class name to the UI.
 */
private fun friendlyErrorMessage(e: Exception, fallback: String): String {
    return when (e) {
        is retrofit2.HttpException -> {
            val errorBody = try { e.response()?.errorBody()?.string() } catch (_: Exception) { null }
            try {
                val json = org.json.JSONObject(errorBody ?: "")
                json.optString("detail", fallback)
            } catch (_: Exception) {
                "$fallback (code ${e.code()})"
            }
        }
        is java.net.ConnectException ->
            "Cannot connect to server (${ApiClient.BASE_URL}). Make sure backend is running."
        is java.net.SocketTimeoutException ->
            "Connection timed out. Check your WiFi / Network."
        else -> e.localizedMessage ?: fallback
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// UI COMPONENTS
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomInputField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    icon: @Composable () -> Unit,
    isPassword: Boolean = false,
    showPassword: Boolean = false,
    onTogglePassword: () -> Unit = {},
    keyboardType: KeyboardType = KeyboardType.Text
) {
    var isFocused by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(BgField)
            .border(
                width = if (isFocused) 1.5.dp else 1.dp,
                color = if (isFocused) IconPurple else FieldBorder,
                shape = RoundedCornerShape(16.dp)
            )
            .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, color = IconPurple, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = TextStyle(color = TextWhite, fontSize = 15.sp),
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    visualTransformation = if (isPassword && !showPassword) PasswordVisualTransformation() else VisualTransformation.None,
                    cursorBrush = SolidColor(PurpleBtn),
                    modifier = Modifier.fillMaxWidth().onFocusChanged { isFocused = it.isFocused },
                    decorationBox = { innerTextField ->
                        Box {
                            if (value.isEmpty()) {
                                Text(placeholder, color = TextGray, fontSize = 14.sp)
                            }
                            innerTextField()
                        }
                    }
                )
            }
            if (isPassword) {
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onTogglePassword, modifier = Modifier.size(30.dp)) {
                    Icon(
                        if (showPassword) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                        contentDescription = "Toggle password",
                        tint = TextGray,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PrimaryButton(text: String, isLoading: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.horizontalGradient(listOf(PurpleBtn, BlueBtn)))
            .clickable(enabled = !isLoading) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (isLoading) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(26.dp), strokeWidth = 2.5.dp)
        } else {
            Box(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                Text(text, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Box(
                    modifier = Modifier.align(Alignment.CenterEnd).size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.20f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.ArrowForward, null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
