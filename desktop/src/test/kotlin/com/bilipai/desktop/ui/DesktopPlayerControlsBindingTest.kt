package com.bilipai.desktop.ui

import com.bilipai.desktop.player.PlayerPreferences
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * The admitted actions of the player controls, run with fake capabilities: a fake page that is current or not, a fake command
 * gate that accepts or refuses, and recorders. No player, no window, no GPU, no network.
 */
class DesktopPlayerControlsBindingTest {
    private class Rig(var isCurrent: Boolean = true, var accepts: Boolean = true, var muted: Boolean = false) {
        val events = mutableListOf<String>()
        val saved = mutableListOf<PlayerPreferences>()
        val speedCalls = mutableListOf<Double>()
        val preferences = PlayerPreferences(volume = 40.0, speed = 1.5, muted = false)
        val speed: (Double) -> Unit = { value -> speedCalls.add(value) }
        val binding = DesktopPlayerControlsBinding(
            current = { isCurrent },
            command = { block -> if (accepts) { block(); true } else false },
            togglePause = { events.add("togglePause") },
            setMuted = { value -> events.add("setMuted($value)") },
            setVolume = { value -> events.add("setVolume($value)") },
            isMuted = { muted },
            preferences = preferences,
            preferencesChanged = { value -> saved.add(value) },
            navigate = { forward -> events.add("navigate($forward)") },
            switchQuality = { id -> events.add("switchQuality($id)") },
            seekToSeconds = { seconds -> events.add("seek($seconds)") },
            goBack = { events.add("back") },
            enterPictureInPicture = { events.add("pip") },
            toggleFullscreen = { events.add("fullscreen") },
            speed = speed,
        )
    }

    @Test fun playPauseTogglesOnlyWhenTheCommandGateAcceptsIt() {
        val rig = Rig(accepts = false)
        rig.binding.playPause()
        assertEquals(emptyList<String>(), rig.events)
        rig.accepts = true
        rig.binding.playPause()
        assertEquals(listOf("togglePause"), rig.events)
    }

    @Test fun muteFlipsTheStateAndStoresTheNewFlagOnlyAfterTheCommandRan() {
        val refused = Rig(accepts = false)
        refused.binding.mute()
        assertEquals(emptyList<String>(), refused.events)
        assertEquals(emptyList<PlayerPreferences>(), refused.saved)

        val rig = Rig(muted = false)
        rig.binding.mute()
        assertEquals(listOf("setMuted(true)"), rig.events)
        assertEquals(listOf(rig.preferences.copy(muted = true)), rig.saved)
    }

    @Test fun muteReadsTheMutedFlagWhenItIsClickedNotWhenTheBindingWasBuilt() {
        val rig = Rig(muted = false)
        rig.muted = true
        rig.binding.mute()
        assertEquals(listOf("setMuted(false)"), rig.events)
        assertEquals(listOf(rig.preferences.copy(muted = false)), rig.saved)
    }

    @Test fun volumeSetsTheValueAndStoresItOnlyAfterTheCommandRan() {
        val refused = Rig(accepts = false)
        refused.binding.volume(55.0)
        assertEquals(emptyList<String>(), refused.events)
        assertEquals(emptyList<PlayerPreferences>(), refused.saved)

        val rig = Rig()
        rig.binding.volume(55.0)
        assertEquals(listOf("setVolume(55.0)"), rig.events)
        assertEquals(listOf(rig.preferences.copy(volume = 55.0)), rig.saved)
        assertEquals(1.5, rig.saved.single().speed)
        assertEquals(false, rig.saved.single().muted)
    }

    @Test fun backQualitySeekPictureInPictureAndFullscreenNeedTheCurrentPage() {
        val stale = Rig(isCurrent = false)
        stale.binding.back()
        stale.binding.quality(80)
        stale.binding.seek(12.5)
        stale.binding.pictureInPicture()
        stale.binding.fullscreen()
        assertEquals(emptyList<String>(), stale.events)

        val rig = Rig()
        rig.binding.back()
        rig.binding.quality(80)
        rig.binding.seek(12.5)
        rig.binding.pictureInPicture()
        rig.binding.fullscreen()
        assertEquals(listOf("back", "switchQuality(80)", "seek(12.5)", "pip", "fullscreen"), rig.events)
    }

    @Test fun previousAndNextHandTheDirectionToTheLeafAndLeaveTheAdmissionToIt() {
        // navigateFromThisClick of the leaf checks the page itself, as it did when the bar called it.
        val rig = Rig(isCurrent = false, accepts = false)
        rig.binding.previous()
        rig.binding.next()
        assertEquals(listOf("navigate(false)", "navigate(true)"), rig.events)
    }

    @Test fun speedIsTheLeafsOwnCallbackHandedOutUnchanged() {
        val rig = Rig(isCurrent = false, accepts = false)
        assertSame(rig.speed, rig.binding.speed)
        rig.binding.speed(2.0)
        assertEquals(listOf(2.0), rig.speedCalls)
        assertEquals(emptyList<String>(), rig.events)
    }
}
