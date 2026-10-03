package com.bilipai.desktop.player

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DesktopLegacyPlaybackPreferenceChangesTest {
    private fun commands(changes: DesktopLegacyPlaybackPreferenceChanges, next: PlayerPreferences): List<String> = buildList {
        changes.dispatch(next, { add("hw:$it") }, { add("volume:$it") }, { add("speed:$it") },
            { add("muted:$it") }, { add("audio:$it") }, { add("loop:$it") })
    }

    @Test fun volumeOnlyActionDoesNotOverwriteNewerNativeSpeedAudioOrLoop() {
        val staleWindow = PlayerPreferences(speed = 1.0, audioOnly = false, playbackMode = PlaybackMode.SEQUENTIAL)
        val newerSource = staleWindow.copy(speed = 1.75, audioOnly = true, playbackMode = PlaybackMode.REPEAT_ONE)
        val next = staleWindow.copy(volume = 80.0)
        val changes = DesktopLegacyPlaybackPreferenceChanges.between(staleWindow, next)
        assertEquals(listOf("volume:80.0"), commands(changes, next))
        val merged = changes.mergeInto(newerSource, next)
        assertEquals(1.75, merged.speed)
        assertTrue(merged.audioOnly)
        assertEquals(PlaybackMode.REPEAT_ONE, merged.playbackMode)
        assertEquals(80.0, merged.volume)
    }

    @Test fun explicitShortcutDispatchesEvenWhenWindowCachedSpeedAlreadyMatches() {
        val cached = PlayerPreferences(speed = 1.5)
        val ordinary = DesktopLegacyPlaybackPreferenceChanges.between(cached, cached)
        assertTrue(commands(ordinary, cached).isEmpty())
        assertEquals(listOf("speed:1.5"), commands(ordinary.copy(speed = true), cached))
        // This tests intent dispatch; Root/native fixture must prove the actual MPV/VM command.
    }

    @Test fun listenActorIgnoresVideoOnlyModeAndPreservesItsUnchangedRepeatFlag() {
        val before = PlayerPreferences()
        val next = before.copy(volume = 90.0, audioOnly = true)
        val calls = mutableListOf<String>()
        DesktopLegacyPlaybackPreferenceChanges.between(before, next).dispatch(next,
            { calls += "hw" }, { calls += "volume" }, { calls += "speed" },
            { calls += "muted" }, { calls += "audio" }, { calls += "loop" }, listenOnly = true)
        assertEquals(listOf("volume"), calls)
    }
}
