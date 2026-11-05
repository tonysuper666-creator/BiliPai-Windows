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
                    kotlin.test.assertTrue(kotlin.math.abs(feed.left - geometry.columnX) <= 1f, "feed x at $width: $feed")
                    kotlin.test.assertTrue(kotlin.math.abs(feed.width - geometry.column) <= 1f, "feed width at $width: $feed")
                    val aside = tagged("dynamic-following-aside")
                    assertEquals(if (width >= 992) 1 else 0, aside.size, "aside at $width")
                    if (aside.isNotEmpty()) {
                        kotlin.test.assertTrue(kotlin.math.abs(aside.single().boundsInRoot.left - geometry.asideX!!) <= 1f)
                        assertEquals(280f, aside.single().boundsInRoot.width)
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
}
