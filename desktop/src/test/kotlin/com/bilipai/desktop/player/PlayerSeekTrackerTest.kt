package com.bilipai.desktop.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayerSeekTrackerTest {
    @Test fun `only restart at the submitted target acknowledges the latest seek`() {
        val tracker = PlayerSeekTracker()
        tracker.submit(1, 4, 30.0, 0)
        assertNull(tracker.acknowledge(4, 15.0, 1))
        tracker.submit(2, 4, 10.0, 2)
        assertNull(tracker.acknowledge(4, 30.0, 3))
        assertEquals(2L, tracker.acknowledge(4, 10.02, 4)?.id)
        assertNull(tracker.acknowledge(4, 10.02, 5))
    }

    @Test fun `source handoff reset and timeout reject stale or never completed native seeks`() {
        val tracker = PlayerSeekTracker()
        tracker.submit(1, 4, 30.0, 0)
        assertNull(tracker.acknowledge(5, 30.0, 1))
        assertNull(tracker.acknowledge(4, 30.0, 2))
        tracker.submit(2, 5, 1.0, 3)
        tracker.reset()
        assertNull(tracker.acknowledge(5, 1.0, 4))
        tracker.submit(3, 5, 1.0, 5)
        assertNull(tracker.acknowledge(5, 1.0, 15_000_000_006L))
    }
}
