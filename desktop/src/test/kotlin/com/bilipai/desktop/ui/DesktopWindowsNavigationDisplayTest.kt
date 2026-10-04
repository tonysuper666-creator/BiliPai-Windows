package com.bilipai.desktop.ui

import com.android.purebilibili.navigation3.BiliPaiNavKey
import org.junit.jupiter.api.Test
import top.yukonga.miuix.kmp.nav.transition.NavSettleSpec
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import kotlin.test.*

class DesktopWindowsNavigationDisplayTest {
    @Test fun `desktop navigation never slides or settles a native surface between client coordinates`() {
        val transition = DesktopWindowsInstantNavigationTransition
        assertEquals(NavSwipeDirection.None, transition.dismissDirection)
        assertEquals(0f, transition.opaqueDepth)
        for (spec in listOf(transition.motion.commit, transition.motion.cancel, transition.motion.programmatic))
            assertEquals(0, assertIs<NavSettleSpec.Tween>(spec).durationMillis)
    }
    @Test fun `ordinary video prepares the real return before the real pop without related restoration`() {
        val calls = mutableListOf<String>()
        completeDesktopWindowsNavigationBack(BiliPaiNavKey.VideoDetail("BVfixture", sourceRoute = "home"), false,
            { calls += "prepare"; true }, { calls += "back" }, { calls += "related" })
        assertEquals(listOf("prepare", "back"), calls)
    }
    @Test fun `related source with query restores original previous source only after back`() {
        val calls = mutableListOf<String>()
        completeDesktopWindowsNavigationBack(BiliPaiNavKey.VideoDetail("BVfixture", sourceRoute = "video/BVprior?from=related"), false,
            { calls += "prepare"; false }, { calls += "back" }, { calls += "related" })
        assertEquals(listOf("prepare", "back", "related"), calls)
    }
    @Test fun `explicit previous source restoration remains after nonvideo back`() {
        val calls = mutableListOf<String>()
        completeDesktopWindowsNavigationBack(BiliPaiNavKey.Settings, true,
            { calls += "prepare"; true }, { calls += "back" }, { calls += "related" })
        assertEquals(listOf("back", "related"), calls)
    }
    @Test fun `normal nonvideo back never performs a video return`() {
        val calls = mutableListOf<String>()
        completeDesktopWindowsNavigationBack(BiliPaiNavKey.Settings, false,
            { calls += "prepare"; true }, { calls += "back" }, { calls += "related" })
        assertEquals(listOf("back"), calls)
    }
}
