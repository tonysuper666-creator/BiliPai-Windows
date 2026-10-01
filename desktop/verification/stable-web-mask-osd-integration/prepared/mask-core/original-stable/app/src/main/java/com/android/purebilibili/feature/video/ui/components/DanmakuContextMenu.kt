package com.android.purebilibili.feature.video.ui.components
import com.android.purebilibili.core.ui.components.AppHorizontalDivider
import androidx.compose.material3.MaterialTheme

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import com.android.purebilibili.core.ui.components.AppIcon
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.core.ui.components.AppIconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.filled.SelectAll
import com.android.purebilibili.core.ui.common.copyPlainTextToClipboard
import com.android.purebilibili.core.ui.common.TextSelectionBottomSheet
import com.android.purebilibili.core.ui.AppShapes
import com.android.purebilibili.core.ui.ContainerLevel

// iOS Visual Styles
private val MenuBackground = Color(0xCC1C1C1E) // Translucent Black
private val SeparatorColor = Color(0xFF38383A)
private val DestructiveColor = Color(0xFFFF453A) // System Red
private val PrimaryColor = Color(0xFF0A84FF) // System Blue

internal enum class DanmakuBlockActionTarget {
    KEYWORD,
    USER
}

private enum class DanmakuContextMenuPage {
    MAIN,
    REPORT,
    RECALL_CONFIRM
}

internal fun resolveDanmakuRecallConfirmationPreview(
    text: String,
    maxLength: Int = 15
): String {
    val normalized = text.trim().replace(Regex("\\s+"), " ")
    if (normalized.length <= maxLength) return normalized
    return normalized.take(maxLength) + "..."
}

internal fun resolveDanmakuBlockActionFeedbackMessage(
    target: DanmakuBlockActionTarget,
    changed: Boolean
): String {
    return when (target) {
        DanmakuBlockActionTarget.KEYWORD -> {
            if (changed) "已加入屏蔽词，可在屏蔽管理里编辑" else "该屏蔽词已存在"
        }
        DanmakuBlockActionTarget.USER -> {
            if (changed) "已屏蔽该发送者，可在屏蔽管理里编辑" else "该发送者已在屏蔽列表中"
        }
    }
}

/** 举报原因（label, API reason code），弹幕点按菜单与弹幕列表共用 */
internal val DanmakuReportReasons: List<Pair<String, Int>> = listOf(
    "违法违禁" to 1,
    "色情低俗" to 2,
    "赌博诈骗" to 3,
    "引战" to 4,
    "人身攻击" to 5,
    "剧透" to 6,
    "刷屏" to 7,
    "其他" to 8
)

private val DanmakuTimestampJumpRegex = Regex("(\\d{1,2}):(\\d{2})(?::(\\d{2}))?")

/**
 * 从弹幕文本中解析可跳转的时间戳（mm:ss 或 h:mm:ss），返回目标位置毫秒数；
 * 无匹配或分秒越界时返回 null。
 */
internal fun resolveDanmakuTimestampJumpMs(text: String): Long? {
    val match = DanmakuTimestampJumpRegex.find(text) ?: return null
    val first = match.groupValues[1].toInt()
    val second = match.groupValues[2].toInt()
    val third = match.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()
    return when (third) {
        null -> if (second < 60) (first * 60L + second) * 1000L else null
        else -> if (second < 60 && third < 60) ((first * 60L + second) * 60L + third) * 1000L else null
    }
}

