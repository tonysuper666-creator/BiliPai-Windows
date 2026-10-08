package com.bilipai.desktop.ui

import coil3.ImageLoader
import coil3.PlatformContext
import com.android.purebilibili.core.store.HomeSettings
import com.android.purebilibili.core.store.LocalPlaylist
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Same original MusicPlayerContent ports, using the existing AU native actor
 * and Root global settings/history/import/image owners. No ordinary assembly. */
internal class DesktopOriginalAuMusicUiBinding(
    private val page: DesktopOriginalAuMusicPage,
    override val context: DesktopOriginalPlayerSettingsContext,
    override val homeSettings: StateFlow<HomeSettings>,
    override val systemReduceMotion: StateFlow<Boolean>,
    override val isInBackground: StateFlow<Boolean>,
    override val volume: StateFlow<Float>,
    storage: DesktopOriginalMusicStorageBinding,
    override val externalPlaylist: DesktopOriginalExternalPlaylistActions,
    imageContext: PlatformContext,
    sameImageLoader: ImageLoader,
    private val updateRootVolume: (Float) -> Unit,
) : DesktopOriginalMusicUiPlatform {
    private val artwork = DesktopOriginalMusicArtworkLoader(imageContext, sameImageLoader, page::ownsRoot)
    private val persistence = storage
    override val history = storage.history
    override val blurEffectsAvailable = true // Existing Windows Java2D/Skia CPU path.
    override val maximumVolumeStep = 100 // Same actual MPV volume range.
    override fun setVolumeFraction(value: Float) {
        require(value.isFinite())
        if (page.setVolume(value.coerceIn(0f, 1f)) && page.ownsSource()) {
            // Root preference/UI callback is outside Store and native monitors.
            updateRootVolume(value.coerceIn(0f, 1f))
        }
    }
    override suspend fun savePlaylist(playlist: LocalPlaylist) = persistence.savePlaylist(playlist)
    override suspend fun loadOwnedArtwork(coverUrl: String) = artwork.load(coverUrl)
    override suspend fun commitUi(block: () -> Unit) {
        val actualCaller = currentCoroutineContext()
        actualCaller.ensureActive()
        if (!page.isCurrentUi()) throw CancellationException("AU music UI page retired")
        if (!page.window.root.entry.gate.commit {
                actualCaller.ensureActive()
                if (!page.isCurrentUi()) throw CancellationException("AU music UI page retired")
                com.bilipai.desktop.plugins.DesktopSubscriptionWriteAdmission.checkCurrentRequestOrOriginal()
                block()
            }) throw CancellationException("AU music UI page retired")
    }
}
