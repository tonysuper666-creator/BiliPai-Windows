package com.bilipai.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.android.purebilibili.core.util.FormatUtils
import com.android.purebilibili.core.plugin.skin.*
import com.android.purebilibili.feature.dynamic.components.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.space.*
import com.bilipai.desktop.data.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

enum class DesktopSpaceHomeSection { VIDEOS, FAVORITES, COINS, LIKED, ARTICLES, AUDIO, FOLLOW_BANGUMI }

/** Root supplies its existing authoritative follow/edit action; presentation does not send account writes. */
@Composable
fun DesktopSpaceHeader(overview: DesktopSpaceOverview, onFollowers: (Long) -> Unit, onFollowings: (Long) -> Unit,
    onUser: (Long) -> Unit, onLive: (Long) -> Unit, onExternalUrl: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    onAvatarPreview: ((Rect?) -> Unit)? = null, onTopPhotoPreview: ((Rect?, String?) -> Unit)? = null,
    isOwner: Boolean = false, action: @Composable () -> Unit = {}) {
    val user = overview.user
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val topItems = user.topImages
    val banner = normalizeSpaceTopPhotoUrl(if (dark && user.nightTopPhoto.isNotBlank()) user.nightTopPhoto else user.topPhoto)
    val context = LocalPlatformContext.current
    val window = LocalWindowInfo.current.containerSize
    val uiSkinState = LocalUiSkinState.current
    val activeProfileSkin = uiSkinState.activeSkin?.takeIf {
        uiSkinState.enabled && isOwner && UiSkinSurface.PROFILE in it.manifest.surfaces
    }
    val skinSpaceBackgroundPaths = activeProfileSkin?.manifest?.assets?.spaceBackgrounds.orEmpty().mapNotNull { background ->
        val preferLandscape = window.width > window.height
        activeProfileSkin?.assetFilePath(if (preferLandscape) background.landscape ?: background.portrait
            else background.portrait ?: background.landscape)
    }
    var currentBannerUrl by remember(user.mid) { mutableStateOf<String?>(null) }
    val topPhotoRect = rememberImagePreviewSourceRect()
    val topPhotoHidden = isImagePreviewSourceHidden(topPhotoRect.value, currentBannerUrl)
    val avatarRect = rememberImagePreviewSourceRect()
    val avatarHidden = isImagePreviewSourceHidden(avatarRect.value, user.face)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.fillMaxWidth().height(150.dp).imagePreviewSourceBounds(topPhotoRect)
            .alpha(if (topPhotoHidden) 0f else 1f)
            .clickable(interactionSource = null, indication = null,
                enabled = onTopPhotoPreview != null && !topPhotoHidden && skinSpaceBackgroundPaths.isEmpty() &&
                    (shouldEnableSpaceTopPhotoPreview(banner) || topItems.isNotEmpty())) {
                onTopPhotoPreview?.invoke(topPhotoRect.value, currentBannerUrl)
            }) {
            DesktopOriginalSpaceHeaderBanner(topImages = topItems, fallbackTopPhotoUrl = banner,
                onCurrentBannerUrlChange = { currentBannerUrl = it }, skinBackgroundPaths = skinSpaceBackgroundPaths,
                isDarkTheme = dark, modifier = Modifier.fillMaxSize())
        }
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(80.dp).imagePreviewSourceBounds(avatarRect).alpha(if (avatarHidden) 0f else 1f)
                .clickable(interactionSource = null, indication = null, enabled = user.face.isNotBlank() && !avatarHidden) {
                    onAvatarPreview?.invoke(avatarRect.value) ?: onUser(user.mid)
                }) {
                AsyncImage(model = ImageRequest.Builder(context)
                    .data(FormatUtils.buildSizedImageUrl(user.face, width = 320, height = 320))
                    .memoryCacheKey(resolveImagePreviewPlaceholderCacheKey(user.face) ?: user.face)
                    .crossfade(false).build(), contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(CircleShape)
                        .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant))
            }
            Column(Modifier.weight(1f)) { SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(user.name, style = MaterialTheme.typography.titleLarge)
                Text(listOf("LV${user.level}", user.vip.label.text.ifBlank { if (user.vip.type == 2) "年度大会员" else "大会员" }.takeIf { user.vip.status == 1 }.orEmpty())
                    .filter(String::isNotBlank).joinToString(" · "), color = MaterialTheme.colorScheme.primary)
                user.official.spliceTitle.ifBlank { user.official.title }.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            } } }
            action()
        }
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            resolveSpaceHeaderMetricItems(overview.relation, overview.statistics).forEach { metric ->
                when (metric.label) {
                    "粉丝" -> TextButton(onClick = { onFollowers(user.mid) }) { Text("${metric.value} ${metric.label}") }
                    "关注" -> TextButton(onClick = { onFollowings(user.mid) }) { Text("${metric.value} ${metric.label}") }
                    else -> Text("${metric.value} ${metric.label}", Modifier.align(Alignment.CenterVertically))
                }
            }
        }
        SelectionContainer { Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (user.sign.isNotBlank()) Text(user.sign.trim())
            Text("UID: ${user.mid}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(remember(user.mid) { ScrollState(0) }), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                resolveSpaceDisplayTags(user.spaceTags, user.ipLocation).forEach { tag -> Text(tag.title,
                    modifier = Modifier.clickable(enabled = tag.uri.isNotBlank()) { onExternalUrl(tag.uri, tag.title) }, style = MaterialTheme.typography.bodySmall) }
            }
        } }
        user.followingsFollowed?.takeIf { it.items.isNotEmpty() }?.let { common ->
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("你关注的人也关注了 TA", style = MaterialTheme.typography.labelMedium)
                Row(Modifier.horizontalScroll(remember(user.mid) { ScrollState(0) }), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    common.items.forEach { person -> Column(Modifier.clickable(enabled = person.mid > 0) { onUser(person.mid) }, horizontalAlignment = Alignment.CenterHorizontally) {
                        AsyncImage(model = imageUrl(person.face), contentDescription = person.name, modifier = Modifier.size(38.dp))
                        Text(person.name, style = MaterialTheme.typography.labelSmall)
                    } }
                    if (common.jumpUrl.isNotBlank()) TextButton(onClick = { onExternalUrl(common.jumpUrl, "共同关注") }) { Text("查看全部") }
                }
            }
        }
        user.liveRoom?.takeIf { it.liveStatus == 1 && it.url.isNotBlank() }?.let { room ->
            val roomId = room.roomId.takeIf { it > 0 } ?: room.url.substringAfterLast('/').substringBefore('?').toLongOrNull() ?: 0
            if (roomId > 0) TextButton(onClick = { onLive(roomId) }, modifier = Modifier.padding(horizontal = 20.dp)) { Text("直播中 · ${room.title.ifBlank { user.name }}") }
        }
        if (user.silence == 1) Text("该账号已被封禁", Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.error)
    }

}

