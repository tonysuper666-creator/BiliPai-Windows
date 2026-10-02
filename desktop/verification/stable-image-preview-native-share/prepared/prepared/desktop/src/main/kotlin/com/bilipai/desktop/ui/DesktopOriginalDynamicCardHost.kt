package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import com.android.purebilibili.core.store.DesktopDynamicCardSettings
import com.android.purebilibili.core.ui.AppAlertDialog
import com.android.purebilibili.core.ui.AppDialogAction
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.dynamic.*
import com.android.purebilibili.feature.dynamic.components.*
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.settings.LocalDesktopDynamicTimelinePreferences
import kotlinx.coroutines.*
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/** One Root-supplied repository. It is never constructed from a second session. */
internal val LocalDesktopDynamicCardRepository=staticCompositionLocalOf<DesktopRepository?>{null}

/** Original card surface with thin desktop owner/action bindings. Required
 * navigation keeps raw original IDs; no title/count placeholders are created.
 * Feed comment clicks navigate to detail, as in the original DynamicScreen.
 */
@Composable internal fun DesktopOriginalDynamicCardHost(
    item:DynamicItem,
    repository:DesktopRepository,
    community:DesktopCommunityRepository,
    navigation:DynamicCardNavigationActions,
    isDetail:Boolean=false,
    onCommentClick:(String)->Unit= { navigation.onDynamicDetailClick?.invoke(it) },
    onNotInterested:suspend(String)->Unit,
    onUpdated:(DynamicItem)->Unit={},
    onRemoved:(String)->Unit={},
    onEdit:((DynamicManageAction.Edit)->Unit)?=null,
    onSaveDynamic:((DynamicItem)->Unit)?=null,
    onLikeConfirmed:((String,Boolean)->Unit)?=null,
    onRepostConfirmed:((String)->Unit)?=null,
    openExternalLink:((String)->Unit)?=null,
    detailImageLayoutOverride:DesktopDynamicCardSettings.DynamicDetailImageLayout?=null,
) {
    val preferences=checkNotNull(LocalDesktopDynamicTimelinePreferences.current){"Root global dynamic preferences are not mounted"}
    val epoch by community.accountEpoch.collectAsState()
    val capturedEpoch=epoch
    val session=checkNotNull(LocalDesktopDynamicCardSession.current){"Root shared dynamic card session is not mounted"}
    // Parent and child flow collectors can observe an epoch on different frames.
    // Retired card content is omitted until the parent supplies the new session.
    if(!session.matches(repository,capturedEpoch))return
    val account by community.account.collectAsState()
    val capturedMid=account?.mid
    val textShare=LocalDesktopTextShareBindings.current
    val imageShare=LocalDesktopImagePreviewShareBindings.current
    val saveParent=LocalDesktopDynamicSaveParent.current
    val imageSaveLocations=checkNotNull(LocalDesktopImageSaveLocations.current){"Root image save locations are not mounted"}
    val alive=remember(repository,capturedEpoch,item.id_str,saveParent,imageSaveLocations){AtomicBoolean(true)}
    val parentScope=rememberCoroutineScope()
    val scope=remember(alive){CoroutineScope(parentScope.coroutineContext+SupervisorJob(parentScope.coroutineContext[Job]))}
    val operations=remember(repository,capturedEpoch,alive,session){DesktopDynamicCardOperations(repository,capturedEpoch,
        stillOwned={alive.get()&&session.isOwned()},sharedEmotes=session.emotes)}
    val assets=remember(operations){
        val guard=repository.dynamicCacheSessionGuard
        val owner=checkNotNull(guard.dynamicCacheOwner())
        DesktopDynamicImageAssets(repository.httpClient,
            stillOwned={operations.isOwned()&&owner.epoch==capturedEpoch},
            sessionGuard=guard,expectedOwner=owner,
            selectTarget={name,mime->selectDynamicSaveTarget(name,mime,saveParent)},
            selectDirectory={selectDynamicSaveDirectory(saveParent)},imageSaveLocations=imageSaveLocations)
    }
    var feedback by remember(alive){mutableStateOf<String?>(null)}
    var shown by remember(alive){mutableStateOf(item)}
    val pageLikeOverrides by session.likeOverrides.collectAsState()
    val likeOverride=pageLikeOverrides[item.id_str]
    var repost by remember(alive){mutableStateOf(false)}
    var messageShare by remember(alive){mutableStateOf(false)}
    var report by remember(alive){mutableStateOf<DynamicManageAction.Report?>(null)}
    val gate=session.likeGate
    val latestNavigation by rememberUpdatedState(navigation)
    val latestUpdated by rememberUpdatedState(onUpdated)
    val latestLikeConfirmed by rememberUpdatedState(onLikeConfirmed)
    val latestRepostConfirmed by rememberUpdatedState(onRepostConfirmed)
    val latestOpenExternalLink by rememberUpdatedState(openExternalLink)
    val latestRemoved by rememberUpdatedState(onRemoved)
    val latestNotInterested by rememberUpdatedState(onNotInterested)
    val latestComment by rememberUpdatedState(onCommentClick)
    val rootEditor = LocalDesktopDynamicEditorActions.current
    val latestEdit by rememberUpdatedState(onEdit ?: rootEditor?.edit)
    val detailLayout by DesktopDynamicCardSettings.getDynamicDetailImageLayout(preferences.context).collectAsState(
        initial=DesktopDynamicCardSettings.peekDynamicDetailImageLayout(preferences.context))
    val previewText by DesktopDynamicCardSettings.getDynamicImagePreviewTextVisible(preferences.context).collectAsState(initial=true)
    fun owned()=operations.isOwned()
    fun show(message:String){if(owned())feedback=message}
    fun guarded(block:()->Unit){if(owned())block()}
    fun runAction(success:String,block:suspend()->Unit){
        if(!owned())return
        scope.launch{
            try{block();ensureActive();if(owned()&&success.isNotBlank())show(success)}
            catch(cancelled:CancellationException){throw cancelled}
            catch(error:Exception){if(owned())show(error.message?:"操作失败")}
        }
    }
    LaunchedEffect(item,alive){if(owned())shown=item}
    DisposableEffect(alive,assets,scope){onDispose{alive.set(false);assets.close();scope.cancel()}}
    val platform=remember(operations,assets,preferences.context,textShare,imageShare){object:DesktopDynamicCardPlatform{
        override val context:DesktopPluginContext=preferences.context
        override val emotes=operations.emotes
        override fun isOwned()=owned()
        override fun copyText(text:String){guarded{runCatching{Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text),null)}.onFailure{show("复制失败")}}}
        override fun shareText(text:String){requestDesktopTextShare(textShare,scope,"BiliPai 分享",text,::owned,::show)}
        override fun showFeedback(message:String)=show(message)
        override fun openLink(url:String){guarded{
            val uri=runCatching{URI(url)}.getOrNull()?:return@guarded
            if(uri.scheme in setOf("https","http")) {
                val open=latestOpenExternalLink
                if(open!=null)open(uri.toString())
                else if(Desktop.isDesktopSupported()&&Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
                    runCatching{Desktop.getDesktop().browse(uri)}.onFailure{show("无法打开链接")}
            }
        }}
        override suspend fun searchUp(name:String)=operations.searchUp(name)
        override suspend fun getVoteInfo(voteId:Long)=operations.getVoteInfo(voteId)
        override suspend fun submitVote(voteId:Long,optionIndexes:List<Int>,dynamicId:String)=operations.submitVote(voteId,optionIndexes,dynamicId)
        override suspend fun getShareTargets(size:Int)=operations.getShareTargets(size)
        override suspend fun getMessageSessions(size:Int)=operations.getMessageSessions(size)
        override suspend fun fetchMessageUserInfo(mid:Long)=operations.fetchMessageUserInfo(mid)
        override suspend fun sendDynamicShare(receiverId:Long,content:String)=operations.sendDynamicShare(receiverId,content)
        override suspend fun saveImage(url:String)=assets.saveImage(url)
        override suspend fun saveImages(urls:List<String>)=assets.saveImages(urls)
        override suspend fun saveLivePhotoVideo(videoUrl:String)=assets.saveLivePhotoVideo(videoUrl)
        override suspend fun saveMotionPhoto(imageUrl:String,videoUrl:String)=assets.saveMotionPhoto(imageUrl,videoUrl)
        override suspend fun shareImage(url:String):Boolean=imageShare.shareImage(url,::owned)
    }}
    val interaction=DynamicCardInteractionActions(
        onCommentClick={guarded{latestComment(it)}},
        onRepostClick={guarded{repost=true}},
        onLikeClickWithState={id,currentlyLiked->
            if(owned())launchDesktopDynamicLike(gate,scope,id){
                val requestLiked=resolveDynamicLikeState(session.likeOverrides.value[id],false,currentlyLiked)
                try{
                    operations.setLike(id,!requestLiked);ensureActive()
                    if(owned()){
                        shown=applyDynamicLikeCountChange(listOf(shown),id,!requestLiked).single();session.confirmLike(id,!requestLiked)
                        latestLikeConfirmed?.invoke(id,!requestLiked)?:latestUpdated(shown)
                        show(if(!requestLiked)"已点赞"else"已取消")
                    }
                }catch(cancelled:CancellationException){throw cancelled}catch(error:Exception){show(error.message?:"点赞失败")}
            }
        },
        onWatchLaterClick={aid->runAction("已添加到稍后再看"){operations.addWatchLater(aid)}},
        onSaveDynamicClick=onSaveDynamic?.let{save->{guarded{save(shown)}}},
        onShareToMessageClick={guarded{messageShare=true}},
        onCheckDynamicClick={runAction(""){val visible=operations.isPubliclyVisible(shown.id_str);show(if(visible)"匿名状态下可见，动态正常"else"匿名状态下不可见，动态可能仅自己可见或尚未通过审核")}},
        onReserveClick={action,completed->
            if(owned())scope.launch{val result=operations.reserve(action);ensureActive();if(owned())completed(result)}
        },
        onDeleteClick={action->runAction("已删除动态"){operations.delete(action);if(owned())latestRemoved(action.dynamicId)}},
        onLoadReplyInteractionStatus={oid,type,loaded->
            if(owned())scope.launch{try{val status=operations.loadReplyInteraction(oid,type);ensureActive();if(owned())loaded(status)}catch(cancelled:CancellationException){throw cancelled}catch(failed:Exception){if(owned())loaded(null)}}
        },
        onManageAction={action->guarded{
            when(action){
                is DynamicManageAction.Report->report=action
                is DynamicManageAction.Edit->latestEdit?.invoke(action)?:show("动态编辑器未挂载")
                is DynamicManageAction.NotInterested->runAction("已标记为不感兴趣"){latestNotInterested(action.dynamicId)}
                is DynamicManageAction.ToggleTop->runAction(if(action.isCurrentlyTop)"已取消置顶"else"已置顶"){operations.setTop(action)}
                is DynamicManageAction.SetVisibility->runAction(if(action.isPrivate)"已设为仅自己可见"else"已设为公开"){operations.setVisibility(action)}
                is DynamicManageAction.SetReplySubject->runAction("设置成功"){operations.modifyReplySubject(action)}
                is DynamicManageAction.BlockAuthor->runAction(""){
                    val result=community.blockedUpRepository.blockUpWithBilibiliSync(action.authorMid,action.authorName,action.authorFace,
                        com.android.purebilibili.data.repository.BlockedUpRelationSource.PROFILE,capturedEpoch)
                    if(owned())show(result.message)
                }
            }
        }},
    )
    // Each navigation callback is already carried in the original schema; wrap
    // all of them with this owner rather than leaving stale menu routes active.
    val routes=navigation.copy(
        onVideoClick={guarded{latestNavigation.onVideoClick(it)}},onUserClick={guarded{latestNavigation.onUserClick(it)}},
        onBangumiClick={sid,eid->guarded{latestNavigation.onBangumiClick(sid,eid)}},onTopicClick={guarded{latestNavigation.onTopicClick(it)}},
        onLiveClick={id,title,uname->guarded{latestNavigation.onLiveClick(id,title,uname)}},
        onTopicKeywordClick=navigation.onTopicKeywordClick?.let{{value->guarded{latestNavigation.onTopicKeywordClick?.invoke(value)}}},
        onMusicClick=navigation.onMusicClick?.let{{value->guarded{latestNavigation.onMusicClick?.invoke(value)}}},
        onCollectionClick=navigation.onCollectionClick?.let{{id,mid,title,url->guarded{latestNavigation.onCollectionClick?.invoke(id,mid,title,url)}}},
        onCourseClick=navigation.onCourseClick?.let{{url,title->guarded{latestNavigation.onCourseClick?.invoke(url,title)}}},
        onArticleClick=navigation.onArticleClick?.let{{id,title->guarded{latestNavigation.onArticleClick?.invoke(id,title)}}},
        onDynamicDetailClick=navigation.onDynamicDetailClick?.let{{id->guarded{latestNavigation.onDynamicDetailClick?.invoke(id)}}},
        onUnfoldRelatedClick=navigation.onUnfoldRelatedClick?.let{{id->guarded{latestNavigation.onUnfoldRelatedClick?.invoke(id)}}},
        onPrimaryClickOverride=navigation.onPrimaryClickOverride?.let{{value->guarded{latestNavigation.onPrimaryClickOverride?.invoke(value)}}},
    )
    val uriHandler=remember(platform){object:UriHandler{override fun openUri(uri:String)=platform.openLink(uri)}}
    key(alive){CompositionLocalProvider(LocalDesktopDynamicCardBindings provides platform,LocalDynamicImagePreviewTextVisible provides previewText,LocalUriHandler provides uriHandler){
        DynamicCardV2(shown,SingletonImageLoader.get(LocalPlatformContext.current),DynamicCardActions(routes,interaction),
            DynamicCardPresentation(isDetail=isDetail,currentUserMid=capturedMid,likeOverride=likeOverride,
                detailImageLayout=detailImageLayoutOverride?:detailLayout))
        if(repost)RepostDialog(onDismiss={repost=false},onRepost={text,alsoComment,complete->
            if(owned())scope.launch{
                try{
                    operations.repost(shown.id_str,text)
                    ensureActive();if(!owned())return@launch
                    shown=applyDynamicForwardCountIncrement(listOf(shown),shown.id_str).single()
                    latestRepostConfirmed?.invoke(shown.id_str)?:latestUpdated(shown)
                    val syncComment=alsoComment&&text.isNotBlank()
                    val commentOk=!syncComment||operations.postSourceComment(shown,text)
                    ensureActive();if(owned()){show(if(!syncComment)"转发成功"else if(commentOk)"转发成功，已同步评论"else"转发成功，评论同步失败");complete(true);repost=false}
                }catch(cancelled:CancellationException){throw cancelled}catch(error:Exception){if(owned()){show(error.message?:"转发失败");complete(false)}}
            }
        })
        if(messageShare)DynamicShareToMessageDialog(shown,onDismiss={messageShare=false},onResult={_,message->show(message)})
        report?.let{action->DesktopOriginalDynamicReportDialog(action,{report=null},::show){target,reason,desc,complete->
            if(owned())scope.launch{try{operations.report(target,reason,desc);if(owned())complete(true,"已提交举报")}catch(cancelled:CancellationException){throw cancelled}catch(error:Exception){if(owned())complete(false,error.message?:"举报失败")}}
        }}
        feedback?.takeIf(String::isNotBlank)?.let{message->AppAlertDialog(onDismissRequest={feedback=null},text={AppText(message)},confirmButton={AppDialogAction(onClick={feedback=null}){AppText("确定")}})}
    }}
}
