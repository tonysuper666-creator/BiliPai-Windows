package com.bilipai.desktop.player

import com.android.purebilibili.danmaku.parser.DanmakuParser
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopUpstreamDanmakuXmlTest {
    @Test fun ordinaryXmlRetainsUnicodeTimingAndEscapedText() {
        val result = DanmakuParser.parse("""<i><d p="1.25,1,25,16777215,0,0,author,42">你好 &amp; welcome</d></i>""".toByteArray())
        val item = result.standardList.single()
        assertEquals("你好 & welcome", item.text)
        assertEquals(1250L, item.showAtTime)
        assertEquals(1f, item.textSizeScale)
    }

    @Test fun externalEntitiesCannotReadLocalFiles() {
        val secret = Files.createTempFile("danmaku-xml-entity", ".txt")
        try {
            Files.writeString(secret, "private-fixture-content")
            val result = DanmakuParser.parse("""<!DOCTYPE i [<!ENTITY external SYSTEM "${secret.toUri()}">]><i><d p="1,1,25,16777215,0,0,author,42">&external;</d></i>""".toByteArray())
            assertTrue(result.standardList.isEmpty())
            assertTrue(result.advancedList.isEmpty())
            assertTrue(result.basList.isEmpty())
        } finally {
            Files.deleteIfExists(secret)
        }
    }
}
