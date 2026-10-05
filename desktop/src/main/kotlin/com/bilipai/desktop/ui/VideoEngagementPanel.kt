package com.bilipai.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.purebilibili.data.model.response.AiSummaryData
import com.android.purebilibili.data.model.response.PlayerInfoData
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VideoEngagementPanel(details: VideoDetails, repository: DesktopRepository, social: DesktopSocialRepository,
    community: DesktopCommunityRepository, onUser: (Long) -> Unit, onLogin: () -> Unit, onNotes: (VideoDetails) -> Unit,
    onSeek: (cid: Long, seconds: Double) -> Unit, commentContent: @Composable () -> Unit,
    globalStore: DesktopPluginStore, stillOwned: () -> Boolean, onFavoriteCount: (Int) -> Unit,
    sourceOwner: DesktopOriginalVideoAcceptedPublication?, modifier: Modifier = Modifier,
    cid: Long = details.pages.firstOrNull()?.cid ?: 0L) {
    val scope = rememberCoroutineScope()
    val account by repository.account.collectAsState()
    val epoch by repository.sessionEpochFlow.collectAsState()
    val capturedEpoch = epoch
    val currentStillOwned by rememberUpdatedState(stillOwned)
    fun owned() = repository.sessionEpoch == capturedEpoch && currentStillOwned()
    var relation by remember(details.aid, epoch) { mutableStateOf<VideoRelation?>(null) }
    var relationRevision by remember(details.aid, epoch) { mutableLongStateOf(0L) }
    var favoriteMessage by remember(details.aid, epoch) { mutableStateOf<String?>(null) }
    var uploader by remember(details.authorMid, epoch) { mutableStateOf<UserProfile?>(null) }
    var error by remember(details.aid) { mutableStateOf<Throwable?>(null) }
    var coins by remember { mutableStateOf(false) }
    var metadata by remember(details.bvid, cid) { mutableStateOf<PlayerInfoData?>(null) }
    var summary by remember(details.bvid, cid) { mutableStateOf<AiSummaryData?>(null) }
    var metadataLoading by remember { mutableStateOf(false) }
    var summaryLoading by remember { mutableStateOf(false) }
    var summaryTranscript by remember(details.bvid, cid) { mutableStateOf(false) }
    var likeEffect by remember(details.aid, account?.mid) { mutableStateOf(false) }
    var likeEffectVersion by remember(details.aid, account?.mid) { mutableIntStateOf(0) }
    val feedMemory = remember(details.aid, account?.mid) { CommunityFeedMemory() }
    fun refreshRelation() { if (account != null) scope.launch {
        val revision = relationRevision
        try { val value = social.videoRelation(details.aid); if (owned() && revision == relationRevision) relation = value }
        catch (failure: Exception) { if (failure is CancellationException) throw failure; if (owned()) error = failure }
    } }
    LaunchedEffect(details.aid, details.authorMid, epoch) {
        if (account != null) {
            val revision = relationRevision
            try { val value = social.videoRelation(details.aid); if (owned() && revision == relationRevision) relation = value }
            catch (failure: Exception) { if (failure is CancellationException) throw failure; if (owned()) error = failure }
        }
        if (details.authorMid > 0) {
            try { val value = social.userProfile(details.authorMid); if (owned()) uploader = value }
            catch (failure: Exception) { if (failure is CancellationException) throw failure; if (owned()) error = failure }
        }
    }
    CompositionLocalProvider(LocalCommunityFeedMemory provides feedMemory, LocalCommunityFeedNamespace provides Pair("engagement", details.aid)) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val liked = relation?.liked == true
                CommunityAction(if (liked) "取消赞" else "点赞", onLogin, action = { social.setLike(details.aid, !liked) },
                    onSuccess = {
                        relation = (relation ?: VideoRelation(false, false, 0)).copy(liked = !liked)
                        if (!liked) { likeEffectVersion++; likeEffect = true }
                    })
                TextButton(onClick = { if (account == null) onLogin() else coins = true }) { Text("投币${relation?.coins?.takeIf { it > 0 }?.let { " · 已投 $it" }.orEmpty()}") }
                details.raw?.let { raw -> DesktopVideoFavoriteRoot(details.aid, repository, community, globalStore, relation?.favorited,
                    raw.stat.favorite, ::owned,
                    onFavoriteLoaded = { value -> relationRevision++; relation = (relation ?: VideoRelation(false, false, 0)).copy(favorited = value) },
                    onFavoriteSaved = { value, count -> relationRevision++; relation = (relation ?: VideoRelation(false, false, 0)).copy(favorited = value); onFavoriteCount(count) },
                    onLogin = onLogin, feedback = { message -> favoriteMessage = message }, sourceOwner = sourceOwner) }
                CommunityAction("加入稍后再看", onLogin, action = { social.setWatchLater(details.aid, true) })
                CommunityAction("移出稍后再看", onLogin, action = { social.setWatchLater(details.aid, false) })
                TextButton(onClick = { onNotes(details) }) { Text("视频笔记") }
            }
            favoriteMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            key(likeEffectVersion) { DesktopSkinLikeEffect(likeEffect, onFinished = { likeEffect = false }) }
            if (details.authorMid > 0) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { onUser(details.authorMid) }) { Text("${details.author} · 查看空间") }
                if (account?.mid != details.authorMid) {
                    val followed = uploader?.isFollowed == true
                    CommunityAction(if (followed) "取消关注" else "关注 UP 主", onLogin,
                        action = { social.setFollowing(details.authorMid, !followed) }, onSuccess = { uploader = uploader?.copy(isFollowed = !followed) })
                }
            }
            error?.let { CommunityFailure(it, onLogin) { error = null; refreshRelation() } }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(enabled = cid > 0 && !metadataLoading, onClick = {
                    metadataLoading = true; error = null
                    scope.launch { try { metadata = community.playerMetadata(details.bvid, cid) }
                        catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure }
                        finally { metadataLoading = false } }
                }) { Text(if (metadataLoading) "加载章节…" else "章节与看点") }
                TextButton(enabled = cid > 0 && !summaryLoading, onClick = {
                    summaryLoading = true; error = null
                    scope.launch { try { summary = community.aiSummary(details.bvid, cid, details.authorMid) }
                        catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure }
                        finally { summaryLoading = false } }
                }) { Text(if (summaryLoading) "加载总结…" else "AI 总结") }
            }
            metadata?.let { info ->
                if (info.viewPoints.isEmpty()) Text("该分 P 暂无章节", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else info.viewPoints.forEach { chapter ->
                    TextButton(onClick = { onSeek(cid, chapter.from.toDouble()) }) { Text("${panelTime(chapter.from.toLong())} · ${chapter.content}") }
                }
            }
            summary?.let { data ->
                val result = data.modelResult
                if (result == null || result.summary.isBlank() && result.outline.isEmpty() && result.subtitle.isEmpty()) Text(if (data.code == -1) "该视频暂不支持 AI 总结" else "该视频暂无 AI 总结",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                else {
                    if (result.summary.isNotBlank()) Text(result.summary, style = MaterialTheme.typography.bodyLarge)
                    result.outline.forEach { outline ->
                        TextButton(onClick = { onSeek(cid, outline.timestamp.toDouble()) }) { Text("${panelTime(outline.timestamp)} · ${outline.title}") }
                        outline.partOutline.forEach { part -> Text(part.content, Modifier.padding(start = 18.dp).clickable { onSeek(cid, part.timestamp.toDouble()) }) }
                    }
                    if (result.subtitle.isNotEmpty()) {
                        TextButton(onClick = { summaryTranscript = !summaryTranscript }) { Text(if (summaryTranscript) "收起总结字幕" else "展开总结字幕") }
                        if (summaryTranscript) result.subtitle.forEach { section ->
                            Text(section.title, style = MaterialTheme.typography.titleMedium)
                            section.partSubtitle.forEach { part -> TextButton(onClick = { onSeek(cid, part.startTimestamp.toDouble()) }) {
                                Text("${panelTime(part.startTimestamp)} · ${part.content}")
                            } }
                        }
                    }
                }
            }
            HorizontalDivider()
            commentContent()
        }
    }
    if (coins) VideoCoinDialog(details.aid, social, onLogin, onDismiss = { coins = false }, onComplete = { coins = false; refreshRelation() })
}

@Composable
private fun VideoCoinDialog(aid: Long, social: DesktopSocialRepository, onLogin: () -> Unit, onDismiss: () -> Unit, onComplete: () -> Unit) {
    var quantity by remember { mutableIntStateOf(1) }
    var alsoLike by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("为视频投币") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("选择这次投入的硬币数量")
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                listOf(1, 2).forEach { amount -> Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = quantity == amount, onClick = { quantity = amount }); Text("$amount 枚")
                } }
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(alsoLike, { alsoLike = it }); Text("同时点赞") }
        }
    }, confirmButton = { CommunityAction("投 $quantity 枚硬币", onLogin, action = { social.giveCoins(aid, quantity, alsoLike) }, onSuccess = onComplete) },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

private fun panelTime(seconds: Long) = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
