package com.bilipai.desktop.settings

import com.android.purebilibili.feature.settings.*
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.*

/** Source identity only: expectations never call or reproduce the upstream matcher. */
@Suppress("UNCHECKED_CAST")
private val upstreamSectionEntries: List<SettingsSearchEntry> by lazy {
    Class.forName("com.android.purebilibili.feature.settings.SettingsSearchPolicyKt")
        .getDeclaredField("SETTINGS_SEARCH_INDEX").apply { isAccessible = true }.get(null) as List<SettingsSearchEntry>
}

/** Preserve every required desktop anchor once; additional matches must be available and really navigable. */
internal fun assertDesktopQueryContract(
    controller: DesktopSettingsSearchController,
    vararg required: Pair<SettingsSearchTarget, String?>,
): List<SettingsSearchResult> {
    val query = controller.query.value
    val results = controller.results.value
    for ((target, focus) in required) {
        assertEquals(1, results.count { it.target == target && it.focusId == focus },
            "$query: required desktop result $target/$focus must occur exactly once: $results")
    }
    val landings = results.map { result ->
        val row = desktopSettingsTargetPlacement.getValue(result.target.name)
        assertEquals(DesktopPlacementTreatment.available, row.treatment, "$query: ${result.target}")
        if ((result.target to result.focusId) !in required) {
            val section = upstreamSectionEntries.singleOrNull { it.settingId == result.settingId }
            val item = settingsItemDirectory.singleOrNull { it.settingId == result.settingId }
            assertTrue(section != null || item != null, "$query: untraceable extra result $result")
            assertEquals(section?.target ?: item!!.target, result.target, query)
            assertEquals(section?.title ?: item!!.title, result.title, query)
            if (item != null) assertEquals(DesktopPlacementTreatment.available,
                desktopSettingsItemPlacement.getValue(item.settingId).treatment, query)
        }
        val category = SettingsRootCategory.valueOf(row.desktopCategory)
        assertTrue(category in DESKTOP_SETTINGS_RAIL_CATEGORIES, "$query: unmounted category $category")
        val expected = when (row.destination) {
            "detail" -> DesktopSettingsPage.Detail(result.target, result.focusId)
            "category" -> when (category) {
                SettingsRootCategory.APPEARANCE_THEME -> DesktopSettingsPage.Detail(SettingsSearchTarget.APPEARANCE, null)
                SettingsRootCategory.PLUGINS_EXTENSIONS -> DesktopSettingsPage.Detail(SettingsSearchTarget.PLUGINS, null)
                else -> DesktopSettingsPage.Category(category)
            }
            else -> fail("$query: invalid destination $row")
        }
        if (expected is DesktopSettingsPage.Detail) {
            // Actual detail branches in DesktopSettingsTree, not every enum the navigator can push.
            assertTrue(expected.target in setOf(SettingsSearchTarget.PLAYBACK, SettingsSearchTarget.HOME_FEED,
                SettingsSearchTarget.APPEARANCE, SettingsSearchTarget.PLUGINS, SettingsSearchTarget.MESSAGE_NOTIFICATION,
                SettingsSearchTarget.BLOCKED_LIST, SettingsSearchTarget.TIPS, SettingsSearchTarget.OPEN_SOURCE_LICENSES,
                SettingsSearchTarget.WEBDAV_BACKUP, SettingsSearchTarget.SETTINGS_SHARE, SettingsSearchTarget.DOWNLOAD_PATH,
                SettingsSearchTarget.CLEAR_CACHE, SettingsSearchTarget.IMAGE_SAVE_PATH), "$query: unmounted detail $expected")
        }
        val navigator = DesktopSettingsNavigator()
        var navigations = 0
        try {
            controller.activate(result, { navigations++; navigator.openCategory(it) },
                { navigations++; navigator.openSearchResult(it) })
            assertEquals(1, navigations, query)
            assertEquals(expected, navigator.state.value.current, "$query: $result")
            val focus = (expected as? DesktopSettingsPage.Detail)?.focusId
            if (focus == null) assertNull(SettingsSearchFocusController.request.value, query)
            else {
                val request = assertNotNull(SettingsSearchFocusController.request.value, query)
                assertEquals((expected as DesktopSettingsPage.Detail).target, request.target, query)
                assertEquals(focus, request.focusId, query)
            }
            assertTrue(navigator.pop(), query)
            assertNull(SettingsSearchFocusController.request.value, "$query: focus leaked after Back")
        } finally {
            SettingsSearchFocusController.clear()
        }
        expected
    }
    assertEquals(landings.size, landings.toSet().size, "$query: duplicate final destinations")
    return results
}

