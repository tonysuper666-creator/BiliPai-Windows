package com.bilipai.desktop.plugins

import com.android.purebilibili.core.plugin.*
import com.android.purebilibili.core.plugin.json.JsonPluginManager
import com.android.purebilibili.core.plugin.json.JsonRulePlugin
import com.android.purebilibili.data.model.response.VideoItem
import com.android.purebilibili.core.store.TodayWatchProfileStore
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.data.DesktopCommunityRepository
import com.bilipai.desktop.data.DesktopDiscoveryRepository
import com.bilipai.desktop.data.PlaybackSource
import com.bilipai.desktop.cast.DesktopGoogleCastPlugin
import com.bilipai.desktop.cast.DesktopCastProxySessions
import com.android.purebilibili.feature.cast.LocalProxyServer
import com.bilipai.desktop.plugins.js.*
import java.nio.file.Path
import com.android.purebilibili.feature.plugin.*
import com.android.purebilibili.feature.plugin.dlna.DlnaCastPlugin
import com.android.purebilibili.feature.video.danmaku.DesktopPluginDanmakuPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class DesktopEyePaint(val dimAlpha: Float, val warmAlpha: Float, val warmArgb: Int = 0xffffc07a.toInt())

/** A captured view of the existing Runtime video, not another generation owner.
 * Root binds its required predicates to one canonical accepted native lease. */
internal class DesktopPlayerPluginDispatch internal constructor(
    internal val runtime: DesktopPluginRuntime,
    internal val videoIdentity: Any,
    internal val generation: Long,
    internal val stillOwned: () -> Boolean,
    internal val admission: ((() -> Unit) -> Boolean),
    internal val providers: List<Plugin>,
) {
    override fun toString() = "DesktopPlayerPluginDispatch(generation=$generation)"
}

