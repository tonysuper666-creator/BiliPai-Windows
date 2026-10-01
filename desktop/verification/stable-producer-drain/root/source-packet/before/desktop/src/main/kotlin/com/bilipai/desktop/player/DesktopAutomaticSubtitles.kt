package com.bilipai.desktop.player

import com.android.purebilibili.data.model.response.PlayerInfoData
import com.android.purebilibili.feature.video.subtitle.*
import com.android.purebilibili.feature.video.viewmodel.resolveSubtitleTrackLoadDecision
import com.android.purebilibili.feature.video.viewmodel.buildSubtitleTrackBindingKey
import com.android.purebilibili.feature.video.viewmodel.shouldRetrySubtitleLoadWithPlayerInfo
import com.bilipai.desktop.data.DesktopCommunityRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Path
import java.security.MessageDigest

/** A platform document binding; the track is the original protocol model. */
internal data class DesktopOnlineSubtitleAsset(val file: Path, val track: SubtitleTrackMeta) {
    val nativeTitle: String get() {
        val suffix = MessageDigest.getInstance("SHA-256").digest(track.trackKey.toByteArray(Charsets.UTF_8))
            .take(6).joinToString("") { "%02x".format(it) }
        return "${track.lanDoc.ifBlank { track.lan }} [$suffix]"
    }
}

internal interface DesktopAutomaticSubtitleDataSource {
    suspend fun metadata(bvid: String, cid: Long): PlayerInfoData
    suspend fun import(track: SubtitleTrackMeta): Path
    /** Windows retains the original parsed cues with the native asset; legacy fixture bindings may omit them. */
    suspend fun cues(file: Path): List<SubtitleCue>? = null
}

internal class RepositoryAutomaticSubtitleDataSource(
    private val community: DesktopCommunityRepository,
    private val assets: DesktopSubtitleAssets,
) : DesktopAutomaticSubtitleDataSource {
    override suspend fun metadata(bvid: String, cid: Long) = community.playerMetadata(bvid, cid)
    override suspend fun import(track: SubtitleTrackMeta) = assets.import(track)
    override suspend fun cues(file: Path) = assets.cues(file)
}

internal interface DesktopAutomaticSubtitlePlayer {
    val controlVersion: Long
    fun owns(sourceVersion: Long): Boolean
    fun install(sourceVersion: Long, expectedControlVersion: Long, primary: DesktopOnlineSubtitleAsset?,
        secondary: DesktopOnlineSubtitleAsset?, mode: SubtitleDisplayMode): Boolean
}

internal class MpvAutomaticSubtitlePlayer(private val player: MpvPlayer) : DesktopAutomaticSubtitlePlayer {
    override val controlVersion get() = player.currentSubtitleControlVersion
    override fun owns(sourceVersion: Long) = player.currentSourceSnapshot()?.sourceVersion == sourceVersion
    override fun install(sourceVersion: Long, expectedControlVersion: Long, primary: DesktopOnlineSubtitleAsset?,
        secondary: DesktopOnlineSubtitleAsset?, mode: SubtitleDisplayMode) =
        player.installSubtitlePair(sourceVersion, expectedControlVersion, primary, secondary, mode)
}

internal data class DesktopAutomaticSubtitleState(
    val loading: Boolean = false,
    val tracks: List<SubtitleTrackMeta> = emptyList(),
    val mode: SubtitleDisplayMode = SubtitleDisplayMode.OFF,
    val error: String? = null,
)

