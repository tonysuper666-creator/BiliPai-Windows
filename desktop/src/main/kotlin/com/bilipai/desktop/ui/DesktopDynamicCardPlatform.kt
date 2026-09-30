package com.bilipai.desktop.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.dynamic.components.*
import kotlinx.coroutines.CancellationException

/** Card callbacks capture one live desktop repository/epoch; no process-global account. */
internal interface DesktopDynamicCardPlatform {
    val context:com.bilipai.desktop.plugins.DesktopPluginContext
    val emotes: DesktopDynamicEmotes
    fun isOwned():Boolean
    fun copyText(text:String)
    fun shareText(text:String)
    fun showFeedback(message:String)
    fun openLink(url:String)
    suspend fun searchUp(name:String):Result<Pair<List<SearchUpItem>,com.android.purebilibili.data.repository.SearchRepository.SearchPageInfo>>
    suspend fun getVoteInfo(voteId:Long):Result<DynamicVoteInfo>
    suspend fun submitVote(voteId:Long,optionIndexes:List<Int>,dynamicId:String):Result<DynamicVoteInfo>
    suspend fun saveImage(url:String):Boolean
    suspend fun saveImages(urls:List<String>):Boolean
    suspend fun saveMotionPhoto(imageUrl:String,videoUrl:String):Boolean
    suspend fun saveLivePhotoVideo(videoUrl:String):Boolean
    suspend fun shareImage(url:String):Boolean
    suspend fun getShareTargets(size:Int=5):Result<List<com.android.purebilibili.data.repository.MessageShareTarget>>
    suspend fun getMessageSessions(size:Int=30):Result<SessionListData>
    suspend fun fetchMessageUserInfo(mid:Long):com.android.purebilibili.feature.message.UserBasicInfo?
    suspend fun sendDynamicShare(receiverId:Long,content:String):Result<Long>
}
internal interface DesktopDynamicEmotes {
    fun snapshot():Map<String,String>
    fun currentSessionKey():Any
    suspend fun ensureLoaded():Map<String,String>
}
internal val LocalDesktopDynamicCardBindings=staticCompositionLocalOf<DesktopDynamicCardPlatform>{error("Dynamic card platform owner is not mounted")}
internal val LocalDynamicImagePreviewTextVisible=staticCompositionLocalOf{true}
internal fun dynamicVideoSharedElementKey(bvid:String):Any=bvid

