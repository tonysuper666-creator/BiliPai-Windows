@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.theme.LocalAppUiStyle
import com.android.purebilibili.core.ui.common.rememberContentTabPager
import com.android.purebilibili.core.ui.components.AppHorizontalPager
import com.android.purebilibili.feature.home.components.CategoryTabRow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.jetbrains.skia.Bitmap
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The compiled home tab renderer with a real content pager; this does not mount HomeScreen. */
class DesktopHomeCategoryPagerMotionTest {
    @Test
    fun nativeHomeRailSurvivesDistantClicksReversalAndContentDragging(): Unit = runBlocking {
        DesktopPageTestFixture().use { fixture ->
            val labels = listOf("推荐", "热门", "动态")
            val selected = mutableIntStateOf(0)
            val clicks = mutableListOf<Int>()
            var pager: PagerState? = null
            var nanos = 0L
            val scene = ImageComposeScene(800, 600, Density(1f), coroutineContext = coroutineContext) {
                CompositionLocalProvider(
                    LocalDesktopHomeEnvironment provides fixture.home,
                    LocalLifecycleOwner provides fixture.lifecycleOwner,
                    LocalAppUiStyle provides AppUiStyle.MATERIAL3,
                ) {
                    MaterialTheme(colorScheme = lightColorScheme(primary = Color.Magenta)) {
                        val state = rememberContentTabPager(
                            pageCount = labels.size, selectedPage = selected.intValue,
                            onPageSelected = { selected.intValue = it }, prepareNeighbors = {},
                        )
                        pager = state
                        Column(Modifier.fillMaxSize()) {
                            // The production chrome bounds the tab strip; without that parent
                            // constraint its fillMaxHeight consumes the content pager's space.
                            Box(Modifier.height(64.dp)) {
                                CategoryTabRow(
                                    categories = labels, categoryKeys = listOf("RECOMMEND", "HOT", "DYNAMIC"),
                                    selectedIndex = selected.intValue, pagerState = state, labelMode = 2,
                                    onCategorySelected = { clicks += it; selected.intValue = it },
                                )
                            }
                            AppHorizontalPager(state = state, modifier = Modifier.weight(1f)) {
                                Box(Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
            fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
            fun label(text: String) = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
                .single { it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true }
                .boundsInRoot
            fun accent(pixel: Int) = (pixel ushr 16 and 255) > 200 &&
                (pixel ushr 8 and 255) < 80 && (pixel and 255) > 200
            var scanY: Int? = null
            suspend fun frames(count: Int): List<Float> = buildList {
                repeat(count) {
                    nanos += 1_000_000_000L / 165
                    scene.render(nanos).use { image ->
                        scanY?.let { y ->
                            Bitmap.makeFromImage(image).use { bitmap ->
                                val xs = (0 until 800).filter { accent(bitmap.getColor(it, y)) }
                                assertTrue(xs.isNotEmpty(), "home underline disappeared at $nanos")
                                add(xs.average().toFloat())
                            }
                        }
                    }
                    yield()
                }
            }
            fun pointer(type: PointerEventType, point: Offset, pressed: Boolean, source: PointerType = PointerType.Mouse) {
                scene.sendPointerEvent(type, point, timeMillis = nanos / 1_000_000,
                    buttons = PointerButtons(isPrimaryPressed = pressed), type = source)
            }
            suspend fun click(text: String): List<Float> {
                val point = label(text).center
                pointer(PointerEventType.Move, point, false)
                pointer(PointerEventType.Press, point, true)
                // Keep sampling an in-flight animation while the next click is held.
                // Advancing event time without rendering would hide several display frames.
                val heldFrames = frames(7)
                pointer(PointerEventType.Release, point, false)
                return heldFrames
            }
            try {
                frames(90)
                val centers = labels.map { label(it).center.x }
                val spacing = centers[1] - centers[0]
                assertTrue(spacing > 0f)
                nanos += 6_060_606L
                scene.render(nanos).use { image ->
                    Bitmap.makeFromImage(image).use { bitmap ->
                        val rows = (labels.maxOf { label(it).bottom }.toInt() until 100).map { y ->
                            y to (0 until 800).count { accent(bitmap.getColor(it, y)) }
                        }
                        val best = rows.maxBy { it.second }
                        assertTrue(best.second > 10, "home tab renderer has no visible underline: $rows")
                        scanY = best.first
                    }
                }
                val start = frames(2).last()
                assertTrue(abs(start - centers[0]) < 3f)
                val travel = listOf(start) + click(labels[2]) + frames(90)
                assertEquals(listOf(2), clicks)
                assertEquals(2, pager!!.settledPage)
                assertTrue(travel.any { it > centers[0] + spacing * .2f && it < centers[2] - spacing * .2f })
                assertTrue(travel.zipWithNext().all { (a, b) -> b >= a - 1f }, "home underline reversed: $travel")
                assertTrue(travel.zipWithNext().all { (a, b) -> abs(b - a) < spacing * .5f }, "home underline jumped: $travel")
                assertTrue(abs(travel.last() - centers[2]) < 3f)

                val reversal = click(labels[0]) + frames(8)
                val retargeted = reversal + click(labels[1]) + frames(90)
                assertEquals(listOf(2, 0, 1), clicks)
                assertEquals(1, pager!!.settledPage)
                assertEquals(1, selected.intValue)
                assertTrue(retargeted.zipWithNext().all { (a, b) -> abs(b - a) < spacing * .5f },
                    "home underline jumped during retargeting (spacing $spacing): $retargeted")
                assertTrue(abs(retargeted.last() - centers[1]) < 3f)

                // Drive actual content input. During a held drag the underline follows the page,
                // rather than remaining owned by a completed tab-click animation.
                pointer(PointerEventType.Press, Offset(200f, 350f), true, PointerType.Touch)
                frames(2)
                val positions = mutableListOf<Float>()
                repeat(12) { step ->
                    pointer(PointerEventType.Move, Offset(200f + (step + 1) * 40f, 350f), true, PointerType.Touch)
                    val rail = frames(2).last()
                    val position = pager!!.currentPage + pager!!.currentPageOffsetFraction
                    positions += position
                    if (position in .1f.. .9f) {
                        val expected = centers[0] + spacing * position
                        assertTrue(abs(rail - expected) < spacing * .1f,
                            "home rail $rail detached from dragged page $position (expected $expected)")
                    }
                }
                assertTrue(positions.any { it in .1f.. .9f }, "pointer input never moved the real pager: $positions")
                pointer(PointerEventType.Release, Offset(680f, 350f), false, PointerType.Touch)
                val settled = frames(90).last()
                assertEquals(0, pager!!.settledPage)
                assertEquals(0, selected.intValue)
                assertTrue(abs(settled - centers[0]) < 3f)
            } finally {
                scene.close()
            }
        }
    }
}
