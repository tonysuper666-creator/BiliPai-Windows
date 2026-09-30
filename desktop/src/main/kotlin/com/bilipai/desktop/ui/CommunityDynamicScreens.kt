package com.bilipai.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.android.purebilibili.data.model.response.*
import com.bilipai.desktop.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

@Composable
internal fun CommunityDynamicFeed(mid: Long, community: DesktopCommunityRepository, navigation: CommunityNavigation) {
    var type by remember { mutableStateOf("all") }
    var composing by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var published by remember { mutableStateOf(false) }
    Column {
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("all" to "全部", "video" to "视频", "pgc" to "番剧").forEach { (value, label) ->
                FilterChip(selected = type == value, onClick = { type = value }, label = { Text(label) })
            }
            Button(onClick = { composing = true }) { DesktopSkinDynamicPublishIcon(composing); Text("发布动态") }
        }
        if (published) Text("动态已提交", Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.primary)
        CommunityFeed(Triple(mid, type, revision), "", load = { offset -> community.dynamicFeed(type, offset).let { CommunityBatch(it.items, it.nextOffset) } },
            identity = { it.id_str }, onLogin = navigation.onLogin) { CommunityDynamicCard(it, community, navigation) }
    }
    if (composing) CommunityDynamicComposer(community, navigation, onDismiss = { composing = false }, onPublished = { id ->
        composing = false; revision++; published = true
        if (id != null) navigation.onDynamic(id)
    })
}

@Composable
internal fun CommunityDynamicDetail(id: String, community: DesktopCommunityRepository, navigation: CommunityNavigation) {
    var data by remember(id) { mutableStateOf<DynamicDetailData?>(null) }
    var error by remember(id) { mutableStateOf<Throwable?>(null) }
    var loading by remember(id) { mutableStateOf(true) }
    var revision by remember(id) { mutableIntStateOf(0) }
    LaunchedEffect(id, revision) {
        loading = true; error = null
        try {
            val primary = community.dynamicDetail(id)
            data = primary
            val opus = primary.item?.modules?.module_dynamic?.major?.opus
            if (opus != null && opus.contentBlocks.isEmpty()) {
                val full = community.opusDetail(id)
                if (full.item != null || full.fallback != null) data = full
            }
        }
        catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure }
        finally { loading = false }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (loading) DesktopLoadingIndicator(Modifier.fillMaxWidth())
        error?.let { CommunityFailure(it, navigation.onLogin) { revision++ } }
        data?.item?.let { CommunityDynamicCard(it, community, navigation, details = true) }
        data?.fallback?.takeIf { it.id > 0 }?.let { fallback ->
            Button(onClick = { navigation.onArticle(fallback.id) }) { Text("查看完整专栏") }
        }
    }
}

