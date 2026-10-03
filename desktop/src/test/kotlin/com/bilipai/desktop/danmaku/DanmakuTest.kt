package com.bilipai.desktop.danmaku

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DanmakuTest {
    @Test fun `original XML pool receives full text and metadata while renderer keeps its display bounds`() {
        val content = "完整原弹幕\n" + "字".repeat(420)
        val attributes = "2.5,5,64,255,0,1,hash-original,123456"
        val comment = DanmakuParser.parse("""<i><d p="$attributes">$content</d></i>""").single()
        val item = requireNotNull(com.android.purebilibili.feature.video.danmaku.DesktopOriginalDanmakuItemParser
            .createTextData(requireNotNull(comment.originalXmlAttributes), requireNotNull(comment.originalXmlContent)))
        assertEquals(content, item.text)
        assertEquals(123456L, item.danmakuId)
        assertEquals("hash-original", item.userHash)
        assertEquals(2500L, item.showAtTime)
        assertEquals(300, comment.text.length)
        assertFalse(comment.text.contains('\n'))
        assertEquals(48, comment.size)
    }

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
        val scheduler = DanmakuScheduler(listOf(comment(0, 0.0), comment(1, 50.0)), DanmakuSettings(displayAreaRatio=1f), liveAdmission=false)
        assertEquals(listOf(1), scheduler.frame(51.0, 640, 144, 36) { 120 }.map { it.comment.id })
        assertEquals(listOf(0), scheduler.frame(1.0, 640, 144, 36) { 120 }.map { it.comment.id })
        assertEquals(listOf(1), scheduler.frame(51.0, 640, 144, 36) { 120 }.map { it.comment.id })
        assertTrue(scheduler.frame(60.0, 640, 144, 36) { 120 }.isEmpty())
    }

    @Test fun `dense mixed comments never intersect on the same lane`() {
        val comments = (0 until 60).map { index ->
            comment(index, index * 0.15, when (index % 9) { 0 -> 5; 1 -> 4; else -> 1 })
        }
        val scheduler = DanmakuScheduler(comments, DanmakuSettings(displayAreaRatio=1f), liveAdmission=false)
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
        val scheduler = DanmakuScheduler(listOf(comment(0, 0.0), comment(1, 1.4)), DanmakuSettings(displayAreaRatio=1f), liveAdmission=false)
        repeat(80) { tick ->
            val frame = scheduler.frame(tick / 10.0, 640, 36, 36) { if (it.id == 0) 80 else 480 }
            assertTrue(frame.size <= 1)
        }
    }

    @Test fun `paused time freezes positions and viewport changes preserve visible content`() {
        val scheduler = DanmakuScheduler(listOf(comment(0, 0.0)), DanmakuSettings(displayAreaRatio=1f), liveAdmission=false)
        val first = scheduler.frame(2.0, 640, 144, 36) { 120 }
        assertEquals(first, scheduler.frame(2.0, 640, 144, 36) { 120 })
        val resized = scheduler.frame(2.0, 320, 72, 36) { 120 }
        assertEquals(first.map { it.comment.id }, resized.map { it.comment.id })
        assertTrue(resized.all { it.baseline <= 72 && it.x < first.first().x })
    }

    @Test fun `settings changes immediately rebuild visible comments and apply type color keyword filters`() {
        val comments = listOf(comment(0, 0.0), comment(1, 0.0, 5), comment(2, 0.0, 4),
            comment(3, 0.0).copy(color = 0xff0000), comment(4, 0.0).copy(text = "包含剧透内容"))
        val scheduler = DanmakuScheduler(comments, DanmakuSettings(displayAreaRatio=1f), liveAdmission=false)
        assertTrue(scheduler.frame(1.0, 640, 360, 36) { 90 }.size > 1)
        scheduler.applySettings(DanmakuSettings(displayAreaRatio = 1f, allowTop = false, allowBottom = false,
            allowColorful = false, blockedKeywords = listOf("剧透")))
        assertEquals(listOf(0), scheduler.frame(1.0, 640, 360, 36) { 90 }.map { it.comment.id })
        scheduler.applySettings(DanmakuSettings(enabled = false))
        assertTrue(scheduler.frame(1.0, 640, 360, 36) { 90 }.isEmpty())
    }

    @Test fun `area and speed settings reserve the chosen screen band after seeking`() {
        val settings = DanmakuSettings(displayAreaRatio = 0.25f, scrollDurationSeconds = 7f, speedFactor = 2f)
        val scheduler = DanmakuScheduler((0 until 20).map { comment(it, 0.0) }, settings, liveAdmission=false)
        val frame = scheduler.frame(10.0, 640, 400, 40) { 100 }
        assertTrue(frame.isNotEmpty(), "Slower scrolling comments remain visible beyond the old eight second seek window")
        assertTrue(frame.all { it.baseline <= 100 })
        assertTrue(scheduler.frame(15.0, 640, 400, 40) { 100 }.isEmpty())
    }

    @Test fun `reverse scrolling comments move left to right without crossing forward comments`() {
        val parsed = DanmakuParser.parse("""<i><d p="0,6,25,16777215">reverse</d></i>""")
        assertEquals(6, parsed.single().mode)
        val scheduler = DanmakuScheduler(listOf(comment(0, 0.0, 6), comment(1, 1.0)), DanmakuSettings(displayAreaRatio=1f), liveAdmission=false)
        val earlier = scheduler.frame(1.0, 640, 36, 36) { 100 }.single()
        val later = scheduler.frame(2.0, 640, 36, 36) { 100 }.single()
        assertEquals(0, earlier.comment.id)
        assertTrue(later.x > earlier.x)
    }

    @Test fun `duplicate merging keeps the original timing and styles and can be disabled`() {
        val comments = listOf(comment(0, 0.0).copy(text = "same"), comment(1, 0.3).copy(text = "same"),
            comment(2, 1.5).copy(text = "same"), comment(3, 0.2).copy(text = "same", color = 0xff0000))
        val merged = mergeDuplicateDanmaku(comments)
        assertEquals(listOf(0, 3, 2), merged.map { it.id })
        assertEquals("same x2", merged.first().text)
        assertEquals(0.0, merged.first().timeSeconds)
        assertEquals(0xff0000, merged[1].color)
        val scheduler = DanmakuScheduler(comments, DanmakuSettings(displayAreaRatio = 1f, allowColorful = false), liveAdmission=false)
        assertEquals(listOf("same x2"), scheduler.frame(0.4, 640, 360, 36) { 100 }.map { it.comment.text })
        scheduler.applySettings(DanmakuSettings(displayAreaRatio = 1f, allowColorful = false, mergeDuplicates = false))
        assertEquals(listOf(0, 1), scheduler.frame(0.4, 640, 360, 36) { 100 }.map { it.comment.id })
    }

    private fun comment(id: Int, time: Double, mode: Int = 1) = DanmakuComment(id, time, mode, 25, 0xffffff, "Comment $id")
}

/** Collision fixtures supply explicit geometry/widths. Production has no rowHeight/config fallback. */
private fun DanmakuScheduler.frame(time:Double,width:Int,height:Int,rowHeight:Int,measure:(DanmakuComment)->Int):List<PositionedDanmaku> {
    val font=requireNotNull(javax.swing.UIManager.getFont("Label.font"))
    val platform=object:DesktopOriginalDanmakuRenderPlatform {
        override fun resolveTypeface(fontWeight:Int)=font
        override fun systemChromeInsetPx()=0 // Declared unused fixture chrome, never a production default.
        override fun maximumDisplayShortSidePx()=360f // Explicit fixture-only display mode, never a product fallback.
    }
    val settings=currentSettings
    val config=settings.originalConfig(platform).resolveRenderConfig(
        com.android.purebilibili.feature.video.danmaku.DanmakuViewport(width,height,1f)
    ).copy(lineHeightPx=rowHeight.toFloat(),lineMarginPx=0f,
        lineCount=(height*settings.displayAreaRatio/rowHeight).toInt().coerceAtLeast(0))
    return frame(time,width,height,config) {DesktopDanmakuTextMetrics(measure(it),rowHeight-6.0)}
}
