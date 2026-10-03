package com.android.purebilibili.feature.download

import android.widget.Toast

import com.android.purebilibili.core.ui.animation.jiggleOnDissolve
import com.android.purebilibili.core.ui.components.VideoListLayoutToggle
import com.android.purebilibili.core.ui.components.resolveVideoListColumns
import com.android.purebilibili.core.ui.components.rememberVideoListLayoutControl
import com.android.purebilibili.core.ui.components.videoListItemModifier
import com.android.purebilibili.core.ui.components.AnimatedVideoListItem
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders

import coil3.request.crossfade
import com.android.purebilibili.core.ui.components.AppIcon
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.core.ui.AppAlertDialog
import com.android.purebilibili.core.ui.AppSpacingTokens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
//  Material Icons
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.android.purebilibili.core.store.SettingsManager
import com.android.purebilibili.core.ui.ImmersiveAppScaffold as AppScaffold
import com.android.purebilibili.core.ui.AppTopBar
import com.android.purebilibili.core.ui.rememberAppBackIcon
import com.android.purebilibili.core.ui.AppSurfaceTokens
import com.android.purebilibili.core.ui.components.AppCard
import com.android.purebilibili.feature.home.components.cards.HorizontalVideoCardFrame
import com.android.purebilibili.core.ui.components.AppCardDefaults
import com.android.purebilibili.core.ui.components.AppCardShape
import com.android.purebilibili.core.ui.components.AppCircularProgressIndicator
import com.android.purebilibili.core.ui.components.AppIconButton
import com.android.purebilibili.core.util.NetworkUtils
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.purebilibili.core.ui.AppShapes
import com.android.purebilibili.core.ui.ContainerLevel

