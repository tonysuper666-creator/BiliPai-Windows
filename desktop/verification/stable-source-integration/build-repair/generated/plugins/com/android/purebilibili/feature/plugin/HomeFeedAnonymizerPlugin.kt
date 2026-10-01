// GENERATED from app/src/main/java/com/android/purebilibili/feature/plugin/HomeFeedAnonymizerPlugin.kt; do not edit.
// LF-normalized SHA-256: a20b4f40c3de9f5e508c9f23350a782b22137348a8b99b2d786e94a868586cf0
package com.android.purebilibili.feature.plugin

import com.android.purebilibili.core.network.policy.HomeFeedAnonymizerRuntime
import com.android.purebilibili.core.network.policy.HomeFeedAnonymizerStatsSnapshot
import com.android.purebilibili.core.plugin.Plugin
import com.android.purebilibili.core.plugin.PluginCapability
import com.android.purebilibili.core.plugin.PluginCapabilityManifest
import com.bilipai.desktop.plugins.DesktopPluginLog as Logger
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal const val HOME_FEED_ANONYMIZER_PLUGIN_ID = "home_feed_anonymizer"
private const val TAG = "HomeFeedAnonymizerPlugin"
private const val HOME_FEED_ANONYMIZER_WEB_ENDPOINT =
    "https://api.bilibili.com/x/web-interface/wbi/index/top/feed/rcmd"
private val HOME_FEED_ANONYMIZER_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

class HomeFeedAnonymizerPlugin : Plugin {
    override val id: String = HOME_FEED_ANONYMIZER_PLUGIN_ID
    override val name: String = "初见推荐"
    override val description: String = "仅在 Web 首页推荐接口隐藏登录 Cookie，让推荐流更接近未登录公共热门"
    override val version: String = "1.0.2"
    override val author: String = "BiliPai项目组"
    override val capabilityManifest: PluginCapabilityManifest = PluginCapabilityManifest(
        pluginId = id,
        displayName = name,
        version = version,
        apiVersion = 1,
        entryClassName = "com.android.purebilibili.feature.plugin.HomeFeedAnonymizerPlugin",
        capabilities = setOf(
            PluginCapability.RECOMMENDATION_CANDIDATES,
            PluginCapability.NETWORK
        )
    )

    override suspend fun onEnable() {
        HomeFeedAnonymizerRuntime.setEnabled(true)
        Logger.d(TAG, "初见推荐已启用：Web 首页推荐接口将不携带 Cookie")
    }

    override suspend fun onDisable() {
        HomeFeedAnonymizerRuntime.setEnabled(false)
        Logger.d(TAG, "初见推荐已禁用：Web 首页推荐接口恢复登录 Cookie")
    }


}
