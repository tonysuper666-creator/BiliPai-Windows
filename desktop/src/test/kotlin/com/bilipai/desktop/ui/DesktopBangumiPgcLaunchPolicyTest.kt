package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.BangumiDetail
import com.android.purebilibili.data.model.response.BangumiEpisode
import com.android.purebilibili.data.model.response.UserStatus
import com.android.purebilibili.navigation3.BiliPaiNavKey
import kotlin.test.*

/** Pure decision tables the retained PGC Entry/Leaf call. The Entry/Leaf wiring itself
 * (RootOwner needs the shell owner, root window and factory) has no offline harness here. */
class DesktopBangumiPgcLaunchPolicyTest {
    private fun episode(id: Long, aid: Long, cid: Long) = BangumiEpisode(id = id, aid = aid, bvid = "BV$id", cid = cid)
    private fun season() = BangumiDetail(seasonId = 19, title = "season",
        episodes = listOf(episode(41, 4101, 104), episode(42, 4201, 105)))
    /** The original VM keeps `detail.copy(userStatus = ...)` in Success after every load. */
    private fun successCopy(detail: BangumiDetail) = detail.copy(userStatus = UserStatus(follow = 1, followStatus = 2))

    @Test fun `read-only origin decides every witness phase and displayed-season combination`() {
        val witnessed = DesktopBangumiReadOnlyOrigin.WITNESSED
        val borrowed = DesktopBangumiReadOnlyOrigin.BORROWED_DISPLAYED
        val none = DesktopBangumiReadOnlyOrigin.NONE
        // (postBegin, hasWitness, displayedSameDecodedSeason) -> origin
        val table = listOf(
            Triple(false, true, false) to witnessed, Triple(false, true, true) to witnessed,
            // Pre-begin has no Binding yet: Mini/SMTC next or retry after a switch must load, never borrow.
            Triple(false, false, false) to none, Triple(false, false, true) to none,
            Triple(true, true, false) to witnessed, Triple(true, true, true) to witnessed,
            Triple(true, false, true) to borrowed, Triple(true, false, false) to none)
        for ((inputs, expected) in table) {
            val (postBegin, hasWitness, displayed) = inputs
            assertEquals(expected, desktopBangumiReadOnlyOrigin(postBegin, hasWitness, displayed), "inputs=$inputs")
        }
    }

    @Test fun `a failed presentation admission retires the load only for the receipt this load witnessed`() {
        val witnessed = DesktopBangumiReadOnlyOrigin.WITNESSED
        val borrowed = DesktopBangumiReadOnlyOrigin.BORROWED_DISPLAYED
        // (origin, loadStillCurrent) -> the load is cancelled with the presentation
        val table = listOf(
            (witnessed to true) to true, (witnessed to false) to true,
            // An aged borrowed receipt must not stop an in-player switch whose own load is still current.
            (borrowed to true) to false, (borrowed to false) to true,
            // No origin never reaches the admission, so it can never retire a load.
            (DesktopBangumiReadOnlyOrigin.NONE to true) to false, (DesktopBangumiReadOnlyOrigin.NONE to false) to false)
        for ((inputs, expected) in table) {
            assertEquals(expected, desktopBangumiReadOnlyAdmissionRetiresLoad(inputs.first, inputs.second), "inputs=$inputs")
        }
    }

    @Test fun `success copy of a decoded season is the same decoded season but data equality is not`() {
        val decoded = season()
        val corrected = successCopy(decoded)
        assertNotEquals(decoded, corrected)
        assertTrue(desktopBangumiSameDecodedSeason(decoded, decoded))
        // switchEpisode/reloadCurrentEpisode hand the Success copy back to the load.
        assertTrue(desktopBangumiSameDecodedSeason(decoded, corrected))
        assertTrue(desktopBangumiSameDecodedSeason(corrected, successCopy(corrected)))
        assertSame(decoded.episodes, corrected.episodes)
    }

    @Test fun `a separately decoded or different season is never borrowed`() {
        val displayed = season()
        val redecoded = displayed.copy(episodes = displayed.episodes!!.toList())
        assertEquals(displayed, redecoded)
        assertNotSame(displayed.episodes, redecoded.episodes)
        assertFalse(desktopBangumiSameDecodedSeason(displayed, redecoded))
        assertFalse(desktopBangumiSameDecodedSeason(displayed, displayed.copy(seasonId = 20)))
        assertFalse(desktopBangumiSameDecodedSeason(null, displayed))
        val withoutEpisodes = BangumiDetail(seasonId = 19)
        assertFalse(desktopBangumiSameDecodedSeason(withoutEpisodes, withoutEpisodes.copy(title = "copy")))
        val empty = BangumiDetail(seasonId = 19, episodes = emptyList())
        assertFalse(desktopBangumiSameDecodedSeason(empty, empty.copy(title = "copy")))
    }

