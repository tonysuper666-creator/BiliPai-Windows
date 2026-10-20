package com.bilipai.desktop.ui

/*
 * Pure logic of the Home card interactions (spec 3.6 and 6.7, slice H2): no Compose, AWT or Windows dependency. Mirrored by
 * desktop/tools/ux/ux_home_card_model.py and checked against the "home_card" section of desktop/tools/ux/desktop-ux-contract.json
 * by DesktopCardInteractionPolicyTest and test_ux_home_card_model.py. The composables (DesktopCardContextMenu.kt and
 * DesktopCardPointer.kt) only read these results.
 *
 * The card_interactions flag decides, and OFF is the vendored behaviour. desktop/tools/ux_home_card.py rewrites the generated
 * VideoCard so that it calls the desktop-owned menu, the right-click and hand-cursor modifiers, the menu label function and the
 * overflow target function; each of them asks the flag first and runs the vendored code (or returns the vendored value) when the
 * flag is OFF.
 */

// UX-CARD-TOKENS-BEGIN
internal const val DESKTOP_CARD_MENU_ROW_DP = 36
internal const val DESKTOP_CARD_MENU_WIDTH_DP = 168
internal const val DESKTOP_CARD_OVERFLOW_TARGET_DP = 32
// UX-CARD-TOKENS-END

/** What the secondary press handler does after the press it took: keep watching, consume the release and stop, or stop. */
internal enum class DesktopCardSecondaryFollow { WAIT, CONSUME_AND_END, END }

/** The text of a menu entry: the plain label while the interactions are on, the vendored label (with its emoji) while they are off. */
internal fun desktopCardMenuLabelFor(interactionsOn: Boolean, original: String, plain: String): String =
    if (interactionsOn) plain else original

/** The edge of the overflow button in dp: the 32 dp target while the interactions are on, the vendored size while they are off. */
internal fun desktopCardOverflowTargetDpFor(interactionsOn: Boolean, originalDp: Float): Float =
    if (interactionsOn) DESKTOP_CARD_OVERFLOW_TARGET_DP.toFloat() else originalDp

/** Whether the right-click handler is mounted: the card has a menu to open and the interactions are on. */
internal fun desktopCardSecondaryClickActive(hasMenu: Boolean, interactionsOn: Boolean): Boolean = hasMenu && interactionsOn

/** A press the card takes as a right click: the secondary button alone. A chord with the primary button is the vendored gesture's. */
internal fun desktopCardOwnsSecondaryPress(primaryPressed: Boolean, secondaryPressed: Boolean): Boolean =
    secondaryPressed && !primaryPressed

/**
 * The step after the press was taken, for each event of the same pointer: a pointer that is gone ends the watch, a pointer that is
 * still down is waited for, and its release is consumed (so that no tap recogniser reads the release of a right click as a tap)
 * and ends the watch.
 */
internal fun desktopCardSecondaryFollow(pointerPresent: Boolean, stillPressed: Boolean): DesktopCardSecondaryFollow = when {
    !pointerPresent -> DesktopCardSecondaryFollow.END
    stillPressed -> DesktopCardSecondaryFollow.WAIT
    else -> DesktopCardSecondaryFollow.CONSUME_AND_END
}
