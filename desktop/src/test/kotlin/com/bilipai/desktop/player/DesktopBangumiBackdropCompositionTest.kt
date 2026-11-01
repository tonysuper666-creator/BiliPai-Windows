package com.bilipai.desktop.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.unit.Density
import com.android.purebilibili.core.ui.blur.ChromeBackdropSource
import com.android.purebilibili.core.ui.blur.rememberChromeBackdropSource
import org.junit.jupiter.api.Test
import top.yukonga.miuix.kmp.blur.drawBackdrop
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class DesktopBangumiBackdropCompositionTest {
    @Test fun recordingBackgroundUpdatesReuseRealLayersAndExitReleasesThem() {
        for (visible in listOf(true, false)) {
            var mounted by mutableStateOf(true)
            var color by mutableStateOf(Color.Red)
            lateinit var source: ChromeBackdropSource
            val created = mutableListOf<GraphicsLayer>()
            val released = mutableListOf<GraphicsLayer>()
            var now = 0L
            val scene = ImageComposeScene(80, 80, Density(1f)) {
                val actual = LocalGraphicsContext.current
                val tracked = remember(actual) {
                    object : GraphicsContext by actual {
                        override fun createGraphicsLayer(): GraphicsLayer = actual.createGraphicsLayer().also { created += it }
                        override fun releaseGraphicsLayer(layer: GraphicsLayer) {
                            released += layer
                            actual.releaseGraphicsLayer(layer)
                        }
                    }
                }
                CompositionLocalProvider(LocalGraphicsContext provides tracked) {
                    if (mounted) {
                        source = rememberChromeBackdropSource(drawContentToScreen = visible, recordingBackground = color)
                        Box(Modifier.fillMaxSize().background(Color.Blue)) {
                            Box(Modifier.fillMaxSize().then(source.modifier))
                            Box(Modifier.fillMaxSize().drawBackdrop(source.backdrop, shape = { RectangleShape }, effects = {}))
                        }
                    }
                }
            }
            fun frames() { repeat(8) { Snapshot.sendApplyNotifications(); now += 20_000_000; scene.render(now).close() } }
            fun assertPixel(expected: Color) {
                val frame = scene.render(now + 1)
                try {
                    val pixel = frame.toComposeImageBitmap().toPixelMap()[40, 40]
                    assertEquals(expected.red, pixel.red, 0.02f)
                    assertEquals(expected.green, pixel.green, 0.02f)
                    assertEquals(expected.blue, pixel.blue, 0.02f)
                    assertEquals(1f, pixel.alpha, 0.02f)
                } finally { frame.close() }
            }
            try {
                frames(); assertPixel(Color.Red)
                assertTrue(source.isReady)
                val original = source
                val originalModifier = source.modifier
                val layers = created.toList()
                assertTrue(layers.isNotEmpty())
                for (next in listOf(Color.Green, Color.Unspecified, Color.Red)) {
                    color = next; frames()
                    assertSame(original, source)
                    assertSame(originalModifier, source.modifier)
                    assertEquals(layers, created)
                    assertTrue(released.isEmpty())
                    assertPixel(if (next == Color.Unspecified) Color.Blue else next)
                }
                mounted = false; frames()
                assertEquals(layers.toSet(), released.toSet())
                assertEquals(layers.size, released.size)
                assertTrue(layers.all { it.isReleased })
                frames()
                assertEquals(layers.size, released.size)
            } finally { scene.close() }
        }
    }
}
