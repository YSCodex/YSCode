// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

enum class ThemeMode {
    DARK, AMOLED, LIGHT
}

@Immutable
data class YSCodeSemanticColors(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val border: Color,
    val text: Color,
    val textDim: Color,
    val accent: Color,
    val info: Color,
    val success: Color,
    val danger: Color,
    val warn: Color,
    val diffAddBg: Color,
    val diffAddWord: Color,
    val diffRemoveBg: Color,
    val diffRemoveWord: Color,
    val isDark: Boolean
)

val LocalYSCodeColors = staticCompositionLocalOf {
    YSCodeSemanticColors(
        bg = YSCodePalette.DarkBg,
        surface = YSCodePalette.DarkSurface,
        surface2 = YSCodePalette.DarkSurface2,
        border = YSCodePalette.DarkBorder,
        text = YSCodePalette.DarkText,
        textDim = YSCodePalette.DarkTextDim,
        accent = YSCodePalette.Accent,
        info = YSCodePalette.Accent2,
        success = YSCodePalette.Success,
        danger = YSCodePalette.Danger,
        warn = YSCodePalette.Warn,
        diffAddBg = YSCodePalette.DiffAddBgDark,
        diffAddWord = YSCodePalette.DiffAddWordDark,
        diffRemoveBg = YSCodePalette.DiffRemoveBgDark,
        diffRemoveWord = YSCodePalette.DiffRemoveWordDark,
        isDark = true
    )
}

private val DarkColorScheme = darkColorScheme(
    primary = YSCodePalette.Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3C2219),
    onPrimaryContainer = Color(0xFFFFDBD0),
    secondary = YSCodePalette.Accent2,
    onSecondary = Color(0xFF0E1B3A),
    secondaryContainer = Color(0xFF1F2B47),
    onSecondaryContainer = Color(0xFFDCE4FF),
    tertiary = YSCodePalette.Success,
    background = YSCodePalette.DarkBg,
    onBackground = YSCodePalette.DarkText,
    surface = YSCodePalette.DarkSurface,
    onSurface = YSCodePalette.DarkText,
    surfaceVariant = YSCodePalette.DarkSurface2,
    onSurfaceVariant = YSCodePalette.DarkTextDim,
    outline = YSCodePalette.DarkBorder,
    outlineVariant = YSCodePalette.DarkBorder,
    error = YSCodePalette.Danger,
    onError = Color.White
)

private val AmoledColorScheme = DarkColorScheme.copy(
    background = YSCodePalette.AmoledBg,
    surface = YSCodePalette.AmoledSurface,
    surfaceVariant = YSCodePalette.AmoledSurface2,
    outline = YSCodePalette.AmoledBorder,
    outlineVariant = YSCodePalette.AmoledBorder
)

private val LightColorScheme = lightColorScheme(
    primary = YSCodePalette.AccentDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE4DB),
    onPrimaryContainer = Color(0xFF47180B),
    secondary = Color(0xFF3E61D6),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3EAFF),
    onSecondaryContainer = Color(0xFF122359),
    tertiary = YSCodePalette.Success,
    background = YSCodePalette.LightBg,
    onBackground = YSCodePalette.LightText,
    surface = YSCodePalette.LightSurface,
    onSurface = YSCodePalette.LightText,
    surfaceVariant = YSCodePalette.LightSurface2,
    onSurfaceVariant = YSCodePalette.LightTextDim,
    outline = YSCodePalette.LightBorder,
    outlineVariant = YSCodePalette.LightBorder,
    error = YSCodePalette.Danger,
    onError = Color.White
)

val YSCodeShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp)
)

@Composable
fun YSCodeTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val isDark = themeMode != ThemeMode.LIGHT
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (isDark) {
                val dyn = dynamicDarkColorScheme(context)
                if (themeMode == ThemeMode.AMOLED) dyn.copy(background = Color.Black, surface = Color(0xFF0A0A0C))
                else dyn
            } else {
                dynamicLightColorScheme(context)
            }
        }
        themeMode == ThemeMode.AMOLED -> AmoledColorScheme
        themeMode == ThemeMode.DARK -> DarkColorScheme
        else -> LightColorScheme
    }

    val semantic = when (themeMode) {
        ThemeMode.DARK -> YSCodeSemanticColors(
            bg = YSCodePalette.DarkBg,
            surface = YSCodePalette.DarkSurface,
            surface2 = YSCodePalette.DarkSurface2,
            border = YSCodePalette.DarkBorder,
            text = YSCodePalette.DarkText,
            textDim = YSCodePalette.DarkTextDim,
            accent = colorScheme.primary,
            info = YSCodePalette.Accent2,
            success = YSCodePalette.Success,
            danger = YSCodePalette.Danger,
            warn = YSCodePalette.Warn,
            diffAddBg = YSCodePalette.DiffAddBgDark,
            diffAddWord = YSCodePalette.DiffAddWordDark,
            diffRemoveBg = YSCodePalette.DiffRemoveBgDark,
            diffRemoveWord = YSCodePalette.DiffRemoveWordDark,
            isDark = true
        )
        ThemeMode.AMOLED -> YSCodeSemanticColors(
            bg = YSCodePalette.AmoledBg,
            surface = YSCodePalette.AmoledSurface,
            surface2 = YSCodePalette.AmoledSurface2,
            border = YSCodePalette.AmoledBorder,
            text = YSCodePalette.DarkText,
            textDim = YSCodePalette.DarkTextDim,
            accent = colorScheme.primary,
            info = YSCodePalette.Accent2,
            success = YSCodePalette.Success,
            danger = YSCodePalette.Danger,
            warn = YSCodePalette.Warn,
            diffAddBg = YSCodePalette.DiffAddBgDark,
            diffAddWord = YSCodePalette.DiffAddWordDark,
            diffRemoveBg = YSCodePalette.DiffRemoveBgDark,
            diffRemoveWord = YSCodePalette.DiffRemoveWordDark,
            isDark = true
        )
        ThemeMode.LIGHT -> YSCodeSemanticColors(
            bg = YSCodePalette.LightBg,
            surface = YSCodePalette.LightSurface,
            surface2 = YSCodePalette.LightSurface2,
            border = YSCodePalette.LightBorder,
            text = YSCodePalette.LightText,
            textDim = YSCodePalette.LightTextDim,
            accent = colorScheme.primary,
            info = colorScheme.secondary,
            success = YSCodePalette.Success,
            danger = YSCodePalette.Danger,
            warn = YSCodePalette.Warn,
            diffAddBg = YSCodePalette.DiffAddBgLight,
            diffAddWord = YSCodePalette.DiffAddWordLight,
            diffRemoveBg = YSCodePalette.DiffRemoveBgLight,
            diffRemoveWord = YSCodePalette.DiffRemoveWordLight,
            isDark = false
        )
    }

    CompositionLocalProvider(LocalYSCodeColors provides semantic) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = YSCodeTypography,
            shapes = YSCodeShapes,
            content = content
        )
    }
}
