package com.bilipai.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.android.purebilibili.core.util.IdUtils
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.list.*
import com.bilipai.desktop.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

enum class PersonalSection(val title: String) {
    FAVORITES("云端收藏夹"), HISTORY("云端历史"), WATCH_LATER("稍后再看"), FOLLOWINGS("我的关注"), LIKED("赞过的视频")
}

@Composable
fun PersonalContentScreen(section: PersonalSection, repository: DesktopRepository, social: DesktopSocialRepository,
    community: DesktopCommunityRepository, onVideo: (VideoCard) -> Unit, onUser: (Long) -> Unit, onLogin: () -> Unit,
    onResource: (PersonalResource) -> Unit = {}, onCollection: (mid: Long, collectionId: Long, collectionType: String) -> Unit = { _, _, _ -> }) {
    CommunityLoginGate(repository, onLogin) { mid ->
        val feedMemory = remember(mid) { CommunityFeedMemory() }
        CompositionLocalProvider(LocalCommunityFeedMemory provides feedMemory, LocalCommunityFeedNamespace provides section) {
        Column(Modifier.fillMaxSize()) {
            Text(section.title, modifier = Modifier.padding(20.dp, 14.dp), style = MaterialTheme.typography.headlineSmall)
            key(section, mid) {
                when (section) {
                    PersonalSection.FAVORITES -> PersonalFavorites(repository, social, community, mid, onVideo, onUser, onLogin, onResource, onCollection)
                    PersonalSection.HISTORY -> PersonalHistory(community, mid, onVideo, onUser, onLogin, onResource, onCollection)
                    PersonalSection.WATCH_LATER -> PersonalWatchLater(community, social, mid, onVideo, onUser, onLogin, onResource, onCollection)
                    PersonalSection.FOLLOWINGS -> PersonalFollowings(community, social, mid, onUser, onLogin)
                    PersonalSection.LIKED -> CommunityFeed<VideoCard, Int>(mid, 1, load = { page -> community.likedVideos(page).let {
                        CommunityBatch(it.items, if (it.hasMore) page + 1 else null) } }, identity = { it.bvid }, onLogin = onLogin) { CommunityVideoRow(it, onVideo, onUser) }
                }
            }
        }
        }
    }
}

