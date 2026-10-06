package com.android.purebilibili.feature.video.share
import com.android.purebilibili.core.ui.components.AppHorizontalDivider

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.ImageView
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.DynamicFeed
import com.android.purebilibili.core.store.TokenManager
import com.android.purebilibili.core.ui.components.AppIcon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.rememberModalBottomSheetState
import com.android.purebilibili.core.ui.components.AppText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.purebilibili.core.ui.AppModalBottomSheet
import com.android.purebilibili.core.ui.components.AppNativeSegmentedControl
import com.android.purebilibili.core.ui.components.AppSegmentOption
import com.android.purebilibili.core.ui.LocalAppThemeConfig
import com.android.purebilibili.feature.home.components.BottomBarLiquidSegmentedControl
import com.android.purebilibili.core.ui.common.copyPlainTextToClipboard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun VideoShareSheetHost(
    payload: VideoSharePayload?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    payload?.let { sharePayload ->
        VideoShareSheet(
            payload = sharePayload,
            onDismiss = onDismiss,
            modifier = modifier,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VideoShareSheet(
    payload: VideoSharePayload,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val shareScope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isLandscape = isLandscapeVideoShare()
    val sheetBounce = rememberVideoShareSheetBounce(sheetState, isLandscape)
    var sharingTarget by remember { mutableStateOf<VideoShareTarget?>(null) }
    var switchingSheet by remember { mutableStateOf(false) }
    var showFollowingPicker by remember { mutableStateOf(false) }
    var showDynamicComposer by remember { mutableStateOf(false) }
    var showMoreTargets by remember { mutableStateOf(false) }
    var moreShareMedia by remember { mutableStateOf<VideoShareCoverFile?>(null) }
    var shareStyle by remember { mutableStateOf(VideoShareStyle.LINK) }
    val neutralIconBackground = MaterialTheme.colorScheme.surfaceContainerHighest
    val neutralIconContent = MaterialTheme.colorScheme.onSurface
    val styleOptions = remember {
        listOf(
            AppSegmentOption(VideoShareStyle.LINK, "链接"),
            AppSegmentOption(VideoShareStyle.CARD, "卡片"),
        )
    }
    val items = listOf(
        VideoShareSheetItem(
            target = VideoShareTarget.BILIBILI_DYNAMIC,
            label = "分享到动态",
            iconText = null,
            iconVector = Icons.Outlined.DynamicFeed,
            backgroundColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        VideoShareSheetItem(
            target = VideoShareTarget.BILIBILI_FRIENDS,
            label = "B 站好友",
            iconText = null,
            iconVector = Icons.Outlined.People,
            backgroundColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        VideoShareSheetItem(
            target = VideoShareTarget.WECHAT,
            label = "微信",
            iconText = "微",
            iconVector = null,
            backgroundColor = Color(0xFF31C95B),
            contentColor = Color.White
        ),
        VideoShareSheetItem(
            target = VideoShareTarget.QQ,
            label = "QQ",
            iconText = "Q",
            iconVector = null,
            backgroundColor = Color(0xFF25A9F2),
            contentColor = Color.White
        ),
        VideoShareSheetItem(
            target = VideoShareTarget.COPY_LINK,
            label = "复制链接",
            iconText = null,
            iconVector = Icons.Outlined.Link,
            backgroundColor = neutralIconBackground,
            contentColor = neutralIconContent
        ),
        VideoShareSheetItem(
            target = VideoShareTarget.MORE,
            label = "更多",
            iconText = null,
            iconVector = Icons.Outlined.MoreHoriz,
            backgroundColor = neutralIconBackground,
            contentColor = neutralIconContent
        )
    )

    if (showDynamicComposer) {
        VideoShareToDynamicDialog(payload = payload, onDismiss = onDismiss)
        return
    }
    if (showFollowingPicker) {
        VideoShareToFollowingDialog(
            payload = payload,
            onDismiss = onDismiss,
            onSuccess = { count ->
                if (count > 0) VideoShareFeedbackEvents.prepared(payload.bvid)
                Toast.makeText(context, "已发送给 $count 位 B 站好友", Toast.LENGTH_SHORT).show()
            },
        )
        return
    }
    if (showMoreTargets) {
        VideoShareMoreTargetsSheet(
            shareMedia = moreShareMedia,
            onDismiss = onDismiss,
            onTargetClick = { target ->
                context.startTargetedVideoShare(
                    payload = payload,
                    packageName = target.packageName,
                    appName = target.label,
                    shareMedia = moreShareMedia,
                    preferredActivityClassName = target.activityClassName,
                )
                onDismiss()
            },
            onSystemChooserClick = {
                context.startMoreVideoShare(payload, moreShareMedia)
                onDismiss()
            },
        )
        return
    }

    AppModalBottomSheet(
        onDismissRequest = { if (!switchingSheet && sharingTarget == null) onDismiss() },
        modifier = modifier,
        presentationOverride = videoSharePresentation(isLandscape),
        sheetSurfaceModifier = sheetBounce,
        sheetState = sheetState,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            AppText(
                text = "分享",
                modifier = Modifier.padding(start = 20.dp, top = 22.dp, bottom = 14.dp),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
            )

            val segmentModifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth()
            if (LocalAppThemeConfig.current.liquidGlassEnabled) {
                BottomBarLiquidSegmentedControl(
                    items = styleOptions.map { it.label },
                    selectedIndex = shareStyle.ordinal,
                    onSelected = { shareStyle = VideoShareStyle.entries[it] },
                    modifier = segmentModifier,
                    forceEqualWidth = true,
                )
            } else {
                AppNativeSegmentedControl(
                    options = styleOptions,
                    selectedValue = shareStyle,
                    modifier = segmentModifier,
                    onSelectionChange = { shareStyle = it },
                )
            }
            AppText(
                text = if (shareStyle == VideoShareStyle.CARD) {
                    "以封面卡片图分享，并附上标题与二维码"
                } else {
                    "以标题 + 链接分享，方便直接打开"
                },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                items.forEach { item ->
                    VideoShareSheetItemView(
                        item = item,
                        onClick = {
                            if (sharingTarget != null || switchingSheet) return@VideoShareSheetItemView
                            when (item.target) {
                                VideoShareTarget.BILIBILI_DYNAMIC -> {
                                    if (sharingTarget != null || switchingSheet) return@VideoShareSheetItemView
                                    if (TokenManager.csrfCache.isNullOrBlank()) {
                                        Toast.makeText(context, "请先登录后分享到动态", Toast.LENGTH_SHORT).show()
                                        return@VideoShareSheetItemView
                                    }
                                    switchingSheet = true
                                    shareScope.launch {
                                        hideVideoShareSheet(sheetState)
                                        showDynamicComposer = true
                                    }
                                }
                                VideoShareTarget.BILIBILI_FRIENDS -> {
                                    if (sharingTarget != null) return@VideoShareSheetItemView
                                    switchingSheet = true
                                    shareScope.launch {
                                        hideVideoShareSheet(sheetState)
                                        showFollowingPicker = true
                                    }
                                }
                                VideoShareTarget.WECHAT,
                                VideoShareTarget.QQ -> {
                                    val packageName = item.target.packageName ?: return@VideoShareSheetItemView
                                    if (sharingTarget != null) return@VideoShareSheetItemView
                                    sharingTarget = item.target
                                    shareScope.launch {
                                        try {
                                            val shareMedia = prepareVideoShareMedia(
                                                context = context,
                                                payload = payload,
                                                style = shareStyle,
                                                progressMessage = "正在生成分享卡片",
                                            )
                                            hideVideoShareSheet(sheetState)
                                            context.startTargetedVideoShare(
                                                payload = payload,
                                                packageName = packageName,
                                                appName = item.label,
                                                shareMedia = shareMedia,
                                            )
                                        } finally {
                                            sharingTarget = null
                                            onDismiss()
                                        }
                                    }
                                }
                                VideoShareTarget.COPY_LINK -> {
                                    copyPlainTextToClipboard(context, payload.url, "视频链接")
                                    VideoShareFeedbackEvents.prepared(payload.bvid)
                                    Toast.makeText(context, "已复制链接", Toast.LENGTH_SHORT).show()
                                    shareScope.launch {
                                        hideVideoShareSheet(sheetState)
                                        onDismiss()
                                    }
                                }
                                VideoShareTarget.MORE -> {
                                    if (sharingTarget != null) return@VideoShareSheetItemView
                                    sharingTarget = item.target
                                    shareScope.launch {
                                        var openedLocalTargets = false
                                        try {
                                            val shareMedia = prepareVideoShareMedia(
                                                context = context,
                                                payload = payload,
                                                style = shareStyle,
                                                progressMessage = "正在生成分享卡片",
                                            )
                                            hideVideoShareSheet(sheetState)
                                            if (isLandscape) {
                                                moreShareMedia = shareMedia
                                                showMoreTargets = true
                                                openedLocalTargets = true
                                            } else {
                                                context.startMoreVideoShare(payload, shareMedia)
                                            }
                                        } finally {
                                            sharingTarget = null
                                            if (!openedLocalTargets) onDismiss()
                                        }
                                    }
                                }
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            AppHorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clickable {
                        shareScope.launch {
                            hideVideoShareSheet(sheetState)
                            onDismiss()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                AppText(
                    text = "取消",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium)
                )
            }
        }
    }
}

@Immutable
private data class VideoShareSheetItem(
    val target: VideoShareTarget,
    val label: String,
    val iconText: String?,
    val iconVector: ImageVector?,
    val backgroundColor: Color,
    val contentColor: Color,
)

@Composable
private fun VideoShareSheetItemView(
    item: VideoShareSheetItem,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val appIcon = remember(context, item.target) {
        item.target.packageName?.let { packageName ->
            try {
                context.packageManager.getApplicationIcon(packageName)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }
    }
    Column(
        modifier = Modifier.width(72.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (appIcon == null) item.backgroundColor else Color.Transparent)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (appIcon != null) {
                AndroidView(
                    factory = { viewContext -> ImageView(viewContext).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    } },
                    update = { it.setImageDrawable(appIcon) },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape),
                )
            } else if (item.iconVector != null) {
                if (item.target == VideoShareTarget.BILIBILI_FRIENDS) {
                    Icon(
                        imageVector = item.iconVector,
                        contentDescription = item.label,
                        modifier = Modifier.size(28.dp),
                        tint = item.contentColor,
                    )
                } else {
                    AppIcon(
                        imageVector = item.iconVector,
                        contentDescription = item.label,
                        modifier = Modifier.size(28.dp),
                        tint = item.contentColor,
                    )
                }
            } else {
                AppText(
                    text = item.iconText.orEmpty(),
                    color = item.contentColor,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        AppText(
            text = item.label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
internal suspend fun hideVideoShareSheet(
    sheetState: androidx.compose.material3.SheetState
) {
    try {
        if (sheetState.isVisible) {
            sheetState.hide()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // A disappearing host can make hide fail; the caller still completes its action.
    }
}

/**
 * 按分享形态准备媒体：链接模式不附图；卡片模式合成卡片图，失败时回退空（走链接）。
 */
private suspend fun prepareVideoShareMedia(
    context: Context,
    payload: VideoSharePayload,
    style: VideoShareStyle,
    progressMessage: String,
): VideoShareCoverFile? {
    return when (style) {
        VideoShareStyle.LINK -> null
        VideoShareStyle.CARD -> {
            Toast.makeText(context, progressMessage, Toast.LENGTH_SHORT).show()
            val cardFile = prepareVideoShareCardFile(context, payload)
            if (cardFile == null) {
                Toast.makeText(context, "卡片生成失败，已改用链接分享", Toast.LENGTH_SHORT).show()
            }
            cardFile
        }
    }
}

private fun Context.startTargetedVideoShare(
    payload: VideoSharePayload,
    packageName: String,
    appName: String,
    shareMedia: VideoShareCoverFile?,
    preferredActivityClassName: String? = null,
) {
    try {
        val mimeType = shareMedia?.mimeType ?: "text/plain"
        val activityClassName = preferredActivityClassName ?: resolveShareActivityClassName(
            packageName = packageName,
            mimeType = mimeType,
        )
        val intent = if (shareMedia != null) {
            buildVideoCoverShareIntent(
                payload = payload,
                coverUri = shareMedia.uri,
                mimeType = shareMedia.mimeType,
                packageName = packageName,
                activityClassName = activityClassName,
                contentResolver = contentResolver
            )
        } else {
            buildTargetedShareIntent(
                payload = payload,
                packageName = packageName,
                activityClassName = activityClassName,
            )
        }
        startActivityWithTaskFlag(intent)
        VideoShareFeedbackEvents.prepared(payload.bvid)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, "未安装$appName", Toast.LENGTH_SHORT).show()
    } catch (_: Exception) {
        Toast.makeText(this, "无法打开$appName", Toast.LENGTH_SHORT).show()
    }
}

private fun Context.startMoreVideoShare(
    payload: VideoSharePayload,
    shareMedia: VideoShareCoverFile?,
) {
    try {
        val sendIntent = if (shareMedia != null) {
            buildVideoCoverShareIntent(
                payload = payload,
                coverUri = shareMedia.uri,
                mimeType = shareMedia.mimeType,
                contentResolver = contentResolver
            )
        } else {
            buildVideoShareIntent(payload)
        }
        val chooser = Intent.createChooser(sendIntent, resolveVideoShareChooserTitle(payload))
        if (shareMedia != null) {
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityWithTaskFlag(chooser)
        VideoShareFeedbackEvents.prepared(payload.bvid)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, "无法打开分享面板", Toast.LENGTH_SHORT).show()
    } catch (_: Exception) {
        Toast.makeText(this, "分享失败", Toast.LENGTH_SHORT).show()
    }
}

private fun Context.startActivityWithTaskFlag(intent: Intent) {
    if (this !is Activity) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    startActivity(intent)
}

/**
 * 锁定「发给好友 / 选一个聊天」入口，避免同包多 Activity 触发系统 Resolver。
 */
private fun Context.resolveShareActivityClassName(
    packageName: String,
    mimeType: String,
): String? {
    val query = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        setPackage(packageName)
    }
    val resolveInfos = packageManager.queryIntentActivities(query, 0)
    if (resolveInfos.isEmpty()) return null
    val candidates = resolveInfos.map { info ->
        ShareActivityCandidate(
            packageName = info.activityInfo.packageName,
            className = info.activityInfo.name,
            label = info.loadLabel(packageManager).toString(),
        )
    }
    return resolvePreferredShareActivity(candidates)?.className
}
