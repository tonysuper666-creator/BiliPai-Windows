package com.bilipai.desktop.popupfixture
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
@Composable
internal fun legacyPopup(surfaceSize: IntSize, content: @Composable () -> Unit) {
    if (surfaceSize.width <= 0 || surfaceSize.height <= 0) return
    val density = LocalDensity.current
    Popup(alignment = Alignment.TopStart, properties = PopupProperties(focusable = false, clippingEnabled = false)) {
        Box(Modifier.size(with(density) { surfaceSize.width.toDp() }, with(density) { surfaceSize.height.toDp() })) { content() }
    }
}
