// GENERATED from app/src/main/java/com/android/purebilibili/feature/plugin/SubscriptionFeedPlugin.kt; do not edit.
// LF-normalized SHA-256: 60d09297b13cc6af088e8681f927545924b11eafae4978752d178eb7820d7ef9
package com.android.purebilibili.feature.plugin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.android.purebilibili.core.plugin.Plugin
import com.android.purebilibili.core.plugin.feed.FeedConditionalStore
import com.android.purebilibili.core.plugin.feed.SubscriptionFeedStore
import com.android.purebilibili.core.plugin.feed.buildSubscriptionOpml
import com.android.purebilibili.core.plugin.feed.resolveSubscriptionTitle
import com.android.purebilibili.core.plugin.feed.resolveImportedSubscriptionTitles
import com.android.purebilibili.plugin.sdk.PluginCapability
import com.android.purebilibili.plugin.sdk.PluginCapabilityManifest

class SubscriptionFeedPlugin : Plugin {
    override val id: String = PLUGIN_ID
    override val name: String = "订阅"
    override val description: String = "关注喜欢的网站，在首页集中阅读更新；支持 RSS、Atom 和 OPML 导入。"
    override val version: String = "1.0.0"
    override val author: String = "BiliPai"
    override val capabilityManifest: PluginCapabilityManifest = PluginCapabilityManifest(
        pluginId = PLUGIN_ID,
        displayName = name,
        version = version,
        apiVersion = 1,
        entryClassName = SubscriptionFeedPlugin::class.java.name,
        capabilities = setOf(
            PluginCapability.FEED_SOURCE,
            PluginCapability.NETWORK,
            PluginCapability.PLUGIN_STORAGE,
        ),
    )





    companion object {
        const val PLUGIN_ID = "subscription_feed"
    }
}



internal suspend fun resolveImportPayload(raw: String): String {
    val text = raw.trim()
    if (!text.contains('\n') && com.android.purebilibili.core.plugin.feed.isHttpFeedUrl(text)) {
        val body = com.android.purebilibili.core.plugin.feed.fetchFeedXml(text).getOrNull()
        if (body != null && (body.contains("<opml", ignoreCase = true) || body.contains("<outline", ignoreCase = true))) {
            return body
        }
    }
    return text
}
