package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.*
import com.android.purebilibili.core.store.FavoriteFolderCoverCache
import com.android.purebilibili.feature.list.resolveFavoriteFolderCoverPrefetchEnd
import com.bilipai.desktop.data.AccountSummary
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.data.DesktopSessionStore
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.Proxy
import java.net.Proxy as NetworkProxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

/** Actual retained Entry/Environment, original VM/repository/cache and real temporary
 * Session/Plugin Store. The CPU dispatcher controls original coroutine scheduling;
 * its tasks execute on Swing, without a Window, Compose layout, process or native media.
 * HTTP terminates in the sealed memory interceptor; no request reaches the network. */
class DesktopFavoriteCoverPrefetchRegressionTest {
    @TempDir lateinit var directory: Path

    @Test fun originalFrontierFeedsActualVmWithInitialEightDedupAndTwoConcurrentRequests(): Unit = runBlocking {
        Fixture(directory, folderCount = 14, holdCovers = true).useSuspending { f ->
            f.driveUntil { f.vm.folders.value.size == 14 && f.coverCalls.size == 2 && f.activeCovers.get() == 2 }
            assertEquals(2, f.activeCovers.get()); assertEquals(2, f.maxActiveCovers.get())
            assertEquals(setOf(101L, 102L), f.coverCalls.map { it.id }.toSet())
            assertTrue(f.vm.folders.value.all { it.cover.isBlank() })
            f.coverGate.countDown()
            f.driveUntil { f.vm.folders.value.count { it.cover.isNotBlank() } == 8 && f.loadJobs.none() }
            assertEquals((101L..108L).toSet(), f.coverCalls.map { it.id }.toSet())
            val end = resolveFavoriteFolderCoverPrefetchEnd(folderCount = 14, lastVisibleIndex = 8)
            assertEquals(12, end) // original visible frontier plus four
            f.ui {
                val candidates = f.vm.folders.value.take(end + 1)
                f.vm.ensureFavoriteFolderCovers(candidates)
                f.vm.ensureFavoriteFolderCovers(candidates)
            }
            f.driveUntil { f.vm.folders.value.count { it.cover.isNotBlank() } == 13 && f.loadJobs.none() }
            assertEquals((101L..113L).toSet(), f.coverCalls.map { it.id }.toSet())
            assertTrue(f.coverCalls.groupingBy { it.id }.eachCount().values.all { it == 1 })
            assertEquals(2, f.maxActiveCovers.get())
            assertTrue(f.vm.folders.value.last().cover.isBlank())
            assertEquals(1, f.catalogCalls.get())
        }
    }

    @Test fun originalCacheRetainsCountTtlBoundaryDiskPayloadAndAccountIsolation(): Unit = runBlocking {
        Fixture(directory, folderCount = 0).useSuspending { f ->
            f.awaitInitialized()
            f.owned {
                val request = f.captureCacheRequest()
                val cache = f.entry.environment.favoriteCoverCache
                cache.store(request, listOf(FavoriteFolderCoverCache.Resolved(501L, 4, "https://image.invalid/41.jpg")), nowMs = 1_000L)
                val lookup = listOf(FavoriteFolderCoverCache.Lookup(501L, 4))
                val deadline = 1_000L + FavoriteFolderCoverCache.DEFAULT_TTL_MS
                assertEquals(mapOf(501L to "https://image.invalid/41.jpg"), cache.lookupAll(request, lookup, nowMs = deadline))
                assertTrue(cache.lookupAll(request, lookup, nowMs = deadline + 1L).isEmpty())
                assertTrue(cache.lookupAll(request, listOf(FavoriteFolderCoverCache.Lookup(501L, 5)), nowMs = 1_001L).isEmpty())
                // Fresh real original instance must parse the existing serialized payload.
                val fresh = FavoriteFolderCoverCache(f.entry.environment.favoriteCoverContext)
                assertEquals(mapOf(501L to "https://image.invalid/41.jpg"), fresh.lookupAll(request, lookup, nowMs = 1_001L))
            }
            assertTrue(Files.isRegularFile(f.preferenceFile))
            val before41 = f.payload(41L)
            assertNotNull(before41)
            f.switchAccount(42L, coldPreferenceBacking = true)
            f.awaitInitialized()
            f.owned {
                val request = f.captureCacheRequest()
                val cache = f.entry.environment.favoriteCoverCache
                assertTrue(cache.lookupAll(request, listOf(FavoriteFolderCoverCache.Lookup(501L, 4)), nowMs = 1_001L).isEmpty())
                cache.store(request, listOf(FavoriteFolderCoverCache.Resolved(501L, 4, "https://image.invalid/42.jpg")), nowMs = 1_002L)
                assertEquals(mapOf(501L to "https://image.invalid/42.jpg"), cache.lookupAll(request,
                    listOf(FavoriteFolderCoverCache.Lookup(501L, 4)), nowMs = 1_003L))
            }
            assertEquals(before41, f.payload(41L)); assertNotEquals(f.payload(41L), f.payload(42L))
            assertTrue(f.coverCalls.isEmpty())
        }
    }