@Composable
fun DesktopSpaceHomeScreen(mid: Long, repository: DesktopRepository, backend: DesktopSpaceContributionsRepository,
    onVideo: (VideoCard) -> Unit, onUser: (Long) -> Unit, onArticle: (Long) -> Unit, onDynamic: (String) -> Unit,
    onAudio: (Long) -> Unit, onBangumi: (Long) -> Unit, onFolder: (FavFolder) -> Unit,
    onSection: (DesktopSpaceHomeSection) -> Unit, onExternalUrl: (String, String) -> Unit, onLogin: () -> Unit) {
    require(mid > 0)
    val account by repository.account.collectAsState()
    val epoch by repository.sessionEpochFlow.collectAsState()
    val owner = listOf("up-space-home", epoch, account?.mid, mid)
    CompositionLocalProvider(LocalCommunityFeedNamespace provides owner) {
        CommunityFeed<DesktopSpaceOverview, Unit>("overview", Unit, load = {
            val overview = coroutineScope {
                val supplemental = async { backend.home(mid) }
                backend.metadata(mid).toOverview(supplemental.await())
                    ?: throw BiliApiException(-1, "空间聚合资料不完整，请刷新资料")
            }
            CommunityBatch(listOf(overview), null)
        }, identity = { it.user.mid }, onLogin = onLogin) { overview ->
            DesktopSpaceHomeContent(overview, onVideo, onUser, onArticle, onDynamic, onAudio, onBangumi, onFolder, onSection, onExternalUrl)
        }
    }
}

