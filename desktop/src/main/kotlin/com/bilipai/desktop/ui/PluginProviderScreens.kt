package com.bilipai.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.android.purebilibili.core.network.policy.HomeFeedAnonymizerRuntime
import com.android.purebilibili.core.plugin.RecommendationStrategy
import com.android.purebilibili.core.plugin.feed.*
import com.android.purebilibili.feature.plugin.*
import com.bilipai.desktop.data.VideoCard
import com.bilipai.desktop.data.discoveryVideoCard
import com.bilipai.desktop.plugins.DesktopPluginRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

@Composable
internal fun DesktopAdditionalPluginSettings(id: String, runtime: DesktopPluginRuntime, onDismiss: () -> Unit,
    onVideo: ((VideoCard) -> Unit)?, onPlayQueue: ((List<VideoCard>, VideoCard) -> Unit)?) {
    when (id) {
        HOME_FEED_ANONYMIZER_PLUGIN_ID -> HomeAnonymizerSettings(onDismiss)
        ADFILTER_PLUGIN_ID -> DesktopAdFilterSettings(runtime, onDismiss)
        CDN_REGION_PLUGIN_ID -> DesktopCdnSettings(runtime, onDismiss)
        SubscriptionFeedPlugin.PLUGIN_ID -> SubscriptionReaderDialog(runtime, onDismiss = onDismiss)
        TodayWatchPlugin.PLUGIN_ID -> DesktopTodayWatchSettings(runtime, onDismiss, onVideo, onPlayQueue)
    }
}

@Composable private fun HomeAnonymizerSettings(onDismiss: () -> Unit) {
    var stats by remember { mutableStateOf(HomeFeedAnonymizerRuntime.statsSnapshot) }
    LaunchedEffect(Unit) { while (true) { stats = HomeFeedAnonymizerRuntime.statsSnapshot; delay(1_000) } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("初见推荐") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (HomeFeedAnonymizerRuntime.enabled) "已启用" else "未启用")
            Text("匿名化 Web 首页请求 ${stats.totalHits} 次")
            Text("最近接口：${stats.lastHitEncodedPath ?: "暂无命中"}")
            Text("作用于 Web 首页推荐；刷新首页后即可查看实际命中。")
            TextButton(onClick = { HomeFeedAnonymizerRuntime.resetStats(); stats = HomeFeedAnonymizerRuntime.statsSnapshot }) { Text("清除命中统计") }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}

