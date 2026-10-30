@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.bilipai.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SwitchAccount
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.android.purebilibili.feature.home.components.BottomNavItem
import com.android.purebilibili.feature.home.components.resolveMaterialBottomBarIcon
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.android.purebilibili.navigation3.toLegacyRoute
import com.bilipai.desktop.ui.kit.DESKTOP_KIT_ICON_DP
import com.bilipai.desktop.ui.kit.DESKTOP_KIT_RADIUS_ROW_DP
import com.bilipai.desktop.ui.kit.DESKTOP_KIT_TOOLTIP_DELAY_MS
import com.bilipai.desktop.ui.kit.DESKTOP_KIT_TOOLTIP_DELAY_RAIL_EXPANDED_MS
import com.bilipai.desktop.ui.kit.DesktopBadge
import com.bilipai.desktop.ui.kit.DesktopContextMenu
import com.bilipai.desktop.ui.kit.DesktopIconButton
import com.bilipai.desktop.ui.kit.DesktopKitDivider
import com.bilipai.desktop.ui.kit.DesktopKitVerticalScrollbar
import com.bilipai.desktop.ui.kit.DesktopMenuRow
import com.bilipai.desktop.ui.kit.DesktopNavRow
import com.bilipai.desktop.ui.kit.DesktopTooltipBox
import com.bilipai.desktop.ui.kit.desktopKitInteraction
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.TooltipAnchorPosition

/*
 * The navigation rail of the desktop shell (spec 3.2): a glass panel with the header (toggle and wordmark), the search
 * row, the browse and library groups in a scrolling middle, and the footer rows pinned below it. It draws what it is
 * given. The rows, the selection and the unread count come from the pure DesktopWindowsRailModel through
 * DesktopWindowsRailHost, which binds them to the Root route assembly; the rail itself takes plain data and lambdas.
 *
 * Every row is one DesktopNavRow, which is one selectable node, and every tooltip wrapper adds no action, so the
 * fixtures still find the accessible names BiliPai, search and downloads each with a single action. The rail is one tab
 * stop with roving focus (Up, Down, Home, End; Enter and Space are the rows' own): only the stop Tab may reach is
 * focusable, and the arrow keys move that stop and then the focus. A right click anywhere on the rail opens the mode menu. The
 * rail never decides what the toggle means: the hamburger and the collapse and expand entries of the menu call the toggle of the
 * shell, which writes the opposite mode or says that the window is too narrow, and only the way back to automatic is a write of
 * the rail's own. The keys the tooltips and the search hint name (Ctrl+B, Ctrl+K, Ctrl+number) are shown only while
 * DESKTOP_RAIL_SHORTCUT_HINTS is true, that is, once the shell shortcut host that handles them exists.
 */

/** One row of the rail as drawn: the model entry, its shown label, whether it is selected, its icon and its unread count. */
internal class DesktopRailRowState(
    val entry: DesktopRailEntry,
    val label: String,
    val selected: Boolean,
    val icon: ImageVector,
    val unreadCount: Int,
)

/** Stop 0 is the header button, stop 1 the search row; the rows follow in display order. */
private const val DESKTOP_RAIL_STOP_HEADER = 0
private const val DESKTOP_RAIL_STOP_SEARCH = 1
private const val DESKTOP_RAIL_FIRST_ROW_STOP = 2

