package com.bilipai.desktop.player

import com.android.purebilibili.data.model.response.PlayerInfoData
import com.android.purebilibili.feature.video.playback.policy.PlaybackHeartbeatSnapshot
import com.android.purebilibili.feature.video.subtitle.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Actual producer lifetimes with memory-only injected work. No second producer,
 * native player, account or HTTP transport is constructed by this fixture. */
object ProducerDrainFixture {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val checks = mutableListOf<String>()
        fun verify(value: Boolean, message: String) { check(value) { message }; checks += message }
        fun report(id: Long) = DesktopHeartbeatReport(DesktopHeartbeatIdentity("BV-fixture", 1, 1, 1), id,
            PlaybackHeartbeatSnapshot(id, id), 1)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val sent = Collections.synchronizedList(mutableListOf<Long>())
        val accepted = Collections.synchronizedList(mutableListOf<Long>())
        val reporter = DesktopHeartbeatReporter({ 1 }, { value ->
            sent += value.playbackSessionId
            if (value.playbackSessionId == 1L) { started.complete(Unit); release.await() }
            true
        }, { accepted += it.playbackSessionId }, requestTimeoutMs = 5_000, closeTimeoutMs = 1_000)
        reporter.submit(report(1)); started.await()
        reporter.submit(report(2)); reporter.submit(report(3))
        val drain = async { reporter.closeAndJoin(report(99)) }
        delay(40L)
        verify(!drain.isCompleted, "Reporter join waits for its actual in-flight worker")
        release.complete(Unit)
        verify(drain.await(), "Existing serial reporter drains and joins successfully")
        verify(sent.toList() == listOf(1L, 99L) && accepted.toList() == sent.toList(),
            "One in-flight report and exactly one final report survive original close queue clearing")
        reporter.submit(report(4))
        verify(reporter.closeAndJoin(report(100)) && sent.toList() == listOf(1L, 99L),
            "Repeated close is idempotent and closed reporter refuses new submissions")

        val epoch = AtomicLong(1)
        val epochStarted = CompletableDeferred<Unit>()
        val epochRelease = CompletableDeferred<Unit>()
        val epochCallbacks = AtomicInteger()
        val epochCalls = AtomicInteger()
        val epochReporter = DesktopHeartbeatReporter(epoch::get, {
            epochCalls.incrementAndGet(); epochStarted.complete(Unit); epochRelease.await(); true
        }, { epochCallbacks.incrementAndGet() }, requestTimeoutMs = 5_000, closeTimeoutMs = 1_000)
        epochReporter.submit(report(1)); epochStarted.await(); epoch.set(2)
        val epochDrain = async { epochReporter.closeAndJoin(report(99)) }
        delay(30L); epochRelease.complete(Unit)
        verify(epochDrain.await(), "Epoch-retired serial work still closes and joins")
        verify(epochCallbacks.get() == 0 && epochCalls.get() == 1,
            "Retired account prevents late acknowledgement and queued final request")

        val stuckStarted = CompletableDeferred<Unit>()
        val stuckRelease = CompletableDeferred<Unit>()
        val stuckReporter = DesktopHeartbeatReporter({ 1 }, {
            stuckStarted.complete(Unit)
            withContext(NonCancellable) { stuckRelease.await() }
            true
        }, {}, requestTimeoutMs = 60_000, closeTimeoutMs = 60)
        stuckReporter.submit(report(1)); stuckStarted.await()
        verify(!stuckReporter.closeAndJoin(), "Non-cooperative reporter work returns false at the bounded join")
        stuckRelease.complete(Unit)
        verify(stuckReporter.closeAndJoin(), "The same reporter can confirm quiescence after blocked work actually exits")

        suspend fun subtitleCase(nonCooperative: Boolean) {
            val entered = CompletableDeferred<Unit>()
            val unblock = CompletableDeferred<Unit>()
            val imports = AtomicInteger()
            val installs = AtomicInteger()
            val source = object : DesktopAutomaticSubtitleDataSource {
                override suspend fun metadata(bvid: String, cid: Long): PlayerInfoData {
                    entered.complete(Unit)
                    if (nonCooperative) withContext(NonCancellable) { unblock.await() }
                    else awaitCancellation()
                    return PlayerInfoData()
                }
                override suspend fun import(track: SubtitleTrackMeta): Path {
                    imports.incrementAndGet(); error("Cancelled subtitle producer must not import")
                }
            }
            val nativeObserver = object : DesktopAutomaticSubtitlePlayer {
                override val controlVersion = 10L
                override fun owns(sourceVersion: Long) = sourceVersion == 21L
                override fun install(sourceVersion: Long, expectedControlVersion: Long,
                    primary: DesktopOnlineSubtitleAsset?, secondary: DesktopOnlineSubtitleAsset?, mode: SubtitleDisplayMode): Boolean {
                    installs.incrementAndGet(); return true
                }
            }
            val subtitles = DesktopAutomaticSubtitles(source, nativeObserver)
            val job = requireNotNull(subtitles.load("BV-subtitle-fixture", 1, 21, SubtitleAutoPreference.ON, false))
            entered.await()
            if (nonCooperative) {
                verify(!subtitles.closeAndJoin() && !job.isCompleted,
                    "Non-cooperative subtitle work returns false while its old Job still exists")
                unblock.complete(Unit)
                verify(subtitles.closeAndJoin() && job.isCompleted,
                    "The same subtitle scope confirms quiescence after blocked metadata exits")
            } else verify(subtitles.closeAndJoin() && job.isCompleted,
                "Cooperative subtitle metadata cancellation joins the actual old Job")
            verify(imports.get() == 0 && installs.get() == 0 && !subtitles.state.value.loading,
                "Retired subtitle work cannot import files, install tracks or retain loading state")
            verify(subtitles.load("BV-subtitle-fixture", 1, 21, SubtitleAutoPreference.ON, false) == null,
                "Closed automatic subtitle scope refuses a new downloader operation")
        }
        subtitleCase(false); subtitleCase(true)
        val origins = listOf(DesktopHeartbeatReporter::class.java, DesktopAutomaticSubtitles::class.java).map { type ->
            val bytes = requireNotNull(type.getResourceAsStream("/" + type.name.replace('.', '/') + ".class")).use { it.readBytes() }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            "{\"class\":\"${type.name}\",\"codeSource\":\"${type.protectionDomain.codeSource.location.toURI()}\",\"sha256ClassBytes\":\"$hash\"}"
        }
        Files.writeString(Path.of(args.single()), "{\"passed\":true,\"assertions\":${checks.size},\"checks\":[${checks.joinToString(",") { "\"$it\"" }}],\"origins\":[${origins.joinToString(",")}],\"memoryWorkOnly\":true,\"nativePlayerConstructed\":false,\"realAccountUsed\":false,\"fullControllerDrain\":false}\n")
        println("PASS ${checks.size} actual producer close-and-join assertions")
    }
}
