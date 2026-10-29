@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.bilipai.desktop.diagnostics

import kotlin.test.*

/** Compose's default window handler opens a system message box for an uncaught
 * exception. The desktop records the failure and keeps the notice in-app. */
class DesktopWindowExceptionHandlerTest {
    @Test fun uncaughtWindowFailureIsRecordedAndShownInApp() {
        val recorded = mutableListOf<Throwable>()
        val notices = mutableListOf<String>()
        val handler = DesktopWindowExceptionHandler({ recorded += it }, { notices += it })
        val failure = IllegalStateException("Original resolved video metadata is required before transport publication")

        handler.handle(failure)

        assertEquals(listOf<Throwable>(failure), recorded)
        val notice = notices.single()
        assertFalse(notice.contains("Original resolved video metadata"), "no raw exception text in the notice")
        assertTrue(notice.contains("IllegalStateException"))
    }

    @Test fun aFailingRecordStepStillShowsTheNotice() {
        val notices = mutableListOf<String>()
        val handler = DesktopWindowExceptionHandler({ throw IllegalStateException("disk unavailable") }, { notices += it })

        handler.handle(RuntimeException("window failure"))

        assertEquals(1, notices.size)
    }

    @Test fun aFailingNoticeStepNeverEscapesToTheWindow() {
        val records = mutableListOf<Throwable>()
        val handler = DesktopWindowExceptionHandler({ records += it }, { throw IllegalArgumentException("ui unavailable") })

        handler.handle(RuntimeException("window failure"))

        assertEquals(1, records.size)
    }
}
