package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bilipai.desktop.data.*

@Composable
fun CommunityCollectionScreen(mid: Long, collectionId: Long, collectionType: String,
    community: DesktopCommunityRepository, onVideo: (VideoCard) -> Unit, onUser: (Long) -> Unit, onLogin: () -> Unit,
    space: DesktopSpaceRepository? = null, onResource: ((PersonalResource) -> Unit)? = null, initialTitle: String = "") {
    var title by remember(mid, collectionId, collectionType, initialTitle) {
        mutableStateOf(initialTitle.ifBlank { if (collectionType == "favorite") "收藏夹" else "合集 / 系列" })
    }
    Column(Modifier.fillMaxSize()) {
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(20.dp))
        if (collectionType == "favorite") {
            val folderRepository = checkNotNull(space) { "Root public favorite folder repository is not mounted" }
            val openResource = checkNotNull(onResource) { "Root favorite resource navigation is not mounted" }
            CommunityFeed<PersonalResource, Int>(Triple(mid, collectionId, collectionType), 1, load = { page ->
                folderRepository.favoriteResources(collectionId, page).let { CommunityBatch(it.items, it.nextCursor) }
            }, identity = { (it as PersonalResource.Favorite).item.let { data -> "${data.type}:${data.id}" } }, onLogin = onLogin) { resource ->
                val item = (resource as PersonalResource.Favorite).item.toVideoItem(ownerFallbackMid = mid)
                val card = VideoCard(item.bvid, item.title, personalImageUrl(item.pic), item.owner.name,
                    item.stat.view.toLong(), item.duration, preferredCid = item.cid, authorMid = item.owner.mid)
                CommunityVideoRow(card, onVideo = { openResource(resource) }, onUser = onUser)
            }
        } else {
        CommunityFeed<VideoCard, Int>(Triple(mid, collectionId, collectionType), 1, load = { page ->
            val result = community.collectionVideos(mid, collectionId, collectionType, page)
            if (result.title.isNotBlank()) title = result.title
            CommunityBatch(result.videos, result.nextPage)
        }, identity = { it.bvid }, onLogin = onLogin) { CommunityVideoRow(it, onVideo, onUser) }
        }
    }
}
