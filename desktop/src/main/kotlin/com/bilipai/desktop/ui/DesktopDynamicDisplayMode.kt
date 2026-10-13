package com.bilipai.desktop.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.resolveGlobalWallpaperProtectiveColor
import com.android.purebilibili.feature.dynamic.SidebarUser

// Policies copied from the pinned originals (v0.2.5 79e8fa30):
// DynamicTopBar.kt, DynamicLayoutPolicy.kt, DynamicScreenStatePolicy.kt, DynamicSidebar.kt.
// DynamicTopBar.kt and DynamicSidebar.kt are not extractor inputs; these names stay in this module.

internal const val DESKTOP_DYNAMIC_DISPLAY_MODE_KEY = "dynamic_display_mode"

internal enum class DesktopDynamicDisplayMode {
    SIDEBAR,
    SIDEBAR_RIGHT,
    HORIZONTAL,
    DRAWER_LEFT,
    DRAWER_RIGHT,
}

internal fun DesktopDynamicDisplayMode.isHorizontalUserList(): Boolean = this == DesktopDynamicDisplayMode.HORIZONTAL

internal fun DesktopDynamicDisplayMode.isFixedSidebar(): Boolean =
    this == DesktopDynamicDisplayMode.SIDEBAR || this == DesktopDynamicDisplayMode.SIDEBAR_RIGHT

internal fun DesktopDynamicDisplayMode.isRightAligned(): Boolean =
    this == DesktopDynamicDisplayMode.SIDEBAR_RIGHT || this == DesktopDynamicDisplayMode.DRAWER_RIGHT

internal fun DesktopDynamicDisplayMode.isDrawer(): Boolean =
    this == DesktopDynamicDisplayMode.DRAWER_LEFT || this == DesktopDynamicDisplayMode.DRAWER_RIGHT

internal fun resolveDynamicDisplayModeLabel(mode: DesktopDynamicDisplayMode): String = when (mode) {
    DesktopDynamicDisplayMode.SIDEBAR -> "左侧竖条"
    DesktopDynamicDisplayMode.SIDEBAR_RIGHT -> "右侧竖条"
    DesktopDynamicDisplayMode.HORIZONTAL -> "顶部横条"
    DesktopDynamicDisplayMode.DRAWER_LEFT -> "左侧抽屉"
    DesktopDynamicDisplayMode.DRAWER_RIGHT -> "右侧抽屉"
}

/** Original `DynamicDisplayMode.valueOf`, with a missing or unknown name returning SIDEBAR. */
internal fun parseDesktopDynamicDisplayMode(raw: String?): DesktopDynamicDisplayMode {
    if (raw.isNullOrBlank()) return DesktopDynamicDisplayMode.SIDEBAR
    return try {
        DesktopDynamicDisplayMode.valueOf(raw)
    } catch (_: IllegalArgumentException) {
        DesktopDynamicDisplayMode.SIDEBAR
    }
}

internal const val DynamicTopBarReservedHeightDp = 60
internal const val DynamicHorizontalUserListReservedHeightDp = 96
internal const val DynamicHorizontalExpandedHeaderReservedHeightDp =
    DynamicTopBarReservedHeightDp + DynamicHorizontalUserListReservedHeightDp

internal fun resolveDynamicTopBarHeightDp(): Int = 50

internal fun resolveDynamicSidebarReturnHeaderHeightDp(): Int = resolveDynamicTopBarHeightDp()

internal fun resolveDynamicSidebarDividerTopOffset(topPadding: Dp): Dp =
    topPadding + resolveDynamicSidebarReturnHeaderHeightDp().dp

internal fun resolveDynamicTopBarHorizontalPadding(): Dp = 14.dp

internal fun resolveDynamicTopBarTabItemWidthDp(): Int = 72

internal data class DynamicTopBarLiquidTabSpec(
    val topPaddingDp: Int,
    val bottomPaddingDp: Int,
    val heightDp: Int,
    val indicatorHeightDp: Int,
    val labelFontSizeSp: Int,
)

internal fun resolveDynamicTopBarLiquidTabSpec(): DynamicTopBarLiquidTabSpec {
    val heightDp = resolveDynamicTopBarHeightDp()
    return DynamicTopBarLiquidTabSpec(
        topPaddingDp = 0,
        bottomPaddingDp = 0,
        heightDp = heightDp,
        indicatorHeightDp = heightDp - 8,
        labelFontSizeSp = 13,
    )
}

internal fun resolveDynamicSidebarWidth(isExpanded: Boolean): Dp = if (isExpanded) 68.dp else 60.dp

internal fun resolveDynamicListTopPaddingExtraDp(
    isHorizontalMode: Boolean,
    shouldShowHorizontalUserList: Boolean = true,
): Int = when {
    isHorizontalMode && shouldShowHorizontalUserList -> DynamicHorizontalExpandedHeaderReservedHeightDp
    else -> DynamicTopBarReservedHeightDp
}

internal const val DYNAMIC_SIDEBAR_MAX_CASCADE_ITEMS = 8
internal const val DYNAMIC_SIDEBAR_INITIAL_AVATAR_PREFETCH_COUNT = 30
internal const val DYNAMIC_SIDEBAR_FLING_VELOCITY_MULTIPLIER = 0.70f

internal fun resolveDynamicSidebarFlingDampingFactor(): Float = DYNAMIC_SIDEBAR_FLING_VELOCITY_MULTIPLIER

internal fun shouldAnimateSidebarItemCascade(
    index: Int,
    hasScrolled: Boolean,
    initialEntranceActive: Boolean,
): Boolean = initialEntranceActive && !hasScrolled && index < DYNAMIC_SIDEBAR_MAX_CASCADE_ITEMS

internal fun resolveDynamicSidebarContainerColor(
    surfaceColor: Color,
    globalWallpaperVisible: Boolean,
): Color = if (globalWallpaperVisible) {
    resolveGlobalWallpaperProtectiveColor(surfaceColor, lightAlpha = 0.74f, darkAlpha = 0.80f)
} else {
    surfaceColor
}

internal fun resolveDynamicSidebarReturnHeaderColor(
    surfaceColor: Color,
    backgroundAlpha: Float,
    globalWallpaperVisible: Boolean,
): Color {
    val rawColor = surfaceColor.copy(alpha = backgroundAlpha)
    if (!globalWallpaperVisible) return rawColor
    val protectiveColor = resolveGlobalWallpaperProtectiveColor(
        surfaceColor,
        lightAlpha = 0.74f,
        darkAlpha = 0.80f,
    )
    return rawColor.copy(alpha = maxOf(rawColor.alpha, protectiveColor.alpha))
}

internal fun resolveDynamicSidebarUserAvatarUrl(face: String): String {
    val raw = face.trim()
    return when {
        raw.isEmpty() -> ""
        raw.startsWith("https://") -> raw
        raw.startsWith("http://") -> raw.replace("http://", "https://")
        raw.startsWith("//") -> "https:$raw"
        else -> "https://$raw"
    }
}

internal fun resolveDynamicSidebarAvatarPrefetchUrls(
    users: List<SidebarUser>,
    startIndex: Int = 0,
    limit: Int = DYNAMIC_SIDEBAR_INITIAL_AVATAR_PREFETCH_COUNT,
): List<String> {
    if (startIndex < 0 || startIndex >= users.size || limit <= 0) return emptyList()
    val endIndex = minOf(startIndex + limit, users.size)
    return users.subList(startIndex, endIndex).mapNotNull { user ->
        resolveDynamicSidebarUserAvatarUrl(user.face).ifEmpty { null }
    }
}
