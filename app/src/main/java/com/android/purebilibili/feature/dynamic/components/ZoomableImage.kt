package com.android.purebilibili.feature.dynamic.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import coil3.ImageLoader
import coil3.compose.AsyncImage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal const val ZOOMABLE_IMAGE_TAG = "zoomable_image"

/**
 * 可缩放的图片组件
 * - 支持双指缩放
 * - 支持双击放大
 * - 支持长图滑动
 * - 自动处理边界限制
 */
@Composable
fun ZoomableImage(
    model: Any?,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    onZoomChange: (Float) -> Unit = {},
    // Reports image bounds in this viewport, before any parent layer transforms.
    onDisplayRectChange: (Rect?) -> Unit = {},
    onVerticalDismissDragStart: () -> Unit = {},
    onVerticalDismissDrag: (Offset) -> Unit = {},
    onVerticalDismissDragEnd: (Float) -> Unit = {},
    onVerticalDismissDragCancel: () -> Unit = {},
    onExtremeAspectRatioDetected: () -> Unit = {},
    onLongPress: () -> Unit = {},
    onClick: () -> Unit = {},
    resetZoomTrigger: Int = 0,
    gesturesEnabled: Boolean = true,
    displayRectTrackingEnabled: Boolean = true,
) {
    // Gesture coroutines outlive recomposition; always use the current host callbacks.
    val latestOnZoomChange by rememberUpdatedState(onZoomChange)
    val latestOnDisplayRectChange by rememberUpdatedState(onDisplayRectChange)
    val latestOnDragStart by rememberUpdatedState(onVerticalDismissDragStart)
    val latestOnDrag by rememberUpdatedState(onVerticalDismissDrag)
    val latestOnDragEnd by rememberUpdatedState(onVerticalDismissDragEnd)
    val latestOnDragCancel by rememberUpdatedState(onVerticalDismissDragCancel)
    val latestOnLongPress by rememberUpdatedState(onLongPress)
    val latestOnClick by rememberUpdatedState(onClick)
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    
    // 图片原始尺寸
    var imageSize by remember { mutableStateOf(IntSize.Zero) }
    // 容器尺寸
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(containerSize, imageSize, scale, offsetX, offsetY, displayRectTrackingEnabled) {
        if (displayRectTrackingEnabled) {
            latestOnDisplayRectChange(
                resolveZoomableImageLocalDisplayRect(imageSize, containerSize, scale, offsetX, offsetY)
            )
        }
    }
    
    // 外部触发的缩放复位(放大态退出预回弹):把 scale/offset 动画回 fit。
    // 逐帧写状态,宿主可经 onZoomChange 观察 activeZoomScale 到达 1。
    LaunchedEffect(resetZoomTrigger) {
        if (resetZoomTrigger <= 0 || scale <= 1f) return@LaunchedEffect
        val startScale = scale
        val startOffsetX = offsetX
        val startOffsetY = offsetY
        val resetAnim = androidx.compose.animation.core.Animatable(0f)
        resetAnim.animateTo(
            targetValue = 1f,
            animationSpec = androidx.compose.animation.core.tween(
                durationMillis = 180,
                easing = androidx.compose.animation.core.CubicBezierEasing(0f, 0f, 0.58f, 1f)
            )
        ) {
            // 本项目 Compose 版本的 animateTo block 是 Animatable 接收者 lambda,
            // value 即 this.value(当前动画值 0f..1f)。
            val progress = value
            scale = startScale + (1f - startScale) * progress
            offsetX = startOffsetX * (1f - progress)
            offsetY = startOffsetY * (1f - progress)
            latestOnZoomChange(scale)
        }
        scale = 1f
        offsetX = 0f
        offsetY = 0f
        latestOnZoomChange(1f)
    }

    // 双击放大逻辑
    fun onDoubleTap(tapOffset: Offset) {
        if (scale > 1f) {
            // 恢复原大小
            scale = 1f
            offsetX = 0f
            offsetY = 0f
            latestOnZoomChange(1f)
        } else {
            val scaleLimits = resolveZoomableImageScaleLimits(
                imageWidth = imageSize.width,
                imageHeight = imageSize.height,
                containerWidth = containerSize.width,
                containerHeight = containerSize.height
            )
            // 普通图片保持 2.5 倍；长条图片直接放到短边铺满视口，避免双击后仍然看不清。
            scale = scaleLimits.doubleTapScale
            
            // 计算偏移量，使点击点居中
            if (containerSize != IntSize.Zero) {
                val centerX = containerSize.width / 2f
                val centerY = containerSize.height / 2f
                
                offsetX = (centerX - tapOffset.x) * (scale - 1)
                offsetY = (centerY - tapOffset.y) * (scale - 1)
                
                // 边界限制
                val fitScale = min(
                    containerSize.width.toFloat() / imageSize.width,
                    containerSize.height.toFloat() / imageSize.height
                )
                val displayWidth = imageSize.width * fitScale * scale
                val displayHeight = imageSize.height * fitScale * scale
                
                val maxOffsetX = max(0f, (displayWidth - containerSize.width) / 2f)
                val maxOffsetY = max(0f, (displayHeight - containerSize.height) / 2f)
                
                offsetX = offsetX.coerceIn(-maxOffsetX, maxOffsetX)
                offsetY = offsetY.coerceIn(-maxOffsetY, maxOffsetY)
            }
            latestOnZoomChange(scale)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
            .pointerInput(gesturesEnabled) {
                if (!gesturesEnabled) return@pointerInput
                detectTapGestures(
                    onDoubleTap = { onDoubleTap(it) },
                    onLongPress = { latestOnLongPress() },
                    onTap = { latestOnClick() }
                )
            }
            .pointerInput(gesturesEnabled) {
                if (!gesturesEnabled) return@pointerInput
                // 手势监听：缩放 + 拖拽
                awaitEachGesture {
                    var zoom = 1f
                    var pan = Offset.Zero
                    var pastTouchSlop = false
                    val touchSlop = viewConfiguration.touchSlop
                    var isMultiTouch = false
                    var gestureMode = ZoomableImageGestureMode.UNDECIDED
                    var verticalDismissStarted = false
                    var gestureCanceled = false
                    // 竖滑松手速度（px/s）：EMA 平滑瞬时速度，供回位动画延续手势动量。
                    var lastVerticalDragTimeMs = 0L
                    var verticalDragVelocityY = 0f
                    
                    awaitFirstDown(requireUnconsumed = false)
                    
                    do {
                        val event = awaitPointerEvent()
                        val canceled = event.changes.any { it.isConsumed }
                        if (canceled) {
                            gestureCanceled = true
                            break
                        }

                        if (event.changes.size > 1) {
                            isMultiTouch = true
                        }

                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()

                        if (!pastTouchSlop) {
                            zoom *= zoomChange
                            pan += panChange

                            val centroidSize = event.calculateCentroidSize(useCurrent = false)
                            val zoomMotion = abs(1 - zoom) * centroidSize
                            val panMotion = pan.getDistance()

                            if (zoomMotion > touchSlop || panMotion > touchSlop) {
                                pastTouchSlop = true
                                gestureMode = resolveZoomableImageGestureMode(
                                    isMultiTouch = isMultiTouch,
                                    scale = scale,
                                    panX = pan.x,
                                    panY = pan.y
                                )

                                if (gestureMode == ZoomableImageGestureMode.VERTICAL_DISMISS) {
                                    verticalDismissStarted = true
                                    latestOnDragStart()
                                }
                            }
                        }

                        if (pastTouchSlop) {
                            when (gestureMode) {
                                ZoomableImageGestureMode.VERTICAL_DISMISS -> {
                                    if (panChange != Offset.Zero) {
                                        // 双轴跟随：竖滑退出时手指横向漂移也实时传给宿主
                                        latestOnDrag(panChange)
                                    }
                                    val moveTimeMs = event.changes.firstOrNull()?.uptimeMillis ?: 0L
                                    if (lastVerticalDragTimeMs != 0L && moveTimeMs > lastVerticalDragTimeMs) {
                                        val instantVelocityY = panChange.y / (moveTimeMs - lastVerticalDragTimeMs) * 1000f
                                        verticalDragVelocityY = if (verticalDragVelocityY == 0f) {
                                            instantVelocityY
                                        } else {
                                            verticalDragVelocityY * 0.6f + instantVelocityY * 0.4f
                                        }
                                    }
                                    lastVerticalDragTimeMs = moveTimeMs
                                    event.changes.forEach {
                                        if (it.position != it.previousPosition) {
                                            it.consume()
                                        }
                                    }
                                }
                                ZoomableImageGestureMode.IMAGE_INTERACTION -> {
                                    val centroid = event.calculateCentroid(useCurrent = false)
                                    if (zoomChange != 1f || panChange != Offset.Zero) {
                                        val oldScale = scale
                                        val maxScale = resolveZoomableImageScaleLimits(
                                            imageWidth = imageSize.width,
                                            imageHeight = imageSize.height,
                                            containerWidth = containerSize.width,
                                            containerHeight = containerSize.height
                                        ).maxScale
                                        scale = (scale * zoomChange).coerceIn(1f, maxScale)

                                        if (oldScale != scale) {
                                            val zoomFactor = scale / oldScale
                                            val dx = (1 - zoomFactor) * (centroid.x - containerSize.width / 2f - offsetX)
                                            val dy = (1 - zoomFactor) * (centroid.y - containerSize.height / 2f - offsetY)
                                            offsetX += dx
                                            offsetY += dy
                                        }

                                        offsetX += panChange.x
                                        offsetY += panChange.y

                                        if (containerSize != IntSize.Zero && imageSize != IntSize.Zero) {
                                            val fitScale = min(
                                                containerSize.width.toFloat() / imageSize.width,
                                                containerSize.height.toFloat() / imageSize.height
                                            )

                                            val displayWidth = imageSize.width * fitScale * scale
                                            val displayHeight = imageSize.height * fitScale * scale

                                            val maxOffsetX = max(0f, (displayWidth - containerSize.width) / 2f)
                                            val maxOffsetY = max(0f, (displayHeight - containerSize.height) / 2f)

                                            offsetX = offsetX.coerceIn(-maxOffsetX, maxOffsetX)
                                            offsetY = offsetY.coerceIn(-maxOffsetY, maxOffsetY)
                                        }

                                        latestOnZoomChange(scale)
                                    }

                                    if (isMultiTouch || scale > 1.01f) {
                                        event.changes.forEach {
                                            if (it.position != it.previousPosition) {
                                                it.consume()
                                            }
                                        }
                                    }
                                }
                                ZoomableImageGestureMode.HORIZONTAL_PAGER,
                                ZoomableImageGestureMode.UNDECIDED -> Unit
                            }
                        }
                    } while (!gestureCanceled && event.changes.any { it.pressed })

                    if (verticalDismissStarted) {
                        if (gestureCanceled) {
                            latestOnDragCancel()
                        } else {
                            latestOnDragEnd(verticalDragVelocityY)
                        }
                    }
                }
            }
    ) {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            imageLoader = imageLoader,
            modifier = Modifier
                .fillMaxSize()
                .align(Alignment.Center)
                .testTag(ZOOMABLE_IMAGE_TAG)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = offsetY
            },
            onSuccess = { state ->
                val originalSize = state.painter.intrinsicSize
                if (originalSize.width > 0 && originalSize.height > 0) {
                    imageSize = IntSize(originalSize.width.toInt(), originalSize.height.toInt())
                    
                    if (isExtremeAspectRatio(imageSize.width, imageSize.height)) {
                        onExtremeAspectRatioDetected()
                    }
                }
            },
            // 使用 Fit 模式确保初始完整显示
            contentScale = ContentScale.Fit
        )
    }
}
