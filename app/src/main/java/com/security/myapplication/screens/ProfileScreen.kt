package com.security.myapplication.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.security.myapplication.models.User
import com.security.myapplication.models.UserProfileUpdateRequest
import com.security.myapplication.network.ApiClient
import com.security.myapplication.ui.theme.pumpBounceScroll
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.cos
import kotlin.math.sin

private val PurpleAccent  = Color(0xFF8B6BFF)
private val CardBg        = Color(0xFF13102C)
private val IconBoxBg     = Color(0xFF221A4B)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    userId: Int,
    onBack: () -> Unit,
    onUserUpdated: (User) -> Unit = {}
) {
    var user by remember { mutableStateOf<User?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var showEditDialog by remember { mutableStateOf(false) }
    var editNameText by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // Decode Base64 -> Bitmap
    val profileBitmap by produceState<Bitmap?>(null, user?.profile_pic) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            user?.profile_pic?.let { base64 ->
                try {
                    val bytes = Base64.decode(base64, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } catch (_: Exception) { null }
            }
        }
    }

    // Compress Uri -> Base64 (center-crop to square, then scale — like WhatsApp)
    fun encodeUri(uri: Uri): String? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 1200 || bounds.outHeight / sample > 1200) sample *= 2
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val orig = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOptions)
        } ?: return null
        val size = minOf(orig.width, orig.height)
        val x = (orig.width - size) / 2
        val y = (orig.height - size) / 2
        val cropped = Bitmap.createBitmap(orig, x, y, size, size)
        if (cropped !== orig) orig.recycle()
        val scaled = Bitmap.createScaledBitmap(cropped, 600, 600, true)
        if (scaled !== cropped) cropped.recycle()
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    } catch (_: Exception) { null }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            coroutineScope.launch {
                isSaving = true
                try {
                    // Image decode/compression is CPU and memory heavy; keep it
                    // off the Compose/Main dispatcher before the API request.
                    val b64 = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        encodeUri(it)
                    } ?: return@launch
                    val updated = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        ApiClient.apiService.updateUserProfile(
                            userId, UserProfileUpdateRequest(name = user?.name, profile_pic = b64)
                        )
                    }
                    user = updated
                    onUserUpdated(updated)
                } catch (_: Exception) {}
                isSaving = false
            }
        }
    }

    LaunchedEffect(userId) {
        try {
            val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                ApiClient.apiService.getUser(userId)
            }
            user = res
            editNameText = res.name
        } catch (_: Exception) {}
        isLoading = false
    }

    // ── Root Container with premium layered background ───────────────────────
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF090718))
    ) {

        // Layer 2: top violet accent radial glow
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF5B3FCC).copy(alpha = 0.55f),
                            Color(0xFF3A1FA8).copy(alpha = 0.25f),
                            Color.Transparent
                        ),
                        center = Offset(0.5f, 0.0f),
                        radius = 900f,
                        tileMode = TileMode.Clamp
                    )
                )
        )

        // Layer 3: subtle bottom teal accent glow
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF1A0F4E).copy(alpha = 0.6f),
                            Color.Transparent
                        )
                    )
                )
        )

        // Layer 4: sparkle star dots
        Canvas(modifier = Modifier.fillMaxSize().alpha(0.6f)) {
            val stars = listOf(
                Offset(60f, 180f) to 2.2f,
                Offset(size.width - 80f, 120f) to 1.8f,
                Offset(40f, 520f) to 1.5f,
                Offset(size.width - 50f, 460f) to 2.0f,
                Offset(120f, 820f) to 1.4f,
                Offset(size.width - 140f, 700f) to 1.6f,
                Offset(size.width * 0.2f, 1100f) to 1.2f,
                Offset(size.width * 0.85f, 1000f) to 1.9f,
                Offset(size.width * 0.5f, 90f) to 1.3f,
                Offset(size.width * 0.7f, 350f) to 1.7f,
            )
            stars.forEach { (offset, radius) ->
                drawCircle(
                    color = Color.White.copy(alpha = 0.7f),
                    radius = radius,
                    center = offset
                )
            }
            // 4-pointed sparkle at top right
            val cx = size.width - 55f
            val cy = 220f
            val len = 12f
            for (angle in listOf(0.0, 90.0, 180.0, 270.0)) {
                val rad = Math.toRadians(angle)
                drawLine(
                    color = Color.White.copy(alpha = 0.5f),
                    start = Offset(cx, cy),
                    end = Offset(cx + (len * cos(rad)).toFloat(), cy + (len * sin(rad)).toFloat()),
                    strokeWidth = 1.4f
                )
            }
        }

        // Main Content
        if (isLoading) {
            CircularProgressIndicator(color = PurpleAccent, modifier = Modifier.align(Alignment.Center))
        } else if (user != null) {
            val currentUser = user!!
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
                    .pumpBounceScroll()
                    .verticalScroll(scrollState)
            ) {
                // ── TopBar ───────────────────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.12f))
                            .clickable { onBack() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Text("Profile", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                }

                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(14.dp))

                    // ── DP Circle with premium ring ────────────────────────────
                    Box(
                        modifier = Modifier.size(152.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        // Outer glow ring
                        Box(
                            modifier = Modifier
                                .size(150.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        listOf(
                                            Color(0xFF8B6BFF).copy(alpha = 0.35f),
                                            Color.Transparent
                                        )
                                    )
                                )
                        )
                        // Main DP circle
                        Box(
                            modifier = Modifier
                                .size(130.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        listOf(Color(0xFF2A1A5E), Color(0xFF150D35))
                                    )
                                )
                                .border(
                                    width = 2.5.dp,
                                    brush = Brush.linearGradient(
                                        listOf(
                                            Color(0xFFBD9FFF),
                                            Color(0xFF7B5CF5),
                                            Color(0xFF4A2FCF)
                                        )
                                    ),
                                    shape = CircleShape
                                )
                                .clickable { imagePicker.launch("image/*") },
                            contentAlignment = Alignment.Center
                        ) {
                            val avatar = profileBitmap
                            if (avatar != null) {
                                Image(
                                    bitmap = avatar.asImageBitmap(),
                                    contentDescription = "DP",
                                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                // Premium gradient initial text
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(
                                            Brush.radialGradient(
                                                listOf(Color(0xFF5B3FCC), Color(0xFF2A1470))
                                            )
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        (currentUser.name.firstOrNull()?.uppercaseChar() ?: "U").toString(),
                                        color = Color.White,
                                        fontSize = 48.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }
                        }
                        // Camera badge
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .align(Alignment.BottomEnd)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        listOf(Color(0xFF9B79FF), Color(0xFF5B3FCC))
                                    )
                                )
                                .border(2.5.dp, Color(0xFF080617), CircleShape)
                                .clickable { imagePicker.launch("image/*") },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.PhotoCamera,
                                contentDescription = "Change Photo",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(18.dp))

                    // Name
                    Text(
                        currentUser.name,
                        color = Color.White,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(5.dp))
                    // Username
                    Text(
                        currentUser.username ?: "-",
                        color = Color(0xFFAA88FF),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(14.dp))

                    // Role Badge (premium gradient pill)
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
                                    listOf(Color(0xFF9B79FF).copy(alpha = 0.8f), Color(0xFF5B3FCC).copy(alpha = 0.4f))
                                ),
                                RoundedCornerShape(50.dp)
                            )
                            .padding(horizontal = 18.dp, vertical = 8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.School, null, tint = PurpleAccent, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(7.dp))
                            Text(
                                currentUser.role,
                                color = Color(0xFFBDA5FF),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Spacer(Modifier.height(32.dp))

                    // ── Info Card (premium glass) ─────────────────────────────
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(22.dp))
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color(0xFF1A1240).copy(alpha = 0.95f), Color(0xFF0E0B28).copy(alpha = 0.95f))
                                )
                            )
                            .border(
                                1.dp,
                                Brush.linearGradient(
                                    listOf(
                                        Color(0xFF6A50D3).copy(alpha = 0.6f),
                                        Color(0xFF2A1A5E).copy(alpha = 0.3f),
                                        Color(0xFF6A50D3).copy(alpha = 0.5f)
                                    )
                                ),
                                RoundedCornerShape(22.dp)
                            )
                            .padding(vertical = 6.dp)
                    ) {
                        Column {
                            ProfileInfoRow(Icons.Outlined.Person, "Name", currentUser.name)
                            Box(modifier = Modifier.fillMaxWidth().height(0.7.dp).padding(horizontal = 16.dp).background(Color(0xFF2E2260).copy(alpha = 0.8f)))
                            ProfileInfoRow(Icons.Outlined.AlternateEmail, "Username", currentUser.username ?: "-")
                            Box(modifier = Modifier.fillMaxWidth().height(0.7.dp).padding(horizontal = 16.dp).background(Color(0xFF2E2260).copy(alpha = 0.8f)))
                            ProfileInfoRow(Icons.Outlined.Mail, "Email", currentUser.email)
                            Box(modifier = Modifier.fillMaxWidth().height(0.7.dp).padding(horizontal = 16.dp).background(Color(0xFF2E2260).copy(alpha = 0.8f)))
                            ProfileInfoRow(Icons.Outlined.School, "Role", currentUser.role)
                        }
                    }

                    Spacer(Modifier.height(26.dp))

                    // ── Edit Profile Button (gradient border) ─────────────────
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFF2A1A5E).copy(alpha = 0.8f), Color(0xFF1A1040).copy(alpha = 0.8f))
                                )
                            )
                            .border(
                                1.5.dp,
                                Brush.linearGradient(
                                    listOf(Color(0xFF9B79FF), Color(0xFF5B3FCC))
                                ),
                                RoundedCornerShape(16.dp)
                            )
                            .clickable {
                                editNameText = currentUser.name
                                showEditDialog = true
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Edit, null, tint = Color(0xFFBDA5FF), modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "Edit Profile",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFBDA5FF)
                            )
                        }
                    }

                    Spacer(Modifier.height(36.dp))
                }
            }
        }

        // ── Edit Dialog ───────────────────────────────────────────────────────
        if (showEditDialog && user != null) {
            AlertDialog(
                onDismissRequest = { if (!isSaving) showEditDialog = false },
                containerColor = Color(0xFF12102A),
                tonalElevation = 0.dp,
                title = {
                    Text("Edit Profile", color = Color.White, fontWeight = FontWeight.Bold)
                },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { imagePicker.launch("image/*") },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF221A4B)),
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Outlined.PhotoCamera, null, tint = PurpleAccent, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Change Profile Photo", color = Color.White, fontSize = 13.sp)
                        }
                        Spacer(Modifier.height(18.dp))
                        Text("Display Name", color = Color(0xFFAA88FF), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = editNameText,
                            onValueChange = { editNameText = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PurpleAccent,
                                unfocusedBorderColor = Color(0xFF2A2A5A),
                                cursorColor = PurpleAccent,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedContainerColor = Color(0xFF0D0D2B),
                                unfocusedContainerColor = Color(0xFF0D0D2B)
                            ),
                            shape = RoundedCornerShape(10.dp)
                        )
                        Spacer(Modifier.height(14.dp))
                        Text("Username", color = Color(0xFF6868A0), fontSize = 11.sp)
                        Text(user!!.username ?: "-", color = Color(0xFF8B6BFF), fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("Email", color = Color(0xFF6868A0), fontSize = 11.sp)
                        Text(user!!.email, color = Color.White, fontSize = 13.sp)
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (editNameText.isNotBlank()) {
                                coroutineScope.launch {
                                    isSaving = true
                                    try {
                                        val updated = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            ApiClient.apiService.updateUserProfile(
                                                userId, UserProfileUpdateRequest(name = editNameText.trim())
                                            )
                                        }
                                        user = updated
                                        onUserUpdated(updated)
                                        showEditDialog = false
                                    } catch (_: Exception) {}
                                    isSaving = false
                                }
                            }
                        },
                        enabled = !isSaving && editNameText.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = PurpleAccent)
                    ) {
                        if (isSaving) CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                        else Text("Save Changes", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { if (!isSaving) showEditDialog = false }) {
                        Text("Cancel", color = Color(0xFF6868A0))
                    }
                }
            )
        }
    }
}

@Composable
private fun ProfileInfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF2E1F6E), Color(0xFF1E1550))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = Color(0xFFAA88FF), modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = Color(0xFF8070C0), fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(3.dp))
            Text(value, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