@Composable private fun DesktopAdFilterSettings(runtime: DesktopPluginRuntime, onDismiss: () -> Unit) {
    var config by remember { mutableStateOf<AdFilterConfig?>(null) }
    var names by remember { mutableStateOf("") }; var mids by remember { mutableStateOf("") }
    var words by remember { mutableStateOf("") }; var minimum by remember { mutableStateOf("") }
    var records by remember { mutableStateOf<List<AdFilterRecord>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snapshot by runtime.store.snapshot("plugin_prefs").collectAsState()
    LaunchedEffect(runtime) {
        try {
            val loaded = runtime.configuration(ADFILTER_PLUGIN_ID)?.let { Json.decodeFromString<AdFilterConfig>(it) } ?: AdFilterConfig()
            config = loaded; names = loaded.blockedUpNames.joinToString("\n"); mids = loaded.blockedUpMids.joinToString("\n")
            words = loaded.blockedKeywords.joinToString("\n"); minimum = loaded.minViewCount.toString()
        } catch (e: Exception) { if (e is CancellationException) throw e; error = e.message }
    }
    LaunchedEffect(snapshot) { records = AdFilterInsightStore.readRecords(runtime.context) }
    ProviderSettingsDialog("去广告", busy, error, onDismiss, {
        val current = config ?: return@ProviderSettingsDialog
        busy = true; scope.launch {
            try {
                val values = splitEditorList(mids).map { it.toLongOrNull()?.takeIf { value -> value > 0 } ?: throw IllegalArgumentException("UP 主 MID 须为正整数：$it") }
                val count = minimum.trim().toIntOrNull()?.takeIf { it >= 0 } ?: throw IllegalArgumentException("最低播放量须为非负整数")
                runtime.saveAdFilterConfiguration(current.copy(minViewCount = count, blockedUpMids = values,
                    blockedUpNames = splitEditorList(names), blockedKeywords = splitEditorList(words))); onDismiss()
            } catch (e: Exception) { if (e is CancellationException) throw e; error = e.message }
            finally { busy = false }
        }
    }) {
        config?.let { current ->
            ProviderSwitch("屏蔽广告推广", current.filterSponsored) { config = current.copy(filterSponsored = it) }
            ProviderSwitch("屏蔽标题党", current.filterClickbait) { config = current.copy(filterClickbait = it) }
            ProviderSwitch("过滤低播放量", current.filterLowQuality) { config = current.copy(filterLowQuality = it) }
            ProviderText("最低播放量", minimum) { minimum = it }
            ProviderText("屏蔽 UP 主名称（换行分隔）", names) { names = it }
            ProviderText("屏蔽 UP 主 MID（换行分隔）", mids) { mids = it }
            ProviderText("屏蔽关键词（换行分隔）", words) { words = it }
            Text("近期过滤记录 · ${records.size}", style = MaterialTheme.typography.titleMedium)
            records.take(15).forEach { record -> Text("${record.reasonLabel} · ${record.videoTitle}\n${record.upName} · ${record.matchedText}", style = MaterialTheme.typography.bodySmall) }
        } ?: CircularProgressIndicator()
    }
}

private fun splitEditorList(text: String) = text.split('\n', ',', '，').map(String::trim).filter(String::isNotBlank).distinct()

@Composable private fun DesktopCdnSettings(runtime: DesktopPluginRuntime, onDismiss: () -> Unit) {
    var config by remember { mutableStateOf<CdnRegionPluginCache?>(null) }
    var rules by remember { mutableStateOf("[]") }; var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }; val scope = rememberCoroutineScope()
    LaunchedEffect(runtime) {
        val loaded = CdnRegionPluginStore.read(runtime.context)
        config = loaded; rules = Json { prettyPrint = true }.encodeToString(ListSerializer(CdnCustomRule.serializer()), loaded.customRules)
    }
    ProviderSettingsDialog("CDN 线路", busy, error, onDismiss, {
        val current = config ?: return@ProviderSettingsDialog
        busy = true; scope.launch {
            try {
                val parsed = Json.decodeFromString(ListSerializer(CdnCustomRule.serializer()), rules)
                validateCdnCustomRules(parsed).firstOrNull { it.error != null }?.let { throw IllegalArgumentException(it.error!!) }
                runtime.saveCdnConfiguration(current.copy(customRules = parsed)); onDismiss()
            } catch (e: Exception) { if (e is CancellationException) throw e; error = e.message }
            finally { busy = false }
        }
    }) {
        config?.let { current ->
            Text("${current.location.country} ${current.location.province} ${current.location.city} · ${current.location.isp}")
            Text("地区：${current.selectedRegion.ifBlank { "尚未识别" }} · 候选 ${current.selectedHosts.size}")
            current.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("默认按已签名的可用候选与线路健康状态选线。", style = MaterialTheme.typography.bodySmall)
            ProviderSwitch("实验性域名改写", current.experimentalRewriteEnabled) { config = current.copy(experimentalRewriteEnabled = it) }
            ProviderSwitch("严格使用自定义 CDN", current.strictCustomCdn) { config = current.copy(strictCustomCdn = it) }
            ProviderText("自定义规则 JSON（pattern / replacement / enabled）", rules) { rules = it }
            current.healthByHost.values.forEach { health -> Text("${health.host} · 就绪 ${health.readyCount} · 错误 ${health.playbackErrorCount} · 缓冲 ${health.bufferingCount}", style = MaterialTheme.typography.bodySmall) }
        } ?: CircularProgressIndicator()
    }
}

