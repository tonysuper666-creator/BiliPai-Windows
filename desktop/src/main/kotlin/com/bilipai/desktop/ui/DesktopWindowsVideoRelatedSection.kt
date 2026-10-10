package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.LocalUpBadgeVisibility
import com.android.purebilibili.core.ui.transition.LocalVideoCardSharedElementSourceRoute
import com.android.purebilibili.data.model.response.RelatedVideo
import com.android.purebilibili.feature.common.resolveIndexedVideoLazyKey
import com.android.purebilibili.feature.video.screen.buildDesktopOriginalVideoNavigationOptions
import com.android.purebilibili.feature.video.ui.components.RelatedVideoGridRow
import com.android.purebilibili.feature.video.ui.components.RelatedVideosHeader
import com.android.purebilibili.feature.video.ui.components.chunkRelatedVideosForHomeStyleGrid
import com.android.purebilibili.feature.video.ui.components.rememberRelatedVideoCardLayout
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState
import com.android.purebilibili.navigation3.BiliPaiNavKey

/** Mount the complete original card/grid in the finite Windows details sibling.
 * The retained original VM supplies full RelatedVideo data, never the legacy title projection.
 * Navigation carries this panel's permanent metadata lease to Root's final physical commit.
 * Original mutation menus require a source-scoped binding and are enabled in a later slice. */
@Composable internal fun DesktopWindowsVideoRelatedSection(
    assembly: DesktopOriginalVideoOwnerAssembly,
    success: VideoPlaybackUiState.Success,
    sourceOwner: DesktopOriginalVideoAcceptedPublication,
    content: DesktopOriginalVideoContentBindings,
    stillOwned: () -> Boolean,
    onNavigation: (BiliPaiNavKey, () -> Boolean) -> Unit,
) {
    val info = success.info
    key(assembly, sourceOwner, info) {
        val latestOwned by rememberUpdatedState(stillOwned)
        val latestNavigation by rememberUpdatedState(onNavigation)
        val loadToken = remember { assembly.playback.captureDesktopLoadState().currentLoadRequestToken }
        val lease = remember {
            DesktopWindowsVideoMetadataLease {
                latestOwned() && assembly.owns() && assembly.native.isCurrent(sourceOwner) &&
                    (assembly.playback.captureDesktopPlaybackState() as? VideoPlaybackUiState.Success)?.let { current ->
                        !current.isQualitySwitching && desktopWindowsVideoMetadataMatchesSource(current.info,
                            sourceOwner.request, assembly.playback.captureDesktopLoadState(), loadToken)
                    } == true
            }
        }
        DisposableEffect(lease) { onDispose { lease.close() } }
        fun ownsCard(video: RelatedVideo): Boolean = lease.isOwned() &&
            (assembly.playback.captureDesktopPlaybackState() as? VideoPlaybackUiState.Success)
                ?.related?.any { it === video } == true
        val relatedRows = remember(success.related) { chunkRelatedVideosForHomeStyleGrid(success.related) }
        val showUpBadge = LocalUpBadgeVisibility.current.showBadges
        if (lease.isOwned()) {
            CompositionLocalProvider(
                LocalDesktopOriginalVideoContentBindings provides content,
                LocalVideoCardSharedElementSourceRoute provides ("video/" + info.bvid),
            ) {
                val cardLayout = rememberRelatedVideoCardLayout()
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                    item { RelatedVideosHeader() }
                    if (relatedRows.isEmpty()) item {
                        Text("暂无相关推荐", style = MaterialTheme.typography.bodyMedium)
                    }
                    itemsIndexed(relatedRows, key = { rowIndex, row ->
                        val first = row.firstOrNull()
                        resolveIndexedVideoLazyKey(namespace = "video_related_row", index = rowIndex,
                            bvid = first?.bvid.orEmpty(), aid = first?.aid ?: 0L, cid = first?.cid ?: 0L)
                    }) { _, row ->
                        RelatedVideoGridRow(
                            videos = row,
                            cardLayout = cardLayout,
                            followingMids = success.followingMids,
                            showUpBadge = showUpBadge,
                            showActions = false,
                            onVideoClick = { video ->
                                val clickOwned = { ownsCard(video) }
                                if (clickOwned()) {
                                    val options = buildDesktopOriginalVideoNavigationOptions(
                                        targetCid = video.cid, coverUrl = video.pic)
                                    latestNavigation(BiliPaiNavKey.VideoDetail(video.bvid,
                                        options?.first ?: 0L, options?.second.orEmpty()), clickOwned)
                                }
                            },
                            onUpClick = { video ->
                                val clickOwned = { ownsCard(video) }
                                if (video.owner.mid > 0L && clickOwned())
                                    latestNavigation(BiliPaiNavKey.Space(video.owner.mid), clickOwned)
                            },
                        )
                    }
                }
            }
        }
    }
}
