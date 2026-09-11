package com.security.myapplication.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.security.myapplication.models.PersonalChatUser
import com.security.myapplication.models.PersonalBlockStatus
import com.security.myapplication.models.User
import com.security.myapplication.network.ApiClient
import com.security.myapplication.ui.theme.NoNativeOverscroll
import com.security.myapplication.ui.theme.pumpBounceScroll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── Design Tokens (exact match with GroupInfoScreen & EDU Connect theme) ──────
private val BgDark          = Color(0xFF0D0A22)
private val CardBg          = Color(0xFF13102C)
private val CardBorder      = Color(0xFF221D47)
private val CardDivider     = Color(0xFF1E1A3D)
private val PurpleAccent    = Color(0xFF8B6BFF)
private val PurpleDark      = Color(0xFF5B3FCC)
private val PurpleLight     = Color(0xFF9D80FF)
private val SubText         = Color(0xFF8B88A6)
private val TextMuted       = Color(0xFF9E9DB5)
private val OnlineGreen     = Color(0xFF30C96B)
private val RedDanger       = Color(0xFFFF4D6D)
private val ActionCircleBg  = Color(0xFF1B163B)
private val ActionCircleBdr = Color(0xFF2B2459)

private const val AVATAR_DECODE_SIZE_PX = 272