@Composable
internal fun DesktopWindowsNavigationRail(
    shell: DesktopWindowsShellRail,
    rows: List<DesktopRailRowState>,
    onSearch: () -> Unit,
    onRowClick: (DesktopRailRowState) -> Unit,
    modifier: Modifier = Modifier,
) {
    val presentation = shell.presentation
    val collapsed = presentation == DesktopRailPresentation.COLLAPSED
    val colors = MaterialTheme.colorScheme
    val sidePadding = if (collapsed) 4.dp else 8.dp

    // Roving focus: the stops are numbered in tab order and exactly one of them may take the focus from Tab.
    val stopCount = DESKTOP_RAIL_FIRST_ROW_STOP + rows.size
    var roving by remember { mutableStateOf<Int?>(null) }
    val selectedIndex = rows.indexOfFirst { it.selected }
    val tabStop = desktopRailTabStop(stopCount, roving, if (selectedIndex < 0) null else selectedIndex + DESKTOP_RAIL_FIRST_ROW_STOP)
    val requesters = remember(stopCount) { List(stopCount) { FocusRequester() } }
    var pendingFocus by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(pendingFocus, tabStop) {
        val target = pendingFocus
        if (target != null && target == tabStop) {
            pendingFocus = null
            try {
                requesters.getOrNull(target)?.requestFocus()
            } catch (_: IllegalStateException) {
                // The stop is not attached any more (the rows changed meanwhile): the next key press starts again.
            }
        }
    }
    var menuPointer by remember { mutableStateOf<IntOffset?>(null) }
    val scroll = rememberScrollState()
    val toggle: () -> Unit = {
        roving = DESKTOP_RAIL_STOP_HEADER
        shell.toggle()
    }

    Box(modifier.width(presentation.footprintDp.dp).fillMaxHeight().padding(DESKTOP_RAIL_MARGIN_DP.dp)) {
        DesktopWindowsGlassSurface(Modifier.fillMaxSize(), shape = RoundedCornerShape(DESKTOP_RAIL_PANEL_RADIUS_DP.dp)) {
            // The box that receives the right click is also the parent layout of the menu, so the pointer is in its coordinates.
            Box(
                Modifier.fillMaxSize().onPointerEvent(PointerEventType.Press) { event ->
                    if (event.buttons.isSecondaryPressed && desktopRailMenuActions(shell.mode, presentation).isNotEmpty()) {
                        val at = event.changes.first().position
                        menuPointer = IntOffset(at.x.roundToInt(), at.y.roundToInt())
                    }
                },
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .semantics { contentDescription = DesktopShellStrings.RAIL_NAME }
                        .onKeyEvent { event ->
                            val rovingKey = desktopRailRovingKeyOf(event)
                            if (rovingKey == null) {
                                false
                            } else {
                                val target = desktopRailRovingTarget(stopCount, tabStop, rovingKey)
                                if (target >= 0) {
                                    roving = target
                                    pendingFocus = target
                                }
                                true
                            }
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // The header container keeps the accessible name of the app in both modes; the wordmark shows when expanded.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(DESKTOP_RAIL_HEADER_DP.dp)
                            .padding(horizontal = sidePadding)
                            .semantics { contentDescription = DesktopShellStrings.WORDMARK },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.spacedBy(8.dp),
                    ) {
                        DesktopIconButton(
                            onClick = toggle,
                            label = if (collapsed) DesktopShellStrings.RAIL_EXPAND else DesktopShellStrings.RAIL_COLLAPSE,
                            modifier = Modifier.desktopRailStop(requesters, DESKTOP_RAIL_STOP_HEADER, tabStop),
                            shortcut = if (DESKTOP_RAIL_SHORTCUT_HINTS) DesktopShellStrings.RAIL_TOGGLE_SHORTCUT else null,
                        ) {
                            Icon(Icons.Outlined.Menu, contentDescription = null, modifier = Modifier.size(DESKTOP_KIT_ICON_DP.dp), tint = colors.onSurface)
                        }
                        if (!collapsed) {
                            Text(
                                DesktopShellStrings.WORDMARK, color = colors.onSurface,
                                style = MaterialTheme.typography.titleMedium, maxLines = 1,
                            )
                        }
                    }
                    DesktopRailSearchRow(
                        collapsed = collapsed,
                        onClick = {
                            roving = DESKTOP_RAIL_STOP_SEARCH
                            onSearch()
                        },
                        modifier = Modifier.padding(horizontal = sidePadding).desktopRailStop(requesters, DESKTOP_RAIL_STOP_SEARCH, tabStop),
                    )
                    // The groups scroll with the shared scrollbar; the footer below them never scrolls.
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        Column(
                            Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = sidePadding),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            listOf(DesktopRailGroup.BROWSE, DesktopRailGroup.LIBRARY).forEachIndexed { index, group ->
                                val inGroup = rows.filter { it.entry.group == group }
                                if (inGroup.isNotEmpty()) {
                                    DesktopRailGroupTitle(group, collapsed, first = index == 0)
                                    inGroup.forEach { row ->
                                        androidx.compose.runtime.key(desktopRailRowKey(row)) {
                                            DesktopRailItemRow(row, presentation, rows, requesters, tabStop, onClick = {
                                                roving = DESKTOP_RAIL_FIRST_ROW_STOP + rows.indexOf(row)
                                                onRowClick(row)
                                            })
                                        }
                                    }
                                }
                            }
                        }
                        DesktopKitVerticalScrollbar(scroll)
                    }
                    val footer = rows.filter { it.entry.group == DesktopRailGroup.FOOTER }
                    if (footer.isNotEmpty()) {
                        DesktopKitDivider(Modifier.padding(horizontal = sidePadding))
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = sidePadding, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            footer.forEach { row ->
                                androidx.compose.runtime.key(desktopRailRowKey(row)) {
                                    DesktopRailItemRow(row, presentation, rows, requesters, tabStop, onClick = {
                                        roving = DESKTOP_RAIL_FIRST_ROW_STOP + rows.indexOf(row)
                                        onRowClick(row)
                                    })
                                }
                            }
                        }
                    }
                }
                DesktopContextMenu(
                    expanded = menuPointer != null,
                    onDismissRequest = { menuPointer = null },
                    pointer = menuPointer ?: IntOffset.Zero,
                    label = DesktopShellStrings.RAIL_NAME,
                ) {
                    desktopRailMenuActions(shell.mode, presentation).forEach { action ->
                        DesktopMenuRow(
                            label = DesktopShellStrings.railMenuLabel(action),
                            onClick = {
                                menuPointer = null
                                if (action == DesktopRailMenuAction.RESTORE_AUTO) shell.setMode(action.mode) else shell.toggle()
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Only the stop Tab may reach is focusable; the requester lets the arrow keys move the focus to any stop once it is. */
private fun Modifier.desktopRailStop(requesters: List<FocusRequester>, stop: Int, tabStop: Int): Modifier =
    this.focusRequester(requesters[stop]).focusProperties { this.canFocus = stop == tabStop }

/** The roving key of an unmodified key press, or null: Alt, Ctrl, Shift and Meta chords belong to the shell shortcuts. */
private fun desktopRailRovingKeyOf(event: KeyEvent): DesktopRailRovingKey? {
    if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed || event.isShiftPressed) {
        return null
    }
    return when (event.key) {
        Key.DirectionUp -> DesktopRailRovingKey.UP
        Key.DirectionDown -> DesktopRailRovingKey.DOWN
        Key.MoveHome -> DesktopRailRovingKey.HOME
        Key.MoveEnd -> DesktopRailRovingKey.END
        else -> null
    }
}

/** The title of a group when the rail is expanded; a hairline between the groups when it is collapsed. The footer has none. */
@Composable
private fun DesktopRailGroupTitle(group: DesktopRailGroup, collapsed: Boolean, first: Boolean) {
    val title = DesktopShellStrings.groupTitle(group)
    if (collapsed) {
        if (!first) DesktopKitDivider(Modifier.padding(vertical = 6.dp))
    } else if (title != null) {
        Text(
            title, Modifier.padding(start = 12.dp, top = if (first) 4.dp else 12.dp, bottom = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, maxLines = 1,
        )
    }
}

/**
 * One row: the kit nav row (one selectable node) with the icon, the dynamic unread badge, and the tooltip of its mode. A
 * collapsed row shows its label, and its Ctrl+number when the shortcut hints are on; an expanded row shows only the Ctrl+number,
 * after the long delay, and no tooltip at all while the hints are off. A row with unread items is named with the count.
 */
@Composable
private fun DesktopRailItemRow(
    row: DesktopRailRowState,
    presentation: DesktopRailPresentation,
    rows: List<DesktopRailRowState>,
    requesters: List<FocusRequester>,
    tabStop: Int,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val collapsed = presentation == DesktopRailPresentation.COLLAPSED
    val number = if (DESKTOP_RAIL_SHORTCUT_HINTS) row.entry.item?.let { desktopRailNumberOf(it) } else null
    val badge = desktopRailBadgeKind(row.unreadCount, presentation)
    DesktopNavRow(
        label = row.label,
        selected = row.selected,
        onClick = onClick,
        modifier = Modifier.desktopRailStop(requesters, DESKTOP_RAIL_FIRST_ROW_STOP + rows.indexOf(row), tabStop),
        collapsed = collapsed,
        icon = {
            Icon(
                row.icon, contentDescription = null, modifier = Modifier.size(DESKTOP_KIT_ICON_DP.dp),
                tint = if (row.selected) colors.primary else colors.onSurfaceVariant,
            )
        },
        badge = if (badge == DesktopRailBadgeKind.NONE) null else {
            { DesktopBadge(text = if (badge == DesktopRailBadgeKind.PILL) desktopRailBadgeText(row.unreadCount) else null) }
        },
        tooltip = if (collapsed) DesktopShellStrings.collapsedTooltip(row.label, number) else DesktopShellStrings.expandedTooltip(number),
        tooltipDelayMillis = if (collapsed) DESKTOP_KIT_TOOLTIP_DELAY_MS else DESKTOP_KIT_TOOLTIP_DELAY_RAIL_EXPANDED_MS,
        description = if (row.unreadCount > 0) DesktopShellStrings.unreadAnnouncement(row.label, row.unreadCount) else null,
    )
}

/**
 * The identity of a row across compositions: the table item, or the vendored constant name of an item the table does not know.
 * The loops of the rail wrap each row in key(...) with it, so a row that appears or goes (the account switcher) leaves the state
 * of the rows around it alone. The key has to sit at the call site: the call itself is the group that is matched by position.
 */
private fun desktopRailRowKey(row: DesktopRailRowState): String? = row.entry.item?.name ?: row.entry.bottomNavName

/**
 * The search row: icon, the word and, while the shortcut hints are on, the Ctrl K hint when expanded (the hint is left out of the
 * semantics, so the accessible name is the word alone); a square icon button named the same when collapsed. One clickable; the
 * tooltip wrapper adds none.
 */
@Composable
private fun DesktopRailSearchRow(collapsed: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val source = remember { MutableInteractionSource() }
    val colors = MaterialTheme.colorScheme
    val radius = DESKTOP_KIT_RADIUS_ROW_DP.dp
    val height: Dp = (if (collapsed) DESKTOP_RAIL_SEARCH_COLLAPSED_DP else DESKTOP_RAIL_SEARCH_ROW_DP).dp
    DesktopTooltipBox(
        text = if (DESKTOP_RAIL_SHORTCUT_HINTS) DesktopShellStrings.searchTooltip() else DesktopShellStrings.SEARCH,
        modifier = modifier, enabled = collapsed,
        positioning = TooltipAnchorPosition.Right,
    ) {
        Row(
            (if (collapsed) Modifier.size(height) else Modifier.fillMaxWidth().height(height))
                .background(colors.surfaceContainerHighest, RoundedCornerShape(radius))
                .desktopKitInteraction(source, radius)
                .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
                .semantics { if (collapsed) contentDescription = DesktopShellStrings.SEARCH }
                .padding(horizontal = 10.dp),
            horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(DESKTOP_KIT_ICON_DP.dp), tint = colors.onSurfaceVariant)
            if (!collapsed) {
                Text(
                    DesktopShellStrings.SEARCH, Modifier.weight(1f), color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                )
                if (DESKTOP_RAIL_SHORTCUT_HINTS) {
                    Text(
                        DesktopShellStrings.SEARCH_HINT, Modifier.clearAndSetSemantics { }, color = colors.onSurfaceVariant.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall, maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * The rail as the Root chrome mounts it: the rows are derived from the shell's visible items with the pure model (table
 * order, the downloads row always, the account row only when there is an account switcher), the selection uses the shell's
 * existing rule, and every click keeps the route owner guard and goes through the admitted push entry of the Root
 * assembly (onItemClick), so there is no parallel navigation stack. Clicking the selected recommend or dynamic row runs the
 * existing scroll-to-top-and-refresh action instead of a push.
 */
@Composable
internal fun DesktopWindowsRailHost(
    routes: DesktopOriginalRootRouteAssembly,
    binding: DesktopOriginalRootChromeBindings,
    currentItem: BottomNavItem,
    visibleItems: List<BottomNavItem>,
    onItemClick: (BottomNavItem) -> Unit,
    shell: DesktopWindowsShellRail,
) {
    val key = routes.currentKey
    val onMainHost = key == BiliPaiNavKey.MainHost
    val legacyRoute = key.toLegacyRoute()
    val unreadCount = binding.dynamicUnreadCount()
    val hasAccountSwitcher = binding.accountSwitcher != null
    val names = visibleItems.map { it.name }
    val entries = remember(names, hasAccountSwitcher) { desktopRailEntries(names, hasAccountSwitcher) }
    val byName = visibleItems.associateBy { it.name }
    val rows = entries.map { entry ->
        val item = entry.bottomNavName?.let { byName[it] }
        val selected = item != null && desktopRailItemSelected(onMainHost, currentItem.name, item.name, legacyRoute, item.route)
        DesktopRailRowState(
            entry = entry,
            label = entry.item?.let { DesktopShellStrings.railLabel(it) } ?: item?.label.orEmpty(),
            selected = selected,
            icon = desktopRailIcon(entry, item, selected),
            unreadCount = if (entry.item == DesktopRailItem.DYNAMIC) unreadCount else 0,
        )
    }
    DesktopWindowsNavigationRail(
        shell = shell,
        rows = rows,
        onSearch = { if (routes.owns()) binding.actions.searchClick() },
        onRowClick = { row -> desktopRailActivate(routes, binding, row, row.entry.bottomNavName?.let { byName[it] }, onItemClick) },
    )
}

private fun desktopRailIcon(entry: DesktopRailEntry, item: BottomNavItem?, selected: Boolean): ImageVector = when {
    item != null -> resolveMaterialBottomBarIcon(item, selected)
    entry.item == DesktopRailItem.ACCOUNT_SWITCH -> Icons.Outlined.SwitchAccount
    else -> Icons.Outlined.Download
}

/** What a click does: the downloads route, the account switcher, the refresh of the selected row, or the admitted tab push. */
private fun desktopRailActivate(
    routes: DesktopOriginalRootRouteAssembly,
    binding: DesktopOriginalRootChromeBindings,
    row: DesktopRailRowState,
    item: BottomNavItem?,
    onItemClick: (BottomNavItem) -> Unit,
) {
    if (!routes.owns()) return
    when (row.entry.item) {
        DesktopRailItem.DOWNLOADS -> {
            routes.push(BiliPaiNavKey.DownloadList)
        }
        DesktopRailItem.ACCOUNT_SWITCH -> {
            binding.accountSwitcher?.invoke()
        }
        else -> {
            if (item == null) return
            when (desktopRailClick(row.entry.item, row.selected)) {
                DesktopRailClick.HOME_DOUBLE_TAP -> binding.actions.homeDoubleTap()
                DesktopRailClick.DYNAMIC_DOUBLE_TAP -> binding.actions.dynamicDoubleTap()
                DesktopRailClick.NAVIGATE -> onItemClick(item)
            }
        }
    }
}
