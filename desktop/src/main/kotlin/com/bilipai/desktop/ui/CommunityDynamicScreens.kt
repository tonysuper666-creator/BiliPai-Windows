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
import com.bilipai.desktop.settings.LocalDesktopDynamicTimelinePreferences
import com.bilipai.desktop.settings.DesktopDynamicTabsPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

@Composable
internal fun CommunityDynamicFeed(mid: Long, community: DesktopCommunityRepository, navigation: CommunityNavigation) {
    val cache=checkNotNull(LocalDesktopDynamicCache.current){"Root dynamic cache is not mounted"}
    val epoch by community.accountEpoch.collectAsState()
    DesktopDynamicCacheContent(cache,mid,epoch,navigation.onLogin) { session ->
        CommunityDynamicFeedReady(mid,community,navigation,epoch,session)
    }
}

@Composable
private fun CommunityDynamicFeedReady(mid: Long, community: DesktopCommunityRepository, navigation: CommunityNavigation,
    capturedEpoch:Long, cache:DesktopDynamicCacheSession) {
    val preferences=checkNotNull(LocalDesktopDynamicTimelinePreferences.current){"Root shared dynamic preferences are not mounted"}
    val blocked by community.blockedUps.mids.collectAsState()
    val notInterested by cache.notInterestedIds.collectAsState()
    val cacheFailure by cache.writeFailure.collectAsState()
    val scope=rememberCoroutineScope()
    val cardRegistry=checkNotNull(LocalDesktopDynamicCardStateRegistry.current){"Root dynamic mutation registry is not mounted"}
    val tabsPreferences=remember(preferences){DesktopDynamicTabsPreferences(preferences.context)}
    val users=remember(mid,capturedEpoch,tabsPreferences) {
        DesktopDynamicUsersState(scope,tabsPreferences,mid,
            followingPage={community.followings(mid,it).data},
            liveRooms={community.dynamicFollowedLiveUsers()},unreadUsers={community.dynamicUnreadUsers()},
            requestPage={community.dynamicSelectedUserPage(it)},
            stillOwned={community.accountEpoch.value==capturedEpoch&&community.account.value?.mid==mid},selfFace=community.account.value?.avatar.orEmpty())
    }
    DisposableEffect(users){onDispose{users.close()}}
    cardRegistry.register(users)
    val transform=remember(blocked,notInterested){{rows:List<DynamicItem>->desktopVisibleDynamicItems(rows,blocked)
        .filterNot{it.id_str in notInterested}}}
    var composing by remember {mutableStateOf(false)}
    var revision by remember {mutableIntStateOf(0)}
    var published by remember {mutableStateOf(false)}
    val memory=LocalDesktopBrowseMemory.current
    val timelines=remember(memory,mid,capturedEpoch,revision){mutableMapOf<String,DesktopDynamicTimelineState>()}
    fun timeline(type:String)=timelines.getOrPut(type) {
        val create={
            lateinit var model:DesktopDynamicTimelineState
            model=DesktopDynamicTimelineState(type,fetchPage={requestType,offset,baseline->
            try{DynamicFeedResponse(data=community.dynamicFeed(requestType,offset,baseline).data)}
            catch(cancelled:CancellationException){throw cancelled}
            catch(failure:BiliApiException){DynamicFeedResponse(code=failure.apiCode,message=failure.message.orEmpty())}
        },stillOwned={community.accountEpoch.value==capturedEpoch&&community.account.value?.mid==mid},
            initialCachedItems=if(type=="all")cache.cachedAllItems.value else emptyList(),
            onAllTimelineChanged={rows->if(cardRegistry.isCurrentAll(model))cache.saveTimeline(rows)})
            model
        }
        (memory?.screen(listOf("dynamic-settings-timeline",mid,capturedEpoch,type,revision),create)?:create()).also(cardRegistry::register)
    }
    Column {
        cacheFailure?.let{CommunityFailure(it,navigation.onLogin){cache.saveTimeline(timeline("all").page.items)}}
        if(published)com.android.purebilibili.core.ui.components.AppText("动态已提交",Modifier.padding(horizontal=20.dp))
        DesktopDynamicTabsHost(users,preferences,navigation.onUser,navigation.onLogin,transform,::timeline,
            trailing={Button(onClick={composing=true}){DesktopSkinDynamicPublishIcon(composing);Text("发布动态")}}) {
            CommunityDynamicCard(it,community,navigation)
        }
    }
    if(composing)CommunityDynamicComposer(community,navigation,onDismiss={composing=false},onPublished={id->
        composing=false;revision++;published=true;if(id!=null)navigation.onDynamic(id)
    })
}

