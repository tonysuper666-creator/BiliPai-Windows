package com.bilipai.desktop.update

import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.plugins.DesktopPreferenceKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** The original durable opt-out controls background checks only. collectLatest cancels
 * the actual updater request; explicit manual check() does not go through this owner. */
internal suspend fun followDesktopAutomaticUpdateChecks(
    store: DesktopPluginStore,
    owns: () -> Boolean,
    check: suspend () -> Unit,
    intervalMs: Long = DesktopUpdater.AUTO_CHECK_INTERVAL_MS,
) {
    require(intervalMs > 0)
    store.snapshot("settings").map {
        it[DesktopPreferenceKey("auto_check_app_update") { raw ->
            (raw as? JsonPrimitive)?.takeUnless { value -> value.isString }?.booleanOrNull }] ?: true
    }.distinctUntilChanged().collectLatest { enabled ->
        if (enabled) while (true) {
            currentCoroutineContext().ensureActive()
            if (!owns()) throw CancellationException("Windows update check owner retired")
            check()
            delay(intervalMs)
        }
    }
}
