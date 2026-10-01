package com.bilipai.desktop.ui
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonPrimitive

internal data class DesktopOriginalRootAudioBarPreferences(val enabled:Boolean,val opensAudioMode:Boolean)
internal class DesktopOriginalRootNavigationPreferences(
    private val store:DesktopPluginStore,scope:CoroutineScope,private val isCurrent:()->Boolean,
    private val commitIfWindowCurrent:((()->Unit)->Boolean),
) {
    private val snapshot=store.snapshot("settings")
    private fun read(values:DesktopPreferenceSnapshot)=DesktopOriginalRootAudioBarPreferences(
        values[booleanPreferencesKey("audio_now_playing_bar_enabled")]?:true,
        values[booleanPreferencesKey("audio_now_playing_bar_opens_audio_mode")]?:false)
    val realtimeTransitionBlur=snapshot.map{it[booleanPreferencesKey("video_transition_realtime_blur_enabled")]?:false}
        .distinctUntilChanged().stateIn(scope,SharingStarted.Eagerly,snapshot.value[booleanPreferencesKey("video_transition_realtime_blur_enabled")]?:false)
    val directPortraitStoryEntry=snapshot.map{it[booleanPreferencesKey("auto_portrait_fullscreen")]?:false}
        .distinctUntilChanged().stateIn(scope,SharingStarted.Eagerly,snapshot.value[booleanPreferencesKey("auto_portrait_fullscreen")]?:false)
    val audio=snapshot.map(::read).distinctUntilChanged().stateIn(scope,SharingStarted.Eagerly,read(snapshot.value))
    suspend fun setSidebarExpanded(expanded:Boolean)=withContext(Dispatchers.IO){
        ensureActive()
        if(!isCurrent())throw CancellationException("Root window settings retired")
        val accepted=commitIfWindowCurrent {
            if(!isCurrent())throw CancellationException("Root window settings retired")
            store.update("settings",mapOf("sidebar_expanded" to JsonPrimitive(expanded)))
        }
        if(!accepted)throw CancellationException("Root window settings retired")
    }
}
