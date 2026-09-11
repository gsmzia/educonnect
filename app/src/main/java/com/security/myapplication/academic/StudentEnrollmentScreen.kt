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
fun StudentEnrollmentScreen(
    currentUserId: Int,
    uniKey: String = "",
    onBack: () -> Unit,
    onEnrolled: () -> Unit
) {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    val initialKey = if (uniKey.isNotBlank() && uniKey != "none") uniKey else sharedPrefs.getString("cached_uni_key_$currentUserId", "") ?: ""
    val userRole = remember { sharedPrefs.getString("role", "Student") ?: "Student" }
    val isTeacher = userRole.equals("Teacher", ignoreCase = true)
    val existingUniName = remember { sharedPrefs.getString("cached_uni_name_$currentUserId", null) }
    val existingUniKey = remember { sharedPrefs.getString("cached_uni_key_$currentUserId", null) }
    
    var activeUniKey by remember { mutableStateOf(initialKey) }
    var keyInputText by remember { mutableStateOf(initialKey) }
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var isLoadingCurriculum by remember { mutableStateOf(false) }
    var curriculumTree by remember { mutableStateOf<CurriculumResponse?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    // Student Selections (Strict single choice)
    var selectedProgram by remember { mutableStateOf<ProgramResponse?>(null) }
    var selectedDepartment by remember { mutableStateOf<DepartmentResponse?>(null) }
    var selectedSemesterNum by remember { mutableStateOf<Int?>(1) }

    var isEnrolling by remember { mutableStateOf(false) }

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
                // Auto-select first program & dept if available
                val firstProg = response.programs.firstOrNull()
                selectedProgram = firstProg
                selectedDepartment = firstProg?.departments?.firstOrNull()
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
                        text = "Join Your Classes",
                        color = TextWhite,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = curriculumTree?.university_name ?: if (activeUniKey.isNotBlank()) "Key: $activeUniKey" else "Campus Onboarding",
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
                        Icon(Icons.Outlined.AccountBalance, null, tint = PurpleAccent, modifier = Modifier.size(36.dp))
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
                        "Enter the official University Code provided by your institution founder (e.g. EDU-PK-NUST-8291)",
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
                        Text("Connect Campus & Load Classes", fontWeight = FontWeight.Bold)
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
                                    Icon(Icons.Outlined.AccountBalance, null, tint = PurpleAccent, modifier = Modifier.size(22.dp))
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

                        Spacer(Modifier.height(20.dp))

                        // ── Step 1: Select Program ────────────────────────────
                        Text(
                            text = "1. Select Program",
                            color = TextWhite,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            curriculumTree!!.programs.forEach { program ->
                                val isSelected = selectedProgram?.id == program.id
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (isSelected) PurpleAccent else CardBg)
                                        .border(1.dp, if (isSelected) PurpleLight else CardBorder, RoundedCornerShape(10.dp))
                                        .clickable {
                                            selectedProgram = program
                                            selectedDepartment = program.departments.firstOrNull()
                                            selectedSemesterNum = 1
                                        }
                                        .padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = program.name,
                                        color = if (isSelected) TextWhite else TextMuted,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(20.dp))

                        // ── Step 2: Select Department ─────────────────────────
                        Text(
                            text = "2. Select Department / Major",
                            color = TextWhite,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(10.dp))

                        val departments = selectedProgram?.departments ?: emptyList()
                        if (departments.isEmpty()) {
                            Text("No departments found for this program.", color = TextMuted, fontSize = 12.sp)
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                departments.forEach { dept ->
                                    val isSelected = selectedDepartment?.id == dept.id
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (isSelected) Color(0xFF1E1A40) else CardBg)
                                            .border(1.dp, if (isSelected) PurpleAccent else CardBorder, RoundedCornerShape(10.dp))
                                            .clickable {
                                                selectedDepartment = dept
                                                selectedSemesterNum = 1
                                            }
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = isSelected,
                                            onClick = {
                                                selectedDepartment = dept
                                                selectedSemesterNum = 1
                                            },
                                            colors = RadioButtonDefaults.colors(
                                                selectedColor = PurpleAccent,
                                                unselectedColor = TextMuted
                                            )
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                text = dept.name,
                                                color = if (isSelected) TextWhite else TextMuted,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                fontSize = 14.sp
                                            )
                                            Text(
                                                text = "${dept.total_semesters} Semesters",
                                                color = TextMuted,
                                                fontSize = 11.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(20.dp))

                        // ── Step 3: Select Semester ───────────────────────────
                        val totalSems = selectedDepartment?.total_semesters ?: 8
                        Text(
                            text = "3. Select Current Semester",
                            color = TextWhite,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(10.dp))

                        // Grid of semester pills (4 per row)
                        val semList = (1..totalSems).toList()
                        semList.chunked(4).forEach { rowList ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowList.forEach { semNum ->
                                    val isSelected = selectedSemesterNum == semNum
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (isSelected) PurpleAccent else CardBg)
                                            .border(1.dp, if (isSelected) PurpleLight else CardBorder, RoundedCornerShape(10.dp))
                                            .clickable { selectedSemesterNum = semNum }
                                            .padding(vertical = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "Sem $semNum",
                                            color = if (isSelected) TextWhite else TextMuted,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            fontSize = 13.sp
                                        )
                                    }
                                }
                                // Fill missing slots in row if not divisible by 4
                                repeat(4 - rowList.size) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        // ── Preview of Subjects for selected Semester ─────────
                        val currentSemSubjects = selectedDepartment?.semesters
                            ?.firstOrNull { it.semester_num == selectedSemesterNum }
                            ?.subjects ?: emptyList()

                        Card(
                            colors = CardDefaults.cardColors(containerColor = CardBg),
                            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Class, null, tint = PurpleAccent, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "Semester $selectedSemesterNum Official Groups (${currentSemSubjects.size})",
                                        color = TextWhite,
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                if (currentSemSubjects.isEmpty()) {
                                    Text(
                                        "No registered subjects in this semester.",
                                        color = TextMuted,
                                        fontSize = 12.sp
                                    )
                                } else {
                                    currentSemSubjects.forEach { sub ->
                                        Row(
                                            modifier = Modifier.padding(vertical = 3.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(PurpleAccent))
                                            Spacer(Modifier.width(8.dp))
                                            Text(sub.name, color = TextMuted, fontSize = 12.5.sp)
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))
                    }
                }

                // ── Bottom Action Button ──────────────────────────────────────
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = CardBg,
                    shadowElevation = 8.dp,
                    border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
                ) {
                    Box(modifier = Modifier.padding(16.dp)) {
                        Button(
                            onClick = {
                                if (selectedProgram == null || selectedDepartment == null || selectedSemesterNum == null) {
                                    Toast.makeText(context, "Please select program, department, and semester", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                isEnrolling = true
                                coroutineScope.launch {
                                    try {
                                        val res = withContext(Dispatchers.IO) {
                                            ApiClient.apiService.studentAutoEnroll(
                                                StudentAutoEnrollRequest(
                                                    student_id = currentUserId,
                                                    uni_key = activeUniKey,
                                                    program_name = selectedProgram!!.name,
                                                    department_name = selectedDepartment!!.name,
                                                    semester_num = selectedSemesterNum!!
                                                )
                                            )
                                        }
                                        Toast.makeText(context, "Enrolled in ${res.enrolled_groups_count} classes!", Toast.LENGTH_SHORT).show()
                                        onEnrolled()
                                    } catch (e: Exception) {
                                        Toast.makeText(context, e.message ?: "Failed to enroll. Please try again.", Toast.LENGTH_SHORT).show()
                                    } finally {
                                        isEnrolling = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = PurpleAccent),
                            enabled = !isEnrolling && selectedProgram != null && selectedDepartment != null
                        ) {
                            if (isEnrolling) {
                                CircularProgressIndicator(color = TextWhite, strokeWidth = 2.5.dp, modifier = Modifier.size(22.dp))
                            } else {
                                Icon(Icons.Default.School, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Join My Official Classes", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}