@Composable
internal fun CommunityDynamicDetail(id: String, community: DesktopCommunityRepository, navigation: CommunityNavigation) {
    val epoch by community.accountEpoch.collectAsState()
    val capturedEpoch=epoch
    fun owned()=community.accountEpoch.value==capturedEpoch
    var data by remember(id,capturedEpoch) { mutableStateOf<DynamicDetailData?>(null) }
    var error by remember(id,capturedEpoch) { mutableStateOf<Throwable?>(null) }
    var loading by remember(id,capturedEpoch) { mutableStateOf(true) }
    var revision by remember(id,capturedEpoch) { mutableIntStateOf(0) }
    var likeVersion by remember(id,capturedEpoch) { mutableIntStateOf(0) }
    var forwardVersion by remember(id,capturedEpoch) { mutableIntStateOf(0) }
    var foldVersion by remember(id,capturedEpoch) { mutableIntStateOf(0) }
    var removedVersion by remember(id,capturedEpoch) { mutableIntStateOf(0) }
    val rootMutations=checkNotNull(LocalDesktopDynamicCardMutations.current)
    fun mutateDetail(transform:(List<DynamicItem>)->List<DynamicItem>) {
        if(!owned())return
        val current=data?:return
        val item=current.item?:return
        data=current.copy(item=transform(listOf(item)).firstOrNull())
    }
    val detailMutations=DesktopDynamicCardMutationBindings(
        markNotInterested=rootMutations.markNotInterested,
        likeConfirmed={target,liked->if(owned()&&target==id)likeVersion++;rootMutations.likeConfirmed(target,liked);mutateDetail{
            com.android.purebilibili.feature.dynamic.applyDynamicLikeCountChange(it,target,liked)}},
        repostConfirmed={target->if(owned()&&target==id)forwardVersion++;rootMutations.repostConfirmed(target);mutateDetail{
            com.android.purebilibili.feature.dynamic.applyDynamicForwardCountIncrement(it,target)}},
        removed={target->if(owned()&&target==id)removedVersion++;rootMutations.removed(target);mutateDetail{rows->rows.filterNot{it.id_str==target}}},
        unfoldRelated={target->if(owned()&&target==id)foldVersion++;rootMutations.unfoldRelated(target);mutateDetail{
            com.android.purebilibili.feature.dynamic.components.unfoldRelatedDynamicItems(it,target)}},
    )
    LaunchedEffect(id,revision,capturedEpoch) {
        loading = true; error = null
        val beforeLike=likeVersion;val beforeForward=forwardVersion;val beforeFold=foldVersion;val beforeRemoved=removedVersion
        fun mergeReadback(incoming:DynamicDetailData)=mergeDesktopDynamicDetailReadback(incoming,data,
            likeVersion!=beforeLike,forwardVersion!=beforeForward,foldVersion!=beforeFold,removedVersion!=beforeRemoved)
        try {
            val primary = community.dynamicDetail(id)
            if(!owned())return@LaunchedEffect
            data = mergeReadback(primary)
            val opus = primary.item?.modules?.module_dynamic?.major?.opus
            if (opus != null && opus.contentBlocks.isEmpty()) {
                val full = community.opusDetail(id)
                if(!owned())return@LaunchedEffect
                if (full.item != null || full.fallback != null) data = mergeReadback(full)
            }
        }
        catch (failure: Exception) { if (failure is CancellationException) throw failure; if(owned())error = failure }
        finally { if(owned())loading = false }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (loading) DesktopLoadingIndicator(Modifier.fillMaxWidth())
        error?.let { CommunityFailure(it, navigation.onLogin) { revision++ } }
        CompositionLocalProvider(LocalDesktopDynamicCardMutations provides detailMutations) {
            data?.item?.let { CommunityDynamicCard(it, community, navigation, details = true) }
        }
        data?.fallback?.takeIf { it.id > 0 }?.let { fallback ->
            Button(onClick = { navigation.onArticle(fallback.id) }) { Text("查看完整专栏") }
        }
    }
}

