from pathlib import Path
import hashlib,importlib.util,json,os,textwrap,sys
sys.dont_write_bytecode=True
P=Path(__file__).resolve().parent;M=P.parents[2];C=M.parent/'BiliPai-v023';ST=P.parent/'stable-original-story-pager-root-parity'
def wide(p):
 s=str(p);return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+os.path.abspath(s))
def read(p):return wide(p).read_text(encoding='utf8').replace('\r\n','\n')
def sha(t):return hashlib.sha256(t.encode()).hexdigest()
def write(p,t):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_text(t,encoding='utf8',newline='\n')
H=[];targets=[]
def change(rel,edits,base=None):
 before=read(C/rel) if base is None else base;t=before
 write(P/'baseline'/rel,before)
 for name,b,a in edits:
  assert t.count(b)==1,(rel,name,t.count(b));t=t.replace(b,a)
  H.append(dict(path=rel,name=name,before=b,after=a,beforeSha256LF=sha(b),afterSha256LF=sha(a)))
 write(P/'prepared/existing'/rel,t);targets.append(dict(path=rel,beforeSha256LF=sha(before),afterSha256LF=sha(t)))
 return t

# This exact original dispatcher is the sole message parser consumer. No regex,
# URL deconstruction or flattened video DTO is introduced by Windows routing.
origin='app/src/main/java/com/android/purebilibili/navigation/AppNavigation.kt';raw=read(C/origin)
a=raw.index('        fun openMessageLinkInNavigation3(rawLink: String) {');z=raw.index('        LaunchedEffect(pendingVideoId)',a)
selection=textwrap.dedent(raw[a:z]).rstrip()+'\n';body=selection
replacements=[('fun openMessageLinkInNavigation3(rawLink: String)', 'internal fun desktopOriginalOpenMessageLink(rawLink: String, commands: DesktopOriginalRootRouteCommands, sourceRoute: String)'),
('navigateToVideoInNavigation3(action.videoId, 0L, "")','commands.video(BiliPaiNavKey.VideoDetail(action.videoId, 0L, "", sourceRoute=sourceRoute))'),
('pushNavigation3Key(', 'commands.push('),('navigateToVideoRouteInNavigation3(', 'commands.videoRoute('),('sourceRoute = currentRoute','sourceRoute = sourceRoute'),
('pushNavigation3Route(it)','commands.push(legacyRouteToBiliPaiNavKey(it))')]
for b,a in replacements:
 assert b in body,b;body=body.replace(b,a)
write(P/'prepared/manual/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalMessageLinkNavigation.kt','''package com.bilipai.desktop.ui
import androidx.compose.runtime.staticCompositionLocalOf
import com.android.purebilibili.navigation.*
import com.android.purebilibili.navigation3.*

/** Actual Root entry-scoped original AppNavigation message actions. */
internal val LocalDesktopOriginalMessageLinkNavigation = staticCompositionLocalOf<(String) -> Unit> {
    error("Original message links require the actual retained Root route")
}

'''+body)
inverse=body
for b,a in reversed(replacements):inverse=inverse.replace(a,b)
assert inverse==selection
write(P/'original-message-dispatch.kt.txt',selection)
write(P/'message-inverse.json',json.dumps(dict(passed=True,origin=origin,sourceSha256LF=sha(raw),selectedSha256LF=sha(selection),edits=replacements,parserReused=True),ensure_ascii=False,indent=2)+'\n')