    @Test fun oldCacheIoContinuationCannotPoisonNewCatalogDedupAfterRealFolderRefresh(): Unit = runBlocking {
        Fixture(directory, folderCount = 10).useSuspending { f ->
            f.awaitInitialCovers(8)
            f.owned {
                f.entry.environment.favoriteCoverCache.store(f.captureCacheRequest(), listOf(
                    FavoriteFolderCoverCache.Resolved(109L, 10, "https://image.invalid/old-109.jpg")))
            }
            val stale = f.launchFromAction { f.vm.ensureFavoriteFolderCovers(f.vm.folders.value.take(9)) }
            assertTrue(f.actor.runJob(stale))
            // Actual original lookup IO has finished and passed its IO-side current check.
            // Hold only its queued original caller continuation, without touching VM state.
            f.waitUntil { f.actor.hasJob(stale) }
            assertFalse(stale.isCompleted)
            val mutation = f.launchFromAction { f.vm.editFavoriteFolder(101L, "Renamed", "", false, f.vm.folders.value.first().cover) }
            f.driveUntil(exclude = stale) {
                mutation.isCompleted && f.vm.folders.value.size == 10 &&
                    f.vm.folders.value.all { it.media_count == 20 }
            }
            assertEquals(2, f.catalogCalls.get())
            assertTrue(f.vm.folders.value.single { it.id == 109L }.cover.isBlank())
            assertTrue(f.actor.runJob(stale))
            // New initial prefetch legitimately waits for the original prefetch mutex.
            // Retire the stale lookup, then allow the new catalog's initial eight.
            f.driveUntil { stale.isCompleted && f.loadJobs.none() &&
                f.vm.folders.value.take(8).all { it.cover.startsWith("https://image.invalid/r2-") } }
            assertTrue(stale.isCancelled)
            val current = f.launchFromAction { f.vm.ensureFavoriteFolderCovers(f.vm.folders.value.take(9)) }
            f.driveUntil {
                current.isCompleted && f.vm.folders.value.single { it.id == 109L }.cover == "https://image.invalid/r2-109.jpg"
            }
            assertEquals(listOf(CoverCall(109L, 2)), f.coverCalls.filter { it.id == 109L })
            assertEquals("https://image.invalid/r2-109.jpg", f.vm.folders.value.single { it.id == 109L }.cover)
        }
    }

