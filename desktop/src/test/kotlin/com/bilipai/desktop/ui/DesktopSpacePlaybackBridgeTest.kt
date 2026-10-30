package com.bilipai.desktop.ui

import com.android.purebilibili.feature.space.SpaceExternalPlaylist
import com.android.purebilibili.feature.video.player.PlaylistItem
import com.bilipai.desktop.data.VideoCard
import kotlin.test.*

class DesktopSpacePlaybackBridgeTest {
    @Test fun originalQueueOrderSelectedIndexAndExactPartResumeReachRoot() {
        val items = listOf(PlaylistItem("BVone", title = "一", cover = "1", owner = "UP", duration = 60),
            PlaylistItem("BVtwo", cid = 220, title = "二", cover = "2", owner = "UP", duration = 80))
        val history = listOf(VideoCard("BVone", "旧", "", "", 0, 60, 15, 110, 1, authorMid = 22),
            VideoCard("BVtwo", "旧", "", "", 0, 80, 30, 221, 2, authorMid = 22),
            VideoCard("BVtwo", "另一 UP", "", "", 0, 80, 40, 220, 3, authorMid = 99))
        val (rows, selected) = assertNotNull(desktopSpacePlaybackQueue(SpaceExternalPlaylist(items, 1), 22, history))
        assertEquals(1, selected)
        assertEquals(listOf("BVone", "BVtwo"), rows.map { it.bvid })
        assertEquals(110, rows[0].preferredCid); assertEquals(15, rows[0].progressSeconds); assertEquals(1, rows[0].pageIndex)
        assertEquals(220, rows[1].preferredCid); assertNull(rows[1].progressSeconds); assertEquals(0, rows[1].pageIndex)
        assertEquals(listOf(22L, 22L), rows.map { it.authorMid })
        assertNull(desktopSpacePlaybackQueue(SpaceExternalPlaylist(items, 2), 22, history))
    }
}
