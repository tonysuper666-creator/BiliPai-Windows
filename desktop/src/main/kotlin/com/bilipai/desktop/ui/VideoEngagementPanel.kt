package com.bilipai.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.purebilibili.data.model.response.AiSummaryData
import com.android.purebilibili.data.model.response.PlayerInfoData
import com.bilipai.desktop.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VideoEngagementPanel(details: VideoDetails, repository: DesktopRepository, social: DesktopSocialRepository,
    community: DesktopCommunityRepository, onUser: (Long) -> Unit, onLogin: () -> Unit, onNotes: (VideoDetails) -> Unit,
    onSeek: (cid: Long, seconds: Double) -> Unit, modifier: Modifier = Modifier,
    cid: Long = details.pages.firstOrNull()?.cid ?: 0L) {
    val scope = rememberCoroutineScope()
    val account by repository.account.collectAsState()
    var relation by remember(details.aid, account?.mid) { mutableStateOf<VideoRelation?>(null) }
    var uploader by remember(details.authorMid, account?.mid) { mutableStateOf<UserProfile?>(null) }
    var error by remember(details.aid) { mutableStateOf<Throwable?>(null) }
    var coins by remember { mutableStateOf(false) }
    var favorites by remember { mutableStateOf(false) }
    var metadata by remember(details.bvid, cid) { mutableStateOf<PlayerInfoData?>(null) }
    var summary by remember(details.bvid, cid) { mutableStateOf<AiSummaryData?>(null) }
    var metadataLoading by remember { mutableStateOf(false) }
    var summaryLoading by remember { mutableStateOf(false) }
    var summaryTranscript by remember(details.bvid, cid) { mutableStateOf(false) }
    var likeEffect by remember(details.aid, account?.mid) { mutableStateOf(false) }
    var likeEffectVersion by remember(details.aid, account?.mid) { mutableIntStateOf(0) }
    val feedMemory = remember(details.aid, account?.mid) { CommunityFeedMemory() }
    fun refreshRelation() { if (account != null) scope.launch {
        try { relation = social.videoRelation(details.aid) }
        catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure }
    } }
    LaunchedEffect(details.aid, details.authorMid, account?.mid) {
        if (account != null) {
            try { relation = social.videoRelation(details.aid) }
            catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure }
        }
        if (details.authorMid > 0) {
            try { uploader = social.userProfile(details.authorMid) }
            catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure }
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
                TextButton(onClick = { if (account == null) onLogin() else favorites = true }) { Text(if (relation?.favorited == true) "已收藏 · 管理" else "云端收藏") }
                CommunityAction("加入稍后再看", onLogin, action = { social.setWatchLater(details.aid, true) })
                CommunityAction("移出稍后再看", onLogin, action = { social.setWatchLater(details.aid, false) })
                TextButton(onClick = { onNotes(details) }) { Text("视频笔记") }
            }
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
            VideoCommentPanel(details.aid, social, community, onUser, onLogin)
        }
    }
    if (coins) VideoCoinDialog(details.aid, social, onLogin, onDismiss = { coins = false }, onComplete = { coins = false; refreshRelation() })
    if (favorites) VideoFavoriteDialog(details.aid, repository, social, onLogin, onDismiss = { favorites = false }, onChanged = { refreshRelation() })
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

@Composable
private fun VideoFavoriteDialog(aid: Long, repository: DesktopRepository, social: DesktopSocialRepository, onLogin: () -> Unit,
    onDismiss: () -> Unit, onChanged: () -> Unit) {
    var folders by remember { mutableStateOf(emptyList<CloudFavoriteFolder>()) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var loading by remember { mutableStateOf(true) }
    var revision by remember { mutableIntStateOf(0) }
    var title by remember { mutableStateOf("") }
    LaunchedEffect(revision) {
        loading = true; error = null
        try { folders = repository.cloudFavoriteFolders() }
        catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure }
        finally { loading = false }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择云端收藏夹") }, text = {
        Column(Modifier.width(600.dp).heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { CommunityFailure(it, onLogin) { revision++ } }
            folders.forEach { folder ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(folder.title, fontWeight = FontWeight.SemiBold); Text("${folder.mediaCount} 个收藏", style = MaterialTheme.typography.bodySmall) }
                    CommunityAction("收藏到此夹", onLogin, action = { social.setFavorite(aid, folder.id, true) }, onSuccess = onChanged)
                    CommunityAction("从此夹移出", onLogin, action = { social.setFavorite(aid, folder.id, false) }, onSuccess = onChanged)
                }
            }
            OutlinedTextField(title, { title = it }, label = { Text("新收藏夹名称") }, singleLine = true)
            if (title.isNotBlank()) CommunityAction("创建收藏夹", onLogin, action = { social.createFavoriteFolder(title) }, onSuccess = { title = ""; revision++ })
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } })
}

