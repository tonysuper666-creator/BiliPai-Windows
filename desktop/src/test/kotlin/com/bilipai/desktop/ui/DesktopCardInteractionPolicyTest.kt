package com.bilipai.desktop.ui

import com.bilipai.desktop.ui.kit.DESKTOP_KIT_ICON_HIT_DP
import com.bilipai.desktop.ux.DesktopUxContract
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pure logic of the Home card interactions (slice H2) against the home_card section of the shared contract. Pure: the functions
 * of DesktopCardInteractionPolicy.kt, no window, no Compose scene, no GPU, no network. The composables (the menu and the pointer
 * modifiers) read these results and are covered by the Python source checks; a scene test of a right click through the real
 * Initial-pass handler is not part of this class (an ImageComposeScene test is not part of this slice).
 */
class DesktopCardInteractionPolicyTest {
    private val card = DesktopUxContract.section("home_card")
    private val menu = card.getValue("menu").jsonObject
    private val secondary = card.getValue("secondary").jsonObject

    /** A stand-in for the plain label of a contract entry (the contract is ASCII and holds the id, not the Chinese text). */
    private fun plainOf(entry: kotlinx.serialization.json.JsonObject): String = "plain " + DesktopUxContract.string(entry, "id")

    /** The vendored label of a contract entry: the emoji of its code point, a space and the plain label. */
    private fun original(entry: kotlinx.serialization.json.JsonObject): String =
        Character.toChars(DesktopUxContract.string(entry, "emoji_code_point").toInt(16)).concatToString() + " " + plainOf(entry)

    /** The whole gesture of DesktopCardPointer.kt for a first press and the later events of the same pointer: (opened, consumed). */
    private fun replay(primary: Boolean, secondaryButton: Boolean, followers: List<Pair<Boolean, Boolean>>): Pair<Boolean, List<String>> {
        if (!desktopCardOwnsSecondaryPress(primary, secondaryButton)) return false to emptyList()
        val consumed = mutableListOf("PRESS")
        for ((present, pressed) in followers) {
            when (desktopCardSecondaryFollow(present, pressed)) {
                DesktopCardSecondaryFollow.WAIT -> Unit
                DesktopCardSecondaryFollow.CONSUME_AND_END -> {
                    consumed.add("RELEASE")
                    return true to consumed
                }
                DesktopCardSecondaryFollow.END -> return true to consumed
            }
        }
        return true to consumed
    }

    @Test fun theTokenConstantsAreTheContractNumbers() {
        assertEquals(DesktopUxContract.int(menu, "row_height_dp"), DESKTOP_CARD_MENU_ROW_DP)
        assertEquals(DesktopUxContract.int(menu, "width_dp"), DESKTOP_CARD_MENU_WIDTH_DP)
        assertEquals(DesktopUxContract.int(card, "overflow_target_dp"), DESKTOP_CARD_OVERFLOW_TARGET_DP)
    }

    @Test fun theMenuRowIsTheFlyoutRowAndTheOverflowTargetIsTheKitIconHit() {
        val flyout = DesktopUxContract.section("flyout")
        assertEquals(DesktopUxContract.int(flyout, "row_height"), DESKTOP_CARD_MENU_ROW_DP)
        assertEquals(DESKTOP_KIT_ICON_HIT_DP, DESKTOP_CARD_OVERFLOW_TARGET_DP)
        assertEquals(32, DESKTOP_CARD_OVERFLOW_TARGET_DP)
    }

    @Test fun theMenuHoldsAtMostThreeEntriesAndTheCapOfTheContractAllowsThem() {
        val labels = DesktopUxContract.objects(card.getValue("labels"))
        assertEquals(3, labels.size)
        val cap = DesktopUxContract.int(DesktopUxContract.section("flyout"), "card_menu_max_items")
        assertTrue(labels.size <= cap)
    }

