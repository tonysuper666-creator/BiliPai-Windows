package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import com.android.purebilibili.feature.article.ArticleDetailScreen
import com.android.purebilibili.navigation3.BiliPaiNavKey
import kotlinx.coroutines.*

/** Real NavDisplay leaf lifetime; covering the Article retains it, popping cancels it. */
private class DesktopOriginalArticleLeaf(
    private val root: DesktopPersonalListsRoot,
    private val present: () -> Boolean,
) : AutoCloseable {
    val job = SupervisorJob(root.gate.scope.coroutineContext[Job])
    val scope = CoroutineScope(root.gate.scope.coroutineContext + job)
    val identity = Any()
    fun owns(): Boolean = job.isActive && root.owns() && present()
    fun assertOwned() { if (!owns()) throw CancellationException("Article navigation entry retired") }
    suspend fun <T> call(action: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        assertOwned()
        val caller = currentCoroutineContext()[Job]
            ?: throw CancellationException("Article action requires its caller Job")
        val cancellation = job.invokeOnCompletion { caller.cancel(CancellationException("Article navigation entry retired")) }
        return try {
            action().also { currentCoroutineContext().ensureActive(); assertOwned() }
        } finally { cancellation.dispose() }
    }
    override fun close() { job.cancel() }
}

/** Borrow the same image/save/share actors; retain the actual preview caller Job. */
private fun articleGalleryPlatform(
    leaf: DesktopOriginalArticleLeaf,
    gallery: DesktopDynamicCardPlatform,
    textShare: DesktopTextShareBindings?,
): DesktopDynamicCardPlatform = object : DesktopDynamicCardPlatform by gallery {
    override fun isOwned() = leaf.owns() && gallery.isOwned()
    override fun copyText(text: String) { leaf.assertOwned(); gallery.copyText(text) }
    override fun shareText(text: String) {
        requestDesktopTextShare(textShare, leaf.scope, "BiliPai 分享", text, ::isOwned, ::showFeedback)
    }
    override fun showFeedback(message: String) { if (isOwned()) gallery.showFeedback(message) }
    override fun openLink(url: String) { leaf.assertOwned(); gallery.openLink(url) }
    override suspend fun searchUp(name: String) = leaf.call { gallery.searchUp(name) }
    override suspend fun getVoteInfo(voteId: Long) = leaf.call { gallery.getVoteInfo(voteId) }
    override suspend fun submitVote(voteId: Long, optionIndexes: List<Int>, dynamicId: String) =
        leaf.call { gallery.submitVote(voteId, optionIndexes, dynamicId) }
    override suspend fun saveImage(url: String) = leaf.call { gallery.saveImage(url) }
    override suspend fun saveImages(urls: List<String>) = leaf.call { gallery.saveImages(urls) }
    override suspend fun saveMotionPhoto(imageUrl: String, videoUrl: String) = leaf.call { gallery.saveMotionPhoto(imageUrl, videoUrl) }
    override suspend fun saveLivePhotoVideo(videoUrl: String) = leaf.call { gallery.saveLivePhotoVideo(videoUrl) }
    override suspend fun shareImage(url: String) = leaf.call { gallery.shareImage(url) }
    override suspend fun getShareTargets(size: Int) = leaf.call { gallery.getShareTargets(size) }
    override suspend fun getMessageSessions(size: Int) = leaf.call { gallery.getMessageSessions(size) }
    override suspend fun fetchMessageUserInfo(mid: Long) = leaf.call { gallery.fetchMessageUserInfo(mid) }
    override suspend fun sendDynamicShare(receiverId: Long, content: String) = leaf.call { gallery.sendDynamicShare(receiverId, content) }
}

@Composable
internal fun DesktopOriginalArticleRootHost(
    article: BiliPaiNavKey.ArticleDetail,
    root: DesktopPersonalListsRoot,
    commands: DesktopOriginalRootRouteCommands,
    transitionEnabled: Boolean,
) {
    key(root.gate.epoch, root, article) {
        val leaf = remember(root, article, commands) { DesktopOriginalArticleLeaf(root) { commands.containsEntry(article) } }
        DisposableEffect(leaf) { onDispose { leaf.close() } }
        if (!commands.containsEntry(article)) {
            SideEffect { leaf.close() }
        } else {
            val bindings = remember(leaf) {
                val protocol = root.articleBindings(leaf.scope)
                DesktopOriginalArticleBindings({ id -> leaf.call { protocol.load(id) } }, leaf::owns)
            }
            val actualGallery = LocalDesktopDynamicCardBindings.current
            val textShare = LocalDesktopTextShareBindings.current
            val foreground by rememberUpdatedState(LocalDesktopDetailForeground.current)
            val gallery = remember(leaf, actualGallery, textShare) { articleGalleryPlatform(leaf, actualGallery, textShare) }
            // Key the full UI/provider as one leaf: the original loader itself keys only id+retry.
            key(leaf.identity) {
                CompositionLocalProvider(LocalDesktopDynamicCardBindings provides gallery) {
                    ArticleDetailScreen(article.articleId, article.title, transitionEnabled,
                        onBack = { ready -> if (foreground && leaf.owns()) commands.articleBack(article, ready) },
                        onUserClick = { mid -> if (mid > 0 && foreground && leaf.owns()) commands.push(BiliPaiNavKey.Space(mid)) },
                        bindings = bindings)
                }
            }
        }
    }
}
