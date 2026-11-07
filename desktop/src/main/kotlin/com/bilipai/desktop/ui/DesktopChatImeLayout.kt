package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection

internal val LocalDesktopChatImeInsets = staticCompositionLocalOf<WindowInsets?> { null }

/** Shared chat content applies the IME inset to the message list and composer together. */
@Composable
internal fun Modifier.desktopChatImePadding(): Modifier =
    windowInsetsPadding(LocalDesktopChatImeInsets.current ?: WindowInsets.ime)

/** Apply scaffold padding, consume it, then apply IME insets to the shared chat content. */
@Composable
internal fun Modifier.desktopChatContentInsets(paddingValues: PaddingValues): Modifier {
    val layoutDirection = LocalLayoutDirection.current
    return padding(
        start = paddingValues.calculateStartPadding(layoutDirection),
        end = paddingValues.calculateEndPadding(layoutDirection),
        bottom = paddingValues.calculateBottomPadding(),
    ).consumeWindowInsets(paddingValues).desktopChatImePadding()
}
