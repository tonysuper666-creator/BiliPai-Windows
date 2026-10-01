package com.bilipai.desktop.data

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

private var assertions = 0
private fun expect(value: Boolean, reason: String) { check(value) { reason }; assertions++ }
private fun field(receiver: Any, name: String, value: Any?) {
    receiver.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(receiver, value)
}
private fun seed(store: DesktopSessionStore) {
    store.saveAccount(mapOf("SESSDATA" to "fixture-secondary", "bili_jct" to "csrf-two", "buvid3" to "visitor-two"),
        AccountSummary(2, "Synthetic secondary", "", true), imported = true,
        credentials = DesktopAppCredentials("token-two", "refresh-two", "tv", 0))
    store.saveAccount(mapOf("SESSDATA" to "fixture-main", "bili_jct" to "csrf-one", "buvid3" to "visitor-one"),
        AccountSummary(1, "Synthetic primary", ""), imported = true,
        credentials = DesktopAppCredentials("token-one", "refresh-one", "tv", 0))
}
private data class Seen(val path: String, val query: Map<String, String>, val cookies: Map<String, String>)
private class MemoryTransport(val repository: DesktopRepository, val store: DesktopSessionStore,
    val handler: (Seen) -> String) : AutoCloseable {
    val seen = CopyOnWriteArrayList<Seen>()
    private val original = repository.httpClient
    // A task-only terminal APPLICATION interceptor; no socket/BridgeInterceptor is executed.
    // load/save below invokes the actual candidate Store CookieJar in its actual interceptor TLS.
    private val memory = original.newBuilder().addInterceptor { chain ->
        val request = chain.request()
        val value = Seen(request.url.encodedPath,
            request.url.queryParameterNames.associateWith { request.url.queryParameter(it).orEmpty() },
            store.loadForRequest(request.url).associate { it.name to it.value })
        seen += value
        val body = handler(value)
        store.saveFromResponse(request.url, emptyList())
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("synthetic memory")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }.build()
    init {
        field(repository, "client", memory)
        field(repository, "visitorInitialized", true)
        field(repository, "visitorGeneration", repository.sessionEpoch)
        field(repository, "wbiGeneration", repository.sessionEpoch)
        field(repository, "wbiExpiresAt", Long.MAX_VALUE)
        field(repository, "wbiKeys", "ea1db124af3c7062474693fa704f4ff8" to "b7877ae42cfc7062474693fa704f4ff8")
    }
    override fun close() { memory.dispatcher.cancelAll(); memory.dispatcher.executorService.shutdown(); memory.connectionPool.evictAll() }
}
private val video = VideoDetails("BV1xx411c7mD", 99, "Synthetic video", "", "", "", 0, 0, listOf(VideoPart(101, "Part", 1)))
private val playable = """{"quality":80,"format":"mp4","timelength":1000,"accept_quality":[80],"accept_description":["1080P"],"durl":[{"order":1,"length":1000,"size":100,"url":"https://fixture.invalid/media.mp4"}]}"""
private fun webBody() = """{"code":0,"message":"ok","data":$playable}"""
private fun pgcBody() = """{"code":0,"message":"ok","result":$playable}"""
private fun season(course: Boolean = false): Pair<BangumiSeason, BangumiEpisode> {
    val episode = BangumiEpisode(8, 99, video.bvid, 101, "8", "", "", 1)
    val raw = if (course) kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(
        com.android.purebilibili.data.model.response.BangumiDetail.serializer(),
        """{"season_id":7,"season_type":10,"title":"Synthetic course"}""") else null
    return BangumiSeason(7, "Synthetic season", "", "", listOf(episode), upstreamDetail = raw) to episode
}

