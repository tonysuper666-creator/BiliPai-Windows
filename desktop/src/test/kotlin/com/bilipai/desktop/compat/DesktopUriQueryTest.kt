package com.bilipai.desktop.compat

import android.net.Uri
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopUriQueryTest {
    @Test fun `shop deep link preserves nested query separators and decodes once`() {
        val target = "https://mall.bilibili.com/detail.html?id=42&keyword=中文%20商品&literal=%2B#details"
        val uri = Uri.parse("bilibili://mall?url=${Uri.encode(target)}&source=space#ignored")
        assertTrue(uri.isHierarchical)
        assertEquals(target, uri.getQueryParameter("url"))
        assertEquals("space", uri.getQueryParameter("source"))
    }

    @Test fun `first occurrence empty values and absent values follow Android query semantics`() {
        val uri = Uri.parse("https://example.test/?url=first&url=second&empty=&flag#url=fragment")
        assertEquals("first", uri.getQueryParameter("url"))
        assertEquals("", uri.getQueryParameter("empty"))
        assertEquals("", uri.getQueryParameter("flag"))
        assertNull(uri.getQueryParameter("missing"))
        assertNull(Uri.parse("https://example.test/#url=fragment").getQueryParameter("url"))
    }

    @Test fun `query values decode plus as space and preserve escaped plus`() {
        val uri = Uri.parse("https://example.test/?q=a+b%2Bc%20d%3De%26f")
        assertEquals("a b+c d=e&f", uri.getQueryParameter("q"))
    }

    @Test fun `parameter names are encoded before matching`() {
        val key = "中文 +&="
        val uri = Uri.parse("https://example.test/?${Uri.encode(key)}=matched&prefix=other")
        assertEquals("matched", uri.getQueryParameter(key))
        assertNull(uri.getQueryParameter("pre"))
    }

    @Test fun `relative links are hierarchical and opaque links reject query access`() {
        val relative = Uri.parse("/shop?url=https%3A%2F%2Fmall.bilibili.com")
        assertTrue(relative.isHierarchical)
        assertEquals("https://mall.bilibili.com", relative.getQueryParameter("url"))
        val opaque = Uri.parse("bilibili:mall?url=https%3A%2F%2Fmall.bilibili.com")
        assertFalse(opaque.isHierarchical)
        assertFailsWith<UnsupportedOperationException> { opaque.getQueryParameter("url") }
    }
}
