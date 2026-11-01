package com.bilipai.desktop.hardware

import kotlin.test.*

class DesktopHardwareVerificationTest {
    private val snapshot=HardwareSnapshot(complete=true)

    @Test fun noCandidatesDoesNotClaimCompletedDecodes() {
        val state=verifyHardwareSnapshot(snapshot,false,{true}) {_,_->emptyList()}
        assertEquals("unavailable",state.learning?.selfTestReason)
        assertTrue(state.learning!!.decodes.isEmpty())
        assertEquals("no",state.policy.hwdec)
        assertEquals(VideoEnhancement.NONE,state.policy.enhancement)
    }

    @Test fun nativeFailureDoesNotClaimCompletedDecodes() {
        val state=verifyHardwareSnapshot(snapshot,false,{true}) {_,_->error("native loader failed")}
        assertEquals("unavailable",state.learning?.selfTestReason)
        assertTrue(state.learning!!.decodes.isEmpty())
    }

    @Test fun probeOnlyNeverCallsNativeSelfTest() {
        val state=verifyHardwareSnapshot(snapshot,true,{true}) {_,_->error("must not decode")}
        assertEquals("probe_only",state.learning?.selfTestReason)
        assertFalse(state.learning!!.selfTestComplete)
    }
}
