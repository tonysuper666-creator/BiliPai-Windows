package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.space.*
import com.android.purebilibili.feature.video.player.PlaylistItem
import com.bilipai.desktop.data.*
import kotlin.test.*

class DesktopCompleteSpaceTest {
    private fun overview(default: String = "dynamic", audio: Int = 1): DesktopSpaceOverview {
        val data = SpaceAggregateData(card = SpaceAggregateCard(mid = "22", name = "UP", face = "avatar"),
            defaultTab = default, audios = SpaceAggregateAudioSection(audio, emptyList()), tab2 = listOf(
                SpaceAggregateTab("投稿", "contribute", listOf(SpaceAggregateTabItem("视频", "video"),
                    SpaceAggregateTabItem("歌曲", "audio"), SpaceAggregateTabItem("系列甲", "series", seriesId = 91),
                    SpaceAggregateTabItem("系列乙", "series", seriesId = 92))), SpaceAggregateTab("课程", "cheese")))
        return assertNotNull(DesktopSpaceMetadata(data, resolveSpaceMainTabs(data.tab2), resolveSpaceContributionTabs(data.tab2)).toOverview())
    }

    @Test fun refreshKeepsUserSelectionInsteadOfReapplyingServerDefault() {
        val state = DesktopCompleteSpaceState()
        state.acceptOverview(overview())
        assertEquals(SpaceMainTab.DYNAMIC, state.selectedMain)
        val audio = state.contributionTabs().single { it.subTab == SpaceSubTab.AUDIO }
        state.selectSecondary(resolveSpaceSecondarySwitchItems(state.contributionTabs()).single { it.id == audio.id })
        state.acceptOverview(overview("home"))
        assertEquals(SpaceMainTab.CONTRIBUTION, state.selectedMain)
        assertEquals(audio.id, state.selectedContributionId)
        assertEquals(SpaceSubTab.AUDIO, state.selectedSubTab)
        state.acceptOverview(overview("home", audio = 0))
        assertEquals(SpaceSubTab.VIDEO, state.selectedSubTab)
    }

    @Test fun homeSectionsSelectActualLibraryTargetsAndRetainSelectedFavorite() {
        val state = DesktopCompleteSpaceState().apply { acceptOverview(overview()) }
        state.leaf.folder = FavFolder(id = 8, title = "测试收藏夹")
        state.showHomeSection(DesktopSpaceHomeSection.FAVORITES)
        assertEquals(SpaceMainTab.FAVORITE, state.selectedMain)
        assertEquals(8, state.leaf.folder?.id)
        state.showHomeSection(DesktopSpaceHomeSection.COINS)
        assertEquals(DesktopSpaceContributionSection.COINS, state.interaction)
        state.showHomeSection(DesktopSpaceHomeSection.LIKED)
        assertEquals(DesktopSpaceContributionSection.LIKED, state.interaction)
        state.showHomeSection(DesktopSpaceHomeSection.FOLLOW_BANGUMI)
        assertEquals(SpaceMainTab.BANGUMI, state.selectedMain)
        assertNull(state.interaction)
        state.showHomeSection(DesktopSpaceHomeSection.AUDIO)
        assertEquals(SpaceSubTab.AUDIO, state.selectedSubTab)
    }

    @Test fun distinctCollectionIdsAndAccountEpochsCannotShareNavigationState() {
        val memory = DesktopBrowseMemory()
        val first = memory.screen(listOf("complete-up-space", 1L, 2L, 22L)) { DesktopCompleteSpaceState() }
        first.acceptOverview(overview())
        val series = first.contributionTabs().filter { it.subTab == SpaceSubTab.SERIES }
        assertEquals(listOf(91L, 92L), series.map { it.seriesId })
        val switches = resolveSpaceSecondarySwitchItems(first.contributionTabs())
        first.selectSecondary(switches.single { it.id == series[1].id })
        assertEquals(92L, resolveSelectedContributionTab(first.contributionTabs(), first.selectedContributionId, first.selectedSubTab).seriesId)
        assertSame(first, memory.screen(listOf("complete-up-space", 1L, 2L, 22L)) { DesktopCompleteSpaceState() })
        assertNotSame(first, memory.screen(listOf("complete-up-space", 2L, 2L, 22L)) { DesktopCompleteSpaceState() })
        assertNotSame(first, memory.screen(listOf("complete-up-space", 1L, 3L, 22L)) { DesktopCompleteSpaceState() })
    }

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
