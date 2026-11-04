package com.tsproxy.android.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** 界面主题，持久化为 [id]。 */
enum class ThemeMode(val id: String, val label: String, val description: String) {
    SYSTEM("system", "跟随系统", "随系统深浅色自动切换"),
    LIGHT("light", "浅色", "始终使用浅色界面"),
    DARK("dark", "黑暗", "始终使用深色界面");

    companion object {
        fun fromId(id: String?): ThemeMode =
            entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF2E5BFF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDDE4FF),
    onPrimaryContainer = Color(0xFF0A1F63),
    inversePrimary = Color(0xFF9DB2FF),
    secondary = Color(0xFF5A5F72),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE7E9F2),
    onSecondaryContainer = Color(0xFF171B26),
    tertiary = Color(0xFF117A65),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFB8F1E1),
    onTertiaryContainer = Color(0xFF00201A),
    background = Color(0xFFF7F8FB),
    onBackground = Color(0xFF11141A),
    surface = Color(0xFFF7F8FB),
    onSurface = Color(0xFF11141A),
    surfaceVariant = Color(0xFFE6E9F0),
    onSurfaceVariant = Color(0xFF474E5C),
    surfaceTint = Color(0xFF2E5BFF),
    inverseSurface = Color(0xFF2C3037),
    inverseOnSurface = Color(0xFFF0F1F5),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFF7A8290),
    outlineVariant = Color(0xFFC7CCD6),
    scrim = Color(0xFF000000)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFAFC3FF),
    onPrimary = Color(0xFF0A1F63),
    primaryContainer = Color(0xFF1F377F),
    onPrimaryContainer = Color(0xFFDDE4FF),
    inversePrimary = Color(0xFF2E5BFF),
    secondary = Color(0xFFC0C5D6),
    onSecondary = Color(0xFF2A2F3B),
    secondaryContainer = Color(0xFF383E4C),
    onSecondaryContainer = Color(0xFFDDE1EC),
    tertiary = Color(0xFF74D9C1),
    onTertiary = Color(0xFF00382D),
    tertiaryContainer = Color(0xFF005143),
    onTertiaryContainer = Color(0xFFB8F1E1),
    background = Color(0xFF0E1117),
    onBackground = Color(0xFFE3E6ED),
    surface = Color(0xFF0E1117),
    onSurface = Color(0xFFE3E6ED),
    surfaceVariant = Color(0xFF1B2028),
    onSurfaceVariant = Color(0xFFA9B1C0),
    surfaceTint = Color(0xFFAFC3FF),
    inverseSurface = Color(0xFFE3E6ED),
    inverseOnSurface = Color(0xFF1B2028),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8A93A3),
    outlineVariant = Color(0xFF39414E),
    scrim = Color(0xFF000000)
)

@Composable
fun TsProxyTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (dark) DarkColors else LightColors
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = colors.background.toArgb()
            window.navigationBarColor = colors.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    MaterialTheme(colorScheme = colors, content = content)
}
