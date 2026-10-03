package com.android.bilipai.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.android.bilipai.tv.ui.TvUiTokens
import com.android.purebilibili.core.util.FormatUtils
import com.android.purebilibili.data.model.response.VideoItem

/**
 * TV 视频卡片。数据与格式化复用共享层(VideoItem/FormatUtils),
 * 排版与焦点交互按 TV 字阶与遥控器语境独立实现,不直接搬移动端组件。
 */
@Composable
internal fun TvVideoCard(
    video: VideoItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TvAppCard(onClick = onClick, modifier = modifier) {
        Box {
            AsyncImage(
                model = video.pic.let { if (it.startsWith("//")) "https:$it" else it },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
            )
            if (video.duration > 0) {
                Text(
                    text = FormatUtils.formatDuration(video.duration),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(Color(0x99000000), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Text(
            text = video.title,
            style = MaterialTheme.typography.titleSmall,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = TvUiTokens.cardPadding, start = TvUiTokens.cardPadding, end = TvUiTokens.cardPadding),
        )
        val stats = buildList {
            if (video.stat.view > 0) add(FormatUtils.formatStat(video.stat.view.toLong()) + "播放")
            if (video.stat.danmaku > 0) add(FormatUtils.formatStat(video.stat.danmaku.toLong()) + "弹幕")
        }.joinToString(" · ")
        if (stats.isNotBlank()) {
            Text(
                text = stats,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp, start = TvUiTokens.cardPadding, end = TvUiTokens.cardPadding),
            )
        }
        Text(
            text = video.owner.name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(
                top = 4.dp,
                start = TvUiTokens.cardPadding,
                end = TvUiTokens.cardPadding,
                bottom = TvUiTokens.cardPadding,
            ),
        )
    }
}
