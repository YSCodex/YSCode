// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// Core Design Tokens from 03_UIUX.md
object YSCodePalette {
    // Dark Theme (Default)
    val DarkBg = Color(0xFF0E0F11)
    val DarkSurface = Color(0xFF16181B)
    val DarkSurface2 = Color(0xFF1E2125)
    val DarkBorder = Color(0xFF2A2E33)
    val DarkText = Color(0xFFE8EAED)
    val DarkTextDim = Color(0xFF9AA0A6)

    // AMOLED Theme
    val AmoledBg = Color(0xFF000000)
    val AmoledSurface = Color(0xFF0B0C0E)
    val AmoledSurface2 = Color(0xFF14161A)
    val AmoledBorder = Color(0xFF22252A)

    // Light Theme
    val LightBg = Color(0xFFF7F7F8)
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurface2 = Color(0xFFECEEF2)
    val LightBorder = Color(0xFFDADCE0)
    val LightText = Color(0xFF1A1C1E)
    val LightTextDim = Color(0xFF5F6368)

    // Brand & Semantic Accents
    val Accent = Color(0xFFD97757)
    val AccentDark = Color(0xFFC26142)
    val Accent2 = Color(0xFF7C9CFF)
    val Success = Color(0xFF4CAF7A)
    val Danger = Color(0xFFE5534B)
    val Warn = Color(0xFFE0A93B)

    // Diff highlights
    val DiffAddBgDark = Color(0x264CAF7A)
    val DiffAddWordDark = Color(0x4D4CAF7A)
    val DiffRemoveBgDark = Color(0x26E5534B)
    val DiffRemoveWordDark = Color(0x4DE5534B)

    val DiffAddBgLight = Color(0x224CAF7A)
    val DiffAddWordLight = Color(0x444CAF7A)
    val DiffRemoveBgLight = Color(0x22E5534B)
    val DiffRemoveWordLight = Color(0x44E5534B)
}
