package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.PlayUrlData
import com.android.purebilibili.feature.video.playback.dash.AdaptiveDashPlaybackSource
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.data.DesktopSessionStore
import com.bilipai.desktop.player.PlaybackSource
import com.bilipai.desktop.player.PlayerPreferences
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** Exact installed raw binding/cache + task-only media acceptance observer.
 * No socket, account mutation, decoder, HWND or complete-VM acceptance is exercised.
 */
object PlaybackInvocationFixture {
    private var assertions = 0
    private val checks = mutableListOf<String>()
    private fun verify(value: Boolean, label: String) { check(value) { label }; assertions++; checks += label }

    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val store = DesktopSessionStore.temporary()
        val repository = DesktopRepository(store)
        val entry = SupervisorJob(coroutineContext[Job])
        val entryScope = CoroutineScope(coroutineContext + entry)
        var alive = true
        val captures = AtomicInteger()
        val preparedBy = Collections.synchronizedList(mutableListOf<String>())
        val acceptedBy = Collections.synchronizedList(mutableListOf<String>())
        val rawData = Collections.synchronizedMap(mutableMapOf<Int, PlayUrlData>())
        val bindings = Collections.synchronizedMap(mutableMapOf<Int, DesktopOriginalVideoRepositoryBinding>())
        val capturedJobs = Collections.synchronizedMap(mutableMapOf<Int, Job>())

        suspend fun capture(): DesktopOriginalVideoPlaybackInvocation {
            val id = captures.incrementAndGet()
            val binding = DesktopOriginalVideoRepositoryBinding.capture(repository, store.generation, entry,
                { alive }, { block -> if (!alive) false else { block(); true } }, PlayerPreferences(),
                null, emptySet(), av1Supported = false, auto1080pEnabled = { true },
                directedTrafficEnabled = { false }, isMobileData = { false }, canRefreshPrimaryToken = { false },
                refreshPrimaryToken = { _, _ -> error("Guest fixture never refreshes credentials") })
            bindings[id] = binding
            capturedJobs[id] = requireNotNull(currentCoroutineContext()[Job])
            val data = PlayUrlData(quality = 64, format = "invocation-$id", acceptQuality = listOf(64))
            rawData[id] = data
            binding.environment.cache.put("BV-invocation-$id", id.toLong(), data, 64)
            val media = object : DesktopOriginalVideoMediaPort {
                private fun source(url: String): PlaybackSource {
                    binding.assertCurrent()
                    preparedBy += "operation-$id"
                    return binding.authorized(PlaybackSource(url, title = "operation-$id"))
                }
                override fun prepareProgressive(url: String) = source(url)
                override fun prepareLegacyDash(videoUrl: String, audioUrl: String?, cdnCacheKeysByUrl: Map<String, String>) =
                    source(videoUrl).copy(audioUrl = audioUrl)
                override fun prepareAdaptiveDash(source: AdaptiveDashPlaybackSource, cdnCacheKeysByUrl: Map<String, String>): PlaybackSource? =
                    error("Adaptive playback is outside this request-context fixture")
                override fun accept(source: PlaybackSource) {
                    binding.assertCurrent()
                    check(source.authorizationReceipt == binding.receipt)
                    acceptedBy += source.title
                }
            }
            return DesktopOriginalVideoPlaybackInvocation(binding.rawRepository, media, binding::assertCurrent)
        }

        // This accepted view uses the actual receipt admission, not an old request Job.
        // The task-only 'acceptedOwner' flag represents the REQUIRED native publication
        // check; no claim is made that it is an MPV/native actor.
        val acceptedAuthorization = repository.capturePlaybackAuthorization(store.generation) { alive }
        var acceptedOwner = true
        val acceptedMedia = object : DesktopOriginalVideoMediaPort {
            private fun checkAccepted() = repository.withPlaybackReceiptAdmission(acceptedAuthorization.receipt, { alive && acceptedOwner }) { Unit }
            override fun prepareProgressive(url: String): PlaybackSource {
                checkAccepted(); preparedBy += "accepted"
                return PlaybackSource(url, title = "accepted", authorizationReceipt = acceptedAuthorization.receipt)
            }
            override fun prepareLegacyDash(videoUrl: String, audioUrl: String?, cdnCacheKeysByUrl: Map<String, String>) =
                prepareProgressive(videoUrl).copy(audioUrl = audioUrl)
            override fun prepareAdaptiveDash(source: AdaptiveDashPlaybackSource, cdnCacheKeysByUrl: Map<String, String>): PlaybackSource? =
                error("Adaptive playback is outside this fixture")
            override fun accept(source: PlaybackSource) { checkAccepted(); acceptedBy += source.title }
        }
        val status = object : DesktopOriginalVideoPlaybackStatus {
            override fun isPlaybackLoggedIn(): Boolean = error("Context-free auth is not exercised by this fixture")
            override fun isPlaybackVip(): Boolean = error("Context-free VIP is not exercised by this fixture")
            override fun isUsingDedicatedPlaybackAccount(): Boolean = error("Context-free selection is not exercised by this fixture")
            override fun isAppApiCoolingDown(): Boolean = error("Context-free cooldown is not exercised by this fixture")
        }
        val ports = DesktopOriginalVideoPlaybackInvocationPorts(entryScope, { alive }, ::capture, status, { acceptedMedia })
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val first = ports.launch {
            verify(captures.get() == 1, "First load captures in its live launch Job")
            firstStarted.complete(Unit)
            releaseFirst.await()
            coroutineScope {
                val firstResult = async(Dispatchers.IO) { ports.repository.getInitialPlayUrlData("BV-invocation-1", 1, 64, null) }
                val secondResult = async(Dispatchers.Default) {
                    withContext(Dispatchers.IO) { ports.repository.getInitialPlayUrlData("BV-invocation-1", 1, 64, null) }
                }
                verify(firstResult.await() === rawData[1] && secondResult.await() === rawData[1],
                    "Parallel children and dispatcher changes share the original first raw cache object")
            }
            ports.withInvocation {
                verify(captures.get() == 2, "Nested operation inherits the first binding without recapture")
                withContext(Dispatchers.Default) {
                    ports.media.accept(ports.media.prepareProgressive("https://fixture.invalid/first"))
                }
            }
        }
        firstStarted.await()
        val second = ports.launch(Dispatchers.IO) {
            verify(ports.repository.getInitialPlayUrlData("BV-invocation-2", 2, 64, null) === rawData[2],
                "Interleaved second load consumes its own original cached raw response")
            withContext(Dispatchers.Default) {
                ports.media.accept(ports.media.prepareLegacyDash("https://fixture.invalid/second", null, emptyMap()))
            }
        }
        second.join()
        verify(capturedJobs[1]?.isActive == true && capturedJobs[2]?.isActive == false,
            "Completed second request does not retire the suspended first Job")
        releaseFirst.complete(Unit); first.join()
        verify(preparedBy.toList() == listOf("operation-2", "operation-1") && acceptedBy.toList() == preparedBy.toList(),
            "Synchronous prepare and accept retain each immutable request across reverse completion")
        verify(captures.get() == 2, "Parallel and nested original work required exactly two independent captures")
        try { bindings.getValue(2).assertCurrent(); error("Completed request accepted") }
        catch (_: CancellationException) { verify(true, "Captured request cannot be reused after its actual Job completes") }

