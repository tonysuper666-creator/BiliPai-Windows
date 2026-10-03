package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import com.android.purebilibili.feature.settings.DesktopOriginalIconSettingsScreen
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.settings.DesktopOriginalAppIconPreferences
import com.bilipai.desktop.settings.DesktopOriginalIconSettingsBindings
import java.util.concurrent.atomic.AtomicReference

/** The actual IconSettings NavDisplay entry owns its screen requests. Window icon observation
 * is mounted separately at ReadyRoot window scope and keeps following the same shared Store. */
@Composable
internal fun DesktopOriginalIconSettingsRootHost(
    routes: DesktopOriginalRootRouteAssembly,
    handleReference: AtomicReference<DesktopReadyOriginalRootHandle?>,
    context: DesktopPluginContext,
    imageLifetime: DesktopImageSaveLifetime,
    active: Boolean,
    onFailure: (Throwable) -> Unit,
    onNotice: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val activeNow by rememberUpdatedState(active)
    val failureNow by rememberUpdatedState(onFailure)
    val noticeNow by rememberUpdatedState(onNotice)
    val capturedHandle = remember(routes) { requireNotNull(handleReference.get()) }
    val current = {
        activeNow && handleReference.get() === capturedHandle && capturedHandle.isActive() &&
            capturedHandle.route.get() === routes && routes.owns() &&
            routes.currentKey == BiliPaiNavKey.IconSettings && imageLifetime.isActive()
    }
    val preferences = remember(routes, capturedHandle, context, imageLifetime) {
        DesktopOriginalAppIconPreferences(context, current) { action ->
            var applied = false
            val accepted = routes.root.entry.gate.commit {
                // Existing lock order: SessionStore -> entry -> image/window lifetime.
                // Only Store permit creation runs here; stage/fsync/Store CAS remain outside.
                imageLifetime.withCommit {
                    if (current()) { action(); applied = true }
                }
            }
            accepted && applied
        }
    }
    val bindings = remember(preferences, scope) {
        DesktopOriginalIconSettingsBindings(preferences, scope,
            onFailure = { if (current()) failureNow(it) },
            onNotice = { if (current()) noticeNow(it) })
    }
    DesktopOriginalIconSettingsScreen(bindings, onBack = {
        routes.callbackFor(BiliPaiNavKey.IconSettings) { routes.back() }
    })
}
