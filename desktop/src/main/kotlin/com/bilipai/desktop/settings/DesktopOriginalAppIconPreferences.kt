package com.bilipai.desktop.settings

import com.android.purebilibili.core.store.AppIconAppearance
import com.android.purebilibili.core.store.DEFAULT_APP_ICON_KEY
import com.android.purebilibili.core.store.normalizeAppIconKey
import com.android.purebilibili.core.store.resolveAppIconAppearance
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.plugins.DesktopPreferenceKey
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

/** Same global Store, canonical original settings keys and original startup mirror.
 * Only short permit creation enters Root admission; all file IO remains outside it. */
internal class DesktopOriginalAppIconPreferences(
    val context: DesktopPluginContext,
    private val owns: () -> Boolean,
    private val admit: ((() -> Unit) -> Boolean),
) {
    private val key = DesktopPreferenceKey("app_icon_key") { value ->
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
    private val appearanceKey = DesktopPreferenceKey("app_icon_appearance") { value ->
        (value as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
    }
    val state: Flow<DesktopOriginalIconSettingsState> = context.store.snapshot("settings")
        .map { DesktopOriginalIconSettingsState(normalizeAppIconKey(it[key])) }.distinctUntilChanged()
    val appearance: Flow<AppIconAppearance> = context.store.snapshot("settings")
        .map { resolveAppIconAppearance(it[appearanceKey] ?: 0) }.distinctUntilChanged()
    val initialState get() = DesktopOriginalIconSettingsState(normalizeAppIconKey(context.store.snapshot("settings").value[key]))
    val initialAppearance get() = resolveAppIconAppearance(context.store.snapshot("settings").value[appearanceKey] ?: 0)

    fun requireOwned() { if (!owns()) throw CancellationException("Original icon settings owner retired") }
    suspend fun setAppIcon(rawKey: String) = write(
        mapOf("app_icon_key" to JsonPrimitive(normalizeAppIconKey(rawKey))),
        mapOf("current_icon" to JsonPrimitive(normalizeAppIconKey(rawKey))),
    )
    suspend fun setAppIconAppearance(value: AppIconAppearance) = write(
        mapOf("app_icon_appearance" to JsonPrimitive(value.storedValue)),
        mapOf("appearance" to JsonPrimitive(value.storedValue)),
    )
    private suspend fun write(settings: Map<String, JsonElement>, mirror: Map<String, JsonElement>) = withContext(Dispatchers.IO) {
        val caller = currentCoroutineContext()
        fun checkRequest() { caller.ensureActive(); requireOwned() }
        fun acquirePermit(): DesktopPluginStore.OriginalPreferenceWritePermit {
            lateinit var permit: DesktopPluginStore.OriginalPreferenceWritePermit
            checkRequest()
            if (!admit { checkRequest(); permit = DesktopPluginStore.OriginalPreferenceWritePermit(context.store) })
                throw CancellationException("Original icon settings admission rejected")
            return permit
        }
        context.store.updateOriginalNamespacesFromSnapshot("settings", ::checkRequest, ::acquirePermit) {
            Unit to mapOf("settings" to settings, "app_icon_cache" to mirror)
        }
    }
}

internal data class DesktopOriginalIconSettingsState(val appIcon: String = DEFAULT_APP_ICON_KEY)

/** Owns only screen jobs and messages; persistence remains the shared Root Store. */
internal class DesktopOriginalIconSettingsBindings(
    val preferences: DesktopOriginalAppIconPreferences,
    private val scope: CoroutineScope,
    private val onFailure: (Throwable) -> Unit,
    private val onNotice: (String) -> Unit,
) {
    val state get() = preferences.state
    val iconAppearance get() = preferences.appearance
    val initialState get() = preferences.initialState
    val initialAppearance get() = preferences.initialAppearance
    fun showSwitchNotice() { preferences.requireOwned(); onNotice("正在切换图标…") }
    fun setAppIcon(value: String) = update { preferences.setAppIcon(value) }
    fun setAppIconAppearance(value: AppIconAppearance) = update { preferences.setAppIconAppearance(value) }
    private fun update(block: suspend () -> Unit) {
        preferences.requireOwned()
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { currentCoroutineContext().ensureActive(); preferences.requireOwned(); onFailure(failure) }
        }
    }
}
