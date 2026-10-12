package com.bilipai.desktop.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.android.purebilibili.core.network.SearchApi
import com.android.purebilibili.data.model.response.RelatedVideo
import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.android.purebilibili.feature.video.playback.session.PlaybackSessionStore
import com.bilipai.desktop.player.PlayerPreferences
import com.android.purebilibili.core.ui.transition.VideoCardTransitionClock
import com.android.purebilibili.feature.download.DownloadStatus
import com.android.purebilibili.feature.download.DownloadTask
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.android.purebilibili.navigation3.toLegacyRoute
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import java.awt.EventQueue
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import retrofit2.Retrofit

/** Headless backend qualification of the route callback borrowed by BGM primary and
 * nested comments. The real retained Home entry, generated full Home VM, Store gate,
 * Root route assembly, original parser and physical SnapshotStateList remain in use.
 * Unrelated Home jobs are held, unused rendering ports reject calls, all HTTP is sealed,
 * and the actual video resolver reads only memory connectivity/download-task inputs.
 * This does not prove that Compose mounted the BGM callback, pixels, playback, accounts
 * on the server, native media or hardware; that wiring has a separate whole-source proof.
 */
class DesktopMetadataRelatedRootNavigationTest {
    private class HeldDispatcher : CoroutineDispatcher() {
        private data class Work(val job: Job, val block: Runnable)
        private val pending = mutableListOf<Work>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            synchronized(pending) { pending += Work(requireNotNull(context[Job]), block) }
        }
        fun jobs(): Set<Job> = synchronized(pending) { pending.map { it.job }.toSet() }
        fun runOne(job: Job) {
            check(EventQueue.isDispatchThread())
            val work = synchronized(pending) {
                val matches = pending.filter { it.job === job }
                check(matches.size == 1) { "Expected one captured route continuation, got ${matches.size}" }
                matches.single().also(pending::remove)
            }
            work.block.run()
        }
        fun discardCancelled() = synchronized(pending) {
            check(pending.all { !it.job.isActive }) { "Fixture cannot discard a live owned request" }
            pending.clear()
        }
    }

    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java)
    ) { proxy, method, args ->
        when {
            method.declaringClass == Any::class.java && method.name == "toString" -> "Unused ${T::class.java.simpleName}"
            method.declaringClass == Any::class.java && method.name == "hashCode" -> System.identityHashCode(proxy)
            method.declaringClass == Any::class.java && method.name == "equals" -> proxy === args?.singleOrNull()
            else -> error("Unexpected unrelated ${T::class.java.simpleName}.${method.name}")
        }
    } as T

    private fun <T> edt(block: () -> T): T {
        if (EventQueue.isDispatchThread()) return block()
        val result = AtomicReference<Result<T>?>(null)
        EventQueue.invokeAndWait { result.set(runCatching(block)) }
        return requireNotNull(result.get()).getOrThrow()
    }

    private inner class Fixture : AutoCloseable {
        val folder = Files.createTempDirectory("bgm-real-root-47-")
        val sessions = DesktopSessionStore(folder.resolve("synthetic-session.json"), persistent = false)
        val repository = DesktopRepository(sessions)
        val httpAttempts = AtomicInteger()
        val held = HeldDispatcher()
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + held + CoroutineExceptionHandler { _, failure -> uncaught += failure })
        val context = DesktopPluginContext(DesktopPluginStore(folder.resolve("global")))
        val blocked = DesktopBlockedUpStore(context)
        val discovery = DesktopDiscoveryPreferences(folder.resolve("discovery"), blocked)
        val source = BiliPaiNavKey.VideoDetail("BV1GJ411x7h7", cid = 22L)
        val originalSession = PlaybackSessionStore()
        val originalRequest = PlaybackRequest.create(source.bvid, 7L, source.cid)
        val originalLoad = originalSession.beginLoadRequest(originalRequest)
        val relatedCard = RelatedVideo(bvid = "BV17x411w7KC", cid = 80L, title = "Related")
        var relatedCards = listOf(relatedCard)
        lateinit var permission: DesktopOriginalVideoMetadataAdmission
        lateinit var capturedCaller: Job
        lateinit var capturedBinding: DesktopOriginalVideoRepositoryBinding
        val stack = mutableStateListOf<BiliPaiNavKey>(BiliPaiNavKey.MainHost, source)
        var leafActive = true
        var rootAlive = true
        var checkpointAllowed = true
        var onCheckpoint: () -> Unit = {}
        val checkpoints = AtomicInteger()
        val destinationCommits = mutableListOf<Pair<BiliPaiNavKey, BiliPaiNavKey>>()
        val resolverCalls = mutableListOf<Pair<BiliPaiNavKey.VideoDetail, Boolean>>()
        var networkAvailable = true
        val downloads = mutableListOf<DownloadTask>()
        var beforeResolve: suspend () -> Unit = {}
        val videoResolver: DesktopOriginalRootVideoResolver
        val entry: DesktopHomeRetainedEntry
        val root: DesktopHomeRetainedRoot
        val routes: DesktopOriginalRootRouteAssembly

        init {
            sessions.saveAccount(mapOf("SESSDATA" to "synthetic-47-A", "bili_jct" to "synthetic-only"),
                AccountSummary(47L, "BGM synthetic fixture", ""))
            // Seal this fixture's client, already captured Retrofit/API fields and validation
            // clone before any retained owner exists. A mistaken extra call cannot reach DNS.
            val closedTransport = repository.httpClient.newBuilder().addInterceptor {
                httpAttempts.incrementAndGet()
                error("No HTTP is allowed in BGM navigation backend qualification")
            }.build()
            repository.javaClass.getDeclaredField("client").apply {
                check(trySetAccessible()); set(repository, closedTransport)
            }
            for (name in listOf("api", "searchApi", "passportApi", "validationPassportApi", "buvidApi", "dynamicApi")) {
                repository.javaClass.getDeclaredField(name).apply {
                    check(trySetAccessible()); check(type.isInterface)
                    set(repository, Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
                        httpAttempts.incrementAndGet()
                        error("No unrelated API service is allowed: $name.${method.name}")
                    })
                }
            }
            repository.javaClass.getDeclaredField("validationPassportRetrofit").apply {
                check(trySetAccessible())
                set(repository, (get(repository) as Retrofit).newBuilder().client(closedTransport).build())
            }
            val preferences = runBlocking {
                DesktopOriginalHomePreferences.create(context.store, scope, false, { false })
            }
            val analytics = DesktopHomeRootAnalytics(context.store, null, { rootAlive })
            val pages = object : DesktopHomeEmbeddedRetainedOwner, DesktopHomeEmbeddedPages by unused<DesktopHomeEmbeddedPages>() {
                override fun close() = Unit
                override suspend fun closeAndJoin() = Unit
            }
            entry = DesktopHomeRetainedEntry.create(repository, discovery, context, scope,
                repository.sessionEpoch, 47L, { rootAlive }, preferences, MutableStateFlow(false), { false },
                { analytics }, DesktopBlockedUpRepository(repository, blocked),
                { error("No authentication transport observation") }, { error("No Home feedback") }, { _, _ -> pages })
            val lifecycle = object : LifecycleOwner {
                override val lifecycle: Lifecycle = LifecycleRegistry.createUnsafe(this).apply {
                    currentState = Lifecycle.State.RESUMED
                }
            }
            val environment = DesktopHomeEnvironment(preferences, context, lifecycle,
                MutableStateFlow(false), analytics, { DesktopOriginalHomeGlobalNamespace(context.store, it) },
                MutableStateFlow(null), { _, _ -> error("No wallpaper request") }, {}, { _, _ -> }, pages,
                { _, _ -> error("No image save") }, { error("No rendering feedback") }, unused<DesktopHomeOverlayPorts>())
            val media = DesktopHomeMediaLifetime(entry.gate.scope, entry.gate::owns, entry.gate::commit) {
                error("No native media request")
            }
            val mediaPorts = DesktopHomeActualMediaPorts(media, { error("No wallpaper MIME request") },
                { _, _, _, _ -> error("No wallpaper rendering") }, MutableStateFlow(false), { error("No media feedback") }).ports
            val returns = DesktopHomeReturnNavigationOwner(entry.gate::owns, entry.gate::commit, ::checkpoint,
                { Offset.Zero }, { 47L }, VideoCardTransitionClock(), { false }, { false }, { true })
            root = DesktopHomeRetainedRoot(entry, environment, mediaPorts, returns,
                DesktopLiveNavigationBinding(entry.requests.environment, unused<SearchApi>(), context.store),
                media, DesktopHomeLottieBinding(repository.httpClient, entry.gate::owns, entry.gate::commit,
                    { error("No animation request") }))
            videoResolver = DesktopOriginalRootVideoResolver(root, { networkAvailable }, { downloads.toList() },
                { error("Unexpected no-network feedback") })
            routes = DesktopOriginalRootRouteAssembly(root, stack, DesktopOriginalRootRoutePlatform(
                ::checkpoint, { old, new -> destinationCommits += old to new },
                { key, direct -> resolverCalls += key to direct; beforeResolve(); videoResolver.resolve(key, direct) }, {},
                { error("Unexpected root Back") }), { setOf("home") })
            assertTrue(root.isCurrentOwner()); assertTrue(routes.owns())
            runBlocking<Unit> {
                capturedCaller = currentCoroutineContext().job
                capturedBinding = DesktopOriginalVideoRepositoryBinding.capture(repository, repository.sessionEpoch,
                    entry.gate.scope.coroutineContext.job, entry.gate::owns, entry.gate::commit, PlayerPreferences(),
                    null, emptySet(), false, { false }, { false }, { false }, { false }, { _, _ -> false })
                permission = capturedBinding.captureMetadataAdmission {
                    leafActive && routes.currentKey === source && originalSession.state.value.let {
                        it.currentRequest === originalRequest && it.currentLoadRequestToken == originalLoad.requestToken
                    }
                }
            }
            assertTrue(capturedCaller.isCompleted)
        }

        private fun checkpoint(action: () -> Unit): Boolean {
            checkpoints.incrementAndGet()
            onCheckpoint()
            if (!checkpointAllowed) return false
            action()
            return true
        }

        fun cardCurrent(): Boolean = leafActive && routes.currentKey === source && permission.isCurrent() &&
            relatedCards.any { it === relatedCard }
        fun relatedClick(): Job {
            val before = held.jobs()
            edt {
                routes.videoFromSource(BiliPaiNavKey.VideoDetail(relatedCard.bvid, relatedCard.cid), ::cardCurrent) { action ->
                    var applied = false
                    permission.admit { if (cardCurrent()) { action(); applied = true } } && applied
                }
            }
            val added = held.jobs() - before
            assertEquals(1, added.size, "The real Root queues only one captured destination caller")
            return added.single()
        }

        /** Exactly the existing entry-scoped Shell callback, not another route dispatcher. */
        fun click(raw: String) = edt {
            if (leafActive) routes.callbackFor(source) {
                desktopOriginalOpenMessageLink(raw, routes, source.toLegacyRoute())
            }
        }
        fun backToBgm() = edt {
            assertTrue(routes.back()); assertSame(source, routes.currentKey)
        }
        fun queuedVideo(raw: String): Job {
            val before = held.jobs()
            click(raw)
            val added = held.jobs() - before
            assertEquals(1, added.size, "Only the real video caller may be newly dispatched")
            return added.single()
        }
        fun run(job: Job) = edt { held.runOne(job) }
        fun replaceAccount(mid: Long = 48L) = sessions.saveAccount(
            mapOf("SESSDATA" to "synthetic-47-B", "bili_jct" to "synthetic-only-B"),
            AccountSummary(mid, "Synthetic replacement", ""))
        override fun close() {
            try {
                routes.close(); root.close(); rootAlive = false; scope.cancel(); held.discardCancelled()
                assertEquals(0, httpAttempts.get(), "No unrelated API may execute")
                assertTrue(uncaught.isEmpty(), "Unexpected Root coroutine background failures: $uncaught")
                repository.httpClient.dispatcher.executorService.shutdownNow()
                repository.httpClient.connectionPool.evictAll()
            } finally {
                Files.walk(folder).use { files -> files.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
            }
        }
    }


    @Test fun completedMetadataRequestCannotReadHttpButCanNavigateViaTheActualRootController() = runBlocking<Unit> {
        Fixture().use { f ->
            assertTrue(f.capturedCaller.isCompleted)
            assertFailsWith<CancellationException> { f.capturedBinding.assertCurrent() }
            assertTrue(f.permission.isCurrent())
            val click = f.relatedClick()
            f.run(click)
            assertTrue(click.isCompleted); assertFalse(click.isCancelled)
            val destination = assertIs<BiliPaiNavKey.VideoDetail>(f.routes.currentKey)
            assertEquals(f.relatedCard.bvid, destination.bvid); assertEquals(f.relatedCard.cid, destination.cid)
            assertEquals(1, f.resolverCalls.size); assertTrue(f.resolverCalls.single().second)
            assertEquals(f.source, f.stack[f.stack.lastIndex - 1])
            assertEquals(1, f.destinationCommits.size); assertEquals(0, f.httpAttempts.get())
            assertFalse(f.permission.isCurrent(), "The previous entry cannot navigate a second time")
        }
    }

    @Test fun actualRootFinalAdmissionRejectsNewOriginalTokenDuringItsRealResolver() = runBlocking<Unit> {
        Fixture().use { f ->
            val click = f.relatedClick()
            f.beforeResolve = {
                f.originalSession.beginLoadRequest(PlaybackRequest.create(f.source.bvid, 7L, f.source.cid))
            }
            f.run(click)
            assertTrue(click.isCompleted); assertTrue(click.isCancelled)
            assertSame(f.source, f.routes.currentKey); assertTrue(f.destinationCommits.isEmpty())
            assertFalse(f.permission.isCurrent()); assertEquals(1, f.resolverCalls.size)
        }
    }

    @Test fun actualRootQueuedRelatedNavigationRejectsAccountRetirementAndRemovedCardIdentity() = runBlocking<Unit> {
        for (accountChange in listOf(true, false)) Fixture().use { f ->
            val click = f.relatedClick()
            if (accountChange) f.replaceAccount() else f.relatedCards = emptyList()
            f.run(click)
            assertTrue(click.isCompleted); assertTrue(click.isCancelled)
            assertSame(f.source, f.routes.currentKey); assertTrue(f.destinationCommits.isEmpty())
            assertTrue(f.resolverCalls.isEmpty()); assertEquals(0, f.httpAttempts.get())
        }
    }
}
