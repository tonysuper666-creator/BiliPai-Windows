package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.DesktopPluginRuntime
import com.android.purebilibili.navigation.MessageLinkNavigationAction
import com.android.purebilibili.navigation.resolveMessageLinkNavigationAction
import java.awt.Desktop
import java.net.URI

enum class CommunitySection(val title: String) {
    DYNAMIC("动态"), SEARCH("搜索"), USER("UP 主空间"), MESSAGES("消息"), ARTICLE("专栏"), NOTES("视频笔记")
}

internal class CommunityNavigation(val onVideo: (VideoCard) -> Unit, val onUser: (Long) -> Unit,
    val onArticle: (Long) -> Unit, val onLogin: () -> Unit, val onLive: (Long) -> Unit,
    val onBangumi: (Long) -> Unit, val onDynamic: (String) -> Unit, val onTopic: (Long) -> Unit = {},
    val onTopicKeyword: (String) -> Unit = {}, val onDynamicBack: () -> Unit = {},
    val onDynamicRoute: (DesktopDynamicDetailRoute) -> Unit = { onDynamic(it.dynamicId) },
    val onMessageLink: (String) -> Unit)

@Composable
fun CommunityContentScreen(section: CommunitySection, repository: DesktopRepository, social: DesktopSocialRepository,
    community: DesktopCommunityRepository, query: String = "", userId: Long = 0, articleId: Long = 0,
    noteVideo: VideoDetails? = null, onVideo: (VideoCard) -> Unit, onUser: (Long) -> Unit, onArticle: (Long) -> Unit,
    onLogin: () -> Unit, onLive: (Long) -> Unit = {}, onBangumi: (Long) -> Unit = {}, runtime: DesktopPluginRuntime? = null,
    initialDynamicId: String? = null, onTopic: (Long) -> Unit = {}, onTopicKeyword: (String) -> Unit = {}, defaultSearchHintEnabled: Boolean = true,
    initialCommentRootRpid: Long = 0L, initialCommentTargetRpid: Long = 0L, active: Boolean = true) {
    val account by repository.account.collectAsState()
    val inherited = LocalDesktopBrowseMemory.current
    val fallback = remember(account?.mid) { DesktopBrowseMemory() }
    val browseMemory = inherited ?: fallback
    val initialRoute=initialDynamicId?.let{DesktopDynamicDetailRoute(it,initialCommentRootRpid,initialCommentTargetRpid)}
    var dynamicDetail by remember(browseMemory, section, userId, query, initialRoute) {
        browseMemory.screen(listOf("community-dynamic-detail", section, userId, query, initialRoute)) { mutableStateOf(initialRoute) }
    }
    val feedMemory = browseMemory.feeds
    val messageLink = LocalDesktopOriginalMessageLinkNavigation.current
    val navigation = CommunityNavigation(onVideo, onUser, onArticle, onLogin, onLive, onBangumi,
        { dynamicDetail = DesktopDynamicDetailRoute(it) }, onTopic, onTopicKeyword,
        onDynamicBack={dynamicDetail=null},onDynamicRoute={dynamicDetail=it},onMessageLink=messageLink)
    CompositionLocalProvider(LocalDesktopBrowseMemory provides browseMemory, LocalCommunityFeedMemory provides feedMemory,
        LocalCommunityFeedNamespace provides listOf(section, userId, articleId, noteVideo?.aid)) {
    Column(Modifier.fillMaxSize()) {
        if(dynamicDetail==null)Row(Modifier.fillMaxWidth().padding(20.dp, 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(section.title, style = MaterialTheme.typography.headlineSmall)
        }
        val detail = dynamicDetail
        if (detail != null) CommunityDynamicDetail(detail, community, navigation)
        else key(section, userId, articleId) {
            when (section) {
                CommunitySection.DYNAMIC -> CommunityLoginGate(repository, onLogin) { mid -> CommunityDynamicFeed(mid, community, navigation, active) }
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
    // Topic rich text uses its already installed original topic policy. Every
    // other message target is parsed by the sole original AppNavigation policy.
    val uri = runCatching { URI(url) }.getOrNull()
    val host = uri?.host?.lowercase().orEmpty()
    if (uri?.scheme in setOf("https", "http") && (host == "bilibili.com" || host.endsWith(".bilibili.com")) &&
            uri?.path.orEmpty().contains("topic", ignoreCase=true)) {
        com.android.purebilibili.feature.dynamic.components.resolveDynamicRichTextTopicId(
            com.android.purebilibili.data.model.response.RichTextNode(jump_url=url)
        )?.let { navigation.onTopic(it); return }
    }
    navigation.onMessageLink(url)
}
