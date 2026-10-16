package com.bilipai.desktop.network

import com.android.purebilibili.core.network.policy.AppHttpProxySettings
import com.android.purebilibili.core.network.policy.ProxyRouteMode
import com.android.purebilibili.core.store.NetworkProxyStore
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.settings.DesktopNetworkProxyBindings
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class DesktopNetworkProxyRoutingTest {
    @TempDir lateinit var root: Path

    @Test fun `one actual selector observes persisted route changes while media remains direct and system selector stays untouched`(): Unit = runBlocking {
        val context = DesktopPluginContext(DesktopPluginStore(root))
        val before = NetworkProxyStore.getSync()
        val actualSystem = ProxySelector.getDefault()
        val systemProxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 7891))
        val fakeSystem = object : ProxySelector() {
            override fun select(uri: URI?): List<Proxy> = listOf(systemProxy)
            override fun connectFailed(uri: URI?, address: SocketAddress?, failure: IOException?) = Unit
        }
        val selector = DesktopNetworkProxyPlatform.buildAppProxySelector { fakeSystem }
        val application = OkHttpClient.Builder().proxySelector(selector).build()
        val binding = DesktopNetworkProxyBindings(context, application, this) { throw AssertionError(it) }
        val custom = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 7890))
        val settings = AppHttpProxySettings(enabled = true, host = "127.0.0.1", portText = "7890",
            routeMode = ProxyRouteMode.SPLIT, proxiedDomains = listOf("aicu.cc"))
        try {
            withTimeout(5000) { binding.saveAndAwait(settings) }
            assertSame(selector, application.proxySelector)
            assertEquals(listOf(custom, Proxy.NO_PROXY), application.proxySelector.select(URI("https://www.aicu.cc/")))
            assertEquals(listOf(systemProxy), application.proxySelector.select(URI("https://api.bilibili.com/")))
            val prefs = context.getSharedPreferences("network_proxy_prefs", DesktopPluginContext.MODE_PRIVATE)
            assertEquals("SPLIT", prefs.getString("route_mode", null))
            assertEquals(setOf("aicu.cc"), prefs.getStringSet("proxied_domains", null))
            withTimeout(5000) { binding.saveAndAwait(settings.copy(routeMode = ProxyRouteMode.GLOBAL)) }
            assertEquals(listOf(custom), application.proxySelector.select(URI("https://api.bilibili.com/")))
            withTimeout(5000) { binding.saveAndAwait(settings.copy(enabled = false)) }
            assertEquals(listOf(systemProxy), application.proxySelector.select(URI("https://www.aicu.cc/")))
            assertEquals(Proxy.NO_PROXY, DesktopNetworkProxyPlatform.buildPlaybackOkHttpClient(application).proxy)
            assertSame(actualSystem, ProxySelector.getDefault())
        } finally {
            // All persistence is in this test's private temporary profile. Restore
            // only the in-process singleton snapshot for later unit test classes.
            NetworkProxyStore.save(context, before)
            application.connectionPool.evictAll()
            application.dispatcher.executorService.shutdown()
        }
    }
}