@Composable
private fun PersonalFavorites(repository: DesktopRepository, social: DesktopSocialRepository, community: DesktopCommunityRepository, mid: Long,
    onVideo: (VideoCard) -> Unit, onUser: (Long) -> Unit, onLogin: () -> Unit, onResource: (PersonalResource) -> Unit,
    onCollection: (Long, Long, String) -> Unit) {
    var folder by remember { mutableStateOf<CloudFavoriteFolder?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<CloudFavoriteFolder?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (folder != null) TextButton(onClick = { folder = null }) { Text("‹ 所有收藏夹") }
            Text(folder?.title ?: "我的收藏夹", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { creating = true }) { Text("新建收藏夹") }
            folder?.let { selected -> TextButton(onClick = { deleting = selected }) { Text("删除收藏夹") } }
        }
        val selected = folder
        if (selected == null) CommunityFeed<CloudFavoriteFolder, Unit>(Pair(mid, revision), Unit,
            load = { CommunityBatch(repository.cloudFavoriteFolders(), null) }, identity = { it.id }, onLogin = onLogin) { item ->
            Card(Modifier.fillMaxWidth().clickable { folder = item }) {
                Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    AsyncImage(model = imageUrl(item.cover), contentDescription = item.title, modifier = Modifier.size(100.dp, 70.dp),
                        contentScale = ContentScale.Crop)
                    Column {
                        Text(item.title, style = MaterialTheme.typography.titleMedium)
                        Text("${item.mediaCount} 个收藏", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        } else key(selected.id) {
            CommunityFeed<PersonalResource, Int>(Triple(mid, selected.id, revision), 1,
                load = { page -> community.personalFavorites(selected.id, page).let { CommunityBatch(it.items, it.nextCursor) } },
                identity = ::personalResourceKey, onLogin = onLogin) { item ->
                PersonalResourceCard(item, onVideo, onUser, onResource, onCollection) {
                    val resource = (item as PersonalResource.Favorite).item
                    if (resource.id > 0 && resource.type > 0) CommunityAction("移出此收藏夹", onLogin, action = { social.removeFavoriteResource(selected.id, resource) },
                        onSuccess = { revision++ })
                }
            }
        }
    }
    if (creating) PersonalFolderDialog(social, onLogin, onDismiss = { creating = false }, onComplete = { creating = false; revision++ })
    deleting?.let { selected ->
        PersonalDeleteFolderDialog(selected, social, onLogin, onDismiss = { deleting = null }, onComplete = {
            deleting = null; folder = null; revision++
        })
    }
}

@Composable
private fun PersonalWatchLater(community: DesktopCommunityRepository, social: DesktopSocialRepository, mid: Long,
    onVideo: (VideoCard) -> Unit, onUser: (Long) -> Unit, onLogin: () -> Unit, onResource: (PersonalResource) -> Unit,
    onCollection: (Long, Long, String) -> Unit) {
    var revision by remember { mutableIntStateOf(0) }
    CommunityFeed<PersonalResource, Int>(Pair(mid, revision), 1, load = { page -> community.personalWatchLater(page).let {
        CommunityBatch(it.items, it.nextCursor)
    } }, identity = ::personalResourceKey, onLogin = onLogin) { item ->
        PersonalResourceCard(item, onVideo, onUser, onResource, onCollection) {
            val aid = (item as PersonalResource.WatchLater).item.aid
            if (aid > 0) CommunityAction("从稍后再看移除", onLogin, action = { social.setWatchLater(aid, false) }, onSuccess = { revision++ })
        }
    }
}

internal fun personalResourceKey(resource: PersonalResource): String = when (resource) {
    is PersonalResource.History -> "history:${resolveHistoryRenderKey(resource.item)}:${resource.item.videoItem.view_at}"
    is PersonalResource.Favorite -> "favorite:${resource.item.type}:${resource.item.id}"
    is PersonalResource.WatchLater -> "later:${resource.item.aid}"
}

@Composable
private fun PersonalHistory(community: DesktopCommunityRepository, mid: Long, onVideo: (VideoCard) -> Unit,
    onUser: (Long) -> Unit, onLogin: () -> Unit, onResource: (PersonalResource) -> Unit, onCollection: (Long, Long, String) -> Unit) {
    var revision by remember { mutableIntStateOf(0) }
    var deleting by remember { mutableStateOf<HistoryItem?>(null) }
    CommunityFeed<PersonalResource, CloudHistoryCursor?>(Pair(mid, revision), null,
        load = { cursor -> community.personalHistory(cursor).let { CommunityBatch(it.items, it.nextCursor) } },
        identity = ::personalResourceKey, onLogin = onLogin) { resource ->
        PersonalResourceCard(resource, onVideo, onUser, onResource, onCollection) {
            val history = (resource as PersonalResource.History).item
            if (resolveHistoryDeleteKid(history) != null) TextButton(onClick = { deleting = history }) { Text("删除这条云端历史") }
        }
    }
    deleting?.let { item -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除这条观看记录？") },
        text = { Text(item.videoItem.title) }, confirmButton = {
            CommunityAction("确认删除", onLogin, action = { community.deleteHistoryResource(item) }, onSuccess = { deleting = null; revision++ })
        }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}

@Composable
private fun PersonalResourceCard(resource: PersonalResource, onVideo: (VideoCard) -> Unit, onUser: (Long) -> Unit,
    onResource: (PersonalResource) -> Unit, onCollection: (Long, Long, String) -> Unit, footer: @Composable () -> Unit = {}) {
    val card: VideoCard
    val ordinary: Boolean
    when (resource) {
        is PersonalResource.History -> {
            val history = resource.item
            val item = resolveHistoryCardPresentation(history)?.videoItem ?: history.videoItem
            card = VideoCard(item.bvid, item.title, personalImageUrl(item.pic), item.owner.name, item.stat.view.toLong(), item.duration,
                progressSeconds = (resolveHistoryResumePositionMs(history) / 1000).toInt(), preferredCid = resolveHistoryPlaybackCid(item.cid, history),
                pageIndex = (history.page - 1).coerceAtLeast(0), viewedAt = item.view_at, authorMid = item.owner.mid)
            ordinary = resolveHistoryNavigationKind(history) == HistoryNavigationKind.VIDEO && item.bvid.isNotBlank()
        }
        is PersonalResource.Favorite -> {
            val item = resource.item.toVideoItem()
            card = VideoCard(item.bvid, item.title, personalImageUrl(item.pic), item.owner.name, item.stat.view.toLong(), item.duration,
                preferredCid = item.cid, authorMid = item.owner.mid)
            ordinary = !item.isCollectionResource && item.bvid.isNotBlank()
        }
        is PersonalResource.WatchLater -> {
            val item = resource.item
            card = VideoCard(item.bvid.orEmpty(), item.title.orEmpty(), personalImageUrl(item.pic.orEmpty()), item.owner?.name.orEmpty(),
                item.stat?.view?.toLong() ?: 0, item.duration ?: 0, progressSeconds = item.progress, preferredCid = item.cid ?: 0, authorMid = item.owner?.mid ?: 0)
            ordinary = item.isPgc != true && item.isPugv != true && !item.bvid.isNullOrBlank()
        }
    }
    CommunityVideoRow(card, onVideo = {
        val favorite = (resource as? PersonalResource.Favorite)?.item?.toVideoItem()
        if (favorite?.isCollectionResource == true && favorite.collectionId > 0) onCollection(favorite.collectionMid, favorite.collectionId, "favorite_season")
        else if (ordinary) onVideo(card) else onResource(resource)
    }, onUser = onUser, actions = footer)
}

@Composable
private fun PersonalFollowings(community: DesktopCommunityRepository, social: DesktopSocialRepository, mid: Long,
    onUser: (Long) -> Unit, onLogin: () -> Unit) {
    var revision by remember { mutableIntStateOf(0) }
    CommunityFeed(Pair(mid, revision), 1, load = { page -> community.followings(mid, page).let { CommunityBatch(it.items, it.nextPage) } },
        identity = { it.mid }, onLogin = onLogin) { user ->
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(model = imageUrl(user.face), contentDescription = user.uname, modifier = Modifier.size(60.dp).clickable { onUser(user.mid) })
                Column(Modifier.weight(1f).clickable { onUser(user.mid) }) {
                    Text(user.uname, style = MaterialTheme.typography.titleMedium)
                    Text(user.sign, style = MaterialTheme.typography.bodySmall)
                }
                CommunityAction("取消关注", onLogin, action = { social.setFollowing(user.mid, false) }, onSuccess = { revision++ })
            }
        }
    }
}

@Composable
private fun PersonalFolderDialog(social: DesktopSocialRepository, onLogin: () -> Unit, onDismiss: () -> Unit, onComplete: () -> Unit) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var privateFolder by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("新建收藏夹") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text("收藏夹名称") }, singleLine = true)
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(privateFolder, { privateFolder = it }); Text("设为私密") }
            error?.let { CommunityFailure(it, onLogin) }
        }
    }, confirmButton = {
        Button(enabled = !busy && title.isNotBlank(), onClick = {
            busy = true; error = null
            scope.launch { try { social.createFavoriteFolder(title, privateFolder); onComplete() }
                catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure }
                finally { busy = false } }
        }) { Text(if (busy) "创建中…" else "创建") }
    }, dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } })
}

@Composable
private fun PersonalDeleteFolderDialog(folder: CloudFavoriteFolder, social: DesktopSocialRepository, onLogin: () -> Unit,
    onDismiss: () -> Unit, onComplete: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("删除收藏夹") },
        text = { Column { Text("删除“${folder.title}”及其收藏记录？"); error?.let { CommunityFailure(it, onLogin) } } },
        confirmButton = { Button(enabled = !busy, onClick = {
            busy = true; error = null
            scope.launch { try { social.deleteFavoriteFolder(folder.id); onComplete() }
                catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure }
                finally { busy = false } }
        }) { Text(if (busy) "删除中…" else "删除") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } })
}
