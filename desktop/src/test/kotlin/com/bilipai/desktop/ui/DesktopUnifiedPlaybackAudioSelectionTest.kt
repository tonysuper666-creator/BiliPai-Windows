package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.VideoLoadError
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.playback.loader.*
import com.android.purebilibili.feature.video.playback.session.PlaybackSessionStore
import com.android.purebilibili.feature.video.usecase.VideoLoadResult
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState
import com.android.purebilibili.feature.video.viewmodel.resolveRequestedStartPositionMs
import com.android.purebilibili.core.player.PlaybackProgressManager
import com.bilipai.desktop.player.OwnedPlaybackSourceSnapshot
import com.bilipai.desktop.player.PlaybackSource
import com.bilipai.desktop.player.PlayerState
import com.bilipai.desktop.player.PlayerTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import javax.swing.SwingUtilities
import kotlin.test.*

/** Calls the SAME dispatcher consumed by the facade. No HWND, media IO, account or native actor is created. */
class DesktopUnifiedPlaybackAudioSelectionTest {
    private val raw = VideoPlaybackUiState.Success(info = ViewInfo(bvid = "BVfixture", aid = 7L, cid = 22L), playUrl = "",
        aiAudio = AiAudioInfo(items = listOf(AiAudioItem("en", "英语"), AiAudioItem("ja", "日语"))))
    private fun onUi(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else {
            var failure: Throwable? = null
            SwingUtilities.invokeAndWait { try { block() } catch (error: Throwable) { failure = error } }
            failure?.let { throw it }
        }
    }

    @Test fun realOriginalLoaderReceivesSelectedLanguageCurrentPartAndPausedIntent() = onUi {
        var inAdmission = false
        var checkpointed = false
        var selected: DesktopWindowsAudioLanguageSelection? = null
        var delivered: PlaybackLoadResult? = null
        val calls = mutableListOf<Triple<Long, String?, Boolean>>()
        val port = object : DesktopOriginalVideoLoadPort {
            override suspend fun loadVideo(bvid: String, aid: Long, cid: Long, defaultQuality: Int,
                audioQualityPreference: Int, videoCodecPreference: String, videoSecondCodecPreference: String,
                audioLang: String?, playWhenReady: Boolean, isAv1SupportedOverride: Boolean?,
                isHdrSupportedOverride: Boolean?, isDolbyVisionSupportedOverride: Boolean?, onProgress: (String) -> Unit): VideoLoadResult {
                assertEquals("BVfixture", bvid); assertEquals(7L, aid)
                calls += Triple(cid, audioLang, playWhenReady)
                return VideoLoadResult.Error(VideoLoadError.ApiError(412, "synthetic"), canRetry = false)
            }
        }
        val accepted = DesktopUnifiedPlaybackFacade.consumeAudioLanguage(raw, "en", { true },
            admit = { action -> inAdmission = true; try { action(); true } finally { inAdmission = false } },
            readNative = { assertTrue(inAdmission); PlayerState(positionSeconds = 42.125, paused = true) },
            checkpoint = { assertFalse(inAdmission); checkpointed = true },
            reload = { change ->
                assertFalse(inAdmission); assertTrue(checkpointed); selected = change
                delivered = runBlocking { PlaybackLoader.from(port).load(change.request, change.positionMs,
                    PlaybackLoadConfig(64, -1, "hev1", "avc1", change.request.autoPlay!!, true, false, false)) }
            })
        assertTrue(accepted)
        val change = assertNotNull(selected)
        val result = assertNotNull(delivered)
        assertEquals(listOf<Triple<Long, String?, Boolean>>(Triple(22L, "en", false)), calls)
        assertEquals(42_125L, result.cachedPositionMs)
        assertSame(change.request, result.request)
        assertFalse((result as PlaybackLoadResult.Error).canRetry)
    }

