package com.bilipai.desktop.data

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopSessionStoreTest {
    @Test fun `credential replacement invalidates owners even when account metadata does not change`() {
        val store = DesktopSessionStore(Files.createTempDirectory("session-owner-epoch-").resolve("session.json"))
        val account = AccountSummary(42, "fixture", "")
        store.saveAccount(mapOf("SESSDATA" to "fixture-first"), account)
        val first = store.generationState.value
        store.saveAccount(mapOf("SESSDATA" to "fixture-second"), account)
        assertEquals(account, store.account.value)
        assertEquals(first + 1, store.generationState.value)
        assertEquals(store.generation, store.generationState.value)
        store.saveAccount(mapOf("SESSDATA" to "fixture-second"), account)
        assertEquals(first + 1, store.generationState.value)
        store.logout()
        assertEquals(first + 2, store.generationState.value)
    }

    @Test
    fun `first guest homepage request contains no manufactured visitor cookie`() {
        val path = Files.createTempDirectory("bilipai-first-guest-test").resolve("session.json")
        val store = DesktopSessionStore(path)
        assertTrue(store.loadForRequest("https://www.bilibili.com/".toHttpUrl()).isEmpty())
        // Legacy random identifiers are ignored; only server-issued cookie entries are restored.
        Files.writeString(path, """{"buvid3":"old-random-infoc"}""")
        assertTrue(DesktopSessionStore(path).loadForRequest("https://www.bilibili.com/".toHttpUrl()).isEmpty())
    }

    @Test
    fun `server visitor identifiers persist without replacing homepage values`() {
        val path = Files.createTempDirectory("bilipai-visitor-test").resolve("session.json")
        val store = DesktopSessionStore(path)
        val url = "https://www.bilibili.com/".toHttpUrl()
        store.saveFromResponse(url, listOf(Cookie.Builder().name("buvid3").value("server-visitor")
            .domain("bilibili.com").path("/").secure().build()))
        val restored = DesktopSessionStore(path)
        assertEquals("server-visitor", restored.currentCookies()["buvid3"])
        assertEquals("server-visitor", restored.loadForRequest("https://api.bilibili.com/".toHttpUrl()).single { it.name == "buvid3" }.value)
    }

    @Test
    fun `host-only response cookies keep their original host across account save and restart`() {
        val path = Files.createTempDirectory("bilipai-host-scope-test").resolve("session.json")
        val store = DesktopSessionStore(path)
        val homepage = "https://www.bilibili.com/".toHttpUrl()
        store.saveFromResponse(homepage, listOf(Cookie.Builder().name("sid").value("homepage-only")
            .hostOnlyDomain("www.bilibili.com").path("/").secure().httpOnly().build()))
        store.saveAccount(store.currentCookies() + ("SESSDATA" to "authorized-session"), AccountSummary(42, "测试用户", ""))
        val restored = DesktopSessionStore(path)
        val cookie = restored.loadForRequest(homepage).single { it.name == "sid" }
        assertTrue(cookie.hostOnly)
        assertTrue(cookie.httpOnly)
        assertEquals("www.bilibili.com", cookie.domain)
        assertFalse(restored.loadForRequest("https://api.bilibili.com/".toHttpUrl()).any { it.name == "sid" })
        assertTrue(restored.loadForRequest("https://api.bilibili.com/".toHttpUrl()).any { it.name == "SESSDATA" })
    }

    @Test
    fun `future path response cookie is saved and expiry and flags survive restart`() {
        val path = Files.createTempDirectory("bilipai-path-scope-test").resolve("session.json")
        val expiry = System.currentTimeMillis() + 60_000
        val store = DesktopSessionStore(path)
        store.saveFromResponse("https://www.bilibili.com/".toHttpUrl(), listOf(Cookie.Builder()
            .name("future-session").value("future-only").domain("bilibili.com").path("/future")
            .expiresAt(expiry).httpOnly().build()))
        val restored = DesktopSessionStore(path)
        assertTrue(restored.loadForRequest("https://www.bilibili.com/".toHttpUrl()).isEmpty())
        val cookie = restored.loadForRequest("https://api.bilibili.com/future/page".toHttpUrl()).single()
        assertEquals("/future", cookie.path)
        assertEquals(expiry, cookie.expiresAt)
        assertTrue(cookie.persistent)
        assertTrue(cookie.httpOnly)
        assertFalse(cookie.secure)
        assertFalse(cookie.hostOnly)
    }

    @Test
    fun `same name cookies with distinct host or path are not overwritten`() {
        val path = Files.createTempDirectory("bilipai-cookie-identity-test").resolve("session.json")
        val store = DesktopSessionStore(path)
        store.saveFromResponse("https://www.bilibili.com/".toHttpUrl(), listOf(
            Cookie.Builder().name("sid").value("homepage-root").hostOnlyDomain("www.bilibili.com").path("/").build(),
            Cookie.Builder().name("sid").value("homepage-future").hostOnlyDomain("www.bilibili.com").path("/future").build(),
        ))
        store.saveFromResponse("https://api.bilibili.com/".toHttpUrl(), listOf(
            Cookie.Builder().name("sid").value("api-root").hostOnlyDomain("api.bilibili.com").path("/").build()))
        val restored = DesktopSessionStore(path)
        assertEquals(setOf("homepage-root", "homepage-future"), restored.loadForRequest("https://www.bilibili.com/future".toHttpUrl()).map { it.value }.toSet())
        assertEquals(listOf("api-root"), restored.loadForRequest("https://api.bilibili.com/".toHttpUrl()).map { it.value })
    }

    @Test
    fun `SPI visitor fallback does not widen or replace an actual homepage cookie`() {
        val store = DesktopSessionStore(Files.createTempDirectory("bilipai-spi-scope-test").resolve("session.json"))
        store.saveFromResponse("https://www.bilibili.com/".toHttpUrl(), listOf(Cookie.Builder().name("buvid3")
            .value("homepage-visitor").hostOnlyDomain("www.bilibili.com").path("/").secure().build()))
        store.saveSpiCookies(mapOf("buvid3" to "spi-visitor", "sid" to "not-a-spi-identifier"))
        assertEquals("homepage-visitor", store.loadForRequest("https://www.bilibili.com/".toHttpUrl()).single().value)
        assertEquals("spi-visitor", store.loadForRequest("https://api.bilibili.com/".toHttpUrl()).single().value)
    }

    @Test
    fun `explicit imported and QR account credentials cover Bilibili without widening server cookies`() {
        val path = Files.createTempDirectory("bilipai-import-scope-test").resolve("session.json")
        val store = DesktopSessionStore(path)
        store.saveAccount(mapOf("SESSDATA" to "imported-session", "buvid3" to "imported-visitor"), AccountSummary(42, "测试用户", ""), imported = true)
        val restored = DesktopSessionStore(path)
        listOf("www.bilibili.com", "api.bilibili.com", "passport.bilibili.com").forEach { host ->
            assertEquals(setOf("SESSDATA", "buvid3"), restored.loadForRequest("https://$host/".toHttpUrl()).map { it.name }.toSet())
        }
        assertTrue(restored.loadForRequest("https://example.test/".toHttpUrl()).isEmpty())
    }

    @Test
    fun `legacy flattened visitors are dropped while authorized account credentials survive`() {
        val path = Files.createTempDirectory("bilipai-legacy-scope-test").resolve("session.json")
        Files.writeString(path, """{"cookies":{"SESSDATA":"legacy-authorized-session","sid":"unknown-scope","buvid3":"unknown-scope"}}""")
        val restored = DesktopSessionStore(path)
        assertEquals(listOf("SESSDATA"), restored.loadForRequest("https://api.bilibili.com/".toHttpUrl()).map { it.name })
    }

    @Test
    fun `session survives restart and logout removes persistent credentials`() {
        val path = Files.createTempDirectory("bilipai-session-test").resolve("session.json")
        val store = DesktopSessionStore(path)
        val account = AccountSummary(42, "测试用户", "https://example.test/avatar.jpg")
        store.saveAccount(mapOf("SESSDATA" to "test-session", "bili_jct" to "test-csrf", "Path" to "/"), account)
        val restored = DesktopSessionStore(path)
        assertEquals(account, restored.account.value)
        assertEquals("test-session", restored.currentCookies()["SESSDATA"])
        assertFalse("Path" in restored.currentCookies())
        restored.logout()
        val loggedOut = DesktopSessionStore(path)
        assertNull(loggedOut.account.value)
        assertFalse("SESSDATA" in loggedOut.currentCookies())
    }

    @Test
    fun `cookie jar limits credentials to secure Bilibili requests`() {
        val store = DesktopSessionStore(Files.createTempDirectory("bilipai-host-test").resolve("session.json"))
        store.saveAccount(mapOf("SESSDATA" to "test-session"), AccountSummary(42, "测试用户", ""))
        assertTrue(store.loadForRequest("https://api.bilibili.com/x/web-interface/nav".toHttpUrl()).any { it.name == "SESSDATA" })
        listOf("https://example.test/", "https://api.bilibili.com.example.test/", "https://upos.bilivideo.com/", "http://api.bilibili.com/")
            .forEach { assertTrue(store.loadForRequest(it.toHttpUrl()).isEmpty(), it) }
    }

    @Test
    fun `expired server cookie cannot resurrect persisted session`() {
        val store = DesktopSessionStore(Files.createTempDirectory("bilipai-expiry-test").resolve("session.json"))
        val url = "https://passport.bilibili.com/".toHttpUrl()
        store.saveAccount(mapOf("SESSDATA" to "test-session"), AccountSummary(42, "测试用户", ""))
        store.saveFromResponse(url, listOf(Cookie.Builder().name("SESSDATA").value("").domain("bilibili.com").path("/").expiresAt(1).build()))
        assertFalse("SESSDATA" in store.currentCookies())
        assertFalse(store.loadForRequest(url).any { it.name == "SESSDATA" })
    }

    @Test
    fun `damaged session file starts as guest`() {
        val path = Files.createTempDirectory("bilipai-corrupt-test").resolve("session.json")
        Files.writeString(path, "{broken json")
        val store = DesktopSessionStore(path)
        assertNull(store.account.value)
        assertTrue(store.currentCookies().isEmpty())
    }
}
