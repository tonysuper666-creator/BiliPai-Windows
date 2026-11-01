package com.bilipai.desktop.platform.share

import com.bilipai.desktop.appearance.ReportingDesktopTextClipboard
import java.awt.EventQueue
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.ClipboardOwner
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AwtDesktopShareTest {
    private class RetryClipboard : Clipboard("share test") {
        val failure = IllegalStateException("clipboard busy")
        var fail = true
        var wroteOnEventThread = false
        override fun setContents(contents: Transferable, owner: ClipboardOwner?) {
            wroteOnEventThread = EventQueue.isDispatchThread()
            if (fail) { fail = false; throw failure }
            super.setContents(contents, owner)
        }
    }

    @Test fun preservesExactTextInRealClipboard() {
        val clipboard = Clipboard("share test")
        val share: DesktopShare = AwtDesktopShare { clipboard }
        val link = "  https://example.org/中文?q=😀&x=1\n  "
        share.copyLink(link)
        assertEquals(link, clipboard.getData(DataFlavor.stringFlavor))
        share.copyLink("")
        assertEquals("", clipboard.getData(DataFlavor.stringFlavor))
    }

    @Test fun propagatesNativeFailureAndNextRequestWorks() {
        val clipboard = RetryClipboard()
        val share = AwtDesktopShare { clipboard }
        assertSame(clipboard.failure, assertThrows(IllegalStateException::class.java) { share.copyLink("first") })
        share.copyLink("https://example.org/retry")
        assertTrue(clipboard.wroteOnEventThread)
        assertEquals("https://example.org/retry", clipboard.getData(DataFlavor.stringFlavor))
    }

    @Test fun worksWhenCalledOnEventThread() {
        val clipboard = Clipboard("share test")
        val share = AwtDesktopShare { clipboard }
        EventQueue.invokeAndWait { share.copyLink("https://example.org/edt") }
        assertEquals("https://example.org/edt", clipboard.getData(DataFlavor.stringFlavor))
    }

    @Test fun legacyAdapterReportsFailureAndClearsItAfterRetry() {
        val clipboard = RetryClipboard()
        var message: String? = null
        val legacy = ReportingDesktopTextClipboard(AwtDesktopShare { clipboard }) { message = it }
        assertFalse(legacy.copyText("first"))
        assertEquals("无法写入系统剪贴板，请稍后重试", message)
        assertTrue(legacy.copyText("第二次"))
        assertNull(message)
        assertEquals("第二次", clipboard.getData(DataFlavor.stringFlavor))
    }
}