    @Test fun actualEntryAccountWindowAndRequestRetirementRejectLateCoverCacheAndUi(): Unit = runBlocking {
        for (retirement in listOf("entry", "account", "window", "request")) {
            Fixture(directory.resolve(retirement), folderCount = 1, holdCovers = true).useSuspending { f ->
                f.driveUntil { f.coverCalls.size == 1 && f.activeCovers.get() == 1 &&
                    f.vm.folders.value.size == 1 && f.loadJobs.count { it.isActive } == 1 }
                val before = f.vm.folders.value
                val beforePayload = f.payload(41L)
                val beforeBytes = f.preferenceBytes()
                val coverCaller = f.loadJobs.single { it.isActive }
                f.ui {
                    when (retirement) {
                        "entry" -> f.entry.close()
                        "account" -> f.saveAccount(42L)
                        "window" -> f.windowAlive.set(false)
                        "request" -> coverCaller.cancel("retire actual original cover caller")
                    }
                }
                f.coverGate.countDown()
                f.driveUntil { f.activeCovers.get() == 0 && coverCaller.isCompleted }
                f.drainReady()
                assertEquals(beforePayload, f.payload(41L), retirement)
                assertEquals(before, f.vm.folders.value, retirement)
                assertEquals(beforeBytes?.toList(), f.preferenceBytes()?.toList(), retirement)
                assertTrue(f.vm.folders.value.single().cover.isBlank(), retirement)
            }
        }
    }

    @Test fun completedActualCacheRequestCannotBorrowAFreshCallerButNewRequestCanCommit(): Unit = runBlocking {
        Fixture(directory, folderCount = 0).useSuspending { f ->
            f.awaitInitialized()
            val escaped = f.owned { f.captureCacheRequest() }
            val before = f.preferenceBytes()
            assertFailsWith<CancellationException> {
                f.owned { f.entry.environment.favoriteCoverCache.store(escaped,
                    listOf(FavoriteFolderCoverCache.Resolved(701L, 1, "https://image.invalid/retired.jpg"))) }
            }
            assertEquals(before?.toList(), f.preferenceBytes()?.toList())
            assertNull(f.payload(41L))
            f.owned {
                val fresh = f.captureCacheRequest()
                val cache = f.entry.environment.favoriteCoverCache
                assertTrue(cache.lookupAll(fresh, listOf(FavoriteFolderCoverCache.Lookup(701L, 1))).isEmpty())
                cache.store(fresh, listOf(FavoriteFolderCoverCache.Resolved(701L, 1, "https://image.invalid/current.jpg")))
                assertEquals(mapOf(701L to "https://image.invalid/current.jpg"), cache.lookupAll(fresh,
                    listOf(FavoriteFolderCoverCache.Lookup(701L, 1))))
            }
            assertNotNull(f.payload(41L)); assertTrue(f.coverCalls.isEmpty())
        }
    }

    internal data class CoverCall(val id: Long, val revision: Int)