/** Decodes only enough pixels for the on-screen avatar, avoiding large bitmap allocations. */
private fun decodeAvatarBitmap(base64: String?): Bitmap? {
    if (base64.isNullOrBlank()) return null
    return try {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (bounds.outWidth / sampleSize > AVATAR_DECODE_SIZE_PX * 2 ||
            bounds.outHeight / sampleSize > AVATAR_DECODE_SIZE_PX * 2
        ) {
            sampleSize *= 2
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    } catch (_: Exception) {
        null
    }
}

@Composable
fun PersonalUserInfoScreen(
    currentUserId: Int,
    otherUser: PersonalChatUser,
    onBack: () -> Unit,
    onImageClick: (Bitmap) -> Unit = {},
    onBlockStatusChanged: (PersonalBlockStatus) -> Unit = {}
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // Fetch full live user details asynchronously (for email, degree, major, updated DP)
    var liveUser by remember(otherUser.id) { mutableStateOf<User?>(null) }
    var isLoadingUser by remember(otherUser.id) { mutableStateOf(true) }
    var errorMessage by remember(otherUser.id) { mutableStateOf<String?>(null) }
    var reloadAttempt by remember(otherUser.id) { mutableStateOf(0) }
    var blockStatus by remember(currentUserId, otherUser.id) { mutableStateOf<PersonalBlockStatus?>(null) }
    var isChangingBlock by remember { mutableStateOf(false) }
    var showBlockConfirmation by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(otherUser.id, reloadAttempt) {
        isLoadingUser = true
        errorMessage = null
        try {
            val fetched = withContext(Dispatchers.IO) {
                ApiClient.apiService.getUser(otherUser.id)
            }
            liveUser = fetched
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            errorMessage = "Unable to load user info"
        } finally {
            isLoadingUser = false
        }
    }

    LaunchedEffect(currentUserId, otherUser.id) {
        try {
            blockStatus = withContext(Dispatchers.IO) {
                ApiClient.apiService.getPersonalBlockStatus(currentUserId, otherUser.id)
            }
        } catch (_: Exception) {
            // Server-side checks still prevent messages if this status lookup fails.
        }
    }

    BackHandler(enabled = true) {
        onBack()
    }

    // Resolve DP bitmap using central LRU MediaBitmapCache to prevent memory leaks
    val rawBase64 = liveUser?.profile_pic?.takeIf { it.isNotBlank() } ?: otherUser.profile_pic
    val avatarBitmap by produceState<Bitmap?>(initialValue = null, rawBase64) {
        if (rawBase64.isNullOrBlank()) {
            value = null
            return@produceState
        }
        val cacheKey = "avatar_" + rawBase64.hashCode()
        val cached = MediaBitmapCache.get(cacheKey)
        if (cached != null) {
            value = cached
            return@produceState
        }
        value = withContext(Dispatchers.Default) {
            decodeAvatarBitmap(rawBase64)?.also {
                MediaBitmapCache.put(cacheKey, it)
            }
        }
    }

    val displayName = liveUser?.name?.takeIf { it.isNotBlank() } ?: otherUser.name
    val displayUsername = (liveUser?.username ?: otherUser.username)?.removePrefix("@")
    val displayRole = (liveUser?.role ?: otherUser.role).replaceFirstChar { it.uppercase() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // ── Top Bar ──────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(Modifier.width(8.dp))

                Text(
                    text = "Contact Info",
                    color = Color.White,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(Modifier.weight(1f))

                IconButton(
                    onClick = {
                        if (!displayUsername.isNullOrBlank()) {
                            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cb?.setPrimaryClip(ClipData.newPlainText("Username", "@$displayUsername"))
                            Toast.makeText(context, "Username @$displayUsername copied", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Copy Username",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // ── Scrollable Body ──────────────────────────────────────────────
            NoNativeOverscroll {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .pumpBounceScroll()
                        .verticalScroll(scrollState)
                        .padding(horizontal = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                Spacer(Modifier.height(14.dp))

                when {
                    isLoadingUser -> LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        color = PurpleAccent,
                        trackColor = CardDivider
                    )
                    errorMessage != null -> Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = CardBg,
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.CloudOff, null, tint = SubText, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(errorMessage.orEmpty(), color = TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { reloadAttempt++ }) { Text("Retry") }
                        }
                    }
                }

                if (isLoadingUser || errorMessage != null) Spacer(Modifier.height(12.dp))

                // ── 1. Circular Avatar (Matching GroupInfoScreen 1:1) ──────────
                Box(
                    modifier = Modifier.size(136.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Outer glowing aura ring
                    Box(
                        modifier = Modifier
                            .size(136.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    listOf(
                                        PurpleAccent.copy(alpha = 0.35f),
                                        PurpleDark.copy(alpha = 0.12f),
                                        Color.Transparent
                                    )
                                )
                            )
                    )

                    // Inner Avatar Container
                    Box(
                        modifier = Modifier
                            .size(116.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    listOf(Color(0xFF4328A6), Color(0xFF1E1452))
                                )
                            )
                            .border(2.dp, PurpleAccent.copy(alpha = 0.7f), CircleShape)
                            .clickable {
                                avatarBitmap?.let { onImageClick(it) }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        val currentAvatar = avatarBitmap
                        if (currentAvatar != null) {
                            Image(
                                bitmap = currentAvatar.asImageBitmap(),
                                contentDescription = displayName,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            val initial = displayName.trim().take(1).uppercase()
                            Text(
                                text = initial.ifBlank { "U" },
                                color = Color.White,
                                fontSize = 44.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Online indicator badge
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .align(Alignment.BottomEnd)
                            .offset(x = (-8).dp, y = (-8).dp)
                            .clip(CircleShape)
                            .background(OnlineGreen)
                            .border(2.5.dp, BgDark, CircleShape)
                    )
                }

                Spacer(Modifier.height(16.dp))

                // ── 2. User Name ─────────────────────────────────────────────
                Text(
                    text = displayName,
                    color = Color.White,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(4.dp))

                // ── 3. Username ──────────────────────────────────────────────
                if (!displayUsername.isNullOrBlank()) {
                    Text(
                        text = "@$displayUsername",
                        color = PurpleLight,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(10.dp))
                } else {
                    Spacer(Modifier.height(6.dp))
                }

                // ── 4. Role Badge (Pill with icon & gradient) ────────────────
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF2A1A5E), Color(0xFF1C1240))
                            )
                        )
                        .border(
                            1.dp,
                            Brush.linearGradient(
                                listOf(PurpleLight.copy(alpha = 0.8f), PurpleDark.copy(alpha = 0.4f))
                            ),
                            RoundedCornerShape(50.dp)
                        )
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val roleIcon = when {
                            displayRole.equals("teacher", true) || displayRole.equals("faculty", true) -> Icons.Outlined.School
                            displayRole.equals("admin", true) -> Icons.Outlined.Shield
                            else -> Icons.Outlined.Person
                        }
                        Icon(
                            imageVector = roleIcon,
                            contentDescription = null,
                            tint = PurpleAccent,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = displayRole,
                            color = Color(0xFFBDA5FF),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                // ── 5. Quick Actions Row (WhatsApp style) ────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ContactActionButton(
                        icon = Icons.Outlined.Chat,
                        label = "Message",
                        enabled = blockStatus?.is_blocked != true,
                        onClick = { onBack() }
                    )

                    ContactActionButton(
                        icon = Icons.Outlined.Call,
                        label = "Audio",
                        onClick = {
                            Toast.makeText(context, "Calling $displayName...", Toast.LENGTH_SHORT).show()
                        }
                    )

                    ContactActionButton(
                        icon = Icons.Outlined.Videocam,
                        label = "Video",
                        onClick = {
                            Toast.makeText(context, "Video call coming soon", Toast.LENGTH_SHORT).show()
                        }
                    )

                    ContactActionButton(
                        icon = Icons.Outlined.Share,
                        label = "Share",
                        onClick = {
                            if (!displayUsername.isNullOrBlank()) {
                                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                cb?.setPrimaryClip(ClipData.newPlainText("Contact", "$displayName (@$displayUsername)"))
                                Toast.makeText(context, "Contact info copied", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Shared $displayName", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }

                Spacer(Modifier.height(22.dp))

                // ── 6. Details Card (Matching GroupInfoScreen) ───────────────
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "About & Details",
                        color = PurpleAccent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(CardBg)
                            .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            // Username Row
                            ContactInfoRow(
                                icon = Icons.Outlined.AlternateEmail,
                                title = "Username",
                                subtitle = if (!displayUsername.isNullOrBlank()) "@$displayUsername" else "Not set",
                                onClick = {
                                    if (!displayUsername.isNullOrBlank()) {
                                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        cb?.setPrimaryClip(ClipData.newPlainText("Username", "@$displayUsername"))
                                        Toast.makeText(context, "Username copied", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )

                            HorizontalDivider(color = CardDivider, thickness = 0.8.dp, modifier = Modifier.padding(start = 56.dp))

                            // Role Row
                            ContactInfoRow(
                                icon = if (displayRole.contains("teacher", true) || displayRole.contains("faculty", true)) Icons.Outlined.School else Icons.Outlined.Person,
                                title = "Role",
                                subtitle = displayRole
                            )

                            // Email Row (if available)
                            if (!liveUser?.email.isNullOrBlank()) {
                                HorizontalDivider(color = CardDivider, thickness = 0.8.dp, modifier = Modifier.padding(start = 56.dp))
                                ContactInfoRow(
                                    icon = Icons.Outlined.Mail,
                                    title = "Email",
                                    subtitle = liveUser!!.email,
                                    onClick = {
                                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        cb?.setPrimaryClip(ClipData.newPlainText("Email", liveUser!!.email))
                                        Toast.makeText(context, "Email copied", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }

                            // Degree / Major Row (if available)
                            val academic = listOfNotNull(liveUser?.degree, liveUser?.major, liveUser?.academic_year)
                                .filter { it.isNotBlank() }
                                .joinToString(" • ")

                            if (academic.isNotBlank()) {
                                HorizontalDivider(color = CardDivider, thickness = 0.8.dp, modifier = Modifier.padding(start = 56.dp))
                                ContactInfoRow(
                                    icon = Icons.Outlined.School,
                                    title = "Academic Program",
                                    subtitle = academic
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))

                // ── 7. Privacy & Encryption Card ─────────────────────────────
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Security & Options",
                        color = PurpleAccent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(CardBg)
                            .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            ContactInfoRow(
                                icon = Icons.Outlined.Lock,
                                title = "End-to-end encryption",
                                subtitle = "Messages and calls are secured with encryption"
                            )

                            HorizontalDivider(color = CardDivider, thickness = 0.8.dp, modifier = Modifier.padding(start = 56.dp))

                            ContactInfoRow(
                                icon = Icons.Outlined.Notifications,
                                title = "Notifications",
                                subtitle = "Default tone & alerts"
                            )
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))

                // ── 8. Actions Card (Danger Zone) ────────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF1A0E1F))
                        .border(1.dp, Color(0xFF381525), RoundedCornerShape(16.dp))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isChangingBlock) {
                                if (blockStatus?.is_blocked_by_me == true) {
                                    isChangingBlock = true
                                    coroutineScope.launch {
                                        try {
                                            val updated = withContext(Dispatchers.IO) {
                                                ApiClient.apiService.unblockPersonalUser(currentUserId, otherUser.id)
                                            }
                                            blockStatus = updated
                                            onBlockStatusChanged(updated)
                                            Toast.makeText(context, "$displayName unblocked", Toast.LENGTH_SHORT).show()
                                        } catch (_: Exception) {
                                            Toast.makeText(context, "Could not unblock contact. Please try again.", Toast.LENGTH_SHORT).show()
                                        } finally {
                                            isChangingBlock = false
                                        }
                                    }
                                } else {
                                    showBlockConfirmation = true
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (blockStatus?.is_blocked_by_me == true) Icons.Outlined.LockOpen else Icons.Outlined.Block,
                            contentDescription = if (blockStatus?.is_blocked_by_me == true) "Unblock" else "Block",
                            tint = RedDanger,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(14.dp))
                        Text(
                            text = if (blockStatus?.is_blocked_by_me == true) "Unblock $displayName" else "Block $displayName",
                            color = RedDanger,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(Modifier.height(36.dp))
                }
            }
        }
    }

    if (showBlockConfirmation) {
        AlertDialog(
            onDismissRequest = { showBlockConfirmation = false },
            title = { Text("Block $displayName?") },
            text = { Text("You and $displayName will no longer be able to send each other personal messages.") },
            dismissButton = { TextButton(onClick = { showBlockConfirmation = false }) { Text("Cancel") } },
            confirmButton = {
                TextButton(onClick = {
                    showBlockConfirmation = false
                    isChangingBlock = true
                    coroutineScope.launch {
                        try {
                            val updated = withContext(Dispatchers.IO) {
                                ApiClient.apiService.blockPersonalUser(currentUserId, otherUser.id)
                            }
                            blockStatus = updated
                            onBlockStatusChanged(updated)
                            Toast.makeText(context, "$displayName blocked", Toast.LENGTH_SHORT).show()
                        } catch (_: Exception) {
                            Toast.makeText(context, "Could not block contact. Please try again.", Toast.LENGTH_SHORT).show()
                        } finally {
                            isChangingBlock = false
                        }
                    }
                }) { Text("Block", color = RedDanger) }
            }
        )
    }
}

// ── Helper Composable: Contact Action Circular Button ─────────────────────────
@Composable
private fun ContactActionButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(ActionCircleBg)
                .border(1.dp, ActionCircleBdr, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
            tint = if (enabled) PurpleLight else TextMuted,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            color = Color.White.copy(alpha = if (enabled) 0.88f else 0.45f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

// ── Helper Composable: Contact Info Row Item ──────────────────────────────────
@Composable
private fun ContactInfoRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(ActionCircleBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = PurpleAccent,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = SubText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = Color.White,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (onClick != null) {
            Icon(
                imageVector = Icons.Outlined.ContentCopy,
                contentDescription = "Copy",
                tint = TextMuted,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
