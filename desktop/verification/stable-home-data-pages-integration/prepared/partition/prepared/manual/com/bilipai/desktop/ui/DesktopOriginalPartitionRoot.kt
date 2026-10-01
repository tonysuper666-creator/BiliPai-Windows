package com.bilipai.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.layout.PaddingValues
import com.android.purebilibili.data.model.response.VideoItem
import com.android.purebilibili.feature.partition.PartitionFeedViewModel
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.CoroutineScope

/** The retained Home entry supplies its actual scope, atomic account admission and original raw
 * protocol port. Settings/card visuals read that very same Root global PluginStore projection. */
internal class DesktopPartitionEnvironment(
    val video: DesktopHomeVideoRequests,
    val scope: CoroutineScope,
    val isCurrent: () -> Boolean,
    val commitIfCurrent: ((() -> Unit)) -> Boolean,
)

internal val LocalDesktopPartitionViewModel = staticCompositionLocalOf<PartitionFeedViewModel> {
    error("Original Partition requires its retained per-entry ViewModel")
}

/** Only platform bindings; every original side rail, list, scroll, refresh and load reducer lives
 * in the sole original-source generator. Home platform/lifecycle/theme locals come from Root. */
@Composable
internal fun DesktopOriginalPartitionContent(
    environment: DesktopPartitionEnvironment,
    home: DesktopHomeEnvironment,
    viewModel: PartitionFeedViewModel,
    contentPadding: PaddingValues,
    onVideoClick: (VideoItem) -> Unit,
    onBangumiClick: (Int) -> Unit,
    scrollToTopRequestId: Int,
    hazeState: HazeState?,
) {
    if (environment.isCurrent()) {
        CompositionLocalProvider(LocalDesktopHomeEnvironment provides home,
            LocalDesktopPartitionViewModel provides viewModel) {
            com.android.purebilibili.feature.partition.PartitionContent(
                contentPadding = contentPadding, onVideoClick = onVideoClick,
                onBangumiClick = onBangumiClick, scrollToTopRequestId = scrollToTopRequestId,
                hazeState = hazeState, viewModel = viewModel,
            )
        }
    }
}
