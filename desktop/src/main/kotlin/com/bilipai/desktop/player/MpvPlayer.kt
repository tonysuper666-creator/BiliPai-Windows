package com.bilipai.desktop.player

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.StringArray
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.awt.BorderLayout
import java.awt.Canvas
import java.awt.Color
import java.awt.Dimension
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JPanel

/**
 * Windows native player. SwingPanel embeds [surface]; mpv creates a child HWND
 * inside its Canvas and renders with Direct3D 11. No Android runtime or browser
 * participates in video/audio decoding.
 *
 * All libmpv calls for a playback session run on one worker thread. Surface
 * removal tears down that session before AWT destroys the parent window.
 */
class MpvPlayer internal constructor(private val useNullAudioOutput: Boolean = false) : AutoCloseable {
    private var softwareTarget: MpvSoftwareTarget? = null
    internal constructor(softwareTarget: MpvSoftwareTarget, useNullAudioOutput: Boolean = false) : this(useNullAudioOutput) {
        this.softwareTarget = softwareTarget
    }
    private val mutableState = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = mutableState.asStateFlow()
    private val mutableDecoderCapabilities = MutableStateFlow<MpvDecoderCapabilities?>(null)
    internal val decoderCapabilities: StateFlow<MpvDecoderCapabilities?> = mutableDecoderCapabilities.asStateFlow()
    private val lock = Any()
    private val closed = AtomicBoolean(false)
    @Volatile private var session: Session? = null
    private var attachedWindowId: Long? = null
    private var requestedSource: PlaybackSource? = null
    private var idleCacheMaintenance: Any? = null
    private val retiringCacheSessions=mutableSetOf<Session>()
    private var requestedLoadMute: Boolean? = null
    private var sourceVersion = 0L
    private var playbackRevision = 0L
    private val nextAttemptId = AtomicLong()
    private val nextSeekId = AtomicLong()
    private var softwareDecodingRequested = false
    private var hardwareDecodeEnabled = true
    private var hardwareControlVersion = 0L
    private var subtitleControlVersion = 0L
    internal val currentSubtitleControlVersion: Long get() = synchronized(lock) { subtitleControlVersion }
    private val externalSubtitles = mutableListOf<ExternalSubtitle>()
    private var videoShaderConfiguration = PreparedVideoShaders(emptyList(), emptySet())
    private var videoShaderVersion = 0L
    private var videoShaderSourceVersion: Long? = null
    private val mutableVideoShaders = MutableStateFlow(PlayerVideoShaderState())
    val videoShaderState: StateFlow<PlayerVideoShaderState> = mutableVideoShaders.asStateFlow()
    private val mutableVideoOutput = MutableStateFlow(PlayerVideoOutputState())
    val videoOutput: StateFlow<PlayerVideoOutputState> = mutableVideoOutput.asStateFlow()

    private val canvas = object : Canvas() {
        override fun addNotify() {
            super.addNotify()
            // Windows handles are cast to uint32_t, as required by mpv --wid.
            attach(Pointer.nativeValue(Native.getComponentPointer(this)) and 0xffff_ffffL)
        }

        override fun removeNotify() {
            detach()
            super.removeNotify()
        }
    }.apply {
        background = Color.BLACK
        preferredSize = Dimension(640, 360)
        isFocusable = false
    }

    val surface: JPanel = JPanel(BorderLayout()).apply {
        background = Color.BLACK
        add(canvas, BorderLayout.CENTER)
    }

    fun load(source: PlaybackSource) = load(source, preserveSubtitles = false)

    val currentSourceVersion: Long get() = synchronized(lock) { sourceVersion }
    /** Ownership without copying or exposing any stream credentials. A closed/idle player has no owner. */
    fun ownsSourceVersion(version: Long): Boolean = synchronized(lock) { !closed.get() && requestedSource != null && sourceVersion == version }

    /** Same native-owner exclusion. The actor waits for idle-active after queued Stop before cache IO. */
    internal suspend fun <T> withIdleCacheMaintenance(checkRequest: () -> Unit, block: suspend () -> T): T {
        checkRequest();val token=Any();val completion=CompletableDeferred<Boolean>()
        val retired=synchronized(lock) {
            check(!closed.get());require(requestedSource==null && idleCacheMaintenance==null) { "请先停止播放再清理字幕、弹幕或临时文件" }
            idleCacheMaintenance=token
            val active=session
            if(active==null)completion.complete(true)
            else if(active.closing.get())completion.complete(false)
            else active.commands.offer(Action.IdleCacheBarrier(token,completion))
            retiringCacheSessions.toList()
        }
        try { check(withTimeout(3_000L) { retired.forEach {it.cacheTerminated.await()};completion.await() }) { "原生播放器尚未停止" };checkRequest();return block() }
        finally { completion.cancel();synchronized(lock) {
            if(idleCacheMaintenance===token) {
                idleCacheMaintenance=null
                if(!closed.get() && session==null)attachedWindowId?.let {startSession(it)}
            }
        } }
    }

    /** Internal casting transport only. Credentials remain in memory and must never be placed in a LAN URL or diagnostics. */
    internal fun currentSourceSnapshot(): OwnedPlaybackSourceSnapshot? = synchronized(lock) {
        if (closed.get()) null else requestedSource?.let { source ->
            OwnedPlaybackSourceSnapshot(sourceVersion, source.immutableSnapshot())
        }
    }

    /** The publication identity alone is insufficient for same-version recovery:
     * compare the complete immutable source while holding the native root lock. */
    internal fun ownsSourceSnapshot(expected: OwnedPlaybackSourceSnapshot): Boolean = synchronized(lock) {
        !closed.get() && sourceVersion == expected.sourceVersion && requestedSource == expected.source
    }

    /** Short synchronous work only. The caller enters Store -> entry admission
     * first; no suspend, network, disk, lifecycle teardown or join belongs here. */
    internal fun admitSourceSnapshot(expected: OwnedPlaybackSourceSnapshot, action: () -> Unit): Boolean = synchronized(lock) {
        if (!ownsSourceSnapshot(expected)) return@synchronized false
        action()
        true
    }

    /** Wait outside Store/entry locks, after cancelling and joining the old producer jobs.
     * The existing native actor acknowledges earlier commands without issuing a media command. */
    internal suspend fun drainSourceCommands(expectedSourceVersion: Long): Boolean {
        val completion = CompletableDeferred<Boolean>()
        val queued = synchronized(lock) {
            val active = session
            if (closed.get() || requestedSource == null || sourceVersion != expectedSourceVersion ||
                active == null || active.closing.get()) false
            else active.commands.offer(Action.Barrier(expectedSourceVersion, playbackRevision, completion))
        }
        if (!queued) return false
        return try { kotlinx.coroutines.withTimeoutOrNull(3_000L) { completion.await() } ?: false }
        finally { completion.cancel() }
    }

    /** Root calls inside its Store/entry admission after draining the previous owner.
     * Only publication changes: the already loaded source, clock, tracks and surface stay intact.
     * The expected publication identity prevents a second stale handoff from replacing a new owner. */
    internal fun adoptPublication(expectedSourceVersion: Long, expectedSource: PlaybackSource,
        replacement: PlaybackSource): Boolean = synchronized(lock) {
        val current = requestedSource ?: return@synchronized false
        val native = state.value
        if (closed.get() || sourceVersion != expectedSourceVersion || session == null ||
            replacement.nativePublication == null || current.nativePublication !== expectedSource.nativePublication ||
            !native.ready || native.loading || native.ended || native.error != null || native.failure != null ||
            native.nativePaused == null || (native.videoCodec == null && native.audioCodec == null)) return@synchronized false
        fun mediaIdentity(source: PlaybackSource) = source.copy(startPositionSeconds = current.startPositionSeconds,
            startPaused = current.startPaused, nativePublication = null).immutableSnapshot()
        val identity = mediaIdentity(current)
        if (mediaIdentity(expectedSource) != identity || mediaIdentity(replacement) != identity) return@synchronized false
        requestedSource = current.copy(nativePublication = replacement.nativePublication).immutableSnapshot()
        true
    }

    /** A source ownership token lets a screen dispose only the stream that it actually loaded. */
    fun loadVersioned(source: PlaybackSource): Long = synchronized(lock) {
        load(source)
        sourceVersion
    }
    /** A real new video starts with the current Root user preference, including
     * when the previous entry left a temporary plugin native mute behind. */
    internal fun loadVersionedWithMuted(source: PlaybackSource, muted: Boolean): Long = synchronized(lock) {
        load(source, preserveSubtitles = false, startMuted = muted)
        sourceVersion
    }

    fun stopIfSourceVersion(version: Long): Boolean = synchronized(lock) {
        if (closed.get() || requestedSource == null || sourceVersion != version) false
        else { stop(); true }
    }