/** Windows binds the original plugin manager and original JSON engine to real storage. */
class DesktopPluginRuntime(val store: DesktopPluginStore,
    repository: DesktopRepository? = null, community: DesktopCommunityRepository? = null,
    private val discovery: DesktopDiscoveryRepository? = null,
    private val beforeStoreFreeze: suspend () -> Unit = {}) : AutoCloseable {
    val context = DesktopPluginContext(store)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val configurationMutex = Mutex()
    private val playerMutex = Mutex()
    private val shutdownMutex = Mutex()
    private val closing = AtomicBoolean()
    private var stopped = false
    private val playerGeneration = AtomicLong()
    private data class CurrentVideo(val generation: Long, val bvid: String, val cid: Long)
    private val currentVideo = AtomicReference<CurrentVideo?>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    val eyeProtection = EyeProtectionPlugin()
    val enhancementConfiguration: DesktopVideoEnhancementConfiguration
    private val danmakuEnhance = DanmakuEnhancePlugin()
    private val sponsorBlock = SponsorBlockPlugin()
    internal val originalSponsorBlock get() = sponsorBlock
    val todayWatch = TodayWatchPlugin { DesktopPluginRepositoryBinding.recommendationContext() }
    val dlnaCast = DlnaCastPlugin()
    val googleCast = DesktopGoogleCastPlugin()
    private val castProxyConsumer = DesktopCastProxySessions.registerConsumer {
        googleCast.playbackState.value.isActive || googleCast.isBusy.value
    }
    private val cdn = CdnRegionPlugin()
    private val adFilter = AdFilterPlugin()
    val jsPlugins = DesktopJsPluginRepository(context, DesktopJsPluginHost(
        DesktopJsWorkerResources(desktopJsWorkerResources()).createProcess(context.filesDir.toPath()),
        context.filesDir.toPath(), ownerEpoch = repository?.let { { it.sessionEpoch } }))
    val subscriptions = DesktopSubscriptionRepository(context,
        extraSources = { jsPlugins.feedSourceSnapshot().let { DesktopSubscriptionExtraSources(it.revision, it.sources) } },
        extraSourceRevision = { jsPlugins.host.executionRevision.value }) {
        plugins.value.any { it.plugin.id == SubscriptionFeedPlugin.PLUGIN_ID && it.enabled }
    }
    val packages = DesktopPackageRepository(context)
    val recommendations = DesktopTodayWatchRepository({ repository?.sessionEpoch }, scope)
    val plugins get() = PluginManager.pluginsFlow
    val jsonPlugins get() = JsonPluginManager.plugins
    val jsonFilterStats get() = JsonPluginManager.filterStats
    val danmakuRevision get() = PluginManager.danmakuPluginUpdateToken
    val effectHint get() = PluginEffectHintBus.current

    init {
        if (repository != null && community != null && discovery != null) {
            DesktopPluginRepositoryBinding.initialize(repository, community, discovery)
        }
        PluginManager.initialize(context)
        DesktopPluginAnalytics.initialize(context)
        JsonPluginManager.initialize(context)
        PluginManager.register(sponsorBlock)
        PluginManager.register(danmakuEnhance)
        PluginManager.register(eyeProtection)
        enhancementConfiguration = DesktopVideoEnhancementConfiguration(store,
            // Accepted config writes drain even after shutdown begins, before store.freezeWrites().
            serialize = { operation -> configurationMutex.withLock { operation() } },
            acceptChanges = { !closing.get() })
        PluginManager.register(DesktopFeedFilterPlugin(store))
        PluginManager.register(HomeFeedAnonymizerPlugin())
        PluginManager.register(adFilter)
        PluginManager.register(SubscriptionFeedPlugin())
        PluginManager.register(todayWatch)
        PluginManager.register(DesktopPlaybackCdnPlugin(cdn))
        PluginManager.register(dlnaCast)
        // Windows begins with optional local-network casting disabled; preserve an explicit saved choice.
        initializeDesktopGoogleCastDefault(store)
        PluginManager.register(googleCast)
        scope.launch {
            try { packages.load() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { DesktopPluginLog.e("packages", "Package load failed", failure) }
        }
        scope.launch {
            try {
                if (repository == null) jsPlugins.load()
                else repository.sessionEpochFlow.collect { epoch ->
                    try { jsPlugins.accountChanged(epoch); jsPlugins.load() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { DesktopPluginLog.e("js-plugins", "JS plugin account load failed", failure) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { DesktopPluginLog.e("js-plugins", "JS plugin load failed", failure) }
        }
        scope.launch {
            store.feedFilterEnabled.collect { enabled -> PluginManager.setEnabled("bilipai_feed_filter", enabled) }
        }
        if (repository != null) scope.launch {
            repository.sessionEpochFlow.collect { epoch -> recommendations.accountChanged(epoch) }
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean, stillOwned: () -> Boolean = { true }) {
        checkOpen()
        if (!stillOwned()) return
        require(id in setOf(SPONSOR_BLOCK_PLUGIN_ID, "danmaku_enhance", EYE_PROTECTION_PLUGIN_ID, "bilipai_feed_filter") || plugins.value.any { it.plugin.id == id }) { "插件不存在" }
        PluginManager.awaitPluginReady(id)
        val info = plugins.value.firstOrNull { it.plugin.id == id } ?: error("插件不存在")
        require(!info.plugin.unavailable) { info.plugin.unavailableReason }
        playerMutex.withLock {
            checkOpen()
            if (!stillOwned()) return@withLock
            PluginManager.setEnabled(id, enabled, stillOwned)
            if (!stillOwned()) return@withLock
            check(plugins.value.firstOrNull { it.plugin.id == id }?.enabled == enabled) { "插件启用失败，请检查配置" }
            if (enabled && info.plugin is PlayerPlugin) {
                currentVideo.get()?.takeIf { it.generation == playerGeneration.get() }?.let { video ->
                    info.plugin.onVideoLoad(video.bvid, video.cid)
                }
            }
        }
    }

    suspend fun configuration(id: String): String? = PluginStore.getConfigJson(context, id)

    private fun checkOpen() = check(!closing.get()) { "插件服务已停止" }
    private suspend fun <T> configurationChange(block: suspend () -> T): T = configurationMutex.withLock {
        checkOpen()
        block()
    }

    suspend fun saveDanmakuConfiguration(config: DanmakuEnhanceConfig) = configurationChange {
        PluginStore.setConfigJson(context, "danmaku_enhance", json.encodeToString(config))
        val enabled = plugins.value.any { it.plugin.id == "danmaku_enhance" && it.enabled }
        if (enabled) {
            PluginManager.setEnabled("danmaku_enhance", false)
            PluginManager.setEnabled("danmaku_enhance", true)
        }
        PluginManager.notifyDanmakuPluginsUpdated()
    }

    suspend fun saveSponsorConfiguration(config: SponsorBlockConfig) = configurationChange {
        playerMutex.withLock {
            PluginStore.setConfigJson(context, SPONSOR_BLOCK_PLUGIN_ID, json.encodeToString(config.normalized()))
            if (plugins.value.any { it.plugin.id == SPONSOR_BLOCK_PLUGIN_ID && it.enabled }) {
                currentVideo.get()?.takeIf { it.generation == playerGeneration.get() }?.let { sponsorBlock.onVideoLoad(it.bvid, it.cid) }
            }
        }
    }

    suspend fun saveAdFilterConfiguration(config: AdFilterConfig) = configurationChange {
        require(config.minViewCount >= 0) { "最低播放量不能为负数" }
        PluginStore.setConfigJson(context, ADFILTER_PLUGIN_ID, json.encodeToString(config))
        if (plugins.value.any { it.plugin.id == ADFILTER_PLUGIN_ID && it.enabled }) {
            PluginManager.setEnabled(ADFILTER_PLUGIN_ID, false)
            PluginManager.setEnabled(ADFILTER_PLUGIN_ID, true)
        }
    }

    internal suspend fun saveCdnConfiguration(config: CdnRegionPluginCache) = configurationChange {
        playerMutex.withLock {
        validateCdnCustomRules(config.customRules).firstOrNull { it.error != null }?.let { error(it.error!!) }
        // Adaptive prefetch needs a cache served to mpv; its settings are introduced with that adapter.
        CdnRegionPluginStore.write(context, config.copy(prefetchEnabled = false))
        if (plugins.value.any { it.plugin.id == CDN_REGION_PLUGIN_ID && it.enabled }) {
            PluginManager.setEnabled(CDN_REGION_PLUGIN_ID, false)
            PluginManager.setEnabled(CDN_REGION_PLUGIN_ID, true)
        }
        }
    }

    /** DASH backups are alternatives; progressive parts retain their order and duration. */
    fun rewritePlaybackSource(source: PlaybackSource): PlaybackSource {
        if (plugins.value.none { it.plugin.id == CDN_REGION_PLUGIN_ID && it.enabled }) return source
        if (source.progressiveSegments.isNotEmpty()) {
            val parts = source.progressiveSegments.map { segment ->
                val candidates = cdn.rewritePlaybackCandidates(listOf(segment.url), emptyList()).candidates
                segment.copy(url = candidates.firstOrNull()?.videoUrl ?: segment.url)
            }
            return source.copy(videoUrl = parts.first().url, audioUrl = null, progressiveSegments = parts)
        }
        val candidates = cdn.rewritePlaybackCandidates(listOf(source.videoUrl) + source.videoAlternatives,
            listOfNotNull(source.audioUrl) + source.audioAlternatives).candidates
        val first = candidates.firstOrNull() ?: return source
        return source.copy(videoUrl = first.videoUrl, audioUrl = first.audioUrl,
            videoAlternatives = candidates.drop(1).map { it.videoUrl },
            audioAlternatives = candidates.drop(1).mapNotNull { it.audioUrl })
    }
    fun playbackCdnDiagnostics(source: PlaybackSource) = cdn.buildPlaybackCdnDiagnostics(listOf(source.videoUrl) + source.videoAlternatives)
    suspend fun probePlaybackCdn(source: PlaybackSource) = cdn.probePlaybackCdnCandidates(listOf(source.videoUrl) + source.videoAlternatives)
    fun recordPlaybackCdnEvent(url: String, event: CdnHealthEvent) {
        if (plugins.value.any { it.plugin.id == CDN_REGION_PLUGIN_ID && it.enabled }) cdn.recordPlaybackCdnEvent(url, event)
    }

    fun buildRecommendations(request: RecommendationRequest): List<RecommendationResult> =
        PluginManager.getEnabledPlugins(RecommendationPluginApi::class).map { it.buildRecommendations(request) }
    fun creatorSignals() = TodayWatchProfileStore.getCreatorSignals(DesktopPluginRepositoryBinding.recommendationContext())
    fun recordCreatorWatch(mid: Long, creator: String, deltaSeconds: Long) =
        TodayWatchProfileStore.recordWatchProgress(DesktopPluginRepositoryBinding.recommendationContext(), mid, creator, deltaSeconds)

    fun filterFeedItems(items: List<VideoItem>, kind: FeedKind = FeedKind.GENERIC): List<VideoItem> =
        if (closing.get()) items else JsonPluginManager.filterVideos(PluginManager.filterFeedItems(items, kind))

    fun processDanmaku(item: DanmakuItem): Pair<DanmakuItem, DanmakuStyle?>? {
        if (closing.get()) return item to null
        val native = PluginManager.getEnabledDanmakuPlugins()
        val jsonEnabled = jsonPlugins.value.any { it.enabled && it.plugin.type == "danmaku" }
        val processed = DesktopPluginDanmakuPolicy.runDanmakuFilters(item, native, jsonEnabled) ?: return null
        return processed to DesktopPluginDanmakuPolicy.collectDanmakuStyle(processed, native, jsonEnabled)
    }

    /** One enabled-plugin snapshot for Mode 9's filter -> parse -> style stages.
     * The raw BAS script never goes through ordinary text truncation/type mapping. */
    internal fun captureBasDanmakuProcessor(): com.bilipai.desktop.danmaku.DesktopBasPluginProcessor? {
        if (closing.get()) return null
        val native = PluginManager.getEnabledDanmakuPlugins()
        val jsonEnabled = jsonPlugins.value.any { it.enabled && it.plugin.type == "danmaku" }
        if (native.isEmpty() && !jsonEnabled) return null
        return com.bilipai.desktop.danmaku.DesktopBasPluginProcessor(
            filter = { item ->
                if (closing.get()) throw CancellationException("BAS plugin runtime retired")
                DesktopPluginDanmakuPolicy.runDanmakuFilters(item, native, jsonEnabled)
            },
            style = { item ->
                if (closing.get()) throw CancellationException("BAS plugin runtime retired")
                DesktopPluginDanmakuPolicy.collectDanmakuStyle(item, native, jsonEnabled)
            })
    }

    fun eyePaint(playbackActive: StateFlow<Boolean>): Flow<DesktopEyePaint> = combine(
        eyeProtection.brightnessLevel, eyeProtection.warmFilterStrength,
        eyeProtection.weakenDuringPlayback, playbackActive
    ) { brightness, warm, weaken, playing ->
        resolveEyeOverlayPaint(brightness, warm, weaken && playing).let { DesktopEyePaint(it.dimAlpha, it.warmAlpha, it.warmColor.toInt()) }
    }.combine(plugins) { paint, current ->
        if (current.any { it.plugin.id == EYE_PROTECTION_PLUGIN_ID && it.enabled }) paint else DesktopEyePaint(0f, 0f)
    }

    /** Serialized original callbacks prevent an old HTTP load from mutating a later video. */
    suspend fun onVideoLoad(bvid: String, cid: Long): Long {
        checkOpen()
        val generation = playerGeneration.incrementAndGet()
        val video = CurrentVideo(generation, bvid, cid)
        currentVideo.updateAndGet { previous -> if (previous == null || previous.generation < generation) video else previous }
        try {
            playerMutex.withLock {
                if (closing.get() || generation != playerGeneration.get()) return generation
                for (plugin in PluginManager.getEnabledPlayerPlugins()) {
                    currentCoroutineContext().ensureActive()
                    try { plugin.onVideoLoad(bvid, cid) }
                    catch (error: CancellationException) { throw error }
                    catch (error: Exception) { DesktopPluginLog.e(plugin.id, "Video plugin load failed", error) }
                }
                currentCoroutineContext().ensureActive()
            }
        } catch (cancelled: CancellationException) {
            if (playerGeneration.compareAndSet(generation, generation + 1)) currentVideo.compareAndSet(video, null)
            throw cancelled
        }
        return generation
    }

    /** Full original owner publishes exactly once for a newly accepted native
     * source. Inherited handoff/recovery retains its REAL generation. Each provider
     * identity and Job is captured before waiting for the existing playerMutex. */
    internal suspend fun onVideoLoadOwned(bvid: String, cid: Long,
        stillOwned: () -> Boolean, admission: ((() -> Unit) -> Boolean),
        calls: okhttp3.Call.Factory): Long {
        val caller = currentCoroutineContext()
        fun checkOwner() {
            caller.ensureActive()
            if (closing.get() || !stillOwned()) throw CancellationException("Original Runtime load retired")
        }
        checkOwner()
        val providers = PluginManager.getEnabledPlayerPlugins().toList()
        var video: CurrentVideo? = null
        if (!admission {
            checkOwner()
            val generation = playerGeneration.incrementAndGet()
            video = CurrentVideo(generation, bvid, cid)
            currentVideo.updateAndGet { previous -> if (previous == null || previous.generation < generation) video else previous }
        }) throw CancellationException("Original Runtime publication rejected")
        val captured = checkNotNull(video)
        val dispatch = DesktopPlayerPluginDispatch(this, captured, captured.generation,
            stillOwned, admission, providers)
        try {
            playerMutex.withLock {
                checkOwner()
                for (plugin in providers) {
                    checkOwner()
                    requirePlaybackPluginDispatch(dispatch, plugin, false)
                    if (!admission { checkOwner(); requirePlaybackPluginDispatch(dispatch, plugin, false) })
                        throw CancellationException("Original provider load rejected")
                    val write = playbackPluginWriteOperation(dispatch, plugin, false, calls)
                    try { DesktopPlayerPluginWriteAdmission.withCaptured(write) { plugin.onVideoLoad(bvid, cid) } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { DesktopPluginLog.e(plugin.id, "Video plugin load failed", failure) }
                    checkOwner(); requirePlaybackPluginDispatch(dispatch, plugin, false)
                    if (!admission { checkOwner(); requirePlaybackPluginDispatch(dispatch, plugin, false) })
                        throw CancellationException("Original provider result rejected")
                }
            }
        } catch (cancelled: CancellationException) {
            if (playerGeneration.compareAndSet(captured.generation, captured.generation + 1))
                currentVideo.compareAndSet(captured, null)
            throw cancelled
        }
        checkOwner()
        return captured.generation
    }

    /** Root uses the actual inherited generation from handoff, or the generation
     * returned by this Runtime's real load. Never recapture it after an await. */
    internal fun capturePlaybackPluginDispatch(bvid: String, cid: Long, expectedGeneration: Long,
        stillOwned: () -> Boolean, admission: ((() -> Unit) -> Boolean)): DesktopPlayerPluginDispatch? {
        val video = currentVideo.get() ?: return null
        if (closing.get() || video.generation != expectedGeneration || playerGeneration.get() != expectedGeneration ||
            video.bvid != bvid || video.cid != cid || !stillOwned()) return null
        val dispatch = DesktopPlayerPluginDispatch(this, video, expectedGeneration, stillOwned, admission,
            PluginManager.getEnabledPlugins(Plugin::class).toList())
        return dispatch.takeIf(::isPlaybackPluginDispatchCurrent)
    }

    internal fun isPlaybackPluginDispatchCurrent(dispatch: DesktopPlayerPluginDispatch): Boolean =
        dispatch.runtime === this && !closing.get() && currentVideo.get() === dispatch.videoIdentity &&
            playerGeneration.get() == dispatch.generation && dispatch.stillOwned()

    private fun requirePlaybackPluginDispatch(dispatch: DesktopPlayerPluginDispatch, plugin: Plugin,
        allowDisabledSponsor: Boolean) {
        if (!isPlaybackPluginDispatchCurrent(dispatch)) throw CancellationException("Playback plugin dispatch retired")
        val retainedDisabledSponsor = allowDisabledSponsor && plugin === sponsorBlock
        if (!retainedDisabledSponsor && (dispatch.providers.none { it === plugin } ||
                plugins.value.none { it.plugin === plugin && it.enabled }))
            throw CancellationException("Captured playback plugin is no longer enabled")
    }

    /** Wait for the SAME playerMutex outside Store/entry/native gates. Synchronous
     * provider mutations then use Root's short captured-source atomic admission. */
    private suspend fun playbackPluginWriteOperation(dispatch: DesktopPlayerPluginDispatch, plugin: Plugin,
        allowDisabledSponsor: Boolean, playbackCalls: okhttp3.Call.Factory?): DesktopPlayerPluginWriteAdmission.Operation {
        val origin = currentCoroutineContext()[Job] ?: error("Playback plugin requires a caller Job")
        return DesktopPlayerPluginWriteAdmission.Operation(context, origin, playbackCalls,
            checkCaptured = { requirePlaybackPluginDispatch(dispatch, plugin, allowDisabledSponsor) },
            admission = dispatch.admission,
            serializedDeferred = { captured, block ->
                playerMutex.withLock {
                    currentCoroutineContext().ensureActive(); captured.check()
                    if (!dispatch.admission { captured.check() })
                        throw CancellationException("Deferred playback plugin admission retired")
                    DesktopPlayerPluginWriteAdmission.withCaptured(captured, block)
                    currentCoroutineContext().ensureActive(); captured.check()
                }
            })
    }

    internal suspend fun <T> mutatePlaybackPlugin(dispatch: DesktopPlayerPluginDispatch, plugin: Plugin,
        allowDisabledSponsor: Boolean, action: () -> T): T = playerMutex.withLock {
        currentCoroutineContext().ensureActive()
        requirePlaybackPluginDispatch(dispatch, plugin, allowDisabledSponsor)
        val captured = playbackPluginWriteOperation(dispatch, plugin, allowDisabledSponsor, null)
        DesktopPlayerPluginWriteAdmission.withCaptured(captured) {
            var result: Result<T>? = null
            if (!dispatch.admission {
                requirePlaybackPluginDispatch(dispatch, plugin, allowDisabledSponsor)
                result = runCatching { DesktopPlayerPluginWriteAdmission.inSynchronousAdmission(captured, action) }
            }) throw CancellationException("Playback plugin mutation admission retired")
            currentCoroutineContext().ensureActive()
            requirePlaybackPluginDispatch(dispatch, plugin, allowDisabledSponsor)
            (result ?: throw CancellationException("Playback plugin admission did not execute")).getOrThrow()
        }
    }

    /** Original three-argument ABI remains for callbacks which do not probe playback URLs. */
    internal suspend fun <T> runPlaybackPluginCallback(dispatch: DesktopPlayerPluginDispatch, plugin: Plugin,
        action: suspend () -> T): T = runPlaybackPluginCallbackOwned(dispatch, plugin, null, action)

    /** Root captures this SAME accepted lease Call.Factory before the callback's first await. */
    internal suspend fun <T> runPlaybackPluginCallback(dispatch: DesktopPlayerPluginDispatch, plugin: Plugin,
        playbackCalls: okhttp3.Call.Factory, action: suspend () -> T): T =
        runPlaybackPluginCallbackOwned(dispatch, plugin, playbackCalls, action)

    private suspend fun <T> runPlaybackPluginCallbackOwned(dispatch: DesktopPlayerPluginDispatch, plugin: Plugin,
        playbackCalls: okhttp3.Call.Factory?, action: suspend () -> T): T = playerMutex.withLock {
        currentCoroutineContext().ensureActive()
        requirePlaybackPluginDispatch(dispatch, plugin, allowDisabledSponsor = false)
        if (!dispatch.admission { requirePlaybackPluginDispatch(dispatch, plugin, allowDisabledSponsor = false) })
            throw CancellationException("Playback plugin callback admission retired")
        val captured = playbackPluginWriteOperation(dispatch, plugin, false, playbackCalls)
        val result = DesktopPlayerPluginWriteAdmission.withCaptured(captured, action)
        currentCoroutineContext().ensureActive()
        requirePlaybackPluginDispatch(dispatch, plugin, allowDisabledSponsor = false)
        if (!dispatch.admission { requirePlaybackPluginDispatch(dispatch, plugin, allowDisabledSponsor = false) })
            throw CancellationException("Playback plugin result admission retired")
        result
    }

    /** A NEW immutable playback request has no accepted native source yet.
     * Waiting uses this Runtime's existing playerMutex; original pure candidate
     * rewriting/diagnostics run in the supplied captured request admission.
     * Caller captures its request and provider before waiting, never latest state.
     */
    internal suspend fun <T> mutateCapturedPlaybackPluginRequest(plugin: Plugin,
        stillOwned: () -> Boolean, admission: ((() -> Unit) -> Boolean), action: () -> T): T = playerMutex.withLock {
        val caller = currentCoroutineContext()
        fun checkRequest() {
            caller.ensureActive()
            if (closing.get() || !stillOwned() || plugins.value.none { it.plugin === plugin && it.enabled })
                throw CancellationException("Captured playback plugin request retired")
        }
        checkRequest()
        var result: Result<T>? = null
        if (!admission { checkRequest(); result = runCatching(action) })
            throw CancellationException("Playback plugin request admission rejected")
        checkRequest()
        (result ?: throw CancellationException("Playback request admission did not execute")).getOrThrow()
    }

    /** Root settings uses the same playerMutex and original Store even before a first
     * accepted source exists. Captured entry/native identity remains fixed across IO. */
    internal suspend fun <T> runCapturedPlaybackPluginSettings(plugin: Plugin,
        stillOwned: () -> Boolean, admission: ((() -> Unit) -> Boolean), action: suspend () -> T): T = playerMutex.withLock {
        val caller = currentCoroutineContext()[Job] ?: error("CDN settings requires caller Job")
        fun checkSettings() {
            caller.ensureActive()
            if (closing.get() || !stillOwned() || plugins.value.none { it.plugin === plugin && it.enabled })
                throw CancellationException("Captured CDN settings owner retired")
        }
        checkSettings()
        if (!admission { checkSettings() }) throw CancellationException("CDN settings admission rejected")
        val captured = DesktopPlayerPluginWriteAdmission.Operation(context, caller, null,
            ::checkSettings, admission, serializedDeferred = { previous, block ->
                playerMutex.withLock {
                    currentCoroutineContext().ensureActive(); previous.check()
                    if (!admission { previous.check() }) throw CancellationException("Deferred CDN settings retired")
                    DesktopPlayerPluginWriteAdmission.withCaptured(previous, block)
                }
            })
        val result = DesktopPlayerPluginWriteAdmission.withCaptured(captured, action)
        checkSettings()
        if (!admission { checkSettings() }) throw CancellationException("CDN settings result rejected")
        result
    }

    /** Cleanup cannot depend on the canceled VM scope or current native entry.
     * Capture OLD generation/provider identities now, then use Runtime's existing
     * scope. A subsequent load increments generation BEFORE waiting for the mutex. */
    internal fun retirePlaybackPluginDispatch(dispatch: DesktopPlayerPluginDispatch): Boolean {
        if (dispatch.runtime !== this || closing.get() || currentVideo.get() !== dispatch.videoIdentity ||
            !playerGeneration.compareAndSet(dispatch.generation, dispatch.generation + 1)) return false
        val oldVideo = currentVideo.get()?.takeIf { it === dispatch.videoIdentity }
        if (oldVideo != null) currentVideo.compareAndSet(oldVideo, null)
        val capturedPlayers = dispatch.providers.filterIsInstance<PlayerPlugin>()
        scope.launch {
            playerMutex.withLock {
                for (plugin in capturedPlayers) {
                    currentCoroutineContext().ensureActive()
                    if (closing.get() || playerGeneration.get() != dispatch.generation + 1 || currentVideo.get() != null)
                        return@withLock
                    if (plugins.value.none { it.plugin === plugin && it.enabled }) continue
                    try { plugin.onVideoEnd() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { DesktopPluginLog.e(plugin.id, "Captured video plugin end failed", failure) }
                }
            }
        }
        return true
    }

    suspend fun onPositionUpdate(generation: Long, positionMs: Long): List<SkipAction> {
        if (generation != playerGeneration.get()) return emptyList()
        if (!playerMutex.tryLock()) return emptyList()
        try {
            if (generation != playerGeneration.get()) return emptyList()
            return PluginManager.getEnabledPlayerPlugins().mapNotNull { plugin ->
                try { plugin.onPositionUpdate(positionMs)?.takeUnless { it is SkipAction.None } }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { DesktopPluginLog.e(plugin.id, "Video plugin position failed", error); null }
            }
        } finally { playerMutex.unlock() }
    }

    fun onUserSeek(positionMs: Long, generation: Long = playerGeneration.get()) {
        scope.launch {
            playerMutex.withLock {
                if (generation != playerGeneration.get()) return@withLock
                PluginManager.getEnabledPlayerPlugins().forEach { plugin ->
                    try { plugin.onUserSeek(positionMs) }
                    catch (error: Exception) { DesktopPluginLog.e(plugin.id, "Video plugin seek failed", error) }
                }
            }
        }
    }

    fun onVideoEnd(generation: Long = playerGeneration.get()) {
        if (!playerGeneration.compareAndSet(generation, generation + 1)) return
        currentVideo.get()?.takeIf { it.generation == generation }?.let { currentVideo.compareAndSet(it, null) }
        scope.launch {
            playerMutex.withLock {
                if (playerGeneration.get() != generation + 1) return@withLock
                PluginManager.getEnabledPlayerPlugins().forEach { plugin ->
                    try { plugin.onVideoEnd() }
                    catch (error: Exception) { DesktopPluginLog.e(plugin.id, "Video plugin end failed", error) }
                }
            }
        }
    }

    fun markSponsorSkipped(segmentId: String) = sponsorBlock.markAsSkipped(segmentId)
    fun sponsorProgressMarkers() = sponsorBlock.getProgressMarkers()
    fun sponsorSegments() = sponsorBlock.getSegments()
    fun sponsorActiveSegment() = sponsorBlock.getActiveSegment()
    fun sponsorContributionEnabled() = sponsorBlock.isCommunityContributionEnabled()
    internal suspend fun onSponsorSkipCompleted(generation: Long, snapshot: SponsorBlockVideoSnapshot,
        segmentId: String, startMs: Long, endMs: Long, categoryName: String, manual: Boolean): Unit = playerMutex.withLock {
        if (closing.get() || generation != playerGeneration.get()) return@withLock
        val record = buildSponsorBlockSkipRecord(snapshot, segmentId, categoryName, startMs, endMs,
            if (manual) SponsorBlockSkipTrigger.MANUAL else SponsorBlockSkipTrigger.AUTO, System.currentTimeMillis())
        SponsorBlockInsightStore.appendRecord(context, record)
        scope.launch {
            if (!closing.get() && generation == playerGeneration.get()) sponsorBlock.uploadViewedSegmentIfEnabled(segmentId)
        }
    }
    suspend fun submitSponsorSegment(generation: Long, bvid: String, cid: Long, durationSeconds: Float,
        startMs: Long, endMs: Long, category: String, actionType: String) = playerMutex.withLock {
        checkOpen()
        require(generation == playerGeneration.get()) { "视频已切换，请重新选择片段" }
        sponsorBlock.submitCommunitySegment(bvid, cid, durationSeconds, startMs, endMs, category, actionType)
    }
    suspend fun voteSponsorSegment(generation: Long, segmentId: String, vote: Int) = playerMutex.withLock {
        checkOpen()
        require(generation == playerGeneration.get()) { "视频已切换，请重新选择片段" }
        sponsorBlock.voteOnCommunitySegment(segmentId, vote)
    }
    fun resetJsonStats(id: String? = null) { checkOpen(); JsonPluginManager.resetStats(id) }
    suspend fun previewJsonUrl(url: String): Result<JsonRulePlugin> = JsonPluginManager.previewFromUrl(url)
    suspend fun importJsonUrl(url: String): Result<JsonRulePlugin> = configurationChange { JsonPluginManager.importFromUrl(url) }
    suspend fun importJsonText(text: String): Result<JsonRulePlugin> = configurationChange { JsonPluginManager.importFromText(text) }
    fun setJsonEnabled(id: String, enabled: Boolean) { checkOpen(); JsonPluginManager.setEnabled(id, enabled) }
    fun removeJsonPlugin(id: String) {
        checkOpen()
        require(jsonPlugins.value.any { it.plugin.id == id }) { "插件不存在" }
        JsonPluginManager.removePlugin(id)
    }
    fun updateJsonPlugin(plugin: JsonRulePlugin) { checkOpen(); JsonPluginManager.updatePlugin(plugin) }
    fun exportJsonPlugin(id: String): String = json.encodeToString(
        jsonPlugins.value.firstOrNull { it.plugin.id == id }?.plugin ?: error("插件不存在"))

    /** Root stops playback first; this returns only when original jobs can no longer write. */
    suspend fun shutdownForRestore(): Unit = withContext(NonCancellable) { shutdownMutex.withLock {
        if (stopped) return@withLock
        closing.set(true)
        // Retire/join the single original Home owner before its global backing is frozen.
        recommendations.shutdownForRestore()
        beforeStoreFreeze()
        playerGeneration.incrementAndGet()
        scope.coroutineContext[Job]?.cancelAndJoin()
        jsPlugins.shutdownForRestore()
        subscriptions.shutdownForRestore()
        packages.shutdownForRestore()
        DesktopSkinVideoRegistry.shutdownForRestore()
        enhancementConfiguration.flushAndClose()
        configurationMutex.withLock {
            playerMutex.withLock {
                DesktopPluginScopeRegistry.shutdown()
                // Also close a never-enabled provider; restore must not leave any constructor-owned transport behind.
                googleCast.onDisable()
                PluginManager.getEnabledPlugins(Plugin::class).forEach { plugin ->
                    if (plugin === googleCast) return@forEach
                    try { plugin.onDisable() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { DesktopPluginLog.e(plugin.id, "Plugin shutdown failed", error) }
                }
                castProxyConsumer.close()
                LocalProxyServer.stopAndClear()
                discovery?.freezeWritesForRestore()
                store.freezeWrites()
                stopped = true
            }
        }
    } }

    override fun close() {
        if (closing.compareAndSet(false, true)) {
            CoroutineScope(Dispatchers.Main).launch { shutdownForRestore() }
        }
    }
}

private fun desktopJsWorkerResources(): Path {
    System.getProperty("compose.application.resources.dir")?.takeIf { it.isNotBlank() }?.let {
        return Path.of(it).resolve("js-engine")
    }
    return Path.of(requireNotNull(System.getProperty("bilipai.js.workerResources")) {
        "The verified JS plugin runtime is missing from this application."
    })
}

internal fun initializeDesktopGoogleCastDefault(store: DesktopPluginStore) {
    val key = booleanPreferencesKey("plugin_enabled_google_cast")
    if (store.snapshot("plugin_prefs").value[key] == null) {
        store.update("plugin_prefs", mapOf(key.name to kotlinx.serialization.json.JsonPrimitive(false)))
    }
}

/** Only the implemented signed candidate selection and diagnostics are advertised. */
private class DesktopPlaybackCdnPlugin(private val original: CdnRegionPlugin) : PlaybackCdnPlugin by original {
    override val description = "在当前授权的签名 CDN 候选中选线，并检测线路状态"
}

/** Configuration comes from the same live store used by Discovery's actual filter editor. */
private class DesktopFeedFilterPlugin(private val store: DesktopPluginStore) : FeedPlugin {
    override val id = "bilipai_feed_filter"
    override val name = "推荐流过滤"
    override val description = "时长/播放量/点赞率/标题关键词/屏蔽用户/白名单"
    override val version = "1.0.0"
    override val author = "qyo123oyq"
    override fun shouldShowItem(item: VideoItem): Boolean = shouldShowItem(item, FeedKind.HOME_RECOMMEND)
    override fun shouldShowItem(item: VideoItem, feedKind: FeedKind): Boolean {
        val config = store.feedFilterConfig.value
        fun pattern(text: String) = DesktopFeedFilterEditor.parseBanWordToRegex(text)?.let { Regex(it, RegexOption.IGNORE_CASE) }
        return shouldShowFeedItem(config, item, feedKind, pattern(config.banWordForRecommend), pattern(config.banWordForZone))
    }
}
