package com.bilipai.desktop.ui

import com.android.purebilibili.core.store.DesktopDynamicCardSettings
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.settings.DesktopDynamicPageChromePreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopDynamicPageChromeTest {
    @Test fun fiveOriginalKeysMergeWithoutReplacingOtherSettingsOrUserKeys(): Unit = runBlocking {
        val root = Files.createTempDirectory("dynamic-page-chrome-")
        val store = DesktopPluginStore(root)
        store.update("settings", mapOf("incremental_timeline_refresh" to JsonPrimitive(true)))
        store.update("dynamic_user_prefs_42", mapOf(
            DESKTOP_DYNAMIC_DISPLAY_MODE_KEY to JsonPrimitive("DRAWER_LEFT"),
            "dynamic_selected_tab" to JsonPrimitive(2),
        ))
        val chrome = DesktopDynamicPageChromePreferences(DesktopPluginContext(store))
        assertTrue(chrome.peekImagePreviewTextVisible())
        assertEquals(DesktopDynamicCardSettings.DynamicDetailImageLayout.EXPANDED, chrome.peekDetailImageLayout())
        assertFalse(chrome.peekTopBarCollapseOnScroll())
        assertFalse(chrome.peekTopActionsCollapsed())
        assertEquals(DesktopDynamicPageLayoutDirection.LEFT, chrome.peekPageLayoutDirection())

        chrome.setImagePreviewTextVisible(false)
        chrome.setDetailImageLayout(DesktopDynamicCardSettings.DynamicDetailImageLayout.THUMBNAIL)
        chrome.setTopBarCollapseOnScroll(true)
        chrome.setTopActionsCollapsed(true)
        chrome.setPageLayoutDirection(DesktopDynamicPageLayoutDirection.RIGHT)

        val disk = Json.parseToJsonElement(Files.readString(root.resolve("plugin-settings.json"))).jsonObject
        val settings = disk["settings"]!!.jsonObject
        assertFalse(settings[DYNAMIC_IMAGE_PREVIEW_TEXT_VISIBLE_KEY]!!.jsonPrimitive.boolean)
        assertEquals(1, settings[DYNAMIC_DETAIL_IMAGE_LAYOUT_KEY]!!.jsonPrimitive.int)
        assertTrue(settings[DYNAMIC_TOP_BAR_COLLAPSE_ON_SCROLL_KEY]!!.jsonPrimitive.boolean)
        assertTrue(settings[DYNAMIC_TOP_ACTIONS_COLLAPSED_KEY]!!.jsonPrimitive.boolean)
        assertEquals(1, settings[DYNAMIC_PAGE_LAYOUT_DIRECTION_KEY]!!.jsonPrimitive.int)
        assertTrue(settings["incremental_timeline_refresh"]!!.jsonPrimitive.boolean)
        val user = disk["dynamic_user_prefs_42"]!!.jsonObject
        assertEquals("DRAWER_LEFT", user[DESKTOP_DYNAMIC_DISPLAY_MODE_KEY]!!.jsonPrimitive.content)
        assertEquals(2, user["dynamic_selected_tab"]!!.jsonPrimitive.int)

        val cold = DesktopDynamicPageChromePreferences(DesktopPluginContext(DesktopPluginStore(root)))
        assertFalse(cold.imagePreviewTextVisible.first())
        assertEquals(DesktopDynamicCardSettings.DynamicDetailImageLayout.THUMBNAIL, cold.detailImageLayout.first())
        assertTrue(cold.topBarCollapseOnScroll.first())
        assertTrue(cold.topActionsCollapsed.first())
        assertEquals(DesktopDynamicPageLayoutDirection.RIGHT, cold.pageLayoutDirection.first())
        assertFalse(cold.peekImagePreviewTextVisible())
        assertEquals(DesktopDynamicCardSettings.DynamicDetailImageLayout.THUMBNAIL, cold.peekDetailImageLayout())
    }

    @Test fun unknownStoredValuesStayOnTheOriginalDefaults(): Unit = runBlocking {
        val root = Files.createTempDirectory("dynamic-page-chrome-unknown-")
        val store = DesktopPluginStore(root)
        store.update("settings", mapOf(
            DYNAMIC_PAGE_LAYOUT_DIRECTION_KEY to JsonPrimitive(9),
            DYNAMIC_DETAIL_IMAGE_LAYOUT_KEY to JsonPrimitive(9),
            DYNAMIC_IMAGE_PREVIEW_TEXT_VISIBLE_KEY to JsonPrimitive("nope"),
            DYNAMIC_TOP_BAR_COLLAPSE_ON_SCROLL_KEY to JsonPrimitive("nope"),
            DYNAMIC_TOP_ACTIONS_COLLAPSED_KEY to JsonPrimitive("nope"),
        ))
        val chrome = DesktopDynamicPageChromePreferences(DesktopPluginContext(store))
        assertEquals(DesktopDynamicPageLayoutDirection.LEFT, chrome.peekPageLayoutDirection())
        assertEquals(DesktopDynamicPageLayoutDirection.LEFT, chrome.pageLayoutDirection.first())
        assertEquals(DesktopDynamicCardSettings.DynamicDetailImageLayout.EXPANDED, chrome.peekDetailImageLayout())
        assertEquals(DesktopDynamicCardSettings.DynamicDetailImageLayout.EXPANDED, chrome.detailImageLayout.first())
        assertTrue(chrome.peekImagePreviewTextVisible())
        assertTrue(chrome.imagePreviewTextVisible.first())
        assertFalse(chrome.peekTopBarCollapseOnScroll())
        assertFalse(chrome.topBarCollapseOnScroll.first())
        assertFalse(chrome.peekTopActionsCollapsed())
        assertFalse(chrome.topActionsCollapsed.first())
    }
}
