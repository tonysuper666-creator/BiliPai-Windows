package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bilipai.desktop.data.VideoCard

internal enum class DesktopWindowsVideoDetailsTab(val title: String) {
    INTRODUCTION("简介与分P"), COMMENTS("评论"), RELATED("相关推荐")
}

/** A real sibling of the native viewport, never a Compose overlay over its heavyweight Canvas. */
@Composable
internal fun DesktopWindowsVideoDetailsPanel(
    modifier: Modifier, selectedTab: DesktopWindowsVideoDetailsTab,
    onTabChange: (DesktopWindowsVideoDetailsTab) -> Unit, onClose: () -> Unit,
    current: () -> Boolean, related: List<VideoCard>, onVideo: (VideoCard) -> Unit,
    introduction: @Composable () -> Unit, comments: @Composable () -> Unit,
) {
    DesktopWindowsPlayerSurface(modifier) {
        Column(Modifier.fillMaxSize().semantics { contentDescription = "视频详情面板" }) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("视频详情", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = onClose, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "关闭详情")
                }
            }
            TabRow(selectedTab.ordinal) {
                DesktopWindowsVideoDetailsTab.entries.forEach { tab ->
                    Tab(selected = selectedTab == tab, onClick = { if (current()) onTabChange(tab) },
                        text = { Text(tab.title, style = MaterialTheme.typography.labelMedium, maxLines = 1) })
                }
            }
            // The original comment tab owns its LazyColumn/weight. Give it a
            // finite sibling viewport, never an unbounded outer list item.
            if (selectedTab == DesktopWindowsVideoDetailsTab.COMMENTS) {
                Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp)) { comments() }
            } else LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (selectedTab) {
                    DesktopWindowsVideoDetailsTab.INTRODUCTION -> item { introduction() }
                    DesktopWindowsVideoDetailsTab.COMMENTS -> Unit // Rendered in the bounded sibling above.
                    DesktopWindowsVideoDetailsTab.RELATED -> {
                        if (related.isEmpty()) item { Text("暂无相关推荐", style = MaterialTheme.typography.bodyMedium) }
                        items(related, key = { it.bvid }) { video ->
                            OutlinedButton(onClick = { if (current()) onVideo(video) }, modifier = Modifier.fillMaxWidth()) {
                                Text(video.title)
                            }
                        }
                    }
                }
            }
        }
    }
}
