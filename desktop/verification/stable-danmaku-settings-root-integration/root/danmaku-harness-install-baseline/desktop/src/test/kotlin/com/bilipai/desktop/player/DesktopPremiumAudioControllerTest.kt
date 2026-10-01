package com.bilipai.desktop.player

import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.playback.audio.*
import com.bilipai.desktop.DesktopLibrary
import com.bilipai.desktop.DesktopPlaybackController
import com.bilipai.desktop.DesktopPlaybackDataSource
import com.bilipai.desktop.data.*
import com.bilipai.desktop.data.PlaybackSource as ResolvedSource
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities
import kotlin.test.*

/** The real controller observes actual mpv end-file ABI emitted by the dormant C API actor. */
class DesktopPremiumAudioControllerTest {
    @Test fun `actual AO init failure chooses original cached AAC and retains owner CID pause position and user intent`() = runBlocking {
        Fixture().use { f ->
            f.open()
            val owner = f.player.currentSourceVersion
            PremiumRecoveryActor(f.player).use { actor ->
                f.loaded(actor)
                swing { actor.fail(-14) }
                f.await { f.controller.currentCastSource(owner)?.audioUrl?.endsWith("aac-high") == true }
                val source = assertNotNull(f.controller.currentCastSource(owner))
                assertEquals(AUDIO_QUALITY_HI_RES, source.audioSelection?.requestedPreferenceId)
                assertEquals(AUDIO_QUALITY_AUTO, source.audioSelection?.selectedPreferenceId)
                assertEquals(AudioFallbackReason.DECODER_ERROR, source.audioSelection?.fallbackReason)
                assertEquals(owner, f.player.currentSourceVersion)
                assertEquals(7L, f.controller.state.value.details?.pages?.single()?.cid)
                assertEquals(27.75, f.player.state.value.positionSeconds)
                assertTrue(f.player.state.value.paused)
                assertFalse(f.player.state.value.softwareDecodingRequested)
                assertEquals("https://fixture.invalid/hevc", f.player.currentSourceSnapshot()?.source?.videoUrl)
                assertEquals(emptyList(), f.remembered)
                assertEquals(AUDIO_QUALITY_HI_RES, f.preferences.defaultAudioQuality)
                assertEquals(0, f.cdnPlaybackErrors())
                assertEquals(0 to 0, f.recoveryBudget())
                swing { actor.drain() }
                @Suppress("UNCHECKED_CAST") val options = actor.native.loads.last()[4] as Map<String, String>
                assertEquals("https://fixture.invalid/aac-high", options["audio-files"])
                assertEquals("27.75", options["start"])
                assertEquals("yes", options["pause"])
            }
        }
    }

    @Test fun `same CID quality codec and network refresh keep AAC failure fallback until explicit reselection`() = runBlocking {
        Fixture().use { f ->
            f.open(); val owner = f.player.currentSourceVersion
            PremiumRecoveryActor(f.player).use { actor ->
                f.loaded(actor); swing { actor.fail(-14) }
                f.await { f.controller.currentCastSource(owner)?.audioSelection?.fallbackReason == AudioFallbackReason.DECODER_ERROR }
                swing { f.controller.switchQuality(64) }
                f.await { !f.controller.state.value.opening && f.requests.size >= 2 }
                assertEquals(AUDIO_QUALITY_AUTO, f.requests.last().preferences.defaultAudioQuality)
                assertEquals(AUDIO_QUALITY_HI_RES, f.controller.currentCastSource(owner)?.audioSelection?.requestedPreferenceId)
                assertEquals(AudioFallbackReason.DECODER_ERROR, f.controller.currentCastSource(owner)?.audioSelection?.fallbackReason)
                val previous = f.preferences
                f.preferences = previous.copy(videoCodecPreference = "avc1")
                swing { f.controller.onPlaybackPreferencesChanged(previous, f.preferences) }
                f.await { !f.controller.state.value.opening && f.requests.size >= 3 }
                assertEquals("avc1", f.controller.currentCastSource(owner)?.videoCodecFamily)
                assertEquals(AUDIO_QUALITY_AUTO, f.requests.last().preferences.defaultAudioQuality)
                swing { f.controller.retry() }
                f.await { !f.controller.state.value.opening && f.requests.size >= 4 }
                assertTrue(f.requests.last().forceRefresh)
                assertEquals(AudioFallbackReason.DECODER_ERROR, f.controller.currentCastSource(owner)?.audioSelection?.fallbackReason)
                swing { f.controller.selectAudioQuality(AUDIO_QUALITY_HI_RES) }
                f.await { !f.controller.state.value.opening && f.requests.size >= 5 }
                assertEquals(AUDIO_QUALITY_HI_RES, f.controller.currentCastSource(owner)?.audioSelection?.selectedPreferenceId)
                assertNull(f.controller.currentCastSource(owner)?.audioSelection?.fallbackReason)
                assertEquals(listOf(AUDIO_QUALITY_HI_RES), f.remembered)
                assertEquals(owner, f.player.currentSourceVersion)
            }
        }
    }

