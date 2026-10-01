package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import com.android.purebilibili.data.repository.DanmakuThumbupState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope

/** Only already-owned Root ports. No client, identity, store or native authority is constructed. */
internal interface DesktopDanmakuActions {
    suspend fun getDanmakuThumbupState(cid:Long,dmid:Long):Result<DanmakuThumbupState>
    suspend fun recallDanmaku(cid:Long,dmid:Long):Result<String>
    suspend fun likeDanmaku(cid:Long,dmid:Long,like:Boolean):Result<Unit>
    suspend fun reportDanmaku(cid:Long,dmid:Long,reason:Int,content:String):Result<Unit>
}

/** Lifetime: one page/CID/native sourceVersion/accountEpoch, including same-MID replacement. */
internal class DesktopDanmakuSessionEnvironment(
    val cid:Long,
    val sourceVersion:Long,
    val expectedEpoch:Long,
    val scope:CoroutineScope,
    val actions:DesktopDanmakuActions,
    private val stillOwned:()->Boolean,
    val currentMid:()->Long,
    private val feedback:(String)->Unit,
    private val seekFromUser:(Long)->Unit,
) {
    fun isOwned()=stillOwned()
    fun assertOwned(){if(!isOwned())throw CancellationException("Danmaku page/playback/account owner retired")}
    fun showFeedback(message:String){if(isOwned())feedback(message)}
    // Root passes DesktopPlaybackController.seekTo(ms/1000.0), preserving its existing user-seek/sponsor flow.
    fun seekTo(positionMs:Long){if(isOwned())seekFromUser(positionMs)}
}

internal interface DesktopDanmakuPlatform {
    fun isOwned():Boolean
    fun showFeedback(message:String)
    fun copyText(text:String,label:String)
    /** Required delegate to the existing unique original TextSelectionBottomSheet under Root's real owned card provider. */
    @Composable fun textSelection(text:String,title:String?,onDismiss:()->Unit)
}

internal val LocalDesktopDanmakuBindings=staticCompositionLocalOf<DesktopDanmakuPlatform>{error("Danmaku platform owner is not mounted")}
internal fun copyDesktopDanmakuText(platform:DesktopDanmakuPlatform,text:String,label:String){if(platform.isOwned())platform.copyText(text,label)}
@Composable internal fun DesktopDanmakuTextSelection(text:String,title:String?=null,onDismiss:()->Unit){
    val platform=LocalDesktopDanmakuBindings.current
    if(platform.isOwned())platform.textSelection(text,title,onDismiss)
}