change('desktop/src/main/kotlin/com/bilipai/desktop/ui/CommunityScreens.kt',[
('mandatory-typed-original-message-port','    val onDynamicRoute: (DesktopDynamicDetailRoute) -> Unit = { onDynamic(it.dynamicId) })','    val onDynamicRoute: (DesktopDynamicDetailRoute) -> Unit = { onDynamic(it.dynamicId) },\n    val onMessageLink: (String) -> Unit)'),
('actual-root-link-provider','    val navigation = CommunityNavigation(onVideo, onUser, onArticle, onLogin, onLive, onBangumi,','    val messageLink = LocalDesktopOriginalMessageLinkNavigation.current\n    val navigation = CommunityNavigation(onVideo, onUser, onArticle, onLogin, onLive, onBangumi,'),
('preserve-full-actions-in-content','        onDynamicBack={dynamicDetail=null},onDynamicRoute={dynamicDetail=it})','        onDynamicBack={dynamicDetail=null},onDynamicRoute={dynamicDetail=it},onMessageLink=messageLink)'),
('one-original-parser-only',read(C/'desktop/src/main/kotlin/com/bilipai/desktop/ui/CommunityScreens.kt').split('internal fun navigateCommunityUrl(',1)[1],'''raw: String, navigation: CommunityNavigation) {
    val url = imageUrl(raw.trim())
    // Topic rich text uses its already installed original topic policy. Every
    // other message target is parsed by the sole original AppNavigation policy.
    val uri = runCatching { URI(url) }.getOrNull()
    val host = uri?.host?.lowercase().orEmpty()
    if (uri?.scheme in setOf("https", "http") && (host == "bilibili.com" || host.endsWith(".bilibili.com")) &&
            uri?.path.orEmpty().contains("topic", ignoreCase=true)) {
        com.android.purebilibili.feature.dynamic.components.resolveDynamicRichTextTopicId(
            com.android.purebilibili.data.model.response.RichTextNode(jump_url=url)
        )?.let { navigation.onTopic(it); return }
    }
    navigation.onMessageLink(url)
}
''')])
change('desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopSpaceScreens.kt',[
('space-shares-original-message-router','    val navigation = CommunityNavigation(onVideo, onUser, onArticle, onLogin, onLive, onBangumi, onDynamic, onTopic, onTopicKeyword)',
'    val messageLink = LocalDesktopOriginalMessageLinkNavigation.current\n    val navigation = CommunityNavigation(onVideo, onUser, onArticle, onLogin, onLive, onBangumi, onDynamic, onTopic, onTopicKeyword, onMessageLink=messageLink)')])

bindings='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalCommentRootBindings.kt'
change(bindings,[('sole-shared-root-comment-owner','@Composable internal fun DesktopOriginalCommentRootBindings(','''internal val LocalDesktopOriginalCommentRootOwner = staticCompositionLocalOf<DesktopOriginalCommentRootOwner> {
    error("Original standalone comment requires the same Root comment owner")
}

@Composable internal fun DesktopOriginalCommentRootBindings('''),
('publish-the-existing-owner-only','if (owned()) CompositionLocalProvider(LocalDesktopCommentBindings provides platform,','if (owned()) CompositionLocalProvider(LocalDesktopOriginalCommentRootOwner provides ownerBindings,\n            LocalDesktopCommentBindings provides platform,')])

# The shared comment binding must be stable even before/without video resources.
# It stays above both content() and the eventual video assembler; no API/client
# or comment owner is created by an individual standalone navigation destination.
mount='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoReadyRootMount.kt'
change(mount,[('borrow-the-root-comment-owner',read(C/mount).split(') {\n',1)[1],'''    val comments = LocalDesktopOriginalCommentRootOwner.current
    val commentPlatform = LocalDesktopCommentBindings.current
    val assembler = remember(environment, shell, resources, comments, commentPlatform) {
        DesktopOriginalVideoRootAssembler(environment, shell, resources, comments, commentPlatform)
    }
    DesktopOriginalVideoShellMount(environment, shell, { assembler.factory },
        { _, owner -> assembler.Platforms(owner) }, assembler::afterDrain,
        assembler::afterUnconstructedDrain, content)
}
''')])

shell='desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt'
base=read(C/shell)
for h in json.loads(read(ST/'install-hunks.json')):
 if h['path']==shell:
  if h['after'] in base:continue
  assert base.count(h['before'])==1
  base=base.replace(h['before'],h['after'])
