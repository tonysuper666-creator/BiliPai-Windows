package com.bilipai.desktop.ui

import com.android.purebilibili.core.store.DesktopOriginalReplySettings
import com.android.purebilibili.data.repository.BlockedUpRelationSource
import com.android.purebilibili.data.repository.BlockedUpWriteResult
import com.android.purebilibili.feature.video.ui.components.ReplyCommentImageSpec
import com.bilipai.desktop.appearance.DesktopTextClipboard
import com.bilipai.desktop.data.DesktopCommunityRepository
import com.bilipai.desktop.data.DesktopDynamicCardOperations
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.plugins.DesktopPluginContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Bind the original rows to the existing page/account/settings owners. */
internal class DesktopDynamicCommentPlatform(
    override val context: DesktopPluginContext,
    override val emotes: DesktopDynamicEmotes,
    private val repository: DesktopRepository,
    private val community: DesktopCommunityRepository,
    private val operations: DesktopDynamicCardOperations,
    private val expectedEpoch: Long,
    private val stillOwned: () -> Boolean,
    private val clipboard: DesktopTextClipboard,
    private val feedback: (String) -> Unit,
    private val saveImage: suspend (ReplyCommentImageSpec) -> Boolean,
) : DesktopCommentPlatform {
    override val collapsedReplyPreviewLimit get() = DesktopOriginalReplySettings.getCommentCollapsedReplyPreviewLimitSync(context)
    override val subReplyLoadedCountEnabled = DesktopOriginalReplySettings.getSubReplyLoadedCountEnabled(context)
    override fun isOwned() = stillOwned() && operations.isOwned() && repository.sessionEpoch == expectedEpoch
    override fun showFeedback(message: String) { if (isOwned()) feedback(message) }
    override fun copyText(text: String, label: String) {
        if (isOwned() && !clipboard.copyText(text)) showFeedback("复制失败，请重试")
    }
    override fun shareText(text: String, title: String) {
        if (isOwned()) showFeedback("Windows 系统文字分享尚未接入，可使用复制")
    }
    private suspend fun ensureOwned() {
        currentCoroutineContext().ensureActive()
        if (!isOwned()) throw CancellationException("Reply page owner retired")
    }
    override suspend fun videoTitle(bvid: String): Result<String?> {
        ensureOwned()
        return try {
            val details = repository.videoDetails(bvid)
            ensureOwned(); Result.success(details.title)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { ensureOwned(); Result.failure(failure) }
    }
    override suspend fun translateReply(type: Long, oid: Long, rpid: Long): Result<String?> {
        ensureOwned()
        return operations.translateReply(type, oid, rpid).also { ensureOwned() }
    }
    override suspend fun blockUser(mid: Long, name: String, face: String, relationSource: BlockedUpRelationSource): BlockedUpWriteResult {
        ensureOwned()
        return community.blockedUpRepository.blockUpWithBilibiliSync(mid, name, face, relationSource, expectedEpoch)
            .also { ensureOwned() }
    }
    override suspend fun saveCommentImage(spec: ReplyCommentImageSpec): Boolean {
        ensureOwned()
        return saveImage(spec).also { ensureOwned() }
    }
}