/** Tests the installed Windows controller/catalog and navigation contracts, not labels. */
class DesktopWindowsSettingsCatalogTest {
    // Keyed on what DesktopSettingsTree mounts: MESSAGE_NOTIFICATION is a Detail page opened from the privacy category.
    private val mountedTargets = setOf(SettingsSearchTarget.PLAYBACK, SettingsSearchTarget.HOME_FEED,
        SettingsSearchTarget.APPEARANCE, SettingsSearchTarget.PRIVACY_PERMISSION,
        SettingsSearchTarget.MESSAGE_NOTIFICATION,
        SettingsSearchTarget.DATA_BACKUP, SettingsSearchTarget.PLUGINS, SettingsSearchTarget.DIAGNOSTICS)
    private val removedTargets = setOf(SettingsSearchTarget.PERMISSION,
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
            SettingsSearchTarget.APPEARANCE to DARK_THEME_SCHEDULE_FOCUS_ID,
            SettingsSearchTarget.PLAYBACK to "windows_video_enhancement")
        assertEquals(expectedFocusAnchors, focusEntries.map { it.target to it.focus }.toSet())
        // windows_audio_output is advertised by two entries; the dark theme schedule is the ninth anchored entry.
        assertEquals(9, focusEntries.size)
        // No obsolete original phone focus token is advertised by the new controller.
        assertTrue(desktopWindowsSettingsSearchEntries.all { it.focus == null ||
            (it.target to it.focus) in expectedFocusAnchors })
    }

    @Test fun actualControllerCannotFindPhonePermissionGestureOrPortraitControls() {
        val controller = controller()
        for (query in listOf("权限", "通知权限", "手势", "三指截图", "竖屏", "横竖屏", "陀螺仪", "底栏", "亮度", "重新引导")) {
            controller.setQuery(query)
            assertEquals(query, controller.query.value)
            if (query in setOf("竖屏", "横竖屏")) {
                assertEquals(setOf(SettingsSearchTarget.TIPS), controller.results.value.map { it.target }.toSet(), query)
                val tips = controller.results.value.single()
                val navigator = DesktopSettingsNavigator()
                controller.activate(tips, navigator::openCategory, navigator::openSearchResult)
                assertEquals(DesktopSettingsPage.Detail(SettingsSearchTarget.TIPS, null), navigator.state.value.current)
                // Compiled PortraitVideoPager.kt:2360/2694/2703 consumes this preference; no other portrait item is available.
                val haze = settingsItemDirectory.single { it.settingId == "playback.portrait_letterbox_ambient_haze" }
                val matches = (listOf(haze.title) + haze.aliases).any { it.trim().lowercase().contains(query.trim().lowercase()) }
                assertEquals(if (matches) setOf(haze.settingId) else emptySet(),
                    controller.itemResults.value.map { it.item.settingId }.toSet(), query)
                assertEquals(DesktopPlacementTreatment.available, desktopSettingsItemPlacement.getValue(haze.settingId).treatment)
            } else {
                assertTrue(controller.results.value.isEmpty(), "Removed control remains searchable: $query")
            }
        }
        controller.setQuery("   ")
        assertTrue(controller.results.value.isEmpty())
    }

    @Test fun desktopQueriesUseActualControllerAndKeepPlaybackBehaviorsAccessible() {
        val controller = controller()
        val playbackAnchors = mapOf("后台" to "windows_playback_behavior", "续播" to "windows_playback_behavior",
            "断点" to "windows_playback_behavior", "循环" to "windows_playback_behavior",
            "排序" to "windows_playback_comments", "详细时间" to "windows_playback_comments",
            "字幕" to "windows_playback_speed_subtitle", "HEVC" to "windows_playback_quality", "光纤" to "windows_audio_output")
        for ((query, focus) in playbackAnchors) {
            controller.setQuery(query)
            val results = controller.results.value
            assertTrue(results.isNotEmpty(), "Missing desktop control: $query")
            // The old audio subtitle is "后续播放的音轨质量偏好", so 续播 also retains its audio anchor.
            val required = listOf(SettingsSearchTarget.PLAYBACK to focus) +
                if (query == "续播") listOf(SettingsSearchTarget.PLAYBACK to "windows_audio_output") else emptyList()
            assertDesktopQueryContract(controller, *required.toTypedArray())
        }
        controller.setQuery("评论")
        val comments = controller.results.value
        assertDesktopQueryContract(controller, SettingsSearchTarget.PLAYBACK to "windows_playback_comments",
            SettingsSearchTarget.HOME_FEED to null)
        assertEquals("windows_playback_comments",
            comments.single { it.target == SettingsSearchTarget.PLAYBACK }.focusId)
        for (query in listOf("动态评论预览", "评论预览")) {
            controller.setQuery(query)
            assertDesktopQueryContract(controller, SettingsSearchTarget.HOME_FEED to null)
            val preview = controller.results.value.single { it.target == SettingsSearchTarget.HOME_FEED }
            assertEquals(SettingsSearchTarget.HOME_FEED, preview.target)
            assertNull(preview.focusId)
            var category: SettingsRootCategory? = null
            var destination: SettingsSearchResult? = null
            controller.activate(preview, { category = it }, { destination = it })
            val placement = desktopSettingsTargetPlacement.getValue(preview.target.name)
            assertEquals("detail", placement.destination)
            assertNull(category)
            assertEquals(preview, destination)
            val navigator = DesktopSettingsNavigator()
            navigator.openSearchResult(preview)
            assertEquals(DesktopSettingsPage.Detail(preview.target, null), navigator.state.value.current)
            assertNull(SettingsSearchFocusController.request.value)
        }
        controller.setQuery("  wAsApI  输出 ")
        val audio = assertDesktopQueryContract(controller, SettingsSearchTarget.PLAYBACK to "windows_audio_output")
            .single { it.target == SettingsSearchTarget.PLAYBACK && it.focusId == "windows_audio_output" }
        assertEquals(SettingsSearchTarget.PLAYBACK, audio.target)
        assertEquals("windows_audio_output", audio.focusId)
        controller.setQuery("代理")
        val proxy = assertDesktopQueryContract(controller, SettingsSearchTarget.DIAGNOSTICS to null)
            .single { it.target == SettingsSearchTarget.DIAGNOSTICS }
        assertEquals(SettingsSearchTarget.DIAGNOSTICS, proxy.target)
        var proxyCategory: SettingsRootCategory? = null
        controller.activate(proxy, { proxyCategory = it }, { fail("Proxy must reach the system category") })
        assertEquals(SettingsRootCategory.SYSTEM_ABOUT, proxyCategory)
        controller.setQuery("DPI")
        val dpi = assertDesktopQueryContract(controller, SettingsSearchTarget.APPEARANCE to "windows_display_scale")
            .single { it.target == SettingsSearchTarget.APPEARANCE && it.focusId == "windows_display_scale" }
        assertEquals(SettingsSearchTarget.APPEARANCE, dpi.target)
        assertEquals("windows_display_scale", dpi.focusId)
        controller.setQuery("Ctrl")
        assertEquals("windows_display_scale", assertDesktopQueryContract(controller,
            SettingsSearchTarget.APPEARANCE to "windows_display_scale").single { it.focusId == "windows_display_scale" }.focusId)
    }

    @Test fun nvidiaQueriesReachOnePlaybackFocusAndOldAlgorithmsAreNotSearchable() {
        val controller = controller()
        for (query in listOf("NVIDIA", "RTX", "VSR", "HDR", "超分辨率")) {
            controller.setQuery(query)
            val result = assertDesktopQueryContract(controller, SettingsSearchTarget.PLAYBACK to "windows_video_enhancement")
                .single { it.target == SettingsSearchTarget.PLAYBACK && it.focusId == "windows_video_enhancement" }
            assertEquals(SettingsSearchTarget.PLAYBACK, result.target)
            assertEquals("windows_video_enhancement", result.focusId)
        }
        for (query in listOf("Anime4K", "FSR", "CNN")) {
            controller.setQuery(query)
            assertTrue(controller.results.value.isEmpty(), "Obsolete algorithm remains searchable: $query")
        }
    }

    @Test fun mountedMessageNotificationDetailIsSearchableAndOpensThroughTheRealNavigator() {
        val controller = controller()
        try {
            for (query in listOf("通知", "消息通知", "私信", "@我", "点赞", "开播提醒", "检查频率")) {
                controller.setQuery(query)
                val result = assertDesktopQueryContract(controller, SettingsSearchTarget.MESSAGE_NOTIFICATION to null)
                    .single { it.target == SettingsSearchTarget.MESSAGE_NOTIFICATION && it.focusId == null }
                assertEquals(SettingsSearchTarget.MESSAGE_NOTIFICATION, result.target, query)
                assertNull(result.focusId, query)
                var category: SettingsRootCategory? = null
                var detail: SettingsSearchResult? = null
                controller.activate(result, { category = it }, { detail = it })
                assertNull(category, query)
                assertEquals(result, detail, query)
                assertNull(SettingsSearchFocusController.request.value, query)
                // DesktopSettingsTree renders exactly this Detail page with DesktopMessageNotificationSettingsContent.
                val navigator = DesktopSettingsNavigator()
                navigator.openSearchResult(result)
                assertEquals(DesktopSettingsPage.Detail(SettingsSearchTarget.MESSAGE_NOTIFICATION, null),
                    navigator.state.value.current, query)
            }
            // The mounted page has no Android permission, battery or resident-service control to find.
            for (query in listOf("通知权限", "权限", "电池", "常驻服务")) {
                controller.setQuery(query)
                assertTrue(controller.results.value.isEmpty(), "Removed notification control is searchable: $query")
            }
        } finally {
            SettingsSearchFocusController.request.value?.let { SettingsSearchFocusController.clear(it.token) }
        }
    }

    @Test fun mountedControlsWithoutTheirOwnRowAreReachableByTheirLabels() {
        val controller = controller()
        val labels = mapOf(
            SettingsSearchTarget.PRIVACY_PERMISSION to listOf("无痕", "不新增", "我的回顾", "默认搜索词"),
            SettingsSearchTarget.DATA_BACKUP to listOf("下载位置", "图片保存位置", "清除缓存", "自动清理缓存", "缓存容量上限", "设置分享"),
            SettingsSearchTarget.APPEARANCE to listOf("图标样式", "列表样式", "点击文字复制", "入场动画", "女仆"),
            SettingsSearchTarget.DIAGNOSTICS to listOf("自动检查更新", "彩蛋"))
        for ((target, queries) in labels) {
            for (query in queries) {
                controller.setQuery(query)
                assertDesktopQueryContract(controller, target to null)
            }
        }
    }

    @Test fun autoContinueGateSwitchesReachThePlaybackBehaviorGroup() {
        val controller = controller()
        for (query in listOf("自动播放下一个", "列表连续播放", "收藏夹")) {
            controller.setQuery(query)
            val result = assertDesktopQueryContract(controller, SettingsSearchTarget.PLAYBACK to "windows_playback_behavior")
                .single { it.target == SettingsSearchTarget.PLAYBACK && it.focusId == "windows_playback_behavior" }
            assertEquals(SettingsSearchTarget.PLAYBACK, result.target, query)
            assertEquals("windows_playback_behavior", result.focusId, query)
        }
    }

    @Test fun eachPublishedResultDispatchesToTheMountedCategoryOrExactDetailAndFocus() {
        val controller = controller()
        try {
            for (entry in desktopWindowsSettingsSearchEntries) {
                // Search by an actual catalog alias, then feed the controller's real result back.
                controller.setQuery(entry.words.first())
                // Existing Windows overlaps: comment aliases, appearance sections, and notification privacy section.
                val required = listOf(entry.target to entry.focus) + when (entry.words.first()) {
                    "评论" -> listOf(SettingsSearchTarget.HOME_FEED to null)
                    "外观" -> listOf(SettingsSearchTarget.APPEARANCE to DARK_THEME_SCHEDULE_FOCUS_ID,
                        SettingsSearchTarget.APPEARANCE to "windows_display_scale")
                    "隐私" -> listOf(SettingsSearchTarget.MESSAGE_NOTIFICATION to null)
                    else -> emptyList()
                }
                assertDesktopQueryContract(controller, *required.toTypedArray())
                val result = controller.results.value.single { it.target == entry.target && it.title == entry.title }
                assertTrue(result.target in mountedTargets)
                var category: SettingsRootCategory? = null
                var detail: SettingsSearchResult? = null
                controller.activate(result, { category = it }, { detail = it })
                val placement = desktopSettingsTargetPlacement.getValue(result.target.name)
                if (result.target == SettingsSearchTarget.HOME_FEED && placement.destination == "detail") {
                    assertNull(category)
                    assertEquals(result, detail)
                    val navigator = DesktopSettingsNavigator()
                    navigator.openSearchResult(result)
                    assertEquals(DesktopSettingsPage.Detail(result.target, result.focusId), navigator.state.value.current)
                } else if (isSceneSettingsSearchTarget(result.target)) {
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
