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
import androidx.compose.material.icons.filled.ArrowBack
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
import com.security.myapplication.models.OTPRequest
import com.security.myapplication.models.SignupRequest
import com.security.myapplication.network.ApiClient
import com.security.myapplication.ui.theme.pumpBounceScroll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.School

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// COLOUR TOKENS — matched from reference image
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
private val BgTop       = Color(0xFF131244)
private val BgBottom    = Color(0xFF08081E)
private val BgField     = Color(0xFF0F0F2C)
private val FieldBorder = Color(0xFF1E1E50)
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
private const val MAX_NAME_LEN = 50
private const val MAX_USERNAME_LEN = 30
private const val MAX_PASSWORD_LEN = 128
private const val MAX_UNI_KEY_LEN = 40

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SignupScreen(navController: NavController) {
    var email by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("Student") } // Default Student
    var showPassword by remember { mutableStateOf(false) }

    var step by remember { mutableStateOf(1) }   // 1 = Info, 2 = OTP
    var otpCode by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var resendCountdown by remember { mutableStateOf(0) }
    var uniKeyInput by remember { mutableStateOf("") }
    var verifiedUniName by remember { mutableStateOf<String?>(null) }
    var isValidatingKey by remember { mutableStateOf(false) }
    var keyValidationError by remember { mutableStateOf<String?>(null) }

    val coroutineScope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scrollState = rememberScrollState()

    fun startResendCountdown() {
        coroutineScope.launch {
            resendCountdown = 60
            while (resendCountdown > 0) {
                delay(1000)
                resendCountdown--
            }
        }
    }

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

        // ── SPARKLE STARS (Background glow effect) ─────────────────────
        Text("✦", color = Color(0xFF5A5AA0), fontSize = 10.sp, modifier = Modifier.offset(x = 140.dp, y = 70.dp).alpha(0.8f))
        Text("✦", color = Color(0xFF5A5AA0), fontSize = 7.sp, modifier = Modifier.offset(x = 250.dp, y = 90.dp).alpha(0.6f))
        Text("✦", color = Color(0xFF5A5AA0), fontSize = 8.sp, modifier = Modifier.offset(x = 270.dp, y = 140.dp).alpha(0.5f))

        // ── DOT GRID ───────────────────────────────────────────────────
        Column(modifier = Modifier.align(Alignment.TopEnd).padding(top = 80.dp, end = 24.dp).alpha(0.20f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(3) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(3) { Box(Modifier.size(3.dp).clip(CircleShape).background(Color(0xFF5055BB))) }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .pumpBounceScroll()
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp, vertical = 8.dp)
        ) {
            // ── TOP BAR (Back Button) ──────────────────────────────────
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF0B0B24))
                    .border(1.dp, FieldBorder, RoundedCornerShape(14.dp))
                    .clickable {
                        if(step == 2) step = 1 else navController.popBackStack()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextWhite, modifier = Modifier.size(20.dp))
            }

            // ── HEADER ─────────────────────────────────────────────────
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Graduation Cap
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
                        withStyle(SpanStyle(color = TextWhite, fontSize = 28.sp, fontWeight = FontWeight.Bold)) {
                            append("Create Your ")
                        }
                        withStyle(SpanStyle(color = TitlePurple, fontSize = 28.sp, fontWeight = FontWeight.Bold)) {
                            append("Account")
                        }
                    }
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = "Join EDU Connect and start\nyour learning journey",
                    color = TextGray, fontSize = 14.sp,
                    textAlign = TextAlign.Center, lineHeight = 20.sp
                )
            }

            Spacer(Modifier.height(18.dp))

            if (step == 1) {
                // ════════════════════════════════════════════════════════════════
                // STEP 1: INFO FORM
                // ════════════════════════════════════════════════════════════════

                // ── FIRST NAME ───────────────────────────────────────────
                CustomInputField(
                    label = "First Name",
                    value = name,
                    onValueChange = { name = it.take(MAX_NAME_LEN) },
                    placeholder = "Enter your first name",
                    icon = { Icon(Icons.Outlined.Person, null, tint = IconPurple, modifier = Modifier.size(24.dp)) }
                )
                Spacer(Modifier.height(14.dp))

                // ── USER NAME ────────────────────────────────────────────
                CustomInputField(
                    label = "User Name",
                    value = username,
                    onValueChange = {
                        // Keep usernames predictable for the backend: lowercase,
                        // spaces become underscores, and anything that isn't a
                        // letter/digit/underscore is stripped out entirely.
                        username = it.lowercase()
                            .replace(" ", "_")
                            .filter { c -> c.isLetterOrDigit() || c == '_' }
                            .take(MAX_USERNAME_LEN)
                    },
                    placeholder = "Choose a user name",
                    icon = { Icon(Icons.Outlined.PersonOutline, null, tint = IconPurple, modifier = Modifier.size(24.dp)) }
                )
                Spacer(Modifier.height(14.dp))

                // ── GMAIL ────────────────────────────────────────────────
                CustomInputField(
                    label = "Gmail",
                    value = email,
                    onValueChange = { email = it.take(MAX_EMAIL_LEN) },
                    placeholder = "Enter your Gmail address",
                    keyboardType = KeyboardType.Email,
                    icon = { Icon(Icons.Outlined.Email, null, tint = IconPurple, modifier = Modifier.size(24.dp)) }
                )
                Spacer(Modifier.height(14.dp))

                // ── PASSWORD ─────────────────────────────────────────────
                CustomInputField(
                    label = "Password",
                    value = password,
                    onValueChange = { password = it.take(MAX_PASSWORD_LEN) },
                    placeholder = "Create a strong password",
                    isPassword = true,
                    showPassword = showPassword,
                    onTogglePassword = { showPassword = !showPassword },
                    keyboardType = KeyboardType.Password,
                    icon = { Icon(Icons.Outlined.Lock, null, tint = IconPurple, modifier = Modifier.size(24.dp)) }
                )

                Spacer(Modifier.height(24.dp))

                // ── ROLE SELECTION ───────────────────────────────────────
                Text("Select Your Role", color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))

                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RoleCard(
                            modifier = Modifier.weight(1f),
                            title = "Student",
                            subtitle = "Learn new skills and achieve your goals",
                            icon = { Text("🎓", fontSize = 24.sp) },
                            iconBg = Color(0xFF1E1A40),
                            isSelected = role == "Student",
                            onClick = { role = "Student" }
                        )
                        RoleCard(
                            modifier = Modifier.weight(1f),
                            title = "Teacher",
                            subtitle = "Teach, inspire and empower students",
                            icon = { Icon(Icons.Outlined.CoPresent, null, tint = Color(0xFF3BAE9C), modifier = Modifier.size(24.dp)) },
                            iconBg = Color(0xFF0F262B),
                            isSelected = role == "Teacher",
                            onClick = { role = "Teacher" }
                        )
                    }

                    RoleCard(
                        modifier = Modifier.fillMaxWidth(),
                        title = "Simple User",
                        subtitle = "Connect, chat, share posts and create groups",
                        icon = { Icon(Icons.Outlined.Person, null, tint = Color(0xFF8B6BFF), modifier = Modifier.size(24.dp)) },
                        iconBg = Color(0xFF261A4A),
                        isSelected = role == "Simple User",
                        onClick = { role = "Simple User" }
                    )
                }

                // ── ACADEMIC / UNIVERSITY SECTION (Only for Student & Teacher) ──
                if (role != "Simple User") {
                    Spacer(Modifier.height(20.dp))
                    Text(
                        text = if (role == "Teacher") "University Access Key" else "University Code (Optional)",
                        color = TextWhite,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            CustomInputField(
                                label = "",
                                value = uniKeyInput,
                                onValueChange = {
                                    uniKeyInput = it.uppercase().take(MAX_UNI_KEY_LEN)
                                    verifiedUniName = null
                                    keyValidationError = null
                                },
                                placeholder = "e.g. EDU-PK-NUST-8291",
                                icon = { Icon(Icons.Outlined.AccountBalance, null, tint = IconPurple, modifier = Modifier.size(22.dp)) }
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Button(
                            onClick = {
                                if (isValidatingKey) return@Button
                                val clean = uniKeyInput.trim().uppercase()
                                if (clean.isBlank()) return@Button
                                coroutineScope.launch {
                                    isValidatingKey = true
                                    keyValidationError = null
                                    try {
                                        val res = ApiClient.apiService.validateUniversityKey(clean)
                                        if (res.valid) {
                                            verifiedUniName = res.name
                                            keyValidationError = null
                                        } else {
                                            verifiedUniName = null
                                            keyValidationError = res.message ?: res.detail ?: "Invalid Campus Key"
                                        }
                                    } catch (e: Exception) {
                                        verifiedUniName = null
                                        keyValidationError = friendlyErrorMessage(e, "Failed to verify. Check network.")
                                    } finally {
                                        isValidatingKey = false
                                    }
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = PurpleBtn),
                            modifier = Modifier.height(52.dp),
                            enabled = uniKeyInput.isNotBlank() && !isValidatingKey
                        ) {
                            if (isValidatingKey) {
                                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                            } else {
                                Text("Verify", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    if (verifiedUniName != null) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF0C2B1B))
                                .border(1.dp, Color(0xFF1B6B42), RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.CheckCircle, null, tint = Color(0xFF30C96B), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Campus: $verifiedUniName", color = Color(0xFF30C96B), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }

                    if (keyValidationError != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(keyValidationError!!, color = Color(0xFFFF6B6B), fontSize = 12.sp)
                    }

                    Spacer(Modifier.height(14.dp))

                    // Founder Callout Card
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
                                .clip(CircleShape)
                                .background(Color(0xFF251F56)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Outlined.AccountBalance, null, tint = TitlePurple, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Are you a Campus Founder?", color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("Register your University & Curriculum here", color = TextGray, fontSize = 11.5.sp)
                        }
                        Icon(Icons.Outlined.ChevronRight, null, tint = TextGray, modifier = Modifier.size(18.dp))
                    }
                }

                Spacer(Modifier.height(28.dp))

                // Error Message
                if (message.isNotEmpty() && !isLoading) {
                    Text(message, color = Color(0xFFFF6B6B), fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                }

                // ── SIGN UP BUTTON ───────────────────────────────────────
                PrimaryButton(
                    text = "Sign Up",
                    isLoading = isLoading,
                    onClick = {
                        if (isLoading) return@PrimaryButton

                        val trimmedEmail = email.trim()
                        if (name.isBlank() || trimmedEmail.isBlank() || password.isBlank() || username.isBlank()) {
                            message = "Please fill in all fields."
                            return@PrimaryButton
                        }
                        if (!trimmedEmail.matches(EMAIL_REGEX)) {
                            message = "Please enter a valid email address."
                            return@PrimaryButton
                        }
                        if (password.length < 6) {
                            message = "Password must be at least 6 characters long."
                            return@PrimaryButton
                        }
                        if (username.length < 3) {
                            message = "Username must be at least 3 characters long."
                            return@PrimaryButton
                        }
                        // A University Code that was typed but never verified must not
                        // silently ride along to the next screen — either it gets
                        // verified, or the user clears it.
                        if (role != "Simple User" && uniKeyInput.isNotBlank() && verifiedUniName == null) {
                            message = "Please verify your University Code, or clear the field to continue without one."
                            return@PrimaryButton
                        }
                        // Teachers must always have a verified campus key — the label
                        // above the field says "required" but nothing was enforcing it.
                        if (role == "Teacher" && verifiedUniName == null) {
                            message = "University Access Key is required for Teacher accounts. Please verify it."
                            return@PrimaryButton
                        }
                        coroutineScope.launch {
                            isLoading = true; message = ""
                            try {
                                ApiClient.apiService.requestOtp(OTPRequest(trimmedEmail))
                                step = 2; message = ""
                                startResendCountdown()
                            } catch (e: Exception) {
                                message = friendlyErrorMessage(e, "Failed to send OTP. Check email.")
                            } finally { isLoading = false }
                        }
                    }
                )

                Spacer(Modifier.height(20.dp))

                // ── ALREADY HAVE ACCOUNT ─────────────────────────────────
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Text("Already have an account? ", color = TextGray, fontSize = 14.sp)
                    Text(
                        "Login", color = TitlePurple, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { navController.navigate("login") }
                    )
                }

            } else {
                // ════════════════════════════════════════════════════════════════
                // STEP 2: OTP VERIFICATION
                // ════════════════════════════════════════════════════════════════

                Text(
                    "Email Verification", color = TextWhite, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "We've sent a 6-digit OTP to\n${email.trim()}", color = TextGray, fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, lineHeight = 20.sp
                )

                Spacer(Modifier.height(30.dp))

                CustomInputField(
                    label = "OTP Code",
                    value = otpCode,
                    onValueChange = { otpCode = it.filter { c -> c.isDigit() }.take(6) },
                    placeholder = "Enter 6-digit OTP",
                    keyboardType = KeyboardType.Number,
                    icon = { Icon(Icons.Outlined.VerifiedUser, null, tint = IconPurple, modifier = Modifier.size(24.dp)) }
                )

                Spacer(Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (resendCountdown > 0) {
                        Text("Resend OTP in ${resendCountdown}s", color = TextGray, fontSize = 13.sp)
                    } else {
                        Text(
                            "Resend OTP", color = TitlePurple, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable {
                                if (isLoading) return@clickable
                                coroutineScope.launch {
                                    isLoading = true
                                    try {
                                        ApiClient.apiService.requestOtp(OTPRequest(email.trim()))
                                        message = "OTP resent!"
                                        startResendCountdown()
                                    } catch (e: Exception) {
                                        message = friendlyErrorMessage(e, "Failed to resend.")
                                    } finally { isLoading = false }
                                }
                            }
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))

                if (message.isNotEmpty() && !isLoading) {
                    Text(message, color = if (message.contains("sent")) TitlePurple else Color(0xFFFF6B6B), fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                }

                PrimaryButton(
                    text = "Verify & Create Account",
                    isLoading = isLoading,
                    onClick = {
                        if (isLoading) return@PrimaryButton
                        if (otpCode.length < 6) { message = "Enter valid OTP"; return@PrimaryButton }
                        coroutineScope.launch {
                            isLoading = true; message = ""
                            try {
                                val response = ApiClient.apiService.signup(otpCode, SignupRequest(email.trim(), username, name, password, role))
                                val sharedPrefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                                response.access_token?.let { token ->
                                    sharedPrefs.edit().putString("authToken", token).apply()
                                    com.security.myapplication.network.AuthManager.setToken(token)
                                }
                                sharedPrefs.edit().putInt("userId", response.user.id).putString("role", response.user.role).apply()

                                // Long-lived work should use the application context, not
                                // the composable's (Activity) context, so nothing leaks if
                                // the user navigates away before these callbacks return.
                                val appContext = context.applicationContext

                                com.security.myapplication.notifications.AppMessageNotificationWatcher.start(appContext, response.user.id)

                                // A newly created account does not receive onNewToken() again, so
                                // explicitly bind this phone's existing FCM token to the new user.
                                // Only force a server round-trip if the token actually differs from
                                // what's cached — avoids a redundant sync call on every signup.
                                com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                                    .addOnSuccessListener { token ->
                                        if (!token.isNullOrBlank()) {
                                            val previousToken = sharedPrefs.getString(
                                                com.security.myapplication.notifications.EduConnectFCMService.KEY_FCM_TOKEN, null
                                            )
                                            val tokenChanged = token != previousToken
                                            if (tokenChanged) {
                                                sharedPrefs.edit()
                                                    .putString(com.security.myapplication.notifications.EduConnectFCMService.KEY_FCM_TOKEN, token)
                                                    .apply()
                                            }
                                            com.security.myapplication.notifications.EduConnectFCMService
                                                .syncTokenToServer(appContext, response.user.id, token, force = tokenChanged)
                                        }
                                    }
                                    .addOnFailureListener { e ->
                                        android.util.Log.w("SignupScreen", "Failed to fetch FCM token", e)
                                    }

                                val cleanKey = uniKeyInput.trim().uppercase()
                                if (cleanKey.isNotBlank()) {
                                    sharedPrefs.edit().putString("cached_uni_key_${response.user.id}", cleanKey).apply()
                                    if (role == "Teacher") {
                                        navController.navigate("teacher-subject-picker/${response.user.id}/$cleanKey") {
                                            popUpTo("login") { inclusive = true }
                                        }
                                    } else if (role == "Student") {
                                        navController.navigate("student-enrollment/${response.user.id}/$cleanKey") {
                                            popUpTo("login") { inclusive = true }
                                        }
                                    } else {
                                        navController.navigate("dashboard/${response.user.id}/${response.user.role}") {
                                            popUpTo("login") { inclusive = true }
                                        }
                                    }
                                } else {
                                    navController.navigate("dashboard/${response.user.id}/${response.user.role}") {
                                        popUpTo("login") { inclusive = true }
                                    }
                                }
                            } catch (e: Exception) {
                                message = friendlyErrorMessage(e, "Invalid OTP. Try again.")
                            } finally { isLoading = false }
                        }
                    }
                )
            }

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
private fun RoleCard(
    modifier: Modifier,
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    iconBg: Color,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (isSelected) Color(0xFF131135) else BgField)
            .border(1.dp, if (isSelected) PurpleBtn else FieldBorder, RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(12.dp)
    ) {
        Column {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier.size(44.dp).clip(CircleShape).background(iconBg),
                    contentAlignment = Alignment.Center
                ) { icon() }

                // Radio button
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .border(2.dp, if (isSelected) PurpleBtn else TextGray, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(TextWhite))
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(title, color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = TextGray, fontSize = 11.sp, lineHeight = 16.sp)
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
