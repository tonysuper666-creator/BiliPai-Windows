package com.bilipai.desktop.ui

import com.android.purebilibili.feature.download.DownloadTask
import com.bilipai.desktop.download.DesktopDownloadManager
import com.bilipai.desktop.player.PlaybackSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonPrimitive

/** Fixed transport metadata for ONE original download click/invocation.
 * Root resolves this from the exact same request or matching accepted native lease,
 * never by stamping an old raw task URL with latest credentials. This is a transient
 * platform view, not another task model, cache, receipt authority, or client.
 */
internal class DesktopOriginalVideoOwnerDownloadCapture(
    val source: PlaybackSource,
    val serverDownloadAllowed: Boolean,
    val seasonId: Long,
    val episodeId: Long,
    val isCourse: Boolean,
    val callerJob: Job,
    private val ownsCapturedSource: () -> Boolean,
) {
    fun isCurrent(): Boolean = callerJob.isActive && ownsCapturedSource()
    override fun toString() = "DesktopOriginalVideoOwnerDownloadCapture(authorized=${source.authorizationReceipt != null})"
}

/** Original tasks are a read projection of the SAME existing global manager.
 * Entry/caller admission ends at queue acceptance; queued workers retain the
 * manager's own task/account receipt lifetime after this video entry closes.
 */
internal class DesktopOriginalVideoOwnerDownloadBinding(
    private val manager: DesktopDownloadManager,
    private val settings: DesktopOriginalPlayerSettingsContext,
    private val assets: DesktopDynamicImageAssets,
    private val entryScope: CoroutineScope,
    private val stillOwned: () -> Boolean,
    private val entryCommit: ((() -> Unit) -> Boolean),
    private val resolveCaptured: (DownloadTask) -> DesktopOriginalVideoOwnerDownloadCapture,
    private val captureConstructedTask: (DownloadTask, com.android.purebilibili.data.model.response.PlayUrlData?) -> DownloadTask,
) : DesktopOriginalVideoOwnerDownload {
    override val tasks: StateFlow<Map<String, DownloadTask>> = manager.tasks
        .map { queue -> queue.associate { it.id to it.item } }
        .stateIn(entryScope, SharingStarted.Eagerly, manager.tasks.value.associate { it.id to it.item })

    private fun owns(): Boolean = entryScope.coroutineContext[Job]?.isActive == true && stillOwned()
    private fun assertOwned() {
        if (!owns()) throw CancellationException("Original video download entry retired")
    }

    override fun addTask(task: DownloadTask): Boolean = addTask(task, ::owns)

    override fun addTask(task: DownloadTask, stillCaptured: () -> Boolean): Boolean {
        assertOwned(); settings.requireCurrent()
        if (!stillCaptured()) throw CancellationException("Original download click retired")
        // MUST be a synchronous fixed capture. No launch-and-return-true, HTTP,
        // blocking wait, latest-cookie fallback or unrelated source is permitted.
        val captured = resolveCaptured(task)
        captured.callerJob.ensureActive()
        if (!captured.isCurrent()) throw CancellationException("Original download source retired")
        requireNotNull(captured.source.authorizationReceipt) { "Original download requires its playback authorization receipt" }
        val destination = manager.destinationFor(task.customSaveDir)
        // Manager owns the SAME publication and applies Store -> entry only for
        // actual queue mutation. Preparation/persistence/scheduling stay outside.
        return manager.enqueueOriginal(task, captured.source, destination,
            captured.serverDownloadAllowed, captured.seasonId, captured.episodeId, captured.isCourse,
            stillOwned = { owns() && captured.isCurrent() && stillCaptured() }, entryAdmission = entryCommit)
    }

    override fun captureTask(task: DownloadTask, explicitReply: com.android.purebilibili.data.model.response.PlayUrlData?): DownloadTask {
        assertOwned(); settings.requireCurrent()
        return captureConstructedTask(task, explicitReply)
    }

    override fun getVideoTask(bvid: String, cid: Long): DownloadTask? {
        assertOwned()
        // Exact original DownloadManager.getVideoTask ordering and audio exclusion.
        return manager.tasks.value.map { it.item }
            .filter { !it.isAudioOnly && it.bvid == bvid && it.cid == cid }
            .sortedWith(compareByDescending<DownloadTask> { it.isComplete }
                .thenByDescending { it.isDownloading }.thenByDescending { it.createdAt })
            .firstOrNull()
    }

    override suspend fun saveImageToGallery(context: DesktopOriginalPlayerSettingsContext,
        url: String, title: String): Boolean = saveImageToGallery(context, url, title, ::owns, null)

    override suspend fun saveImageToGallery(context: DesktopOriginalPlayerSettingsContext,
        url: String, title: String, stillCaptured: () -> Boolean,
        fileAdmission: ((() -> Unit) -> Boolean)?): Boolean {
        currentCoroutineContext().ensureActive(); assertOwned()
        require(context === settings) { "Original cover save requires the same global settings entry view" }
        settings.requireCurrent()
        val caller = currentCoroutineContext()[Job] ?: error("Cover save caller Job is required")
        val current = { caller.isActive && owns() && stillCaptured() }
        return try {
            val admission = fileAdmission ?: entryCommit
            val saved = assets.saveVideoCoverToGallery(url, title, current) { action ->
                admission {
                    if (!current()) throw CancellationException("Original cover click retired before file publication")
                    action()
                }
            }
            currentCoroutineContext().ensureActive(); assertOwned()
            saved
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            currentCoroutineContext().ensureActive(); assertOwned()
            false
        }
    }
}
