package com.security.myapplication.academic

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
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
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
private val TextWhite     = Color(0xFFFFFFFF)
private val TextMuted     = Color(0xFF8B88A6)
private val GreenSuccess  = Color(0xFF30C96B)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeacherSubjectPickerScreen(
    currentUserId: Int,
    uniKey: String = "",
    onBack: () -> Unit,
    onAssigned: () -> Unit
) {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    val initialKey = if (uniKey.isNotBlank() && uniKey != "none") uniKey else sharedPrefs.getString("cached_uni_key_$currentUserId", "") ?: ""
    val userRole = remember { sharedPrefs.getString("role", "Teacher") ?: "Teacher" }
    val isStudent = userRole.equals("Student", ignoreCase = true)
    val existingUniName = remember { sharedPrefs.getString("cached_uni_name_$currentUserId", null) }
    val existingUniKey = remember { sharedPrefs.getString("cached_uni_key_$currentUserId", null) }

    var activeUniKey by remember { mutableStateOf(initialKey) }
    var keyInputText by remember { mutableStateOf(initialKey) }
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var isLoadingCurriculum by remember { mutableStateOf(false) }
    var curriculumTree by remember { mutableStateOf<CurriculumResponse?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    // Selected Subject IDs
    val selectedSubjectIds = remember { mutableStateListOf<Int>() }
    var isAssigning by remember { mutableStateOf(false) }

    // Load curriculum on start
    fun loadCurriculum(keyToLoad: String) {
        val cleanKey = keyToLoad.trim().uppercase()
        if (cleanKey.isBlank()) {
            loadError = "Please enter your University Key."
            return
        }
        isLoadingCurriculum = true
        loadError = null
        coroutineScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    ApiClient.apiService.getCurriculumTree(cleanKey)
                }
                curriculumTree = response
                activeUniKey = cleanKey
                sharedPrefs.edit().putString("cached_uni_key_$currentUserId", cleanKey).apply()
                com.security.myapplication.storage.EduConnectStorageManager
                    .setCachedUniversityName(context.applicationContext, currentUserId, response.university_name)
            } catch (e: Exception) {
                loadError = e.message ?: "Failed to load campus curriculum. Please check your key."
            } finally {
                isLoadingCurriculum = false
            }
        }
    }

    LaunchedEffect(uniKey) {
        if (uniKey.isNotBlank() && uniKey != "none") {
            loadCurriculum(uniKey)
        } else if (activeUniKey.isNotBlank()) {
            loadCurriculum(activeUniKey)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ScreenBg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // ── Top App Bar ───────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = TextWhite
                    )
                }

                Spacer(Modifier.width(8.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Assign Subjects",
                        color = TextWhite,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = curriculumTree?.university_name ?: if (activeUniKey.isNotBlank()) "Key: $activeUniKey" else "Campus Teaching",
                        color = TextMuted,
                        fontSize = 11.5.sp
                    )
                }

                if (curriculumTree != null) {
                    TextButton(onClick = {
                        curriculumTree = null
                        keyInputText = ""
                        activeUniKey = ""
                    }) {
                        Text("Switch Key", color = PurpleLight, fontSize = 12.sp)
                    }
                }
            }

            HorizontalDivider(color = CardBorder, thickness = 1.dp)

            if (isLoadingCurriculum) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = PurpleAccent, strokeWidth = 3.dp)
                        Spacer(Modifier.height(14.dp))
                        Text("Loading campus curriculum...", color = TextMuted, fontSize = 13.sp)
                    }
                }
            } else if (curriculumTree == null) {
                // Key entry prompt
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(CardBg)
                            .border(1.dp, CardBorder, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Outlined.CoPresent, null, tint = PurpleAccent, modifier = Modifier.size(36.dp))
                    }
                    Spacer(Modifier.height(20.dp))
                    Text(
                        "Enter Campus Key",
                        color = TextWhite,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Enter the official University Code to view all available departments and assign yourself to taught subjects",
                        color = TextMuted,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(24.dp))

                    OutlinedTextField(
                        value = keyInputText,
                        onValueChange = { keyInputText = it.uppercase() },
                        placeholder = { Text("e.g. EDU-PK-NUST-8291", color = TextMuted) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextWhite,
                            unfocusedTextColor = TextWhite,
                            focusedBorderColor = PurpleAccent,
                            unfocusedBorderColor = CardBorder,
                            focusedContainerColor = FieldBg,
                            unfocusedContainerColor = FieldBg
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    if (loadError != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(loadError!!, color = Color(0xFFFF6B6B), fontSize = 12.sp, textAlign = TextAlign.Center)
                    }

                    Spacer(Modifier.height(20.dp))

                    Button(
                        onClick = { loadCurriculum(keyInputText) },
                        colors = ButtonDefaults.buttonColors(containerColor = PurpleAccent),
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        enabled = keyInputText.isNotBlank()
                    ) {
                        Text("Connect Campus & View Subjects", fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                NoNativeOverscroll {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .pumpBounceScroll()
                            .verticalScroll(scrollState)
                            .padding(16.dp)
                    ) {
                        // Campus Info Card
                        Card(
                            colors = CardDefaults.cardColors(containerColor = CardBg),
                            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF1E1A40)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Outlined.School, null, tint = PurpleAccent, modifier = Modifier.size(22.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = curriculumTree!!.university_name,
                                        color = TextWhite,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Official Key: ${curriculumTree!!.uni_key}",
                                        color = GreenSuccess,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        Text(
                            text = "Select all subjects you teach across programs and semesters:",
                            color = TextMuted,
                            fontSize = 13.sp
                        )

                        Spacer(Modifier.height(16.dp))

                        // Tree: Programs -> Departments -> Semesters -> Subjects
                        curriculumTree!!.programs.forEach { program ->
                            Text(
                                text = "🎓 ${program.name} Program",
                                color = PurpleLight,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(8.dp))

                            program.departments.forEach { dept ->
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = CardBg),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                                ) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        Text(
                                            text = dept.name,
                                            color = TextWhite,
                                            fontSize = 14.5.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(Modifier.height(10.dp))

                                        dept.semesters.forEach { sem ->
                                            if (sem.subjects.isNotEmpty()) {
                                                Text(
                                                    text = "Semester ${sem.semester_num}",
                                                    color = TextMuted,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                                Spacer(Modifier.height(6.dp))

                                                sem.subjects.forEach { subject ->
                                                    val isSelected = selectedSubjectIds.contains(subject.id)
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clip(RoundedCornerShape(8.dp))
                                                            .background(if (isSelected) Color(0xFF1E1A40) else Color.Transparent)
                                                            .clickable {
                                                                if (isSelected) selectedSubjectIds.remove(subject.id)
                                                                else selectedSubjectIds.add(subject.id)
                                                            }
                                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Checkbox(
                                                            checked = isSelected,
                                                            onCheckedChange = { checked ->
                                                                if (checked) selectedSubjectIds.add(subject.id)
                                                                else selectedSubjectIds.remove(subject.id)
                                                            },
                                                            colors = CheckboxDefaults.colors(
                                                                checkedColor = PurpleAccent,
                                                                uncheckedColor = TextMuted
                                                            ),
                                                            modifier = Modifier.size(24.dp)
                                                        )
                                                        Spacer(Modifier.width(10.dp))
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text(
                                                                text = subject.name,
                                                                color = if (isSelected) TextWhite else Color(0xFFCBC8E0),
                                                                fontSize = 13.5.sp,
                                                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                                            )
                                                            if (subject.group_name != null) {
                                                                Text(
                                                                    text = "Class: ${subject.group_name}",
                                                                    color = TextMuted,
                                                                    fontSize = 11.sp
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                                Spacer(Modifier.height(8.dp))
                                            }
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }

                // ── Bottom Action Bar ─────────────────────────────────────────
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = CardBg,
                    shadowElevation = 8.dp,
                    border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "${selectedSubjectIds.size} Subject(s) Selected",
                                color = TextWhite,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Instant instructor assignment",
                                color = TextMuted,
                                fontSize = 11.5.sp
                            )
                        }

                        Button(
                            onClick = {
                                if (selectedSubjectIds.isEmpty()) {
                                    Toast.makeText(context, "Please select at least 1 subject", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                isAssigning = true
                                coroutineScope.launch {
                                    try {
                                        withContext(Dispatchers.IO) {
                                            ApiClient.apiService.teacherAssignSubjects(
                                                TeacherSubjectAssignmentRequest(
                                                    teacher_id = currentUserId,
                                                    uni_key = activeUniKey,
                                                    subject_ids = selectedSubjectIds.toList()
                                                )
                                            )
                                        }
                                        Toast.makeText(context, "Assigned to ${selectedSubjectIds.size} class group(s)!", Toast.LENGTH_SHORT).show()
                                        onAssigned()
                                    } catch (e: Exception) {
                                        Toast.makeText(context, e.message ?: "Failed to assign subjects", Toast.LENGTH_SHORT).show()
                                    } finally {
                                        isAssigning = false
                                    }
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = PurpleAccent),
                            enabled = selectedSubjectIds.isNotEmpty() && !isAssigning
                        ) {
                            if (isAssigning) {
                                CircularProgressIndicator(color = TextWhite, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                            } else {
                                Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Confirm Classes", fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}
