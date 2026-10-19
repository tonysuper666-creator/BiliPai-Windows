package com.bilipai.desktop.ui

/*
 * Pure model of the Windows settings controls (spec 3.7, slice X3): the metrics of the settings chrome and the rule
 * that picks how a choice is shown. No Compose, AWT or Windows dependency. Mirrored by
 * desktop/tools/ux/ux_settings_model.py and checked against the "settings" section of desktop-ux-contract.json by
 * DesktopWindowsSettingsModelTest and test_ux_settings_model.py, so a drifting number fails in one of the two runs.
 */

// UX-SETTINGS-METRICS-BEGIN
internal const val DESKTOP_SETTINGS_CONTENT_MAX_WIDTH_DP = 960
internal const val DESKTOP_SETTINGS_RAIL_WIDTH_DP = 200
internal const val DESKTOP_SETTINGS_HEADER_HEIGHT_DP = 56
internal const val DESKTOP_SETTINGS_ROW_PADDING_DP = 12
internal const val DESKTOP_SETTINGS_ROW_PADDING_VERTICAL_DP = 8
internal const val DESKTOP_SETTINGS_PAGE_PADDING_DP = 24
internal const val DESKTOP_SETTINGS_SECTION_GAP_DP = 24
internal const val DESKTOP_SETTINGS_SEGMENTED_MAX = 4
internal const val DESKTOP_SETTINGS_SEGMENT_PADDING_DP = 16
internal const val DESKTOP_SETTINGS_SEGMENT_MIN_DP = 56
internal const val DESKTOP_SETTINGS_TRACK_PADDING_DP = 4
internal const val DESKTOP_SETTINGS_SEGMENT_GAP_DP = 2
internal const val DESKTOP_SETTINGS_INLINE_CONTROL_MAX_DP = 360
internal const val DESKTOP_SETTINGS_INLINE_TITLE_MIN_DP = 200
internal const val DESKTOP_SETTINGS_GLYPH_WIDE_DP = 14
internal const val DESKTOP_SETTINGS_GLYPH_NARROW_DP = 8
internal const val DESKTOP_SETTINGS_GLYPH_SPACE_DP = 4
// UX-SETTINGS-METRICS-END

/** How a choice is presented. AUTO picks the form from the choices; DROPDOWN is a select row for a long fixed list. */
internal enum class DesktopWindowsSettingsChoicePresentation { AUTO, DROPDOWN }

/**
 * The form a choice takes. A segmented control sits beside its title when there is room (INLINE) and under it
 * otherwise (STACKED). RADIO_ROWS is the stacked list of radio rows, never a popup, so it is safe inside the
 * fixed-size native popovers. DROPDOWN is the select row with its menu.
 */
internal enum class DesktopSettingsChoiceForm { SEGMENTED_INLINE, SEGMENTED_STACKED, RADIO_ROWS, DROPDOWN }

/** controlWidthDp is the width a segmented control needs; the other forms fill the width that is offered. */
internal data class DesktopSettingsChoiceLayout(val form: DesktopSettingsChoiceForm, val controlWidthDp: Int)

private fun desktopSettingsGlyphWidthDp(glyph: Char): Int {
    val code = glyph.code
    return when {
        code in 0x2E80..0x9FFF || code in 0xAC00..0xD7A3 || code in 0xF900..0xFAFF || code in 0xFF00..0xFFEF ->
            DESKTOP_SETTINGS_GLYPH_WIDE_DP
        glyph == ' ' -> DESKTOP_SETTINGS_GLYPH_SPACE_DP
        else -> DESKTOP_SETTINGS_GLYPH_NARROW_DP
    }
}

/**
 * An estimate of the width of a segment label at the label style: wide (CJK and fullwidth) glyphs, spaces and the rest.
 * It counts dp for the font-size preset 1.0. The kit labels are in sp, so at a larger preset the real label is wider than
 * the estimate and a segmented control chosen as "every label fits" may still ellipsise a label; the kit then shows the
 * full label in a tooltip, so nothing is lost. The estimate is not scaled by the preset here (the function stays pure and
 * mirrored by the contract), and a caller that wants to be safe at a larger preset offers a smaller available width.
 */
internal fun desktopSettingsLabelWidthDp(label: String): Int = label.sumOf { desktopSettingsGlyphWidthDp(it) }

/** The width of a segmented control whose segments are as wide as its widest label (never under the minimum). */
internal fun desktopSettingsSegmentedWidthDp(labels: List<String>): Int {
    if (labels.isEmpty()) return 0
    val widest = labels.maxOf { desktopSettingsLabelWidthDp(it) }
    val segment = maxOf(widest + DESKTOP_SETTINGS_SEGMENT_PADDING_DP, DESKTOP_SETTINGS_SEGMENT_MIN_DP)
    return labels.size * segment + DESKTOP_SETTINGS_TRACK_PADDING_DP + DESKTOP_SETTINGS_SEGMENT_GAP_DP * (labels.size - 1)
}

/**
 * The form of a choice (spec 3.7). DROPDOWN is only chosen by the caller. AUTO is segmented when there are at most
 * four choices and every label fits its segment, otherwise stacked radio rows. availableWidthDp is the width left for
 * the control and its title.
 */
internal fun desktopSettingsChoiceLayout(
    presentation: DesktopWindowsSettingsChoicePresentation,
    labels: List<String>,
    availableWidthDp: Int,
): DesktopSettingsChoiceLayout {
    val available = availableWidthDp.coerceAtLeast(0)
    if (presentation == DesktopWindowsSettingsChoicePresentation.DROPDOWN) {
        return DesktopSettingsChoiceLayout(DesktopSettingsChoiceForm.DROPDOWN, available)
    }
    if (labels.isEmpty() || labels.size > DESKTOP_SETTINGS_SEGMENTED_MAX) {
        return DesktopSettingsChoiceLayout(DesktopSettingsChoiceForm.RADIO_ROWS, available)
    }
    val needed = desktopSettingsSegmentedWidthDp(labels)
    if (needed > available) return DesktopSettingsChoiceLayout(DesktopSettingsChoiceForm.RADIO_ROWS, available)
    val inline = needed <= DESKTOP_SETTINGS_INLINE_CONTROL_MAX_DP && available - needed >= DESKTOP_SETTINGS_INLINE_TITLE_MIN_DP
    return DesktopSettingsChoiceLayout(
        if (inline) DesktopSettingsChoiceForm.SEGMENTED_INLINE else DesktopSettingsChoiceForm.SEGMENTED_STACKED, needed,
    )
}
