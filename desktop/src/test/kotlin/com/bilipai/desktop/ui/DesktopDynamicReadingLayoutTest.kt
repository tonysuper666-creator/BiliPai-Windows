package com.bilipai.desktop.ui

import androidx.compose.ui.semantics.getOrNull
import androidx.compose.foundation.layout.fillMaxWidth
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopDynamicReadingLayoutTest {
    @Test fun productionValuesMatchContract() {
        val contract = com.bilipai.desktop.ux.DesktopUxContract.section("reading").getValue("dynamic").jsonObject
        val values = mapOf("column_min" to DYNAMIC_READING_COLUMN_MIN, "column_max" to DYNAMIC_READING_COLUMN_MAX,
            "side_padding" to DYNAMIC_READING_SIDE_PADDING, "aside_gap" to DYNAMIC_READING_ASIDE_GAP,
            "aside_width" to DYNAMIC_READING_ASIDE_WIDTH, "aside_from_content_width" to DYNAMIC_READING_ASIDE_FROM_WIDTH)
        values.forEach { (key, value) -> assertEquals(com.bilipai.desktop.ux.DesktopUxContract.int(contract, key), value, key) }
    }

    @Test fun goldenHostWidths() {
        val contract = com.bilipai.desktop.ux.DesktopUxContract.section("reading").getValue("dynamic").jsonObject
        val rows = com.bilipai.desktop.ux.DesktopUxContract.objects(contract.getValue("golden"))
        assertEquals(7, rows.size)
        for (row in rows) {
            val wc = com.bilipai.desktop.ux.DesktopUxContract.double(row, "wc").toFloat()
            val expected = row.getValue("expect").jsonObject
            val geometry = desktopDynamicReadingGeometry(wc, true)
            assertEquals(com.bilipai.desktop.ux.DesktopUxContract.double(expected, "column").toFloat(), geometry.column)
            assertEquals(com.bilipai.desktop.ux.DesktopUxContract.double(expected, "column_x").toFloat(), geometry.columnX)
            if (expected.getValue("aside").jsonPrimitive.boolean)
                assertEquals(com.bilipai.desktop.ux.DesktopUxContract.double(expected, "aside_x").toFloat(), geometry.asideX)
            else assertNull(geometry.asideX)
        }
    }

    @Test fun pgcAndNarrowHostsDoNotAllocateAnAside() {
        assertNull(desktopDynamicReadingGeometry(2515f, false).asideX)
        assertEquals(500f, desktopDynamicReadingGeometry(500f, true).column)
        assertEquals(640f, desktopDynamicReadingGeometry(688f, true).column)
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class DesktopDynamicReadingHostTest {
    private fun nodes(node: androidx.compose.ui.semantics.SemanticsNode): List<androidx.compose.ui.semantics.SemanticsNode> =
        listOf(node) + node.children.flatMap(::nodes)

    @Test fun actualHostUsesReadingBoundsAndOnlyOneFollowingPresentation() = kotlinx.coroutines.runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("dynamic-reading-host-")
        val caches = mutableListOf<com.bilipai.desktop.data.DesktopDynamicCache>()
        val context = com.bilipai.desktop.plugins.DesktopPluginContext(com.bilipai.desktop.plugins.DesktopPluginStore(dir))
        val preferences = dynamicAccountTestPreferences(context) { caches += it }
        val users = DesktopDynamicUsersState(this, preferences, 42, { com.android.purebilibili.data.model.response.FollowingsData() },
            { emptyList() }, { null }, { tabsResponse(emptyList()) }, { true })
        val timeline = DesktopDynamicTimelineState("all", { _, _, _ -> tabsResponse(emptyList()) }, initialCachedItems = listOf(tabsDynamic("host")))
        val timelinePrefs = com.bilipai.desktop.settings.DesktopDynamicTimelinePreferences(context)
        try {
            for (dark in listOf(false, true)) for (width in listOf(888, 991, 992, 1064, 1207, 1320, 2515)) {
                val scene = androidx.compose.ui.ImageComposeScene(width, 600, androidx.compose.ui.unit.Density(1f), coroutineContext = coroutineContext) {
                    androidx.compose.material3.MaterialTheme(colorScheme = if (dark) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()) {
                        DesktopDynamicTabsHost(users, timelinePrefs, {}, {}, { it }, { timeline }, active = false) {
                            androidx.compose.material3.Text("reading-row", androidx.compose.ui.Modifier.fillMaxWidth())
                        }
                    }
                }
                try {
                    scene.render(1L).close()
                    scene.render(2L).close()
                    val all = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
                    fun find(value: String) = all.filter { node ->
                        node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.any { it.text == value } == true
                    }
                    val row = kotlin.test.assertNotNull(find("reading-row").singleOrNull(), "row at $width")
                    val geometry = desktopDynamicReadingGeometry(width.toFloat(), true)
                    kotlin.test.assertTrue(kotlin.math.abs(row.boundsInRoot.left - geometry.columnX) <= 1f, "row x at $width")
                    kotlin.test.assertTrue(kotlin.math.abs(row.boundsInRoot.width - geometry.column) <= 1f, "row width at $width")
                    fun tagged(value: String) = all.filter { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag) == value }
                    val feed = tagged("dynamic-reading-feed").single().boundsInRoot
                    val tabs = tagged("dynamic-reading-tabs").single().boundsInRoot
                    val count = tagged("dynamic-reading-count").single().boundsInRoot
                    assertEquals(tabs.left, count.left, "count left at $width")
                    assertEquals(tabs.width, count.width, "count width at $width")
                    val strip = tagged("dynamic-reading-strip")
                    assertEquals(if (width >= 992) 0 else 1, strip.size, "strip at $width")
                    strip.singleOrNull()?.boundsInRoot?.let {
                        assertEquals(tabs.left, it.left, "strip left at $width")
                        assertEquals(tabs.width, it.width, "strip width at $width")
                    }
                    kotlin.test.assertTrue(kotlin.math.abs(feed.left - geometry.columnX) <= 1f, "feed x at $width: $feed")
                    kotlin.test.assertTrue(kotlin.math.abs(feed.width - geometry.column) <= 1f, "feed width at $width: $feed")
                    val aside = tagged("dynamic-following-aside")
                    assertEquals(if (width >= 992) 1 else 0, aside.size, "aside at $width")
                    if (aside.isNotEmpty()) {
                        kotlin.test.assertTrue(kotlin.math.abs(aside.single().boundsInRoot.left - geometry.asideX!!) <= 1f)
                        assertEquals(280f, aside.single().boundsInRoot.width)
                        assertEquals(feed.top, aside.single().boundsInRoot.top, "aside top at $width")
                    }
                    assertEquals(if (width >= 992) 1 else 0, find("关注的 UP 主").size, "aside heading at $width")
                    assertEquals(1, find("全部动态").size, "one copy of the follow controls at $width")
                } finally { scene.close() }
            }
        } finally {
            users.close()
            caches.forEach { it.shutdownForRestore() }
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun pgcLoadingAndEmptyUseTheSameMountedHost() = kotlinx.coroutines.runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("dynamic-reading-pgc-")
        val caches = mutableListOf<com.bilipai.desktop.data.DesktopDynamicCache>()
        val context = com.bilipai.desktop.plugins.DesktopPluginContext(com.bilipai.desktop.plugins.DesktopPluginStore(dir))
        val preferences = dynamicAccountTestPreferences(context) { caches += it }
        val users = DesktopDynamicUsersState(this, preferences, 42, { com.android.purebilibili.data.model.response.FollowingsData() },
            { emptyList() }, { null }, { tabsResponse(emptyList()) }, { true })
        val timeline = DesktopDynamicTimelineState("pgc", { _, _, _ -> tabsResponse(emptyList()) })
        val timelinePrefs = com.bilipai.desktop.settings.DesktopDynamicTimelinePreferences(context)
        var mounts = 0
        var disposals = 0
        users.selectTab(2)
        val scene = androidx.compose.ui.ImageComposeScene(1320, 600, androidx.compose.ui.unit.Density(1f), coroutineContext = coroutineContext) {
            androidx.compose.material3.MaterialTheme {
                DesktopDynamicTabsHost(users, timelinePrefs, {}, {}, { it }, { timeline }, active = false,
                    trailing = { androidx.compose.runtime.DisposableEffect(Unit) { mounts++; onDispose { disposals++ } } }) {
                    androidx.compose.material3.Text("pgc-row")
                }
            }
        }
        try {
            fun allNodes() = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
            fun tagged(tag: String) = allNodes().filter { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag) == tag }
            fun text(value: String) = allNodes().filter { n -> n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.any { it.text == value } == true }
            scene.render(1L).close(); scene.render(2L).close()
            assertEquals(2, users.selectedLogicalTab)
            assertEquals(0, tagged("dynamic-following-aside").size)
            assertEquals(0, tagged("dynamic-reading-strip").size)
            assertEquals(280f, tagged("dynamic-reading-feed").single().boundsInRoot.left)
            kotlin.test.assertTrue(allNodes().any { it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo) }, "loading indicator")
            assertEquals(0, text("暂无内容").size)
            kotlin.test.assertTrue(timeline.initialize(false))
            scene.render(3L).close(); scene.render(4L).close()
            assertEquals(1, text("暂无内容").size)
            assertEquals(1, mounts)
            assertEquals(0, disposals)
        } finally {
            scene.close()
            assertEquals(1, disposals)
            users.close(); caches.forEach { it.shutdownForRestore() }; dir.toFile().deleteRecursively()
        }
    }

    @Test fun keyboardFocusMovesFromTabsThroughFeedToAside() = kotlinx.coroutines.runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("dynamic-reading-focus-")
        val caches = mutableListOf<com.bilipai.desktop.data.DesktopDynamicCache>()
        val context = com.bilipai.desktop.plugins.DesktopPluginContext(com.bilipai.desktop.plugins.DesktopPluginStore(dir))
        val preferences = dynamicAccountTestPreferences(context) { caches += it }
        val users = DesktopDynamicUsersState(this, preferences, 42, { com.android.purebilibili.data.model.response.FollowingsData() },
            { emptyList() }, { null }, { tabsResponse(emptyList()) }, { true })
        val timeline = DesktopDynamicTimelineState("all", { _, _, _ -> tabsResponse(emptyList()) }, initialCachedItems = listOf(tabsDynamic("focus")))
        val timelinePrefs = com.bilipai.desktop.settings.DesktopDynamicTimelinePreferences(context)
        val scene = androidx.compose.ui.ImageComposeScene(1064, 600, androidx.compose.ui.unit.Density(1f), coroutineContext = coroutineContext) {
            androidx.compose.material3.MaterialTheme {
                val original = androidx.compose.ui.platform.LocalWindowInfo.current
                val window = androidx.compose.runtime.remember(original) { object : androidx.compose.ui.platform.WindowInfo by original { override val isWindowFocused = true } }
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalWindowInfo provides window) {
                    DesktopDynamicTabsHost(users, timelinePrefs, {}, {}, { it }, { timeline }, active = false) {
                        androidx.compose.material3.Button(onClick = {}) { androidx.compose.material3.Text("focus-row") }
                    }
                }
            }
        }
        try {
            scene.render(1L).close(); scene.render(2L).close()
            fun allNodes() = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
            fun bounds(tag: String) = allNodes().single { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag) == tag }.boundsInRoot
            val tabs = bounds("dynamic-reading-tabs"); val feed = bounds("dynamic-reading-feed"); val aside = bounds("dynamic-following-aside")
            val regions = mutableListOf<String>()
            repeat(20) { index ->
                scene.sendKeyEvent(androidx.compose.ui.input.key.KeyEvent(androidx.compose.ui.input.key.Key.Tab, androidx.compose.ui.input.key.KeyEventType.KeyDown))
                scene.sendKeyEvent(androidx.compose.ui.input.key.KeyEvent(androidx.compose.ui.input.key.Key.Tab, androidx.compose.ui.input.key.KeyEventType.KeyUp))
                scene.render((3L + index) * 1000000).close()
                val focus = allNodes().lastOrNull { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Focused) == true }
                val point = focus?.boundsInRoot?.center
                val region = when { point == null -> null; tabs.contains(point) -> "tabs"; feed.contains(point) -> "feed"; aside.contains(point) -> "aside"; else -> null }
                if (region != null && regions.lastOrNull() != region) regions += region
            }
            val tabIndex = regions.indexOf("tabs"); val feedIndex = regions.indexOf("feed"); val asideIndex = regions.indexOf("aside")
            kotlin.test.assertTrue(tabIndex >= 0 && feedIndex > tabIndex && asideIndex > feedIndex, "focus regions: $regions")
        } finally {
            scene.close(); users.close(); caches.forEach { it.shutdownForRestore() }; dir.toFile().deleteRecursively()
        }
    }
}
