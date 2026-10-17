package com.bilipai.desktop.player

import kotlin.test.*
import org.junit.jupiter.api.Test

/** Production control entry points, without starting a native core, window or audio device. */
class DesktopWindowsAudioOutputProtocolTest {
    private fun source(label: String, paused: Boolean = false) =
        PlaybackSource("https://synthetic.invalid/$label", title = label, startPositionSeconds = 31.0, startPaused = paused)

    @Test fun `actual load publishes resource intent and stop retires only its own intent`() {
        val focus = DesktopWindowsAudioOutputFocus(); val video = MpvPlayer(audioOutputFocus = focus)
        val music = MpvPlayer(audioOutputFocus = focus)
        try {
            val videoVersion = video.loadVersioned(source("video"))
            assertEquals(videoVersion, focus.observation().requestedSourceVersion)
            assertNull(focus.observation().desiredSequence) // No native/media admission has happened.
            val oldSequence = focus.observation().requestedSequence
            music.loadVersioned(source("music"))
            val musicSequence = focus.observation().requestedSequence
            assertNotEquals(oldSequence, musicSequence)
            video.stop()
            assertEquals(musicSequence, focus.observation().requestedSequence)
            music.stop(); assertNull(focus.observation().requestedSequence)
        } finally { video.close(); music.close() }
        assertEquals(0, focus.observation().actors); assertEquals(0, focus.observation().nativeSessions)
    }
    @Test fun `same source replay issues a new intent and user resume supersedes the earlier pause serial`() {
        val focus = DesktopWindowsAudioOutputFocus(); val player = MpvPlayer(audioOutputFocus = focus)
        try {
            player.loadVersioned(source("one"))
            val first = focus.observation()
            player.loadVersioned(source("one"))
            val second = focus.observation()
            assertNotEquals(first.requestedSequence, second.requestedSequence)
            assertNotEquals(first.requestedSourceVersion, second.requestedSourceVersion)
            player.setPaused(true) // A still-unattached Load retains its permission to prepare a paused file.
            assertTrue(player.state.value.paused)
            player.setPaused(false)
            val resumed = focus.observation()
            assertNotEquals(second.requestedSequence, resumed.requestedSequence)
            assertNotEquals(second.requestedPauseSerial, resumed.requestedPauseSerial)
            assertEquals(second.requestedSourceVersion, resumed.requestedSourceVersion)
        } finally { player.close() }
    }
    @Test fun `stale source pause and stop cannot mutate a replacement resource intent`() {
        val focus = DesktopWindowsAudioOutputFocus(); val player = MpvPlayer(audioOutputFocus = focus)
        try {
            val old = player.loadVersioned(source("old")); player.loadVersioned(source("new"))
            val current = focus.observation()
            assertFalse(player.pauseIfSourceVersion(old)); assertFalse(player.stopIfSourceVersion(old))
            assertEquals(current, focus.observation()); assertFalse(player.state.value.paused)
        } finally { player.close() }
    }
    @Test fun `future output preference and other settings never mint playback focus`() {
        val focus = DesktopWindowsAudioOutputFocus(); val player = MpvPlayer(audioOutputFocus = focus)
        try {
            val owner = Any(); player.bindWindowsAudioOutputOwner(owner)
            player.requestWindowsAudioOutputPreferences(owner, DesktopWindowsAudioOutputPreferences(true))
            player.setMuted(true); player.setVolume(23.0); player.setSpeed(1.25)
            assertNull(focus.observation().desiredSequence)
            player.loadVersioned(source("audio", true)); val before = focus.observation()
            player.requestWindowsAudioOutputPreferences(owner, DesktopWindowsAudioOutputPreferences(false))
            assertEquals(before, focus.observation()); assertEquals(31.0, player.state.value.positionSeconds)
            assertTrue(player.state.value.paused); assertTrue(player.state.value.muted)
            assertEquals(23.0, player.state.value.volume); assertEquals(1.25, player.state.value.speed)
        } finally { player.close() }
    }
    @Test fun `null diagnostic players cannot take the real root's physical output intent`() {
        val focus = DesktopWindowsAudioOutputFocus(); val root = MpvPlayer(audioOutputFocus = focus)
        val diagnostic = MpvPlayer(useNullAudioOutput = true, audioOutputFocus = focus)
        try {
            root.loadVersioned(source("root")); val actual = focus.observation()
            diagnostic.loadVersioned(source("fixture")); diagnostic.setPaused(false); diagnostic.stop()
            assertEquals(actual, focus.observation())
        } finally { diagnostic.close(); root.close() }
    }
    @Test fun `closing old player cannot retire newer owner and leaves no session bookkeeping`() {
        val focus = DesktopWindowsAudioOutputFocus(); val old = MpvPlayer(audioOutputFocus = focus)
        val next = MpvPlayer(audioOutputFocus = focus)
        try {
            old.loadVersioned(source("old")); next.loadVersioned(source("next"))
            val newer = focus.observation().requestedSequence
            old.close(); old.close()
            assertEquals(newer, focus.observation().requestedSequence)
            assertEquals(1, focus.observation().actors)
        } finally { old.close(); next.close() }
        assertNull(focus.observation().desiredSequence); assertEquals(0, focus.observation().actors)
    }

