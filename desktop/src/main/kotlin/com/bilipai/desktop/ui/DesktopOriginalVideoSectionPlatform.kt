package com.bilipai.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import com.android.purebilibili.core.store.DanmakuSettings
import com.android.purebilibili.danmaku.engine.DanmakuItem
import com.android.purebilibili.feature.anime4k.Anime4KConfig
import com.android.purebilibili.feature.anime4k.VideoEnhancementAlgorithm
import com.android.purebilibili.feature.anime4k.Anime4KPreset
import com.android.purebilibili.danmaku.parser.AdvancedDanmakuData
import com.android.purebilibili.feature.video.danmaku.CommandDanmakuItem
import com.android.purebilibili.feature.video.danmaku.DanmakuViewport
import com.android.purebilibili.feature.video.ui.components.VideoViewportLayout
import com.bilipai.desktop.player.DesktopVideoEnhancementState
import com.bilipai.desktop.settings.DesktopOriginalDanmakuPreferences
import kotlinx.coroutines.flow.StateFlow

/** Required façade of the already mounted Overlay document/session. No implementation,
 * client, parser, list/cache authority, tick/poll job or native window is created here.
 * Root must derive the pool from its source-version-tagged sole raw document.
 */
internal interface DesktopOriginalSectionDanmakuPort {
    var isEnabled: Boolean
    var opacity: Float
    var fontScale: Float
    var fontWeight: Int
    var speedFactor: Float
    var displayArea: Float
    var strokeWidth: Float
    var lineHeight: Float
    var scrollDurationSeconds: Float
    var staticDurationSeconds: Float
    var scrollFixedVelocity: Boolean
    var staticDanmakuToScroll: Boolean
    var massiveMode: Boolean
    val advancedDanmakuFlow: StateFlow<List<AdvancedDanmakuData>>
    val commandDanmakuFlow: StateFlow<List<CommandDanmakuItem>>
    fun updateSettings(settings: DanmakuSettings)
    fun clear()
    suspend fun loadDanmaku(cid: Long, aid: Long, durationHintMs: Long, bvid: String)
    fun seekTo(positionMs: Long)
    fun prepareForSeekScrub()
    fun cancelSeekScrub()
    fun getLoadedDanmakuList(): List<DanmakuItem>
}

internal interface DesktopOriginalSectionVolumePort {
    fun currentStep(): Int
    fun maximumStep(): Int
    fun setStep(step: Int)
    fun toggleMute()
}

internal interface DesktopOriginalSectionEnhancementActions {
    fun setAlgorithm(value: VideoEnhancementAlgorithm)
    fun setPreset(value: Anime4KPreset)
    fun setFsrSharpness(value: Float)
}

/** One same-entry platform binding. Every effect/transport is required, with actual
 * Store/account/route/CID/sourceVersion admission; never bind it to a no-op/default.
 * This source-only interface is not an assertion that Root has supplied these ports.
 */
internal interface DesktopOriginalVideoSectionPlatform {
    @Composable fun RenderPlayerForeground(content: @Composable () -> Unit)
    val settingsContext: DesktopOriginalPlayerSettingsContext
    val viewport: DesktopOriginalPlayerViewportPort
    val viewportAttached: Boolean
    val viewportHeightPixels: Int
    /** Actual client inset / monitor reference from the existing Window owner.
     * Windows has no Android status bar/display cutout: the client inset can be zero
     * as an explicit platform mapping; never substitute a guessed Android dimension.
     */
    val statusBarInsetPixels: Int
    val danmakuReferencePixels: Float
    val volume: DesktopOriginalSectionVolumePort
    val enhancementConfig: StateFlow<Anime4KConfig>
    val enhancementState: StateFlow<DesktopVideoEnhancementState>
    val enhancementActions: DesktopOriginalSectionEnhancementActions
    val danmaku: DesktopOriginalSectionDanmakuPort
    val danmakuPreferences: DesktopOriginalDanmakuPreferences
    val cloudSync: DesktopDanmakuCloudSyncBinding
    fun hasPlaylistNext(): Boolean
    fun setScreenshotAndOrientationLock(locked: Boolean): AutoCloseable
    fun setViewportActive(active: Boolean)
    fun acquireViewportLease(): AutoCloseable
    fun releaseViewportForThisEntry()
    fun recoverViewport(identity: String, fullscreen: Boolean, pip: Boolean, predictiveBackGeneration: Int)
    fun readViewportBrightness(): Float
    fun setViewportBrightness(value: Float, requestSystemBrightness: Boolean)
    suspend fun captureAmbientFrame(targetWidth: Int, targetHeight: Int): ImageBitmap?
    suspend fun captureAndSaveScreenshot(videoWidth: Int, videoHeight: Int, title: String): Boolean
    fun captureAmbientSourceLease(): DesktopOriginalAmbientSourceLease?
    fun ambientViewportBoundsInWindow(): androidx.compose.ui.geometry.Rect?
    suspend fun captureAndSaveScreenshotForShare(videoWidth: Int, videoHeight: Int, title: String): DesktopOriginalSavedVideoScreenshot?
    suspend fun shareSavedScreenshot(screenshot: DesktopOriginalSavedVideoScreenshot): Boolean
    fun setCurrentVideoEnhancementEnabled(enabled: Boolean)
    fun recordDanmakuToggle(enabled: Boolean)
    suspend fun submitGradeDanmaku(aid: Long, cid: Long, progress: Long, gradeId: String, gradeScore: Int): Result<Unit>
    suspend fun submitVote(voteId: Long, optionIndexes: List<Int>): Result<Unit>
    fun commandVotePlatform(): DesktopWindowsCommandVotePlatform?
    /** Reuse the sole Surface's actual Canvas/Popup. Texture alpha/clip/Haze and
     * navigation transforms require an explicit capability, not a fabricated Android View.
     */
    @Composable fun NativeViewport(
        modifier: Modifier, layout: VideoViewportLayout, resizeMode: Int,
        revealAlpha: Float, revealScale: Float, freeScale: Float, panX: Float, panY: Float,
        flipHorizontal: Boolean, flipVertical: Boolean, visible: Boolean, keepAwake: Boolean,
    )
    /** Ordinary + advanced painting must remain the existing native Overlay actor.
     * Command buttons are the original Compose foreground in the sole shaped carrier.
     */
    @Composable fun NativeDanmakuSurface(viewport: DanmakuViewport, modifier: Modifier)
}

internal val LocalDesktopOriginalVideoSectionPlatform = staticCompositionLocalOf<DesktopOriginalVideoSectionPlatform> {
    error("The original player Section needs its same-native-source Root platform binding")
}
