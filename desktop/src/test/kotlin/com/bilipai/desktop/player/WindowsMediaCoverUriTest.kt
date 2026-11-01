package com.bilipai.desktop.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WindowsMediaCoverUriTest {
    @Test fun upstreamHttpAndSchemeRelativeCoversUpgradeToHttps() {
        assertEquals("https://i0.hdslb.com/bfs/archive/a.jpg",
            normalizedSmTcCoverUri("http://i0.hdslb.com/bfs/archive/a.jpg"))
        assertEquals("https://i0.hdslb.com/bfs/archive/a.jpg",
            normalizedSmTcCoverUri("//i0.hdslb.com/bfs/archive/a.jpg"))
        assertEquals("https://i0.hdslb.com/bfs/archive/a.jpg",
            normalizedSmTcCoverUri("  https://i0.hdslb.com/bfs/archive/a.jpg  "))
    }

    @Test fun unusableCoversBecomeNoCoverInsteadOfFailingTheSession() {
        // These are the values the real detail payload can carry; none may reach WinRT as a bad URI.
        assertNull(normalizedSmTcCoverUri(""))
        assertNull(normalizedSmTcCoverUri("http://"))
        assertNull(normalizedSmTcCoverUri("http:///nohost"))
        assertNull(normalizedSmTcCoverUri("ftp://i0.hdslb.com/a.jpg"))
        assertNull(normalizedSmTcCoverUri("i0.hdslb.com/a.jpg"))
        assertNull(normalizedSmTcCoverUri("https://i0.hdslb.com/a b.jpg"))
        assertNull(normalizedSmTcCoverUri("data:image/png;base64,AAAA"))
    }

    @Test fun localFileCoversKeepTheirScheme() {
        assertEquals("file:///C:/Users/TONYS/cover.jpg", normalizedSmTcCoverUri("file:///C:/Users/TONYS/cover.jpg"))
        assertNull(normalizedSmTcCoverUri("file:"))
    }
}
