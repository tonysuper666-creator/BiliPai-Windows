package com.bilipai.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.article.ArticleContentBlock
import com.bilipai.desktop.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class CommunityMessageEntry(val key: String, val author: String, val avatar: String, val mid: Long,
    val title: String, val text: String, val image: String, val uri: String, val time: Long)

@Composable
internal fun CommunityMessages(mid: Long, community: DesktopCommunityRepository, navigation: CommunityNavigation) {
    var tab by remember { mutableIntStateOf(0) }
    var unread by remember(mid) { mutableStateOf<CommunityUnread?>(null) }
    var unreadError by remember(mid) { mutableStateOf<Throwable?>(null) }
    var unreadRevision by remember(mid) { mutableIntStateOf(0) }
    var selectedSession by remember(mid) { mutableStateOf<SessionItem?>(null) }
    var historyRevision by remember(mid) { mutableIntStateOf(0) }
    var withdraw by remember(mid) { mutableStateOf<PrivateMessageItem?>(null) }
    var acknowledged by remember(mid, selectedSession) { mutableStateOf(false) }
    var newestSeqno by remember(mid, selectedSession) { mutableLongStateOf(0) }
    LaunchedEffect(mid, unreadRevision) {
        try { unread = community.unreadMessages() }
        catch (error: Exception) { if (error is CancellationException) throw error; unreadError = error }
    }
    Column(Modifier.fillMaxSize()) {
        val session = selectedSession
        if (session != null) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { selectedSession = null }) { Text("‹ 会话列表") }
                Text(session.account_info?.name?.ifBlank { session.group_name } ?: session.group_name.ifBlank { "用户 ${session.talker_id}" },
                    style = MaterialTheme.typography.titleMedium)
                Text(" · 由新到旧", color = MaterialTheme.colorScheme.onSurfaceVariant)
                val ack = maxOf(session.max_seqno, newestSeqno)
                if (ack > 0) CommunityAction(if (acknowledged) "已标记已读" else "标记本会话已读", navigation.onLogin,
                    action = { community.markSessionRead(session.talker_id, session.session_type, ack) }, onSuccess = { acknowledged = true; unreadRevision++ })
            }
            CommunityPrivateComposer(session, community, navigation, onSent = { historyRevision++ })
            CommunityFeed<PrivateMessageItem, Long>(listOf("history", session.talker_id, session.session_type, historyRevision), 0L,
                load = { end -> community.messageHistory(session.talker_id, session.session_type, end).let { page ->
                    newestSeqno = maxOf(newestSeqno, page.data.messages.orEmpty().maxOfOrNull { it.msg_seqno } ?: 0)
                    CommunityBatch(page.data.messages.orEmpty(), page.nextEndSeqno) } },
                identity = { it.msg_key.takeIf { key -> key > 0 } ?: it.msg_seqno }, onLogin = navigation.onLogin) { message ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (message.sender_uid == mid) "我" else session.account_info?.name.orEmpty().ifBlank { "用户 ${message.sender_uid}" },
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { navigation.onUser(message.sender_uid) })
                        if (message.sys_cancel || message.msg_status == 1) Text("此消息已撤回", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else CommunityPrivateMessageBody(message.content, message.msg_type, navigation)
                        Text(communityMessageTime(message.timestamp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (message.sender_uid == mid && message.msg_key > 0 && !message.sys_cancel && message.msg_status != 1)
                            TextButton(onClick = { withdraw = message }) { Text("撤回这条消息") }
                    }
                }
            }
            withdraw?.let { message -> AlertDialog(onDismissRequest = { withdraw = null }, title = { Text("撤回这条私信？") },
                text = { Text("平台会按消息状态和时限处理撤回请求。") }, confirmButton = {
                    CommunityAction("确认撤回", navigation.onLogin, action = { community.withdrawMessage(session.talker_id, message, session.session_type) },
                        onSuccess = { withdraw = null; historyRevision++ })
                }, dismissButton = { TextButton(onClick = { withdraw = null }) { Text("取消") } }) }
        } else {
            val counts = listOf(unread?.activity?.reply ?: 0, unread?.activity?.at ?: 0, unread?.activity?.like ?: 0,
                unread?.activity?.sysMsg ?: 0, (unread?.privateMessages?.follow_unread ?: 0) + (unread?.privateMessages?.unfollow_unread ?: 0))
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("回复我的", "@我的", "收到的赞", "系统通知", "私信").forEachIndexed { index, title ->
                    FilterChip(selected = tab == index, onClick = { tab = index }, label = { Text(title + if (counts[index] > 0) " (${counts[index]})" else "") })
                }
            }
            unreadError?.let { Text(it.message ?: "未读数量加载失败", Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.error) }
            key(mid, tab) {
                when (tab) {
                    0 -> CommunityFeed<CommunityMessageEntry, CommunityMessageCursor?>(Pair(mid, tab), null, load = { cursor ->
                        val page = community.replyMessages(cursor)
                        CommunityBatch(page.data.items.orEmpty().map { item ->
                            val content = item.item
                            CommunityMessageEntry("reply:${item.id}", item.user?.nickname.orEmpty(), item.user?.avatar.orEmpty(), item.user?.mid ?: 0,
                                content?.title.orEmpty(), listOf(content?.sourceContent, content?.targetReplyContent, content?.rootReplyContent)
                                    .filterNotNull().filter { it.isNotBlank() }.distinct().joinToString("\n"), content?.image.orEmpty(), content?.uri.orEmpty(), item.replyTime.toLong())
                        }, page.nextCursor)
                    }, identity = { it.key }, onLogin = navigation.onLogin) { CommunityMessageCard(it, navigation) }
                    1 -> CommunityFeed<CommunityMessageEntry, CommunityMessageCursor?>(Pair(mid, tab), null, load = { cursor ->
                        val page = community.mentionMessages(cursor)
                        CommunityBatch(page.data.items.orEmpty().map { item ->
                            val content = item.item
                            CommunityMessageEntry("mention:${item.id}", item.user?.nickname.orEmpty(), item.user?.avatar.orEmpty(), item.user?.mid ?: 0,
                                content?.title.orEmpty(), content?.sourceContent.orEmpty(), content?.image.orEmpty(), content?.uri.orEmpty(), item.atTime)
                        }, page.nextCursor)
                    }, identity = { it.key }, onLogin = navigation.onLogin) { CommunityMessageCard(it, navigation) }
                    2 -> CommunityFeed<CommunityMessageEntry, CommunityMessageCursor?>(Pair(mid, tab), null, load = { cursor ->
                        val page = community.likeMessages(cursor)
                        val items = (if (cursor == null) page.data.latest?.items.orEmpty() else emptyList()) + page.data.total?.items.orEmpty()
                        CommunityBatch(items.distinctBy { it.id }.map { item ->
                            val author = item.users.orEmpty().firstOrNull(); val content = item.item
                            CommunityMessageEntry("like:${item.id}", author?.nickname.orEmpty(), author?.avatar.orEmpty(), author?.mid ?: 0,
                                content?.title.orEmpty(), "${item.counts} 次赞", content?.image.orEmpty(), content?.uri.orEmpty(), item.likeTime.toLong())
                        }, page.nextCursor)
                    }, identity = { it.key }, onLogin = navigation.onLogin) { CommunityMessageCard(it, navigation) }
                    3 -> CommunityFeed<SystemNoticeItem, Long?>(Pair(mid, tab), null, load = { cursor -> community.systemNotices(cursor).let { CommunityBatch(it.items, it.nextCursor) } },
                        identity = { it.cursor.takeIf { value -> value > 0 } ?: it.id.toLong() }, onLogin = navigation.onLogin) { notice ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(notice.title, style = MaterialTheme.typography.titleMedium)
                                val blocks = remember(notice.content) { communityArticleDocument(ArticleViewData(content = notice.content)).blocks }
                                if (blocks.isEmpty()) Text(notice.content) else blocks.forEach { CommunityArticleBlock(it) }
                                Text(notice.timeAt, style = MaterialTheme.typography.labelSmall)
                                Regex("https?://[^\\s\"<>]+").findAll(notice.content).map { it.value }.distinct().take(3).forEach { url ->
                                    TextButton(onClick = { navigateCommunityUrl(url, navigation) }) { Text("打开通知链接") }
                                }
                            }
                        }
                    }
                    4 -> CommunityFeed<SessionItem, CommunitySessionCursor>(Triple(mid, tab, unreadRevision), CommunitySessionCursor(1, 0),
                        load = { cursor -> community.messageSessions(cursor).let { CommunityBatch(it.data.session_list.orEmpty(), it.nextCursor) } },
                        identity = { "${it.talker_id}:${it.session_type}" }, onLogin = navigation.onLogin) { item ->
                        Card(Modifier.fillMaxWidth().clickable(enabled = item.session_type in 1..2 && item.talker_id > 0) { selectedSession = item }) {
                            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                AsyncImage(model = imageUrl(item.account_info?.avatarUrl.orEmpty().ifBlank { item.group_cover }), contentDescription = item.account_info?.name,
                                    modifier = Modifier.size(52.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(item.account_info?.name.orEmpty().ifBlank { item.group_name.ifBlank { "用户 ${item.talker_id}" } }, style = MaterialTheme.typography.titleMedium)
                                    item.last_msg?.let { message -> Text(if (message.msg_status == 1) "消息已撤回" else communityMessagePreview(message.content), maxLines = 2) }
                                }
                                if (item.unread_count > 0) Text("${item.unread_count} 条未读", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommunityPrivateComposer(session: SessionItem, community: DesktopCommunityRepository,
    navigation: CommunityNavigation, onSent: () -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember(session.talker_id, session.session_type) { mutableStateOf("") }
    var image by remember(session.talker_id, session.session_type) { mutableStateOf<CommunityLocalImage?>(null) }
    var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<Throwable?>(null) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(text, { text = it }, enabled = !busy && image == null, label = { Text("私信内容") }, modifier = Modifier.weight(1f))
            TextButton(enabled = !busy, onClick = { scope.launch {
                try { image = selectCommunityImages(false).firstOrNull() }
                catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure }
            } }) { Text("选择图片") }
            Button(enabled = !busy && (image != null || text.isNotBlank()), onClick = {
                busy = true; error = null; scope.launch {
                    try {
                        val selected = image
                        if (selected == null) community.sendTextMessage(session.talker_id, text, session.session_type)
                        else { val uploaded = withContext(Dispatchers.IO) { community.uploadPrivateImage(selected.name, selected.mime, selected.bytes()) }
                            community.sendImageMessage(session.talker_id, uploaded, session.session_type, selected.mime) }
                        text = ""; image = null; onSent()
                    } catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure }
                    finally { busy = false }
                }
            }) { Text(if (busy) "发送中…" else "发送") }
        }
        image?.let { selected -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("待发送图片：${selected.name}"); TextButton(enabled = !busy, onClick = { image = null }) { Text("移除") }
        } }
        error?.let { CommunityFailure(it, navigation.onLogin) }
    }
}

