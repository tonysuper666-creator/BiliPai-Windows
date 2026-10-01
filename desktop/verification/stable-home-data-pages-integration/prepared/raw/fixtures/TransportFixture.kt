package com.bilipai.desktop.data

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.ServerSocket
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.nio.file.*
import kotlinx.serialization.json.*

private class LocalReply(private val delayed:Boolean=false) : AutoCloseable {
    private val server=ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress())
    val url="http://127.0.0.1:${server.localPort}/fixture"
    val arrived=CountDownLatch(1)
    val release=CountDownLatch(if(delayed)1 else 0)
    val headers=ConcurrentHashMap<String,String>()
    var body=""
    private val executor=Executors.newSingleThreadExecutor()
    val task=executor.submit {
        server.accept().use { socket ->
            socket.soTimeout=5000
            val input=socket.getInputStream()
            fun line():String {
                val b=java.io.ByteArrayOutputStream()
                while(true){val v=input.read();if(v<0)break;if(v==10)break;if(v!=13)b.write(v)}
                return b.toString(Charsets.UTF_8)
            }
            headers[":request"]=line()
            while(true){val l=line();if(l.isEmpty())break;val i=l.indexOf(':');if(i>0)headers[l.substring(0,i).lowercase()]=l.substring(i+1).trim()}
            val length=headers["content-length"]?.toIntOrNull()?:0
            body=String(input.readNBytes(length),Charsets.UTF_8)
            arrived.countDown();check(release.await(5,TimeUnit.SECONDS))
            val bytes="{\"code\":0}".toByteArray()
            val response="HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nSet-Cookie: SESSDATA=fixture-guest-response; Path=/\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()+bytes
            socket.getOutputStream().write(response);socket.getOutputStream().flush()
        }
    }
    override fun close(){release.countDown();server.close();executor.shutdownNow()}
}
private fun expectFailure(block:()->Unit):Throwable = runCatching(block).exceptionOrNull() ?: error("Expected rejected request")

