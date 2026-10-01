package com.bilipai.desktop.ui

import com.android.purebilibili.feature.video.viewmodel.VideoCoinBalanceLoader
import com.android.purebilibili.feature.video.viewmodel.VideoEngagementActions
import com.bilipai.desktop.plugins.DesktopPluginContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope

/** One detail entry/current subject plus Root's actual account epoch and caller scope. */
internal class DesktopOriginalVideoEngagementEnvironment(
    val context: DesktopPluginContext,
    val scope: CoroutineScope,
    val actions: VideoEngagementActions,
    val coinBalanceLoader: VideoCoinBalanceLoader,
    private val isCurrent: () -> Boolean,
    private val commitIfCurrent: ((() -> Unit) -> Boolean),
) {
    fun isOwned(): Boolean = isCurrent()
    fun assertOwned() {
        if (!isOwned()) throw CancellationException("Original video engagement owner retired")
    }
    fun commit(block: () -> Unit) {
        assertOwned()
        if (!commitIfCurrent(block)) throw CancellationException("Original video engagement admission retired")
    }
}

/** Root must supply its real analytics capability; no Firebase client is constructed here. */
internal interface DesktopOriginalVideoInteractionAnalytics {
    fun logLike(videoId: String, isLiked: Boolean)
    fun logDislike(videoId: String, isDisliked: Boolean)
    fun logFavorite(videoId: String, isFavorited: Boolean)
    fun logFollow(userId: String, isFollowed: Boolean)
    fun logCoin(videoId: String, coinCount: Int)
}

internal object DesktopOriginalVideoInteractionLog {
    fun d(tag: String, message: String) = java.util.logging.Logger.getLogger(tag).fine(message)
    fun e(tag: String, message: String, failure: Throwable? = null) {
        java.util.logging.Logger.getLogger(tag).log(java.util.logging.Level.FINE, message, failure)
    }
}
