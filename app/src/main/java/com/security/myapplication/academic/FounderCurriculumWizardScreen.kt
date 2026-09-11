package com.security.myapplication.academic

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import org.json.JSONArray
import org.json.JSONObject

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
private val RedDanger     = Color(0xFFFF4D6D)

// Internal working draft model for the wizard
private class WizardDeptDraft(
    var name: String = "",
    var totalSemesters: Int = 8
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FounderCurriculumWizardScreen(
    uniKey: String,
    uniName: String,
    onBack: () -> Unit,
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    // ── Wizard State ──────────────────────────────────────────────────────────
    var currentStep by remember { mutableStateOf(1) } // 1: Programs, 2: Depts, 3: Subjects, 4: Finish

    // Step 1: Programs (BS, MS, PhD + custom)
    val availablePrograms = remember { mutableStateListOf("BS", "MS", "PhD") }
    val selectedPrograms = remember { mutableStateListOf("BS", "MS", "PhD") }
    var customProgramInput by remember { mutableStateOf("") }

    // Step 2: Departments per program: Map<ProgramName, List<WizardDeptDraft>>
    val departmentsByProgram = remember {
        mutableStateMapOf<String, MutableList<WizardDeptDraft>>()
    }

    // Step 3: Subjects per (Program, Department, SemesterNum) -> List<String>
    // Key: "ProgramName__DeptName__SemNum"
    val subjectsBySemester = remember {
        mutableStateMapOf<String, MutableList<String>>()
    }

    // Current pointer in Step 3
    var activeProgIndex by remember { mutableStateOf(0) }
    var activeDeptIndex by remember { mutableStateOf(0) }
    var activeSemNum by remember { mutableStateOf(1) }

    var isSubmitting by remember { mutableStateOf(false) }
    var submitSuccess by remember { mutableStateOf(false) }
    var isDraftRestored by remember { mutableStateOf(false) }
    var showBulkPasteDialog by remember { mutableStateOf(false) }
    var bulkPasteText by remember { mutableStateOf("") }

    // ── Draft Persistence (Auto-Save & Resume) ────────────────────────────────
    fun saveDraft() {
        try {
            val root = JSONObject()
            root.put("currentStep", currentStep)
            root.put("activeProgIndex", activeProgIndex)
            root.put("activeDeptIndex", activeDeptIndex)
            root.put("activeSemNum", activeSemNum)

            val progsArr = JSONArray()
            selectedPrograms.forEach { progsArr.put(it) }
            root.put("selectedPrograms", progsArr)

            val deptsObj = JSONObject()
            departmentsByProgram.forEach { (prog, dList) ->
                val dArr = JSONArray()
                dList.forEach { d ->
                    val dObj = JSONObject()
                    dObj.put("name", d.name)
                    dObj.put("totalSemesters", d.totalSemesters)
                    dArr.put(dObj)
                }
                deptsObj.put(prog, dArr)
            }
            root.put("departmentsByProgram", deptsObj)

            val subjsObj = JSONObject()
            subjectsBySemester.forEach { (key, sList) ->
                val sArr = JSONArray()
                sList.forEach { sArr.put(it) }
                subjsObj.put(key, sArr)
            }
            root.put("subjectsBySemester", subjsObj)

            sharedPrefs.edit().putString("draft_curriculum_$uniKey", root.toString()).apply()
        } catch (_: Exception) {}
    }

    fun loadDraft(): Boolean {
        val raw = sharedPrefs.getString("draft_curriculum_$uniKey", null) ?: return false
        return try {
            val root = JSONObject(raw)
            currentStep = root.optInt("currentStep", 1)
            activeProgIndex = root.optInt("activeProgIndex", 0)
            activeDeptIndex = root.optInt("activeDeptIndex", 0)
            activeSemNum = root.optInt("activeSemNum", 1)

            val progsArr = root.optJSONArray("selectedPrograms")
            if (progsArr != null && progsArr.length() > 0) {
                selectedPrograms.clear()
                for (i in 0 until progsArr.length()) {
                    selectedPrograms.add(progsArr.getString(i))
                }
            }

            val deptsObj = root.optJSONObject("departmentsByProgram")
            if (deptsObj != null) {
                departmentsByProgram.clear()
                val keys = deptsObj.keys()
                while (keys.hasNext()) {
                    val prog = keys.next()
                    val dArr = deptsObj.getJSONArray(prog)
                    val list = mutableStateListOf<WizardDeptDraft>()
                    for (i in 0 until dArr.length()) {
                        val dObj = dArr.getJSONObject(i)
                        list.add(WizardDeptDraft(dObj.getString("name"), dObj.optInt("totalSemesters", 8)))
                    }
                    departmentsByProgram[prog] = list
                }
            }

            val subjsObj = root.optJSONObject("subjectsBySemester")
            if (subjsObj != null) {
                subjectsBySemester.clear()
                val keys = subjsObj.keys()
                while (keys.hasNext()) {
                    val semKey = keys.next()
                    val sArr = subjsObj.getJSONArray(semKey)
                    val list = mutableStateListOf<String>()
                    for (i in 0 until sArr.length()) {
                        list.add(sArr.getString(i))
                    }
                    subjectsBySemester[semKey] = list
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    // Auto-restore on screen entry
    LaunchedEffect(uniKey) {
        val restored = loadDraft()
        if (restored) {
            isDraftRestored = true
        } else {
            // Also check if backend already has curriculum published
            try {
                val existing = withContext(Dispatchers.IO) {
                    ApiClient.apiService.getCurriculumTree(uniKey)
                }
                if (existing.programs.isNotEmpty()) {
                    selectedPrograms.clear()
                    existing.programs.forEach { p ->
                        selectedPrograms.add(p.name)
                        val dList = mutableStateListOf<WizardDeptDraft>()
                        p.departments.forEach { d ->
                            dList.add(WizardDeptDraft(d.name, d.total_semesters))
                            d.semesters.forEach { s ->
                                val sKey = "${p.name}__${d.name}__${s.semester_num}"
                                val sList = mutableStateListOf<String>()
                                s.subjects.forEach { sub -> sList.add(sub.name) }
                                subjectsBySemester[sKey] = sList
                            }
                        }
                        departmentsByProgram[p.name] = dList
                    }
                    isDraftRestored = true
                }
            } catch (_: Exception) {}
        }
    }

    // Auto-save whenever current step changes
    LaunchedEffect(currentStep, activeProgIndex, activeDeptIndex, activeSemNum) {
        if (!submitSuccess) {
            saveDraft()
        }
    }

    // Initialize departments when moving from Step 1 to Step 2
    fun initDepartments() {
        selectedPrograms.forEach { prog ->
            if (!departmentsByProgram.containsKey(prog)) {
                val defaultSemesters = when (prog.uppercase()) {
                    "BS" -> 8
                    "MS" -> 4
                    "PHD" -> 6
                    else -> 8
                }
                departmentsByProgram[prog] = mutableStateListOf(
                    WizardDeptDraft("Computer Science", defaultSemesters),
                    WizardDeptDraft("Software Engineering", defaultSemesters)
                )
            }
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
                .imePadding()
        ) {
            // ── Top Bar ───────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    saveDraft()
                    if (currentStep > 1 && !submitSuccess) currentStep-- else onBack()
                }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = TextWhite
                    )
                }

                Spacer(Modifier.width(8.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Curriculum Wizard",
                        color = TextWhite,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Step $currentStep of 4 • $uniName",
                        color = TextMuted,
                        fontSize = 11.5.sp
                    )
                }

                // Save & Exit button
                if (!submitSuccess) {
                    TextButton(
                        onClick = {
                            saveDraft()
                            Toast.makeText(context, "Draft saved! You can resume anytime.", Toast.LENGTH_SHORT).show()
                            onBack()
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = PurpleLight)
                    ) {
                        Icon(Icons.Outlined.BookmarkBorder, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Save & Exit", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // Step Progress Line
            LinearProgressIndicator(
                progress = { currentStep / 4f },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = PurpleAccent,
                trackColor = CardBorder
            )

            // Draft Restored Banner
            if (isDraftRestored && !submitSuccess) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(GreenSuccess.copy(alpha = 0.12f))
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.CheckCircle, null, tint = GreenSuccess, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Resumed from saved draft",
                        color = GreenSuccess,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "Reset",
                        color = RedDanger,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable {
                            sharedPrefs.edit().remove("draft_curriculum_$uniKey").apply()
                            isDraftRestored = false
                            currentStep = 1
                            selectedPrograms.clear()
                            selectedPrograms.addAll(listOf("BS", "MS", "PhD"))
                            departmentsByProgram.clear()
                            subjectsBySemester.clear()
                            Toast.makeText(context, "Draft cleared", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }

            NoNativeOverscroll {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .pumpBounceScroll()
                        .verticalScroll(scrollState)
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when (currentStep) {
                        1 -> {
                            // ── STEP 1: SELECT PROGRAMS OFFERED ───────────────
                            Text(
                                text = "Which programs does your university offer?",
                                color = TextWhite,
                                fontSize = 19.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "Select all degrees offered by $uniName",
                                color = TextMuted,
                                fontSize = 13.sp
                            )
                            Spacer(Modifier.height(20.dp))

                            // Programs list
                            availablePrograms.forEach { prog ->
                                val isSelected = selectedPrograms.contains(prog)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(if (isSelected) PurpleAccent.copy(alpha = 0.15f) else CardBg)
                                        .border(1.dp, if (isSelected) PurpleAccent else CardBorder, RoundedCornerShape(14.dp))
                                        .clickable {
                                            if (isSelected) {
                                                if (selectedPrograms.size > 1) selectedPrograms.remove(prog)
                                                else Toast.makeText(context, "Select at least 1 program", Toast.LENGTH_SHORT).show()
                                            } else {
                                                selectedPrograms.add(prog)
                                            }
                                            saveDraft()
                                        }
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "$prog Degree",
                                        color = TextWhite,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = { checked ->
                                            if (checked) selectedPrograms.add(prog)
                                            else if (selectedPrograms.size > 1) selectedPrograms.remove(prog)
                                            saveDraft()
                                        },
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = PurpleAccent,
                                            checkmarkColor = TextWhite
                                        )
                                    )
                                }
                                Spacer(Modifier.height(10.dp))
                            }

                            // Add custom degree
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = customProgramInput,
                                    onValueChange = { customProgramInput = it },
                                    placeholder = { Text("e.g. Associate Degree, Diploma", color = TextMuted, fontSize = 13.sp) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = TextWhite,
                                        unfocusedTextColor = TextWhite,
                                        focusedContainerColor = FieldBg,
                                        unfocusedContainerColor = FieldBg,
                                        focusedBorderColor = PurpleAccent,
                                        unfocusedBorderColor = CardBorder
                                    )
                                )
                                Spacer(Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        val trimmed = customProgramInput.trim()
                                        if (trimmed.isNotBlank() && !availablePrograms.contains(trimmed)) {
                                            availablePrograms.add(trimmed)
                                            selectedPrograms.add(trimmed)
                                            customProgramInput = ""
                                            saveDraft()
                                        }
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = PurpleAccent)
                                ) {
                                    Text("Add")
                                }
                            }

                            Spacer(Modifier.height(30.dp))

                            Button(
                                onClick = {
                                    initDepartments()
                                    saveDraft()
                                    currentStep = 2
                                },
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = PurpleAccent),
                                enabled = selectedPrograms.isNotEmpty()
                            ) {
                                Text("Next: Configure Departments →", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        2 -> {
                            // ── STEP 2: CONFIGURE DEPARTMENTS PER PROGRAM ──────
                            Text(
                                text = "Departments & Duration",
                                color = TextWhite,
                                fontSize = 19.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "Add departments and total semesters for each degree program.",
                                color = TextMuted,
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(18.dp))

                            selectedPrograms.forEach { prog ->
                                val depts = departmentsByProgram.getOrPut(prog) { mutableStateListOf() }

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(CardBg)
                                        .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
                                        .padding(16.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "$prog Degree Departments",
                                            color = PurpleLight,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        TextButton(onClick = {
                                            val defSems = when (prog.uppercase()) { "MS" -> 4; "PHD" -> 6; else -> 8 }
                                            depts.add(WizardDeptDraft("New Department", defSems))
                                            saveDraft()
                                        }) {
                                            Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("+ Add Dept", fontSize = 12.5.sp)
                                        }
                                    }

                                    Spacer(Modifier.height(10.dp))

                                    depts.forEachIndexed { index, dept ->
                                        var deptName by remember(dept.name) { mutableStateOf(dept.name) }
                                        var totalSems by remember(dept.totalSemesters) { mutableIntStateOf(dept.totalSemesters) }

                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            OutlinedTextField(
                                                value = deptName,
                                                onValueChange = {
                                                    deptName = it
                                                    dept.name = it
                                                    saveDraft()
                                                },
                                                placeholder = { Text("Department name", color = TextMuted, fontSize = 13.sp) },
                                                singleLine = true,
                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(10.dp),
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedTextColor = TextWhite,
                                                    unfocusedTextColor = TextWhite,
                                                    focusedContainerColor = FieldBg,
                                                    unfocusedContainerColor = FieldBg,
                                                    focusedBorderColor = PurpleAccent,
                                                    unfocusedBorderColor = CardBorder
                                                )
                                            )

                                            Spacer(Modifier.width(8.dp))

                                            // Semesters picker chip
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(FieldBg)
                                                    .border(1.dp, CardBorder, RoundedCornerShape(10.dp))
                                                    .clickable {
                                                        totalSems = if (totalSems == 8) 4 else if (totalSems == 4) 6 else if (totalSems == 6) 2 else 8
                                                        dept.totalSemesters = totalSems
                                                        saveDraft()
                                                    }
                                                    .padding(horizontal = 10.dp, vertical = 14.dp)
                                            ) {
                                                Text(
                                                    text = "$totalSems Sems",
                                                    color = PurpleLight,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }

                                            if (depts.size > 1) {
                                                IconButton(onClick = {
                                                    depts.removeAt(index)
                                                    saveDraft()
                                                }) {
                                                    Icon(Icons.Outlined.Delete, null, tint = RedDanger, modifier = Modifier.size(20.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(16.dp))
                            }

                            Spacer(Modifier.height(20.dp))

                            Button(
                                onClick = {
                                    activeProgIndex = 0
                                    activeDeptIndex = 0
                                    activeSemNum = 1
                                    saveDraft()
                                    currentStep = 3
                                },
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = PurpleAccent)
                            ) {
                                Text("Next: Configure Semester Subjects →", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        3 -> {
                            // ── STEP 3: SEMESTER SUBJECTS INPUT ───────────────
                            val currentProg = selectedPrograms.getOrNull(activeProgIndex) ?: ""
                            val currentDepts = departmentsByProgram[currentProg] ?: emptyList()
                            val currentDept = currentDepts.getOrNull(activeDeptIndex)
                            val totalSemestersForDept = currentDept?.totalSemesters ?: 8

                            val semesterKey = "${currentProg}__${currentDept?.name}__${activeSemNum}"
                            val currentSubjects = subjectsBySemester.getOrPut(semesterKey) {
                                mutableStateListOf("Programming Fundamentals", "Calculus I", "English")
                            }

                            // Program Tabs
                            if (selectedPrograms.size > 1) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    selectedPrograms.forEachIndexed { pIdx, pName ->
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(if (pIdx == activeProgIndex) PurpleAccent else CardBg)
                                                .border(1.dp, if (pIdx == activeProgIndex) PurpleAccent else CardBorder, RoundedCornerShape(10.dp))
                                                .clickable {
                                                    activeProgIndex = pIdx
                                                    activeDeptIndex = 0
                                                    activeSemNum = 1
                                                    saveDraft()
                                                }
                                                .padding(horizontal = 14.dp, vertical = 7.dp)
                                        ) {
                                            Text(pName, color = TextWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                            }

                            // Department Tabs
                            if (currentDepts.size > 1) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    currentDepts.forEachIndexed { dIdx, d ->
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(if (dIdx == activeDeptIndex) PurpleAccent.copy(alpha = 0.25f) else CardBg)
                                                .border(1.dp, if (dIdx == activeDeptIndex) PurpleAccent else CardBorder, RoundedCornerShape(10.dp))
                                                .clickable {
                                                    activeDeptIndex = dIdx
                                                    activeSemNum = 1
                                                    saveDraft()
                                                }
                                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                        ) {
                                            Text(d.name, color = if (dIdx == activeDeptIndex) PurpleLight else TextMuted, fontSize = 12.5.sp)
                                        }
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                            }

                            // Breadcrumb Header
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(CardBg)
                                    .border(1.dp, PurpleAccent.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "$currentProg • ${currentDept?.name}",
                                    color = PurpleLight,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "Sem $activeSemNum of $totalSemestersForDept",
                                    color = TextWhite,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            Spacer(Modifier.height(12.dp))

                            // Direct Semester Jump Chips
                            Text("Jump to Semester:", color = TextMuted, fontSize = 11.5.sp)
                            Spacer(Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                (1..totalSemestersForDept).forEach { sNum ->
                                    val isCurrent = sNum == activeSemNum
                                    val key = "${currentProg}__${currentDept?.name}__${sNum}"
                                    val hasSubjs = (subjectsBySemester[key]?.size ?: 0) > 0

                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isCurrent) PurpleAccent else if (hasSubjs) GreenSuccess.copy(alpha = 0.15f) else FieldBg)
                                            .border(1.dp, if (isCurrent) PurpleAccent else if (hasSubjs) GreenSuccess.copy(alpha = 0.4f) else CardBorder, RoundedCornerShape(8.dp))
                                            .clickable {
                                                activeSemNum = sNum
                                                saveDraft()
                                            }
                                            .padding(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            text = "Sem $sNum${if (hasSubjs && !isCurrent) " ✓" else ""}",
                                            color = if (isCurrent) TextWhite else if (hasSubjs) GreenSuccess else TextMuted,
                                            fontSize = 12.sp,
                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(18.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Semester $activeSemNum Subjects",
                                    color = TextWhite,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                TextButton(onClick = { showBulkPasteDialog = true }) {
                                    Icon(Icons.Default.ContentPaste, null, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Bulk Paste", fontSize = 12.sp)
                                }
                            }

                            // Subjects List
                            currentSubjects.forEachIndexed { sIdx, sName ->
                                var textVal by remember(sName) { mutableStateOf(sName) }

                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = textVal,
                                        onValueChange = {
                                            textVal = it
                                            currentSubjects[sIdx] = it
                                            saveDraft()
                                        },
                                        placeholder = { Text("Subject name (e.g. Data Structures)", color = TextMuted, fontSize = 12.5.sp) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = TextWhite,
                                            unfocusedTextColor = TextWhite,
                                            focusedContainerColor = FieldBg,
                                            unfocusedContainerColor = FieldBg,
                                            focusedBorderColor = PurpleAccent,
                                            unfocusedBorderColor = CardBorder
                                        )
                                    )

                                    if (currentSubjects.size > 1) {
                                        IconButton(onClick = {
                                            currentSubjects.removeAt(sIdx)
                                            saveDraft()
                                        }) {
                                            Icon(Icons.Outlined.Close, null, tint = RedDanger, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(8.dp))

                            OutlinedButton(
                                onClick = {
                                    currentSubjects.add("")
                                    saveDraft()
                                },
                                modifier = Modifier.fillMaxWidth().height(42.dp),
                                shape = RoundedCornerShape(10.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, PurpleAccent.copy(alpha = 0.6f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = PurpleLight)
                            ) {
                                Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("+ Add Another Subject", fontSize = 13.sp)
                            }

                            Spacer(Modifier.height(26.dp))

                            // Navigation buttons for semesters
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (activeSemNum > 1 || activeDeptIndex > 0 || activeProgIndex > 0) {
                                    OutlinedButton(
                                        onClick = {
                                            if (activeSemNum > 1) {
                                                activeSemNum--
                                            } else if (activeDeptIndex > 0) {
                                                activeDeptIndex--
                                                val prevDept = currentDepts[activeDeptIndex]
                                                activeSemNum = prevDept.totalSemesters
                                            } else if (activeProgIndex > 0) {
                                                activeProgIndex--
                                                val prevProg = selectedPrograms[activeProgIndex]
                                                val prevDepts = departmentsByProgram[prevProg] ?: emptyList()
                                                activeDeptIndex = (prevDepts.size - 1).coerceAtLeast(0)
                                                activeSemNum = prevDepts.getOrNull(activeDeptIndex)?.totalSemesters ?: 8
                                            }
                                            saveDraft()
                                        },
                                        modifier = Modifier.weight(1f).height(48.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextWhite)
                                    ) {
                                        Text("Previous", fontSize = 13.5.sp)
                                    }
                                }

                                Button(
                                    onClick = {
                                        saveDraft()
                                        // Move forward
                                        if (activeSemNum < totalSemestersForDept) {
                                            activeSemNum++
                                        } else if (activeDeptIndex < currentDepts.size - 1) {
                                            activeDeptIndex++
                                            activeSemNum = 1
                                        } else if (activeProgIndex < selectedPrograms.size - 1) {
                                            activeProgIndex++
                                            activeDeptIndex = 0
                                            activeSemNum = 1
                                        } else {
                                            currentStep = 4
                                        }
                                    },
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = PurpleAccent)
                                ) {
                                    val isLast = (activeSemNum == totalSemestersForDept) &&
                                            (activeDeptIndex == currentDepts.size - 1) &&
                                            (activeProgIndex == selectedPrograms.size - 1)
                                    Text(if (isLast) "Review & Publish →" else "Next Semester →", fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            // Quick Jump to Review
                            Spacer(Modifier.height(10.dp))
                            TextButton(onClick = {
                                saveDraft()
                                currentStep = 4
                            }) {
                                Text("Skip to Review & Publish (partial ok) →", color = TextMuted, fontSize = 12.sp)
                            }
                        }

                        4 -> {
                            // ── STEP 4: REVIEW & PUBLISH ──────────────────────
                            if (!submitSuccess) {
                                Box(
                                    modifier = Modifier
                                        .size(70.dp)
                                        .clip(CircleShape)
                                        .background(PurpleAccent.copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.CloudUpload, null, tint = PurpleAccent, modifier = Modifier.size(38.dp))
                                }

                                Spacer(Modifier.height(14.dp))

                                Text(
                                    text = "Review & Publish",
                                    color = TextWhite,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "Official class groups will be generated for each subject automatically. You can always add more departments or semesters later.",
                                    color = TextMuted,
                                    fontSize = 13.sp,
                                    textAlign = TextAlign.Center
                                )

                                Spacer(Modifier.height(20.dp))

                                // Summary Stats Card
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(CardBg)
                                        .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
                                        .padding(20.dp)
                                ) {
                                    Text("Curriculum Summary", color = PurpleLight, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.height(12.dp))

                                    selectedPrograms.forEach { prog ->
                                        val depts = departmentsByProgram[prog] ?: emptyList()
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(text = "$prog Degree:", color = TextWhite, fontSize = 13.5.sp)
                                            Text(text = "${depts.size} Department(s)", color = TextMuted, fontSize = 13.sp)
                                        }
                                    }
                                }

                                Spacer(Modifier.height(26.dp))

                                Button(
                                    onClick = {
                                        isSubmitting = true
                                        coroutineScope.launch {
                                            try {
                                                // Build CurriculumSetupRequest
                                                val programInputs = selectedPrograms.map { pName ->
                                                    val depts = departmentsByProgram[pName] ?: emptyList()
                                                    val deptInputs = depts.map { d ->
                                                        val semInputs = (1..d.totalSemesters).map { sNum ->
                                                            val semKey = "${pName}__${d.name}__${sNum}"
                                                            val subjs = subjectsBySemester[semKey] ?: emptyList()
                                                            val subjInputs = subjs.filter { it.isNotBlank() }.map { sTitle ->
                                                                SubjectInput(name = sTitle.trim())
                                                            }
                                                            SemesterInput(semester_num = sNum, subjects = subjInputs)
                                                        }
                                                        DepartmentInput(
                                                            name = d.name.trim(),
                                                            total_semesters = d.totalSemesters,
                                                            semesters = semInputs
                                                        )
                                                    }
                                                    ProgramInput(name = pName.trim(), departments = deptInputs)
                                                }

                                                withContext(Dispatchers.IO) {
                                                    ApiClient.apiService.setupCurriculum(
                                                        CurriculumSetupRequest(
                                                            uni_key = uniKey,
                                                            programs = programInputs
                                                        )
                                                    )
                                                }

                                                // Clean saved draft on publish
                                                sharedPrefs.edit().remove("draft_curriculum_$uniKey").apply()
                                                submitSuccess = true
                                                Toast.makeText(context, "Curriculum Published Successfully!", Toast.LENGTH_LONG).show()
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                                            } finally {
                                                isSubmitting = false
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().height(54.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = PurpleAccent),
                                    enabled = !isSubmitting
                                ) {
                                    if (isSubmitting) {
                                        CircularProgressIndicator(color = TextWhite, strokeWidth = 2.5.dp, modifier = Modifier.size(24.dp))
                                    } else {
                                        Icon(Icons.Default.CloudDone, null, modifier = Modifier.size(20.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Publish Campus Curriculum", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            } else {
                                // Celebration Screen
                                Box(
                                    modifier = Modifier
                                        .size(80.dp)
                                        .clip(CircleShape)
                                        .background(GreenSuccess.copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.CheckCircle, null, tint = GreenSuccess, modifier = Modifier.size(48.dp))
                                }

                                Spacer(Modifier.height(18.dp))

                                Text(
                                    text = "Campus Is Live!",
                                    color = TextWhite,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )

                                Spacer(Modifier.height(8.dp))

                                Text(
                                    text = "All programs, departments, and official subject groups are now online for $uniName.\n\nYour teachers and students can now sign up using your campus key:",
                                    color = TextMuted,
                                    fontSize = 13.5.sp,
                                    textAlign = TextAlign.Center
                                )

                                Spacer(Modifier.height(18.dp))

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(FieldBg)
                                        .border(1.dp, PurpleAccent, RoundedCornerShape(12.dp))
                                        .padding(horizontal = 20.dp, vertical = 12.dp)
                                ) {
                                    Text(
                                        text = uniKey,
                                        color = PurpleLight,
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = 2.sp
                                    )
                                }

                                Spacer(Modifier.height(30.dp))

                                Button(
                                    onClick = onFinish,
                                    modifier = Modifier.fillMaxWidth().height(52.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = PurpleAccent)
                                ) {
                                    Text("Go to Dashboard", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Bulk Paste Dialog
        if (showBulkPasteDialog) {
            AlertDialog(
                onDismissRequest = { showBulkPasteDialog = false },
                title = { Text("Paste Multiple Subjects", color = TextWhite, fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        Text(
                            "Paste subjects separated by commas or new lines. They will all be added to Semester $activeSemNum at once.",
                            color = TextMuted,
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = bulkPasteText,
                            onValueChange = { bulkPasteText = it },
                            placeholder = { Text("e.g. Programming Fundamentals, OOP, Calculus, English", color = TextMuted, fontSize = 12.5.sp) },
                            modifier = Modifier.fillMaxWidth().height(120.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextWhite,
                                unfocusedTextColor = TextWhite,
                                focusedContainerColor = FieldBg,
                                unfocusedContainerColor = FieldBg,
                                focusedBorderColor = PurpleAccent,
                                unfocusedBorderColor = CardBorder
                            )
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val items = bulkPasteText
                                .split(Regex("[,\\n]+"))
                                .map { it.trim() }
                                .filter { it.isNotBlank() }
                            if (items.isNotEmpty()) {
                                val currentProg = selectedPrograms.getOrNull(activeProgIndex) ?: ""
                                val currentDepts = departmentsByProgram[currentProg] ?: emptyList()
                                val currentDept = currentDepts.getOrNull(activeDeptIndex)
                                val key = "${currentProg}__${currentDept?.name}__${activeSemNum}"
                                val sList = subjectsBySemester.getOrPut(key) { mutableStateListOf() }
                                items.forEach { sList.add(it) }
                                saveDraft()
                            }
                            bulkPasteText = ""
                            showBulkPasteDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PurpleAccent)
                    ) {
                        Text("Add All")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showBulkPasteDialog = false }) {
                        Text("Cancel", color = TextMuted)
                    }
                },
                containerColor = CardBg
            )
        }
    }
}
