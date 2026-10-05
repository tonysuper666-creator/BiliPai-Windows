package com.android.purebilibili.feature.video.ui.components

import com.android.purebilibili.data.model.response.ReplyContentUrl
import kotlin.test.Test
import kotlin.test.assertEquals

/** The sole generated original policy is tested without opening any URL. */
class DesktopV029ReplyLinkPolicyTest {
    @Test fun unsupportedCommercialSchemeKeepsUsableWebTarget() {
        val web = "https://example.invalid/item/123"
        assertEquals(web, resolveReplyContentUrlNavigationUrl("商品", ReplyContentUrl(
            url = web, appUrlSchema = "vendor-store://item/123")))
    }

    @Test fun recognizedVideoSchemaKeepsOriginalNativeNavigationPriority() {
        val native = "bilibili://video/170001"
        assertEquals(native, resolveReplyContentUrlNavigationUrl("视频", ReplyContentUrl(
            url = "https://www.bilibili.com/video/BV17x411w7KC", appUrlSchema = native)))
    }

    @Test fun dynamicWebTargetWinsOverMisidentifiedVideoSchema() {
        val dynamic = "https://t.bilibili.com/123456789012345678"
        assertEquals(dynamic, resolveReplyContentUrlNavigationUrl("动态", ReplyContentUrl(
            url = dynamic, appUrlSchema = "bilibili://video/123456789012345678")))
    }

    @Test fun webTokenRemainsAvailableWhenMetadataContainsOnlyUnsupportedScheme() {
        val web = "https://example.invalid/article"
        assertEquals(web, resolveReplyContentUrlNavigationUrl(web, ReplyContentUrl(
            appUrlSchema = "unsupported://article")))
    }
}
