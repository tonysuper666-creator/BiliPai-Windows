package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bilipai.desktop.data.*

@Composable
fun CommunityCollectionScreen(mid: Long, collectionId: Long, collectionType: String,
    community: DesktopCommunityRepository, onVideo: (VideoCard) -> Unit, onUser: (Long) -> Unit, onLogin: () -> Unit) {
    var title by remember(mid, collectionId, collectionType) { mutableStateOf("合集 / 系列") }
    Column(Modifier.fillMaxSize()) {
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(20.dp))
        CommunityFeed<VideoCard, Int>(Triple(mid, collectionId, collectionType), 1, load = { page ->
            val result = community.collectionVideos(mid, collectionId, collectionType, page)
            if (result.title.isNotBlank()) title = result.title
            CommunityBatch(result.videos, result.nextPage)
        }, identity = { it.bvid }, onLogin = onLogin) { CommunityVideoRow(it, onVideo, onUser) }
    }
}
