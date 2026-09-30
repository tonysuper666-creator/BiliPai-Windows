package com.bilipai.desktop.settings

import androidx.compose.runtime.*
import com.android.purebilibili.core.store.DesktopDynamicTabsSettings
import com.android.purebilibili.feature.dynamic.*
import com.android.purebilibili.feature.settings.DesktopDynamicTabsSettingsFields
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Same global settings and original dynamic_user_prefs namespace, never a new account store. */
internal class DesktopDynamicTabsPreferences(val context:DesktopPluginContext) {
    init {context.store.requireObjectNamespace("settings");context.store.requireObjectNamespace(DesktopOriginalDynamicUserPreferenceKeys.PREFS_DYNAMIC_USERS)}
    val visibleTabs=DesktopDynamicTabsSettings.getDynamicTabVisibleTabs(context)
    val tabOrder=DesktopDynamicTabsSettings.getDynamicTabOrder(context)
    val allTabUsers=DesktopDynamicTabsSettings.getDynamicAllTabHorizontalUserListVisible(context)
    // These are synchronous maps of the already loaded StateFlow, with no disk/network wait.
    val initialVisibleTabs:Set<String> get()=runBlocking{visibleTabs.first()}
    val initialTabOrder:List<String> get()=runBlocking{tabOrder.first()}
    val users=context.store.snapshot(DesktopOriginalDynamicUserPreferenceKeys.PREFS_DYNAMIC_USERS)
    private val shared=context.getSharedPreferences(DesktopOriginalDynamicUserPreferenceKeys.PREFS_DYNAMIC_USERS,DesktopPluginContext.MODE_PRIVATE)
    val selectedTab:Int get()=resolveDynamicSelectedTab(if(shared.all.containsKey(DesktopOriginalDynamicUserPreferenceKeys.KEY_SELECTED_TAB))shared.getInt(DesktopOriginalDynamicUserPreferenceKeys.KEY_SELECTED_TAB,0)else null,5)
    private val selectedKey=dynamicIntPreferencesKey(DesktopOriginalDynamicUserPreferenceKeys.KEY_SELECTED_TAB)
    val selectedTabFlow=users.map{resolveDynamicSelectedTab(it[selectedKey],5)}
    private fun setKey(name:String)=DesktopPreferenceKey(name){value:JsonElement->
        require(value is JsonArray){"动态用户设置格式无效"}
        val array=value
        require(array.all{it is JsonPrimitive&&it.isString}){"动态用户设置格式无效"}
        array.mapNotNull{(it as? JsonPrimitive)?.takeIf{p->p.isString}?.content?.toLongOrNull()}.toSet()}
    val pinned=users.map{it[setKey(DesktopOriginalDynamicUserPreferenceKeys.KEY_PINNED_USERS)].orEmpty()}
    val hidden=users.map{it[setKey(DesktopOriginalDynamicUserPreferenceKeys.KEY_HIDDEN_USERS)].orEmpty()}
    val initialPinned:Set<Long> get()=runBlocking{pinned.first()}
    val initialHidden:Set<Long> get()=runBlocking{hidden.first()}
    val initialAllTabUsers:Boolean get()=runBlocking{allTabUsers.first()}
    suspend fun setSelectedTab(value:Int)=withContext(Dispatchers.IO){
        context.store.requireObjectNamespace(DesktopOriginalDynamicUserPreferenceKeys.PREFS_DYNAMIC_USERS)
        shared.edit().putInt(DesktopOriginalDynamicUserPreferenceKeys.KEY_SELECTED_TAB,resolveDynamicSelectedTab(value,5)).apply()
    }
    suspend fun setVisibleTabs(value:Set<String>)=DesktopDynamicTabsSettings.setDynamicTabVisibleTabs(context,value)
    suspend fun setOrder(value:List<String>)=DesktopDynamicTabsSettings.setDynamicTabOrder(context,value)
    suspend fun setAllTabUsers(value:Boolean)=DesktopDynamicTabsSettings.setDynamicAllTabHorizontalUserListVisible(context,value)
    suspend fun toggleVisibility(id:String)=setVisibleTabs(resolveDynamicVisibleTabIdsAfterToggle(visibleTabs.first(),id))
    // StringSet has no Android platform representation on Windows. A JSON array preserves
    // the original set of decimal MID strings in the same original namespace and key.
    suspend fun toggleUserPreference(name:String,uid:Long)=withContext(Dispatchers.IO){
        require(name==DesktopOriginalDynamicUserPreferenceKeys.KEY_PINNED_USERS||name==DesktopOriginalDynamicUserPreferenceKeys.KEY_HIDDEN_USERS)
        context.store.requireObjectNamespace(DesktopOriginalDynamicUserPreferenceKeys.PREFS_DYNAMIC_USERS)
        val current=context.store.preferences(DesktopOriginalDynamicUserPreferenceKeys.PREFS_DYNAMIC_USERS)[name]
        require(current==null||current is JsonArray){"动态用户设置格式无效"}
        val ids=current.orEmpty().map {
            require(it is JsonPrimitive&&it.isString){"动态用户设置格式无效"};it.content
        }.toMutableSet()
        if(!ids.add(uid.toString()))ids.remove(uid.toString())
        context.store.update(DesktopOriginalDynamicUserPreferenceKeys.PREFS_DYNAMIC_USERS,mapOf(name to JsonArray(ids.map(::JsonPrimitive))))
    }
}

@Composable internal fun DesktopDynamicTabsSettings(preferences:DesktopDynamicTabsPreferences,onFailure:(Throwable)->Unit) {
    val visible by preferences.visibleTabs.collectAsState(preferences.initialVisibleTabs)
    val order by preferences.tabOrder.collectAsState(preferences.initialTabOrder)
    val users by preferences.allTabUsers.collectAsState(preferences.initialAllTabUsers)
    val scope=rememberCoroutineScope();val writer=remember(preferences){Mutex()};val failure by rememberUpdatedState(onFailure)
    fun write(block:suspend()->Unit){scope.launch{try{writer.withLock{block()}}catch(cancelled:CancellationException){throw cancelled}catch(error:Exception){failure(error)}}}
    DesktopDynamicTabsSettingsFields(users,{write{preferences.setAllTabUsers(it)}},visible,
        {write{preferences.toggleVisibility(it)}},order,{write{preferences.setOrder(it)}})
}