/** Original language/AI/session and partial-load policies, with Windows source/account guards. */
internal class DesktopAutomaticSubtitles(
    private val source: DesktopAutomaticSubtitleDataSource,
    private val player: DesktopAutomaticSubtitlePlayer,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val sessionEpoch: () -> Long = { 0L },
) : AutoCloseable {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableState = MutableStateFlow(DesktopAutomaticSubtitleState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var generation = 0L
    private var closed = false
    private var epoch = sessionEpoch()
    private var previousSessionKey: String? = null
    private var previousMode = SubtitleDisplayMode.OFF
    private var binding: Binding? = null
    private var manualTrackOwner: Long? = null
    private data class Binding(val sourceVersion: Long, val epoch: Long, val controlVersion: Long, val primary: DesktopOnlineSubtitleAsset?,
        val secondary: DesktopOnlineSubtitleAsset?)
    private data class Loaded(val asset: DesktopOnlineSubtitleAsset, val cues: List<SubtitleCue>?)

    /** Host calls this on a credential epoch change, including replacement credentials for the same MID. */
    fun onSessionChanged() = synchronized(lock) { synchronizeEpoch(); Unit }

    fun load(bvid: String, cid: Long, sourceVersion: Long, preference: SubtitleAutoPreference, isMuted: Boolean): Job? = synchronized(lock) {
        synchronizeEpoch()
        if (closed || bvid.isBlank() || cid <= 0 || !player.owns(sourceVersion) || manualTrackOwner == sourceVersion)
            return@synchronized null
        job?.cancel()
        val token = ++generation
        val control = player.controlVersion
        val capturedEpoch = epoch
        mutableState.value = mutableState.value.copy(loading = true, error = null)
        val next = scope.launch(start = CoroutineStart.LAZY) {
            try {
                verifyCurrent(token, sourceVersion, capturedEpoch, control)
                val metadata = source.metadata(bvid, cid)
                verifyCurrent(token, sourceVersion, capturedEpoch, control)
                var tracks = mapPlayerInfoSubtitleTracks(metadata.subtitle?.subtitles.orEmpty())
                val languages = resolveDefaultSubtitleLanguages(tracks, metadata.subtitle?.lan)
                var primaryTrack = tracks.firstOrNull { it.lan == languages.primaryLanguage }
                var secondaryTrack = tracks.firstOrNull { it.lan == languages.secondaryLanguage && it.trackKey != primaryTrack?.trackKey }
                suspend fun loadOne(track: SubtitleTrackMeta?): Result<Loaded?> {
                    if (track == null) return Result.success(null)
                    return try {
                        verifyCurrent(token, sourceVersion, capturedEpoch, control)
                        val file = source.import(track)
                        verifyCurrent(token, sourceVersion, capturedEpoch, control)
                        val cues = source.cues(file)
                        verifyCurrent(token, sourceVersion, capturedEpoch, control)
                        Result.success(Loaded(DesktopOnlineSubtitleAsset(file, track), cues))
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { Result.failure(failure) }
                }
                val initial = coroutineScope {
                    val primary = async { loadOne(primaryTrack) }
                    val secondary = async { loadOne(secondaryTrack) }
                    primary.await() to secondary.await()
                }
                var primaryResult = initial.first
                var secondaryResult = initial.second
                // Exactly the upstream one-time refresh for auth/expired URL failures. Preserve the
                // already successful track, and rebind failures by exact original key then language.
                if (shouldRetrySubtitleLoadWithPlayerInfo(primaryResult.exceptionOrNull()?.message) ||
                    shouldRetrySubtitleLoadWithPlayerInfo(secondaryResult.exceptionOrNull()?.message)) {
                    verifyCurrent(token, sourceVersion, capturedEpoch, control)
                    val refreshed = try { source.metadata(bvid, cid) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { null }
                    verifyCurrent(token, sourceVersion, capturedEpoch, control)
                    val refreshedTracks = mapPlayerInfoSubtitleTracks(refreshed?.subtitle?.subtitles.orEmpty())
                    if (refreshedTracks.isNotEmpty()) {
                        tracks = refreshedTracks
                        val oldPrimary = primaryTrack
                        val retryPrimary = refreshedTracks.firstOrNull { bindingKey(it) == oldPrimary?.let(::bindingKey) }
                            ?: refreshedTracks.firstOrNull { it.lan == oldPrimary?.lan }
                        if (retryPrimary != null && primaryResult.isFailure) {
                            primaryTrack = retryPrimary
                            primaryResult = loadOne(retryPrimary)
                        }
                        val oldSecondary = secondaryTrack
                        if (oldSecondary != null) {
                            val retrySecondary = refreshedTracks.firstOrNull { bindingKey(it) == bindingKey(oldSecondary) }
                                ?: refreshedTracks.firstOrNull { it.lan == oldSecondary.lan && it.trackKey != primaryTrack?.trackKey }
                            if (retrySecondary != null && secondaryResult.isFailure) {
                                secondaryTrack = retrySecondary
                                secondaryResult = loadOne(retrySecondary)
                            }
                        }
                    }
                }
                verifyCurrent(token, sourceVersion, capturedEpoch, control)
                val primaryLoaded = primaryResult.getOrNull()
                val secondaryLoaded = secondaryResult.getOrNull()
                val chosen = if (primaryLoaded?.cues != null || secondaryLoaded?.cues != null ||
                    (primaryLoaded == null && secondaryLoaded == null)) {
                    val decision = resolveSubtitleTrackLoadDecision(primaryTrack?.lan.orEmpty(), primaryLoaded?.cues.orEmpty(),
                        primaryTrack?.let(::isLikelyAiSubtitleTrack) == true, secondaryTrack?.lan, secondaryLoaded?.cues.orEmpty(),
                        secondaryTrack?.let(::isLikelyAiSubtitleTrack) == true)
                    val available = listOfNotNull(primaryLoaded, secondaryLoaded)
                    available.firstOrNull { it.asset.track.lan == decision.primaryLanguage }?.asset to
                        available.firstOrNull { it.asset.track.lan == decision.secondaryLanguage }?.asset
                } else {
                    // Legacy platform fixtures supplying only native files have no cue-quality data.
                    // Actual repository documents always provide the original parsed cue list above.
                    if (primaryLoaded == null) secondaryLoaded?.asset to null else primaryLoaded.asset to secondaryLoaded?.asset
                }
                val policy = resolveSubtitlePreferenceSession(bvid, cid, chosen.first?.track?.lan, chosen.second?.track?.lan,
                    chosen.first?.track?.let(::isLikelyAiSubtitleTrack) == true,
                    chosen.second?.track?.let(::isLikelyAiSubtitleTrack) == true,
                    chosen.first != null, chosen.second != null, preference, isMuted)
                synchronized(lock) {
                    if (!valid(token, sourceVersion, capturedEpoch) || player.controlVersion != control) return@launch
                    val mode = resolveSubtitleDisplayModePreference(previousSessionKey, policy.key, previousMode, policy.initialMode)
                    if (!player.install(sourceVersion, control, chosen.first, chosen.second, mode)) return@launch
                    previousSessionKey = policy.key
                    previousMode = mode
                    binding = Binding(sourceVersion, capturedEpoch, player.controlVersion, chosen.first, chosen.second)
                    mutableState.value = DesktopAutomaticSubtitleState(tracks = tracks, mode = mode,
                        error = if (chosen.first == null && chosen.second == null &&
                            (primaryResult.isFailure || secondaryResult.isFailure)) "字幕加载失败，请手动重试。" else null)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                synchronized(lock) {
                    if (valid(token, sourceVersion, capturedEpoch)) mutableState.value = mutableState.value.copy(error = "字幕加载失败，请手动重试。")
                }
            } finally {
                synchronized(lock) {
                    if (valid(token, sourceVersion, capturedEpoch)) mutableState.value = mutableState.value.copy(loading = false)
                }
            }
        }
        job = next
        next.start()
        next
    }

    fun setDisplayMode(mode: SubtitleDisplayMode): Boolean = synchronized(lock) {
        synchronizeEpoch()
        val bound = binding ?: return@synchronized false
        if (closed || bound.epoch != epoch || !player.owns(bound.sourceVersion)) return@synchronized false
        val normalized = normalizeSubtitleDisplayMode(mode, bound.primary != null, bound.secondary != null)
        if (!player.install(bound.sourceVersion, player.controlVersion, bound.primary, bound.secondary, normalized))
            return@synchronized false
        previousMode = normalized
        binding = bound.copy(controlVersion = player.controlVersion)
        mutableState.value = mutableState.value.copy(mode = normalized)
        true
    }

    /** Call immediately before an actual manual track change, rather than when a menu is opened. */
    fun onUserTrackSelection(sourceVersion: Long) = synchronized(lock) {
        synchronizeEpoch()
        if (closed || !player.owns(sourceVersion)) return@synchronized
        manualTrackOwner = sourceVersion
        generation++
        job?.cancel()
        mutableState.value = mutableState.value.copy(loading = false)
    }

    private fun synchronizeEpoch() {
        val current = sessionEpoch()
        if (current == epoch) return
        val oldBinding = binding
        epoch = current
        generation++
        job?.cancel()
        binding = null
        manualTrackOwner = null
        previousSessionKey = null
        previousMode = SubtitleDisplayMode.OFF
        mutableState.value = DesktopAutomaticSubtitleState()
        // Retire only the automatic pair we still own, including an actor transaction not yet
        // processed. A newer native source or manual control version must remain untouched.
        if (oldBinding != null && player.owns(oldBinding.sourceVersion) && player.controlVersion == oldBinding.controlVersion)
            player.install(oldBinding.sourceVersion, oldBinding.controlVersion, null, null, SubtitleDisplayMode.OFF)
    }

    private fun bindingKey(track: SubtitleTrackMeta) = buildSubtitleTrackBindingKey(track.id, track.idStr, track.lan, track.subtitleUrl)

    private fun valid(token: Long, sourceVersion: Long, capturedEpoch: Long): Boolean {
        synchronizeEpoch()
        return !closed && generation == token && epoch == capturedEpoch && player.owns(sourceVersion)
    }

    private suspend fun verifyCurrent(token: Long, sourceVersion: Long, capturedEpoch: Long, control: Long) {
        currentCoroutineContext().ensureActive()
        val current = synchronized(lock) { valid(token, sourceVersion, capturedEpoch) && player.controlVersion == control }
        if (!current) throw CancellationException("Subtitle request no longer owns the playback session")
    }

    override fun close() = synchronized(lock) {
        closed = true
        generation++
        job?.cancel()
        scope.cancel()
        binding = null
        mutableState.value = mutableState.value.copy(loading = false)
    }
}
