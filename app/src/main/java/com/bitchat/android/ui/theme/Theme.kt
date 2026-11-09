package com.bitchat.android.ui.theme

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsetsController
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView

// Standard UI semantics live in Material so stock components and custom Bitchat composables
// share one source of truth. LocalBitchatPalette below only supplies app-specific extra colors.
// Faro: negro puro (ahorra batería en pantallas OLED) con el ámbar del icono como acento.
// Neutros ligeramente cálidos en lugar de los verdes de terminal de bitchat.
internal val DarkBitchatColorScheme = darkColorScheme(
    primary = Color(0xFFFFB300),
    onPrimary = Color(0xFF1A1205),
    primaryContainer = Color(0xFF3A2A00),
    onPrimaryContainer = Color(0xFFFFE08A),
    secondary = Color(0xFF7CC4FF),
    onSecondary = Color(0xFF00203A),
    secondaryContainer = Color(0xFF0E2F4A),
    onSecondaryContainer = Color(0xFFCFE6FF),
    tertiary = DarkBitchatPalette.accentOrange,
    onTertiary = Color(0xFF1A1205),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF5F2EA),
    surface = Color(0xFF121214),
    onSurface = Color(0xFFF5F2EA),
    surfaceVariant = Color(0xFF1C1C1F),
    onSurfaceVariant = Color(0xFFA19D94),
    outline = Color(0xFF2E2E33),
    outlineVariant = Color(0xFF232327),
    error = Color(0xFFFF6B5E),
    onError = Color.Black
)

internal val LightBitchatColorScheme = lightColorScheme(
    primary = Color(0xFFB86E00),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE3A8),
    onPrimaryContainer = Color(0xFF2E1D00),
    secondary = Color(0xFF0063B1),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD6E9FF),
    onSecondaryContainer = Color(0xFF002C5C),
    tertiary = LightBitchatPalette.accentOrange,
    onTertiary = Color.White,
    background = Color(0xFFFBF8F2),
    onBackground = Color(0xFF1C1A16),
    surface = Color(0xFFF3EFE7),
    onSurface = Color(0xFF1C1A16),
    surfaceVariant = Color(0xFFEAE5DB),
    onSurfaceVariant = Color(0xFF5E5A52),
    outline = Color(0xFFD6D0C4),
    outlineVariant = Color(0xFFE4DFD5),
    error = Color(0xFFC62828),
    onError = Color.White
)

@Composable
fun BitchatTheme(
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit
) {
    // App-level override from ThemePreferenceManager
    val themePref by ThemePreferenceManager.themeFlow.collectAsState(initial = ThemePreference.System)
    val shouldUseDark = when (darkTheme) {
        true -> true
        false -> false
        null -> when (themePref) {
            ThemePreference.Dark -> true
            ThemePreference.Light -> false
            ThemePreference.System -> isSystemInDarkTheme()
        }
    }

    val colorScheme = if (shouldUseDark) DarkBitchatColorScheme else LightBitchatColorScheme
    val palette = if (shouldUseDark) DarkBitchatPalette else LightBitchatPalette

    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.setSystemBarsAppearance(
                    if (!shouldUseDark) WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS else 0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                )
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = if (!shouldUseDark) {
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                } else 0
            }
            window.navigationBarColor = colorScheme.background.toArgb()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
        }
    }

    CompositionLocalProvider(LocalBitchatPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
