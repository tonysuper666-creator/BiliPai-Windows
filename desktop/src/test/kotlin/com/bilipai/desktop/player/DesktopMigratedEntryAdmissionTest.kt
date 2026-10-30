package com.bilipai.desktop.player

import com.android.purebilibili.feature.video.screen.resolveVideoDetailEntryPlaybackPreloadDelayMillis
import com.android.purebilibili.feature.video.screen.shouldDeferVideoDetailLoadUntilEntryTransitionFinished
import com.android.purebilibili.feature.video.screen.shouldFinishVideoDetailNavigationEntry
import com.android.purebilibili.feature.video.screen.shouldMountVideoDetailPlayerHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopMigratedEntryAdmissionTest {
    @Test
    fun returningFlagCannotBypassTheCurrentNavigationEntry() {
        assertTrue(shouldDeferVideoDetailLoadUntilEntryTransitionFinished(
            transitionEnabled = true, detailShellSharedBoundsEnabled = true,
            reuseFromMiniPlayerAtEntry = false, isReturningFromDetail = true,
            hasNavigationTransitionScope = true,
        ))
        assertFalse(shouldDeferVideoDetailLoadUntilEntryTransitionFinished(
            transitionEnabled = true, detailShellSharedBoundsEnabled = true,
            reuseFromMiniPlayerAtEntry = true, isReturningFromDetail = true,
            hasNavigationTransitionScope = true,
        ))
    }

    @Test
    fun coveredOrUnsettledEntryCannotFinishAdmission() {
        assertTrue(shouldFinishVideoDetailNavigationEntry(false, true, false, false, true))
        assertFalse(shouldFinishVideoDetailNavigationEntry(false, false, false, false, true))
        assertFalse(shouldFinishVideoDetailNavigationEntry(true, true, false, false, true))
        assertFalse(shouldFinishVideoDetailNavigationEntry(false, true, true, false, true))
        assertFalse(shouldFinishVideoDetailNavigationEntry(false, true, false, true, true))
        assertFalse(shouldFinishVideoDetailNavigationEntry(false, true, false, false, false))
    }

    @Test
    fun residentCoverCanGateFreshHostWithoutUnmountingLivePlayback() {
        assertFalse(shouldMountVideoDetailPlayerHost(false, true, false, true, false))
        assertTrue(shouldMountVideoDetailPlayerHost(true, true, false, true, false))
        assertTrue(shouldMountVideoDetailPlayerHost(false, true, false, true, true))
        assertTrue(shouldMountVideoDetailPlayerHost(false, true, false, false, false))
        assertTrue(shouldMountVideoDetailPlayerHost(false, true, true, true, false))
    }

    @Test
    fun installedDesktopObserverRetainsItsBoundedPreloadTiming() {
        assertEquals(0, resolveVideoDetailEntryPlaybackPreloadDelayMillis(-100))
        assertEquals(0, resolveVideoDetailEntryPlaybackPreloadDelayMillis(0))
        assertEquals(128, resolveVideoDetailEntryPlaybackPreloadDelayMillis(400))
        assertEquals(320, resolveVideoDetailEntryPlaybackPreloadDelayMillis(1000))
    }
}
