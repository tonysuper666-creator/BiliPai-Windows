package com.bilipai.desktop.ui

import kotlin.test.*
import kotlinx.coroutines.*

class DesktopWindowsVideoInteractionTest {
    private data class Source(val version: Long, val media: String, val start: Long = 0, val player: Any = Unit)
    private data class Pause(val source: Source, val serial: Long)
    private class Playback {
        var source = Source(1, "first")
        var paused = false
        var serial = 0L
        var pauses = 0
        var resumes = 0
        var allowPause = true
        val lifetime = DesktopWindowsDanmakuComposerLifetime(
            { source },
            { captured: Source ->
                if (!allowPause || captured != source || paused) null else {
                    pauses++; paused = true; Pause(captured, ++serial)
                }
            },
            { token: Pause ->
                if (token.source != source || token.serial != serial || !paused) false else {
                    resumes++; paused = false; ++serial; true
                }
            }, { first, second -> first == second })
    }

    @Test fun freshActualMpvSnapshotWrappersOfSameFullSourcePermitComposerResume() {
        com.bilipai.desktop.player.MpvPlayer().use { player ->
            player.loadVersioned(com.bilipai.desktop.player.PlaybackSource("file:///C:/composer-fixture.avi"))
            val original = assertNotNull(player.currentSourceSnapshot())
            val fresh = assertNotNull(player.currentSourceSnapshot())
            assertNotSame(original, fresh)
            var resumes = 0
            val lifetime = DesktopWindowsDanmakuComposerLifetime<DesktopWindowsDanmakuComposerSource, Any>(
                { player.currentSourceSnapshot()?.let { DesktopWindowsDanmakuComposerSource(player, it) } },
                { Any() }, { resumes++; true }, ::desktopWindowsDanmakuComposerSourceMatches)
            lifetime.open(true)
            assertTrue(lifetime.dismiss())
            assertEquals(1, resumes)
        }
    }

    @Test fun actualSameVersionMpvRecoveryChangesFullSourceAndRejectsComposerResume() {
        com.bilipai.desktop.player.MpvPlayer().use { player ->
            val version = player.loadVersioned(com.bilipai.desktop.player.PlaybackSource("file:///C:/composer-fixture.avi"))
            var resumes = 0
            val lifetime = DesktopWindowsDanmakuComposerLifetime<DesktopWindowsDanmakuComposerSource, Any>(
                { player.currentSourceSnapshot()?.let { DesktopWindowsDanmakuComposerSource(player, it) } },
                { Any() }, { resumes++; true }, ::desktopWindowsDanmakuComposerSourceMatches)
            lifetime.open(true)
            assertTrue(player.recoverSource(version, positionSeconds = 20.0, paused = true))
            assertEquals(version, player.currentSourceVersion)
            assertFalse(lifetime.dismiss())
            assertEquals(0, resumes)
        }
    }

    @Test fun composerRestoresItsOwnAutomaticPauseExactlyOnce() {
        val p = Playback(); p.lifetime.open(true)
        assertTrue(p.paused); assertEquals(1, p.pauses)
        assertTrue(p.lifetime.dismiss()); assertFalse(p.paused); assertEquals(1, p.resumes)
        assertFalse(p.lifetime.dismiss()); assertEquals(1, p.resumes)
    }

    @Test fun alreadyPausedPlaybackIsNotResumedByComposer() {
        val p = Playback(); p.paused = true; p.lifetime.open(false)
        assertFalse(p.lifetime.dismiss()); assertTrue(p.paused)
        assertEquals(0, p.pauses); assertEquals(0, p.resumes)
    }

    @Test fun fullSnapshotReplacementAtSameVersionDoesNotResumeSuccessor() {
        for (replacement in listOf(Source(1, "replacement"), Source(1, "first", 20),
            Source(1, "first", player = Any()), Source(2, "first"))) {
            val p = Playback(); p.lifetime.open(true); p.source = replacement
            assertFalse(p.lifetime.dismiss()); assertTrue(p.paused); assertEquals(0, p.resumes)
        }
    }

    @Test fun explicitUserPauseSupersedesComposerResumeToken() {
        val p = Playback(); p.lifetime.open(true)
        // The production token is checked by MPV's existing pauseIntentSerial.
        p.serial++; p.paused = true
        assertFalse(p.lifetime.dismiss()); assertTrue(p.paused); assertEquals(0, p.resumes)
    }

    @Test fun oldWindowRetirementCannotClearNewComposerAtSameSource() {
        val p = Playback(); p.lifetime.open(true); val old = assertNotNull(p.lifetime.stamp())
        p.paused = false; p.lifetime.open(true); val fresh = assertNotNull(p.lifetime.stamp())
        assertNotSame(old, fresh); assertFalse(p.lifetime.retire(old)); assertSame(fresh, p.lifetime.stamp())
        assertTrue(p.lifetime.dismiss()); assertEquals(1, p.resumes)
    }

