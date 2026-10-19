package com.bilipai.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measured
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.VerticalAlignmentLine
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.bilipai.desktop.ui.kit.DESKTOP_KIT_ICON_DP
import com.bilipai.desktop.ui.kit.DESKTOP_KIT_RADIUS_PANEL_DP
import com.bilipai.desktop.ui.kit.DESKTOP_KIT_RADIUS_ROW_DP
import com.bilipai.desktop.ui.kit.DESKTOP_KIT_ROW_SETTINGS_COMPACT_DP
import com.bilipai.desktop.ui.kit.DesktopIconButton
import com.bilipai.desktop.ui.kit.DesktopKitDivider
import com.bilipai.desktop.ui.kit.DesktopKitSegment
import com.bilipai.desktop.ui.kit.DesktopKitVerticalScrollbar
import com.bilipai.desktop.ui.kit.DesktopMenuRow
import com.bilipai.desktop.ui.kit.DesktopPopover
import com.bilipai.desktop.ui.kit.DesktopSegmentedControl
import com.bilipai.desktop.ui.kit.DesktopSelectRow
import com.bilipai.desktop.ui.kit.DesktopToggleRow
import com.bilipai.desktop.ui.kit.desktopKitDisabledAlpha
import com.bilipai.desktop.ui.kit.desktopKitHairlineBorder
import com.bilipai.desktop.ui.kit.desktopKitInteraction
import com.bilipai.desktop.ui.kit.desktopKitSnapStrokePx
import kotlinx.coroutines.flow.Flow

/*
 * The settings chrome of the Windows pages (spec 3.7, slice X3): Pane, Group, Switch and Choice keep their signatures and
 * their call sites; their bodies are the component kit. A group is one rounded container with one-pixel dividers between its
 * children (no card inside a card), a switch is the kit toggle row, and a choice is a segmented control, a stack of radio
 * rows, or (only when the caller asks) a select row. The chooser is the pure desktopSettingsChoiceLayout.
 */

/**
 * True inside DesktopWindowsSettingsGroup, however deeply nested. The group pads every child that is not a row, so a choice
 * there adds no gap of its own; a choice outside a group (a native popover, the layout card) keeps its vertical gap.
 */
internal val LocalDesktopSettingsInGroup = staticCompositionLocalOf { false }

/**
 * The one header row of the settings tree. A Pane inside the tree hands its title and its Back action to this state, so
 * the tree draws a single row with one Back action left of the page title. A Pane outside a tree draws its own row.
 */
internal class DesktopSettingsHeaderState {
    var title by mutableStateOf<String?>(null)
    var onBack by mutableStateOf<(() -> Unit)?>(null)
}

internal val LocalDesktopSettingsHeader = staticCompositionLocalOf<DesktopSettingsHeaderState?> { null }

/** Content that the tree puts above the groups of the Pane it hosts (the home page carries the recommendation fields). */
internal val LocalDesktopSettingsPanePrelude = compositionLocalOf<(@Composable ColumnScope.() -> Unit)?> { null }

/**
 * The layout id of a settings row (a kit toggle row or select row): a row spans the container. Every other child of a group
 * is padded and keeps the width it asks for, up to the padded container; a choice is such a child, not a row.
 */
private const val DESKTOP_SETTINGS_ROW_ID = "desktopSettingsRow"

