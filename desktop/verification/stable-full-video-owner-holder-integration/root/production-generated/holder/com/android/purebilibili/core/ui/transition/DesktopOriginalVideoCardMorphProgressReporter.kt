package com.android.purebilibili.core.ui.transition

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * 卡片 ↔ 详情 **唯一时间轴契约**。
 *
 * ## 语义（morphFraction）
 * - **0** = 落在列表源卡（景深清晰）
 * - **1** = 落在详情全屏（景深满糊）
 *
 * 元素 scale、背景 blur / scrim、chrome settle、壁纸 depth **只读** [depthProgress]，
 * 不再各自 Animatable 并行。
 *
 * Miuix 主链路：只跟随 NavDisplay，包含预测 seek/commit/cancel 和真实结束帧。
 * ## 无 Nav 主驱动的兼容路径优先级（高 → 低）
 * 1. **预测手势** [gestureBackProgress]
 * 2. **Shared morph**（详情 AVS 与 sharedBounds 同一 Transition 的 progress）
 * 3. **Host fallback Animatable**（无 shared / 首帧详情未挂上时）
 *
 * Shared bounds 仍由 Compose SharedTransition 播 bounds（无法外注 progress），
 * 但 AVS `animateFloat` 与 boundsTransform **强制同一 duration + easing**，
 * 再把 AVS progress 回灌本时钟 → 墙钟与曲线同源。
 */
internal fun interface VideoCardMorphProgressReporter {
    fun report(morphFraction: Float, active: Boolean)
}

internal val LocalVideoCardMorphProgressReporter =
    compositionLocalOf<VideoCardMorphProgressReporter?> { null }

