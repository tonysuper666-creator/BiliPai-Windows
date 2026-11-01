package com.bilipai.desktop.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.*
import androidx.compose.ui.unit.Density
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.feature.bangumi.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class DesktopCompleteBangumiPagerCompositionTest {
    @Test fun realPagerSettlesAfterRepeatedSelectionAndTracksHalfOffset() = runBlocking {
        var selected by mutableStateOf(BangumiChannel.BANGUMI)
        lateinit var pager: PagerState
        lateinit var sceneScope: CoroutineScope
        val prepared = mutableListOf<BangumiChannel>()
        var now = 0L
        val scene = ImageComposeScene(800, 900, Density(1f)) {
            sceneScope = rememberCoroutineScope()
            MaterialTheme {
                CompositionLocalProvider(LocalAppThemeConfig provides AppThemeConfig(liquidGlassEnabled = false)) {
                    pager = rememberBangumiChannelPagerState(selected, true) { selected = it }
                    BangumiHubContent(
                        state = BangumiHubUiState(accountGeneration = 1, channel = selected, showPgcTimeline = false),
                        channelPagerState = pager,
                        onPrepareChannel = { prepared += it },
                        onBangumiClick = { _ -> error("Unexpected onBangumiClick") },
                        onEpisodeClick = { _, _ -> error("Unexpected onEpisodeClick") },
                        onPlayFollow = { _ -> error("Unexpected onPlayFollow") },
                        onRefreshHome = { error("Unexpected onRefreshHome") },
                        onLoadMoreHomeRecommendations = { error("Unexpected onLoadMoreHomeRecommendations") },
                        onLoadMoreHomeFollows = { error("Unexpected onLoadMoreHomeFollows") },
                        onRetryTimeline = { error("Unexpected onRetryTimeline") },
                        onTimelineRangeSelected = { _ -> error("Unexpected onTimelineRangeSelected") },
                        onOpenIndex = { error("Unexpected onOpenIndex") },
                        onOpenFollow = { error("Unexpected onOpenFollow") },
                        onIndexCategorySelected = { _ -> error("Unexpected onIndexCategorySelected") },
                        onIndexFilterSelected = { _, _ -> error("Unexpected onIndexFilterSelected") },
                        onToggleFiltersExpanded = { error("Unexpected onToggleFiltersExpanded") },
                        onRetryIndexConditions = { error("Unexpected onRetryIndexConditions") },
                        onRetryIndexResults = { error("Unexpected onRetryIndexResults") },
                        onLoadMoreIndexResults = { error("Unexpected onLoadMoreIndexResults") },
                        onFollowStatusSelected = { _ -> error("Unexpected onFollowStatusSelected") },
                        onRefreshFollow = { error("Unexpected onRefreshFollow") },
                        onLoadMoreFollow = { error("Unexpected onLoadMoreFollow") },
                        onToggleFollowSelection = { _ -> error("Unexpected onToggleFollowSelection") },
                        onSelectAllFollow = { error("Unexpected onSelectAllFollow") },
                        onClearFollowSelection = { error("Unexpected onClearFollowSelection") },
                        onMoveSelectedFollow = { _ -> error("Unexpected onMoveSelectedFollow") },
                        onMoveSingleFollow = { _, _ -> error("Unexpected onMoveSingleFollow") },
                        onUnfollowSingle = { _ -> error("Unexpected onUnfollowSingle") },
                        onSearchCategorySelected = { _ -> error("Unexpected onSearchCategorySelected") },
                        onLoadMoreSearch = { error("Unexpected onLoadMoreSearch") },
                        onSaveCover = { _, _ -> error("Unexpected onSaveCover") },
                    )
                }
            }
        }
        fun frames(n: Int) { repeat(n) { Snapshot.sendApplyNotifications(); now += 20_000_000; scene.render(now).close() } }
        try {
            frames(8)
            assertTrue(prepared.contains(BangumiChannel.BANGUMI))
            selected = BangumiChannel.CINEMA; frames(60)
            assertEquals(1, pager.settledPage)
            assertEquals(1f, pager.currentPage + pager.currentPageOffsetFraction, 0.001f)
            selected = BangumiChannel.BANGUMI; frames(3)
            selected = BangumiChannel.CINEMA; frames(60)
            val stopped = pager.currentPage + pager.currentPageOffsetFraction
            println("rapid stop: selected=$selected current=${pager.currentPage} settled=${pager.settledPage} offset=${pager.currentPageOffsetFraction} scrolling=${pager.isScrollInProgress}")
            assertFalse(pager.isScrollInProgress)
            frames(60)
            assertEquals(stopped, pager.currentPage + pager.currentPageOffsetFraction)
            assertEquals(1f, pager.currentPage + pager.currentPageOffsetFraction, 0.001f)
            val reached = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val move = sceneScope.launch(start = CoroutineStart.UNDISPATCHED) {
                pager.scroll {
                    scrollBy(-0.6f * (pager.layoutInfo.pageSize + pager.layoutInfo.pageSpacing))
                    reached.complete(Unit)
                    release.await()
                }
            }
            frames(8); reached.await()
            assertTrue(pager.isScrollInProgress)
            assertEquals(0.4f, pager.currentPage + pager.currentPageOffsetFraction, 0.02f)
            release.complete(Unit); frames(60); move.join()
            assertEquals(0f, pager.currentPageOffsetFraction, 0.001f)
            assertFalse(pager.isScrollInProgress)
            assertTrue(prepared.all { it in BangumiChannel.entries })
        } finally { scene.close() }
    }

    @Test fun settledAndSubthresholdOffsetsDoNotStartAnotherScroll() = runBlocking {
        for (offset in listOf(0f, -0.0005f)) {
            var enabled by mutableStateOf(false)
            lateinit var pager: PagerState
            lateinit var scope: CoroutineScope
            var starts = 0
            var observing by mutableStateOf(false)
            var now = 0L
            val scene = ImageComposeScene(2000, 200, Density(1f)) {
                scope = rememberCoroutineScope()
                pager = rememberBangumiChannelPagerState(BangumiChannel.CINEMA, enabled) { }
                LaunchedEffect(pager) {
                    snapshotFlow { pager.isScrollInProgress }.collect { if (it && observing) starts++ }
                }
                androidx.compose.foundation.pager.HorizontalPager(pager) { Box(Modifier.fillMaxSize()) }
            }
            fun frames(n: Int) { repeat(n) { Snapshot.sendApplyNotifications(); now += 20_000_000; scene.render(now).close() } }
            try {
                frames(8)
                val setup = scope.launch(start = CoroutineStart.UNDISPATCHED) { pager.scrollToPage(1, offset) }
                frames(8); setup.join()
                val initial = pager.currentPageOffsetFraction
                assertEquals(offset, initial, 0.0001f)
                observing = true; enabled = true; frames(60)
                assertEquals(0, starts)
                assertFalse(pager.isScrollInProgress)
                assertEquals(initial, pager.currentPageOffsetFraction)
            } finally { scene.close() }
        }
    }
}
