package com.android.purebilibili.feature.following

import com.android.purebilibili.feature.list.DesktopFavoriteEnvironment
import com.android.purebilibili.data.model.response.FollowingUser
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Same retained navigation entry, API/action instance and canonical global store. */
class DesktopFollowingEnvironment(
    val favorites: DesktopFavoriteEnvironment,
    val cacheContext: DesktopFollowingCacheContext,
) {
    val scope get() = favorites.scope
    fun assertOwned() = favorites.assertOwned()
    suspend fun getFollowings(mid:Long,pn:Int,ps:Int) = owned { favorites.api.getFollowings(mid,pn,ps) }
    val actions = DesktopFollowingOwnedActions(this)
    suspend fun <T> owned(block:suspend ()->T):T {
        currentCoroutineContext().ensureActive();assertOwned()
        val result=block()
        currentCoroutineContext().ensureActive();assertOwned()
        return result
    }
}
abstract class DesktopFollowingScopedOwner(protected val environment:DesktopFollowingEnvironment) {
    protected val viewModelScope get() = environment.scope
}
/** Delegates only; exact original protocol bodies live in the sole Favorites actions producer. */
class DesktopFollowingOwnedActions(private val environment:DesktopFollowingEnvironment) {
    private val action get()=environment.favorites.actions
    suspend fun getAllFollowGroupUsers()=environment.owned {action.getAllFollowGroupUsers()}
    suspend fun followUser(mid:Long,follow:Boolean,emitBrandFeedback:Boolean=true)=environment.owned {action.followUser(mid,follow,emitBrandFeedback)}
    suspend fun getFollowGroupTags()=environment.owned {action.getFollowGroupTags()}
    suspend fun getFollowGroupMemberMids(tagId:Long,targetMids:Set<Long>)=environment.owned {action.getFollowGroupMemberMids(tagId,targetMids)}
    suspend fun getUserFollowGroupIds(mid:Long)=environment.owned {action.getUserFollowGroupIds(mid)}
    suspend fun overwriteFollowGroupIds(targetMids:Set<Long>,selectedTagIds:Set<Long>)=environment.owned {action.overwriteFollowGroupIds(targetMids,selectedTagIds)}
}

/** Original SharedPreferences shape over Root's one store. No Android account context. */
class DesktopFollowingCacheContext(private val store:DesktopPluginStore,
    private val stillOwned:()->Boolean,private val commitIfCurrent:((()->Unit)->Boolean)) {
    private fun checkOwned() {if(!stillOwned())throw CancellationException("Following cache owner retired")}
    fun forRequest(stillCurrent:()->Boolean) = DesktopFollowingCacheContext(store,
        {stillOwned() && stillCurrent()},commitIfCurrent)
    fun getSharedPreferences(name:String,mode:Int):Preferences {
        require(name=="following_cache" && mode==MODE_PRIVATE);checkOwned();store.requireObjectNamespace(name)
        return Preferences(name)
    }
    inner class Preferences(private val name:String) {
        fun getString(key:String,fallback:String?):String? {checkOwned();return (store.preferences(name)[key] as? JsonPrimitive)?.takeIf{it.isString}?.content ?: fallback}
        fun edit()=Editor(name)
    }
    inner class Editor(private val name:String) {
        private val values=linkedMapOf<String,JsonElement?>()
        fun putString(key:String,value:String)=apply{values[key]=JsonPrimitive(value)}
        fun remove(key:String)=apply{values[key]=null}
        fun apply() {
            fun checkRequest() { checkOwned();com.bilipai.desktop.plugins.DesktopSubscriptionWriteAdmission.checkCurrentRequestOrOriginal() }
            store.updateOriginalFromSnapshot(name,::checkRequest, {
                var permit:com.bilipai.desktop.plugins.DesktopPluginStore.OriginalPreferenceWritePermit?=null
                if(!commitIfCurrent {checkRequest();permit=com.bilipai.desktop.plugins.DesktopPluginStore.OriginalPreferenceWritePermit(store)})throw CancellationException("Following cache publication retired")
                checkNotNull(permit)
            }) { Unit to values.toMap() }
        }
    }
    companion object {const val MODE_PRIVATE=0}
}
