package com.bilipai.desktop.diagnostics

import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals

/** The取证 report is read by tools that parse strict JSON; line breaks and quotes in a value must not break it. */
class DesktopWindowExceptionUiReportTest {
    @Test fun reportWithLineBreaksQuotesAndControlCharactersIsStrictJson() {
        val tree = "\njavax.swing.JRootPane 8,35 484x277\n  javax.swing.JPanel\t\"quoted\" \\ back\u0001slash"
        val text = DesktopWindowExceptionHandlerUiFixture.renderReport(
            passed = true,
            observations = linkedMapOf("noticeComponentTree" to tree, "noticeText" to "重启客户端「恢复」"),
            failure = IllegalStateException("multi\nline \"failure\""),
        )

        // Jackson rejects unescaped control characters unless told otherwise.
        val parsed = ObjectMapper().readTree(text)

        assertEquals(true, parsed["passed"].asBoolean())
        assertEquals(tree, parsed["noticeComponentTree"].asText())
        assertEquals("重启客户端「恢复」", parsed["noticeText"].asText())
        assertEquals("java.lang.IllegalStateException: multi\nline \"failure\"", parsed["failure"].asText())
    }

    @Test fun reportWithoutFailureWritesNull() {
        val parsed = ObjectMapper().readTree(
            DesktopWindowExceptionHandlerUiFixture.renderReport(false, linkedMapOf("a" to "b"), null))
        assertEquals(false, parsed["passed"].asBoolean())
        assertEquals(true, parsed["failure"].isNull)
    }
}