/** Data-only counterpart is also useful when Root already loaded aggregate metadata for its header/tabs. */
@Composable
fun DesktopSpaceHomeContent(overview: DesktopSpaceOverview, onVideo: (VideoCard) -> Unit, onUser: (Long) -> Unit,
    onArticle: (Long) -> Unit, onDynamic: (String) -> Unit, onAudio: (Long) -> Unit, onBangumi: (Long) -> Unit,
    onFolder: (FavFolder) -> Unit, onSection: (DesktopSpaceHomeSection) -> Unit, onExternalUrl: (String, String) -> Unit) {
    val seed = overview.seed; val mid = seed.userInfo.mid
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        overview.supplemental.topVideo?.let { top ->
            Text("置顶视频${top.reason.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}", style = MaterialTheme.typography.titleMedium)
            val bvid = top.bvid.ifBlank { top.aid.takeIf { it > 0 }?.let(com.android.purebilibili.core.util.IdUtils::av2bv).orEmpty() }
            CommunityVideoRow(VideoCard(bvid, top.title, top.pic, seed.userInfo.name, top.stat.view, top.duration, publishedAt = top.pubdate, authorMid = mid), onVideo, onUser)
        }
        overview.supplemental.notice.takeIf(String::isNotBlank)?.let { notice -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("公告"); Text(notice) } } }
        if (seed.videos.isNotEmpty() || seed.totalVideos > 0) {
            SpaceHomeSectionHeader("视频", seed.totalVideos.takeIf { it > 0 } ?: seed.videos.size) { onSection(DesktopSpaceHomeSection.VIDEOS) }
            seed.videos.take(4).forEach { CommunityVideoRow(desktopSpaceVideoCard(it, mid), onVideo, onUser) }
        }
        if (seed.homeFavoriteFolders.isNotEmpty() || seed.homeFavoriteFolderCount > 0) {
            SpaceHomeSectionHeader("收藏", seed.homeFavoriteFolderCount.takeIf { it > 0 } ?: seed.homeFavoriteFolders.size) { onSection(DesktopSpaceHomeSection.FAVORITES) }
            seed.homeFavoriteFolders.take(1).forEach { folder -> CommunityLinkCard(folder.title, "", "${folder.media_count} 项") { onFolder(folder) } }
        }
        @Composable fun archives(title: String, items: List<SpaceAggregateArchiveItem>, total: Int, take: Int, section: DesktopSpaceHomeSection?) {
            if (items.isEmpty()) return
            SpaceHomeSectionHeader(title, total.takeIf { it > 0 } ?: items.size, section?.let { { onSection(it) } })
            items.take(take).forEach { item -> CommunityLinkCard(item.title, item.cover,
                listOf(item.author, item.subtitle, item.publishTimeText, item.styles, item.label, item.badges.joinToString(" · ") { it.text }).filter(String::isNotBlank).joinToString(" · ")) {
                dispatchDesktopSpaceAggregate(item, mid, onVideo, onAudio, onBangumi, onExternalUrl)
            } }
        }
        archives("最近投币的视频", seed.homeCoinVideos, seed.homeCoinVideoCount, 2, DesktopSpaceHomeSection.COINS)
        archives("最近点赞的视频", seed.homeLikeVideos, seed.homeLikeVideoCount, 2, DesktopSpaceHomeSection.LIKED)
        if (seed.articles.isNotEmpty() || seed.totalArticles > 0) {
            SpaceHomeSectionHeader("图文", seed.totalArticles.takeIf { it > 0 } ?: seed.articles.size) { onSection(DesktopSpaceHomeSection.ARTICLES) }
            seed.articles.take(1).forEach { article -> CommunityLinkCard(article.title, article.displayImageUrls().firstOrNull().orEmpty(),
                listOf(article.summary, buildSpaceArticleStatsText(article)).filter(String::isNotBlank).joinToString("\n")) {
                when (val target = resolveSpaceArticleClickAction(article)) {
                    is SpaceDynamicClickAction.OpenArticle -> onArticle(target.articleId)
                    is SpaceDynamicClickAction.OpenDynamicDetail -> onDynamic(target.dynamicId)
                    is SpaceDynamicClickAction.OpenUser -> onUser(target.mid)
                    is SpaceDynamicClickAction.OpenVideo -> onVideo(VideoCard(target.bvid, article.title, article.displayImageUrls().firstOrNull().orEmpty(), "", 0, 0))
                    SpaceDynamicClickAction.None -> Unit
                }
            } }
        }
        if (seed.audios.isNotEmpty() || seed.totalAudios > 0) {
            SpaceHomeSectionHeader("音频", seed.totalAudios.takeIf { it > 0 } ?: seed.audios.size) { onSection(DesktopSpaceHomeSection.AUDIO) }
            seed.audios.take(1).forEach { audio -> CommunityLinkCard(audio.title, audio.cover,
                listOf(audio.author.ifBlank { audio.uname }, audio.intro).filter(String::isNotBlank).joinToString(" · ")) { onAudio(audio.id) } }
        }
        archives("追番", seed.homeBangumiItems, seed.homeBangumiCount, 3, DesktopSpaceHomeSection.FOLLOW_BANGUMI)
        archives("漫画", seed.homeComicItems, seed.homeComicCount, 1, null)
        if (seed.videos.isEmpty() && seed.homeFavoriteFolders.isEmpty() && seed.homeCoinVideos.isEmpty() && seed.homeLikeVideos.isEmpty()
            && seed.articles.isEmpty() && seed.audios.isEmpty() && seed.homeBangumiItems.isEmpty() && seed.homeComicItems.isEmpty()) {
            Text("主页空空的", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun SpaceHomeSectionHeader(title: String, count: Int, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("$title ($count)", style = MaterialTheme.typography.titleMedium)
        if (onClick != null) TextButton(onClick = onClick) { Text("查看全部") }
    }
}
