package com.bilipai.desktop.settings

import com.android.purebilibili.feature.settings.*
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.*

/** The Windows item directory, its matching, the controller's row dispatch and the item focus claim. Needs no window. */
class DesktopSettingsItemSearchTest {
    private val playbackAnchors = setOf("windows_playback_quality", "windows_playback_speed_subtitle",
        "windows_playback_behavior", "windows_playback_comments", "windows_audio_output", "windows_video_enhancement")
    private val appearanceAnchors = setOf(SettingsSearchFocusIds.APPEARANCE_THEME, SettingsSearchFocusIds.APPEARANCE_DISPLAY,
        "windows_display_scale")
    private val mountedTargets = setOf(SettingsSearchTarget.PLAYBACK, SettingsSearchTarget.APPEARANCE,
        SettingsSearchTarget.MESSAGE_NOTIFICATION, SettingsSearchTarget.PRIVACY_PERMISSION)
    private val phoneWords = listOf("权限", "手势", "竖屏", "底栏", "亮度", "陀螺仪", "截图", "引导", "电池", "常驻")

    @AfterEach fun clearSharedFocusState() {
        SettingsSearchFocusController.clear()
        desktopSettingsItemFocus.clear()
    }

    private fun controller(): DesktopSettingsSearchController = DesktopSettingsSearchController(
        DesktopSettingsSearchRepository(DesktopPluginContext(DesktopPluginStore(
            Files.createTempDirectory("bilipai-settings-item-search-")))) { false })

    private fun first(query: String) = resolveDesktopSettingsItemSearch(query).first()

    @Test fun everyDirectoryRowHasAnIdATitleAMountedPageAndOnlyARealGroupAnchor() {
        val directory = desktopSettingsItemDirectory
        assertTrue(directory.size > 40)
        assertEquals(directory.size, directory.map { it.settingId }.toSet().size)
        assertEquals(directory.size, directory.map { it.title }.toSet().size, "A repeated title would make an exact-title hit ambiguous")
        for (item in directory) {
            assertTrue(Regex("[a-z0-9_]+(\\.[a-z0-9_]+)+").matches(item.settingId), item.settingId)
            assertTrue(item.title.isNotBlank(), item.settingId)
            assertTrue(item.target in mountedTargets, item.settingId)
            assertEquals(item.aliases.size, item.aliases.toSet().size, item.settingId)
            assertTrue(item.aliases.all { it.isNotBlank() && it == it.trim() }, item.settingId)
            val allowed = when (item.target) {
                SettingsSearchTarget.PLAYBACK -> playbackAnchors
                SettingsSearchTarget.APPEARANCE -> appearanceAnchors
                else -> emptySet()
            }
            assertTrue(item.focus == null || item.focus in allowed, "${item.settingId} advertises ${item.focus}")
            if (item.target == SettingsSearchTarget.PLAYBACK) assertNotNull(item.focus, item.settingId)
            val text = listOf(item.title, item.description) + item.aliases
            for (word in phoneWords) assertTrue(text.none { word in it }, "${item.settingId} names the removed control $word")
        }
    }

    @Test fun everyRowFindsItselfFirstByItsTitleAndByEachAlias() {
        for (item in desktopSettingsItemDirectory) {
            val byTitle = first(item.title)
            assertEquals(item.settingId, byTitle.item.settingId, item.title)
            assertEquals(7, byTitle.tier, item.title)
            for (alias in item.aliases) {
                val hits = resolveDesktopSettingsItemSearch(alias, maxResults = 500)
                assertTrue(hits.any { it.item.settingId == item.settingId }, "${item.settingId} is not found by $alias")
            }
        }
    }

    @Test fun pinnedQueriesReachTheRowsTheWindowsPagesMount() {
        assertEquals("playback.background_playback_enabled", first("后台播放").item.settingId)
        assertEquals("playback.video_codec_preference", first(" HEVC ").item.settingId)
        assertEquals("playback.subtitle_auto_preference", first("字幕").item.settingId)
        assertEquals("playback.next_watch_hint_enabled", first("接下来观看").item.settingId)
        assertEquals("audio.wasapi_exclusive", first("wasapi 独占").item.settingId)
        assertEquals("appearance.effective_liquid_glass_enabled", first("液态玻璃").item.settingId)
        assertEquals("notification.notify_at_me", first("@我").item.settingId)
        assertEquals("privacy.privacy_mode_enabled", first("无痕").item.settingId)
        // Several words: every word has to match, and the audio output group is where both rows live.
        val audio = resolveDesktopSettingsItemSearch("  wAsApI  输出 ")
        assertTrue(audio.isNotEmpty())
        assertTrue(audio.all { it.item.focus == "windows_audio_output" })
        // A Latin query also matches pinyin: full pinyin and initials of a Chinese title.
        val speed = resolveDesktopSettingsItemSearch("beisu").map { it.item.settingId }
        assertTrue("playback.default_playback_speed" in speed)
        assertTrue("playback.remember_last_playback_speed" in speed)
        assertTrue(resolveDesktopSettingsItemSearch("beisu").all { it.tier == 2 })
        // A word that is in no title and no alias, only in a description, is the weakest tier.
        val fromDescription = resolveDesktopSettingsItemSearch("相对时间规则").single()
        assertEquals("playback.detailed_comment_time_enabled", fromDescription.item.settingId)
        assertEquals(1, fromDescription.tier)
    }

