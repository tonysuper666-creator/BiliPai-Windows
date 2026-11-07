package com.android.purebilibili.feature.plugin

import com.android.purebilibili.core.cooldown.CooldownStatus
import com.android.purebilibili.core.cooldown.PlaybackCooldownManager as Api
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/** Standalone only: the unchanged API algorithm receives a test-only wall clock binding.
 * This is deliberately separate from transport cooldown and the normal Gradle test source set. */
class PlaybackApiCooldownBoundaryTest {
    private var now = 1_000_000L
    private val savedClock = CdnTransferClock.nowMs
    @BeforeEach fun prepare() { CdnTransferClock.nowMs = { now }; Api.clearAll() }
    @AfterEach fun cleanup() { Api.clearAll(); CdnTransferClock.nowMs = savedClock }

    @Test fun videoCooldownExpiresAtSixtySecondsWithoutBlockingOtherVideos() {
        repeat(2) { Api.recordFailure("video-a") }
        assertEquals(CooldownStatus.Ready, Api.getCooldownStatus("video-a"))
        Api.recordFailure("video-a")
        assertEquals(CooldownStatus.Ready, Api.getCooldownStatus("video-b"))
        now += 59_999
        assertEquals(CooldownStatus.VideoCooldown(1, "video-a"), Api.getCooldownStatus("video-a"))
        now++
        assertEquals(CooldownStatus.Ready, Api.getCooldownStatus("video-a"))
    }

    @Test fun globalCooldownSpansVideosUntilExactTwoMinuteBoundary() {
        repeat(7) { Api.recordFailure("video-$it") }
        assertEquals(CooldownStatus.Ready, Api.getCooldownStatus("unrelated"))
        Api.recordFailure("video-7")
        now += 119_999
        assertEquals(CooldownStatus.GlobalCooldown(1), Api.getCooldownStatus("unrelated"))
        now++
        assertEquals(CooldownStatus.Ready, Api.getCooldownStatus("unrelated"))
        assertEquals(0, Api.getConsecutiveFailures())
    }

    @Test fun successClearsGlobalButRetainsAlreadyCooledVideoPerUpstream() {
        repeat(3) { Api.recordFailure("video-a") }
        repeat(5) { Api.recordFailure("other-$it") }
        Api.recordSuccess()
        assertEquals(CooldownStatus.Ready, Api.getCooldownStatus("video-b"))
        assertEquals(CooldownStatus.VideoCooldown(60_000, "video-a"), Api.getCooldownStatus("video-a"))
    }

    @Test fun accountSessionClearAllResetsBothCooldownsAndFailureCounts() {
        repeat(3) { Api.recordFailure("video-a") }
        repeat(5) { Api.recordFailure("other-$it") }
        Api.clearAll() // The existing DesktopRepository account-session reset entry point.
        assertEquals(CooldownStatus.Ready, Api.getCooldownStatus("video-a"))
        assertEquals(CooldownStatus.Ready, Api.getCooldownStatus("another-video"))
        repeat(2) { Api.recordFailure("video-a") }
        assertEquals(CooldownStatus.Ready, Api.getCooldownStatus("video-a"))
    }
}
