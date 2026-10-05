package com.bilipai.desktop.settings

import androidx.compose.runtime.*
import com.android.purebilibili.core.store.DesktopDynamicTabsSettings
import com.android.purebilibili.feature.dynamic.*
import com.android.purebilibili.feature.settings.DesktopDynamicTabsSettingsFields
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.data.DesktopDynamicCacheSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Global display settings; original MID/guest user preferences in the same Root Store. */
internal class DesktopDynamicTabsPreferences(val context:DesktopPluginContext, internal val account:DesktopDynamicCacheSession? = null) {
    init {context.store.requireObjectNamespace("settings")}
    private fun requireAccount()=checkNotNull(account){"Dynamic user preferences require the actual current Root cache session"}
    val visibleTabs=DesktopDynamicTabsSettings.getDynamicTabVisibleTabs(context)
    val tabOrder=DesktopDynamicTabsSettings.getDynamicTabOrder(context)
    val allTabUsers=DesktopDynamicTabsSettings.getDynamicAllTabHorizontalUserListVisible(context)
    // These are synchronous maps of the already loaded StateFlow, with no disk/network wait.
    val initialVisibleTabs:Set<String> get()=runBlocking{visibleTabs.first()}
    val initialTabOrder:List<String> get()=runBlocking{tabOrder.first()}
    val users get()=requireAccount().userPreferences(context.store)
    val selectedTab:Int get()=resolveDynamicSelectedTab(users.value[selectedKey],5)
    private val selectedKey=dynamicIntPreferencesKey(DesktopOriginalDynamicUserPreferenceKeys.KEY_SELECTED_TAB)
    val selectedTabFlow by lazy {users.map{resolveDynamicSelectedTab(it[selectedKey],5)}}
    private fun setKey(name:String)=DesktopPreferenceKey(name){value:JsonElement->
        require(value is JsonArray){"动态用户设置格式无效"}
        val array=value
        require(array.all{it is JsonPrimitive&&it.isString}){"动态用户设置格式无效"}
        array.mapNotNull{(it as? JsonPrimitive)?.takeIf{p->p.isString}?.content?.toLongOrNull()}.toSet()}
    val pinned by lazy {users.map{it[setKey(DesktopOriginalDynamicUserPreferenceKeys.KEY_PINNED_USERS)].orEmpty()}}
    val hidden by lazy {users.map{it[setKey(DesktopOriginalDynamicUserPreferenceKeys.KEY_HIDDEN_USERS)].orEmpty()}}
    val initialPinned:Set<Long> get()=runBlocking{pinned.first()}
    val initialHidden:Set<Long> get()=runBlocking{hidden.first()}
    val initialAllTabUsers:Boolean get()=runBlocking{allTabUsers.first()}
    private suspend fun writeUser(edit:(DesktopPreferenceSnapshot)->Map<String,JsonElement?>) {
        val session=requireAccount()
        val caller=currentCoroutineContext()[Job] ?: error("Dynamic preference write requires a caller Job")
        caller.ensureActive()
        withContext(Dispatchers.IO) { session.updateUserPreferences(context.store,caller,edit) }
    }
    suspend fun setSelectedTab(value:Int)=writeUser {
        mapOf(DesktopOriginalDynamicUserPreferenceKeys.KEY_SELECTED_TAB to JsonPrimitive(resolveDynamicSelectedTab(value,5)))
    }
    suspend fun setVisibleTabs(value:Set<String>)=DesktopDynamicTabsSettings.setDynamicTabVisibleTabs(context,value)
    suspend fun setOrder(value:List<String>)=DesktopDynamicTabsSettings.setDynamicTabOrder(context,value)
    suspend fun setAllTabUsers(value:Boolean)=DesktopDynamicTabsSettings.setDynamicAllTabHorizontalUserListVisible(context,value)
    suspend fun toggleVisibility(id:String)=setVisibleTabs(resolveDynamicVisibleTabIdsAfterToggle(visibleTabs.first(),id))
    // StringSet has no Android platform representation on Windows. A JSON array preserves
    // the original set of decimal MID strings in the same original namespace and key.
    suspend fun toggleUserPreference(name:String,uid:Long) {
        require(name==DesktopOriginalDynamicUserPreferenceKeys.KEY_PINNED_USERS||name==DesktopOriginalDynamicUserPreferenceKeys.KEY_HIDDEN_USERS)
        writeUser { snapshot ->
            val current=snapshot[DesktopPreferenceKey<JsonElement>(name){it}]
            require(current==null||current is JsonArray){"动态用户设置格式无效"}
            val ids=(current as? JsonArray).orEmpty().map {
                require(it is JsonPrimitive&&it.isString){"动态用户设置格式无效"};it.content
            }.toMutableSet()
            if(!ids.add(uid.toString()))ids.remove(uid.toString())
            mapOf(name to JsonArray(ids.map(::JsonPrimitive)))
        }
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
