package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.core.ui.components.AppTextButton
import com.android.purebilibili.core.util.FormatUtils
import com.android.purebilibili.data.model.response.BangumiDetail

/** Upstream BangumiIntroductionPanel "详情" page: it takes the area below the player while the
 * player keeps running above it, and 收起 returns to the tab/list state the caller kept alive.
 * The "点评" page (embedded BangumiReviewScreen) is not carried on this read point. */
@Composable internal fun DesktopBangumiIntroductionPanel(
    detail: BangumiDetail,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppText(
                text = "详情",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            AppTextButton(onClick = onDismiss) { AppText(text = "收起") }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppText(text = detail.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            detail.stat?.let { stat ->
                AppText(
                    text = "${FormatUtils.formatStat(stat.views)}播放 · ${FormatUtils.formatStat(stat.danmakus)}弹幕",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val meta = desktopBangumiIntroductionMeta(detail)
            if (meta.isNotBlank()) {
                AppText(text = meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val styles = detail.styles.orEmpty().filter { it.isNotBlank() }
            if (styles.isNotEmpty()) {
                AppText(text = styles.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            DesktopBangumiIntroductionSection("简介", detail.evaluate)
            DesktopBangumiIntroductionSection("演职人员", detail.actors)
            DesktopBangumiIntroductionSection("制作人员", detail.staff)
        }
    }
}

/** Same order as upstream: areas, publish time (shown form first), episode summary. */
internal fun desktopBangumiIntroductionMeta(detail: BangumiDetail): String = listOfNotNull(
    detail.areas.orEmpty().map { it.name }.filter { it.isNotBlank() }.joinToString(" / ").takeIf { it.isNotBlank() },
    detail.publish?.let { publish -> publish.pubTimeShow.ifBlank { publish.pubTime } }?.takeIf { it.isNotBlank() },
    detail.newEp?.desc?.takeIf { it.isNotBlank() },
).joinToString(" · ")

@Composable private fun DesktopBangumiIntroductionSection(title: String, body: String) {
    if (body.isBlank()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AppText(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        AppText(text = body, style = MaterialTheme.typography.bodyMedium)
    }
}
