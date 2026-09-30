package com.bilipai.desktop.settings

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.android.purebilibili.core.store.*
import com.android.purebilibili.feature.settings.DesktopOriginalHomeCardVisualFields
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

internal fun homeCardVisualBooleanKey(name:String)=DesktopPreferenceKey(name){v:JsonElement->(v as? JsonPrimitive)?.booleanOrNull}
internal fun homeCardVisualIntKey(name:String)=DesktopPreferenceKey(name){v:JsonElement->(v as? JsonPrimitive)?.intOrNull}
internal class DesktopHomeCardVisualEditor(private val snapshot:DesktopPreferenceSnapshot) {
    val changes=linkedMapOf<String,JsonElement>()
    operator fun <T> get(key:DesktopPreferenceKey<T>):T?=changes[key.name]?.let(key.decode)?:snapshot[key]
    operator fun set(key:DesktopPreferenceKey<Boolean>,value:Boolean){changes[key.name]=JsonPrimitive(value)}
    operator fun set(key:DesktopPreferenceKey<Int>,value:Int){changes[key.name]=JsonPrimitive(value)}
}
internal class DesktopHomeCardVisualDataStore(private val context:DesktopPluginContext) {
    val data:StateFlow<DesktopPreferenceSnapshot> get()=context.store.snapshot("settings")
    suspend fun edit(block:(DesktopHomeCardVisualEditor)->Unit)=withContext(Dispatchers.IO){
        context.store.requireObjectNamespace("settings")
        // Original dynamic-tint migration reads and writes together on the actual shared store lock.
        context.store.updateFromSnapshot("settings") {snapshot ->
            DesktopHomeCardVisualEditor(snapshot).apply(block).changes
        }
    }
}
internal val DesktopPluginContext.homeCardVisualDataStore get()=DesktopHomeCardVisualDataStore(this)

/** Same Root global document and settings namespace; no account scope or competing defaults. */
class DesktopHomeCardVisualPreferences(val context:DesktopPluginContext) {
    init{context.store.requireObjectNamespace("settings")}
    fun initialSettings()=DesktopOriginalHomeCardVisualSettings.decode(context.store.snapshot("settings").value)
    val settings=DesktopOriginalHomeCardVisualSettings.getSettings(context).distinctUntilChanged()
    suspend fun setCardAnimationEnabled(v:Boolean)=DesktopOriginalHomeCardVisualSettings.setCardAnimationEnabled(context,v)
    suspend fun setCompactVideoStatsOnCover(v:Boolean)=DesktopOriginalHomeCardVisualSettings.setCompactVideoStatsOnCover(context,v)
    suspend fun setHomeUpBadgesVisible(v:Boolean)=DesktopOriginalHomeCardVisualSettings.setHomeUpBadgesVisible(context,v)
    suspend fun setHomeUpAvatarsVisible(v:Boolean)=DesktopOriginalHomeCardVisualSettings.setHomeUpAvatarsVisible(context,v)
    suspend fun setHomePublishTimeVisible(v:Boolean)=DesktopOriginalHomeCardVisualSettings.setHomePublishTimeVisible(context,v)
    suspend fun setFullVideoCardContentVisible(v:Boolean)=DesktopOriginalHomeCardVisualSettings.setFullVideoCardContentVisible(context,v)
    suspend fun setVideoCardLongPressActionEnabled(v:Boolean)=DesktopOriginalHomeCardVisualSettings.setVideoCardLongPressActionEnabled(context,v)
    suspend fun setHomeCardDynamicTintEnabled(v:Boolean)=DesktopOriginalHomeCardVisualSettings.setHomeCardDynamicTintEnabled(context,v)
    suspend fun setHomeDurationStyle(v:HomeDurationStyle)=DesktopOriginalHomeCardVisualSettings.setHomeDurationStyle(context,v)
    suspend fun setShowOnlineCount(v:Boolean)=DesktopOriginalHomeCardVisualSettings.setShowOnlineCount(context,v)
}

@Composable
fun DesktopHomeCardVisualSettingsSection(preferences:DesktopHomeCardVisualPreferences,onFailure:(Throwable)->Unit,modifier:Modifier=Modifier) {
    val state by preferences.settings.collectAsState(preferences.initialSettings());val scope=rememberCoroutineScope()
    val writer=remember(preferences){Mutex()};val failure by rememberUpdatedState(onFailure)
    fun write(action:suspend()->Unit){scope.launch{try{writer.withLock{action()}}catch(cancelled:CancellationException){throw cancelled}catch(error:Exception){failure(error)}}}
    androidx.compose.foundation.layout.Column(modifier){DesktopOriginalHomeCardVisualFields(state,
        {write{preferences.setCardAnimationEnabled(it)}},
        {write{preferences.setCompactVideoStatsOnCover(it)}},
        {write{preferences.setHomeUpBadgesVisible(it)}},
        {write{preferences.setHomeUpAvatarsVisible(it)}},
        {write{preferences.setHomePublishTimeVisible(it)}},
        {write{preferences.setFullVideoCardContentVisible(it)}},
        {write{preferences.setVideoCardLongPressActionEnabled(it)}},
        {write{preferences.setHomeCardDynamicTintEnabled(it)}},
        {write{preferences.setHomeDurationStyle(it)}},
        {write{preferences.setShowOnlineCount(it)}})}
}
