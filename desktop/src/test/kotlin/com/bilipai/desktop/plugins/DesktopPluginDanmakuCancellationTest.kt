package com.bilipai.desktop.plugins

import com.android.purebilibili.core.plugin.DanmakuItem
import com.android.purebilibili.core.plugin.DanmakuPlugin
import com.android.purebilibili.core.plugin.DanmakuStyle
import com.android.purebilibili.core.plugin.json.JsonPluginManager
import com.android.purebilibili.core.plugin.json.LoadedJsonPlugin
import com.android.purebilibili.feature.video.danmaku.DesktopPluginDanmakuPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

/** Actual generated policy and actual JSON manager calls; no runtime initialization,
 * Store, saved plugin, account, network or renderer is used. */
class DesktopPluginDanmakuCancellationTest {
    private val item = DanmakuItem(77, "def text t { content = \"visible\" }", 2_000, type = 9)

    private fun plugin(
        filter: (DanmakuItem) -> DanmakuItem? = { it },
        style: (DanmakuItem) -> DanmakuStyle? = { null },
    ) = object : DanmakuPlugin {
        override val id = "private-cancellation-fixture"
        override val name = "Private cancellation fixture"
        override val description = "In-memory generated-policy test"
        override val version = "1"
        override fun filterDanmaku(danmaku: DanmakuItem) = filter(danmaku)
        override fun styleDanmaku(danmaku: DanmakuItem) = style(danmaku)
    }

    /** Feed a throwing in-memory collection through the REAL manager's first
     * collection traversal, then restore its precise previous reference. A size
     * different from the saved value avoids StateFlow equals traversing this
     * collection during fixture assignment. No manager callbacks are mocked. */
    @Suppress("UNCHECKED_CAST")
    private fun withJsonFailure(failure: Exception, action: () -> Unit) = synchronized(JsonPluginManager) {
        val field = JsonPluginManager::class.java.getDeclaredField("_plugins").apply { isAccessible = true }
        val state = field.get(JsonPluginManager) as MutableStateFlow<List<LoadedJsonPlugin>>
        val previous = state.value
        val throwing = object : AbstractList<LoadedJsonPlugin>() {
            override val size = previous.size + 1
            override fun get(index: Int): LoadedJsonPlugin = throw failure
        }
        try {
            state.value = throwing
            assertSame(throwing, state.value)
            action()
        } finally {
            state.value = previous
            assertSame(previous, state.value)
        }
    }

    @Test fun nativeFilterCancellationPropagatesFromActualPolicy() {
        val cancelled = CancellationException("filter retired")
        assertSame(cancelled, assertFailsWith<CancellationException> {
            DesktopPluginDanmakuPolicy.runDanmakuFilters(item, listOf(plugin(filter = { throw cancelled })), false)
        })
    }

    @Test fun nativeStyleCancellationPropagatesFromActualPolicy() {
        val cancelled = CancellationException("style retired")
        assertSame(cancelled, assertFailsWith<CancellationException> {
            DesktopPluginDanmakuPolicy.collectDanmakuStyle(item, listOf(plugin(style = { throw cancelled })), false)
        })
    }

    @Test fun jsonFilterCancellationTraversesActualManagerAndPropagates() {
        val cancelled = CancellationException("JSON filter retired")
        withJsonFailure(cancelled) {
            assertSame(cancelled, assertFailsWith<CancellationException> {
                DesktopPluginDanmakuPolicy.runDanmakuFilters(item, emptyList(), true)
            })
        }
    }

    @Test fun jsonStyleCancellationTraversesActualManagerAndPropagates() {
        val cancelled = CancellationException("JSON style retired")
        withJsonFailure(cancelled) {
            assertSame(cancelled, assertFailsWith<CancellationException> {
                DesktopPluginDanmakuPolicy.collectDanmakuStyle(item, emptyList(), true)
            })
        }
    }

    @Test fun nativeOrdinaryFailureKeepsOriginalFallbackAndBatchOrder() {
        val seen = mutableListOf<String>()
        val failed = plugin(filter = { seen += "first-filter"; error("ordinary plugin failure") },
            style = { seen += "first-style"; error("ordinary style failure") })
        val following = plugin(filter = { seen += "next-filter"; assertSame(item, it); it.copy(timeMs = 3_000) },
            style = { seen += "next-style"; DanmakuStyle(scale = 1.5f) })
        val filtered = assertNotNull(DesktopPluginDanmakuPolicy.runDanmakuFilters(item, listOf(failed, following), false))
        assertEquals(3_000L, filtered.timeMs)
        assertEquals(1.5f, DesktopPluginDanmakuPolicy.collectDanmakuStyle(filtered, listOf(failed, following), false)?.scale)
        assertEquals(listOf("first-filter", "next-filter", "first-style", "next-style"), seen)
    }

    @Test fun jsonOrdinaryFailureKeepsOriginalShowAndNoStyleFallback() {
        withJsonFailure(IllegalStateException("ordinary JSON failure")) {
            assertSame(item, DesktopPluginDanmakuPolicy.runDanmakuFilters(item, emptyList(), true))
            assertNull(DesktopPluginDanmakuPolicy.collectDanmakuStyle(item, emptyList(), true))
        }
    }
}