    @Test fun `new video reads requested premium setting after previous video temporarily fell back`() = runBlocking {
        Fixture().use { f ->
            f.open(); val old = f.player.currentSourceVersion
            PremiumRecoveryActor(f.player).use { actor ->
                f.loaded(actor); swing { actor.fail(-14) }
                f.await { f.controller.currentCastSource(old)?.audioSelection?.fallbackReason == AudioFallbackReason.DECODER_ERROR }
                swing { f.controller.open(VideoCard("BV-next", "Next", "", "", 0, 120)) }
                f.await { !f.controller.state.value.opening && f.controller.state.value.details?.bvid == "BV-next" }
                val next = f.player.currentSourceVersion
                assertTrue(next > old)
                assertEquals(AUDIO_QUALITY_HI_RES, f.controller.currentCastSource(next)?.audioSelection?.selectedPreferenceId)
                assertNull(f.controller.currentCastSource(next)?.audioSelection?.fallbackReason)
            }
        }
    }

    @Test fun `missing AAC pauses only this owned failed source without pretending fallback succeeded`() = runBlocking {
        Fixture(includeAac = false).use { f ->
            f.open(); val owner = f.player.currentSourceVersion
            PremiumRecoveryActor(f.player).use { actor ->
                f.loaded(actor); swing { actor.fail(-14) }
                f.await { f.controller.state.value.error?.contains("没有可回退") == true }
                assertEquals(AUDIO_QUALITY_HI_RES, f.controller.currentCastSource(owner)?.audioSelection?.selectedPreferenceId)
                assertTrue(f.player.state.value.paused)
                assertEquals(1, f.requests.size)
                assertEquals(emptyList(), f.remembered)
                assertFalse(f.controller.state.value.recovering)
                val foreign = f.player.loadVersioned(PlaybackSource("foreign.avi", startPaused = false))
                assertFalse(f.player.pauseIfSourceVersion(owner))
                assertEquals(foreign, f.player.currentSourceVersion)
                assertFalse(f.player.state.value.paused)
            }
        }
    }

    @Test fun `changed account rejects an old source AO failure and stale preferences`() = runBlocking {
        Fixture().use { f ->
            f.open(); val owner = f.player.currentSourceVersion
            PremiumRecoveryActor(f.player).use { actor ->
                f.loaded(actor)
                swing {
                    f.epoch.incrementAndGet()
                    actor.fail(-14)
                    f.controller.selectAudioQuality(AUDIO_QUALITY_AUTO)
                }
                delay(80)
                assertNull(f.controller.currentCastSource(owner))
                assertEquals("https://fixture.invalid/flac", f.player.currentSourceSnapshot()?.source?.audioUrl)
                assertEquals(1, f.requests.size)
                assertEquals(emptyList(), f.remembered)
            }
        }
    }

    @Test fun `unknown video output failure never impersonates premium audio recovery`() = runBlocking {
        Fixture().use { f ->
            f.open(); val owner = f.player.currentSourceVersion
            PremiumRecoveryActor(f.player).use { actor ->
                f.loaded(actor)
                var failure: PlayerFailure? = null
                swing { failure = actor.fail(-15) }
                f.await { f.controller.state.value.recovering && f.player.state.value.loading }
                assertEquals(PlayerFailureKind.VIDEO_OUTPUT, failure?.kind)
                assertEquals(AUDIO_QUALITY_HI_RES, f.controller.currentCastSource(owner)?.audioSelection?.selectedPreferenceId)
                assertEquals("https://fixture.invalid/flac", f.player.currentSourceSnapshot()?.source?.audioUrl)
                assertEquals(1, f.requests.size)
                assertEquals("正在重新载入视频", f.controller.state.value.recoveryMessage)
                assertEquals(1, f.cdnPlaybackErrors())
                assertEquals(1 to 0, f.recoveryBudget())
                assertFalse(f.player.state.value.softwareDecodingRequested)
                swing { actor.drain() }
                @Suppress("UNCHECKED_CAST") val options = actor.native.loads.last()[4] as Map<String, String>
                assertEquals("https://fixture.invalid/flac", options["audio-files"])
            }
        }
    }

