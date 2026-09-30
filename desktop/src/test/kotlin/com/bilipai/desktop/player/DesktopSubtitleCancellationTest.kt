package com.bilipai.desktop.player

import com.android.purebilibili.data.model.response.PlayerInfoData
import com.android.purebilibili.data.model.response.PlayerInfoResponse
import com.android.purebilibili.data.model.response.SubtitleInfo
import com.android.purebilibili.data.model.response.SubtitleItem
import com.android.purebilibili.feature.video.subtitle.*
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.DynamicTest
import com.android.purebilibili.core.network.BilibiliApi
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

private const val JSON_BODY = """{"body":[{"from":0.0,"to":3.0,"content":"Local subtitle cue"}]}"""
private enum class Mode { SUCCESS, DELAY_HEADERS, DELAY_BODY, MALFORMED, DECLARED_TOO_LARGE, CHUNKED_TOO_LARGE }
private class ObservedRequest(val path: String, val header: String, val mode: Mode) {
    val released = AtomicBoolean(mode !in setOf(Mode.DELAY_HEADERS,Mode.DELAY_BODY))
    val disconnectedBeforeRelease = CountDownLatch(1)
    val bodySent = AtomicBoolean(false)
    fun release() { released.set(true) }
}

/** Actual HTTP/1.1 sockets, including client FIN/reset detection while the server deliberately waits. */
private class SubtitleHttpFixture : AutoCloseable {
    private val server = ServerSocket(0,50,java.net.InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newCachedThreadPool { runnable -> Thread(runnable,"subtitle-loopback-fixture").apply { isDaemon=true } }
    private val closed = AtomicBoolean(false)
    private val sockets = CopyOnWriteArrayList<Socket>()
    private val modes = ConcurrentHashMap<String,Mode>()
    private val statuses = ConcurrentHashMap<String,Int>()
    private val bodies = ConcurrentHashMap<String,String>()
    private val bodyQueues = ConcurrentHashMap<String,ConcurrentLinkedQueue<String>>()
    val requests = CopyOnWriteArrayList<ObservedRequest>()
    val cancelEvents = AtomicInteger()
    val client = OkHttpClient.Builder()
        .readTimeout(30,TimeUnit.SECONDS)
        .addInterceptor { chain ->
            // The product still validates and builds its original trusted HTTPS request. This fixture
            // alone directs that request to a loopback HTTP socket, preserving non-account headers.
            val request=chain.request()
            chain.proceed(request.newBuilder().url(request.url.newBuilder().scheme("http").host("127.0.0.1")
                .port(server.localPort).build()).header("X-Fixture-Header","preserved").build())
        }
        .eventListener(object : EventListener() {
            override fun canceled(call: Call) { cancelEvents.incrementAndGet() }
        }).build()
    init {
        workers.submit {
            while(!closed.get()) {
                val socket=try { server.accept() } catch(_:Exception) { break }
                sockets.add(socket)
                workers.submit { handle(socket) }
            }
        }
    }
    fun route(path: String, mode: Mode=Mode.SUCCESS, status: Int=200, body: String=JSON_BODY) {
        modes[path]=mode; statuses[path]=status; bodies[path]=body
    }
    fun track(id: String, mode: Mode=Mode.SUCCESS, status: Int=200, body: String=JSON_BODY): SubtitleTrackMeta {
        val path="/subtitle/$id.json"; route(path,mode,status,body)
        return SubtitleTrackMeta(id=id.hashCode().toLong().coerceAtLeast(1),lan="en",lanDoc=id,
            subtitleUrl="https://fixture.hdslb.com$path")
    }
    fun await(path: String, count: Int=1): ObservedRequest {
        return awaitRaw("/subtitle/$path.json",count)
    }
    fun awaitRaw(path: String, count: Int=1): ObservedRequest {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while(System.nanoTime()<end) {
            requests.filter { it.path==path }.getOrNull(count-1)?.let { return it }
            Thread.sleep(5)
        }
        error("No loopback request for $path/$count")
    }
    fun playerApi(responses: List<PlayerInfoData>, mode: Mode=Mode.SUCCESS): BilibiliApi {
        val json=Json { ignoreUnknownKeys=true }
        val path="/x/player/wbi/v2"
        val encoded=responses.map { json.encodeToString(PlayerInfoResponse.serializer(),PlayerInfoResponse(data=it)) }
        route(path,mode,body=encoded.last())
        bodyQueues[path]=ConcurrentLinkedQueue(encoded)
        return Retrofit.Builder().baseUrl("https://api.bilibili.com/").client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(BilibiliApi::class.java)
    }
    fun assertCancelled(request: ObservedRequest) {
        assertTrue(request.disconnectedBeforeRelease.await(2,TimeUnit.SECONDS),"No actual socket FIN/reset on cancellation")
        assertTrue(cancelEvents.get()>0,"No task-owned Call.cancel event")
    }
    private fun handle(socket: Socket) {
        try {
            val input=socket.getInputStream()
            val header=StringBuilder()
            while(!header.endsWith("\r\n\r\n")) {
                val next=input.read(); if(next<0)return
                header.append(next.toChar()); check(header.length<64*1024)
            }
            val target=header.toString().lineSequence().first().split(' ')[1]
            val path=target.substringBefore('?')
            val key=if(modes.containsKey(target))target else path
            val mode=modes[key]?:error("Unregistered loopback path")
            val request=ObservedRequest(path,header.toString(),mode)
            val output=socket.getOutputStream()
            val body=(if(mode==Mode.MALFORMED)"{" else bodyQueues[path]?.poll()?:bodies[key]?:JSON_BODY).toByteArray(StandardCharsets.UTF_8)
            fun headers(length: Long) {
                output.write("HTTP/1.1 ${statuses[key]?:200} fixture\r\nContent-Type: application/json\r\nContent-Length: $length\r\nConnection: close\r\n\r\n".toByteArray())
                output.flush()
            }
            var already=0
            if(mode==Mode.DELAY_BODY) { headers(body.size.toLong()); already=5; output.write(body,0,already); output.flush() }
            requests.add(request)
            if(mode in setOf(Mode.DELAY_HEADERS,Mode.DELAY_BODY)) {
                workers.submit {
                    try { input.read() } catch(_:Exception) { }
                    if(!request.released.get()) request.disconnectedBeforeRelease.countDown()
                }
                val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
                while(!request.released.get() && request.disconnectedBeforeRelease.count>0 && System.nanoTime()<end) Thread.sleep(5)
                if(!request.released.get()) return
            }
            when(mode) {
                Mode.DECLARED_TOO_LARGE -> headers(8L*1024*1024+1)
                Mode.CHUNKED_TOO_LARGE -> {
                    output.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n".toByteArray())
                    val block=ByteArray(16*1024) { ' '.code.toByte() }
                    repeat(513) {
                        output.write("4000\r\n".toByteArray()); output.write(block); output.write("\r\n".toByteArray())
                    }
                    output.write("0\r\n\r\n".toByteArray()); output.flush()
                }
                else -> { if(already==0)headers(body.size.toLong()); output.write(body,already,body.size-already); output.flush(); request.bodySent.set(true) }
            }
        } catch(_:Exception) {
            // A reset while writing an intentionally canceled/oversized response is expected.
        } finally { runCatching { socket.close() }; sockets.remove(socket) }
    }
    override fun close() {
        closed.set(true); server.close(); sockets.forEach { runCatching { it.close() } }
        workers.shutdownNow(); client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll()
    }
}

private fun directory(assets: DesktopSubtitleAssets): Path = DesktopSubtitleAssets::class.java.getDeclaredField("directory")
    .apply { isAccessible=true }.get(assets) as Path
private fun fileCount(directory:Path)=if(!Files.exists(directory))0 else Files.list(directory).use { it.count().toInt() }
private suspend fun quietCancellation(job: Deferred<Path>) {
    withTimeout(2_000) { job.cancelAndJoin() }
    assertTrue(job.isCancelled)
}
private fun assertNoLateFiles(directory:Path, request:ObservedRequest) {
    request.release(); Thread.sleep(150)
    assertEquals(0,fileCount(directory),"A canceled or closed subtitle import wrote a late cache/temp file")
}

class DesktopSubtitleCancellationTest {
    @Test fun cancelBeforeHeadersClosesActualConnectionAndWritesNothing() = runBlocking<Unit> {
        SubtitleHttpFixture().use { fixture -> DesktopSubtitleAssets(fixture.client).use { assets ->
            val dir=directory(assets)
            val job=async(Dispatchers.Default) { assets.import(fixture.track("cancel-headers",Mode.DELAY_HEADERS)) }
            val request=fixture.await("cancel-headers")
            quietCancellation(job); fixture.assertCancelled(request); assertNoLateFiles(dir,request)
        } }
    }
    @Test fun cancelAfterHeadersWhileBodyBlocksClosesActualConnection() = runBlocking<Unit> {
        SubtitleHttpFixture().use { fixture -> DesktopSubtitleAssets(fixture.client).use { assets ->
            val dir=directory(assets)
            val job=async(Dispatchers.Default) { assets.import(fixture.track("cancel-body",Mode.DELAY_BODY)) }
            val request=fixture.await("cancel-body")
            quietCancellation(job); fixture.assertCancelled(request); assertNoLateFiles(dir,request)
        } }
    }
    @Test fun assetsCloseCancelsAllOwnedHeaderAndBodyCallsWithoutNewFiles() = runBlocking<Unit> {
        SubtitleHttpFixture().use { fixture -> supervisorScope {
            val assets=DesktopSubtitleAssets(fixture.client); val dir=directory(assets)
            val first=async(Dispatchers.Default) { runCatching { assets.import(fixture.track("close-headers",Mode.DELAY_HEADERS)) } }
            val second=async(Dispatchers.Default) { runCatching { assets.import(fixture.track("close-body",Mode.DELAY_BODY)) } }
            val a=fixture.await("close-headers"); val b=fixture.await("close-body")
            assets.close(); assets.close()
            withTimeout(2_000) { assertTrue(first.await().isFailure); assertTrue(second.await().isFailure) }
            fixture.assertCancelled(a); fixture.assertCancelled(b)
            assertNoLateFiles(dir,a); assertNoLateFiles(dir,b); assertFalse(Files.exists(dir))
            assertTrue(fixture.cancelEvents.get()>=2)
        } }
    }
    @Test fun automaticSessionCloseCancelsBothImportsButKeepsApplicationCacheOwnerAlive() = runBlocking<Unit> {
        SubtitleHttpFixture().use { fixture -> DesktopSubtitleAssets(fixture.client).use { assets ->
            val primary=fixture.track("auto-primary",Mode.DELAY_HEADERS).copy(lan="zh-Hans")
            val secondary=fixture.track("auto-secondary",Mode.DELAY_BODY).copy(lan="en-US")
            val data=object : DesktopAutomaticSubtitleDataSource {
                override suspend fun metadata(bvid:String,cid:Long)=PlayerInfoData(subtitle=SubtitleInfo(subtitles=listOf(primary,secondary).map {
                    SubtitleItem(id=it.id,lan=it.lan,lanDoc=it.lanDoc,subtitleUrl=it.subtitleUrl)
                }))
                override suspend fun import(track:SubtitleTrackMeta)=assets.import(track)
            }
            val installCount=AtomicInteger()
            val player=object : DesktopAutomaticSubtitlePlayer {
                override val controlVersion=0L
                override fun owns(sourceVersion:Long)=sourceVersion==7L
                override fun install(sourceVersion:Long,expectedControlVersion:Long,primary:DesktopOnlineSubtitleAsset?,
                    secondary:DesktopOnlineSubtitleAsset?,mode:SubtitleDisplayMode):Boolean { installCount.incrementAndGet();return true }
            }
            val automatic=DesktopAutomaticSubtitles(data,player)
            val pending=requireNotNull(automatic.load("BV-local",7,7,SubtitleAutoPreference.ON,false))
            val a=fixture.await("auto-primary"); val b=fixture.await("auto-secondary")
            automatic.close(); withTimeout(2_000) { pending.join() }
            fixture.assertCancelled(a); fixture.assertCancelled(b)
            assertNoLateFiles(directory(assets),a); assertNoLateFiles(directory(assets),b)
            assertEquals(0,installCount.get())
            assertTrue(Files.isRegularFile(assets.import(fixture.track("after-session-close"))))
        } }
    }
    @Test fun completedCacheReusedBySameOwnerUntilAssetsCloseWithoutAnotherRequest() = runBlocking<Unit> {
        SubtitleHttpFixture().use { fixture ->
            val assets=DesktopSubtitleAssets(fixture.client)
            val track=fixture.track("cached")
            val first=assets.import(track)
            assertTrue(Files.readString(first).contains("00:00:00,000 --> 00:00:03,000\nLocal subtitle cue"))
            repeat(3) { assertEquals(first,assets.import(track)); assertTrue(Files.isRegularFile(first)) }
            assertEquals(1,fixture.requests.size)
            assertTrue(fixture.requests.single().header.contains("X-Fixture-Header: preserved"))
            assertTrue(fixture.requests.single().header.contains("Referer: https://www.bilibili.com"))
            assertTrue(fixture.requests.single().header.contains("Cache-Control: no-cache"))
            assertTrue(fixture.requests.single().header.contains("Pragma: no-cache"))
            assets.close(); assertFalse(Files.exists(first))
        }
    }
    @Test fun cancelOneTrackDoesNotCancelIndependentSuccessfulTrackOrDeleteItsCache() = runBlocking<Unit> {
        SubtitleHttpFixture().use { fixture -> DesktopSubtitleAssets(fixture.client).use { assets ->
            val bad=async(Dispatchers.Default) { assets.import(fixture.track("independent-cancel",Mode.DELAY_BODY)) }
            val a=fixture.await("independent-cancel")
            val good=assets.import(fixture.track("independent-good"))
            quietCancellation(bad); fixture.assertCancelled(a); a.release(); Thread.sleep(150)
            assertTrue(Files.isRegularFile(good)); assertEquals(1,fileCount(directory(assets)))
            assertTrue(Files.readString(good).contains("Local subtitle cue"))
        } }
    }
    @Test fun canceledDuplicateDoesNotDeleteAnotherSuccessfulImportOfSameUrl() = runBlocking<Unit> {
        SubtitleHttpFixture().use { fixture -> DesktopSubtitleAssets(fixture.client).use { assets ->
            val track=fixture.track("duplicate",Mode.DELAY_HEADERS)
            val first=async(Dispatchers.Default) { assets.import(track) }
            val a=fixture.await("duplicate")
            val second=async(Dispatchers.Default) { assets.import(track) }
            val b=fixture.await("duplicate",2)
            quietCancellation(first); fixture.assertCancelled(a); b.release()
            val path=withTimeout(2_000) { second.await() }
            a.release(); Thread.sleep(150)
            assertTrue(Files.isRegularFile(path)); assertEquals(path,assets.import(track)); assertEquals(1,fileCount(directory(assets)))
        } }
    }
    @Test fun malformedResponseFailsWithoutDamagingPreviouslySuccessfulTrack() = runBlocking<Unit> {
        SubtitleHttpFixture().use { fixture -> DesktopSubtitleAssets(fixture.client).use { assets ->
            val good=assets.import(fixture.track("before-malformed"))
            assertTrue(runCatching { assets.import(fixture.track("malformed",Mode.MALFORMED)) }.isFailure)
            assertTrue(Files.isRegularFile(good)); assertEquals(1,fileCount(directory(assets)))
        } }
    }
    @Test fun declaredEightMiBLimitRemainsEnforcedWithoutCacheWrite() = runBlocking<Unit> {
        SubtitleHttpFixture().use { fixture -> DesktopSubtitleAssets(fixture.client).use { assets ->
            val failure=runCatching { assets.import(fixture.track("declared-oversize",Mode.DECLARED_TOO_LARGE)) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException); assertEquals(0,fileCount(directory(assets)))
        } }
    }
    @Test fun unknownLengthChunkedEightMiBLimitRemainsEnforcedWhileReading() = runBlocking<Unit> {
        SubtitleHttpFixture().use { fixture -> DesktopSubtitleAssets(fixture.client).use { assets ->
            val failure=runCatching { assets.import(fixture.track("chunked-oversize",Mode.CHUNKED_TOO_LARGE)) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException); assertEquals(0,fileCount(directory(assets)))
        } }
    }
    @Test fun untrustedAddressRejectedBeforeAnyCall() = runBlocking<Unit> {
        SubtitleHttpFixture().use { fixture -> DesktopSubtitleAssets(fixture.client).use { assets ->
            assertFailsWith<IllegalArgumentException> { assets.import(SubtitleTrackMeta(lan="en",lanDoc="bad",
                subtitleUrl="https://untrusted.invalid/subtitle.json")) }
            assertEquals(0,fixture.requests.size)
        } }
    }
    @Test fun closedAssetsRejectNewImportBeforeAnyCall() = runBlocking<Unit> {
        SubtitleHttpFixture().use { fixture ->
            val assets=DesktopSubtitleAssets(fixture.client); assets.close()
            assertFailsWith<IllegalStateException> { assets.import(fixture.track("closed")) }
            assertEquals(0,fixture.requests.size)
        }
    }
}

private fun metadataFor(vararg tracks:SubtitleTrackMeta)=PlayerInfoData(subtitle=SubtitleInfo(subtitles=tracks.map {
    SubtitleItem(id=it.id,idStr=it.idStr,lan=it.lan,lanDoc=it.lanDoc,subtitleUrl=it.subtitleUrl,aiStatus=it.aiStatus,aiType=it.aiType)
}))
private class RecordingSubtitlePlayer : DesktopAutomaticSubtitlePlayer {
    data class Installation(val primary:DesktopOnlineSubtitleAsset?,val secondary:DesktopOnlineSubtitleAsset?,val mode:SubtitleDisplayMode)
    override var controlVersion=0L
    var owner=7L
    val installs=CopyOnWriteArrayList<Installation>()
    override fun owns(sourceVersion:Long)=sourceVersion==owner
    override fun install(sourceVersion:Long,expectedControlVersion:Long,primary:DesktopOnlineSubtitleAsset?,
        secondary:DesktopOnlineSubtitleAsset?,mode:SubtitleDisplayMode):Boolean {
        if(!owns(sourceVersion)||expectedControlVersion!=controlVersion)return false
        controlVersion++;installs.add(Installation(primary,secondary,mode));return true
    }
}
private class SubtitleSessionFixture(val fixture:SubtitleHttpFixture,responses:List<PlayerInfoData>,metadataMode:Mode=Mode.SUCCESS) : AutoCloseable {
    val assets=DesktopSubtitleAssets(fixture.client)
    val player=RecordingSubtitlePlayer()
    val epoch=AtomicLong(1)
    val metadataCalls=AtomicInteger()
    var afterMetadata:(suspend ()->Unit)?=null
    var afterCues:(()->Unit)?=null
    private val api=fixture.playerApi(responses,metadataMode)
    val data=object : DesktopAutomaticSubtitleDataSource {
        override suspend fun metadata(bvid:String,cid:Long):PlayerInfoData {
            metadataCalls.incrementAndGet()
            val result=requireNotNull(api.getPlayerInfo(mapOf("bvid" to bvid,"cid" to cid.toString())).data)
            afterMetadata?.invoke();return result
        }
        override suspend fun import(track:SubtitleTrackMeta)=assets.import(track)
        override suspend fun cues(file:Path)=assets.cues(file).also { afterCues?.invoke() }
    }
    val session=DesktopAutomaticSubtitles(data,player,sessionEpoch=epoch::get)
    fun load()=requireNotNull(session.load("BV-local",7,player.owner,SubtitleAutoPreference.ON,false))
    override fun close() { session.close();assets.close() }
}

class DesktopSubtitleSessionParityTest {
    @TestFactory fun authLikeFailuresUseOriginalOneTimePlayerInfoRefresh()=listOf(401,403,404,410,412).map { status ->
        DynamicTest.dynamicTest("actual HTTP $status refreshes original playerinfo once and retains healthy track") {
            runBlocking {
                SubtitleHttpFixture().use { fixture ->
                    val old=fixture.track("expired-$status",status=status).copy(id=11,lan="zh-Hans")
                    val fresh=fixture.track("renewed-$status").copy(id=11,lan="zh-Hans")
                    val secondary=fixture.track("healthy-$status").copy(id=22,lan="en-US")
                    SubtitleSessionFixture(fixture,listOf(metadataFor(old,secondary),metadataFor(fresh,secondary))).use { h ->
                        withTimeout(5_000) { h.load().join() }
                        assertEquals(2,h.metadataCalls.get())
                        assertEquals(fresh.subtitleUrl,h.player.installs.last().primary?.track?.subtitleUrl)
                        assertEquals(secondary.subtitleUrl,h.player.installs.last().secondary?.track?.subtitleUrl)
                        assertEquals(SubtitleDisplayMode.BILINGUAL,h.session.state.value.mode)
                        assertEquals(1,fixture.requests.count { it.path=="/subtitle/healthy-$status.json" })
                        assertEquals(2,fixture.requests.count { it.path=="/x/player/wbi/v2" })
                    }
                }
            }
        }
    }
    @Test fun nonAuthFailureDoesNotRefreshAndPromotesSuccessfulSecondary()=runBlocking<Unit> {
        SubtitleHttpFixture().use { f ->
            val primary=f.track("failed-primary",status=500).copy(lan="zh-Hans")
            val secondary=f.track("promoted-secondary").copy(lan="en-US")
            SubtitleSessionFixture(f,listOf(metadataFor(primary,secondary))).use { h ->
                withTimeout(5_000) { h.load().join() }
                assertEquals(1,h.metadataCalls.get());assertEquals("en-US",h.player.installs.last().primary?.track?.lan)
                assertNull(h.player.installs.last().secondary);assertEquals(SubtitleDisplayMode.PRIMARY_ONLY,h.session.state.value.mode)
                assertNull(h.session.state.value.error)
            }
        }
    }
    @Test fun repeatedAuthFailureStopsAfterOneRefreshAndKeepsHealthyTrack()=runBlocking<Unit> {
        SubtitleHttpFixture().use { f ->
            val primary=f.track("still-expired",status=403).copy(lan="zh-Hans")
            val secondary=f.track("still-healthy").copy(lan="en-US")
            SubtitleSessionFixture(f,listOf(metadataFor(primary,secondary))).use { h ->
                withTimeout(5_000) { h.load().join() }
                assertEquals(2,h.metadataCalls.get());assertEquals(2,f.requests.count { it.path=="/subtitle/still-expired.json" })
                assertEquals(1,f.requests.count { it.path=="/subtitle/still-healthy.json" })
                assertEquals("en-US",h.player.installs.last().primary?.track?.lan)
                assertEquals(SubtitleDisplayMode.PRIMARY_ONLY,h.session.state.value.mode)
            }
        }
    }
    @Test fun refreshMatchesOriginalBindingKeyBeforeLanguageWhenSignedQueryChanges()=runBlocking<Unit> {
        SubtitleHttpFixture().use { f ->
            val base=f.track("signed-path").copy(id=11,lan="zh-Hans",lanDoc="Z correct")
            val old=base.copy(subtitleUrl=base.subtitleUrl+"?ticket=old")
            val fresh=base.copy(subtitleUrl=base.subtitleUrl+"?ticket=new")
            f.route("/subtitle/signed-path.json?ticket=old",status=403)
            f.route("/subtitle/signed-path.json?ticket=new")
            val wrong=f.track("same-language-wrong").copy(id=12,lan="zh-Hans",lanDoc="A wrong")
            val secondary=f.track("signed-secondary").copy(id=22,lan="en-US")
            SubtitleSessionFixture(f,listOf(metadataFor(old,secondary),metadataFor(wrong,fresh,secondary))).use { h ->
                withTimeout(5_000) { h.load().join() }
                assertEquals(fresh.subtitleUrl,h.player.installs.last().primary?.track?.subtitleUrl)
                assertTrue(f.requests.none { it.path=="/subtitle/same-language-wrong.json" })
            }
        }
    }
    @Test fun secondaryFailureKeepsOriginalPrimaryWithoutDiscardingItsNativeAsset()=runBlocking<Unit> {
        SubtitleHttpFixture().use { f ->
            val primary=f.track("retained-primary").copy(lan="zh-Hans")
            val secondary=f.track("failed-secondary",status=500).copy(lan="en-US")
            SubtitleSessionFixture(f,listOf(metadataFor(primary,secondary))).use { h ->
                withTimeout(5_000) { h.load().join() }
                val chosen=h.player.installs.last()
                assertEquals(primary.subtitleUrl,chosen.primary?.track?.subtitleUrl);assertNull(chosen.secondary)
                assertTrue(Files.isRegularFile(requireNotNull(chosen.primary).file))
                assertEquals(SubtitleDisplayMode.PRIMARY_ONLY,chosen.mode)
            }
        }
    }
    @Test fun originalCueQualityPolicyPromotesDenseSecondaryOverOneLongPrimary()=runBlocking<Unit> {
        SubtitleHttpFixture().use { f ->
            val dense="""{"body":["""+(0..9).joinToString(",") { """{"from":$it,"to":${it+1},"content":"cue $it"}""" }+"]}"
            val primary=f.track("low-quality-primary",body="""{"body":[{"from":0,"to":40,"content":"one long cue"}]}""").copy(lan="zh-Hans")
            val secondary=f.track("dense-secondary",body=dense).copy(lan="en-US")
            SubtitleSessionFixture(f,listOf(metadataFor(primary,secondary))).use { h ->
                withTimeout(5_000) { h.load().join() }
                val chosen=h.player.installs.last()
                assertEquals("en-US",chosen.primary?.track?.lan);assertNull(chosen.secondary)
                assertEquals(10,h.assets.cues(requireNotNull(chosen.primary).file).size)
                assertEquals(2,fileCount(directory(h.assets)),"Application cache must preserve unused legal native assets")
            }
        }
    }
    @Test fun sameMidEpochChangeCancelsActualPlayerInfoConnectionBeforeAnyImport()=runBlocking<Unit> {
        SubtitleHttpFixture().use { f ->
            val primary=f.track("not-imported").copy(lan="zh-Hans")
            SubtitleSessionFixture(f,listOf(metadataFor(primary)),Mode.DELAY_BODY).use { h ->
                val pending=h.load();val request=f.awaitRaw("/x/player/wbi/v2")
                h.epoch.incrementAndGet();h.session.onSessionChanged()
                withTimeout(2_000) { pending.join() };f.assertCancelled(request);assertNoLateFiles(directory(h.assets),request)
                assertTrue(h.player.installs.isEmpty());assertEquals(1,f.requests.size)
                assertEquals(DesktopAutomaticSubtitleState(),h.session.state.value)
            }
        }
    }
    @Test fun nonCooperativeMetadataReturnedAfterEpochChangeCannotStartImports()=runBlocking<Unit> {
        SubtitleHttpFixture().use { f ->
            val primary=f.track("stale-metadata-primary").copy(lan="zh-Hans")
            SubtitleSessionFixture(f,listOf(metadataFor(primary))).use { h ->
                val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
                h.afterMetadata={ entered.complete(Unit);withContext(NonCancellable) { release.await() } }
                val pending=h.load();withTimeout(2_000) { entered.await() }
                h.epoch.incrementAndGet();release.complete(Unit);withTimeout(2_000) { pending.join() }
                assertTrue(h.player.installs.isEmpty());assertEquals(1,f.requests.size);assertEquals(0,fileCount(directory(h.assets)))
                assertEquals(DesktopAutomaticSubtitleState(),h.session.state.value)
            }
        }
    }
    @Test fun epochChangeDuringBothBodyImportsCancelsOnlyThoseOwnedCalls()=runBlocking<Unit> {
        SubtitleHttpFixture().use { f ->
            val p=f.track("epoch-primary",Mode.DELAY_BODY).copy(lan="zh-Hans")
            val s=f.track("epoch-secondary",Mode.DELAY_HEADERS).copy(lan="en-US")
            SubtitleSessionFixture(f,listOf(metadataFor(p,s))).use { h ->
                val pending=h.load();val a=f.await("epoch-primary");val b=f.await("epoch-secondary")
                h.epoch.incrementAndGet();h.session.onSessionChanged();withTimeout(2_000) { pending.join() }
                f.assertCancelled(a);f.assertCancelled(b);assertNoLateFiles(directory(h.assets),a);assertNoLateFiles(directory(h.assets),b)
                assertTrue(h.player.installs.isEmpty());assertEquals(DesktopAutomaticSubtitleState(),h.session.state.value)
                assertTrue(Files.isRegularFile(h.assets.import(f.track("independent-after-epoch"))))
            }
        }
    }
    @Test fun epochGuardAfterCueImportRejectsOldPairWithoutDeletingLegalCache()=runBlocking<Unit> {
        SubtitleHttpFixture().use { f ->
            val primary=f.track("epoch-after-cues").copy(lan="zh-Hans")
            SubtitleSessionFixture(f,listOf(metadataFor(primary))).use { h ->
                h.afterCues={ h.epoch.incrementAndGet() }
                withTimeout(5_000) { h.load().join() }
                assertTrue(h.player.installs.isEmpty());assertEquals(1,fileCount(directory(h.assets)))
                assertFalse(h.session.setDisplayMode(SubtitleDisplayMode.PRIMARY_ONLY))
            }
        }
    }
    @Test fun epochRetiresOwnedBindingManualLockAndModeButPreservesNativeCacheFiles()=runBlocking<Unit> {
        SubtitleHttpFixture().use { f ->
            val primary=f.track("epoch-owned-cache").copy(lan="zh-Hans")
            SubtitleSessionFixture(f,listOf(metadataFor(primary))).use { h ->
                withTimeout(5_000) { h.load().join() };val file=requireNotNull(h.player.installs.last().primary).file
                h.session.onUserTrackSelection(7)
                assertNull(h.session.load("BV-local",7,7,SubtitleAutoPreference.ON,false))
                h.epoch.incrementAndGet();h.session.onSessionChanged()
                assertEquals(SubtitleDisplayMode.OFF,h.player.installs.last().mode)
                assertNull(h.player.installs.last().primary);assertEquals(DesktopAutomaticSubtitleState(),h.session.state.value)
                assertFalse(h.session.setDisplayMode(SubtitleDisplayMode.PRIMARY_ONLY));assertTrue(Files.isRegularFile(file))
                withTimeout(5_000) { h.load().join() };assertEquals(SubtitleDisplayMode.PRIMARY_ONLY,h.session.state.value.mode)
                assertEquals(1,f.requests.count { it.path=="/subtitle/epoch-owned-cache.json" })
            }
        }
    }
    @Test fun epochRetirementDoesNotTouchForeignSourceOrNewManualControlVersion()=runBlocking<Unit> {
        SubtitleHttpFixture().use { f ->
            val primary=f.track("epoch-foreign").copy(lan="zh-Hans")
            SubtitleSessionFixture(f,listOf(metadataFor(primary))).use { h ->
                withTimeout(5_000) { h.load().join() };h.player.owner=8
                h.epoch.incrementAndGet();h.session.onSessionChanged();assertEquals(1,h.player.installs.size)
                assertFalse(h.session.setDisplayMode(SubtitleDisplayMode.OFF))
                h.player.owner=7;withTimeout(5_000) { h.load().join() };val count=h.player.installs.size
                h.player.controlVersion++
                h.epoch.incrementAndGet();h.session.onSessionChanged();assertEquals(count,h.player.installs.size)
            }
        }
    }
    @Test fun epochChangeDuringOneTimeMetadataRefreshCancelsRefreshAndCannotInstallOldHealthyTrack()=runBlocking<Unit> {
        SubtitleHttpFixture().use { f ->
            val primary=f.track("refresh-epoch-expired",status=403).copy(lan="zh-Hans")
            val secondary=f.track("refresh-epoch-good").copy(lan="en-US")
            SubtitleSessionFixture(f,listOf(metadataFor(primary,secondary))).use { h ->
                // After the first real playerinfo response, make the second response body wait.
                h.afterMetadata={ if(h.metadataCalls.get()==1) f.route("/x/player/wbi/v2",Mode.DELAY_BODY,
                    body=Json.encodeToString(PlayerInfoResponse.serializer(),PlayerInfoResponse(data=metadataFor(primary,secondary)))) }
                val pending=h.load();val request=f.awaitRaw("/x/player/wbi/v2",2)
                h.epoch.incrementAndGet();h.session.onSessionChanged();withTimeout(2_000) { pending.join() };f.assertCancelled(request)
                assertTrue(h.player.installs.isEmpty());assertEquals(2,h.metadataCalls.get())
                assertEquals(1,fileCount(directory(h.assets)),"Successful imported documents remain application-owned")
            }
        }
    }
    @Test fun sourceOrManualControlChangedDuringRefreshCannotInstallOldPair()=runBlocking<Unit> {
        for(manual in listOf(false,true)) SubtitleHttpFixture().use { f ->
            val primary=f.track("guard-refresh-expired",status=403).copy(lan="zh-Hans")
            val secondary=f.track("guard-refresh-good").copy(lan="en-US")
            SubtitleSessionFixture(f,listOf(metadataFor(primary,secondary))).use { h ->
                h.afterMetadata={ if(h.metadataCalls.get()==1) f.route("/x/player/wbi/v2",Mode.DELAY_BODY,
                    body=Json.encodeToString(PlayerInfoResponse.serializer(),PlayerInfoResponse(data=metadataFor(primary,secondary)))) }
                val pending=h.load();val request=f.awaitRaw("/x/player/wbi/v2",2)
                if(manual)h.player.controlVersion++ else h.player.owner++
                request.release();withTimeout(2_000) { pending.join() }
                assertTrue(h.player.installs.isEmpty())
                assertEquals(1,f.requests.count { it.path=="/subtitle/guard-refresh-expired.json" },"No retry import after ownership/control changes")
                assertEquals(1,fileCount(directory(h.assets)))
            }
        }
    }
    @Test fun epochRetirementInvalidatesActuallyQueuedMpvSubtitleTransaction()=runBlocking<Unit> {
        SubtitleHttpFixture().use { f ->
            val primary=f.track("native-actor-primary").copy(lan="zh-Hans")
            val assets=DesktopSubtitleAssets(f.client)
            val player=MpvPlayer()
            val owner=player.loadVersioned(PlaybackSource("https://fixture.invalid/unmounted-local-actor",referer=""))
            // Reuse the frozen, independently compiled native-actor fixture: real main private
            // Session.perform and queues, fake C ABI, no DLL/thread/HWND.
            val actorClass=Class.forName("com.bilipai.desktop.player.DormantActor")
            val actor=actorClass.getDeclaredConstructor(MpvPlayer::class.java).apply { isAccessible=true }.newInstance(player)
            fun invoke(name:String,vararg args:Any?):Any?=actorClass.declaredMethods.single { it.name==name }
                .apply { isAccessible=true }.invoke(actor,*args)
            val native=requireNotNull(invoke("getNative"))
            val properties=native.javaClass.getDeclaredMethod("getProperties").apply { isAccessible=true }.invoke(native) as List<*>
            val api=f.playerApi(listOf(metadataFor(primary)))
            val data=object : DesktopAutomaticSubtitleDataSource {
                override suspend fun metadata(bvid:String,cid:Long)=requireNotNull(api.getPlayerInfo(mapOf("bvid" to bvid,"cid" to cid.toString())).data)
                override suspend fun import(track:SubtitleTrackMeta)=assets.import(track)
                override suspend fun cues(file:Path)=assets.cues(file)
            }
            val epoch=AtomicLong(1)
            val session=DesktopAutomaticSubtitles(data,MpvAutomaticSubtitlePlayer(player),sessionEpoch=epoch::get)
            try {
                withTimeout(5_000) { requireNotNull(session.load("BV-actor",7,owner,SubtitleAutoPreference.ON,false)).join() }
                val queuedOriginal=requireNotNull(invoke("queued"))
                val oldControl=player.currentSubtitleControlVersion
                epoch.incrementAndGet();session.onSessionChanged()
                assertTrue(player.currentSubtitleControlVersion>oldControl)
                val queuedRetirement=requireNotNull(invoke("queued"))
                invoke("execute",queuedOriginal)
                assertTrue(properties.isEmpty(),"Retired automatic transaction must not reach C ABI")
                invoke("execute",queuedRetirement)
                assertEquals(listOf("sub-visibility" to "no","secondary-sub-visibility" to "no"),properties)
                assertEquals(owner,player.currentSourceVersion)
                assertEquals(1,fileCount(directory(assets)))
            } finally {
                session.close();invoke("close");player.close();assets.close()
            }
        }
    }
}
