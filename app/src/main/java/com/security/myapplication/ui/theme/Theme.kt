package com.security.myapplication.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80,
    background = Color(0xFF0D0A22),
    surface = Color(0xFF0D0A22)
)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    // The product UI is intentionally purple/dark; do not imply a light-mode
    // branch exists until a complete light palette and screen audit are added.
    val colorScheme = DarkColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.foundation.LocalOverscrollConfiguration provides null,
            content = content
        )
    }
}
