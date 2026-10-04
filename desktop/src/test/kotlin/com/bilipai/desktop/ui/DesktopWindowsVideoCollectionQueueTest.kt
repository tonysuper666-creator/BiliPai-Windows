package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.player.*
import com.bilipai.desktop.data.VideoDetails
import kotlin.test.*

class DesktopWindowsVideoCollectionQueueTest {
    private val first = PlaylistItem("BV1", 11L, "第一集", "", "UP")
    private val second = PlaylistItem("BV2", 22L, "第二集", "", "UP")
    private val state = PlaylistUiState(playlist = listOf(first, second), currentIndex = 0,
        isExternalPlaylist = true, externalPlaylistSource = ExternalPlaylistSource.FAVORITE,
        shuffleEnabled = true)

    @Test fun sameOriginalListAndExactRowMayBeSelectedWithoutChangingItsQueue() {
        val session = Any(); val source = Any()
        val captured = DesktopWindowsVideoQueueSnapshot(session, source, state)
        assertTrue(desktopWindowsVideoQueueSelectionIsCurrent(captured, session, source, state, 1, second))
        assertSame(state.playlist, captured.state.playlist)
        assertTrue(captured.state.shuffleEnabled)
        assertEquals(ExternalPlaylistSource.FAVORITE, captured.state.externalPlaylistSource)
    }

    @Test fun equalValueReplacementQueueIsRejected() {
        val session = Any(); val source = Any()
        val captured = DesktopWindowsVideoQueueSnapshot(session, source, state)
        val replacement = state.copy(playlist = state.playlist.toMutableList())
        assertEquals(state.playlist, replacement.playlist)
        assertFalse(desktopWindowsVideoQueueSelectionIsCurrent(captured, session, source, replacement, 1, second))
    }

    @Test fun retiredSessionOrSameBvCidNewSourceCannotSelectFromOldWindow() {
        val session = Any(); val source = Any()
        val captured = DesktopWindowsVideoQueueSnapshot(session, source, state)
        assertFalse(desktopWindowsVideoQueueSelectionIsCurrent(captured, Any(), source, state, 1, second))
        assertFalse(desktopWindowsVideoQueueSelectionIsCurrent(captured, session, Any(), state, 1, second))
    }

    @Test fun copiedRowInvalidIndexOrChangedCursorCannotRetargetSelection() {
        val session = Any(); val source = Any()
        val captured = DesktopWindowsVideoQueueSnapshot(session, source, state)
        assertFalse(desktopWindowsVideoQueueSelectionIsCurrent(captured, session, source, state, 1, second.copy()))
        assertFalse(desktopWindowsVideoQueueSelectionIsCurrent(captured, session, source, state, 2, second))
        assertFalse(desktopWindowsVideoQueueSelectionIsCurrent(captured, session, source, state.copy(currentIndex = 1), 1, second))
    }

    private val multiPart = UgcEpisode(id = 1L, bvid = "BV1", cid = 11L, title = "第一集",
        pages = listOf(Page(cid = 11L, page = 1, part = "P1"), Page(cid = 12L, page = 2, part = "P2")))
    private val otherEpisode = UgcEpisode(id = 2L, bvid = "BV2", cid = 22L, title = "第二集")
    private fun details() = VideoDetails("BV1", 1L, "第一集", "", "", "UP", 0L, 0L,
        emptyList(), raw = ViewInfo(bvid = "BV1", ugc_season = UgcSeason(id = 7L,
            sections = listOf(UgcSection(id = 1L, episodes = listOf(multiPart)),
                UgcSection(id = 2L, episodes = listOf(otherEpisode))))))

    @Test fun originalPartChipKeepsItsExactCidAndIndexInTheSameSeasonPosition() {
        val result = assertNotNull(desktopWindowsUgcEpisodeSelection(details(), 11L, multiPart.copy(cid = 12L)))
        assertEquals(listOf("BV1", "BV2"), result.queue.map { it.bvid })
        assertEquals(12L, result.selected.preferredCid)
        assertEquals(1, result.selected.pageIndex)
        assertSame(result.selected, result.queue[0])
        assertEquals(22L, result.queue[1].preferredCid)
    }

    @Test fun otherSectionSelectionUsesExistingSeasonQueue() {
        val result = assertNotNull(desktopWindowsUgcEpisodeSelection(details(), 11L, otherEpisode))
        assertSame(result.selected, result.queue[1])
        assertEquals(22L, result.selected.preferredCid)
        assertEquals(11L, result.queue[0].preferredCid)
    }

    @Test fun absentSeasonOrUnknownEpisodeCannotInventQueueRows() {
        assertNull(desktopWindowsUgcEpisodeSelection(details().copy(raw = null), 11L, otherEpisode))
        assertNull(desktopWindowsUgcEpisodeSelection(details(), 11L, otherEpisode.copy(bvid = "BV999")))
    }
}