change(shell,[('stable-root-comment-owner-before-media','''                        val appResources = ordinaryVideoResources
                        if (appResources == null) content() else {''','''                        val appResources = ordinaryVideoResources
                        DesktopOriginalCommentRootBindings(environment.repository, community, commentFraud,
                            environment.root, environment::owns, Modifier.fillMaxSize(),
                            borrowedImageAssets=environment.gallery.imageAssets) { _ ->
                        if (appResources == null) content() else {'''),
('close-stable-comment-provider','''                            DesktopOriginalVideoReadyRootMount(environment,ordinaryVideo,shellResources,content)
                        }
                    }, discovery,community''','''                            DesktopOriginalVideoReadyRootMount(environment,ordinaryVideo,shellResources,content)
                        }
                        }
                    }, discovery,community'''),
('captured-entry-original-message-router','''                    CompositionLocalProvider(LocalDesktopDetailForeground provides (active&&hostVisible&&hostDisplayable)) {''','''                    val messageRoutes = commands as DesktopOriginalRootRouteAssembly
                    val messageLink: (String) -> Unit = { raw ->
                        if (active) messageRoutes.callbackFor(entryKey) {
                            desktopOriginalOpenMessageLink(raw, commands, entryKey.toLegacyRoute())
                        }
                    }
                    CompositionLocalProvider(LocalDesktopDetailForeground provides (active&&hostVisible&&hostDisplayable),
                        LocalDesktopOriginalMessageLinkNavigation provides messageLink) {'''),
('native-standalone-comment-detail-leaf','''                            entryKey is BiliPaiNavKey.VideoDetail || entryKey is BiliPaiNavKey.AudioMode ->''','''                            entryKey is BiliPaiNavKey.CommentDetail ->
                                DesktopDetailWindow { DesktopOriginalCommentDetailRootHost(entryKey, messageRoutes, active) }
                            entryKey is BiliPaiNavKey.VideoDetail || entryKey is BiliPaiNavKey.AudioMode ->'''),
('topic-shares-original-typed-link-actions','''                                    onDynamicRoute=::openDynamicRoute),''','''                                    onDynamicRoute=::openDynamicRoute,onMessageLink=messageLink),'''),
('actual-comment-section-selection','''    BiliPaiNavKey.Inbox,BiliPaiNavKey.ReplyMe,BiliPaiNavKey.AtMe,BiliPaiNavKey.LikeMe,BiliPaiNavKey.SystemNotice,is BiliPaiNavKey.Chat -> DesktopSection.MESSAGES''','''    BiliPaiNavKey.Inbox,BiliPaiNavKey.ReplyMe,BiliPaiNavKey.AtMe,BiliPaiNavKey.LikeMe,BiliPaiNavKey.SystemNotice,is BiliPaiNavKey.Chat,is BiliPaiNavKey.CommentDetail -> DesktopSection.MESSAGES''')],base)

