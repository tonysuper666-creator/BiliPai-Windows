package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.focusable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Selection follows queue task IDs, never row positions or task object instances. */
@Stable
class DesktopDownloadListSelection {
    var selected by mutableStateOf<Set<String>>(emptySet()); private set
    var focused by mutableStateOf<String?>(null); private set
    var anchor by mutableStateOf<String?>(null); private set
    var contextTarget by mutableStateOf<String?>(null); private set
    var pendingDelete by mutableStateOf<Set<String>>(emptySet()); private set
    private var order = emptyList<String>()
    private var additiveRangeBase: Set<String>? = null

    fun reconcile(ids: List<String>) {
        require(ids.size == ids.distinct().size) { "Duplicate download task ID" }
        order = ids.toList()
        val available = ids.toSet()
        selected = selected.intersect(available)
        additiveRangeBase = additiveRangeBase?.intersect(available)
        if (focused !in available) focused = null
        if (anchor !in available) anchor = null
        if (contextTarget !in available) contextTarget = null
        pendingDelete = pendingDelete.intersect(available)
    }
    fun click(id: String, ctrl: Boolean = false, shift: Boolean = false) {
        if (id !in order) return
        focused = id
        if (shift && anchor in order) {
            val first = order.indexOf(anchor)
            val last = order.indexOf(id)
            val range = order.subList(minOf(first, last), maxOf(first, last) + 1).toSet()
            selected = if (ctrl) {
                val base = additiveRangeBase ?: selected.also { additiveRangeBase = it }
                base + range
            } else {
                additiveRangeBase = null
                range
            }
        } else {
            additiveRangeBase = null
            selected = if (ctrl) {
                if (id in selected) selected - id else selected + id
            } else setOf(id)
            anchor = id
        }
    }
    fun move(delta: Int) {
        if (order.isEmpty()) return
        val index = order.indexOf(focused)
        val next = if (index < 0) { if (delta < 0) order.lastIndex else 0 }
                   else (index + delta).coerceIn(0, order.lastIndex)
        focused = order[next]
    }
    fun toggleFocused() { focused?.let { click(it, ctrl = true) } }
    fun openContextMenu(id: String) {
        if (id !in order) return
        focused = id
        contextTarget = id
    }
    fun closeContextMenu() { contextTarget = null }
    fun requestDelete(ids: Set<String>) { pendingDelete = ids.intersect(order.toSet()) }
    fun cancelDelete() { pendingDelete = emptySet() }
    fun confirmDelete(deleteFiles: Boolean, remove: (String, Boolean) -> Unit) {
        val confirmed = pendingDelete
        pendingDelete = emptySet()
        additiveRangeBase = null
        confirmed.forEach { remove(it, deleteFiles) }
        selected = selected - confirmed
    }
}

fun Modifier.downloadSelectionKeyboard(
    selection: DesktopDownloadListSelection,
    focus: FocusRequester,
): Modifier = onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) false else when (event.key) {
        Key.DirectionUp -> { selection.move(-1); true }
        Key.DirectionDown -> { selection.move(1); true }
        Key.Spacebar -> { selection.toggleFocused(); true }
        else -> false
    }
}.focusRequester(focus).focusable()

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun androidx.compose.ui.Modifier.downloadSelectionPointer(
    id: String,
    selection: DesktopDownloadListSelection,
    focus: androidx.compose.ui.focus.FocusRequester,
): androidx.compose.ui.Modifier = this.then(
    androidx.compose.ui.Modifier.pointerInput(id, selection, focus) {
        awaitPointerEventScope {
            var captured: PointerId? = null
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press) {
                    val down = event.changes.firstOrNull { it.pressed && !it.previousPressed } ?: continue
                    val secondary = event.buttons.isSecondaryPressed
                    val primary = event.buttons.isPrimaryPressed
                    val ctrl = event.keyboardModifiers.isCtrlPressed
                    val shift = event.keyboardModifiers.isShiftPressed
                    if (secondary || primary) {
                        focus.requestFocus()
                        if (secondary) selection.openContextMenu(id) else selection.click(id, ctrl, shift)
                    }
                    if (secondary || (primary && (ctrl || shift))) captured = down.id
                }
                captured?.let { id ->
                    val change = event.changes.firstOrNull { it.id == id }
                    if (change != null) {
                        change.consume()
                        if (!change.pressed) captured = null
                    }
                }
            }
        }
    },
)

@androidx.compose.runtime.Composable
fun DesktopDownloadTaskFrame(
    coverContent: @androidx.compose.runtime.Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
    infoContent: @androidx.compose.runtime.Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    coverModifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    coverOverlayContent: @androidx.compose.runtime.Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
    trailingContent: @androidx.compose.runtime.Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
    coverAspectRatio: Float,
    stacked: Boolean,
) {
    androidx.compose.foundation.layout.Box(modifier) {
        androidx.compose.foundation.layout.Row(
            modifier = androidx.compose.ui.Modifier.fillMaxWidth().padding(end = 48.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        ) {
            androidx.compose.foundation.layout.Box(coverModifier.size(96.dp, 54.dp)) {
                coverContent()
                coverOverlayContent()
            }
            androidx.compose.foundation.layout.Column(modifier = androidx.compose.ui.Modifier.weight(1f), content = infoContent)
        }
        trailingContent()
    }
}
