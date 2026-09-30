package com.bilipai.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.article.ArticleContentBlock
import com.android.purebilibili.feature.article.ArticleTextSpan
import com.bilipai.desktop.data.*
import com.android.purebilibili.feature.video.note.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun CommunityImage(url: String, description: String) {
    if (url.isBlank()) return
    var expanded by remember(url) { mutableStateOf(false) }
    AsyncImage(model = imageUrl(url), contentDescription = description, contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 360.dp).clickable { expanded = true })
    if (expanded) AlertDialog(onDismissRequest = { expanded = false }, text = {
        AsyncImage(model = imageUrl(url), contentDescription = description, contentScale = ContentScale.Fit,
            modifier = Modifier.width(700.dp).heightIn(min = 280.dp, max = 700.dp))
    }, confirmButton = { TextButton(onClick = { expanded = false }) { Text("关闭图片") } })
}

@Composable
internal fun CommunityArticle(articleId: Long, community: DesktopCommunityRepository, navigation: CommunityNavigation) {
    var article by remember(articleId) { mutableStateOf<ArticleDocument?>(null) }
    var error by remember(articleId) { mutableStateOf<Throwable?>(null) }
    var loading by remember(articleId) { mutableStateOf(true) }
    var revision by remember(articleId) { mutableIntStateOf(0) }
    LaunchedEffect(articleId, revision) {
        loading = true; error = null
        try { article = community.articleDetail(articleId) }
        catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure }
        finally { loading = false }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { CommunityFailure(it, navigation.onLogin) { revision++ } }
        article?.let { document ->
            Text(document.data.title.ifBlank { document.data.opus?.title.orEmpty() }, style = MaterialTheme.typography.headlineMedium)
            document.data.author?.let { author ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.clickable { if (author.mid > 0) navigation.onUser(author.mid) }) {
                    AsyncImage(model = imageUrl(author.face), contentDescription = author.name, modifier = Modifier.size(40.dp))
                    Text(author.name, style = MaterialTheme.typography.titleMedium)
                }
            }
            if (document.data.summary.isNotBlank()) Text(document.data.summary, color = MaterialTheme.colorScheme.onSurfaceVariant)
            document.blocks.forEach { CommunityArticleBlock(it) }
            if (document.blocks.none { it is ArticleContentBlock.Image }) document.data.imageUrls.forEach { CommunityImage(it, "专栏图片") }
        }
    }
}

@Composable
internal fun CommunityArticleBlock(block: ArticleContentBlock) {
    when (block) {
        is ArticleContentBlock.Heading -> Text(communityAnnotatedText(block.text, block.spans), style = MaterialTheme.typography.titleLarge)
        is ArticleContentBlock.Paragraph -> Text(communityAnnotatedText(block.text, block.spans), style = MaterialTheme.typography.bodyLarge)
        is ArticleContentBlock.Quote -> Surface(color = MaterialTheme.colorScheme.surfaceVariant) { Text(communityAnnotatedText(block.text, block.spans), Modifier.fillMaxWidth().padding(14.dp)) }
        is ArticleContentBlock.ListBlock -> block.items.forEachIndexed { index, item -> Text("${if (block.ordered) "${index + 1}." else "•"} $item", style = MaterialTheme.typography.bodyLarge) }
        is ArticleContentBlock.Code -> Surface(color = MaterialTheme.colorScheme.surfaceVariant) { Text(block.content, Modifier.fillMaxWidth().padding(14.dp), fontFamily = FontFamily.Monospace) }
        is ArticleContentBlock.Image -> CommunityImage(block.url, "正文图片")
    }
}

private fun communityAnnotatedText(text: String, spans: List<ArticleTextSpan>) = buildAnnotatedString {
    if (spans.isEmpty()) append(text)
    else spans.forEach { span ->
        pushStyle(SpanStyle(fontSize = span.fontSizeSp?.sp ?: TextUnit.Unspecified,
            color = span.colorArgb?.let { Color(it) } ?: Color.Unspecified,
            fontWeight = if (span.bold) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (span.italic) FontStyle.Italic else FontStyle.Normal,
            textDecoration = if (span.strikethrough) TextDecoration.LineThrough else TextDecoration.None))
        append(span.text); pop()
    }
}

private data class CommunityNoteBody(val title: String, val summary: String, val content: String,
    val tags: List<VideoNoteTagData>, val author: VideoNoteAuthor? = null, val privateId: String? = null)

