package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.BgmInfo
import com.android.purebilibili.data.model.response.ViewInfo
import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.android.purebilibili.feature.video.playback.session.PlaybackSessionStore
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState
import kotlinx.coroutines.Job
import kotlin.test.*

class DesktopOriginalVideoBgmTest {
    private val source = PlaybackRequest.create("BV1music", aid = 1L, cid = 11L)
    private val song = BgmInfo(musicId = "song1", musicTitle = "原音乐")
    private fun state(cid: Long = 11L, bgm: BgmInfo? = null, list: List<BgmInfo> = emptyList()) =
        VideoPlaybackUiState.Success(info = ViewInfo(bvid = source.bvid, aid = source.aid, cid = cid),
            playUrl = "", bgmInfo = bgm, bgmInfoList = list)

    private fun session() = PlaybackSessionStore().also {
        it.beginLoadRequest(source)
        it.updateCurrentMedia(cid = source.cid)
    }

    @Test fun originalRequestAndCommittedRawSuccessAreBothRequired() {
        val store = PlaybackSessionStore()
        val load = store.beginLoadRequest(source)
        val request = DesktopOriginalVideoBgmRequest(source.bvid, source.cid, load.requestToken)
        assertFalse(desktopOriginalVideoBgmRequestIsCurrent(request, request, store.state.value,
            state(), true, false))
        store.updateCurrentMedia(cid = source.cid)
        assertTrue(desktopOriginalVideoBgmRequestIsCurrent(request, request, store.state.value,
            state(), true, false))
        assertFalse(desktopOriginalVideoBgmRequestIsCurrent(request, request, store.state.value,
            state(cid = 12L), true, false))
        assertFalse(desktopOriginalVideoBgmRequestIsCurrent(request, request, store.state.value,
            state().copy(info = ViewInfo(bvid = "BV2foreign", cid = 11L)), true, false))
    }

    @Test fun delayedResponseForSameBvCidCannotCrossANewLoadToken() {
        val store = session()
        val old = DesktopOriginalVideoBgmRequest(source.bvid, source.cid, store.state.value.currentLoadRequestToken)
        val next = store.beginLoadRequest(source.copy(force = true))
        val current = DesktopOriginalVideoBgmRequest(source.bvid, source.cid, next.requestToken)
        assertFalse(desktopOriginalVideoBgmRequestIsCurrent(old, old, store.state.value, state(), true, false))
        assertTrue(desktopOriginalVideoBgmRequestIsCurrent(current, current, store.state.value, state(), true, false))
    }

    @Test fun sameTokenPartRoundTripDoesNotReviveOldPlayerInfoInvocation() {
        val store = session()
        val token = store.state.value.currentLoadRequestToken
        val old = DesktopOriginalVideoBgmRequest(source.bvid, 11L, token)
        store.updateCurrentMedia(cid = 12L)
        assertFalse(desktopOriginalVideoBgmRequestIsCurrent(old, old, store.state.value, state(12L), true, false))
        store.updateCurrentMedia(cid = 11L)
        val returned = DesktopOriginalVideoBgmRequest(source.bvid, 11L, token)
        assertFalse(desktopOriginalVideoBgmRequestIsCurrent(old, returned, store.state.value, state(), true, false))
        assertTrue(desktopOriginalVideoBgmRequestIsCurrent(returned, returned, store.state.value, state(), true, false))
    }

    @Test fun pendingPartChangeRejectsLateOldResponseBeforeCidCommits() {
        val store = session()
        val request = DesktopOriginalVideoBgmRequest(source.bvid, source.cid, store.state.value.currentLoadRequestToken)
        assertFalse(desktopOriginalVideoBgmRequestIsCurrent(request, request, store.state.value,
            state().copy(isQualitySwitching = true), true, switchingPart = true))
        // A quality-only transition does not change the BGM source.
        assertTrue(desktopOriginalVideoBgmRequestIsCurrent(request, request, store.state.value,
            state().copy(isQualitySwitching = true), true, switchingPart = false))
    }

