package com.bilipai.desktop.danmaku

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DanmakuTest {
    @Test fun `XML preserves standard modes colors and time while ignoring code comments`() {
        val comments = DanmakuParser.parse("""<i>
            <d p="2,4,25,16711680,0,0,hash,1">bottom &amp; red</d>
            <d p="1,1,25,16777215,0,0,hash,2">scroll</d>
            <d p="1.5,5,30,255,0,0,hash,3">top</d>
            <d p="0,8,25,0,0,0,hash,4">executable code</d>
            <d p="NaN,1,25,0,0,0,hash,5">invalid</d>
        </i>""")
        assertEquals(listOf("scroll", "top", "bottom & red"), comments.map { it.text })
        assertEquals(listOf(1, 5, 4), comments.map { it.mode })
        assertEquals(0xff0000, comments.last().color)
    }

    @Test fun `XML rejects external entities instead of opening local files`() {
        assertFails {
            DanmakuParser.parse("""<!DOCTYPE i [<!ENTITY secret SYSTEM "file:///C:/Windows/win.ini">]>
                <i><d p="0,1,25,16777215">&secret;</d></i>""")
        }
    }

    @Test fun `backward and forward seeks rebuild only the destination timeline`() {
        val scheduler = DanmakuScheduler(listOf(comment(0, 0.0), comment(1, 50.0)))
        assertEquals(listOf(1), scheduler.frame(51.0, 640, 144, 36) { 120 }.map { it.comment.id })
        assertEquals(listOf(0), scheduler.frame(1.0, 640, 144, 36) { 120 }.map { it.comment.id })
        assertEquals(listOf(1), scheduler.frame(51.0, 640, 144, 36) { 120 }.map { it.comment.id })
        assertTrue(scheduler.frame(60.0, 640, 144, 36) { 120 }.isEmpty())
    }

    @Test fun `dense mixed comments never intersect on the same lane`() {
        val comments = (0 until 60).map { index ->
            comment(index, index * 0.15, when (index % 9) { 0 -> 5; 1 -> 4; else -> 1 })
        }
        val scheduler = DanmakuScheduler(comments)
        var displayed = false
        repeat(150) { tick ->
            val frame = scheduler.frame(tick / 10.0, 640, 144, 36) { if (it.id % 3 == 0) 320 else 100 }
            displayed = displayed || frame.isNotEmpty()
            frame.forEachIndexed { index, item ->
                frame.drop(index + 1).filter { it.baseline == item.baseline }.forEach { other ->
                    val intersects = item.x < other.x + other.textWidth && other.x < item.x + item.textWidth
                    assertFalse(intersects, "Comments ${item.comment.id} and ${other.comment.id} collided at ${tick / 10.0}s")
                }
            }
        }
        assertTrue(displayed)
    }

    @Test fun `a long fast comment cannot catch a short slow comment in one lane`() {
        val scheduler = DanmakuScheduler(listOf(comment(0, 0.0), comment(1, 1.4)))
        repeat(80) { tick ->
            val frame = scheduler.frame(tick / 10.0, 640, 36, 36) { if (it.id == 0) 80 else 480 }
            assertTrue(frame.size <= 1)
        }
    }

    @Test fun `paused time freezes positions and viewport changes preserve visible content`() {
        val scheduler = DanmakuScheduler(listOf(comment(0, 0.0)))
        val first = scheduler.frame(2.0, 640, 144, 36) { 120 }
        assertEquals(first, scheduler.frame(2.0, 640, 144, 36) { 120 })
        val resized = scheduler.frame(2.0, 320, 72, 36) { 120 }
        assertEquals(first.map { it.comment.id }, resized.map { it.comment.id })
        assertTrue(resized.all { it.baseline <= 72 && it.x < first.first().x })
    }

    private fun comment(id: Int, time: Double, mode: Int = 1) = DanmakuComment(id, time, mode, 25, 0xffffff, "Comment $id")
}