@Composable
internal fun CommunityVideoNotes(initialVideo: VideoDetails?, repository: DesktopRepository, community: DesktopCommunityRepository,
    navigation: CommunityNavigation) {
    val scope = rememberCoroutineScope()
    val account by repository.account.collectAsState()
    var video by remember(initialVideo) { mutableStateOf(initialVideo) }
    var draft by remember { mutableStateOf("") }
    var opening by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    var body by remember(video, account?.mid) { mutableStateOf<CommunityNoteBody?>(null) }
    var editor by remember(video, account?.mid) { mutableStateOf<VideoNoteEditorDocument?>(null) }
    var editingId by remember(video, account?.mid) { mutableStateOf<String?>(null) }
    var noteRevision by remember(video, account?.mid) { mutableIntStateOf(0) }
    var forbidden by remember(video) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(video?.aid) {
        video?.let { selected -> try { forbidden = community.noteAvailability(selected.aid).forbidNoteEntrance }
            catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure } }
    }
    if (video == null) Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        OutlinedTextField(draft, { draft = it }, label = { Text("视频 BV 号或链接") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(enabled = !opening && draft.isNotBlank(), onClick = {
            val bvid = Regex("BV[0-9A-Za-z]{10}").find(draft)?.value
            if (bvid == null) { error = IllegalArgumentException("请输入有效的 BV 号或视频链接"); return@Button }
            opening = true; error = null
            scope.launch { try { video = repository.videoDetails(bvid) }
                catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure }
                finally { opening = false } }
        }) { Text(if (opening) "加载视频…" else "查看视频笔记") }
        error?.let { CommunityFailure(it, navigation.onLogin) }
    } else {
        val selected = video!!
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (body != null) TextButton(onClick = { body = null }) { Text("‹ 笔记列表") }
                Text(selected.title, style = MaterialTheme.typography.titleMedium)
                if (account == null) TextButton(onClick = navigation.onLogin) { Text("登录并写笔记") }
                else TextButton(enabled = forbidden == false, onClick = {
                    editingId = null; editor = VideoNoteEditorDocument(selected.title, listOf(VideoNoteBlock.Text("")))
                }) { Text(if (forbidden == true) "此视频不支持笔记" else "新建笔记") }
            }
            val note = body
            if (note != null) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(note.title, style = MaterialTheme.typography.headlineSmall)
                if (note.privateId != null) TextButton(enabled = forbidden == false, onClick = {
                    editingId = note.privateId; editor = VideoNoteContentCodec.decode(note.title, note.content)
                }) { Text("编辑 / 分享 / 删除") }
                note.author?.let { Text(it.name, Modifier.clickable { if (it.mid > 0) navigation.onUser(it.mid) }, color = MaterialTheme.colorScheme.primary) }
                if (note.summary.isNotBlank()) Text(note.summary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val parsed = remember(note.content) { communityArticleDocument(ArticleViewData(content = note.content)).blocks }
                if (parsed.isEmpty() && note.content.isNotBlank()) Text(note.content) else parsed.forEach { CommunityArticleBlock(it) }
                note.tags.forEach { tag -> TextButton(onClick = {
                    navigation.onVideo(VideoCard(selected.bvid, selected.title, selected.cover, selected.author, selected.playCount, 0,
                        progressSeconds = tag.seconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), preferredCid = tag.cid,
                        pageIndex = selected.pages.indexOfFirst { it.cid == tag.cid }.coerceAtLeast(0), authorMid = selected.authorMid))
                }) { Text("跳转 ${tag.seconds / 60}:${(tag.seconds % 60).toString().padStart(2, '0')} · 分P ${tag.index + 1}") } }
            } else {
                Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("公开笔记") })
                    FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("我的笔记") })
                }
                error?.let { CommunityFailure(it, navigation.onLogin) }
                key(selected.aid, tab, account?.mid, noteRevision) {
                    if (tab == 0) CommunityFeed(selected.aid, 1, load = { page -> community.publicNotes(selected.aid, page).let { CommunityBatch(it.data.list, it.nextPage) } },
                        identity = { it.cvid }, onLogin = navigation.onLogin) { item ->
                        CommunityLinkCard(item.title, item.author?.face.orEmpty(), "${item.author?.name.orEmpty()} · ${item.likes} 赞\n${item.summary}") {
                            if (!opening) { opening = true; error = null; scope.launch {
                                try { val result = community.publicNote(item.cvid); body = CommunityNoteBody(result.title, result.summary, result.content, result.tags, result.author) }
                                catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure }
                                finally { opening = false }
                            } }
                        }
                    } else CommunityLoginGate(repository, navigation.onLogin) { mid ->
                        CommunityFeed<String, Unit>(Triple(selected.aid, mid, noteRevision), Unit, load = { CommunityBatch(community.privateNoteIds(selected.aid).noteIds, null) },
                            identity = { it }, onLogin = navigation.onLogin) { id ->
                            CommunityLinkCard("我的笔记", "", "笔记编号 $id") {
                                if (!opening) { opening = true; error = null; scope.launch {
                                    try { val result = community.privateNote(selected.aid, id); body = CommunityNoteBody(result.title, result.summary, result.content, result.tags, privateId = id) }
                                    catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure }
                                    finally { opening = false }
                                } }
                            }
                        }
                    }
                }
                if (opening) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            editor?.let { document -> CommunityNoteEditor(selected, document, editingId, community, navigation.onLogin,
                onDismiss = { editor = null }, onSaved = { editor = null; body = null; noteRevision++; tab = 1 }) }
        }
    }
}
