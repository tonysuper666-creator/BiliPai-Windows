package com.android.purebilibili.feature.settings

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.android.purebilibili.core.ui.AdaptiveLoadingIndicator
import com.android.purebilibili.core.ui.AppShapes
import com.android.purebilibili.core.ui.AppPopupSurface
import com.android.purebilibili.core.ui.AppPopupSurfaceType
import com.android.purebilibili.core.ui.ContainerLevel
import com.android.purebilibili.core.ui.motion.AppMotionTokens
import com.android.purebilibili.core.ui.blur.hazeEffectCompat
import com.android.purebilibili.core.ui.components.AppButton
import com.android.purebilibili.core.ui.components.AppCheckbox
import com.android.purebilibili.core.ui.components.AppCheckboxDefaults
import com.android.purebilibili.core.ui.components.AppCircularProgressIndicator
import com.android.purebilibili.core.ui.components.AppIcon
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.core.ui.components.AppTextButton
import com.android.purebilibili.core.util.CacheClearTarget
import com.android.purebilibili.core.util.CacheUtils
import kotlin.math.min

/**
 * 构造饱和度色彩矩阵（Rec.709 亮度加权），> 1 提升饱和度，用于模糊背景的 vibrancy 效果。
 * 项目所用 Compose 版本的 ui-graphics 没有 ColorMatrix.saturation 扩展，需手工构造。
 */