host='''package com.bilipai.desktop.ui
import androidx.compose.runtime.*
import com.android.purebilibili.feature.comment.*
import com.android.purebilibili.feature.video.viewmodel.DesktopVideoCommentRequests
import com.android.purebilibili.data.model.CommentFraudStatus
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.navigation3.BiliPaiNavKey
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/** Complete original CommentDetail UI/VM on one actual NavEntry, independent
 * of the ordinary playback Assembly. All protocols/assets/settings/records are
 * borrowed from the already mounted same Root owner. */
@Composable internal fun DesktopOriginalCommentDetailRootHost(
    key: BiliPaiNavKey.CommentDetail,
    routes: DesktopOriginalRootRouteAssembly,
    active: Boolean,
) {
    val shared = LocalDesktopOriginalCommentRootOwner.current
    val messageLink = LocalDesktopOriginalMessageLinkNavigation.current
    val gate = routes.root.entry.gate
    androidx.compose.runtime.key(key, shared, gate) {
        val open = remember { AtomicBoolean(true) }
        val scope = remember { CoroutineScope(shared.scope.coroutineContext + SupervisorJob(shared.scope.coroutineContext[Job])) }
        fun owns() = open.get() && scope.isActive && shared.isOwned() && gate.owns() && routes.containsEntry(key)
        fun commit(action: () -> Unit): Boolean {
            var applied = false
            return gate.commit { if (owns()) { action(); applied=true } } && applied
        }
        val requests = remember(shared, scope) { DesktopOriginalCommentEntryRequests(shared.requests, ::owns, ::commit) }
        val detail = remember(scope, requests) { CommentDetailViewModel(scope,requests,::commit) }
        LaunchedEffect(scope, routes, key) {
            snapshotFlow { routes.containsEntry(key) }.collect { retained ->
                if (!retained) scope.cancel("Original comment NavEntry removed")
            }
        }
        DisposableEffect(scope) { onDispose { open.set(false);scope.cancel() } }
        if (owns()) CommentDetailScreen(oid=key.oid,rootId=key.rootId,targetId=key.targetId,
            type=key.type,enterUri=key.enterUri,
            onBack={ if(active) routes.callbackFor(key) { routes.back() } },
            onOpenLink=messageLink,
            onUserClick={ mid -> if(active) routes.callbackFor(key) { routes.push(BiliPaiNavKey.Space(mid)) } },
            viewModel=detail,requests=requests,isCurrentPage=active && routes.currentKey==key,
            admitUiAction={ action ->
                var admitted=false
                commit { if(active && routes.currentKey==key) { action();admitted=true } } && admitted
            })
    }
}

/** Every delegate call uses this entry's real caller Job. The shared protocol
 * still supplies its existing captured account/epoch/HTTP admission; Kotlin
 * interface delegation does not substitute its internal isOwned implementation.
 * Both short entry checks stay outside network/body/file waits. */
internal class DesktopOriginalCommentEntryRequests(
    private val delegate: DesktopVideoCommentRequests,
    private val entryOwned: () -> Boolean,
    private val commitEntry: ((() -> Unit) -> Boolean),
) : DesktopVideoCommentRequests {
    override fun isOwned() = entryOwned() && delegate.isOwned()
    private fun checkEntry() {
        var admitted=false
        if (!commitEntry { if (isOwned()) admitted=true } || !admitted)
            throw CancellationException("Original comment entry retired")
    }
    private suspend fun <T> request(action:suspend()->T):T {
        currentCoroutineContext().ensureActive();checkEntry()
        val result=action()
        currentCoroutineContext().ensureActive();checkEntry()
        return result
    }
    override fun currentMid():Long {
        var mid=0L
        checkEntry()
        if (!commitEntry { if (!isOwned()) throw CancellationException("Original comment account retired");mid=delegate.currentMid() })
            throw CancellationException("Original comment account retired")
        return mid
    }
    override suspend fun getCommentsForSubject(oid:Long,type:Int,page:Int,ps:Int,mode:Int,paginationOffset:String?,fallbackOnMissingLocation:Boolean)=request { delegate.getCommentsForSubject(oid,type,page,ps,mode,paginationOffset,fallbackOnMissingLocation) }
    override suspend fun getSortedSubCommentsForSubject(oid:Long,type:Int,rootId:Long,mode:Int,paginationOffset:String?,targetReplyId:Long)=request { delegate.getSortedSubCommentsForSubject(oid,type,rootId,mode,paginationOffset,targetReplyId) }
    override suspend fun getDialogCommentsForSubject(oid:Long,type:Int,rootId:Long,dialogId:Long,page:Int,paginationOffset:String?)=request { delegate.getDialogCommentsForSubject(oid,type,rootId,dialogId,page,paginationOffset) }
    override suspend fun uploadCommentPicture(source:String,index:Int)=request { delegate.uploadCommentPicture(source,index) }
    override suspend fun addCommentForSubject(oid:Long,type:Int,message:String,root:Long,parent:Long,pictures:List<ReplyPicture>,syncToDynamic:Boolean)=request { delegate.addCommentForSubject(oid,type,message,root,parent,pictures,syncToDynamic) }
    override suspend fun likeCommentForSubject(oid:Long,type:Int,rpid:Long,like:Boolean)=request { delegate.likeCommentForSubject(oid,type,rpid,like) }
    override suspend fun hateCommentForSubject(oid:Long,type:Int,rpid:Long,hate:Boolean)=request { delegate.hateCommentForSubject(oid,type,rpid,hate) }
    override suspend fun deleteCommentForSubject(oid:Long,type:Int,rpid:Long)=request { delegate.deleteCommentForSubject(oid,type,rpid) }
    override suspend fun setCommentTopForSubject(oid:Long,type:Int,rpid:Long,isCurrentlyTop:Boolean)=request { delegate.setCommentTopForSubject(oid,type,rpid,isCurrentlyTop) }
    override suspend fun reportCommentForSubject(oid:Long,type:Int,rpid:Long,reason:Int,content:String)=request { delegate.reportCommentForSubject(oid,type,rpid,reason,content) }
    override suspend fun checkCommentStatus(aid:Long,rpid:Long,rootId:Long,hasPictures:Boolean,sentAtSeconds:Long,waitMs:Long)=request { delegate.checkCommentStatus(aid,rpid,rootId,hasPictures,sentAtSeconds,waitMs) }
    override suspend fun saveFraudRecord(rpid:Long,oid:Long,type:Int,root:Long,message:String,status:CommentFraudStatus,initialStatus:CommentFraudStatus?)=request { delegate.saveFraudRecord(rpid,oid,type,root,message,status,initialStatus) }
}
'''
write(P/'prepared/manual/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalCommentDetailRoot.kt',host)