    @Test fun `paired subtitle metadata rejects NUL before any native selection is mutated`() {
        val subtitle = Files.createTempFile("bilipai-premium-subtitle-", ".srt")
        try {
            Files.writeString(subtitle, "1\n00:00:00,000 --> 00:00:50,000\nfixture\n")
            MpvPlayer().use { player ->
                val owner = player.loadVersioned(PlaybackSource("fixture.avi"))
                val control = player.currentSubtitleControlVersion
                val track = com.android.purebilibili.feature.video.subtitle.SubtitleTrackMeta(
                    id = 1, lan = "zh", lanDoc = "fixture\u0000invalid", subtitleUrl = "https://fixture.invalid/subtitle.json")
                assertFailsWith<IllegalArgumentException> {
                    player.installSubtitlePair(owner, control, DesktopOnlineSubtitleAsset(subtitle, track), null,
                        com.android.purebilibili.feature.video.subtitle.SubtitleDisplayMode.PRIMARY_ONLY)
                }
                assertEquals(control, player.currentSubtitleControlVersion)
                assertEquals(owner, player.currentSourceVersion)
            }
        } finally { Files.deleteIfExists(subtitle) }
    }

    @Test fun `direct obsolete recovery cannot penalize CDN or consume a new owners budget`() = runBlocking {
        Fixture().use { f ->
            f.open(); val old = f.currentContext()
            PremiumRecoveryActor(f.player).use { actor ->
                f.loaded(actor)
                swing {
                    val failure = actor.fail(-15)
                    f.player.loadVersioned(PlaybackSource("foreign.avi", title = "Foreign"))
                    f.invokeRecovery(old, failure)
                }
                assertEquals(0, f.cdnPlaybackErrors())
                assertEquals(0 to 0, f.recoveryBudget())
                assertEquals("Foreign", f.player.state.value.sourceTitle)
            }
        }
    }

    @Test fun `direct prior account recovery cannot write health or consume video budget`() = runBlocking {
        Fixture().use { f ->
            f.open(); val old = f.currentContext()
            PremiumRecoveryActor(f.player).use { actor ->
                f.loaded(actor)
                swing {
                    val failure = actor.fail(-15)
                    f.epoch.incrementAndGet()
                    f.invokeRecovery(old, failure)
                }
                assertEquals(0, f.cdnPlaybackErrors())
                assertEquals(0 to 0, f.recoveryBudget())
                assertEquals("https://fixture.invalid/flac", f.player.currentSourceSnapshot()?.source?.audioUrl)
            }
        }
    }

    @Test fun `stale failure attempt cannot pause or recover a newer attempt of the same media owner`() {
        MpvPlayer().use { player ->
            val owner = player.loadVersioned(PlaybackSource("fixture.avi"))
            PremiumRecoveryActor(player).use { actor ->
                assertTrue(player.recoverSource(owner)); actor.drain(); actor.loaded()
                val failure = actor.fail(-14)
                assertTrue(player.recoverSource(owner, paused = false, expectedFailureAttemptId = failure.attemptId))
                assertFalse(player.pauseIfSourceVersion(owner, failure.attemptId))
                assertFalse(player.state.value.paused)
            }
        }
    }

    @Test fun `refresh metadata preservation only labels an actual standard AAC selection`() {
        val dash = settingsFixtureDash()
        val old = resolveAudioStreamSelection(dash, AUDIO_QUALITY_HI_RES).copy(fallbackReason = AudioFallbackReason.DECODER_ERROR)
        val standard = ResolvedSource("video", "aac", "fixture", "", audioSelection = resolveAudioStreamSelection(dash, AUDIO_QUALITY_AUTO))
        assertEquals(AUDIO_QUALITY_HI_RES, retainDesktopPremiumAudioFallback(standard, old).audioSelection?.requestedPreferenceId)
        assertEquals(AudioFallbackReason.DECODER_ERROR, retainDesktopPremiumAudioFallback(standard, old).audioSelection?.fallbackReason)
        val noAudio = standard.copy(audioSelection = resolveAudioStreamSelection(Dash(), AUDIO_QUALITY_AUTO))
        assertEquals(noAudio, retainDesktopPremiumAudioFallback(noAudio, old))
        assertEquals(standard, retainDesktopPremiumAudioFallback(standard, old.copy(fallbackReason = AudioFallbackReason.SPEED_INCOMPATIBLE)))
    }

