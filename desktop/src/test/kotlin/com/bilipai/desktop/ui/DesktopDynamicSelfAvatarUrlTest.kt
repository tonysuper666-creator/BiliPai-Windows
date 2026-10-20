package com.bilipai.desktop.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** The self entry of the follow strip normalises its avatar like the upstream sidebar avatar URL. */
class DesktopDynamicSelfAvatarUrlTest {
    @Test
    fun everyUpstreamAvatarFormBecomesHttps() {
        assertEquals("", resolveDesktopDynamicSelfAvatarUrl(""))
        assertEquals("", resolveDesktopDynamicSelfAvatarUrl("   "))
        assertEquals("https://i0.hdslb.com/a.jpg", resolveDesktopDynamicSelfAvatarUrl("https://i0.hdslb.com/a.jpg"))
        assertEquals("https://i0.hdslb.com/a.jpg", resolveDesktopDynamicSelfAvatarUrl(" http://i0.hdslb.com/a.jpg "))
        assertEquals("https://i0.hdslb.com/a.jpg", resolveDesktopDynamicSelfAvatarUrl("//i0.hdslb.com/a.jpg"))
        assertEquals("https://i0.hdslb.com/a.jpg", resolveDesktopDynamicSelfAvatarUrl("i0.hdslb.com/a.jpg"))
    }
}
