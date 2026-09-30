package com.bilipai.desktop.plugins

import androidx.compose.ui.graphics.Color
import com.android.purebilibili.core.plugin.*
import com.android.purebilibili.core.plugin.json.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.plugin.*
import com.android.purebilibili.feature.video.danmaku.DesktopPluginDanmakuPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class DesktopPluginParityTest {
    @Test fun `shared filter configuration and original plugin preferences survive reopening`(): Unit = runBlocking {
        val root = Files.createTempDirectory("bilipai-plugins-")
        val store = DesktopPluginStore(root)
        val context = DesktopPluginContext(store)
        assertFalse(store.feedFilterEnabled.value)
        val config = BiliPaiFeedFilterConfig(minDurationForRcmd = 40, minPlayForRcmd = 900,
            banWordForRecommend = "测试|推广", whitelistMids = mapOf(10L to "up"))
        store.setFeedFilterConfig(config)
        store.setFeedFilterEnabled(true)
        PluginStore.setConfigJson(context, "danmaku_enhance", "{\"enableFilter\":false}")
        PluginStore.setDataJson(context, "sponsor_block", "skip_history", "[]")
        val reopened = DesktopPluginStore(root)
        val reopenedContext = DesktopPluginContext(reopened)
        assertEquals(config, reopened.feedFilterConfig.value)
        assertTrue(reopened.feedFilterEnabled.value)
        assertTrue(PluginStore.isEnabledFlow(reopenedContext, "bilipai_feed_filter").first())
        assertEquals("{\"enableFilter\":false}", PluginStore.getConfigJson(reopenedContext, "danmaku_enhance"))
        assertEquals("[]", PluginStore.getDataJson(reopenedContext, "sponsor_block", "skip_history"))
        assertTrue(PluginStore.isEnabled(reopenedContext, "dlna_cast"))
        assertFalse(PluginStore.isEnabled(reopenedContext, "danmaku_enhance"))
    }

    @Test fun `invalid regex cannot replace the prior configuration`(): Unit = runBlocking {
        val root = Files.createTempDirectory("bilipai-plugins-invalid-")
        val store = DesktopPluginStore(root)
        val valid = BiliPaiFeedFilterConfig(minDurationForRcmd = 10)
        store.setFeedFilterConfig(valid)
        val before = Files.readString(root.resolve("plugin-settings.json"))
        assertFailsWith<Exception> { store.setFeedFilterConfig(valid.copy(banWordForRecommend = "[")) }
        assertEquals(before, Files.readString(root.resolve("plugin-settings.json")))
        assertEquals(valid, store.feedFilterConfig.value)
        assertFailsWith<IllegalArgumentException> { store.setFeedFilterConfig(valid.copy(minLikeRatioForRecommend = 101)) }
        assertEquals(before, Files.readString(root.resolve("plugin-settings.json")))
    }

    @Test fun `JSON stats and enabled preferences keep independent namespaces`() {
        val root = Files.createTempDirectory("bilipai-json-prefs-")
        val context = DesktopPluginContext(DesktopPluginStore(root))
        context.getSharedPreferences("json_plugins", 0).edit().putBoolean("enabled_user.rule", false).apply()
        context.getSharedPreferences("json_plugin_stats", 0).edit().putInt("user.rule", 8).apply()
        context.getSharedPreferences("today_watch_feedback", 0).edit().putString("payload", "{\"标题\":\"不感兴趣 🎬\"}").apply()
        val other = DesktopPluginContext(DesktopPluginStore(root))
        assertFalse(other.getSharedPreferences("json_plugins", 0).getBoolean("enabled_user.rule", true))
        assertEquals(8, other.getSharedPreferences("json_plugin_stats", 0).all["user.rule"])
        assertEquals("{\"标题\":\"不感兴趣 🎬\"}", other.getSharedPreferences("today_watch_feedback", 0).getString("payload", null))
        assertNull(other.getSharedPreferences("today_watch_feedback", 0).getString("missing", null))
        assertEquals("fallback", other.getSharedPreferences("json_plugin_stats", 0).getString("user.rule", "fallback"))
        other.getSharedPreferences("json_plugin_stats", 0).edit().clear().apply()
        assertFalse(other.getSharedPreferences("json_plugins", 0).getBoolean("enabled_user.rule", true))
    }

    @Test fun `original nested JSON rules evaluate owner numbers and regex titles`() {
        val rule = Rule(action = RuleAction.HIDE, condition = Condition.And(listOf(
            Condition.Simple("stat.view", RuleOperator.LT, JsonPrimitive(100)),
            Condition.Or(listOf(Condition.Simple("owner.mid", RuleOperator.EQ, JsonPrimitive(5)),
                Condition.Simple("title", RuleOperator.REGEX, JsonPrimitive("推广.*")))))))
        assertFalse(RuleEngine.shouldShowVideo(VideoItem(title = "普通", owner = Owner(mid = 5), stat = Stat(view = 20)), listOf(rule)))
        assertFalse(RuleEngine.shouldShowVideo(VideoItem(title = "推广测试", owner = Owner(mid = 8), stat = Stat(view = 20)), listOf(rule)))
        assertTrue(RuleEngine.shouldShowVideo(VideoItem(title = "推广测试", owner = Owner(mid = 8), stat = Stat(view = 1000)), listOf(rule)))
        val encoded = Json.encodeToString(JsonRulePlugin.serializer(), JsonRulePlugin("example", "test", type = "feed", rules = listOf(rule)))
        val decoded = Json.decodeFromString(JsonRulePlugin.serializer(), encoded)
        assertFalse(RuleEngine.shouldShowVideo(VideoItem(owner = Owner(mid = 5), stat = Stat(view = 20)), decoded.rules))
    }

    @Test fun `original danmaku chain falls back after plugin failure and merges style precedence`() {
        val item = DanmakuItem(1, "【翻译】hello", 300, userId = "user")
        val failing = plugin(filter = { throw IllegalStateException("failure") })
        val modifying = plugin(filter = { it.copy(content = it.content + "!") }, style = DanmakuStyle(textColor = Color.Red, bold = true, scale = 1.2f))
        val next = plugin(style = DanmakuStyle(backgroundColor = Color.Black, scale = 1f))
        val result = assertNotNull(DesktopPluginDanmakuPolicy.runDanmakuFilters(item, listOf(failing, modifying, next), false))
        assertEquals("【翻译】hello!", result.content)
        val style = assertNotNull(DesktopPluginDanmakuPolicy.collectDanmakuStyle(result, listOf(failing, modifying, next), false))
        assertEquals(Color.Red, style.textColor)
        assertEquals(Color.Black, style.backgroundColor)
        assertTrue(style.bold)
        assertEquals(1.2f, style.scale)
        assertNull(DesktopPluginDanmakuPolicy.runDanmakuFilters(item, listOf(plugin(filter = { null })), false))
    }

    @Test fun `color and URI bindings preserve upstream validation behavior`() {
        assertEquals(0xffffd700.toInt(), DesktopPluginColor.parseColor("#FFD700"))
        assertEquals(0x8044aaff.toInt(), DesktopPluginColor.parseColor("#8044AAFF"))
        assertEquals(0xffff0000.toInt(), DesktopPluginColor.parseColor("RED"))
        assertFailsWith<IllegalArgumentException> { DesktopPluginColor.parseColor("#fff") }
        assertNull(DesktopPluginUrl.parse("not a valid uri").host)
        assertEquals("https", DesktopPluginUrl.parse("https://example.com/plugin.json").scheme)
        val highlight = Rule(field = "content", op = RuleOperator.CONTAINS, value = JsonPrimitive("翻译"),
            action = RuleAction.HIGHLIGHT, style = HighlightStyle(color = "#FFD700", bold = true, scale = 1.05f))
        assertTrue(assertNotNull(RuleEngine.getDanmakuHighlightStyle(DanmakuItem(1, "翻译", 0), listOf(highlight))).bold)
    }

    @Test fun `external plugin policy rejects version drift and still requires explicit grants`() {
        val manifest = PluginCapabilityManifest(pluginId = "sample", displayName = "sample", version = "1", apiVersion = 1,
            entryClassName = "sample.Plugin", capabilities = setOf(PluginCapability.NETWORK, PluginCapability.PLAYER_CONTROL))
        assertIs<ExternalPluginInstallDecision.Rejected>(evaluateExternalPluginInstall(ExternalPluginPackageDescriptor(manifest.copy(apiVersion = 2), "abc", null), emptySet()))
        val approval = assertIs<ExternalPluginInstallDecision.RequiresUserApproval>(evaluateExternalPluginInstall(ExternalPluginPackageDescriptor(manifest, "abc", "trusted"), setOf("trusted")))
        assertTrue(approval.signerTrusted)
        assertEquals(manifest.capabilities, approval.sensitiveCapabilities)
        assertFalse(resolvePluginCapabilityGrants(manifest, setOf(PluginCapability.NETWORK)).isGranted(PluginCapability.PLAYER_CONTROL))
    }

    private fun plugin(filter: (DanmakuItem) -> DanmakuItem? = { it }, style: DanmakuStyle? = null) = object : DanmakuPlugin {
        override val id = "test"
        override val name = "test"
        override val description = "test"
        override val version = "1"
        override fun filterDanmaku(danmaku: DanmakuItem) = filter(danmaku)
        override fun styleDanmaku(danmaku: DanmakuItem) = style
    }
}
