package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.shouldResolveVerticalVideoForPortraitEntry
import com.android.purebilibili.feature.download.DownloadTask
import com.android.purebilibili.feature.download.resolveOfflineVideoNavigationTask
import com.android.purebilibili.navigation.*
import com.android.purebilibili.navigation3.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The original AppNavigation's TWO entry boundaries: direct Home/card navigation includes
 * offline resolution; an already constructed route preserves comment/fullscreen/resume flags.
 * Portrait/offline policies and the dimension cache remain their original sole owners.
 * Network availability is the actual transport observation, never a guessed HTTP-success flag. */
internal class DesktopOriginalRootVideoResolver(
    private val root: DesktopHomeRetainedRoot,
    private val directPortraitStoryEntry: () -> Boolean,
    private val cardTransitionEnabled: () -> Boolean,
    private val networkAvailable: () -> Boolean,
    private val downloadTasks: () -> Collection<DownloadTask>,
    private val feedback: (String) -> Unit,
) {
    private suspend fun assertOwned() {
        currentCoroutineContext().ensureActive()
        if (!root.isCurrentOwner()) throw CancellationException("Video navigation owner retired")
    }
    suspend fun resolve(key: BiliPaiNavKey.VideoDetail, directEntry: Boolean): BiliPaiNavKey? {
        assertOwned()
        val directPortrait = directPortraitStoryEntry()
        val cardTransition = cardTransitionEnabled()
        val hasCommentJump = key.commentRootRpid > 0L || key.commentTargetRpid > 0L
        val initialMorph = resolveDirectPortraitDetailMorphEntry(directPortrait,
            cardTransition, key.initialVertical || key.directPortraitEntry, key.coverUrl, key.startAudio) || key.directPortraitEntry
        if (!hasCommentJump) {
            resolvePortraitStoryNavigationSeed(directPortrait, key.initialVertical, key.startAudio,
                key.bvid, key.cid, key.coverUrl, cardTransitionEnabled = cardTransition)?.let { seed ->
                assertOwned()
                return BiliPaiNavKey.Story(seedBvid=seed.bvid, seedCid=seed.cid,
                    seedCover=seed.coverUrl, sourceRoute=key.sourceRoute)
            }
        }
        if (directEntry) {
            val network = networkAvailable()
            val offline = resolveOfflineVideoNavigationTask(downloadTasks(), key.bvid, key.cid, network)
            assertOwned()
            if (offline != null) return BiliPaiNavKey.OfflineVideoPlayer(offline.id)
            if (!network) {
                root.entry.gate.commit { feedback("当前无网络，仅支持播放已缓存视频") }
                return null
            }
        }
        val knownVertical = if (directEntry) key.initialVertical || initialMorph else key.initialVertical
        if (!hasCommentJump && shouldResolveVerticalVideoForPortraitEntry(directPortrait,
                key.startAudio, key.bvid, knownVertical, key.coverUrl)) {
            val vertical = root.entry.requests.ports.video.isVerticalVideo(key.bvid)
            assertOwned()
            if (vertical) {
                return if (cardTransition) key.copy(autoPortrait=true, initialVertical=true, directPortraitEntry=true)
                else BiliPaiNavKey.Story(seedBvid=key.bvid.trim(),seedCid=key.cid,
                    seedCover=key.coverUrl,sourceRoute=key.sourceRoute)
            }
        }
        // Preserve the complete typed route, including comment target, fullscreen and resume.
        // This is the exact original standard-route builder, rather than converting to VideoCard.
        val route = resolveStandardVideoRoute(key.bvid,key.cid,key.coverUrl,key.startAudio,
            key.autoPortrait || initialMorph,key.fullscreen,key.resumePositionMs,key.commentRootRpid,
            key.commentTargetRpid,key.initialVertical || initialMorph,initialMorph)
        assertOwned()
        return (legacyRouteToBiliPaiNavKey(route) as BiliPaiNavKey.VideoDetail).copy(
            sourceRoute=key.sourceRoute,openId=key.openId)
    }
}
