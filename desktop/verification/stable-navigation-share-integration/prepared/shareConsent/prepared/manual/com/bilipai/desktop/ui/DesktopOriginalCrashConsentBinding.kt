package com.bilipai.desktop.ui

import com.bilipai.desktop.diagnostics.DesktopDiagnostics
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonPrimitive

/** Windows maps remote Crashlytics consent to explicit ENHANCED LOCAL logging.
 * Original crash-tracking keys stay in the same global namespace. Basic crash
 * snapshot retention remains the original independent always-on policy. */
internal class DesktopOriginalCrashConsentBinding(
    private val diagnostics:DesktopDiagnostics,
    private val owned:()->Boolean,
    private val commit:((()->Unit)->Boolean),
    private val applyOriginalChoice:suspend(Boolean,()->Boolean,((()->Unit)->Boolean))->Unit,
) : DesktopCrashConsentBindings {
    override val enhancedEnabled:Boolean get()=diagnostics.enhancedEnabled.value
    override suspend fun saveChoice(enabled:Boolean) {
        currentCoroutineContext().ensureActive()
        if(!owned())throw CancellationException("诊断授权页面已退役")
        applyOriginalChoice(enabled,owned,commit)
    }
}
