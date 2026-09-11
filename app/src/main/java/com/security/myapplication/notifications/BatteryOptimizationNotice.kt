package com.security.myapplication.notifications

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Prominent top-of-dashboard banner and interactive dialog that guides users to disable
 * aggressive battery optimization / saver restrictions (especially on Xiaomi, Vivo, Oppo,
 * Huawei, Samsung) so real-time push notifications arrive instantly without delay.
 *
 * Automatically disappears the moment battery optimization is disabled for the app, or when
 * the user dismisses it for the current session.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatteryOptimizationNotice(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Real app display name — used instead of a hardcoded literal so the guidance text always
    // matches whatever this build is actually named.
    val appName = remember {
        context.applicationInfo.loadLabel(context.packageManager).toString()
    }

    // State: true if the OS is STILL restricting / optimizing this app
    var isOptimized by remember {
        mutableStateOf(!BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context))
    }

    var showDetailsSheet by rememberSaveable { mutableStateOf(false) }
    // Lets the user close the banner for this session instead of it nagging on every screen.
    var dismissedForSession by rememberSaveable { mutableStateOf(false) }
    var launchFeedback by rememberSaveable { mutableStateOf<String?>(null) }
    fun feedbackFor(destination: BatteryOptimizationHelper.SettingsDestination) = when (destination) {
        BatteryOptimizationHelper.SettingsDestination.DIRECT_SYSTEM_DIALOG ->
            "The system confirmation is open. Select Allow to continue."
        BatteryOptimizationHelper.SettingsDestination.GENERAL_BATTERY_SETTINGS ->
            "Battery settings are open. Set $appName to Unrestricted."
        BatteryOptimizationHelper.SettingsDestination.OEM_BACKGROUND_SETTINGS ->
            "Your phone's background settings are now open."
        BatteryOptimizationHelper.SettingsDestination.APP_INFO ->
            "App info is open. In Battery, select Unrestricted."
        BatteryOptimizationHelper.SettingsDestination.UNAVAILABLE ->
            "The settings screen could not be opened. Search for Battery in your phone settings."
    }

    // Re-check whenever the app comes back to the foreground (e.g. returning from settings)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isOptimized = !BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
                if (!isOptimized) {
                    showDetailsSheet = false
                    launchFeedback = null
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // If battery optimization is already disabled (or the user dismissed it), show nothing
    AnimatedVisibility(
        visible = isOptimized && !dismissedForSession,
        enter = fadeIn(animationSpec = tween(300)) + expandVertically(),
        exit = fadeOut(animationSpec = tween(300)) + shrinkVertically(),
        modifier = modifier
    ) {
        // Pulsing glow animation
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val alphaGlow by infiniteTransition.animateFloat(
            initialValue = 0.4f,
            targetValue = 0.85f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "alphaGlow"
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color(0xFF2A153A),
                            Color(0xFF1E1438),
                            Color(0xFF2E1724)
                        )
                    )
                )
                .border(
                    1.2.dp,
                    Color(0xFFFFB300).copy(alpha = alphaGlow),
                    RoundedCornerShape(16.dp)
                )
                .clickable(onClickLabel = "Open notification setup details") {
                    showDetailsSheet = true
                }
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Left Icon with badge
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF422108))
                        .border(1.dp, Color(0xFFFFB300).copy(alpha = 0.6f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.NotificationsActive,
                        contentDescription = null,
                        tint = Color(0xFFFFB300),
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(Modifier.width(12.dp))

                // Middle Text: Problem & Reason
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Real-Time Notifications",
                            color = Color.White,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFFFFB300).copy(alpha = 0.2f))
                                .padding(horizontal = 5.dp, vertical = 1.5.dp)
                        ) {
                            Text(
                                text = "Action Required",
                                color = Color(0xFFFFB300),
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Battery restrictions can delay messages. Adjust the settings to keep alerts reliable.",
                        color = Color(0xFFC7C5DD),
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp
                    )
                }

                Spacer(Modifier.width(10.dp))

                // Action Button
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xFFFF9800), Color(0xFFFF5722))
                            )
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Fix Now",
                            color = Color.White,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.width(2.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }

            // Small dismiss control — lets the user close the banner for this session instead of
            // it re-appearing on every single screen with no way to opt out.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .clickable(onClickLabel = "Dismiss banner") {
                        dismissedForSession = true
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Dismiss",
                    tint = Color(0xFFC7C5DD),
                    modifier = Modifier.size(13.dp)
                )
            }
        }
    }

    // ── Interactive Setup Modal Sheet ─────────────────────────────────────────
    if (showDetailsSheet) {
        val brandName = remember { BatteryOptimizationHelper.getDeviceBrandName() }
        val instructions = remember { BatteryOptimizationHelper.getBrandInstructions(appName) }

        ModalBottomSheet(
            onDismissRequest = { showDetailsSheet = false },
            containerColor = Color(0xFF140F28),
            tonalElevation = 0.dp,
            dragHandle = {
                Box(
                    modifier = Modifier
                        .padding(vertical = 10.dp)
                        .width(40.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFF3B3360))
                )
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                // Header
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF382006))
                            .border(1.dp, Color(0xFFFFB300), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.BatteryAlert,
                            contentDescription = null,
                            tint = Color(0xFFFFB300),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(
                            text = "Enable Instant Notifications",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Allow reliable background delivery",
                            color = Color(0xFF8B6BFF),
                            fontSize = 12.5.sp
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Why is this needed card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF1D173A))
                        .border(1.dp, Color(0xFF2F265C), RoundedCornerShape(14.dp))
                        .padding(14.dp)
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = null,
                                tint = Color(0xFF1DE9B6),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Why is this needed?",
                                color = Color(0xFF1DE9B6),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Some phones restrict background apps to save battery. This can delay new-message notifications until the app is opened again.",
                            color = Color(0xFFD3D1EA),
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Primary Action Button: 1-Tap Disable Battery Optimization
                Button(
                    onClick = {
                        launchFeedback = feedbackFor(
                            BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(context)
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF8F00)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.PowerSettingsNew,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Open Battery Permission (Allow)",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.5.sp
                    )
                }

                launchFeedback?.let { feedback ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = feedback,
                        color = Color(0xFFC7C5DD),
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp
                    )
                }

                Spacer(Modifier.height(16.dp))

                // OEM Specific Instructions
                Text(
                    text = "Phone Brand: $brandName",
                    color = Color.White,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF181232))
                        .padding(12.dp)
                ) {
                    instructions.forEachIndexed { idx, step ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                text = "${idx + 1}.",
                                color = Color(0xFFFFB300),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(18.dp)
                            )
                            Text(
                                text = step,
                                color = Color(0xFFE2E0F5),
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Open Autostart / Settings Button
                OutlinedButton(
                    onClick = {
                        launchFeedback = feedbackFor(
                            BatteryOptimizationHelper.openAutostartOrAppSettings(context)
                        )
                    },
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF8B6BFF)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = null,
                        tint = Color(0xFF8B6BFF),
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Open Background Settings Link",
                        color = Color(0xFF8B6BFF),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }

                Spacer(Modifier.height(10.dp))

                // Re-check & Done Button
                TextButton(
                    onClick = {
                        isOptimized = !BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
                        if (!isOptimized) {
                            showDetailsSheet = false
                        }
                    },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF1DE9B6),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Check Status / Done",
                        color = Color(0xFF1DE9B6),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
