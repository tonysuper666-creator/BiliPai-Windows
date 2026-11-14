@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.android.purebilibili.core.store.LocalPlaylist
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.theme.LocalAppUiStyle
import com.android.purebilibili.feature.audio.player.MusicPlayerUiState
import com.android.purebilibili.feature.audio.screen.MusicPlayerContent
import com.bilipai.desktop.settings.DesktopLiquidTabSettings
import com.bilipai.desktop.settings.LocalDesktopLiquidTabSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.jetbrains.skia.Bitmap
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/** Real compiled compact MusicPlayerContent; isolated settings and inert native/IO ports. */
class DesktopMusicPlayerPagerMotionTest {
    @Test
    fun coverAndLyricsSelectionFollowsClicksRetargetingAndTouch(): Unit = runBlocking {
        DesktopPageTestFixture().use { fixture ->
            val context = DesktopOriginalPlayerSettingsContext(fixture.pluginContext, { true }, { it(); true })
            val platform = object : DesktopOriginalMusicUiPlatform {
                override val context = context
                override val homeSettings = fixture.home.settings.homeSettings
                override val systemReduceMotion = MutableStateFlow(false)
                override val isInBackground = MutableStateFlow(false)
                override val blurEffectsAvailable = false
                override val volume = MutableStateFlow(.5f)
                override val maximumVolumeStep = 100
                override val history = DesktopOriginalMusicStorageBinding(context).history
                override val externalPlaylist: DesktopOriginalExternalPlaylistActions
                    get() = error("unexpected playlist access")
                override fun setVolumeFraction(value: Float) = error("unexpected volume change")
                override suspend fun savePlaylist(playlist: LocalPlaylist) = error("unexpected playlist save")
                override suspend fun loadOwnedArtwork(coverUrl: String): DesktopMusicRaster? {
                    check(coverUrl == "fixture://cover")
                    return DesktopMusicRaster.createBitmap(16, 16, DesktopMusicRaster.Config.ARGB_8888).also {
                        it.setPixels(IntArray(256) { 0xff203040.toInt() }, 0, 16, 0, 0, 16, 16)
                    }
                }
                override suspend fun commitUi(block: () -> Unit) = block()
            }
            var nanos = 0L
            val liquidTabSettings = DesktopLiquidTabSettings(fixture.pluginContext.store)
            val scene = ImageComposeScene(480, 900, Density(1f), coroutineContext = coroutineContext) {
                CompositionLocalProvider(
                    LocalDesktopOriginalMusicUiPlatform provides platform,
                    LocalDesktopOriginalPlayerSettingsContext provides context,
                    LocalDesktopUpstreamAndroidContext provides DesktopOriginalSettingsAndroidContext(context),
                    LocalDesktopLiquidTabSettings provides liquidTabSettings,
                    LocalLifecycleOwner provides fixture.lifecycleOwner,
                    LocalAppUiStyle provides AppUiStyle.MATERIAL3,
                ) {
                    MaterialTheme(colorScheme = darkColorScheme(primary = Color.Magenta, secondaryContainer = Color.Magenta)) {
                        DesktopDetailWindow {
                            MusicPlayerContent(
                                state = MusicPlayerUiState(title = "Motion fixture song", artist = "Fixture artist",
                                    durationMs = 180_000, coverUrl = "fixture://cover"),
                                onBack = { error("unexpected back") }, onPlayPause = { error("unexpected playback") },
                                onSeek = { error("unexpected seek") }, liquidGlassEffectsEnabled = true,
                                lyricsBlurEffectsEnabled = false,
                            )
                        }
                    }
                }
            }
            fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
            fun allNodes() = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
            fun label(node: SemanticsNode, text: String) =
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
            fun tab(text: String) = allNodes().single {
                it.config.getOrNull(SemanticsProperties.Selected) != null && nodes(it).any { child -> label(child, text) }
            }
            fun selected(text: String) = tab(text).config[SemanticsProperties.Selected]
            fun accent(pixel: Int) = (pixel ushr 16 and 255) > 200 &&
                (pixel ushr 8 and 255) < 80 && (pixel and 255) > 200
            var scanY: Int? = null
            val railPositions = mutableListOf<Float>()
            fun coverX(): Float? = allNodes().firstOrNull {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("专辑封面") == true
            }?.positionInRoot?.x
            suspend fun frames(count: Int): List<Float> = buildList {
                repeat(count) {
                    nanos += 1_000_000_000L / 165
                    scene.render(nanos).use { image -> scanY?.let { y ->
                        Bitmap.makeFromImage(image).use { bitmap ->
                            val xs = (0 until 480).filter { accent(bitmap.getColor(it, y)) }
                            assertTrue(xs.isNotEmpty(), "music rail disappeared at $nanos")
                            railPositions += xs.average().toFloat()
                        }
                    } }
                    coverX()?.let { add(it) }
                    yield()
                }
            }
            fun pointer(event: PointerEventType, point: Offset, pressed: Boolean, type: PointerType = PointerType.Mouse) {
                scene.sendPointerEvent(event, point, timeMillis = nanos / 1_000_000,
                    buttons = PointerButtons(isPrimaryPressed = pressed), type = type)
            }
            suspend fun click(text: String) {
                val bounds = tab(text).boundsInRoot
                assertTrue(bounds.width > 10f && bounds.height > 10f && bounds.left >= 0f && bounds.right <= 480f,
                    "music tab must be laid out inside the scene: $text $bounds")
                pointer(PointerEventType.Move, bounds.center, false)
                pointer(PointerEventType.Press, bounds.center, true)
                frames(7)
                pointer(PointerEventType.Release, bounds.center, false)
            }
            try {
                withTimeout(6_000) {
                    while (allNodes().none { label(it, "歌词") }) { frames(2); delay(5) }
                }
                frames(90)
                assertTrue(selected("封面"))
                withTimeout(6_000) {
                    while (coverX() == null) { frames(2); delay(5) }
                }
                val initialX = requireNotNull(coverX()) { "real cover page must be mounted" }
                val first = tab("封面").boundsInRoot
                val last = tab("歌词").boundsInRoot
                val textBottom = nodes(tab("封面")).single { label(it, "封面") }.boundsInRoot.bottom.toInt()
                nanos += 1_000_000_000L / 165
                scene.render(nanos).use { image -> Bitmap.makeFromImage(image).use { bitmap ->
                    val rows = (textBottom until first.bottom.toInt()).map { y ->
                        y to (first.left.toInt() until last.right.toInt()).count { accent(bitmap.getColor(it, y)) }
                    }
                    val best = rows.maxBy { it.second }
                    assertTrue(best.second > 10, "no visible music indicator: $rows")
                    scanY = best.first
                } }
                frames(2)
                assertTrue(abs(railPositions.last() - first.center.x) < 3f)
                val railStart = railPositions.size
                click("歌词")
                val early = frames(2)
                assertTrue(selected("歌词"), "the clicked music page must be selected before the cover crosses the midpoint")
                val travel = early + frames(90)
                assertTrue(travel.any { it < initialX - 20f && it > initialX - 400f },
                    "real music cover did not pass intermediate page positions: $travel")
                assertTrue(travel.zipWithNext().all { (a, b) -> b <= a + 1f }, "cover moved backwards: $travel")
                assertTrue(travel.zipWithNext().all { (a, b) -> abs(b - a) < 240f }, "cover jumped half a page: $travel")
                val rail = railPositions.drop(railStart)
                val spacing = last.center.x - first.center.x
                assertTrue(rail.any { it > first.center.x + spacing * .2f && it < last.center.x - spacing * .2f },
                    "music indicator skipped intermediate positions: $rail")
                assertTrue(rail.zipWithNext().all { (a, b) -> b >= a - 1f }, "music indicator moved backwards: $rail")
                assertTrue(rail.zipWithNext().all { (a, b) -> abs(b - a) < spacing * .5f }, "music indicator jumped: $rail")
                assertTrue(abs(rail.last() - last.center.x) < 3f)
                assertTrue(allNodes().any { label(it, "未找到匹配歌词") && it.boundsInRoot.width > 20f })

                click("封面"); frames(8)
                click("歌词"); frames(2)
                assertTrue(selected("歌词"), "latest music click must own selection during reversal")
                frames(90)
                assertTrue(abs(railPositions.last() - last.center.x) < 3f)
                assertTrue(allNodes().any { label(it, "未找到匹配歌词") && it.boundsInRoot.width > 20f })

                pointer(PointerEventType.Press, Offset(80f, 260f), true, PointerType.Touch)
                frames(2)
                val swipe = mutableListOf<Float>()
                repeat(10) { step ->
                    pointer(PointerEventType.Move, Offset(80f + (step + 1) * 35f, 260f), true, PointerType.Touch)
                    swipe += frames(2)
                }
                assertTrue(swipe.any { it < initialX - 20f && it > initialX - 400f },
                    "touch did not move the actual music pager: $swipe")
                pointer(PointerEventType.Release, Offset(430f, 260f), false, PointerType.Touch)
                frames(90)
                assertTrue(selected("封面"))
                assertTrue(abs(requireNotNull(coverX()) - initialX) < 2f, "music pager did not settle back on the cover")
                assertTrue(abs(railPositions.last() - first.center.x) < 3f, "music indicator did not settle on cover")
            } finally { scene.close() }
        }
    }
}
