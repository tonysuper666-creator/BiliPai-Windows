package com.bilipai.desktop.ui

import com.bilipai.desktop.appearance.DesktopTextClipboard
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.DesktopPluginContext
import kotlinx.coroutines.CoroutineScope
import java.net.URI

/** The already existing platform schema. All service/save calls are forwarded
 * to the existing Operations/Assets; the avatar only uses the image actions.
 */
internal class DesktopSpaceAvatarPlatform(
    override val context: DesktopPluginContext,
    override val emotes: DesktopDynamicEmotes,
    private val operations: DesktopDynamicCardOperations,
    private val assets: DesktopDynamicImageAssets,
    private val stillOwned: () -> Boolean,
    private val clipboard: DesktopTextClipboard,
    private val feedback: (String) -> Unit,
    private val openExternalLink: (String) -> Unit,
    private val imageShare: DesktopImagePreviewShareBindings,
    private val textShare: DesktopTextShareBindings? = null,
    private val shareScope: CoroutineScope? = null,
) : DesktopDynamicCardPlatform {
    override fun isOwned() = stillOwned() && operations.isOwned()
    override fun showFeedback(message: String) { if (isOwned()) feedback(message) }
    override fun copyText(text: String) { if (isOwned() && !clipboard.copyText(text)) showFeedback("复制失败，请重试") }
    override fun shareText(text: String) { requestDesktopTextShare(textShare, shareScope, "BiliPai 分享", text, ::isOwned, ::showFeedback) }
    override fun openLink(url: String) {
        if (!isOwned()) return
        val uri = runCatching { URI(url) }.getOrNull() ?: return
        if (uri.scheme in setOf("http", "https")) runCatching { openExternalLink(uri.toString()) }
            .onFailure { showFeedback("无法打开链接") }
    }
    override suspend fun searchUp(name: String) = operations.searchUp(name)
    override suspend fun getVoteInfo(voteId: Long) = operations.getVoteInfo(voteId)
    override suspend fun submitVote(voteId: Long, optionIndexes: List<Int>, dynamicId: String) = operations.submitVote(voteId, optionIndexes, dynamicId)
    override suspend fun saveImage(url: String) = assets.saveImage(url)
    override suspend fun saveImages(urls: List<String>) = assets.saveImages(urls)
    override suspend fun saveMotionPhoto(imageUrl: String, videoUrl: String) = assets.saveMotionPhoto(imageUrl, videoUrl)
    override suspend fun saveLivePhotoVideo(videoUrl: String) = assets.saveLivePhotoVideo(videoUrl)
    override suspend fun shareImage(url: String): Boolean = imageShare.shareImage(url, ::isOwned)
    override suspend fun getShareTargets(size: Int) = operations.getShareTargets(size)
    override suspend fun getMessageSessions(size: Int) = operations.getMessageSessions(size)
    override suspend fun fetchMessageUserInfo(mid: Long) = operations.fetchMessageUserInfo(mid)
    override suspend fun sendDynamicShare(receiverId: Long, content: String) = operations.sendDynamicShare(receiverId, content)
}
