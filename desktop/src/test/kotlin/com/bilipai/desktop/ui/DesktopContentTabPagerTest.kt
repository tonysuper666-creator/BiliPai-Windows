package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.android.purebilibili.core.ui.common.rememberContentTabPager
import com.android.purebilibili.core.ui.components.AppHorizontalPager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * The PagerState rebuild guard of the carried v0.4.6 ContentTabPager (roadmap space-profile-content-pager).
 *
 * The Space screen lets its tab list grow while the first page is already showing (the cheese tab arrives with a
 * later request). The page key list is built from the displayed tab list of the same frame, so a pager that kept
 * the old page count would index a shorter list: the out of bounds crash of the 0.4.4 pager. These tests drive the
 * real rememberContentTabPager under the real AppHorizontalPager with a key list that throws on any index outside
 * the current tab list. They need no window, no GPU and no network: the scene renders into an offscreen image.
 */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class DesktopContentTabPagerTest {
    private class Harness(initialSelection: Int) : AutoCloseable {
        val selection = mutableIntStateOf(initialSelection)
        val tabs = mutableStateOf(listOf("home", "dynamic", "contribution"))
        var pager: PagerState? = null
        private var nanos = 0L
        // An exception thrown inside the scene's recomposer belongs to this test, not to whichever runTest runs next.
        val uncaught = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val scene = ImageComposeScene(400, 300, Density(1f),
            coroutineContext = Dispatchers.Unconfined + CoroutineExceptionHandler { _, error -> uncaught += error }) {
            val displayed = tabs.value
            val state = rememberContentTabPager(
                pageCount = displayed.size,
                selectedPage = selection.intValue,
                onPageSelected = { selection.intValue = it },
                prepareNeighbors = {},
            )
            pager = state
            AppHorizontalPager(
                state = state,
                key = { displayed[it] },
                modifier = Modifier.fillMaxSize(),
            ) { _ ->
                Box(Modifier.fillMaxSize())
            }
        }

        fun current(): PagerState = checkNotNull(pager) { "the scene has not composed yet" }

        suspend fun settle(frames: Int = 90) {
            repeat(frames) {
                nanos += 16_000_000L
                scene.render(nanos).close()
                yield()
            }
        }

        override fun close() {
            scene.close()
            assertTrue(uncaught.isEmpty(), "uncaught composition exceptions: $uncaught")
        }
    }

    @Test
    fun asyncTabsGrowAndShrinkWithoutMixingThePagerCountWithTheOldKeys(): Unit = runBlocking {
        Harness(initialSelection = 2).use { harness ->
            harness.settle()
            val original = harness.current()
            assertEquals(3, original.pageCount)
            assertEquals(2, original.settledPage)

            harness.tabs.value = harness.tabs.value + "cheese"
            harness.settle()
            val grown = harness.current()
            assertNotSame(original, grown, "a changed page count must replace the pager")
            assertEquals(3, original.pageCount, "the replaced pager keeps the count its own key list had")
            assertEquals(4, grown.pageCount)
            assertEquals(2, grown.settledPage)
            assertEquals(2, harness.selection.intValue)

            harness.selection.intValue = 3
            harness.settle()
            assertEquals(3, harness.current().settledPage)

            val expanded = harness.current()
            harness.tabs.value = harness.tabs.value.take(3)
            harness.selection.intValue = 2
            harness.settle()
            val shrunk = harness.current()
            assertNotSame(expanded, shrunk)
            assertEquals(4, expanded.pageCount)
            assertEquals(3, shrunk.pageCount)
            assertEquals(2, shrunk.settledPage)
            assertEquals(2, harness.selection.intValue)
        }
    }

    @Test
    fun anAsyncTabArrivingDuringTheTabAnimationKeepsTheRequestedSelection(): Unit = runBlocking {
        Harness(initialSelection = 0).use { harness ->
            harness.settle()
            harness.selection.intValue = 2
            harness.settle(frames = 6)
            harness.tabs.value = harness.tabs.value + "cheese"
            harness.settle()
            val state = harness.current()
            assertEquals(4, state.pageCount)
            assertEquals(2, harness.selection.intValue)
            assertEquals(2, state.settledPage)
        }
    }
}