    @Test fun sameVersionReplacementAndSamePartLoadAbaCannotConsumeOldPopup() = onUi {
        val request = PlaybackRequest.create("BVfixture", 7L, 22L)
        val source = DesktopOriginalVideoAcceptedPublication(request,
            OwnedPlaybackSourceSnapshot(8L, PlaybackSource("file:///C:/synthetic-a.avi")))
        val replacement = DesktopOriginalVideoAcceptedPublication(request,
            OwnedPlaybackSourceSnapshot(8L, PlaybackSource("file:///C:/synthetic-b.avi")))
        val session = PlaybackSessionStore()
        val token = session.beginLoadRequest(request).requestToken
        session.updateCurrentMedia(cid = 22L)
        var currentSource = source
        var currentRaw: VideoPlaybackUiState = raw
        val current = { desktopWindowsAudioSelectionIdentityCurrent(source, currentSource, raw, currentRaw, token, session.state.value) }
        var loads = 0
        assertFalse(DesktopUnifiedPlaybackFacade.consumeAudioLanguage(raw, "en", current,
            { action -> action(); currentSource = replacement; true }, { PlayerState(positionSeconds = 3.0) },
            { fail("retired source cannot checkpoint") }, { loads++ }))
        currentSource = source
        currentRaw = raw.copy()
        assertFalse(current())
        currentRaw = raw
        session.beginLoadRequest(request.copy(force = true)); session.updateCurrentMedia(cid = 22L)
        assertFalse(current())
        assertEquals(0, loads)
    }

    @Test fun retirementDuringCheckpointRejectsOriginalReload() = onUi {
        var owned = true
        var checkpoints = 0
        var loads = 0
        assertFalse(DesktopUnifiedPlaybackFacade.consumeAudioLanguage(raw, "en", { owned },
            { action -> action(); true }, { PlayerState(positionSeconds = 12.0) },
            { checkpoints++; owned = false }, { loads++ }))
        assertEquals(1, checkpoints); assertEquals(0, loads)
    }

    @Test fun deniedAdmissionCancelledCheckpointAndUnlistedLanguageNeverReload() = onUi {
        var reads = 0; var checkpoints = 0; var loads = 0
        val read = { reads++; PlayerState() }
        val checkpoint: () -> Unit = { checkpoints++ }
        val reload: (DesktopWindowsAudioLanguageSelection) -> Unit = { loads++ }
        assertFalse(DesktopUnifiedPlaybackFacade.consumeAudioLanguage(raw, "en", { true }, { false }, read, checkpoint, reload))
        assertEquals(0, reads)
        assertFalse(DesktopUnifiedPlaybackFacade.consumeAudioLanguage(raw, "de", { true }, { it(); true }, read, checkpoint, reload))
        assertEquals(0, checkpoints)
        assertFalse(DesktopUnifiedPlaybackFacade.consumeAudioLanguage(raw, "en", { true }, { it(); true }, read,
            { throw CancellationException("retired caller") }, reload))
        assertEquals(0, loads)
    }

    @Test fun restoringOriginalLanguageIsNotSelectingANativeTrack() = onUi {
        var selected: DesktopWindowsAudioLanguageSelection? = null
        assertTrue(DesktopUnifiedPlaybackFacade.consumeAudioLanguage(raw.copy(currentAudioLang = "en"), null, { true },
            { it(); true }, { PlayerState(positionSeconds = 7.5) }, {}, { selected = it }))
        assertNull(assertNotNull(selected).request.audioLang)
        assertEquals(22L, selected!!.request.cid)
        assertEquals(true, selected!!.request.autoPlay)
    }

