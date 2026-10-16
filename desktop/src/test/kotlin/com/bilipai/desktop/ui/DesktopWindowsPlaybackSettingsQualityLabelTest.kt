package com.bilipai.desktop.ui

import com.bilipai.desktop.settings.DesktopSettingsShareWindowsCatalog
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.*

/** The six default-quality chips and the read-only caption for every other id settings import accepts. */
class DesktopWindowsPlaybackSettingsQualityLabelTest {
    private val chipIds = listOf(16, 32, 64, 80, 112, 120)

    @Test fun `the six chips stay selectable and never get a caption`() {
        assertEquals(chipIds, desktopWindowsDefaultQualityChoices.map { it.first })
        for (id in chipIds) assertNull(desktopWindowsOffListQualityCaption(id), "chip $id")
        assertNull(desktopWindowsOffListQualityCaption(null))
    }

    @Test fun `every quality id settings import accepts is a chip or has a labelled caption`() {
        val accepted = (0..300).filter { id ->
            DesktopSettingsShareWindowsCatalog.fields.getValue("wifi_default_quality").normalize(JsonPrimitive(id)) != null
        }
        assertTrue(chipIds.all { it in accepted }, "the chips must stay importable")
        val offList = accepted - chipIds.toSet()
        assertTrue(offList.isNotEmpty(), "import accepts ids that no chip represents")
        for (id in offList) {
            val caption = assertNotNull(desktopWindowsOffListQualityCaption(id), "no caption for imported id $id")
            assertTrue(caption.contains("（${id}）"), caption)
        }
    }

    @Test fun `off-list ids reuse the labels of the original quality tables`() {
        // The original settings table wins (125 is "4K HDR" there, "HDR 真彩" in QualityManager); the
        // original QualityManager table covers what the settings table lacks, including its own fallback.
        val expected = mapOf(126 to "杜比视界", 125 to "4K HDR", 116 to "1080P60", 74 to "720P60", 127 to "8K", 6 to "其他画质")
        for ((id, label) in expected) {
            val caption = assertNotNull(desktopWindowsOffListQualityCaption(id), "no caption for $id")
            assertTrue(caption.contains("${label}（${id}）"), caption)
        }
    }
}
