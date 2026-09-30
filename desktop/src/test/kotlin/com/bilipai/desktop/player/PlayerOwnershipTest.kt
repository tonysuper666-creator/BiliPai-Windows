package com.bilipai.desktop.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerOwnershipTest {
    @Test fun `a disposed old screen cannot stop a newer source and replay preserves ownership`() {
        MpvPlayer().use { player ->
            val first = player.loadVersioned(PlaybackSource("file:///C:/first.mp4", title = "First"))
            val second = player.loadVersioned(PlaybackSource("file:///C:/second.mp4", title = "Second"))
            assertTrue(second > first)
            assertFalse(player.stopIfSourceVersion(first))
            assertEquals("Second", player.state.value.sourceTitle)
            player.replay()
            assertEquals(second, player.currentSourceVersion)
            assertTrue(player.stopIfSourceVersion(second))
            assertEquals("BiliPai", player.state.value.sourceTitle)
            assertFalse(player.stopIfSourceVersion(second))
        }
    }
}