@Composable private fun DesktopTodayWatchSettings(runtime: DesktopPluginRuntime, onDismiss: () -> Unit,
    onVideo: ((VideoCard) -> Unit)?, onPlayQueue: ((List<VideoCard>, VideoCard) -> Unit)?) {
    val stored by runtime.todayWatch.configState.collectAsState()
    var config by remember { mutableStateOf(stored) }
    var showPlan by remember { mutableStateOf(false) }; var confirmClear by remember { mutableStateOf(false) }
    ProviderSettingsDialog("今日推荐单", false, null, onDismiss, {
        runtime.todayWatch.updateConfig { config }; onDismiss()
    }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TodayWatchPluginMode.entries.forEach { mode -> FilterChip(config.currentMode == mode, { config = config.copy(currentMode = mode) }, { Text(if (mode == TodayWatchPluginMode.RELAX) "休闲" else "学习") }) }
        }
        Text("推荐策略")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { RecommendationStrategy.entries.forEach { strategy ->
            FilterChip(config.recommendationStrategy == strategy, { config = config.copy(recommendationStrategy = strategy) }, { Text(when (strategy) { RecommendationStrategy.BALANCED -> "均衡"; RecommendationStrategy.AFFINITY -> "偏好"; RecommendationStrategy.EXPLORE -> "探索" }) })
        } }
        ProviderSwitch("扩充在线候选", config.candidatePoolMode == TodayWatchCandidatePoolMode.EXPANDED) { config = config.copy(candidatePoolMode = if (it) TodayWatchCandidatePoolMode.EXPANDED else TodayWatchCandidatePoolMode.LOCAL) }
        Text("队列 ${config.queueBuildLimit} 项"); Slider(config.queueBuildLimit.toFloat(), { config = config.copy(queueBuildLimit = it.toInt()) }, valueRange = 6f..40f, steps = 33)
        Text("历史样本 ${config.historySampleLimit} 项"); Slider(config.historySampleLimit.toFloat(), { config = config.copy(historySampleLimit = it.toInt()) }, valueRange = 20f..120f, steps = 99)
        Text("偏好 UP ${config.upRankLimit} 位"); Slider(config.upRankLimit.toFloat(), { config = config.copy(upRankLimit = it.toInt()) }, valueRange = 1f..12f, steps = 10)
        ProviderSwitch("关联护眼夜间信号", config.linkEyeCareSignal) { config = config.copy(linkEyeCareSignal = it) }
        ProviderSwitch("显示推荐原因", config.showReasonHint) { config = config.copy(showReasonHint = it) }
        ProviderSwitch("显示偏好 UP", config.showUpRank) { config = config.copy(showUpRank = it) }
        Button(onClick = { runtime.todayWatch.updateConfig { config }; showPlan = true }) { Text("打开今日推荐单") }
        TextButton(onClick = { confirmClear = true }) { Text("清除当前账号画像和反馈") }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("清除个性化数据") }, text = { Text("清除当前账号的观看偏好和不感兴趣记录。") },
        confirmButton = { TextButton(onClick = { runtime.todayWatch.clearPersonalizationData(); confirmClear = false }) { Text("清除") } }, dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } })
    if (showPlan) TodayWatchPlanDialog(runtime, { showPlan = false }, onVideo, onPlayQueue)
}