@Composable
fun DanmakuContextMenu(
    text: String,
    onDismiss: () -> Unit,
    onLike: () -> Unit,
    onRecall: () -> Unit,
    canRecall: Boolean = false,
    onReport: (reason: Int) -> Unit,
    voteCount: Int = 0,
    hasLiked: Boolean = false,
    voteLoading: Boolean = false,
    canVote: Boolean = false,
    timestampJumpMs: Long? = null,
    onSeekToTimestamp: (Long) -> Unit = {},
    onBlockKeyword: () -> Unit = {},
    canBlockKeyword: Boolean = true,
    canBlockUser: Boolean = true,
    onBlockUser: () -> Unit = {}
) {
    val context = LocalContext.current
    var currentPage by remember { mutableStateOf(DanmakuContextMenuPage.MAIN) }
    var showTextSelectionSheet by remember { mutableStateOf(false) }

    if (showTextSelectionSheet) {
        TextSelectionBottomSheet(
            text = text,
            title = "选择弹幕内容",
            onDismiss = {
                showTextSelectionSheet = false
                onDismiss()
            }
        )
    } else {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    ),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = currentPage,
                    transitionSpec = {
                        (fadeIn() + slideInVertically { it / 2 }).togetherWith(fadeOut() + slideOutVertically { it / 2 })
                    },
                    label = "MenuTransition"
                ) { page ->
                    when (page) {
                        DanmakuContextMenuPage.REPORT -> ReportReasonMenu(
                            onSelectReason = { reason ->
                                onReport(reason)
                                onDismiss()
                            },
                            onBack = { currentPage = DanmakuContextMenuPage.MAIN }
                        )
                        DanmakuContextMenuPage.RECALL_CONFIRM -> RecallConfirmMenu(
                            previewText = resolveDanmakuRecallConfirmationPreview(text),
                            onBack = { currentPage = DanmakuContextMenuPage.MAIN },
                            onConfirm = {
                                onRecall()
                                onDismiss()
                            }
                        )
                        DanmakuContextMenuPage.MAIN -> MainMenu(
                            text = text,
                            voteCount = voteCount,
                            hasLiked = hasLiked,
                            voteLoading = voteLoading,
                            canVote = canVote,
                            canRecall = canRecall,
                            timestampJumpMs = timestampJumpMs,
                            onSeekToTimestamp = {
                                onSeekToTimestamp(it)
                                onDismiss()
                            },
                            onLike = {
                                onLike()
                                onDismiss()
                            },
                            onRecall = {
                                currentPage = DanmakuContextMenuPage.RECALL_CONFIRM
                            },
                            onReportClick = { currentPage = DanmakuContextMenuPage.REPORT },
                            onCopy = {
                                copyPlainTextToClipboard(context, text, "弹幕")
                                onDismiss()
                            },
                            onSelectText = {
                                showTextSelectionSheet = true
                            },
                            onBlockKeyword = {
                                onBlockKeyword()
                                onDismiss()
                            },
                            canBlockKeyword = canBlockKeyword,
                            canBlockUser = canBlockUser,
                            onBlockUser = {
                                onBlockUser()
                                onDismiss()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecallConfirmMenu(
    previewText: String,
    onBack: () -> Unit,
    onConfirm: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(280.dp)
            .clip(AppShapes.container(ContainerLevel.Dialog))
            .background(MenuBackground),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            AppIconButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                AppIcon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = PrimaryColor,
                    modifier = Modifier.size(24.dp),
                )
            }
            AppText(
                text = "确认撤回",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.align(Alignment.Center)
            )
        }

        MenuSeparator()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AppText(
                text = "撤回后不可恢复",
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
            )
            Spacer(modifier = Modifier.height(8.dp))
            AppText(
                text = "确认撤回这条弹幕？",
                color = Color.White.copy(alpha = 0.82f),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            AppText(
                text = previewText,
                color = Color.White.copy(alpha = 0.62f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }

        MenuSeparator()

        MenuItem(
            label = "撤回弹幕",
            icon = Icons.AutoMirrored.Filled.Reply,
            color = DestructiveColor,
            onClick = onConfirm
        )
    }
}

@Composable
private fun MainMenu(
    text: String,
    voteCount: Int,
    hasLiked: Boolean,
    voteLoading: Boolean,
    canVote: Boolean,
    canRecall: Boolean,
    timestampJumpMs: Long? = null,
    onSeekToTimestamp: (Long) -> Unit = {},
    onLike: () -> Unit,
    onRecall: () -> Unit,
    onReportClick: () -> Unit,
    onCopy: () -> Unit,
    onSelectText: () -> Unit,
    onBlockKeyword: () -> Unit,
    canBlockKeyword: Boolean,
    canBlockUser: Boolean,
    onBlockUser: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(280.dp)
            .clip(AppShapes.container(ContainerLevel.Dialog))
            .background(MenuBackground)
            .padding(vertical = 0.dp), // iOS menus often have no outer padding inside the rounded container
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Preview Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AppText(
                text = "弹幕内容",
                color = Color.White.copy(0.5f),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium)
            )
            Spacer(modifier = Modifier.height(4.dp))
            SelectionContainer {
                AppText(
                    text = text,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }

        MenuSeparator()

        val voteLabel = when {
            voteLoading -> "加载点赞状态..."
            !canVote -> "当前弹幕不支持点赞"
            hasLiked -> "取消点赞 (${formatVoteCount(voteCount)})"
            else -> "点赞弹幕 (${formatVoteCount(voteCount)})"
        }
        MenuItem(
            label = voteLabel,
            icon = Icons.Filled.ThumbUp,
            enabled = canVote && !voteLoading,
            onClick = onLike
        )
        
        MenuSeparator()
        
        MenuItem(
            label = "复制全部",
            icon = Icons.Filled.ContentCopy,
            onClick = onCopy
        )

        MenuSeparator()

        if (timestampJumpMs != null) {
            MenuItem(
                label = "跳转到 ${formatDanmakuTimestampJumpLabel(timestampJumpMs)}",
                icon = Icons.Filled.PlayArrow,
                onClick = { onSeekToTimestamp(timestampJumpMs) }
            )
            MenuSeparator()
        }

        MenuItem(
            label = "选择内容",
            icon = Icons.Filled.SelectAll,
            onClick = onSelectText
        )

        MenuSeparator()

        MenuItem(
            label = "加入屏蔽词",
            icon = Icons.Filled.Block,
            enabled = canBlockKeyword,
            onClick = onBlockKeyword
        )

        MenuSeparator()

        if (canRecall) {
            // 仅自己的弹幕才允许撤回
            MenuItem(
                label = "撤回弹幕",
                icon = Icons.AutoMirrored.Filled.Reply,
                onClick = onRecall
            )
            MenuSeparator()
        }
        
        MenuItem(
            label = "屏蔽发送者",
            icon = Icons.Filled.Block,
            enabled = canBlockUser,
            onClick = onBlockUser
        )

        MenuSeparator()

        MenuItem(
            label = "举报弹幕",
            icon = Icons.Filled.Report,
            color = DestructiveColor, // Red for Report
            onClick = onReportClick
        )
    }
}

@Composable
private fun ReportReasonMenu(
    onSelectReason: (Int) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(280.dp)
            .clip(AppShapes.container(ContainerLevel.Dialog))
            .background(MenuBackground),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header with Back
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            AppIconButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                AppIcon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = PrimaryColor,
                    modifier = Modifier.size(24.dp),
                )
            }
            AppText(
                text = "举报原因",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.align(Alignment.Center)
            )
        }

        MenuSeparator()

        val reasons = DanmakuReportReasons

        reasons.forEachIndexed { index, (label, code) ->
            MenuItem(
                label = label,
                centered = true, // Center text for options
                onClick = { onSelectReason(code) }
            )
            if (index < reasons.lastIndex) {
                MenuSeparator()
            }
        }
    }
}

private fun formatVoteCount(rawCount: Int): String {
    val count = rawCount.coerceAtLeast(0)
    if (count < 10_000) return count.toString()
    val compact = ((count / 1000) / 10f).toString().removeSuffix(".0")
    return "${compact}万"
}

private fun formatDanmakuTimestampJumpLabel(timeMs: Long): String {
    val totalSeconds = timeMs / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

@Composable
private fun MenuItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    color: Color = Color.White,
    centered: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val clickModifier = if (enabled) Modifier.clickable(onClick = onClick) else Modifier
    val displayColor = if (enabled) color else color.copy(alpha = 0.45f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(clickModifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = if (centered) Arrangement.Center else Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppText(
            text = label,
            color = displayColor,
            style = MaterialTheme.typography.bodyLarge
        )
        if (icon != null) {
            AppIcon(
                imageVector = icon,
                contentDescription = null,
                tint = displayColor,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun MenuSeparator() {
    AppHorizontalDivider(
        color = SeparatorColor,
        thickness = 0.5.dp
    )
}
