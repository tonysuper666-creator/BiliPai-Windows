package com.android.purebilibili.feature.video.screen
import com.android.purebilibili.core.ui.resolveFilledButtonContainerColor
import com.android.purebilibili.core.ui.resolveFilledButtonContentColor
import com.android.purebilibili.core.ui.components.AppHorizontalDivider

import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext as Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ButtonDefaults
import com.android.purebilibili.core.ui.components.AppIcon
import androidx.compose.material3.MaterialTheme
import com.android.purebilibili.core.ui.components.AppText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.bilipai.desktop.ui.DesktopHomeCardWindowMetrics as LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import com.bilipai.desktop.ui.DesktopOriginalMpvOverlayControl as Player
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.purebilibili.core.ui.AppShapes
import com.android.purebilibili.core.ui.AppSurfaceTokens
import com.android.purebilibili.core.ui.ContainerLevel
import com.android.purebilibili.core.ui.appContentDialogWidth
//  已改用 MaterialTheme.colorScheme.primary

import com.android.purebilibili.feature.common.resolveIndexedVideoLazyKey
// Refactored UI components
import com.android.purebilibili.feature.video.ui.components.resolveDanmakuTimestampJumpMs
// Imports for moved classes
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackViewModel
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState
import com.android.purebilibili.feature.video.viewmodel.QualitySwitchFailureDialogState
import com.android.purebilibili.feature.video.viewmodel.CommentUiState
import com.android.purebilibili.feature.video.viewmodel.VideoCommentViewModel
import com.android.purebilibili.feature.video.ui.components.VideoCommentSheetHost

import com.android.purebilibili.feature.video.usecase.playPlayerFromUserAction
import com.android.purebilibili.core.ui.AdaptiveLoadingIndicator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
//  共享元素过渡
import com.android.purebilibili.core.ui.rememberAppPlayIcon
import com.android.purebilibili.feature.video.player.PlaylistItem
// 📱 [新增] 竖屏全屏
import com.android.purebilibili.core.ui.blur.unifiedBlur
import com.android.purebilibili.core.ui.AppAlertDialog
import com.android.purebilibili.core.ui.AppModalBottomSheet
import com.android.purebilibili.core.ui.components.AppButton
import com.android.purebilibili.core.ui.components.AppCheckbox
import com.android.purebilibili.core.ui.components.AppCircularProgressIndicator
import com.android.purebilibili.core.ui.components.AppSurface
import com.android.purebilibili.core.ui.components.AppTextButton
import coil3.compose.AsyncImage
import dev.chrisbanes.haze.HazeState
import com.android.purebilibili.core.ui.blur.hazeEffectCompat
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import com.android.purebilibili.feature.video.ui.components.DanmakuContextMenu
import com.android.purebilibili.feature.video.ui.components.DanmakuBlockActionTarget
import com.android.purebilibili.feature.video.ui.components.resolveDanmakuBlockActionFeedbackMessage
import com.android.purebilibili.feature.video.danmaku.appendDanmakuKeywordBlockRule
import com.android.purebilibili.feature.video.danmaku.appendDanmakuUserHashBlockRule
import kotlin.math.roundToInt

