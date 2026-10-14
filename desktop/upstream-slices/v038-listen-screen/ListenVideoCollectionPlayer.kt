package com.android.purebilibili.feature.audio.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.purebilibili.core.ui.components.AppSurface
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.core.ui.components.AppTextButton
import com.android.purebilibili.feature.audio.viewmodel.ListenVideoCollectionViewModel

@Composable
internal fun ListenVideoCollectionPlayer(
    mediaId: Long,
    seasonId: Long,
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ListenVideoCollectionViewModel = viewModel(),
    player: @Composable (bvid: String, cid: Long) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(mediaId, seasonId) { viewModel.open(mediaId, seasonId) }
    Box(modifier = modifier.fillMaxSize()) {
        player(state.firstTrack?.bvid.orEmpty(), state.firstTrack?.cid ?: 0L)
        if (state.firstTrack == null && state.error != null) {
            AppSurface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AppTextButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart).padding(top = 40.dp)) {
                        AppText("返回")
                    }
                    Column(modifier = Modifier.align(Alignment.Center).padding(24.dp)) {
                        AppText(title, style = MaterialTheme.typography.titleLarge)
                        AppText(state.error.orEmpty())
                        AppTextButton(onClick = viewModel::retry) { AppText("重试") }
                    }
                }
            }
        } else if (state.firstTrack != null && state.error != null) {
            AppSurface(
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                    AppText("已读取 ${state.loadedTrackCount} 首，后续曲目读取失败", modifier = Modifier.weight(1f))
                    AppTextButton(onClick = viewModel::retry) { AppText("继续读取") }
                }
            }
        }
    }
}