    @Test fun deferredSendACompletesWithoutClosingOrClearingReopenedComposerB(): Unit = runBlocking {
        val p = Playback(); p.lifetime.open(true)
        val stampA = assertNotNull(p.lifetime.stamp())
        var draft = com.android.purebilibili.feature.video.viewmodel.VideoComposerDraftState(
            videoId = "local-fixture", danmaku = com.android.purebilibili.feature.video.viewmodel.DanmakuComposerDraft("A"))
        val complete = CompletableDeferred<Unit>()
        var localSentEvents = 0
        val pendingSendA = launch(start = CoroutineStart.UNDISPATCHED) {
            complete.await()
            p.lifetime.completeSubmission(stampA) {
                p.lifetime.dismiss()
                draft = draft.copy(danmaku = com.android.purebilibili.feature.video.viewmodel.DanmakuComposerDraft())
            }
            localSentEvents++ // Existing successful network/local event remains independent.
        }
        assertTrue(p.lifetime.dismiss())
        p.lifetime.open(true)
        val stampB = assertNotNull(p.lifetime.stamp())
        draft = draft.copy(danmaku = com.android.purebilibili.feature.video.viewmodel.DanmakuComposerDraft("B", true))
        complete.complete(Unit); pendingSendA.join()
        assertSame(stampB, p.lifetime.stamp()); assertTrue(p.paused)
        assertEquals("B", draft.danmaku.text); assertTrue(draft.danmaku.attentionCommand)
        assertEquals(1, p.resumes); assertEquals(1, localSentEvents)
        assertTrue(p.lifetime.completeSubmission(stampB) { p.lifetime.dismiss() })
        assertEquals(2, p.resumes)
    }

    @Test fun retiringExactComposerNeverResumesAndIsIdempotent() {
        val p = Playback(); p.lifetime.open(true); val stamp = assertNotNull(p.lifetime.stamp())
        assertTrue(p.lifetime.retire(stamp)); assertFalse(p.lifetime.retire(stamp))
        assertNull(p.lifetime.stamp()); assertFalse(p.lifetime.dismiss()); assertTrue(p.paused)
        assertEquals(0, p.resumes)
    }

    @Test fun rejectedAutomaticPauseGrantsNoResume() {
        val p = Playback(); p.allowPause = false; p.lifetime.open(true)
        assertFalse(p.lifetime.dismiss()); assertEquals(0, p.pauses); assertEquals(0, p.resumes)
    }

    @Test fun changedOwnerAtFinalAdmissionCannotPublish() {
        var current = true; var writes = 0
        val lease = DesktopWindowsVideoInteractionLease({ current }, { action -> current = false; action(); true })
        assertFalse(lease.commit { writes++ }); assertEquals(0, writes)
    }

    @Test fun windowDisposalInsideAdmissionRejectsLateCallback() {
        lateinit var lease: DesktopWindowsVideoInteractionLease
        var writes = 0
        lease = DesktopWindowsVideoInteractionLease({ true }, { action -> lease.close(); action(); true })
        assertFalse(lease.commit { writes++ }); assertFalse(lease.isOwned()); assertEquals(0, writes)
    }

    @Test fun gateRejectionDoesNotInventSuccessEvenIfOwnerIsStillCurrent() {
        var writes = 0
        val lease = DesktopWindowsVideoInteractionLease({ true }, { false })
        assertFalse(lease.commit { writes++ }); assertFalse(lease.effect { writes++ }); assertEquals(0, writes)
    }

    @Test fun nativeClipboardAndBrowserEffectsAreOutsideAdmission() {
        var inside = false; var effects = 0
        val lease = DesktopWindowsVideoInteractionLease({ true }, { action ->
            inside = true; try { action(); true } finally { inside = false }
        })
        assertTrue(lease.effect { assertFalse(inside); effects++ })
        lease.close(); assertFalse(lease.effect { effects++ }); assertEquals(1, effects)
    }

    @Test fun successfulNativeOpenSurvivesNormalSheetDismissalButNotSourceRetirement(): Unit = runBlocking {
        var presentation = true; var source = true; var insideAdmission = false
        val handoff = DesktopWindowsNativeShareHandoff({ presentation }, { source }, { action ->
            insideAdmission = true; try { action(); true } finally { insideAdmission = false }
        })
        var actualNativeOwner: (() -> Boolean)? = null
        assertTrue(handoff.open { owner -> assertFalse(insideAdmission); actualNativeOwner = owner; true })
        presentation = false
        assertTrue(checkNotNull(actualNativeOwner).invoke())
        source = false
        assertFalse(checkNotNull(actualNativeOwner).invoke())
    }

    @Test fun dismissedQueuedSheetNeverOpensNativeChooser(): Unit = runBlocking {
        var presentation = true; var opens = 0
        val handoff = DesktopWindowsNativeShareHandoff({ presentation }, { true }, { action -> action(); true })
        presentation = false
        assertFalse(handoff.open { opens++; true }); assertEquals(0, opens)
    }

    @Test fun dismissalOrSourceRetirementDuringNativeShowRejectsHandoff(): Unit = runBlocking {
        for (retireSource in listOf(false, true)) {
            var presentation = true; var source = true
            val handoff = DesktopWindowsNativeShareHandoff({ presentation }, { source }, { action -> action(); true })
            var nativeOwner: (() -> Boolean)? = null
            assertFalse(handoff.open { owner -> nativeOwner = owner
                if (retireSource) source = false else presentation = false
                true
            })
            assertFalse(checkNotNull(nativeOwner).invoke())
        }
    }

    @Test fun failedActualNativeShowDoesNotGrantPostDismissalLifetime(): Unit = runBlocking {
        var presentation = true
        val handoff = DesktopWindowsNativeShareHandoff({ presentation }, { true }, { action -> action(); true })
        assertFalse(handoff.open { false }); presentation = false
        assertFalse(handoff.isOwned())
    }

    @Test fun finalAdmissionRejectionDoesNotPromoteOpenedNativeChooser(): Unit = runBlocking {
        var gates = 0
        val handoff = DesktopWindowsNativeShareHandoff({ true }, { true }, { action ->
            if (++gates == 1) { action(); true } else false
        })
        var nativeOwner: (() -> Boolean)? = null
        assertFalse(handoff.open { owner -> nativeOwner = owner; true })
        assertEquals(2, gates); assertFalse(checkNotNull(nativeOwner).invoke())
    }
}
