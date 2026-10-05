@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.LocalAwtWindow
import androidx.compose.ui.unit.IntSize
import com.android.purebilibili.core.events.BrandSuccessFeedback
import kotlinx.coroutines.delay
import javax.swing.RootPaneContainer

/** Same Root carrier, not a second native pipeline. All pixels are decorative,
 * native mouse-through/no-activate requirements remain the existing ones. */
@Composable
internal fun DesktopBrandSuccessFrame(
    event: BrandSuccessFeedback,
    dismiss: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val window = LocalAwtWindow.current ?: return
    val anchor = (window as? RootPaneContainer)?.contentPane ?: return
    val events = LocalDesktopBrandSuccessEvents.current
    val current = { events.isCurrent(event) }
    var everAvailable by remember(event) { mutableStateOf(false) }
    // The original host retains its 2600ms final guard. This local bounded
    // presentation observer retires a source/account before that deadline.
    LaunchedEffect(event) {
        while (current()) delay(16L)
        dismiss()
    }
    DesktopDecorativeBrandSuccessPopup(IntSize(anchor.width, anchor.height), anchor,
        event, current, { _, ready -> if (ready) everAvailable = true }, dismiss) { available ->
        if ((available || everAvailable) && current()) Box(Modifier.fillMaxSize(), content = content)
    }
}
