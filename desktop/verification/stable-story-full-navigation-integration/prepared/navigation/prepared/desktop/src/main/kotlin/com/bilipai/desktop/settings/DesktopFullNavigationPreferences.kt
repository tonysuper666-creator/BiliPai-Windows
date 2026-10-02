package com.bilipai.desktop.settings

import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*

internal fun fullNavigationBooleanKey(name:String)=DesktopPreferenceKey(name){(it as? JsonPrimitive)?.booleanOrNull}
internal fun fullNavigationIntKey(name:String)=DesktopPreferenceKey(name){(it as? JsonPrimitive)?.intOrNull}
internal fun fullNavigationStringKey(name:String)=DesktopPreferenceKey(name){(it as? JsonPrimitive)?.contentOrNull}

/** Datastore-shaped platform binding only: no preference/default/cache/account authority.
 * Original compound label/color edits execute inside the existing shared backing transaction. */
internal class DesktopFullNavigationEditor(private val snapshot:DesktopPreferenceSnapshot) {
    val changes=linkedMapOf<String,JsonElement?>()
    operator fun <T> get(key:DesktopPreferenceKey<T>):T?=
        if(changes.containsKey(key.name)) changes[key.name]?.let(key.decode) else snapshot[key]
    operator fun set(key:DesktopPreferenceKey<Boolean>,value:Boolean){changes[key.name]=JsonPrimitive(value)}
    operator fun set(key:DesktopPreferenceKey<Int>,value:Int){changes[key.name]=JsonPrimitive(value)}
    operator fun set(key:DesktopPreferenceKey<String>,value:String){changes[key.name]=JsonPrimitive(value)}
    fun <T> remove(key:DesktopPreferenceKey<T>){changes[key.name]=null}
}
internal class DesktopFullNavigationDataStore(private val context:DesktopPluginContext) {
    val data:StateFlow<DesktopPreferenceSnapshot> get()=context.store.snapshot("settings")
    suspend fun edit(block:(DesktopFullNavigationEditor)->Unit)=withContext(Dispatchers.IO){
        ensureActive()
        context.store.requireObjectNamespace("settings")
        context.store.updateFromSnapshot("settings"){snapshot->DesktopFullNavigationEditor(snapshot).apply(block).changes}
    }
}
internal val DesktopPluginContext.fullNavigationDataStore get()=DesktopFullNavigationDataStore(this)
