package com.bilipai.desktop.settings

import com.android.purebilibili.feature.settings.*
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import java.nio.file.Files
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Contracts use the actual controller click and navigator, including their category/detail boundary. */
class DesktopSettingsUpstreamDirectoryTest {
    private fun controller() = DesktopSettingsSearchController(DesktopSettingsSearchRepository(
        DesktopPluginContext(DesktopPluginStore(Files.createTempDirectory("bilipai-upstream-search-")))) { false })

    @AfterEach fun clearFocus() {
        SettingsSearchFocusController.clear()
        desktopSettingsItemFocus.clear()
    }

    private fun click(controller: DesktopSettingsSearchController, hit: DesktopSettingsItemHit): DesktopSettingsPage {
        val navigator = DesktopSettingsNavigator()
        controller.activateItem(hit, navigator::openCategory, navigator::openSearchResult)
        return navigator.state.value.current
    }

    private fun expected(item: DesktopSettingsItem): DesktopSettingsPage {
        val row = desktopSettingsTargetPlacement[item.target.name]
        return if (row?.treatment == DesktopPlacementTreatment.available && row.destination == "detail" &&
            row.desktopCategory == item.category.name) DesktopSettingsPage.Detail(item.target, item.focus)
        else when (item.category) {
            SettingsRootCategory.APPEARANCE_THEME -> DesktopSettingsPage.Detail(SettingsSearchTarget.APPEARANCE, null)
            SettingsRootCategory.PLUGINS_EXTENSIONS -> DesktopSettingsPage.Detail(SettingsSearchTarget.PLUGINS, null)
            else -> DesktopSettingsPage.Category(item.category)
        }
    }

    @Test fun everyAvailableUpstreamIdIsSearchableAndItsClickHonorsBothPlacementLayers() {
        val available = desktopSettingsItemPlacement.values.filter { it.treatment == DesktopPlacementTreatment.available }
        assertEquals(152, available.size)
        assertEquals(available.size + desktopOnlySettingsIds.size + desktopWindowsUxSearchItems.size,
            desktopSettingsItemDirectory.size)
        val controller = controller()
        val failures = mutableListOf<String>()
        for (row in available) {
            val item = desktopSettingsItemDirectory.single { it.settingId == row.id }
            assertEquals(row.desktopCategory, item.category.name, row.id)
            assertEquals(settingsItemDirectory.single { it.settingId == row.id }.target, item.target, row.id)
            controller.setQuery(item.title)
            val hit = controller.itemResults.value.single { it.item.settingId == row.id }
            assertEquals(item.settingId, hit.toSearchResult().settingId)
            assertEquals(item.category, hit.toSearchResult().page)
            val actual = click(controller, hit)
            if (actual != expected(item)) failures += "${row.id}: expected ${expected(item)}, got $actual"
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test fun allFilteredIdsAreAbsentEvenWhenTheirOwnTitlesAndAliasesAreQueried() {
        val filtered = desktopSettingsItemPlacement.values.filter { it.treatment != DesktopPlacementTreatment.available }.map { it.id }.toSet()
        assertEquals(74, filtered.size)
        assertTrue(desktopSettingsItemDirectory.none { it.settingId in filtered })
        val controller = controller()
        for (item in settingsItemDirectory.filter { it.settingId in filtered }) {
            for (query in listOf(item.title) + item.aliases) {
                controller.setQuery(query)
                assertTrue(controller.itemResults.value.none { it.item.settingId in filtered }, "${item.settingId}: $query")
                assertTrue(controller.results.value.none { it.settingId in filtered }, "${item.settingId}: section $query")
            }
        }
    }

    @Test fun desktopRowsAndSharedPresentationKeepTheirExistingTitlesAliasesAndFocus() {
        assertEquals(17, desktopOnlySettingsIds.size)
        assertEquals(39, desktopSettingsItemPresentation.count { it.settingId !in desktopOnlySettingsIds })
        for (old in desktopSettingsItemPresentation + desktopWindowsUxSearchItems) {
            val current = desktopSettingsItemDirectory.single { it.settingId == old.settingId }
            assertEquals(old.title, current.title, old.settingId)
            assertEquals(old.aliases, current.aliases, old.settingId)
            assertEquals(old.focus, current.focus, old.settingId)
        }
        val retained = (desktopSettingsItemPresentation + desktopWindowsUxSearchItems).map { it.settingId }.toSet()
        val added = desktopSettingsItemDirectory.filter { it.settingId !in retained }
        assertEquals(113, added.size)
        assertTrue(added.all { it.focus == null })
    }

    @Test fun aMissingPlacementStaysVisibleAndClicksItsCanonicalCategory() {
        val original = PlaybackSettingItem("future.test.setting", "未来设置", SettingsRootCategory.FULLSCREEN_GESTURE, "future", null)
        val items = buildDesktopSettingsItemDirectory(listOf(original), emptyMap(), emptyList(), emptyList())
        assertEquals(SettingsRootCategory.PLAYBACK_QUALITY, items.single().category)
        val hit = resolveDesktopSettingsItemSearch(original.title, directory = items).single()
        assertEquals(DesktopSettingsPage.Detail(SettingsSearchTarget.PLAYBACK, null), click(controller(), hit))
    }

    @Test fun itemFilteringDoesNotInheritAReplacedTarget() {
        assertEquals(DesktopPlacementTreatment.replaced, desktopSettingsTargetPlacement.getValue("ANIMATION").treatment)
        val item = desktopSettingsItemDirectory.single { it.settingId == "glass.popup_palette" }
        assertEquals(SettingsSearchTarget.ANIMATION, item.target)
        val controller = controller()
        controller.setQuery(item.title)
        assertEquals(DesktopSettingsPage.Detail(SettingsSearchTarget.APPEARANCE, null),
            click(controller, controller.itemResults.value.single { it.item.settingId == item.settingId }))
    }

    @Test fun fullscreenGestureClickOpensPlaybackCategoryAndHasNoDirectDetail() {
        val controller = controller()
        controller.setQuery("全屏与手势")
        val result = controller.results.value.single { it.target == SettingsSearchTarget.FULLSCREEN_GESTURE }
        val navigator = DesktopSettingsNavigator()
        controller.activate(result, navigator::openCategory, navigator::openSearchResult)
        assertEquals(DesktopSettingsPage.Category(SettingsRootCategory.PLAYBACK_QUALITY), navigator.state.value.current)
        assertNull(desktopSettingsDirectDetail(SettingsRootCategory.FULLSCREEN_GESTURE))
    }

    @Test fun deletingAnUpstreamIdCannotLeaveAnOrphanPresentation() {
        val removed = desktopSettingsItemPresentation.first { it.settingId !in desktopOnlySettingsIds }
        val failure = assertFailsWith<IllegalStateException> {
            buildDesktopSettingsItemDirectory(settingsItemDirectory.filterNot { it.settingId == removed.settingId })
        }
        assertTrue(removed.settingId in failure.message.orEmpty())
    }

    @Test fun desktopAndWindowsIdsCannotShadowUpstream() {
        for (id in desktopOnlySettingsIds + desktopSearchableWindowsUxIds) {
            val collision = PlaybackSettingItem(id, "冲突", SettingsRootCategory.PLAYBACK_QUALITY, "collision", null)
            assertFailsWith<IllegalStateException>(id) {
                buildDesktopSettingsItemDirectory(settingsItemDirectory + collision)
            }
        }
    }
}
