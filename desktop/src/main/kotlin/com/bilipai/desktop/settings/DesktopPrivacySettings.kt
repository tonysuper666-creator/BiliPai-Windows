package com.bilipai.desktop.settings

import com.android.purebilibili.core.store.DesktopPrivacyAuthenticationSettings
import com.android.purebilibili.core.store.SearchHintSettingsStore
import com.bilipai.desktop.data.DesktopSearchPreferences
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext

/** Original independent Boolean settings keys, in the supplied Root shared backing. */
internal class DesktopPrivacySettingsDataStore(private val context: DesktopPluginContext) {
    val data: StateFlow<DesktopPreferenceSnapshot> get() = context.store.snapshot("settings")
    suspend fun edit(block: (DesktopPreferenceEditor) -> Unit) = withContext(Dispatchers.IO) {
        val editor = DesktopPreferenceEditor().apply(block)
        // Merge only changed keys under the existing backing lock. No cached document copy.
        context.store.update("settings", editor.values)
    }
}
internal val DesktopPluginContext.privacySettingsDataStore get() = DesktopPrivacySettingsDataStore(this)

/** Both references must be the Root's existing instances; no second privacy facade/file. */
class DesktopPrivacySectionBindings(
    val context: DesktopPluginContext,
    val searchPreferences: DesktopSearchPreferences,
) {
    val personalRecapEnabled = com.bilipai.desktop.ui.DesktopPersonalRecapSettings.getSubscriptionRecapEnabled(context)
    val defaultHintEnabled: Flow<Boolean> = SearchHintSettingsStore.isEnabled(context)
    val authenticationConfigured: Flow<Boolean> = DesktopPrivacyAuthenticationSettings.getPrivacyContentAuthenticationEnabled(context)
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    suspend fun setDefaultHintEnabled(enabled: Boolean): Boolean = write {
        SearchHintSettingsStore.setEnabled(context, enabled)
    }
    suspend fun setPersonalRecapEnabled(settings: com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext, enabled: Boolean): Boolean = write {
        require(settings.pluginContext.store === context.store) { "Recap settings must use Root's existing Store" }
        com.bilipai.desktop.ui.DesktopPersonalRecapSettings.setSubscriptionRecapEnabled(settings, enabled)
    }
    suspend fun setPrivacyMode(enabled: Boolean): Boolean = write { searchPreferences.setPrivacyMode(enabled) }
    suspend fun setSuggestionsEnabled(enabled: Boolean): Boolean = write { searchPreferences.setSuggestionsEnabled(enabled) }

    private suspend fun write(operation: suspend () -> Unit): Boolean = try {
        operation(); _error.value = null; true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        _error.value = "隐私设置保存失败，原设置保持不变"
        false
    }
}