private fun saturationColorMatrix(saturation: Float): ColorMatrix {
    val inv = 1f - saturation
    val lumR = 0.213f
    val lumG = 0.715f
    val lumB = 0.072f
    return ColorMatrix(
        floatArrayOf(
            lumR * inv + saturation, lumG * inv, lumB * inv, 0f, 0f,
            lumR * inv, lumG * inv + saturation, lumB * inv, 0f, 0f,
            lumR * inv, lumG * inv, lumB * inv + saturation, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    )
}

data class CacheClearProgress(
    val current: Long,
    val total: Long,
    val isComplete: Boolean = false,
    val clearedSize: String = ""
)

@Composable
internal fun CacheClearConfirmDialog(
    breakdown: CacheUtils.CacheBreakdown?,
    selectedCacheSizeSummary: String,
    options: List<CacheClearOptionUiModel>,
    selectedTargets: Set<CacheClearTarget>,
    onTargetToggle: (CacheClearTarget, Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val segments = remember(breakdown, selectedTargets, options) {
        resolveCacheClearDonutSegments(
            breakdown = breakdown,
            selectedTargets = selectedTargets,
            options = options
        )
    }
    val colorScheme = MaterialTheme.colorScheme
    val segmentColors = remember(colorScheme) {
        listOf(
            colorScheme.primary,
            colorScheme.tertiary,
            colorScheme.secondary,
            colorScheme.error,
            colorScheme.primaryContainer,
            colorScheme.tertiaryContainer
        )
    }
    val centerSize = resolveCacheClearDonutCenterSize(breakdown, selectedTargets)
    val buttonLabel = resolveCacheClearButtonLabel(breakdown, selectedTargets)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        com.android.purebilibili.core.ui.ModalWindowBlurBehindEffect(enabled = true)
        AppPopupSurface(
            type = AppPopupSurfaceType.DIALOG,
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .widthIn(max = 480.dp)
                .fillMaxWidth(),
            shape = AppShapes.container(ContainerLevel.Dialog),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CacheClearUsageDonut(
                    segments = segments,
                    colors = segmentColors,
                    centerSize = centerSize,
                    onSegmentClick = { target ->
                        onTargetToggle(target, target !in selectedTargets)
                    }
                )
                Spacer(modifier = Modifier.height(16.dp))
                AppText(
                    text = "存储使用情况",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                AppText(
                    text = selectedCacheSizeSummary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                AppText(
                    text = resolveCacheClearConfirmationMessage(selectedTargets),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                segments.forEach { segment ->
                    val color = segmentColors[segment.colorIndex % segmentColors.size]
                    CacheClearCategoryRow(
                        segment = segment,
                        color = color,
                        onToggle = { checked -> onTargetToggle(segment.target, checked) }
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
                AppButton(
                    onClick = onConfirm,
                    enabled = selectedTargets.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    AppText(buttonLabel)
                }
                AppTextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    AppText("取消")
                }
            }
        }
    }
}

@Composable
private fun CacheClearUsageDonut(
    segments: List<CacheClearDonutSegment>,
    colors: List<Color>,
    centerSize: String,
    onSegmentClick: (CacheClearTarget) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(220.dp)
            .semantics {
                contentDescription = "缓存占用圆环，点击扇区可选择要清理的类型"
            }
            .pointerInput(segments) {
                detectTapGestures { offset ->
                    val radius = min(size.width, size.height) / 2f
                    val dx = offset.x - size.width / 2f
                    val dy = offset.y - size.height / 2f
                    val target = resolveCacheClearDonutHitTarget(
                        segments = segments,
                        dx = dx,
                        dy = dy,
                        innerRadius = radius * 0.58f,
                        outerRadius = radius
                    )
                    if (target != null) onSegmentClick(target)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val donutSpec = AppMotionTokens.spatialSpec<Float>()
        val sweepStates = segments.map { segment ->
            animateFloatAsState(
                targetValue = segment.sweepAngle,
                animationSpec = donutSpec,
                label = "cacheDonutSweep-${segment.target}"
            )
        }
        val startStates = segments.map { segment ->
            animateFloatAsState(
                targetValue = segment.startAngle,
                animationSpec = donutSpec,
                label = "cacheDonutStart-${segment.target}"
            )
        }
        val trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.18f
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Butt)
            )
            segments.forEachIndexed { index, segment ->
                val sweep = sweepStates[index].value
                if (sweep <= 0.5f) return@forEachIndexed
                val start = startStates[index].value
                val color = colors[segment.colorIndex % colors.size]
                val gap = if (sweep > 8f) 3f else 0f
                drawArc(
                    color = color,
                    startAngle = start + gap / 2f,
                    sweepAngle = (sweep - gap).coerceAtLeast(0.5f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Butt)
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AppText(
                text = "BiliPai",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AppText(
                text = centerSize,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun CacheClearCategoryRow(
    segment: CacheClearDonutSegment,
    color: Color,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(AppShapes.container(ContainerLevel.Card))
            .clickable { onToggle(!segment.selected) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppCheckbox(
            checked = segment.selected,
            onCheckedChange = onToggle,
            colors = AppCheckboxDefaults.colors(
                checkedColor = color,
                uncheckedColor = color.copy(alpha = 0.6f)
            )
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            AppText(
                text = segment.title,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium
            )
            AppText(
                text = segment.percentLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
        AppText(
            text = formatCacheClearBytes(segment.bytes),
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun CacheClearAnimationDialog(
    progress: CacheClearProgress,
    onDismiss: () -> Unit,
    hazeState: dev.chrisbanes.haze.HazeState? = null,
) {
    val window = com.android.purebilibili.core.util.LocalAppWindowAdaptiveInfo.current.windowSizeClass
    val characterSize = if (window.heightDp < 480.dp) 96.dp else 120.dp
    val maxSheetHeight = (window.heightDp * 0.8f).coerceAtLeast(96.dp)
    val progressValue = if (progress.total > 0) {
        (progress.current.toFloat() / progress.total.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val animatedProgress by animateFloatAsState(
        targetValue = if (progress.isComplete) 1f else progressValue,
        label = "cacheClearProgress"
    )

    val latestOnDismiss by androidx.compose.runtime.rememberUpdatedState(onDismiss)
    LaunchedEffect(progress.isComplete) {
        if (progress.isComplete) {
            kotlinx.coroutines.delay(2000L)
            latestOnDismiss()
        }
    }
    androidx.activity.compose.BackHandler(enabled = progress.isComplete) {
        latestOnDismiss()
    }

    // 窗口内覆盖层（而非独立 Dialog 窗口），才能对页面内容做同窗口模糊。
    val overlayState = remember {
        androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true }
    }
    val slideSpec = AppMotionTokens.spatialSpec<androidx.compose.ui.unit.IntOffset>()
    val fadeSpec = AppMotionTokens.spatialSpec<Float>()
    val useBackgroundBlur = hazeState != null &&
        com.android.purebilibili.core.ui.blur.shouldAllowRuntimeShaderBackedHazeEffect(
            Build.VERSION.SDK_INT
        )

    Box(modifier = Modifier.fillMaxSize()) {
        // 背景整体模糊 + 压暗；清理期间不响应点击，与旧 Dialog 行为一致。
        androidx.compose.animation.AnimatedVisibility(
            visibleState = overlayState,
            enter = androidx.compose.animation.fadeIn(fadeSpec),
            exit = androidx.compose.animation.fadeOut(fadeSpec),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (useBackgroundBlur && hazeState != null) {
                            // 纯模糊 + 饱和度提升（iOS vibrancy）：模糊后颜色更通透，
                            // 不叠加色调层，保留背景原本的明暗与色彩层次
                            Modifier.hazeEffectCompat(
                                state = hazeState,
                                style = dev.chrisbanes.haze.blur.HazeBlurStyle(
                                    backgroundColor = Color.Transparent,
                                    colorEffects = listOf(
                                        dev.chrisbanes.haze.blur.HazeColorEffect.ColorFilter(
                                            androidx.compose.ui.graphics.ColorFilter.colorMatrix(
                                                saturationColorMatrix(1.6f)
                                            )
                                        )
                                    ),
                                    noiseFactor = 0f,
                                ),
                            )
                        } else {
                            Modifier
                        }
                    )
                    .background(Color.Black.copy(alpha = 0.08f))
            )
        }

        // 弹窗自下而上弹出
        androidx.compose.animation.AnimatedVisibility(
            visibleState = overlayState,
            enter = androidx.compose.animation.slideInVertically(slideSpec) { it } +
                androidx.compose.animation.fadeIn(fadeSpec),
            exit = androidx.compose.animation.slideOutVertically(slideSpec) { it } +
                androidx.compose.animation.fadeOut(fadeSpec),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val successSize = characterSize
            AppPopupSurface(
                type = AppPopupSurfaceType.SHEET,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 480.dp)
                    .heightIn(max = maxSheetHeight)
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp),
                shape = AppShapes.container(ContainerLevel.Sheet),
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier.size(successSize),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            progress.isComplete -> {
                                CacheClearSuccessAnimation(size = successSize)
                            }

                            else -> {
                                com.android.purebilibili.core.ui.BlueSnowMaidAnimation(
                                    animation = com.android.purebilibili.core.ui.MaidAnimation.CLEANING,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                    if (!progress.isComplete) {
                        if (progress.total > 0L) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AppCircularProgressIndicator(
                                    progress = { animatedProgress },
                                    modifier = Modifier.size(20.dp)
                                )
                                AppText(
                                    text = "${(progressValue * 100).toInt()}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            AdaptiveLoadingIndicator(size = 20.dp)
                        }
                    }
                    AppText(
                        text = if (progress.isComplete) "清理完成" else "正在清理",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    AppText(
                        text = if (progress.clearedSize.isNotEmpty()) {
                            if (progress.isComplete) "共释放 ${progress.clearedSize}"
                            else "已清理 ${progress.clearedSize}"
                        } else {
                            "准备中…"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (progress.isComplete) {
                        AppText(
                            text = "即将自动关闭…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CacheClearSuccessAnimation(size: Dp = 144.dp, modifier: Modifier = Modifier) {
    // 完成勾由 CLEAN_COMPLETE 动画自身在收尾时给出（品牌约定），不再叠加静态勾图标，
    // 否则动画播放到末帧时会出现两个勾。
    com.android.purebilibili.core.ui.BlueSnowMaidAnimation(
        animation = com.android.purebilibili.core.ui.MaidAnimation.CLEAN_COMPLETE,
        modifier = modifier.size(size)
    )
}