private fun selectionAndCookieContract() {
    val store = DesktopSessionStore.temporary(); seed(store)
    val epoch = store.generation
    val original = store.capturePlaybackAuthorization(epoch) { true }
    val revision = original.receipt.revision
    expect(store.storedAccountSessions().map { it.mid }.toSet() == setOf(1L, 2L), "same sole account catalog")
    expect(!store.setPlaybackAccountMid(999, epoch) { true }, "unverified selection rejected")
    expect(store.playbackAuthorizationRevision.value == revision && store.getPlaybackAccountMid() == null, "invalid selection has no side effect")
    expect(store.setPlaybackAccountMid(2, epoch) { true }, "valid selected session")
    expect(store.generation == epoch && store.activeAccountMid() == 1L, "selection never switches primary epoch/MID")
    val selected = store.capturePlaybackAuthorization(epoch) { true }
    expect(!store.isPlaybackAuthorizationCurrent(original.receipt), "old primary URL authorization retired")
    expect(selected.playbackAccount?.mid == 2L && selected.cookieJar != null, "dedicated stored credentials")
    val url = "https://api.bilibili.com/x/player/wbi/playurl".toHttpUrl()
    val cookies = store.playbackRequestCookies(selected, url) { true }
    expect(cookies["SESSDATA"] == "fixture-secondary" && cookies["bili_jct"] == "csrf-two" && cookies["DedeUserID"] == "2" && cookies["buvid3"] == "visitor-two", "full original cookie projection")
    store.requestGeneration.set(epoch); store.requestAdmission.set { true }; store.requestPlaybackAuthorization.set(selected)
    try {
        expect(store.loadForRequest(url).associate { it.name to it.value } == cookies, "actual selected CookieJar load")
        store.saveFromResponse(url, listOf(Cookie.Builder().name("fixture_response").value("two").domain("bilibili.com").build()))
        expect(store.loadForRequest(url).any { it.name == "fixture_response" && it.value == "two" }, "original isolated response-cookie merge")
        expect(store.currentCookies()["SESSDATA"] == "fixture-main" && store.currentCookies()["fixture_response"] == null, "selected response cannot mutate primary saved cookies")
        store.setPlaybackAccountMid(null, epoch) { true }
        expect(runCatching { store.loadForRequest(url) }.exceptionOrNull() is java.io.IOException, "retired cookie load rejected")
        store.saveFromResponse(url, listOf(Cookie.Builder().name("SESSDATA").value("late-secondary").domain("bilibili.com").build()))
        expect(store.currentCookies()["SESSDATA"] == "fixture-main", "retired response cannot save primary credentials")
    } finally { store.requestPlaybackAuthorization.remove();store.requestAdmission.remove();store.requestGeneration.remove() }
    val main = store.capturePlaybackAuthorization(epoch) { true }
    expect(main.cookieJar == null && main.playbackAccount == null, "null original main fallback")
    expect(store.setPlaybackAccountMid(1, epoch) { true }, "select current MID")
    expect(store.capturePlaybackAuthorization(epoch) { true }.cookieJar == null, "same MID uses original main transport")
    val current = store.capturePlaybackAuthorization(epoch) { true }
    store.saveAccount(store.currentCookies(), store.account.value!!, imported = true,
        credentials = DesktopAppCredentials("token-one-new", "refresh-one", "tv", 0))
    expect(store.generation == epoch && !store.isPlaybackAuthorizationCurrent(current.receipt), "same MID/token update retires URL without primary epoch change")
    store.setPlaybackAccountMid(2, epoch) { true };store.logout()
    expect(store.activeAccountMid() == null && store.getPlaybackAccountMid() == 2L && store.getPlaybackAccount()?.mid == 2L, "original logout retains dedicated selection")
    expect(store.removeAccount(2) && store.getPlaybackAccountMid() == null, "remove selected account clears playback_mid")
    expect(store.capturePlaybackAuthorization(store.generation) { true }.cookieJar == null, "removed account falls back to main/guest")
    expect(runCatching { store.setPlaybackAccountMid(1, epoch) { true } }.exceptionOrNull() is CancellationException, "old primary epoch cannot set selection")
    expect(runCatching { store.setPlaybackAccountMid(1, store.generation) { false } }.exceptionOrNull() is CancellationException, "closed owner cannot set selection")
}

private suspend fun videoTransportAndCache() {
    val store=DesktopSessionStore.temporary();seed(store);val repo=DesktopRepository(store)
    store.setPlaybackAccountMid(2, store.generation) { true }
    MemoryTransport(repo,store) { webBody() }.use { transport ->
        val selected=repo.playback(video)
        expect(transport.seen.size==1 && transport.seen.single().path=="/x/player/wbi/playurl", "actual video original API")
        expect(transport.seen.single().cookies["SESSDATA"]=="fixture-secondary", "video selected account")
        expect(transport.seen.single().query.let { it["bvid"]==video.bvid && it["cid"]=="101" && it["qn"]=="80" && !it["w_rid"].isNullOrBlank() && !it["wts"].isNullOrBlank() }, "original video params + actual WBI")
        expect(selected.authorizationReceipt!=null && selected.cookieHeader.isEmpty(), "nonsecret returned receipt, no CDN credential leak")
        repo.playback(video)
        expect(transport.seen.size==1, "same authorization uses sole existing URL cache")
        store.setPlaybackAccountMid(null,store.generation) { true }
        val late=AtomicInteger()
        expect(runCatching { repo.withPlaybackSourceAdmission(selected,{true}) { late.incrementAndGet() } }.exceptionOrNull() is CancellationException && late.get()==0, "old source cannot final-publish")
        val main=repo.playback(video)
        expect(transport.seen.size==2 && transport.seen.last().cookies["SESSDATA"]=="fixture-main", "changed selection cannot hit old cache")
        expect(main.authorizationReceipt!=selected.authorizationReceipt && repo.isPlaybackSourceCurrent(main), "cache source new authorization")
        repo.withPlaybackSourceAdmission(main,{true}) { late.incrementAndGet() }
        expect(late.get()==1, "current source final admission")
        val frame=repo.capturePlaybackAuthorization(repo.sessionEpoch) { true }
        val queued=repo.ownedPlaybackCallFactory(frame) { true }.newCall(Request.Builder().url("https://api.bilibili.com/x/player/wbi/playurl").build())
        store.setPlaybackAccountMid(2,store.generation) { true }
        expect(runCatching { queued.execute() }.isFailure && transport.seen.size==2, "old queued immutable tag rejected before terminal transport")
    }
}

