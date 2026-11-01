package com.android.purebilibili.feature.video.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.*
import com.android.purebilibili.core.ui.AppThemeConfig
import com.android.purebilibili.core.ui.LocalAppThemeConfig
import com.android.purebilibili.core.ui.LocalComponentMotionEnabled
import com.android.purebilibili.core.ui.blur.LocalAppGlassContainer
import com.android.purebilibili.core.ui.components.LocalAppGlassMenuBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import org.junit.jupiter.api.Test
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class DesktopPlayerGlassDockCompositionTest {
    @Test
    fun realShellKeepsContentBoundsAndClickWithAndWithoutBackdrop() {
        for (hasBackdrop in listOf(false, true)) {
            val glass = mutableStateOf(true)
            var size = IntSize.Zero
            var clicks = 0
            var material = false
            var menuBackdrop = false
            val scene = ImageComposeScene(160, 100, Density(1f)) {
                MaterialTheme {
                    val backdrop = rememberLayerBackdrop(onDraw = { drawRect(Color.Blue) })
                    CompositionLocalProvider(
                        LocalAppThemeConfig provides AppThemeConfig(liquidGlassEnabled = glass.value),
                        LocalComponentMotionEnabled provides false,
                        LocalPlayerPageBackdrop provides backdrop.takeIf { hasBackdrop },
                    ) {
                        PlayerControlsBackdropHost(enabled = true) {
                            menuBackdrop = LocalAppGlassMenuBackdrop.current != null
                            PlayerControlGlassDock(enabled = true, modifier = Modifier.size(80.dp, 48.dp),
                                minimumReadabilityScrimAlpha = 0.5f) {
                                material = LocalAppGlassContainer.current != null
                                Box(Modifier.fillMaxSize().background(Color.Red)
                                    .onGloballyPositioned { size = it.size }.clickable { clicks++ })
                            }
                        }
                    }
                }
            }
            fun frames() { repeat(12) { Snapshot.sendApplyNotifications(); scene.render().close(); Thread.sleep(5) } }
            fun click() {
                scene.sendPointerEvent(PointerEventType.Press, Offset(40f, 24f), buttons = PointerButtons(isPrimaryPressed = true))
                scene.sendPointerEvent(PointerEventType.Release, Offset(40f, 24f), buttons = PointerButtons())
                frames()
            }
            try {
                frames()
                assertEquals(IntSize(80, 48), size)
                assertEquals(hasBackdrop, material)
                assertEquals(hasBackdrop, menuBackdrop)
                assertEquals(0, clicks)
                click(); assertEquals(1, clicks)
                glass.value = false; frames()
                assertFalse(material); assertFalse(menuBackdrop)
                assertEquals(IntSize(80, 48), size)
                click(); assertEquals(2, clicks)
            } finally { scene.close() }
        }
    }
}