    private fun load(source: PlaybackSource, preserveSubtitles: Boolean, startMuted: Boolean? = null) {
        synchronized(lock) {
            check(!closed.get()) { "Player is closed" }
            check(idleCacheMaintenance==null) { "播放器缓存正在清理，请稍后重试" }
            // Validate and freeze caller-owned maps before transferring media ownership.
            val retainedSource = source.immutableSnapshot()
            val previousTransport = requestedSource?.nativeTransport
            if (!preserveSubtitles) retainedSource.nativeTransport?.lease?.requireUnattached()
            if (!preserveSubtitles || previousTransport !== retainedSource.nativeTransport)
                previousTransport?.retire(sourceVersion, requestedSource?.nativePublication)
            if (!preserveSubtitles) {
                sourceVersion++; softwareDecodingRequested = false
                if (videoShaderSourceVersion != null) installVideoShaders(PreparedVideoShaders(emptyList(), emptySet()))
            }
            if (!preserveSubtitles) { externalSubtitles.clear(); subtitleControlVersion++ }
            requestedSource = retainedSource
            requestedLoadMute = startMuted
            val revision = ++playbackRevision
            softwareTarget?.beginSource(sourceVersion, revision)
            mutableVideoOutput.update { it.copy(sourceVersion = sourceVersion, inputWidth = 0, inputHeight = 0, displayWidth = 0, displayHeight = 0, viewport = null, gamma = null, dolbyVisionProfile = null) }
            mutableVideoShaders.update { it.copy(active = false, executedPasses = emptyList()) }
            mutableState.update {
                it.copy(loading = true, paused = source.startPaused, positionSeconds = source.startPositionSeconds, durationSeconds = 0.0,
                    firstVideoFrameReady = false, pausedForCache = false, bufferedForwardSeconds = null, nativePaused = null, videoBitrateBps = null, audioBitrateBps = null,
                    sourceTitle = source.title, videoWidth = 0, videoHeight = 0,
                    ended = false, error = null, failure = null, softwareDecodingRequested = softwareDecodingRequested,
                    hardwareDecoder = null, seekCompletedId = 0, seekCompletedPositionSeconds = null, operationError = null, videoCodec = null, audioCodec = null, avSyncSeconds = null)
                    .copy(tracks = emptyList(), subtitleText = null, secondarySubtitleText = null)
            }
            val active = session
            if (active != null) active.commands.offer(Action.Load(retainedSource, sourceVersion, softwareDecodingRequested, revision, startMuted))
            else attachedWindowId?.let(::startSession)
        }
    }

    fun setPaused(paused: Boolean) {
        mutableState.update { it.copy(paused = paused, nativePaused = null) }
        send(Action.Property("pause", if (paused) "yes" else "no"))
    }
    /** Atomic ownership check prevents an obsolete recovery from pausing a replacement stream. */
    internal fun pauseIfSourceVersion(expectedSourceVersion: Long, expectedFailureAttemptId: Long? = null): Boolean = synchronized(lock) {
        if (closed.get() || requestedSource == null || sourceVersion != expectedSourceVersion ||
            (expectedFailureAttemptId != null && state.value.failure?.attemptId != expectedFailureAttemptId)) return@synchronized false
        setPaused(true)
        true
    }
    fun togglePause() {
        if (state.value.ended) replay() else setPaused(!state.value.paused)
    }
    /** keep-open=no unloads a finished stream, so restart the retained source instead of seeking an idle player. */
    fun replay() {
        synchronized(lock) {
            requestedSource?.let { load(it.copy(startPositionSeconds = 0.0, startPaused = false), preserveSubtitles = true) }
        }
    }
    /** Re-admit a retained stream for replay without replacing another owner/source. */
    internal fun replayAuthorized(expectedSourceVersion: Long, replacement: PlaybackSource): Boolean = synchronized(lock) {
        if (closed.get() || sourceVersion != expectedSourceVersion || requestedSource == null) return@synchronized false
        load(replacement.copy(startPositionSeconds = 0.0, startPaused = false), preserveSubtitles = true)
        true
    }
    /** Reloads the same item for CDN/codec recovery without transferring surface ownership or losing subtitles. */
    fun recoverSource(
        expectedSourceVersion: Long,
        replacement: PlaybackSource? = null,
        positionSeconds: Double = state.value.positionSeconds,
        paused: Boolean = state.value.paused,
        forceSoftwareDecoding: Boolean = false,
        expectedFailureAttemptId: Long? = null,
    ): Boolean = synchronized(lock) {
        if (closed.get() || sourceVersion != expectedSourceVersion || requestedSource == null) return@synchronized false
        if (expectedFailureAttemptId != null && state.value.failure?.attemptId != expectedFailureAttemptId) return@synchronized false
        if (!positionSeconds.isFinite()) return@synchronized false
        val retained = (replacement ?: requireNotNull(requestedSource)).immutableSnapshot()
        if (forceSoftwareDecoding) softwareDecodingRequested = true
        load(retained.copy(startPositionSeconds = positionSeconds.coerceAtLeast(0.0), startPaused = paused), preserveSubtitles = true)
        true
    }
    fun seekTo(seconds: Double) { seekToTracked(seconds) }
    /** Returns an operation ID; success is published only after native playback-restart confirms its position. */
    fun seekToTracked(seconds: Double): Long? = seekTracked(seconds, relative = false)
    /** Original source-owned controls retain this exact command ID until native
     * playback-restart confirms it. Queue admission is not seek completion. */
    internal fun seekToTrackedIfSourceVersion(expectedSourceVersion: Long, seconds: Double): Long? = synchronized(lock) {
        val source = requestedSource ?: return@synchronized null
        val active = session ?: return@synchronized null
        if (!seconds.isFinite() || closed.get() || active.closing.get() || sourceVersion != expectedSourceVersion ||
            (state.value.videoCodec == null && state.value.audioCodec == null) ||
            (source.nativePublication == null && (source.authorizationReceipt != null || source.primaryAccountEpoch != null))) return@synchronized null
        val id = nextSeekId.incrementAndGet()
        active.commands.offer(Action.Seek(id, sourceVersion, playbackRevision, seconds, false, source))
        id
    }
    fun seekBy(seconds: Double) { seekTracked(seconds, relative = true) }
    private fun seekTracked(seconds: Double, relative: Boolean): Long? = synchronized(lock) {
        val active = session ?: return@synchronized null
        if (!seconds.isFinite() || closed.get() || requestedSource == null || active.closing.get() ||
            (state.value.videoCodec == null && state.value.audioCodec == null)) return@synchronized null
        val id = nextSeekId.incrementAndGet()
        active.commands.offer(Action.Seek(id, sourceVersion, playbackRevision, seconds, relative))
        id
    }
    fun setVolume(volume: Double) {
        if (!volume.isFinite()) return
        val value = volume.coerceIn(0.0, 100.0)
        mutableState.update { it.copy(volume = value) }
        send(Action.Property("volume", value.toString()))
    }
    fun setSpeed(speed: Double) {
        if (!speed.isFinite()) return
        val value = speed.coerceIn(0.1, 8.0)
        mutableState.update { it.copy(speed = value) }
        send(Action.Property("speed", value.toString()))
    }
    fun setMuted(muted: Boolean) = synchronized(lock) {
        if (requestedLoadMute != null) requestedLoadMute = muted
        mutableState.update { it.copy(muted = muted) }
        send(Action.Property("mute", if (muted) "yes" else "no"))
    }
    /** Retained-owner restoration must not mutate a replacement native source.
     * No optimistic state write: the existing native poller provides readback. */
    internal fun setMutedIfSourceVersion(expectedSourceVersion: Long, muted: Boolean): Boolean = synchronized(lock) {
        val source = requestedSource ?: return@synchronized false
        val active = session ?: return@synchronized false
        if (closed.get() || active.closing.get() || sourceVersion != expectedSourceVersion ||
            source.nativePublication == null) return@synchronized false
        active.commands.offer(Action.OwnedMute(sourceVersion, playbackRevision, source, muted))
    }
    fun toggleMuted() = setMuted(!state.value.muted)
    fun setAudioOnly(audioOnly: Boolean) {
        mutableState.update { it.copy(audioOnly = audioOnly) }
        send(Action.Property("vid", if (audioOnly) "no" else "auto"))
    }
    /** mpv zoom/crop for the upstream profile skin's ZOOM fit mode. */
    fun setVideoPanscan(panscan: Double) {
        if (!panscan.isFinite() || closed.get()) return
        val value = panscan.coerceIn(0.0, 1.0)
        mutableState.update { it.copy(videoPanscan = value) }
        send(Action.Property("panscan", value.toString()))
    }
    internal fun setOriginalVideoViewport(expected:OwnedPlaybackSourceSnapshot,value:DesktopNativeVideoViewportTransform):Boolean = synchronized(lock) {
        if(!ownsSourceSnapshot(expected)) return@synchronized false
        val active=session ?: return@synchronized false
        if(active.closing.get()) false else active.commands.offer(Action.OwnedVideoViewport(expected.sourceVersion,playbackRevision,expected.source,value))
    }
    fun setLoop(looping: Boolean) {
        mutableState.update { it.copy(looping = looping) }
        send(Action.Property("loop-file", if (looping) "inf" else "no"))
    }
    fun applyPreferences(preferences: PlayerPreferences) {
        val normalized = preferences.normalized()
        setHardwareDecodingEnabled(normalized.hardwareDecodeEnabled)
        setVolume(normalized.volume)
        setSpeed(normalized.speed)
        setMuted(normalized.muted)
        setAudioOnly(normalized.audioOnly)
        setLoop(normalized.playbackMode == PlaybackMode.REPEAT_ONE)
    }
    /** Changing the user setting never cancels software fallback for an already failed source. */
    fun setHardwareDecodingEnabled(enabled: Boolean, expectedSourceVersion: Long? = null): Boolean = synchronized(lock) {
        if (closed.get() || (expectedSourceVersion != null &&
                (requestedSource == null || sourceVersion != expectedSourceVersion))) return@synchronized false
        hardwareDecodeEnabled = enabled
        val control = ++hardwareControlVersion
        mutableState.update { it.copy(hardwareDecodeEnabled = enabled) }
        session?.commands?.offer(Action.HardwareDecoding(control, sourceVersion, playbackRevision))
        true
    }

