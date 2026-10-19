package com.bilipai.desktop.ui

import com.android.purebilibili.core.plugin.feed.publicContentImageHttpClient
import com.bilipai.desktop.data.DesktopRepository
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.ProxySelector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** No network: calls are created but never executed. */
class DesktopPublicContentImageRoutingTest {
    private class RecordingCalls : Call.Factory {
        val hosts = mutableListOf<String>()
        val agents = mutableListOf<String?>()

        override fun newCall(request: Request): Call {
            hosts += request.url.host
            agents += request.header("User-Agent")
            return OkHttpClient().newCall(request)
        }
    }

    private fun request(host: String) = Request.Builder().url("https://$host/cover.jpg").build()

    @Test
    fun `Bilibili hosts keep the owned guest call factory`() {
        val bilibiliCalls = RecordingCalls()
        val publicCalls = RecordingCalls()
        val routed = routedImageCalls(bilibiliCalls, publicCalls) { true }
        val hosts = listOf("i0.hdslb.com", "www.bilibili.com", "upos.bilivideo.com", "upos.bilivideo.cn", "s1.hdslb.com")
        hosts.forEach { routed.newCall(request(it)) }
        assertEquals(hosts, bilibiliCalls.hosts)
        assertTrue(bilibiliCalls.agents.all { it == null })
        assertTrue(publicCalls.hosts.isEmpty())
    }

    @Test
    fun `RSS image hosts and lookalike domains take the anonymous public image path`() {
        val bilibiliCalls = RecordingCalls()
        val publicCalls = RecordingCalls()
        val routed = routedImageCalls(bilibiliCalls, publicCalls) { true }
        val hosts = listOf(
            "cdnfile.sspai.com", "image.gcores.com", "mmbiz.qpic.cn", "www.jintiankansha.me",
            "evilbilibili.com", "bilibili.com.example.com", "127.0.0.1",
        )
        hosts.forEach { routed.newCall(request(it)) }
        assertEquals(hosts, publicCalls.hosts)
        assertTrue(bilibiliCalls.hosts.isEmpty())
    }

    @Test
    fun `public image calls keep the desktop browser agent unless the caller names one`() {
        val publicCalls = RecordingCalls()
        val routed = routedImageCalls(RecordingCalls(), publicCalls) { true }
        routed.newCall(request("cdnfile.sspai.com"))
        routed.newCall(Request.Builder().url("https://image.gcores.com/a.png").header("User-Agent", "caller-agent").build())
        assertEquals(listOf(DesktopRepository.USER_AGENT, "caller-agent"), publicCalls.agents)
    }

    @Test
    fun `a retired owner cannot start a public image call and Bilibili calls keep their own gate`() {
        val bilibiliCalls = RecordingCalls()
        val publicCalls = RecordingCalls()
        val routed = routedImageCalls(bilibiliCalls, publicCalls) { false }
        assertFailsWith<IOException> { routed.newCall(request("cdnfile.sspai.com")) }
        assertTrue(publicCalls.hosts.isEmpty())
        routed.newCall(request("i0.hdslb.com"))
        assertEquals(listOf("i0.hdslb.com"), bilibiliCalls.hosts)
    }

    @Test
    fun `the public image client stays anonymous and keeps both public interceptors and the app proxy and time budget`() {
        val client = desktopPublicImageCalls()
        assertSame(CookieJar.NO_COOKIES, client.cookieJar)
        assertSame(Authenticator.NONE, client.authenticator)
        assertSame(Authenticator.NONE, client.proxyAuthenticator)
        assertEquals(0, client.interceptors.size)
        assertEquals(2, client.networkInterceptors.size)
        assertEquals(publicContentImageHttpClient.networkInterceptors, client.networkInterceptors)
        assertNull(client.cache)
        assertEquals(15_000, client.connectTimeoutMillis)
        assertEquals(25_000, client.readTimeoutMillis)
        assertEquals(0, client.callTimeoutMillis)
        assertNotSame(publicContentImageHttpClient.proxySelector, client.proxySelector)
        assertNotSame(ProxySelector.getDefault(), client.proxySelector)
    }
}
