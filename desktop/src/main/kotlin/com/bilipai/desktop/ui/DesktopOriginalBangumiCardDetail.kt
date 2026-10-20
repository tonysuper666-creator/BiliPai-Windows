package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.android.purebilibili.data.model.response.BangumiEpisode
import com.android.purebilibili.feature.bangumi.BangumiDetailScreen
import com.android.purebilibili.feature.bangumi.BangumiViewModel

/**
 * Destination of the carried v0.4.6 Bangumi card container transition (BangumiCardTransitionHost).
 *
 * The container transition is phone-style motion that the roadmap decisions defer (D1), and no Windows host passes
 * onPlayEpisode, which is the only way the carried host opens a destination. The destination still has to compile
 * against the Windows detail screen, which requires its view model and has no container reveal: it builds the pages
 * view model from the page environment of the entry that hosts it (the same construction the independent page
 * host uses for a detail route) and shows the loaded detail at once. contentTransitionReady belongs to the deferred
 * motion and is not read.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
internal fun DesktopOriginalBangumiCardDetailScreen(
    seasonId: Long,
    epId: Long,
    onBack: () -> Unit,
    onEpisodeClick: (Long, BangumiEpisode) -> Unit,
    contentTransitionReady: Boolean,
    onSeasonClick: (Long) -> Unit,
    onReviewsClick: (Long, String) -> Unit,
    onUserClick: (Long) -> Unit,
    modifier: Modifier,
) {
    val environment = LocalDesktopOriginalBangumiPagesEnvironment.current
    val viewModel = remember(environment) { BangumiViewModel(environment) }
    Box(modifier) {
        BangumiDetailScreen(
            seasonId = seasonId,
            epId = epId,
            onBack = onBack,
            onEpisodeClick = onEpisodeClick,
            onSeasonClick = onSeasonClick,
            onReviewsClick = onReviewsClick,
            onUserClick = onUserClick,
            viewModel = viewModel,
        )
    }
}
