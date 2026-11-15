package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.android.purebilibili.data.model.response.FollowingsData
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.settings.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

/** The same tests compile against the old Host: assertions exercise real Root writes and rendered nodes. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class DesktopDynamicUpListVisibilityTest {
    private var frameTime = 0L
    private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
    private fun ImageComposeScene.nodes() = semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
    private fun ImageComposeScene.tagCount(tag: String) = nodes().count { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
    private suspend fun ImageComposeScene.settle() {
        repeat(4) { yield(); delay(5); frameTime += 16_000_000; render(frameTime).close() }
    }
    private fun DesktopPluginContext.writeVisibility(visible: Boolean) =
        getSharedPreferences("settings", DesktopPluginContext.MODE_PRIVATE).edit()
            .putBoolean("dynamic_up_list_visible", visible).apply()

    @Test fun mountedHostTracksRootWritesAcrossWidthsAndTabsWithoutResettingAuthor(): Unit = runBlocking {
        val dir = Files.createTempDirectory("up-list-host-")
        val caches = mutableListOf<DesktopDynamicCache>()
        val context = DesktopPluginContext(DesktopPluginStore(dir))
        val prefs = dynamicAccountTestPreferences(context) { caches += it }
        val users = DesktopDynamicUsersState(this, prefs, 42, { FollowingsData() }, { emptyList() }, { null },
            { tabsResponse(listOf(tabsDynamic("author", 93))) }, { true })
        val timeline = DesktopDynamicTimelineState("all", { _, _, _ -> tabsResponse(emptyList()) },
            initialCachedItems = listOf(tabsDynamic("feed")))
        try {
            for (width in listOf(888, 1320)) {
                context.writeVisibility(true)
                val scene = ImageComposeScene(width, 600, Density(1f), coroutineContext = coroutineContext) {
                    MaterialTheme {
                        DesktopDynamicTabsHost(users, DesktopDynamicTimelinePreferences(context), {}, {}, { it }, { timeline }, active = false) {
                            Text("actual-feed-row")
                        }
                    }
                }
                try {
                    for (tab in listOf(0, 1, 3, 2)) {
                        users.selectTab(tab)
                        scene.settle()
                        val allowed = tab != 2
                        assertEquals(if (allowed && width < 992) 1 else 0, scene.tagCount("dynamic-reading-strip"))
                        assertEquals(if (allowed && width >= 992) 1 else 0, scene.tagCount("dynamic-following-aside"))
                        if (allowed && users.selectedUid != 93L) {
                            users.selectUser(93)
                            withTimeout(2000) { while (users.userLoading) delay(5) }
                        }
                        val selected = users.selectedUid
                        val items = users.userItems
                        context.writeVisibility(false)
                        scene.settle()
                        assertEquals(0, scene.tagCount("dynamic-reading-strip"), "off hides narrow strip at tab=$tab")
                        assertEquals(0, scene.tagCount("dynamic-following-aside"), "off hides wide aside at tab=$tab")
                        val feed = scene.nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == "dynamic-reading-feed" }
                        assertEquals(desktopDynamicReadingGeometry(width.toFloat(), false).columnX, feed.boundsInRoot.left,
                            "hidden aside releases its space")
                        assertEquals(tab, users.selectedLogicalTab)
                        assertEquals(selected, users.selectedUid)
                        assertSame(items, users.userItems, "display preference does not reset loaded author rows")
                        context.writeVisibility(true)
                        scene.settle()
                        assertEquals(if (allowed && width < 992) 1 else 0, scene.tagCount("dynamic-reading-strip"))
                        assertEquals(if (allowed && width >= 992) 1 else 0, scene.tagCount("dynamic-following-aside"))
                    }
                } finally { scene.close() }
            }
        } finally { users.close(); caches.forEach { it.shutdownForRestore() }; dir.toFile().deleteRecursively() }
    }

    @Test fun actualSettingsControlWritesCanonicalKeyAndIgnoresRetiredKey(): Unit = runBlocking {
        val dir = Files.createTempDirectory("up-list-control-")
        val context = DesktopPluginContext(DesktopPluginStore(dir))
        val settings = context.getSharedPreferences("settings", DesktopPluginContext.MODE_PRIVATE)
        settings.edit().putBoolean("dynamic_all_tab_horizontal_user_list_visible", false).apply()
        val failures = mutableListOf<Throwable>()
        val scene = ImageComposeScene(888, 900, Density(1f), coroutineContext = coroutineContext) {
            MaterialTheme { Column { DesktopDynamicTabsSettings(DesktopDynamicTabsPreferences(context), failures::add) } }
        }
        try {
            scene.settle()
            fun control(): SemanticsNode {
                var current = assertNotNull(scene.nodes().singleOrNull {
                    it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == "显示动态UP主头像栏" } == true
                }, "actual upstream settings control is mounted")
                while (current.config.getOrNull(SemanticsActions.OnClick)?.action == null) current = assertNotNull(current.parent)
                return current
            }
            fun switchState() = assertNotNull(nodes(control()).singleOrNull {
                it.config.getOrNull(SemanticsProperties.ToggleableState) != null
            }, "the rendered preference row contains its actual switch").config.getOrNull(SemanticsProperties.ToggleableState)
            assertEquals(androidx.compose.ui.state.ToggleableState.On, switchState(),
                "default true ignores the retired setting")
            assertTrue(assertNotNull(control().config.getOrNull(SemanticsActions.OnClick)?.action).invoke())
            withTimeout(2000) { while (settings.getBoolean("dynamic_up_list_visible", true)) { scene.settle() } }
            scene.settle()
            assertEquals(androidx.compose.ui.state.ToggleableState.Off, switchState())
            assertTrue(assertNotNull(control().config.getOrNull(SemanticsActions.OnClick)?.action).invoke())
            withTimeout(2000) { while (!settings.getBoolean("dynamic_up_list_visible", false)) { scene.settle() } }
            assertTrue(failures.isEmpty(), failures.toString())
            val disk = Json.parseToJsonElement(Files.readString(dir.resolve("plugin-settings.json"))).jsonObject.getValue("settings").jsonObject
            assertEquals(JsonPrimitive(true), disk["dynamic_up_list_visible"])
            assertEquals(JsonPrimitive(false), disk["dynamic_all_tab_horizontal_user_list_visible"], "retired key is neither read nor rewritten")
        } finally { scene.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun globalChoiceSurvivesAccountEpochAndColdStoreWhileAccountTabsStaySeparate(): Unit = runBlocking {
        val dir = Files.createTempDirectory("up-list-accounts-")
        val sessions = DesktopSessionStore(dir.resolve("synthetic-session.json"), false)
        val store = DesktopPluginStore(dir.resolve("preferences"))
        val context = DesktopPluginContext(store)
        val cache = DesktopDynamicCache(sessions, store)
        var restoredCache: DesktopDynamicCache? = null
        var users: DesktopDynamicUsersState? = null
        var scene: ImageComposeScene? = null
        try {
            suspend fun account(mid: Long): DesktopDynamicTabsPreferences {
                sessions.saveAccount(mapOf("SESSDATA" to "synthetic-$mid"), AccountSummary(mid, "Fixture", ""))
                return DesktopDynamicTabsPreferences(context, checkNotNull(cache.openCurrent()))
            }
            val a = account(101); a.setSelectedTab(3)
            context.writeVisibility(false)
            val b = account(202); assertEquals(0, b.selectedTab); b.setSelectedTab(1)
            users = DesktopDynamicUsersState(this, b, 202, { FollowingsData() }, { emptyList() }, { null }, { tabsResponse(emptyList()) }, { true })
            val timeline = DesktopDynamicTimelineState("video", { _, _, _ -> tabsResponse(emptyList()) })
            scene = ImageComposeScene(1320, 600, Density(1f), coroutineContext = coroutineContext) {
                MaterialTheme {
                    DesktopDynamicTabsHost(checkNotNull(users), DesktopDynamicTimelinePreferences(context), {}, {}, { it }, { timeline }, active = false) { Text("rebuilt") }
                }
            }
            checkNotNull(scene).settle()
            assertEquals(0, checkNotNull(scene).tagCount("dynamic-following-aside"), "account B starts from the global off setting")
            context.writeVisibility(true); checkNotNull(scene).settle()
            assertEquals(1, checkNotNull(scene).tagCount("dynamic-following-aside"))
            checkNotNull(scene).close(); scene = null; checkNotNull(users).close(); users = null
            val again = account(101); assertEquals(3, again.selectedTab)
            context.writeVisibility(false)
            cache.shutdownForRestore(); store.freezeWrites()
            val cold = DesktopPluginStore(store.root)
            restoredCache = DesktopDynamicCache(sessions, cold)
            val restored = DesktopDynamicTabsPreferences(DesktopPluginContext(cold), checkNotNull(restoredCache.openCurrent()))
            assertEquals(3, restored.selectedTab)
            // Use the actual settings renderer after cold load, rather than an in-memory test flag.
            val coldContext = restored.context
            users = DesktopDynamicUsersState(this, restored, 101, { FollowingsData() }, { emptyList() }, { null }, { tabsResponse(emptyList()) }, { true })
            scene = ImageComposeScene(888, 600, Density(1f), coroutineContext = coroutineContext) {
                MaterialTheme { DesktopDynamicTabsHost(checkNotNull(users), DesktopDynamicTimelinePreferences(coldContext), {}, {}, { it }, { timeline }, active = false) { Text("cold") } }
            }
            checkNotNull(scene).settle()
            assertEquals(0, checkNotNull(scene).tagCount("dynamic-reading-strip"), "cold rebuild restores off before first visible strip")
            coldContext.writeVisibility(true); checkNotNull(scene).settle()
            assertEquals(1, checkNotNull(scene).tagCount("dynamic-reading-strip"))
            assertEquals(3, checkNotNull(users).selectedLogicalTab)
            assertEquals(setOf("all", "video", "pgc", "article"), restored.visibleTabs.first())
        } finally {
            scene?.close(); users?.close(); cache.shutdownForRestore(); restoredCache?.shutdownForRestore(); dir.toFile().deleteRecursively()
        }
    }
}