private suspend fun fallbackAndRetirement() {
    for(course in listOf(false,true)) {
        val store=DesktopSessionStore.temporary();seed(store);val repo=DesktopRepository(store);store.setPlaybackAccountMid(2,store.generation) { true }
        MemoryTransport(repo,store) { seen ->
            if(seen.path.contains("pugv")) """{"code":-403,"message":"synthetic denied"}"""
            else pgcBody()
        }.use { transport ->
            val (season,ep)=season(course)
            val source=DesktopMediaRepository(repo).bangumiPlaybackInfo(season,ep).source
            expect(source.authorizationReceipt!=null && repo.isPlaybackSourceCurrent(source), "PGC/PUGV receipt")
            expect(transport.seen.all { it.cookies["SESSDATA"]=="fixture-secondary" }, "all fallback candidates retain selected session")
            expect(transport.seen.all { it.query["ep_id"]=="8" && it.query["qn"]=="80" && !it.query["w_rid"].isNullOrBlank() }, "original PGC/PUGV params/WBI")
            expect(if(course) transport.seen.map { it.path }==listOf("/pugv/player/web/playurl","/pgc/player/web/v2/playurl") else transport.seen.map { it.path }==listOf("/pgc/player/web/v2/playurl"), "original course/PGC ordering: ${transport.seen.map { it.path }}")
        }
    }
    val store=DesktopSessionStore.temporary();seed(store);val repo=DesktopRepository(store);store.setPlaybackAccountMid(2,store.generation) { true }
    MemoryTransport(repo,store) { store.setPlaybackAccountMid(null,store.generation) { true }; """{"code":-403,"message":"synthetic denied"}""" }.use { transport ->
        val (season,ep)=season()
        expect(runCatching { DesktopMediaRepository(repo).bangumiPlaybackInfo(season,ep) }.exceptionOrNull() is CancellationException, "selection retirement surfaces cancellation")
        expect(transport.seen.size==1, "retirement never attempts legacy/PUGV/main fallback")
    }
    val cancelledStore=DesktopSessionStore.temporary();seed(cancelledStore);val cancelledRepo=DesktopRepository(cancelledStore)
    val requestJob=Job(); val ownerStillActive={true}
    MemoryTransport(cancelledRepo,cancelledStore) { requestJob.cancel();webBody() }.use { transport ->
        expect(runCatching { withContext(requestJob) { cancelledRepo.playback(video) } }.exceptionOrNull() is CancellationException, "only request Job cancellation")
        expect(transport.seen.size==1 && ownerStillActive() && cancelledStore.activeAccountMid()==1L, "cancel does not retire account/owner or try fallback")
    }
}
private fun encryptedRoundtrip(root:Path) {
    val path=root.resolve("synthetic-encrypted-session.json");val store=DesktopSessionStore(path);seed(store)
    store.setPlaybackAccountMid(2,store.generation) { true }
    val bytes=Files.readString(path)
    expect(bytes.contains("playback_mid") && !bytes.contains("fixture-secondary") && !bytes.contains("token-two"), "one existing DPAPI file, original key, encrypted synthetic credentials")
    val restored=DesktopSessionStore(path)
    expect(restored.getPlaybackAccountMid()==2L && restored.activeAccountMid()==1L && restored.getPlaybackAccount()?.sessData=="fixture-secondary", "encrypted original selection restore")
    restored.removeAccount(2)
    expect(DesktopSessionStore(path).getPlaybackAccountMid()==null, "removal persists selection clearing")
}

fun main(args:Array<String>)=runBlocking {
    val root=Path.of(args.single());Files.createDirectories(root)
    selectionAndCookieContract();videoTransportAndCache();fallbackAndRetirement();encryptedRoundtrip(root)
    val classes=listOf(DesktopSessionStore::class.java,DesktopRepository::class.java,DesktopMediaRepository::class.java,
        DesktopPlaybackCache::class.java,PlaybackSource::class.java,DesktopPlaybackAuthorizationReceipt::class.java,
        com.android.purebilibili.core.store.StoredAccountSession::class.java,
        com.android.purebilibili.core.network.BilibiliApi::class.java,com.android.purebilibili.core.network.BangumiApi::class.java)
    val origins=classes.joinToString(",") { type ->
        val resource="/"+type.name.replace('.','/')+".class"
        val digest=java.security.MessageDigest.getInstance("SHA-256").digest(type.getResourceAsStream(resource)!!.use { it.readBytes() }).joinToString("") { "%02x".format(it) }
        """{"class":"${type.name}","codeSource":"${type.protectionDomain.codeSource.location.toExternalForm()}","classSha256":"$digest"}"""
    }
    Files.writeString(root.resolve("result.json"),"""{"status":"PASS","groups":4,"assertions":$assertions,"scope":"prospective same-Store/session/tag/API memory transport; no sockets, no primary user credentials","actualProductAcceptance":false,"nativeOrRootMounted":false,"origins":[$origins]}"""+"\n")
    println("PASS 4 groups / $assertions assertions")
}
