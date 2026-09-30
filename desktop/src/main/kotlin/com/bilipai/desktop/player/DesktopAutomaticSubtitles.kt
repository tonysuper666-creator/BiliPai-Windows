package com.bilipai.desktop.player

import com.android.purebilibili.data.model.response.PlayerInfoData
import com.android.purebilibili.feature.video.subtitle.*
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
}

internal class RepositoryAutomaticSubtitleDataSource(
    private val community: DesktopCommunityRepository,
    private val assets: DesktopSubtitleAssets,
) : DesktopAutomaticSubtitleDataSource {
    override suspend fun metadata(bvid: String, cid: Long) = community.playerMetadata(bvid, cid)
    override suspend fun import(track: SubtitleTrackMeta) = assets.import(track)
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

/** Original language/AI/session policy, with a Windows ownership guard around asynchronous work. */
internal class DesktopAutomaticSubtitles(
    private val source: DesktopAutomaticSubtitleDataSource,
    private val player: DesktopAutomaticSubtitlePlayer,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AutoCloseable {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableState = MutableStateFlow(DesktopAutomaticSubtitleState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var generation = 0L
    private var closed = false
    private var previousSessionKey: String? = null
    private var previousMode = SubtitleDisplayMode.OFF
    private var binding: Binding? = null
    private var manualTrackOwner: Long? = null
    private data class Binding(val sourceVersion: Long, val primary: DesktopOnlineSubtitleAsset?,
        val secondary: DesktopOnlineSubtitleAsset?)

    fun load(bvid: String, cid: Long, sourceVersion: Long, preference: SubtitleAutoPreference, isMuted: Boolean): Job? = synchronized(lock) {
        if (closed || bvid.isBlank() || cid <= 0 || !player.owns(sourceVersion) || manualTrackOwner == sourceVersion)
            return@synchronized null
        job?.cancel()
        val token = ++generation
        val control = player.controlVersion
        mutableState.value = mutableState.value.copy(loading = true, error = null)
        val next = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val metadata = source.metadata(bvid, cid)
                ensureActive()
                val tracks = mapPlayerInfoSubtitleTracks(metadata.subtitle?.subtitles.orEmpty())
                val languages = resolveDefaultSubtitleLanguages(tracks, metadata.subtitle?.lan)
                val primaryTrack = tracks.firstOrNull { it.lan == languages.primaryLanguage }
                val secondaryTrack = tracks.firstOrNull { it.lan == languages.secondaryLanguage && it.trackKey != primaryTrack?.trackKey }
                val documents = coroutineScope {
                    val primary = async { primaryTrack?.let { DesktopOnlineSubtitleAsset(source.import(it), it) } }
                    val secondary = async { secondaryTrack?.let { DesktopOnlineSubtitleAsset(source.import(it), it) } }
                    primary.await() to secondary.await()
                }
                ensureActive()
                val policy = resolveSubtitlePreferenceSession(bvid, cid, primaryTrack?.lan, secondaryTrack?.lan,
                    primaryTrack?.let(::isLikelyAiSubtitleTrack) == true,
                    secondaryTrack?.let(::isLikelyAiSubtitleTrack) == true,
                    primaryTrack != null, secondaryTrack != null, preference, isMuted)
                synchronized(lock) {
                    if (!valid(token, sourceVersion) || player.controlVersion != control) return@launch
                    val mode = resolveSubtitleDisplayModePreference(previousSessionKey, policy.key, previousMode, policy.initialMode)
                    if (!player.install(sourceVersion, control, documents.first, documents.second, mode)) return@launch
                    previousSessionKey = policy.key
                    previousMode = mode
                    binding = Binding(sourceVersion, documents.first, documents.second)
                    mutableState.value = DesktopAutomaticSubtitleState(tracks = tracks, mode = mode)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                // URLs/cookie-bearing transport messages never become product diagnostics.
                synchronized(lock) {
                    if (valid(token, sourceVersion)) mutableState.value = mutableState.value.copy(error = "字幕加载失败，请手动重试。")
                }
            } finally {
                synchronized(lock) {
                    if (valid(token, sourceVersion)) mutableState.value = mutableState.value.copy(loading = false)
                }
            }
        }
        job = next
        next.start()
        next
    }

    fun setDisplayMode(mode: SubtitleDisplayMode): Boolean = synchronized(lock) {
        val bound = binding ?: return@synchronized false
        if (closed || !player.owns(bound.sourceVersion)) return@synchronized false
        val normalized = normalizeSubtitleDisplayMode(mode, bound.primary != null, bound.secondary != null)
        if (!player.install(bound.sourceVersion, player.controlVersion, bound.primary, bound.secondary, normalized))
            return@synchronized false
        previousMode = normalized
        mutableState.value = mutableState.value.copy(mode = normalized)
        true
    }

    /** Call immediately before manual online/local track changes. Same-owner CDN recovery keeps that choice. */
    fun onUserTrackSelection(sourceVersion: Long) = synchronized(lock) {
        if (closed || !player.owns(sourceVersion)) return@synchronized
        manualTrackOwner = sourceVersion
        generation++
        job?.cancel()
        mutableState.value = mutableState.value.copy(loading = false)
    }

    private fun valid(token: Long, sourceVersion: Long) = !closed && generation == token && player.owns(sourceVersion)

    override fun close() = synchronized(lock) {
        closed = true
        generation++
        job?.cancel()
        scope.cancel()
        binding = null
        mutableState.value = mutableState.value.copy(loading = false)
        // Assets are owned by the app: libmpv can reopen them during same-owner surface reconstruction.
    }
}