    @Test fun cancelledActualCallerCannotPublishEvenWhenAllSourceFieldsMatch() {
        val store = session()
        val request = DesktopOriginalVideoBgmRequest(source.bvid, source.cid, store.state.value.currentLoadRequestToken)
        val caller = Job()
        caller.cancel()
        assertFalse(desktopOriginalVideoBgmRequestIsCurrent(request, request, store.state.value,
            state(), caller.isActive, false))
    }

    @Test fun successfulResultRemainsAfterCallerNaturallyCompletes() {
        val store = session()
        val request = DesktopOriginalVideoBgmRequest(source.bvid, source.cid, store.state.value.currentLoadRequestToken)
        val result = DesktopOriginalVideoBgmResult(request, song, emptyList())
        val caller = Job()
        assertTrue(desktopOriginalVideoBgmRequestIsCurrent(request, request, store.state.value,
            state(bgm = song), caller.isActive, false))
        caller.complete()
        assertFalse(caller.isActive)
        assertSame(result, desktopOriginalVideoBgmForOwner(result, request, store.state.value,
            state(bgm = song), false))
    }

    @Test fun resultCannotBeRelabelledByAnotherEqualRequestOrDifferentRawFields() {
        val store = session()
        val request = DesktopOriginalVideoBgmRequest(source.bvid, source.cid, store.state.value.currentLoadRequestToken)
        val other = DesktopOriginalVideoBgmRequest(request.bvid, request.cid, request.requestToken)
        val result = DesktopOriginalVideoBgmResult(request, song, listOf(song))
        assertNull(desktopOriginalVideoBgmForOwner(result, other, store.state.value, state(bgm = song, list = listOf(song)), false))
        assertNull(desktopOriginalVideoBgmForOwner(result, request, store.state.value, state(bgm = song), false))
        assertNull(desktopOriginalVideoBgmForOwner(result, request, store.state.value, state(list = listOf(song)), false))
        assertNull(desktopOriginalVideoBgmForOwner(result, request, store.state.value, null, false))
    }

    @Test fun sourceInvalidationAndUnknownIdentifiersFailClosed() {
        val store = session()
        val request = DesktopOriginalVideoBgmRequest(source.bvid, source.cid, store.state.value.currentLoadRequestToken)
        val result = DesktopOriginalVideoBgmResult(request, song, emptyList())
        store.clearCurrentMedia()
        assertNull(desktopOriginalVideoBgmForOwner(result, request, store.state.value, state(bgm = song), false))
        for (bad in listOf(DesktopOriginalVideoBgmRequest("", 11L, 1L), DesktopOriginalVideoBgmRequest(source.bvid, 0L, 1L))) {
            assertFalse(desktopOriginalVideoBgmRequestIsCurrent(bad, bad, store.state.value, state(), true, false))
        }
    }

    @Test fun failedPartSwitchMayRestoreOnlyItsOriginalCurrentPayload() {
        val store = session()
        val request = DesktopOriginalVideoBgmRequest(source.bvid, source.cid, store.state.value.currentLoadRequestToken)
        val result = DesktopOriginalVideoBgmResult(request, song, listOf(song))
        assertNull(desktopOriginalVideoBgmForOwner(result, request, store.state.value, state().copy(isQualitySwitching = true), true))
        assertSame(result, desktopOriginalVideoBgmForOwner(result, request, store.state.value,
            state(bgm = song, list = listOf(song)), false))
    }

    @Test fun sourceStampCopiesTransportListAndRetainsOriginalSingleFallback() {
        val store = session()
        val request = DesktopOriginalVideoBgmRequest(source.bvid, source.cid, store.state.value.currentLoadRequestToken)
        val transport = mutableListOf(song)
        val result = DesktopOriginalVideoBgmResult(request, song, transport)
        transport.clear()
        assertEquals(listOf(song), result.bgmInfoList)
        val single = DesktopOriginalVideoBgmResult(request, song, emptyList())
        assertSame(single, desktopOriginalVideoBgmForOwner(single, request, store.state.value, state(bgm = song), false))
        assertEquals(source.bvid, single.bvid)
        assertEquals(source.cid, single.cid)
        assertEquals(store.state.value.currentLoadRequestToken, single.requestToken)
    }
}
