package com.bilipai.desktop.settings

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Backup import keeps the v0.3.8/v0.3.9 merge window range (1 s to 15 s, normalizeDanmakuDuplicateMergeWindowMs) and no
 * longer maps the expanded-mode key that left the Android share definitions in v0.3.8. */
class DesktopSettingsShareMergeWindowTest {
    private fun imported(value: JsonPrimitive) =
        DesktopSettingsShareWindowsCatalog.normalize(mapOf("danmaku_duplicate_merge_window_ms" to value))

    @Test
    fun theFifteenSecondDefaultSurvivesAnExportAndImportRoundTrip() {
        assertEquals(JsonPrimitive(15_000), imported(JsonPrimitive(15_000))["danmaku_duplicate_merge_window_ms"])
        assertEquals(JsonPrimitive(9_000), imported(JsonPrimitive(9_000))["danmaku_duplicate_merge_window_ms"])
    }

    @Test
    fun oldAndOversizedValuesAreCoercedIntoTheRangeInsteadOfDropped() {
        assertEquals(JsonPrimitive(1_000), imported(JsonPrimitive(500))["danmaku_duplicate_merge_window_ms"])
        assertEquals(JsonPrimitive(3_000), imported(JsonPrimitive(3_000))["danmaku_duplicate_merge_window_ms"])
        assertEquals(JsonPrimitive(15_000), imported(JsonPrimitive(20_000))["danmaku_duplicate_merge_window_ms"])
    }

    @Test
    fun aNonNumericWindowIsRejected() {
        assertTrue(imported(JsonPrimitive("15000")).isEmpty())
        assertTrue(imported(JsonPrimitive(true)).isEmpty())
        assertTrue(imported(JsonPrimitive(1.5)).isEmpty())
    }

    @Test
    fun theRetiredExpandedModeKeyIsNotMappedAnyMore() {
        assertFalse("hot_danmaku_expanded_mode" in DesktopSettingsShareWindowsCatalog.fields)
        assertTrue(DesktopSettingsShareWindowsCatalog.normalize(mapOf("hot_danmaku_expanded_mode" to JsonPrimitive(true))).isEmpty())
        assertTrue("danmaku_duplicate_merge_window_ms" in DesktopSettingsShareWindowsCatalog.fields)
    }
}
