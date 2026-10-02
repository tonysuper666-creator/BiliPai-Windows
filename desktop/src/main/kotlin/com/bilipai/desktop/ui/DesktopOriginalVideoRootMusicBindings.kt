package com.bilipai.desktop.ui

import coil3.ImageLoader
import coil3.PlatformContext
import com.android.purebilibili.core.store.LocalPlaylist
import com.android.purebilibili.core.store.HomeSettings
import com.android.purebilibili.feature.video.player.PlayMode
import com.android.purebilibili.feature.video.player.PlaylistItem
import com.android.purebilibili.feature.video.player.resolvePlaylistUiState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** BV music renders the same original playlist. This adapter has no queue,
 * cursor, persistent snapshot, Mini cache, MPV or ListenAudioSession of its own.
 */
internal class DesktopOriginalVideoRootAudioPlaylist(
    private val original: DesktopOriginalVideoPlaylistBinding,
    scope: CoroutineScope,
) : DesktopOriginalAudioPlaylistPort, DesktopOriginalVideoHolderPlaylist {
    override val playlist get() = original.playlist
    override val currentIndex get() = original.currentIndex
    override val playMode get() = original.playMode
    override val shuffleEnabled get() = original.shuffleEnabled
    override val uiState = original.uiState.stateIn(scope, SharingStarted.Eagerly,
        resolvePlaylistUiState(original.playMode.value, original.playlist.value,
            original.currentIndex.value, original.isExternalPlaylist.value,
            original.externalPlaylistSource.value, original.shuffleEnabled.value))
    override fun addToPlaylist(item: PlaylistItem) = original.addToPlaylist(item)
    override fun setPlaylist(items: List<PlaylistItem>) = original.setPlaylist(items, 0)
    override fun playAt(index: Int) = original.playAt(index)
    override fun setPlayMode(mode: PlayMode) = original.setPlayMode(mode)
    override fun setShuffleEnabled(enabled: Boolean) = original.setShuffleEnabled(enabled)
    override fun togglePlayMode() { original.togglePlayMode() }
}

/** Full original Music UI over the one Window settings/history/import/image and
 * native-volume owners. ExternalPlaylistActions is the actual required original
 * captured-request implementation; it has no empty import/search fallback.
 */
internal class DesktopOriginalVideoRootMusicBindings(
    private val assembly: DesktopOriginalVideoOwnerAssembly,
    private val gate: DesktopOriginalVideoRootGate,
    override val context: DesktopOriginalPlayerSettingsContext,
    override val homeSettings: StateFlow<HomeSettings>,
    override val systemReduceMotion: StateFlow<Boolean>,
    override val isInBackground: StateFlow<Boolean>,
    storage: DesktopOriginalMusicStorageBinding,
    override val externalPlaylist: DesktopOriginalExternalPlaylistActions,
    imageContext: PlatformContext,
    sameImageLoader: ImageLoader,
    private val updateRootVolume: (Float) -> Unit,
) : DesktopOriginalMusicUiPlatform {
    private fun owns() = gate.owns() && assembly.owns()
    private val artwork = DesktopOriginalMusicArtworkLoader(imageContext, sameImageLoader, ::owns)
    override val history = storage.history
    private val persistence = storage
    // The complete frozen Windows raster implementation uses actual Java2D and
    // Skia box blur; it does not claim Android RenderEffect/physical HDR output.
    override val blurEffectsAvailable = true
    override val maximumVolumeStep = 100 // Same MPV application volume range.
    override val volume: StateFlow<Float> = assembly.section.nativePlayer.state
        .map { (it.volume / 100.0).toFloat().coerceIn(0f, 1f) }
        .stateIn(gate.scope, SharingStarted.Eagerly,
            (assembly.section.nativePlayer.state.value.volume / 100.0).toFloat().coerceIn(0f, 1f))
    override fun setVolumeFraction(value: Float) {
        require(value.isFinite())
        if (!owns()) throw CancellationException("Original music volume owner retired")
        val expected = assembly.native.current() ?: return
        if (assembly.native.admitPlaybackDispatch(expected) {
                assembly.section.nativePlayer.setVolume(value.coerceIn(0f, 1f) * 100.0)
            }) {
            // Existing Root preference writer/UI publication is outside the
            // native/source gate. The Root callback retains its same user intent.
            if (owns()) updateRootVolume(value.coerceIn(0f, 1f))
        }
    }
    override suspend fun savePlaylist(playlist: LocalPlaylist) = persistence.savePlaylist(playlist)
    override suspend fun loadOwnedArtwork(coverUrl: String) = artwork.load(coverUrl)
    override suspend fun commitUi(block: () -> Unit) {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        if (!gate.commit {
                caller.ensureActive()
                if (!owns()) throw CancellationException("Original music UI owner retired")
                com.bilipai.desktop.plugins.DesktopSubscriptionWriteAdmission.checkCurrentRequestOrOriginal()
                block()
            }) throw CancellationException("Original music UI owner retired")
    }
}

/** The installed lyrics binding captures the logical original Subject object,
 * not a short resolver Job or a decoder/quality publication. Every later UI and
 * private-cache update is rejected after another BV/CID replaces that object.
 */
internal fun captureDesktopOriginalMusicSource(
    assembly: DesktopOriginalVideoOwnerAssembly,
    gate: DesktopOriginalVideoRootGate,
    bvid: String,
    cid: Long,
): DesktopOriginalMusicSourceLease {
    val subject = assembly.playback.subjectSnapshot.value
        ?: throw CancellationException("Original lyrics has no confirmed subject")
    require(subject.bvid == bvid && subject.cid == cid)
    fun owns() = gate.owns() && assembly.owns() && assembly.playback.subjectSnapshot.value === subject
    if (!owns()) throw CancellationException("Original lyrics subject retired")
    return DesktopOriginalMusicSourceLease(bvid, cid, ::owns) { action ->
        gate.commit { if (!owns()) throw CancellationException("Original lyrics subject retired"); action() }
    }
}
