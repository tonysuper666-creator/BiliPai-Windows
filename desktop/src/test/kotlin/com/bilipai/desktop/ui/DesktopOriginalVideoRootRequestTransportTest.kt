package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.ViewInfo
import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.android.purebilibili.feature.video.playback.session.PlaybackSessionState
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.data.DesktopSearchPreferences
import com.bilipai.desktop.data.DesktopSessionStore
import com.bilipai.desktop.player.DesktopSubtitleAssets
import com.bilipai.desktop.player.PlayerPreferences
import kotlinx.coroutines.*
import java.nio.file.Files
import kotlin.test.*

class DesktopOriginalVideoRootRequestTransportTest {
    private class Fixture : AutoCloseable {
        val directory = Files.createTempDirectory("video-request-transport-")
        val repository = DesktopRepository(DesktopSessionStore.temporary())
        val entry = SupervisorJob()
        var current = true

        suspend fun transport(request: PlaybackRequest, initialInfo: ViewInfo? = null): DesktopOriginalVideoRootRequestTransport {
            val binding = DesktopOriginalVideoRepositoryBinding.capture(repository, repository.sessionEpoch, entry,
                { current }, { action -> if (!current) false else { action(); true } },
                PlayerPreferences(), null, emptySet(), false, { false }, { false }, { false }, { false },
                { _, _ -> false }, onPlaybackAuthorizationRetired = { error("No HTTP or authorization mutation") })
            val raw = createDesktopOriginalVideoOwnerRequestRepository(repository, binding,
                DesktopSubtitleAssets(repository.httpClient), DesktopSearchPreferences(directory.resolve("search")),
                { _, _ -> false })
            return DesktopOriginalVideoRootRequestTransport(raw,
                PlaybackSessionState(currentRequest = request, currentLoadRequestToken = 1L), initialInfo, {})
        }

        override fun close() {
            current = false
            entry.cancel()
            repository.httpClient.dispatcher.executorService.shutdownNow()
            repository.httpClient.connectionPool.evictAll()
            directory.toFile().deleteRecursively()
        }
    }

    @Test fun aidOnlyRequestUsesItsResolvedCanonicalMetadataForMediaPreparation() = runBlocking<Unit> {
        Fixture().use { fixture ->
            val transport = fixture.transport(PlaybackRequest(bvid = "", aid = 170001L, cid = 22L))
            transport.info(ViewInfo(bvid = "BV17x411w7KC", aid = 170001L, cid = 22L, title = "Resolved title"))
            val source = transport.source("https://fixture.invalid/video", null)
            assertEquals("Resolved title", source.title)
            assertEquals("https://fixture.invalid/video", source.videoUrl)
            assertNotNull(source.authorizationReceipt)
        }
    }

    @Test fun anotherVideosMetadataCannotSupplyTheCapturedBvidRequest() = runBlocking<Unit> {
        Fixture().use { fixture ->
            val transport = fixture.transport(PlaybackRequest(bvid = "BV1GJ411x7h7", aid = 170001L, cid = 22L))
            transport.info(ViewInfo(bvid = "BV17x411w7KC", aid = 170001L, cid = 22L, title = "Another video"))
            assertFailsWith<IllegalStateException> { transport.source("https://fixture.invalid/video", null) }
        }
    }

    @Test fun anotherAidCannotSupplyAnAidOnlyRequest() = runBlocking<Unit> {
        Fixture().use { fixture ->
            val transport = fixture.transport(PlaybackRequest(bvid = "", aid = 170001L, cid = 22L))
            transport.info(ViewInfo(bvid = "BV1GJ411x7h7", aid = 7L, cid = 22L, title = "Another aid"))
            assertFailsWith<IllegalStateException> { transport.source("https://fixture.invalid/video", null) }
        }
    }

    @Test fun committedCanonicalMetadataCanSeedAnAidOnlyRequestWithoutRefetching() = runBlocking<Unit> {
        Fixture().use { fixture ->
            val info = ViewInfo(bvid = "BV17x411w7KC", aid = 170001L, cid = 22L, title = "Committed title")
            val transport = fixture.transport(PlaybackRequest(bvid = "", aid = info.aid, cid = info.cid), info)
            assertEquals(info.title, transport.source("https://fixture.invalid/video", null).title)
        }
    }

    @Test fun retiredBindingRejectsPreviouslyResolvedMetadata() = runBlocking<Unit> {
        Fixture().use { fixture ->
            val info = ViewInfo(bvid = "BV17x411w7KC", aid = 170001L, cid = 22L, title = "Retired title")
            val transport = fixture.transport(PlaybackRequest(bvid = info.bvid, aid = info.aid, cid = info.cid), info)
            fixture.current = false
            assertFailsWith<CancellationException> { transport.source("https://fixture.invalid/video", null) }
        }
    }
}
