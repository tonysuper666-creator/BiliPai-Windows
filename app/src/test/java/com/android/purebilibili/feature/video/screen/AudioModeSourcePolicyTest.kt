package com.android.purebilibili.feature.video.screen

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioModeSourcePolicyTest {
    @Test
    fun oldTrackAndDifferentPartDoNotResolveNewEntry() {
        assertFalse(matchesAudioModeInitialSource("BVnew", 22L, "BVold", 11L))
        assertFalse(matchesAudioModeInitialSource("BVnew", 22L, "BVnew", 11L))
        assertTrue(matchesAudioModeInitialSource("BVnew", 22L, "BVnew", 22L))
    }

    @Test
    fun unspecifiedPartAndNowPlayingEntryAcceptTheirExistingContent() {
        assertTrue(matchesAudioModeInitialSource("BVnew", 0L, "BVnew", 22L))
        assertTrue(matchesAudioModeInitialSource("", 0L, "BVplaying", 33L))
    }
}
