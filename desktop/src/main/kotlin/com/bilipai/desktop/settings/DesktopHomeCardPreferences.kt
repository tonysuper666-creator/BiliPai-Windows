package com.bilipai.desktop.settings

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.store.*
import com.android.purebilibili.core.util.WindowWidthSizeClass
import com.android.purebilibili.core.util.resolveWindowWidthSizeClass
import com.android.purebilibili.feature.settings.DesktopOriginalHomeCardFields
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

internal fun homeCardIntPreferencesKey(name:String)=DesktopPreferenceKey(name){value:JsonElement->
    (value as? JsonPrimitive)?.intOrNull}
internal class DesktopHomeCardPreferenceEditor {
    val values=linkedMapOf<String,JsonElement>()
    operator fun set(key:DesktopPreferenceKey<Int>,value:Int){values[key.name]=JsonPrimitive(value)}
}
internal class DesktopHomeCardDataStore(private val context:DesktopPluginContext) {
    val data:StateFlow<DesktopPreferenceSnapshot> get()=context.store.snapshot("settings")
    suspend fun edit(block:(DesktopHomeCardPreferenceEditor)->Unit)=withContext(Dispatchers.IO) {
        val editor=DesktopHomeCardPreferenceEditor().apply(block)
        context.store.requireObjectNamespace("settings")
        context.store.update("settings",editor.values)
    }
}
internal val DesktopPluginContext.homeCardSettingsDataStore get()=DesktopHomeCardDataStore(this)

/** Exact original read mapper and setters on the existing Root global backing generation. */
class DesktopHomeCardPreferences(val context:DesktopPluginContext) {
    init {context.store.requireObjectNamespace("settings")}
    fun initialSettings():DesktopHomeCardSettings = DesktopOriginalHomeCardSettings.decode(context.store.snapshot("settings").value)
    val settings:Flow<DesktopHomeCardSettings> = DesktopOriginalHomeCardSettings.getSettings(context).distinctUntilChanged()
    suspend fun setGridColumnCount(value:Int)=DesktopOriginalHomeCardSettings.setGridColumnCount(context,value)
    suspend fun setGridColumnCountCompact(value:Int)=DesktopOriginalHomeCardSettings.setGridColumnCountCompact(context,value)
    suspend fun setWidthPreset(value:HomeFeedCardWidthPreset)=DesktopOriginalHomeCardSettings.setHomeFeedCardWidthPreset(context,value)
    suspend fun setStyle(value:HomeFeedCardStyle)=DesktopOriginalHomeCardSettings.setHomeFeedCardStyle(context,value)
}
val LocalDesktopHomeCardPreferences=staticCompositionLocalOf<DesktopHomeCardPreferences?>{null}

/** Root mounts this within its existing Home settings route and theme; no second store is opened. */
@Composable
fun DesktopHomeCardSettingsSection(preferences:DesktopHomeCardPreferences,onFailure:(Throwable)->Unit,modifier:Modifier=Modifier) {
    val state by preferences.settings.collectAsState(preferences.initialSettings())
    val scope=rememberCoroutineScope();val writer=remember(preferences){Mutex()}
    val currentFailure by rememberUpdatedState(onFailure)
    val windowDp=(LocalWindowInfo.current.containerSize.width/LocalDensity.current.density).dp
    fun write(operation:suspend ()->Unit){scope.launch {
        try {writer.withLock{operation()}}
        catch(cancelled:CancellationException){throw cancelled}
        catch(failure:Exception){currentFailure(failure)}
    }}
    androidx.compose.foundation.layout.Column(modifier) {
        DesktopOriginalHomeCardFields(state,resolveWindowWidthSizeClass(windowDp)!=WindowWidthSizeClass.Compact,
            {value->write{preferences.setGridColumnCount(value)}},
            {value->write{preferences.setWidthPreset(value)}},
            {value->write{preferences.setStyle(value)}})
        val visualPreferences=remember(preferences.context){DesktopHomeCardVisualPreferences(preferences.context)}
        DesktopHomeCardVisualSettingsSection(visualPreferences,onFailure)
    }
}
