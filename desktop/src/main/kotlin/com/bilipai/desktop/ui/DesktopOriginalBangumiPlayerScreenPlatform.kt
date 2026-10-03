package com.bilipai.desktop.ui
import com.android.purebilibili.feature.video.screen.shouldUseLargeScreenVideoLayout
import com.android.purebilibili.feature.video.screen.resolveLargeScreenVideoMetrics

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import com.android.purebilibili.feature.bangumi.BangumiPlayerViewModel
import com.android.purebilibili.feature.video.viewmodel.VideoCommentViewModel

/** Required same existing Root share boundary; no exporter, browser, Store or
 * platform renderer is created by the complete original PGC overlay body. */
internal val LocalDesktopOriginalBangumiPlayerShare = staticCompositionLocalOf<DesktopOriginalBangumiPlayerShare> {
    error("Original PGC Player requires actual Root share owner")
}

internal class DesktopOriginalBangumiPlayerShare(
    private val bindings: DesktopTextShareBindings,
    private val scope: CoroutineScope,
    private val isOwned: () -> Boolean,
    private val feedback: (String) -> Unit,
) {
    fun shareBangumi(context: DesktopOriginalPlayerSettingsContext, title: String,
        seasonId: Long, epId: Long?) {
        context.requireCurrent()
        val target = epId?.let { "ep$it" } ?: "ss$seasonId"
        val text = "$title\nhttps://www.bilibili.com/bangumi/play/$target"
        requestDesktopTextShare(bindings, scope, title, text, isOwned, feedback)
    }
    fun shareText(title: String, text: String) =
        requestDesktopTextShare(bindings, scope, title, text, isOwned, feedback)
}

/** Required retained Root views. Screen disposal releases view/window handles;
 * the same PGC presenter/native owner survives covered leaves and Mini mode.
 * Construction creates neither playback, credentials, queues nor a session. */
internal interface DesktopOriginalBangumiPlayerScreenPlatform {
    val section: DesktopOriginalVideoSectionPlatform
    val player: DesktopOriginalMpvSectionControl
    val viewModel: BangumiPlayerViewModel
    val comments: VideoCommentViewModel
    val window: DesktopOriginalVideoHolderWindowPort
    val fullscreen: StateFlow<Boolean>
    val share: DesktopOriginalBangumiPlayerShare
    fun owns(): Boolean
    fun ensureMiniCallbacks(viewModel: BangumiPlayerViewModel, isCourse: Boolean)
    fun acquireDanmakuPlayer(player: DesktopOriginalMpvSectionControl): AutoCloseable
    fun acquirePlaybackHandoff(seasonId: Long, epId: Long, position: () -> Long): AutoCloseable
    fun acquirePresentation(fullscreen: Boolean): AutoCloseable
    fun requestFullscreen(fullscreen: Boolean)
    fun showFeedback(message: String)
    fun elapsedRealtimeMillis(): Long
    fun isActualDrmFailure(error: DesktopOriginalNativePlaybackError): Boolean
}

internal val LocalDesktopOriginalBangumiPlayerScreenPlatform =
    staticCompositionLocalOf<DesktopOriginalBangumiPlayerScreenPlatform> {
        error("Full original PGC Screen requires its retained same native/Window/domain Root binding")
    }

/** The mobile PGC page assumes a tall portrait display. Use the already owned
 * original large-window geometry for Windows while keeping its collapse state. */
internal fun desktopOriginalBangumiInlinePlayerHeightDp(widthDp: Int, heightDp: Int): Float {
    val width = widthDp.toFloat().coerceAtLeast(0f)
    val height = heightDp.toFloat().coerceAtLeast(0f)
    val portraitHeight = width * 2f / 3f
    if (!shouldUseLargeScreenVideoLayout(width, height, horizontalAdaptationEnabled = true)) return portraitHeight
    return minOf(portraitHeight, resolveLargeScreenVideoMetrics(width, height, isVerticalVideo = false).playerHeightDp)
}
