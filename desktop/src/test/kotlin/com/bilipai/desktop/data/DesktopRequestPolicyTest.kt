package com.bilipai.desktop.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopRequestPolicyTest {
    @Test
    fun `video detail referer follows upstream video page policy`() {
        assertEquals("https://www.bilibili.com/video/BV1xx411c7mD", DesktopRepository.resolveReferer(
            "https://api.bilibili.com/x/web-interface/view?bvid=BV1xx411c7mD".toHttpUrl(), null))
        assertEquals("https://www.bilibili.com", DesktopRepository.resolveReferer(
            "https://api.bilibili.com/x/web-interface/popular?pn=1&ps=20".toHttpUrl(), null))
    }

    @Test
    fun `WBI omits default referer but preserves explicit caller header`() {
        val url = "https://api.bilibili.com/x/player/wbi/playurl?bvid=BV1xx411c7mD".toHttpUrl()
        assertNull(DesktopRepository.resolveReferer(url, null))
        assertNull(DesktopRepository.resolveReferer(url, ""))
        assertEquals("https://www.bilibili.com/video/BV1xx411c7mD", DesktopRepository.resolveReferer(url, "https://www.bilibili.com/video/BV1xx411c7mD"))
        assertNull(DesktopRepository.resolveReferer("https://api.bilibili.com/x/web-interface/wbi/search/type".toHttpUrl(), null))
    }
}
