package com.bilipai.desktop.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.components.AppSlider
import com.android.purebilibili.core.ui.components.AppSwitch
import top.yukonga.miuix.kmp.basic.TooltipAnchorPosition

/*
 * Controls of the desktop component kit, part 1 (spec 5.1). Pointer and keyboard affordances come from
 * DesktopKitFocus, tooltips from DesktopKitTooltip, numbers from DesktopKitTokens. Each control declares exactly one
 * action, and a control that has a row around it makes the row the action: the vendored switch inside a toggle row is
 * display only, so the tree holds one toggle and not two.
 */

private fun desktopKitDisabledAlpha(enabled: Boolean): Float = if (enabled) 1f else DESKTOP_KIT_DISABLED_ALPHA_PERCENT / 100f

/** An icon button: a 32 dp hit (36 dp when large), a 20 dp glyph slot, exactly one clickable with Role.Button. */
@Composable
internal fun DesktopIconButton(
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean? = null,
    large: Boolean = false,
    tooltip: String? = label,
    shortcut: String? = null,
    content: @Composable () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val hit = (if (large) DESKTOP_KIT_ICON_HIT_LARGE_DP else DESKTOP_KIT_ICON_HIT_DP).dp
    DesktopTooltipBox(text = tooltip.orEmpty(), modifier = modifier, shortcut = shortcut, enabled = enabled && tooltip != null) {
        Box(
            Modifier
                .size(hit)
                .alpha(desktopKitDisabledAlpha(enabled))
                .desktopKitInteraction(source, DESKTOP_KIT_RADIUS_CONTROL_DP.dp, enabled, selected = active == true)
                .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics {
                    contentDescription = label
                    if (active != null) selected = active
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(DESKTOP_KIT_ICON_DP.dp), contentAlignment = Alignment.Center) { content() }
        }
    }
}

/**
 * A navigation row: icon slot, label, optional badge. Selected rows get the primary fill, a left-edge pill and the
 * primary label color. A collapsed row shows the icon only (the badge sits on the icon corner) and keeps its name as
 * the content description. One selectable node with Role.Tab, like the rows of a Material navigation rail.
 */
@Composable
internal fun DesktopNavRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    collapsed: Boolean = false,
    icon: (@Composable () -> Unit)? = null,
    badge: (@Composable () -> Unit)? = null,
    tooltip: String? = null,
    tooltipShortcut: String? = null,
    tooltipDelayMillis: Int = DESKTOP_KIT_TOOLTIP_DELAY_MS,
) {
    val source = remember { MutableInteractionSource() }
    val colors = MaterialTheme.colorScheme
    DesktopTooltipBox(
        text = tooltip.orEmpty(), modifier = modifier, shortcut = tooltipShortcut, enabled = enabled && tooltip != null,
        delayMillis = tooltipDelayMillis,
        positioning = if (collapsed) TooltipAnchorPosition.Right else TooltipAnchorPosition.Below,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(DESKTOP_KIT_ROW_NAV_DP.dp)
                .alpha(desktopKitDisabledAlpha(enabled))
                .desktopKitInteraction(source, DESKTOP_KIT_RADIUS_ROW_DP.dp, enabled, selected)
                .drawBehind {
                    if (selected) {
                        val pill = Size(DESKTOP_KIT_NAV_PILL_WIDTH_DP.dp.toPx(), DESKTOP_KIT_NAV_PILL_HEIGHT_DP.dp.toPx())
                        drawRoundRect(
                            color = colors.primary, topLeft = Offset(0f, (size.height - pill.height) / 2f), size = pill,
                            cornerRadius = CornerRadius(pill.width / 2f),
                        )
                    }
                }
                .selectable(selected = selected, interactionSource = source, indication = null, enabled = enabled, role = Role.Tab, onClick = onClick)
                .semantics { if (collapsed) contentDescription = label }
                .padding(horizontal = 12.dp),
            horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.spacedBy(DESKTOP_KIT_NAV_ICON_GAP_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null || (collapsed && badge != null)) {
                Box(Modifier.size(DESKTOP_KIT_ICON_DP.dp), contentAlignment = Alignment.Center) {
                    icon?.invoke()
                    if (collapsed && badge != null) Box(Modifier.align(Alignment.TopEnd)) { badge() }
                }
            }
            if (!collapsed) {
                Text(
                    label, Modifier.weight(1f), color = if (selected) colors.primary else colors.onSurface,
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                badge?.invoke()
            }
        }
    }
}

