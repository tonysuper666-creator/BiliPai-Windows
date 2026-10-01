// OriginalSource: app/src/main/java/com/android/purebilibili/core/ui/components/LiquidDockViewport.kt
// OriginalSHA256: 295ca5d2cfbc6c1d4645ccf464b773a9099cef6bbf38528bc662f181d6748152
package com.android.purebilibili.core.ui.components

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.theme.LocalAppUiStyle
import com.android.purebilibili.core.ui.AppShapes
import com.android.purebilibili.core.ui.ContainerLevel
import com.android.purebilibili.core.ui.LocalAppThemeConfig

/**
 * Keep the visible dock viewport rounded for every chrome mode.
 *
 * A long liquid rail is wider than the viewport that hosts it. Its shared renderer draws a
 * fixed capsule behind the moving content; this clip keeps labels and the indicator inside
 * that visible capsule instead of leaking across its rounded ends.
 */
@Composable
internal fun Modifier.liquidDockViewport(): Modifier {
    val uiStyle = LocalAppUiStyle.current
    val liquidGlassEnabled = LocalAppThemeConfig.current.liquidGlassEnabled
    val shape = if (liquidGlassEnabled) {
        CircleShape
    } else if (uiStyle == AppUiStyle.MIUIX) {
        AppShapes.container(ContainerLevel.Card)
    } else {
        CircleShape
    }
    return this.clip(shape)
}
