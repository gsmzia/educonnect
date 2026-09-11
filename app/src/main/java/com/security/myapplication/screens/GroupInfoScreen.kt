package com.security.myapplication.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.filled.*
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.security.myapplication.models.*
import com.security.myapplication.network.ApiClient
import com.security.myapplication.ui.theme.NoNativeOverscroll
import com.security.myapplication.ui.theme.pumpBounceScroll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

// ── Design Tokens ─────────────────────────────────────────────────────────────
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
private val RedCardBg       = Color(0xFF24101A)
private val RedCardBorder   = Color(0xFF4A1A28)
private val ActionCircleBg  = Color(0xFF1B163B)
private val ActionCircleBdr = Color(0xFF2B2459)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupInfoScreen(
    groupId: Int,
    groupName: String,
    currentUserId: Int,
    onBack: () -> Unit,
    onOpenDirectChat: ((PersonalChatUser) -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // ── States ───────────────────────────────────────────────────────────────
    var groupDetails by remember { mutableStateOf<GroupDetailsResponse?>(null) }
    var allMembers by remember { mutableStateOf<List<User>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }

    var isExpandedMembers by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    var showLeaveDialog by remember { mutableStateOf(false) }
    var isLeaving by remember { mutableStateOf(false) }

    var showAddMembersSheet by remember { mutableStateOf(false) }
    var isUpdatingAvatar by remember { mutableStateOf(false) }

    BackHandler { onBack() }

    // ── Data Fetcher ──────────────────────────────────────────────────────────
    fun loadGroupData() {
        isLoading = true
        loadFailed = false
        coroutineScope.launch {
            try {
                val detailsDeferred = withContext(Dispatchers.IO) {
                    ApiClient.apiService.getGroupDetails(groupId)
                }
                val membersDeferred = withContext(Dispatchers.IO) {
                    ApiClient.apiService.getGroupMembers(groupId)
                }
                groupDetails = detailsDeferred
                // The server returns every group member once; retain a defensive
                // UI guard for old deployments or duplicate legacy links.
                allMembers = membersDeferred.distinctBy { it.id }
                isLoading = false
            } catch (_: Exception) {
                loadFailed = true
                isLoading = false
            }
        }
    }

    LaunchedEffect(groupId) {
        loadGroupData()
    }

    // ── Group DP Decoder ──────────────────────────────────────────────────────
    val rawGroupDp = groupDetails?.profile_pic
    val groupAvatarBitmap by produceState<Bitmap?>(null, rawGroupDp) {
        value = withContext(Dispatchers.Default) {
            if (!rawGroupDp.isNullOrBlank()) {
                val cacheKey = "group_avatar_" + rawGroupDp.hashCode()
                MediaBitmapCache.get(cacheKey) ?: run {
                    try {
                        val bytes = Base64.decode(rawGroupDp, Base64.DEFAULT)
                        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (decoded != null) MediaBitmapCache.put(cacheKey, decoded)
                        decoded
                    } catch (_: Exception) { null }
                }
            } else null
        }
    }

    // ── Update Group DP ───────────────────────────────────────────────────────
    fun updateAvatarUri(uri: Uri) {
        coroutineScope.launch {
            isUpdatingAvatar = true
            try {
                val prepared = withContext(Dispatchers.Default) {
                    // Avatar output is 512px; decoding a 48MP source at full
                    // resolution first needlessly allocates ~190MB.
                    val orig = MediaManager.decodeThumbnailBitmap(context, uri, 768, 768)
                    if (orig == null) null else {
                        val size = minOf(orig.width, orig.height)
                        val x = (orig.width - size) / 2
                        val y = (orig.height - size) / 2
                        val cropped = Bitmap.createBitmap(orig, x, y, size, size)
                        if (cropped !== orig) orig.recycle()
                        val scaled = Bitmap.createScaledBitmap(cropped, 512, 512, true)
                        if (scaled !== cropped) cropped.recycle()
                        val out = ByteArrayOutputStream()
                        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
                        val encoded = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                        scaled.recycle()
                        encoded
                    }
                }
                if (prepared != null) {
                    val b64 = prepared
                    withContext(Dispatchers.IO) {
                        ApiClient.apiService.updateGroupProfile(groupId, GroupProfileUpdateRequest(profile_pic = b64))
                    }
                    groupDetails = groupDetails?.copy(profile_pic = b64)
                    Toast.makeText(context, "Group icon updated", Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                Toast.makeText(context, "Failed to update group icon", Toast.LENGTH_SHORT).show()
            } finally {
                isUpdatingAvatar = false
            }
        }
    }

    val avatarGalleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { updateAvatarUri(it) }
    }

    // ── Leave Group ───────────────────────────────────────────────────────────
    fun handleLeaveGroup() {
        isLeaving = true
        coroutineScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ApiClient.apiService.leaveGroup(groupId, LeaveGroupRequest(user_id = currentUserId))
                }
                Toast.makeText(context, "You left the group", Toast.LENGTH_SHORT).show()
                onBack()
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: "Failed to leave group", Toast.LENGTH_SHORT).show()
                isLeaving = false
                showLeaveDialog = false
            }
        }
    }

    // Creator / Admin check
    val isAdmin = (groupDetails?.teacher_id == currentUserId) || allMembers.firstOrNull()?.id == currentUserId

    // Deduplicate members so self (and any duplicate ID/username) strictly appears ONCE, with current user (You) at top
    val sortedUniqueMembers = remember(allMembers, currentUserId, groupDetails) {
        val uniqueById = allMembers.distinctBy { it.id }
        val (selfList, others) = uniqueById.partition { it.id == currentUserId }
        val selfItem = selfList.firstOrNull()
        val otherDeduplicated = if (selfItem != null) {
            others.filter { it.id != selfItem.id && (it.username.isNullOrBlank() || !it.username.equals(selfItem.username, ignoreCase = true)) }
        } else {
            others
        }
        val sortedOthers = otherDeduplicated.sortedWith(
            compareByDescending<User> { it.id == groupDetails?.teacher_id }
                .thenBy { it.name.lowercase() }
        )
        if (selfItem != null) listOf(selfItem) + sortedOthers else sortedOthers
    }

    // Filter members by search
    val filteredMembers = remember(sortedUniqueMembers, searchQuery) {
        if (searchQuery.isBlank()) sortedUniqueMembers
        else sortedUniqueMembers.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
            (it.username?.contains(searchQuery, ignoreCase = true) == true)
        }
    }

    val displayMembers = remember(isExpandedMembers, filteredMembers) {
        if (isExpandedMembers || filteredMembers.size <= 8) filteredMembers else filteredMembers.take(8)
    }
    var memberToRemove by remember { mutableStateOf<User?>(null) }
    var isRemovingMember by remember { mutableStateOf(false) }

    // ── Main UI Layout ────────────────────────────────────────────────────────
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
            // ── Top App Bar ───────────────────────────────────────────────────
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
                        tint = Color.White
                    )
                }

                Spacer(Modifier.width(8.dp))

                Text(
                    text = "Group Info",
                    color = Color.White,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(Modifier.weight(1f))

                IconButton(
                    onClick = {
                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cb?.setPrimaryClip(ClipData.newPlainText("Group Name", groupName))
                        Toast.makeText(context, "Group name copied", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Copy info",
                        tint = TextMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            HorizontalDivider(color = CardDivider, thickness = 0.8.dp)

            if (isLoading) {
                LoadingBlock()
            } else if (loadFailed) {
                ErrorBlock(onRetry = { loadGroupData() })
            } else {
                NoNativeOverscroll {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().pumpBounceScroll(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // ── 1. Hero Group Header Card ─────────────────────────────
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(CardBg)
                                    .border(1.dp, CardBorder, RoundedCornerShape(20.dp))
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                // Circular Group DP with Edit Badge
                                Box(
                                    modifier = Modifier
                                        .size(100.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF1E1940))
                                    .border(2.5.dp, PurpleAccent.copy(alpha = 0.6f), CircleShape)
                                    .clickable { avatarGalleryLauncher.launch("image/*") },
                                contentAlignment = Alignment.Center
                            ) {
                                if (isUpdatingAvatar) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(32.dp),
                                        color = PurpleAccent,
                                        strokeWidth = 3.dp
                                    )
                                } else {
                                    val avatar = groupAvatarBitmap
                                    if (avatar != null) {
                                    Image(
                                        bitmap = avatar.asImageBitmap(),
                                        contentDescription = groupName,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.Groups,
                                            contentDescription = null,
                                            tint = PurpleAccent,
                                            modifier = Modifier.size(50.dp)
                                        )
                                    }
                                }

                                // Mini Camera Edit Badge
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(PurpleAccent)
                                        .border(2.dp, CardBg, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CameraAlt,
                                        contentDescription = "Edit Icon",
                                        tint = Color.White,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }

                            Spacer(Modifier.height(14.dp))

                            // Group Name
                            Text(
                                text = groupDetails?.name ?: groupName,
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )

                            Spacer(Modifier.height(4.dp))

                            // Subtitle (Degree/Major or Custom Group Info)
                            val subInfo = buildString {
                                val deg = groupDetails?.degree?.takeIf { it.isNotBlank() }
                                val maj = groupDetails?.major?.takeIf { it.isNotBlank() }
                                val yr = groupDetails?.academic_year?.takeIf { it.isNotBlank() }
                                if (deg != null || maj != null) {
                                    append(listOfNotNull(deg, maj, yr).joinToString(" • "))
                                } else {
                                    append("Group • ${sortedUniqueMembers.size} participants")
                                }
                            }
                            Text(
                                text = subInfo,
                                color = SubText,
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center
                            )

                            Spacer(Modifier.height(18.dp))

                            // Action Quick Buttons Row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                if (isAdmin) {
                                    QuickActionItem(
                                        icon = Icons.Default.PersonAdd,
                                        label = "Add Member",
                                        onClick = { showAddMembersSheet = true }
                                    )
                                }
                                QuickActionItem(
                                    icon = Icons.Outlined.Search,
                                    label = "Search",
                                    onClick = { isExpandedMembers = true }
                                )
                                QuickActionItem(
                                    icon = Icons.Outlined.Share,
                                    label = "Share",
                                    onClick = {
                                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        cb?.setPrimaryClip(ClipData.newPlainText("Group", "${groupDetails?.name ?: groupName} on EDU Connect"))
                                        Toast.makeText(context, "Group info copied", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        }
                    }

                    // ── 2. Members Section Card ───────────────────────────────
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .background(CardBg)
                                .border(1.dp, CardBorder, RoundedCornerShape(18.dp))
                                .padding(16.dp)
                        ) {
                            // Section Header
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${sortedUniqueMembers.size} Participants",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold
                                )

                                if (isAdmin) {
                                    Text(
                                        text = "+ Add",
                                        color = PurpleAccent,
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable { showAddMembersSheet = true }
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            // Search bar if members > 5
                            if (sortedUniqueMembers.size > 5) {
                                Spacer(Modifier.height(12.dp))
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    placeholder = { Text("Filter members…", color = TextMuted, fontSize = 12.5.sp) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Search,
                                            contentDescription = null,
                                            tint = TextMuted,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedBorderColor = PurpleAccent,
                                        unfocusedBorderColor = CardBorder,
                                        focusedContainerColor = Color(0xFF191436),
                                        unfocusedContainerColor = Color(0xFF191436)
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            Spacer(Modifier.height(12.dp))

                            // Members Rows
                            if (displayMembers.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("No participants found", color = TextMuted, fontSize = 13.sp)
                                }
                            } else {
                                displayMembers.forEach { member ->
                                    val isMemberAdmin = (groupDetails?.teacher_id == member.id)
                                    val isSelf = (member.id == currentUserId)

                                    GroupUserRowItem(
                                        user = member,
                                        isAdmin = isMemberAdmin,
                                        isSelf = isSelf,
                                        canRemove = isAdmin && !isSelf,
                                        onRemoveClick = { memberToRemove = member },
                                        onMessageClick = {
                                            onOpenDirectChat?.invoke(
                                                PersonalChatUser(
                                                    id = member.id,
                                                    name = member.name,
                                                    username = member.username,
                                                    role = member.role,
                                                    profile_pic = member.profile_pic
                                                )
                                            )
                                        }
                                    )
                                    Spacer(Modifier.height(8.dp))
                                }

                                // See all / Show fewer toggle
                                if (filteredMembers.size > 8) {
                                    Spacer(Modifier.height(4.dp))
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { isExpandedMembers = !isExpandedMembers }
                                            .padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = if (isExpandedMembers) "Show fewer members" else "View all ${filteredMembers.size} members",
                                            color = PurpleAccent,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // ── 3. Danger Zone: Leave Group ───────────────────────────
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(RedCardBg)
                                .border(1.dp, RedCardBorder, RoundedCornerShape(16.dp))
                                .clickable { showLeaveDialog = true }
                                .padding(16.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Outlined.ExitToApp,
                                        contentDescription = "Leave",
                                        tint = RedDanger,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = "Leave Group",
                                            color = RedDanger,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "You will no longer receive messages",
                                            color = RedDanger.copy(alpha = 0.7f),
                                            fontSize = 11.5.sp
                                        )
                                    }
                                }

                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = RedDanger.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }
                }
            }
        }

        // ── Remove Member Confirmation Dialog (Admin Only) ───────────────────
        if (memberToRemove != null) {
            val userToRemove = memberToRemove!!
            AlertDialog(
                onDismissRequest = { if (!isRemovingMember) memberToRemove = null },
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.PersonRemove,
                        contentDescription = "Remove",
                        tint = RedDanger,
                        modifier = Modifier.size(32.dp)
                    )
                },
                title = {
                    Text(
                        text = "Remove ${userToRemove.name}?",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = "${userToRemove.name} will be removed from this group and will no longer be able to send or receive messages.",
                        color = TextMuted,
                        fontSize = 13.5.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            isRemovingMember = true
                            coroutineScope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        ApiClient.apiService.removeGroupMember(
                                            groupId = groupId,
                                            req = RemoveGroupMemberRequest(
                                                admin_id = currentUserId,
                                                user_id = userToRemove.id
                                            )
                                        )
                                    }
                                    Toast.makeText(context, "${userToRemove.name} removed from group", Toast.LENGTH_SHORT).show()
                                    memberToRemove = null
                                    loadGroupData()
                                } catch (e: Exception) {
                                    Toast.makeText(context, e.message ?: "Failed to remove member", Toast.LENGTH_SHORT).show()
                                } finally {
                                    isRemovingMember = false
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = RedDanger),
                        enabled = !isRemovingMember
                    ) {
                        if (isRemovingMember) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Text("Remove", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { memberToRemove = null },
                        enabled = !isRemovingMember
                    ) {
                        Text("Cancel", color = Color.White)
                    }
                },
                containerColor = CardBg,
                shape = RoundedCornerShape(16.dp)
            )
        }

        // ── Leave Confirmation Dialog ─────────────────────────────────────────
        if (showLeaveDialog) {
            AlertDialog(
                onDismissRequest = { if (!isLeaving) showLeaveDialog = false },
                title = {
                    Text(
                        text = "Leave \"${groupDetails?.name ?: groupName}\"?",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = "You will not be able to send or receive messages in this group unless added again.",
                        color = TextMuted,
                        fontSize = 13.5.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = { handleLeaveGroup() },
                        colors = ButtonDefaults.buttonColors(containerColor = RedDanger),
                        enabled = !isLeaving
                    ) {
                        if (isLeaving) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Text("Leave", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showLeaveDialog = false },
                        enabled = !isLeaving
                    ) {
                        Text("Cancel", color = Color.White)
                    }
                },
                containerColor = CardBg,
                shape = RoundedCornerShape(16.dp)
            )
        }

        // ── Add Members Bottom Sheet ──────────────────────────────────────────
        if (showAddMembersSheet) {
            AddMembersBottomSheet(
                groupId = groupId,
                existingMemberIds = sortedUniqueMembers.map { it.id }.toSet(),
                onDismiss = { showAddMembersSheet = false },
                onMembersAdded = {
                    showAddMembersSheet = false
                    loadGroupData()
                }
            )
        }
    }
}

