package com.bilipai.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bilipai.desktop.ui.kit.DESKTOP_KIT_CONTROL_HEIGHT_DP
import com.bilipai.desktop.ui.kit.DESKTOP_KIT_RADIUS_CONTROL_DP
import com.bilipai.desktop.ui.kit.DESKTOP_KIT_ROW_LIST_DP
import com.bilipai.desktop.ui.kit.DesktopKitToastEntry
import com.bilipai.desktop.ui.kit.DesktopKitToastState
import com.bilipai.desktop.ui.kit.desktopKitInteraction
import kotlinx.coroutines.delay

/*
 * The shell toast (spec 3.4): one glass pill at a time, 2.4 s, or 5 s when it carries an action. It does not replace the
 * root SnackbarHost. Callers get a DesktopToaster from LocalDesktopToaster, whose default does nothing, so a screen or a
 * fixture that has no host still works; the shell provides a real DesktopToastController and mounts one
 * DesktopToastHost above the now-playing bar. The text comes from the caller (the strings objects own every label).
 *
 * The one-at-a-time rule, the trimming and the durations are the pure DesktopKitToastState of the kit (mirrored by
 * the contract); this file only holds the callbacks and draws.
 */

/** What a caller may do with the toast host. */
internal interface DesktopToaster {
    /** Shows the message now, replacing any toast on screen. A blank message is ignored. onAction runs once if the action is pressed. */
    fun show(message: String, actionLabel: String? = null, onAction: (() -> Unit)? = null)
}

/** The default toaster: drops everything. */
internal object DesktopNoopToaster : DesktopToaster {
    override fun show(message: String, actionLabel: String?, onAction: (() -> Unit)?) = Unit
}

internal val LocalDesktopToaster = staticCompositionLocalOf<DesktopToaster> { DesktopNoopToaster }

/** The real toaster: pure state in a snapshot state, plus the one callback of the toast on screen. */
internal class DesktopToastController : DesktopToaster {
    var state: DesktopKitToastState by mutableStateOf(DesktopKitToastState())
        private set
    private var pendingAction: (() -> Unit)? = null

    override fun show(message: String, actionLabel: String?, onAction: (() -> Unit)?) {
        val next = state.show(message, actionLabel)
        if (next === state) return
        state = next
        pendingAction = if (next.current?.actionLabel != null) onAction else null
    }

    /** Closes the toast with this id; a replaced toast's id is ignored. */
    fun dismiss(id: Long) {
        state = state.dismiss(id)
    }

    /** The action of the toast with this id was pressed: close it, then run its callback once. */
    fun invokeAction(id: Long) {
        if (state.current?.id != id) return
        val action = pendingAction
        pendingAction = null
        state = state.dismiss(id)
        action?.invoke()
    }
}

@Composable
internal fun rememberDesktopToastController(): DesktopToastController = remember { DesktopToastController() }

/**
 * The pill, bottom centre of the Box that holds it. bottomInset lifts it above other bottom chrome (the now-playing bar).
 * lifetimeMillis decides how long each toast stays and defaults to the kit durations; a test shortens it.
 */
@Composable
internal fun BoxScope.DesktopToastHost(
    controller: DesktopToastController,
    modifier: Modifier = Modifier,
    bottomInset: Dp = 24.dp,
    lifetimeMillis: (DesktopKitToastEntry) -> Long = { it.durationMs },
) {
    val entry = controller.state.current ?: return
    LaunchedEffect(entry.id) {
        delay(lifetimeMillis(entry))
        controller.dismiss(entry.id)
    }
    val colors = MaterialTheme.colorScheme
    DesktopWindowsGlassSurface(
        modifier
            .align(Alignment.BottomCenter)
            .padding(start = 16.dp, end = 16.dp, bottom = bottomInset)
            .widthIn(max = 520.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = CircleShape,
    ) {
        Row(
            Modifier
                .defaultMinSize(minHeight = DESKTOP_KIT_ROW_LIST_DP.dp)
                .padding(start = 18.dp, end = if (entry.actionLabel != null) 8.dp else 18.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                entry.message, Modifier.weight(1f, fill = false), color = colors.onSurface,
                style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            val label = entry.actionLabel
            if (label != null) DesktopToastAction(label) { controller.invokeAction(entry.id) }
        }
    }
}

/** The action of a toast: one 32 dp button in the accent color. */
@Composable
private fun DesktopToastAction(label: String, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .height(DESKTOP_KIT_CONTROL_HEIGHT_DP.dp)
            .desktopKitInteraction(source, DESKTOP_KIT_RADIUS_CONTROL_DP.dp)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}
