package com.bilipai.desktop.network

import com.android.purebilibili.core.network.policy.*
import com.android.purebilibili.core.store.NetworkProxyStore
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.settings.DesktopNetworkProxyBindings
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import java.io.*
import java.net.*
import java.nio.file.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private class FixtureSocket(private val role:String):AutoCloseable {
    private val listener=ServerSocket(0,32,InetAddress.getByAddress(byteArrayOf(127,0,0,1)))
    private val executor=Executors.newCachedThreadPool{r->Thread(r,"owned-proxy-fixture").apply{isDaemon=true}}
    private val sockets=ConcurrentHashMap.newKeySet<Socket>()
    val requests=AtomicInteger();val connects=AtomicInteger();val proxyCredentialsObserved=AtomicBoolean(false)
    val delayed=CountDownLatch(1);val canceledConnection=CountDownLatch(1)
    private val closed=AtomicBoolean(false)
    val port:Int get()=listener.localPort
    init {executor.execute{while(!closed.get()){try {val socket=listener.accept();sockets+=socket;executor.execute{serve(socket)}}catch(e:SocketException){if(!closed.get())throw e}}}}
    private fun serve(socket:Socket) {
        try {
            socket.soTimeout=8000
            val reader=socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            while(!closed.get()) {
                val line=reader.readLine()?:break
                if(line.isEmpty())continue
                val headers=mutableMapOf<String,String>()
                while(true){val header=reader.readLine()?:return;if(header.isEmpty())break;headers[header.substringBefore(':').lowercase()]=header.substringAfter(':').trim()}
                if(headers.containsKey("proxy-authorization"))proxyCredentialsObserved.set(true)
                if(line.startsWith("CONNECT ")) {
                    check(line.substringAfter("CONNECT ").substringBefore(' ')=="fixture.invalid:443")
                    connects.incrementAndGet()
                    socket.getOutputStream().write("HTTP/1.1 502 Fixture tunnel rejected\r\nContent-Length:0\r\nConnection:close\r\n\r\n".toByteArray(Charsets.ISO_8859_1));break
                }
                requests.incrementAndGet()
                val uri=URI(line.split(' ')[1])
                if(uri.path=="/delay") {
                    delayed.countDown()
                    try {if(reader.read()==-1)canceledConnection.countDown()}catch(expected:SocketException){canceledConnection.countDown()}
                    break
                }
                val body=role.toByteArray()
                val close=headers["connection"]?.equals("close",true)==true
                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length:${body.size}\r\nContent-Type:text/plain\r\nConnection:${if(close)"close" else "keep-alive"}\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
                socket.getOutputStream().write(body);socket.getOutputStream().flush()
                if(close)break
            }
        }catch(expected:IOException){} finally {sockets.remove(socket);socket.close()}
    }
    override fun close(){closed.set(true);listener.close();sockets.forEach{runCatching{it.close()}};executor.shutdownNow()}
}
private suspend fun Call.awaitText():String=suspendCancellableCoroutine {continuation->
    continuation.invokeOnCancellation{cancel()}
    enqueue(object:Callback {
        override fun onFailure(call:Call,e:IOException){if(continuation.isActive)continuation.resumeWithException(e)}
        override fun onResponse(call:Call,response:Response){response.use{if(continuation.isActive)try{continuation.resume(it.body.string())}catch(e:Exception){continuation.resumeWithException(e)}}}
    })
}
private fun request(url:String)=Request.Builder().url(url).header("X-Task-Fixture","loopback-only").build()
private fun clientField(value:Any):OkHttpClient=value.javaClass.getDeclaredField("client").apply{isAccessible=true}.get(value) as OkHttpClient

