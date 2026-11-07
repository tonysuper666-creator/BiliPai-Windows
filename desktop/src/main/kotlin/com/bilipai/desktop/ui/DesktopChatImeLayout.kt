package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier

/** Shared chat content applies the IME inset to the message list and composer together. */
internal fun Modifier.desktopChatImePadding(imeInsets: WindowInsets = WindowInsets.ime): Modifier =
    windowInsetsPadding(imeInsets)
