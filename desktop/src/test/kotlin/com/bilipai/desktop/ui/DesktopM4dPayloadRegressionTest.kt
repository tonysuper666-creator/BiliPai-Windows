package com.bilipai.desktop.ui

import com.android.purebilibili.feature.message.parseChatTip
import com.android.purebilibili.feature.plugin.js.parseJsDanmuColorInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopM4dPayloadRegressionTest {
    @Test
    fun sessionTipsRejectBareLiteralsAndKeepQuotedText() {
        listOf("invalid", "true", "42", "null").forEach { payload ->
            assertEquals("", parseChatTip(payload), payload)
        }
        assertEquals("提示", parseChatTip("\"提示\""))
        assertEquals("提示", parseChatTip("""[{"text":"提示"},{"content":42}]"""))
    }

    @Test
    fun decimalColorParsingDoesNotDependOnDigitCount() {
        listOf("255", "123456", "16711680", "4294967295").forEach { decimal ->
            assertEquals(decimal.toLong().toInt(), parseJsDanmuColorInt(decimal), decimal)
        }
        assertEquals(0xFF123456.toInt(), parseJsDanmuColorInt("#123456"))
        assertEquals(0x80112233.toInt(), parseJsDanmuColorInt("#80112233"))
        assertNull(parseJsDanmuColorInt("#12345"))
        assertNull(parseJsDanmuColorInt("FFFFFF"))
    }
}
