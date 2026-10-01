package com.bilipai.desktop.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toArgb
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.android.purebilibili.core.store.HomeSettings
import com.android.purebilibili.core.store.LocalPlaylist
import com.android.purebilibili.core.store.PlayHistoryEntry
import com.android.purebilibili.core.store.PlayLastSession
import com.android.purebilibili.data.model.response.VideoItem
import com.android.purebilibili.data.repository.SearchRepository
import com.android.purebilibili.data.repository.DesktopOriginalExternalPlaylistSchema as Schema
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Required views of Root's already owned music/library/request actors.
 * The full original UI never constructs another player, queue, Store or client.
 * Root adapters must preserve the caller Job and Store -> entry/source admission.
 */
internal interface DesktopOriginalAudioHistoryPort {
    fun recent(limit: Int): Flow<List<PlayHistoryEntry>>
    fun lastSession(): Flow<PlayLastSession?>
    suspend fun record(entry: PlayHistoryEntry)
    suspend fun saveLastSession(session: PlayLastSession)
}
internal interface DesktopOriginalExternalPlaylistActions {
    suspend fun loadImportCheckpoint(context: DesktopOriginalPlayerSettingsContext): Schema.ImportCheckpoint?
    suspend fun saveImportCheckpoint(context: DesktopOriginalPlayerSettingsContext, checkpoint: Schema.ImportCheckpoint)
    suspend fun clearImportCheckpoint(context: DesktopOriginalPlayerSettingsContext)
    suspend fun fetchPlaylist(source: Schema.Source, id: String): Result<Schema.ExternalPlaylistMeta>
    suspend fun matchTracks(tracks: List<Schema.ExternalTrack>, startIndex: Int = 0,
        onProgress: suspend (completed: Int, total: Int, outcome: Schema.MatchOutcome) -> Unit): List<Schema.MatchOutcome>
    suspend fun search(keyword: String): Result<Pair<List<VideoItem>, SearchRepository.SearchPageInfo>>
}
internal interface DesktopOriginalMusicUiPlatform {
    val context: DesktopOriginalPlayerSettingsContext
    val homeSettings: StateFlow<HomeSettings>
    val systemReduceMotion: StateFlow<Boolean>
    val isInBackground: StateFlow<Boolean>
    val blurEffectsAvailable: Boolean
    val volume: StateFlow<Float>
    val maximumVolumeStep: Int
    fun setVolumeFraction(value: Float)
    val history: DesktopOriginalAudioHistoryPort
    val externalPlaylist: DesktopOriginalExternalPlaylistActions
    suspend fun savePlaylist(playlist: LocalPlaylist)
    suspend fun loadOwnedArtwork(coverUrl: String): DesktopMusicRaster?
    /** Capture/check the caller Job outside and inside Root's Store -> entry gate.
     * This only publishes Compose state; IO/native waits stay outside the gate. */
    suspend fun commitUi(block: () -> Unit)
}
internal val LocalDesktopOriginalMusicUiPlatform = staticCompositionLocalOf<DesktopOriginalMusicUiPlatform> {
    error("Complete original music UI requires Root's same music/native/library/settings owner")
}

/** Concrete acquisition backend over Root's SAME SingletonImageLoader. Only pixels
 * are copied: the borrowed Coil Bitmap is never closed or recycled. No extra client,
 * image cache or persistent file is constructed, and cancellation is not swallowed.
 */
internal class DesktopOriginalMusicArtworkLoader(
    private val platformContext: PlatformContext,
    private val imageLoader: ImageLoader,
    private val owns: () -> Boolean,
) {
    private suspend fun checkpoint() {
        currentCoroutineContext().ensureActive()
        if (!owns()) throw CancellationException("Music artwork entry retired")
    }
    suspend fun load(coverUrl: String): DesktopMusicRaster? = withContext(Dispatchers.IO) {
        checkpoint()
        if (coverUrl.isBlank()) return@withContext null
        val result = imageLoader.execute(ImageRequest.Builder(platformContext).data(coverUrl).size(512, 512).build())
        checkpoint()
        val bitmap = ((result as? SuccessResult)?.image as? coil3.BitmapImage)?.bitmap ?: return@withContext null
        if (bitmap.isClosed || bitmap.isEmpty) return@withContext null
        val width = bitmap.width; val height = bitmap.height
        require(width > 0 && height > 0 && width.toLong() * height <= 16_777_216L)
        val copy = DesktopMusicRaster.createBitmap(width, height, DesktopMusicRaster.Config.ARGB_8888)
        try {
            val row = IntArray(width)
            for (y in 0 until height) {
                checkpoint()
                for (x in 0 until width) row[x] = bitmap.getColor(x, y)
                copy.setPixels(row, 0, width, 0, y, width, 1)
            }
            checkpoint(); copy
        } catch (failure: Throwable) { copy.recycle(); throw failure }
    }
}

/** Own copy for the CPU background; the input ImageBitmap remains borrowed. */
internal fun desktopMusicRasterCopy(bitmap: ImageBitmap): DesktopMusicRaster {
    require(bitmap.width > 0 && bitmap.height > 0 && bitmap.width.toLong() * bitmap.height <= 16_777_216L)
    val pixels = bitmap.toPixelMap()
    return DesktopMusicRaster.createBitmap(bitmap.width, bitmap.height, DesktopMusicRaster.Config.ARGB_8888).also { copy ->
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) row[x] = pixels[x, y].toArgb()
            copy.setPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
        }
    }
}
