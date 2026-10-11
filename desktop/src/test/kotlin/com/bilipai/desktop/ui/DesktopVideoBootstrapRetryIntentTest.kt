package com.bilipai.desktop.ui

import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Actual private immutable retry-parameter policy only. This is not an AWT
 * RootWindow/BootstrapSeed test and creates no owner, stamp or accepted source. */
class DesktopVideoBootstrapRetryIntentTest {
    @Test fun forcedRetryRequiresTheExactSubjectLanguageIntentAndResumePosition() {
        val type = Class.forName("com.bilipai.desktop.ui.DesktopVideoBootstrapRetryIntent")
        val intent = type.getDeclaredConstructor(String::class.java, java.lang.Long.TYPE,
            java.lang.Long.TYPE, java.lang.Boolean.TYPE, String::class.java)
            .apply { isAccessible = true }.newInstance("BV1GJ411x7h7", 104L, 27_750L, false, "en")
        val method = type.getDeclaredMethod("matches", PlaybackRequest::class.java, java.lang.Long.TYPE)
            .apply { isAccessible = true }
        fun matches(request: PlaybackRequest, resumeMs: Long = 27_750L) =
            method.invoke(intent, request, resumeMs) as Boolean
        val expected = PlaybackRequest.create("BV1GJ411x7h7", cid = 104L,
            force = true, autoPlay = false, audioLang = "en")
        assertTrue(matches(expected))
        for (different in listOf(
            expected.copy(force = false),
            expected.copy(bvid = "BV17x411w7KC"),
            expected.copy(cid = 105L),
            expected.copy(aid = 101L),
            expected.copy(autoPlay = true),
            expected.copy(autoPlay = null),
            expected.copy(ignoreSavedProgress = true),
            expected.copy(audioLang = "fr"),
            expected.copy(audioLang = null),
            expected.copy(videoCodecOverride = "avc1"),
        )) assertFalse(matches(different), different.toString())
        assertFalse(matches(expected, 27_749L))
        assertTrue(matches(expected), "Rejected parameters must not mutate the retained retry intent")
    }
}
