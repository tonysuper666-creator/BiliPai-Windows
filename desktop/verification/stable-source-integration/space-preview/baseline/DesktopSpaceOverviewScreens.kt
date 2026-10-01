package com.bilipai.desktop.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
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
    modifier: Modifier = Modifier, action: @Composable () -> Unit = {}) {
    val user = overview.user
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val topItems = user.topImages
    val banner = topItems.firstOrNull()?.header ?: if (dark) user.nightTopPhoto.ifBlank { user.topPhoto } else user.topPhoto
    var preview by remember(user.mid) { mutableStateOf(false) }
    var previewIndex by remember(user.mid) { mutableIntStateOf(0) }
    val previews = topItems.ifEmpty { banner.takeIf(String::isNotBlank)?.let { listOf(SpaceTopImageItem(header = it, fullCover = it)) }.orEmpty() }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (banner.isNotBlank()) AsyncImage(model = imageUrl(banner), contentDescription = "空间头图",
            contentScale = ContentScale.Crop, alignment = resolveSpaceBannerAlignment(topItems.firstOrNull()?.dy ?: 0f),
            colorFilter = resolveSpaceBannerColorFilter(!dark), modifier = Modifier.fillMaxWidth().height(150.dp).clickable {
                previewIndex = 0; preview = true
            })
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(model = imageUrl(user.face), contentDescription = user.name, modifier = Modifier.size(72.dp).clickable { onUser(user.mid) })
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
    if (preview && previews.isNotEmpty()) Dialog(onDismissRequest = { preview = false }) {
        Surface(shape = MaterialTheme.shapes.large) { Column(Modifier.widthIn(max = 900.dp).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val item = previews[previewIndex.coerceIn(previews.indices)]
            AsyncImage(model = imageUrl(item.fullCover), contentDescription = item.title?.title ?: "空间头图",
                contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 650.dp))
            item.title?.let { title -> if (title.title.isNotBlank()) Text(title.title); if (title.subTitle.isNotBlank()) Text(title.subTitle) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { previewIndex-- }, enabled = previewIndex > 0) { Text("上一张") }
                Text("${previewIndex + 1} / ${previews.size}")
                TextButton(onClick = { previewIndex++ }, enabled = previewIndex < previews.lastIndex) { Text("下一张") }
                TextButton(onClick = { preview = false }) { Text("关闭") }
            }
        } }
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