    @Test fun `headless native lifecycle attaches without activation and repeated actors leave no physical lease`() {
        val focus = DesktopWindowsAudioOutputFocus()
        repeat(4) { index ->
            MpvPlayer(audioOutputFocus = focus).use { player ->
                val version = player.loadVersioned(source("memory-$index"))
                val staged = focus.observation().requestedSequence
                PremiumRecoveryActor(player).use { actor ->
                    assertEquals(staged, focus.observation().requestedSequence)
                    assertNull(focus.observation().desiredSequence) // Attachment cannot claim playback.
                    assertTrue(player.recoverSource(version, positionSeconds = 31.0, paused = false))
                    actor.drain()
                    assertEquals(1, actor.native.loads.size)
                    assertEquals(version, focus.observation().desiredSourceVersion)
                    assertEquals(1, focus.observation().unreleasedSessions)
                }
                assertEquals(0, focus.observation().nativeSessions)
                assertEquals(1, focus.observation().actors)
            }
            assertEquals(0, focus.observation().actors)
            assertEquals(0, focus.observation().nativeSessions)
        }
    }
    @Test fun `actual queued replacement load waits until earlier memory actor retires then normal loop advances it`() {
        val focus = DesktopWindowsAudioOutputFocus()
        MpvPlayer(audioOutputFocus = focus).use { video ->
            MpvPlayer(audioOutputFocus = focus).use { music ->
                val videoVersion = video.loadVersioned(source("video-core"))
                PremiumRecoveryActor(video).use { old ->
                    assertTrue(video.recoverSource(videoVersion, paused = false)); old.drain()
                    assertEquals(1, old.native.loads.size)
                    val musicVersion = music.loadVersioned(source("music-core"))
                    PremiumRecoveryActor(music).use { next ->
                        assertTrue(music.recoverSource(musicVersion, paused = false)); next.drain()
                        assertTrue(next.native.loads.isEmpty())
                        assertEquals(DesktopWindowsAudioOutputPhase.WAITING_FOR_RELEASE, music.windowsAudioOutput.value.phase)
                        assertEquals(2, focus.observation().unreleasedSessions)
                        old.close() // No handle existed; memory native resources are released first.
                        assertEquals(1, focus.observation().nativeSessions)
                        next.advanceAudioFocus() // The same production event-loop continuation, no manual load.
                        assertEquals(1, next.native.loads.size)
                        assertEquals(musicVersion, focus.observation().desiredSourceVersion)
                        assertEquals("https://synthetic.invalid/music-core", next.native.loads.single()[1])
                    }
                }
            }
        }
        assertEquals(0, focus.observation().actors)
        assertEquals(0, focus.observation().nativeSessions)
    }
    @Test fun `memory fixture teardown cannot acknowledge an unrelated unknown native core`() {
        val focus = DesktopWindowsAudioOutputFocus()
        val foreign = focus.registerActor(); val unknown = focus.registerSession(foreign)
        try {
            MpvPlayer(audioOutputFocus = focus).use { player ->
                val version = player.loadVersioned(source("blocked"))
                PremiumRecoveryActor(player).use { actor ->
                    assertTrue(player.recoverSource(version, paused = false)); actor.drain()
                    assertTrue(actor.native.loads.isEmpty())
                }
                assertEquals(1, focus.observation().nativeSessions)
                assertEquals(1, focus.observation().unreleasedSessions)
                assertFalse(focus.isReleased(unknown))
            }
            assertEquals(1, focus.observation().actors)
            assertEquals(1, focus.observation().nativeSessions)
        } finally {
            focus.markClosing(unknown); focus.acknowledgeDestroyed(unknown); focus.retire(foreign)
        }
        assertEquals(0, focus.observation().actors)
        assertEquals(0, focus.observation().nativeSessions)
    }
}
