package com.bilipai.desktop.ui
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.serialization.json.*
import kotlinx.coroutines.CancellationException

internal class DesktopHomeFollowingCache(private val store:DesktopPluginStore,
 private val commitIfCurrent:((()->Unit))->Boolean) {
 init{store.requireObjectNamespace("following_cache")}
 fun getLong(key:String,fallback:Long):Long=(store.preferences("following_cache")[key] as? JsonPrimitive)?.longOrNull?:fallback
 fun getStringSet(key:String,fallback:Set<String>?):Set<String>?=(store.preferences("following_cache")[key] as? JsonArray)?.map{it.jsonPrimitive.content}?.toSet()?:fallback
 fun edit()=Editor()
 inner class Editor {
  private val values=linkedMapOf<String,JsonElement>()
  fun putLong(key:String,value:Long)=apply{values[key]=JsonPrimitive(value)}
  fun putStringSet(key:String,value:Set<String>)=apply{values[key]=JsonArray(value.map(::JsonPrimitive))}
  fun apply(){if(!commitIfCurrent{store.update("following_cache",values)})throw CancellationException("Home entry retired")}
 }
}