    private data class Request(val preferences: PlayerPreferences, val forceRefresh: Boolean)
    private class Fixture(includeAac: Boolean = true) : AutoCloseable {
        val player = MpvPlayer()
        val epoch = AtomicLong()
        var preferences = PlayerPreferences(defaultAudioQuality = AUDIO_QUALITY_HI_RES)
        val requests = CopyOnWriteArrayList<Request>()
        val remembered = mutableListOf<Int>()
        private val repo = DesktopRepository(DesktopSessionStore.temporary())
        private val dash = settingsFixtureDash().let { if(includeAac) it else it.copy(audio = emptyList()) }
        private val source = object : DesktopPlaybackDataSource {
            override val sessionEpoch get() = epoch.get()
            override suspend fun videoDetails(bvid: String) = VideoDetails(bvid, 1, bvid, "", "", "", 0, 0, listOf(VideoPart(7, "P1", 120)))
            override suspend fun related(bvid: String) = emptyList<VideoCard>()
            override suspend fun playback(details: VideoDetails, index: Int, quality: Int, codecOverride: String?, forceRefresh: Boolean): ResolvedSource =
                error("Configured original policy path required")
            override suspend fun playbackConfigured(details: VideoDetails, index: Int, quality: Int, playbackPreferences: PlayerPreferences,
                blockedVideoCodecs: Set<String>, codecOverride: String?, forceRefresh: Boolean): ResolvedSource {
                requests.add(Request(playbackPreferences, forceRefresh))
                return repo.run { PlayUrlData(quality = quality, dash = dash).toPlaybackSource(details, quality,
                    codecOverride, playbackPreferences, blockedVideoCodecs)!! }
            }
        }
        private val failures = CopyOnWriteArrayList<Throwable>()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing + CoroutineExceptionHandler { _, failure -> failures.add(failure) })
        private val directory = Files.createTempDirectory("bilipai-premium-controller-")
        val controller = DesktopPlaybackController(repo, player, null, null, DesktopLibrary(directory) { false }, { preferences }, scope,
            dataSource = source, onRememberAudioQuality = { remembered.add(it) })
        suspend fun open() {
            swing { controller.open(VideoCard("BV-premium", "Fixture", "", "", 0, 120)) }
            await { !controller.state.value.opening && controller.state.value.details != null }
        }
        fun loaded(actor: PremiumRecoveryActor) = swing {
            check(player.recoverSource(player.currentSourceVersion)); actor.drain(); actor.loaded()
        }
        fun currentContext(): Any = requireNotNull(DesktopPlaybackController::class.java.getDeclaredField("current")
            .apply { isAccessible = true }.get(controller))
        fun invokeRecovery(context: Any, failure: PlayerFailure) {
            DesktopPlaybackController::class.java.getDeclaredMethod("recover", context.javaClass, PlayerFailure::class.java, PlayerState::class.java)
                .apply { isAccessible = true }.invoke(controller, context, failure, player.state.value)
        }
        @Suppress("UNCHECKED_CAST")
        fun cdnPlaybackErrors(): Int = (DesktopPlaybackController::class.java.getDeclaredField("health")
            .apply { isAccessible = true }.get(controller) as Map<String, com.android.purebilibili.feature.plugin.CdnCandidateHealth>)
            .values.sumOf { it.playbackErrorCount }
        fun recoveryBudget(): Pair<Int, Int> = (DesktopPlaybackController::class.java.getDeclaredField("budget")
            .apply { isAccessible = true }.get(controller) as DesktopPlaybackRecoveryBudget).let { it.retries to it.cdnSwitches }
        suspend fun await(condition: () -> Boolean) = withTimeout(4_000) {
            while(!withContext(Dispatchers.Swing) { condition() }) delay(5)
        }
        override fun close() {
            swing { controller.close() }; scope.cancel(); player.close()
            val target = directory.toAbsolutePath().normalize()
            check(target.startsWith(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()) &&
                target.fileName.toString().startsWith("bilipai-premium-controller-"))
            Files.walk(target).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { path ->
                check(path.toAbsolutePath().normalize().startsWith(target)); Files.deleteIfExists(path)
            } }
            check(failures.isEmpty()) { "Unexpected controller background failure: ${failures.first()}" }
        }
    }
}

private fun swing(block: () -> Unit) {
    if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block)
}
