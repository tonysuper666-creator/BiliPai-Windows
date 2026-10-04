package com.bilipai.desktop.ui

import com.android.purebilibili.danmaku.parser.bas.BasTarget
import kotlinx.coroutines.CancellationException
import java.math.BigDecimal
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.swing.SwingUtilities

/** Windows platform mapping of fixed v0.2.9 BasDanmakuOverlay.activate/seconds:
 * the original target preference, part and fractional timestamp are preserved.
 * Encode a whole path segment, just as Android Uri.Builder.appendPath does. */
internal fun desktopBasExternalTarget(target: BasTarget): URI? {
    fun seconds(timeMs: Long) = BigDecimal.valueOf(timeMs, 3).stripTrailingZeros().toPlainString()
    fun segment(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
    return when (target) {
        is BasTarget.Seek -> null
        is BasTarget.Video -> {
            if (target.timeMs < 0L || target.page < 1 || target.aid?.let { it < 0L } == true) return null
            val video = target.bvid ?: target.aid?.let { "av$it" } ?: return null
            if (video.isBlank() || video.length > 1_024 || video.any(Char::isISOControl)) return null
            URI("https://www.bilibili.com/video/${segment(video)}?p=${target.page}&t=${seconds(target.timeMs)}")
        }
        is BasTarget.Bangumi -> {
            if (target.timeMs < 0L || target.episodeId?.let { it < 0L } == true ||
                target.seasonId?.let { it < 0L } == true) return null
            val episode = target.episodeId?.let { "ep$it" } ?: target.seasonId?.let { "ss$it" } ?: return null
            URI("https://www.bilibili.com/bangumi/play/$episode?t=${seconds(target.timeMs)}")
        }
    }
}

/** One actual accepted publication, with existing Root/entry/account admission.
 * Called after the overlay releases its monitor. No browser I/O runs in admit.
 * A true seek result means dispatch; completion remains the native seek readback. */
internal class DesktopWindowsBasActions(
    private val expected: DesktopOriginalVideoAcceptedPublication,
    private val currentSource: () -> DesktopOriginalVideoAcceptedPublication?,
    private val presentationCurrent: () -> Boolean,
    private val admit: ((() -> Unit) -> Boolean),
    private val originalSeek: (Long) -> Unit,
    private val originalPause: () -> Unit,
    private val openExternal: (URI) -> Boolean,
) {
    private fun current() = presentationCurrent() && currentSource() === expected

    fun dispatch(target: BasTarget): Boolean {
        check(SwingUtilities.isEventDispatchThread()) { "BAS actions require the desktop UI dispatcher" }
        if (!current()) return false
        val external = if (target is BasTarget.Seek) {
            if (target.timeMs < 0L) return false
            null
        } else desktopBasExternalTarget(target) ?: return false
        var dispatched = false
        val admitted = try {
            admit {
                if (current()) {
                    if (target is BasTarget.Seek) originalSeek(target.timeMs) else originalPause()
                    dispatched = true
                }
            }
        } catch (_: CancellationException) { false }
        if (!admitted || !dispatched) return false
        if (external == null) return true
        return current() && openExternal(external)
    }
}
