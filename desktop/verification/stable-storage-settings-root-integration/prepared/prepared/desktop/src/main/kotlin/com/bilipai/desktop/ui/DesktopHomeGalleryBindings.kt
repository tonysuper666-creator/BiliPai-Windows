package com.bilipai.desktop.ui

import com.bilipai.desktop.data.DesktopDynamicCardOperations
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.plugins.DesktopPluginContext
import kotlinx.coroutines.*
import java.awt.Component

/** Same actual CardHost assets actor/operations/catalog/session and global destination authority.
 * No image HTTP/encoder/file protocol is copied here. Saves execute in the original suspending
 * UI caller's Job; its cancellation is consumed by DesktopDynamicImageAssets's Call/file owner.
 * The same Home lifetime additionally cancels this actor's outstanding Calls when retired. */
internal class DesktopHomeGalleryBindings private constructor(
    override val context: DesktopPluginContext,
    private val lifetime: DesktopHomeEmbeddedLifetime,
    private val session: DesktopDynamicCardSession,
    private val operations: DesktopDynamicCardOperations,
    private val assets: DesktopDynamicImageAssets,
    internal val imageShare: DesktopImagePreviewShareBindings,
    internal val shareFiles: DesktopVideoShareFiles,
    private val textShare: DesktopTextShareBindings,
    private val clipboard: (String) -> Unit,
    private val externalLink: (String) -> Unit,
    private val feedback: (String) -> Unit,
) : DesktopDynamicCardPlatform, AutoCloseable {
    override val emotes get() = session.emotes
    /** Read-only access to the SAME assets actor for original Profile gallery saves. */
    internal val imageAssets: DesktopDynamicImageAssets get() = assets
    override fun isOwned() = lifetime.owns() && session.isOwned()
    private fun commit(block: () -> Unit) { lifetime.commit { if (session.isOwned()) block() } }
    private suspend fun <T> owned(block: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        if (!isOwned()) throw CancellationException("Home gallery owner retired")
        val result = block()
        currentCoroutineContext().ensureActive()
        if (!isOwned()) throw CancellationException("Home gallery owner retired")
        return result
    }
    override fun copyText(text: String) = commit { clipboard(text) }
    override fun shareText(text: String) {
        requestDesktopTextShare(textShare, lifetime.scope, "BiliPai 分享", text, ::isOwned, ::showFeedback)
    }
    override fun showFeedback(message: String) = commit { feedback(message) }
    override fun openLink(url: String) = commit { externalLink(url) }
    override suspend fun searchUp(name: String) = owned { operations.searchUp(name) }
    override suspend fun getVoteInfo(voteId: Long) = owned { operations.getVoteInfo(voteId) }
    override suspend fun submitVote(voteId: Long, optionIndexes: List<Int>, dynamicId: String) =
        owned { operations.submitVote(voteId, optionIndexes, dynamicId) }
    override suspend fun getShareTargets(size: Int) = owned { operations.getShareTargets(size) }
    override suspend fun getMessageSessions(size: Int) = owned { operations.getMessageSessions(size) }
    override suspend fun fetchMessageUserInfo(mid: Long) = owned { operations.fetchMessageUserInfo(mid) }
    override suspend fun sendDynamicShare(receiverId: Long, content: String) =
        owned { operations.sendDynamicShare(receiverId, content) }
    override suspend fun saveImage(url: String) = owned { assets.saveImage(url) }
    override suspend fun saveImages(urls: List<String>) = owned { assets.saveImages(urls) }
    override suspend fun saveMotionPhoto(imageUrl: String, videoUrl: String) = owned { assets.saveMotionPhoto(imageUrl, videoUrl) }
    override suspend fun saveLivePhotoVideo(videoUrl: String) = owned { assets.saveLivePhotoVideo(videoUrl) }
    override suspend fun shareImage(url: String): Boolean =
        imageShare.shareImage(url, ::isOwned)
    override fun close() = assets.close()

    companion object {
        fun create(
            repository: DesktopRepository,
            context: DesktopPluginContext,
            gate: DesktopHomeRetainedGate,
            lifetime: DesktopHomeEmbeddedLifetime,
            sameCardSession: DesktopDynamicCardSession,
            actualImageLocations: DesktopImageSaveLocations,
            actualRootWindow: Component,
            shareFiles: DesktopVideoShareFiles,
            mediaShare: DesktopImagePreviewMediaShare,
            textShare: DesktopTextShareBindings,
            clipboard: (String) -> Unit,
            externalLink: (String) -> Unit,
            feedback: (String) -> Unit,
        ): DesktopHomeGalleryBindings {
            lifetime.assertOwned()
            require(sameCardSession.matches(repository, gate.epoch)) { "Home requires Root's actual epoch card session" }
            require(actualImageLocations.isActive()) { "Home requires active Root global image locations" }
            val guard = repository.dynamicCacheSessionGuard
            var captured: com.bilipai.desktop.data.DesktopDynamicCacheOwner? = null
            if (!lifetime.commit { captured = guard.dynamicCacheOwner() })
                throw CancellationException("Home gallery retired before capture")
            val owner = captured ?: throw CancellationException("Home gallery session unavailable")
            if (owner.epoch != gate.epoch) throw CancellationException("Home gallery epoch changed")
            val operations = DesktopDynamicCardOperations(repository, gate.epoch,
                stillOwned = { lifetime.owns() && sameCardSession.isOwned() }, sharedEmotes = sameCardSession.emotes)
            val assets = DesktopDynamicImageAssets(repository.httpClient,
                stillOwned = { lifetime.owns() && sameCardSession.isOwned() },
                sessionGuard = guard, expectedOwner = owner,
                selectTarget = { name, mime -> selectDynamicSaveTarget(name, mime, actualRootWindow) },
                selectDirectory = { selectDynamicSaveDirectory(actualRootWindow) },
                imageSaveLocations = actualImageLocations)
            val imageShare = DesktopImagePreviewShareBindings(assets::readOriginalShareBytes, shareFiles,
                { lifetime.owns() && sameCardSession.isOwned() }, mediaShare)
            return DesktopHomeGalleryBindings(context, lifetime, sameCardSession, operations, assets,
                imageShare, shareFiles, textShare, clipboard, externalLink, feedback)
        }
    }
}