@Composable fun TodayWatchPlanDialog(runtime: DesktopPluginRuntime, onDismiss: () -> Unit,
    onVideo: ((VideoCard) -> Unit)?, onPlayQueue: ((List<VideoCard>, VideoCard) -> Unit)?) {
    val state by runtime.recommendations.state.collectAsState()
    val config by runtime.todayWatch.configState.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(runtime, config.refreshTriggerToken, config.currentMode, config.recommendationStrategy, config.candidatePoolMode) { runtime.recommendations.reload() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("今日推荐单") }, text = {
        LazyColumn(Modifier.width(880.dp).heightIn(max = 650.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scope.launch { runtime.recommendations.reload(forceHistory = true) } }, enabled = !state.loading) { Text("重新生成") }
                    val queue = state.plan?.videoQueue.orEmpty().map(::discoveryVideoCard)
                    OutlinedButton(onClick = { queue.firstOrNull()?.let { first -> onDismiss(); onPlayQueue?.invoke(queue, first) } }, enabled = queue.isNotEmpty() && onPlayQueue != null) { Text("播放队列") }
                }
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                state.plan?.let { plan ->
                    Text("已参考 ${plan.historySampleCount} 条历史 · ${plan.videoQueue.size} 项推荐")
                    if (config.showUpRank) Text(plan.upRanks.joinToString(" · ") { "${it.name} (${it.watchCount})" }, style = MaterialTheme.typography.bodySmall)
                }
            }
            items(state.plan?.videoQueue.orEmpty(), key = { it.bvid }) { video ->
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AsyncImage(video.pic, video.title, modifier = Modifier.size(150.dp, 90.dp))
                        Column(Modifier.weight(1f)) {
                            Text(video.title, style = MaterialTheme.typography.titleMedium)
                            Text(video.owner.name, style = MaterialTheme.typography.bodySmall)
                            if (config.showReasonHint) Text(state.plan?.explanationByBvid?.get(video.bvid).orEmpty(), style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { onDismiss(); onVideo?.invoke(discoveryVideoCard(video)) }, enabled = onVideo != null) { Text("播放") }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}

@Composable fun SubscriptionReaderDialog(runtime: DesktopPluginRuntime, refreshOnOpen: Boolean = false, onDismiss: () -> Unit) {
    val repository = runtime.subscriptions
    val jsRevision by runtime.jsPlugins.host.executionRevision.collectAsState()
    val state by repository.state.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("") }; var url by remember { mutableStateOf("") }
    var importText by remember { mutableStateOf<String?>(null) }; var showSources by remember { mutableStateOf(true) }
    var unreadOnly by remember { mutableStateOf(false) }; var article by remember { mutableStateOf<ParsedFeedItem?>(null) }
    fun action(block: suspend () -> Unit) { if (busy) return; busy = true; error = null; scope.launch {
        try { block() } catch (e: Exception) { if (e is CancellationException) throw e; error = e.message ?: "订阅操作失败" } finally { busy = false }
    } }
    LaunchedEffect(repository, refreshOnOpen, jsRevision) { repository.loadCached(); if (refreshOnOpen) action { repository.refresh() } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("订阅与阅读") }, text = {
        LazyColumn(Modifier.width(950.dp).heightIn(max = 680.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { action { repository.refresh() } }, enabled = !busy && !state.loading) { Text("刷新订阅") }
                    TextButton(onClick = { showSources = !showSources }) { Text(if (showSources) "收起订阅源" else "管理订阅源") }
                    TextButton(onClick = { importText = "" }) { Text("导入 OPML") }
                    TextButton(onClick = { action { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(repository.exportOpml()), null) } }) { Text("复制 OPML") }
                }
                if (busy || state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                (state.errors + listOfNotNull(error)).forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            if (showSources) {
                item {
                    ProviderText("订阅名称（可选）", title) { title = it }
                    ProviderText("RSS 或 Atom 地址", url) { url = it }
                    TextButton(onClick = { action { repository.add(title, url); title = ""; url = "" } }, enabled = !busy && url.isNotBlank()) { Text("添加订阅") }
                }
                items(state.sources, key = { "source:${it.id}" }) { source -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) { Text(source.title); Text(source.url, style = MaterialTheme.typography.bodySmall) }
                    Switch(source.enabled, { value -> action { repository.setEnabled(source.id, value) } }, enabled = !busy)
                    TextButton(onClick = { action { repository.remove(source.id) } }, enabled = !busy) { Text("删除") }
                } }
            }
            item { ProviderSwitch("只看未读", unreadOnly) { unreadOnly = it }; Text("${state.reading.items.size} 篇缓存文章", style = MaterialTheme.typography.bodySmall) }
            val articles = state.reading.items.filter { !unreadOnly || feedItemKey(it) !in state.reading.readKeys }
            items(articles, key = ::feedItemKey) { item ->
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().clickable { article = item; action { repository.setRead(item, true) } }) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(item.title, style = MaterialTheme.typography.titleMedium)
                        Text("${item.sourceTitle} · ${item.author} · ${if (feedItemKey(item) in state.reading.readKeys) "已读" else "未读"}", style = MaterialTheme.typography.bodySmall)
                        Text(feedPlainText(item.summary).take(250), maxLines = 3)
                        TextButton(onClick = { action { repository.setRead(item, feedItemKey(item) !in state.reading.readKeys) } }) { Text("切换已读状态") }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
    importText?.let { initial ->
        var text by remember(initial) { mutableStateOf(initial) }
        AlertDialog(onDismissRequest = { importText = null }, title = { Text("导入 OPML / 订阅列表") }, text = { OutlinedTextField(text, { text = it }, minLines = 10, maxLines = 18, modifier = Modifier.width(750.dp)) },
            confirmButton = { TextButton(onClick = { action { repository.importOpml(text); importText = null } }, enabled = !busy && text.isNotBlank()) { Text("导入") } }, dismissButton = { TextButton(onClick = { importText = null }) { Text("取消") } })
    }
    article?.let { item -> SubscriptionArticleDialog(runtime, item) { article = null } }
}

