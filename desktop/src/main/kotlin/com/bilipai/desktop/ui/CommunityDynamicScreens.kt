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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicBoolean
import com.android.purebilibili.core.store.DesktopDynamicCardSettings
import com.android.purebilibili.feature.dynamic.DesktopOriginalDynamicReplySession
import com.bilipai.desktop.appearance.LocalDesktopTextClipboard

@Composable
internal fun CommunityDynamicFeed(mid: Long, community: DesktopCommunityRepository, navigation: CommunityNavigation, active: Boolean = true) {
    val cache=checkNotNull(LocalDesktopDynamicCache.current){"Root dynamic cache is not mounted"}
    val epoch by community.accountEpoch.collectAsState()
    DesktopDynamicCacheContent(cache,mid,epoch,navigation.onLogin) { session ->
        CommunityDynamicFeedReady(mid,community,navigation,epoch,session,active)
    }
}

@Composable
private fun CommunityDynamicFeedReady(mid: Long, community: DesktopCommunityRepository, navigation: CommunityNavigation,
    capturedEpoch:Long, cache:DesktopDynamicCacheSession, active:Boolean) {
    val preferences=checkNotNull(LocalDesktopDynamicTimelinePreferences.current){"Root shared dynamic preferences are not mounted"}
    val blocked by community.blockedUps.mids.collectAsState()
    val notInterested by cache.notInterestedIds.collectAsState()
    val cacheFailure by cache.writeFailure.collectAsState()
    val scope=rememberCoroutineScope()
    val latestActive=rememberUpdatedState(active)
    fun owned()=community.accountEpoch.value==capturedEpoch&&community.account.value?.mid==mid
    // Observe the existing physical-page signal, in the actual current request Job.
    // This wait belongs only to this composition's sidebar jobs, never to retained timelines.
    suspend fun awaitPageActive() {
        currentCoroutineContext().ensureActive()
        if(!owned())throw CancellationException("Dynamic source retired")
        snapshotFlow { latestActive.value }.first { it }
        currentCoroutineContext().ensureActive()
        if(!owned())throw CancellationException("Dynamic source retired")
    }
    val cardRegistry=checkNotNull(LocalDesktopDynamicCardStateRegistry.current){"Root dynamic mutation registry is not mounted"}
    val tabsPreferences=remember(preferences,cache){DesktopDynamicTabsPreferences(preferences.context,cache)}
    val users=remember(mid,capturedEpoch,tabsPreferences) {
        DesktopDynamicUsersState(scope,tabsPreferences,mid,
            followingPage={awaitPageActive();community.followings(mid,it,capturedEpoch,mid,::owned).data},
            liveRooms={awaitPageActive();community.dynamicFollowedLiveUsers(capturedEpoch,mid,::owned)},
            unreadUsers={awaitPageActive();community.dynamicUnreadUsers(capturedEpoch,mid,::owned)},
            requestPage={awaitPageActive();community.dynamicSelectedUserPage(it,capturedEpoch,mid,::owned)},
            stillOwned=::owned,selfFace=community.account.value?.avatar.orEmpty())
    }
    DisposableEffect(users){onDispose{users.close()}}
    cardRegistry.register(users)
    val transform=remember(blocked,notInterested){{rows:List<DynamicItem>->desktopVisibleDynamicItems(rows,blocked)
        .filterNot{it.id_str in notInterested}}}
    val editor=checkNotNull(LocalDesktopDynamicEditorActions.current){"Root dynamic editor is not mounted"}
    val memory=LocalDesktopBrowseMemory.current
    val timelines=remember(memory,mid,capturedEpoch){mutableMapOf<String,DesktopDynamicTimelineState>()}
    fun timeline(type:String)=timelines.getOrPut(type) {
        val create={
            lateinit var model:DesktopDynamicTimelineState
            model=DesktopDynamicTimelineState(type,fetchPage={requestType,offset,baseline->
            try{DynamicFeedResponse(data=community.dynamicFeed(requestType,offset,baseline,capturedEpoch,mid,::owned).data)}
            catch(cancelled:CancellationException){throw cancelled}
            catch(failure:BiliApiException){DynamicFeedResponse(code=failure.apiCode,message=failure.message.orEmpty())}
        },stillOwned=::owned,
            initialCachedItems=if(type=="all")cache.cachedAllItems.value else emptyList(),
            onAllTimelineChanged={rows->if(cardRegistry.isCurrentAll(model))cache.saveTimeline(rows)})
            model
        }
        (memory?.screen(listOf("dynamic-settings-timeline",mid,capturedEpoch,type),create)?:create()).also(cardRegistry::register)
    }
    Column {
        cacheFailure?.let{CommunityFailure(it,navigation.onLogin){cache.saveTimeline(timeline("all").page.items)}}
        DesktopDynamicTabsHost(users,preferences,navigation.onUser,navigation.onLogin,transform,::timeline,
            active=active,awaitPageActive=::awaitPageActive,
            trailing={Button(onClick={editor.publish(DynamicPublishDraft(text=""))}){DesktopSkinDynamicPublishIcon(false);Text("发布动态")}}) {
            CommunityDynamicCard(it,community,navigation)
        }
    }
}

