package com.bilipai.desktop.ui

import com.android.purebilibili.core.store.HomeSettings
import com.android.purebilibili.core.store.LocalPlaylist
import kotlinx.coroutines.flow.StateFlow

/** The music UI platform of the ListenVideo page. The page opens exactly one original music composable, the v0.4.6
 * recent plays sheet (PlayHistorySheet), and that sheet reads one member: [history]. Everything else belongs to the
 * full player, which only the AudioMode, AU music and video holder hosts can supply (they own a native player,
 * a volume actor and the import actions). Those members are rejected instead of inventing values, so a future
 * composable that needs them fails in its first frame on this page rather than showing wrong state.
 *
 * Nothing here touches audio output, volume or the native player: it only reads the global play history that the
 * AudioMode already writes through the same settings document. */
internal class DesktopOriginalListenHistoryPlatform(
    override val context: DesktopOriginalPlayerSettingsContext,
    override val history: DesktopOriginalAudioHistoryPort,
) : DesktopOriginalMusicUiPlatform {
    override val homeSettings: StateFlow<HomeSettings> get() = notUsed("homeSettings")
    override val systemReduceMotion: StateFlow<Boolean> get() = notUsed("systemReduceMotion")
    override val isInBackground: StateFlow<Boolean> get() = notUsed("isInBackground")
    override val blurEffectsAvailable: Boolean get() = notUsed("blurEffectsAvailable")
    override val volume: StateFlow<Float> get() = notUsed("volume")
    override val maximumVolumeStep: Int get() = notUsed("maximumVolumeStep")
    override fun setVolumeFraction(value: Float) { notUsed("setVolumeFraction") }
    override val externalPlaylist: DesktopOriginalExternalPlaylistActions get() = notUsed("externalPlaylist")
    override suspend fun savePlaylist(playlist: LocalPlaylist) { notUsed("savePlaylist") }
    override suspend fun loadOwnedArtwork(coverUrl: String): DesktopMusicRaster? { notUsed("loadOwnedArtwork") }
    override suspend fun commitUi(block: () -> Unit) { notUsed("commitUi") }

    private fun notUsed(member: String): Nothing =
        throw UnsupportedOperationException("The ListenVideo page supplies only the play history, not music UI member $member")
}
