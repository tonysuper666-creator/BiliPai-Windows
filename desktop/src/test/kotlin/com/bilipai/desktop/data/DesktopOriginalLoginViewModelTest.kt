package com.bilipai.desktop.data

import com.android.purebilibili.core.network.AppSignUtils
import com.android.purebilibili.core.network.PassportApi
import com.android.purebilibili.feature.login.BiliPaiQrDecoder
import com.android.purebilibili.feature.login.LoginState
import com.android.purebilibili.feature.login.LoginViewModel
import com.android.purebilibili.feature.login.createPiliPlusLoginIdentity
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Original VM -> real HTTP/JSON -> the real Store's captured-epoch install actor, with synthetic credentials.
 * Production nav validation remains DesktopLoginRepository/Repository's existing installLogin path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopOriginalLoginViewModelTest {
    @BeforeTest fun mainDispatcher() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @AfterTest fun resetDispatcher() { Dispatchers.resetMain() }

    @Test fun `default login renders TV QR and installs literal cookies and HD token after confirmation`() = runBlocking {
        Fixture().use { f ->
            val vm = f.viewModel()
            vm.loadQrCode()
            val qr = withTimeout(10_000) { vm.state.first { it is LoginState.QrCode } } as LoginState.QrCode
            assertEquals(f.qrUrl, BiliPaiQrDecoder.decodeBitmap(qr.bitmap))
            withTimeout(10_000) { vm.state.first { it is LoginState.Scanned } }
            assertNull(f.store.account.value)
            withTimeout(10_000) { vm.state.first { it is LoginState.Success } }
            assertEquals(42L, f.store.account.value?.mid)
            assertEquals("candidate%2Csession", f.store.currentCookies()["SESSDATA"])
            assertEquals("hd-access", f.store.appCredentials()?.accessToken)
            assertEquals("android_hd", f.store.appCredentials()?.platform)
            assertEquals(1, f.installs.get())
            assertTrue(f.requests.first().contains("appkey=${AppSignUtils.ANDROID_HD_APP_KEY}"))
            assertTrue(f.requests.first().contains("/qrcode/auth_code"))
            assertTrue(f.requests.drop(1).all { it.contains("auth_code=candidate-auth") })
        }
    }

    @Test fun `expired QR stays signed out and a fresh VM can refresh it`() = runBlocking {
        Fixture(expired = true).use { f ->
            val first = f.viewModel()
            first.loadQrCode()
            val error = withTimeout(10_000) { first.state.first { it is LoginState.Error } } as LoginState.Error
            assertTrue(error.msg.contains("过期"))
            f.retire()
            val refreshed = f.viewModel()
            refreshed.loadQrCode()
            withTimeout(10_000) { refreshed.state.first { it is LoginState.QrCode } }
            assertEquals(0, f.installs.get())
            assertNull(f.store.account.value)
            assertEquals(2, f.requests.count { it.contains("/qrcode/auth_code") })
        }
    }

    @Test fun `closing the dialog cancels the original poll before another HTTP request`() = runBlocking {
        Fixture().use { f ->
            val vm = f.viewModel()
            vm.loadQrCode()
            withTimeout(10_000) { vm.state.first { it is LoginState.QrCode } }
            vm.stopPolling()
            f.retire()
            delay(2_300)
            assertEquals(1, f.requests.size)
            assertEquals(0, f.installs.get())
            assertNull(f.store.account.value)
        }
    }

    @Test fun `account change while awaiting confirmation rejects the captured login installation`() = runBlocking {
        Fixture().use { f ->
            val vm = f.viewModel()
            vm.loadQrCode()
            withTimeout(10_000) { vm.state.first { it is LoginState.Scanned } }
            f.store.saveAccount(mapOf("SESSDATA" to "different-account"), AccountSummary(99, "other fixture", ""))
            delay(2_500)
            assertEquals(99L, f.store.account.value?.mid)
            assertEquals("different-account", f.store.currentCookies()["SESSDATA"])
            assertNull(f.store.appCredentials())
            assertFalse(vm.state.value is LoginState.Success)
        }
    }

    private class Fixture(private val expired: Boolean = false) : AutoCloseable {
        val qrUrl = "https://passport.bilibili.com/x/passport-tv-login/h5/qrcode/auth?auth_code=" + "0".repeat(32)
        val store = DesktopSessionStore.temporary()
        private val sourceEpoch = store.generation
        private val executor = Executors.newCachedThreadPool()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        private val work = mutableListOf<DesktopLoginLaunchScope>()
        val requests = CopyOnWriteArrayList<String>()
        val installs = AtomicInteger()
        private val polls = AtomicInteger()
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0).apply {
            executor = this@Fixture.executor
            createContext("/") { exchange ->
                requests += exchange.requestURI.toString()
                val response = if (exchange.requestURI.path.endsWith("/auth_code"))
                    """{"code":0,"data":{"auth_code":"candidate-auth","url":"$qrUrl"}}"""
                else if (expired) """{"code":86038}"""
                else if (polls.incrementAndGet() == 1) """{"code":86090}"""
                else """{"code":0,"data":{"mid":42,"access_token":"hd-access","refresh_token":"hd-refresh","cookie_info":{"cookies":[{"name":"SESSDATA","value":"candidate%2Csession"},{"name":"bili_jct","value":"fixture-csrf"}]}}}"""
                val bytes = response.toByteArray()
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
            start()
        }
        private val transport = OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES).addInterceptor { chain ->
            val request = chain.request()
            check(request.url.host == "passport.bilibili.com")
            val url = request.url.newBuilder().scheme("http").host("127.0.0.1").port(server.address.port).build()
            chain.proceed(request.newBuilder().url(url).build())
        }.build()
        private val api = Retrofit.Builder().baseUrl("https://passport.bilibili.com/").client(transport)
            .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType()))
            .build().create(PassportApi::class.java)

        fun viewModel(): LoginViewModel {
            val jobs = DesktopLoginLaunchScope { scope.launch(block = it) }.also(work::add)
            val platform = object : DesktopLoginPlatform {
                override val passportApi = api
                override val identity = createPiliPlusLoginIdentity()
                override suspend fun install(submission: DesktopLoginSubmission): AccountSummary {
                    val account = AccountSummary(submission.mid, "synthetic fixture", "")
                    store.saveLoginAccount(submission.cookies, account,
                        DesktopAppCredentials(submission.accessToken, submission.refreshToken, submission.accessTokenPlatform),
                        null, sourceEpoch) { true }
                    installs.incrementAndGet()
                    return account
                }
            }
            return LoginViewModel(DesktopLoginEnvironment(platform, jobs))
        }

        fun retire() { work.forEach { it.close() }; work.clear() }
        override fun close() {
            retire(); scope.cancel(); server.stop(0); executor.shutdownNow()
            transport.dispatcher.executorService.shutdownNow(); transport.connectionPool.evictAll()
        }
    }
}
