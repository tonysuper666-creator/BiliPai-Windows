package com.bilipai.desktop.ui

import com.android.purebilibili.danmaku.parser.DanmakuProto
import com.android.purebilibili.feature.video.danmaku.CommandDanmakuItem
import com.android.purebilibili.feature.video.danmaku.CommandDanmakuType
import com.android.purebilibili.feature.video.danmaku.buildCommandDanmakuItem
import kotlin.test.*

/** Actual fixed metadata parser -> the selection function used by the Windows command consumer.
 * No network, accounts, Compose window or native player is created. */
class DesktopWindowsCommandCardPolicyTest {
    private fun metadata(): List<CommandDanmakuItem> = listOf(
        DanmakuProto.CommandDm(id = 1, command = "#UP#", content = "原 UP 提示", progress = 1000),
        DanmakuProto.CommandDm(id = 2, command = "#LINK#", progress = 1000,
            extra = """{"aid":17,"bvid":"BVfixture","title":"原关联视频","icon":"https://example.invalid/icon.png"}"""),
        DanmakuProto.CommandDm(id = 3, command = "NOTICE", content = "原文本提示", progress = 1000),
        DanmakuProto.CommandDm(id = 4, command = "#ATTENTION#", content = "关注作者", progress = 1000,
            extra = """{"type":2,"duration":6500,"posX_2":90,"posY_2":90}"""),
        DanmakuProto.CommandDm(id = 5, command = "#VOTE#", progress = 1000,
            extra = """{"vote_id":99,"title":"原投票","options":[{"idx":1,"desc":"选项"}]}"""),
        DanmakuProto.CommandDm(id = 6, command = "#GRADE#", progress = 1000,
            extra = """{"grade_id":100,"msg":"原评分","mid_score":0,"count":76,"avg_score":9.8}"""),
        DanmakuProto.CommandDm(id = 7, command = "#VOTE#", content = "原投票降级提示", progress = 1000,
            extra = "{}"),
    ).map { assertNotNull(buildCommandDanmakuItem(it)) }

    @Test fun originalMetadataInfoAndVoteFallbackReachTheActualSelection() {
        val raw = metadata()
        val visible = desktopWindowsVisibleCommandCards(raw, hideInteractiveCommands = false, attentionOwned = false)
        assertEquals(listOf(CommandDanmakuType.UP, CommandDanmakuType.LINK, CommandDanmakuType.TEXT,
            CommandDanmakuType.VOTE, CommandDanmakuType.VOTE, CommandDanmakuType.TEXT), visible.map { it.type })
        // The Windows selection must not clone, normalize, reorder or turn a title into an action.
        val originalNonAttention = listOf(raw[0], raw[1], raw[2], raw[4], raw[5], raw[6])
        visible.zip(originalNonAttention).forEach { (actual, original) -> assertSame(original, actual) }
    }

    @Test fun existingHidePreferenceSuppressesAllOriginalCommandTypes() {
        val raw = metadata()
        for (attentionOwned in listOf(false, true)) {
            assertTrue(desktopWindowsVisibleCommandCards(raw, hideInteractiveCommands = true, attentionOwned = attentionOwned).isEmpty())
        }
    }

    @Test fun attentionRequiresItsRealOwnedBindingWithoutDroppingInformationalCards() {
        val raw = metadata()
        val unowned = desktopWindowsVisibleCommandCards(raw, hideInteractiveCommands = false, attentionOwned = false)
        assertFalse(unowned.any { it.type == CommandDanmakuType.ATTENTION })
        val owned = desktopWindowsVisibleCommandCards(raw, hideInteractiveCommands = false, attentionOwned = true)
        assertEquals(raw.size, owned.size)
        raw.zip(owned).forEach { (original, actual) -> assertSame(original, actual) }
        // Binding retirement changes only ATTENTION visibility; informational items remain the same objects.
        val retired = desktopWindowsVisibleCommandCards(raw, hideInteractiveCommands = false, attentionOwned = false)
        assertEquals(unowned, retired)
    }
}
