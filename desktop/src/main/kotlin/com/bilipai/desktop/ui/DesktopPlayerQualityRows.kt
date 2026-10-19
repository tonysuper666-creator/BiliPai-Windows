package com.bilipai.desktop.ui

/*
 * Pure quality row states of the desktop player (spec 4.9). No Compose, AWT or Windows dependency: the quality flyout (slice
 * V1) feeds it the leaf's playback readbacks and lists what it returns, so it never offers a quality the user cannot obtain.
 * The gate follows the vendored QualitySelectionMenu: the smart-repair id 100 and the ids from 112 need the VIP, the ids from
 * 80 need a login, the VIP gate comes first, and a non-empty switchable list that lacks the id makes the row unavailable (an
 * empty list means "unknown"). The playing quality is always the current row. A higher id is a better quality (the original
 * quality chain). The explicit rows never look at the device, like the vendored menu. Only the target of the automatic row does,
 * and it follows the original resolveAutoHighestTargetQuality (VideoPlaybackUseCase): the account ceiling (VIP 129, logged in 80,
 * logged out 64, so a guest is not taken to 720P60) and the device-safe ids (126 only with Dolby Vision, 125 only with HDR).
 * The caller passes the capability readbacks (DesktopOriginalMpvPlaybackCapabilities.isHdrSupported and isDolbyVisionSupported).
 * test_ux_player_policy.py and DesktopPlayerQualityRowsTest check the rules against the vendored menu and resolver.
 */

// UX-PLAYER-QUALITY-BEGIN
internal const val DESKTOP_PLAYER_QUALITY_SMART_REPAIR_ID = 100
internal const val DESKTOP_PLAYER_QUALITY_VIP_FROM_ID = 112
internal const val DESKTOP_PLAYER_QUALITY_LOGIN_FROM_ID = 80
// UX-PLAYER-QUALITY-END

// UX-PLAYER-QUALITY-AUTO-BEGIN
internal const val DESKTOP_PLAYER_QUALITY_AUTO_CEILING_VIP_ID = 129
internal const val DESKTOP_PLAYER_QUALITY_AUTO_CEILING_LOGGED_IN_ID = 80
internal const val DESKTOP_PLAYER_QUALITY_AUTO_CEILING_GUEST_ID = 64
internal const val DESKTOP_PLAYER_QUALITY_DOLBY_VISION_ID = 126
internal const val DESKTOP_PLAYER_QUALITY_HDR_ID = 125
// UX-PLAYER-QUALITY-AUTO-END

internal enum class DesktopPlayerQualityRowState { CURRENT, SELECTABLE, NEEDS_LOGIN, NEEDS_VIP, UNAVAILABLE }

/** enabled: a selectable row while no switch is under way. */
internal data class DesktopPlayerQualityRow(val id: Int, val label: String, val state: DesktopPlayerQualityRowState, val enabled: Boolean)

/**
 * The flyout content. resolvedLabel is the label of the playing quality (the secondary text of the automatic row and the pill
 * text), null when it is not listed. autoSelected is the stored preference "highest available first". autoTargetId is the best
 * selectable quality above the playing one that the account ceiling and the device allow, which the automatic row switches to, or
 * null when there is none. The automatic row is enabled whenever no switch is under way.
 */
internal data class DesktopPlayerQualityMenu(
    val rows: List<DesktopPlayerQualityRow>,
    val resolvedLabel: String?,
    val autoSelected: Boolean,
    val autoTargetId: Int?,
    val switching: Boolean,
) {
    val autoEnabled: Boolean get() = !switching
}

internal fun resolveDesktopPlayerQualityMenu(
    qualityIds: List<Int>,
    qualityLabels: List<String>,
    currentQuality: Int?,
    switchableQualityIds: List<Int>,
    loggedIn: Boolean,
    vip: Boolean,
    hdrSupported: Boolean,
    dolbyVisionSupported: Boolean,
    switching: Boolean,
    autoPreferred: Boolean,
): DesktopPlayerQualityMenu {
    val rows = qualityIds.mapIndexed { index, id ->
        val state = when {
            id == currentQuality -> DesktopPlayerQualityRowState.CURRENT
            (id == DESKTOP_PLAYER_QUALITY_SMART_REPAIR_ID || id >= DESKTOP_PLAYER_QUALITY_VIP_FROM_ID) && !vip ->
                DesktopPlayerQualityRowState.NEEDS_VIP
            id >= DESKTOP_PLAYER_QUALITY_LOGIN_FROM_ID && !loggedIn -> DesktopPlayerQualityRowState.NEEDS_LOGIN
            switchableQualityIds.isNotEmpty() && id !in switchableQualityIds -> DesktopPlayerQualityRowState.UNAVAILABLE
            else -> DesktopPlayerQualityRowState.SELECTABLE
        }
        DesktopPlayerQualityRow(id, qualityLabels.getOrNull(index) ?: id.toString(), state, state == DesktopPlayerQualityRowState.SELECTABLE && !switching)
    }
    val better = rows.filter {
        it.state == DesktopPlayerQualityRowState.SELECTABLE && (currentQuality == null || it.id > currentQuality) &&
            desktopPlayerQualityAutoAllowed(it.id, loggedIn, vip, hdrSupported, dolbyVisionSupported)
    }
    return DesktopPlayerQualityMenu(
        rows = rows,
        resolvedLabel = rows.firstOrNull { it.state == DesktopPlayerQualityRowState.CURRENT }?.label,
        autoSelected = autoPreferred,
        autoTargetId = better.maxOfOrNull { it.id },
        switching = switching,
    )
}

/** The id filter of the original resolveAutoHighestTargetQuality: below the account ceiling, and no HDR or Dolby Vision id the device cannot show. */
private fun desktopPlayerQualityAutoAllowed(id: Int, loggedIn: Boolean, vip: Boolean, hdrSupported: Boolean, dolbyVisionSupported: Boolean): Boolean {
    val ceiling = when {
        vip -> DESKTOP_PLAYER_QUALITY_AUTO_CEILING_VIP_ID
        loggedIn -> DESKTOP_PLAYER_QUALITY_AUTO_CEILING_LOGGED_IN_ID
        else -> DESKTOP_PLAYER_QUALITY_AUTO_CEILING_GUEST_ID
    }
    val deviceSafe = when (id) {
        DESKTOP_PLAYER_QUALITY_DOLBY_VISION_ID -> dolbyVisionSupported
        DESKTOP_PLAYER_QUALITY_HDR_ID -> hdrSupported
        else -> true
    }
    return deviceSafe && id <= ceiling
}
