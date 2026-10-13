package com.bilipai.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.components.AppWindowAction
import com.android.purebilibili.core.ui.resolveGlobalWallpaperProtectiveColor
import com.android.purebilibili.feature.dynamic.SidebarUser

// Policies copied from the pinned originals (v0.2.5 79e8fa30):
// DynamicTopBar.kt, DynamicLayoutPolicy.kt, DynamicScreenStatePolicy.kt, DynamicSidebar.kt.
// DynamicTopBar.kt and DynamicSidebar.kt are not extractor inputs; these names stay in this module.

internal const val DESKTOP_DYNAMIC_DISPLAY_MODE_KEY = "dynamic_display_mode"
internal const val DYNAMIC_IMAGE_PREVIEW_TEXT_VISIBLE_KEY = "dynamic_image_preview_text_visible"
internal const val DYNAMIC_DETAIL_IMAGE_LAYOUT_KEY = "dynamic_detail_image_layout"
internal const val DYNAMIC_TOP_BAR_COLLAPSE_ON_SCROLL_KEY = "dynamic_top_bar_collapse_on_scroll"
internal const val DYNAMIC_TOP_ACTIONS_COLLAPSED_KEY = "dynamic_top_actions_collapsed"
internal const val DYNAMIC_PAGE_LAYOUT_DIRECTION_KEY = "dynamic_page_layout_direction"

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

/**
 * Windows places the tab strip and the horizontal UP list in the column until that chrome
 * overlays the lazy grid. Reserve only the overlaying chrome, and keep the reserve stable
 * while it hides so the staggered grid does not remeasure.
 */
internal fun resolveWindowsDynamicListTopPaddingExtraDp(
    topBarOverlaysFeed: Boolean,
    horizontalUserListOverlaysFeed: Boolean,
): Int {
    var extra = 0
    if (topBarOverlaysFeed) extra += DynamicTopBarReservedHeightDp
    if (horizontalUserListOverlaysFeed) extra += DynamicHorizontalUserListReservedHeightDp
    return extra
}

/** Same zero tolerance DynamicScreen passes into the collapse predicates. */
internal const val DynamicHeaderCollapseTriggerPx = 0

internal fun shouldCollapseDynamicHorizontalUserList(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    topTolerancePx: Int = 8,
): Boolean = firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > topTolerancePx

/** Tab collapse is optional. The horizontal UP list still collapses on its own. */
internal fun shouldCollapseDynamicTopBar(
    collapseOnScrollEnabled: Boolean,
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    topTolerancePx: Int = 8,
): Boolean {
    if (!collapseOnScrollEnabled) return false
    return shouldCollapseDynamicHorizontalUserList(
        firstVisibleItemIndex = firstVisibleItemIndex,
        firstVisibleItemScrollOffset = firstVisibleItemScrollOffset,
        topTolerancePx = topTolerancePx,
    )
}

/** Scroll snapshot for collapsing chrome. Feeds publish it; they do not read it while composing cards. */
internal class DesktopDynamicFeedChromeScroll {
    var firstVisibleItemIndex by mutableIntStateOf(0)
        private set
    var firstVisibleItemScrollOffset by mutableIntStateOf(0)
        private set

    fun publish(index: Int, offset: Int) {
        if (firstVisibleItemIndex != index) firstVisibleItemIndex = index
        if (firstVisibleItemScrollOffset != offset) firstVisibleItemScrollOffset = offset
    }
}

/** Original DynamicLayoutDirection. Unknown values stay on the left. */
internal enum class DesktopDynamicPageLayoutDirection(val value: Int, val label: String) {
    LEFT(0, "左侧"),
    RIGHT(1, "右侧");

    companion object {
        fun fromValue(value: Int): DesktopDynamicPageLayoutDirection =
            entries.find { it.value == value } ?: LEFT
    }
}

/** RIGHT mirrors the mode's side. LEFT keeps SIDEBAR on the left and SIDEBAR_RIGHT on the right. */
internal fun resolveDynamicSidebarOnRight(
    mode: DesktopDynamicDisplayMode,
    direction: DesktopDynamicPageLayoutDirection,
): Boolean {
    val base = mode.isRightAligned()
    return if (direction == DesktopDynamicPageLayoutDirection.RIGHT) !base else base
}

/** Large-screen detail split. LEFT keeps the card on the left. */
internal fun resolveDynamicDetailContentOnRight(direction: DesktopDynamicPageLayoutDirection): Boolean =
    direction == DesktopDynamicPageLayoutDirection.RIGHT

internal fun buildDynamicPageOverflowGroups(
    displayMode: DesktopDynamicDisplayMode,
    previewTextVisible: Boolean,
    detailImageLayoutValue: Int,
    topBarCollapseOnScroll: Boolean,
    pageLayoutDirection: DesktopDynamicPageLayoutDirection,
    onDisplayMode: (DesktopDynamicDisplayMode) -> Unit,
    onPreviewText: (Boolean) -> Unit,
    onDetailLayout: (Int) -> Unit,
    onTopBarCollapse: (Boolean) -> Unit,
    onDirection: (DesktopDynamicPageLayoutDirection) -> Unit,
): List<List<AppWindowAction>> = listOf(
    DesktopDynamicDisplayMode.entries.map { mode ->
        AppWindowAction(
            label = resolveDynamicDisplayModeLabel(mode),
            selected = displayMode == mode,
            onClick = { onDisplayMode(mode) },
        )
    },
    DesktopDynamicPageLayoutDirection.entries.map { direction ->
        AppWindowAction(
            label = direction.label,
            selected = pageLayoutDirection == direction,
            onClick = { onDirection(direction) },
        )
    },
    listOf(0 to "展开大图", 1 to "缩略图").map { (value, label) ->
        AppWindowAction(
            label = label,
            selected = detailImageLayoutValue == value,
            onClick = { onDetailLayout(value) },
        )
    },
    listOf(
        AppWindowAction(
            label = "动态图片默认显示文字",
            selected = previewTextVisible,
            onClick = { onPreviewText(!previewTextVisible) },
        ),
    ),
    listOf(
        AppWindowAction(
            label = "浏览动态时收起顶部栏",
            selected = topBarCollapseOnScroll,
            onClick = { onTopBarCollapse(!topBarCollapseOnScroll) },
        ),
    ),
)

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
