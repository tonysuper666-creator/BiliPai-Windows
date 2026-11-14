package com.bilipai.desktop.settings

import com.android.purebilibili.feature.settings.SettingsRootCategory
import com.android.purebilibili.feature.settings.SettingsSearchFocusController
import com.android.purebilibili.feature.settings.SettingsSearchResult
import com.android.purebilibili.feature.settings.SettingsSearchTarget
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.ui.DesktopWindowsUxSettingsStrings
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.*

class DesktopWindowsUxSearchTest {
    @AfterEach fun clearFocus() {
        SettingsSearchFocusController.clear()
        desktopSettingsItemFocus.clear()
    }

    @Test fun enabledLayoutControlsOpenTheirActualAppearanceAnchor() {
        val expected = mapOf(
            "windows_ux.rail_mode" to DesktopWindowsUxSettingsStrings.RAIL,
            "windows_ux.player_layout" to DesktopWindowsUxSettingsStrings.PLAYER,
            "windows_ux.details_panel" to DesktopWindowsUxSettingsStrings.DETAILS,
            "windows_ux.details_panel_width" to DesktopWindowsUxSettingsStrings.DETAILS_WIDTH,
            "windows_ux.home_layout" to DesktopWindowsUxSettingsStrings.HOME,
            "windows_ux.card_interactions" to DesktopWindowsUxSettingsStrings.CARDS,
            "windows_ux.reset_layout" to DesktopWindowsUxSettingsStrings.RESET,
        )
        assertEquals(expected.keys, desktopSearchableWindowsUxIds)
        assertEquals(expected.keys, desktopSettingsItemDirectory.filter { it.settingId.startsWith("windows_ux.") }.map { it.settingId }.toSet())
        val repository = DesktopSettingsSearchRepository(DesktopPluginContext(DesktopPluginStore(
            Files.createTempDirectory("bilipai-windows-ux-search-")))) { false }
        val controller = DesktopSettingsSearchController(repository)
        for ((id, title) in expected) {
            controller.setQuery(title)
            val hit = controller.itemResults.value.single { it.item.settingId == id }
            var category: SettingsRootCategory? = null
            var detail: SettingsSearchResult? = null
            controller.activateItem(hit, { category = it }, { detail = it })
            assertNull(category, id)
            assertEquals(SettingsSearchTarget.APPEARANCE, assertNotNull(detail, id).target, id)
            assertEquals("windows_display_scale", detail?.focusId, id)
            val navigator = DesktopSettingsNavigator()
            navigator.openSearchResult(detail!!)
            assertEquals(DesktopSettingsPage.Detail(SettingsSearchTarget.APPEARANCE, "windows_display_scale"), navigator.state.value.current, id)
            assertEquals(id, desktopSettingsItemFocus.claim(SettingsSearchTarget.APPEARANCE, "windows_display_scale")?.settingId, id)
        }
    }

    /**
     * The wheel / click / double-click pointer actions have neither a control nor a consumer in the
     * Windows layout group, so they must never look operable from search. The player layout, the
     * details panel and its width slider do have mounted, enabled controls (see
     * [com.bilipai.desktop.settings.DesktopWindowsUxSettings] and the PR196 consumer test), which is
     * exactly why those three rows are registered above.
     */
    @Test fun pointerActionsWithoutAConsumerAreNotSearchable() {
        val prohibited = setOf("windows_ux.wheel_action", "windows_ux.click_action", "windows_ux.double_click_action")
        assertTrue(desktopSettingsItemDirectory.none { it.settingId in prohibited })
        for (query in listOf("滚轮操作", "单击操作", "双击操作")) {
            assertTrue(resolveDesktopSettingsItemSearch(query).isEmpty(), query)
        }
    }
}
