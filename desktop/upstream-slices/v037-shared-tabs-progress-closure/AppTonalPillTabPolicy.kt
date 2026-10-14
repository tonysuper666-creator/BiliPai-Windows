package com.android.purebilibili.core.ui.renderer.material3

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * PiliPlus 式 tonal 胶囊 Tab 行的几何策略。
 * 规格:PiliPlus 搜索结果页 TabBar —— secondaryContainer 胶囊、20dp 圆角、
 * 13sp 标签、无分隔线、无水波纹外溢。
 */
fun resolveTonalPillTabDefaultLabelFontSize(): TextUnit = 13.sp

fun resolveTonalPillTabCornerRadius(): Dp = 20.dp

fun resolveTonalPillTabItemHorizontalPadding(): Dp = 12.dp

fun resolveTonalPillTabVerticalInset(): Dp = 4.dp

fun resolveTonalPillTabDefaultHeight(): Dp = 40.dp
