package com.bilipai.desktop.settings

import com.android.purebilibili.feature.settings.*
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.*

/** Tests the installed Windows controller/catalog and navigation contracts, not labels. */
class DesktopWindowsSettingsCatalogTest {
    private val mountedTargets = setOf(SettingsSearchTarget.PLAYBACK, SettingsSearchTarget.HOME_FEED,
        SettingsSearchTarget.APPEARANCE, SettingsSearchTarget.PRIVACY_PERMISSION,
        SettingsSearchTarget.DATA_BACKUP, SettingsSearchTarget.PLUGINS, SettingsSearchTarget.DIAGNOSTICS)
    private val removedTargets = setOf(SettingsSearchTarget.PERMISSION, SettingsSearchTarget.MESSAGE_NOTIFICATION,
        SettingsSearchTarget.FULLSCREEN_GESTURE, SettingsSearchTarget.BOTTOM_BAR, SettingsSearchTarget.NAVIGATION,
        SettingsSearchTarget.ANIMATION, SettingsSearchTarget.REPLAY_ONBOARDING)

    private fun controller(): DesktopSettingsSearchController = DesktopSettingsSearchController(
        DesktopSettingsSearchRepository(DesktopPluginContext(DesktopPluginStore(
            Files.createTempDirectory("bilipai-windows-settings-search-")))) { false })

    @Test fun catalogTargetsAreMountedWindowsPagesWithOnlyActualFocusAnchors() {
        val targets = desktopWindowsSettingsSearchEntries.map { it.target }.toSet()
        assertEquals(mountedTargets, targets)
        assertTrue(targets.intersect(removedTargets).isEmpty())
        val focusEntries = desktopWindowsSettingsSearchEntries.filter { it.focus != null }
        val expectedFocusAnchors = setOf(SettingsSearchTarget.PLAYBACK to "windows_playback_quality",
            SettingsSearchTarget.PLAYBACK to "windows_playback_speed_subtitle",
            SettingsSearchTarget.PLAYBACK to "windows_playback_behavior",
            SettingsSearchTarget.PLAYBACK to "windows_playback_comments",
            SettingsSearchTarget.PLAYBACK to "windows_audio_output",
            SettingsSearchTarget.APPEARANCE to "windows_display_scale",
            SettingsSearchTarget.PLAYBACK to "windows_video_enhancement")
        assertEquals(expectedFocusAnchors, focusEntries.map { it.target to it.focus }.toSet())
        assertEquals(8, focusEntries.size)
        // No obsolete original phone focus token is advertised by the new controller.
        assertTrue(desktopWindowsSettingsSearchEntries.all { it.focus == null ||
            (it.target to it.focus) in expectedFocusAnchors })
    }

    @Test fun actualControllerCannotFindPhonePermissionGestureOrPortraitControls() {
        val controller = controller()
        for (query in listOf("权限", "通知权限", "手势", "三指截图", "竖屏", "横竖屏", "陀螺仪", "底栏", "亮度", "重新引导")) {
            controller.setQuery(query)
            assertEquals(query, controller.query.value)
            assertTrue(controller.results.value.isEmpty(), "Removed control remains searchable: $query")
        }
        controller.setQuery("   ")
        assertTrue(controller.results.value.isEmpty())
    }

    @Test fun desktopQueriesUseActualControllerAndKeepPlaybackBehaviorsAccessible() {
        val controller = controller()
        for (query in listOf("后台", "续播", "断点", "循环", "评论", "排序", "详细时间", "字幕", "HEVC", "光纤")) {
            controller.setQuery(query)
            val results = controller.results.value
            assertTrue(results.isNotEmpty(), "Missing desktop control: $query")
            assertTrue(results.all { it.target == SettingsSearchTarget.PLAYBACK })
        }
        controller.setQuery("  wAsApI  输出 ")
        val audio = controller.results.value.single()
        assertEquals(SettingsSearchTarget.PLAYBACK, audio.target)
        assertEquals("windows_audio_output", audio.focusId)
        controller.setQuery("代理")
        assertEquals(SettingsSearchTarget.DIAGNOSTICS, controller.results.value.single().target)
        var proxyCategory: SettingsRootCategory? = null
        controller.activate(controller.results.value.single(), { proxyCategory = it }, { fail("Proxy must reach the system category") })
        assertEquals(SettingsRootCategory.SYSTEM_ABOUT, proxyCategory)
        controller.setQuery("DPI")
        assertEquals(SettingsSearchTarget.APPEARANCE, controller.results.value.single().target)
        assertEquals("windows_display_scale", controller.results.value.single().focusId)
        controller.setQuery("Ctrl")
        assertEquals("windows_display_scale", controller.results.value.single().focusId)
    }

    @Test fun nvidiaQueriesReachOnePlaybackFocusAndOldAlgorithmsAreNotSearchable() {
        val controller = controller()
        for (query in listOf("NVIDIA", "RTX", "VSR", "HDR", "超分辨率")) {
            controller.setQuery(query)
            val result = controller.results.value.single()
            assertEquals(SettingsSearchTarget.PLAYBACK, result.target)
            assertEquals("windows_video_enhancement", result.focusId)
        }
        for (query in listOf("Anime4K", "FSR", "CNN")) {
            controller.setQuery(query)
            assertTrue(controller.results.value.isEmpty(), "Obsolete algorithm remains searchable: $query")
        }
    }

    @Test fun eachPublishedResultDispatchesToTheMountedCategoryOrExactDetailAndFocus() {
        val controller = controller()
        try {
            for (entry in desktopWindowsSettingsSearchEntries) {
                // Search by an actual catalog alias, then feed the controller's real result back.
                controller.setQuery(entry.words.first())
                val result = controller.results.value.single { it.target == entry.target && it.title == entry.title }
                assertTrue(result.target in mountedTargets)
                var category: SettingsRootCategory? = null
                var detail: SettingsSearchResult? = null
                controller.activate(result, { category = it }, { detail = it })
                if (isSceneSettingsSearchTarget(result.target)) {
                    assertEquals(resolveSettingsRootCategoryForSearchTarget(result.target), category)
                    assertNull(detail)
                } else {
                    assertNull(category)
                    assertEquals(result, detail)
                    if (result.focusId == null) {
                        assertNull(SettingsSearchFocusController.request.value)
                    } else {
                        val request = assertNotNull(SettingsSearchFocusController.request.value)
                        assertEquals(result.target, request.target)
                        assertEquals(result.focusId, request.focusId)
                        SettingsSearchFocusController.clear(request.token)
                    }
                }
            }
        } finally {
            SettingsSearchFocusController.request.value?.let { SettingsSearchFocusController.clear(it.token) }
        }
    }
}