fun main(args:Array<String>)=runBlocking {
    val dir=Path.of(args[0]);Files.createDirectories(dir)
    val names=mutableListOf<String>()
    val sessions=DesktopSessionStore.temporary()
    sessions.saveAccount(mapOf("SESSDATA" to "fixture-old","bili_jct" to "fixture-csrf"),AccountSummary(10,"fixture",""))
    val repository=DesktopRepository(sessions)
    val originalClient=repository.httpClient
    val captured=repository.sessionEpoch
    val alive=AtomicBoolean(true)
    val factory=repository.ownedHomeCallFactory(captured,alive::get)
    val oldQueued=factory.newCall(Request.Builder().url("http://127.0.0.1:9/fixture-never-network").build())
    sessions.saveAccount(mapOf("SESSDATA" to "fixture-new","bili_jct" to "fixture-new-csrf"),AccountSummary(10,"fixture",""))
    check(expectFailure{oldQueued.execute().close()}.message=="Request owner retired")
    check(sessions.currentCookies()["SESSDATA"]=="fixture-new")
    check(repository.httpClient===originalClient)
    names+="captured epoch survives newCall queue and rejects same MID replacement before network/cookie load"

    val activeEpoch=repository.sessionEpoch
    val active=repository.ownedHomeCallFactory(activeEpoch,alive::get)
    val retired=active.newCall(Request.Builder().url("http://127.0.0.1:9/fixture-never-network").build())
    alive.set(false);check(expectFailure{retired.execute().close()}.message=="Request owner retired");alive.set(true)
    val before=sessions.currentCookies()
    sessions.requestGeneration.set(activeEpoch);sessions.requestAdmission.set{false}
    expectFailure{sessions.loadForRequest("https://api.bilibili.com/fixture".toHttpUrl())}
    sessions.saveFromResponse("https://api.bilibili.com/fixture".toHttpUrl(),listOf(Cookie.Builder().domain("bilibili.com").name("SESSDATA").value("fixture-late").path("/").secure().build()))
    sessions.requestAdmission.remove();sessions.requestGeneration.remove()
    check(sessions.currentCookies()==before)
    names+="same epoch retired lifetime and actual SessionStore cookie load/save reject stale owner"

    LocalReply().use { local ->
        repository.ownedHomeCallFactory(activeEpoch,alive::get,guest=true).newCall(Request.Builder().url(local.url)
            .header("Cookie","SESSDATA=fixture-forced").header(com.android.purebilibili.core.network.FORCE_COOKIE_HEADER,"SESSDATA=fixture-forced").build()).execute().use {check(it.isSuccessful)}
        local.task.get(5,TimeUnit.SECONDS)
        val cookie=local.headers["cookie"].orEmpty()
        check(Regex("buvid3=[a-f0-9]{32}infoc").matches(cookie)){"guest identity shape"}
        check(!local.headers.containsKey(com.android.purebilibili.core.network.FORCE_COOKIE_HEADER.lowercase()))
        check(sessions.currentCookies()==before)
    }
    // The same actual store's HTTPS response path proves guest suppression too; loopback HTTP
    // alone cannot prove an HTTPS account-cookie persistence rule.
    sessions.requestGeneration.set(activeEpoch);sessions.requestAdmission.set{true};sessions.requestGuestBuvid3.set("0".repeat(32)+"infoc")
    val visitor=sessions.loadForRequest("https://api.bilibili.com/fixture".toHttpUrl())
    check(visitor.map{it.name}==listOf("buvid3"))
    sessions.saveFromResponse("https://api.bilibili.com/fixture".toHttpUrl(),listOf(Cookie.Builder().domain("bilibili.com").name("SESSDATA").value("fixture-guest-response").path("/").secure().build()))
    sessions.requestGuestBuvid3.remove();sessions.requestAdmission.remove();sessions.requestGeneration.remove()
    check(sessions.currentCookies()==before)
    names+="real loopback guest forceCookie stripped and original visitor shape; actual HTTPS store response suppression"

    LocalReply().use { local ->
        active.newCall(Request.Builder().url(local.url+"?w_rid=fixture-sign&wts=123")
            .header("Referer","https://www.bilibili.com/video/fixture").header("X-Fixture","kept")
            .post(FormBody.Builder().add("csrf","fixture-new-csrf").build()).build()).execute().use{check(it.isSuccessful)}
        local.task.get(5,TimeUnit.SECONDS)
        check(local.headers[":request"].orEmpty().contains("w_rid=fixture-sign&wts=123"))
        check(local.body=="csrf=fixture-new-csrf")
        check(local.headers["referer"]=="https://www.bilibili.com/video/fixture")
        check(local.headers["x-fixture"]=="kept")
        check(!local.headers["user-agent"].isNullOrBlank())
        check(local.headers["origin"]=="https://www.bilibili.com")
        check(sessions.requestGeneration.get()==null && sessions.requestAdmission.get()==null && sessions.requestGuestBuvid3.get()==null)
    }
    names+="active same epoch preserves explicit headers query and CSRF body; existing headers and ThreadLocal cleanup"

    LocalReply(true).use { local ->
        val pool=Executors.newSingleThreadExecutor()
        try {
            val result=pool.submit<Boolean>{runCatching{active.newCall(Request.Builder().url(local.url).build()).execute().use{it.isSuccessful}}.isFailure}
            check(local.arrived.await(5,TimeUnit.SECONDS));sessions.logout();local.release.countDown()
            check(result.get(5,TimeUnit.SECONDS));local.task.get(5,TimeUnit.SECONDS)
        } finally{pool.shutdownNow()}
    }
    names+="actual delayed loopback response retired by epoch is rejected before publication"

    sessions.saveAccount(mapOf("SESSDATA" to "fixture-final"),AccountSummary(10,"fixture",""))
    val navEpoch=repository.sessionEpoch
    val invalidations=mutableListOf<Pair<Long,Long>>()
    val invalidated:(Long,Long)->Unit={e,mid->invalidations+=e to mid}
    check(repository.updateHomeNavIdentity(navEpoch,10,10,true,invalidated))
    check(repository.account.value?.isVip==true && repository.sessionEpoch==navEpoch)
    check(repository.savedAccounts.value.single().account.isVip)
    check(!repository.updateHomeNavIdentity(navEpoch-1,10,10,false,invalidated))
    check(!repository.updateHomeNavIdentity(navEpoch,10,20,false,invalidated))
    check(invalidations.isEmpty())
    check(repository.updateHomeNavIdentity(navEpoch,10,null,false,invalidated))
    check(invalidations==listOf(navEpoch to 10L))
    check(repository.account.value?.mid==10L && sessions.currentCookies()["SESSDATA"]=="fixture-final")
    names+="same-store nav VIP projection rejects foreign/old epoch; AUTH guest raises required event without clearing credentials"
    val binding=com.bilipai.desktop.ui.DesktopHomeRootRequestBinding(repository,DesktopDiscoveryPreferences(dir.resolve("temporary-preferences")),this,
        navEpoch,10,alive::get,{block->block();true},invalidated)
    check(binding.environment.accessToken()==null && binding.environment.csrf()==null)
    binding.close()
    check(runCatching {binding.ports.video.getPopularVideos(1)}.exceptionOrNull() is kotlinx.coroutines.CancellationException)
    check(!binding.environment.isCurrent())
    names+="concrete required Root API binding reuses temporary preferences/epoch and rejects calls after owned close without HTTP"
    repository.httpClient.dispatcher.executorService.shutdown()
    repository.httpClient.connectionPool.evictAll()
    Files.writeString(dir.resolve("transport-result.json"),buildJsonObject {
        put("preparedOverrides",true);put("actualMainAcceptance",false);put("sameExistingClient",true)
        put("accountCredentials","synthetic temporary store only");put("actualNetwork","task loopback only; first two retired calls rejected before network")
        put("actualTls",false);put("gates",names.size);put("names",JsonArray(names.map(::JsonPrimitive)))
        put("codeSource",buildJsonObject {
            for(type in listOf(DesktopRepository::class.java,DesktopSessionStore::class.java,com.android.purebilibili.core.network.BilibiliApi::class.java))
                put(type.name,type.protectionDomain.codeSource?.location.toString())
        })
    }.toString())
    println("PASS ${names.size} transport gates")
}
