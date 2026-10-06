package com.bilipai.desktop.ui

import kotlin.test.*

class DesktopLiveChatFollowTest {
    private data class Message(val text: String)

    @Test fun incomingBurstDoesNotReplaceUsersBottomIntentWithShiftedIndices() {
        val follow = DesktopLiveChatFollowState()
        // The previously visible tail can now be many rows from the latest tail.
        assertFalse(desktopLiveChatNearBottom(149, 200))
        assertTrue(follow.mayFollow(pointerHeld = false, scrolling = false))
    }

    @Test fun userInputCancelsPendingFollowAndReadingOlderRowsRemainsProtected() {
        val initial = DesktopLiveChatFollowState()
        val pressed = initial.userInput()
        assertTrue(pressed.revision > initial.revision)
        assertFalse(pressed.mayFollow(pointerHeld = true, scrolling = false))
        val reading = pressed.userInputSettled(desktopLiveChatNearBottom(40, 200))
        repeat(20) { assertFalse(reading.mayFollow(pointerHeld = false, scrolling = false)) }
    }

    @Test fun scrollingBackToBottomOrExplicitReturnResumesFollowing() {
        val reading = DesktopLiveChatFollowState().userInput().userInputSettled(false)
        val atBottom = reading.userInputSettled(desktopLiveChatNearBottom(198, 200))
        assertTrue(atBottom.mayFollow(pointerHeld = false, scrolling = false))
        val explicit = reading.resume()
        assertTrue(explicit.following)
        assertTrue(explicit.revision > reading.revision)
        assertFalse(explicit.mayFollow(pointerHeld = true, scrolling = false))
        assertFalse(explicit.mayFollow(pointerHeld = false, scrolling = true))
    }

    @Test fun newRoomStartsFollowingAndDoesNotInheritTheOldRoomsReadingHold() {
        val old = DesktopLiveChatFollowState().userInput().userInputSettled(false)
        val fresh = DesktopLiveChatFollowState()
        assertFalse(old.following)
        assertTrue(fresh.following)
    }

    @Test fun bottomToleranceMatchesTheOriginalWithoutTreatingUnlaidOutContentAsBottom() {
        assertTrue(desktopLiveChatNearBottom(-1, 0))
        assertFalse(desktopLiveChatNearBottom(-1, 1))
        assertFalse(desktopLiveChatNearBottom(-1, 200))
        assertFalse(desktopLiveChatNearBottom(197, 200))
        assertTrue(desktopLiveChatNearBottom(198, 200))
        assertTrue(desktopLiveChatNearBottom(199, 200))
    }

    @Test fun full200ItemRingKeepsReadingAnchorAndChangesTailKeyWithoutChangingSize() {
        val timeline = DesktopLiveChatTimeline<Message>()
        val first = (0 until 200).map { Message("message $it") }
        val before = timeline.update(first)
        val after = timeline.update(first.drop(1) + Message("new tail"))
        assertEquals(200, after.size)
        assertEquals(before[100].key, after[99].key)
        assertSame(before[100].item, after[99].item)
        assertEquals(before.drop(1).map { it.key }, after.dropLast(1).map { it.key })
        assertTrue(after.last().key > before.last().key)
    }

    @Test fun equalIdlessMessagesAndRepeatedInstancesHaveDistinctStableKeys() {
        val first = Message("same"); val equal = Message("same")
        val timeline = DesktopLiveChatTimeline<Message>()
        val before = timeline.update(listOf(first, equal, first))
        assertEquals(3, before.map { it.key }.distinct().size)
        val after = timeline.update(listOf(first, equal, first, Message("same")))
        assertEquals(before.map { it.key }, after.take(3).map { it.key })
        assertEquals(4, after.map { it.key }.distinct().size)
    }

    @Test fun recallKeepsOtherKeysAndRemovedInstancesAreNotRetainedForever() {
        val timeline = DesktopLiveChatTimeline<Message>()
        val a = Message("a"); val b = Message("b"); val c = Message("c")
        val before = timeline.update(listOf(a, b, c))
        val recalled = timeline.update(listOf(a, c))
        assertEquals(listOf(before[0].key, before[2].key), recalled.map { it.key })
        val later = timeline.update(listOf(a, b, c))
        assertTrue(later[1].key > before.last().key)
    }
}