    @Test fun blankUnknownAndRemovedPhoneQueriesFindNothingAndTheLimitHolds() {
        assertTrue(resolveDesktopSettingsItemSearch("   ").isEmpty())
        assertTrue(resolveDesktopSettingsItemSearch("播放", maxResults = 0).isEmpty())
        assertTrue(resolveDesktopSettingsItemSearch("不存在的zqwxj设置").isEmpty())
        for (query in listOf("权限", "通知权限", "手势", "三指截图", "竖屏", "横竖屏", "陀螺仪", "底栏", "亮度", "重新引导", "电池", "常驻服务",
            "Anime4K")) {
            assertTrue(resolveDesktopSettingsItemSearch(query).isEmpty(), "Removed control remains searchable: $query")
        }
        val limited = resolveDesktopSettingsItemSearch("播放", maxResults = 3)
        assertEquals(3, limited.size)
        assertTrue(resolveDesktopSettingsItemSearch("播放").size <= DESKTOP_SETTINGS_ITEM_SEARCH_LIMIT)
        // Best tier first, directory order among equals.
        val tiers = resolveDesktopSettingsItemSearch("播放").map { it.tier }
        assertEquals(tiers.sortedDescending(), tiers)
    }

    @Test fun breadcrumbAndSnippetCarryTheRailTitleTheGroupAndTheDescription() {
        val row = first("后台播放")
        assertEquals("设置 / 播放与音频 / 播放行为", row.path)
        assertEquals(row.item.description, row.snippet)
        assertEquals(row.path + "\n" + row.item.description, row.subtitle)
        val notification = first("开播提醒")
        assertEquals("设置 / 隐私与屏蔽 / 消息通知", notification.path)
        assertEquals(notification.path, notification.subtitle)
        val appearance = first("字体大小")
        assertEquals("设置 / 外观与显示", appearance.path)
        val exactly140 = "甲".repeat(140)
        assertEquals(exactly140, desktopSettingsDescriptionSnippet(exactly140, "x"))
        val windowed = desktopSettingsDescriptionSnippet("前".repeat(100) + "关键词" + "后".repeat(100), "关键词")
        assertTrue(windowed.startsWith("…") && windowed.endsWith("…"))
        assertTrue("关键词" in windowed)
        assertEquals(140, windowed.length - 2)
    }

    @Test fun controllerKeepsTheSectionResultsAndAddsTheRows() {
        val controller = controller()
        for (query in listOf("后台", "字幕", "评论", "通知", "HEVC", "  wAsApI  输出 ", "代理", "DPI", "不存在的zqwxj设置", "   ")) {
            controller.setQuery(query)
            assertEquals(resolveDesktopWindowsSettingsSearchResults(query), controller.results.value, query)
            assertEquals(resolveDesktopSettingsItemSearch(query), controller.itemResults.value, query)
        }
        controller.setQuery("代理")
        assertTrue(controller.itemResults.value.isEmpty())
        assertTrue(controller.results.value.isNotEmpty())
    }

    @Test fun openingAPlaybackRowSubmitsTheGroupFocusAndTheItemRequestBeforeNavigation() {
        val controller = controller()
        controller.setQuery("后台播放")
        val hit = controller.itemResults.value.first()
        var category: SettingsRootCategory? = null
        var detail: SettingsSearchResult? = null
        controller.activateItem(hit, { category = it }, { detail = it })
        assertNull(category)
        val opened = assertNotNull(detail)
        assertEquals(SettingsSearchTarget.PLAYBACK, opened.target)
        assertEquals("windows_playback_behavior", opened.focusId)
        assertEquals("后台播放", opened.title)
        val focus = assertNotNull(SettingsSearchFocusController.request.value)
        assertEquals(SettingsSearchTarget.PLAYBACK to "windows_playback_behavior", focus.target to focus.focusId)
        val item = assertNotNull(desktopSettingsItemFocus.current)
        assertEquals("playback.background_playback_enabled", item.settingId)
        assertEquals("后台播放", item.query)
        // The real navigator opens exactly that detail and keeps the focus request for the anchor.
        val navigator = DesktopSettingsNavigator()
        navigator.openSearchResult(opened)
        assertEquals(DesktopSettingsPage.Detail(SettingsSearchTarget.PLAYBACK, "windows_playback_behavior"), navigator.state.value.current)
        assertEquals(item, desktopSettingsItemFocus.claim(SettingsSearchTarget.PLAYBACK, "windows_playback_behavior"))
    }

