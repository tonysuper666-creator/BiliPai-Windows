package com.bilipai.desktop.settings

import com.android.purebilibili.feature.settings.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DesktopSettingsNavigatorTest {
    @AfterEach fun clearFocus() = SettingsSearchFocusController.clear()

    @Test fun categoryActionsDoNotProjectToAnUnrelatedDetail() {
        val navigator = DesktopSettingsNavigator()
        navigator.openSearch()
        val result = resolveSettingsSearchResults("检查更新", 20).single { it.target == SettingsSearchTarget.CHECK_UPDATE }
        navigator.openSearchResult(result)
        assertEquals(DesktopSettingsPage.Category(SettingsRootCategory.SYSTEM_ABOUT), navigator.state.value.current)
        assertNull(SettingsSearchFocusController.request.value)
        assertTrue(navigator.pop())
        val diagnostics = resolveSettingsSearchResults("诊断", 20).single { it.target == SettingsSearchTarget.DIAGNOSTICS }
        navigator.openSearchResult(diagnostics)
        assertEquals(DesktopSettingsPage.Category(SettingsRootCategory.SYSTEM_ABOUT), navigator.state.value.current)
        assertTrue(navigator.pop())
        val fullscreen = resolveSettingsSearchResults("全屏", 20).single { it.target == SettingsSearchTarget.FULLSCREEN_GESTURE }
        navigator.openSearchResult(fullscreen)
        assertEquals(DesktopSettingsPage.Detail(SettingsSearchTarget.PLAYBACK, SettingsSearchFocusIds.PLAYBACK_FULLSCREEN), navigator.state.value.current)
    }

    @Test fun detailBackKeepsSearchEntryWhileANewSearchGetsANewToken() {
        val navigator = DesktopSettingsNavigator()
        navigator.openSearch()
        val token = navigator.state.value.searchEntryToken
        navigator.openDetail(SettingsSearchTarget.APPEARANCE, SettingsSearchFocusIds.APPEARANCE_DISPLAY)
        assertEquals(token, navigator.state.value.searchEntryToken)
        assertTrue(navigator.pop())
        assertEquals(DesktopSettingsPage.Search(token!!), navigator.state.value.current)
        assertNull(SettingsSearchFocusController.request.value)
        assertTrue(navigator.pop())
        assertFalse(navigator.pop())
        navigator.openSearch()
        assertNotEquals(token, navigator.state.value.searchEntryToken)
    }

    @Test fun originalCategoriesDirectlyOpenOnlyTheirOriginalDestinations() {
        val navigator = DesktopSettingsNavigator()
        navigator.openCategory(SettingsRootCategory.APPEARANCE_THEME)
        assertEquals(DesktopSettingsPage.Detail(SettingsSearchTarget.APPEARANCE, null), navigator.state.value.current)
        navigator.openRoot()
        navigator.openCategory(SettingsRootCategory.PLUGINS_EXTENSIONS)
        assertEquals(DesktopSettingsPage.Detail(SettingsSearchTarget.PLUGINS, null), navigator.state.value.current)
        navigator.openRoot()
        navigator.openCategory(SettingsRootCategory.PRIVACY_PERMISSION)
        assertEquals(DesktopSettingsPage.Category(SettingsRootCategory.PRIVACY_PERMISSION), navigator.state.value.current)
    }

    @Test fun leavingTheSettingsSubtreeRetiresSearchAndPendingFocus() {
        val navigator = DesktopSettingsNavigator()
        navigator.openSearch()
        navigator.openDetail(SettingsSearchTarget.PLAYBACK, SettingsSearchFocusIds.PLAYBACK_DECODER)
        assertNotNull(SettingsSearchFocusController.request.value)
        navigator.leave()
        assertEquals(listOf(DesktopSettingsPage.Root), navigator.state.value.stack)
        assertNull(navigator.state.value.searchEntryToken)
        assertNull(SettingsSearchFocusController.request.value)
    }

    @Test fun deniedBackupEntryDoesNotPushOrChangeExistingSearchFocus() {
        var canEdit = true
        val navigator = DesktopSettingsNavigator { target ->
            target !in setOf(SettingsSearchTarget.WEBDAV_BACKUP, SettingsSearchTarget.SETTINGS_SHARE) || canEdit
        }
        navigator.openDetail(SettingsSearchTarget.PLAYBACK, SettingsSearchFocusIds.PLAYBACK_DECODER)
        val existing = navigator.state.value
        val focus = SettingsSearchFocusController.request.value
        canEdit = false
        navigator.openDetail(SettingsSearchTarget.WEBDAV_BACKUP, null)
        navigator.openDetail(SettingsSearchTarget.SETTINGS_SHARE, null)
        assertSame(existing, navigator.state.value)
        assertSame(focus, SettingsSearchFocusController.request.value)
        canEdit = true
        navigator.openDetail(SettingsSearchTarget.WEBDAV_BACKUP, null)
        assertEquals(DesktopSettingsPage.Detail(SettingsSearchTarget.WEBDAV_BACKUP, null), navigator.state.value.current)
    }
}