    /** One owned transaction binds both original tracks, their visibility, and the native selection slots. */
    internal fun installSubtitlePair(expectedSourceVersion: Long, expectedControlVersion: Long,
        primary: DesktopOnlineSubtitleAsset?, secondary: DesktopOnlineSubtitleAsset?,
        mode: com.android.purebilibili.feature.video.subtitle.SubtitleDisplayMode): Boolean {
        listOfNotNull(primary, secondary).forEach {
            require(Files.isRegularFile(it.file)) { "Subtitle asset is missing." }
            require('\u0000' !in it.nativeTitle && '\u0000' !in it.track.lan) { "Subtitle metadata contains a NUL character." }
        }
        return synchronized(lock) {
            if (closed.get() || idleCacheMaintenance!=null || requestedSource == null || sourceVersion != expectedSourceVersion ||
                subtitleControlVersion != expectedControlVersion) return@synchronized false
            val normalizedMode = com.android.purebilibili.feature.video.subtitle.normalizeSubtitleDisplayMode(
                mode, primary != null, secondary != null)
            externalSubtitles.replaceAll { it.copy(selection = null) }
            listOfNotNull(primary, secondary).forEach { asset ->
                val path = asset.file.toAbsolutePath().normalize()
                externalSubtitles.removeAll { it.path == path }
                val slot = when {
                    asset === primary && normalizedMode in setOf(
                        com.android.purebilibili.feature.video.subtitle.SubtitleDisplayMode.PRIMARY_ONLY,
                        com.android.purebilibili.feature.video.subtitle.SubtitleDisplayMode.BILINGUAL) -> 0
                    asset === secondary && normalizedMode in setOf(
                        com.android.purebilibili.feature.video.subtitle.SubtitleDisplayMode.SECONDARY_ONLY,
                        com.android.purebilibili.feature.video.subtitle.SubtitleDisplayMode.BILINGUAL) -> 1
                    else -> null
                }
                externalSubtitles.add(ExternalSubtitle(path, asset.nativeTitle, asset.track.lan, slot))
            }
            val control = ++subtitleControlVersion
            val visible = normalizedMode != com.android.purebilibili.feature.video.subtitle.SubtitleDisplayMode.OFF
            mutableState.update { it.copy(subtitlesVisible = visible) }
            session?.commands?.offer(Action.SubtitleConfiguration(control, sourceVersion, playbackRevision, visible))
            true
        }
    }

    /** Applies genuine mpv GLSL hook assets; an empty list restores the native base renderer. */
    fun setVideoShaders(files: List<Path>): Long = setVideoShaders(files, PlayerVideoShaderOptions())
    fun setVideoShaders(files: List<Path>, options: PlayerVideoShaderOptions): Long {
        val prepared = prepareVideoShaders(files).copy(options = options.frozen())
        return synchronized(lock) { installVideoShaders(prepared) }
    }
    /** IO preparation may outlive a source. A stale renderer cannot then modify its replacement. */
    fun setVideoShadersIfSourceVersion(expectedSourceVersion: Long, files: List<Path>, options: PlayerVideoShaderOptions = PlayerVideoShaderOptions()): Long? {
        val prepared = prepareVideoShaders(files).copy(options = options.frozen())
        return synchronized(lock) {
            if (closed.get() || requestedSource == null || sourceVersion != expectedSourceVersion) null else installVideoShaders(prepared, expectedSourceVersion)
        }
    }
    fun clearVideoShadersIfConfigurationVersion(expectedConfigurationVersion: Long): Boolean = synchronized(lock) {
        if (closed.get() || videoShaderVersion != expectedConfigurationVersion) false else {
            installVideoShaders(PreparedVideoShaders(emptyList(), emptySet()))
            true
        }
    }
    private fun installVideoShaders(prepared: PreparedVideoShaders, owner: Long? = null): Long {
        check(!closed.get()) { "Player is closed" }
        require(prepared.descriptions.containsAll(prepared.options.requiredPassDescriptions)) { "Required video shader pass is missing." }
        // Requested options are not native output observations. Rapid clear/restore may
        // coalesce before rendering and keep the same FBO, so no new format log is emitted.
        // Retain the last actual GPU format until a real native log or session reset updates it.
        videoShaderConfiguration = prepared
        videoShaderSourceVersion = owner
        val version = ++videoShaderVersion
        mutableVideoShaders.value = PlayerVideoShaderState(version, prepared.paths, requestedIntermediateFormat = prepared.options.intermediateFormat)
        session?.commands?.offer(Action.VideoShaders(version, prepared))
        return version
    }

    fun setSubtitlesVisible(visible: Boolean) {
        synchronized(lock) { subtitleControlVersion++ }
        mutableState.update { it.copy(subtitlesVisible = visible) }
        send(Action.Property("sub-visibility", if (visible) "yes" else "no"))
        send(Action.Property("secondary-sub-visibility", if (visible) "yes" else "no"))
    }
    fun selectAudioTrack(id: Int?) {
        require(id == null || id > 0) { "Invalid audio track ID." }
        send(Action.Property("aid", id?.toString() ?: "no"))
    }
    fun selectSubtitleTrack(id: Int?) {
        require(id == null || id > 0) { "Invalid subtitle track ID." }
        retainSubtitleSelection(id, 0)
        send(Action.Property("sid", id?.toString() ?: "no"))
    }
    fun selectSecondarySubtitleTrack(id: Int?) {
        require(id == null || id > 0) { "Invalid secondary subtitle track ID." }
        retainSubtitleSelection(id, 1)
        send(Action.Property("secondary-sid", id?.toString() ?: "no"))
    }
    /** Local SRT/ASS/VTT documents, including converted Bilibili subtitle JSON. */
    fun addSubtitle(file: Path, title: String = file.fileName.toString(), language: String = "", select: Boolean = true) {
        require(Files.isRegularFile(file)) { "Subtitle file does not exist." }
        require(listOf(title, language).none { '\u0000' in it }) { "Invalid subtitle metadata." }
        synchronized(lock) {
            check(!closed.get() && idleCacheMaintenance==null && requestedSource != null) { "No video selected or cache maintenance active." }
            subtitleControlVersion++
            val path = file.toAbsolutePath().normalize()
            if (select) externalSubtitles.replaceAll { if (it.selection == 0) it.copy(selection = null) else it }
            externalSubtitles.removeAll { it.path == path }
            externalSubtitles += ExternalSubtitle(path, title, language, if (select) 0 else null)
            session?.commands?.offer(Action.Subtitles(sourceVersion))
        }
    }

