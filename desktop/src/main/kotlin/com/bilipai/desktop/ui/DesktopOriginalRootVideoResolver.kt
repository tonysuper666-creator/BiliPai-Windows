package com.bilipai.desktop.ui

import com.android.purebilibili.feature.download.DownloadTask
import com.android.purebilibili.feature.download.resolveOfflineVideoNavigationTask
import com.android.purebilibili.navigation.*
import com.android.purebilibili.navigation3.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Direct desktop clicks retain offline resolution. Every online video uses the Windows
 * detail renderer, regardless of old phone portrait preferences or video dimensions. */
internal class DesktopOriginalRootVideoResolver(
    private val root: DesktopHomeRetainedRoot,
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
        assertOwned()
        return key.copy(autoPortrait = false, initialVertical = false, directPortraitEntry = false)
    }
}
