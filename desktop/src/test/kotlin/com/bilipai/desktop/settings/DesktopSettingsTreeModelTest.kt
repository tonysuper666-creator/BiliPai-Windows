package com.bilipai.desktop.settings

import com.android.purebilibili.feature.settings.SettingsRootCategory
import com.android.purebilibili.feature.settings.SettingsSearchFocusController
import com.android.purebilibili.feature.settings.SettingsSearchFocusIds
import com.android.purebilibili.feature.settings.SettingsSearchTarget
import com.bilipai.desktop.ui.DesktopWindowsSettingsStrings
import com.bilipai.desktop.ux.DesktopUxContract
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The category rules of the settings tree and the landing of the navigator against the shared contract
 * ("settings.tree"). Plain JVM: the navigator holds only state and the focus controller. The pinned
 * DesktopSettingsNavigatorTest is not edited; this class adds the rail behaviour beside it.
 */
class DesktopSettingsTreeModelTest {
    private val tree = DesktopUxContract.section("settings").getValue("tree").jsonObject

    @AfterEach fun clearFocus() = SettingsSearchFocusController.clear()

    private fun describe(page: DesktopSettingsPage): String = when (page) {
        DesktopSettingsPage.Root -> "Root"
        is DesktopSettingsPage.Search -> "Search"
        is DesktopSettingsPage.Category -> "Category:" + page.category.name
        is DesktopSettingsPage.Detail -> "Detail:" + page.target.name
        is DesktopSettingsPage.CommentFraudHistory -> "CommentFraud"
    }

    private fun run(navigator: DesktopSettingsNavigator, steps: List<String>) {
        for (step in steps) {
            val name = step.substringBefore(':')
            val argument = step.substringAfter(':', "")
            when (name) {
                "root" -> navigator.openRoot()
                "land" -> navigator.landOnLastCategory()
                "rail" -> navigator.replaceCategory(SettingsRootCategory.valueOf(argument))
                "open_category" -> navigator.openCategory(SettingsRootCategory.valueOf(argument))
                "open_detail" -> navigator.openDetail(SettingsSearchTarget.valueOf(argument), null)
                "search" -> navigator.openSearch()
                "pop" -> navigator.pop()
                else -> error("unknown step $step")
            }
        }
    }

    @Test fun theRailListsTheContractCategoriesInOrderWithTheirTitles() {
        val expected = DesktopUxContract.strings(tree.getValue("rail"))
        assertEquals(expected, DESKTOP_SETTINGS_RAIL_CATEGORIES.map { it.name })
        assertEquals(DesktopUxContract.string(tree, "default_category"), DESKTOP_SETTINGS_DEFAULT_CATEGORY.name)
        for (category in DESKTOP_SETTINGS_RAIL_CATEGORIES) assertNotNull(DesktopWindowsSettingsStrings.railTitle(category), category.name)
        assertEquals(7, DESKTOP_SETTINGS_RAIL_CATEGORIES.toSet().size)
        // Landing is the first rail entry, the playback and audio page.
        assertSame(DESKTOP_SETTINGS_RAIL_CATEGORIES.first(), DESKTOP_SETTINGS_DEFAULT_CATEGORY)
    }

    @Test fun theRailTitlesAreTheOnesTheTreeAlwaysHad() {
        val titles = DESKTOP_SETTINGS_RAIL_CATEGORIES.map { DesktopWindowsSettingsStrings.railTitle(it) }
        assertEquals(listOf("播放与音频", "首页与推荐", "外观与显示",
            "缓存与备份", "隐私与屏蔽", "插件与扩展", "更新与诊断"), titles)
        assertNull(DesktopWindowsSettingsStrings.railTitle(SettingsRootCategory.NAVIGATION_INTERACTION))
    }

    @Test fun directDetailsAreTheContractOnes() {
        val direct = tree.getValue("direct_detail").jsonObject
        // The three entries that only restore old saved navigation state map onto the canonical ones and are skipped here.
        val restoreOnly = setOf("APPEARANCE_INTERACTION", "CONTENT_PLAYBACK", "PRIVACY_STORAGE")
        for (category in SettingsRootCategory.entries.filter { it.name !in restoreOnly }) {
            val expected = direct[category.name]?.jsonPrimitive?.content
            assertEquals(expected, desktopSettingsDirectDetail(category)?.name, category.name)
        }
        for ((category, target) in direct) {
            val page = desktopSettingsRailTarget(SettingsRootCategory.valueOf(category))
            assertEquals(DesktopSettingsPage.Detail(SettingsSearchTarget.valueOf(target.jsonPrimitive.content), null), page)
        }
        assertEquals(DesktopSettingsPage.Category(SettingsRootCategory.PRIVACY_PERMISSION),
            desktopSettingsRailTarget(SettingsRootCategory.PRIVACY_PERMISSION))
        assertEquals(DesktopSettingsPage.Category(SettingsRootCategory.SYSTEM_ABOUT), desktopSettingsRailTarget(SettingsRootCategory.SYSTEM_ABOUT))
    }

