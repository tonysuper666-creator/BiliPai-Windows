package com.bilipai.desktop.ui

import com.android.purebilibili.feature.audio.lyrics.*
import com.android.purebilibili.feature.audio.player.MusicPlaybackSource
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

private var lyricsAssertions = 0
private fun lyricsVerify(value: Boolean, message: String) { check(value) { message }; lyricsAssertions++ }
private class LyricsFixtureSubject(val bvid: String, val cid: Long)
private class LyricsFixtureReadGate(val key: String) {
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val returned = CompletableDeferred<Unit>()
}
private class LyricsFixtureCache(private val disk: LyricsCache) : LyricsCache {
    val gate = AtomicReference<LyricsFixtureReadGate?>()
    val reads = AtomicInteger()
    val writes = AtomicInteger()
    override suspend fun read(key: String): LyricDocument? {
        reads.incrementAndGet()
        gate.get()?.takeIf { it.key == key }?.let {
            it.started.complete(Unit)
            // Deliberately model an existing blocking provider/cache operation
            // that finishes despite coroutine cancellation; no HTTP is sent.
            return withContext(NonCancellable) {
                it.release.await()
                disk.read(key).also { _ -> it.returned.complete(Unit) }
            }
        }
        return disk.read(key)
    }
    override suspend fun write(key: String, document: LyricDocument) { disk.write(key, document); writes.incrementAndGet() }
}
private class LyricsFixtureProvider : LyricsProvider {
    override val source = LyricSource.NETEASE
    val searches = AtomicInteger()
    val fetches = AtomicInteger()
    val fail = AtomicBoolean()
    var title = "B Song"
    override suspend fun search(query: LyricQuery): List<LyricCandidate> {
        searches.incrementAndGet()
        if (fail.get()) throw java.io.IOException("Fixture provider failure")
        return listOf(LyricCandidate(source, "remote-picked", title, "Artist", 150_000))
    }
    override suspend fun fetch(candidate: LyricCandidate): RawLyrics {
        fetches.incrementAndGet()
        return RawLyrics("[00:00.00]Remote original lyric\n[00:01.00]second line")
    }
}
private suspend fun awaitLyrics(predicate: () -> Boolean) = withTimeout(5_000) { while (!predicate()) delay(10) }