@Composable
private fun CommunityMessageCard(item: CommunityMessageEntry, navigation: CommunityNavigation) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(model = imageUrl(item.avatar), contentDescription = item.author, modifier = Modifier.size(34.dp))
                Text(item.author, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable(enabled = item.mid > 0) { navigation.onUser(item.mid) })
                Text(communityMessageTime(item.time), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (item.title.isNotBlank()) Text(item.title, style = MaterialTheme.typography.titleMedium)
            if (item.text.isNotBlank()) Text(item.text)
            if (item.image.isNotBlank()) CommunityImage(item.image, item.title)
            if (item.uri.isNotBlank()) TextButton(onClick = { navigateCommunityUrl(item.uri, navigation) }) { Text("查看原内容") }
        }
    }
}

@Composable
private fun CommunityPrivateMessageBody(raw: String, type: Int, navigation: CommunityNavigation) {
    val json = remember(raw) { runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() }
    fun field(name: String) = (json?.get(name) as? JsonPrimitive)?.contentOrNull.orEmpty()
    val text = field("content").ifBlank { field("title").ifBlank { if (json == null) raw else communityMessagePreview(raw) } }
    if (text.isNotBlank()) Text(text)
    val image = if (type == 2) field("url") else field("pic").ifBlank { field("cover") }
    if (image.isNotBlank()) CommunityImage(image, "私信图片")
    val url = field("url").ifBlank { field("uri") }
    val bvid = field("bvid")
    if (bvid.isNotBlank()) TextButton(onClick = { navigation.onVideo(VideoCard(bvid, field("title"), image, "", 0, 0)) }) { Text("打开视频") }
    else if (url.isNotBlank() && type != 2) TextButton(onClick = { navigateCommunityUrl(url, navigation) }) { Text("打开内容") }
}

private fun communityMessagePreview(raw: String): String {
    val json = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return raw
    return listOf("content", "title", "description", "text").mapNotNull { (json[it] as? JsonPrimitive)?.contentOrNull }
        .firstOrNull { it.isNotBlank() } ?: if (json["url"] != null) "图片或链接" else "附件消息"
}

private fun communityMessageTime(seconds: Long): String = if (seconds <= 0) "" else runCatching {
    DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(seconds))
}.getOrDefault("")
