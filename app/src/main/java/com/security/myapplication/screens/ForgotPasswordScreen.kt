package com.security.myapplication.screens

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
import com.security.myapplication.models.ForgotPasswordRequest
import com.security.myapplication.models.ForgotPasswordReset
import com.security.myapplication.network.ApiClient
import com.security.myapplication.ui.theme.pumpBounceScroll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// COLOUR TOKENS — matched from SignupScreen
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
private const val MAX_PASSWORD_LEN = 128
private const val MIN_PASSWORD_LEN = 6

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ForgotPasswordScreen(navController: NavController) {
    // Step 1 = Email, Step 2 = OTP, Step 3 = New Password
    var step          by remember { mutableStateOf(1) }
    var email         by remember { mutableStateOf("") }
    var otpCode       by remember { mutableStateOf("") }
    var newPassword   by remember { mutableStateOf("") }
    var confirmPass   by remember { mutableStateOf("") }
    var showNewPass   by remember { mutableStateOf(false) }
    var showConfPass  by remember { mutableStateOf(false) }
    var isLoading     by remember { mutableStateOf(false) }
    var message       by remember { mutableStateOf("") }
    var isError       by remember { mutableStateOf(false) }

    var resendCountdown by remember { mutableStateOf(0) }
    val coroutineScope  = rememberCoroutineScope()
    val scrollState     = rememberScrollState()

    fun startCountdown() {
        coroutineScope.launch {
            resendCountdown = 60
            while (resendCountdown > 0) {
                delay(1000)
                resendCountdown--
            }
        }
    }

    fun sendOtp() {
        if (isLoading) return

        val trimmedEmail = email.trim()
        if (!trimmedEmail.matches(EMAIL_REGEX)) {
            isError = true
            message = "Please enter a valid email address."
            return
        }

        coroutineScope.launch {
            isLoading = true; isError = false; message = ""
            try {
                ApiClient.apiService.forgotPasswordRequestOtp(ForgotPasswordRequest(trimmedEmail))
                isError = false
                message = "If an account exists for this email, an OTP has been sent."
                step = 2
                startCountdown()
            } catch (e: retrofit2.HttpException) {
                if (e.code() in 400..499) {
                    // Never reveal whether an email is registered — a 4xx here
                    // (e.g. "not found") is treated the same as success so the
                    // response can't be used to enumerate real accounts.
                    isError = false
                    message = "If an account exists for this email, an OTP has been sent."
                    step = 2
                    startCountdown()
                } else {
                    isError = true
                    message = friendlyErrorMessage(e, "Server error. Please try again in a moment.")
                }
            } catch (e: Exception) {
                isError = true
                message = friendlyErrorMessage(e, "Something went wrong. Please try again.")
            } finally { isLoading = false }
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
        Column(modifier = Modifier.align(Alignment.TopStart).padding(top = 290.dp, start = 4.dp).alpha(0.16f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(5) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(4) { Box(Modifier.size(3.dp).clip(CircleShape).background(Color(0xFF5055BB))) }
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
                        if(step > 1) step -= 1 else navController.popBackStack()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextWhite, modifier = Modifier.size(20.dp))
            }

            Spacer(Modifier.height(20.dp))

            // ── HEADER ─────────────────────────────────────────────────
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
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
                        withStyle(SpanStyle(color = TextWhite, fontSize = 28.sp, fontWeight = FontWeight.Bold)) {
                            append(when(step) {
                                1 -> "Forgot "
                                2 -> "Verify "
                                else -> "New "
                            })
                        }
                        withStyle(SpanStyle(color = TitlePurple, fontSize = 28.sp, fontWeight = FontWeight.Bold)) {
                            append(when(step) {
                                1 -> "Password?"
                                2 -> "Email"
                                else -> "Password"
                            })
                        }
                    }
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = when (step) {
                        1 -> "Enter your registered email\nto receive an OTP"
                        2 -> "We've sent a 6-digit OTP to\n${email.trim()}"
                        else -> "Create a strong new password\nfor your account"
                    },
                    color = TextGray, fontSize = 14.sp,
                    textAlign = TextAlign.Center, lineHeight = 20.sp
                )
            }

            Spacer(Modifier.height(16.dp))

            // ─────────────────────────────────────────────────────────────────
            // STEP 1 – Email
            // ─────────────────────────────────────────────────────────────────
            if (step == 1) {
                CustomInputField(
                    label = "Email",
                    value = email,
                    onValueChange = { email = it.take(MAX_EMAIL_LEN) },
                    placeholder = "Enter your Email address",
                    keyboardType = KeyboardType.Email,
                    icon = { Icon(Icons.Outlined.Email, null, tint = IconPurple, modifier = Modifier.size(24.dp)) }
                )

                if (message.isNotEmpty() && !isLoading) {
                    Spacer(Modifier.height(12.dp))
                    Text(message, color = if(isError) Color(0xFFFF6B6B) else TitlePurple, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }

                Spacer(Modifier.height(24.dp))

                PrimaryButton(
                    text = "Send OTP",
                    isLoading = isLoading,
                    onClick = { sendOtp() }
                )
            }

            // ─────────────────────────────────────────────────────────────────
            // STEP 2 – OTP
            // ─────────────────────────────────────────────────────────────────
            if (step == 2) {
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
                            modifier = Modifier.clickable { sendOtp() }
                        )
                    }
                }

                if (message.isNotEmpty() && !isLoading) {
                    Spacer(Modifier.height(12.dp))
                    Text(message, color = if(isError) Color(0xFFFF6B6B) else TitlePurple, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }

                Spacer(Modifier.height(24.dp))

                PrimaryButton(
                    text = "Verify OTP",
                    isLoading = isLoading,
                    onClick = {
                        if (isLoading) return@PrimaryButton
                        if (otpCode.length != 6) {
                            isError = true
                            message = "Please enter the 6-digit OTP."
                            return@PrimaryButton
                        }
                        // Nothing to await here — the OTP itself is verified
                        // server-side together with the password reset call,
                        // so there is no need to wrap this in a coroutine or
                        // flip isLoading for a purely local step change.
                        isError = false
                        message = ""
                        step = 3
                    }
                )
            }

            // ─────────────────────────────────────────────────────────────────
            // STEP 3 – New Password
            // ─────────────────────────────────────────────────────────────────
            if (step == 3) {
                CustomInputField(
                    label = "New Password",
                    value = newPassword,
                    onValueChange = { newPassword = it.take(MAX_PASSWORD_LEN) },
                    placeholder = "Create a new password",
                    isPassword = true,
                    showPassword = showNewPass,
                    onTogglePassword = { showNewPass = !showNewPass },
                    keyboardType = KeyboardType.Password,
                    icon = { Icon(Icons.Outlined.Lock, null, tint = IconPurple, modifier = Modifier.size(24.dp)) }
                )
                Spacer(modifier = Modifier.height(14.dp))
                CustomInputField(
                    label = "Confirm Password",
                    value = confirmPass,
                    onValueChange = { confirmPass = it.take(MAX_PASSWORD_LEN) },
                    placeholder = "Confirm new password",
                    isPassword = true,
                    showPassword = showConfPass,
                    onTogglePassword = { showConfPass = !showConfPass },
                    keyboardType = KeyboardType.Password,
                    icon = { Icon(Icons.Outlined.Lock, null, tint = IconPurple, modifier = Modifier.size(24.dp)) }
                )

                if (newPassword.isNotBlank() && confirmPass.isNotBlank() && newPassword != confirmPass) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Passwords do not match", color = Color(0xFFFF6B6B), fontSize = 12.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                }

                if (message.isNotEmpty() && !isLoading) {
                    Spacer(Modifier.height(12.dp))
                    Text(message, color = if(isError) Color(0xFFFF6B6B) else TitlePurple, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }

                Spacer(Modifier.height(24.dp))

                PrimaryButton(
                    text = "Reset Password",
                    isLoading = isLoading,
                    onClick = {
                        if (isLoading) return@PrimaryButton
                        if (newPassword.length < MIN_PASSWORD_LEN) {
                            isError = true; message = "Password must be at least $MIN_PASSWORD_LEN characters long."
                            return@PrimaryButton
                        }
                        if (newPassword != confirmPass) {
                            isError = true; message = "Passwords do not match."
                            return@PrimaryButton
                        }

                        coroutineScope.launch {
                            isLoading = true; isError = false; message = ""
                            try {
                                ApiClient.apiService.forgotPasswordReset(
                                    ForgotPasswordReset(email = email.trim(), otp_code = otpCode, new_password = newPassword)
                                )
                                isError = false
                                message = "✅ Password reset successfully!"
                                delay(1500)
                                navController.navigate("login") { popUpTo(0) }
                            } catch (e: retrofit2.HttpException) {
                                isError = true
                                message = friendlyErrorMessage(e, "Invalid or expired OTP. Please try again.")
                                // Only bounce back to the OTP step for an actual
                                // rejection from the server (bad/expired code) —
                                // not for a network error, which has nothing to
                                // do with whether the OTP was correct.
                                step = 2
                            } catch (e: Exception) {
                                isError = true
                                message = friendlyErrorMessage(e, "Something went wrong. Please try again.")
                            } finally { isLoading = false }
                        }
                    }
                )
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