        ports.media.accept(ports.media.prepareProgressive("https://fixture.invalid/accepted-recovery"))
        verify(preparedBy.last() == "accepted" && acceptedBy.last() == "accepted",
            "Context is restored after coroutine completion; synchronous recovery uses the accepted receipt view")
        acceptedOwner = false
        try { ports.media.prepareProgressive("https://fixture.invalid/foreign"); error("Foreign accepted source used") }
        catch (_: CancellationException) { verify(true, "Foreign native-publication admission is rejected by the supplied accepted view") }
        acceptedOwner = true

        val cancellationEntered = CompletableDeferred<Unit>()
        val third = ports.launch {
            cancellationEntered.complete(Unit)
            awaitCancellation()
        }
        cancellationEntered.await(); third.cancelAndJoin()
        try { bindings.getValue(3).assertCurrent(); error("Canceled request accepted") }
        catch (_: CancellationException) { verify(true, "Canceling one operation retires its captured request Job") }
        ports.media.accept(ports.media.prepareProgressive("https://fixture.invalid/still-accepted"))
        verify(acceptedBy.last() == "accepted" && entry.isActive,
            "Canceled load leaves entry and independent accepted-source recovery alive")

        val neverRuns = AtomicInteger()
        val parentCanceled = Job().apply { cancel() }
        val canceledScope = CoroutineScope(Dispatchers.Default + parentCanceled)
        val canceledPorts = DesktopOriginalVideoPlaybackInvocationPorts(canceledScope, { true },
            { neverRuns.incrementAndGet(); error("Pre-canceled scope captured request") }, status, { acceptedMedia })
        try { canceledPorts.launch { error("Pre-canceled scope ran") }; error("Retired scope admitted") }
        catch (_: CancellationException) { verify(neverRuns.get() == 0, "Pre-canceled entry refuses capture before launch") }

        ports.close()
        try { ports.media.prepareProgressive("https://fixture.invalid/closed"); error("Closed entry accepted") }
        catch (_: CancellationException) { verify(true, "Closed forwarding ports reject new synchronous source preparation") }
        try { ports.launch { error("Closed entry ran") }; error("Closed entry admitted") }
        catch (_: CancellationException) { verify(true, "Closed forwarding ports reject new launches") }
        verify(acceptedOwner && entry.isActive, "Closing facade neither stops accepted source nor cancels Root scope implicitly")
        entry.cancelAndJoin()

        val origins = listOf(DesktopRepository::class.java, DesktopSessionStore::class.java,
            DesktopOriginalVideoRepositoryBinding::class.java, DesktopOriginalVideoLoadProtocolEnvironment::class.java,
            DesktopOriginalVideoPlaybackInvocationPorts::class.java).joinToString(",") { type ->
                "{\"class\":\"${type.name}\",\"codeSource\":\"${type.protectionDomain.codeSource.location.toURI()}\"}"
            }
        Files.writeString(Path.of(args.single()), "{\"passed\":true,\"assertions\":$assertions,\"checks\":[${checks.joinToString(",") { "\"$it\"" }}],\"origins\":[$origins],\"scope\":\"task-only invocation facade over actual installed Repository/Store/raw binding/cache; media acceptance observer only; no socket/MPV/HWND/full VM or Holder\"}\n")
        println("PASS $assertions request-context assertions; actual installed raw binding/cache, no sockets/native")
    }
}
