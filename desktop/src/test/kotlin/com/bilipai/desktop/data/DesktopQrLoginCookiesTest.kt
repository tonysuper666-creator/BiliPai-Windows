package com.bilipai.desktop.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class DesktopQrLoginCookiesTest {
    private val responseUrl = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll".toHttpUrl()

    @Test
    fun `raw Set-Cookie credentials win over decoded URL and previous account`() {
        val resolved = DesktopRepository.resolveQrLoginCookies(responseUrl,
            listOf("SESSDATA=new%2Csession; Domain=.bilibili.com; Path=/; Secure", "bili_jct=new-csrf; Path=/; Secure"),
            "https://passport.bilibili.com/success?SESSDATA=new%2Csession&bili_jct=url-csrf",
            mapOf("SESSDATA" to "old-session", "bili_jct" to "old-csrf", "buvid3" to "existing-visitor"))
        assertEquals("new%2Csession", resolved["SESSDATA"])
        assertEquals("new-csrf", resolved["bili_jct"])
        assertEquals("existing-visitor", resolved["buvid3"])
    }

    @Test
    fun `URL supplements missing credentials with exactly one decode`() {
        val resolved = DesktopRepository.resolveQrLoginCookies(responseUrl, emptyList(),
            "https://passport.bilibili.com/success?SESSDATA=new%252Csession&bili_jct=new-csrf&DedeUserID=42",
            mapOf("SESSDATA" to "old-session", "DedeUserID" to "7"))
        assertEquals("new%2Csession", resolved["SESSDATA"])
        assertEquals("new-csrf", resolved["bili_jct"])
        assertEquals("42", resolved["DedeUserID"])
    }

    @Test
    fun `missing or empty credentials cannot reuse previous account`() {
        val resolved = DesktopRepository.resolveQrLoginCookies(responseUrl,
            listOf("SESSDATA=; Domain=.bilibili.com; Path=/; Max-Age=0"),
            "https://passport.bilibili.com/success?SESSDATA=&bili_jct=",
            mapOf("SESSDATA" to "old-session", "bili_jct" to "old-csrf", "DedeUserID" to "7", "sid" to "visitor"))
        assertNull(resolved["SESSDATA"])
        assertNull(resolved["bili_jct"])
        assertNull(resolved["DedeUserID"])
        assertEquals(mapOf("sid" to "visitor"), resolved)
    }

    @Test
    fun `credential names and response domains are restricted to Bilibili login data`() {
        val resolved = DesktopRepository.resolveQrLoginCookies(responseUrl,
            listOf("SESSDATA=foreign; Domain=example.test; Path=/", "unrelated=value; Domain=.bilibili.com; Path=/"),
            "https://bilibili.com.example.test/success?SESSDATA=foreign-url",
            mapOf("unrelated" to "old-value", "buvid4" to "visitor"))
        assertEquals(mapOf("buvid4" to "visitor"), resolved)
        assertFalse("unrelated" in resolved)
        assertFailsWith<IllegalArgumentException> {
            DesktopRepository.resolveQrLoginCookies("https://example.test/poll".toHttpUrl(), emptyList(), null, emptyMap())
        }
    }
}