@Composable
internal fun CommunityDynamicCard(item: DynamicItem, community: DesktopCommunityRepository, navigation: CommunityNavigation,
    depth: Int = 0, details: Boolean = false) {
    var expanded by remember(item.id_str) { mutableStateOf(item.visible) }
    var comments by remember { mutableStateOf(false) }
    var repost by remember { mutableStateOf(false) }
    var liked by remember(item.id_str) { mutableStateOf(item.modules.module_stat?.like?.status == true) }
    val author = item.modules.module_author
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AsyncImage(model = imageUrl(author?.face.orEmpty()), contentDescription = author?.name,
                    modifier = Modifier.size(42.dp).clickable(enabled = (author?.mid ?: 0) > 0) { navigation.onUser(author!!.mid) })
                Column(Modifier.weight(1f)) {
                    Text(author?.name.orEmpty(), fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable(enabled = (author?.mid ?: 0) > 0) { navigation.onUser(author!!.mid) })
                    Text(author?.pub_time.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!details) TextButton(onClick = { navigation.onDynamic(item.id_str) }) { Text("详情") }
            }
            if (!expanded) TextButton(onClick = { expanded = true }) { Text("展开折叠内容") }
            else {
                val content = item.modules.module_dynamic
                content?.desc?.let { CommunityDynamicText(it.text, it.rich_text_nodes, navigation) }
                val major = content?.major
                val archive = major?.archive ?: major?.ugc_season?.archive
                archive?.let {
                    CommunityVideoRow(VideoCard(it.bvid, it.title, it.cover, author?.name.orEmpty(), personalCountText(it.stat.play),
                        personalDurationText(it.duration_text), publishedAt = author?.pub_ts ?: 0, authorMid = author?.mid ?: 0), navigation.onVideo, navigation.onUser)
                }
                major?.pgc?.let { pgc -> CommunityLinkCard(pgc.title, pgc.cover, pgc.desc) { navigateCommunityUrl(pgc.jump_url, navigation) } }
                major?.article?.let { article -> CommunityLinkCard(article.title, article.covers.firstOrNull().orEmpty(), article.desc) {
                    if (article.id > 0) navigation.onArticle(article.id) else navigateCommunityUrl(article.jump_url, navigation)
                } }
                major?.draw?.items?.forEach { picture -> CommunityImage(picture.src, "动态图片") }
                major?.opus?.let { opus ->
                    opus.title?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.titleLarge) }
                    if (opus.contentBlocks.isNotEmpty()) opus.contentBlocks.forEach { CommunityOpusBlock(it, navigation) }
                    else {
                        opus.summary?.let { CommunityDynamicText(it.text, it.rich_text_nodes, navigation) }
                        opus.pics.forEach { CommunityImage(it.url, opus.title.orEmpty()) }
                    }
                    if (!details) TextButton(onClick = { navigation.onDynamic(item.id_str) }) { Text("阅读全文") }
                }
                major?.common?.let { contentCard -> CommunityLinkCard(contentCard.title, contentCard.cover, contentCard.desc) {
                    navigateCommunityUrl(contentCard.jump_url, navigation)
                } }
                major?.music?.let { music -> CommunityLinkCard(music.title, music.cover, music.label) { navigateCommunityUrl(music.jump_url, navigation) } }
                major?.live?.let { live -> CommunityLinkCard(live.title, live.cover, listOf(live.desc_first, live.desc_second).filter { it.isNotBlank() }.joinToString(" · ")) {
                    navigateCommunityUrl(live.jump_url, navigation)
                } }
                major?.medialist?.let { collection -> CommunityLinkCard(collection.title, collection.cover, collection.sub_title) { navigateCommunityUrl(collection.jump_url, navigation) } }
                major?.courses?.let { course -> CommunityLinkCard(course.title, course.cover, course.desc) { navigateCommunityUrl(course.jump_url, navigation) } }
                major?.none?.tips?.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                (major?.live_rcmd ?: major?.subscription_new?.live_rcmd)?.content?.let { raw ->
                    val live = runCatching { Json.parseToJsonElement(raw).jsonObject["live_play_info"]?.jsonObject }.getOrNull()
                    val room = (live?.get("room_id") as? JsonPrimitive)?.longOrNull ?: 0
                    if (live != null) CommunityLinkCard((live["title"] as? JsonPrimitive)?.content.orEmpty(),
                        (live["cover"] as? JsonPrimitive)?.content.orEmpty(), "直播") { if (room > 0) navigation.onLive(room) }
                }
                content?.topic?.let { Text(it.name, color = MaterialTheme.colorScheme.primary) }
                if (depth < 3) item.orig?.let { original -> CommunityDynamicCard(original, community, navigation, depth + 1) }
                else item.orig?.let { TextButton(onClick = { navigation.onDynamic(it.id_str) }) { Text("查看原动态") } }
                if (depth == 0) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (item.modules.module_stat?.like?.forbidden != true) CommunityAction(if (liked) "取消赞" else "赞 ${item.modules.module_stat?.like?.count ?: 0}",
                        navigation.onLogin, action = { community.setDynamicLike(item.id_str, !liked) }, onSuccess = { liked = !liked })
                    if (dynamicCommentTarget(item) != null) TextButton(onClick = { comments = true }) { Text("评论 ${item.modules.module_stat?.comment?.count ?: 0}") }
                    if (item.modules.module_stat?.forward?.forbidden != true) TextButton(onClick = { repost = true }) { Text("转发") }
                }
            }
        }
    }
    if (comments) dynamicCommentTarget(item)?.let { CommunityDynamicComments(it, community, navigation) { comments = false } }
    if (repost) CommunityRepostDialog(item.id_str, community, navigation) { repost = false }
}

@Composable
internal fun CommunityDynamicText(text: String, nodes: List<RichTextNode>, navigation: CommunityNavigation) {
    val resolved = text.ifBlank { nodes.joinToString("") { it.text.ifBlank { it.orig_text } } }
    if (resolved.isNotBlank()) Text(resolved)
    nodes.filter { it.jump_url != null || it.type.contains("AT") || it.emoji != null }.forEach { node ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            node.emoji?.let { AsyncImage(model = imageUrl(it.icon_url.ifBlank { it.webp_url }), contentDescription = it.text, modifier = Modifier.size(28.dp)) }
            if (node.jump_url != null || node.type.contains("AT")) TextButton(onClick = {
                val mid = node.rid?.toLongOrNull()
                if (node.type.contains("AT") && mid != null && mid > 0) navigation.onUser(mid)
                else node.jump_url?.let { navigateCommunityUrl(it, navigation) }
            }) { Text(node.text.ifBlank { node.orig_text }) }
        }
    }
}

