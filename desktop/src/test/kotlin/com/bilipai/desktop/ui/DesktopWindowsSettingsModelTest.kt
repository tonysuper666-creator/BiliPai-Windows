package com.bilipai.desktop.ui

import com.bilipai.desktop.ux.DesktopUxContract
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The metrics and the choice rule of the settings chrome against the shared contract ("settings"). No Compose scene, no
 * window, no GPU, no network: the model is plain Kotlin.
 */
class DesktopWindowsSettingsModelTest {
    private val settings = DesktopUxContract.section("settings")
    private val metrics = settings.getValue("metrics").jsonObject
    private val choice = settings.getValue("choice").jsonObject

    /** A label of the given glyph counts. The contract holds the counts, never the text. */
    private fun label(descriptor: JsonObject): String =
        "中".repeat(descriptor["wide"]?.jsonPrimitive?.int ?: 0) +
            "a".repeat(descriptor["narrow"]?.jsonPrimitive?.int ?: 0) +
            " ".repeat(descriptor["space"]?.jsonPrimitive?.int ?: 0)

    private fun presentation(name: String) = DesktopWindowsSettingsChoicePresentation.valueOf(name)

    @Test fun metricConstantsAreTheContractNumbers() {
        val glyph = choice.getValue("glyph").jsonObject
        assertEquals(DesktopUxContract.int(metrics, "content_max_width"), DESKTOP_SETTINGS_CONTENT_MAX_WIDTH_DP)
        assertEquals(DesktopUxContract.int(metrics, "rail_width"), DESKTOP_SETTINGS_RAIL_WIDTH_DP)
        assertEquals(DesktopUxContract.int(metrics, "header_height"), DESKTOP_SETTINGS_HEADER_HEIGHT_DP)
        assertEquals(DesktopUxContract.int(metrics, "row_padding"), DESKTOP_SETTINGS_ROW_PADDING_DP)
        assertEquals(DesktopUxContract.int(metrics, "row_padding_vertical"), DESKTOP_SETTINGS_ROW_PADDING_VERTICAL_DP)
        assertEquals(DesktopUxContract.int(metrics, "page_padding"), DESKTOP_SETTINGS_PAGE_PADDING_DP)
        assertEquals(DesktopUxContract.int(metrics, "section_gap"), DESKTOP_SETTINGS_SECTION_GAP_DP)
        assertEquals(DesktopUxContract.int(choice, "segmented_max"), DESKTOP_SETTINGS_SEGMENTED_MAX)
        assertEquals(DesktopUxContract.int(choice, "segment_padding"), DESKTOP_SETTINGS_SEGMENT_PADDING_DP)
        assertEquals(DesktopUxContract.int(choice, "segment_min"), DESKTOP_SETTINGS_SEGMENT_MIN_DP)
        assertEquals(DesktopUxContract.int(choice, "track_padding"), DESKTOP_SETTINGS_TRACK_PADDING_DP)
        assertEquals(DesktopUxContract.int(choice, "segment_gap"), DESKTOP_SETTINGS_SEGMENT_GAP_DP)
        assertEquals(DesktopUxContract.int(choice, "inline_control_max"), DESKTOP_SETTINGS_INLINE_CONTROL_MAX_DP)
        assertEquals(DesktopUxContract.int(choice, "inline_title_min"), DESKTOP_SETTINGS_INLINE_TITLE_MIN_DP)
        assertEquals(DesktopUxContract.int(glyph, "wide"), DESKTOP_SETTINGS_GLYPH_WIDE_DP)
        assertEquals(DesktopUxContract.int(glyph, "narrow"), DESKTOP_SETTINGS_GLYPH_NARROW_DP)
        assertEquals(DesktopUxContract.int(glyph, "space"), DESKTOP_SETTINGS_GLYPH_SPACE_DP)
    }

    @Test fun theSegmentedLimitIsTheSpecFourAndTheContentCapIsTheSpecNineHundredSixty() {
        assertEquals(4, DESKTOP_SETTINGS_SEGMENTED_MAX)
        assertEquals(960, DESKTOP_SETTINGS_CONTENT_MAX_WIDTH_DP)
    }

    @Test fun oneGlyphHasTheWidthOfItsRangeAtEveryEdge() {
        for (case in DesktopUxContract.objects(choice.getValue("glyph_cases"))) {
            val code = DesktopUxContract.int(case, "code")
            assertEquals(DesktopUxContract.int(case, "width"), desktopSettingsLabelWidthDp(code.toChar().toString()), "code $code")
        }
    }

    @Test fun aLabelIsTheSumOfItsGlyphs() {
        assertEquals(0, desktopSettingsLabelWidthDp(""))
        assertEquals(14 * 3 + 4 * 2 + 8 * 3, desktopSettingsLabelWidthDp("中中中 a+b "))
        assertEquals(8 * 3, desktopSettingsLabelWidthDp("AV1"))
    }

