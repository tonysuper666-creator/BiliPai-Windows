package com.bilipai.desktop.settings

import com.android.purebilibili.feature.settings.SettingsSearchTarget

/*
 * The item half of a settings search focus (upstream v0.3.9: one claim per request, a timeout for a request nobody claims,
 * a held then faded highlight). The original SettingsSearchFocusController (v0.2.5, compiled as is) only carries
 * (target, focusId); this registry carries which row was opened and the query that found it, next to that request.
 *
 * The group anchors of the Windows pages (desktopSettingsSearchFocusAnchor) are the consumers: when the anchor of the
 * request's own group scrolls into view, it claims the pending item request and highlights the group. The registry
 * is a plain object with an injectable clock; the anchor reads it once per request, so it needs no Compose state.
 */

/** The values of upstream v0.4.6 SettingsSearchHighlightTiming (SettingsSearchFocusPolicy.kt). */
internal object DesktopSettingsItemHighlightTiming {
    const val HOLD_MILLIS = 1_600L
    const val FADE_MILLIS = 280
    const val UNCLAIMED_TIMEOUT_MILLIS = 5_000L
}

internal data class DesktopSettingsItemFocusRequest(
    val settingId: String,
    val target: SettingsSearchTarget,
    val focusId: String,
    val query: String,
    val token: Long,
)

internal class DesktopSettingsItemFocusRegistry(private val nanoTime: () -> Long = System::nanoTime) {
    private val lock = Any()
    private var pending: DesktopSettingsItemFocusRequest? = null
    private var submittedAt = 0L
    private var claimed = false
    private var lastToken = 0L

    /** The request that nobody has finished yet, claimed or not. */
    val current: DesktopSettingsItemFocusRequest? get() = synchronized(lock) { pending }

    /**
     * Records that [item] was opened from a search for [query]. A row without a group anchor leaves nothing pending (there is no
     * anchor to claim it), and a new request replaces the old one, claimed or not.
     */
    fun submit(item: DesktopSettingsItem, query: String): DesktopSettingsItemFocusRequest? = synchronized(lock) {
        claimed = false
        val focus = item.focus
        if (focus == null) {
            pending = null
            return@synchronized null
        }
        val request = DesktopSettingsItemFocusRequest(item.settingId, item.target, focus, query.trim(), ++lastToken)
        pending = request
        submittedAt = nanoTime()
        request
    }

    /**
     * The anchor of ([target], [focusId]) takes the pending request: only the matching group, only once, and only while the
     * request is younger than the unclaimed timeout. A request that expired is dropped.
     */
    fun claim(target: SettingsSearchTarget, focusId: String): DesktopSettingsItemFocusRequest? = synchronized(lock) {
        val request = pending ?: return@synchronized null
        if (claimed || request.target != target || request.focusId != focusId) return@synchronized null
        if (nanoTime() - submittedAt > DesktopSettingsItemHighlightTiming.UNCLAIMED_TIMEOUT_MILLIS * NANOS_PER_MILLI) {
            pending = null
            return@synchronized null
        }
        claimed = true
        request
    }

    /** The highlight of [request] is over; a newer request is left alone. */
    fun finish(request: DesktopSettingsItemFocusRequest) = synchronized(lock) {
        if (pending?.token == request.token) {
            pending = null
            claimed = false
        }
    }

    fun clear() = synchronized(lock) {
        pending = null
        claimed = false
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/** The registry of the running window. Settings search is one window-wide flow, like SettingsSearchFocusController. */
internal val desktopSettingsItemFocus = DesktopSettingsItemFocusRegistry()
