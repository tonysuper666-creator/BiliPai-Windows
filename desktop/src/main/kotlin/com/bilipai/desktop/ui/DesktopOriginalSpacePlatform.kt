package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import com.android.purebilibili.core.database.entity.BlockedUp
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.BlockedUpRelationSource
import com.android.purebilibili.feature.dynamic.DynamicDeleteAction
import com.android.purebilibili.feature.dynamic.components.*
import com.android.purebilibili.feature.space.*
import com.android.purebilibili.feature.video.player.*
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.booleanPreferencesKey
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal val LocalDesktopOriginalSpacePlatform=staticCompositionLocalOf<DesktopOriginalSpacePlatform> {
    error("Original Space requires the retained same Root entry and actual shared consumers")
}

/** Renderer/side-effect ports over existing owners. A Space leaf owns its original
 * VM state and child jobs; image, comments, native media, blocked records, playlist
 * and preferences stay owned by the installed retained Root. No new HTTP client,
 * persisted cache, session, renderer or settings default authority is constructed.
 */
internal class DesktopOriginalSpacePlatform(
    val entry:DesktopOriginalSpacePageEntry,
    val home:DesktopHomeEnvironment,
    val supportsRenderEffects:Boolean,
    private val community:DesktopCommunityRepository,
    private val session:DesktopDynamicCardSession,
    private val comments:DesktopOriginalCommentRootOwner,
    private val rootPlaylist:DesktopOriginalVideoPlaylistBinding,
    private val cachedVideoPosition:(String)->Long,
    private val shareText:(String,String,()->Boolean)->Unit,
    private val openDynamicDetail:(String)->Unit,
) : AutoCloseable {
    val environment get()=entry.environment
    val viewModel get()=entry.viewModel
    val locatePromptEnabled=home.pluginContext.store.snapshot("settings").map {
        it[booleanPreferencesKey("space_played_video_locate_prompt_enabled")]?:true
    }.distinctUntilChanged()
    fun rank(mid:Long,name:String,count:Long):SpaceUpowerRankViewModel {
        environment.requireMid(mid)
        return entry.rank(mid,name,count)
    }
    fun guard(mid:Long,name:String,count:Long):SpaceMemberGuardViewModel {
        environment.requireMid(mid)
        return entry.guard(mid,name,count)
    }
    fun cachedPosition(bvid:String):Long { environment.checkpoint();return cachedVideoPosition(bvid) }
    fun feedback(text:String) { if(environment.owns())home.feedback(text) }
    fun shareSpace(mid:Long) { environment.requireMid(mid);environment.requireVisibleAction();shareText("分享空间","https://space.bilibili.com/$mid",environment::owns) }
    val playlist=SpacePlaylist()
    inner class SpacePlaylist {
        fun setExternalPlaylist(items:List<PlaylistItem>,index:Int,source:ExternalPlaylistSource) {
            environment.requireVisibleAction();rootPlaylist.setExternalPlaylist(items,index,source)
        }
        fun setPlayMode(mode:PlayMode) { environment.requireVisibleAction();rootPlaylist.setPlayMode(mode) }
    }
    val blockedUps=SpaceBlockedUps()
    inner class SpaceBlockedUps {
        fun isBlocked(mid:Long):Flow<Boolean> { environment.requireMid(mid);return community.blockedUpRepository.store.mids.map {mid in it}.distinctUntilChanged() }
        private suspend fun check() { currentCoroutineContext().ensureActive();environment.requireVisibleAction() }
        suspend fun blockUpWithBilibiliSync(mid:Long,name:String,face:String):com.android.purebilibili.data.repository.BlockedUpWriteResult =
            ownedBlocked(mid,true,name,face)
        suspend fun unblockUpWithBilibiliSync(mid:Long):com.android.purebilibili.data.repository.BlockedUpWriteResult = ownedBlocked(mid,false,"","")
        private suspend fun ownedBlocked(mid:Long,block:Boolean,name:String,face:String):com.android.purebilibili.data.repository.BlockedUpWriteResult = environment.withActualCaller {
            check();environment.requireMid(mid)
            val caller=currentCoroutineContext()
            fun assertOwned(){caller.ensureActive();environment.requireVisibleAction()}
            val repository=community.blockedUpRepository
            val store=repository.store
            val permit={ environment.preferenceWritePermit(home.pluginContext.store) }
            val result=if(block) repository.blockUpWithCapturedLocalWrite(mid,name,face,BlockedUpRelationSource.PROFILE,environment.epoch,
                ::assertOwned,{store.upsertCaptured(it,::assertOwned,permit)},environment.api,environment::csrf,{environment.ensureSession()})
            else repository.unblockUpWithCapturedLocalWrite(mid,BlockedUpRelationSource.PROFILE,environment.epoch,
                ::assertOwned,{store.removeCaptured(it,::assertOwned,permit)},environment.api,environment::csrf,{environment.ensureSession()})
            check();result
        }
    }
    /** These flows are projections of the sole Root confirmed-like state, not a
     * duplicate liked-ID cache. Original Space optimistic forward delta stays in
     * its unchanged Screen remember state, scoped to that retained visit.
     */
    val dynamic=SpaceDynamicActions()
    inner class SpaceDynamicActions {
        val likeOverrides=session.likeOverrides
        val likedDynamics=session.likeOverrides.map {map->map.filterValues {it}.keys}.stateIn(environment.scope,SharingStarted.Eagerly,session.likeOverrides.value.filterValues {it}.keys)
        fun openCommentSheet(item:DynamicItem) { environment.requireVisibleAction();openDynamicDetail(item.id_str) }
        private fun action(channel:String,onResult:(Boolean,String)->Unit,body:suspend()->String) {
            environment.requireVisibleAction()
            environment.launchMutation(channel) {
                try {
                    val message=body();ensureActive();environment.checkpoint()
                    onResult(true,message)
                } catch(cancelled:CancellationException){throw cancelled}
                catch(failure:Exception){ensureActive();environment.checkpoint();onResult(false,failure.message?:"操作失败")}
            }
        }
        fun likeDynamic(id:String,currentlyLiked:Boolean,onResult:(Boolean,String)->Unit) = action("like:$id",onResult) {
            val next=!(session.likeOverrides.value[id]?:currentlyLiked)
            comments.operations.setLike(id,next);environment.checkpoint();session.confirmLike(id,next)
            if(next)"已点赞"else"已取消点赞"
        }
        fun deleteDynamic(action:DynamicDeleteAction,onResult:(Boolean,String)->Unit)=action("delete:${action.dynamicId}",onResult) {
            comments.operations.delete(action);"删除成功"
        }
        fun toggleDynamicReserve(action:DynamicReserveAction,onResult:(Result<DynamicReserveResult>)->Unit) {
            environment.requireVisibleAction()
            environment.launchMutation("reserve:${action.reserveId}") {
                val result=comments.operations.reserve(action);ensureActive();environment.checkpoint();onResult(result)
            }
        }
        fun repostDynamic(id:String,content:String,alsoComment:Boolean,onResult:(Boolean,String)->Unit)=action("repost:$id",onResult) {
            comments.operations.repost(id,content);environment.checkpoint()
            val item=(viewModel.uiState.value as? SpaceUiState.Success)?.dynamics
                ?.let(::resolveSpaceDynamicCardItems)?.firstOrNull {it.id_str==id}
            val comment=if(alsoComment&&content.isNotBlank())item?.let {comments.operations.postSourceComment(it,content)}==true else true
            if(!comment)"转发成功，评论同步失败"else if(alsoComment&&content.isNotBlank())"转发成功，已同步评论"else"转发成功"
        }
    }
    override fun close(){environment.close()}
    suspend fun closeAndJoin():Boolean=environment.closeAndJoin()
}

internal data class DesktopSpaceWindowBounds(val screenWidthDp:Int,val screenHeightDp:Int)
internal object DesktopSpaceWindowConfiguration {
    val current:DesktopSpaceWindowBounds @Composable get() {
        val size=LocalWindowInfo.current.containerSize;val density=LocalDensity.current.density
        return DesktopSpaceWindowBounds((size.width/density).toInt(),(size.height/density).toInt())
    }
}
