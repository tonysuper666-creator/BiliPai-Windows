package com.bilipai.desktop.data

import com.android.purebilibili.core.store.StoredAccountSession
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopPlaybackCookieBoundaryTest {
    private fun jar() = DesktopOriginalPlaybackAccountCookieJar(StoredAccountSession(
        mid = 41, sessData = "synthetic-playback-session", csrf = "synthetic-csrf", buvid3 = "synthetic-visitor"))

    @Test fun `dedicated playback credentials remain available on HTTPS Bilibili endpoints`() {
        val jar = jar()
        listOf("bilibili.com", "api.bilibili.com", "passport.bilibili.com").forEach { host ->
            val cookies = jar.loadForRequest("https://$host/x/player".toHttpUrl())
            assertEquals(setOf("SESSDATA", "bili_jct", "DedeUserID", "buvid3"), cookies.map { it.name }.toSet())
            assertEquals("synthetic-playback-session", cookies.single { it.name == "SESSDATA" }.value)
            assertTrue(cookies.all { it.domain == "bilibili.com" && it.secure })
        }
    }

    @Test fun `CDN external and lookalike hosts receive no playback account cookies`() {
        val jar = jar()
        listOf("https://media.bilivideo.com/video", "https://example.invalid/feed",
            "https://notbilibili.com/", "https://bilibili.com.example.invalid/",
            "https://127.0.0.1/media", "http://api.bilibili.com/x/player").forEach { url ->
            assertTrue(jar.loadForRequest(url.toHttpUrl()).isEmpty(), url)
        }
    }

    @Test fun `external and insecure responses cannot change captured playback credentials`() {
        val jar = jar()
        val replacement = Cookie.Builder().name("SESSDATA").value("synthetic-replacement")
            .domain("bilibili.com").secure().build()
        listOf("https://example.invalid/", "https://notbilibili.com/", "http://api.bilibili.com/").forEach { url ->
            jar.saveFromResponse(url.toHttpUrl(), listOf(replacement))
        }
        assertEquals("synthetic-playback-session", jar.loadForRequest("https://api.bilibili.com/".toHttpUrl())
            .single { it.name == "SESSDATA" }.value)
    }

    @Test fun `response cookie scope must match the actual Bilibili response host`() {
        val jar = jar()
        val response = "https://api.bilibili.com/".toHttpUrl()
        jar.saveFromResponse(response, listOf(
            Cookie.Builder().name("SESSDATA").value("foreign").domain("example.invalid").build(),
            Cookie.Builder().name("bili_jct").value("other-subdomain").hostOnlyDomain("passport.bilibili.com").build(),
            Cookie.Builder().name("buvid3").value("synthetic-next-visitor").domain("bilibili.com").secure().build()))
        val values = jar.loadForRequest(response).associate { it.name to it.value }
        assertEquals("synthetic-playback-session", values["SESSDATA"])
        assertEquals("synthetic-csrf", values["bili_jct"])
        assertEquals("synthetic-next-visitor", values["buvid3"])
    }

    @Test fun `native media header uses the dedicated account only inside the Bilibili boundary`() {
        val sessions = DesktopSessionStore.temporary()
        sessions.saveAccount(mapOf("SESSDATA" to "synthetic-account-A"), AccountSummary(41, "A", ""))
        sessions.saveAccount(mapOf("SESSDATA" to "synthetic-account-B"), AccountSummary(42, "B", ""))
        val repository = DesktopRepository(sessions)
        assertTrue(repository.setPlaybackAccountMid(41, repository.sessionEpoch) { true })
        val captured = repository.capturePlaybackAuthorization(repository.sessionEpoch) { true }
        val header = repository.capturePlaybackMediaCookieHeader(captured, "https://api.bilibili.com/x/player") { true }
        assertTrue(header.contains("SESSDATA=synthetic-account-A"))
        assertFalse(header.contains("synthetic-account-B"))
        listOf("https://media.bilivideo.com/video", "https://notbilibili.com/", "http://api.bilibili.com/").forEach { url ->
            assertEquals("", repository.capturePlaybackMediaCookieHeader(captured, url) { true })
        }
    }
}
