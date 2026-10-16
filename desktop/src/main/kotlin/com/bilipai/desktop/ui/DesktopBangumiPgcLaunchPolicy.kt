package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.BangumiDetail
import com.android.purebilibili.navigation3.BiliPaiNavKey

/** Pure decisions of the retained original PGC Entry/Leaf. Nothing here reads a
 * Binding, player, VM, credential or coroutine, and nothing is an authority: the
 * production Entry/Leaf call these exact functions so every table is testable
 * without the Root window/Store/native fixture. */

/** Which read receipt, if any, may authorize the display-only season/episode metadata. */
internal enum class DesktopBangumiReadOnlyOrigin {
    /** A genuine initial primary-detail HTTP read witnessed by THIS launch. */
    WITNESSED,
    /** Post-begin only: the already displayed DTO of the same decoded season supplies its receipt. */
    BORROWED_DISPLAYED,
    /** No proof of origin. Nothing is presented; the load itself is never aborted by it. */
    NONE,
}

/** The metadata is presentation only, so a missing origin means "present nothing", never
 * "cancel playback". A pre-begin read has no Binding yet and may only be witnessed. */
internal fun desktopBangumiReadOnlyOrigin(postBegin: Boolean, hasWitness: Boolean,
    displayedSameDecodedSeason: Boolean): DesktopBangumiReadOnlyOrigin = when {
    hasWitness -> DesktopBangumiReadOnlyOrigin.WITNESSED
    postBegin && displayedSameDecodedSeason -> DesktopBangumiReadOnlyOrigin.BORROWED_DISPLAYED
    else -> DesktopBangumiReadOnlyOrigin.NONE
}

/** A presentation admission that fails after the origin was decided. Only the receipt THIS load
 * witnessed retires the load with it. A borrowed receipt belongs to the previously displayed season and
 * may simply have aged (login stamp, epoch): while the load itself is still current the display-only
 * metadata is dropped and playback continues; a load that is no longer current is cancelled as before. */
internal fun desktopBangumiReadOnlyAdmissionRetiresLoad(origin: DesktopBangumiReadOnlyOrigin,
    loadCurrent: Boolean): Boolean = when (origin) {
    DesktopBangumiReadOnlyOrigin.WITNESSED -> true
    DesktopBangumiReadOnlyOrigin.BORROWED_DISPLAYED -> !loadCurrent
    DesktopBangumiReadOnlyOrigin.NONE -> false
}

/** The original VM publishes the decoded DTO but keeps `detail.copy(userStatus = ...)` in
 * Success, and switch/reload/recovery hand that copy back. The copy shares the decoded
 * episode list, so list identity (never data-class equality, which the copy breaks) names
 * the same decoded season. A separately decoded season always owns a fresh list. */
internal fun desktopBangumiSameDecodedSeason(displayed: BangumiDetail?, requested: BangumiDetail): Boolean {
    if (displayed == null) return false
    if (displayed === requested) return true
    val episodes = requested.episodes ?: return false
    return episodes.isNotEmpty() && displayed.episodes === episodes && displayed.seasonId == requested.seasonId
}

/** An Entry serves the very navigation key instance it was opened for (a covered return keeps
 * that instance). An equal but newly pushed key (Back, then the same episode again) is a new
 * navigation entry: the retained Entry's initial-read witness and login intent are bound to the
 * popped instance, so it is replaced like any other Entry and the fresh VM resumes from progress. */
internal fun desktopBangumiPgcEntryReusable(existing: BiliPaiNavKey.BangumiPlayer,
    requested: BiliPaiNavKey.BangumiPlayer): Boolean = existing === requested

/** Route for a bangumi card. Upstream opens BangumiDetail for a season-only favorite or dynamic
 * card and the player only for a concrete episode (or a course); history keeps the player.
 * The Shell's card helper is the only production caller and always passes seasonOnlyOpensDetail = true.
 * The other parameters pin the key construction shared with `showSeason`, which still builds its
 * history/course/community keys inline, so they are a mapper contract and not Shell wiring. */
internal fun desktopBangumiOpenKey(seasonId: Long, epId: Long, isCourse: Boolean = false,
    resumePositionMs: Long = 0L, seasonOnlyOpensDetail: Boolean = false): BiliPaiNavKey? {
    if (seasonId <= 0L && epId <= 0L) return null
    if (seasonOnlyOpensDetail && epId <= 0L && !isCourse) return BiliPaiNavKey.BangumiDetail(seasonId = seasonId, epId = epId)
    return BiliPaiNavKey.BangumiPlayer(seasonId, epId, resumePositionMs.coerceAtLeast(0L), isCourse)
}
