package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.AiAudioInfo
import com.android.purebilibili.data.model.response.AiAudioItem
import com.android.purebilibili.data.model.response.ViewInfo
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState
import com.bilipai.desktop.player.PlayerState
import com.bilipai.desktop.player.PlayerTrack
import kotlin.test.*

class DesktopWindowsVideoAudioSelectionTest {
    private fun success(language: String? = null) = VideoPlaybackUiState.Success(
        info = ViewInfo(bvid = "BVfixture", aid = 7L, cid = 22L), playUrl = "",
        aiAudio = AiAudioInfo(items = listOf(AiAudioItem("en", "英语"), AiAudioItem("ja", "日语"))),
        currentAudioLang = language,
    )

    @Test fun originalAudioIsExplicitAndUnknownLanguageCannotBecomeARequest() {
        val value = success("en")
        assertEquals(listOf(null, "en", "ja"), desktopWindowsAudioLanguageOptions(value).map { it.language })
        assertNull(resolveDesktopWindowsAudioLanguageSelection(value, "de", PlayerState()))
        assertNull(resolveDesktopWindowsAudioLanguageSelection(value, "en", PlayerState()))
        assertNull(resolveDesktopWindowsAudioLanguageSelection(success(""), null, PlayerState()))
        assertNull(resolveDesktopWindowsAudioLanguageSelection(value.copy(aiAudio = null), null, PlayerState()))
        assertNull(resolveDesktopWindowsAudioLanguageSelection(value.copy(isQualitySwitching = true), null, PlayerState()))
    }

    @Test fun languageReplacementKeepsActualPartPositionAndPauseIntent() {
        val selected = assertNotNull(resolveDesktopWindowsAudioLanguageSelection(success(), "en",
            PlayerState(positionSeconds = 42.125, paused = true, nativePaused = false)))
        assertEquals("BVfixture", selected.request.bvid)
        assertEquals(22L, selected.request.cid)
        assertEquals(42_125L, selected.positionMs)
        assertEquals(false, selected.request.autoPlay)
        assertTrue(selected.request.force)
        assertEquals("en", selected.request.audioLang)
        assertNull(assertNotNull(resolveDesktopWindowsAudioLanguageSelection(success("en"), null, PlayerState())).request.audioLang)
        assertFalse(assertNotNull(resolveDesktopWindowsAudioLanguageSelection(success(), "en", PlayerState(ended = true))).request.autoPlay!!)
    }

    @Test fun invalidReadbackAndMalformedMetadataAreNotSelectable() {
        for (state in listOf(PlayerState(positionSeconds = Double.NaN), PlayerState(positionSeconds = -1.0), PlayerState(loading = true)))
            assertNull(resolveDesktopWindowsAudioLanguageSelection(success(), "en", state))
        val value = success().copy(aiAudio = AiAudioInfo(items = listOf(
            AiAudioItem("en", ""), AiAudioItem("en", "duplicate"), AiAudioItem(" ", "bad"),
            AiAudioItem("en\n", "bad"), AiAudioItem("x".repeat(65), "bad"))))
        assertEquals(listOf("原声", "en"), desktopWindowsAudioLanguageOptions(value).map { it.label })
    }

    @Test fun nativeTrackIdsAndLanguageCodesStaySeparate() {
        val state = PlayerState(tracks = listOf(PlayerTrack(1, "video"), PlayerTrack(2, "audio", language = "en", selected = true),
            PlayerTrack(3, "audio", title = "Commentary", language = "ja"), PlayerTrack(3, "audio"), PlayerTrack(4, "sub"), PlayerTrack(0, "audio")))
        assertEquals(listOf(2, 3), desktopWindowsNativeAudioTracks(state).map { it.id })
        assertEquals("Commentary · ja", desktopWindowsNativeAudioTrackLabel(state.tracks[2]))
        assertEquals("音轨 8", desktopWindowsNativeAudioTrackLabel(PlayerTrack(8, "audio")))
        assertTrue(desktopWindowsNativeAudioTracks(PlayerState()).isEmpty())
    }
}