    @Test fun nativeTrackSelectionUsesActualCurrentCatalogAndInsideSourceAdmission() = onUi {
        var inAdmission = false
        val selected = mutableListOf<Int>()
        val state = PlayerState(ready = true, tracks = listOf(PlayerTrack(2, "audio", language = "en", selected = true),
            PlayerTrack(3, "audio", language = "ja"), PlayerTrack(4, "sub")))
        val admit: ((() -> Unit) -> Boolean) = { action -> inAdmission = true; try { action(); true } finally { inAdmission = false } }
        val apply: (Int) -> Boolean = { assertTrue(inAdmission); selected += it; true }
        assertTrue(DesktopUnifiedPlaybackFacade.consumeNativeAudioTrack(3, { true }, admit, { state }, apply))
        for (id in listOf(0, 2, 4, 99))
            assertFalse(DesktopUnifiedPlaybackFacade.consumeNativeAudioTrack(id, { true }, admit, { state }, apply))
        assertFalse(DesktopUnifiedPlaybackFacade.consumeNativeAudioTrack(3, { false }, admit, { state }, apply))
        assertFalse(DesktopUnifiedPlaybackFacade.consumeNativeAudioTrack(3, { true }, { false }, { state }, apply))
        assertFalse(DesktopUnifiedPlaybackFacade.consumeNativeAudioTrack(3, { true }, admit, { state.copy(ready = false) }, apply))
        assertFalse(DesktopUnifiedPlaybackFacade.consumeNativeAudioTrack(3, { true }, admit, { state.copy(loading = true) }, apply))
        assertFalse(DesktopUnifiedPlaybackFacade.consumeNativeAudioTrack(3, { true }, admit, { state.copy(error = "synthetic") }, apply))
        assertEquals(listOf(3), selected)
    }

    @Test fun backgroundCallerCannotMutateTheUiOwnedVmBeforeItsInvocationGuard() {
        assertFalse(SwingUtilities.isEventDispatchThread())
        assertFailsWith<IllegalStateException> { DesktopUnifiedPlaybackFacade.consumeAudioLanguage(raw, "en", { true },
            { fail("no admission on background caller") }, { fail("no read") }, { fail("no checkpoint") }, { fail("no reload") }) }
    }

    @Test fun originalCacheAndBothVmDecisionsKeepExplicitTwoSecondsZeroAndPausedNearEnd() = onUi {
        // A fresh non-singleton original Manager has only its own memory map: no Context, prefs, files or profile.
        val progress = PlaybackProgressManager()
        progress.savePosition("BVfixture", cid = 22L, positionMs = 42_000L)
        for (position in listOf(2_000L, 0L, 59_000L)) {
            progress.savePosition("BVfixture", cid = 22L, positionMs = 42_000L)
            assertEquals(42_000L, progress.getCachedPosition("BVfixture", 22L))
            var completed = false
            assertTrue(DesktopUnifiedPlaybackFacade.consumeAudioLanguage(raw, "en", { true },
                { it(); true }, { PlayerState(positionSeconds = position / 1_000.0, paused = true) },
                { progress.savePosition("BVfixture", cid = 22L, positionMs = position) }, { selection ->
                    val cached = progress.getCachedPosition("BVfixture", 22L)
                    if (position < 5_000L) assertEquals(42_000L, cached) // Reproduce the original short-progress policy.
                    val beforeLoad = resolveRequestedStartPositionMs(cached, selection.positionMs, selection.positionMs)
                    assertEquals(position, beforeLoad)
                    val loaded = runBlocking { PlaybackLoader { _, _ ->
                        VideoLoadResult.Error(VideoLoadError.ApiError(412, "synthetic"), canRetry = false)
                    }.load(selection.request, cached, PlaybackLoadConfig(64, -1, "hev1", "avc1", false, true, false, false)) }
                    val afterLoad = resolveRequestedStartPositionMs(loaded.cachedPositionMs, selection.positionMs, selection.positionMs)
                    assertEquals(position, afterLoad)
                    assertFalse(desktopWindowsShouldRestartPlaybackAtEnd(60_000L, afterLoad, selection.positionMs))
                    assertEquals(false, loaded.request.autoPlay)
                    completed = true
                }))
            assertTrue(completed)
        }
        assertEquals(42_000L, resolveRequestedStartPositionMs(42_000L, 2_000L))
        assertEquals(2_000L, resolveRequestedStartPositionMs(0L, 2_000L))
        assertTrue(desktopWindowsShouldRestartPlaybackAtEnd(60_000L, 59_000L, null))
        assertFalse(desktopWindowsShouldRestartPlaybackAtEnd(60_000L, 42_000L, null))
        assertFailsWith<IllegalArgumentException> { resolveRequestedStartPositionMs(42_000L, 0L, -1L) }
    }
}
