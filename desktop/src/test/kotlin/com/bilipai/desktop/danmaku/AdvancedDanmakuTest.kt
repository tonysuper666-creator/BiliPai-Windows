package com.bilipai.desktop.danmaku

import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdvancedDanmakuTest {
    @Test fun `XML mode seven retains array interpolation and rejects mode nine arrays and executable mode eight`() {
        val document = DanmakuParser.parseDocument("""<i>
            <d p="1,7,25,16711680,0,0,hash,10">[0.2,0.3,"1-0",2,"BAS",30,0,0.8,0.7,2,0,"false","","0"]</d>
            <d p="1,9,25,16777215,0,0,hash,11">[336,219,"",3,"mode9"]</d>
            <d p="1,8,25,16777215,0,0,hash,12">arbitrary executable code</d>
            <d p="2,1,25,16777215,0,0,abcd,20">normal</d>
        </i>""")
        assertEquals(listOf("BAS"), document.advanced.map { it.content })
        assertTrue(document.bas.isEmpty())
        assertEquals(listOf("normal"), document.comments.map { it.text })
        assertEquals("abcd", document.comments.single().userHash)
        assertEquals(20L, document.comments.single().serverId)
        val renderer = AdvancedDanmakuRenderer(document.advanced)
        assertTrue(renderer.frame(0, DanmakuSettings()).isEmpty())
        val halfway = renderer.frame(2_000, DanmakuSettings(opacity = 1f)).first()
        // Quadratic ease-out maps the halfway translation to 75% of the authored path.
        assertEquals(0.65f, halfway.x, 0.0001f)
        assertEquals(0.6f, halfway.y, 0.0001f)
        assertEquals(0.5f, halfway.alpha, 0.0001f)
        val pixelCoordinates = DanmakuParser.parseDocument("""<i><d p="1,7,25,16777215">[336,219,"",3,"pixel coordinates"]</d></i>""")
        assertEquals(0.5f, pixelCoordinates.advanced.single().startX)
        assertTrue(renderer.frame(6_000, DanmakuSettings()).isEmpty())
        assertEquals(halfway, renderer.frame(2_000, DanmakuSettings(opacity = 1f)).first())
    }

    @Test fun `Swing renderer paints actual transparent colored glyphs at the authored position`() {
        val document = DanmakuParser.parseDocument("""<i><d p="0,7,25,16711680">[0.2,0.25,"1-0",2,"WINDOWS BAS"]</d></i>""")
        val renderer = AdvancedDanmakuRenderer(document.advanced)
        val image = BufferedImage(500, 300, BufferedImage.TYPE_INT_ARGB)
        val settings = DanmakuSettings(opacity = 1f, strokeEnabled = false)
        image.createGraphics().use { renderer.paint(it, 1_000, image.width, image.height, settings) }
        val pixels = (0 until image.height).flatMap { y -> (0 until image.width).map { x -> x to y } }
            .filter { (x, y) -> Color(image.getRGB(x, y), true).alpha > 0 }
        assertTrue(pixels.size > 100, "Advanced glyphs did not render")
        assertTrue(pixels.all { (x, y) -> x >= 100 && y >= 75 })
        assertTrue(pixels.any { (x, y) -> Color(image.getRGB(x, y), true).let { it.red > 200 && it.green < 30 && it.alpha in 120..130 } })
        val hidden = BufferedImage(500, 300, BufferedImage.TYPE_INT_ARGB)
        hidden.createGraphics().use { renderer.paint(it, 1_000, hidden.width, hidden.height, settings.copy(allowSpecial = false)) }
        assertFalse((0 until hidden.height).any { y -> (0 until hidden.width).any { x -> hidden.getRGB(x, y) != 0 } })
    }

    @Test fun `upstream regex hash and colorful filters work with protocol metadata`() {
        val comments = listOf(
            DanmakuComment(0, 0.0, 1, 25, 0xffffff, "spoiler 123", userHash = "AbCd"),
            DanmakuComment(1, 0.0, 1, 25, 0xffffff, "ordinary"),
            DanmakuComment(2, 0.0, 1, 25, 0xffffff, "VIP gradient", isVipGradualColor = true),
        )
        assertFalse(DanmakuSettings(blockedRules = listOf("regex:spoiler\\s+\\d+")).allows(comments[0]))
        assertFalse(DanmakuSettings(blockedRules = listOf("uid:abcd")).allows(comments[0]))
        assertTrue(DanmakuSettings(blockedRules = listOf("regex:[invalid")).allows(comments[1]))
        assertFalse(DanmakuSettings(allowColorful = false).allows(comments[2]))
    }

    private inline fun <T> java.awt.Graphics2D.use(block: (java.awt.Graphics2D) -> T): T = try { block(this) } finally { dispose() }
}
