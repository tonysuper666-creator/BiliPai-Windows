package com.bilipai.desktop.platform.toast

import com.bilipai.desktop.integration.DesktopNotificationCenter
import com.bilipai.desktop.integration.DesktopToastNotifications
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class DesktopToastTest {
    /** Stands in for the notification center at the facade boundary; records exactly what it was asked to show. */
    private class RecordingCenter : DesktopNotificationCenter {
        val shown = mutableListOf<List<String>>()
        var failure: RuntimeException? = null

        override fun show(title: String, text: String, route: String, tag: String, group: String) {
            failure?.let { throw it }
            shown += listOf(title, text, route, tag, group)
        }

        override fun remove(tag: String, group: String) = throw AssertionError("a toast never withdraws")
        override fun clear(group: String) = throw AssertionError("a toast never clears a group")
    }

    @Test fun theMessageAndDestinationReachTheCenterUnchanged() {
        val center = RecordingCenter()
        val message = "保存失败：路径 “C:\\图片” 不可写 & <重试>\n第二行 😀"
        DesktopToast(center).show(message, route = "settings/appearance", tag = "save-1", group = "settings")
        assertEquals(
            listOf(DesktopToast.APP_NAME, message, "settings/appearance", "save-1", "settings"),
            center.shown.single(),
        )
    }

    @Test fun omittedArgumentsUseTheToastDefaults() {
        val center = RecordingCenter()
        DesktopToast(center).show("已保存")
        assertEquals(
            listOf(DesktopToast.APP_NAME, "已保存", "", DesktopToast.DEFAULT_TAG, DesktopToast.DEFAULT_GROUP),
            center.shown.single(),
        )
    }

    @Test fun nativeFailureReachesTheCaller() {
        val center = RecordingCenter()
        val failure = IllegalStateException("Windows 通知中心失败: Windows 通知已被系统或用户禁用")
        center.failure = failure
        assertSame(failure, assertFailsWith<IllegalStateException> { DesktopToast(center).show("不会出现") })
        assertEquals(emptyList(), center.shown, "a failed toast must not be recorded as delivered")
    }

    @Test fun aFailedToastCanBeShownAgain() {
        val center = RecordingCenter()
        val toast = DesktopToast(center)
        center.failure = IllegalStateException("Windows 通知中心响应超时")
        assertFailsWith<IllegalStateException> { toast.show("第一次") }
        center.failure = null
        toast.show("第二次")
        assertEquals("第二次", center.shown.single()[1])
    }

    @Test fun theRealCentersPayloadCarriesTheMessageUnchanged() {
        // The default center hands Windows exactly this XML; escaping must not alter the text the user reads.
        val message = "保存失败：路径 “C:\\图片” 不可写 & <重试>"
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(DesktopToastNotifications.content(DesktopToast.APP_NAME, message, "settings/appearance").byteInputStream())
        val texts = document.getElementsByTagName("text")
        assertEquals(DesktopToast.APP_NAME, texts.item(0).textContent)
        assertEquals(message, texts.item(1).textContent)
    }
}
