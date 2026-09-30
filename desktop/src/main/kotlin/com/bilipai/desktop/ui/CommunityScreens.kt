package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.DesktopPluginRuntime
import java.awt.Desktop
import java.net.URI

enum class CommunitySection(val title: String) {
    DYNAMIC("动态"), SEARCH("搜索"), USER("UP 主空间"), MESSAGES("消息"), ARTICLE("专栏"), NOTES("视频笔记")
}

internal class CommunityNavigation(val onVideo: (VideoCard) -> Unit, val onUser: (Long) -> Unit,
    val onArticle: (Long) -> Unit, val onLogin: () -> Unit, val onLive: (Long) -> Unit,
    val onBangumi: (Long) -> Unit, val onDynamic: (String) -> Unit, val onTopic: (Long) -> Unit = {},
    val onTopicKeyword: (String) -> Unit = {})

@Composable
fun CommunityContentScreen(section: CommunitySection, repository: DesktopRepository, social: DesktopSocialRepository,
    community: DesktopCommunityRepository, query: String = "", userId: Long = 0, articleId: Long = 0,
    noteVideo: VideoDetails? = null, onVideo: (VideoCard) -> Unit, onUser: (Long) -> Unit, onArticle: (Long) -> Unit,
    onLogin: () -> Unit, onLive: (Long) -> Unit = {}, onBangumi: (Long) -> Unit = {}, runtime: DesktopPluginRuntime? = null,
    initialDynamicId: String? = null, onTopic: (Long) -> Unit = {}, onTopicKeyword: (String) -> Unit = {}, defaultSearchHintEnabled: Boolean = true) {
    val account by repository.account.collectAsState()
    val inherited = LocalDesktopBrowseMemory.current
    val fallback = remember(account?.mid) { DesktopBrowseMemory() }
    val browseMemory = inherited ?: fallback
    var dynamicDetail by remember(browseMemory, section, userId, query, initialDynamicId) {
        browseMemory.screen(listOf("community-dynamic-detail", section, userId, query, initialDynamicId)) { mutableStateOf(initialDynamicId) }
    }
    val feedMemory = browseMemory.feeds
    val navigation = CommunityNavigation(onVideo, onUser, onArticle, onLogin, onLive, onBangumi, { dynamicDetail = it }, onTopic, onTopicKeyword)
    CompositionLocalProvider(LocalDesktopBrowseMemory provides browseMemory, LocalCommunityFeedMemory provides feedMemory,
        LocalCommunityFeedNamespace provides listOf(section, userId, articleId, noteVideo?.aid)) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(20.dp, 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (dynamicDetail != null) TextButton(onClick = { dynamicDetail = null }) { Text("‹ 返回") }
            Text(if (dynamicDetail != null) "动态详情" else section.title, style = MaterialTheme.typography.headlineSmall)
        }
        val detail = dynamicDetail
        if (detail != null) CommunityDynamicDetail(detail, community, navigation)
        else key(section, userId, articleId) {
            when (section) {
                CommunitySection.DYNAMIC -> CommunityLoginGate(repository, onLogin) { mid -> CommunityDynamicFeed(mid, community, navigation) }
                CommunitySection.SEARCH -> CommunitySearch(query, community, navigation, runtime, defaultSearchHintEnabled)
                CommunitySection.USER -> CommunityUserSpace(userId, repository, social, community, navigation)
                CommunitySection.MESSAGES -> CommunityLoginGate(repository, onLogin) { mid -> CommunityMessages(mid, community, navigation) }
                CommunitySection.ARTICLE -> CommunityArticle(articleId, community, navigation)
                CommunitySection.NOTES -> CommunityVideoNotes(noteVideo, repository, community, navigation)
            }
        }
    }
    }
}

internal fun navigateCommunityUrl(raw: String, navigation: CommunityNavigation) {
    val url = imageUrl(raw.trim())
    val uri = runCatching { URI(url) }.getOrNull() ?: return
    if (uri.scheme !in setOf("https", "http")) return
    val host = uri.host?.lowercase().orEmpty()
    if (host == "bilibili.com" || host.endsWith(".bilibili.com")) {
        if (uri.path.orEmpty().contains("topic", ignoreCase = true)) {
            com.android.purebilibili.feature.dynamic.components.resolveDynamicRichTextTopicId(
                com.android.purebilibili.data.model.response.RichTextNode(jump_url = url)
            )?.let { navigation.onTopic(it); return }
        }
        Regex("BV[0-9A-Za-z]{10}").find(url)?.value?.let {
            navigation.onVideo(VideoCard(it, "", "", "", 0, 0)); return
        }
        Regex("/read/cv(\\d+)").find(uri.path.orEmpty())?.groupValues?.get(1)?.toLongOrNull()?.let { navigation.onArticle(it); return }
        Regex("/bangumi/play/ss(\\d+)").find(uri.path.orEmpty())?.groupValues?.get(1)?.toLongOrNull()?.let { navigation.onBangumi(it); return }
        if (host == "space.bilibili.com") uri.path.trim('/').substringBefore('/').toLongOrNull()?.let { navigation.onUser(it); return }
        if (host == "live.bilibili.com") uri.path.trim('/').substringBefore('/').toLongOrNull()?.let { navigation.onLive(it); return }
        if (host == "t.bilibili.com") uri.path.trim('/').toLongOrNull()?.takeIf { it > 0 }?.let { navigation.onDynamic(it.toString()); return }
        Regex("/opus/(\\d+)").find(uri.path.orEmpty())?.groupValues?.get(1)?.let { navigation.onDynamic(it); return }
    }
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
        runCatching { Desktop.getDesktop().browse(uri) }
}
