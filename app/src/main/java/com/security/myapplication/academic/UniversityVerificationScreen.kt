package com.security.myapplication.academic

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.security.myapplication.network.ApiClient
import com.security.myapplication.ui.theme.NoNativeOverscroll
import com.security.myapplication.ui.theme.pumpBounceScroll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── Design Tokens ─────────────────────────────────────────────────────────────
private val ScreenBg      = Color(0xFF0D0A22)
private val CardBg        = Color(0xFF13102C)
private val CardBorder    = Color(0xFF221D47)
private val FieldBg       = Color(0xFF181438)
private val PurpleAccent  = Color(0xFF8B6BFF)
private val PurpleLight   = Color(0xFFA78BFA)
private val PurpleDark    = Color(0xFF5B3FCC)
private val TextWhite     = Color(0xFFFFFFFF)
private val TextMuted     = Color(0xFF8B88A6)
private val GreenSuccess  = Color(0xFF30C96B)
private val OrangeWarning = Color(0xFFF97316)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UniversityVerificationScreen(
    currentUserId: Int? = null,
    onBack: () -> Unit,
    onProceedToCurriculum: (uniKey: String, uniName: String) -> Unit
) {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    // University identity is account-scoped. Never use the legacy global
    // cache here because a previous account on this device may belong to a
    // different university.
    val existingUniName = remember(currentUserId) {
        currentUserId?.let { sharedPrefs.getString("cached_uni_name_$it", null) }
    }
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var universityName by remember { mutableStateOf("") }
    var country by remember { mutableStateOf("Pakistan") }
    var isRegistering by remember { mutableStateOf(false) }
    var generatedKey by remember { mutableStateOf<String?>(null) }
    var registeredUniId by remember { mutableStateOf<Int?>(null) }
    var registeredUniName by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // OTP step: 0=hidden, 1=email-input, 2=otp-code-input, 3=submitted-pending-review
    var otpStep by remember { mutableStateOf(0) }
    var founderEmail by remember { mutableStateOf("") }
    var otpCode by remember { mutableStateOf("") }
    var isSendingOtp by remember { mutableStateOf(false) }
    var isVerifyingOtp by remember { mutableStateOf(false) }
    var otpErrorMessage by remember { mutableStateOf<String?>(null) }
    var otpSuccessMessage by remember { mutableStateOf<String?>(null) }

    val popularCountries = listOf("Pakistan", "United States", "United Kingdom", "Canada", "Australia", "India", "Germany", "Malaysia", "UAE", "Saudi Arabia")

    Box(modifier = Modifier.fillMaxSize().background(ScreenBg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
        ) {
            // Top Bar
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextWhite)
                }
                Spacer(Modifier.width(8.dp))
                Text("University Verification", color = TextWhite, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            }

            HorizontalDivider(color = CardBorder, thickness = 1.dp)

            NoNativeOverscroll {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .pumpBounceScroll()
                        .verticalScroll(scrollState)
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Hero Badge
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(Brush.radialGradient(listOf(PurpleDark, Color(0xFF1E1452))))
                            .border(2.dp, PurpleAccent.copy(alpha = 0.6f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Outlined.AccountBalance, contentDescription = null, tint = PurpleLight, modifier = Modifier.size(40.dp))
                    }

                    Spacer(Modifier.height(14.dp))
                    Text("Register Your Campus", color = TextWhite, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "As the campus founder, you will receive an official University Key to share with your teachers and students.",
                        color = TextMuted, fontSize = 13.5.sp, textAlign = TextAlign.Center, lineHeight = 19.sp
                    )
                    Spacer(Modifier.height(24.dp))

                    if (generatedKey == null) {
                        // Registration Form
                        Column(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                                .background(CardBg).border(1.dp, CardBorder, RoundedCornerShape(20.dp)).padding(20.dp)
                        ) {
                            Text("University Full Name", color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = universityName, onValueChange = { universityName = it; errorMessage = null },
                                placeholder = { Text("e.g. National University of Sciences & Technology", color = TextMuted, fontSize = 13.sp) },
                                singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = TextWhite, unfocusedTextColor = TextWhite,
                                    focusedContainerColor = FieldBg, unfocusedContainerColor = FieldBg,
                                    focusedBorderColor = PurpleAccent, unfocusedBorderColor = CardBorder
                                )
                            )
                            Spacer(Modifier.height(18.dp))
                            Text("Country", color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = country, onValueChange = { country = it; errorMessage = null },
                                placeholder = { Text("Country name", color = TextMuted, fontSize = 13.sp) },
                                singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = TextWhite, unfocusedTextColor = TextWhite,
                                    focusedContainerColor = FieldBg, unfocusedContainerColor = FieldBg,
                                    focusedBorderColor = PurpleAccent, unfocusedBorderColor = CardBorder
                                )
                            )
                            Spacer(Modifier.height(10.dp))
                            Text("Popular countries:", color = TextMuted, fontSize = 11.5.sp)
                            Spacer(Modifier.height(6.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                popularCountries.take(3).forEach { c ->
                                    Box(
                                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                            .background(if (country == c) PurpleAccent.copy(alpha = 0.25f) else FieldBg)
                                            .border(1.dp, if (country == c) PurpleAccent else CardBorder, RoundedCornerShape(8.dp))
                                            .clickable { country = c }.padding(horizontal = 10.dp, vertical = 5.dp)
                                    ) { Text(c, color = if (country == c) PurpleLight else TextMuted, fontSize = 11.5.sp) }
                                }
                            }
                            if (errorMessage != null) { Spacer(Modifier.height(14.dp)); Text(errorMessage!!, color = Color(0xFFFF5252), fontSize = 12.5.sp) }
                            Spacer(Modifier.height(24.dp))
                            Button(
                                onClick = {
                                    if (universityName.isBlank() || country.isBlank()) { errorMessage = "Please enter both university name and country."; return@Button }
                                    if (currentUserId == null || currentUserId <= 0 || com.security.myapplication.network.AuthManager.authToken.isNullOrBlank()) {
                                        errorMessage = "Please sign in first."; return@Button
                                    }
                                    isRegistering = true; errorMessage = null
                                    coroutineScope.launch {
                                        try {
                                            val response = withContext(Dispatchers.IO) {
                                                ApiClient.apiService.verifyUniversity(UniversityVerifyRequest(name = universityName.trim(), country = country.trim()))
                                            }
                                            generatedKey = response.uni_key
                                            registeredUniId = response.id
                                            registeredUniName = response.name
                                            currentUserId?.let { uid ->
                                                com.security.myapplication.storage.EduConnectStorageManager
                                                    .setCachedUniversityName(context.applicationContext, uid, response.name)
                                            }
                                            otpStep = 1
                                            Toast.makeText(context, "University registered! Now verify your email.", Toast.LENGTH_SHORT).show()
                                        } catch (e: Exception) {
                                            errorMessage = e.message ?: "Failed to register university."
                                        } finally { isRegistering = false }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = PurpleAccent), enabled = !isRegistering
                            ) {
                                if (isRegistering) CircularProgressIndicator(color = TextWhite, strokeWidth = 2.5.dp, modifier = Modifier.size(22.dp))
                                else { Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Generate University Key", fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                            }
                        }
                    } else {
                        // Key Generated Card
                        Column(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                                .background(CardBg).border(1.5.dp, GreenSuccess.copy(alpha = 0.7f), RoundedCornerShape(20.dp)).padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(modifier = Modifier.size(56.dp).clip(CircleShape).background(GreenSuccess.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = GreenSuccess, modifier = Modifier.size(32.dp))
                            }
                            Spacer(Modifier.height(14.dp))
                            Text(registeredUniName.orEmpty(), color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(4.dp))
                            Text("Official Campus Key Generated", color = TextMuted, fontSize = 12.5.sp)
                            Spacer(Modifier.height(18.dp))
                            Box(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(FieldBg)
                                    .border(1.dp, PurpleAccent.copy(alpha = 0.5f), RoundedCornerShape(14.dp)).padding(vertical = 16.dp, horizontal = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(generatedKey.orEmpty(), color = PurpleLight, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.Monospace, letterSpacing = 2.sp, textAlign = TextAlign.Center)
                            }
                            Spacer(Modifier.height(14.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        cb?.setPrimaryClip(ClipData.newPlainText("University Key", generatedKey))
                                        Toast.makeText(context, "Key copied!", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.weight(1f).height(46.dp), shape = RoundedCornerShape(12.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, PurpleAccent.copy(alpha = 0.6f)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = PurpleLight)
                                ) { Icon(Icons.Outlined.ContentCopy, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Copy Key", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                                OutlinedButton(
                                    onClick = {
                                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, "Join our campus on EduConnect!\nUniversity: $registeredUniName\nCampus Key: $generatedKey")
                                        }, "Share University Key"))
                                    },
                                    modifier = Modifier.weight(1f).height(46.dp), shape = RoundedCornerShape(12.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, GreenSuccess.copy(alpha = 0.6f)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = GreenSuccess)
                                ) { Icon(Icons.Outlined.Share, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Share Key", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        // Email OTP Verification Card (steps 0-2)
                        AnimatedVisibility(visible = otpStep < 3) {
                            Column(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                                    .background(CardBg)
                                    .border(1.dp, if (otpStep == 0) CardBorder else OrangeWarning.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                                    .padding(20.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(modifier = Modifier.size(36.dp).clip(CircleShape).background(OrangeWarning.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Outlined.Email, null, tint = OrangeWarning, modifier = Modifier.size(20.dp))
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Text("Verify Your Identity", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                        Text("Required to activate your university key", color = OrangeWarning, fontSize = 11.5.sp)
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "Enter your official university email (e.g. admin@university.edu.pk). A code will be sent to confirm you are from this institution.",
                                    color = TextMuted, fontSize = 13.sp, lineHeight = 18.sp
                                )
                                Spacer(Modifier.height(16.dp))

                                if (otpStep <= 1) {
                                    OutlinedTextField(
                                        value = founderEmail, onValueChange = { founderEmail = it; otpErrorMessage = null },
                                        placeholder = { Text("admin@youruniversity.edu.pk", color = TextMuted, fontSize = 13.sp) },
                                        singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                        leadingIcon = { Icon(Icons.Outlined.Email, null, tint = TextMuted) },
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = TextWhite, unfocusedTextColor = TextWhite,
                                            focusedContainerColor = FieldBg, unfocusedContainerColor = FieldBg,
                                            focusedBorderColor = OrangeWarning, unfocusedBorderColor = CardBorder
                                        )
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    Button(
                                        onClick = {
                                            val uniId = registeredUniId ?: run { otpErrorMessage = "University ID not found."; return@Button }
                                            if (!founderEmail.contains("@") || !founderEmail.contains(".")) { otpErrorMessage = "Enter a valid email."; return@Button }
                                            isSendingOtp = true; otpErrorMessage = null
                                            coroutineScope.launch {
                                                try {
                                                    withContext(Dispatchers.IO) {
                                                        ApiClient.apiService.founderRequestEmailOtp(FounderOTPRequest(university_id = uniId, founder_email = founderEmail.trim()))
                                                    }
                                                    otpStep = 2
                                                    Toast.makeText(context, "OTP sent to ${founderEmail.trim()}", Toast.LENGTH_LONG).show()
                                                } catch (e: Exception) { otpErrorMessage = e.message ?: "Failed to send OTP." }
                                                finally { isSendingOtp = false }
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = OrangeWarning),
                                        enabled = !isSendingOtp && founderEmail.isNotBlank()
                                    ) {
                                        if (isSendingOtp) CircularProgressIndicator(color = TextWhite, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                                        else { Icon(Icons.Default.Send, null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(8.dp)); Text("Send Verification Code", fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                                    }
                                }

                                if (otpStep == 2) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                            .background(GreenSuccess.copy(alpha = 0.1f)).padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.CheckCircle, null, tint = GreenSuccess, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Code sent to ${founderEmail.trim()}", color = GreenSuccess, fontSize = 12.5.sp)
                                    }
                                    Spacer(Modifier.height(14.dp))
                                    Text("Enter 6-digit code:", color = TextWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = otpCode,
                                        onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) { otpCode = it; otpErrorMessage = null } },
                                        placeholder = { Text("000000", color = TextMuted, fontSize = 20.sp, fontFamily = FontFamily.Monospace) },
                                        singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                        textStyle = androidx.compose.ui.text.TextStyle(
                                            fontSize = 24.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                                            color = PurpleLight, letterSpacing = 6.sp
                                        ),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = PurpleLight, unfocusedTextColor = PurpleLight,
                                            focusedContainerColor = FieldBg, unfocusedContainerColor = FieldBg,
                                            focusedBorderColor = PurpleAccent, unfocusedBorderColor = CardBorder
                                        )
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    Button(
                                        onClick = {
                                            val uniId = registeredUniId ?: run { otpErrorMessage = "University ID not found."; return@Button }
                                            if (otpCode.length != 6) { otpErrorMessage = "Enter the 6-digit code."; return@Button }
                                            isVerifyingOtp = true; otpErrorMessage = null
                                            coroutineScope.launch {
                                                try {
                                                    val resp = withContext(Dispatchers.IO) {
                                                        ApiClient.apiService.founderVerifyEmailOtp(FounderVerifyOTPRequest(university_id = uniId, otp_code = otpCode.trim()))
                                                    }
                                                    otpStep = 3; otpSuccessMessage = resp.message
                                                } catch (e: Exception) { otpErrorMessage = e.message ?: "Verification failed." }
                                                finally { isVerifyingOtp = false }
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = PurpleAccent),
                                        enabled = !isVerifyingOtp && otpCode.length == 6
                                    ) {
                                        if (isVerifyingOtp) CircularProgressIndicator(color = TextWhite, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                                        else { Icon(Icons.Default.Verified, null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(8.dp)); Text("Verify Code", fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    TextButton(onClick = { otpStep = 1; otpCode = "" }) {
                                        Text("Change email or resend code", color = TextMuted, fontSize = 12.sp)
                                    }
                                }

                                if (otpErrorMessage != null) { Spacer(Modifier.height(8.dp)); Text(otpErrorMessage!!, color = Color(0xFFFF5252), fontSize = 12.5.sp) }
                            }
                        }

                        // Pending Admin Review Card (step 3)
                        AnimatedVisibility(visible = otpStep >= 3) {
                            Column(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                                    .background(CardBg).border(1.5.dp, OrangeWarning.copy(alpha = 0.7f), RoundedCornerShape(20.dp)).padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(modifier = Modifier.size(60.dp).clip(CircleShape).background(OrangeWarning.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Outlined.HourglassTop, null, tint = OrangeWarning, modifier = Modifier.size(32.dp))
                                }
                                Spacer(Modifier.height(16.dp))
                                Text("Application Submitted!", color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Your university is now under admin review. Once approved, your University Key will be active and students/teachers can join.",
                                    color = TextMuted, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 20.sp
                                )
                                Spacer(Modifier.height(14.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Box(modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(GreenSuccess.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 5.dp)) {
                                        Text("Email Verified", color = GreenSuccess, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                    Box(modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(OrangeWarning.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 5.dp)) {
                                        Text("Admin Review Pending", color = OrangeWarning, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                                Spacer(Modifier.height(20.dp))
                                HorizontalDivider(color = CardBorder, thickness = 1.dp)
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    "While waiting, you can set up your campus curriculum. After admin approval, the key becomes distributable.",
                                    color = TextMuted, fontSize = 12.5.sp, textAlign = TextAlign.Center
                                )
                                Spacer(Modifier.height(16.dp))
                                Button(
                                    onClick = { onProceedToCurriculum(generatedKey!!, registeredUniName.orEmpty()) },
                                    modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = PurpleAccent)
                                ) { Text("Setup Campus Curriculum →", fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                            }
                        }

                        // Skip/Later option (before email verified)
                        if (otpStep < 3) {
                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider(color = CardBorder, thickness = 1.dp)
                            Spacer(Modifier.height(16.dp))
                            Text("You can skip email verification and set up curriculum first:", color = TextMuted, fontSize = 12.sp, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = { onProceedToCurriculum(generatedKey!!, registeredUniName.orEmpty()) },
                                modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, PurpleAccent.copy(alpha = 0.5f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = PurpleLight)
                            ) { Text("Setup Curriculum (verify email later)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                        }
                    }
                }
            }
        }
    }
}
