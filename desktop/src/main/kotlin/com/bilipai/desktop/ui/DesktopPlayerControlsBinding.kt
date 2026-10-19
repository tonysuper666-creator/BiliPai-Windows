package com.bilipai.desktop.ui

import com.bilipai.desktop.player.PlayerPreferences

/*
 * The admitted actions of the ordinary-video player controls (spec 4.2 rule 6 and 4.15 L7, slice V2). No Compose, AWT or
 * Windows dependency.
 *
 * The leaf (DesktopWindowsVideoPhysicalLeaf) owns the player. It alone knows whether this page is still the current one
 * (current()), how to dispatch a command through the admitted native path (command {}), the playback facade and the
 * Root actions. It builds this object from them and hands out plain callbacks. The LEGACY control bar and its top strip
 * call these callbacks today; the carrier overlay of the DESKTOP layout will call the same ones, so both layouts run one
 * copy of every admission rule and the overlay never reaches the player, the assembly or the native window.
 *
 * Each action is the lambda that the leaf used to pass to the bar, moved here unchanged: a player command runs only when
 * command {} accepts it, and a preference is written only after its command ran. A value that the original lambda read
 * when it ran (the muted flag) is read through a getter, so a click sees the value of that moment and not the one of the
 * last composition. The leaf builds a new binding on every composition, as it built a new lambda before.
 *
 * Mirrored by desktop/tools/ux/ux_leaf_model.py; DesktopPlayerControlsBindingTest and test_ux_leaf_layout_binding.py
 * check the agreement.
 */
internal class DesktopPlayerControlsBinding(
    private val current: () -> Boolean,
    private val command: (() -> Unit) -> Boolean,
    private val togglePause: () -> Unit,
    private val setMuted: (Boolean) -> Unit,
    private val setVolume: (Double) -> Unit,
    private val isMuted: () -> Boolean,
    private val preferences: PlayerPreferences,
    private val preferencesChanged: (PlayerPreferences) -> Unit,
    private val navigate: (Boolean) -> Unit,
    private val switchQuality: (Int) -> Unit,
    private val seekToSeconds: (Double) -> Unit,
    private val goBack: () -> Unit,
    private val enterPictureInPicture: () -> Unit,
    private val toggleFullscreen: () -> Unit,
    /** The leaf's own speed change. It checks current() and writes the speed preference itself, so it is handed out as it is. */
    val speed: (Double) -> Unit,
) {
    val back: () -> Unit = { if (current()) goBack() }
    val playPause: () -> Unit = { command { togglePause() } }
    val previous: () -> Unit = { navigate(false) }
    val next: () -> Unit = { navigate(true) }
    val mute: () -> Unit = { if (command { setMuted(!isMuted()) }) preferencesChanged(preferences.copy(muted = !isMuted())) }
    val volume: (Double) -> Unit = { value -> if (command { setVolume(value) }) preferencesChanged(preferences.copy(volume = value)) }
    val quality: (Int) -> Unit = { id -> if (current()) switchQuality(id) }
    val seek: (Double) -> Unit = { seconds -> if (current()) seekToSeconds(seconds) }
    val pictureInPicture: () -> Unit = { if (current()) enterPictureInPicture() }
    val fullscreen: () -> Unit = { if (current()) toggleFullscreen() }
}