    @Test fun everyGoldenRowGetsItsFormAndControlWidth() {
        val rows = DesktopUxContract.objects(choice.getValue("golden"))
        assertTrue(rows.size >= 15)
        for (row in rows) {
            val id = DesktopUxContract.string(row, "id")
            val labels = DesktopUxContract.objects(row.getValue("labels")).map { label(it) }
            val expect = row.getValue("expect").jsonObject
            val actual = desktopSettingsChoiceLayout(
                presentation(DesktopUxContract.string(row, "presentation")), labels, DesktopUxContract.int(row, "available"),
            )
            assertEquals(DesktopSettingsChoiceForm.valueOf(DesktopUxContract.string(expect, "form")), actual.form, id)
            assertEquals(DesktopUxContract.int(expect, "control"), actual.controlWidthDp, id)
        }
    }

    @Test fun autoNeverChoosesTheSelectRowAndTheSelectRowIsAlwaysChosenWhenAsked() {
        for (count in 0..6) for (available in listOf(-10, 0, 100, 300, 700, 1200)) {
            val labels = List(count) { "a".repeat(it + 1) }
            assertFalse(desktopSettingsChoiceLayout(DesktopWindowsSettingsChoicePresentation.AUTO, labels, available).form ==
                DesktopSettingsChoiceForm.DROPDOWN)
            assertEquals(DesktopSettingsChoiceForm.DROPDOWN,
                desktopSettingsChoiceLayout(DesktopWindowsSettingsChoicePresentation.DROPDOWN, labels, available).form)
        }
    }

    @Test fun segmentedNeedsOneToFourLabelsThatFitAndOtherwiseTheRadioRowsAreStacked() {
        for (count in 0..6) for (width in listOf(1, 5, 12, 40, 90)) for (available in 0..900 step 7) {
            val labels = List(count) { "a".repeat(width) }
            val needed = desktopSettingsSegmentedWidthDp(labels)
            val actual = desktopSettingsChoiceLayout(DesktopWindowsSettingsChoicePresentation.AUTO, labels, available)
            val segmented = actual.form == DesktopSettingsChoiceForm.SEGMENTED_INLINE || actual.form == DesktopSettingsChoiceForm.SEGMENTED_STACKED
            assertEquals(count in 1..DESKTOP_SETTINGS_SEGMENTED_MAX && needed <= available, segmented, "$count labels of $width at $available")
            if (segmented) {
                assertEquals(needed, actual.controlWidthDp)
                assertEquals(needed <= DESKTOP_SETTINGS_INLINE_CONTROL_MAX_DP && available - needed >= DESKTOP_SETTINGS_INLINE_TITLE_MIN_DP,
                    actual.form == DesktopSettingsChoiceForm.SEGMENTED_INLINE)
            }
        }
    }

    @Test fun moreRoomNeverTurnsASegmentedControlIntoRadioRows() {
        val labels = listOf("中中", "中中中", "ab")
        var seenSegmented = false
        for (available in 0..1200) {
            val form = desktopSettingsChoiceLayout(DesktopWindowsSettingsChoicePresentation.AUTO, labels, available).form
            if (form != DesktopSettingsChoiceForm.RADIO_ROWS) seenSegmented = true
            else assertFalse(seenSegmented, "radio rows again at $available")
        }
        assertTrue(seenSegmented)
    }

    @Test fun anEmptyOrNegativeOfferNeverThrowsAndNeverOffersANegativeWidth() {
        assertEquals(0, desktopSettingsSegmentedWidthDp(emptyList()))
        assertEquals(0, desktopSettingsChoiceLayout(DesktopWindowsSettingsChoicePresentation.AUTO, listOf("a"), -50).controlWidthDp)
        assertEquals(0, desktopSettingsChoiceLayout(DesktopWindowsSettingsChoicePresentation.DROPDOWN, emptyList(), -1).controlWidthDp)
    }

    @Test fun theDefaultQualityListIsTheSixValuesThatTheDropdownShowsInTheirPinnedOrder() {
        // The list itself is pinned by DesktopWindowsPlaybackSettingsQualityLabelTest; the select row shows it as given.
        assertEquals(6, desktopWindowsDefaultQualityChoices.size)
        assertEquals(DesktopSettingsChoiceForm.DROPDOWN,
            desktopSettingsChoiceLayout(DesktopWindowsSettingsChoicePresentation.DROPDOWN, desktopWindowsDefaultQualityChoices.map { it.second }, 936).form)
        assertEquals(DesktopSettingsChoiceForm.RADIO_ROWS,
            desktopSettingsChoiceLayout(DesktopWindowsSettingsChoicePresentation.AUTO, desktopWindowsDefaultQualityChoices.map { it.second }, 936).form)
    }

    @Test fun theChoicesOfTheFixedNativePopoverFollowTheSpecRule() {
        // 320 dp native popover less the 12 dp end padding of DesktopWindowsPlayerMenu's scroll column and the 12 dp padding on
        // both sides of the NVIDIA content column: 284 dp. Content and strength as shipped.
        val content = listOf("清晰度", "清晰度 + HDR 自动", "仅 HDR 自动")
        val strength = listOf("轻柔", "均衡", "完整")
        assertEquals(DesktopSettingsChoiceForm.RADIO_ROWS,
            desktopSettingsChoiceLayout(DesktopWindowsSettingsChoicePresentation.AUTO, content, 284).form)
        assertEquals(DesktopSettingsChoiceForm.SEGMENTED_STACKED,
            desktopSettingsChoiceLayout(DesktopWindowsSettingsChoicePresentation.AUTO, strength, 284).form)
    }
}
