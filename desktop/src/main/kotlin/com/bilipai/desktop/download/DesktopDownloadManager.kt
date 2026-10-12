package com.bilipai.desktop.download

import com.android.purebilibili.feature.download.*
import com.android.purebilibili.feature.download.DownloadTask as UpstreamDownloadTask
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.data.DesktopMediaRepository
import com.bilipai.desktop.player.PlaybackSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Original queue/resume/chunk behavior, with Windows storage and muxer adapters. */
class DesktopDownloadManager internal constructor(
    private val client: OkHttpClient,
    private val stateFile: Path,
    private val muxer: DownloadMuxer,
    private val sourceResolver: (suspend (DownloadTask) -> DesktopDownloadResolvedSource?)? = null,
    private val danmakuDownloader: (suspend (DownloadTask, Path, (DownloadAssetState) -> Unit) -> Pair<List<String>, String?>)? = null,
    private val publication: com.bilipai.desktop.player.DesktopPlaybackPublication,
    private val defaultDestination: () -> Path = Companion::defaultDownloadRoot,
    private val brandEvents: com.android.purebilibili.core.events.BrandSuccessEvents? = null,
) : AutoCloseable {
    constructor(repository: DesktopRepository) : this(repository.playbackHttpClient, defaultStateFile(), WindowsFfmpegMuxer(),
        defaultSourceResolver(repository), defaultDanmakuDownloader(repository), com.bilipai.desktop.player.DesktopRepositoryPlaybackPublication(repository))

    constructor(repository: DesktopRepository, defaultDestination: () -> Path) : this(repository.playbackHttpClient,
        defaultStateFile(), WindowsFfmpegMuxer(), defaultSourceResolver(repository), defaultDanmakuDownloader(repository),
        com.bilipai.desktop.player.DesktopRepositoryPlaybackPublication(repository), defaultDestination)

    constructor(repository: DesktopRepository, brandEvents: com.android.purebilibili.core.events.BrandSuccessEvents,
        defaultDestination: () -> Path) : this(repository.playbackHttpClient, defaultStateFile(), WindowsFfmpegMuxer(),
        defaultSourceResolver(repository), defaultDanmakuDownloader(repository),
        com.bilipai.desktop.player.DesktopRepositoryPlaybackPublication(repository), defaultDestination, brandEvents)

    /** Future admissions only. An explicit original task directory wins; queued roots never migrate. */
    internal fun destinationFor(explicit: String? = null): Path =
        com.bilipai.desktop.ui.resolveDesktopOriginalDownloadDestination(explicit, defaultDestination())

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val serializer = ListSerializer(DownloadTask.serializer())
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()
    private var lastPersistNanos = 0L
    private var closed = false
    /** One exact runtime-update transfer, not another queue or download producer. */
    internal class UpdateHandoff internal constructor(internal val workers: List<Job>) {
        internal var drained = false
        internal var committed = false
    }
    private var updateHandoff: UpdateHandoff? = null
    // Only in-flight enqueue directory preparation, not a second task queue.
    private var directoryPreparations = 0
    private val directoryPreparationCount = MutableStateFlow(0)
    private val mutableUpdateHandoffPending = MutableStateFlow(false)
    internal val updateHandoffPending: StateFlow<Boolean> = mutableUpdateHandoffPending.asStateFlow()

    internal fun acceptsMutations(): Boolean = synchronized(lock) {
        !closed && updateHandoff == null && scope.coroutineContext[Job]?.isActive == true
    }
    private fun assertMutationsAllowedLocked() {
        check(!closed) { "下载队列已关闭" }
        check(updateHandoff == null) { "下载队列正在交接更新，暂不能修改" }
    }

    /** Freeze every new queue mutation before waiting. Includes an already admitted
     * remove's ATOMIC/NonCancellable retirement job; joins never hold this lock. */
    internal fun beginUpdateHandoff(): UpdateHandoff = synchronized(lock) {
        assertMutationsAllowedLocked()
        check(scope.coroutineContext[Job]?.isActive == true)
        UpdateHandoff(jobs.values.toList()).also {
            updateHandoff = it
            mutableUpdateHandoffPending.value = true
        }
    }

    private fun prepareOwnedDirectoryForEnqueue(task: DownloadTask): Path {
        synchronized(lock) {
            assertMutationsAllowedLocked()
            directoryPreparations++
            directoryPreparationCount.value = directoryPreparations
        }
        return try { ensureOwnedDirectory(task) } finally {
            synchronized(lock) {
                directoryPreparations--
                directoryPreparationCount.value = directoryPreparations
            }
        }
    }

    private suspend fun awaitRetiringHandoffWork(ticket: UpdateHandoff) {
        ticket.workers.forEach { it.cancel() }
        withTimeout(5_000L) {
            directoryPreparationCount.first { it == 0 }
            ticket.workers.forEach { it.join() }
        }
    }

    internal suspend fun awaitUpdateHandoff(ticket: UpdateHandoff) = withContext(NonCancellable + Dispatchers.IO) {
        synchronized(lock) { check(updateHandoff === ticket && !ticket.committed) }
        awaitRetiringHandoffWork(ticket)
        synchronized(lock) {
            check(updateHandoff === ticket && !ticket.committed)
            check(directoryPreparations == 0 && ticket.workers.all { it.isCompleted } && jobs.values.all { it.isCompleted })
            // Preserve user-paused/completed tasks and all IDs, directories, progress,
            // options and assets. The original cold-queue policy owns normalization.
            mutableTasks.value = mutableTasks.value.map {
                it.copy(item = normalizeRestoredDownloadTask(it.item))
            }
            persistLocked(force = true, handoffWrite = true)
            ticket.drained = true // Only the final successful durable write permits launch.
        }
    }

    /** Invoked only after the updater confirms no launched child remains. Keep the
     * original supervisor alive; a failed successor may have advanced the same queue. */
    internal suspend fun rollbackUpdateHandoff(ticket: UpdateHandoff): Boolean = withContext(NonCancellable + Dispatchers.IO) {
        if (synchronized(lock) { closed }) return@withContext false
        synchronized(lock) { check(updateHandoff === ticket && !ticket.committed) }
        awaitRetiringHandoffWork(ticket)
        synchronized(lock) {
            if (closed) return@synchronized false
            check(updateHandoff === ticket && !ticket.committed)
            check(directoryPreparations == 0 && ticket.workers.all { it.isCompleted } && jobs.values.all { it.isCompleted })
            mutableTasks.value = if (ticket.drained) restore(strict = true) else mutableTasks.value.map {
                it.copy(item = normalizeRestoredDownloadTask(it.item))
            }
            // If persistence or strict reload fails, the exact ticket stays frozen.
            persistLocked(force = true, handoffWrite = true)
            updateHandoff = null
            mutableUpdateHandoffPending.value = false
            scheduleLocked()
            true
        }
    }

    /** Healthy registry commit only. Nonthrowing, synchronous and no IO; subsequent
     * close/shutdown must never overwrite a now-running successor's queue snapshot. */
    internal fun commitUpdateHandoff(ticket: UpdateHandoff) = synchronized(lock) {
        if (updateHandoff === ticket && ticket.drained) ticket.committed = true
    }
    private val mutableTasks = MutableStateFlow(restore())
    val tasks: StateFlow<List<DownloadTask>> = mutableTasks.asStateFlow()

    init { synchronized(lock) { scheduleLocked() } }

    fun enqueue(source: PlaybackSource, destination: Path = destinationFor(), metadata: DownloadMetadata = DownloadMetadata(), stillOwned: () -> Boolean = { true }): String {
        synchronized(lock) { assertMutationsAllowedLocked() }
        publication.admit(source, stillOwned) { Unit }
        val task = prepareDownloadTask(source, destination, metadata)
        prepareOwnedDirectoryForEnqueue(task) // actual directory work remains outside the Store monitor
        val id = publication.admit(source, stillOwned) { enqueueAdmitted(task, source) }
        synchronized(lock) { persistLocked(force = true); scheduleLocked() }
        return id
    }

    /** Original full task consumer; storage/HTTP context stays in the existing wrapper.
     * Caller/entry only guard admission. The global queue owns its accepted task afterwards. */
    internal fun enqueueOriginal(item: UpstreamDownloadTask, source: PlaybackSource, destination: Path,
        downloadAllowed: Boolean, seasonId: Long, episodeId: Long, isCourse: Boolean,
        stillOwned: () -> Boolean, entryAdmission: ((() -> Unit) -> Boolean)): Boolean {
        synchronized(lock) { assertMutationsAllowedLocked() }
        require(downloadAllowed) { "此媒体的服务器权限不允许下载" }
        require(item.bvid.isNotBlank() && item.cid > 0L) { "原下载任务必须保留实际 BVID/CID" }
        publication.admit(source, stillOwned) { Unit }
        val video = requireMediaUrl(source.videoUrl)
        val audio = source.audioUrl?.let(::requireMediaUrl)
        val segments = source.progressiveSegments.map { DownloadProgressiveSegment(requireMediaUrl(it.url), it.durationSeconds) }
        require(segments.isEmpty() || audio == null) { "多段渐进媒体不能同时含独立音轨" }
        require(segments.none { it.url.substringBefore('?').endsWith(".m3u8", ignoreCase = true) } &&
            !video.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) { "直播 HLS 不适用于视频下载队列" }
        if (item.isAudioOnly) {
            require(item.videoUrl.isBlank() && requireMediaUrl(item.audioUrl) == video && audio == null && segments.isEmpty()) {
                "音频下载必须使用同一已捕获音轨，不能丢弃或替换原任务地址"
            }
        } else require(requireMediaUrl(item.videoUrl) == video && item.audioUrl == audio.orEmpty()) {
            "原任务音视频地址与捕获的授权来源不一致"
        }
        val queued = DownloadTask(item.copy(status = DownloadStatus.QUEUED, errorMessage = null),
            destination.toAbsolutePath().normalize().toString(), source.referer, source.userAgent,
            seasonId, episodeId, isCourse, segments, source.authorizationReceipt, source.cookieHeader,
            com.bilipai.desktop.player.copyPlaybackStreamHeaders(source.streamHeaders))
        prepareOwnedDirectoryForEnqueue(queued) // existing filesystem, tracked outside Store/entry
        var added = false
        publication.admit(source, stillOwned) {
            if (!entryAdmission {
                if (!stillOwned()) throw CancellationException("原下载入口已退役")
                synchronized(lock) {
                    assertMutationsAllowedLocked()
                    val existing = mutableTasks.value.firstOrNull { it.id == queued.id }
                    // EXACT original addTask duplicate/failed/paused policy.
                    if (existing == null || existing.status == DownloadStatus.FAILED || existing.status == DownloadStatus.PAUSED) {
                        mutableTasks.value = mutableTasks.value.filterNot { it.id == queued.id } + queued
                        added = true
                    }
                }
            }) throw CancellationException("原下载入口已退役")
        }
        if (added) synchronized(lock) { persistLocked(force = true); scheduleLocked() }
        return added
    }

    private fun prepareDownloadTask(source: PlaybackSource, destination: Path, metadata: DownloadMetadata): DownloadTask {
        require(metadata.downloadAllowed) { "此媒体的服务器权限不允许下载" }
        val video = requireMediaUrl(source.videoUrl)
        val audio = source.audioUrl?.let(::requireMediaUrl)
        val segments = source.progressiveSegments.map { DownloadProgressiveSegment(requireMediaUrl(it.url), it.durationSeconds) }
        require(segments.isEmpty() || audio == null) { "多段渐进媒体不能同时含独立音轨" }
        require(segments.none { it.url.substringBefore('?').endsWith(".m3u8", ignoreCase = true) }) { "直播 HLS 不适用于视频下载队列" }
        require(!video.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) { "直播 HLS 不适用于视频下载队列" }
        val item = UpstreamDownloadTask(aid = metadata.aid,
            bvid = metadata.bvid.ifBlank { "local-${UUID.randomUUID()}" }, cid = metadata.cid, title = source.title,
            episodeLabel = metadata.episodeLabel, cover = metadata.cover, ownerName = metadata.author,
            ownerFace = "", duration = metadata.durationSeconds, quality = metadata.quality,
            qualityDesc = metadata.qualityLabel, videoUrl = video, audioUrl = audio.orEmpty(), status = DownloadStatus.QUEUED,
            isAudioOnly = metadata.audioOnly, options = DownloadOptions(includeDanmaku = metadata.includeDanmaku),
            groupKey = metadata.groupKey, groupTitle = metadata.groupTitle, episodeSortIndex = metadata.episodeSortIndex,
            episodeCount = metadata.episodeCount, isVerticalVideo = metadata.isVerticalVideo,
            assets = if (metadata.includeCover) emptyList() else listOf(DownloadAssetState(DownloadAssetKind.COVER, DownloadAssetStatus.SKIPPED)))
        val task = DownloadTask(item, destination.toAbsolutePath().normalize().toString(), source.referer, source.userAgent,
            metadata.seasonId, metadata.episodeId, metadata.isCourse, segments, source.authorizationReceipt, source.cookieHeader,
            com.bilipai.desktop.player.copyPlaybackStreamHeaders(source.streamHeaders))
        return task
    }

    private fun enqueueAdmitted(task: DownloadTask, source: PlaybackSource): String = synchronized(lock) {
        assertMutationsAllowedLocked()
        val existing = mutableTasks.value.firstOrNull { it.id == task.id }
        if (existing != null) {
            if (existing.status in setOf(DownloadStatus.FAILED, DownloadStatus.PAUSED)) resumeAdmitted(existing.id, source)
            return existing.id
        }
        mutableTasks.value = mutableTasks.value.filterNot { it.id == task.id } + task
        task.id
    }

    /** Pause/cancel retain upstream .part/chunk files for a later Range resume. */
    fun pause(id: String) = synchronized(lock) {
        assertMutationsAllowedLocked()
        // A stale pause-all snapshot cannot undo an already confirmed output.
        if (mutableTasks.value.any { it.id == id && it.status == DownloadStatus.COMPLETED }) return@synchronized
        updateLocked(id) { it.copy(item = it.item.copy(status = DownloadStatus.PAUSED, errorMessage = null)) }
        jobs[id]?.cancel()
        persistLocked(force = true)
        scheduleLocked()
    }
    fun cancel(id: String) = pause(id)

    fun resume(id: String, refreshedSource: PlaybackSource? = null) {
        synchronized(lock) { assertMutationsAllowedLocked() }
        if (refreshedSource != null) publication.admit(refreshedSource, { true }) { resumeAdmitted(id, refreshedSource) }
        else resumeAdmitted(id, null)
        synchronized(lock) { persistLocked(force = true); scheduleLocked() }
    }
    private fun resumeAdmitted(id: String, refreshedSource: PlaybackSource?) = synchronized(lock) {
        assertMutationsAllowedLocked()
        val task = mutableTasks.value.firstOrNull { it.id == id } ?: return@synchronized
        if (task.status !in setOf(DownloadStatus.FAILED, DownloadStatus.PAUSED)) return@synchronized
        updateLocked(id) {
            val item = if (refreshedSource == null) it.item else it.item.copy(videoUrl = requireMediaUrl(refreshedSource.videoUrl),
                audioUrl = refreshedSource.audioUrl?.let(::requireMediaUrl).orEmpty())
            if (refreshedSource != null) require(mediaIdentity(item.videoUrl) == mediaIdentity(it.item.videoUrl) &&
                mediaIdentity(item.audioUrl) == mediaIdentity(it.item.audioUrl) &&
                refreshedSource.progressiveSegments.map { part -> mediaIdentity(part.url) } == it.progressiveSegments.map { part -> mediaIdentity(part.url) }) {
                "新的媒体轨道或分段与此任务不同，请移除此任务后重新加入下载"
            }
            it.copy(item = item.copy(status = DownloadStatus.QUEUED, errorMessage = null),
                referer = refreshedSource?.referer ?: it.referer, userAgent = refreshedSource?.userAgent ?: it.userAgent,
                authorizationReceipt = refreshedSource?.authorizationReceipt, cookieHeader = refreshedSource?.cookieHeader.orEmpty(),
                streamHeaders = refreshedSource?.streamHeaders?.let { headers -> com.bilipai.desktop.player.copyPlaybackStreamHeaders(headers) } ?: emptyMap(),
                progressiveSegments = if (refreshedSource == null) it.progressiveSegments else it.progressiveSegments.zip(refreshedSource.progressiveSegments).map { (old, part) -> old.copy(url = requireMediaUrl(part.url), durationSeconds = part.durationSeconds) })
        }
    }
    fun retry(id: String, refreshedSource: PlaybackSource? = null) = resume(id, refreshedSource)

    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    fun remove(id: String, deleteFiles: Boolean = false) = synchronized(lock) {
        assertMutationsAllowedLocked()
        val task = mutableTasks.value.firstOrNull { it.id == id } ?: return@synchronized
        val previousJob = jobs[id]
        mutableTasks.value = mutableTasks.value.filterNot { it.id == id }
        if (deleteFiles) {
            // Transfer the same slot before cancel: completion may otherwise reschedule synchronously.
            // ATOMIC enters cleanup even if close cancels this scope before dispatch.
            val retirement = scope.launch(start = CoroutineStart.ATOMIC) {
                withContext(NonCancellable) {
                    previousJob?.join()
                    deleteOwnedDirectory(task)
                }
            }
            installJobLocked(id, retirement)
        }
        previousJob?.cancel()
        persistLocked(force = true)
        scheduleLocked()
    }

    fun offlinePlayback(id: String): PlaybackSource {
        val task = tasks.value.firstOrNull { it.id == id } ?: throw IllegalArgumentException("下载任务不存在")
        require(task.status == DownloadStatus.COMPLETED) { "下载尚未完成" }
        val output = task.item.filePath?.let(Path::of) ?: throw IOException("下载文件不存在")
        val directory = Path.of(task.directory).toAbsolutePath().normalize()
        require(output.toAbsolutePath().normalize().startsWith(directory) && Files.isRegularFile(output)) { "下载文件不存在或不在任务目录" }
        return PlaybackSource(output.toAbsolutePath().toString(), referer = "", title = task.title,
            startPositionSeconds = task.item.lastPlaybackPositionMs.coerceAtLeast(0) / 1000.0)
    }

    fun savePlaybackPosition(id: String, positionMs: Long, durationMs: Long) = update(id, true) {
        it.copy(item = it.item.copy(lastPlaybackPositionMs = resolveOfflinePersistedPlaybackPosition(positionMs, durationMs)))
    }

    fun offlineEpisodeQueue(id: String): List<DownloadTask> {
        val snapshot = tasks.value
        val current = snapshot.firstOrNull { it.id == id } ?: return emptyList()
        val wrappers = snapshot.associateBy { it.id }
        return resolveOfflineEpisodeQueue(snapshot.map { it.item }, current.item).mapNotNull { wrappers[it.id] }
    }

    fun offlineDanmaku(id: String): OfflineDanmakuFiles? {
        val task = tasks.value.firstOrNull { it.id == id && it.status == DownloadStatus.COMPLETED } ?: return null
        val directory = Path.of(task.directory).toAbsolutePath().normalize()
        fun owned(value: String): Boolean = runCatching {
            val path = Path.of(value).toAbsolutePath().normalize()
            path.startsWith(directory) && !Files.isSymbolicLink(path) &&
                (!Files.exists(path) || path.toRealPath().startsWith(directory.toRealPath()))
        }.getOrDefault(false)
        val safePaths = task.item.localDanmakuSegmentPaths.mapIndexed { index, value ->
            if (owned(value)) value else directory.resolve(".missing-danmaku-$index.pb").toString()
        }
        val safeTask = task.item.copy(localDanmakuSegmentPaths = safePaths,
            localDanmakuMetadataPath = task.item.localDanmakuMetadataPath?.takeIf(::owned))
        val manifest = safeTask.localDanmakuMetadataPath?.let { path -> runCatching {
            json.decodeFromString(LocalDanmakuManifest.serializer(), Files.readString(Path.of(path)))
        }.getOrNull() } ?: return null
        val local = DownloadDanmakuAssetService.readLocalSource(safeTask)
        if (local.totalFileCount == 0) return null
        // Keep the original manifest slot positions even if a user later removes a
        // middle file. The window loader reports that hole instead of shifting time.
        val standardCount = manifest.standardSegmentCount.coerceIn(0, safePaths.size)
        return OfflineDanmakuFiles(safePaths.take(standardCount).map(Path::of), local.specialSegmentPaths.map(Path::of))
    }

    /** Completion, including cancellation before body entry, retires this exact jobs slot. */
    private fun installJobLocked(id: String, job: Job) {
        jobs[id] = job
        job.invokeOnCompletion {
            synchronized(lock) {
                if (jobs[id] === job) {
                    jobs.remove(id)
                    scheduleLocked()
                }
            }
        }
    }

    private fun scheduleLocked() {
        if (closed || updateHandoff != null) return
        // A cancelled Windows job retains its file owner until its finally block
        // completes. Reserve that slot while the original policy selects work.
        val candidates = mutableTasks.value.map { task ->
            if (task.id in jobs && !isDownloadTaskActive(task.item))
                task.item.copy(status = DownloadStatus.PENDING) else task.item
        }
        val retiringRemovedJobs = jobs.keys.count { id -> mutableTasks.value.none { it.id == id } }
        resolveNextQueuedDownloadTaskIds(candidates,
            maxConcurrent = (DEFAULT_MAX_CONCURRENT_DOWNLOADS - retiringRemovedJobs).coerceAtLeast(0)).forEach { id ->
            // Match original enqueueDownload: reserve PENDING under the queue
            // lock before launching, so a rapid second enqueue cannot overbook.
            updateLocked(id) { it.copy(item = it.item.copy(status = DownloadStatus.PENDING, errorMessage = null)) }
            val job = scope.launch(start = CoroutineStart.LAZY) { runTask(id) }
            installJobLocked(id, job)
            job.start()
        }
        persistLocked(force = true)
    }

    private suspend fun runTask(id: String) {
        val pendingJob = currentCoroutineContext().job
        var expectedTask: DownloadTask? = null
        val activeStatuses = setOf(DownloadStatus.PENDING, DownloadStatus.DOWNLOADING, DownloadStatus.MERGING)
        fun queueOwnedLocked(statuses: Set<DownloadStatus>, requireActive: Boolean = true): Boolean {
            val expected = expectedTask ?: return false
            return !closed && updateHandoff == null && (!requireActive || pendingJob.isActive) && jobs[id] === pendingJob &&
                mutableTasks.value.any { it.id == id && it.item.createdAt == expected.item.createdAt &&
                    it.destinationRoot == expected.destinationRoot && it.authorizationReceipt == expected.authorizationReceipt &&
                    it.status in statuses }
        }
        fun owned(statuses: Set<DownloadStatus>) = { synchronized(lock) { queueOwnedLocked(statuses) } }
        fun publish(statuses: Set<DownloadStatus>, change: (DownloadTask) -> DownloadTask) {
            val expected = expectedTask ?: throw CancellationException("下载任务已退役")
            val current = owned(statuses)
            publication.admit(expected.playbackSource(), current) {
                synchronized(lock) {
                    if (!queueOwnedLocked(statuses)) throw CancellationException("下载任务已退役")
                    updateLocked(id, change)
                }
            }
            synchronized(lock) { persistLocked(force = true) }
        }
        fun read(statuses: Set<DownloadStatus>): DownloadTask = synchronized(lock) {
            if (!queueOwnedLocked(statuses)) throw CancellationException("下载任务已退役")
            mutableTasks.value.first { it.id == id }
        }
        fun pauseRetiredWorker() {
            // Retired authorization can no longer admit a business result. Only retire this
            // exact existing queue entity; never a replacement, queued user intent or new account.
            synchronized(lock) {
                if (queueOwnedLocked(activeStatuses, requireActive = false)) {
                    updateLocked(id) { it.copy(item = it.item.copy(status = DownloadStatus.PAUSED)) }
                    persistLocked(force = true)
                }
            }
        }
        try {
            var task = synchronized(lock) {
                mutableTasks.value.firstOrNull { !closed && updateHandoff == null && jobs[id] === pendingJob &&
                    it.id == id && it.status == DownloadStatus.PENDING }
            } ?: return
            expectedTask = task
            currentCoroutineContext().ensureActive()
            val starting = setOf(DownloadStatus.PENDING)
            val downloading = setOf(DownloadStatus.DOWNLOADING)
            val merging = setOf(DownloadStatus.MERGING)
            if (publication.requiresAccountReceipt && task.authorizationReceipt == null) {
                val resolution = sourceResolver?.invoke(task) ?: throw CancellationException("Saved download requires fresh owned authorization")
                val refreshed = resolution.source
                currentCoroutineContext().ensureActive()
                publication.admit(refreshed, owned(starting)) { Unit }
                val refreshedVideoUrl = requireMediaUrl(refreshed.videoUrl)
                val refreshedAudioUrl = refreshed.audioUrl?.let(::requireMediaUrl).orEmpty()
                val restoredSegments = refreshed.progressiveSegments.mapIndexed { index, part ->
                    val url = requireMediaUrl(part.url)
                    val old = task.progressiveSegments.getOrNull(index)
                    // CDN/signature refreshes keep the same indexed asset and its resume counters.
                    if (old != null && mediaIdentity(old.url) == mediaIdentity(url))
                        old.copy(url = url, durationSeconds = part.durationSeconds)
                    else DownloadProgressiveSegment(url, part.durationSeconds)
                }
                val oldVideoIdentity = task.item.videoUrl.takeIf { !task.item.isAudioOnly && task.progressiveSegments.isEmpty() }?.let(::mediaIdentity)
                val newVideoIdentity = refreshedVideoUrl.takeIf { !task.item.isAudioOnly && restoredSegments.isEmpty() }?.let(::mediaIdentity)
                val oldAudioIdentity = task.item.audioUrl.ifBlank { task.item.videoUrl }
                    .takeIf { task.progressiveSegments.isEmpty() && (task.item.audioUrl.isNotBlank() || task.item.isAudioOnly) }?.let(::mediaIdentity)
                val newAudioIdentity = refreshedAudioUrl.ifBlank { refreshedVideoUrl }
                    .takeIf { restoredSegments.isEmpty() && (refreshedAudioUrl.isNotBlank() || task.item.isAudioOnly) }?.let(::mediaIdentity)
                val videoIdentityChanged = oldVideoIdentity != newVideoIdentity
                val audioIdentityChanged = oldAudioIdentity != newAudioIdentity
                val changedSegmentIndices = (0 until maxOf(task.progressiveSegments.size, restoredSegments.size)).filter { index ->
                    task.progressiveSegments.getOrNull(index)?.url?.let(::mediaIdentity) !=
                        restoredSegments.getOrNull(index)?.url?.let(::mediaIdentity)
                }
                val segmentKind = if (task.item.isAudioOnly) DownloadAssetKind.AUDIO else DownloadAssetKind.VIDEO
                val changedAssetKinds = buildSet {
                    if (videoIdentityChanged) add(DownloadAssetKind.VIDEO)
                    if (audioIdentityChanged) add(DownloadAssetKind.AUDIO)
                    if (changedSegmentIndices.isNotEmpty()) add(segmentKind)
                }
                val restoredDirectory = ensureOwnedDirectory(task)
                val restoredVideo = restoredDirectory.resolve("video.m4s")
                val oldVideoAsset = task.item.assets.firstOrNull { it.kind == DownloadAssetKind.VIDEO }
                val completedVideoRetained = oldVideoIdentity != null && !videoIdentityChanged &&
                    oldVideoAsset?.status == DownloadAssetStatus.COMPLETED && oldVideoAsset.totalBytes > 0L &&
                    oldVideoAsset.downloadedBytes == oldVideoAsset.totalBytes && Files.isRegularFile(restoredVideo) &&
                    Files.size(restoredVideo) == oldVideoAsset.totalBytes
                currentCoroutineContext().ensureActive()
                publication.admit(refreshed, owned(starting)) {
                    synchronized(lock) {
                        if (!queueOwnedLocked(starting)) throw CancellationException("下载任务已退役")
                        // Invalidate changed inputs before replacing their identities, under the final source/worker admission.
                        if (videoIdentityChanged) clearAssetFiles(restoredVideo)
                        if (audioIdentityChanged) clearAssetFiles(restoredDirectory.resolve("audio.m4s"))
                        changedSegmentIndices.forEach { index ->
                            clearAssetFiles(restoredDirectory.resolve("segment-${(index + 1).toString().padStart(4, '0')}.media"))
                        }
                        updateLocked(id) { wrapper ->
                            val assetsReset = if (changedAssetKinds.isNotEmpty()) {
                                val next = changedAssetKinds.fold(wrapper.item) { item, kind ->
                                    val segmented = kind == segmentKind && restoredSegments.isNotEmpty()
                                    val used = segmented || (if (kind == DownloadAssetKind.VIDEO) newVideoIdentity else newAudioIdentity) != null
                                    item.withAssetState(DownloadAssetState(kind,
                                        if (used) DownloadAssetStatus.PENDING else DownloadAssetStatus.SKIPPED,
                                        totalBytes = if (segmented) restoredSegments.sumOf { it.totalBytes } else 0L,
                                        downloadedBytes = if (segmented) restoredSegments.sumOf { it.downloadedBytes } else 0L,
                                        segmentCount = if (segmented) restoredSegments.size else 0))
                                }
                                val downloaded = next.assets.sumOf { it.downloadedBytes }
                                val total = next.assets.sumOf { it.totalBytes }
                                wrapper.copy(item = next.copy(
                                    videoProgress = if (DownloadAssetKind.VIDEO in changedAssetKinds) 0f else next.videoProgress,
                                    audioProgress = if (DownloadAssetKind.AUDIO in changedAssetKinds) 0f else next.audioProgress,
                                    downloadedSize = downloaded,
                                    progress = if (total > 0L) (downloaded.toDouble() / total * 0.9).coerceIn(0.0, 0.9).toFloat() else 0f))
                            } else wrapper
                            // A complete retained video is still the original file, even if a new parse advertises another track.
                            val qualified = if (completedVideoRetained) assetsReset else resolution.applyActualQuality(assetsReset)
                            qualified.copy(item = qualified.item.copy(videoUrl = refreshedVideoUrl, audioUrl = refreshedAudioUrl),
                            referer = refreshed.referer, userAgent = refreshed.userAgent, authorizationReceipt = refreshed.authorizationReceipt,
                            cookieHeader = refreshed.cookieHeader, streamHeaders = com.bilipai.desktop.player.copyPlaybackStreamHeaders(refreshed.streamHeaders),
                            progressiveSegments = restoredSegments) }
                        task = mutableTasks.value.first { it.id == id }
                        expectedTask = task
                    }
                }
                synchronized(lock) { persistLocked(force = true) }
            }
            val brandSource = task.playbackSource()
            val brandCreatedAt = task.item.createdAt
            val brandOrigin = pendingJob.let { worker -> com.bilipai.desktop.ui.DesktopBrandSuccessOrigin(worker,
                { synchronized(lock) { !closed && updateHandoff == null && mutableTasks.value.any {
                    it.id == id && it.item.createdAt == brandCreatedAt && it.authorizationReceipt == task.authorizationReceipt &&
                        it.status == DownloadStatus.COMPLETED
                } } && publication.isCurrent(brandSource) },
                { action ->
                    try {
                        publication.admit(brandSource, { synchronized(lock) { !closed && updateHandoff == null && mutableTasks.value.any {
                            it.id == id && it.item.createdAt == brandCreatedAt && it.authorizationReceipt == task.authorizationReceipt &&
                                it.status == DownloadStatus.COMPLETED
                        } } }) {
                            synchronized(lock) {
                                if (!closed && updateHandoff == null && mutableTasks.value.any { it.id == id && it.item.createdAt == brandCreatedAt &&
                                    it.authorizationReceipt == task.authorizationReceipt && it.status == DownloadStatus.COMPLETED }) action()
                            }
                        }
                        true
                    } catch (_: CancellationException) { false }
                }) }
            publication.admit(task.playbackSource(), owned(starting)) { Unit }
            currentCoroutineContext().ensureActive()
            val directory = ensureOwnedDirectory(task) // this worker retains the same file slot until actual completion
            publish(starting) { it.copy(item = it.item.copy(status = DownloadStatus.DOWNLOADING, errorMessage = null)) }
            downloadOptionalAssets(read(downloading), directory, pendingJob, owned(downloading), { action ->
                synchronized(lock) {
                    if (!queueOwnedLocked(downloading)) false else { action(); true }
                }
            }) { change -> publish(downloading, change) }
            val video = directory.resolve("video.m4s")
            val audio = directory.resolve("audio.m4s")
            val segmentFiles = task.progressiveSegments.indices.map { directory.resolve("segment-${(it + 1).toString().padStart(4, '0')}.media") }
            if (segmentFiles.isNotEmpty()) {
                for ((index, file) in segmentFiles.withIndex()) {
                    val active = read(downloading)
                    downloadAsset(id, active.progressiveSegments[index].url, file,
                        if (task.item.isAudioOnly) DownloadAssetKind.AUDIO else DownloadAssetKind.VIDEO, index)
                }
            } else if (!task.item.isAudioOnly) downloadAsset(id, task.item.videoUrl, video, DownloadAssetKind.VIDEO)
            val current = read(downloading)
            if (segmentFiles.isEmpty() && (current.item.audioUrl.isNotBlank() || task.item.isAudioOnly))
                downloadAsset(id, current.item.audioUrl.ifBlank { current.item.videoUrl }, audio, DownloadAssetKind.AUDIO)
            currentCoroutineContext().ensureActive()
            publish(downloading) { it.copy(item = it.item.copy(status = DownloadStatus.MERGING, progress = 0.95f)) }
            val output = directory.resolve("${safeOutputName(task.title)}.${if (task.item.isAudioOnly) "m4a" else "mp4"}")
            if (segmentFiles.isNotEmpty()) muxer.muxSegments(segmentFiles, output, task.item.isAudioOnly)
            else muxer.mux(video.takeUnless { task.item.isAudioOnly }, audio.takeIf { current.item.audioUrl.isNotBlank() || task.item.isAudioOnly }, output)
            currentCoroutineContext().ensureActive()
            val outputSize = Files.size(output)
            // Confirm a ready output before discarding inputs. Cancellation before this commit
            // keeps every downloaded track; confirmed output remains complete if cleanup is interrupted.
            publish(merging) { it.copy(item = it.item.copy(status = DownloadStatus.COMPLETED, progress = 1f,
                filePath = output.toString(), fileSize = outputSize, errorMessage = null)) }
            withContext(NonCancellable) {
                (listOf(video, audio) + segmentFiles).forEach { path -> runCatching { Files.deleteIfExists(path) } }
            }
            // The same jobs slot remains reserved through input cleanup and worker completion.
            runCatching { brandEvents?.downloadCompleted(brandOrigin, id, brandCreatedAt, task.title) }
        } catch (cancelled: CancellationException) {
            pauseRetiredWorker()
        } catch (error: Exception) {
            if (!pendingJob.isActive) pauseRetiredWorker() else try {
                publish(activeStatuses) { it.copy(item = it.item.copy(status = DownloadStatus.FAILED,
                    errorMessage = error.message?.take(2000) ?: "下载失败")) }
            } catch (_: CancellationException) {
                pauseRetiredWorker()
            }
        }
    }

    private suspend fun downloadOptionalAssets(task: DownloadTask, directory: Path, callerJob: Job,
        stillOwned: () -> Boolean, taskAdmission: ((() -> Unit) -> Boolean),
        publish: ((DownloadTask) -> DownloadTask) -> Unit) {
        val id = task.id
        fun publishAssetState(state: DownloadAssetState) {
            publish { wrapper ->
                val next = wrapper.item.withAssetState(state)
                val downloaded = next.assets.sumOf { it.downloadedBytes }
                val total = next.assets.sumOf { it.totalBytes }
                wrapper.copy(item = next.copy(downloadedSize = downloaded,
                    progress = if (total > 0L) (downloaded.toDouble() / total * 0.9).coerceIn(0.0, 0.9).toFloat() else 0f))
            }
        }
        if (task.item.cover.isNotBlank() && task.item.assets.none { it.kind == DownloadAssetKind.COVER && it.status == DownloadAssetStatus.SKIPPED }) {
            val cover = directory.resolve("cover.jpg")
            try {
                downloadAsset(id, requireMediaUrl(task.item.cover), cover, DownloadAssetKind.COVER)
                publish { it.copy(item = it.item.copy(localCoverPath = cover.toString())) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                publishAssetState(DownloadAssetState(
                    DownloadAssetKind.COVER, DownloadAssetStatus.FAILED, errorMessage = error.message))
            }
        } else publishAssetState(DownloadAssetState(DownloadAssetKind.COVER, DownloadAssetStatus.SKIPPED))
        if (task.item.options.includeDanmaku && task.item.cid > 0 && danmakuDownloader != null) {
            try {
                val (segments, manifest) = DownloadDanmakuTransport.withTask(task.playbackSource(), publication,
                    callerJob, stillOwned, taskAdmission) {
                    danmakuDownloader.invoke(task, directory) { state ->
                        publishAssetState(state)
                    }
                }
                publish { it.copy(item = it.item.copy(localDanmakuSegmentPaths = segments, localDanmakuMetadataPath = manifest)) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                publishAssetState(DownloadAssetState(
                    DownloadAssetKind.DANMAKU, DownloadAssetStatus.FAILED, errorMessage = error.message))
            }
        } else publishAssetState(DownloadAssetState(DownloadAssetKind.DANMAKU, DownloadAssetStatus.SKIPPED))
    }

    private suspend fun downloadAsset(id: String, url: String, output: Path, kind: DownloadAssetKind, progressiveIndex: Int? = null) {
        val context = currentCoroutineContext()
        val task = synchronized(lock) { mutableTasks.value.firstOrNull { it.id == id } } ?: throw CancellationException()
        val source = task.playbackSource()
        val callerJob = context[Job]
        val owned = { callerJob?.isActive == true && synchronized(lock) { !closed && updateHandoff == null && jobs[id] === callerJob &&
            mutableTasks.value.any { it.id == id && it.status == DownloadStatus.DOWNLOADING && it.authorizationReceipt == task.authorizationReceipt } } }
        publication.admit(source, owned) { Unit }
        var activeSource = source
        fun calls() = publication.calls(client, activeSource, owned, callerJob)
        fun progress(wrapper: DownloadTask, bytes: Long, total: Long, completed: Boolean, count: Int): DownloadTask {
            val parts = if (progressiveIndex == null) wrapper.progressiveSegments else wrapper.progressiveSegments.mapIndexed { index, part ->
                if (index == progressiveIndex) part.copy(totalBytes = total, downloadedBytes = bytes) else part
            }
            val downloaded = if (progressiveIndex == null) bytes else parts.sumOf { it.downloadedBytes }
            val knownTotal = if (progressiveIndex == null) total else parts.sumOf { it.totalBytes }
            val allComplete = completed && (progressiveIndex == null || progressiveIndex == parts.lastIndex)
            val next = wrapper.item.withAssetState(DownloadAssetState(kind,
                if (allComplete) DownloadAssetStatus.COMPLETED else DownloadAssetStatus.DOWNLOADING,
                knownTotal, downloaded, output.toString(), if (allComplete) null else output.toString() + ".part",
                segmentCount = if (progressiveIndex == null) count else parts.size))
            val allDownloaded = next.assets.sumOf { it.downloadedBytes }
            val allTotal = next.assets.sumOf { it.totalBytes }
            return wrapper.copy(progressiveSegments = parts, item = next.copy(downloadedSize = allDownloaded,
                progress = if (allTotal > 0) (allDownloaded.toDouble() / allTotal * 0.9).toFloat().coerceIn(0f, 0.9f) else 0f))
        }
        fun reportProgress(bytes: Long, total: Long, completed: Boolean, count: Int) {
            publication.admit(activeSource, owned) {
                synchronized(lock) {
                    if (!owned()) throw CancellationException("下载任务已退役")
                    updateLocked(id) { wrapper -> progress(wrapper, bytes, total, completed, count) }
                }
            }
            // Persist an already-admitted task snapshot outside the Store monitor.
            synchronized(lock) { persistLocked(force = completed) }
        }
        suspend fun download(activeUrl: String) = ResumableAssetDownloader(calls()).download(
            HttpDownloadAssetRequest(activeUrl, output.toFile(), com.bilipai.desktop.cast.desktopCastStreamHeaders(activeSource)),
            ensureActive = { context.ensureActive(); publication.admit(activeSource, owned) { Unit } },
            onProgress = { bytes, total -> reportProgress(bytes, total, false, 0) })
        val result = try { download(url)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) {
            if (sourceResolver == null || kind !in setOf(DownloadAssetKind.VIDEO, DownloadAssetKind.AUDIO) ||
                !shouldRefreshDownloadUrlAfterFailure(error)) throw error
            context.ensureActive()
            // A retired choice is cancellation, never a request under the newly selected account.
            publication.admit(activeSource, owned) { Unit }
            val resolution = sourceResolver.invoke(task) ?: throw error
            val refreshed = resolution.source
            context.ensureActive()
            if (refreshed.authorizationReceipt != source.authorizationReceipt ||
                refreshed.primaryAccountEpoch != source.primaryAccountEpoch)
                throw CancellationException("下载来源授权已退役")
            publication.admit(activeSource, owned) { Unit }
            publication.admit(refreshed, owned) { Unit }
            if (refreshed.progressiveSegments.map { mediaIdentity(it.url) } != task.progressiveSegments.map { mediaIdentity(it.url) })
                throw IOException("原媒体分段已改变，请移除此任务后重新加入下载", error)
            val refreshedVideoUrl = requireMediaUrl(refreshed.videoUrl)
            val refreshedAudioUrl = refreshed.audioUrl?.let(::requireMediaUrl).orEmpty()
            val refreshedSegments = task.progressiveSegments.zip(refreshed.progressiveSegments).map { (old, part) ->
                old.copy(url = requireMediaUrl(part.url), durationSeconds = part.durationSeconds)
            }
            if (progressiveIndex == null && kind == DownloadAssetKind.AUDIO && !task.item.isAudioOnly && refreshedAudioUrl.isBlank())
                throw IOException("原音轨已不可用，请移除此任务后重新加入下载", error)
            val nextUrl = if (progressiveIndex != null) refreshedSegments[progressiveIndex].url
                else if (kind == DownloadAssetKind.AUDIO) refreshedAudioUrl.ifBlank { refreshedVideoUrl } else refreshedVideoUrl
            if (nextUrl == url) throw error
            val currentIdentityChanged = mediaIdentity(nextUrl) != mediaIdentity(url)
            // Audio runs after video: never pair a different audio asset with a different parsed video.
            // A signature/CDN refresh of the same audio is still valid for the video already retained.
            val retainVideo = progressiveIndex == null && kind == DownloadAssetKind.AUDIO && !task.item.isAudioOnly
            if (retainVideo && currentIdentityChanged && mediaIdentity(refreshedVideoUrl) != mediaIdentity(task.item.videoUrl))
                throw IOException("新音轨与已缓存视频不匹配，请移除此任务后重新加入下载", error)
            val otherAudioChanged = progressiveIndex == null && kind == DownloadAssetKind.VIDEO &&
                mediaIdentity(task.item.audioUrl) != mediaIdentity(refreshedAudioUrl)
            context.ensureActive()
            publication.admit(refreshed, owned) {
                synchronized(lock) {
                    if (!owned()) throw CancellationException("下载任务已退役")
                    // Keep file invalidation and the corresponding URL/state replacement in one owned admission.
                    if (currentIdentityChanged) clearAssetFiles(output)
                    if (otherAudioChanged) clearAssetFiles(output.resolveSibling("audio.m4s"))
                    updateLocked(id) { wrapper ->
                        var item = wrapper.item
                        if (currentIdentityChanged) item = item.withAssetState(DownloadAssetState(kind, DownloadAssetStatus.PENDING))
                        if (otherAudioChanged) item = item.withAssetState(DownloadAssetState(DownloadAssetKind.AUDIO,
                            if (refreshedAudioUrl.isNotBlank()) DownloadAssetStatus.PENDING else DownloadAssetStatus.SKIPPED))
                        if (currentIdentityChanged || otherAudioChanged) {
                            val total = item.assets.sumOf { it.totalBytes }
                            val downloaded = item.assets.sumOf { it.downloadedBytes }
                            item = item.copy(
                                videoProgress = if (currentIdentityChanged && kind == DownloadAssetKind.VIDEO) 0f else item.videoProgress,
                                audioProgress = if (otherAudioChanged || (currentIdentityChanged && kind == DownloadAssetKind.AUDIO)) 0f else item.audioProgress,
                                downloadedSize = downloaded,
                                progress = if (total > 0L) (downloaded.toDouble() / total * 0.9).coerceIn(0.0, 0.9).toFloat() else 0f)
                        }
                        // Refreshing only audio cannot change the identity or quality of the video already saved.
                        val reset = wrapper.copy(item = item)
                        val qualified = if (kind == DownloadAssetKind.VIDEO) resolution.applyActualQuality(reset) else reset
                        qualified.copy(item = qualified.item.copy(
                            videoUrl = if (retainVideo) wrapper.item.videoUrl else refreshedVideoUrl,
                            audioUrl = refreshedAudioUrl), authorizationReceipt = refreshed.authorizationReceipt,
                            referer = refreshed.referer, userAgent = refreshed.userAgent,
                            cookieHeader = refreshed.cookieHeader, streamHeaders = com.bilipai.desktop.player.copyPlaybackStreamHeaders(refreshed.streamHeaders),
                            progressiveSegments = refreshedSegments)
                    }
                }
            }
            synchronized(lock) { persistLocked(force = true) }
            activeSource = if (retainVideo) refreshed.copy(videoUrl = task.item.videoUrl) else refreshed
            download(nextUrl)
        }
        reportProgress(result.downloadedBytes, result.totalBytes, true, result.segmentCount)
    }

    private fun update(id: String, force: Boolean, change: (DownloadTask) -> DownloadTask) = synchronized(lock) {
        assertMutationsAllowedLocked()
        updateLocked(id, change)
        persistLocked(force)
    }

    private fun updateLocked(id: String, change: (DownloadTask) -> DownloadTask) {
        mutableTasks.value = mutableTasks.value.map { if (it.id == id) change(it).let { next -> next.copy(item = sanitizeDownloadTask(next.item)) } else it }
    }

    private fun persistLocked(force: Boolean, handoffWrite: Boolean = false) {
        // Old worker completions and normal close cannot publish into a successor's
        // queue. Only the explicit prelaunch/confirmed-rollback checkpoint can write.
        if (updateHandoff != null && !handoffWrite) return
        val now = System.nanoTime()
        if (!force && now - lastPersistNanos < 300_000_000L) return
        Files.createDirectories(stateFile.toAbsolutePath().parent)
        val temporary = Files.createTempFile(stateFile.toAbsolutePath().parent, "downloads-", ".tmp")
        try {
            Files.writeString(temporary, json.encodeToString(serializer, mutableTasks.value))
            try { Files.move(temporary, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING)
            }
            lastPersistNanos = now
        } finally { Files.deleteIfExists(temporary) }
    }

    private fun restore(strict: Boolean = false): List<DownloadTask> {
        fun read(): List<DownloadTask> {
            if (!Files.isRegularFile(stateFile)) {
                check(!strict) { "更新后的下载队列无法确认，保持交接冻结" }
                return emptyList()
            }
            return json.decodeFromString(serializer, Files.readString(stateFile)).map {
                it.copy(item = normalizeRestoredDownloadTask(it.item))
            }.distinctBy { it.id }
        }
        return if (strict) read() else runCatching { read() }.getOrDefault(emptyList())
    }

    private fun ensureOwnedDirectory(task: DownloadTask): Path {
        require(task.id.matches(Regex("[A-Za-z0-9_-]+"))) { "下载任务标识无效" }
        val root = Path.of(task.destinationRoot).toAbsolutePath().normalize()
        val directory = root.resolve(DownloadTask.directoryName(task.id)).normalize()
        require(directory.parent == root && !Files.isSymbolicLink(directory)) { "下载目录无效" }
        Files.createDirectories(directory)
        val marker = directory.resolve(".bilipai-download")
        if (Files.exists(marker)) require(Files.readString(marker) == task.id) { "下载目录属于其他任务" }
        else {
            Files.list(directory).use { require(!it.findAny().isPresent) { "下载目录已存在其他文件" } }
            Files.writeString(marker, task.id, java.nio.file.StandardOpenOption.CREATE_NEW)
        }
        return directory
    }

    private fun deleteOwnedDirectory(task: DownloadTask) {
        val directory = Path.of(task.directory).toAbsolutePath().normalize()
        val root = Path.of(task.destinationRoot).toAbsolutePath().normalize()
        require(directory.parent == root && directory.fileName.toString() == DownloadTask.directoryName(task.id) && !Files.isSymbolicLink(directory))
        if (Files.notExists(directory)) return // repeated retirement after the same directory was already removed
        val marker = directory.resolve(".bilipai-download")
        require(Files.isRegularFile(marker) && Files.readString(marker) == task.id) { "无法确认下载目录归属" }
        Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    /** Root awaits the original workers before exit or launching a replacement process. */
    internal suspend fun shutdownForApplicationExit(): Unit = withContext(NonCancellable + Dispatchers.IO) {
        try {
            synchronized(lock) {
                if (!closed) {
                    closed = true
                    mutableTasks.value = mutableTasks.value.map {
                        if (isDownloadTaskActive(it.item)) it.copy(item = normalizeRestoredDownloadTask(it.item)) else it
                    }
                }
                // A retry after a failed persistence writes the same closed queue again.
                persistLocked(force = true)
            }
        } finally {
            // Child cleanup and completion handlers need the queue lock; await outside it.
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            mutableTasks.value = mutableTasks.value.map { if (isDownloadTaskActive(it.item)) it.copy(item = it.item.copy(status = DownloadStatus.PAUSED)) else it }
            persistLocked(force = true)
            scope.cancel()
        }
    }

    companion object {
        fun defaultDownloadRoot(): Path = Path.of(System.getProperty("user.home"), "Downloads", "BiliPai")
        internal fun defaultSourceResolver(repository: DesktopRepository): suspend (DownloadTask) -> DesktopDownloadResolvedSource? = { task ->
            val caller = currentCoroutineContext()[Job]
            val expectedReceipt = task.authorizationReceipt
            val owned = { caller?.isActive != false &&
                (expectedReceipt == null || repository.isPlaybackReceiptCurrent(expectedReceipt)) }
            val epoch = expectedReceipt?.accountEpoch ?: repository.sessionEpoch
            if (expectedReceipt == null) {
                // SPI can update the Store's effective buvid identity before a restored task has authority.
                repository.ensureOwnedHomeSession(epoch, owned, repository.ownedHomeService(
                    com.android.purebilibili.core.network.BuvidApi::class.java, "https://api.bilibili.com/", epoch, owned))
            }
            val authorization = repository.capturePlaybackAuthorization(epoch, owned)
            if (expectedReceipt != null && authorization.receipt != expectedReceipt)
                throw CancellationException("下载来源授权已退役")
            // Restored tasks acquire authority once through the existing Store; active tasks keep their exact receipt.
            val receipt = authorization.receipt
            repository.assertPlaybackAuthorization(authorization, owned)
            val source = if (task.episodeId > 0) {
                val media = DesktopMediaRepository(repository)
                val season = media.bangumiSeason(seasonId = task.seasonId, episodeId = task.episodeId,
                    isCourse = task.isCourse, expectedPlaybackReceipt = receipt)
                media.bangumiPlayback(season, season.episodes.first { it.id == task.episodeId },
                    task.item.quality.takeIf { it > 0 } ?: 80, expectedPlaybackReceipt = receipt)
            } else if (task.item.bvid.startsWith("BV") && task.item.cid > 0) {
                val details = repository.videoDetails(task.item.bvid, expectedPlaybackReceipt = receipt)
                val index = details.pages.indexOfFirst { it.cid == task.item.cid }
                if (index < 0) throw IOException("此视频分 P 已不存在")
                repository.playback(details, index, task.item.quality.takeIf { it > 0 } ?: 80,
                    forceRefresh = true, expectedPlaybackReceipt = receipt)
            } else null
            source?.let {
                repository.assertPlaybackAuthorization(authorization, owned)
                if (it.authorizationReceipt != receipt) throw CancellationException("下载来源授权已退役")
                val cookie = repository.capturePlaybackMediaCookieHeader(authorization, it.videoUrl, owned)
                // The repository resolved fresh direct Bilibili URLs, not the old plugin route.
                DesktopDownloadResolvedSource(PlaybackSource(it.videoUrl, it.audioUrl, it.referer, userAgent = task.userAgent,
                    cookieHeader = cookie, title = it.title, progressiveSegments = it.progressiveSegments,
                    authorizationReceipt = receipt), actualVideoQuality = it.quality.takeIf { quality ->
                        // Repository already exposes the selected DASH track ID. Never infer it
                        // from the request, advertised qualities or a progressive video container.
                        !task.item.isAudioOnly && quality > 0 && !it.audioUrl.isNullOrBlank() && it.progressiveSegments.isEmpty()
                    })
            }
        }
        private fun defaultDanmakuDownloader(repository: DesktopRepository): suspend (DownloadTask, Path, (DownloadAssetState) -> Unit) -> Pair<List<String>, String?> = { task, directory, update ->
            val owner = DownloadDanmakuTransport.taskOwner()
            if (owner.source.authorizationReceipt != task.authorizationReceipt)
                throw CancellationException("离线弹幕下载授权与原任务不同")
            DownloadDanmakuTransport.withApi(repository) {
                val result = DownloadDanmakuAssetService.download(task.item, directory.toFile(), update)
                owner.assertCurrent()
                if (result.metadataPath != null) {
                    val manifest = Json { ignoreUnknownKeys = true }.decodeFromString(LocalDanmakuManifest.serializer(),
                        Files.readString(Path.of(result.metadataPath)))
                    if (manifest.standardSegmentCount != result.expectedStandardSegmentCount)
                        throw IOException("弹幕标准分段未全部下载（" + manifest.standardSegmentCount + "/" + result.expectedStandardSegmentCount + "），媒体文件继续下载")
                }
                owner.assertCurrent()
                result.segmentPaths to result.metadataPath
            }
        }
        private fun defaultStateFile(): Path = Path.of(System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
            ?: Path.of(System.getProperty("user.home"), ".local", "share").toString(), "BiliPai", "downloads.json")
        private fun requireMediaUrl(value: String): String = value.toHttpUrlOrNull()?.toString()
            ?: throw IllegalArgumentException("下载需要有效的 HTTP 媒体地址")
        private fun mediaIdentity(value: String) = value.toHttpUrlOrNull()?.encodedPath.orEmpty()
        private fun clearAssetFiles(output: Path) {
            val parent = output.toAbsolutePath().normalize().parent
            require(Files.isRegularFile(parent.resolve(".bilipai-download"))) { "下载轨道不在任务目录" }
            Files.list(parent).use { paths -> paths.filter { it.fileName.toString() == output.fileName.toString() ||
                it.fileName.toString() == output.fileName.toString() + ".part" || it.fileName.toString().startsWith(output.fileName.toString() + ".part.chunk")
            }.forEach { Files.deleteIfExists(it) } }
        }
        internal fun safeOutputName(title: String): String = title.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_")
            .trim(' ', '.').take(100).ifBlank { "video" }.let { name ->
                if (name.substringBefore('.').uppercase() in setOf("CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9")) "_$name" else name
            }
    }
}