    @Test fun aPageBelongsToTheRailEntryOfItsCategory() {
        assertNull(desktopSettingsPageCategory(DesktopSettingsPage.Root))
        assertNull(desktopSettingsPageCategory(DesktopSettingsPage.Search(1)))
        assertEquals(SettingsRootCategory.PLAYBACK_QUALITY, desktopSettingsPageCategory(DesktopSettingsPage.Category(SettingsRootCategory.PLAYBACK_QUALITY)))
        assertEquals(SettingsRootCategory.PLAYBACK_QUALITY, desktopSettingsPageCategory(DesktopSettingsPage.Category(SettingsRootCategory.CONTENT_PLAYBACK)))
        assertEquals(SettingsRootCategory.PLAYBACK_QUALITY, desktopSettingsPageCategory(DesktopSettingsPage.Detail(SettingsSearchTarget.PLAYBACK, null)))
        assertEquals(SettingsRootCategory.HOME_RECOMMENDATION, desktopSettingsPageCategory(DesktopSettingsPage.Detail(SettingsSearchTarget.HOME_FEED, null)))
        assertEquals(SettingsRootCategory.PRIVACY_PERMISSION,
            desktopSettingsPageCategory(DesktopSettingsPage.Detail(SettingsSearchTarget.MESSAGE_NOTIFICATION, null)))
        assertEquals(SettingsRootCategory.PRIVACY_PERMISSION, desktopSettingsPageCategory(DesktopSettingsPage.CommentFraudHistory(1)))
        assertEquals(SettingsRootCategory.STORAGE_BACKUP, desktopSettingsPageCategory(DesktopSettingsPage.Detail(SettingsSearchTarget.WEBDAV_BACKUP, null)))
        assertEquals(SettingsRootCategory.SYSTEM_ABOUT, desktopSettingsPageCategory(DesktopSettingsPage.Detail(SettingsSearchTarget.TIPS, null)))
        // The original navigation category is not listed by the Windows settings.
        assertNull(desktopSettingsPageCategory(DesktopSettingsPage.Detail(SettingsSearchTarget.ANIMATION, null)))
        assertNull(desktopSettingsPageCategory(DesktopSettingsPage.Category(SettingsRootCategory.NAVIGATION_INTERACTION)))
    }

    @Test fun theSystemCategoryIsListedButNeverLanded() {
        val notLandable = DesktopUxContract.strings(tree.getValue("not_landable"))
        for (category in DESKTOP_SETTINGS_RAIL_CATEGORIES) {
            val landable = desktopSettingsLandableCategory(DesktopSettingsPage.Category(category))
            assertEquals(category.name !in notLandable, landable != null, category.name)
        }
        assertNull(desktopSettingsLandableCategory(DesktopSettingsPage.Detail(SettingsSearchTarget.TIPS, null)))
    }

    @Test fun theReplaceStackKeepsTheRootAndAnythingThatIsNotACategoryOrADetail() {
        val root = DesktopSettingsPage.Root
        val search = DesktopSettingsPage.Search(3)
        val category = DesktopSettingsPage.Category(SettingsRootCategory.PRIVACY_PERMISSION)
        val detail = DesktopSettingsPage.Detail(SettingsSearchTarget.MESSAGE_NOTIFICATION, null)
        val target = DesktopSettingsPage.Detail(SettingsSearchTarget.PLUGINS, null)
        assertEquals(listOf(root, target), desktopSettingsReplaceStack(listOf(root), target))
        assertEquals(listOf(root, target), desktopSettingsReplaceStack(listOf(root, category, detail), target))
        assertEquals(listOf(root, search, target), desktopSettingsReplaceStack(listOf(root, search), target))
        assertEquals(listOf(root, search, target), desktopSettingsReplaceStack(listOf(root, search, category), target))
        assertEquals(listOf(root, category, search, target), desktopSettingsReplaceStack(listOf(root, category, search), target))
    }

    @Test fun everyContractScenarioEndsInItsStackLastCategoryAndPendingState() {
        val scenarios = DesktopUxContract.objects(tree.getValue("scenarios"))
        assertTrue(scenarios.size >= 12)
        for (scenario in scenarios) {
            val id = DesktopUxContract.string(scenario, "id")
            val navigator = DesktopSettingsNavigator()
            run(navigator, DesktopUxContract.strings(scenario.getValue("steps")))
            val expect = scenario.getValue("expect").jsonObject
            assertEquals(DesktopUxContract.strings(expect.getValue("stack")), navigator.state.value.stack.map { describe(it) }, id)
            assertEquals(DesktopUxContract.string(expect, "last"), navigator.lastCategory.name, id)
            assertEquals(expect.getValue("pending").jsonPrimitive.boolean, navigator.state.value.landingPending, id)
        }
    }

    private fun pageOf(text: String): DesktopSettingsPage = when (text.substringBefore(':')) {
        "Root" -> DesktopSettingsPage.Root
        "Category" -> DesktopSettingsPage.Category(SettingsRootCategory.valueOf(text.substringAfter(':')))
        "Detail" -> DesktopSettingsPage.Detail(SettingsSearchTarget.valueOf(text.substringAfter(':')), null)
        else -> error("unknown page $text")
    }

