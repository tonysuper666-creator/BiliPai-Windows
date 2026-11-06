package com.bilipai.desktop.ui

import kotlin.test.*

class DesktopNavigationParserBindingTest {
    @Test fun originalNavigationParserKeepsWrappedLinksAndJvmUriBinding() {
        val parser = com.android.purebilibili.core.util.BilibiliNavigationTargetParser
        assertEquals(com.android.purebilibili.core.util.BilibiliNavigationTarget.Article(88),
            parser.parse("https://www.bilibili.com/blackboard?url=https%3A%2F%2Fwww.bilibili.com%2Fread%2Fcv88"))
        assertEquals("1000000000000001", com.android.purebilibili.core.util.BilibiliUrlParser
            .parseUri(android.net.Uri.parse("bilibili://opus/1000000000000001")).dynamicId)
    }
}
