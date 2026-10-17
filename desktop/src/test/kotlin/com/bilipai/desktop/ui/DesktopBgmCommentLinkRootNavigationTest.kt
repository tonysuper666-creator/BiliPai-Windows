package com.bilipai.desktop.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.android.purebilibili.core.network.SearchApi
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
class DesktopBgmCommentLinkRootNavigationTest {
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
        val scope = CoroutineScope(SupervisorJob() + held)
        val context = DesktopPluginContext(DesktopPluginStore(folder.resolve("global")))
        val blocked = DesktopBlockedUpStore(context)
        val discovery = DesktopDiscoveryPreferences(folder.resolve("discovery"), blocked)
        val source = BiliPaiNavKey.BgmDetail("MA47", aid = 470L, cid = 471L, showVideos = false)
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
        }

        private fun checkpoint(action: () -> Unit): Boolean {
            checkpoints.incrementAndGet()
            onCheckpoint()
            if (!checkpointAllowed) return false
            action()
            return true
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
                repository.httpClient.dispatcher.executorService.shutdownNow()
                repository.httpClient.connectionPool.evictAll()
            } finally {
                Files.walk(folder).use { files -> files.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
            }
        }
    }

    @Test fun selectedMainHostSettingsAdmitsContentWithoutBroadeningPhysicalEntryCallbacks() {
        Fixture().use { f ->
            edt {
                f.stack.clear(); f.stack += BiliPaiNavKey.MainHost
                val selected = AtomicReference<BiliPaiNavKey>(BiliPaiNavKey.Settings)
                val destination: () -> BiliPaiNavKey = { selected.get() }
                f.routes.bindMainHostDestination(destination)
                var contentActions = 0
                var physicalActions = 0
                assertTrue(f.routes.isCurrentContent(BiliPaiNavKey.Settings))
                f.routes.callbackForContent(BiliPaiNavKey.Settings) { contentActions++ }
                f.routes.callbackFor(BiliPaiNavKey.Settings) { physicalActions++ }
                assertEquals(1, contentActions)
                assertEquals(0, physicalActions, "MainHost remains the sole physical stack entry")
                assertEquals(listOf(BiliPaiNavKey.MainHost), f.stack.toList())
                selected.set(BiliPaiNavKey.Home)
                f.routes.callbackForContent(BiliPaiNavKey.Settings) { contentActions++ }
                assertEquals(1, contentActions, "The outgoing Settings tab cannot act")
                assertTrue(f.routes.isCurrentContent(BiliPaiNavKey.Home))
                f.routes.unbindMainHostDestination(destination)
            }
        }
    }

    @Test fun physicalOverlayAndUnboundHostRejectSettingsWhileStaleUnbindKeepsSuccessorBinding() {
        Fixture().use { f ->
            edt {
                f.stack.clear(); f.stack += BiliPaiNavKey.MainHost
                val first: () -> BiliPaiNavKey = { BiliPaiNavKey.Settings }
                val successor: () -> BiliPaiNavKey = { BiliPaiNavKey.Settings }
                f.routes.bindMainHostDestination(first)
                assertTrue(f.routes.push(BiliPaiNavKey.SettingsSearch))
                assertFalse(f.routes.isCurrentContent(BiliPaiNavKey.Settings))
                var actions = 0
                f.routes.callbackForContent(BiliPaiNavKey.Settings) { actions++ }
                f.routes.callbackForContent(BiliPaiNavKey.SettingsSearch) { actions++ }
                assertEquals(1, actions, "Only the physical foreground search may act")
                assertTrue(f.routes.back())
                assertTrue(f.routes.isCurrentContent(BiliPaiNavKey.Settings))
                f.routes.bindMainHostDestination(successor)
                f.routes.unbindMainHostDestination(first)
                assertTrue(f.routes.isCurrentContent(BiliPaiNavKey.Settings))
                f.routes.unbindMainHostDestination(successor)
                assertFalse(f.routes.isCurrentContent(BiliPaiNavKey.Settings))
                f.routes.callbackForContent(BiliPaiNavKey.Settings) { actions++ }
                assertEquals(1, actions)
            }
        }
    }

    @Test fun realPlaybackSettingsMigrationUsesSelectedTabAndRetiresItsExistingStorePermit() {
        Fixture().use { f ->
            val selected = AtomicReference<BiliPaiNavKey>(BiliPaiNavKey.Settings)
            val destination: () -> BiliPaiNavKey = { selected.get() }
            edt {
                f.stack.clear(); f.stack += BiliPaiNavKey.MainHost
                f.routes.bindMainHostDestination(destination)
            }
            val owns = { f.routes.isCurrentContent(BiliPaiNavKey.Settings) }
            val context = DesktopOriginalPlayerSettingsContext(f.context, owns, { action ->
                var applied = false
                val accepted = f.entry.gate.commit { if (owns()) { action(); applied = true } }
                accepted && applied
            })
            val hardware = DesktopOriginalHardwareDecodePreferences(f.context.store)
            runBlocking { hardware.ensureMigrated(context, legacyWindowsFallback = false) }
            assertFalse(hardware.current(legacyWindowsFallback = true))
            val file = f.folder.resolve("global/plugin-settings.json")
            val before = Files.readAllBytes(file)
            edt { selected.set(BiliPaiNavKey.Home) }
            assertFailsWith<CancellationException> {
                runBlocking { hardware.ensureMigrated(context, legacyWindowsFallback = true) }
            }
            assertContentEquals(before, Files.readAllBytes(file), "Retired Settings cannot alter canonical or mirror preferences")
            edt { f.routes.unbindMainHostDestination(destination) }
        }
    }

    private fun bindMainHostSelection(f: Fixture): AtomicInteger = edt {
        val selected = AtomicInteger(0)
        f.stack.clear(); f.stack += BiliPaiNavKey.MainHost
        f.routes.bindMainHostDestination { if (selected.get() == 0) BiliPaiNavKey.Home else BiliPaiNavKey.Settings }
        f.routes.bindMainHostNavigation { key ->
            val next = when (key) {
                BiliPaiNavKey.Home -> 0
                BiliPaiNavKey.Settings -> 1
                else -> null
            }
            if (next == null) false else {
                f.routes.selectMainHostPageAdmitted(selected.get(), next) { selected.set(next) }
                true
            }
        }
        selected
    }

    private fun queuedMainHostVideo(f: Fixture): Job {
        val before = f.held.jobs()
        edt { f.routes.video(BiliPaiNavKey.VideoDetail("av115391124741470", sourceRoute = "home")) }
        val added = f.held.jobs() - before
        assertEquals(1, added.size, "Only the actual Root video resolver should be newly queued")
        return added.single()
    }

    @Test fun realMainHostTabSelectionCancelsTheQueuedVideoWhileKeepingThePhysicalStack() {
        Fixture().use { f ->
            val selected = bindMainHostSelection(f)
            val revision = f.routes.sourceNavigationRevision
            val pending = queuedMainHostVideo(f)
            edt { assertTrue(f.routes.push(BiliPaiNavKey.Settings)) }
            assertEquals(1, selected.get())
            assertTrue(f.routes.sourceNavigationRevision > revision)
            assertTrue(pending.isCancelled)
            f.run(pending)
            assertEquals(listOf(BiliPaiNavKey.MainHost), f.stack.toList())
            assertTrue(f.routes.isCurrentContent(BiliPaiNavKey.Settings))
            assertTrue(f.resolverCalls.isEmpty()); assertTrue(f.destinationCommits.isEmpty())
        }
    }

    @Test fun mainHostAwayAndBackCannotPublishAnAlreadySuspendedVideoIntoTheReturnedTab() {
        Fixture().use { f ->
            val selected = bindMainHostSelection(f)
            val release = CompletableDeferred<Unit>()
            f.beforeResolve = { release.await() }
            val pending = queuedMainHostVideo(f)
            f.run(pending)
            assertEquals(1, f.resolverCalls.size)
            val revision = f.routes.sourceNavigationRevision
            edt {
                assertTrue(f.routes.push(BiliPaiNavKey.Settings))
                assertTrue(f.routes.push(BiliPaiNavKey.Home))
            }
            assertEquals(0, selected.get())
            assertTrue(f.routes.sourceNavigationRevision >= revision + 2)
            assertTrue(pending.isCancelled)
            release.complete(Unit)
            f.run(pending)
            assertEquals(listOf(BiliPaiNavKey.MainHost), f.stack.toList())
            assertTrue(f.routes.isCurrentContent(BiliPaiNavKey.Home))
            assertTrue(f.destinationCommits.isEmpty())
        }
    }

    @Test fun reselectingTheSameMainHostTabPreservesTheCurrentVideoRequest() {
        Fixture().use { f ->
            val selected = bindMainHostSelection(f)
            val revision = f.routes.sourceNavigationRevision
            val pending = queuedMainHostVideo(f)
            edt { assertTrue(f.routes.push(BiliPaiNavKey.Home)) }
            assertEquals(0, selected.get())
            assertEquals(revision, f.routes.sourceNavigationRevision)
            assertTrue(pending.isActive)
            f.run(pending)
            assertIs<BiliPaiNavKey.VideoDetail>(f.routes.currentKey)
            assertEquals(1, f.resolverCalls.size)
        }
    }

    @Test fun retiredRootCannotSelectAnotherMainHostTabOrAdvanceItsSourceRevision() {
        Fixture().use { f ->
            val selected = bindMainHostSelection(f)
            val revision = f.routes.sourceNavigationRevision
            f.replaceAccount()
            edt { assertFalse(f.routes.push(BiliPaiNavKey.Settings)) }
            assertEquals(0, selected.get())
            assertEquals(revision, f.routes.sourceNavigationRevision)
            assertEquals(listOf(BiliPaiNavKey.MainHost), f.stack.toList())
            assertTrue(f.resolverCalls.isEmpty())
        }
    }

    private fun queuedContentLink(f: Fixture, binding: DesktopOriginalContentLinkBinding,
        raw: String, title: String = "链接"): Job {
        val before = f.held.jobs()
        edt { assertTrue(binding.open(raw, title)) }
        val added = f.held.jobs() - before
        assertEquals(1, added.size, "Only one original content-link caller should be queued")
        return added.single()
    }

    private fun beginContentLink(f: Fixture, job: Job) {
        f.run(job) // The required yield leaves the originating Store admission.
        f.run(job)
    }

    @Test fun originalContentLinkNativeDispatchLeavesTheOriginatingStoreAdmissionFirst() {
        Fixture().use { f ->
            var insideSourceAdmission = false
            f.onCheckpoint = { assertFalse(insideSourceAdmission) }
            DesktopOriginalContentLinkBinding(f.routes, stillPresented = { true },
                openExternal = { error("No external launch") }, feedback = { error(it) }).use { binding ->
                val before = f.held.jobs()
                edt {
                    f.entry.gate.commit {
                        insideSourceAdmission = true
                        try { assertTrue(binding.open("bilibili://space/23")) }
                        finally { insideSourceAdmission = false }
                    }
                }
                assertSame(f.source, f.routes.currentKey)
                val job = (f.held.jobs() - before).single()
                beginContentLink(f, job)
                assertEquals(BiliPaiNavKey.Space(23L), f.routes.currentKey)
            }
        }
    }

    @Test fun pendingOriginalContentShortLinkBlocksAnotherClickBeyondTheDebounceWindow() {
        Fixture().use { f ->
            var now = 0L
            val target = CompletableDeferred<com.android.purebilibili.core.util.BilibiliNavigationTarget?>()
            var resolutions = 0
            DesktopOriginalContentLinkBinding(f.routes, stillPresented = { true },
                openExternal = { error("No external launch") }, feedback = { error(it) }, nowMillis = { now },
                resolveShortLink = { resolutions++; target.await() }).use { binding ->
                val job = queuedContentLink(f, binding, "https://b23.tv/fixture221")
                beginContentLink(f, job)
                now = 5000L
                edt { assertFalse(binding.open("bilibili://space/24")) }
                assertEquals(1, resolutions)
                assertSame(f.source, f.routes.currentKey)
                target.complete(com.android.purebilibili.core.util.BilibiliNavigationTarget.Space(23L))
                f.run(job)
                assertEquals(BiliPaiNavKey.Space(23L), f.routes.currentKey)
                f.backToBgm()
                beginContentLink(f, queuedContentLink(f, binding, "bilibili://space/24"))
                assertEquals(BiliPaiNavKey.Space(24L), f.routes.currentKey)
            }
        }
    }

    @Test fun originalContentShortLinkCannotPublishAfterPhysicalNavigationAwayAndBack() {
        Fixture().use { f ->
            val target = CompletableDeferred<com.android.purebilibili.core.util.BilibiliNavigationTarget?>()
            DesktopOriginalContentLinkBinding(f.routes, stillPresented = { true },
                openExternal = { error("No external launch") }, feedback = { error(it) },
                resolveShortLink = { target.await() }).use { binding ->
                val job = queuedContentLink(f, binding, "https://b23.tv/fixture221")
                beginContentLink(f, job)
                edt { assertTrue(f.routes.push(BiliPaiNavKey.Settings)) }
                f.backToBgm()
                val before = f.stack.toList(); val commits = f.destinationCommits.toList()
                target.complete(com.android.purebilibili.core.util.BilibiliNavigationTarget.Article(31L))
                f.run(job)
                assertEquals(before, f.stack.toList()); assertEquals(commits, f.destinationCommits)
            }
        }
    }

    @Test fun originalContentShortLinkCannotPublishAfterMainHostTabAwayAndBack() {
        Fixture().use { f ->
            val selected = bindMainHostSelection(f)
            val target = CompletableDeferred<com.android.purebilibili.core.util.BilibiliNavigationTarget?>()
            DesktopOriginalContentLinkBinding(f.routes, stillPresented = { true },
                openExternal = { error("No external launch") }, feedback = { error(it) },
                resolveShortLink = { target.await() }).use { binding ->
                val job = queuedContentLink(f, binding, "https://b23.tv/fixture221")
                beginContentLink(f, job)
                edt {
                    assertTrue(f.routes.push(BiliPaiNavKey.Settings))
                    assertTrue(f.routes.push(BiliPaiNavKey.Home))
                }
                target.complete(com.android.purebilibili.core.util.BilibiliNavigationTarget.Space(23L))
                f.run(job)
                assertEquals(0, selected.get())
                assertEquals(listOf(BiliPaiNavKey.MainHost), f.stack.toList())
                assertTrue(f.destinationCommits.isEmpty())
            }
        }
    }

    @Test fun unresolvedOrFailedOriginalContentShortLinkKeepsItsExactWebFallbackAndTitle() {
        for (fail in listOf(false, true)) Fixture().use { f ->
            val raw = "https://b23.tv/fixture221?from=BV1xx411c7mD"
            DesktopOriginalContentLinkBinding(f.routes, stillPresented = { true },
                openExternal = { error("No external launch") }, feedback = { error(it) },
                resolveShortLink = { if (fail) throw java.io.IOException("Synthetic resolution failure") else null }).use { binding ->
                beginContentLink(f, queuedContentLink(f, binding, raw, "保留的链接标题"))
                assertEquals(BiliPaiNavKey.Web(raw, "保留的链接标题"), f.routes.currentKey)
            }
        }
    }

    @Test fun originalContentNativeVideoHandoffSurvivesCompletionOfTheLinkCaller() {
        Fixture().use { f ->
            DesktopOriginalContentLinkBinding(f.routes, stillPresented = { true },
                openExternal = { error("No external launch") }, feedback = { error(it) }).use { binding ->
                val before = f.held.jobs()
                val link = queuedContentLink(f, binding, "bilibili://video/115391124741470")
                beginContentLink(f, link)
                assertTrue(link.isCompleted)
                val video = (f.held.jobs() - before).single()
                assertTrue(video.isActive)
                f.run(video)
                assertIs<BiliPaiNavKey.VideoDetail>(f.routes.currentKey)
                assertEquals(1, f.resolverCalls.size)
            }
        }
    }

    @Test fun closingOrRetiringTheOriginalContentLinkOwnerRejectsItsPendingResult() {
        for (closeBinding in listOf(false, true)) Fixture().use { f ->
            val target = CompletableDeferred<com.android.purebilibili.core.util.BilibiliNavigationTarget?>()
            DesktopOriginalContentLinkBinding(f.routes, stillPresented = { true },
                openExternal = { error("No external launch") }, feedback = { error(it) },
                resolveShortLink = { target.await() }).use { binding ->
                val job = queuedContentLink(f, binding, "https://b23.tv/fixture221")
                beginContentLink(f, job)
                if (closeBinding) binding.close() else f.replaceAccount()
                target.complete(com.android.purebilibili.core.util.BilibiliNavigationTarget.Space(23L))
                f.run(job)
                assertEquals(listOf(BiliPaiNavKey.MainHost, f.source), f.stack.toList())
                assertTrue(f.destinationCommits.isEmpty())
                edt { assertFalse(binding.open("bilibili://space/24")) }
            }
        }
    }

    @Test fun originalContentLookalikesStayExternalAndCompletedOpensKeepTheOriginalDebounce() {
        Fixture().use { f ->
            var now = 0L
            val launches = mutableListOf<String>()
            val raw = "https://bilibili.com.example.invalid/shop"
            DesktopOriginalContentLinkBinding(f.routes, stillPresented = { true },
                openExternal = { launches += it }, feedback = { error(it) }, nowMillis = { now }).use { binding ->
                beginContentLink(f, queuedContentLink(f, binding, raw))
                now = 799L
                edt { assertFalse(binding.open(raw)) }
                now = 800L
                beginContentLink(f, queuedContentLink(f, binding, raw))
                assertEquals(listOf(raw, raw), launches)
                assertEquals(listOf(BiliPaiNavKey.MainHost, f.source), f.stack.toList())
                assertTrue(f.destinationCommits.isEmpty())
            }
        }
    }

    @Test fun inactiveWindowDoesNotAdmitOrPublishOriginalContentLinks() {
        Fixture().use { f ->
            f.leafActive = false
            DesktopOriginalContentLinkBinding(f.routes, stillPresented = { f.leafActive },
                openExternal = { error("No external launch") }, feedback = { error(it) }).use { binding ->
                edt { assertFalse(binding.open("bilibili://space/23")) }
                f.leafActive = true
                val job = queuedContentLink(f, binding, "bilibili://space/23")
                f.leafActive = false
                beginContentLink(f, job)
                assertSame(f.source, f.routes.currentKey)
                assertTrue(f.destinationCommits.isEmpty())
            }
        }
    }

    @Test fun accountReplacementRetiresMainHostSettingsAndItsContentCallback() {
        Fixture().use { f ->
            edt {
                f.stack.clear(); f.stack += BiliPaiNavKey.MainHost
                val destination: () -> BiliPaiNavKey = { BiliPaiNavKey.Settings }
                f.routes.bindMainHostDestination(destination)
                assertTrue(f.routes.isCurrentContent(BiliPaiNavKey.Settings))
                f.replaceAccount()
                assertFalse(f.routes.isCurrentContent(BiliPaiNavKey.Settings))
                var calls = 0
                f.routes.callbackForContent(BiliPaiNavKey.Settings) { calls++ }
                assertEquals(0, calls)
                f.routes.unbindMainHostDestination(destination)
            }
        }
    }

    @Test fun nativePrimaryAndNestedCommentLinksKeepTheirWholeReplyDestinationsInTheActualStack() {
        Fixture().use { f ->
            f.click("bilibili://comment/detail/14/470/701?comment_id=703&enterUri=bilibili%3A%2F%2Fmusic%3Fmusic_id%3DMA47")
            val comment = assertIs<BiliPaiNavKey.CommentDetail>(f.routes.currentKey)
            assertEquals(BiliPaiNavKey.CommentDetail(470L, 701L, 703L, 14,
                "bilibili://music?music_id=MA47"), comment)
            assertEquals(listOf(BiliPaiNavKey.MainHost, f.source, comment), f.stack.toList())
            assertEquals(listOf<Pair<BiliPaiNavKey, BiliPaiNavKey>>(f.source to comment), f.destinationCommits)
            f.backToBgm()
            f.click("bilibili://browser/?url=https%3A%2F%2Fwww.bilibili.com%2Fh5%2Fcomment%2Fsub%3Foid%3D470%26pageType%3D14%26root%3D701%26comment_secondary_id%3D709")
            val nested = assertIs<BiliPaiNavKey.CommentDetail>(f.routes.currentKey)
            assertEquals(BiliPaiNavKey.CommentDetail(470L, 701L, 709L, 14, "bilibili://video/470"), nested)
            assertSame(f.source, f.stack[f.stack.lastIndex - 1])
            assertTrue(f.resolverCalls.isEmpty())
        }
    }

    @Test fun acceptedNativeCommentTargetsUseTheOriginalRootPushForSpaceLiveDynamicArticleBangumiAndMusic() {
        Fixture().use { f ->
            val targets = listOf(
                "bilibili://space/23" to BiliPaiNavKey.Space(23L),
                "bilibili://live/57" to BiliPaiNavKey.Live(roomId = "57"),
                "bilibili://following/detail/123" to BiliPaiNavKey.DynamicDetail("123"),
                "bilibili://article/31" to BiliPaiNavKey.ArticleDetail(31L),
                "https://www.bilibili.com/bangumi/play/ss91" to BiliPaiNavKey.BangumiDetail(91L),
                "https://www.bilibili.com/bangumi/play/ep92" to BiliPaiNavKey.BangumiDetail(0L, epId = 92L),
                "bilibili://music?music_id=au93" to BiliPaiNavKey.MusicDetail(93L),
                "bilibili://music?music_id=MA94" to BiliPaiNavKey.BgmDetail("MA94"),
            )
            for ((link, expected) in targets) {
                f.click(link)
                assertEquals(expected, f.routes.currentKey, link)
                assertEquals(listOf(BiliPaiNavKey.MainHost, f.source, expected), f.stack.toList(), link)
                assertEquals(f.source to expected, f.destinationCommits.last(), link)
                f.backToBgm()
            }
            assertEquals(targets.size * 2, f.checkpoints.get())
            assertTrue(f.resolverCalls.isEmpty())
        }
    }

    @Test fun videoAndVideoCommentLinksReachTheRealResolverWithSourceAndWholeReplyContext() {
        Fixture().use { f ->
            val plain = f.queuedVideo("bilibili://video/115391124741470?page=0")
            assertTrue(f.resolverCalls.isEmpty())
            f.run(plain)
            val first = f.resolverCalls.single()
            assertEquals("av115391124741470", first.first.bvid)
            assertEquals(f.source.toLegacyRoute(), first.first.sourceRoute)
            assertTrue(first.second, "Plain message video retains direct-entry routing")
            assertIs<BiliPaiNavKey.VideoDetail>(f.routes.currentKey)
            f.backToBgm()
            val replies = f.queuedVideo("https://www.bilibili.com/video/BV1xx411c7mD?comment_root_id=701&comment_secondary_id=703")
            f.run(replies)
            val last = f.resolverCalls.last()
            assertEquals("BV1xx411c7mD", last.first.bvid)
            assertEquals(701L, last.first.commentRootRpid); assertEquals(703L, last.first.commentTargetRpid)
            assertEquals(f.source.toLegacyRoute(), last.first.sourceRoute)
            assertFalse(last.second, "Video-comment handoff keeps the original non-direct routing")
            val destination = assertIs<BiliPaiNavKey.VideoDetail>(f.routes.currentKey)
            assertEquals("BV1xx411c7mD", destination.bvid)
            assertEquals(last.first.cid, destination.cid); assertEquals(last.first.coverUrl, destination.coverUrl)
            assertEquals(last.first.resumePositionMs, destination.resumePositionMs)
            assertFalse(destination.autoPortrait); assertFalse(destination.initialVertical); assertFalse(destination.directPortraitEntry)
            assertEquals(701L, destination.commentRootRpid); assertEquals(703L, destination.commentTargetRpid)
            assertTrue(destination.openId > 0L)
            assertSame(f.source, f.stack[f.stack.lastIndex - 1])
            f.backToBgm()
            // Exercise the unchanged true offline policy, not an invented Offline key.
            val cacheFile = Files.createFile(f.folder.resolve("exists-only-cache.mp4"))
            val cache = DownloadTask(bvid = "av115391124741470", cid = 471L, title = "Memory download metadata",
                cover = "", ownerName = "", ownerFace = "", duration = 47, quality = 80, qualityDesc = "1080P",
                videoUrl = "", audioUrl = "", status = DownloadStatus.COMPLETED, filePath = cacheFile.toString(), createdAt = 47L)
            f.downloads += cache; f.networkAvailable = false
            f.run(f.queuedVideo("bilibili://video/115391124741470"))
            assertEquals(BiliPaiNavKey.OfflineVideoPlayer(cache.id), f.routes.currentKey)
        }
    }

    @Test fun h5CommentLookalikeHostsKeepTheirExactWebDestinationInTheActualRoot() {
        Fixture().use { f ->
            for (host in listOf("bilibili.com.example.invalid", "fakebilibili.com", "www.bilibili.com@evil.invalid")) {
                val raw = "https://$host/h5/comment/sub?oid=470&pageType=14&root=701&comment_secondary_id=703"
                f.click(raw)
                assertEquals(BiliPaiNavKey.Web(raw), f.routes.currentKey, raw)
                assertEquals(listOf(BiliPaiNavKey.MainHost, f.source, BiliPaiNavKey.Web(raw)), f.stack.toList(), raw)
                assertTrue(f.resolverCalls.isEmpty())
                f.backToBgm()
            }
        }
    }

    @Test fun nestedBrowserWrappedH5LookalikesCannotBypassTheActualRootHostGuard() {
        Fixture().use { f ->
            val target = "https://bilibili.com.example.invalid/h5/comment/sub?oid=470&pageType=14&root=701&comment_secondary_id=703"
            fun wrapped(url: String) = "bilibili://browser/?url=" +
                java.net.URLEncoder.encode(url, java.nio.charset.StandardCharsets.UTF_8)
            for (raw in listOf(wrapped(target), wrapped(wrapped(target)))) {
                f.click(raw)
                assertEquals(BiliPaiNavKey.Web(target), f.routes.currentKey, raw)
                assertEquals(listOf(BiliPaiNavKey.MainHost, f.source, BiliPaiNavKey.Web(target)), f.stack.toList(), raw)
                assertTrue(f.resolverCalls.isEmpty())
                f.backToBgm()
            }
        }
    }

    @Test fun genuineH5CommentHostsRetainReplyContextIncludingCaseAndDnsRootDot() {
        Fixture().use { f ->
            val expected = BiliPaiNavKey.CommentDetail(470L, 701L, 703L, 14, "bilibili://video/470")
            for (host in listOf("bilibili.com", "www.bilibili.com", "comment.bilibili.com", "WWW.BILIBILI.COM.")) {
                f.click("https://$host/h5/comment/sub?oid=470&pageType=14&root=701&comment_secondary_id=703")
                assertEquals(expected, f.routes.currentKey, host)
                assertEquals(listOf(BiliPaiNavKey.MainHost, f.source, expected), f.stack.toList(), host)
                assertEquals(f.source to expected, f.destinationCommits.last(), host)
                assertTrue(f.resolverCalls.isEmpty())
                f.backToBgm()
            }
        }
    }

    @Test fun unknownHttpAndShortLinksKeepTheExactWebFallbackWhileUnsupportedSchemesDoNothing() {
        Fixture().use { f ->
            for (raw in listOf("https://example.invalid/exact?q=a%2Bb#chapter", "https://b23.tv/fixture47")) {
                f.click(raw)
                assertEquals(BiliPaiNavKey.Web(raw), f.routes.currentKey)
                f.backToBgm()
            }
            val before = f.stack.toList(); val commits = f.destinationCommits.toList()
            val checkpoints = f.checkpoints.get()
            for (raw in listOf("bilibili://unknown_feature/fixture47", "file:///fixture47", "javascript:fixture47", "")) f.click(raw)
            assertEquals(before, f.stack.toList()); assertEquals(commits, f.destinationCommits)
            assertEquals(checkpoints, f.checkpoints.get()); assertTrue(f.resolverCalls.isEmpty())
        }
    }

    @Test fun inactiveAwayClosedOrRetiredAccountCannotNavigateFromTheRetainedBgmCallback() {
        for (retire in listOf<(Fixture) -> Unit>(
            { it.leafActive = false },
            { f -> edt { assertTrue(f.routes.push(BiliPaiNavKey.Settings)) } },
            { it.routes.close() },
            { it.replaceAccount() },
            { it.replaceAccount(47L) },
        )) {
            Fixture().use { f ->
                retire(f)
                val before = f.stack.toList(); val commits = f.destinationCommits.toList()
                val checkpoints = f.checkpoints.get(); val jobs = f.held.jobs()
                f.click("bilibili://space/23")
                f.click("bilibili://comment/detail/14/470/701?comment_id=703")
                f.click("bilibili://video/115391124741470")
                assertEquals(before, f.stack.toList()); assertEquals(commits, f.destinationCommits)
                assertEquals(checkpoints, f.checkpoints.get()); assertEquals(jobs, f.held.jobs())
                assertTrue(f.resolverCalls.isEmpty())
            }
        }
    }

    @Test fun concurrentAccountReplacementAtTheRealCheckpointCannotCrossStoreEntryAdmission() {
        Fixture().use { f ->
            val checkpoint = CountDownLatch(1); val replaced = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>(null)
            val worker = Thread({
                try {
                    check(checkpoint.await(3, TimeUnit.SECONDS))
                    f.replaceAccount()
                } catch (problem: Throwable) { failure.set(problem) }
                finally { replaced.countDown() }
            }, "bgm-checkpoint-synthetic-account").apply { isDaemon = true; start() }
            f.onCheckpoint = { checkpoint.countDown(); check(replaced.await(3, TimeUnit.SECONDS)) }
            try {
                f.click("bilibili://comment/detail/14/470/701?comment_id=703")
                failure.get()?.let { throw it }
                assertEquals(listOf(BiliPaiNavKey.MainHost, f.source), f.stack.toList())
                assertEquals(1, f.checkpoints.get()); assertTrue(f.destinationCommits.isEmpty())
                assertFalse(f.routes.owns()); assertTrue(f.resolverCalls.isEmpty())
            } finally { checkpoint.countDown(); worker.join(3000L); check(!worker.isAlive) }
        }
    }

    @Test fun queuedAndSuspendedVideoRequestsCannotPublishAfterRealNavigationOrAccountRetirement() {
        Fixture().use { f ->
            val queued = f.queuedVideo("bilibili://video/115391124741470")
            edt { assertTrue(f.routes.push(BiliPaiNavKey.Settings)) }
            f.backToBgm()
            assertFalse(queued.isActive)
            f.run(queued)
            assertTrue(f.resolverCalls.isEmpty()); assertSame(f.source, f.routes.currentKey)
            val release = CompletableDeferred<Unit>()
            f.beforeResolve = { release.await() }
            val pending = f.queuedVideo("https://www.bilibili.com/video/BV1xx411c7mD?comment_root_id=701&comment_secondary_id=703")
            f.run(pending)
            assertEquals(1, f.resolverCalls.size); assertTrue(pending.isActive)
            val beforeReturnState = f.root.returns.session.value
            f.replaceAccount()
            release.complete(Unit)
            f.run(pending)
            assertSame(f.source, f.routes.currentKey)
            assertEquals(beforeReturnState, f.root.returns.session.value)
            assertFalse(pending.isActive)
        }
    }
}
