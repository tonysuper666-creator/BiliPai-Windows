package com.android.purebilibili.feature.home.components
import androidx.compose.ui.unit.*
import com.android.purebilibili.core.ui.*
internal fun normalizeTopTabLabelMode(mode: Int): Int {
    return when (mode) {
        0, 1, 2 -> mode
        else -> 2
    }
}

internal fun resolveHomeTopDockShellHeight(isFloatingStyle: Boolean): Dp =
    if (isFloatingStyle) 52.dp else 48.dp

internal fun resolveIosTopTabActionButtonSize(isFloatingStyle: Boolean): Dp =
    if (isFloatingStyle) AppSpacingTokens.DoubleExtraLarge + AppSpacingTokens.ExtraSmall else AppSpacingTokens.DoubleExtraLarge

internal fun resolveIosTopTabActionButtonCorner(isFloatingStyle: Boolean): Dp =
    if (isFloatingStyle) AppSpacingTokens.ExtraLarge else AppSpacingTokens.Large + AppSpacingTokens.ExtraSmall

internal fun resolveIosTopTabActionIconSize(isFloatingStyle: Boolean): Dp =
    AppSpacingTokens.ExtraLarge - AppSpacingTokens.ExtraSmall

internal fun resolveMd3TopTabActionButtonCorner(
    isFloatingStyle: Boolean,
    presentation: AppTopTabPresentation = AppTopTabPresentation.MATERIAL_UNDERLINE
) = if (presentation == AppTopTabPresentation.TONAL_CAPSULE) {
    if (isFloatingStyle) AppSpacingTokens.Large + AppSpacingTokens.Micro else AppSpacingTokens.Medium + AppSpacingTokens.Micro
} else {
    if (isFloatingStyle) AppSpacingTokens.Large else AppSpacingTokens.Medium
}

internal fun resolveMd3TopTabActionButtonSize(
    isFloatingStyle: Boolean,
    presentation: AppTopTabPresentation = AppTopTabPresentation.MATERIAL_UNDERLINE
) = if (presentation == AppTopTabPresentation.TONAL_CAPSULE) {
    if (isFloatingStyle) AppSpacingTokens.TripleExtraLarge + AppSpacingTokens.Micro else AppSpacingTokens.DoubleExtraLarge + AppSpacingTokens.Medium
} else {
    if (isFloatingStyle) AppSpacingTokens.TripleExtraLarge else AppSpacingTokens.DoubleExtraLarge + AppSpacingTokens.Small + AppSpacingTokens.Micro
}

internal fun resolveMd3TopTabActionIconSize(
    isFloatingStyle: Boolean,
    presentation: AppTopTabPresentation = AppTopTabPresentation.MATERIAL_UNDERLINE
) = if (presentation == AppTopTabPresentation.TONAL_CAPSULE) {
    if (isFloatingStyle) AppSpacingTokens.ExtraLarge else AppSpacingTokens.ExtraLarge - AppSpacingTokens.Micro
} else {
    if (isFloatingStyle) AppSpacingTokens.ExtraLarge else AppSpacingTokens.ExtraLarge - AppSpacingTokens.Micro
}