/**
 *  离线缓存列表页面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadListScreen(
    onBack: () -> Unit,
    onVideoClick: (String) -> Unit,  // bvid - 在线播放
    onOfflineVideoClick: (String) -> Unit = {}  // 🔧 [新增] taskId - 离线播放
) {
    val context = LocalContext.current
    val listLayout = rememberVideoListLayoutControl(defaultSingleColumn = true)
    val gridState = rememberLazyGridState()
    val columns = resolveVideoListColumns(
        listLayout.singleColumn,
        androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.toFloat(),
    )
    val tasks by DownloadManager.tasks.collectAsStateWithLifecycle()
    var isNetworkAvailable by remember(context) { mutableStateOf(NetworkUtils.isNetworkAvailable(context)) }
    val customDownloadPath by SettingsManager.getDownloadPath(context).collectAsStateWithLifecycle(initialValue = null)
    val downloadExportTreeUri by SettingsManager.getDownloadExportTreeUri(context).collectAsStateWithLifecycle(initialValue = null)
    val taskList = tasks.values.toList().sortedByDescending { it.createdAt }
    var pendingDeleteTask by remember { mutableStateOf<com.android.purebilibili.feature.download.DownloadTask?>(null) }
    var dissolvingTaskIds by remember { mutableStateOf(setOf<String>()) }
    val currentDir = resolveDisplayedDownloadLocation(
        defaultManagedPath = remember(context) { SettingsManager.getDefaultDownloadPath(context) },
        customManagedPath = customDownloadPath,
        exportTreeUri = downloadExportTreeUri
    )

    LaunchedEffect(context) {
        while (true) {
            isNetworkAvailable = NetworkUtils.isNetworkAvailable(context)
            delay(1_500L)
        }
    }

    // 下载速度：串行队列同时只有一个任务在下载，按已下载字节差值估算
    var activeDownloadSpeedBytesPerSecond by remember { mutableStateOf(0L) }
    var lastSampledBytes by remember { mutableStateOf(0L) }
    LaunchedEffect(taskList) {
        while (true) {
            delay(1_000L)
            val activeTask = tasks.values.firstOrNull { it.status == DownloadStatus.DOWNLOADING }
            if (activeTask == null) {
                activeDownloadSpeedBytesPerSecond = 0L
                lastSampledBytes = 0L
                continue
            }
            val totalBytes = activeTask.assets.sumOf { it.downloadedBytes.coerceAtLeast(0L) }
            val delta = totalBytes - lastSampledBytes
            if (lastSampledBytes > 0L && delta >= 0L) {
                activeDownloadSpeedBytesPerSecond = delta
            }
            lastSampledBytes = totalBytes
        }
    }

    AppScaffold(
        topBar = {
            AppTopBar(
                title = "离线缓存",
                navigationIcon = {
                    AppIconButton(onClick = onBack) {
                        AppIcon(rememberAppBackIcon(), contentDescription = "返回")
                    }
                },
                actions = {
                    val hasActive = taskList.any(::shouldPauseAllInclude)
                    val hasResumable = taskList.any(::shouldContinueAllInclude)
                    if (hasActive || hasResumable) {
                        androidx.compose.material3.TextButton(onClick = {
                            if (hasActive) {
                                taskList.filter(::shouldPauseAllInclude).forEach {
                                    DownloadManager.pauseDownload(it.id)
                                }
                            } else {
                                taskList.filter(::shouldContinueAllInclude).forEach {
                                    DownloadManager.startDownload(it.id)
                                }
                            }
                        }) {
                            AppText(
                                text = if (hasActive) "暂停全部" else "继续全部",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    VideoListLayoutToggle(
                        singleColumn = listLayout.singleColumn,
                        onClick = listLayout.toggle,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        // 遮挡铰链时整页内容落入最大安全区，软折痕允许跨越（与首页/动态/搜索一致）。
        com.android.purebilibili.core.ui.adaptive.AppHingeSafeContent(
            modifier = Modifier.fillMaxSize(),
        ) {
        if (taskList.isEmpty()) {
            // 空状态
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(modifier = Modifier.height(AppSpacingTokens.Large))
                    AppText(
                        text = "暂无缓存视频",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(AppSpacingTokens.Small))
                    AppText(
                        text = "在视频详情页点击「缓存」按钮下载",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxSize()
                    ,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 16.dp, bottom = padding.calculateBottomPadding() + 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(taskList, key = { it.id }) { task ->
                    val isDissolving = task.id in dissolvingTaskIds
                    com.android.purebilibili.core.ui.animation.MaybeDissolvableVideoCard(
                        isDissolving = isDissolving,
                        onDissolveComplete = {
                            dissolvingTaskIds = dissolvingTaskIds - task.id
                            DownloadManager.removeTask(task.id)
                            pendingDeleteTask = null
                        },
                        cardId = task.id,
                        preset = com.android.purebilibili.core.ui.animation.DissolveAnimationPreset.TELEGRAM_FAST,
                        modifier = Modifier.jiggleOnDissolve(
                            cardId = task.id,
                            isCurrentCardDissolving = isDissolving,
                        ),
                    ) {
                    AnimatedVideoListItem(modifier = videoListItemModifier(), enabled = true) {
                        val playableOffline = remember(task.filePath, task.status) {
                            isDownloadTaskPlayableOffline(task)
                        }
                        DownloadTaskItem(
                            task = task,
                            stacked = columns > 1,
                            onClick = {
                                when (resolveDownloadTaskClickTarget(task, isNetworkAvailable = isNetworkAvailable)) {
                                    DownloadTaskClickTarget.OfflinePlayer -> onOfflineVideoClick(task.id)
                                    DownloadTaskClickTarget.OnlinePlayer -> {
                                        Toast.makeText(
                                            context,
                                            "本地文件不可用，已切换在线播放",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        onVideoClick(task.bvid)
                                    }
                                    null -> Unit
                                }
                            },
                            onPauseResume = {
                                if (task.isDownloading) {
                                    DownloadManager.pauseDownload(task.id)
                                } else if (task.canResume) {
                                    DownloadManager.startDownload(task.id)
                                }
                            },
                            onDelete = {
                                pendingDeleteTask = task
                            },
                            offlinePlayable = playableOffline,
                            speedBytesPerSecond = if (task.status == DownloadStatus.DOWNLOADING) {
                                activeDownloadSpeedBytesPerSecond
                            } else {
                                0L
                            }
                        )
                    }
                    }
                }

                // [新增] 显示当前存储路径
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        AppText(
                            text = "共 ${taskList.size} 个 · 已用 " + formatDownloadStorageBytes(
                                taskList.sumOf { it.fileSize.coerceAtLeast(0L) }
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.height(AppSpacingTokens.ExtraSmall))
                        AppText(
                            text = "存储位置",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.height(AppSpacingTokens.ExtraSmall))
                        AppText(
                            text = currentDir,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
        }
        }
    }

    // 删除确认：避免误触直接清掉已下载的文件
    pendingDeleteTask?.let { taskToDelete ->
        AppAlertDialog(
            onDismissRequest = { pendingDeleteTask = null },
            title = { AppText("删除缓存") },
            text = {
                AppText(
                    text = "确定删除「${taskToDelete.title}」吗？已下载的视频、音频和弹幕文件将一并清除。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        // Dissolve first; the actual removal happens onDissolveComplete.
                        dissolvingTaskIds = dissolvingTaskIds + taskToDelete.id
                    }
                ) {
                    AppText("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingDeleteTask = null }) {
                    AppText("取消")
                }
            }
        )
    }
}


/** 下载占用容量摘要（B / KB / MB / GB 自适应） */
internal fun formatDownloadStorageBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format(java.util.Locale.US, "%.2f GB", gb)
        mb >= 1.0 -> String.format(java.util.Locale.US, "%.1f MB", mb)
        kb >= 1.0 -> String.format(java.util.Locale.US, "%.0f KB", kb)
        else -> "$bytes B"
    }
}


