package com.bilipai.desktop.ui

import com.bilipai.desktop.diagnostics.DesktopDiagnostics
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Windows platform selection, not a Firebase implementation. Available local diagnostics
 * observe the SAME analytics key and enhanced consent. A failed diagnostic initialization
 * is an explicit unavailable capability; it does not obstruct original feed algorithms. */
internal class DesktopHomeRootAnalytics(
    private val globalStore: DesktopPluginStore,
    private val diagnostics: DesktopDiagnostics?,
    private val isCurrent: () -> Boolean,
) : DesktopHomeAnalyticsPort, DesktopHomeIdentityAnalytics {
    val firebaseTransportAvailable: Boolean get() = false
    val localDiagnosticConsumerAvailable: Boolean get() = diagnostics != null
    private val identity = diagnostics?.let { DesktopHomeLocalIdentityAnalytics(globalStore, it, isCurrent) }
    override fun syncUserContext(mid: Long?, isVip: Boolean, privacyModeEnabled: Boolean) {
        identity?.syncUserContext(mid, isVip, privacyModeEnabled)
    }
    private fun record(tag: String, text: String) {
        try {
            if (!isCurrent()) return
            if (globalStore.preferences("settings")["analytics_enabled"]?.jsonPrimitive?.booleanOrNull == false) return
            diagnostics?.record("I", tag, text)
        } catch (_: Exception) { /* Original platform failures do not interrupt navigation/feed. */ }
    }
    override fun logScreenView(name: String) = record("HomeScreen", "screen=${name.take(80)}")
    fun logSearch(keyword: String) = record("Search", "queryLength=${keyword.length}")
    override fun logCategoryView(categoryName: String, categoryId: Int) =
        record("HomeCategory", "category=${categoryName.take(80)}, id=$categoryId")
    override fun logHomeReturnAnimationPerformance(actualDurationMs: Long, plannedSuppressionMs: Long,
        sharedTransitionEnabled: Boolean, sharedTransitionReady: Boolean, isQuickReturn: Boolean,
        isTabletLayout: Boolean, cardAnimationEnabled: Boolean, builtinPluginEnabledCount: Int,
        playerPluginEnabledCount: Int, feedPluginEnabledCount: Int, danmakuPluginEnabledCount: Int,
        jsonPluginEnabledCount: Int, jsonFeedPluginEnabledCount: Int, jsonDanmakuPluginEnabledCount: Int) =
        record("HomeReturn", "actualMs=$actualDurationMs, plannedMs=$plannedSuppressionMs, shared=$sharedTransitionEnabled, ready=$sharedTransitionReady, quick=$isQuickReturn, tablet=$isTabletLayout, animated=$cardAnimationEnabled, builtin=$builtinPluginEnabledCount, player=$playerPluginEnabledCount, feed=$feedPluginEnabledCount, danmaku=$danmakuPluginEnabledCount, json=$jsonPluginEnabledCount, jsonFeed=$jsonFeedPluginEnabledCount, jsonDanmaku=$jsonDanmakuPluginEnabledCount")
}
