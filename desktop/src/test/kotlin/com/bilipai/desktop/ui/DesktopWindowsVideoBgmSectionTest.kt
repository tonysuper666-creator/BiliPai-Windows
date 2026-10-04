package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.android.purebilibili.feature.video.ui.section.resolveDisplayBgmList
import com.bilipai.desktop.audio.*
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopWindowsVideoBgmSectionTest {
    private fun result(request: DesktopOriginalVideoBgmRequest = DesktopOriginalVideoBgmRequest("BV1bgm", 11L, 7L)) =
        DesktopOriginalVideoBgmResult(request, BgmInfo(musicId = "song1"), emptyList())

    private fun requests(
        owned: () -> Boolean = { true },
        admit: ((() -> Unit) -> Boolean) = { action -> action(); true },
        detail: suspend (String, Long, Long) -> Result<BgmDetailData?> = { _, _, _ -> Result.success(null) },
        recommendations: suspend (String, Long, Long, Int, Int) -> Result<List<BgmRecommendVideo>> =
            { _, _, _, _, _ -> Result.success(emptyList()) },
    ) = DesktopWindowsVideoBgmRequests(owned, admit, detail, recommendations)

    @Test fun samePartAndTokenCannotReviveAReplacedMetadataInvocation() {
        val old = result()
        val replacement = result()
        val source = PlaybackRequest.create("BV1bgm", cid = 11L)
        assertTrue(desktopWindowsVideoBgmMatchesSource(old, old, source))
        assertFalse(desktopWindowsVideoBgmMatchesSource(old, replacement, source))
        assertFalse(desktopWindowsVideoBgmMatchesSource(old, null, source))
        assertFalse(desktopWindowsVideoBgmMatchesSource(old, old, source.copy(cid = 12L)))
        assertFalse(desktopWindowsVideoBgmMatchesSource(old, old, source.copy(bvid = "BV2")))
    }

    @Test fun singleToMultiPublicationKeepsItsOneOriginalInvocation() {
        val single = result()
        val songs = listOf(BgmInfo(musicId = "song1"), BgmInfo(musicId = "song2"))
        val multiple = DesktopOriginalVideoBgmResult(single.request, single.bgmInfo, songs)
        assertTrue(desktopWindowsVideoBgmMatchesSource(single, multiple, PlaybackRequest.create("BV1bgm", cid = 11L)))
        assertEquals(songs, resolveDisplayBgmList(multiple.bgmInfo, multiple.bgmInfoList))
        assertEquals(listOf(single.bgmInfo), resolveDisplayBgmList(single.bgmInfo, emptyList()))
    }

    @Test fun originalDetailAndPagingParametersReachOnlyTheRequiredPorts(): Unit = runBlocking {
        val calls = mutableListOf<List<Any>>()
        val detail = BgmDetailData(musicTitle = "actual song")
        val videos = listOf(BgmRecommendVideo(bvid = "BVrelated", cid = 33L))
        val owner = requests(
            detail = { musicId, aid, cid -> calls += listOf(musicId, aid, cid); Result.success(detail) },
            recommendations = { musicId, aid, cid, page, size ->
                calls += listOf(musicId, aid, cid, page, size); Result.success(videos)
            },
        )
        assertSame(detail, owner.getBgmDetail("song1", 101L, 11L).getOrThrow())
        assertEquals(videos, owner.getBgmRecommendVideos("song1", 101L, 11L, 3, 5).getOrThrow())
        assertEquals<List<List<Any>>>(listOf(listOf("song1", 101L, 11L), listOf("song1", 101L, 11L, 3, 5)), calls)
        owner.close()
    }

    @Test fun retiredSourceCannotStartEitherNetworkRequest(): Unit = runBlocking {
        var calls = 0
        val owner = requests(owned = { false },
            detail = { _, _, _ -> calls++; Result.success(null) },
            recommendations = { _, _, _, _, _ -> calls++; Result.success(emptyList()) })
        assertFailsWith<CancellationException> { owner.getBgmDetail("song", 1L, 11L) }
        assertFailsWith<CancellationException> { owner.getBgmRecommendVideos("song", 1L, 11L, 1, 5) }
        assertEquals(0, calls)
    }

    @Test fun lateSuccessfulDetailCannotPopulateTheNextPart(): Unit = runBlocking {
        withTimeout(3_000L) {
            var owned = true
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val owner = requests(owned = { owned }, detail = { _, _, _ ->
                entered.complete(Unit); release.await(); Result.success(BgmDetailData(musicTitle = "old part"))
            })
            val pending = async { owner.getBgmDetail("old", 1L, 11L) }
            entered.await(); owned = false; release.complete(Unit)
            assertFailsWith<CancellationException> { pending.await() }
            owner.close()
        }
    }

    @Test fun lateRecommendationPageCannotPopulateAClosedSelectionWindow(): Unit = runBlocking {
        withTimeout(3_000L) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val owner = requests(recommendations = { _, _, _, _, _ ->
                entered.complete(Unit); release.await(); Result.success(listOf(BgmRecommendVideo(bvid = "old")))
            })
            val pending = async { owner.getBgmRecommendVideos("song", 1L, 11L, 2, 5) }
            entered.await(); owner.close(); release.complete(Unit)
            assertFailsWith<CancellationException> { pending.await() }
        }
    }

    @Test fun completedOldPanelCannotBeRevivedByReturningToTheSamePart(): Unit = runBlocking {
        var current = true
        var calls = 0
        val owner = requests(owned = { current }, detail = { _, _, _ -> calls++; Result.success(null) })
        owner.close(); current = false; current = true
        assertFailsWith<CancellationException> { owner.getBgmDetail("song", 1L, 11L) }
        assertFalse(owner.withAdmission { error("A disposed panel cannot commit") })
        assertEquals(0, calls)
    }

    @Test fun finalAccountAdmissionIsRequiredForOriginalStatePublication() {
        var publications = 0
        val denied = requests(admit = { false })
        assertFailsWith<CancellationException> { denied.commitBgmDiscoveryState { publications++ } }
        assertEquals(0, publications)
        val accepted = requests()
        accepted.commitBgmDiscoveryState { publications++ }
        assertEquals(1, publications)
    }

    @Test fun sourceRetiredAtFinalAdmissionCannotCommitTheOriginalMap() {
        var current = true
        var publications = 0
        val owner = requests(owned = { current }, admit = { action -> current = false; action(); true })
        assertFailsWith<CancellationException> { owner.commitBgmDiscoveryState { publications++ } }
        assertEquals(0, publications)
    }

    @Test fun cancellationResultIsRethrownAndNeverDisplayedAsASongError(): Unit = runBlocking {
        val cancelled = CancellationException("original source retired")
        val owner = requests(detail = { _, _, _ -> Result.failure(cancelled) },
            recommendations = { _, _, _, _, _ -> Result.failure(cancelled) })
        assertEquals(cancelled.message, assertFailsWith<CancellationException> {
            owner.getBgmDetail("song", 1L, 11L)
        }.message)
        assertFailsWith<CancellationException> { owner.getBgmRecommendVideos("song", 1L, 11L, 1, 5) }
    }

    @Test fun ordinaryApiFailureRetainsItsErrorForTheOriginalRetryPolicy(): Unit = runBlocking {
        val failure = IllegalStateException("service unavailable")
        val owner = requests(detail = { _, _, _ -> Result.failure(failure) })
        assertSame(failure, owner.getBgmDetail("song", 1L, 11L).exceptionOrNull())
    }

    @Test fun originalMusicNavigationRetainsCidAndExternalFallbackWithoutInventingIds() {
        val detail = resolveDesktopBgmMusicTarget(BgmInfo(musicId = "original"), "BV1bgm", 11L)
        assertEquals(DesktopBgmMusicTarget.Detail("original", cid = 11L), detail)
        val url = "https://example.invalid/music?title=a+b"
        assertEquals(DesktopBgmMusicTarget.Web(url), resolveDesktopBgmMusicTarget(BgmInfo(jumpUrl = url), "BV1bgm", 11L))
        assertNull(resolveDesktopBgmMusicTarget(BgmInfo(), "BV1bgm", 11L))
    }
}
