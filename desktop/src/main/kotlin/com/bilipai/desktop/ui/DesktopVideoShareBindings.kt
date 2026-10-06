package com.bilipai.desktop.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import com.android.purebilibili.feature.video.share.*
import com.android.purebilibili.data.model.response.*
import com.bilipai.desktop.data.DesktopDynamicCardOperations
import kotlinx.coroutines.*
import java.nio.file.Path

/** A view of the EXISTING retained Root owner, operations and physical actors.
 * Native success means the Windows chooser opened, never receiver delivery. */
internal class DesktopVideoShareBindings(
    private val operations: DesktopDynamicCardOperations,
    private val following: DesktopHomeFollowingRequests,
    private val sendText: suspend (Long, String) -> Result<SendMessageData>,
    val files: DesktopVideoShareFiles,
    private val mid: () -> Long?,
    private val owner: () -> Boolean,
    private val copy: (String) -> Unit,
    private val feedback: (String) -> Unit,
    private val textShare: DesktopTextShareBindings,
    private val mediaShare: suspend (Path, String, String, () -> Boolean, (Boolean) -> Unit) -> Boolean,
    private val chooseSave: suspend (String, String) -> Path?,
    private val nativeAvailable: () -> Boolean,
    private val windowWidthDp: () -> Int,
    private val windowHeightDp: () -> Int,
    private val presentationAdmission: ((() -> Unit) -> Boolean)? = null,
    private val nativeHandoffOwner: (() -> Boolean)? = null,
    private val preparedFeedback: (suspend (String) -> Unit)? = null,
    private val presentationForeground: (() -> Boolean)? = null,
    private val guardedChooseSave: (suspend (String, String, () -> Boolean) -> Path?)? = null,
) {
    /** Borrow all existing Root effects; only add the popup's exact source lifetime. */
    fun forPresentation(owns: () -> Boolean, admit: ((() -> Unit) -> Boolean),
        handoffOwned: () -> Boolean): DesktopVideoShareBindings =
        DesktopVideoShareBindings(operations, following, sendText, files, mid,
            { isOwned() && owns() }, copy, feedback, textShare, mediaShare, chooseSave,
            nativeAvailable, windowWidthDp, windowHeightDp, admit, { isOwned() && handoffOwned() }, preparedFeedback,
            presentationForeground, guardedChooseSave)

    /** Prepared feedback is dispatched before the original success toast/dismiss.
     * Its confirmed receipt has source/account lifetime, independent of this sheet. */
    fun withPreparedFeedback(feedback: suspend (String) -> Unit): DesktopVideoShareBindings =
        DesktopVideoShareBindings(operations, following, sendText, files, mid, owner, copy,
            this.feedback, textShare, mediaShare, chooseSave, nativeAvailable,
            windowWidthDp, windowHeightDp, presentationAdmission, nativeHandoffOwner, feedback,
            presentationForeground, guardedChooseSave)
    /** Network/state keep the source lease; only external UI requires foreground. */
    fun whilePresented(visible: () -> Boolean): DesktopVideoShareBindings =
        DesktopVideoShareBindings(operations, following, sendText, files, mid, owner, copy,
            feedback, textShare, mediaShare, chooseSave, nativeAvailable, windowWidthDp, windowHeightDp,
            presentationAdmission, nativeHandoffOwner, preparedFeedback, visible, guardedChooseSave)
    fun isPresented(): Boolean = isOwned() && (presentationForeground?.invoke() != false)
    private fun acceptUiEffect() {
        if (!isPresented() || !withAdmission { if (!isPresented()) throw CancellationException("Share external UI hidden") } ||
            !isPresented()) throw CancellationException("Share external UI hidden")
    }
    suspend fun sharePrepared(bvid: String) { checkOwned(); preparedFeedback?.invoke(bvid); checkOwned() }
    fun canShareToDynamic(): Boolean = isOwned() && operations.canShareVideoToDynamic()
    suspend fun shareToDynamic(bvid: String, text: String): Result<String> {
        val caller = currentCoroutineContext()
        fun owned(): Boolean = caller[Job]?.isActive != false && isOwned()
        checkOwned()
        return operations.shareVideoToDynamic(bvid, text, ::owned,
            { action -> caller.ensureActive(); withAdmission { caller.ensureActive(); action() } }
        ).also { checkOwned() }
    }

    fun withAdmission(action: () -> Unit): Boolean {
        if (!isOwned()) return false
        var applied = false
        val publish = { if (isOwned()) { action(); applied = true }; Unit }
        val gate = presentationAdmission
        return (if (gate == null) { publish(); applied } else gate(publish)) && applied
    }

    private fun acceptEffect() {
        if (!withAdmission {}) throw CancellationException("视频分享来源已退役")
    }
    fun isOwned() = owner() && operations.isOwned()
    private suspend fun checkOwned() { currentCoroutineContext().ensureActive();if(!isOwned())throw CancellationException("视频分享页面已退役") }
    val screenHeightDp: Int get() = windowHeightDp().also { require(it > 0) }
    val isLandscape: Boolean get() = windowWidthDp().also { require(it > 0) } > screenHeightDp
    fun currentMid(): Long? { if(!isOwned())throw CancellationException("分享账号已退役");return mid() }
    fun showFeedback(message: String) { withAdmission { feedback(message) } }
    fun copyText(text:String) { acceptUiEffect();if(!isPresented())throw CancellationException("分享页面已退役");copy(text) }
    suspend fun getFollowings(mid:Long,page:Int,pageSize:Int):FollowingsResponse {
        checkOwned();return following.getFollowings(mid,page,pageSize).also {checkOwned()}
    }
    suspend fun sendTextMessage(receiverId:Long,content:String):Result<SendMessageData> {
        checkOwned();acceptEffect();return sendText(receiverId,content).also {checkOwned()}
    }
    fun availableTargets(mimeType:String):List<VideoShareAppTarget> {
        if(!isOwned())return emptyList()
        return buildList {
            if(nativeAvailable())add(VideoShareAppTarget(VideoShareTarget.SYSTEM_SHARE,"Windows 系统分享",Icons.Outlined.Share))
            if(mimeType.startsWith("image/"))add(VideoShareAppTarget(VideoShareTarget.SAVE_CARD,"保存分享卡片",Icons.Outlined.SaveAlt))
            add(VideoShareAppTarget(VideoShareTarget.COPY_LINK,"复制视频链接",Icons.Outlined.Link))
        }
    }
    suspend fun performTarget(target:VideoShareTarget,payload:VideoSharePayload,media:VideoShareCoverFile?) {
        checkOwned()
        try {
            when(target) {
                VideoShareTarget.COPY_LINK -> { copyText(payload.url);showFeedback("已复制链接") }
                VideoShareTarget.SAVE_CARD -> {
                    val card=media
                    checkOwned()
                    if(card == null) {showFeedback("卡片生成失败，未保存");return}
                    acceptUiEffect()
                    val guardedSave = guardedChooseSave
                    val destination = (if (guardedSave == null) chooseSave(resolveVideoShareCardFileName(payload), card.mimeType)
                        else guardedSave(resolveVideoShareCardFileName(payload), card.mimeType, ::isPresented)) ?: return
                    checkOwned();acceptEffect();files.save(card,destination,::withAdmission);checkOwned();files.retire(card,true);showFeedback("分享卡片已保存")
                }
                VideoShareTarget.SYSTEM_SHARE,VideoShareTarget.MORE -> {
                    if(!nativeAvailable()) {showFeedback("Windows 系统分享暂不可用，可复制链接或保存卡片");return}
                    acceptUiEffect()
                    val handoff = DesktopWindowsNativeShareHandoff(::isPresented, nativeHandoffOwner ?: ::isOwned, ::withAdmission)
                    val shown = handoff.open { nativeOwned ->
                        if(media == null)textShare.share(resolveVideoShareChooserTitle(payload),payload.text,nativeOwned)
                        else {
                            files.mayExpose(media)
                            try { mediaShare(media.path,resolveVideoShareChooserTitle(payload),payload.url,nativeOwned) { safe -> files.retire(media,safe) } }
                            catch(failure:Throwable) { /* possibly supplied file is conservatively retained */ throw failure }
                        }
                    }
                    checkOwned();if(!shown)showFeedback("系统分享面板未打开，未确认发送")
                }
                VideoShareTarget.BILIBILI_FRIENDS,VideoShareTarget.BILIBILI_DYNAMIC -> error("应用内分享由原完整表单接收")
            }
        } catch(cancelled:CancellationException) {throw cancelled}
        catch(failure:Exception) {checkOwned();showFeedback(failure.message ?: "视频分享失败")}
    }
}
internal val LocalDesktopVideoShareBindings=staticCompositionLocalOf<DesktopVideoShareBindings>{error("原视频分享需要同 Root retained owner / Ops / 文件 / 原生分享绑定")}

/** Required facade of the ONE diagnostic actor and global SettingsManager keys. */
internal interface DesktopCrashConsentBindings {
    val enhancedEnabled:Boolean
    suspend fun saveChoice(enabled:Boolean)
}
internal val LocalDesktopCrashConsentBindings=staticCompositionLocalOf<DesktopCrashConsentBindings>{error("Windows 本地诊断授权必须绑定唯一实际 diagnostic actor")}
