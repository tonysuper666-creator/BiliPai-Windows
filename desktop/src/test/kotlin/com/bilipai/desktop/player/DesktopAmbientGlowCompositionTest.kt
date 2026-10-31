package com.android.purebilibili.feature.video.ambient

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.android.purebilibili.core.ui.LocalComponentMotionEnabled
import com.android.purebilibili.core.ui.LocalSystemReduceMotion
import com.android.purebilibili.core.ui.LocalVisualWorkAllowed
import com.bilipai.desktop.ui.DesktopAmbientBitmap
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class DesktopAmbientGlowCompositionTest {
    @Test fun reducedMotionDrawsTheFrameAndHiddenPageRemovesTheHalo() = runBlocking {
        verifyVisibility(reduceMotion = true, componentMotion = true)
    }

    @Test fun disabledComponentMotionStillDrawsAndLifecycleStopsTheHalo() = runBlocking {
        verifyVisibility(reduceMotion = false, componentMotion = false)
    }

    private suspend fun verifyVisibility(reduceMotion: Boolean, componentMotion: Boolean) {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
            override val lifecycle: Lifecycle get() = registry
        }
        var allowed by mutableStateOf(true)
        val image = DesktopAmbientBitmap.createBitmap(IntArray(64) { 0xffff0000.toInt() }, 8, 8,
            DesktopAmbientBitmap.Config.ARGB_8888).image()
        val presentation = AmbientPresentation().apply {
            current = AmbientRenderFrame(image, image, atMs = 100L)
            visible = true
            aspectRatio = 1f
        }
        val scene = ImageComposeScene(200, 100, Density(1f)) {
            val actual = LocalWindowInfo.current
            val focused = remember(actual) { object : WindowInfo by actual { override val isWindowFocused = true } }
            CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalWindowInfo provides focused,
                LocalVisualWorkAllowed provides allowed, LocalSystemReduceMotion provides reduceMotion,
                LocalComponentMotionEnabled provides componentMotion) {
                MaterialTheme { PlayerAmbientGlow(presentation, fullscreen = true, Modifier.fillMaxSize()) }
            }
        }
        suspend fun pixels(): Pair<Int, Int> {
            repeat(5) { scene.render().close(); delay(10) }
            val png = scene.render().use { it.encodeToData(EncodedImageFormat.PNG)!!.use { data -> data.bytes } }
            val frame = ImageIO.read(ByteArrayInputStream(png))
            var outside = 0
            var inside = 0
            for (y in 0 until 100) for (x in 0 until 200) {
                if (frame.getRGB(x, y) ushr 24 != 0) {
                    if (x in 50 until 150) inside++ else outside++
                }
            }
            return outside to inside
        }
        try {
            val visible = pixels()
            assertTrue(visible.first > 0, "actual native halo must draw without animated progress")
            assertEquals(0, visible.second, "halo must not paint over the video")
            allowed = false
            assertEquals(0 to 0, pixels(), "retained hidden page must stop drawing")
            allowed = true
            assertTrue(pixels().first > 0, "reactivation must render again")
            owner.registry.currentState = Lifecycle.State.CREATED
            assertEquals(0 to 0, pixels(), "stopped lifecycle must stop drawing")
        } finally { scene.close(); owner.registry.currentState = Lifecycle.State.DESTROYED }
    }
}
