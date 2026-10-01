package com.bilipai.desktop.player

import com.bilipai.desktop.DesktopLibrary
import com.bilipai.desktop.DesktopPlaybackController
import com.bilipai.desktop.DesktopPlaybackDataSource
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.data.DesktopSessionStore
import com.bilipai.desktop.data.VideoCard
import com.bilipai.desktop.data.VideoDetails
import com.bilipai.desktop.data.VideoPart
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import java.io.File
import java.util.Collections
import javax.swing.SwingUtilities
import kotlin.math.abs

/** Real native EOF/queue/heartbeat integration; playback URLs are local files or a synthetic loopback server. */
internal object DesktopControllerNativeSmoke {
    fun run(player: MpvPlayer, video: File, outputDirectory: File) {
        val reports = Collections.synchronizedList(mutableListOf<DesktopHeartbeatReport>())
        fun reportsSnapshot() = synchronized(reports) { reports.toList() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        val directory = File(outputDirectory, "controller-fixture").apply { mkdirs() }.toPath()
        val repository = DesktopRepository(DesktopSessionStore(directory.resolve("fixture-account.json"), persistent = false))
        val source = object : DesktopPlaybackDataSource {
            override val sessionEpoch = 7L
            override suspend fun videoDetails(bvid: String): VideoDetails {
                val start = if (bvid == "BVfixtureA") 10L else 20L
                return VideoDetails(bvid, start, bvid, "", "", "Fixture creator", 0, 0,
                    listOf(VideoPart(start + 1, "Part one", 10), VideoPart(start + 2, "Part two", 10)))
            }
            override suspend fun related(bvid: String) = emptyList<VideoCard>()
            override suspend fun playback(details: VideoDetails, index: Int, quality: Int, codecOverride: String?, forceRefresh: Boolean) =
                com.bilipai.desktop.data.PlaybackSource(video.absolutePath, null, "${details.bvid}/${details.pages[index].cid}", "", quality = quality)
            override suspend fun reportHeartbeat(report: DesktopHeartbeatReport): Boolean { reports += report; return true }
        }
        val controller = DesktopPlaybackController(repository, player, null, null, DesktopLibrary(directory) { false },
            { PlayerPreferences(playbackMode = PlaybackMode.SEQUENTIAL) }, scope, currentDanmakuSettings = { error("This controller harness has no danmaku overlay") }, dataSource = source)
        try {
            SwingUtilities.invokeAndWait { controller.openQueue(listOf(VideoCard("BVfixtureA", "Fixture A", "", "", 0, 10),
                VideoCard("BVfixtureB", "Fixture B", "", "", 0, 10))) }
            waitFor(player, "first native queue source") { !it.loading && it.videoCodec != null && it.sourceTitle == "BVfixtureA/11" }
            SwingUtilities.invokeAndWait {
                check(controller.currentCastSource(player.currentSourceVersion)?.title == "BVfixtureA/11") {
                    "Casting could not obtain the actual controller-owned native source."
                }
            }
            SwingUtilities.invokeAndWait { controller.seekTo(9.7) }
            waitFor(player, "native EOF advances to second part") { !it.loading && it.videoCodec != null && it.sourceTitle == "BVfixtureA/12" }
            check(controller.state.value.currentPart == 1 && controller.state.value.queueIndex == 0)
            SwingUtilities.invokeAndWait { controller.seekTo(9.7) }
            waitFor(player, "native EOF crosses to next video") { !it.loading && it.videoCodec != null && it.sourceTitle == "BVfixtureB/21" }
            check(controller.state.value.currentPart == 0 && controller.state.value.queueIndex == 1)
            val deadline = System.nanoTime() + 5_000_000_000L
            while (System.nanoTime() < deadline && reportsSnapshot().none { !it.initial && it.identity.bvid == "BVfixtureA" && it.identity.cid == 12L }) Thread.sleep(25)
            val old = reportsSnapshot().filter { !it.initial && it.identity.bvid == "BVfixtureA" }
            check(old.map { it.identity.cid }.toSet() == setOf(11L, 12L)) { "The native part/video transition did not flush both original heartbeat sessions." }
            check(old.all { it.snapshot.playedTimeSec >= 9L && it.snapshot.realPlayedTimeSec in 0L..3L }) {
                "Native seeking was incorrectly counted as watched wall time."
            }
            val foreign = player.loadVersioned(PlaybackSource(video.absolutePath, referer = "", title = "Foreign controller source", startPositionSeconds = 2.0, startPaused = true))
            SwingUtilities.invokeAndWait {
                check(controller.currentCastSource(foreign) == null) { "Casting adopted a foreign native source." }
            }
            SwingUtilities.invokeAndWait { controller.pause(); controller.close() }
            waitFor(player, "foreign source survives controller pause and close") {
                !it.loading && it.paused && it.videoCodec != null && abs(it.positionSeconds - 2.0) < 0.3
            }
            check(player.currentSourceVersion == foreign && player.state.value.sourceTitle == "Foreign controller source")
        } finally { SwingUtilities.invokeAndWait { controller.close() }; scope.cancel() }
    }

    /** Exercises real libmpv HTTP errors, the unchanged recovery policy and an authorized backup pair. */
    fun runCdnRecovery(player: MpvPlayer, video: File, outputDirectory: File) {
        DesktopHttpMediaFixture(video).use { http ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
            val directory = File(outputDirectory, "controller-cdn-fixture").apply { mkdirs() }.toPath()
            val repository = DesktopRepository(DesktopSessionStore(directory.resolve("fixture-account.json"), persistent = false))
            var playbackRequests = 0
            val source = object : DesktopPlaybackDataSource {
                override suspend fun videoDetails(bvid: String) = VideoDetails(bvid, 100L, "CDN fixture", "", "", "Local fixture", 0L, 0L,
                    listOf(VideoPart(101L, "Synthetic HTTP source", 10L)))
                override suspend fun related(bvid: String) = emptyList<VideoCard>()
                override suspend fun playback(details: VideoDetails, index: Int, quality: Int, codecOverride: String?, forceRefresh: Boolean):
                    com.bilipai.desktop.data.PlaybackSource {
                    playbackRequests++
                    return com.bilipai.desktop.data.PlaybackSource(http.deniedUrl, null, "Synthetic HTTP CDN recovery", "",
                        cookieHeader = "SESSDATA=fixture-cookie", quality = quality, videoAlternatives = listOf(http.playableUrl))
                }
            }
            val controller = DesktopPlaybackController(repository, player, null, null, DesktopLibrary(directory) { false },
                { PlayerPreferences(playbackMode = PlaybackMode.SEQUENTIAL) }, scope, currentDanmakuSettings = { error("This controller harness has no danmaku overlay") }, dataSource = source)
            try {
                SwingUtilities.invokeAndWait { controller.open(VideoCard("BVfixtureCDN", "CDN fixture", "", "", 0L, 10)) }
                waitFor(player, "native HTTP 403 classification", allowError = true) {
                    it.failure?.let { failure -> failure.kind == PlayerFailureKind.NETWORK && failure.httpStatus == 403 } == true
                }
                val failed = requireNotNull(player.state.value.failure)
                check(failed.diagnostics.isNotEmpty()) { "The real HTTP failure published no diagnostic evidence." }
                val safe = failed.diagnostics.joinToString("\n") + failed.safeMessage
                check(!safe.contains("fixture-cookie") && !safe.contains("fixture-signature") && !safe.contains("/denied")) {
                    "Native HTTP failure diagnostics exposed playback credentials or signed paths."
                }
                waitFor(player, "native authorized CDN recovery", allowError = true) {
                    !it.loading && it.videoCodec != null && it.failure == null && it.error == null
                }
                check(player.currentSourceVersion == failed.sourceVersion) { "A CDN retry changed media ownership." }
                check(http.denied.get() > 0 && http.served.get() > 0 && http.suppliedFixtureCookie.get()) {
                    "The synthetic HTTP fixture did not observe both failed and recovered authenticated media requests."
                }
                check(playbackRequests == 1) { "An authorized CDN candidate unnecessarily fetched a fresh playback response." }
                val deadline = System.nanoTime() + 2_000_000_000L
                while (controller.state.value.recovering && System.nanoTime() < deadline) Thread.sleep(25)
                check(!controller.state.value.recovering && controller.state.value.error == null)
            } finally { SwingUtilities.invokeAndWait { controller.close() }; scope.cancel() }
        }
        player.load(PlaybackSource(video.absolutePath, referer = "", title = "Native overlay fixture", startPositionSeconds = 2.0, startPaused = true))
        waitFor(player, "paused local source after CDN fixture") { !it.loading && it.paused && it.videoCodec != null && abs(it.positionSeconds - 2.0) < 0.3 }
    }

    private fun waitFor(player: MpvPlayer, operation: String, allowError: Boolean = false, condition: (PlayerState) -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            val state = player.state.value
            if (condition(state)) return
            check(allowError || state.error == null) { "$operation failed: ${state.failure?.kind}" }
            Thread.sleep(25)
        }
        error("Timed out waiting for $operation")
    }
}
