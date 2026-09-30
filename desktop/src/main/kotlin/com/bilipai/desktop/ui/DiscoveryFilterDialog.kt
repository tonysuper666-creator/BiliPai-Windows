package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.purebilibili.feature.plugin.DesktopFeedFilterEditor
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*

@Composable
internal fun DiscoveryFilterDialog(current: DesktopDiscoveryFilters, store: DesktopPluginStore,
    onSaved: (DesktopDiscoveryFilters) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope(); val original = current.config
    var enabled by remember { mutableStateOf(current.enabled) }
    var duration by remember { mutableStateOf(original.minDurationForRcmd.toString()) }
    var views by remember { mutableStateOf(original.minPlayForRcmd.toString()) }
    var ratio by remember { mutableStateOf(original.minLikeRatioForRecommend.toString()) }
    var titleWords by remember { mutableStateOf(original.banWordForRecommend) }
    var regionWords by remember { mutableStateOf(original.banWordForZone) }
    var blocked by remember { mutableStateOf(original.recommendBlockedMids.entries.joinToString("\n") { (uid, name) -> "${name.ifBlank { "UID" }} ($uid)" }) }
    var whitelist by remember { mutableStateOf(original.whitelistMids.entries.joinToString("\n") { (uid, name) -> "${name.ifBlank { "UID" }} ($uid)" }) }
    var followed by remember { mutableStateOf(original.exemptFilterForFollowed) }
    var popular by remember { mutableStateOf(original.applyToHotVideos) }
    var ranking by remember { mutableStateOf(original.applyToRankVideos) }
    var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("推荐筛选") }, text = {
        Column(Modifier.width(650.dp).heightIn(max = 650.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("规则保存在本机，与插件页的推荐筛选共用设置。")
            DiscoveryFilterSwitch("开启筛选", enabled, !busy) { enabled = it }
            OutlinedTextField(duration, { duration = it.filter(Char::isDigit) }, label = { Text("最小时长（秒，0 不限制）") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(views, { views = it.filter(Char::isDigit) }, label = { Text("最小播放量（0 不限制）") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(ratio, { ratio = it.filter(Char::isDigit) }, label = { Text("最低点赞率（%，0 不限制）") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(titleWords, { titleWords = it }, label = { Text("屏蔽标题关键词 / 正则（每行一条）") }, minLines = 2, maxLines = 4, enabled = !busy, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(regionWords, { regionWords = it }, label = { Text("屏蔽分区关键词 / 正则（每行一条）") }, minLines = 2, maxLines = 4, enabled = !busy, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(blocked, { blocked = it }, label = { Text("本地屏蔽 UP（每行 UID 或名称 + UID）") }, minLines = 2, maxLines = 4, enabled = !busy, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(whitelist, { whitelist = it }, label = { Text("白名单 UP（优先豁免，格式同上）") }, minLines = 2, maxLines = 4, enabled = !busy, modifier = Modifier.fillMaxWidth())
            DiscoveryFilterSwitch("推荐中已关注 UP 豁免", followed, !busy) { followed = it }
            DiscoveryFilterSwitch("也应用于综合热门 / 每周必看 / 入站必刷", popular, !busy) { popular = it }
            DiscoveryFilterSwitch("也应用于排行榜", ranking, !busy) { ranking = it }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = {
        if (busy) return@TextButton
        busy = true; error = null
        scope.launch {
            try {
                val config = original.copy(minDurationForRcmd = duration.toLongOrNull() ?: throw IllegalArgumentException("请输入有效时长"),
                    minPlayForRcmd = views.toLongOrNull() ?: throw IllegalArgumentException("请输入有效播放量"),
                    minLikeRatioForRecommend = ratio.toIntOrNull() ?: throw IllegalArgumentException("请输入有效点赞率"),
                    banWordForRecommend = titleWords, banWordForZone = regionWords,
                    recommendBlockedMids = DesktopFeedFilterEditor.parseUidMap(blocked), whitelistMids = DesktopFeedFilterEditor.parseUidMap(whitelist),
                    exemptFilterForFollowed = followed, applyToHotVideos = popular, applyToRankVideos = ranking)
                val value = DesktopDiscoveryFilters(enabled, config)
                validateDiscoveryFilters(value)
                store.setFeedFilterConfig(value.config)
                store.setFeedFilterEnabled(value.enabled)
                onSaved(value)
            } catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure.message ?: "保存失败" }
            finally { busy = false }
        }
    }) { Text("保存") } }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } })
}

@Composable private fun DiscoveryFilterSwitch(label: String, value: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, Modifier.weight(1f)); Switch(value, onChange, enabled = enabled) }
}