@Composable private fun SubscriptionArticleDialog(runtime: DesktopPluginRuntime, item: ParsedFeedItem, onDismiss: () -> Unit) {
    val reader by runtime.subscriptions.state.collectAsState()
    var full by remember(item) { mutableStateOf(reader.reading.fullBodies[feedItemKey(item)]) }
    var loading by remember(item) { mutableStateOf(false) }; var error by remember(item) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope(); val uri = LocalUriHandler.current
    val html = full ?: item.htmlContent.ifBlank { item.summary }
    val blocks = remember(html, item.link) { parseFeedHtml(html, item.link) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(item.title) }, text = {
        LazyColumn(Modifier.width(900.dp).heightIn(max = 680.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("${item.sourceTitle} · ${item.author}", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { uri.openUri(item.link) }, enabled = isHttpFeedUrl(item.link)) { Text("打开原文") }
                    TextButton(onClick = { loading = true; error = null; scope.launch {
                        try { full = runtime.subscriptions.loadFullArticle(item) } catch (e: Exception) { if (e is CancellationException) throw e; error = e.message } finally { loading = false }
                    } }, enabled = !loading) { Text("加载完整原文") }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            items(blocks) { block -> FeedBlockContent(block) }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}

@Composable private fun FeedBlockContent(block: FeedBlock) {
    val uri = LocalUriHandler.current
    when (block) {
        is FeedBlock.Heading -> FeedInlineContent(block.inlines, heading = true)
        is FeedBlock.Paragraph -> FeedInlineContent(block.inlines)
        is FeedBlock.Quote -> Surface(color = MaterialTheme.colorScheme.surfaceVariant) { Box(Modifier.padding(12.dp)) { FeedInlineContent(block.inlines) } }
        is FeedBlock.Code -> SelectionContainer { Text(block.text, style = MaterialTheme.typography.bodySmall) }
        is FeedBlock.BulletList -> block.items.forEach { Row { Text("• "); FeedInlineContent(it) } }
        is FeedBlock.NumberedList -> block.items.forEachIndexed { index, line -> Row { Text("${index + 1}. "); FeedInlineContent(line) } }
        is FeedBlock.Image -> if (isHttpFeedUrl(block.url)) AsyncImage(block.url, block.alt, modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp))
        is FeedBlock.EmbeddedLink -> TextButton(onClick = { uri.openUri(block.url) }, enabled = isHttpFeedUrl(block.url)) { Text(block.title.ifBlank { block.url }) }
    }
}

@Composable private fun FeedInlineContent(inlines: List<FeedInline>, heading: Boolean = false) {
    val uri = LocalUriHandler.current
    val builder = AnnotatedString.Builder()
    inlines.forEach { inline -> when (inline) {
        is FeedInline.Text -> { builder.pushStyle(SpanStyle(fontWeight = if (inline.bold) FontWeight.Bold else null, fontStyle = if (inline.italic) FontStyle.Italic else null)); builder.append(inline.text); builder.pop() }
        is FeedInline.Link -> { builder.pushStyle(SpanStyle(textDecoration = TextDecoration.Underline)); builder.append(inline.text); builder.pop() }
    } }
    SelectionContainer { Text(builder.toAnnotatedString(), style = if (heading) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge) }
    inlines.filterIsInstance<FeedInline.Link>().filter { isHttpFeedUrl(it.url) }.distinctBy { it.url }.forEach { link ->
        TextButton(onClick = { uri.openUri(link.url) }) { Text(link.text.ifBlank { link.url }) }
    }
}

@Composable private fun ProviderSettingsDialog(title: String, busy: Boolean, error: String?, onDismiss: () -> Unit,
    save: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(title) }, text = {
        Column(Modifier.width(750.dp).heightIn(max = 650.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            content(); error?.let { Text(it, color = MaterialTheme.colorScheme.error) }; if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(onClick = save, enabled = !busy) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } })
}
@Composable private fun ProviderSwitch(label: String, checked: Boolean, update: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, Modifier.weight(1f)); Switch(checked, update) }
}
@Composable private fun ProviderText(label: String, value: String, update: (String) -> Unit) = OutlinedTextField(value, update, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