/** The Back action of the settings chrome: one icon button named by DesktopWindowsSettingsStrings.BACK. */
@Composable
internal fun DesktopSettingsBackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val glyph = MaterialTheme.colorScheme.onSurface
    DesktopIconButton(onClick = onClick, label = DesktopWindowsSettingsStrings.BACK, modifier = modifier) {
        Canvas(Modifier.size(DESKTOP_KIT_ICON_DP.dp)) {
            val stroke = desktopKitSnapStrokePx(1.5f * density)
            val tip = Offset(size.width * 0.2f, size.height * 0.5f)
            drawLine(glyph, Offset(size.width * 0.85f, size.height * 0.5f), tip, strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(glyph, Offset(size.width * 0.45f, size.height * 0.2f), tip, strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(glyph, Offset(size.width * 0.45f, size.height * 0.8f), tip, strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}

/** The header row of a Pane that has no tree around it. */
@Composable
private fun DesktopSettingsPaneHeader(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(DESKTOP_SETTINGS_HEADER_HEIGHT_DP.dp).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DesktopSettingsBackButton(onBack)
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    DesktopKitDivider()
}

/** Window-sized settings content. Exactly this finite pane owns its scrolling. */
@Composable
internal fun DesktopWindowsSettingsPane(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val header = LocalDesktopSettingsHeader.current
    val latestBack by rememberUpdatedState(onBack)
    if (header != null) {
        DisposableEffect(header, title) {
            header.title = title
            header.onBack = { latestBack() }
            onDispose {
                if (header.title == title) {
                    header.title = null
                    header.onBack = null
                }
            }
        }
    }
    val prelude = LocalDesktopSettingsPanePrelude.current
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize()) {
        if (header == null) DesktopSettingsPaneHeader(title, onBack)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                Column(
                    Modifier.widthIn(max = DESKTOP_SETTINGS_CONTENT_MAX_WIDTH_DP.dp).fillMaxWidth()
                        .padding(DESKTOP_SETTINGS_PAGE_PADDING_DP.dp),
                    verticalArrangement = Arrangement.spacedBy(DESKTOP_SETTINGS_SECTION_GAP_DP.dp),
                ) {
                    prelude?.invoke(this)
                    content()
                }
            }
            DesktopKitVerticalScrollbar(scroll)
        }
    }
}

/** The receiver that lets a group enumerate its children: the layout sees each emitted child, which a Column cannot offer. */
private object DesktopSettingsGroupScope : ColumnScope {
    override fun Modifier.weight(weight: Float, fill: Boolean): Modifier = this
    override fun Modifier.align(alignment: Alignment.Horizontal): Modifier = this
    override fun Modifier.alignBy(alignmentLine: VerticalAlignmentLine): Modifier = this
    override fun Modifier.alignBy(alignmentLineBlock: (Measured) -> Int): Modifier = this
}

/** The y positions of the dividers of the last layout pass. Read when drawing, which follows the layout. */
private class DesktopSettingsDividers {
    var ys: IntArray = IntArray(0)
}

/**
 * Stacks the children with a one-pixel line between each two. A settings row is measured exactly as wide as the container.
 * Any other child (a caption, a plain text, a button, a choice, a wrapper of several controls) is padded on every side and
 * measured at most as wide as the padded container, never forced to it, so a button keeps its own width, start aligned.
 * The lines are drawn behind the children, edge to edge.
 */
@Composable
private fun DesktopSettingsGroupLayout(modifier: Modifier, dividerColor: Color, content: @Composable ColumnScope.() -> Unit) {
    val dividers = remember { DesktopSettingsDividers() }
    Layout(
        content = { DesktopSettingsGroupScope.content() },
        modifier = modifier.drawBehind {
            for (y in dividers.ys) drawRect(dividerColor, topLeft = Offset(0f, y.toFloat()), size = Size(size.width, 1f))
        },
    ) { measurables, constraints ->
        val inset = DESKTOP_SETTINGS_ROW_PADDING_DP.dp.roundToPx()
        val vertical = DESKTOP_SETTINGS_ROW_PADDING_VERTICAL_DP.dp.roundToPx()
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val placed = ArrayList<Triple<Placeable, Int, Int>>(measurables.size)
        val lines = ArrayList<Int>(measurables.size)
        var y = 0
        measurables.forEachIndexed { index, measurable ->
            if (index > 0) {
                lines.add(y)
                y += 1
            }
            val isRow = measurable.layoutId == DESKTOP_SETTINGS_ROW_ID
            val side = if (isRow) 0 else inset
            val padding = if (isRow) 0 else vertical
            val inner = (width - 2 * side).coerceAtLeast(0)
            val limits = if (isRow) Constraints(minWidth = inner, maxWidth = inner, minHeight = 0, maxHeight = Constraints.Infinity)
                else Constraints(minWidth = 0, maxWidth = inner, minHeight = 0, maxHeight = Constraints.Infinity)
            val placeable = measurable.measure(limits)
            y += padding
            placed.add(Triple(placeable, side, y))
            y += placeable.height + padding
        }
        dividers.ys = lines.toIntArray()
        layout(width, y) { placed.forEach { (placeable, x, top) -> placeable.placeRelative(x, top) } }
    }
}

@Composable
internal fun DesktopWindowsSettingsGroup(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val radius = DESKTOP_KIT_RADIUS_PANEL_DP.dp
    val shape = RoundedCornerShape(radius)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.padding(horizontal = 4.dp), color = colors.onSurfaceVariant, style = MaterialTheme.typography.titleSmall)
        CompositionLocalProvider(LocalDesktopSettingsInGroup provides true) {
            DesktopSettingsGroupLayout(
                Modifier.fillMaxWidth().clip(shape).background(colors.surfaceContainerLow, shape)
                    .desktopKitHairlineBorder(colors.outlineVariant, radius),
                colors.outlineVariant, content,
            )
        }
    }
}

