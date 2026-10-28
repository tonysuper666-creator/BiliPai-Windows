package com.android.purebilibili.feature.share

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.AppModalBottomSheet
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.core.ui.common.copyPlainTextToClipboard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContentShareSheet(source: ContentSharePayload, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var target by remember(source.key) { mutableStateOf("") }
    var precise by remember(source.key) { mutableStateOf(false) }
    val payload = source.copy(url = if (precise) buildPositionShareUrl(source.url, source.positionMs) else source.url)
    when (target) {
        "message" -> { ContentMessageShareSheet(payload, onDismiss); return }
        "dynamic" -> { ContentDynamicShareSheet(payload, onDismiss); return }
    }
    fun launchExternal(intent: Intent) {
        try { context.startActivity(intent); onDismiss() } catch (_: Exception) { Toast.makeText(context, "无法打开此分享入口，请重试", Toast.LENGTH_SHORT).show() }
    }
    AppModalBottomSheet(modifier = modifier, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.9f).dp).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AppText("分享「${source.title}」", maxLines = 2)
            if (source.positionMs > 0) Row {
                AppCheckbox(checked = precise, onCheckedChange = { precise = it })
                AppText("从 ${source.positionMs / 60000}:${(source.positionMs / 1000 % 60).toString().padStart(2, '0')} 开始播放" +
                    if (source.dynamicType != null) "（动态分享除外）" else "", modifier = Modifier.weight(1f))
            }
            AppButton(onClick = { copyPlainTextToClipboard(context, payload.url, "内容链接"); onDismiss() }, modifier = Modifier.fillMaxWidth()) { AppText("复制链接") }
            AppButton(onClick = { target = "message" }, modifier = Modifier.fillMaxWidth()) { AppText("分享至消息") }
            if (source.dynamicType != null) AppButton(onClick = { target = "dynamic" }, modifier = Modifier.fillMaxWidth()) { AppText("分享到动态") }
            AppButton(onClick = { launchExternal(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, payload.title); putExtra(Intent.EXTRA_TEXT, "${payload.title}\n${payload.url}") }, "分享")) }, modifier = Modifier.fillMaxWidth()) { AppText("分享至其他应用") }
            AppTextButton(onClick = { launchExternal(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(payload.url))) }, modifier = Modifier.fillMaxWidth()) { AppText("其他应用打开") }
            AppTextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { AppText("取消") }
        }
    }
}

internal fun bangumiDynamicType(seasonType: Int): Int? = when (seasonType) {
    1 -> 4097
    2 -> 4098
    3 -> 4101
    4 -> 4100
    5, 7 -> 4099
    else -> null
}

internal fun buildBangumiContentSharePayload(epId: Long, title: String, cover: String, seasonType: Int, positionMs: Long, aid: Long = 0) = ContentSharePayload(
    key = "episode:$epId", title = title, url = "https://www.bilibili.com/bangumi/play/ep$epId", cover = cover,
    source = 16, resourceId = epId, dynamicType = bangumiDynamicType(seasonType), positionMs = positionMs,
    replyOid = aid,
)
