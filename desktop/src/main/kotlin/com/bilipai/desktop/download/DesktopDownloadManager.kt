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
    private val sourceResolver: (suspend (DownloadTask) -> PlaybackSource?)? = null,
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

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val serializer = ListSerializer(DownloadTask.serializer())
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()
    private var lastPersistNanos = 0L
    private var closed = false
    private val mutableTasks = MutableStateFlow(restore())
    val tasks: StateFlow<List<DownloadTask>> = mutableTasks.asStateFlow()

    init { synchronized(lock) { scheduleLocked() } }

    fun enqueue(source: PlaybackSource, destination: Path = destinationFor(), metadata: DownloadMetadata = DownloadMetadata(), stillOwned: () -> Boolean = { true }): String {
        publication.admit(source, stillOwned) { Unit }
        val task = prepareDownloadTask(source, destination, metadata)
        ensureOwnedDirectory(task) // filesystem work is outside the Store monitor
        val id = publication.admit(source, stillOwned) { enqueueAdmitted(task, source) }
        synchronized(lock) { persistLocked(force = true); scheduleLocked() }
        return id
    }

    /** Original full task consumer; storage/HTTP context stays in the existing wrapper.
     * Caller/entry only guard admission. The global queue owns its accepted task afterwards. */
    internal fun enqueueOriginal(item: UpstreamDownloadTask, source: PlaybackSource, destination: Path,
        downloadAllowed: Boolean, seasonId: Long, episodeId: Long, isCourse: Boolean,
        stillOwned: () -> Boolean, entryAdmission: ((() -> Unit) -> Boolean)): Boolean {
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
        ensureOwnedDirectory(queued) // same manager filesystem, outside Store/entry
        var added = false
        publication.admit(source, stillOwned) {
            if (!entryAdmission {
                if (!stillOwned()) throw CancellationException("原下载入口已退役")
                synchronized(lock) {
                    check(!closed) { "下载队列已关闭" }
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
        check(!closed) { "下载队列已关闭" }
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
        updateLocked(id) { it.copy(item = it.item.copy(status = DownloadStatus.PAUSED, errorMessage = null)) }
        jobs[id]?.cancel()
        persistLocked(force = true)
        scheduleLocked()
    }
    fun cancel(id: String) = pause(id)

    fun resume(id: String, refreshedSource: PlaybackSource? = null) {
        if (refreshedSource != null) publication.admit(refreshedSource, { true }) { resumeAdmitted(id, refreshedSource) }
        else resumeAdmitted(id, null)
        synchronized(lock) { persistLocked(force = true); scheduleLocked() }
    }
    private fun resumeAdmitted(id: String, refreshedSource: PlaybackSource?) = synchronized(lock) {
        check(!closed) { "下载队列已关闭" }
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

    fun remove(id: String, deleteFiles: Boolean = false) {
        val removed = synchronized(lock) {
            val task = mutableTasks.value.firstOrNull { it.id == id } ?: return
            val job = jobs[id]
            job?.cancel()
            mutableTasks.value = mutableTasks.value.filterNot { it.id == id }
            persistLocked(force = true)
            task to job
        }
        scope.launch {
            removed.second?.join()
            if (deleteFiles) deleteOwnedDirectory(removed.first)
            synchronized(lock) { scheduleLocked() }
        }
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

    private fun scheduleLocked() {
        if (closed) return
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
            jobs[id] = job
            job.start()
        }
        persistLocked(force = true)
    }

    private suspend fun runTask(id: String) {
        try {
            var task = synchronized(lock) { mutableTasks.value.firstOrNull { it.id == id } } ?: return
            if (task.status != DownloadStatus.PENDING) return
            val pendingJob = currentCoroutineContext()[Job]
            val owned = { pendingJob?.isActive == true && synchronized(lock) { !closed && jobs[id] === pendingJob &&
                mutableTasks.value.any { it.id == id && it.status in setOf(DownloadStatus.PENDING, DownloadStatus.DOWNLOADING) } } }
            if (publication.requiresAccountReceipt && task.authorizationReceipt == null) {
                val refreshed = sourceResolver?.invoke(task) ?: throw CancellationException("Saved download requires fresh owned authorization")
                currentCoroutineContext().ensureActive()
                publication.admit(refreshed, owned) {
                    synchronized(lock) { updateLocked(id) { it.copy(item = it.item.copy(videoUrl = requireMediaUrl(refreshed.videoUrl),
                        audioUrl = refreshed.audioUrl?.let(::requireMediaUrl).orEmpty()),
                        referer = refreshed.referer, userAgent = refreshed.userAgent, authorizationReceipt = refreshed.authorizationReceipt,
                        cookieHeader = refreshed.cookieHeader, streamHeaders = com.bilipai.desktop.player.copyPlaybackStreamHeaders(refreshed.streamHeaders),
                        progressiveSegments = refreshed.progressiveSegments.map { part -> DownloadProgressiveSegment(requireMediaUrl(part.url), part.durationSeconds) }) } }
                }
                synchronized(lock) { persistLocked(force = true) }
                task = synchronized(lock) { mutableTasks.value.first { it.id == id } }
            }
            val brandSource = task.playbackSource()
            val brandCreatedAt = task.item.createdAt
            val brandOrigin = pendingJob?.let { worker -> com.bilipai.desktop.ui.DesktopBrandSuccessOrigin(worker,
                { synchronized(lock) { !closed && mutableTasks.value.any {
                    it.id == id && it.item.createdAt == brandCreatedAt && it.status == DownloadStatus.COMPLETED
                } } && publication.isCurrent(brandSource) },
                { action ->
                    try {
                        publication.admit(brandSource, { synchronized(lock) { !closed && mutableTasks.value.any {
                            it.id == id && it.item.createdAt == brandCreatedAt && it.status == DownloadStatus.COMPLETED
                        } } }) {
                            synchronized(lock) {
                                if (!closed && mutableTasks.value.any { it.id == id && it.item.createdAt == brandCreatedAt && it.status == DownloadStatus.COMPLETED }) action()
                            }
                        }
                        true
                    } catch (_: CancellationException) { false }
                }) }
            publication.admit(task.playbackSource(), owned) { Unit }
            currentCoroutineContext().ensureActive()
            val directory = ensureOwnedDirectory(task)
            val caller = currentCoroutineContext()
            synchronized(lock) {
                caller.ensureActive()
                if (closed || mutableTasks.value.none { it.id == id && it.status == DownloadStatus.PENDING })
                    throw CancellationException("下载任务已暂停")
                update(id, true) { it.copy(item = it.item.copy(status = DownloadStatus.DOWNLOADING, errorMessage = null)) }
            }
            downloadOptionalAssets(id, directory)
            val video = directory.resolve("video.m4s")
            val audio = directory.resolve("audio.m4s")
            val segmentFiles = task.progressiveSegments.indices.map { directory.resolve("segment-${(it + 1).toString().padStart(4, '0')}.media") }
            if (segmentFiles.isNotEmpty()) {
                for ((index, file) in segmentFiles.withIndex()) {
                    val active = synchronized(lock) { mutableTasks.value.firstOrNull { it.id == id } } ?: throw CancellationException()
                    downloadAsset(id, active.progressiveSegments[index].url, file,
                        if (task.item.isAudioOnly) DownloadAssetKind.AUDIO else DownloadAssetKind.VIDEO, index)
                }
            } else if (!task.item.isAudioOnly) downloadAsset(id, task.item.videoUrl, video, DownloadAssetKind.VIDEO)
            val current = synchronized(lock) { mutableTasks.value.firstOrNull { it.id == id } } ?: throw CancellationException()
            if (segmentFiles.isEmpty() && (current.item.audioUrl.isNotBlank() || task.item.isAudioOnly))
                downloadAsset(id, current.item.audioUrl.ifBlank { current.item.videoUrl }, audio, DownloadAssetKind.AUDIO)
            currentCoroutineContext().ensureActive()
            update(id, true) { it.copy(item = it.item.copy(status = DownloadStatus.MERGING, progress = 0.95f)) }
            val output = directory.resolve("${safeOutputName(task.title)}.${if (task.item.isAudioOnly) "m4a" else "mp4"}")
            if (segmentFiles.isNotEmpty()) muxer.muxSegments(segmentFiles, output, task.item.isAudioOnly)
            else muxer.mux(video.takeUnless { task.item.isAudioOnly }, audio.takeIf { current.item.audioUrl.isNotBlank() || task.item.isAudioOnly }, output)
            currentCoroutineContext().ensureActive()
            val outputSize = Files.size(output)
            Files.deleteIfExists(video)
            Files.deleteIfExists(audio)
            segmentFiles.forEach { Files.deleteIfExists(it) }
            // COMPLETED is observable immediately; finish owned temporary track cleanup first.
            update(id, true) { it.copy(item = it.item.copy(status = DownloadStatus.COMPLETED, progress = 1f,
                filePath = output.toString(), fileSize = outputSize, errorMessage = null)) }
            // Keep the completed task even if decoration fails or its account retires.
            if (brandOrigin != null) runCatching {
                brandEvents?.downloadCompleted(brandOrigin, id, brandCreatedAt, task.title)
            }
        } catch (cancelled: CancellationException) {
            update(id, true) { task -> if (task.status in setOf(DownloadStatus.PAUSED, DownloadStatus.QUEUED)) task
                else task.copy(item = task.item.copy(status = DownloadStatus.PAUSED)) }
        } catch (error: Exception) {
            update(id, true) { it.copy(item = it.item.copy(status = DownloadStatus.FAILED,
                errorMessage = error.message?.take(2000) ?: "下载失败")) }
        } finally {
            synchronized(lock) { jobs.remove(id); scheduleLocked() }
        }
    }

    private suspend fun downloadOptionalAssets(id: String, directory: Path) {
        val task = synchronized(lock) { mutableTasks.value.firstOrNull { it.id == id } } ?: throw CancellationException()
        if (task.item.cover.isNotBlank() && task.item.assets.none { it.kind == DownloadAssetKind.COVER && it.status == DownloadAssetStatus.SKIPPED }) {
            val cover = directory.resolve("cover.jpg")
            try {
                downloadAsset(id, requireMediaUrl(task.item.cover), cover, DownloadAssetKind.COVER)
                update(id, true) { it.copy(item = it.item.copy(localCoverPath = cover.toString())) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                update(id, true) { it.copy(item = it.item.withAssetState(DownloadAssetState(
                    DownloadAssetKind.COVER, DownloadAssetStatus.FAILED, errorMessage = error.message))) }
            }
        } else update(id, true) { it.copy(item = it.item.withAssetState(DownloadAssetState(DownloadAssetKind.COVER, DownloadAssetStatus.SKIPPED))) }
        if (task.item.options.includeDanmaku && task.item.cid > 0 && danmakuDownloader != null) {
            try {
                val (segments, manifest) = danmakuDownloader.invoke(task, directory) { state ->
                    update(id, true) { it.copy(item = it.item.withAssetState(state)) }
                }
                update(id, true) { it.copy(item = it.item.copy(localDanmakuSegmentPaths = segments, localDanmakuMetadataPath = manifest)) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                update(id, true) { it.copy(item = it.item.withAssetState(DownloadAssetState(
                    DownloadAssetKind.DANMAKU, DownloadAssetStatus.FAILED, errorMessage = error.message))) }
            }
        } else update(id, true) { it.copy(item = it.item.withAssetState(DownloadAssetState(DownloadAssetKind.DANMAKU, DownloadAssetStatus.SKIPPED))) }
    }

    private suspend fun downloadAsset(id: String, url: String, output: Path, kind: DownloadAssetKind, progressiveIndex: Int? = null) {
        val context = currentCoroutineContext()
        val task = synchronized(lock) { mutableTasks.value.firstOrNull { it.id == id } } ?: throw CancellationException()
        val source = task.playbackSource()
        val callerJob = context[Job]
        val owned = { callerJob?.isActive == true && synchronized(lock) { !closed && jobs[id] === callerJob &&
            mutableTasks.value.any { it.id == id && it.status == DownloadStatus.DOWNLOADING && it.authorizationReceipt == task.authorizationReceipt } } }
        publication.admit(source, owned) { Unit }
        val headers = com.bilipai.desktop.cast.desktopCastStreamHeaders(source)
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
        suspend fun download(activeUrl: String) = ResumableAssetDownloader(calls()).download(HttpDownloadAssetRequest(activeUrl, output.toFile(), headers),
            ensureActive = { context.ensureActive() }, onProgress = { bytes, total ->
                update(id, false) { wrapper ->
                    progress(wrapper, bytes, total, false, 0)
                }
            })
        val result = try { download(url)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) {
            if (sourceResolver == null || kind !in setOf(DownloadAssetKind.VIDEO, DownloadAssetKind.AUDIO) ||
                !shouldRefreshDownloadUrlAfterFailure(error)) throw error
            context.ensureActive()
            // A retired choice is cancellation, never a request under the newly selected account.
            publication.admit(activeSource, owned) { Unit }
            val refreshed = sourceResolver.invoke(task) ?: throw error
            context.ensureActive()
            publication.admit(refreshed, owned) { Unit }
            if (progressiveIndex != null && refreshed.progressiveSegments.map { mediaIdentity(it.url) } != task.progressiveSegments.map { mediaIdentity(it.url) })
                throw IOException("原媒体分段已改变，请移除此任务后重新加入下载", error)
            val nextUrl = if (progressiveIndex != null) refreshed.progressiveSegments[progressiveIndex].url
                else if (kind == DownloadAssetKind.AUDIO) refreshed.audioUrl ?: refreshed.videoUrl else refreshed.videoUrl
            if (nextUrl == url) throw error
            if (mediaIdentity(nextUrl) != mediaIdentity(url)) clearAssetFiles(output)
            publication.admit(refreshed, owned) {
                synchronized(lock) { updateLocked(id) { wrapper -> wrapper.copy(item = wrapper.item.copy(videoUrl = refreshed.videoUrl,
                    audioUrl = refreshed.audioUrl.orEmpty()), authorizationReceipt = refreshed.authorizationReceipt,
                    cookieHeader = refreshed.cookieHeader, streamHeaders = com.bilipai.desktop.player.copyPlaybackStreamHeaders(refreshed.streamHeaders),
                    progressiveSegments = wrapper.progressiveSegments.zip(refreshed.progressiveSegments).map { (old, part) -> old.copy(url = requireMediaUrl(part.url), durationSeconds = part.durationSeconds) }) } }
            }
            synchronized(lock) { persistLocked(force = true) }
            activeSource = refreshed
            download(requireMediaUrl(nextUrl))
        }
        update(id, true) { wrapper -> progress(wrapper, result.downloadedBytes, result.totalBytes, true, result.segmentCount) }
    }

    private fun update(id: String, force: Boolean, change: (DownloadTask) -> DownloadTask) = synchronized(lock) {
        updateLocked(id, change)
        persistLocked(force)
    }

    private fun updateLocked(id: String, change: (DownloadTask) -> DownloadTask) {
        mutableTasks.value = mutableTasks.value.map { if (it.id == id) change(it).let { next -> next.copy(item = sanitizeDownloadTask(next.item)) } else it }
    }

    private fun persistLocked(force: Boolean) {
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

    private fun restore(): List<DownloadTask> = runCatching {
        if (!Files.isRegularFile(stateFile)) return@runCatching emptyList()
        json.decodeFromString(serializer, Files.readString(stateFile)).map {
            it.copy(item = normalizeRestoredDownloadTask(it.item))
        }.distinctBy { it.id }
    }.getOrDefault(emptyList())

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
        val marker = directory.resolve(".bilipai-download")
        require(Files.isRegularFile(marker) && Files.readString(marker) == task.id) { "无法确认下载目录归属" }
        Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
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
        private fun defaultSourceResolver(repository: DesktopRepository): suspend (DownloadTask) -> PlaybackSource? = { task ->
            val source = if (task.episodeId > 0) {
                val media = DesktopMediaRepository(repository)
                val season = media.bangumiSeason(seasonId = task.seasonId, episodeId = task.episodeId, isCourse = task.isCourse)
                media.bangumiPlayback(season, season.episodes.first { it.id == task.episodeId }, task.item.quality.takeIf { it > 0 } ?: 80)
            } else if (task.item.bvid.startsWith("BV") && task.item.cid > 0) {
                val details = repository.videoDetails(task.item.bvid)
                val index = details.pages.indexOfFirst { it.cid == task.item.cid }
                if (index < 0) throw IOException("此视频分 P 已不存在")
                repository.playback(details, index, task.item.quality.takeIf { it > 0 } ?: 80)
            } else null
            source?.let { PlaybackSource(it.videoUrl, it.audioUrl, it.referer, cookieHeader = it.cookieHeader, title = it.title,
                progressiveSegments = it.progressiveSegments, authorizationReceipt = it.authorizationReceipt) }
        }
        private fun defaultDanmakuDownloader(repository: DesktopRepository): suspend (DownloadTask, Path, (DownloadAssetState) -> Unit) -> Pair<List<String>, String?> = { task, directory, update ->
            repository.ensureSession()
            DownloadDanmakuTransport.configure(repository)
            DownloadDanmakuTransport.resetMetadata(task.item.cid)
            val result = DownloadDanmakuAssetService.download(task.item, directory.toFile(), update)
            if (result.metadataPath != null) {
                val manifest = Json { ignoreUnknownKeys = true }.decodeFromString(LocalDanmakuManifest.serializer(), Files.readString(Path.of(result.metadataPath)))
                val expected = com.android.purebilibili.data.repository.resolveDanmakuSegmentCount(
                    task.item.duration.coerceAtLeast(0) * 1000L,
                    if (task.item.aid > 0) DownloadDanmakuTransport.metadataSegmentCount(task.item.cid) else null)
                if (manifest.standardSegmentCount != expected) throw IOException("弹幕标准分段未全部下载（${manifest.standardSegmentCount}/$expected），媒体文件继续下载")
            }
            result.segmentPaths to result.metadataPath
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
