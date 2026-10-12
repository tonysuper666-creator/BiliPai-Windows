package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.AppShapes
import com.android.purebilibili.core.ui.ContainerLevel
import com.android.purebilibili.core.ui.components.AppSurface
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.feature.bangumi.ui.detail.BangumiDetailHeader

/** Original readonly season/episode data beside REAL Loading/Error content.
 * No Success, player, VM, request scope or source action is created. */
@Composable internal fun DesktopOriginalBangumiPendingContent(
    readOnlyDetail: DesktopOriginalBangumiReadOnlyDetail?,
    loading: Boolean,
    modifier: Modifier = Modifier,
    status: @Composable () -> Unit,
) {
    val current = readOnlyDetail?.takeIf { it.isCurrent() }
    if (current == null) {
        Box(modifier.fillMaxSize()) { status() }
        return
    }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        BoxWithConstraints(Modifier.widthIn(max = 1120.dp).fillMaxWidth().fillMaxHeight().padding(16.dp)) {
            if (maxWidth >= 840.dp) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                        DesktopOriginalBangumiReadonlyMetadata(current)
                    }
                    DesktopOriginalBangumiPendingStatus(Modifier.width(320.dp).fillMaxHeight(), status)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        // Keep the original error/login/retry visible before metadata.
                        DesktopOriginalBangumiPendingStatus(
                            Modifier.fillMaxWidth().height(if (loading) 96.dp else 320.dp), status)
                    }
                    item { DesktopOriginalBangumiReadonlyMetadata(current) }
                }
            }
        }
    }
}

/** Retain original centered status for short content and allow a long original
 * error/login/retry panel to scroll within its allocated window space. */
@Composable private fun DesktopOriginalBangumiPendingStatus(
    modifier: Modifier,
    status: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier) {
        val viewportHeight = maxHeight
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().heightIn(min = viewportHeight)) { status() }
        }
    }
}

/** The complete original poster/rating/stat header is reused unchanged.
 * Episode selection/follow/download and all playback remain Success-only. */
@Composable private fun DesktopOriginalBangumiReadonlyMetadata(
    current: DesktopOriginalBangumiReadOnlyDetail,
) {
    if (!current.isCurrent()) return
    val detail = current.detail
    val episode = current.currentEpisode
    val episodeLabel = listOf(episode.title, episode.longTitle)
        .filter { it.isNotBlank() }.joinToString(" ")
        .ifBlank { "第" + (current.currentEpisodeIndex + 1) + "集" }
    AppSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.container(ContainerLevel.Sheet),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            BangumiDetailHeader(detail, PaddingValues(0.dp))
            Column(Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppText("当前剧集：" + episodeLabel, style = MaterialTheme.typography.titleSmall)
                if (detail.subtitle.isNotBlank())
                    AppText(detail.subtitle, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (detail.evaluate.isNotBlank())
                    AppText(detail.evaluate, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
