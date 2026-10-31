package com.bilipai.desktop.player

import com.android.purebilibili.feature.video.danmaku.DanmakuCloudRuleSyncPolicy
import com.android.purebilibili.feature.video.danmaku.appendDanmakuUserHashBlockRule
import com.android.purebilibili.feature.video.danmaku.matchesDanmakuBlockRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopDanmakuSenderRuleIdentityTest {
    @Test fun legacyHexAndExplicitHashKeepTheExistingRuleAndOrder() {
        val original = "剧透\nuid:abc123\nregex:^spoiler"
        assertEquals(original, appendDanmakuUserHashBlockRule(original, "hash:ABC123"))
        assertTrue(matchesDanmakuBlockRule("ordinary", "uid:abc123", "ABC123"))
    }

    @Test fun numericUidAndNumericHashRemainDifferentSenders() {
        val original = "uid:12345"
        assertEquals("uid:12345\nhash:12345", appendDanmakuUserHashBlockRule(original, "12345"))
        assertFalse(matchesDanmakuBlockRule("ordinary", original, "12345"))
        assertTrue(matchesDanmakuBlockRule("ordinary", "hash:12345", "12345"))
    }

    @Test fun aHashAlreadyCoveredByAnActualUidDoesNotAddADuplicate() {
        val original = "uid:12345"
        val resolved = requireNotNull(DanmakuCloudRuleSyncPolicy.localUidRuleToCloudFilter(original))
        assertEquals(original, appendDanmakuUserHashBlockRule(original, "hash:$resolved"))
        assertTrue(matchesDanmakuBlockRule("ordinary", original, resolved))
    }

    @Test fun textAndRegexWithTheSameBodyAreNotSenderRules() {
        val original = "abc123\nregex:abc123"
        assertEquals("$original\nhash:abc123", appendDanmakuUserHashBlockRule(original, "hash:abc123"))
    }
}