// ── Quick Action Circle Button ────────────────────────────────────────────────
@Composable
private fun QuickActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(ActionCircleBg)
                .border(1.dp, ActionCircleBdr, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = PurpleAccent,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            color = SubText,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

// ── Participant User Row Item ─────────────────────────────────────────────────
@Composable
private fun GroupUserRowItem(
    user: User,
    isAdmin: Boolean,
    isSelf: Boolean,
    canRemove: Boolean = false,
    onRemoveClick: () -> Unit = {},
    onMessageClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF181335))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            // User Avatar
            val avatarBitmap by produceState<Bitmap?>(null, user.profile_pic) {
                value = withContext(Dispatchers.Default) {
                    if (!user.profile_pic.isNullOrBlank()) {
                        val cacheKey = "avatar_" + user.profile_pic.hashCode()
                        MediaBitmapCache.get(cacheKey) ?: run {
                            try {
                                val bytes = Base64.decode(user.profile_pic, Base64.DEFAULT)
                                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                if (decoded != null) MediaBitmapCache.put(cacheKey, decoded)
                                decoded
                            } catch (_: Exception) { null }
                        }
                    } else null
                }
            }

            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF2A2355)),
                contentAlignment = Alignment.Center
            ) {
                val avatar = avatarBitmap
                if (avatar != null) {
                    Image(
                        bitmap = avatar.asImageBitmap(),
                        contentDescription = user.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text(
                        text = user.name.take(1).uppercase(),
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (isSelf) "${user.name} (You)" else user.name,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(6.dp))
                    if (isAdmin) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(PurpleAccent.copy(alpha = 0.2f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("Admin", color = PurpleAccent, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        val (col, lbl) = when (user.role.lowercase()) {
                            "teacher" -> Pair(PurpleLight, "Teacher")
                            "student" -> Pair(Color(0xFF38BDF8), "Student")
                            else      -> Pair(TextMuted, "Member")
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(col.copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(lbl, color = col, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Text(
                    text = "@${user.username ?: "user"}",
                    color = TextMuted,
                    fontSize = 11.5.sp
                )
            }
        }

        // Action: Message & Admin Remove button if not self
        if (!isSelf) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onMessageClick,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Chat,
                        contentDescription = "Message",
                        tint = PurpleAccent,
                        modifier = Modifier.size(18.dp)
                    )
                }
                if (canRemove) {
                    Spacer(Modifier.width(2.dp))
                    IconButton(
                        onClick = onRemoveClick,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PersonRemove,
                            contentDescription = "Remove",
                            tint = RedDanger.copy(alpha = 0.85f),
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }
        }
    }
}

// ── Add Members Bottom Sheet Modal ────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddMembersBottomSheet(
    groupId: Int,
    existingMemberIds: Set<Int>,
    onDismiss: () -> Unit,
    onMembersAdded: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

    var searchInput by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var searchStatus by remember { mutableStateOf<String?>(null) }
    var foundUser by remember { mutableStateOf<User?>(null) }
    var isAdding by remember { mutableStateOf(false) }

    fun searchUser() {
        val query = searchInput.trim().removePrefix("@").trim()
        if (query.isBlank()) {
            searchStatus = "Please enter a username"
            foundUser = null
            return
        }

        keyboardController?.hide()
        isSearching = true
        searchStatus = null
        foundUser = null

        coroutineScope.launch {
            try {
                val user = withContext(Dispatchers.IO) {
                    ApiClient.apiService.getUserByUsername(query)
                }
                if (existingMemberIds.contains(user.id)) {
                    searchStatus = "User is already a member of this group"
                    foundUser = null
                } else {
                    foundUser = user
                    searchStatus = null
                }
            } catch (_: Exception) {
                searchStatus = "User not found"
                foundUser = null
            } finally {
                isSearching = false
            }
        }
    }

    fun addMember(user: User) {
        isAdding = true
        coroutineScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ApiClient.apiService.addGroupMembers(groupId, AddGroupMembersRequest(listOf(user.id)))
                }
                Toast.makeText(context, "${user.name} added to group!", Toast.LENGTH_SHORT).show()
                onMembersAdded()
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: "Failed to add member", Toast.LENGTH_SHORT).show()
                isAdding = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = CardBg,
        tonalElevation = 8.dp,
        dragHandle = { BottomSheetDefaults.DragHandle(color = CardBorder) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 36.dp)
        ) {
            Text(
                text = "Add Participants",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = "Search by username to add members to this group.",
                color = TextMuted,
                fontSize = 12.5.sp
            )

            Spacer(Modifier.height(16.dp))

            // Search input row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchInput,
                    onValueChange = {
                        searchInput = it
                        searchStatus = null
                    },
                    placeholder = { Text("@username", color = TextMuted, fontSize = 13.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { searchUser() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = PurpleAccent,
                        unfocusedBorderColor = CardBorder,
                        focusedContainerColor = Color(0xFF191436),
                        unfocusedContainerColor = Color(0xFF191436)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                )

                Spacer(Modifier.width(8.dp))

                Button(
                    onClick = { searchUser() },
                    colors = ButtonDefaults.buttonColors(containerColor = PurpleAccent),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
                    enabled = !isSearching && searchInput.isNotBlank()
                ) {
                    if (isSearching) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Icon(imageVector = Icons.Default.Search, contentDescription = "Search", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
            }

            if (searchStatus != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = searchStatus!!,
                    color = if (searchStatus!!.contains("not found", ignoreCase = true)) RedDanger else TextMuted,
                    fontSize = 12.sp
                )
            }

            // Found User Card Preview
            AnimatedVisibility(visible = foundUser != null) {
                foundUser?.let { user ->
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF191436))
                            .border(1.dp, PurpleAccent.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = user.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text(text = "@${user.username ?: "user"} • ${user.role}", color = TextMuted, fontSize = 11.5.sp)
                        }

                        Button(
                            onClick = { addMember(user) },
                            colors = ButtonDefaults.buttonColors(containerColor = OnlineGreen),
                            shape = RoundedCornerShape(8.dp),
                            enabled = !isAdding
                        ) {
                            if (isAdding) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                            } else {
                                Text("Add", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── State Blocks ──────────────────────────────────────────────────────────────
@Composable
private fun LoadingBlock() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = PurpleAccent, strokeWidth = 3.dp)
            Spacer(Modifier.height(12.dp))
            Text("Loading group info…", color = TextMuted, fontSize = 13.5.sp)
        }
    }
}

@Composable
private fun ErrorBlock(onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = RedDanger,
                modifier = Modifier.size(44.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text("Couldn't load group info", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("Check your internet connection and try again.", color = TextMuted, fontSize = 12.5.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = PurpleAccent),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Retry", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}
