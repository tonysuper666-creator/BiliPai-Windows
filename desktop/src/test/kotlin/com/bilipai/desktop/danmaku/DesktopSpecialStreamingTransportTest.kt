package com.bilipai.desktop.danmaku

import kotlinx.coroutines.*
import com.android.purebilibili.data.repository.DanmakuRepository
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.data.DesktopSessionStore
import com.bilipai.desktop.download.DownloadDanmakuTransport
import com.bilipai.desktop.download.DesktopDownloadDanmakuRequestPolicy
import com.bilipai.desktop.player.DesktopRepositoryPlaybackPublication
import com.bilipai.desktop.player.PlaybackSource
import java.nio.file.Files
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

/** Actual Retrofit streaming declarations and original RemoteSpecialSource.
 * Intercepted responses are private virtual streams, not live CDN/profile IO. */
class DesktopSpecialStreamingTransportTest {
    @Test fun `original HTTP200 range fallback skips a stream larger than the ordinary buffered cap`(): Unit = runBlocking {
        val bodies=mutableListOf<VirtualBody>()
        val client=OkHttpClient.Builder().addInterceptor {chain->
            val body=VirtualBody(20L*1024*1024);synchronized(bodies){bodies+=body}
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
        }.build()
        val source=ApiDesktopDanmakuSource(client).openSpecial("https://comment.bilibili.com/private.pb")
        assertEquals(20L*1024*1024,source.byteLength)
        assertTrue(bodies.first().readBytes.get() in 1024L..8192L,"Retrofit must retain only its bounded stream prefix")
        assertEquals(24,source.readRange(20_000,24).size)
        assertTrue(bodies.last().readBytes.get() in 20_024L..28_216L)
        assertTrue(bodies.all {it.closed})
    }

    @Test fun `incorrect206 range is rejected and closes its real body`(): Unit = runBlocking {
        val bodies=mutableListOf<VirtualBody>();var call=0
        val client=OkHttpClient.Builder().addInterceptor {chain->
            val body=VirtualBody(1024);bodies+=body;call++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(206).message("Partial")
                .header("Content-Range",if(call==1)"bytes 0-1023/20000" else "bytes 9-1032/20000").body(body).build()
        }.build()
        val source=ApiDesktopDanmakuSource(client).openSpecial("https://comment.bilibili.com/private.pb")
        assertFailsWith<IOException> {source.readRange(10,24)}
        assertTrue(bodies.all {it.closed})
    }

    @Test fun `cancelling a blocking special range closes the body before joining its IO child`(): Unit = runBlocking {
        withTimeout(4000) {
            var call=0;val entered=CountDownLatch(1);val release=CountDownLatch(1)
            var blocking:VirtualBody?=null
            val client=OkHttpClient.Builder().addInterceptor {chain->
                val body=VirtualBody(20000,if(++call==1)null else entered,release)
                if(call>1)blocking=body
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
            }.build()
            val source=ApiDesktopDanmakuSource(client).openSpecial("https://comment.bilibili.com/private.pb")
            val request=async(Dispatchers.Default) {source.readRange(10,24)}
            try {assertTrue(withContext(Dispatchers.IO){entered.await(2,TimeUnit.SECONDS)});request.cancelAndJoin();assertTrue(blocking!!.closed)}
            finally {release.countDown();request.cancelAndJoin()}
        }
    }

    @Test fun `unknown length sources and cumulative transfer use a bounded source rather than body bytes`(): Unit {
        val stream=VirtualBody(100_000)
        val bounded=BoundedDanmakuBody(stream,8)
        assertFailsWith<IOException> {bounded.source().readByteArray()}
        assertTrue(stream.readBytes.get()<=8192)
        bounded.close();assertTrue(stream.closed)
    }