    /** Scheduling port only: each original runnable runs on Swing with its own context/Job. */
    internal class UiActor : CoroutineDispatcher() {
        private data class Work(val context: CoroutineContext, val action: Runnable)
        private val pending = CopyOnWriteArrayList<Work>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.add(Work(context, block)) }
        fun hasJob(job: Job) = pending.any { it.context[Job] === job }
        suspend fun runJob(job: Job): Boolean = runNext(include = job)
        suspend fun runNext(exclude: Job? = null, include: Job? = null): Boolean {
            val work = pending.firstOrNull { (exclude == null || it.context[Job] !== exclude) &&
                (include == null || it.context[Job] === include) } ?: return false
            if (!pending.remove(work)) return false
            withContext(Dispatchers.Swing) { work.action.run() }
            return true
        }
    }

    internal class Fixture(val directory: Path, private val folderCount: Int, holdCovers: Boolean = false) {
        val sessions = DesktopSessionStore.temporary()
        val repository = DesktopRepository(sessions)
        var store = DesktopPluginStore(directory.resolve("settings"))
        val preferenceFile get() = directory.resolve("settings/plugin-settings.json")
        val windowAlive = AtomicBoolean(true)
        val actor = UiActor()
        private val parent = SupervisorJob()
        private val failures = CopyOnWriteArrayList<Throwable>()
        private val scope = CoroutineScope(parent + actor + CoroutineExceptionHandler { _, error -> failures.add(error) })
        private val selectedEntry = AtomicReference<DesktopFavoritesRootEntry?>()
        lateinit var entry: DesktopFavoritesRootEntry
        val vm get() = entry.viewModel
        val entryJob get() = checkNotNull(entry.scope.coroutineContext[Job])
        private lateinit var subscriptionObserver: Job
        private var observerStarted = false
        val loadJobs get() = entryJob.children.filter { it !== subscriptionObserver }
        val subscriptionRequests = CopyOnWriteArrayList<Request>()
        val mutationRequests = CopyOnWriteArrayList<Request>()
        val folderInfoRequests = CopyOnWriteArrayList<Request>()
        val folderEditRequests = CopyOnWriteArrayList<Request>()
        @Volatile var folderInfoReply: (Request) -> String = { error("Unprepared original folder metadata request") }
        @Volatile var folderEditReply: ((Request) -> String)? = null
        val fansRequests = CopyOnWriteArrayList<Request>()
        @Volatile var fansReply: (Request) -> String = { error("Unprepared native fans protocol") }
        val followingRequests = CopyOnWriteArrayList<Request>()
        @Volatile var followingReply: (Request) -> String = { error("Unprepared current following protocol") }
        @Volatile var subscriptionReply: (Request) -> String = { """{"code":0,"data":{"count":0,"list":[]}}""" }
        @Volatile var mutationReply: (Request) -> String = { """{"code":0}""" }
        val coverGate = CountDownLatch(if (holdCovers) 1 else 0)
        val activeCovers = AtomicInteger()
        val maxActiveCovers = AtomicInteger()
        val coverCalls = CopyOnWriteArrayList<CoverCall>()
        val catalogCalls = AtomicInteger()
        private val revision = AtomicInteger(1)
        private val transport: OkHttpClient

        init {
            saveAccount(41L)
            transport = repository.httpClient.newBuilder().proxy(NetworkProxy.NO_PROXY).retryOnConnectionFailure(false)
                .addInterceptor { chain ->
                    try {
                        val request = chain.request()
                        check(request.url.isHttps && request.url.host == "api.bilibili.com" && request.url.port == 443)
                        val path = request.url.encodedPath
                        val body = when (path) {
                            "/x/relation/followings" -> {
                                check(request.method == "GET")
                                followingRequests.add(request); followingReply(request)
                            }
                            "/x/relation/tags", "/x/relation/tag" -> {
                                check(request.method == "GET")
                                """{"code":0,"data":[]}"""
                            }
                            "/x/relation/fans" -> {
                                check(request.method == "GET")
                                fansRequests.add(request); fansReply(request)
                            }
                            "/x/web-interface/nav" -> {
                                check(request.method == "GET")
                                """{"code":0,"data":{"isLogin":true,"mid":${repository.account.value!!.mid},"vip":{"status":1}}}"""
                            }
                            "/x/v3/fav/folder/info" -> {
                                check(request.method == "GET")
                                folderInfoRequests.add(request); folderInfoReply(request)
                            }
                            "/x/v3/fav/folder/created/list-all" -> {
                                check(request.method == "GET" && request.url.queryParameter("up_mid") == repository.account.value!!.mid.toString())
                                catalogCalls.incrementAndGet(); folderJson()
                            }
                            "/x/v3/fav/folder/collected/list" -> {
                                check(request.method == "GET" && request.url.queryParameter("ps") == "20")
                                subscriptionRequests.add(request); subscriptionReply(request)
                            }
                            "/x/v3/fav/folder/fav", "/x/v3/fav/folder/unfav",
                            "/x/v3/fav/season/fav", "/x/v3/fav/season/unfav" -> {
                                check(request.method == "POST")
                                mutationRequests.add(request); mutationReply(request)
                            }
                            "/x/v3/fav/resource/list" -> {
                                check(request.method == "GET" && request.url.queryParameter("pn") == "1")
                                val id = checkNotNull(request.url.queryParameter("media_id")).toLong()
                                check(id in 101L until (101L + folderCount))
                                val pageSize = checkNotNull(request.url.queryParameter("ps")).toInt()
                                check(pageSize == 1 || pageSize == 20)
                                val currentRevision = revision.get()
                                if (pageSize == 1) {
                                    coverCalls.add(CoverCall(id, currentRevision))
                                    val active = activeCovers.incrementAndGet()
                                    maxActiveCovers.updateAndGet { maxOf(it, active) }
                                    try { check(coverGate.await(6, TimeUnit.SECONDS)) { "CPU cover response gate was not released" } }
                                    finally { activeCovers.decrementAndGet() }
                                }
                                resourceJson(id, currentRevision)
                            }
                            "/x/v3/fav/folder/edit" -> {
                                check(request.method == "POST")
                                folderEditRequests.add(request)
                                val handler = folderEditReply
                                if (handler != null) handler(request) else {
                                val form = request.body as FormBody
                                val fields = (0 until form.size).associate { form.name(it) to form.value(it) }
                                check(fields["media_id"] == "101" && fields["title"] == "Renamed" && fields["csrf"] == "synthetic-csrf-41")
                                check(revision.compareAndSet(1, 2))
                                """{"code":0}"""
                                }
                            }
                            else -> error("Unexpected bounded Favorite protocol: $path")
                        }
                        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("memory")
                            .body(body.toResponseBody("application/json".toMediaType())).build()
                    } catch (error: Throwable) { failures.add(error); throw error }
                }.build()
            installEntry()
        }

        fun saveAccount(mid: Long, credentialSuffix: String = "") {
            sessions.saveAccount(mapOf("SESSDATA" to "synthetic-session-$mid$credentialSuffix", "bili_jct" to "synthetic-csrf-$mid"),
                AccountSummary(mid, "Fixture $mid", "", isVip = true))
            sessions.saveSpiCookies(mapOf("buvid3" to "synthetic-buvid", "buvid4" to "synthetic-buvid4"), sessions.generation)
        }
        private fun installEntry() {
            val epoch = repository.sessionEpoch
            val capturedEntry = AtomicReference<DesktopFavoritesRootEntry?>()
            fun current() = parent.isActive && windowAlive.get() && repository.sessionEpoch == epoch &&
                capturedEntry.get()?.let { selectedEntry.get() === it && it.isOwned() } == true
            val api = repository.ownedHomeService(BilibiliApi::class.java, "https://api.bilibili.com/", epoch,
                ::current, transport = transport)
            entry = DesktopFavoritesRootEntry(epoch, { repository.sessionEpoch }, scope, api,
                unused(), unused(), unused(), store, { repository.account.value?.mid }, { repository.requireCsrf() }, {},
                rootAlive = { parent.isActive && windowAlive.get() },
                admitCoverCache = { requestCurrent, action ->
                    repository.withPrimaryPlaybackAdmission(epoch, { current() && requestCurrent() }) {
                        if (!current() || !requestCurrent()) false else { action(); true }
                    }
                })
            capturedEntry.set(entry); selectedEntry.set(entry)
            // The controlled dispatcher has not run either task: the original
            // constructor queues its one persistent bus observer, then Root's
            // setPageActive queues the initial load. Preserve the observer in
            // the actual entry scope, and distinguish it from finite load jobs.
            val startup = entryJob.children.toList()
            check(startup.size == 2) { "Favorite startup job topology changed: ${startup.size}" }
            subscriptionObserver = startup.first()
            check(actor.hasJob(subscriptionObserver))
            observerStarted = false
        }
        private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader,
            arrayOf(T::class.java)) { _, method, _ ->
                val error = IllegalStateException("Unexpected Favorite auxiliary API ${T::class.java.simpleName}.${method.name}")
                failures.add(error); throw error
            } as T
        suspend fun switchAccount(mid: Long, coldPreferenceBacking: Boolean, credentialSuffix: String = "") {
            entry.close(); driveUntil { entryJob.children.none() }
            if (coldPreferenceBacking) store.freezeWrites()
            saveAccount(mid, credentialSuffix)
            if (coldPreferenceBacking) store = DesktopPluginStore(directory.resolve("settings"))
            installEntry()
        }
        suspend fun captureCacheRequest(): DesktopFavoriteCoverCacheRequest {
            val catalog = vm.folders.value
            return entry.environment.favoriteCoverContext.capture { vm.folders.value === catalog }
        }
        suspend fun <T> owned(block: suspend () -> T): T {
            val deferred = entry.scope.async { block() }
            driveUntil { deferred.isCompleted }
            return deferred.await()
        }
        suspend fun launchFromAction(action: () -> Unit): Job = withContext(Dispatchers.Swing) {
            val before = entryJob.children.toSet()
            action()
            entryJob.children.single { it !in before }
        }
        suspend fun ui(action: () -> Unit) = withContext(Dispatchers.Swing) { action() }
        suspend fun waitUntil(predicate: () -> Boolean) = withTimeout(6_000) {
            while (!predicate()) { assertTrue(failures.isEmpty(), failures.toString()); delay(1) }
        }
        suspend fun driveUntil(exclude: Job? = null, predicate: () -> Boolean) = withTimeout(6_000) {
            if (!observerStarted) {
                val requestsBefore = catalogCalls.get()
                check(actor.runJob(subscriptionObserver))
                check(subscriptionObserver.isActive && !actor.hasJob(subscriptionObserver))
                check(catalogCalls.get() == requestsBefore) { "Startup observer performed a catalog load" }
                observerStarted = true
            }
            while (!predicate()) {
                assertTrue(failures.isEmpty(), failures.toString())
                if (!actor.runNext(exclude)) delay(1)
            }
        }
        suspend fun drainReady() { while (actor.runNext()) Unit }
        suspend fun awaitInitialized() = driveUntil { !vm.uiState.value.isLoading && loadJobs.none() }
        suspend fun awaitInitialCovers(count: Int) = driveUntil {
            vm.folders.value.size == folderCount && vm.folders.value.count { it.cover.isNotBlank() } == count && loadJobs.none()
        }
        fun payload(mid: Long) = store.preferences("favorite_folder_cover_cache:$mid")["favorite_folder_cover_payload_v1"]
        fun preferenceBytes(): ByteArray? = preferenceFile.takeIf { Files.exists(it) }?.let { Files.readAllBytes(it) }
        private fun folderJson(): String {
            val count = if (revision.get() == 1) 10 else 20
            val mid = repository.account.value!!.mid
            val folders = (101L until (101L + folderCount)).joinToString(",") {
                """{"id":$it,"mid":$mid,"title":"Folder $it","cover":"","media_count":$count}"""
            }
            return """{"code":0,"data":{"count":$folderCount,"list":[$folders]}}"""
        }
        private fun resourceJson(id: Long, version: Int) =
            """{"code":0,"data":{"has_more":false,"medias":[{"id":$id,"type":2,"bvid":"BV1GJ411x7h7","title":"Resource $id","cover":"https://image.invalid/r$version-$id.jpg","duration":60,"ugc":{"first_cid":104}}]}}"""
        suspend fun useSuspending(block: suspend (Fixture) -> Unit) {
            try { withTimeout(25_000) { block(this@Fixture) } }
            finally {
                withContext(NonCancellable) {
                    coverGate.countDown(); entry.close()
                    try {
                        withTimeout(6_000) {
                            while (entryJob.children.any()) { if (!actor.runNext()) delay(1) }
                        }
                        parent.cancel(); drainReady(); parent.join()
                    } finally {
                        parent.cancel()
                        transport.dispatcher.executorService.shutdownNow(); transport.connectionPool.evictAll()
                    }
                    assertTrue(failures.isEmpty(), failures.toString())
                }
            }
        }
    }
}
