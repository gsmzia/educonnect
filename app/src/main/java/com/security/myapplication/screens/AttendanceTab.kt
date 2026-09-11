package com.security.myapplication.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.security.myapplication.models.Attendance
import com.security.myapplication.models.AttendanceRequest
import com.security.myapplication.models.User
import com.security.myapplication.network.ApiClient
import com.security.myapplication.ui.theme.pumpBounceScroll
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun AttendanceTab(groupId: Int, role: String) {
    var students by remember { mutableStateOf<List<User>>(emptyList()) }
    var attendanceRecords by remember { mutableStateOf<List<Attendance>>(emptyList()) }
    val coroutineScope = rememberCoroutineScope()
    
    val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    LaunchedEffect(groupId) {
        if (groupId != 0) {
            try {
                if (role == "Teacher") {
                    students = ApiClient.apiService.getGroupStudents(groupId)
                }
                attendanceRecords = ApiClient.apiService.getAttendance(groupId, today)
            } catch (e: Exception) {
                // handle error
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Attendance for $today", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(16.dp))

        if (role == "Teacher") {
            if (students.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                    Text("No students have joined this group yet.", color = MaterialTheme.colorScheme.secondary)
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f).pumpBounceScroll()) {
                    items(students) { student ->
                        val record = attendanceRecords.find { it.student_id == student.id }
                        val status = record?.status ?: "Not Marked"
                        
                        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(
                                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                            Text(student.name)
                            Row {
                                Button(
                                    onClick = {
                                        coroutineScope.launch {
                                            try {
                                                val res = ApiClient.apiService.markAttendance(
                                                    AttendanceRequest(student.id, groupId, today, "Present")
                                                )
                                                attendanceRecords = attendanceRecords.filter { it.student_id != student.id } + res
                                            } catch (e: Exception) { }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (status == "Present") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                                    )
                                ) { Text("Present") }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        coroutineScope.launch {
                                            try {
                                                val res = ApiClient.apiService.markAttendance(
                                                    AttendanceRequest(student.id, groupId, today, "Absent")
                                                )
                                                attendanceRecords = attendanceRecords.filter { it.student_id != student.id } + res
                                            } catch (e: Exception) { }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (status == "Absent") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
                                    )
                                ) { Text("Absent") }
                            }
                        }
                    }
                }
            }
            }
        } else {
            // Student View
            Text("Your attendance today is:")
            // Note: In a real app we would filter by the student's ID, but for simplicity we assume the records fetched include it
            LazyColumn {
                items(attendanceRecords) { record ->
                    Text("Status: ${record.status}")
                }
            }
        }
    }
}