# Append only two local post-adaptation hooks to the existing sole BGM producer.
# No second Screen/VM/MessageFeedError identity or output facade.
vmout='com/android/purebilibili/feature/comment/CommentDetailViewModel.kt';uiout='com/android/purebilibili/feature/comment/CommentDetailScreen.kt'
changes={vmout:[('''    private val requests: DesktopVideoCommentRequests,
) {''','''    private val requests: DesktopVideoCommentRequests,
    private val commitState: ((() -> Unit) -> Boolean) = { action ->
        if (requests.isOwned()) { action(); true } else false
    },
) {
    private fun <T> MutableStateFlow(initial: T): kotlinx.coroutines.flow.MutableStateFlow<T> =
        com.bilipai.desktop.ui.DesktopHomeOwnedMutableStateFlow(initial, commitState)''')],
uiout:[('    requests: DesktopVideoCommentRequests\n','    requests: DesktopVideoCommentRequests,\n    isCurrentPage: Boolean = true,\n    admitUiAction: ((() -> Unit) -> Boolean) = { action -> action(); true },\n'),
('    val subReplyState by viewModel.subReplyState.collectAsState()','    fun uiAction(action: () -> Unit) { if (isCurrentPage) admitUiAction(action) }\n    val subReplyState by viewModel.subReplyState.collectAsState()'),
('    LaunchedEffect(fraudResult) {','    LaunchedEffect(fraudResult, isCurrentPage) {\n        if (!isCurrentPage) return@LaunchedEffect'),
('        if (shouldShowCommentFraudResultDialog(result.status)) {','        if (isCurrentPage && shouldShowCommentFraudResultDialog(result.status)) {'),
('LocalNavigationBackHandler(enabled = true)','LocalNavigationBackHandler(enabled = isCurrentPage)'),
('if (showImagePreview && previewImages.isNotEmpty())','if (isCurrentPage && showImagePreview && previewImages.isNotEmpty())'),
('                visible = showCommentInput,','                visible = isCurrentPage && showCommentInput,'),
('                            viewModel.loadInitial(\n','                            uiAction { viewModel.loadInitial(\n'),
('                                targetReplyId = targetId\n                            )','                                targetReplyId = targetId\n                            ) }'),
('onLoadMore = viewModel::loadMore','onLoadMore = { uiAction(viewModel::loadMore) }'),
('onSortModeChange = viewModel::setSortMode','onSortModeChange = { mode -> uiAction { viewModel.setSortMode(mode) } }'),
('onRootCommentClick = { viewModel.showReplyInput(rootReply) }','onRootCommentClick = { uiAction { viewModel.showReplyInput(rootReply) } }'),
('onReplyClick = { reply -> viewModel.showReplyInput(reply) }','onReplyClick = { reply -> uiAction { viewModel.showReplyInput(reply) } }'),
('onConversationClick = viewModel::openConversation','onConversationClick = { reply -> uiAction { viewModel.openConversation(reply) } }'),
('onConversationBack = viewModel::closeConversation','onConversationBack = { uiAction(viewModel::closeConversation) }'),
('onDissolveStart = viewModel::startDissolve','onDissolveStart = { id -> uiAction { viewModel.startDissolve(id) } }'),
('onDeleteComment = viewModel::deleteComment','onDeleteComment = { id -> uiAction { viewModel.deleteComment(id) } }'),
('onCheckCommentFraud = if (type == 1) viewModel::checkCommentFraud else null','onCheckCommentFraud = if (type == 1) { reply -> uiAction { viewModel.checkCommentFraud(reply) } } else null'),
('onCommentLike = viewModel::likeComment','onCommentLike = { id -> uiAction { viewModel.likeComment(id) } }'),
('onCommentHate = viewModel::hateComment','onCommentHate = { id -> uiAction { viewModel.hateComment(id) } }'),
('onDismiss = viewModel::hideReplyInput','onDismiss = { uiAction(viewModel::hideReplyInput) }'),
('                    viewModel.sendReply(text, uris, sync)','                    uiAction { viewModel.sendReply(text, uris, sync) }'),
('onDismiss = viewModel::dismissFraudResult','onDismiss = { uiAction(viewModel::dismissFraudResult) }'),
('{ viewModel.startDissolve(result.rpid) }','{ uiAction { viewModel.startDissolve(result.rpid) } }'),
('            viewModel.closeConversation()','            uiAction(viewModel::closeConversation)'),
('''        resolveCommentFraudLightMessage(result.status)?.let { message ->
            platform.showFeedback(message)
            viewModel.dismissFraudResult()
        }''','''        resolveCommentFraudLightMessage(result.status)?.let { message -> uiAction {
            platform.showFeedback(message)
            viewModel.dismissFraudResult()
        } }'''),
('''                        onImagePreview = { images, index, rect, textContent ->
                            previewImages = images
                            previewInitialIndex = index
                            previewSourceRect = rect
                            previewTextContent = textContent
                            showImagePreview = true
                        },''','''                        onImagePreview = { images, index, rect, textContent -> uiAction {
                            previewImages = images
                            previewInitialIndex = index
                            previewSourceRect = rect
                            previewTextContent = textContent
                            showImagePreview = true
                        } },'''),
('''                    onDismiss = {
                        showImagePreview = false
                        previewTextContent = null
                    }''','''                    onDismiss = { uiAction {
                        showImagePreview = false
                        previewTextContent = null
                    } }'''),
]}
function='\ndef comment_detail_root_entry_delta(relative,body):\n'
for out,edits in changes.items():
 function+=' if relative=='+repr(out)+':\n';t=read(C/'desktop/build/generated/bgm-detail'/out)
 for b,a in edits:
  assert t.count(b)==1,(out,b,t.count(b));t=t.replace(b,a)
  function+='  before='+repr(b)+'\n  after='+repr(a)+'\n  assert body.count(before)==1\n  body=body.replace(before,after)\n'
 write(P/'reference'/out,t)
