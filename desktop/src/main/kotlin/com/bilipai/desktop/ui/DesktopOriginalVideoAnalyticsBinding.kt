package com.bilipai.desktop.ui

import com.bilipai.desktop.diagnostics.DesktopDiagnostics
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Same Root diagnostics/settings authority. Firebase is an unavailable Windows capability.
 * Original event names/coin_count are retained; original sensitive ID parameters stay omitted. */
internal class DesktopOriginalVideoAnalyticsBinding(
    private val globalStore: DesktopPluginStore,
    private val diagnostics: DesktopDiagnostics?,
    private val isCurrent: () -> Boolean,
) : DesktopOriginalVideoInteractionAnalytics {
    val firebaseTransportAvailable: Boolean get() = false
    val localDiagnosticConsumerAvailable: Boolean get() = diagnostics != null
    private fun record(event: String, coinCount: Int? = null) {
        try {
            if (!isCurrent()) return
            if (globalStore.preferences("settings")["analytics_enabled"]?.jsonPrimitive?.booleanOrNull == false) return
            diagnostics?.record("I", "VideoAnalytics", "event=$event" + (coinCount?.let { ", coin_count=$it" } ?: ""))
        } catch (_: Exception) { /* Original analytics failures must not fail a successful action. */ }
    }
    override fun logLike(videoId: String, isLiked: Boolean) = record(if (isLiked) "video_like" else "video_unlike")
    override fun logDislike(videoId: String, isDisliked: Boolean) = record(if (isDisliked) "video_dislike" else "video_undislike")
    override fun logFavorite(videoId: String, isFavorited: Boolean) = record(if (isFavorited) "video_favorite" else "video_unfavorite")
    override fun logFollow(userId: String, isFollowed: Boolean) = record(if (isFollowed) "user_follow" else "user_unfollow")
    override fun logCoin(videoId: String, coinCount: Int) = record("video_coin", coinCount)
}
