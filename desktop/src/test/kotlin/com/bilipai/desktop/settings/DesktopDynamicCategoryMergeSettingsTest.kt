package com.bilipai.desktop.settings

import com.android.purebilibili.feature.dynamic.allDynamicTabSpecs
import com.android.purebilibili.feature.dynamic.defaultDynamicTabVisibleIds
import com.android.purebilibili.feature.dynamic.resolveDynamicVisibleTabs
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The v0.4.5 dynamic category merge retired the UP tab and the persisted "All page strip" switch. */
class DesktopDynamicCategoryMergeSettingsTest {
    @Test
    fun theStripSwitchKeyIsNotMappedAnyMoreAndAnOldBackupValueIsReportedUnmapped() {
        val key = "dynamic_all_tab_horizontal_user_list_visible"
        assertFalse(key in DesktopSettingsShareWindowsCatalog.fields)
        assertTrue(DesktopSettingsShareWindowsCatalog.normalize(mapOf(key to JsonPrimitive(true))).isEmpty())
        assertTrue(DesktopSettingsShareWindowsCatalog.definitions.none { it.storageKey == key })
    }

    @Test
    fun theNeighbouringDynamicNavigationKeysStayMapped() {
        for (key in listOf("dynamic_top_bar_collapse_on_scroll", "dynamic_comment_preview_visible",
            "dynamic_image_preview_text_visible", "incremental_timeline_refresh")) {
            assertTrue(key in DesktopSettingsShareWindowsCatalog.fields, key)
        }
    }

    @Test
    fun theTabListHasFourCategoriesAndNoUpTab() {
        assertEquals(listOf("all", "video", "pgc", "article"), allDynamicTabSpecs.map { it.id })
        assertEquals(listOf(0, 1, 2, 3), allDynamicTabSpecs.map { it.logicalIndex })
        assertEquals(setOf("all", "video", "pgc", "article"), defaultDynamicTabVisibleIds)
        assertEquals(listOf("all"), resolveDynamicVisibleTabs(setOf("up")).map { it.id },
            "The retired id is unknown now; the settings getters fold it into all before it reaches this resolver")
    }
}