    @Test fun openingAPrivacyRowOpensTheCategoryAndAnUnanchoredRowLeavesNoItemRequest() {
        val controller = controller()
        controller.setQuery("无痕")
        var category: SettingsRootCategory? = null
        var detail: SettingsSearchResult? = null
        controller.activateItem(controller.itemResults.value.first(), { category = it }, { detail = it })
        assertEquals(SettingsRootCategory.PRIVACY_PERMISSION, category)
        assertNull(detail)
        assertNull(desktopSettingsItemFocus.current)
        assertNull(SettingsSearchFocusController.request.value)
        category = null
        controller.setQuery("开播提醒")
        controller.activateItem(controller.itemResults.value.first(), { category = it }, { detail = it })
        assertNull(category)
        assertEquals(SettingsSearchTarget.MESSAGE_NOTIFICATION, assertNotNull(detail).target)
        assertNull(desktopSettingsItemFocus.current)
        assertNull(SettingsSearchFocusController.request.value)
    }

    @Test fun anItemRequestIsClaimedOnceByItsOwnGroupAndExpiresUnclaimed() {
        var now = 0L
        val registry = DesktopSettingsItemFocusRegistry { now }
        val item = desktopSettingsItemDirectory.first { it.settingId == "playback.comment_avatar_push_enabled" }
        val request = assertNotNull(registry.submit(item, "  头像  "))
        assertEquals("头像", request.query)
        assertEquals(request, registry.current)
        assertNull(registry.claim(SettingsSearchTarget.PLAYBACK, "windows_playback_behavior"), "Another group must not claim it")
        assertNull(registry.claim(SettingsSearchTarget.APPEARANCE, "windows_playback_comments"), "Another page must not claim it")
        assertEquals(request, registry.claim(SettingsSearchTarget.PLAYBACK, "windows_playback_comments"))
        assertNull(registry.claim(SettingsSearchTarget.PLAYBACK, "windows_playback_comments"), "A request is claimed once")
        registry.finish(request)
        assertNull(registry.current)

        val second = assertNotNull(registry.submit(item, "头像"))
        assertTrue(second.token != request.token)
        now += DesktopSettingsItemHighlightTiming.UNCLAIMED_TIMEOUT_MILLIS * 1_000_000L
        assertEquals(second, registry.claim(SettingsSearchTarget.PLAYBACK, "windows_playback_comments"), "The timeout is exclusive")
        val third = assertNotNull(registry.submit(item, "头像"))
        now += DesktopSettingsItemHighlightTiming.UNCLAIMED_TIMEOUT_MILLIS * 1_000_000L + 1
        assertNull(registry.claim(SettingsSearchTarget.PLAYBACK, "windows_playback_comments"), "A request nobody claimed in time is dropped")
        assertNull(registry.current)
        registry.finish(third)

        val fourth = assertNotNull(registry.submit(item, "头像"))
        val newer = assertNotNull(registry.submit(item, "评论"))
        registry.finish(fourth)
        assertEquals(newer, registry.current, "Finishing an old request leaves the newer one")
        val unanchored = desktopSettingsItemDirectory.first { it.focus == null }
        assertNull(registry.submit(unanchored, "x"))
        assertNull(registry.current)
        assertEquals(1_600L, DesktopSettingsItemHighlightTiming.HOLD_MILLIS)
        assertEquals(280, DesktopSettingsItemHighlightTiming.FADE_MILLIS)
        assertEquals(5_000L, DesktopSettingsItemHighlightTiming.UNCLAIMED_TIMEOUT_MILLIS)
    }

    @Test fun aSectionThatARowAlreadyOpensIsNotListedTwice() {
        val controller = controller()
        controller.setQuery("后台播放")
        val rows = controller.itemResults.value
        val sections = controller.results.value
        assertTrue(sections.any { it.focusId == "windows_playback_behavior" })
        val visible = desktopSettingsVisibleSectionResults(sections, rows)
        assertTrue(visible.none { it.focusId == "windows_playback_behavior" })
        assertEquals(sections, desktopSettingsVisibleSectionResults(sections, emptyList()))
        // A section that no row opens stays: 代理 has the system section and no row.
        controller.setQuery("代理")
        assertEquals(controller.results.value, desktopSettingsVisibleSectionResults(controller.results.value, controller.itemResults.value))
    }
}