    @Test fun `entry is reused only for the exact key instance`() {
        val key = BiliPaiNavKey.BangumiPlayer(19, 41, 0L, false, 4101)
        val equalButNew = key.copy()
        assertEquals(key, equalButNew)
        assertTrue(desktopBangumiPgcEntryReusable(key, key))
        // Back, then the same episode again: equal data class, new navigation entry.
        assertFalse(desktopBangumiPgcEntryReusable(key, equalButNew))
        assertFalse(desktopBangumiPgcEntryReusable(key, key.copy(epId = 42)))
        assertFalse(desktopBangumiPgcEntryReusable(key, key.copy(seasonId = 20)))
        assertFalse(desktopBangumiPgcEntryReusable(key, key.copy(isCourse = true)))
    }

    @Test fun `open key sends season-only favorites and dynamics to detail and concrete targets to the player`() {
        // Favorites (season only) and a dynamic card without an episode.
        assertEquals(BiliPaiNavKey.BangumiDetail(seasonId = 19, epId = 0),
            desktopBangumiOpenKey(19, 0, seasonOnlyOpensDetail = true))
        // A dynamic card with an episode plays that episode; an episode link may have no season id.
        assertEquals(BiliPaiNavKey.BangumiPlayer(19, 41, 0L, false), desktopBangumiOpenKey(19, 41, seasonOnlyOpensDetail = true))
        assertEquals(BiliPaiNavKey.BangumiPlayer(0, 41, 0L, false), desktopBangumiOpenKey(0, 41, seasonOnlyOpensDetail = true))
        // Neither id: nothing to open.
        assertNull(desktopBangumiOpenKey(0, 0, seasonOnlyOpensDetail = true))
        assertNull(desktopBangumiOpenKey(-1, 0))
        // Courses have no detail page: they keep the player.
        assertEquals(BiliPaiNavKey.BangumiPlayer(19, 0, 0L, true),
            desktopBangumiOpenKey(19, 0, isCourse = true, seasonOnlyOpensDetail = true))
        // History keeps the player with its progress; a negative progress is coerced.
        assertEquals(BiliPaiNavKey.BangumiPlayer(19, 0, 5_000L, false), desktopBangumiOpenKey(19, 0, resumePositionMs = 5_000L))
        assertEquals(BiliPaiNavKey.BangumiPlayer(19, 41, 0L, false), desktopBangumiOpenKey(19, 41, resumePositionMs = -1L))
    }

    @Test fun `dynamic card guard matches upstream for every season and episode id combination`() {
        // Upstream AppNavigation (v0.4.1): seasonId > 0 || epId > 0 opens something; epId > 0 plays, else detail.
        val table: List<Pair<Pair<Long, Long>, BiliPaiNavKey?>> = listOf(
            (19L to 41L) to BiliPaiNavKey.BangumiPlayer(19, 41, 0L, false),
            (0L to 41L) to BiliPaiNavKey.BangumiPlayer(0, 41, 0L, false),
            (19L to 0L) to BiliPaiNavKey.BangumiDetail(seasonId = 19, epId = 0),
            (0L to 0L) to null,
            (-5L to 0L) to null)
        for ((ids, expected) in table) {
            assertEquals(expected, desktopBangumiOpenKey(ids.first, ids.second, seasonOnlyOpensDetail = true), "ids=$ids")
        }
    }

    @Test fun `open key without the detail switch is the legacy player key for every caller that keeps the player`() {
        // Mapper contract only: the Shell's card helper always passes the switch on, and showSeason (history,
        // courses, community links) still builds the same BangumiPlayer key inline, unconditionally.
        assertEquals(BiliPaiNavKey.BangumiPlayer(19, 0, 0L, false), desktopBangumiOpenKey(19, 0))
        assertEquals(BiliPaiNavKey.BangumiPlayer(19, 41, 0L, false), desktopBangumiOpenKey(19, 41))
        assertEquals(BiliPaiNavKey.BangumiPlayer(19, 41, 7_000L, true), desktopBangumiOpenKey(19, 41, true, 7_000L))
        // A course never opens the detail page, even with an episode and the switch on.
        assertEquals(BiliPaiNavKey.BangumiPlayer(19, 41, 0L, true),
            desktopBangumiOpenKey(19, 41, isCourse = true, seasonOnlyOpensDetail = true))
    }
}