    @Test fun everyLabelIsPlainWhileTheInteractionsAreOnAndTheVendoredLabelWhileTheyAreOff() {
        for (entry in DesktopUxContract.objects(card.getValue("labels"))) {
            val plain = plainOf(entry)
            val vendored = original(entry)
            assertTrue(vendored.endsWith(" $plain"), vendored)
            assertTrue(vendored.length > plain.length + 1, vendored)
            assertEquals(plain, desktopCardMenuLabelFor(true, vendored, plain), vendored)
            assertEquals(vendored, desktopCardMenuLabelFor(false, vendored, plain), vendored)
        }
    }

    @Test fun theOverflowTargetIsThirtyTwoWhileOnAndTheVendoredSizeWhileOff() {
        assertEquals(32f, desktopCardOverflowTargetDpFor(true, 48f))
        assertEquals(32f, desktopCardOverflowTargetDpFor(true, 24f))
        assertEquals(48f, desktopCardOverflowTargetDpFor(false, 48f))
        assertEquals(24f, desktopCardOverflowTargetDpFor(false, 24f))
    }

    @Test fun theRightClickHandlerIsMountedOnlyForACardWithAMenuAndTheFlagOn() {
        assertTrue(desktopCardSecondaryClickActive(hasMenu = true, interactionsOn = true))
        assertFalse(desktopCardSecondaryClickActive(hasMenu = true, interactionsOn = false))
        assertFalse(desktopCardSecondaryClickActive(hasMenu = false, interactionsOn = true))
        assertFalse(desktopCardSecondaryClickActive(hasMenu = false, interactionsOn = false))
    }

    @Test fun thePressTableOfTheContract() {
        val rows = DesktopUxContract.objects(secondary.getValue("press_table"))
        assertEquals(4, rows.size)
        for (row in rows) {
            val primary = row.getValue("primary").jsonPrimitive.boolean
            val secondaryButton = row.getValue("secondary").jsonPrimitive.boolean
            assertEquals(row.getValue("owns").jsonPrimitive.boolean, desktopCardOwnsSecondaryPress(primary, secondaryButton), "$primary $secondaryButton")
        }
    }

    @Test fun theFollowTableOfTheContract() {
        val rows = DesktopUxContract.objects(secondary.getValue("follow_table"))
        assertEquals(4, rows.size)
        for (row in rows) {
            val present = row.getValue("present").jsonPrimitive.boolean
            val pressed = row.getValue("pressed").jsonPrimitive.boolean
            assertEquals(
                DesktopCardSecondaryFollow.valueOf(DesktopUxContract.string(row, "step")),
                desktopCardSecondaryFollow(present, pressed),
                "$present $pressed",
            )
        }
    }

    @Test fun aRightClickOpensOnceAtThePressAndConsumesThePressAndTheRelease() {
        assertEquals(listOf("PRESS", "RELEASE"), DesktopUxContract.strings(secondary.getValue("consumes")).map { it })
        assertEquals("PRESS", DesktopUxContract.string(secondary, "opens_on"))
        // Press, one move with the button still down, release.
        assertEquals(true to listOf("PRESS", "RELEASE"), replay(false, true, listOf(true to true, true to false)))
        // Press and release at once.
        assertEquals(true to listOf("PRESS", "RELEASE"), replay(false, true, listOf(true to false)))
    }

    @Test fun aPointerThatLeavesBeforeTheReleaseConsumesOnlyThePress() {
        assertEquals(true to listOf("PRESS"), replay(false, true, listOf(true to true, false to true)))
        assertEquals(true to listOf("PRESS"), replay(false, true, emptyList()))
    }

    @Test fun aChordWithThePrimaryButtonAndAPrimaryPressAreLeftToTheVendoredGesture() {
        assertEquals(false to emptyList<String>(), replay(true, true, listOf(true to false)))
        assertEquals(false to emptyList<String>(), replay(true, false, listOf(true to false)))
        assertEquals(false to emptyList<String>(), replay(false, false, listOf(true to false)))
    }
}
