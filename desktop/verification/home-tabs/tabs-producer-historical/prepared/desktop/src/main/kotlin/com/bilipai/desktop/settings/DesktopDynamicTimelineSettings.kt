package com.bilipai.desktop.settings

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Column
import com.android.purebilibili.core.store.DesktopDynamicSettings
import com.android.purebilibili.feature.settings.DesktopDynamicTimelineSettingsFields
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Same Root settings namespace/backing; the original keys/defaults remain in generated source. */
internal fun dynamicIntPreferencesKey(name:String)=DesktopPreferenceKey(name){value:JsonElement->
    (value as? JsonPrimitive)?.intOrNull}
internal class DesktopDynamicPreferenceEditor {
    val values=linkedMapOf<String,JsonElement>()
    operator fun <T> set(key:DesktopPreferenceKey<T>,value:T) {
        values[key.name]=when(value){is Boolean->JsonPrimitive(value);is Int->JsonPrimitive(value)
            is String->JsonPrimitive(value);else->error("Unsupported original dynamic preference value")}
    }
}
internal class DesktopDynamicSettingsDataStore(private val context:DesktopPluginContext) {
    val data:StateFlow<DesktopPreferenceSnapshot> get()=context.store.snapshot("settings")
    suspend fun edit(block:(DesktopDynamicPreferenceEditor)->Unit)=withContext(Dispatchers.IO) {
        val editor=DesktopDynamicPreferenceEditor().apply(block)
        context.store.requireObjectNamespace("settings")
        context.store.update("settings",editor.values)
    }
}
internal val DesktopPluginContext.dynamicSettingsDataStore get()=DesktopDynamicSettingsDataStore(this)

class DesktopDynamicTimelinePreferences(val context:DesktopPluginContext) {
    init {context.store.requireObjectNamespace("settings")}
    val incrementalRefresh:Flow<Boolean> = DesktopDynamicSettings.getIncrementalTimelineRefresh(context)
    val layoutMode:Flow<DesktopDynamicSettings.DynamicFeedLayoutMode> = DesktopDynamicSettings.getDynamicFeedLayoutMode(context)
    suspend fun setIncrementalRefresh(value:Boolean)=DesktopDynamicSettings.setIncrementalTimelineRefresh(context,value)
    suspend fun setLayoutMode(value:DesktopDynamicSettings.DynamicFeedLayoutMode)=DesktopDynamicSettings.setDynamicFeedLayoutMode(context,value)
}

/** Root must provide its existing global store instance. No guest/account fallback store is created. */
val LocalDesktopDynamicTimelinePreferences=staticCompositionLocalOf<DesktopDynamicTimelinePreferences?>{null}

@Composable
internal fun DesktopDynamicTimelineSettings(preferences:DesktopDynamicTimelinePreferences,onFailure:(Throwable)->Unit,modifier:Modifier=Modifier) {
    val incremental by preferences.incrementalRefresh.collectAsState(false)
    val layout by preferences.layoutMode.collectAsState(DesktopDynamicSettings.DynamicFeedLayoutMode.WATERFALL)
    val scope=rememberCoroutineScope();val writer=remember(preferences){Mutex()};val currentFailure by rememberUpdatedState(onFailure)
    fun write(operation:suspend ()->Unit) {scope.launch {
        try {writer.withLock{operation()}}
        catch(cancelled:CancellationException){throw cancelled}
        catch(failure:Exception){currentFailure(failure)}
    }}
    Column(modifier) {
        DesktopDynamicTimelineSettingsFields(incremental,{value->write{preferences.setIncrementalRefresh(value)}},
            layout,{value->write{preferences.setLayoutMode(value)}})
        val tabs=remember(preferences){DesktopDynamicTabsPreferences(preferences.context)}
        DesktopDynamicTabsSettings(tabs,onFailure)
    }
}