@Composable
internal fun CommunityDynamicDetail(route: DesktopDynamicDetailRoute, community: DesktopCommunityRepository, navigation: CommunityNavigation) {
    val id=route.dynamicId
    val repository=checkNotNull(LocalDesktopDynamicCardRepository.current)
    val cardSession=checkNotNull(LocalDesktopDynamicCardSession.current)
    val preferences=checkNotNull(LocalDesktopDynamicTimelinePreferences.current)
    val epoch by community.accountEpoch.collectAsState()
    val capturedEpoch=epoch
    if(!cardSession.matches(repository,capturedEpoch))return
    val imageSaveLocations=checkNotNull(LocalDesktopImageSaveLocations.current){"Root image save locations are not mounted"}
    val account by community.account.collectAsState()
    val capturedMid=remember(repository,id,capturedEpoch,cardSession){repository.account.value?.mid}
    val alive=remember(repository,id,capturedEpoch,cardSession,imageSaveLocations){AtomicBoolean(true)}
    val exportOwnerLock=remember(alive){Any()}
    val exportGuard=repository.dynamicCacheSessionGuard
    val exportOwner=remember(alive){checkNotNull(exportGuard.dynamicCacheOwner())}
    val parentScope=rememberCoroutineScope()
    val pageScope=remember(alive){CoroutineScope(parentScope.coroutineContext+SupervisorJob(parentScope.coroutineContext[Job]))}
    fun owned()=alive.get()&&imageSaveLocations.isActive()&&exportOwner.epoch==capturedEpoch&&cardSession.isOwned()&&repository.sessionEpoch==capturedEpoch
    val operations=remember(alive){DesktopDynamicCardOperations(repository,capturedEpoch,
        stillOwned=::owned,sharedEmotes=cardSession.emotes)}
    val selectedCommentImages=remember(alive){DesktopDynamicEditorSelectedImages(::owned,operations::withOwnedEditorImageAdmission)}
    val saveParent=LocalDesktopDynamicSaveParent.current
    var data by remember(id,capturedEpoch) { mutableStateOf<DynamicDetailData?>(null) }
    var error by remember(id,capturedEpoch) { mutableStateOf<Throwable?>(null) }
    var loading by remember(id,capturedEpoch) { mutableStateOf(true) }
    var revision by remember(id,capturedEpoch) { mutableIntStateOf(0) }
    var likeVersion by remember(id,capturedEpoch) { mutableIntStateOf(0) }
    var forwardVersion by remember(id,capturedEpoch) { mutableIntStateOf(0) }
    var foldVersion by remember(id,capturedEpoch) { mutableIntStateOf(0) }
    var removedVersion by remember(id,capturedEpoch) { mutableIntStateOf(0) }
    var commentVersion by remember(id,capturedEpoch) { mutableIntStateOf(0) }
    val latestItem by rememberUpdatedState(data?.item)
    val history:suspend(Long)->Unit=remember(alive){{articleId->community.reportDynamicArticleView(articleId,capturedEpoch,capturedMid);Unit}}
    val requests=remember(operations){DesktopDynamicReplyOperationsBinding(operations,
        csrfAvailable={!repository.authCookies()["bili_jct"].isNullOrBlank()},
        detailLoader={target->operations.getDynamicDetail(target,latestItem?.takeIf{it.id_str==target},
            onArticleViewed=history)},imageProvider=selectedCommentImages::read)}
    val replySession=remember(alive){DesktopOriginalDynamicReplySession(id,pageScope,requests,
        seedItem={latestItem?.takeIf{it.id_str==id}},stillOwned=::owned)}
    val snackbar=remember(alive){SnackbarHostState()}
    val commentPickers=remember(alive,saveParent){DesktopDynamicEditorWindowsPickers(selectedCommentImages,::owned,
        {saveParent},onFailure={message->if(owned())pageScope.launch{snackbar.showSnackbar(message)}})}
    val clipboard=LocalDesktopTextClipboard.current
    val textShare=LocalDesktopTextShareBindings.current
    val platform=remember(alive,clipboard,imageSaveLocations,textShare){DesktopDynamicCommentPlatform(preferences.context,cardSession.emotes,
        repository,community,operations,capturedEpoch,::owned,clipboard,
        feedback={message->if(owned())pageScope.launch{snackbar.showSnackbar(message)}},
        saveImage={spec->
            val context=currentCoroutineContext()
            val commitOwned:((()->Unit)->Boolean)={commit->exportGuard.withCurrentDynamicCacheOwner(exportOwner){
                    synchronized(exportOwnerLock){
                        if(!owned())throw CancellationException("Reply export page retired")
                        if(!imageSaveLocations.withCommit(commit))throw CancellationException("Reply export settings retired")
                    }
                }}
            imageSaveLocations.save("BiliPai_comment_${System.currentTimeMillis()}.png",
                checkpoint={context.ensureActive();if(!owned())throw CancellationException("Reply export page retired")},
                withOwnedCommit=commitOwned){target->
                writeDesktopReplyCommentImage(spec,target.path,::owned,replaceExisting=target.replaceExisting,
                    withOwnedCommit=commitOwned)
            }
        },pickImages=commentPickers::pickImages,textShare=textShare,shareScope=pageScope)}
    DisposableEffect(alive){onDispose{synchronized(exportOwnerLock){alive.set(false)};replySession.close();selectedCommentImages.close();pageScope.cancel()}}
    val contentRevision by cardSession.contentRevision.collectAsState()
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
    LaunchedEffect(replySession) {
        combine(replySession.selectedCommentTarget,replySession.commentsLoading,replySession.commentTotalCount) {
            target,isLoading,count->Triple(target,isLoading,count)
        }.collect { (target,isLoading,count)->
            if(target!=null&&!isLoading&&count>=0&&owned()&&replySession.isOwned()) {
                val current=data?:return@collect
                val item=current.item?.takeIf{it.id_str==id}
                val stat=item?.modules?.module_stat
                if(item!=null&&stat!=null&&stat.comment.count!=count) {
                    commentVersion++
                    data=current.copy(item=item.copy(modules=item.modules.copy(module_stat=stat.copy(comment=stat.comment.copy(count=count)))))
                }
            }
        }
    }
    LaunchedEffect(operations,revision,contentRevision) {
        loading = true; error = null
        val beforeLike=likeVersion;val beforeForward=forwardVersion;val beforeFold=foldVersion;val beforeRemoved=removedVersion
        val beforeComment=commentVersion
        val beforeCommentReceipt=replySession.commentConfirmationRevision.value
        fun mergeReadback(incoming:DynamicDetailData)=mergeDesktopDynamicDetailReadback(incoming,data,
            likeVersion!=beforeLike,forwardVersion!=beforeForward,foldVersion!=beforeFold,removedVersion!=beforeRemoved,
            commentChanged=commentVersion!=beforeComment||
                replySession.commentConfirmationRevision.value!=beforeCommentReceipt)
        try {
            val item=operations.getDynamicDetail(id,latestItem?.takeIf{it.id_str==id},onArticleViewed=history).getOrThrow()
            if(!owned())return@LaunchedEffect
            data = mergeReadback(DynamicDetailData(item=item))
        }
        catch (failure: Exception) { if (failure is CancellationException) throw failure; if(owned())error = failure }
        finally { if(owned())loading = false }
    }
    val defaultLayout by DesktopDynamicCardSettings.getDynamicDetailImageLayout(preferences.context).collectAsState(
        initial=DesktopDynamicCardSettings.peekDynamicDetailImageLayout(preferences.context))
    val uiState=error?.let{DesktopOriginalDynamicDetailUiState.Error(it.message.orEmpty())}
        ?:data?.item?.let{DesktopOriginalDynamicDetailUiState.Success(it)}
        ?:DesktopOriginalDynamicDetailUiState.Loading
    Box(Modifier.fillMaxSize()) {
        key(alive) {
        CompositionLocalProvider(LocalDesktopDynamicCardMutations provides detailMutations,
            LocalDesktopCommentBindings provides platform) {
            DesktopDetailWindow {
                DesktopOriginalDynamicDetailLayout(id,uiState,replySession,defaultLayout,
                    liquidGlassEnabled=false,currentMid=account?.mid,
                    openCommentRootRpid=route.rootReplyId,openCommentTargetRpid=route.targetReplyId,
                    onBack=navigation.onDynamicBack,onRetry={revision++},onUserClick=navigation.onUser) {layout,onCommentClick->
                    data?.item?.let{CommunityDynamicCard(it,community,navigation,details=true,
                        detailImageLayoutOverride=layout,onDetailCommentClick=onCommentClick)}
                }
            }
        }
        }
        SnackbarHost(snackbar,Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
internal fun CommunityDynamicCard(item: DynamicItem, community: DesktopCommunityRepository, navigation: CommunityNavigation,
    depth: Int = 0, details: Boolean = false,
    detailImageLayoutOverride:DesktopDynamicCardSettings.DynamicDetailImageLayout?=null,
    onDetailCommentClick:(()->Unit)?=null) {
    val repository=checkNotNull(LocalDesktopDynamicCardRepository.current){"Root dynamic card repository is not mounted"}
    val mutations=checkNotNull(LocalDesktopDynamicCardMutations.current){"Root confirmed dynamic actions are not mounted"}
    val epoch by community.accountEpoch.collectAsState()
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
        onCommentClick={if(details)onDetailCommentClick?.invoke() else navigation.onDynamic(it)},
        onNotInterested=mutations.markNotInterested,onLikeConfirmed=mutations.likeConfirmed,onRepostConfirmed=mutations.repostConfirmed,
        onRemoved={removed->mutations.removed(removed);if(details&&removed==item.id_str)navigation.onDynamicBack()},
        detailImageLayoutOverride=detailImageLayoutOverride)
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