/** One option of a segmented control. */
internal data class DesktopKitSegment<T>(val value: T, val label: String, val enabled: Boolean = true)

/**
 * A segmented control: a 32 dp track of equal segments. The group is one tab stop with Left, Right, Home and End
 * (unmodified keys only, so Alt+Left stays the back shortcut); each segment is a RadioButton in a selectable group.
 * A label that does not fit is ellipsised and gets a tooltip with the full text.
 */
@Composable
internal fun <T> DesktopSegmentedControl(
    segments: List<DesktopKitSegment<T>>,
    selected: T?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String? = null,
) {
    val groupSource = remember { MutableInteractionSource() }
    val focusRequester = remember { FocusRequester() }
    val selectedIndex = segments.indexOfFirst { it.value == selected }
    val flags = segments.map { it.enabled }
    val colors = MaterialTheme.colorScheme
    val radius = DESKTOP_KIT_RADIUS_CONTROL_DP.dp
    fun select(index: Int) {
        val target = segments.getOrNull(index) ?: return
        if (enabled && target.enabled && index != selectedIndex) onSelect(target.value)
    }
    Row(
        modifier
            .height(DESKTOP_KIT_CONTROL_HEIGHT_DP.dp)
            .alpha(desktopKitDisabledAlpha(enabled))
            .desktopKitFocusRing(groupSource, radius, enabled)
            .background(colors.surfaceContainerHighest, RoundedCornerShape(radius))
            .desktopKitHairlineBorder(colors.outlineVariant, radius)
            .onKeyEvent { event ->
                if (!enabled || event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isAltPressed ||
                    event.isMetaPressed || event.isShiftPressed) {
                    false
                } else when (event.key) {
                    Key.DirectionLeft -> { select(desktopKitSegmentStep(flags, selectedIndex, -1)); true }
                    Key.DirectionRight -> { select(desktopKitSegmentStep(flags, selectedIndex, 1)); true }
                    Key.MoveHome -> { select(desktopKitSegmentEdge(flags, last = false)); true }
                    Key.MoveEnd -> { select(desktopKitSegmentEdge(flags, last = true)); true }
                    else -> false
                }
            }
            .focusRequester(focusRequester)
            .focusable(enabled, groupSource)
            .selectableGroup()
            .semantics { if (label != null) contentDescription = label }
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        segments.forEachIndexed { index, segment ->
            val isSelected = index == selectedIndex
            val segmentEnabled = enabled && segment.enabled
            val source = remember { MutableInteractionSource() }
            var overflowed by remember { mutableStateOf(false) }
            DesktopTooltipBox(text = segment.label, modifier = Modifier.weight(1f).fillMaxHeight(), enabled = overflowed) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .alpha(desktopKitDisabledAlpha(segment.enabled))
                        .desktopKitHoverWash(source, radius - 2.dp, segmentEnabled, isSelected)
                        .focusProperties { canFocus = false }
                        .selectable(
                            selected = isSelected, interactionSource = source, indication = null, enabled = segmentEnabled,
                            role = Role.RadioButton,
                            onClick = {
                                focusRequester.requestFocus()
                                select(index)
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        segment.label, Modifier.padding(horizontal = 8.dp),
                        color = if (isSelected) colors.primary else colors.onSurface,
                        style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        onTextLayout = { overflowed = it.hasVisualOverflow },
                    )
                }
            }
        }
    }
}

/**
 * A settings row with a switch. The whole row is the one toggle (Role.Switch); the vendored AppSwitch inside it is
 * display only, which keeps its liquid look when glass is on. A null value means the setting is still loading: the
 * row shows the loading text and ignores input.
 */
