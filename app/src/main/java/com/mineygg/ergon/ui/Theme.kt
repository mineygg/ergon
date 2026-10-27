package com.mineygg.ergon.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.mineygg.ergon.data.ThemeMode

@Immutable
data class ExtraColors(val success: Color, val warning: Color)

val LocalExtra = staticCompositionLocalOf { ExtraColors(Color.Unspecified, Color.Unspecified) }

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4FF),
    onPrimary = Color(0xFF0A1B33),
    primaryContainer = Color(0xFF1B2D4D),
    onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = Color(0xFFB39DFF),
    onSecondary = Color(0xFF1E1240),
    tertiary = Color(0xFF5EEAD4),
    onTertiary = Color(0xFF00201B),
    background = Color(0xFF0B0E14),
    onBackground = Color(0xFFE6E9F0),
    surface = Color(0xFF131822),
    onSurface = Color(0xFFE6E9F0),
    surfaceVariant = Color(0xFF1B2230),
    onSurfaceVariant = Color(0xFF9AA4B5),
    outline = Color(0xFF3A4457),
    outlineVariant = Color(0xFF242C3B),
    error = Color(0xFFFF7B7B),
    onError = Color(0xFF3B0A0A),
    errorContainer = Color(0xFF3A1A1F),
    onErrorContainer = Color(0xFFFFDAD8)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF2F62D6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE6FF),
    onPrimaryContainer = Color(0xFF0A1B33),
    secondary = Color(0xFF6A4FD6),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF0F8F7E),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF5F7FB),
    onBackground = Color(0xFF12161F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF12161F),
    surfaceVariant = Color(0xFFEBEFF6),
    onSurfaceVariant = Color(0xFF586275),
    outline = Color(0xFF9AA4B5),
    outlineVariant = Color(0xFFDDE3EE),
    error = Color(0xFFC62828),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFE3E1),
    onErrorContainer = Color(0xFF410002)
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp)
)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun ClaimTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val extra = if (dark) {
        ExtraColors(Color(0xFF4ADE80), Color(0xFFFBBF24))
    } else {
        ExtraColors(Color(0xFF15803D), Color(0xFFB45309))
    }

    val view = LocalView.current
    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
    }

    CompositionLocalProvider(LocalExtra provides extra) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            shapes = AppShapes,
            content = content
        )
    }
}
