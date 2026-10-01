package com.android.purebilibili.feature.bangumi

// This consumer selects the original cover-badge policy; player orientation belongs to the player port.
import androidx.compose.ui.graphics.Color
import com.android.purebilibili.core.theme.ACCESSIBLE_TEXT_MIN_CONTRAST
import com.android.purebilibili.core.theme.AccessibleContainerColors
import com.android.purebilibili.core.theme.resolveAccessibleContainerColors

internal fun resolveBangumiCoverBadgeColors(
    primary: Color,
    onPrimary: Color,
    surface: Color,
    onSurface: Color,
): AccessibleContainerColors {
    return resolveAccessibleContainerColors(
        containerColor = primary,
        contentColor = onPrimary,
        backgroundColor = surface,
        fallbackContentColors = listOf(onSurface, Color.Black, Color.White),
        minimumContrast = ACCESSIBLE_TEXT_MIN_CONTRAST,
    )
}
