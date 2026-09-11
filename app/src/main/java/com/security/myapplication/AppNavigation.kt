package com.security.myapplication

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.security.myapplication.screens.*
import com.security.myapplication.ui.theme.AppMotion

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val context = androidx.compose.ui.platform.LocalContext.current
    val sharedPrefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    
    val startDest = remember {
        val savedUserId = sharedPrefs.getInt("userId", -1)
        val savedRole = sharedPrefs.getString("role", null)
        val savedToken = sharedPrefs.getString("authToken", null)
        
        if (!savedToken.isNullOrBlank()) {
            com.security.myapplication.network.AuthManager.setToken(savedToken)
        }
        
        com.security.myapplication.network.AuthManager.onUnauthorizedCallback = {
            // OkHttp invokes the interceptor off-main.  Save/navigation/UI work
            // must run on the main queue, after text fields have persisted their
            // drafts in SessionDraftStore.
            mainHandler.post {
                Toast.makeText(
                    context,
                    "Your session expired — please log in again. Your drafts are saved.",
                    Toast.LENGTH_LONG
                ).show()
                com.security.myapplication.models.clearUserSessionData(context, savedUserId)
                sharedPrefs.edit().clear().apply()
                navController.navigate("login") {
                    popUpTo(0) { inclusive = true }
                }
            }
        }
        
        if (savedUserId != -1 && savedRole != null) {
            // NOTE: AppMessageNotificationWatcher.start() is called inside the
            // dashboard composable (below) so we don't call it here again.
            "dashboard/$savedUserId/$savedRole"
        } else {
            "login"
        }
    }

    NavHost(
        navController     = navController,
        startDestination  = startDest,
        enterTransition   = { AppMotion.screenEnter    },
        exitTransition    = { AppMotion.screenExit     },
        popEnterTransition  = { AppMotion.screenPopEnter },
        popExitTransition   = { AppMotion.screenPopExit  }
    ) {
        composable("login") {
            LoginScreen(navController = navController)
        }
        composable("signup") {
            SignupScreen(navController = navController)
        }
        composable("forgot-password") {
            ForgotPasswordScreen(navController = navController)
        }
        composable("dashboard/{userId}/{role}") { backStackEntry ->
            val userIdStr = backStackEntry.arguments?.getString("userId")
            // Fall back to savedUserId if parsing fails or returns 0
            val parsedUserId = userIdStr?.toIntOrNull() ?: 0
            val userId = if (parsedUserId != 0) parsedUserId else sharedPrefs.getInt("userId", -1)

            val roleStr = backStackEntry.arguments?.getString("role")
            val role = if (roleStr != null && roleStr.isNotBlank() && roleStr != "{role}") roleStr else sharedPrefs.getString("role", "Student") ?: "Student"
            val activeMode = com.security.myapplication.storage.SessionModeManager.currentMode(context, userId, role)

            if (userId > 0) {
                com.security.myapplication.notifications.AppMessageNotificationWatcher.start(context, userId)
            }

            when {
                activeMode == com.security.myapplication.storage.SessionModeManager.SIMPLE ->
                    SimpleUserDashboardScreen(userId = userId, role = "Simple User", navController = navController)
                else -> when (role) {
                "Teacher" -> TeacherDashboardScreen(userId = userId, navController = navController)
                "Simple User", "SIMPLE_USER", "SimpleUser" -> SimpleUserDashboardScreen(userId = userId, role = role, navController = navController)
                else -> DashboardScreen(userId = userId, role = role, navController = navController)
                }
            }
        }
        // ── Academic Multi-Tenant University Routes ─────────────────────────
        composable("university-verification") {
            com.security.myapplication.academic.UniversityVerificationScreen(
                currentUserId = sharedPrefs.getInt("userId", -1).takeIf { it > 0 },
                onBack = { navController.popBackStack() },
                onProceedToCurriculum = { key, name ->
                    val encodedName = java.net.URLEncoder.encode(name, "UTF-8")
                    navController.navigate("founder-curriculum-wizard/$key/$encodedName")
                }
            )
        }
        composable("founder-curriculum-wizard/{uniKey}/{uniName}") { backStackEntry ->
            val uniKey = backStackEntry.arguments?.getString("uniKey") ?: ""
            val rawName = backStackEntry.arguments?.getString("uniName") ?: ""
            val uniName = try { java.net.URLDecoder.decode(rawName, "UTF-8") } catch (_: Exception) { rawName }
            com.security.myapplication.academic.FounderCurriculumWizardScreen(
                uniKey = uniKey,
                uniName = uniName,
                onBack = { navController.popBackStack() },
                onFinish = {
                    val userId = sharedPrefs.getInt("userId", -1)
                    val role = sharedPrefs.getString("role", "Student") ?: "Student"
                    if (userId > 0) {
                        navController.navigate("dashboard/$userId/$role") {
                            popUpTo("dashboard/$userId/$role") { inclusive = true }
                        }
                    } else {
                        navController.navigate("login") { popUpTo(0) }
                    }
                }
            )
        }
        composable("teacher-subject-picker/{userId}/{uniKey}") { backStackEntry ->
            val userId = backStackEntry.arguments?.getString("userId")?.toIntOrNull() ?: sharedPrefs.getInt("userId", -1)
            val uniKey = backStackEntry.arguments?.getString("uniKey") ?: ""
            com.security.myapplication.academic.TeacherSubjectPickerScreen(
                currentUserId = userId,
                uniKey = if (uniKey == "none") "" else uniKey,
                onBack = { navController.popBackStack() },
                onAssigned = {
                    navController.navigate("dashboard/$userId/Teacher") {
                        popUpTo("dashboard/$userId/Teacher") { inclusive = true }
                    }
                }
            )
        }
        composable("student-enrollment/{userId}/{uniKey}") { backStackEntry ->
            val userId = backStackEntry.arguments?.getString("userId")?.toIntOrNull() ?: sharedPrefs.getInt("userId", -1)
            val uniKey = backStackEntry.arguments?.getString("uniKey") ?: ""
            com.security.myapplication.academic.StudentEnrollmentScreen(
                currentUserId = userId,
                uniKey = if (uniKey == "none") "" else uniKey,
                onBack = { navController.popBackStack() },
                onEnrolled = {
                    navController.navigate("dashboard/$userId/Student") {
                        popUpTo("dashboard/$userId/Student") { inclusive = true }
                    }
                }
            )
        }
    }
}
