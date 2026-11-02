package com.bilipai.desktop.download

import com.bilipai.desktop.integration.DesktopNotificationCenter
import com.bilipai.desktop.platform.toast.DesktopToast
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DesktopDownloadCompletionToastTest {
    /** Stands in for the Windows notification center; records exactly what the owner asked to show. */
    private class RecordingNotificationCenter : DesktopNotificationCenter {
        val shown = CopyOnWriteArrayList<List<String>>()
        @Volatile var failure: RuntimeException? = null

        override fun show(title: String, text: String, route: String, tag: String, group: String) {
            failure?.let { throw it }
            shown += listOf(title, text, route, tag, group)
        }

        override fun remove(tag: String, group: String) = throw AssertionError("a completion notice never withdraws a single toast")
        override fun clear(group: String) = throw AssertionError("a completion notice never clears a group")
    }

    private fun completion(
        center: RecordingNotificationCenter,
        stillOwned: () -> Boolean = { true },
        onFailure: (String, Exception) -> Unit = { _, _ -> },
    ) = DesktopDownloadCompletionToast(
        toast = DesktopToast(center = center, title = "下载完成"),
        stillOwned = stillOwned,
        onFailure = onFailure,
    )

    private fun flushEdt() = SwingUtilities.invokeAndWait {}

    @Test fun aFinishedTaskReachesTheNotificationCenterUnchanged() {
        val center = RecordingNotificationCenter()
        // The message is user data: a task title must survive quoting, markup characters and newlines.
        val taskTitle = "【合集】第 1 话：路径 “C:\\下载” 不可写 & <重试>\n第二行 😀"
        completion(center).notify("BV1xx411c7mD-p1", taskTitle, { false })
        assertEquals(
            listOf(listOf("下载完成", taskTitle, "downloads", "BV1xx411c7mD-p1", "downloads")),
            center.shown,
        )
    }

    @Test fun twoFinishedTasksKeepDistinctTagsSoNeitherBannerReplacesTheOther() {
        val center = RecordingNotificationCenter()
        val notice = completion(center)
        notice.notify("101", "片头", { false })
        notice.notify("102", "片尾", { false })
        assertEquals(listOf("101", "102"), center.shown.map { it[3] }, "the tag is the task id, exactly as before")
        assertEquals(setOf("downloads"), center.shown.map { it[4] }.toSet(), "every completion stays in one group")
    }

    @Test fun aClosedOwnerPublishesNothing() {
        val center = RecordingNotificationCenter()
        completion(center).notify("7", "任务", { true })
        assertEquals(emptyList(), center.shown, "a retired notification owner must not speak for a finished download")
    }

    @Test fun aRetiredOwnerPublishesNothing() {
        val center = RecordingNotificationCenter()
        completion(center, stillOwned = { false }).notify("7", "任务", { false })
        assertEquals(emptyList(), center.shown, "an owner that lost its window must not post to the notification center")
    }

    @Test fun aCenterFailureIsReportedOnTheEventDispatchThreadInsteadOfEscaping() {
        val center = RecordingNotificationCenter()
        val failure = IllegalStateException("Windows 通知已被系统或用户禁用")
        center.failure = failure
        val reportedMessage = AtomicReference<String?>()
        val reportedFailure = AtomicReference<Throwable?>()
        val reportedOnEdt = AtomicBoolean()
        val reported = CountDownLatch(1)
        completion(center, onFailure = { message, error ->
            reportedMessage.set(message)
            reportedFailure.set(error)
            reportedOnEdt.set(SwingUtilities.isEventDispatchThread())
            reported.countDown()
        }).notify("7", "任务", { false })
        assertTrue(reported.await(10, TimeUnit.SECONDS), "a failed notice must be reported, not swallowed")
        assertEquals("下载完成通知失败", reportedMessage.get())
        assertSame(failure, reportedFailure.get(), "the owner keeps the real cause for its diagnostics")
        assertTrue(reportedOnEdt.get(), "the report belongs on the EDT next to the other download-notification failures")
        assertEquals(emptyList(), center.shown, "a failed toast must not be recorded as delivered")
    }

    @Test fun aFailureThatArrivesAfterTheOwnerClosedIsNotReported() {
        val center = RecordingNotificationCenter()
        center.failure = IllegalStateException("Windows 通知中心响应超时")
        val closed = AtomicBoolean(false)
        val reported = AtomicBoolean(false)
        completion(center, onFailure = { _, _ -> reported.set(true) }).notify("7", "任务") { closed.get() }
        // The IO call has already returned and queued its report; the owner retires before it runs.
        closed.set(true)
        flushEdt()
        assertFalse(reported.get(), "a failure cannot outlive the window it was reporting against")
    }
}
