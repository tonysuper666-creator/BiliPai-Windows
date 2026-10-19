package com.bilipai.desktop.ui

import com.android.purebilibili.feature.video.ui.feedback.canSubmitVideoFollowAction
import com.android.purebilibili.feature.video.ui.feedback.canSubmitVideoTripleAction
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The two cases v0.4.6 CommandDanmakuOverlayPolicyTest gained in 26ea45ac, ported by hand. The committed copy of that
 * original test is the v029 adaptation and stays byte-pinned, so the new cases live here; the Python source test checks
 * that these two bodies equal the upstream ones. The last case walks more combinations of the same two guards. */
class DesktopV046CommandCardPolicyTest {
    @Test
    fun `attention follow is disabled while unknown submitting or already followed`() {
        assertFalse(canSubmitVideoFollowAction(false, false, false))
        assertTrue(canSubmitVideoFollowAction(true, false, false))
        assertFalse(canSubmitVideoFollowAction(true, false, true))
        assertFalse(canSubmitVideoFollowAction(true, true, false))
    }

    @Test
    fun `partial triple permits retry but pending and complete states block submission`() {
        assertFalse(canSubmitVideoTripleAction(false, true, false, true, false))
        assertTrue(canSubmitVideoTripleAction(true, true, false, true, false))
        assertFalse(canSubmitVideoTripleAction(true, true, false, true, true))
        assertFalse(canSubmitVideoTripleAction(true, true, true, true, false))
    }

    @Test
    fun `a card with nothing done yet and a confirmed status can submit exactly once`() {
        assertTrue(canSubmitVideoTripleAction(true, false, false, false, false))
        assertFalse(canSubmitVideoTripleAction(true, false, false, false, true))
        assertTrue(canSubmitVideoTripleAction(true, true, true, false, false))
        assertTrue(canSubmitVideoTripleAction(true, false, true, true, false))
    }
}