@Composable
private fun CommunityOpusBlock(block: OpusContentBlock, navigation: CommunityNavigation) {
    when (block) {
        is OpusContentBlock.Text -> CommunityDynamicText(block.text, block.richTextNodes, navigation)
        is OpusContentBlock.Heading -> Text(block.text, style = MaterialTheme.typography.titleLarge)
        is OpusContentBlock.Quote -> Surface(color = MaterialTheme.colorScheme.surfaceVariant) { Text(block.text, Modifier.padding(12.dp)) }
        is OpusContentBlock.ListBlock -> block.items.forEachIndexed { index, text -> Text("${if (block.ordered) "${index + 1}." else "•"} $text") }
        is OpusContentBlock.Code -> Surface(color = MaterialTheme.colorScheme.surfaceVariant) { Text(block.text, Modifier.padding(12.dp), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) }
        is OpusContentBlock.Image -> CommunityImage(block.pic.url, "正文图片")
        is OpusContentBlock.Divider -> { HorizontalDivider(); block.pic?.let { CommunityImage(it.url, "分隔图片") } }
        is OpusContentBlock.LinkCard -> CommunityLinkCard(block.card.title, block.card.cover, block.card.description) { navigateCommunityUrl(block.card.jumpUrl, navigation) }
    }
}

@Composable
internal fun CommunityLinkCard(title: String, image: String, description: String, onClick: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (image.isNotBlank()) AsyncImage(model = imageUrl(image), contentDescription = title, modifier = Modifier.size(96.dp, 68.dp), contentScale = ContentScale.Crop)
            Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun CommunityRepostDialog(id: String, community: DesktopCommunityRepository, navigation: CommunityNavigation, onDismiss: () -> Unit) {
    var message by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("转发动态") }, text = {
        OutlinedTextField(message, { message = it }, label = { Text("转发时说点什么") }, modifier = Modifier.fillMaxWidth())
    }, confirmButton = { CommunityAction("转发", navigation.onLogin, action = { community.repostDynamic(id, message) }, onSuccess = onDismiss) },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun CommunityDynamicComments(target: CommunityCommentTarget, community: DesktopCommunityRepository,
    navigation: CommunityNavigation, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var root by remember { mutableStateOf<Comment?>(null) }
    var replyTo by remember { mutableStateOf<Comment?>(null) }
    var message by remember { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }
    var posting by remember { mutableStateOf(false) }
    var inputEnabled by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("动态评论") }, text = {
        Column(Modifier.width(640.dp)) {
            if (root != null) TextButton(onClick = { root = null; replyTo = null }) { Text("‹ 全部评论") }
            Box(Modifier.height(350.dp).fillMaxWidth()) {
                CommunityFeed<Comment, Int>(Triple(target, root?.id, revision), 1, load = { page ->
                    val result = root?.let { community.dynamicCommentReplies(target, it.id, page) } ?: community.dynamicComments(target, page)
                    inputEnabled = result.inputEnabled
                    CommunityBatch(result.items, if (result.hasMore) page + 1 else null)
                }, identity = { it.id }, onLogin = navigation.onLogin) { comment ->
                    Column {
                        Text(comment.author, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { if (comment.memberId > 0) navigation.onUser(comment.memberId) })
                        Text(comment.text)
                        CommunityCommentActions(target, comment, community, navigation.onLogin, onChanged = { revision++ })
                        Row {
                            TextButton(onClick = { replyTo = comment }) { Text("回复") }
                            if (root == null && comment.replyCount > 0) TextButton(onClick = { root = comment; replyTo = comment }) { Text("${comment.replyCount} 条回复") }
                        }
                        comment.previewReplies.forEach { preview -> Text("${preview.author}：${preview.text}", style = MaterialTheme.typography.bodySmall) }
                        HorizontalDivider()
                    }
                }
            }
            replyTo?.let { Text("回复 @${it.author}"); TextButton(onClick = { replyTo = null }) { Text("取消回复") } }
            OutlinedTextField(message, { message = it }, enabled = inputEnabled,
                label = { Text(if (inputEnabled) "评论内容" else "评论已关闭") }, modifier = Modifier.fillMaxWidth())
            error?.let { CommunityFailure(it, navigation.onLogin) }
        }
    }, confirmButton = {
        Button(enabled = !posting && inputEnabled && message.isNotBlank(), onClick = {
            posting = true; error = null
            scope.launch {
                try { community.publishDynamicComment(target, message, rootId = root?.id ?: replyTo?.id, parentId = replyTo?.id)
                    message = ""; replyTo = null; revision++ }
                catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure }
                finally { posting = false }
            }
        }) { Text(if (posting) "发送中…" else "发送评论") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
