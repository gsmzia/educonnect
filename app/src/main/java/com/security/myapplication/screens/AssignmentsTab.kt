package com.security.myapplication.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.security.myapplication.models.Assignment
import com.security.myapplication.network.ApiClient
import com.security.myapplication.ui.theme.pumpBounceScroll
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

@Composable
fun AssignmentsTab(groupId: Int, role: String) {
    var assignments by remember { mutableStateOf<List<Assignment>>(emptyList()) }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(groupId) {
        if (groupId != 0) {
            try {
                assignments = ApiClient.apiService.getAssignments(groupId)
            } catch (e: Exception) {
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        if (role == "Teacher") {
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Upload New Assignment", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = {
                        if (title.isNotEmpty() && groupId != 0) {
                            coroutineScope.launch {
                                try {
                                    val groupIdBody = groupId.toString().toRequestBody("text/plain".toMediaTypeOrNull())
                                    val titleBody = title.toRequestBody("text/plain".toMediaTypeOrNull())
                                    val descBody = description.toRequestBody("text/plain".toMediaTypeOrNull())
                                    
                                    // Mocking file upload for simplicity
                                    val fileBody = "Dummy PDF Content".toRequestBody("application/pdf".toMediaTypeOrNull())
                                    val filePart = MultipartBody.Part.createFormData("file", "assignment.pdf", fileBody)
                                    
                                    val newAssign = ApiClient.apiService.uploadAssignment(groupIdBody, titleBody, descBody, filePart)
                                    assignments = assignments + newAssign
                                    title = ""
                                    description = ""
                                } catch (e: Exception) {}
                            }
                        }
                    }) {
                        Text("Upload")
                    }
                }
            }
        }

        Text("Assignments", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        
        if (assignments.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text("No assignments uploaded yet.", color = MaterialTheme.colorScheme.secondary)
            }
        } else {
            LazyColumn(modifier = Modifier.pumpBounceScroll()) {
                items(assignments) { assignment ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(assignment.title, style = MaterialTheme.typography.titleMedium)
                            Text(assignment.description)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("File: ${assignment.file_url ?: "None"}", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}