    @Test fun aRailClickIsRedundantOnlyWhenTheOpenPageIsThePageItWouldOpen() {
        val clicks = DesktopUxContract.objects(tree.getValue("rail_clicks"))
        assertTrue(clicks.size >= 6)
        for (click in clicks) {
            val id = DesktopUxContract.string(click, "id")
            val redundant = click.getValue("redundant").jsonPrimitive.boolean
            assertEquals(redundant, desktopSettingsRailClickIsRedundant(
                pageOf(DesktopUxContract.string(click, "page")), SettingsRootCategory.valueOf(DesktopUxContract.string(click, "category"))), id)
        }
        // A page under the category of the clicked entry is never redundant: replaceCategory opens the category page.
        assertFalse(desktopSettingsRailClickIsRedundant(
            DesktopSettingsPage.Detail(SettingsSearchTarget.MESSAGE_NOTIFICATION, null), SettingsRootCategory.PRIVACY_PERMISSION))
        assertFalse(desktopSettingsRailClickIsRedundant(DesktopSettingsPage.Root, SettingsRootCategory.PLAYBACK_QUALITY))
    }

    @Test fun aFreshNavigatorIsAtTheRootWithNothingPendingAndTheDefaultCategory() {
        val navigator = DesktopSettingsNavigator()
        assertEquals(listOf<DesktopSettingsPage>(DesktopSettingsPage.Root), navigator.state.value.stack)
        assertFalse(navigator.state.value.landingPending)
        assertEquals(DESKTOP_SETTINGS_DEFAULT_CATEGORY, navigator.lastCategory)
        navigator.landOnLastCategory()
        assertEquals(listOf<DesktopSettingsPage>(DesktopSettingsPage.Root), navigator.state.value.stack)
    }

    @Test fun openRootAndLeaveMakeTheNextLookLand() {
        val navigator = DesktopSettingsNavigator()
        navigator.openRoot()
        assertTrue(navigator.state.value.landingPending)
        navigator.landOnLastCategory()
        assertEquals(DesktopSettingsPage.Detail(SettingsSearchTarget.PLAYBACK, null), navigator.state.value.current)
        navigator.leave()
        assertEquals(listOf<DesktopSettingsPage>(DesktopSettingsPage.Root), navigator.state.value.stack)
        assertTrue(navigator.state.value.landingPending)
    }

    @Test fun aRailClickThatChangesNothingKeepsTheSameStateObject() {
        val navigator = DesktopSettingsNavigator()
        navigator.openRoot()
        navigator.landOnLastCategory()
        val before = navigator.state.value
        navigator.replaceCategory(SettingsRootCategory.PLAYBACK_QUALITY)
        assertSame(before, navigator.state.value)
    }

    @Test fun aRefusedDetailLeavesTheNavigatorAlone() {
        val navigator = DesktopSettingsNavigator { it != SettingsSearchTarget.PLAYBACK }
        navigator.openRoot()
        val before = navigator.state.value
        navigator.replaceCategory(SettingsRootCategory.PLAYBACK_QUALITY)
        navigator.landOnLastCategory()
        assertSame(before, navigator.state.value)
        assertTrue(navigator.state.value.landingPending)
    }

    @Test fun aRailClickClearsAPendingSearchFocus() {
        val navigator = DesktopSettingsNavigator()
        navigator.openDetail(SettingsSearchTarget.PLAYBACK, SettingsSearchFocusIds.PLAYBACK_DECODER)
        assertNotNull(SettingsSearchFocusController.request.value)
        navigator.replaceCategory(SettingsRootCategory.PRIVACY_PERMISSION)
        assertNull(SettingsSearchFocusController.request.value)
        assertEquals(DesktopSettingsPage.Category(SettingsRootCategory.PRIVACY_PERMISSION), navigator.state.value.current)
    }

    @Test fun theOriginalOpenCategoryRuleIsUnchangedForPlaybackAndHome() {
        // Only the rail goes straight to the playback and home details; the pinned navigator behaviour stays.
        val navigator = DesktopSettingsNavigator()
        navigator.openCategory(SettingsRootCategory.PLAYBACK_QUALITY)
        assertEquals(DesktopSettingsPage.Category(SettingsRootCategory.PLAYBACK_QUALITY), navigator.state.value.current)
        navigator.openRoot()
        navigator.openCategory(SettingsRootCategory.HOME_RECOMMENDATION)
        assertEquals(DesktopSettingsPage.Category(SettingsRootCategory.HOME_RECOMMENDATION), navigator.state.value.current)
    }

    @Test fun popTracksTheCategoryOfThePageItReturnsTo() {
        val navigator = DesktopSettingsNavigator()
        navigator.openCategory(SettingsRootCategory.PRIVACY_PERMISSION)
        navigator.openDetail(SettingsSearchTarget.PLAYBACK, null)
        assertEquals(SettingsRootCategory.PLAYBACK_QUALITY, navigator.lastCategory)
        assertTrue(navigator.pop())
        assertEquals(SettingsRootCategory.PRIVACY_PERMISSION, navigator.lastCategory)
    }
}