@Composable
internal fun DesktopWindowsSettingsSwitch(title: String, value: Boolean?, enabled: Boolean = true,
    description: String? = null, onChange: (Boolean) -> Unit) {
    DesktopToggleRow(
        title = title, checked = value, onCheckedChange = onChange, modifier = Modifier.layoutId(DESKTOP_SETTINGS_ROW_ID),
        enabled = enabled, description = description,
    )
}

@Composable
private fun DesktopSettingsChoiceTitle(title: String, enabled: Boolean, modifier: Modifier = Modifier) {
    Text(
        title, modifier.alpha(desktopKitDisabledAlpha(enabled)), color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyMedium,
    )
}

/**
 * One radio row of the stacked list: a ring or a dot, and the label. One selectable node with Role.RadioButton. The row is
 * at least the compact settings height and grows with a label of two lines, so a long label is never clipped.
 */
@Composable
private fun DesktopSettingsRadioRow(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = DESKTOP_KIT_ROW_SETTINGS_COMPACT_DP.dp)
            .alpha(desktopKitDisabledAlpha(enabled))
            .desktopKitInteraction(source, DESKTOP_KIT_RADIUS_ROW_DP.dp, enabled)
            .selectable(
                selected = selected, interactionSource = source, indication = null, enabled = enabled,
                role = Role.RadioButton, onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ring = if (selected) colors.primary else colors.onSurfaceVariant
        Canvas(Modifier.size(16.dp)) {
            val stroke = desktopKitSnapStrokePx(1.5f * density)
            drawCircle(ring, radius = (size.minDimension - stroke) / 2f, style = Stroke(width = stroke))
            if (selected) drawCircle(ring, radius = size.minDimension / 4f)
        }
        Text(
            label, Modifier.weight(1f), color = colors.onSurface, style = MaterialTheme.typography.bodyMedium,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The check of the chosen row of a select menu; the other rows keep the empty slot so the labels line up. */
@Composable
private fun DesktopSettingsCheck(visible: Boolean) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(DESKTOP_KIT_ICON_DP.dp)) {
        if (visible) {
            val stroke = desktopKitSnapStrokePx(1.5f * density)
            val corner = Offset(size.width * 0.42f, size.height * 0.72f)
            drawLine(color, Offset(size.width * 0.2f, size.height * 0.5f), corner, strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(color, corner, Offset(size.width * 0.8f, size.height * 0.28f), strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}

/** The select row with its menu, for the long fixed list that a caller names DROPDOWN. */
@Composable
private fun <T> DesktopSettingsDropdownChoice(
    title: String, value: T?, choices: List<Pair<T, String>>, enabled: Boolean, allowUnselected: Boolean, onPick: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val selectedLabel = choices.firstOrNull { it.first == value }?.second
    val valueLabel = selectedLabel ?: if (value != null || allowUnselected) DesktopWindowsSettingsStrings.UNSELECTED else null
    DesktopSelectRow(
        title = title, valueLabel = valueLabel, onClick = { open = true },
        modifier = Modifier.layoutId(DESKTOP_SETTINGS_ROW_ID), enabled = enabled && (value != null || allowUnselected),
        menu = {
            DesktopPopover(expanded = open, onDismissRequest = { open = false }, label = title) {
                choices.forEach { (candidate, label) ->
                    DesktopMenuRow(
                        label = label, onClick = { open = false; onPick(candidate) },
                        icon = { DesktopSettingsCheck(visible = candidate == value) },
                    )
                }
            }
        },
    )
}

/**
 * A choice among fixed values. AUTO shows a segmented control when there are at most four choices and every label fits, and
 * stacked radio rows otherwise (never a popup, so it is safe in the fixed-size native popovers). DROPDOWN shows a select row
 * and is for a caller whose list is long and fixed (the default quality). Equal to the old chips in what it selects: the
 * click on the chosen value is ignored, nothing is selected while value is null, and an unloaded value keeps the control off.
 */
@Composable
internal fun <T> DesktopWindowsSettingsChoice(title: String, value: T?, choices: List<Pair<T, String>>,
    enabled: Boolean = true, allowUnselected: Boolean = false,
    presentation: DesktopWindowsSettingsChoicePresentation = DesktopWindowsSettingsChoicePresentation.AUTO,
    onChange: (T) -> Unit) {
    val pick: (T) -> Unit = { candidate -> if (value != candidate) onChange(candidate) }
    if (presentation == DesktopWindowsSettingsChoicePresentation.DROPDOWN) {
        DesktopSettingsDropdownChoice(title, value, choices, enabled, allowUnselected, pick)
        return
    }
    val interactive = enabled && (value != null || allowUnselected)
    // Not a row, and no inset of its own: inside a group (even nested in a wrapper of several controls) the group pads it,
    // so it lines up with the rows above and below. Outside a group the host pads the sides and the choice keeps its gap.
    val vertical = if (LocalDesktopSettingsInGroup.current) 0 else DESKTOP_SETTINGS_ROW_PADDING_VERTICAL_DP
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val layout = desktopSettingsChoiceLayout(presentation, choices.map { it.second }, maxWidth.value.toInt())
        val segments = choices.map { DesktopKitSegment(it.first, it.second) }
        Column(Modifier.fillMaxWidth().padding(vertical = vertical.dp)) {
            when (layout.form) {
                DesktopSettingsChoiceForm.SEGMENTED_INLINE -> Row(
                    Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    DesktopSettingsChoiceTitle(title, interactive, Modifier.weight(1f))
                    DesktopSegmentedControl(segments, value, pick, Modifier.width(layout.controlWidthDp.dp), interactive, title)
                }
                DesktopSettingsChoiceForm.SEGMENTED_STACKED -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DesktopSettingsChoiceTitle(title, interactive)
                    DesktopSegmentedControl(segments, value, pick, Modifier.width(layout.controlWidthDp.dp), interactive, title)
                }
                else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DesktopSettingsChoiceTitle(title, interactive)
                    Column(Modifier.selectableGroup().semantics { contentDescription = title }) {
                        choices.forEach { (candidate, label) ->
                            DesktopSettingsRadioRow(label, value == candidate, interactive) { pick(candidate) }
                        }
                    }
                }
            }
        }
    }
}

/** Loading is explicit. An initial schema default is never treated as a disk readback. */
@Composable
internal fun <T> desktopWindowsSettingsValue(flow: Flow<T>): T? = flow.collectAsState(initial = null).value