@Composable
private fun DownloadTaskItem(
    task: DownloadTask,
    onClick: () -> Unit,
    onPauseResume: () -> Unit,
    onDelete: () -> Unit,
    stacked: Boolean = false,
    offlinePlayable: Boolean,
    speedBytesPerSecond: Long = 0L,
    modifier: Modifier = Modifier,
) {
    // 与相关推荐/个人列表一致：复用全局横向卡骨架（顶对齐、共享封面宽度与信息区排版）。
    HorizontalVideoCardFrame(
        stacked = stacked,
        coverAspectRatio = 16f / 9f,
        coverModifier = Modifier.padding(top = AppSpacingTokens.Small),
        modifier = modifier
            .fillMaxWidth()
            .clip(AppShapes.container(ContainerLevel.Card))
            .background(AppSurfaceTokens.cardContainer())
            .clickable(onClick = onClick),
        coverContent = {
            // 🖼️ [修复] 优先使用本地封面（无网络时也能显示）
            val localCoverFile = task.localCoverPath?.let { java.io.File(it) }
            val coverSource = if (localCoverFile?.exists() == true) {
                localCoverFile
            } else {
                val coverUrl = task.cover.let { url ->
                    if (url.startsWith("http://")) url.replace("http://", "https://")
                    else url
                }
                coil3.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                    .data(coverUrl)
                    .httpHeaders(NetworkHeaders.Builder().set("Referer", "https://www.bilibili.com").build())
                    .crossfade(true)
                    .build()
            }

            AsyncImage(
                model = coverSource,
                contentDescription = task.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        },
        coverOverlayContent = {
            // 进度/状态覆盖层
            if (!task.isComplete) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    when (task.status) {
                        DownloadStatus.QUEUED -> {
                            AppText("排队中", color = Color.White, style = MaterialTheme.typography.labelMedium)
                        }
                        DownloadStatus.DOWNLOADING, DownloadStatus.MERGING -> {
                            AppCircularProgressIndicator(
                                progress = { resolveDownloadTaskProgress(task) },
                                modifier = Modifier.size(32.dp),
                                color = Color.White,
                                strokeWidth = 3.dp
                            )
                        }
                        DownloadStatus.PAUSED -> {
                            AppText("已暂停", color = Color.White, style = MaterialTheme.typography.labelMedium)
                        }
                        DownloadStatus.FAILED -> {
                            AppText("失败", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                        }
                        else -> {}
                    }
                }
            }

            // 画质标签
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(AppSpacingTokens.ExtraSmall)
                    .background(
                        Color.Black.copy(alpha = 0.7f),
                        AppShapes.container(ContainerLevel.Tag)
                    )
                    .padding(horizontal = AppSpacingTokens.ExtraSmall, vertical = AppSpacingTokens.Micro)
            ) {
                AppText(
                    text = task.qualityDesc,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        },
        infoContent = {
            AppText(
                text = task.title,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(AppSpacingTokens.ExtraSmall))

            resolveDownloadTaskSecondaryText(task)?.let { secondaryText ->
                AppText(
                    text = secondaryText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(AppSpacingTokens.ExtraSmall))
            }

            AppText(
                text = task.ownerName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(AppSpacingTokens.ExtraSmall))

            // 状态文字
            val assetSummary = resolveDownloadAssetSummary(task)
            val assetTexts = listOfNotNull(
                assetSummary.videoText,
                assetSummary.audioText,
                assetSummary.danmakuText
            )
            val hasAssetSummary = assetTexts.isNotEmpty()
            val statusText = when (task.status) {
                DownloadStatus.QUEUED -> "排队中..."
                DownloadStatus.PENDING -> "等待中..."
                DownloadStatus.DOWNLOADING -> buildString {
                    append("下载中 ${resolveDownloadTaskProgressPercent(task)}%")
                    if (speedBytesPerSecond > 0L) {
                        append(" · ")
                        append(formatDownloadStorageBytes(speedBytesPerSecond))
                        append("/s")
                    }
                }
                DownloadStatus.MERGING -> "处理中..."
                // 已完成时资产行（视频/音频/弹幕完成）信息重复，仅在其缺席时显示
                DownloadStatus.COMPLETED -> if (hasAssetSummary) null else "已完成"
                DownloadStatus.PAUSED -> "已暂停"
                DownloadStatus.FAILED -> task.errorMessage ?: "下载失败"
            }
            if (statusText != null) {
                AppText(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = when (task.status) {
                        DownloadStatus.COMPLETED -> MaterialTheme.colorScheme.secondary
                        DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    }
                )
            }

            if (hasAssetSummary) {
                Spacer(modifier = Modifier.height(AppSpacingTokens.ExtraSmall))
                // 单行省略：小横卡信息区宽度有限，折行会截断成“弹幕完/成”
                AppText(
                    text = assetTexts.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (task.isComplete && !offlinePlayable) {
                Spacer(modifier = Modifier.height(AppSpacingTokens.ExtraSmall))
                AppText(
                    text = if (!task.exportedFileUri.isNullOrBlank()) {
                        "已导出到自定义目录，当前列表不直接离线播放"
                    } else {
                        "本地缓存文件不可用"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        trailingContent = {
            Column(
                modifier = Modifier.align(Alignment.BottomEnd),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 暂停/继续
                if (task.isDownloading || task.canResume) {
                    AppIconButton(onClick = onPauseResume) {
                        AppIcon(
                            imageVector = if (task.isDownloading) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            contentDescription = if (task.isDownloading) "暂停" else "继续",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // 删除
                AppIconButton(onClick = onDelete) {
                    AppIcon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
    )
}