@Composable
internal fun CommunityDynamicCard(item: DynamicItem, community: DesktopCommunityRepository, navigation: CommunityNavigation,
    depth: Int = 0, details: Boolean = false) {
    val repository=checkNotNull(LocalDesktopDynamicCardRepository.current){"Root dynamic card repository is not mounted"}
    val mutations=checkNotNull(LocalDesktopDynamicCardMutations.current){"Root confirmed dynamic actions are not mounted"}
    val epoch by community.accountEpoch.collectAsState()
    var comments by remember(item.id_str,epoch){mutableStateOf(false)}
    val nativeRoutes=LocalDesktopDynamicCardNavigation.current
    val routes=(nativeRoutes?:com.android.purebilibili.feature.dynamic.components.DynamicCardNavigationActions(
        onVideoClick={navigation.onVideo(VideoCard(it,"","","",0,0))},onUserClick=navigation.onUser,
        onBangumiClick={sid,_->navigation.onBangumi(sid)},onLiveClick={room,_,_->navigation.onLive(room)})).copy(
        onVideoClick={navigation.onVideo(VideoCard(it,"","","",0,0))},onUserClick=navigation.onUser,
        onTopicClick=navigation.onTopic,onTopicKeywordClick=navigation.onTopicKeyword,
        onArticleClick={id,_->navigation.onArticle(id)},onDynamicDetailClick=navigation.onDynamic,
        onUnfoldRelatedClick=mutations.unfoldRelated,
    )
    DesktopOriginalDynamicCardHost(item,repository,community,routes,isDetail=details,
        onCommentClick={if(details)comments=true else navigation.onDynamic(it)},
        onNotInterested=mutations.markNotInterested,onLikeConfirmed=mutations.likeConfirmed,onRepostConfirmed=mutations.repostConfirmed,
        onRemoved={removed->mutations.removed(removed);if(details&&removed==item.id_str)navigation.onDynamicBack()})
    if(comments)com.android.purebilibili.feature.dynamic.resolveDynamicCommentTargets(item).firstOrNull()?.let{
        CommunityDynamicComments(CommunityCommentTarget(it.oid,it.type),community,navigation){comments=false}
    }
}

@Composable
internal fun CommunityDynamicText(text: String, nodes: List<RichTextNode>, navigation: CommunityNavigation) {
    val resolved = text.ifBlank { nodes.joinToString("") { it.text.ifBlank { it.orig_text } } }
    if (resolved.isNotBlank()) Text(resolved)
    nodes.filter { it.jump_url != null || it.type.contains("AT") || it.type.contains("TOPIC") || it.emoji != null }.forEach { node ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            node.emoji?.let { AsyncImage(model = imageUrl(it.icon_url.ifBlank { it.webp_url }), contentDescription = it.text, modifier = Modifier.size(28.dp)) }
            if (node.jump_url != null || node.type.contains("AT") || node.type.contains("TOPIC")) TextButton(onClick = {
                val mid = node.rid?.toLongOrNull()
                val topic = node.takeIf { it.type.contains("TOPIC") }?.let(::desktopDynamicTopicLinkAction)
                if (topic is com.android.purebilibili.feature.dynamic.components.DynamicRichTextLinkAction.TopicId) navigation.onTopic(topic.topicId)
                else if (topic is com.android.purebilibili.feature.dynamic.components.DynamicRichTextLinkAction.TopicKeyword) navigation.onTopicKeyword(topic.keyword)
                else if (node.type.contains("AT") && mid != null && mid > 0) navigation.onUser(mid)
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