function+=' return body\n\n'
producer='desktop/tools/extract-upstream-bgm-detail.py'
change(producer,[('sole-original-comment-entry-adaptation','def generate(repo,output):',function+'def generate(repo,output):'),
('route-owned-original-state-publication','    def emit(relative,body):\n','    def emit(relative,body):\n        body=comment_detail_root_entry_delta(relative,body)\n')])
for sibling in ['extract-upstream-dynamic-reply-protocol.py','extract-upstream-dynamic-reply.py']:
 write(P/'prepared/existing/desktop/tools'/sibling,read(C/'desktop/tools'/sibling))
spec=importlib.util.spec_from_file_location('prospective_bgm',wide(P/'prepared/existing'/producer));mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod);out=wide(P/'replay');mod.generate(C,out)
for f in out.rglob('*.kt'):
 rel=str(f.relative_to(out)).replace('\\','/')
 if rel in changes:assert read(f)==read(P/'reference'/rel)
 else:assert read(f)==read(C/'desktop/build/generated/bgm-detail'/rel),(rel,'unexpected output')
write(P/'replay-receipt.json',json.dumps(dict(passed=True,soleProducer=producer,onlyChangedOutputs=list(changes),otherOutputsByteUnchanged=True,newOriginalIdentities=0,productionWrites=0),indent=2)+'\n')
write(P/'exact-hunks.json',json.dumps(H,ensure_ascii=False,indent=2)+'\n');write(P/'targets.json',json.dumps(targets,indent=2)+'\n')
print('Prepared',len(H),'exact edits /',len(targets),'families; 2new manuals; 0 new client/model/player')
