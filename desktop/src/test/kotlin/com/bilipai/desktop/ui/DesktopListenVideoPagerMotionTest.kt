@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.theme.LocalAppUiStyle
import com.android.purebilibili.feature.audio.library.ListenVideoSection
import com.android.purebilibili.feature.audio.screen.ListenVideoScreen
import com.android.purebilibili.feature.audio.viewmodel.ListenVideoUiState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.jetbrains.skia.Bitmap
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Observe the actual page's selection semantics and rendered rail, without exposing its private PagerState. */
class DesktopListenVideoPagerMotionTest {
    @Test
    fun clickedDestinationStaysSelectedWhileTheVisibleRailTravelsAndReverses(): Unit = runBlocking {
        DesktopPageTestFixture().use { fixture ->
            var nanos = 0L
            val settled = mutableListOf<ListenVideoSection>()
            val scene = ImageComposeScene(800, 600, Density(1f), coroutineContext = coroutineContext) {
                CompositionLocalProvider(
                    LocalDesktopHomeEnvironment provides fixture.home,
                    LocalLifecycleOwner provides fixture.lifecycleOwner,
                    LocalAppUiStyle provides AppUiStyle.MATERIAL3,
                ) {
                    // A distinctive theme color isolates the real indicator from text and page content.
                    MaterialTheme(colorScheme = lightColorScheme(
                        primary = Color.Magenta, secondaryContainer = Color.Magenta,
                        onSecondaryContainer = Color.Black,
                    )) {
                        ListenVideoScreen(
                            state = ListenVideoUiState(), nowPlaying = null,
                            onNowPlayingClick = { _, _ -> error("unexpected playback") },
                            onRefresh = { error("unexpected refresh") },
                            onOpenHistory = { error("unexpected history") },
                            onSectionSelected = { settled += it },
                            onPlaylistSelected = { error("unexpected playlist") },
                            onAlbumSelected = { error("unexpected album") },
                            onArtistSelected = { error("unexpected artist") },
                            onCloseDetail = { error("unexpected detail") },
                            onRetryDetail = { error("unexpected retry") },
                            onRetryIndex = { error("unexpected index") },
                            onTrackSelected = { _, _ -> error("unexpected track") },
                            onLogin = { error("unexpected login") },
                        )
                    }
                }
            }
            fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
            fun allNodes() = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
            fun hasLabel(node: SemanticsNode, label: String) =
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
            fun label(label: String) = allNodes().single { hasLabel(it, label) }
            fun isSelected(label: String) = allNodes().any { node ->
                node.config.getOrNull(SemanticsProperties.Selected) == true && nodes(node).any { hasLabel(it, label) }
            }
            fun accent(pixel: Int) = (pixel ushr 16 and 255) > 200 &&
                (pixel ushr 8 and 255) < 80 && (pixel and 255) > 200
            var scanY: Int? = null
            fun frame(): Float? {
                nanos += 1_000_000_000L / 165
                scene.render(nanos).use { image ->
                    val y = scanY ?: return null
                    Bitmap.makeFromImage(image).use { bitmap ->
                        val xs = (20 until 780).filter { accent(bitmap.getColor(it, y)) }
                        assertTrue(xs.isNotEmpty(), "indicator disappeared at frame time $nanos, y=$y")
                        return xs.average().toFloat()
                    }
                }
            }
            suspend fun frames(count: Int) = buildList {
                repeat(count) { frame()?.let { add(it) }; yield() }
            }
            fun click(text: String) {
                val point = label(text).boundsInRoot.center
                scene.sendPointerEvent(PointerEventType.Move, point, timeMillis = nanos / 1_000_000)
                scene.sendPointerEvent(PointerEventType.Press, point, timeMillis = nanos / 1_000_000 + 1,
                    buttons = PointerButtons(isPrimaryPressed = true))
                scene.sendPointerEvent(PointerEventType.Release, point, timeMillis = nanos / 1_000_000 + 41,
                    buttons = PointerButtons())
                nanos += 42_000_000L
            }
            try {
                frames(90)
                val first = label("播放列表").boundsInRoot
                val last = label("歌手").boundsInRoot
                val spacing = (last.center.x - first.center.x) / 2f
                assertTrue(spacing > 0f)
                // The native MD3 underline is below the text, inside the selectable tab's bounds.
                val tab = allNodes().single { node ->
                    node.config.getOrNull(SemanticsProperties.Selected) == true &&
                        nodes(node).any { hasLabel(it, "播放列表") }
                }.boundsInRoot
                nanos += 1_000_000_000L / 165
                scene.render(nanos).use { image ->
                    Bitmap.makeFromImage(image).use { bitmap ->
                        val rows = (first.bottom.toInt() until tab.bottom.toInt()).map { y ->
                            y to (20 until 780).count { accent(bitmap.getColor(it, y)) }
                        }
                        val best = rows.maxBy { it.second }
                        assertTrue(best.second > 10, "no visible themed indicator: $rows")
                        scanY = best.first
                    }
                }
                val start = frames(2).last()
                assertTrue(abs(start - first.center.x) < 3f, "initial rail $start vs $first")
                click("歌手")
                val early = frames(2)
                assertTrue(isSelected("歌手"), "the clicked destination must be selected before the pager crosses intermediate pages")
                val travel = listOf(start) + early + frames(85)
                assertTrue(travel.any { it > start + spacing * .2f && it < last.center.x - spacing * .2f },
                    "the actual indicator must visit intermediate positions: $travel")
                assertTrue(travel.zipWithNext().all { (a, b) -> b >= a - 1f }, "rail moved backwards: $travel")
                assertTrue(travel.zipWithNext().all { (a, b) -> abs(b - a) < spacing * .5f },
                    "rail jumped half a tab in one 165 Hz frame: $travel")
                assertEquals(ListenVideoSection.ARTISTS, settled.last())
                assertTrue(abs(travel.last() - last.center.x) < 3f)

                click("播放列表")
                val reversal = frames(8)
                click("专辑")
                val retargeted = frames(2)
                assertTrue(isSelected("专辑"), "a reversing click owns selection immediately")
                val reversed = reversal + retargeted + frames(85)
                assertTrue(reversed.zipWithNext().all { (a, b) -> abs(b - a) < spacing * .5f },
                    "reversing clicks snapped the actual rail: $reversed")
                assertEquals(ListenVideoSection.ALBUMS, settled.last())
                assertTrue(isSelected("专辑"))
                assertTrue(abs(reversed.last() - label("专辑").boundsInRoot.center.x) < 3f,
                    "rail did not settle on the final clicked tab: ${reversed.last()}")
            } finally {
                scene.close()
            }
        }
    }
}
