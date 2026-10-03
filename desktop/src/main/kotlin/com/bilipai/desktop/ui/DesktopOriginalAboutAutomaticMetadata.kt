package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import com.android.purebilibili.feature.onboarding.APP_WELCOME_PREFS_NAME
import com.android.purebilibili.feature.onboarding.USER_AGREEMENT_ACK_KEY
import com.android.purebilibili.feature.settings.AppUpdateAutoCheckGate
import com.android.purebilibili.feature.settings.shouldRunAppEntryAutoCheck
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.settings.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicReference

/** Real retained Root startup consumer of original auto/channel preferences. Agreement ACK
 * is observed from the same durable Store. No release network runs before original consent. */
@Composable internal fun DesktopOriginalAboutAutomaticMetadata(
    routes: DesktopOriginalRootRouteAssembly,
    handleReference: AtomicReference<DesktopReadyOriginalRootHandle?>,
    context: DesktopPluginContext,
    imageLifetime: DesktopImageSaveLifetime,
    metadata: DesktopOriginalAboutReleaseMetadata,
    rootAlive: () -> Boolean,
    onNotice: (String) -> Unit,
) {
    val captured = remember(routes) { requireNotNull(handleReference.get()) }
    val rootAliveNow by rememberUpdatedState(rootAlive)
    val noticeNow by rememberUpdatedState(onNotice)
    val current = {
        handleReference.get() === captured && captured.isActive() && captured.route.get() === routes &&
            captured.retainer.root.value === routes.root && routes.root.entry.gate.scope.isActive &&
            rootAliveNow() && imageLifetime.isActive()
    }
    val admit: ((() -> Unit) -> Boolean) = remember(routes, captured, imageLifetime) {
        { action ->
            var applied = false
            val accepted = routes.root.entry.gate.commit {
                imageLifetime.withCommit { if (current()) { action(); applied = true } }
            }
            accepted && applied
        }
    }
    val preferences = remember(routes, context) { DesktopOriginalAboutPreferences(context, current, admit) }
    val autoCheck by preferences.autoCheck.collectAsState(preferences.initialAutoCheck)
    val channel by preferences.channel.collectAsState(preferences.initialChannel)
    val acknowledged by remember(context) {
        context.store.snapshot(APP_WELCOME_PREFS_NAME).map { snapshot ->
            snapshot[DesktopPreferenceKey(USER_AGREEMENT_ACK_KEY) { raw ->
                (raw as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull }] == true
        }
    }.collectAsState(false)
    LaunchedEffect(routes, autoCheck, channel, acknowledged) {
        if (!acknowledged || !current() || !autoCheck) return@LaunchedEffect
        if (!shouldRunAppEntryAutoCheck(autoCheck, AppUpdateAutoCheckGate.tryMarkChecked()))
            return@LaunchedEffect
        try {
            val result = metadata.check(DESKTOP_ORIGINAL_ABOUT_VERSION, DESKTOP_ORIGINAL_ABOUT_VERSION_CODE,
                channel == DesktopOriginalAboutSettings.AppUpdateChannel.BETA, current, admit, silent = true)
            ensureActive()
            val info = result?.getOrNull()
            if (info?.isUpdateAvailable == true && current()) {
                // This is upstream metadata; Windows installation belongs to the existing updater.
                admit { noticeNow("上游 ${info.message}；可在系统与关于查看更新日志。") }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        // Original silent startup failure leaves the previous release evidence and no error dialog.
        catch (_: Exception) { }
    }
}
