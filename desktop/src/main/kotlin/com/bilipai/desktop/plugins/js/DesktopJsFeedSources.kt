package com.bilipai.desktop.plugins.js

import com.android.purebilibili.core.plugin.feed.FeedSource
import com.android.purebilibili.core.plugin.feed.loadEnabledFeedSources

/** The original catalog constructs RSS sources; the host adds exact-current-script authorization. */
data class DesktopJsFeedSourceSnapshot(val revision: Long, val sources: List<FeedSource>)

suspend fun DesktopJsPluginRepository.feedSourceSnapshot(): DesktopJsFeedSourceSnapshot {
    val (revision, allowed) = approvedFeedModuleIds()
    val sources = if (allowed.isEmpty()) emptyList() else loadEnabledFeedSources(context).filter { it.id in allowed }
    check(host.executionRevision.value == revision) { "JS 插件授权在加载订阅时变化" }
    return DesktopJsFeedSourceSnapshot(revision, sources)
}

suspend fun DesktopJsPluginRepository.enabledFeedSources(): List<FeedSource> = feedSourceSnapshot().sources