    private fun retainSubtitleSelection(id: Int?, slot: Int) = synchronized(lock) {
        subtitleControlVersion++
        externalSubtitles.replaceAll {
            when {
                id != null && it.nativeId == id -> it.copy(selection = slot)
                it.selection == slot -> it.copy(selection = null)
                else -> it
            }
        }
    }
    /** Captures the decoded frame through libmpv, without screen occlusion or desktop chrome. */
    suspend fun captureScreenshot(destination: Path, includeSubtitles: Boolean = true): Path {
        check(!closed.get()) { "Player is closed" }
        val completion = CompletableDeferred<Path>()
        synchronized(lock) {
            val current = session
            check(current != null && state.value.ready && !state.value.ended && !state.value.audioOnly && state.value.videoCodec != null) {
                "播放器未显示视频，无法截图。"
            }
            current.commands.offer(Action.Screenshot(destination.toAbsolutePath().normalize(), includeSubtitles, completion))
        }
        try { return withTimeout(15_000) { completion.await() } }
        finally { if (!completion.isCompleted) completion.cancel() }
    }
    fun stop() {
        synchronized(lock) {
            requestedSource?.nativeTransport?.retire(sourceVersion, requestedSource?.nativePublication)
            requestedSource = null
            softwareTarget?.clear()
            softwareDecodingRequested = false
            externalSubtitles.clear()
            sourceVersion++
            if (videoShaderSourceVersion != null) installVideoShaders(PreparedVideoShaders(emptyList(), emptySet()))
            playbackRevision++
            mutableVideoOutput.update { it.copy(sourceVersion = sourceVersion, inputWidth = 0, inputHeight = 0, displayWidth = 0, displayHeight = 0, viewport = null, gamma = null, dolbyVisionProfile = null) }
            send(Action.Command(listOf("stop")))
            mutableState.update {
                it.copy(loading = false, paused = false, positionSeconds = 0.0, durationSeconds = 0.0,
                    firstVideoFrameReady = false, pausedForCache = false, bufferedForwardSeconds = null, nativePaused = null, videoBitrateBps = null, audioBitrateBps = null,
                    sourceTitle = "BiliPai", videoWidth = 0, videoHeight = 0,
                    ended = false, error = null, failure = null, softwareDecodingRequested = softwareDecodingRequested,
                    hardwareDecoder = null, seekCompletedId = 0, seekCompletedPositionSeconds = null, operationError = null, videoCodec = null, audioCodec = null, avSyncSeconds = null)
                    .copy(tracks = emptyList(), subtitleText = null, secondarySubtitleText = null)
            }
        }
    }

    private fun send(action: Action) {
        if (!closed.get()) session?.commands?.offer(action)
    }

    /** Headless Compose texture transport; owns the SAME normal session/event loop and mpv instance. */
    internal fun startSoftwareTransport() = synchronized(lock) {
        check(softwareTarget != null) { "A software render target is required" }
        check(idleCacheMaintenance==null) { "Cache maintenance is active" }
        check(!closed.get()) { "Player is closed" }
        if (session == null) startSession(0L)
    }

    private fun attach(windowId: Long) {
        synchronized(lock) {
            if (closed.get() || session != null || softwareTarget != null) return
            if (windowId == 0L) {
                mutableState.update { it.copy(error = "Cannot attach native player to the Windows video surface.") }
                return
            }
            attachedWindowId = windowId
            startSession(windowId)
        }
    }

    private fun startSession(windowId: Long) {
        if(idleCacheMaintenance!=null)return
        mutableDecoderCapabilities.value = null
        mutableVideoOutput.value = PlayerVideoOutputState(sourceVersion = sourceVersion)
        val next = Session(windowId, requestedSource, sourceVersion, playbackRevision, requestedLoadMute)
        session = next
        next.thread.start()
    }