fun main() = runBlocking {
    withTimeout(30_000) {
        val root = Files.createTempDirectory("bilipai-original-audio-lyrics-")
        val store = DesktopPluginStore(root.resolve("settings"))
        val entryOwned = AtomicBoolean(true)
        val gate = Any()
        val settings = DesktopOriginalPlayerSettingsContext(DesktopPluginContext(store), entryOwned::get, { action ->
            synchronized(gate) { if (!entryOwned.get()) false else { action(); true } }
        })
        val parent = SupervisorJob()
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(dispatcher + parent)
        val disk = FileLyricsCache(root.resolve("lyrics").toFile())
        val cache = LyricsFixtureCache(disk)
        val provider = LyricsFixtureProvider()
        val repository = LyricsRepository(listOf(provider), cache)
        val a = LyricsFixtureSubject("BV1-fixture-A", 11)
        val b = LyricsFixtureSubject("BV1-fixture-B", 22)
        val current = AtomicReference(a)
        val accepted = AtomicInteger()
        fun capture(bvid: String, cid: Long): DesktopOriginalMusicSourceLease {
            val subject = current.get()
            require(subject.bvid == bvid && subject.cid == cid)
            fun owns() = entryOwned.get() && parent.isActive && current.get() === subject
            return DesktopOriginalMusicSourceLease(bvid, cid, ::owns, { action ->
                synchronized(gate) { if (!owns()) false else { accepted.incrementAndGet(); action(); true } }
            })
        }
        val keyA = MusicPlaybackSource.VideoAudio(a.bvid, a.cid, "A Song").stableId
        val keyB = MusicPlaybackSource.VideoAudio(b.bvid, b.cid, "B Song").stableId
        val documentA = LyricDocument(lines = listOf(LyricLine(0, 1_000, "A cached lyric")), source = LyricSource.BILIBILI)
        val documentB = LyricDocument(lines = listOf(LyricLine(0, 1_000, "B cached lyric")), source = LyricSource.BILIBILI)
        disk.write(keyA, documentA); disk.write(keyB, documentB)
        val binding = DesktopOriginalAudioLyricsBinding(settings, repository, scope, ::capture)
        binding.initPlayer(settings)
        lyricsVerify(binding.uiState.value.lyricsDocument == null, "sole original MusicUiState begins empty")
        val oldGate = LyricsFixtureReadGate(keyA)
        cache.gate.set(oldGate)
        binding.loadLyricsForVideo("A Song", "Artist", a.bvid, a.cid, 150_000)
        oldGate.started.await()
        lyricsVerify(binding.uiState.value.isLyricsSearching, "original automatic load publishes searching before IO")
        current.set(b)
        binding.loadLyricsForVideo("B Song", "Artist", b.bvid, b.cid, 150_000)
        binding.uiState.first { it.lyricsDocument?.lines?.firstOrNull()?.text == "B cached lyric" }
        lyricsVerify(binding.uiState.value.lyrics == "B cached lyric", "original cached document becomes original display text")
        lyricsVerify(!binding.uiState.value.isLyricsSearching && binding.uiState.value.lyricsError == null, "original Found terminal state is preserved")
        oldGate.release.complete(Unit)
        oldGate.returned.await()
        delay(50)
        lyricsVerify(binding.uiState.value.lyricsDocument?.lines?.firstOrNull()?.text == "B cached lyric", "late cancelled old BV response cannot replace current lyrics")
        cache.gate.set(null)
        val priorSearch = provider.searches.get()
        binding.retryLyrics()
        binding.uiState.first { it.lyricsDocument?.source == LyricSource.NETEASE && !it.isLyricsSearching }
        lyricsVerify(provider.searches.get() > priorSearch && provider.fetches.get() > 0, "original retry bypasses cache and uses same supplied Repository")
        lyricsVerify(binding.uiState.value.lyrics?.contains("Remote original lyric") == true, "original provider parse and display result is retained")
        provider.title = "Manual title"
        binding.searchLyrics("Manual title")
        binding.uiState.first { it.lyricCandidates.isNotEmpty() && !it.isLyricsSearching }
        lyricsVerify(binding.uiState.value.lyricCandidates.single().title == "Manual title", "original manual search ranking/dedup is retained")
        binding.selectLyricsCandidate(0)
        binding.uiState.first { it.lyricsDocument?.manuallySelected == true && !it.isLyricsSearching }
        lyricsVerify(binding.uiState.value.lyricCandidates.isEmpty() && binding.uiState.value.lyricsError == null, "original selection clears candidates and error")
        lyricsVerify(disk.read(keyB)?.manuallySelected == true && disk.read(keyA) == documentA, "manual selection saves only fixed current BV/CID cache key")
        binding.adjustLyricsOffset(100_000)
        lyricsVerify(binding.uiState.value.lyricsDocument?.offsetMs == LYRIC_OFFSET_LIMIT_MS, "original synchronous offset clamp is retained")
        withTimeout(5_000) { while (disk.read(keyB)?.offsetMs != LYRIC_OFFSET_LIMIT_MS) delay(10) }
        lyricsVerify(disk.read(keyB)?.offsetMs == LYRIC_OFFSET_LIMIT_MS, "original offset Job writes same existing file cache")
        val queued = LyricsFixtureSubject("BV1-fixture-queued", 55)
        val queuedDocument = LyricDocument(lines = listOf(LyricLine(0, 1_000, "Queued source lyric")))
        disk.write(MusicPlaybackSource.VideoAudio(queued.bvid, queued.cid, "Queued Song").stableId, queuedDocument)
        val workerBlocked = CountDownLatch(1);val releaseWorker = CountDownLatch(1)
        executor.submit { workerBlocked.countDown();check(releaseWorker.await(5, TimeUnit.SECONDS)) }
        withContext(Dispatchers.IO) { check(workerBlocked.await(5, TimeUnit.SECONDS)) }
        val previousState = binding.uiState.value
        current.set(queued)
        binding.loadLyricsForVideo("Queued Song", "Artist", queued.bvid, queued.cid, 150_000)
        binding.retryLyrics();binding.searchLyrics("must-not-retarget");binding.selectLyricsCandidate(0);binding.adjustLyricsOffset(-100_000)
        lyricsVerify(binding.uiState.value == previousState, "new-source actions cannot use the previous private query or document before their queued load starts")
        releaseWorker.countDown()
        binding.uiState.first { it.lyricsDocument?.lines?.firstOrNull()?.text == "Queued source lyric" }
        lyricsVerify(binding.uiState.value.lyrics == "Queued source lyric", "guarded early retry cannot cancel the queued original new-source load")
        lyricsVerify(disk.read(keyB)?.offsetMs == LYRIC_OFFSET_LIMIT_MS, "early new-source offset cannot rewrite previous source cache")
        val frozenUi = binding.uiState.value
        current.set(a)
        lyricsVerify(runCatching { binding.retryLyrics() }.exceptionOrNull() is CancellationException, "replaced logical subject rejects old public lyrics actions")
        lyricsVerify(binding.uiState.value == frozenUi, "retired action cannot alter original state")
        current.set(b)
        val unfinished = LyricsFixtureReadGate(keyB)
        cache.gate.set(unfinished)
        binding.loadLyricsForVideo("B Song", "Artist", b.bvid, b.cid, 150_000)
        unfinished.started.await()
        val atClose = binding.uiState.value
        lyricsVerify(!binding.closeCancelJoin(25), "close reports false while original blocking IO is still alive")
        unfinished.release.complete(Unit)
        lyricsVerify(binding.closeCancelJoin(5_000), "close drains all original jobs after their IO actually finishes")
        lyricsVerify(binding.uiState.value == atClose, "cancelled close cannot publish a late original terminal state")
        lyricsVerify(runCatching { binding.initPlayer(settings) }.exceptionOrNull() is CancellationException, "closed binding cannot initialize again")
        lyricsVerify(runCatching { binding.loadLyricsForVideo("B Song", "Artist", b.bvid, b.cid, 150_000) }.exceptionOrNull() is CancellationException, "closed binding cannot launch new work")

        cache.gate.set(null)
        val c = LyricsFixtureSubject("BV1-fixture-C", 33);current.set(c)
        val notFoundGate = LyricsFixtureReadGate(MusicPlaybackSource.VideoAudio(c.bvid, c.cid, "C Song").stableId)
        cache.gate.set(notFoundGate)
        val notFound = DesktopOriginalAudioLyricsBinding(settings, LyricsRepository(emptyList(), cache), scope, ::capture)
        notFound.initPlayer(settings);notFound.loadLyricsForVideo("C Song", "Artist", c.bvid, c.cid, 150_000)
        notFoundGate.started.await();notFoundGate.release.complete(Unit)
        notFound.uiState.first { !it.isLyricsSearching }
        lyricsVerify(notFound.uiState.value.lyricsDocument == null && notFound.uiState.value.lyricsError == null, "original NotFound behavior is preserved")
        lyricsVerify(notFound.closeCancelJoin(), "NotFound original lifetime drains")
        cache.gate.set(null)
        val d = LyricsFixtureSubject("BV1-fixture-D", 44);current.set(d);provider.fail.set(true)
        val failed = DesktopOriginalAudioLyricsBinding(settings, repository, scope, ::capture)
        failed.initPlayer(settings);failed.loadLyricsForVideo("D Song", "Artist", d.bvid, d.cid, 150_000)
        failed.uiState.first { it.lyricsError != null && !it.isLyricsSearching }
        lyricsVerify(failed.uiState.value.lyricsError == "歌词加载失败，请检查网络后重试", "original Failed error message and terminal state are preserved")
        lyricsVerify(failed.closeCancelJoin(), "Failed original lifetime drains")
        val badLease = DesktopOriginalAudioLyricsBinding(settings, repository, scope, { _, _ ->
            DesktopOriginalMusicSourceLease("wrong-BV", 999, { true }, { action -> action(); true })
        })
        lyricsVerify(runCatching { badLease.loadLyricsForVideo("D Song", "Artist", d.bvid, d.cid, 150_000) }.isFailure,
            "binding rejects a Root capture for a different BV/CID")
        lyricsVerify(badLease.closeCancelJoin(), "rejected binding lifetime drains")
        val rejected = DesktopOriginalMusicSourceLease("BV-rejected", 1, { true }, { false })
        lyricsVerify(runCatching { rejected.commit { error("must not execute") } }.exceptionOrNull() is CancellationException,
            "Root publication refusal never reports a successful mutation")
        lyricsVerify(accepted.get() > 0 && Files.list(root.resolve("lyrics")).use { it.count() >= 2 }, "actual original cache files and real short publication were exercised")
        entryOwned.set(false);parent.cancelAndJoin();dispatcher.close()
        println("AudioLyricsProof PASS $lyricsAssertions assertions; same original Repository/models/FileLyricsCache, no HTTP or native player")
        println("Fixture temporary root: $root")
    }
}
