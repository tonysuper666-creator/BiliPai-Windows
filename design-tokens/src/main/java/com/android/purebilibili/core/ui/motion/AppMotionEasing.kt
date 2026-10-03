package com.android.purebilibili.core.ui.motion

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

object AppMotionEasing {
    /** Apple/SwiftUI easeInOut unit curve for reversible on-screen morphs. */
    val IosEaseInOut: Easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
    val EmphasizedEnter: Easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
    val EmphasizedExit: Easing = CubicBezierEasing(0.32f, 0f, 0.67f, 0f)
    val Continuity: Easing = CubicBezierEasing(0.20f, 0.90f, 0.22f, 1.00f)
    val GentleEnter: Easing = CubicBezierEasing(0.18f, 0.80f, 0.20f, 1.00f)
    /** 景深返回清晰：ease-in 向 0，先留住模糊再柔化，避免 Continuity 在 1→0 时过早掐清。 */
    val SoftClear: Easing = CubicBezierEasing(0.40f, 0.00f, 0.55f, 0.30f)

    // ═══ Material Design 3 官方标准曲线 (Material Motion Spec) ═══
    /** MD3 官方强调入场减速 (Emphasized Decelerate) */
    val Md3EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.70f, 0.10f, 1.00f)
    /** MD3 官方强调离场加速 (Emphasized Accelerate) */
    val Md3EmphasizedAccelerate: Easing = CubicBezierEasing(0.30f, 0.00f, 0.80f, 0.15f)
    /** MD3 官方标准曲线 (Standard Easing) */
    val Md3Standard: Easing = CubicBezierEasing(0.20f, 0.00f, 0.00f, 1.00f)
    /** MD3 官方标准减速 (Standard Decelerate) */
    val Md3StandardDecelerate: Easing = CubicBezierEasing(0.00f, 0.00f, 0.00f, 1.00f)
    /** MD3 官方标准加速 (Standard Accelerate) */
    val Md3StandardAccelerate: Easing = CubicBezierEasing(0.30f, 0.00f, 1.00f, 1.00f)
}