    private suspend fun <T> withDownloadApi(body:ResponseBody,block:suspend CoroutineScope.()->T):T = coroutineScope {
        val root=Files.createTempDirectory("private-special-authority-")
        val repository=DesktopRepository(DesktopSessionStore(root.resolve("session.json"),persistent=false))
        val authorization=repository.capturePlaybackAuthorization(repository.sessionEpoch) {true}
        val source=PlaybackSource("https://media.bilivideo.com/private-fixture.m4s",
            authorizationReceipt=authorization.receipt)
        val publication=DesktopRepositoryPlaybackPublication(repository)
        val gate=Any()
        val transport=repository.httpClient.newBuilder().proxy(java.net.Proxy.NO_PROXY).retryOnConnectionFailure(false)
            .addInterceptor {chain->
                val request=chain.request()
                check(request.method=="GET" && request.url.toString()=="https://comment.bilibili.com/private.pb")
                val policy=checkNotNull(request.tag(DesktopDownloadDanmakuRequestPolicy::class.java))
                policy.validate(request)
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("private stream").body(body).build()
            }.build()
        // Keep the Repository's real epoch/body policy; replace only this private fixture's transport.
        DesktopRepository::class.java.getDeclaredField("client").apply {isAccessible=true}.set(repository,transport)
        try {
            DownloadDanmakuTransport.withTask(source,publication,currentCoroutineContext().job,{true},
                {action->synchronized(gate) {action();true}}) {
                DownloadDanmakuTransport.withApi(repository) {
                    val binding=DownloadDanmakuTransport.currentBinding()
                    assertSame(source,binding.owner.source)
                    assertSame(repository,binding.repository)
                    coroutineScope {block()}
                }
            }
        } finally {
            transport.connectionPool.evictAll()
            transport.dispatcher.executorService.shutdown()
            root.toFile().deleteRecursively()
        }
    }
    @Test fun `original offline special export writes the real stream without a whole byte array`(): Unit = runBlocking {
        val body=VirtualBody(512*1024);val file=Files.createTempFile("private-special-export-",".pb")
        try {
            withDownloadApi(body) {
                assertEquals(512L*1024,DanmakuRepository.downloadSpecialDanmaku("https://comment.bilibili.com/private.pb",file.toFile()))
            }
            assertEquals(512L*1024,Files.size(file));assertTrue(body.closed)
        } finally {Files.deleteIfExists(file)}
    }
    @Test fun `cancelled original stream export deletes only its partial owned destination`(): Unit = runBlocking {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val body=VirtualBody(512*1024,entered,release)
        val file=Files.createTempFile("private-special-cancel-",".pb")
        try {
            withDownloadApi(body) {withTimeout(4000) {
                val work=async(Dispatchers.Default) {DanmakuRepository.downloadSpecialDanmaku("https://comment.bilibili.com/private.pb",file.toFile())}
                try {assertTrue(withContext(Dispatchers.IO) {entered.await(2,TimeUnit.SECONDS)});work.cancelAndJoin()}
                finally {release.countDown();work.cancelAndJoin()}
            }}
            assertTrue(body.closed);assertFalse(Files.exists(file))
        } finally {release.countDown();Files.deleteIfExists(file)}
    }
    @Test fun `failed original stream export returns absent and deletes its partial destination`(): Unit = runBlocking {
        val body=VirtualBody(512*1024,failRead=true);val file=Files.createTempFile("private-special-failure-",".pb")
        try {
            withDownloadApi(body) {assertNull(DanmakuRepository.downloadSpecialDanmaku("https://comment.bilibili.com/private.pb",file.toFile()))}
            assertFalse(Files.exists(file));assertTrue(body.closed)
        } finally {Files.deleteIfExists(file)}
    }

    private class VirtualBody(val length:Long,val entered:CountDownLatch?=null,val release:CountDownLatch=CountDownLatch(0),val failRead:Boolean=false):ResponseBody() {
        val readBytes=AtomicLong();@Volatile var closed=false
        private val input=object:Source {
            override fun timeout()=Timeout.NONE
            override fun close() {closed=true;release.countDown()}
            override fun read(sink:Buffer,byteCount:Long):Long {
                if(failRead)throw IOException("Private stream failure")
                if(entered!=null) {entered.countDown();check(release.await(3,TimeUnit.SECONDS));if(closed)throw IOException("Private body closed")}
                if(readBytes.get()>=length)return -1
                val count=minOf(byteCount,length-readBytes.get(),8192L).toInt()
                sink.write(ByteArray(count));readBytes.addAndGet(count.toLong());return count.toLong()
            }
        }.buffer()
        override fun source()=input
        override fun contentLength()=length
        override fun contentType()="application/octet-stream".toMediaType()
    }
}