    private fun detach() {
        val previous = synchronized(lock) {
            attachedWindowId = null
            val snapshot = state.value
            if (snapshot.ready && !snapshot.loading && !snapshot.ended && snapshot.error == null) {
                requestedSource = requestedSource?.copy(startPositionSeconds = snapshot.positionSeconds.coerceAtLeast(0.0),
                    startPaused = snapshot.paused)
            }
            mutableDecoderCapabilities.value = null
            session.also { session = null; it?.closing?.set(true);if(it!=null)retiringCacheSessions.add(it) }
        }
        // Shutdown must finish while the HWND is still alive. mpv does not
        // call the AWT event thread, so this wait cannot create an EDT cycle.
        previous?.thread?.join(5_000)
        mutableVideoShaders.update { it.copy(active = false, appliedFiles = emptyList(), executedPasses = emptyList(), actualIntermediateFormat = null) }
        mutableVideoOutput.value = PlayerVideoOutputState(sourceVersion = currentSourceVersion)
        mutableState.update { it.copy(ready = false, loading = false, activeVideoPanscan = null, nativePaused = null) }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            softwareTarget?.close()
            synchronized(lock) { requestedSource?.nativeTransport?.retire(sourceVersion, requestedSource?.nativePublication); requestedSource = null; externalSubtitles.clear() }
            detach()
        }
    }

    internal suspend fun captureScreenshotForSource(expected:OwnedPlaybackSourceSnapshot,destination:Path,includeSubtitles:Boolean):Path {
        val completion=CompletableDeferred<Path>()
        val queued=synchronized(lock) {
            val active=session
            if(!ownsSourceSnapshot(expected) || active==null || active.closing.get() || !state.value.ready ||
                state.value.ended || state.value.audioOnly || state.value.videoCodec==null) false
            else active.commands.offer(Action.Screenshot(destination.toAbsolutePath().normalize(),includeSubtitles,completion,expected,playbackRevision))
        }
        if(!queued) throw kotlinx.coroutines.CancellationException("Screenshot source retired/unavailable")
        try { return withTimeout(15_000L){completion.await()} } finally {if(!completion.isCompleted)completion.cancel()}
    }

    private sealed interface Action {
        data class Load(val source: PlaybackSource, val version: Long, val softwareDecoding: Boolean, val revision: Long, val startMuted: Boolean? = null) : Action
        data class Subtitles(val version: Long) : Action
        data class HardwareDecoding(val controlVersion: Long, val version: Long, val revision: Long) : Action
        data class SubtitleConfiguration(val controlVersion: Long, val version: Long, val revision: Long, val visible: Boolean) : Action
        data class VideoShaders(val version: Long, val configuration: PreparedVideoShaders) : Action
        data class Property(val name: String, val value: String) : Action
        data class OwnedVideoViewport(val version:Long,val revision:Long,val source:PlaybackSource,val value:DesktopNativeVideoViewportTransform):Action
        data class OwnedMute(val version: Long, val revision: Long, val source: PlaybackSource, val muted: Boolean) : Action
        data class Command(val args: List<String>) : Action
        data class Seek(val id: Long, val sourceVersion: Long, val revision: Long, val seconds: Double, val relative: Boolean,
            val admissionSource: PlaybackSource? = null) : Action
        data class Screenshot(val destination: Path, val includeSubtitles: Boolean, val completion: CompletableDeferred<Path>, val owned:OwnedPlaybackSourceSnapshot?=null, val revision:Long?=null) : Action
        data class IdleCacheBarrier(val token: Any, val completion: CompletableDeferred<Boolean>) : Action
        data class Barrier(val version: Long, val revision: Long, val completion: CompletableDeferred<Boolean>) : Action
    }

    private data class ExternalSubtitle(val path: Path, val title: String, val language: String, val selection: Int?, val nativeId: Int? = null)

    private inner class Session(private val windowId: Long, private val initialSource: PlaybackSource?, private val initialVersion: Long,
        private val initialRevision: Long, private val initialMuted: Boolean?) {
        val commands = LinkedBlockingQueue<Action>()
        val closing = AtomicBoolean(false)
        val cacheTerminated=CompletableDeferred<Unit>()
        // Worker-owned: Stop is asynchronous even after mpv_command returns. Keep the
        // barrier armed while this same event loop advances mpv's unload events.
        private var pendingIdleCacheBarrier: Action.IdleCacheBarrier? = null
        val thread = Thread(::run, "BiliPai-native-player").apply { isDaemon = true }
        private var sectionFlipHorizontal=false
        private var sectionFlipVertical=false
        private fun clearSectionViewport(native:MpvNative,handle:Pointer) {
            if(sectionFlipHorizontal) {checkResult(native,native.mpv_command(handle,StringArray(arrayOf("vf","remove","@bilipai-section-hflip"),"UTF-8")),"remove-section-hflip");sectionFlipHorizontal=false}
            if(sectionFlipVertical) {checkResult(native,native.mpv_command(handle,StringArray(arrayOf("vf","remove","@bilipai-section-vflip"),"UTF-8")),"remove-section-vflip");sectionFlipVertical=false}
            for((name,value)in listOf("video-zoom" to "0","video-pan-x" to "0","video-pan-y" to "0","keepaspect" to "yes","panscan" to state.value.videoPanscan.toString()))
                checkResult(native,native.mpv_set_property_string(handle,name,value),name)
        }
        private var activeEntry: Long? = null
        private var expectedEntry: Long? = null
        private var fileLoaded = false
        private var lastTrackPoll = 0L
        private var tracks = emptyList<PlayerTrack>()
        private var activeSourceVersion = initialVersion
        private var activeRevision = initialRevision
        private var activeAttemptId = 0L
        private val diagnostics = PlayerDiagnostics()
        private val seekTracker = PlayerSeekTracker()
        private val loadedSubtitlePaths = mutableSetOf<Path>()
        private var activeShaderVersion = -1L
        private var activeShaders = PreparedVideoShaders(emptyList(), emptySet())
        private var lastShaderPoll = 0L

        private fun run() {
            var native: MpvNative? = null
            var handle: Pointer? = null
            var fatalFailure: Throwable? = null
            var softwareRenderer: MpvSoftwareRenderer? = null
            try {
                native = MpvNative.load()
                handle = native.mpv_create() ?: error("Unable to create the native player.")
                val options = mapOf(
                    "config" to "no",
                    "load-scripts" to "no",
                    "ytdl" to "no",
                    "terminal" to "no",
                    "osc" to "no",
                    "osd-level" to "0",
                    "input-default-bindings" to "no",
                    "input-vo-keyboard" to "no",
                    "input-cursor" to "no",
                    "idle" to "yes",
                    "keep-open" to "no",
                    "wid" to windowId.toString(),
                    "vo" to "gpu",
                    "gpu-api" to "d3d11",
                    "hwdec" to synchronized(lock) { resolveMpvHardwareDecoding(hardwareDecodeEnabled, softwareDecodingRequested) },
                    "audio-client-name" to "BiliPai",
                    "network-timeout" to "20",
                    "demuxer-max-bytes" to "64MiB",
                    "volume" to state.value.volume.toString(),
                    "speed" to state.value.speed.toString(),
                    "panscan" to state.value.videoPanscan.toString(),
                    "mute" to if (state.value.muted) "yes" else "no",
                    "vid" to if (state.value.audioOnly) "no" else "auto",
                    "loop-file" to if (state.value.looping) "inf" else "no",
                    "sub-visibility" to if (state.value.subtitlesVisible) "yes" else "no",
                    "secondary-sub-visibility" to if (state.value.subtitlesVisible) "yes" else "no",
                    "screenshot-format" to "png",
                ).let { original ->
                    if (softwareTarget == null) original else original.filterKeys { it != "wid" && it != "gpu-api" } +
                        mapOf("vo" to "libmpv", "hwdec" to "no")
                } + if (useNullAudioOutput) mapOf("ao" to "null") else emptyMap()
                options.forEach { (name, value) -> checkResult(native, native.mpv_set_option_string(handle, name, value), name) }
                // Read only two exact GPU metadata messages at verbose level; all other verbose text is discarded.
                checkResult(native, native.mpv_request_log_messages(handle, "v"), "request-log-messages")
                checkResult(native, native.mpv_initialize(handle), "initialize")
                softwareTarget?.let { target ->
                    softwareRenderer = MpvSoftwareRenderer(native, handle, target).also { it.start() }
                }
                val shaders = synchronized(lock) { Action.VideoShaders(videoShaderVersion, videoShaderConfiguration) }
                perform(native, handle, shaders)
                val nativeVersion = property(native, handle, "mpv-version")
                val decoders = readMpvDecoderCapabilities(native, handle)
                synchronized(lock) {
                    if (session === this && !closing.get()) {
                        mutableDecoderCapabilities.value = decoders
                        mutableState.update { it.copy(ready = true, error = null, failure = null, nativeVersion = nativeVersion) }
                    }
                }
                initialSource?.let { perform(native, handle, Action.Load(it, initialVersion, softwareDecodingRequested, initialRevision, initialMuted)) }
                var lastPoll = 0L
                while (!closing.get()) {
                    softwareRenderer?.throwIfFailed()
                    var action = commands.poll()
                    while (action != null) {
                        if (closing.get()) {
                            if (action is Action.IdleCacheBarrier) action.completion.complete(false)
                            if (action is Action.Screenshot) action.completion.completeExceptionally(IllegalStateException("Player stopped before the screenshot was saved."))
                            break
                        }
                        perform(native, handle, action)
                        action = commands.poll()
                    }
                    if (closing.get()) break
                    val event = native.mpv_wait_event(handle, 0.05)
                    receiveEvent(native, handle, event)
                    val now = System.nanoTime()
                    if (now - lastPoll >= 200_000_000L) {
                        refreshState(native, handle)
                        lastPoll = now
                    }
                    advanceIdleCacheBarrier(native, handle)
                }
            } catch (failure: Throwable) {
                if (!closing.get()) fatalFailure = failure
            } finally {
                pendingIdleCacheBarrier?.completion?.complete(false)
                pendingIdleCacheBarrier = null
                while (true) {
                    val pending = commands.poll() ?: break
                    if(pending is Action.IdleCacheBarrier)pending.completion.complete(false)
                    if (pending is Action.Screenshot) pending.completion.completeExceptionally(IllegalStateException("Player stopped before the screenshot was saved."))
                }
                // The render context/thread MUST terminate before its core. Never call render APIs from this client worker.
                try { softwareRenderer?.close() }
                finally { if (native != null && handle != null) native.mpv_terminate_destroy(handle) }
                cacheTerminated.complete(Unit)
                synchronized(lock) {
                    retiringCacheSessions.remove(this)
                    if (session === this) {
                        session = null
                        mutableDecoderCapabilities.value = null
                        fatalFailure?.let { failure ->
                            val typed = diagnostics.failure((failure as? MpvCallException)?.nativeCode,
                                failure.message ?: "Native player failed.", sourceVersion, nextAttemptId.incrementAndGet())
                            mutableState.update { it.copy(ready = false, loading = false, error = typed.safeMessage, failure = typed) }
                        }
                    }
                }
            }
        }

        private fun advanceIdleCacheBarrier(native: MpvNative, handle: Pointer) {
            val pending = pendingIdleCacheBarrier ?: return
            if (pending.completion.isCompleted) {
                pendingIdleCacheBarrier = null
                return
            }
            fun stillOwned(): Boolean = synchronized(lock) {
                !closed.get() && session === this && !closing.get() &&
                    requestedSource == null && idleCacheMaintenance === pending.token
            }
            if (!stillOwned()) {
                pendingIdleCacheBarrier = null
                pending.completion.complete(false)
                return
            }
            // Native property/event progression belongs to this worker, outside lock.
            // A false idle-active means keep polling, never await our own event loop.
            try {
                val idle = property(native, handle, "idle-active") == "yes"
                val owned = stillOwned()
                if (!owned || idle) {
                    pendingIdleCacheBarrier = null
                    pending.completion.complete(owned && idle)
                }
            } catch (failure: Exception) {
                pendingIdleCacheBarrier = null
                pending.completion.completeExceptionally(failure)
            }
        }

        private fun perform(native: MpvNative, handle: Pointer, action: Action) {
            try {
                if (action !is Action.Load && action !is Action.Screenshot && action !is Action.Barrier && action !is Action.IdleCacheBarrier && action !is Action.OwnedMute && action !is Action.OwnedVideoViewport &&
                    !(action is Action.Seek && action.admissionSource != null)) publishState { it.copy(operationError = null) }
                when (action) {
                    is Action.Load -> {
                        val command = { synchronized(lock) {
                        if (session !== this || closing.get() || action.version != sourceVersion || action.revision != playbackRevision) return@synchronized
                        activeEntry = null
                        expectedEntry = null
                        fileLoaded = false
                        tracks = emptyList()
                        activeSourceVersion = action.version
                        activeRevision = action.revision
                        softwareTarget?.beginSource(action.version, action.revision)
                        lastShaderPoll = 0L
                        activeAttemptId = nextAttemptId.incrementAndGet()
                        seekTracker.reset()
                        diagnostics.reset(action.source)
                        checkResult(native, native.mpv_set_property_string(handle, "hwdec", synchronized(lock) { if (softwareTarget == null) resolveMpvHardwareDecoding(hardwareDecodeEnabled, action.softwareDecoding) else "no" }), "hwdec")
                        clearSectionViewport(native,handle)
                        loadedSubtitlePaths.clear()
                        lastTrackPoll = 0L
                        MpvNodes().use { nodes ->
                            // Per-file options prevent old audio tracks or cookies
                            // from leaking into the next video, even on rapid loads.
                            val args = nodes.array(listOf("loadfile", action.source.nativeLoadUrl, "replace", "-1", action.source.mpvFileOptions()))
                            checkResult(native, native.mpv_command_node(handle, args, null), "loadfile")
                        }
                        if (action.startMuted != null) requestedLoadMute?.let { muted ->
                            checkResult(native, native.mpv_set_property_string(handle, "mute", if (muted) "yes" else "no"), "mute")
                        }
                        requestedLoadMute = null
                        // loadfile synchronously installs the new playlist entry before its asynchronous events.
                        expectedEntry = property(native, handle, "playlist/0/id")?.toLongOrNull()
                        if (expectedEntry != null) action.source.nativePublication?.onLoadCommandAccepted()
                        } }
                        val admission = action.source.nativePublication
                        if (admission != null) admission.admit(command)
                        else if (action.source.authorizationReceipt == null && action.source.primaryAccountEpoch == null) command()
                        // Rejection leaves the active native media untouched.
                    }
                    is Action.HardwareDecoding -> synchronized(lock) {
                        if (session !== this || closing.get() || action.controlVersion != hardwareControlVersion ||
                            action.version != sourceVersion || action.revision != playbackRevision ||
                            action.version != activeSourceVersion || action.revision != activeRevision) return
                        checkResult(native, native.mpv_set_property_string(handle, "hwdec",
                            if (softwareTarget == null) resolveMpvHardwareDecoding(hardwareDecodeEnabled, softwareDecodingRequested) else "no"), "hwdec")
                    }
                    is Action.SubtitleConfiguration -> synchronized(lock) {
                        if (session !== this || closing.get() || action.controlVersion != subtitleControlVersion ||
                            action.version != sourceVersion || action.revision != playbackRevision ||
                            action.version != activeSourceVersion || action.revision != activeRevision) return
                        checkResult(native, native.mpv_set_property_string(handle, "sub-visibility", if(action.visible) "yes" else "no"), "sub-visibility")
                        checkResult(native, native.mpv_set_property_string(handle, "secondary-sub-visibility", if(action.visible) "yes" else "no"), "secondary-sub-visibility")
                        if (fileLoaded) restoreSubtitles(native, handle)
                    }
                    is Action.Subtitles -> if (fileLoaded && activeSourceVersion == action.version) restoreSubtitles(native, handle)
                    is Action.Seek -> {
                        fun seek() {
                            val position = if (action.relative) (property(native, handle, "time-pos")?.toDoubleOrNull() ?: state.value.positionSeconds) + action.seconds else action.seconds
                            val duration = property(native, handle, "duration")?.toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() }
                            val target = position.coerceAtLeast(0.0).let { if (duration == null) it else it.coerceAtMost(duration) }
                            checkResult(native, native.mpv_command(handle, StringArray(arrayOf("seek", target.toString(), "absolute+exact"), "UTF-8")), "seek")
                            seekTracker.submit(action.id, action.sourceVersion, target)
                        }
                        val owned = action.admissionSource
                        if (owned == null) {
                            // Existing controller commands issued before retirement retain their semantics.
                            if (!fileLoaded || action.sourceVersion != activeSourceVersion || action.revision != activeRevision ||
                                !synchronized(lock) { action.sourceVersion == sourceVersion && action.revision == playbackRevision }) return
                            seek()
                        } else {
                            val command = { synchronized(lock) {
                                if (session === this && !closing.get() && fileLoaded &&
                                    sourceVersion == action.sourceVersion && playbackRevision == action.revision &&
                                    activeSourceVersion == action.sourceVersion && activeRevision == action.revision &&
                                    requestedSource?.nativePublication === owned.nativePublication) {
                                    seek()
                                    publishState { it.copy(operationError = null) }
                                }
                            } }
                            if (owned.nativePublication != null) owned.nativePublication.admit(command)
                            else if (owned.authorizationReceipt == null && owned.primaryAccountEpoch == null) command()
                        }
                    }
                    is Action.VideoShaders -> {
                        if (!synchronized(lock) { session === this && !closing.get() && action.version == videoShaderVersion }) return
                        checkResult(native, native.mpv_request_log_messages(handle, "v"), "request-video-metadata")
                        checkResult(native, native.mpv_set_property_string(handle, "fbo-format", action.configuration.options.intermediateFormat), "fbo-format")
                        checkResult(native, native.mpv_set_property_string(handle, "glsl-shader-opts", action.configuration.options.nativeParameterValue()), "glsl-shader-opts")
                        checkResult(native, MpvVideoShaderProperties.set(native, handle, action.configuration.paths), "glsl-shaders")
                        activeShaderVersion = action.version
                        activeShaders = action.configuration
                        lastShaderPoll = 0L
                        refreshPausedVideoFrame(native, handle, action.version)
                        refreshVideoShaders(native, handle)
                    }
                    is Action.OwnedVideoViewport -> {
                        val command={synchronized(lock) {
                            if(session===this && !closing.get() && sourceVersion==action.version && playbackRevision==action.revision &&
                                requestedSource==action.source && activeSourceVersion==action.version && activeRevision==action.revision) {
                                val value=action.value
                                for((name,setting)in value.nativeProperties())checkResult(native,native.mpv_set_property_string(handle,name,setting),name)
                                if(sectionFlipHorizontal!=value.flipHorizontal) {
                                    checkResult(native,native.mpv_command(handle,StringArray(arrayOf("vf",if(value.flipHorizontal)"add"else"remove",if(value.flipHorizontal)"@bilipai-section-hflip:hflip"else"@bilipai-section-hflip"),"UTF-8")),"section-hflip")
                                    sectionFlipHorizontal=value.flipHorizontal
                                }
                                if(sectionFlipVertical!=value.flipVertical) {
                                    checkResult(native,native.mpv_command(handle,StringArray(arrayOf("vf",if(value.flipVertical)"add"else"remove",if(value.flipVertical)"@bilipai-section-vflip:vflip"else"@bilipai-section-vflip"),"UTF-8")),"section-vflip")
                                    sectionFlipVertical=value.flipVertical
                                }
                            }
                        }}
                        action.source.nativePublication?.admit(command)
                    }
                    is Action.Property -> checkResult(native, native.mpv_set_property_string(handle, action.name, action.value), action.name)
                    is Action.OwnedMute -> {
                        val command = { synchronized(lock) {
                            if (session === this && !closing.get() &&
                                sourceVersion == action.version && playbackRevision == action.revision &&
                                activeSourceVersion == action.version && activeRevision == action.revision &&
                                requestedSource?.nativePublication === action.source.nativePublication)
                                checkResult(native, native.mpv_set_property_string(handle, "mute", if (action.muted) "yes" else "no"), "mute")
                        } }
                        action.source.nativePublication?.admit(command)
                    }
                    is Action.Command -> {
                        if (action.args.first() == "stop") { activeEntry = null; expectedEntry = null; fileLoaded = false; seekTracker.reset() }
                        checkResult(native, native.mpv_command(handle, StringArray(action.args.toTypedArray(), "UTF-8")), action.args.first())
                        lastTrackPoll = 0L
                    }
                    is Action.Screenshot -> {
                        val expected=action.owned
                        val allowed=expected==null || synchronized(lock){session===this && !closing.get() && ownsSourceSnapshot(expected) &&
                            action.revision==playbackRevision && activeSourceVersion==expected.sourceVersion && activeRevision==action.revision}
                        if(!allowed) action.completion.cancel(kotlinx.coroutines.CancellationException("Screenshot source changed before capture"))
                        else saveScreenshot(native, handle, action)
                    }
                    is Action.IdleCacheBarrier -> {
                        pendingIdleCacheBarrier?.completion?.complete(false)
                        pendingIdleCacheBarrier = action
                    }
                    is Action.Barrier -> action.completion.complete(synchronized(lock) {
                        session === this && !closing.get() && sourceVersion == action.version &&
                            playbackRevision == action.revision && activeSourceVersion == action.version &&
                            activeRevision == action.revision && fileLoaded
                    })
                }
            } catch (failure: Exception) {
                if(action is Action.IdleCacheBarrier) {action.completion.completeExceptionally(failure);return}
                if (action is Action.Screenshot) {
                    action.completion.completeExceptionally(failure)
                    return
                }
                if (action is Action.VideoShaders) {
                    synchronized(lock) {
                        if (session === this && videoShaderVersion == action.version)
                            mutableVideoShaders.update { it.copy(active = false, error = diagnostics.sanitize(failure.message ?: "Video shader configuration failed.")) }
                    }
                    return
                }
                if (action is Action.Load) publishFailure((failure as? MpvCallException)?.nativeCode,
                    failure.message ?: "Playback operation failed.")
                else publishState { it.copy(operationError = diagnostics.sanitize(failure.message ?: "Playback control failed.")) }
            }
        }

        private fun refreshPausedVideoFrame(native: MpvNative, handle: Pointer, shaderVersion: Long) {
            // vo=gpu can retain its old paused render texture after a shader option changes.
            // A same-position exact seek requests a new decoded render, including when clearing hooks.
            // User seeks already cause that render and must keep their own target/completion identity.
            if (!fileLoaded || state.value.audioOnly || seekTracker.hasPendingSeek || property(native, handle, "pause") != "yes" ||
                property(native, handle, "seeking") == "yes") return
            val position = property(native, handle, "time-pos")?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it >= 0.0 } ?: return
            synchronized(lock) {
                if (session !== this || closing.get() || sourceVersion != activeSourceVersion ||
                    playbackRevision != activeRevision || videoShaderVersion != shaderVersion) return
                // This internal repaint allocates no user seek ID and invokes no SponsorBlock callback.
                checkResult(native, native.mpv_command(handle, StringArray(arrayOf("seek", position.toString(), "absolute+exact"), "UTF-8")),
                    "refresh paused video shaders")
            }
        }

        private fun restoreSubtitles(native: MpvNative, handle: Pointer) {
            val assets = synchronized(lock) {
                if (session === this && sourceVersion == activeSourceVersion && playbackRevision == activeRevision) externalSubtitles.toList() else emptyList()
            }
            if (assets.isEmpty()) return
            assets.forEach { asset ->
                if (asset.path in loadedSubtitlePaths) return@forEach
                try {
                    checkResult(native, native.mpv_command(handle, StringArray(arrayOf("sub-add", asset.path.toString(), "auto", asset.title, asset.language), "UTF-8")), "sub-add")
                    loadedSubtitlePaths.add(asset.path)
                } catch (failure: Exception) {
                    publishState { it.copy(operationError = diagnostics.sanitize(failure.message ?: "字幕加载失败。")) }
                }
            }
            val nativeTracks = readTracks(native, handle)
            synchronized(lock) {
                if (session !== this || sourceVersion != activeSourceVersion || playbackRevision != activeRevision) return
                externalSubtitles.replaceAll { asset ->
                    asset.copy(nativeId = nativeTracks.firstOrNull { it.external && it.type == "sub" && it.title == asset.title && it.language.orEmpty() == asset.language }?.id)
                }
                for (slot in 0..1) {
                    val selected = externalSubtitles.firstOrNull { it.selection == slot }
                    if (selected != null && selected.nativeId == null) continue
                    val propertyName = if (slot == 0) "sid" else "secondary-sid"
                    val result = native.mpv_set_property_string(handle, propertyName, selected?.nativeId?.toString() ?: "no")
                    if (result < 0) mutableState.update { it.copy(operationError = "Native player $propertyName: ${native.mpv_error_string(result)}") }
                }
            }
            lastTrackPoll = 0L
        }

        private fun saveScreenshot(native: MpvNative, handle: Pointer, action: Action.Screenshot) {
            if (!action.completion.isActive) return
            val destination = action.destination
            require(destination.fileName.toString().endsWith(".png", ignoreCase = true)) { "截图请使用 PNG 文件名。" }
            check(!Files.exists(destination)) { "截图目标文件已存在。" }
            val directory = requireNotNull(destination.parent)
            Files.createDirectories(directory)
            val temporary = Files.createTempFile(directory, ".bilipai-screenshot-", ".png")
            try {
                checkResult(native, native.mpv_command(handle, StringArray(arrayOf(
                    "screenshot-to-file", temporary.toString(), if (action.includeSubtitles) "subtitles" else "video",
                ), "UTF-8")), "screenshot")
                check(Files.size(temporary) > 0) { "原生播放器未写出截图。" }
                if (!action.completion.isActive) return
                Files.move(temporary, destination)
                if(!action.completion.complete(destination) && action.owned!=null)Files.deleteIfExists(destination)
            } finally { Files.deleteIfExists(temporary) }
        }

        private fun receiveEvent(native: MpvNative, handle: Pointer, event: Pointer) {
            // mpv_event x64 ABI: int event, int error, uint64 userdata, void* data.
            when (event.getInt(0)) {
                1 -> closing.set(true) // MPV_EVENT_SHUTDOWN
                2 -> { // MPV_EVENT_LOG_MESSAGE: prefix, level, text pointers; then int log_level.
                    val metadataMessage = event.getPointer(16) ?: return
                    if (metadataMessage.getInt(24) > 30) {
                        if (metadataMessage.getInt(24) <= 50) {
                            val prefix = nativeText(metadataMessage.getPointer(0), 96)
                            if (prefix == "vo/gpu" || prefix == "vo/gpu/d3d11") {
                                val capability = parseNativeVideoCapability(prefix, nativeText(metadataMessage.getPointer(16), 512))
                                synchronized(lock) {
                                    if (session === this && !closing.get()) when (capability) {
                                        is NativeVideoCapability.MaximumTextureDimension -> mutableVideoOutput.update { it.copy(maximumTextureDimension = capability.dimension) }
                                        is NativeVideoCapability.IntermediateFormat -> mutableVideoOutput.update { it.copy(intermediateFormat = capability.name) }
                                        null -> Unit
                                    }
                                }
                                val actual = mutableVideoOutput.value.intermediateFormat
                                val expected = activeShaders.options.intermediateFormat
                                if (capability != null && mutableVideoOutput.value.maximumTextureDimension != null &&
                                    ((expected != "auto" && actual == expected) || (expected == "auto" && actual?.contains("f") == true)))
                                    checkResult(native, native.mpv_request_log_messages(handle, "warn"), "finish-video-metadata")
                            }
                        }
                        return
                    }
                    if (activeEntry == null || (expectedEntry != null && expectedEntry != activeEntry) ||
                        !synchronized(lock) { sourceVersion == activeSourceVersion && playbackRevision == activeRevision }) return
                    val message = event.getPointer(16) ?: return
                    if (message.getInt(24) > 30) return
                    val prefix = nativeText(message.getPointer(0), 96)
                    val text = nativeText(message.getPointer(16), 8_192)
                    diagnostics.append(prefix, text)
                    if (activeShaders.paths.isNotEmpty() && Regex("(?is)(?:shader|glsl|spirv).*(?:compil.*(?:error|failed)|(?:failed|error).*compil)|failed to (?:compile|link).*(?:shader|program)|(?:User-specified )?FBO format.*(?:not found|failed to initialize)")
                            .containsMatchIn("$prefix: $text")) synchronized(lock) {
                        if (session === this && videoShaderVersion == activeShaderVersion && playbackRevision == activeRevision)
                            mutableVideoShaders.update { it.copy(active = false, error = diagnostics.sanitize(text)) }
                    }
                }
                6 -> { // MPV_EVENT_START_FILE
                    val entry = event.getPointer(16)?.getLong(0) ?: return
                    if (expectedEntry != null && expectedEntry != entry) return
                    activeEntry = entry
                    fileLoaded = false
                    loadedSubtitlePaths.clear()
                    publishState { it.copy(loading = true, ended = false, error = null, failure = null,
                        firstVideoFrameReady = false, pausedForCache = false, bufferedForwardSeconds = null, nativePaused = null, videoBitrateBps = null, audioBitrateBps = null) }
                }
                8 -> { // MPV_EVENT_FILE_LOADED
                    if (activeEntry == null || (expectedEntry != null && activeEntry != expectedEntry)) return
                    fileLoaded = true
                    publishState { it.copy(loading = false, ended = false, error = null, failure = null) }
                    restoreSubtitles(native, handle)
                }
                21 -> { // MPV_EVENT_PLAYBACK_RESTART: file startup alone has no submitted seek to acknowledge.
                    if (!fileLoaded) return
                    val outputWidth = property(native, handle, "video-out-params/w")?.toIntOrNull() ?: 0
                    val outputHeight = property(native, handle, "video-out-params/h")?.toIntOrNull() ?: 0
                    if (!state.value.audioOnly && outputWidth > 0 && outputHeight > 0 && property(native, handle, "video-codec") != null)
                        publishState {
                            softwareTarget?.enableSource(activeSourceVersion, activeRevision)
                            it.copy(firstVideoFrameReady = true)
                        }
                    val position = property(native, handle, "time-pos")?.toDoubleOrNull() ?: return
                    val completed = seekTracker.acknowledge(activeSourceVersion, position) ?: return
                    publishState { it.copy(positionSeconds = position, seekCompletedId = completed.id, seekCompletedPositionSeconds = position) }
                }
                7 -> { // MPV_EVENT_END_FILE
                    val data = event.getPointer(16) ?: return
                    val entry = data.getLong(8)
                    if (activeEntry != entry) return
                    fileLoaded = false
                    val completedAtEnd = if (data.getInt(0) == 0 && state.value.durationSeconds > 0)
                        seekTracker.acknowledge(activeSourceVersion, state.value.durationSeconds) else null
                    seekTracker.reset()
                    when (data.getInt(0)) {
                        0 -> publishState { it.copy(loading = false, ended = true, paused = true,
                            seekCompletedId = completedAtEnd?.id ?: it.seekCompletedId,
                            seekCompletedPositionSeconds = completedAtEnd?.let { it.positionSeconds } ?: it.seekCompletedPositionSeconds) }
                        4 -> publishFailure(data.getInt(4), "Playback failed: ${native.mpv_error_string(data.getInt(4))}")
                    }
                }
            }
        }

        private fun publishFailure(code: Int?, message: String) = synchronized(lock) {
            if (session !== this || sourceVersion != activeSourceVersion || playbackRevision != activeRevision) return@synchronized
            val failure = diagnostics.failure(code, message, activeSourceVersion, activeAttemptId)
            mutableState.update { it.copy(loading = false, error = failure.safeMessage, failure = failure) }
        }

        private fun publishState(transform: (PlayerState) -> PlayerState) = synchronized(lock) {
            if (session === this && sourceVersion == activeSourceVersion && playbackRevision == activeRevision)
                mutableState.update(transform)
        }

        private fun nativeText(pointer: Pointer?, maximumBytes: Int): String {
            if (pointer == null) return ""
            // Stop at the first native NUL rather than allocate an unbounded getString result.
            var count = 0
            while (count < maximumBytes && pointer.getByte(count.toLong()) != 0.toByte()) count++
            return String(pointer.getByteArray(0, count), Charsets.UTF_8)
        }

        private fun refreshState(native: MpvNative, handle: Pointer) {
            fun packetBitrate(name: String): Long? = if (fileLoaded) property(native, handle, name)?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it >= 0.0 && it <= Long.MAX_VALUE.toDouble() }?.toLong() else null
            val videoBitrate = packetBitrate("video-bitrate")
            val audioBitrate = packetBitrate("audio-bitrate")
            val nativePaused = when (property(native, handle, "pause")) { "yes" -> true; "no" -> false; else -> null }
            val paused = nativePaused ?: state.value.paused
            val buffering = property(native, handle, "paused-for-cache") == "yes"
            val bufferedForward = if (fileLoaded) property(native, handle, "demuxer-cache-duration")?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it >= 0.0 } else null
            val position = property(native, handle, "time-pos")?.toDoubleOrNull()
            val duration = property(native, handle, "duration")?.toDoubleOrNull()
            val videoCodec = property(native, handle, "video-codec")
            val audioCodec = property(native, handle, "audio-codec")
            val avSync = property(native, handle, "avsync")?.toDoubleOrNull()
            val volume = property(native, handle, "volume")?.toDoubleOrNull()
            val speed = property(native, handle, "speed")?.toDoubleOrNull()
            val panscan = property(native, handle, "panscan")?.toDoubleOrNull()?.takeIf(Double::isFinite)
            val muted = property(native, handle, "mute") == "yes"
            val subtitlesVisible = property(native, handle, "sub-visibility") == "yes"
            val subtitleText = if (fileLoaded && subtitlesVisible) property(native, handle, "sub-text")?.takeIf(String::isNotBlank) else null
            val secondarySubtitleText = if (fileLoaded && subtitlesVisible) property(native, handle, "secondary-sub-text")?.takeIf(String::isNotBlank) else null
            val now = System.nanoTime()
            if (now - lastShaderPoll > 1_000_000_000L) {
                refreshVideoShaders(native, handle)
                lastShaderPoll = now
            }
            if (fileLoaded && now - lastTrackPoll > 1_000_000_000L) {
                tracks = readTracks(native, handle)
                lastTrackPoll = now
            }
            val hardwareDecoder = if (fileLoaded) property(native, handle, "hwdec-current")?.takeUnless { it == "no" } else null
            val videoWidth = if (fileLoaded) property(native, handle, "video-params/dw")?.toIntOrNull() ?: 0 else 0
            val videoHeight = if (fileLoaded) property(native, handle, "video-params/dh")?.toIntOrNull() ?: 0 else 0
            val inputWidth = if (fileLoaded) property(native, handle, "video-params/w")?.toIntOrNull()?.coerceAtLeast(0) ?: 0 else 0
            val inputHeight = if (fileLoaded) property(native, handle, "video-params/h")?.toIntOrNull()?.coerceAtLeast(0) ?: 0 else 0
            val osdWidth = if (fileLoaded && videoWidth > 0) property(native, handle, "osd-dimensions/w")?.toIntOrNull() else null
            val osdHeight = if (fileLoaded && videoHeight > 0) property(native, handle, "osd-dimensions/h")?.toIntOrNull() else null
            fun margin(name: String) = if (fileLoaded) property(native, handle, "osd-dimensions/$name")?.toIntOrNull() else null
            val marginLeft = margin("ml"); val marginRight = margin("mr")
            val marginTop = margin("mt"); val marginBottom = margin("mb")
            val displayWidth = if (osdWidth != null && marginLeft != null && marginRight != null)
                (osdWidth - marginLeft - marginRight).coerceAtLeast(0) else 0
            val displayHeight = if (osdHeight != null && marginTop != null && marginBottom != null)
                (osdHeight - marginTop - marginBottom).coerceAtLeast(0) else 0
            val videoViewport = if(osdWidth!=null&&osdWidth>0&&osdHeight!=null&&osdHeight>0&&marginLeft!=null&&marginTop!=null&&marginRight!=null&&marginBottom!=null&&displayWidth>0&&displayHeight>0)
                PlayerVideoViewport(osdWidth,osdHeight,marginLeft,marginTop,displayWidth,displayHeight) else null
            val gamma = if (fileLoaded) property(native, handle, "video-params/gamma")?.takeIf { it.length <= 64 } else null
            val dolbyVisionProfile = if (fileLoaded) tracks.firstOrNull { it.type == "video" && it.selected }?.let { selected ->
                val count = property(native, handle, "track-list/count")?.toIntOrNull()?.coerceIn(0, 200) ?: 0
                (0 until count).firstOrNull { property(native, handle, "track-list/$it/id")?.toIntOrNull() == selected.id }
                    ?.let { property(native, handle, "track-list/$it/dolby-vision-profile")?.toIntOrNull() }
            } else null
            synchronized(lock) {
                if (session === this && sourceVersion == activeSourceVersion && playbackRevision == activeRevision)
                    mutableVideoOutput.update { it.copy(sourceVersion = activeSourceVersion, inputWidth = inputWidth, inputHeight = inputHeight, displayWidth = displayWidth, displayHeight = displayHeight, viewport = videoViewport, gamma = gamma, dolbyVisionProfile = dolbyVisionProfile) }
            }
            publishState {
                it.copy(
                    loading = if (fileLoaded) buffering else it.loading,
                    pausedForCache = fileLoaded && buffering,
                    bufferedForwardSeconds = bufferedForward,
                    videoBitrateBps = videoBitrate,
                    audioBitrateBps = audioBitrate,
                    paused = if (it.ended) true else if (fileLoaded) paused else it.paused,
                    nativePaused = if (fileLoaded) nativePaused else null,
                    positionSeconds = if (fileLoaded) position ?: it.positionSeconds else it.positionSeconds,
                    durationSeconds = if (fileLoaded) duration ?: it.durationSeconds else it.durationSeconds,
                    volume = volume ?: it.volume,
                    speed = speed ?: it.speed,
                    activeVideoPanscan = panscan,
                    videoCodec = if (fileLoaded) videoCodec else null,
                    hardwareDecoder = hardwareDecoder,
                    videoWidth = videoWidth,
                    videoHeight = videoHeight,
                    audioCodec = if (fileLoaded) audioCodec else null,
                    avSyncSeconds = if (fileLoaded) avSync else null,
                    muted = muted,
                    subtitlesVisible = subtitlesVisible,
                    subtitleText = subtitleText,
                    secondarySubtitleText = secondarySubtitleText,
                    tracks = if (fileLoaded) tracks else emptyList(),
                )
            }
        }

        private fun refreshVideoShaders(native: MpvNative, handle: Pointer) {
            if (activeShaderVersion < 0) return
            val files = MpvVideoShaderProperties.files(native, handle).orEmpty()
            val passes = if (fileLoaded && activeShaders.paths.isNotEmpty()) MpvVideoShaderProperties.passes(native, handle).orEmpty() else emptyList()
            synchronized(lock) {
                if (session !== this || videoShaderVersion != activeShaderVersion || playbackRevision != activeRevision) return
                mutableVideoShaders.update { previous ->
                    previous.copy(appliedFiles = java.util.Collections.unmodifiableList(files.toList()),
                        executedPasses = java.util.Collections.unmodifiableList(passes.toList()),
                        actualIntermediateFormat = mutableVideoOutput.value.intermediateFormat,
                        active = previous.error == null && files == activeShaders.paths && activeShaders.executed(passes) &&
                            (activeShaders.options.intermediateFormat == "auto" || mutableVideoOutput.value.intermediateFormat == activeShaders.options.intermediateFormat))
                }
            }
        }

        private fun readTracks(native: MpvNative, handle: Pointer): List<PlayerTrack> {
            val count = property(native, handle, "track-list/count")?.toIntOrNull()?.coerceIn(0, 200) ?: return emptyList()
            return (0 until count).mapNotNull { index ->
                val prefix = "track-list/$index"
                val id = property(native, handle, "$prefix/id")?.toIntOrNull() ?: return@mapNotNull null
                val type = property(native, handle, "$prefix/type") ?: return@mapNotNull null
                PlayerTrack(id, type, property(native, handle, "$prefix/title"), property(native, handle, "$prefix/lang"),
                    property(native, handle, "$prefix/selected") == "yes", property(native, handle, "$prefix/external") == "yes",
                    property(native, handle, "$prefix/main-selection")?.toIntOrNull())
            }
        }
    }

    private fun property(native: MpvNative, handle: Pointer, name: String): String? {
        val value = native.mpv_get_property_string(handle, name) ?: return null
        return try { value.getString(0, "UTF-8") } finally { native.mpv_free(value) }
    }

    private fun checkResult(native: MpvNative, result: Int, operation: String) {
        if (result < 0) throw MpvCallException(result, "Native player $operation: ${native.mpv_error_string(result)}")
    }
}

private class MpvCallException(val nativeCode: Int, message: String) : IllegalStateException(message)