@Composable
private fun VideoCommentPanel(aid: Long, social: DesktopSocialRepository, community: DesktopCommunityRepository,
    onUser: (Long) -> Unit, onLogin: () -> Unit) {
    val scope = rememberCoroutineScope()
    var sort by remember(aid) { mutableIntStateOf(1) }
    var root by remember(aid) { mutableStateOf<Comment?>(null) }
    var replyTo by remember(aid) { mutableStateOf<Comment?>(null) }
    var message by remember(aid) { mutableStateOf("") }
    var inputEnabled by remember(aid) { mutableStateOf(true) }
    var revision by remember(aid) { mutableIntStateOf(0) }
    var posting by remember(aid) { mutableStateOf(false) }
    var error by remember(aid) { mutableStateOf<Throwable?>(null) }
    Text("评论", style = MaterialTheme.typography.titleLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (root != null) TextButton(onClick = { root = null; replyTo = null }) { Text("‹ 全部评论") }
        else listOf(1 to "最热", 0 to "最新").forEach { (value, label) -> FilterChip(selected = sort == value, onClick = { sort = value }, label = { Text(label) }) }
    }
    root?.let { Text("${it.author}：${it.text}", style = MaterialTheme.typography.bodyMedium) }
    Box(Modifier.fillMaxWidth().height(440.dp)) {
        CommunityFeed<Comment, Int>(listOf(aid, root?.id, sort, revision), 1, load = { page ->
            val thread = root
            val result = if (thread == null) social.commentPage(aid, page, sort) else social.commentReplies(aid, thread.id, page)
            inputEnabled = result.inputEnabled
            CommunityBatch(result.items, if (result.hasMore) page + 1 else null)
        }, identity = { it.id }, onLogin = onLogin) { comment ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(comment.author, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable(enabled = comment.memberId > 0) { onUser(comment.memberId) })
                Text(comment.text)
                CommunityCommentActions(CommunityCommentTarget(aid, 1), comment, community, onLogin, onChanged = { revision++ })
                Row {
                    Text("${comment.likeCount} 赞", style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = { replyTo = comment }) { Text("回复") }
                    if (root == null && comment.replyCount > 0) TextButton(onClick = { root = comment; replyTo = null }) { Text("展开 ${comment.replyCount} 条回复") }
                }
                comment.previewReplies.forEach { preview ->
                    Text("${preview.author}：${preview.text}", style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.clickable { root = comment; replyTo = preview })
                }
                HorizontalDivider()
            }
        }
    }
    replyTo?.let { Row { Text("回复 @${it.author}"); TextButton(onClick = { replyTo = null }) { Text("取消") } } }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(message, { message = it }, enabled = inputEnabled, label = { Text(if (inputEnabled) "发表评论" else "评论已关闭") }, modifier = Modifier.weight(1f))
        Button(enabled = !posting && inputEnabled && message.isNotBlank(), onClick = {
            if (posting) return@Button
            posting = true; error = null
            scope.launch {
                try { social.publishComment(aid, message, rootId = root?.id ?: replyTo?.id, parentId = replyTo?.id)
                    message = ""; replyTo = null; revision++ }
                catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure }
                finally { posting = false }
            }
        }) { Text(if (posting) "发送中…" else "发布") }
    }
    error?.let { CommunityFailure(it, onLogin) }
}

private fun panelTime(seconds: Long) = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