fun main(args:Array<String>):Unit=runBlocking {
    if(args[0]=="fresh-read") {
        val context=DesktopPluginContext(DesktopPluginStore(Path.of(args[1])))
        NetworkProxyStore.init(context)
        check(NetworkProxyStore.getSync()==AppHttpProxySettings(true,"127.0.0.1",args[2]))
        println("Fresh JVM original proxy settings loaded from actual disk.");return@runBlocking
    }
    val output=Path.of(args[0]);Files.createDirectories(output)
    val passed=mutableListOf<String>()
    suspend fun case(name:String,block:suspend ()->Unit){println("START $name");block();passed+=name;println("PASS $name")}
    val root=Files.createTempDirectory("bilipai-owned-api-proxy-")
    val store=DesktopPluginStore(root.resolve("prefs"));val context=DesktopPluginContext(store)
    NetworkProxyStore.init(context)
    val repository=DesktopRepository(DesktopSessionStore(root.resolve("session.json"),persistent=false))
    val api=repository.httpClient
    val binding=DesktopNetworkProxyBindings(context,api,this){throw AssertionError("Fixture persistence failed",it)}
    val originalGlobal=ProxySelector.getDefault()
    var oldWarmRoute="not-run"
    try {FixtureSocket("origin").use {origin->FixtureSocket("proxy").use {proxy->
        val target="http://127.0.0.1:${origin.port}/api"
        check(originalGlobal?.select(URI(target)).orEmpty().all{it.type()==Proxy.Type.DIRECT}) {"Fixture aborted before HTTP: isolated system selector is not direct for loopback"}
        val enabled=AppHttpProxySettings(true,"127.0.0.1",proxy.port.toString())
        case("original host-port policy and sanitizer semantics remain unchanged") {
            check(AppHttpProxySettings()==AppHttpProxySettings(false,"",""))
            check(parseProxyPort(" 65535 ")==65535&&parseProxyPort("0")==null&&parseProxyPort("65536")==null)
            check(!isValidProxyHost("a b")&&!isValidProxyHost("x".repeat(254)))
            check(sanitizeProxyHostInput(" https://127.0.0.1:7890/test ")=="127.0.0.1")
            check(sanitizeProxyPortInput("a12-3456")=="12345")
            check(formatAppHttpProxySummary(enabled).contains("127.0.0.1:${proxy.port}"))
        }
        case("actual repository disabled route uses only the task origin without global proxy mutation") {
            check(api.newCall(request(target)).awaitText()=="origin")
            check(proxy.requests.get()==0);check(ProxySelector.getDefault()===originalGlobal)
        }
        // Source-faithful dynamic selector alone can reuse an existing idle route.
        NetworkProxyStore.save(context,enabled)
        oldWarmRoute=api.newCall(request(target)).awaitText()
        NetworkProxyStore.save(context,enabled.copy(enabled=false))
        case("successful original persistence plus thin idle-route retirement switches a warm actual graph") {
            binding.saveAndAwait(enabled.copy(host=" 127.0.0.1 ",portText=" ${proxy.port} "))
            check(NetworkProxyStore.getSync()==enabled)
            check(api.newCall(request(target)).awaitText()=="proxy")
            val disk=Json.parseToJsonElement(Files.readString(store.root.resolve("plugin-settings.json"))).jsonObject["network_proxy_prefs"]!!.jsonObject
            check(disk.keys==setOf("enabled","host","port"))
            check(disk["host"]!!.jsonPrimitive.content=="127.0.0.1")
            check(disk["port"]!!.jsonPrimitive.content==proxy.port.toString())
        }
        case("existing community and isolated login client graphs share the selector while keeping their own cookie jars") {
            val community=clientField(DesktopCommunityRepository(repository))
            val login=DesktopLoginRepository(repository)
            val loginClient=clientField(login)
            check(community.proxySelector===api.proxySelector&&loginClient.proxySelector===api.proxySelector)
            check(loginClient.cookieJar!==api.cookieJar&&login.captchaClient.cookieJar===CookieJar.NO_COOKIES)
            check(community.newCall(request(target+"-community")).awaitText()=="proxy")
            check(loginClient.newCall(request(target+"-login")).awaitText()=="proxy")
            check(login.captchaClient.newCall(request(target+"-captcha")).awaitText()=="proxy")
            check(repository.account.value==null)
        }
        case("original playback-client NO_PROXY boundary bypasses enabled application proxy") {
            val direct=repository.playbackHttpClient
            check(direct.proxy==Proxy.NO_PROXY)
            val before=proxy.requests.get()
            check(direct.newCall(request(target+"-media")).awaitText()=="origin")
            check(proxy.requests.get()==before)
        }
        case("the actual application client tunnels HTTPS through configured HTTP proxy without inventing proxy credentials") {
            val call=api.newCall(request("https://fixture.invalid:443/probe"))
            var failed=false
            try{call.awaitText()}catch(expected:IOException){failed=true}
            check(failed&&proxy.connects.get()==1&&!proxy.proxyCredentialsObserved.get())
        }
        case("task coroutine cancellation cancels only its real proxy socket while a separate request succeeds") {
            val call=api.newCall(request("http://127.0.0.1:${origin.port}/delay"))
            val work=async(Dispatchers.IO){call.awaitText()}
            check(withContext(Dispatchers.IO){proxy.delayed.await(3,TimeUnit.SECONDS)})
            check(api.newCall(request(target+"-independent")).awaitText()=="proxy")
            work.cancelAndJoin();check(call.isCanceled())
            check(withContext(Dispatchers.IO){proxy.canceledConnection.await(3,TimeUnit.SECONDS)})
        }
        case("disable keeps endpoint and live selector returns actual subsequent API request to direct route") {
            binding.saveAndAwait(enabled.copy(enabled=false))
            check(NetworkProxyStore.getSync().host==enabled.host)
            check(api.newCall(request(target+"-disabled")).awaitText()=="origin")
        }
        case("original disabled or invalid config uses injected system list without setting global defaults") {
            val system=Proxy(Proxy.Type.HTTP,InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127,0,0,1)),proxy.port))
            val fakeSystem=object:ProxySelector(){override fun select(uri:URI?)=listOf(system);override fun connectFailed(uri:URI?,sa:SocketAddress?,ioe:IOException?)=Unit}
            val selector=DesktopNetworkProxyPlatform.buildAppProxySelector{fakeSystem}
            check(selector.select(URI(target))==listOf(system))
            val empty=DesktopNetworkProxyPlatform.buildAppProxySelector{null}
            check(empty.select(URI(target))==listOf(Proxy.NO_PROXY))
            NetworkProxyStore.save(context,enabled.copy(portText="0"))
            check(selector.select(URI(target))==listOf(system))
            check(ProxySelector.getDefault()===originalGlobal)
        }
        case("actual failed store replacement does not publish or normalize a new proxy state") {
            // Build the backing before obstructing its file so the failure is a real update failure.
            val failureRoot=Files.createTempDirectory("bilipai-proxy-write-failure-")
            val failureStore=DesktopPluginStore(failureRoot)
            Files.createDirectories(failureRoot.resolve("plugin-settings.json"))
            val previous=NetworkProxyStore.getSync();var failedWrite=false
            try{NetworkProxyStore.save(DesktopPluginContext(failureStore),enabled)}catch(expected:IOException){failedWrite=true}
            check(failedWrite&&NetworkProxyStore.getSync()==previous&&NetworkProxyStore.settings.value==previous)
        }
        case("real refused task proxy invokes the original failure hook without URI or exception-message logging") {
            val unused=ServerSocket(0,1,InetAddress.getByAddress(byteArrayOf(127,0,0,1))).use{it.localPort}
            val before=desktopProxyConnectionFailureCount.get()
            binding.saveAndAwait(enabled.copy(portText=unused.toString()))
            var refused=false
            try{api.newCall(request(target+"?fixture_secret=not-a-real-secret")).awaitText()}catch(expected:IOException){refused=true}
            check(refused&&desktopProxyConnectionFailureCount.get()>before)
        }
        case("new JVM reads original namespace and keys from actual persisted proxy settings") {
            binding.saveAndAwait(enabled)
            val java=Path.of(System.getProperty("java.home"),"bin","java.exe").toString()
            val freshArguments=listOf("-Dfile.encoding=UTF-8","-Djava.awt.headless=true","-cp",System.getProperty("java.class.path"),"com.bilipai.desktop.network.ProxyFixtureKt","fresh-read",store.root.toString(),proxy.port.toString())
            val freshFile=output.resolve("fresh-jvm.args")
            Files.writeString(freshFile,freshArguments.joinToString("\n"){"\""+it.replace('\\','/')+"\""})
            val process=ProcessBuilder(java,"@"+freshFile.toString()).redirectErrorStream(true).start()
            val stdout=withContext(Dispatchers.IO){process.inputStream.bufferedReader().readText()}
            check(withContext(Dispatchers.IO){process.waitFor(20,TimeUnit.SECONDS)}&&process.exitValue()==0){stdout}
            Files.writeString(output.resolve("fresh-jvm.log"),stdout)
        }
        case("actual restore write fence rejects a late old binding without changing the original live state") {
            val previous=NetworkProxyStore.getSync()
            store.freezeWrites()
            var rejected=false
            try{binding.saveAndAwait(previous.copy(enabled=false))}catch(expected:IllegalStateException){rejected=true}
            check(rejected&&NetworkProxyStore.getSync()==previous&&NetworkProxyStore.settings.value==previous)
        }
    }}} finally {
        api.dispatcher.cancelAll();api.connectionPool.evictAll();api.dispatcher.executorService.shutdownNow()
    }
    check(ProxySelector.getDefault()===originalGlobal)
    Files.writeString(output.resolve("result.json"),buildJsonObject {
        put("passed",true);put("checks",JsonArray(passed.map(::JsonPrimitive)));put("originalUnretiredWarmRoute",oldWarmRoute)
        put("actualCurrentRepositorySourceOverride",true);put("taskOwnedLoopbackSocketsOnly",true)
        put("externalAccountOrNetworkRequest",false);put("globalProxySelectorChanged",false);put("nativeWindowCreated",false)
        put("mediaClientBoundaryTested",true);put("downloadCallerIntegrated",false);put("activeRequestRetirementClaimed",false)
        put("realTlsHandshakeOrRemoteProxyTested",false);put("proxyAuthenticationInvented",false)
    }.toString())
    println("Original proxy graph/store: ${passed.size} actual policy/socket/graph/disk cases passed. Unretired warm route=$oldWarmRoute.")
    Unit
}