@Composable
internal fun DesktopToggleRow(
    title: String,
    checked: Boolean?,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    description: String? = null,
    loadingText: String = DesktopKitStrings.LOADING_SETTING,
) {
    val source = remember { MutableInteractionSource() }
    val interactive = enabled && checked != null
    val colors = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = DESKTOP_KIT_ROW_LIST_DP.dp)
            .desktopKitInteraction(source, DESKTOP_KIT_RADIUS_ROW_DP.dp, interactive)
            .toggleable(
                value = checked == true, interactionSource = source, indication = null, enabled = interactive,
                role = Role.Switch, onValueChange = onCheckedChange,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).alpha(desktopKitDisabledAlpha(enabled))) {
            Text(title, color = colors.onSurface, style = MaterialTheme.typography.bodyMedium)
            if (description != null) {
                Text(description, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            if (checked == null) {
                Text(loadingText, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
        AppSwitch(checked = checked == true, onCheckedChange = null, enabled = interactive)
    }
}

/**
 * A settings row that shows the current choice and a chevron, for a choice list too long for a segmented control.
 * Clicking calls onClick; the caller owns the open state and passes its popup as menu, which is anchored to the row.
 * A null valueLabel means the setting is still loading.
 */
@Composable
internal fun DesktopSelectRow(
    title: String,
    valueLabel: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    description: String? = null,
    loadingText: String = DesktopKitStrings.LOADING_SETTING,
    menu: (@Composable () -> Unit)? = null,
) {
    val source = remember { MutableInteractionSource() }
    val interactive = enabled && valueLabel != null
    val colors = MaterialTheme.colorScheme
    Box(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = DESKTOP_KIT_ROW_LIST_DP.dp)
                .alpha(desktopKitDisabledAlpha(enabled))
                .desktopKitInteraction(source, DESKTOP_KIT_RADIUS_ROW_DP.dp, interactive)
                .clickable(interactionSource = source, indication = null, enabled = interactive, role = Role.DropdownList, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = colors.onSurface, style = MaterialTheme.typography.bodyMedium)
                if (description != null) {
                    Text(description, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                valueLabel ?: loadingText, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp),
            )
            val chevron = colors.onSurfaceVariant
            Canvas(Modifier.size(10.dp)) {
                val stroke = desktopKitSnapStrokePx(1.5f * density)
                val left = Offset(size.width * 0.1f, size.height * 0.3f)
                val middle = Offset(size.width * 0.5f, size.height * 0.7f)
                val right = Offset(size.width * 0.9f, size.height * 0.3f)
                drawLine(chevron, left, middle, strokeWidth = stroke, cap = StrokeCap.Round)
                drawLine(chevron, middle, right, strokeWidth = stroke, cap = StrokeCap.Round)
            }
        }
        menu?.invoke()
    }
}

/** A settings row with a title, the vendored AppSlider and an optional value label. */
@Composable
internal fun DesktopSliderRow(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    valueLabel: String? = null,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = DESKTOP_KIT_ROW_LIST_DP.dp)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title, Modifier.weight(1f).alpha(desktopKitDisabledAlpha(enabled)), color = colors.onSurface,
            style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        AppSlider(
            value = value, onValueChange = onValueChange, modifier = Modifier.weight(1.4f).semantics { contentDescription = title },
            enabled = enabled, valueRange = valueRange, steps = steps, onValueChangeFinished = onValueChangeFinished,
        )
        if (valueLabel != null) {
            Text(
                valueLabel, Modifier.widthIn(min = 44.dp), color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End, maxLines = 1,
            )
        }
    }
}

/**
 * A badge: an 18 dp pill with the text, or an 8 dp dot when text is null. Decorative: it adds nothing to the
 * accessibility tree, so the host names the row that carries it (for example the unread count of the rail).
 */
@Composable
internal fun DesktopBadge(
    text: String?,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
) {
    if (text == null) {
        Box(modifier.clearAndSetSemantics { }.size(DESKTOP_KIT_BADGE_DOT_DP.dp).background(containerColor, CircleShape))
    } else {
        Box(
            modifier
                .clearAndSetSemantics { }
                .defaultMinSize(minWidth = DESKTOP_KIT_BADGE_DP.dp, minHeight = DESKTOP_KIT_BADGE_DP.dp)
                .background(containerColor, RoundedCornerShape((DESKTOP_KIT_BADGE_DP / 2).dp))
                .padding(horizontal = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, color = contentColor, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

/** A divider that is exactly one physical pixel tall at any zoom (a 1 dp line blurs at fractional densities). */
@Composable
internal fun DesktopKitDivider(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.outlineVariant) {
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    Box(modifier.fillMaxWidth().height(desktopKitHairlineDp(density).dp).background(color))
}
