package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import com.android.purebilibili.data.repository.DesktopOriginalBangumiHubRepository
import com.android.purebilibili.feature.bangumi.BangumiHubViewModel
import kotlinx.coroutines.CoroutineScope

/** The retained Home entry supplies the existing owned raw services and account admission.
 * All original Hub state transitions, index/filter/search and follow mutations remain upstream. */
internal class DesktopBangumiHubEnvironment(
    val repository: DesktopOriginalBangumiHubRepository,
    val scope: CoroutineScope,
    val isLoggedIn: () -> Boolean,
    val isCurrent: () -> Boolean,
    val commitIfCurrent: ((() -> Unit)) -> Boolean,
)

internal val LocalDesktopBangumiHubViewModel = staticCompositionLocalOf<BangumiHubViewModel> {
    error("Original Home Bangumi requires its retained per-entry ViewModel")
}

@Composable
internal fun DesktopOriginalHomeBangumiContent(
    environment: DesktopBangumiHubEnvironment,
    home: DesktopHomeEnvironment,
    viewModel: BangumiHubViewModel,
    contentPadding: PaddingValues,
    onBangumiClick: (Long) -> Unit,
    onBangumiEpisodeClick: (Long, Long) -> Unit,
    scrollToTopRequestId: Int,
) {
    if (environment.isCurrent()) {
        CompositionLocalProvider(LocalDesktopHomeEnvironment provides home,
            LocalDesktopBangumiHubViewModel provides viewModel) {
            com.android.purebilibili.feature.bangumi.HomeBangumiTabPage(
                contentPadding = contentPadding,
                onBangumiClick = onBangumiClick,
                onBangumiEpisodeClick = onBangumiEpisodeClick,
                scrollToTopRequestId = scrollToTopRequestId,
                viewModel = viewModel,
            )
        }
    }
}
