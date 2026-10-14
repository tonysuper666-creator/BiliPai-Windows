package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.repository.FollowStateChange
import com.android.purebilibili.data.repository.DesktopOriginalCreatorStatus
import com.android.purebilibili.data.repository.DesktopOriginalFavoriteFolderProtocol
import com.android.purebilibili.data.repository.DesktopOriginalVideoActionStatus
import com.android.purebilibili.data.repository.DesktopOriginalVideoEngagementProtocol
import com.android.purebilibili.feature.list.DesktopFavoriteEnvironment
import com.android.purebilibili.feature.video.usecase.VideoInteractionUseCase
import kotlinx.coroutines.*
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/** Action protocol view of this continuation's already admitted primary API.
 * Reflection forwards Retrofit's actual method/Continuation unchanged; it cannot
 * create a service, client, CookieJar, account, folder store or action authority.
 * All original protocol instances below reuse that view and selected original
 * request builders. Retirement after an original error fallback still propagates.
 */
internal class DesktopOriginalVideoOwnerActionView(
    private val invocations: DesktopOriginalVideoPlaybackInvocationPorts,
    entryScope: CoroutineScope,
    private val repository: com.bilipai.desktop.data.DesktopRepository,
    private val capturedEpoch: Long,
    analytics: DesktopOriginalVideoInteractionAnalytics,
    private val confirmFollow: (FollowStateChange) -> Unit,
    feedback: (String) -> Unit,
) : DesktopOriginalVideoOwnerActions {
    private fun request(): DesktopOriginalVideoOwnerRequestRepository =
        invocations.requireRequestRepository() as? DesktopOriginalVideoOwnerRequestRepository
            ?: error("Original action needs this continuation's captured primary repository")

    private val api = Proxy.newProxyInstance(BilibiliApi::class.java.classLoader,
        arrayOf(BilibiliApi::class.java)) { _, method, args ->
        val captured = request()
        captured.binding.assertCurrent()
        try { method.invoke(captured.primaryApi, *(args ?: emptyArray())) }
        catch (wrapped: InvocationTargetException) { throw wrapped.targetException }
    } as BilibiliApi

    private val status = DesktopOriginalVideoActionStatus(api) { request().binding.primarySessData() }
    private val creator = DesktopOriginalCreatorStatus(api)
    // Only the canonical folder/group methods are consumed, as in the existing
    // video folder drawer. Other optional Favorite page families are not mounted.
    private val folder = DesktopFavoriteEnvironment.forFolderDrawer(entryScope, api,
        { runCatching { invocations.assertCurrent(); request().binding.assertCurrent() }.isSuccess },
        { request().binding.primaryCsrf() }, { request().binding.primaryMid() }, feedback)
    private val engagement = DesktopOriginalVideoEngagementProtocol(api,
        { request().binding.primaryCsrf() }, { request().binding.primaryMid() },
        { request().binding.primarySessData() }, { request().binding.primaryAccessToken() },
        { request().binding.assertCurrent() },
        { change ->
            val captured = request().binding
            if (!captured.admitCurrentMutation {
                    confirmFollow(change)
                })
                throw CancellationException("Original confirmed follow request retired")
        }, DesktopOriginalFavoriteFolderProtocol(api, { request().binding.primaryMid() },
            { request().binding.primaryCsrf() }, { request().binding.assertCurrent() }),
        repository.videoInteractionStatusCache, repository.videoFollowingSnapshotGuard,
        { request().binding.assertCurrent(); capturedEpoch },
        { block ->
            val captured = request().binding
            if (!captured.admitCurrentMutation(block))
                throw CancellationException("Original interaction cache admission retired")
        })
    val interactionUseCase = VideoInteractionUseCase(engagement, analytics)

    private suspend fun <T> owned(action: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        val captured = request().binding
        captured.assertCurrent()
        return action().also { currentCoroutineContext().ensureActive(); captured.assertCurrent() }
    }
    // Original account-cache reads also occur synchronously before a UI command
    // launches. They need the real account/entry gate, while network operations
    // continue to require that launch's captured repository and API.
    private fun <T> interactionSnapshot(block: () -> T): T =
        repository.withPrimaryPlaybackAdmission(capturedEpoch, {
            runCatching { invocations.assertCurrent() }.isSuccess
        }) { invocations.assertCurrent(); block() }
    override fun interactionSession(): Any = interactionSnapshot { capturedEpoch }
    override fun cachedLikeStatus(aid: Long) = interactionSnapshot {
        repository.videoInteractionStatusCache.read(capturedEpoch, "like:$aid").first
    }
    override fun cachedFollowStatus(mid: Long) = interactionSnapshot {
        repository.videoInteractionStatusCache.read(capturedEpoch, "follow:$mid").first
    }
    override fun likeStatusVersion(aid: Long) = interactionSnapshot {
        repository.videoInteractionStatusCache.read(capturedEpoch, "like:$aid").second
    }
    override fun followStatusVersion(mid: Long) = interactionSnapshot {
        repository.videoInteractionStatusCache.read(capturedEpoch, "follow:$mid").second
    }
    override suspend fun queryLikeStatus(aid: Long) = owned { engagement.queryLikeStatus(aid) }
    override suspend fun queryFollowStatus(mid: Long) = owned { engagement.queryFollowStatus(mid) }
    override suspend fun checkFollowStatus(mid: Long) = owned { engagement.checkFollowStatus(mid) }
    override suspend fun checkFavoriteStatus(aid: Long) = owned { status.checkFavoriteStatus(aid) }
    override suspend fun checkLikeStatus(aid: Long) = owned { engagement.checkLikeStatus(aid) }
    override suspend fun checkCoinStatus(aid: Long) = owned { status.checkCoinStatus(aid) }
    override suspend fun checkDislikeStatus(aid: Long) = owned { status.checkDislikeStatus(aid) }
    override suspend fun checkWatchLaterStatus(aid: Long) = owned { status.checkWatchLaterStatus(aid) }
    override suspend fun createFavFolder(title: String, intro: String, isPrivate: Boolean) =
        owned { folder.actions.createFavFolder(title, intro, isPrivate) }
    override suspend fun getFollowGroupTags() = owned { folder.actions.getFollowGroupTags() }
    override suspend fun getUserFollowGroupIds(mid: Long) = owned { folder.actions.getUserFollowGroupIds(mid) }
    override suspend fun overwriteFollowGroupIds(targetMids: Set<Long>, selectedTagIds: Set<Long>) =
        owned { folder.actions.overwriteFollowGroupIds(targetMids, selectedTagIds) }
}
