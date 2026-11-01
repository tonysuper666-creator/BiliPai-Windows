package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import com.android.purebilibili.data.repository.DesktopOriginalBangumiHubRepository
import com.android.purebilibili.feature.bangumi.BangumiHubViewModel
import kotlinx.coroutines.*

/** The retained Home entry supplies the existing owned raw services and account admission.
 * All original Hub state transitions, index/filter/search and follow mutations remain upstream. */
internal class DesktopBangumiHubEnvironment(
    val repository: DesktopOriginalBangumiHubRepository,
    val scope: CoroutineScope,
    val isLoggedIn: () -> Boolean,
    val isCurrent: () -> Boolean,
    val commitIfCurrent: ((() -> Unit)) -> Boolean,
    val accountIdentities: kotlinx.coroutines.flow.StateFlow<com.android.purebilibili.core.store.AccountSessionIdentity>,
)

/** One full VM per retained host instance; only its parent entry/page closes this child.
 * Same-epoch installation notification gaps are owned by TON-1148, not polled here. */
internal class DesktopBangumiHubInstance(parent: DesktopBangumiHubEnvironment) {
    private val job = SupervisorJob(parent.scope.coroutineContext[Job])
    val environment = DesktopBangumiHubEnvironment(parent.repository,
        CoroutineScope(parent.scope.coroutineContext + job), parent.isLoggedIn,
        { job.isActive && parent.isCurrent() },
        { action ->
            var applied = false
            parent.commitIfCurrent { if (job.isActive) { action(); applied = true } } && applied
        }, parent.accountIdentities)
    val viewModel = try { BangumiHubViewModel(environment) }
        catch (failure: Throwable) { job.cancel(); throw failure }
}

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
    onPlayFollow: (Long) -> Unit,
    scrollToTopRequestId: Int,
) {
    if (environment.isCurrent()) {
        CompositionLocalProvider(LocalDesktopHomeEnvironment provides home,
            LocalDesktopBangumiHubViewModel provides viewModel) {
            com.android.purebilibili.feature.bangumi.HomeBangumiTabPage(
                contentPadding = contentPadding,
                onBangumiClick = onBangumiClick,
                onBangumiEpisodeClick = onBangumiEpisodeClick,
                onPlayFollow = onPlayFollow,
                scrollToTopRequestId = scrollToTopRequestId,
                viewModel = viewModel,
            )
        }
    }
}