@Composable
internal fun VideoDetailFollowGroupDialog(
    viewModel: VideoPlaybackViewModel,
    admitAction: ((() -> Unit) -> Boolean) = { action -> action(); true },
) {
    val holderPlatform = com.bilipai.desktop.ui.LocalDesktopOriginalVideoHolderPlatform.current
    val followGroupDialogVisible by viewModel.followGroupDialogVisible.collectAsStateWithLifecycle(
        context = kotlin.coroutines.EmptyCoroutineContext
    )
    val followGroupTags by viewModel.followGroupTags.collectAsStateWithLifecycle(
        context = kotlin.coroutines.EmptyCoroutineContext
    )
    val followGroupSelectedTagIds by viewModel.followGroupSelectedTagIds.collectAsStateWithLifecycle(
        context = kotlin.coroutines.EmptyCoroutineContext
    )
    val isFollowGroupsLoading by viewModel.isFollowGroupsLoading.collectAsStateWithLifecycle(
        context = kotlin.coroutines.EmptyCoroutineContext
    )
    val isSavingFollowGroups by viewModel.isSavingFollowGroups.collectAsStateWithLifecycle(
        context = kotlin.coroutines.EmptyCoroutineContext
    )
    if (!followGroupDialogVisible) return

    AppAlertDialog(
        onDismissRequest = {
            if (!isSavingFollowGroups) admitAction { viewModel.dismissFollowGroupDialog() }
        },
        title = { AppText("设置关注分组") },
        text = {
            if (isFollowGroupsLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AdaptiveLoadingIndicator()
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (followGroupTags.isEmpty()) {
                        AppText(
                            text = "暂无可用分组（不勾选即为默认分组）",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        followGroupTags.forEach { tag ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { admitAction { viewModel.toggleFollowGroupSelection(tag.tagid) } }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AppCheckbox(
                                    checked = followGroupSelectedTagIds.contains(tag.tagid),
                                    onCheckedChange = { admitAction { viewModel.toggleFollowGroupSelection(tag.tagid) } }
                                )
                                AppText(
                                    text = "${tag.name} (${tag.count})",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                    AppText(
                        text = "可多选，确定后覆盖原分组设置。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            AppButton(
                onClick = { admitAction { viewModel.saveFollowGroupSelection() } },
                enabled = !isFollowGroupsLoading && !isSavingFollowGroups
            ) {
                if (isSavingFollowGroups) {
                    AppCircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    AppText("确定")
                }
            }
        },
        dismissButton = {
            AppTextButton(
                onClick = { admitAction { viewModel.dismissFollowGroupDialog() } },
                enabled = !isSavingFollowGroups
            ) {
                AppText("取消")
            }
        }
    )
}

@Composable
internal fun VideoDetailPlaybackEndedDialog(
    viewModel: VideoPlaybackViewModel,
    player: Player
) {
    val holderPlatform = com.bilipai.desktop.ui.LocalDesktopOriginalVideoHolderPlatform.current
    val showPlaybackEndedDialog by viewModel.showPlaybackEndedDialog.collectAsStateWithLifecycle(
        context = kotlin.coroutines.EmptyCoroutineContext
    )
    if (!showPlaybackEndedDialog) return

    val dialogLayout = remember {
        com.android.purebilibili.core.ui.resolveAppContentDialogLayoutPolicy(maxWidthDp = 420)
    }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = { viewModel.dismissPlaybackEndedDialog() },
        properties = com.android.purebilibili.core.ui.resolveAppContentDialogProperties(
            usePlatformDefaultWidth = dialogLayout.usePlatformDefaultWidth,
        ),
    ) {
        AppSurface(
            modifier = Modifier.appContentDialogWidth(policy = dialogLayout),
            shape = AppShapes.container(ContainerLevel.Dialog),
            color = AppSurfaceTokens.surface(),
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                AppText(
                    text = "播放完成",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                AppText(
                    text = "选择接下来的操作",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                AppButton(
                    onClick = {
                        viewModel.dismissPlaybackEndedDialog()
                        player.seekTo(0)
                        playPlayerFromUserAction(player)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    AppText("🔄 重播当前视频")
                }
                AppButton(
                    onClick = {
                        viewModel.dismissPlaybackEndedDialog()
                        viewModel.playNextRecommended()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = resolveFilledButtonContainerColor(MaterialTheme.colorScheme),

                        contentColor = resolveFilledButtonContentColor(MaterialTheme.colorScheme)
                    )
                ) {
                    AppText("▶️ 播放下一个视频")
                }
                AppTextButton(
                    onClick = { viewModel.dismissPlaybackEndedDialog() }
                ) {
                    AppText("暂不操作")
                }
            }
        }
    }
}

@Composable
internal fun VideoDetailQualitySwitchFailureDialog(
    context: Context,
    viewModel: VideoPlaybackViewModel,
    qualitySwitchFailureDialog: QualitySwitchFailureDialogState?,
    qualitySwitchFailureDialogEnabled: Boolean,
    qualitySwitchFailureDialogOnceEnabled: Boolean,
    qualitySwitchFailureDialogShown: Boolean,
    playerDiagnosticLoggingEnabled: Boolean,
    qualitySwitchDialogScope: CoroutineScope
) {
    val holderPlatform = com.bilipai.desktop.ui.LocalDesktopOriginalVideoHolderPlatform.current
    LaunchedEffect(
        qualitySwitchFailureDialog?.requestedQualityId,
        qualitySwitchFailureDialogEnabled,
        qualitySwitchFailureDialogOnceEnabled,
        qualitySwitchFailureDialogShown
    ) {
        val dialog = qualitySwitchFailureDialog ?: return@LaunchedEffect
        val shouldSuppressDialog = !qualitySwitchFailureDialogEnabled ||
            (qualitySwitchFailureDialogOnceEnabled && qualitySwitchFailureDialogShown)
        if (shouldSuppressDialog) {
            viewModel.dismissQualitySwitchFailureDialog()
        }
    }

    qualitySwitchFailureDialog
        ?.takeIf {
            qualitySwitchFailureDialogEnabled &&
                !(qualitySwitchFailureDialogOnceEnabled && qualitySwitchFailureDialogShown)
        }
        ?.let { dialog ->
            var onceForCurrentDialog by remember(dialog) {
                mutableStateOf(qualitySwitchFailureDialogOnceEnabled)
            }
            LaunchedEffect(qualitySwitchFailureDialogOnceEnabled) {
                onceForCurrentDialog = qualitySwitchFailureDialogOnceEnabled
            }

            fun dismissQualitySwitchFailureDialogAfterUserChoice() {
                qualitySwitchDialogScope.launch {
                    if (onceForCurrentDialog) {
                        com.android.purebilibili.core.store.DesktopOriginalVideoHolderSettings.markQualitySwitchFailureDialogShown(context)
                    }
                    viewModel.dismissQualitySwitchFailureDialog()
                }
            }

            AppAlertDialog(
                onDismissRequest = { dismissQualitySwitchFailureDialogAfterUserChoice() },
                title = { AppText(dialog.title) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        AppText(dialog.message)
                        AppTextButton(
                            onClick = {
                                qualitySwitchDialogScope.launch {
                                    holderPlatform.setPlayerDiagnosticLoggingEnabled(!playerDiagnosticLoggingEnabled
                                        )
                                }
                            },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            AppText(
                                if (playerDiagnosticLoggingEnabled) {
                                    "关闭诊断日志"
                                } else {
                                    "开启诊断日志"
                                }
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable {
                                    val nextValue = !onceForCurrentDialog
                                    onceForCurrentDialog = nextValue
                                    qualitySwitchDialogScope.launch {
                                        com.android.purebilibili.core.store.DesktopOriginalVideoHolderSettings.setQualitySwitchFailureDialogOnceEnabled(context, nextValue)
                                    }
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppText(
                                text = "仅提示一次",
                                modifier = Modifier.weight(1f),
                                tapToCopyEnabled = false
                            )
                            AppCheckbox(
                                checked = onceForCurrentDialog,
                                onCheckedChange = { checked ->
                                    onceForCurrentDialog = checked
                                    qualitySwitchDialogScope.launch {
                                        com.android.purebilibili.core.store.DesktopOriginalVideoHolderSettings.setQualitySwitchFailureDialogOnceEnabled(context, checked)
                                    }
                                }
                            )
                        }
                    }
                },
                confirmButton = {
                    AppTextButton(
                        onClick = {
                            holderPlatform.exportAndShareLogs()
                            dismissQualitySwitchFailureDialogAfterUserChoice()
                        }
                    ) {
                        AppText("导出日志")
                    }
                },
                dismissButton = {
                    AppTextButton(onClick = { dismissQualitySwitchFailureDialogAfterUserChoice() }) {
                        AppText("关闭")
                    }
                }
            )
        }
}

@Composable
internal fun VideoDetailDanmakuContextMenu(
    context: Context,
    viewModel: VideoPlaybackViewModel,
    activeDanmakuBlockRulesRaw: String,
    activeDanmakuScope: com.android.purebilibili.core.store.DanmakuSettingsScope,
    sortPreferenceScope: CoroutineScope
) {
    val holderPlatform = com.bilipai.desktop.ui.LocalDesktopOriginalVideoHolderPlatform.current
    val danmakuMenuState by viewModel.danmakuMenuState.collectAsStateWithLifecycle(
        context = kotlin.coroutines.EmptyCoroutineContext
    )
    if (!danmakuMenuState.visible) return

    DanmakuContextMenu(
        text = danmakuMenuState.text,
        onDismiss = { viewModel.hideDanmakuMenu() },
        onLike = { viewModel.likeDanmaku(danmakuMenuState.dmid) },
        onRecall = { viewModel.recallDanmaku(danmakuMenuState.dmid) },
        onReport = { reason ->
            viewModel.reportDanmaku(danmakuMenuState.dmid, reason)
        },
        voteCount = danmakuMenuState.voteCount,
        hasLiked = danmakuMenuState.hasLiked,
        voteLoading = danmakuMenuState.voteLoading,
        canVote = danmakuMenuState.canVote,
        timestampJumpMs = resolveDanmakuTimestampJumpMs(danmakuMenuState.text),
        onSeekToTimestamp = { viewModel.seekTo(it) },
        canRecall = danmakuMenuState.isSelf,
        canBlockKeyword = danmakuMenuState.text.isNotBlank(),
        onBlockKeyword = {
            val updatedRules = appendDanmakuKeywordBlockRule(
                rawRules = activeDanmakuBlockRulesRaw,
                keyword = danmakuMenuState.text
            )
            val changed = updatedRules != activeDanmakuBlockRulesRaw
            sortPreferenceScope.launch {
                holderPlatform.section.danmakuPreferences.setDanmakuBlockRulesRaw(updatedRules,
                    activeDanmakuScope
                )
            }
            viewModel.toast(
                resolveDanmakuBlockActionFeedbackMessage(
                    target = DanmakuBlockActionTarget.KEYWORD,
                    changed = changed
                )
            )
        },
        canBlockUser = danmakuMenuState.userHash.isNotBlank(),
        onBlockUser = {
            val userHash = danmakuMenuState.userHash
            if (userHash.isBlank()) {
                viewModel.toast("该弹幕缺少发送者标识")
            } else {
                val updatedRules = appendDanmakuUserHashBlockRule(
                    rawRules = activeDanmakuBlockRulesRaw,
                    userHash = userHash
                )
                val changed = updatedRules != activeDanmakuBlockRulesRaw
                sortPreferenceScope.launch {
                    holderPlatform.section.danmakuPreferences.setDanmakuBlockRulesRaw(updatedRules,
                        activeDanmakuScope
                    )
                }
                viewModel.toast(
                    resolveDanmakuBlockActionFeedbackMessage(
                        target = DanmakuBlockActionTarget.USER,
                        changed = changed
                    )
                )
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExternalPlaylistQueueSheet(
    visible: Boolean,
    title: String,
    playlist: List<PlaylistItem>,
    currentIndex: Int,
    hazeState: HazeState,
    presentation: ExternalPlaylistQueueSheetPresentation,
    onDismiss: () -> Unit,
    onVideoSelected: (Int, PlaylistItem) -> Unit
) {
    val holderPlatform = com.bilipai.desktop.ui.LocalDesktopOriginalVideoHolderPlatform.current
    if (!visible) return

    com.android.purebilibili.core.ui.LocalNavigationBackHandler(enabled = visible) {
        onDismiss()
    }

    val configuration = LocalConfiguration.current
    val listMaxHeight = resolveExternalPlaylistQueueListMaxHeightDp(configuration.screenHeightDp).dp
    val navigationBarBottomPadding = WindowInsets.navigationBars
        .asPaddingValues()
        .calculateBottomPadding()
    val bottomSpacerHeight = resolveExternalPlaylistQueueBottomSpacerDp(
        navigationBarBottomPadding.value.roundToInt()
    ).dp
    val sheetShape = AppShapes.container(ContainerLevel.Sheet)

    when (presentation) {
        ExternalPlaylistQueueSheetPresentation.INLINE_HAZE -> {
            val interactionSource = remember { MutableInteractionSource() }
            val useHazeEffect = com.bilipai.desktop.ui.desktopDetailRenderEffectsSupported()
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.18f))
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null
                        ) { onDismiss() }
                )

                AppSurface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .clip(sheetShape)
                        .then(
                            if (useHazeEffect) {
                                Modifier.hazeEffectCompat(
                                    state = hazeState,
                                    style = HazeMaterials.ultraThin()
                                )
                            } else {
                                Modifier
                            }
                        ),
                    shape = sheetShape,
                    color = AppSurfaceTokens.surface().copy(alpha = 0.74f),
                    tonalElevation = 0.dp,
                    border = BorderStroke(
                        width = 0.6.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                    )
                ) {
                    ExternalPlaylistQueueSheetContent(
                        title = title,
                        playlist = playlist,
                        currentIndex = currentIndex,
                        listMaxHeight = listMaxHeight,
                        bottomSpacerHeight = bottomSpacerHeight,
                        onVideoSelected = onVideoSelected
                    )
                }
            }
        }
        ExternalPlaylistQueueSheetPresentation.MODAL -> {
            AppModalBottomSheet(
                onDismissRequest = onDismiss,
                containerColor = Color.Transparent,
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)
            ) {
                AppSurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(sheetShape)
                        .unifiedBlur(hazeState = hazeState, shape = sheetShape),
                    shape = sheetShape,
                    color = AppSurfaceTokens.surface().copy(alpha = 0.80f),
                    tonalElevation = 0.dp,
                    border = BorderStroke(
                        width = 0.6.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                    )
                ) {
                    ExternalPlaylistQueueSheetContent(
                        title = title,
                        playlist = playlist,
                        currentIndex = currentIndex,
                        listMaxHeight = listMaxHeight,
                        bottomSpacerHeight = bottomSpacerHeight,
                        onVideoSelected = onVideoSelected
                    )
                }
            }
        }
    }
}

@Composable
internal fun ExternalPlaylistQueueSheetContent(
    title: String,
    playlist: List<PlaylistItem>,
    currentIndex: Int,
    listMaxHeight: androidx.compose.ui.unit.Dp,
    bottomSpacerHeight: androidx.compose.ui.unit.Dp,
    onVideoSelected: (Int, PlaylistItem) -> Unit
) {
    val holderPlatform = com.bilipai.desktop.ui.LocalDesktopOriginalVideoHolderPlatform.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppText(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.weight(1f))
            AppText(
                text = "${playlist.size}个视频",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }

        AppHorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = listMaxHeight),
            contentPadding = PaddingValues(bottom = bottomSpacerHeight)
        ) {
            items(
                playlist.size,
                key = { index ->
                    val item = playlist[index]
                    resolveIndexedVideoLazyKey(
                        namespace = "video_playlist",
                        index = index,
                        bvid = item.bvid
                    )
                }
            ) { index ->
                val item = playlist[index]
                val selected = index == currentIndex
                val normalizedCoverUrl = normalizePlaylistCoverUrlForUi(item.cover)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (selected) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                            } else {
                                Color.Transparent
                            }
                        )
                        .clickable { onVideoSelected(index, item) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppText(
                        text = "${index + 1}",
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Box(
                        modifier = Modifier
                            .width(96.dp)
                            .height(54.dp)
                            .clip(AppShapes.container(ContainerLevel.Field))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    ) {
                        if (normalizedCoverUrl.isNotEmpty()) {
                            AsyncImage(
                                model = normalizedCoverUrl,
                                contentDescription = item.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                AppText(
                                    text = "无封面",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        AppText(
                            text = item.title,
                            maxLines = 1,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        AppText(
                            text = item.owner,
                            maxLines = 1,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (selected) {
                        AppIcon(
                            imageVector = rememberAppPlayIcon(),
                            contentDescription = "当前播放",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun DetachedVideoCommentThreadHost(
    visible: Boolean,
    successState: VideoPlaybackUiState.Success?,
    commentState: CommentUiState,
    commentViewModel: VideoCommentViewModel,
    forceInitialize: Boolean,
    viewModel: VideoPlaybackViewModel,
    onUpClick: (Long) -> Unit,
    onNavigateToRelatedVideo: (String) -> Unit,
    onSearchKeywordClick: (String) -> Unit,
    onOpenBilibiliLink: ((String) -> Unit)?,
    screenHeightPx: Int,
    topReservedPx: Int,
    onTimestampClick: (Long) -> Unit,
    onBackToTop: () -> Unit = {},
    onCoveredBlurProgressChange: ((Float) -> Unit)? = null,
) {
    val holderPlatform = com.bilipai.desktop.ui.LocalDesktopOriginalVideoHolderPlatform.current
    if (!visible) return

    val subReplyState by commentViewModel.subReplyState.collectAsStateWithLifecycle()

    VideoCommentSheetHost(
        mainSheetVisible = resolveVideoDetailCommentThreadHostMainSheetVisible(
            useEmbeddedPresentation = com.android.purebilibili.feature.video.ui.pager
                .shouldUseEmbeddedVideoSubReplyPresentation(),
            subReplyVisible = subReplyState.visible
        ),
        onDismiss = { commentViewModel.closeSubReply() },
        commentViewModel = commentViewModel,
        aid = successState?.info?.aid ?: 0L,
        upMid = commentState.upMid,
        expectedReplyCount = commentState.replyCount,
        emoteMap = successState?.emoteMap ?: emptyMap(),
        onRootCommentClick = { viewModel.openRootCommentComposer() },
        onReplyClick = { replyItem ->
            android.util.Log.d("VideoDetailScreen", "📝 Reply to: ${replyItem.member.uname}")
            viewModel.setReplyingTo(replyItem)
            viewModel.showCommentInputDialog()
        },
        onUserClick = onUpClick,
        onVideoClick = onNavigateToRelatedVideo,
        onSearchKeywordClick = onSearchKeywordClick,
        onOpenBilibiliLink = onOpenBilibiliLink,
        screenHeightPx = screenHeightPx,
        topReservedPx = topReservedPx,
        onTimestampClick = onTimestampClick,
        maxTimestampMs = successState?.videoDurationMs?.takeIf { it > 0L },
        onBackToTop = onBackToTop,
        forceInitialize = forceInitialize,
        handleFraudEvents = false,
        // 楼中楼（嵌入呈现）不盖全屏阴影：播放器上方保持可见，点背景关闭仍有效。
        maxScrimAlphaOverride = 0f,
        onCoveredBlurProgressChange = onCoveredBlurProgressChange
    )
}
