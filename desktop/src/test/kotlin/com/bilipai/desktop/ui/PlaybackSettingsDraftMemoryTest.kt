package com.bilipai.desktop.ui

import com.android.purebilibili.feature.video.playback.audio.*
import com.bilipai.desktop.*
import com.bilipai.desktop.data.*
import com.bilipai.desktop.data.PlaybackSource as ResolvedSource
import com.bilipai.desktop.player.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.swing.Swing
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlin.test.*

class PlaybackSettingsDraftMemoryTest {
    @Test fun `delayed actual controller audio selection survives draft save in a fresh disk reader`(): Unit {
        val root = Files.createTempDirectory("bp-draft-memory-")
        Files.writeString(root.resolve("fixture-owner.json"), "{\"owner\":\"playback-settings-draft-memory\"}")
        val file = root.resolve("player-settings.json")
        val store = PlayerPreferencesStore(file)
        var preferences = PlayerPreferences(lastSelectedAudioQuality = AUDIO_QUALITY_AUTO).normalized()
        store.save(preferences)
        val writer = DesktopPlayerPreferencesWriter(store::save)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        val player = MpvPlayer() // Unattached real source actor: no HWND, libmpv session or media decoding.
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val source = object : DesktopPlaybackDataSource {
            override suspend fun videoDetails(bvid: String) = VideoDetails(bvid, 11, "fixture", "", "", "", 0, 0,
                listOf(VideoPart(12, "P1", 90)))
            override suspend fun related(bvid: String) = emptyList<VideoCard>()
            override suspend fun playback(details: VideoDetails, index: Int, quality: Int, codecOverride: String?,
                forceRefresh: Boolean) = ResolvedSource("file:///C:/fixture-unattached.mp4", null, "fixture", "", quality = quality)
            override suspend fun playbackConfigured(details: VideoDetails, index: Int, quality: Int,
                playbackPreferences: PlayerPreferences, blockedVideoCodecs: Set<String>, codecOverride: String?,
                forceRefresh: Boolean): ResolvedSource {
                if (playbackPreferences.defaultAudioQuality == AUDIO_QUALITY_HI_RES) {
                    started.complete(Unit); gate.await()
                }
                return playback(details, index, quality, codecOverride, forceRefresh)
            }
        }
        val repository = DesktopRepository(DesktopSessionStore(root.resolve("account.json"), persistent = false))
        val controller = DesktopPlaybackController(repository, player, null, null, DesktopLibrary(root) { false },
            { preferences }, scope, dataSource = source, onRememberAudioQuality = { value ->
                preferences = preferences.copy(lastSelectedAudioQuality = value).normalized()
                assertTrue(writer.submit(preferences))
            })
        try {
            onSwing { controller.open(VideoCard("BV-FIXTURE", "fixture", "", "", 0, 90)) }
            await(controller) { !it.opening && it.details != null }
            val owner = player.currentSourceVersion
            onSwing { controller.selectAudioQuality(AUDIO_QUALITY_HI_RES) }
            runBlocking { withTimeout(3_000) { started.await() } }
            lateinit var draft: PlayerPreferences
            onSwing {
                // Same remember(preferences) snapshot the dialog opens while transport is still pending.
                draft = preferences.copy(defaultAudioQuality = AUDIO_QUALITY_DOLBY,
                    hardwareDecodeEnabled = false, rememberLastSpeed = false, defaultSpeed = 1.5,
                    videoCodecPreference = "av01", playbackMode = PlaybackMode.REPEAT_ONE)
                assertEquals(AUDIO_QUALITY_AUTO, draft.lastSelectedAudioQuality)
            }
            gate.complete(Unit)
            await(controller) { !it.opening }
            onSwing {
                assertEquals(owner, player.currentSourceVersion)
                assertEquals(AUDIO_QUALITY_HI_RES, preferences.lastSelectedAudioQuality)
                // Proven baseline failure using the original save expression and the actual disk format.
                val baselineFile = root.resolve("baseline-stale-settings.json")
                PlayerPreferencesStore(baselineFile).save(draft.copy(
                    speed = if (draft.rememberLastSpeed) draft.speed else draft.defaultSpeed).normalized())
                assertEquals(AUDIO_QUALITY_AUTO, PlayerPreferencesStore(baselineFile).read().lastSelectedAudioQuality)
                preferences = resolvePlaybackSettingsDraftSave(draft, preferences)
                assertTrue(writer.submit(preferences))
            }
            runBlocking { writer.flushAndClose() }
            val restored = PlayerPreferencesStore(file).read()
            assertEquals(AUDIO_QUALITY_HI_RES, restored.lastSelectedAudioQuality)
            assertEquals(AUDIO_QUALITY_DOLBY, restored.defaultAudioQuality)
            assertEquals(1.5, restored.defaultSpeed)
            assertEquals(1.5, restored.speed)
            assertFalse(restored.hardwareDecodeEnabled)
            assertEquals("av01", restored.videoCodecPreference)
            assertEquals(PlaybackMode.REPEAT_ONE, restored.playbackMode)
            assertTrue(java.awt.Window.getWindows().none { it.isDisplayable })
        } finally {
            gate.complete(Unit)
            onSwing { controller.close() }; scope.cancel(); player.close()
            runBlocking { writer.flushAndClose() }
        }
    }

    @Test fun `save keeps original remembered speed contract and normalizes latest audio memory`(): Unit {
        val latest = PlayerPreferences(lastSelectedAudioQuality = 999, defaultAudioQuality = AUDIO_QUALITY_HI_RES)
        val draft = PlayerPreferences(lastSelectedAudioQuality = AUDIO_QUALITY_DOLBY,
            defaultAudioQuality = AUDIO_QUALITY_AUTO, speed = 1.75, defaultSpeed = 1.25, rememberLastSpeed = true,
            speedOptions = listOf(1.0, 1.25, 1.75), volume = 500.0)
        val saved = resolvePlaybackSettingsDraftSave(draft, latest)
        assertEquals(AUDIO_QUALITY_AUTO, saved.lastSelectedAudioQuality)
        assertEquals(AUDIO_QUALITY_AUTO, saved.defaultAudioQuality)
        assertEquals(1.75, saved.speed)
        assertEquals(1.25, saved.defaultSpeed)
        assertEquals(100.0, saved.volume)
        assertEquals(999, latest.lastSelectedAudioQuality)
        assertEquals(AUDIO_QUALITY_DOLBY, draft.lastSelectedAudioQuality)
    }

    private fun await(controller: DesktopPlaybackController, predicate: (DesktopPlaybackState) -> Boolean) =
        runBlocking { withTimeout(3_000) { controller.state.first { predicate(it) } } }
    private fun onSwing(action: () -> Unit) {
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait { try { action() } catch (caught: Throwable) { failure = caught } }
        failure?.let { throw it }
    }
}
