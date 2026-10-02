package com.bilipai.desktop.settings

import com.android.purebilibili.core.store.DesktopOriginalNavigationInteractionSettings as Original
import com.android.purebilibili.feature.settings.*
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.ui.DesktopOriginalHomePreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DesktopNavigationInteractionSettingsTest {
    @Test fun existingAppearanceAndPluginsAdmissionAlreadyUsesTheirDetailConsumers() {
        val navigator = DesktopSettingsNavigator()
        navigator.openCategory(SettingsRootCategory.APPEARANCE_THEME)
        assertEquals(DesktopSettingsPage.Detail(SettingsSearchTarget.APPEARANCE, null), navigator.state.value.current)
        navigator.openRoot()
        navigator.openCategory(SettingsRootCategory.PLUGINS_EXTENSIONS)
        assertEquals(DesktopSettingsPage.Detail(SettingsSearchTarget.PLUGINS, null), navigator.state.value.current)
        navigator.openRoot()
        navigator.openCategory(SettingsRootCategory.NAVIGATION_INTERACTION)
        assertEquals(DesktopSettingsPage.Category(SettingsRootCategory.NAVIGATION_INTERACTION), navigator.state.value.current)
    }

    @Test fun upstreamIndividualDefaultsRemainDifferentFromAggregateWhereSpecified(): Unit = runBlocking {
        val root = Files.createTempDirectory("bp-nav-")
        val context = DesktopPluginContext(DesktopPluginStore(root))
        assertTrue(Original.getBottomBarFloating(context).first())
        assertFalse(Original.getNavigationIconCrossScaleEnabled(context).first())
        assertFalse(Original.getBottomBarSearchEnabled(context).first())
        assertTrue(Original.getLinkedDockMergeOnScrollEnabled(context).first())
        assertFalse(Original.getListScopedSearchEnabled(context).first())
        assertFalse(Original.getCardAnimationEnabled(context).first())
        assertTrue(Original.getCardTransitionEnabled(context).first())
        assertFalse(Files.exists(root.resolve("plugin-settings.json")))
    }

    @Test fun exactSettersUpdateActualHomeReaderAndColdDiskWithoutDiscardingOtherKeys(): Unit = runBlocking {
        val root = Files.createTempDirectory("bp-nav-")
        val store = DesktopPluginStore(root)
        val context = DesktopPluginContext(store)
        store.update("settings", mapOf("foreign_key" to JsonPrimitive("keep")))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val home = DesktopOriginalHomePreferences.create(store, scope, false) { false }
        try {
            Original.setBottomBarFloating(context, false)
            Original.setNavigationIconCrossScaleEnabled(context, true)
            Original.setBottomBarSearchEnabled(context, true)
            Original.setLinkedDockMergeOnScrollEnabled(context, false)
            Original.setListScopedSearchEnabled(context, true)
            Original.setCardAnimationEnabled(context, true)
            Original.setCardTransitionEnabled(context, false)
            withTimeout(3000) { home.homeSettings.first { !it.isBottomBarFloating && it.navigationIconCrossScaleEnabled &&
                it.isBottomBarSearchEnabled && !it.linkedDockMergeOnScrollEnabled && it.listScopedSearchEnabled &&
                it.cardAnimationEnabled && !it.cardTransitionEnabled } }
            val disk = Json.parseToJsonElement(Files.readString(root.resolve("plugin-settings.json"))).jsonObject["settings"]!!.jsonObject
            assertEquals("keep", disk["foreign_key"]!!.jsonPrimitive.content)
            assertTrue(disk["bottom_bar_search_enabled"]!!.jsonPrimitive.boolean)
            assertFalse(disk["card_transition_enabled"]!!.jsonPrimitive.boolean)
            val fresh = DesktopPluginContext(DesktopPluginStore(root))
            assertTrue(Original.getListScopedSearchEnabled(fresh).first())
        } finally { scope.cancel(); scope.coroutineContext[Job]!!.join() }
    }

    @Test fun failedAtomicReplacementDoesNotPublishState(): Unit = runBlocking {
        val root = Files.createTempDirectory("bp-nav-")
        val store = DesktopPluginStore(root)
        val context = DesktopPluginContext(store)
        Original.setBottomBarFloating(context, true)
        val file = root.resolve("plugin-settings.json")
        val bytes = Files.readAllBytes(file)
        Files.delete(file); Files.createDirectory(file)
        try {
            assertThrows(Exception::class.java) { runBlocking { Original.setBottomBarFloating(context, false) } }
            assertTrue(Original.getBottomBarFloating(context).first())
        } finally { Files.delete(file); Files.write(file, bytes) }
    }

    @Test fun restoreFenceRejectsOldSetterAndFreshGenerationReadsRestoredSettings(): Unit = runBlocking {
        val root = Files.createTempDirectory("bp-nav-")
        val old = DesktopPluginStore(root)
        val context = DesktopPluginContext(old)
        Original.setCardTransitionEnabled(context, true)
        old.freezeWrites()
        Files.writeString(root.resolve("plugin-settings.json"), """{"settings":{"card_transition_enabled":false}}""")
        assertThrows(IllegalStateException::class.java) { runBlocking { Original.setCardTransitionEnabled(context, true) } }
        val fresh = DesktopPluginContext(DesktopPluginStore(root))
        assertFalse(Original.getCardTransitionEnabled(fresh).first())
        assertFalse(Json.parseToJsonElement(Files.readString(root.resolve("plugin-settings.json"))).jsonObject["settings"]!!.jsonObject["card_transition_enabled"]!!.jsonPrimitive.boolean)
    }

    @Test fun cancelledCallerDoesNotWriteAndExpandedFocusRetainsOriginalIndexes(): Unit = runBlocking {
        val root = Files.createTempDirectory("bp-nav-")
        val context = DesktopPluginContext(DesktopPluginStore(root))
        val job = launch(start = CoroutineStart.LAZY) { Original.setCardAnimationEnabled(context, true) }
        job.cancelAndJoin()
        assertFalse(Files.exists(root.resolve("plugin-settings.json")))
        assertEquals(1, desktopNavigationInteractionFocusIndex(SettingsSearchTarget.BOTTOM_BAR, SettingsSearchFocusIds.BOTTOM_BAR_BEHAVIOR))
        assertEquals(5, desktopNavigationInteractionFocusIndex(SettingsSearchTarget.BOTTOM_BAR, SettingsSearchFocusIds.BOTTOM_BAR_TOP_TABS))
        assertEquals(4, desktopNavigationInteractionFocusIndex(SettingsSearchTarget.ANIMATION, SettingsSearchFocusIds.ANIMATION_VISUAL_EFFECTS))
        assertNull(desktopNavigationInteractionFocusIndex(SettingsSearchTarget.PLAYBACK, SettingsSearchFocusIds.BOTTOM_BAR_TOP_TABS))
        SettingsSearchFocusController.submit(SettingsSearchTarget.BOTTOM_BAR, SettingsSearchFocusIds.BOTTOM_BAR_TOP_TABS)
        val token = SettingsSearchFocusController.request.value!!.token
        assertEquals(token, SettingsSearchFocusController.request.value!!.token)
        SettingsSearchFocusController.clear(token)
    }
}
