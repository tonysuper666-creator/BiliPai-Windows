package com.bilipai.desktop.integration

import org.junit.Test
import org.junit.Assert.*
import javax.xml.parsers.DocumentBuilderFactory

class DesktopToastNotificationsTest {
    @Test fun unicodeDestinationsAndMarkupRemainDataAcrossProtocolActivation() {
        val route = "account:123:message/chat?name=中文 & \"user\""
        val uri = DesktopToastNotifications.activationUri(route)
        assertEquals(route, DesktopToastNotifications.routeFromArgs(arrayOf(uri)))
        val content = DesktopToastNotifications.content("<消息 & 回复>", "带 '引号' 的内容", route)
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(content.byteInputStream())
        assertEquals(uri, document.documentElement.getAttribute("launch"))
        assertEquals("<消息 & 回复>", document.getElementsByTagName("text").item(0).textContent)
    }
    @Test fun ordinaryLaunchDoesNotInventAnActivation() {
        assertEquals("", DesktopToastNotifications.routeFromArgs(arrayOf("--video=BV123")))
    }
}
