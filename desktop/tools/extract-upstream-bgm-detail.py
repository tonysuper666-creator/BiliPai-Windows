"""Original stable BGM detail protocol/state/UI, with desktop lifetime seams only."""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse, hashlib, importlib.util, json, re, textwrap
from v029_brand_consumers import empty_consumer
import v029_comment_search as comment_search
from v033_comment_refresh import apply_selected as apply_v033_comment_refresh
BASE='app/src/main/java/com/android/purebilibili/'
BGM=BASE+'feature/audio/bgm/'
SOURCES=[BGM+n+'.kt' for n in ['BgmDetailViewModel','BgmDetailPolicy','BgmHeatChart','BgmDetailScreen']]+[BASE+n+'.kt' for n in ['data/repository/ViewGrpcRepository','data/model/response/PlayerInfoResponse','core/network/ApiClient','navigation/AppNavigation','core/util/BilibiliNavigationTargetParser','feature/video/viewmodel/VideoCommentViewModel','feature/video/ui/components/CommentInputDialog','feature/video/ui/components/CommentSortFilterBar','data/repository/CommentFraudDetectionPolicy','data/model/CommentFraudStatus','core/database/entity/CommentFraudRecord','core/database/dao/CommentFraudDao','data/repository/CommentFraudRepository']]
def module(p,name):
    spec=importlib.util.spec_from_file_location(name,p);obj=importlib.util.module_from_spec(spec);spec.loader.exec_module(obj);return obj
SOURCES += [BASE+n+".kt" for n in ["feature/video/ui/section/VideoInfoSection","core/ui/LocalNavigationBackHandler","feature/video/ui/components/VideoCardSkeleton","feature/video/ui/components/SkeletonComponents","feature/video/ui/VideoDetailShapes","feature/video/ui/components/RelatedVideoItem"]]
SOURCES += [BASE+n+".kt" for n in ["feature/comment/CommentDetailScreen","feature/comment/CommentDetailViewModel","feature/video/ui/components/CommentFraudDialog","feature/message/feed/MessageFeedCommon","data/repository/CommentRepository"]]
SOURCES += [BASE+n+".kt" for n in ["feature/video/ui/components/CommentMentionInsertPolicy","feature/video/viewmodel/CommentPaginationPolicy","core/ui/LottieComponents"]]
SOURCES += ["design-system/src/main/java/com/android/purebilibili/core/ui/motion/VerticalContentRevealMotionPolicy.kt"]
def replace_once(text,old,new):
    assert text.count(old)==1,(old,text.count(old));return text.replace(old,new)

def comment_detail_root_entry_delta(relative,body):
 if relative=='com/android/purebilibili/feature/comment/CommentDetailViewModel.kt':
  before='    private val requests: DesktopVideoCommentRequests,\n) {'
  after='    private val requests: DesktopVideoCommentRequests,\n    private val commitState: ((() -> Unit) -> Boolean) = { action ->\n        if (requests.isOwned()) { action(); true } else false\n    },\n) {\n    private fun <T> MutableStateFlow(initial: T): kotlinx.coroutines.flow.MutableStateFlow<T> =\n        com.bilipai.desktop.ui.DesktopHomeOwnedMutableStateFlow(initial, commitState)'
  assert body.count(before)==1
  body=body.replace(before,after)
 if relative=='com/android/purebilibili/feature/comment/CommentDetailScreen.kt':
  before='    requests: DesktopVideoCommentRequests\n'
  after='    requests: DesktopVideoCommentRequests,\n    isCurrentPage: Boolean = true,\n    admitUiAction: ((() -> Unit) -> Boolean) = { action -> action(); true },\n'
  assert body.count(before)==1
  body=body.replace(before,after)
  before='    val subReplyState by viewModel.subReplyState.collectAsState()'
  after='    fun uiAction(action: () -> Unit) { if (isCurrentPage) admitUiAction(action) }\n    val subReplyState by viewModel.subReplyState.collectAsState()'
  assert body.count(before)==1
  body=body.replace(before,after)
  before='    LaunchedEffect(fraudResult) {'
  after='    LaunchedEffect(fraudResult, isCurrentPage) {\n        if (!isCurrentPage) return@LaunchedEffect'
  assert body.count(before)==1
  body=body.replace(before,after)
  before='        if (shouldShowCommentFraudResultDialog(result.status)) {'
  after='        if (isCurrentPage && shouldShowCommentFraudResultDialog(result.status)) {'
  assert body.count(before)==1
  body=body.replace(before,after)
  before='LocalNavigationBackHandler(enabled = true)'
  after='LocalNavigationBackHandler(enabled = isCurrentPage)'
  assert body.count(before)==1
  body=body.replace(before,after)
  before='if (showImagePreview && previewImages.isNotEmpty())'
  after='if (isCurrentPage && showImagePreview && previewImages.isNotEmpty())'
  assert body.count(before)==1
  body=body.replace(before,after)
  before='                visible = showCommentInput,'
  after='                visible = isCurrentPage && showCommentInput,'
  assert body.count(before)==1
  body=body.replace(before,after)
  before='                            viewModel.loadInitial(\n'
  after='                            uiAction { viewModel.loadInitial(\n'
  assert body.count(before)==1
  body=body.replace(before,after)
  before='                                targetReplyId = targetId\n                            )'
  after='                                targetReplyId = targetId\n                            ) }'
  assert body.count(before)==1
  body=body.replace(before,after)
  before='onLoadMore = viewModel::loadMore'
  after='onLoadMore = { uiAction(viewModel::loadMore) }'
  assert body.count(before)==1
  body=body.replace(before,after)
  before='onSortModeChange = viewModel::setSortMode'
  after='onSortModeChange = { mode -> uiAction { viewModel.setSortMode(mode) } }'
  assert body.count(before)==1
  body=body.replace(before,after)
  before='onRootCommentClick = { viewModel.showReplyInput(rootReply) }'
  after='onRootCommentClick = { uiAction { viewModel.showReplyInput(rootReply) } }'
  assert body.count(before)==1
  body=body.replace(before,after)
  before='onReplyClick = { reply -> viewModel.showReplyInput(reply) }'
  after='onReplyClick = { reply -> uiAction { viewModel.showReplyInput(reply) } }'
  assert body.count(before)==1
  body=body.replace(before,after)
  before='onConversationClick = viewModel::openConversation'
  after='onConversationClick = { reply -> uiAction { viewModel.openConversation(reply) } }'
  assert body.count(before)==1
  body=body.replace(before,after)
  before='onConversationBack = viewModel::closeConversation'
  after='onConversationBack = { uiAction(viewModel::closeConversation) }'
  assert body.count(before)==1
  body=body.replace(before,after)
  before='onDissolveStart = viewModel::startDissolve'
  after='onDissolveStart = { id -> uiAction { viewModel.startDissolve(id) } }'
  assert body.count(before)==1
  body=body.replace(before,after)
  before='onDeleteComment = viewModel::deleteComment'
  after='onDeleteComment = { id -> uiAction { viewModel.deleteComment(id) } }'
  assert body.count(before)==1
  body=body.replace(before,after)
  before='onCheckCommentFraud = if (type == 1) viewModel::checkCommentFraud else null'
  after='onCheckCommentFraud = if (type == 1) { reply -> uiAction { viewModel.checkCommentFraud(reply) } } else null'
  assert body.count(before)==1
  body=body.replace(before,after)
  before='onCommentLike = viewModel::likeComment'
  after='onCommentLike = { id -> uiAction { viewModel.likeComment(id) } }'
  assert body.count(before)==1
  body=body.replace(before,after)
  before='onCommentHate = viewModel::hateComment'
  after='onCommentHate = { id -> uiAction { viewModel.hateComment(id) } }'
  assert body.count(before)==1
  body=body.replace(before,after)
  before='onDismiss = viewModel::hideReplyInput'
  after='onDismiss = { uiAction(viewModel::hideReplyInput) }'
  assert body.count(before)==1
  body=body.replace(before,after)
  before='                    viewModel.sendReply(text, uris, sync)'
  after='                    uiAction { viewModel.sendReply(text, uris, sync) }'
  assert body.count(before)==1
  body=body.replace(before,after)
  before='onDismiss = viewModel::dismissFraudResult'
  after='onDismiss = { uiAction(viewModel::dismissFraudResult) }'
  assert body.count(before)==1
  body=body.replace(before,after)
  before='{ viewModel.startDissolve(result.rpid) }'
  after='{ uiAction { viewModel.startDissolve(result.rpid) } }'
  assert body.count(before)==1
  body=body.replace(before,after)
  before='            viewModel.closeConversation()'
  after='            uiAction(viewModel::closeConversation)'
  assert body.count(before)==1
  body=body.replace(before,after)
  before='        resolveCommentFraudLightMessage(result.status)?.let { message ->\n            platform.showFeedback(message)\n            viewModel.dismissFraudResult()\n        }'
  after='        resolveCommentFraudLightMessage(result.status)?.let { message -> uiAction {\n            platform.showFeedback(message)\n            viewModel.dismissFraudResult()\n        } }'
  assert body.count(before)==1
  body=body.replace(before,after)
  before='                        onImagePreview = { images, index, rect, textContent ->\n                            previewImages = images\n                            previewInitialIndex = index\n                            previewSourceRect = rect\n                            previewTextContent = textContent\n                            showImagePreview = true\n                        },'
  after='                        onImagePreview = { images, index, rect, textContent -> uiAction {\n                            previewImages = images\n                            previewInitialIndex = index\n                            previewSourceRect = rect\n                            previewTextContent = textContent\n                            showImagePreview = true\n                        } },'
  assert body.count(before)==1
  body=body.replace(before,after)
  before='                    onDismiss = {\n                        showImagePreview = false\n                        previewTextContent = null\n                    }'
  after='                    onDismiss = { uiAction {\n                        showImagePreview = false\n                        previewTextContent = null\n                    } }'
  assert body.count(before)==1
  body=body.replace(before,after)
 return body

def generate(repo,output):
    shared=module(Path(__file__).with_name('extract-upstream-dynamic-reply-protocol.py'),'bgm_identity')
    original,identities=shared.load_pinned_sources(repo,SOURCES)
    def emit(relative,body):
        body=comment_detail_root_entry_delta(relative,body)
        target=output/relative;v=str(target.absolute());safe=Path(v if v.startswith('\\\\?\\') else '\\\\?\\'+v)
        safe.parent.mkdir(parents=True,exist_ok=True);safe.write_text(body,encoding='utf-8',newline='\n');return target
    package='com/android/purebilibili/feature/audio/bgm/'
    changes=[]
    raw=original[BASE+'data/repository/ViewGrpcRepository.kt'];body=raw
    body=body.replace('import com.android.purebilibili.core.network.NetworkModule\n','import com.android.purebilibili.core.network.BilibiliApi\n')
    body=body.replace('import com.android.purebilibili.core.network.WbiKeyManager\n','').replace('import com.android.purebilibili.core.network.WbiUtils\n','')
    body=replace_once(body,'internal object ViewGrpcRepository {','internal class DesktopOriginalBgmRepository(\n    private val api: BilibiliApi,\n    private val sign: suspend (Map<String, String>) -> Map<String, String>,\n) {')
    body=replace_once(body,'                val (imgKey, subKey) = WbiKeyManager.getWbiKeys().getOrThrow()\n','')
    body=body.replace('NetworkModule.api.','api.');body=replace_once(body,'WbiUtils.sign(params, imgKey, subKey)','sign(params)')
    body=replace_once(body,'    private const val TAG = "BgmList"\n','')
    body=shared.drop_logs(body)
    emit('com/android/purebilibili/data/repository/DesktopOriginalBgmRepository.kt',body)
    changes.append(dict(source=BASE+'data/repository/ViewGrpcRepository.kt',strategy='full original object body; constructor receives sole existing API and repository signer; no new transport/cache/store',originalSha256=hashlib.sha256(raw.encode()).hexdigest(),generatedSha256=hashlib.sha256(body.encode()).hexdigest()))
    raw=original[BGM+'BgmDetailViewModel.kt'];body=raw
    for line in ['import androidx.lifecycle.ViewModel\n','import androidx.lifecycle.viewModelScope\n','import com.android.purebilibili.core.network.NetworkModule\n','import com.android.purebilibili.core.store.TokenManager\n','import com.android.purebilibili.data.repository.ViewGrpcRepository\n']:
        body=replace_once(body,line,'')
    body=replace_once(body,'import kotlinx.coroutines.Job\n','import kotlinx.coroutines.Job\nimport kotlinx.coroutines.CoroutineScope\n')
    body=replace_once(body,'internal class BgmDetailViewModel : ViewModel() {','internal class BgmDetailViewModel(\n    private val scope: CoroutineScope,\n    private val requests: DesktopBgmDetailRequests,\n) {\n    private inline fun updateOwnedState(block: (BgmDetailUiState) -> BgmDetailUiState) {\n        if (requests.isOwned()) _state.update { if (requests.isOwned()) block(it) else it }\n    }')
    body=body.replace('viewModelScope.launch','scope.launch').replace('ViewGrpcRepository.','requests.').replace('_state.update { it.copy','updateOwnedState { it.copy')
    body=replace_once(body,'        val csrf = TokenManager.csrfCache\n        if (TokenManager.sessDataCache.isNullOrBlank() || csrf.isNullOrBlank()) {','        if (!requests.hasLogin()) {')
    body=replace_once(body,'NetworkModule.api.updateBgmWish(id, if (detail.wishListen) 2 else 1, csrf)','requests.updateBgmWish(id, if (detail.wishListen) 2 else 1).getOrThrow()')
    emit(package+'BgmDetailViewModel.kt',body)
    changes.append(dict(source=BGM+'BgmDetailViewModel.kt',strategy='full original UI state and every method; Android ViewModelScope/TokenManager/API become required existing-owner request/scope seams; retired owner state admission',originalSha256=hashlib.sha256(raw.encode()).hexdigest(),generatedSha256=hashlib.sha256(body.encode()).hexdigest()))
    emit(package+'DesktopBgmDetailRequests.kt',"""package com.android.purebilibili.feature.audio.bgm

import com.android.purebilibili.data.model.response.*

/** Requests are implemented only by the current DesktopDynamicCardOperations owner. */
internal interface DesktopBgmDetailRequests {
    fun isOwned(): Boolean
    fun hasLogin(): Boolean
    suspend fun getBgmDetail(musicId: String, aid: Long = 0, cid: Long = 0): Result<BgmDetailData?>
    suspend fun getAllBgmRecommendVideos(musicId: String): Result<List<BgmRecommendVideo>>
    suspend fun updateBgmWish(musicId: String, state: Int): Result<SimpleApiResponse>
    suspend fun getEmotePackages(): Result<List<EmotePackage>>
}
""")
    for name in ['BgmDetailPolicy','BgmHeatChart']:
        emit(package+name+'.kt',original[BGM+name+'.kt'])
        changes.append(dict(source=BGM+name+'.kt',strategy='full original LF-identical file',originalSha256=hashlib.sha256(original[BGM+name+'.kt'].encode()).hexdigest(),generatedSha256=hashlib.sha256(original[BGM+name+'.kt'].encode()).hexdigest()))
    raw=original[BASE+'navigation/AppNavigation.kt'];mask=shared.masked(raw)
    matches=list(re.finditer(r'onBgmClick\s*=\s*\{\s*bgm\s*->',mask));assert len(matches)==1
    start=mask.index('{',matches[0].start());end=shared.balanced(mask,start,'{','}')
    body=textwrap.dedent(raw[mask.index('->',start)+2:end-1]).strip()
    body=replace_once(body,'pushNavigation3Key(BiliPaiNavKey.BgmDetail(musicId, cid = videoKey.cid))','return DesktopBgmMusicTarget.Detail(musicId, cid = cid)')
    body=replace_once(body,'pushNavigation3Key(BiliPaiNavKey.Web(bgm.jumpUrl, "发现音乐"))','return DesktopBgmMusicTarget.Web(bgm.jumpUrl)')
    emit('com/bilipai/desktop/audio/DesktopOriginalBgmRouting.kt',"""package com.bilipai.desktop.audio

import com.android.purebilibili.data.model.response.BgmInfo
import com.android.purebilibili.core.util.BilibiliNavigationTarget
import com.android.purebilibili.core.util.BilibiliNavigationTargetParser

/** Full original AppNavigation.onBgmClick decision, with Root route destinations. */
internal fun resolveOriginalBgmMusicTarget(bgm: BgmInfo, cid: Long): DesktopBgmMusicTarget? {
"""+textwrap.indent(body,'    ')+'\n    return null\n}\n')
    generate_ui(repo,output,original,shared,emit,changes)
    generate_bgm_discovery(original,shared,emit,changes)
    generate_comment_detail(original,shared,emit,changes)
    emit("com/android/purebilibili/feature/video/viewmodel/DesktopVideoCommentRequests.kt", 'package com.android.purebilibili.feature.video.viewmodel\n\nimport com.android.purebilibili.data.model.CommentFraudStatus\nimport com.android.purebilibili.data.model.response.*\nimport com.android.purebilibili.data.repository.DesktopOriginalCommentFraudRepository\nimport com.bilipai.desktop.data.DesktopDynamicCardOperations\nimport com.bilipai.desktop.data.DesktopRepository\nimport com.bilipai.desktop.ui.DesktopDynamicReplyOperationsBinding\nimport okhttp3.RequestBody\n\n/** Required typed-subject original operations. There is no dynamic/item surrogate. */\ninternal interface DesktopVideoCommentRequests {\n    fun isOwned(): Boolean\n    fun currentMid(): Long\n    suspend fun getCommentsForSubject(oid:Long,type:Int,page:Int,ps:Int=20,mode:Int=3,paginationOffset:String?=null,fallbackOnMissingLocation:Boolean=false):Result<ReplyData>\n    suspend fun getSortedSubCommentsForSubject(oid:Long,type:Int,rootId:Long,mode:Int,paginationOffset:String?=null,targetReplyId:Long=0):Result<ReplyData>\n    suspend fun getDialogCommentsForSubject(oid:Long,type:Int,rootId:Long,dialogId:Long,page:Int,paginationOffset:String?=null):Result<ReplyData>\n    suspend fun uploadCommentPicture(source:String,index:Int):Result<ReplyPicture>\n    suspend fun addCommentForSubject(oid:Long,type:Int,message:String,root:Long=0,parent:Long=0,pictures:List<ReplyPicture> = emptyList(),syncToDynamic:Boolean=false):Result<ReplyItem?>\n    suspend fun likeCommentForSubject(oid:Long,type:Int,rpid:Long,like:Boolean):Result<Unit>\n    suspend fun hateCommentForSubject(oid:Long,type:Int,rpid:Long,hate:Boolean):Result<Unit>\n    suspend fun deleteCommentForSubject(oid:Long,type:Int,rpid:Long):Result<Unit>\n    suspend fun setCommentTopForSubject(oid:Long,type:Int,rpid:Long,isCurrentlyTop:Boolean):Result<Unit>\n    suspend fun reportCommentForSubject(oid:Long,type:Int,rpid:Long,reason:Int,content:String=""):Result<Unit>\n    suspend fun checkCommentStatus(aid:Long,rpid:Long,rootId:Long=0,hasPictures:Boolean=false,sentAtSeconds:Long=0,waitMs:Long = -1):Result<CommentFraudStatus>\n    suspend fun saveFraudRecord(rpid:Long,oid:Long,type:Int=1,root:Long=0,message:String,status:CommentFraudStatus,initialStatus:CommentFraudStatus?=null)\n}\n\n/** Reuses the sole operations and the exact existing streaming picture binding. */\ninternal class DesktopVideoCommentOperationsBinding(\n    private val operations: DesktopDynamicCardOperations,\n    private val repository: DesktopRepository,\n    imageProvider: suspend(String)->Triple<String?,String?,RequestBody>,\n    private val records: DesktopOriginalCommentFraudRepository,\n    private val checkStatus: suspend(Long,Long,Long,Boolean,Long,Long)->Result<CommentFraudStatus>,\n    private val publishedRecord: (ReplyItem,Long,Int,Long,Long,String,Long)->Unit,\n):DesktopVideoCommentRequests {\n    private val images=DesktopDynamicReplyOperationsBinding(operations,\n        { isOwned() && repository.account.value != null && !repository.authCookies()["bili_jct"].isNullOrBlank() },\n        { Result.failure(IllegalStateException("A typed music subject has no DynamicItem detail")) },imageProvider)\n    override fun isOwned()=operations.isOwned()\n    override fun currentMid()=repository.account.value?.mid ?: 0L\n    override suspend fun getCommentsForSubject(oid:Long,type:Int,page:Int,ps:Int,mode:Int,paginationOffset:String?,fallbackOnMissingLocation:Boolean)=operations.getCommentsForSubject(oid,type,page,ps,mode,paginationOffset,fallbackOnMissingLocation)\n    override suspend fun getSortedSubCommentsForSubject(oid:Long,type:Int,rootId:Long,mode:Int,paginationOffset:String?,targetReplyId:Long)=operations.getSortedSubCommentsForSubject(oid,type,rootId,mode,paginationOffset,targetReplyId)\n    override suspend fun getDialogCommentsForSubject(oid:Long,type:Int,rootId:Long,dialogId:Long,page:Int,paginationOffset:String?)=operations.getDialogCommentsForSubject(oid,type,rootId,dialogId,page,paginationOffset)\n    override suspend fun uploadCommentPicture(source:String,index:Int)=images.uploadCommentPicture(source,index)\n    override suspend fun addCommentForSubject(oid:Long,type:Int,message:String,root:Long,parent:Long,pictures:List<ReplyPicture>,syncToDynamic:Boolean)=operations.addCommentForSubject(oid,type,message,root,parent,pictures,syncToDynamic,publishedRecord)\n    override suspend fun likeCommentForSubject(oid:Long,type:Int,rpid:Long,like:Boolean)=operations.likeCommentForSubject(oid,type,rpid,like)\n    override suspend fun hateCommentForSubject(oid:Long,type:Int,rpid:Long,hate:Boolean)=operations.hateCommentForSubject(oid,type,rpid,hate)\n    override suspend fun deleteCommentForSubject(oid:Long,type:Int,rpid:Long)=operations.deleteCommentForSubject(oid,type,rpid)\n    override suspend fun setCommentTopForSubject(oid:Long,type:Int,rpid:Long,isCurrentlyTop:Boolean)=operations.setCommentTopForSubject(oid,type,rpid,isCurrentlyTop)\n    override suspend fun reportCommentForSubject(oid:Long,type:Int,rpid:Long,reason:Int,content:String)=operations.reportCommentForSubject(oid,type,rpid,reason,content)\n    override suspend fun checkCommentStatus(aid:Long,rpid:Long,rootId:Long,hasPictures:Boolean,sentAtSeconds:Long,waitMs:Long)=checkStatus(aid,rpid,rootId,hasPictures,sentAtSeconds,waitMs)\n    override suspend fun saveFraudRecord(rpid:Long,oid:Long,type:Int,root:Long,message:String,status:CommentFraudStatus,initialStatus:CommentFraudStatus?)=records.saveRecord(rpid,oid,type,root,message=message,status=status,initialStatus=initialStatus)\n}\n')
    emote_members,emote_receipt = bgm_emote_members(repo)
    emit("bgm-operations-members.fragment", emote_members)
    emit("emote-source-inventory.json", json.dumps(emote_receipt,ensure_ascii=False,indent=2)+"\n")
    emit('bgm-source-identities.json',json.dumps(dict(sources=identities,sourceSelection=changes),ensure_ascii=False,indent=2)+'\n')
    emit('bgm-original-retained/AppNavigation.onBgmClick.kt',raw[matches[0].start():end])
    for path,text in original.items():emit('bgm-original-retained/'+path,text)
BGM_OPS_MEMBERS = '\n    // STABLE_ORIGINAL_BGM_MEMBERS\n    // Original ViewGrpcRepository BGM operations use this existing owner\'s API and WBI signer.\n    private val bgm = com.android.purebilibili.data.repository.DesktopOriginalBgmRepository(api,\n        { params -> assertOwned(); repository.signWebParams(params).also { assertOwned() } })\n    suspend fun getBgmList(aid: Long, bvid: String, cid: Long): Result<List<BgmInfo>> = result {\n        read { bgm.getBgmList(aid, bvid, cid).getOrThrow() }\n    }\n    suspend fun getBgmDetail(musicId: String, aid: Long = 0, cid: Long = 0): Result<BgmDetailData?> = result {\n        read { bgm.getBgmDetail(musicId, aid, cid).getOrThrow() }\n    }\n    suspend fun getBgmRecommendVideos(musicId: String, aid: Long, cid: Long, page: Int = 1, pageSize: Int = 5): Result<List<BgmRecommendVideo>> = result {\n        read { bgm.getBgmRecommendVideos(musicId, aid, cid, page, pageSize).getOrThrow() }\n    }\n    suspend fun getAllBgmRecommendVideos(musicId: String): Result<List<BgmRecommendVideo>> = result {\n        read { bgm.getAllBgmRecommendVideos(musicId).getOrThrow() }\n    }\n    suspend fun updateBgmWish(musicId: String, state: Int): Result<SimpleApiResponse> = result {\n        mutate { csrf -> api.updateBgmWish(musicId, state, csrf) }\n    }\n    suspend fun getBgmEmotePackages(): Result<List<EmotePackage>> = result { read {\n        val response = api.getEmotes(mutableMapOf("business" to "reply"))\n        if (response.code == 0) response.data?.packages ?: response.data?.all_packages ?: emptyList()\n        else throw Exception(response.message)\n    } }\n    suspend fun loadBgmEmptyAnimation(url: String): ByteArray = read {\n        require(url == com.android.purebilibili.core.ui.LottieUrls.EMPTY)\n        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->\n            val call = guestWeb.callFactory().newCall(okhttp3.Request.Builder().url(url).build())\n            continuation.invokeOnCancellation { call.cancel() }\n            call.enqueue(object : okhttp3.Callback {\n                override fun onFailure(call: okhttp3.Call, failure: IOException) {\n                    if (continuation.isActive) continuation.resumeWith(Result.failure(failure))\n                }\n                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {\n                    try {\n                        val bytes = response.use {\n                            assertOwned(); check(it.isSuccessful)\n                            val bytes = checkNotNull(it.body).byteStream().use { stream -> stream.readNBytes(32 * 1024 * 1024 + 1) }\n                            require(bytes.size <= 32 * 1024 * 1024); assertOwned(); bytes\n                        }\n                        if (continuation.isActive) continuation.resumeWith(Result.success(bytes))\n                    } catch (failed: Exception) { response.close(); if (continuation.isActive) continuation.resumeWith(Result.failure(failed)) }\n                }\n            })\n        }\n    }\n    fun bgmDetailRequests(): com.android.purebilibili.feature.audio.bgm.DesktopBgmDetailRequests =\n        object : com.android.purebilibili.feature.audio.bgm.DesktopBgmDetailRequests {\n            override fun isOwned() = this@DesktopDynamicCardOperations.isOwned()\n            override fun hasLogin() = isOwned() && repository.account.value != null && !repository.authCookies()["bili_jct"].isNullOrBlank()\n            override suspend fun getBgmDetail(musicId: String, aid: Long, cid: Long) = this@DesktopDynamicCardOperations.getBgmDetail(musicId, aid, cid)\n            override suspend fun getAllBgmRecommendVideos(musicId: String) = this@DesktopDynamicCardOperations.getAllBgmRecommendVideos(musicId)\n            override suspend fun updateBgmWish(musicId: String, state: Int) = this@DesktopDynamicCardOperations.updateBgmWish(musicId, state)\n            override suspend fun getEmotePackages() = this@DesktopDynamicCardOperations.getBgmEmotePackages()\n        }\n'

def inventory(repo):
    return [dict(path=p,mode='policy-extract',features=['stable-bgm-native-detail'],sha256=hashlib.sha256((_desktop_canonical_source(repo, p)).read_text(encoding='utf-8').replace('\r\n','\n').encode()).hexdigest()) for p in SOURCES]
def generate_ui(repo,output,original,shared,emit,changes):
    def retained(path,generated,strategy):
        changes.append(dict(source=path,strategy=strategy,originalSha256=hashlib.sha256(original[path].encode()).hexdigest(),generatedSha256=hashlib.sha256(generated.encode()).hexdigest()))
    def remove_declaration(text,name):
        mask=shared.masked(text)
        m=re.search(r'(?m)^[ \t]*(?:(?:internal|private|public|data|enum|suspend|inline)\s+)*(?:class|fun)\s+'+re.escape(name)+r'\b',mask);assert m,name
        if re.search(r'\bfun\b',m.group()):
            opening=mask.index('(',m.start());params=shared.balanced(mask,opening)
            opening=mask.index('{',params);end=shared.balanced(mask,opening,'{','}')
        else:
            opening=mask.index('(',m.start()) if name not in ['CommentSortMode'] else mask.index('{',m.start())
            end=shared.balanced(mask,opening,'{','}') if mask[opening]=='{' else shared.balanced(mask,opening)
            tail=mask[end:].lstrip();padding=len(mask[end:])-len(tail)
            if tail.startswith('{'):end=shared.balanced(mask,end+padding,'{','}')
        return text[:m.start()]+text[end:]
    path=BASE+'feature/video/viewmodel/VideoCommentViewModel.kt';body, comment_selection = comment_search.select_full(repo,path,original[path]); comment_raw=body
    body, v033_refresh_selection = apply_v033_comment_refresh(repo, path, body)
    comment_selection['v033SubReplyRefresh'] = v033_refresh_selection
    for name in ['CommentSortMode','SubReplyUiState','resolveSubReplyRemoteTotalCount','resolveSubReplyLoadedTotalCount','resolveRoutedCommentRootReply']:
        body=remove_declaration(body,name)
    body='\n'.join(l for l in body.splitlines() if not l.startswith('import android.') and not any(l==x for x in ['import androidx.lifecycle.ViewModel','import androidx.lifecycle.viewModelScope','import com.android.purebilibili.core.network.NetworkModule','import com.android.purebilibili.data.repository.CommentRepository','import com.android.purebilibili.data.repository.CommentFraudRepository']))+'\n'
    body=replace_once(body,'import kotlinx.coroutines.Job\n','import kotlinx.coroutines.Job\nimport kotlinx.coroutines.CoroutineScope\nimport kotlinx.coroutines.CancellationException\nimport kotlinx.coroutines.currentCoroutineContext\nimport kotlinx.coroutines.ensureActive\n')
    body=replace_once(body,'class VideoCommentViewModel : ViewModel() {','internal class VideoCommentViewModel(\n    private val scope: CoroutineScope,\n    private val requests: DesktopVideoCommentRequests,\n) {\n    private suspend fun ensureRequestOwned() {\n        currentCoroutineContext().ensureActive()\n        if (!requests.isOwned()) throw CancellationException("Music comment owner retired")\n    }\n    private fun launchOwned(block: suspend CoroutineScope.() -> Unit): Job = scope.launch {\n        ensureRequestOwned(); block()\n    }')
    # An upstream VM's mutable state belongs to its own keyed screen, not a persistent store.
    body=body.replace('viewModelScope.launch','launchOwned').replace('return@launch','return@launchOwned')
    body=body.replace('com.android.purebilibili.core.store.TokenManager.midCache ?: 0L','requests.currentMid()')
    body=body.replace('com.android.purebilibili.data.repository.CommentFraudRepository.saveRecord','requests.saveFraudRecord').replace('CommentFraudRepository.saveRecord','requests.saveFraudRecord').replace('CommentRepository.','requests.')
    upload_start,upload_end=shared.fun_span(body,'uploadCommentPictures')
    body=body[:upload_start]+'''    private suspend fun uploadCommentPictures(imageUris: List<String>): Result<List<ReplyPicture>> =
        withContext(Dispatchers.IO) {
            try {
                Result.success(imageUris.take(9).mapIndexed { index, uri ->
                    ensureRequestOwned()
                    requests.uploadCommentPicture(uri, index).getOrElse { throw it }
                })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { ensureRequestOwned(); Result.failure(failure) }
        }'''+body[upload_end:]
    body=remove_declaration(body,'queryDisplayName').replace('List<Uri>','List<String>')
    body=shared.drop_logs(body)
    # Every original diagnostic Android Log call is standalone; remove only that call.
    while True:
        mask=shared.masked(body);log=re.search(r'android\.util\.Log\.[dwei]\s*\(',mask)
        if log is None:break
        start=body.rfind('\n',0,log.start())+1;assert not body[start:log.start()].strip()
        end=shared.balanced(mask,mask.index('(',log.start()))
        body=body[:start]+body[end:]
    # Request callbacks and original subject/root guards jointly admit publications.
    body=re.sub(r'(\.onSuccess\s*\{(?:\s*\w+\s*->)?)',r'\1\n                ensureRequestOwned()',body)
    body=re.sub(r'(\.onFailure\s*\{(?:\s*\w+\s*->)?)',r'\1\n                ensureRequestOwned()',body)
    body=replace_once(body,'            val picturesResult = uploadCommentPictures(imageUris)','            ensureRequestOwned()\n            val picturesResult = uploadCommentPictures(imageUris)')
    body, search_lifetime = comment_search.vm_delta(body)
    comment_selection['originalToPlatform'] = comment_search.whole_proof(comment_raw,body)
    comment_selection['searchLifetime'] = search_lifetime
    changes.append(dict(source=path,v029CommentSearch=comment_selection))
    emit('com/android/purebilibili/feature/video/viewmodel/VideoCommentViewModel.kt',body)
    retained(path,body,'Full original generic VM, including typed subjects, paging, subreply/conversation, send/pictures/sync, reactions, delete, original fraud hooks; sole editor/count-helper declarations omitted because already emitted once. Android lifecycle/Uri/currentMid/fraud persistence become required same-owner seams. No DynamicItem surrogate.')
    path=BASE+'feature/video/ui/components/CommentInputDialog.kt';body=original[path]
    body='\n'.join(l for l in body.splitlines() if not l.startswith('import android.') and not l.startswith('import androidx.activity.') and 'import com.android.purebilibili.core.util.PickMultipleGalleryVisualMedia' not in l)+'\n'
    body=body.replace('import androidx.compose.ui.platform.LocalConfiguration','import com.android.purebilibili.core.util.LocalWindowSizeClass\nimport com.bilipai.desktop.ui.LocalDesktopCommentBindings')
    body=body.replace('    val configuration = LocalConfiguration.current\n    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp','    val platform = LocalDesktopCommentBindings.current\n    val configuration = LocalWindowSizeClass.current\n    val isLandscape = configuration.widthDp > configuration.heightDp')
    # Existing isTablet owner and original Dialog layout, skin emojis and panels remain.
    body=body.replace('List<Uri>','List<String>')
    body=body.replace('uris.map(Uri::toString)', 'uris.toList()').replace('uris.map(Uri::parse)', 'uris.toList()')
    body=body.replace('remember(configuration.orientation)', 'remember(isLandscape)')
    body=body.replace('configuration.screenWidthDp', 'configuration.widthDp.value.toInt()').replace('configuration.screenHeightDp', 'configuration.heightDp.value.toInt()')
    imports_seen=set(); body='\n'.join(line for line in body.splitlines() if not (line.startswith('import ') and (line in imports_seen or imports_seen.add(line))))+'\n'
    start=body.index('    val imagePickerLauncher = rememberLauncherForActivityResult(');end=body.index('\n    fun updateTextFieldValue',start)
    result=body[start:end];assert 'distinct()' in result and '.take(9)' in result
    body=body[:start]+body[end:]
    body=replace_once(body,'imagePickerLauncher.launch(\n                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)\n                                        )','''platform.pickCommentImages(9) { uris ->
                                            if (platform.isOwned() && uris.isNotEmpty()) {
                                                selectedImageUris = (selectedImageUris + uris).distinct().take(9)
                                                onDraftChange(textFieldValue.text, selectedImageUris, isForwardToDynamic)
                                            }
                                        }''')
    body=replace_once(body,'                decorFitsSystemWindows = false   // 沉浸式：内容延伸到状态栏/导航栏下\n','')
    body=shared.drop_logs(body)
    from v029_comment_composer import dialog_delta
    body=dialog_delta(repo,output,original[path],body)
    emit('com/android/purebilibili/feature/video/ui/components/CommentInputDialog.kt',body)
    retained(path,body,'Full original input renderer/policies/mention/emotes/skin/draft/sync/9 images; owned existing Windows picker replaces ActivityResult, measured viewport replaces Android configuration and Android-only dialog inset flag is omitted.')
    path=BASE+'feature/video/ui/components/CommentSortFilterBar.kt';body=original[path]
    end=body.index('/**\n * 评论排序分段控件，放置在详情页顶栏')
    body=body[:end] # Complete original CommentSortHeader + every pure helper it consumes.
    body=replace_once(body,'import com.android.purebilibili.feature.home.components.BottomBarLiquidSegmentedControl\n','')
    emit('com/android/purebilibili/feature/video/ui/components/DesktopOriginalCommentSortHeader.kt',body)
    retained(path,body,'Full original generic header/list-title and preceding policies; unrelated filter-bar/segmented renderer stays outside this consumer selection.')
    path=BGM+'BgmDetailScreen.kt';body=original[path]
    body='\n'.join(l for l in body.splitlines() if not l.startswith('import android.') and not any(l==x for x in ['import androidx.compose.ui.platform.LocalContext','import androidx.lifecycle.compose.collectAsStateWithLifecycle','import androidx.lifecycle.viewmodel.compose.viewModel as composeViewModel','import com.android.purebilibili.core.store.TokenManager']))+'\n'
    body=replace_once(body,'import kotlinx.coroutines.flow.distinctUntilChanged','import kotlinx.coroutines.flow.distinctUntilChanged\nimport com.bilipai.desktop.ui.LocalDesktopCommentBindings')
    body=replace_once(body,'    viewModel: BgmDetailViewModel = composeViewModel(),\n    commentViewModel: VideoCommentViewModel = composeViewModel(key = "bgm-comments-$musicId"),','    viewModel: BgmDetailViewModel,\n    commentViewModel: VideoCommentViewModel,\n    requests: DesktopBgmDetailRequests,\n    nowPlayingBarOverlayVisible: Boolean,\n    onMediaSearch: (String, String, String) -> Boolean,')
    body=body.replace('.collectAsStateWithLifecycle()','.collectAsState()').replace('    val context = LocalContext.current','    val platform = LocalDesktopCommentBindings.current')
    body=body.replace('com.android.purebilibili.data.repository.CommentRepository.getEmotePackages()','requests.getEmotePackages()')
    body=body.replace('Toast.makeText(context, it, Toast.LENGTH_SHORT).show()','platform.showFeedback(it)').replace('Toast.makeText(context, comments.sendError, Toast.LENGTH_LONG).show()','platform.showFeedback(comments.sendError.orEmpty())').replace('Toast.makeText(context, "当前评论区暂不可发言", Toast.LENGTH_SHORT).show()','platform.showFeedback("当前评论区暂不可发言")')
    body=replace_once(body,'TokenManager.sessDataCache.isNullOrBlank()','!requests.hasLogin()')
    start=body.index('            context.startActivity(Intent.createChooser(');end=body.index('\n        },',start)
    body=body[:start]+'''            platform.shareText("${state.detail?.musicTitle.orEmpty()} https://music.bilibili.com/h5/music-detail?music_id=${java.net.URLEncoder.encode(musicId, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20")}", "分享音乐")'''+body[end:]
    body=replace_once(body,'        onLinkClick = onLinkClick, modifier = modifier,','        onLinkClick = onLinkClick, modifier = modifier,\n        nowPlayingBarOverlayVisible = nowPlayingBarOverlayVisible, onMediaSearch = onMediaSearch,')
    needle='    modifier: Modifier = Modifier,\n) {\n    var previewImages'
    body=replace_once(body,needle,'    modifier: Modifier = Modifier,\n    nowPlayingBarOverlayVisible: Boolean,\n    onMediaSearch: (String, String, String) -> Boolean,\n) {\n    var previewImages')
    overlay='                val nowPlayingBarOverlayVisible by com.android.purebilibili.feature.audio.player\n                    .AudioNowPlayingSession.barOverlayVisible\n                    .collectAsState()\n'
    body=replace_once(body,overlay,'')
    body=body.replace('BgmInfoCard(detail, onVideosClick, onVideoClick, onUserClick,','BgmInfoCard(detail, onVideosClick, onVideoClick, onUserClick, onMediaSearch,')
    body=replace_once(body,'    onUserClick: (Long) -> Unit,\n    modifier: Modifier = Modifier,\n) {\n    val platform','    onUserClick: (Long) -> Unit,\n    onMediaSearch: (String, String, String) -> Boolean,\n    modifier: Modifier = Modifier,\n) {\n    val platform')
    body=replace_once(body,'        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("音乐", text))\n        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()','        platform.copyText(text, "音乐")\n        platform.showFeedback("已复制")')
    start=body.index('                        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)');end=body.index('\n                    }, contentPadding',start)
    body=body[:start]+'''                        if (!onMediaSearch(detail.musicTitle, detail.originArtist.ifBlank { detail.originArtistList }, detail.album)) copy(detail.musicTitle)'''+body[end:]
    emit('com/android/purebilibili/feature/audio/bgm/BgmDetailScreen.kt',body)
    retained(path,body,'Entire original Screen/Content/InfoCard/Videos retained, with owned required VM/platform callbacks, actual ListenAudioSession overlay parameter, and original external-media-search fallback. Native BGM never becomes legacy AU/Music page.')
    for suffix in ['data/model/CommentFraudStatus.kt','data/repository/CommentFraudDetectionPolicy.kt']:
        emit('com/android/purebilibili/'+suffix,original[BASE+suffix])
    path=BASE+'core/database/entity/CommentFraudRecord.kt';body=original[path]
    body='\n'.join(l for l in body.splitlines() if not l.startswith('import androidx.room.') and not l.lstrip().startswith('@Entity(') and l.strip()!='@PrimaryKey')+'\n'
    emit('com/android/purebilibili/core/database/entity/CommentFraudRecord.kt',body)
    retained(path,body,'Exact original serializable record schema, computations, enums and JSON SerialName fields; only Room Entity/PrimaryKey metadata removed.')
    path=BASE+'core/database/dao/CommentFraudDao.kt';body=original[path]
    body='\n'.join(l for l in body.splitlines() if not l.startswith('import androidx.room.') and not l.lstrip().startswith('@'))+'\n'
    body=replace_once(body,'package com.android.purebilibili.core.database.dao','package com.android.purebilibili.data.repository')
    body=replace_once(body,'interface CommentFraudDao {','internal interface DesktopCommentFraudDao {')
    emit('com/android/purebilibili/data/repository/DesktopCommentFraudDao.kt',body)
    retained(path,body,'Full original DAO method contract; Room annotations/SQL replaced by Windows adapter preserving primary-key replacement and effective post-time DESC ordering.')
    path=BASE+'data/repository/CommentFraudRepository.kt';body=original[path]
    body='\n'.join(l for l in body.splitlines() if not l.startswith('import android.') and l not in ['import com.android.purebilibili.app.PureApplication','import com.android.purebilibili.core.database.AppDatabase'])+'\n'
    body=replace_once(body,'object CommentFraudRepository {','internal class DesktopOriginalCommentFraudRepository(\n    private val dao: DesktopCommentFraudDao,\n    private val checkStatus: suspend (Long, Long, Long) -> Result<CommentFraudStatus>,\n    private val deleteComment: suspend (Long, Long) -> Result<Unit>,\n) {')
    start=body.index('    private fun getDao(');end=body.index('\n    /**',start)
    body=body[:start]+'    private fun getDao() = dao\n'+body[end:]
    body=body.replace(',\n        context: Context = PureApplication.instance','').replace('context: Context = PureApplication.instance','')
    body=body.replace('rpid: Long, )','rpid: Long)').replace('jsonContent: String, )','jsonContent: String)').replace('getDao(context)','getDao()')
    body=replace_once(body,'CommentRepository.checkCommentStatus(\n                aid = record.oid,\n                rpid = record.rpid,\n                rootId = record.root,\n                waitMs = 0L\n            )','checkStatus(record.oid, record.rpid, record.root)')
    body=replace_once(body,'CommentRepository.deleteComment(\n                aid = record.oid,\n                rpid = record.rpid\n            )','deleteComment(record.oid, record.rpid)')
    body=shared.drop_logs(body)
    # Cancellation belongs to owner lifetime and may not become a success/UNKNOWN record.
    body=body.replace('import kotlinx.coroutines.Dispatchers','import kotlinx.coroutines.CancellationException\nimport kotlinx.coroutines.Dispatchers')
    body=body.replace('} catch (e: Exception) {','} catch (cancelled: CancellationException) { throw cancelled\n        } catch (e: Exception) {')
    emit('com/android/purebilibili/data/repository/DesktopOriginalCommentFraudRepository.kt',body)
    retained(path,body,'Complete original record merge/save/recheck/delete/export/import bodies; Android Room getDao/context replaced by required same-account Windows DAO, requests remain existing-owner callbacks; cancellation rethrows.')
    for suffix in ['feature/video/ui/components/CommentMentionInsertPolicy.kt','feature/video/viewmodel/CommentPaginationPolicy.kt']:
        emit('com/android/purebilibili/'+suffix,original[BASE+suffix])
        retained(BASE+suffix,original[BASE+suffix],'Full original pure helper file, LF-identical.')
    path='design-system/src/main/java/com/android/purebilibili/core/ui/motion/VerticalContentRevealMotionPolicy.kt'
    emit('com/android/purebilibili/core/ui/motion/VerticalContentRevealMotionPolicy.kt',original[path])
    retained(path,original[path],'Full original Compose reveal motion file, LF-identical.')
    path=BASE+'core/ui/LottieComponents.kt'
    body,audit=empty_consumer(original[path])
    emit('com/android/purebilibili/core/ui/DesktopOriginalBgmEmptyState.kt',body)
    changes.append(dict(strategy='Complete fixed-v029 EmptyState body; original same-action/replay/default parameters; existing legacy LottieUrls/CutePersonLoadingIndicator retained in this sole output',**audit))


def generate_comment_detail(original,shared,emit,changes):
    def retained(path,body,strategy):
        changes.append(dict(source=path,strategy=strategy,originalSha256=hashlib.sha256(original[path].encode()).hexdigest(),generatedSha256=hashlib.sha256(body.encode()).hexdigest()))
    path=BASE+'feature/comment/CommentDetailViewModel.kt';body=original[path]
    body='\n'.join(line for line in body.splitlines() if line not in ['import android.net.Uri','import androidx.lifecycle.ViewModel','import androidx.lifecycle.viewModelScope','import com.android.purebilibili.data.repository.CommentFraudRepository','import com.android.purebilibili.data.repository.CommentRepository'])+'\n'
    body=replace_once(body,'import kotlinx.coroutines.Job\n','import kotlinx.coroutines.Job\nimport kotlinx.coroutines.CoroutineScope\nimport kotlinx.coroutines.CancellationException\nimport kotlinx.coroutines.currentCoroutineContext\nimport kotlinx.coroutines.ensureActive\nimport com.android.purebilibili.feature.video.viewmodel.DesktopVideoCommentRequests\n')
    body=replace_once(body,'class CommentDetailViewModel : ViewModel() {','''internal class CommentDetailViewModel(
    private val scope: CoroutineScope,
    private val requests: DesktopVideoCommentRequests,
) {
    private suspend fun ensureOwned() {
        currentCoroutineContext().ensureActive()
        if (!requests.isOwned()) throw CancellationException("Comment detail owner retired")
    }
    private fun launchOwned(block: suspend CoroutineScope.() -> Unit): Job = scope.launch {
        ensureOwned(); block()
    }''')
    body=body.replace('List<Uri>','List<String>').replace('viewModelScope.launch','launchOwned').replace('return@launch','return@launchOwned')
    body=body.replace('CommentFraudRepository.saveRecord','requests.saveFraudRecord').replace('CommentRepository.','requests.')
    # Retain all original business functions. Enforce current owner before asynchronous publication.
    body=re.sub(r'\.(onSuccess|onFailure)\s*\{\s*(\w+)\s*->',r'.\1 { \2 -> ensureOwned();',body)
    body=body.replace('.onFailure {\n','.onFailure { ensureOwned();\n')
    emit('com/android/purebilibili/feature/comment/CommentDetailViewModel.kt',body)
    retained(path,body,'Full original CommentDetail VM: root-target load, sorting/pagination/conversation, send/reactions/delete/dissolve/type1 fraud. Android Uri/lifecycle/singleton repositories become required same-owner requests/scope. Original pictures parameter remains unused by original sendReply; no fabricated image-upload parity.')
    path=BASE+'feature/comment/CommentDetailScreen.kt';body=original[path]
    body='\n'.join(line for line in body.splitlines() if line not in ['import android.widget.Toast','import androidx.activity.compose.BackHandler','import androidx.compose.ui.platform.LocalContext','import androidx.lifecycle.compose.collectAsStateWithLifecycle','import androidx.lifecycle.viewmodel.compose.viewModel','import com.android.purebilibili.core.store.TokenManager'])+'\n'
    body=replace_once(body,'import androidx.compose.runtime.Composable\n','import androidx.compose.runtime.Composable\nimport androidx.compose.runtime.collectAsState\nimport com.bilipai.desktop.ui.LocalDesktopCommentBindings\nimport com.android.purebilibili.feature.video.viewmodel.DesktopVideoCommentRequests\n')
    body=replace_once(body,'    viewModel: CommentDetailViewModel = viewModel()','    viewModel: CommentDetailViewModel,\n    requests: DesktopVideoCommentRequests')
    body=replace_once(body,'fun CommentDetailScreen(','internal fun CommentDetailScreen(')
    body=body.replace('.collectAsStateWithLifecycle()','.collectAsState()')
    body=replace_once(body,'    val context = LocalContext.current','    val platform = LocalDesktopCommentBindings.current')
    body=replace_once(body,'Toast.makeText(context, message, Toast.LENGTH_SHORT).show()','platform.showFeedback(message)')
    body=replace_once(body,'    val currentMid = remember { TokenManager.midCache ?: 0L }','    val currentMid = requests.currentMid()')
    body=replace_once(body,'    BackHandler(enabled = true) {','    com.android.purebilibili.core.ui.LocalNavigationBackHandler(enabled = true) {')
    emit('com/android/purebilibili/feature/comment/CommentDetailScreen.kt',body)
    retained(path,body,'Full original BGM navigation destination CommentDetailScreen; all SubReplyDetailContent/image/composer/fraud/back branches retained. Only current owner VM/platform/ID and desktop navigation event seams replace Android bindings.')
    path=BASE+'feature/video/ui/components/CommentFraudDialog.kt';body=original[path]
    emit('com/android/purebilibili/feature/video/ui/components/CommentFraudDialog.kt',body)
    retained(path,body,'Full original LF-identical fraud dialog, banner, models.')
    path=BASE+'feature/message/feed/MessageFeedCommon.kt';raw=original[path]
    mask=shared.masked(raw);match=re.search(r'(?m)^internal fun MessageFeedError\(',mask);assert match
    start=match.start();param=shared.balanced(mask,mask.index('(',start))
    end=shared.balanced(mask,mask.index('{',param),'{','}')
    # Prefix annotation is outside function_span; select its exact original annotation as well.
    start=raw.rfind('@Composable',0,start)
    body='''package com.android.purebilibili.feature.message.feed
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.core.ui.components.AppButton
'''+raw[start:end]+'\n'
    emit('com/android/purebilibili/feature/message/feed/DesktopCommentDetailError.kt',body)
    retained(path,body,'Complete original MessageFeedError function consumed by CommentDetailScreen; no unrelated message-feed renderers selected.')
    path=BASE+'data/repository/CommentRepository.kt';raw=original[path];mask=shared.masked(raw)
    start=mask.index('AppScope.ioScope.launch {',mask.index('suspend fun addCommentForSubject('))
    opening=mask.index('{',start);end=shared.balanced(mask,opening,'{','}')
    selected=textwrap.dedent(raw[opening+1:end-1]).strip()
    selected=replace_once(selected,'CommentFraudRepository.saveRecord(','saveRecord(')
    body='''package com.android.purebilibili.data.repository
import com.android.purebilibili.data.model.CommentFraudStatus
import com.android.purebilibili.data.model.response.ReplyItem
/** Original addCommentForSubject asynchronous original-record body. Root owns its lifetime. */
internal suspend fun DesktopOriginalCommentFraudRepository.savePublishedCommentRecord(
    reply: ReplyItem, oid: Long, type: Int, root: Long, parent: Long, message: String, serverPostTime: Long,
) {
    val userUid = reply.mid
'''+textwrap.indent(selected,'    ')+'\n}\n'
    emit('com/android/purebilibili/data/repository/DesktopOriginalPublishedCommentRecord.kt',body)
    retained(path,body,'Exact original addCommentForSubject background record save block; Root same-account epoch scope replaces AppScope, original UID/type/root/parent/server ctime/UNKNOWN merge preserved.')


def generate_bgm_discovery(original,shared,emit,changes):
    path=BASE+'feature/video/ui/section/VideoInfoSection.kt';raw=original[path]
    mask=shared.masked(raw);match=re.search(r'(?m)^private fun InlineBgmSection\(',mask);assert match
    start=raw.rfind('@Composable',0,match.start());body=raw[start:];original_tail=body
    adaptations=[]
    def adapt(before,after,count=1):
        nonlocal body
        assert body.count(before)==count,(before,count,body.count(before))
        cursor=0
        for _ in range(count):
            index=body.index(before,cursor)
            adaptations.append(dict(index=index,before=before,after=after))
            body=body[:index]+after+body[index+len(before):]
            cursor=index+len(after)
    adapt('private fun InlineBgmSection(','internal fun DesktopOriginalInlineBgmSection(')
    adapt('(String, android.os.Bundle?) -> Unit','(String, Long) -> Unit',2)
    adapt('                                buildVideoNavigationOptions(targetCid = video.cid)','                                video.cid')
    first=body.index('internal fun resolveBgmTagInfo(');last=body.index('private fun resolveQueryLongParam(',first)
    adapt(body[first:last],'') # The original two shared helpers already have one existing producer.
    adapt('    return android.net.Uri.parse(url).getQueryParameter(key)?.toLongOrNull() ?: 0L','    return com.bilipai.desktop.audio.desktopBgmQueryParameter(url, key)?.toLongOrNull() ?: 0L')
    # Coil's existing desktop platform context preserves image loading/rendering.
    # Home card style comes from the already mounted Root settings owner.
    old="""    val context = LocalContext.current
    val homeFeedCardStyle by SettingsManager
        .getHomeFeedCardStyle(context)
        .collectAsStateWithLifecycle(initialValue = HomeFeedCardStyle.BILIPAI)"""
    new="""    val preferences = checkNotNull(com.bilipai.desktop.settings.LocalDesktopHomeCardPreferences.current)
    val homeSettings by preferences.settings.collectAsState(preferences.initialSettings())
    val homeFeedCardStyle = homeSettings.homeFeedCardStyle"""
    adapt(old,new,2)
    adapt('    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)','    val requests = com.bilipai.desktop.audio.LocalDesktopBgmDiscoveryRequests.current\n    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)')
    adapt('ViewGrpcRepository.getBgmDetail(','requests.getBgmDetail(')
    adapt('ViewGrpcRepository.getBgmRecommendVideos(','requests.getBgmRecommendVideos(',2)
    adapt('                cid = cid\n            )','                cid = cid,\n                requests = requests\n            )')
    adapt('    cid: Long\n) {\n    val currentState = itemStateByKey[itemKey] ?: return','    cid: Long,\n    requests: com.bilipai.desktop.audio.DesktopBgmDiscoveryRequests,\n) {\n    val currentState = itemStateByKey[itemKey] ?: return')
    # Native video is heavyweight: only this selector's surface becomes an owned Dialog.
    adapt('    com.android.purebilibili.core.ui.AppModalBottomSheet(\n        onDismissRequest = onDismiss,\n        sheetState = sheetState,\n        dragHandle = null\n    ) {',
          '    com.bilipai.desktop.ui.DesktopWindowsBgmModalSheet(\n        title = title,\n        onDismissRequest = onDismiss,\n        sheetState = sheetState\n    ) {')
    adapt('.fillMaxHeight(0.68f)', '.fillMaxHeight(com.bilipai.desktop.ui.desktopWindowsBgmSelectionHeightFraction())')
    # Preserve each complete original state assignment, but publish under the same owner.
    assignments=list(re.finditer(r'(?m)^( *)itemStateByKey\[(?:selectedItemKey|itemKey)\] = ',shared.masked(body)))
    assert len(assignments)==4
    original_assignments=[]
    for assignment in assignments:
        open_paren=body.index('(',assignment.end());depth=1;cursor=open_paren+1;masked=shared.masked(body)
        while depth:
            if masked[cursor]=='(':depth+=1
            elif masked[cursor]==')':depth-=1
            cursor+=1
        original_assignments.append((assignment.group(1),body[assignment.start():cursor]))
    for indent,assignment in original_assignments:
        adapt(assignment,indent+'requests.commitBgmDiscoveryState {\n'+textwrap.indent(assignment,'    ')+'\n'+indent+'}')
    inverse=body
    for row in reversed(adaptations):
        index=row['index'];after=row['after'];assert inverse[index:index+len(after)]==after
        inverse=inverse[:index]+row['before']+inverse[index+len(after):]
    assert inverse==original_tail
    constants='\n'.join(line for line in raw.splitlines() if re.match(r'private (?:const )?val (?:BGM_DISCOVERY_LOAD_DELAY_MS|BGM_RECOMMEND_PAGE_SIZE|BGM_RECOMMEND_ROW_START_INDEX|AUDIO_NOW_PLAYING_BAR_CLEARANCE_DP|BGM_DETAIL_CARD_MIN_HEIGHT)\b',line))
    assert len(constants.splitlines())==5
    imports='''package com.android.purebilibili.feature.video.ui.section
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.animation.core.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import coil3.compose.LocalPlatformContext as LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.core.store.HomeFeedCardStyle
import com.android.purebilibili.core.util.FormatUtils
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.home.components.cards.ElegantVideoCard
import com.android.purebilibili.feature.home.resolveHomeFeedCardLayout
import com.android.purebilibili.feature.video.ui.components.VideoCardSkeleton
import com.android.purebilibili.feature.video.ui.components.ShimmerContainer
import com.android.purebilibili.feature.video.ui.components.SkeletonBox
import kotlinx.coroutines.delay
import com.bilipai.desktop.audio.commitBgmDiscoveryState
'''
    body=imports+'\n'+constants+'\n\n'+body
    emit('com/android/purebilibili/feature/video/ui/section/DesktopOriginalBgmDiscovery.kt',body)
    changes.append(dict(source=path,strategy='Entire original BGM-only InlineBgmSection/BgmInfoRow/selection sheet/detail/recommendation paging/strip/skeleton + all consumed pure helpers. Existing shared display/tag helpers remain sole-owned. Android Bundle/Uri/Coil/context/settings singletons map to same existing Root owners/typed cid; Windows selector changes only its native container and four final same-owner state admission seams, with complete original tail inverse.',originalSha256=hashlib.sha256(raw.encode()).hexdigest(),generatedSha256=hashlib.sha256(body.encode()).hexdigest(),originalTailSha256LF=hashlib.sha256(original_tail.encode()).hexdigest(),adaptations=adaptations,exactOriginalTailInverse=True,originalStateAssignments=4,nativeSelectorContainerOnly=True))
    for suffix in ['core/ui/LocalNavigationBackHandler','feature/video/ui/components/VideoCardSkeleton']:
        path=BASE+suffix+'.kt';body=original[path]
        emit('com/android/purebilibili/'+suffix+'.kt',body)
        changes.append(dict(source=path,strategy='Full original LF-identical helper; existing Root navigation-event/settings/skeleton owners stay sole-defined.',originalSha256=hashlib.sha256(body.encode()).hexdigest(),generatedSha256=hashlib.sha256(body.encode()).hexdigest()))
    path=BASE+'feature/video/ui/components/SkeletonComponents.kt';body=original[path]
    body=body.replace('import androidx.compose.ui.platform.LocalContext\n','').replace('import androidx.lifecycle.compose.collectAsStateWithLifecycle\n','').replace('import com.android.purebilibili.core.store.SettingsManager\n','')
    old='''    val context = LocalContext.current
    val homeFeedCardStyle by SettingsManager
        .getHomeFeedCardStyle(context)
        .collectAsStateWithLifecycle(initialValue = HomeFeedCardStyle.BILIPAI)'''
    assert body.count(old)==1;body=body.replace(old,new)
    body=replace_once(body,'import androidx.compose.runtime.Composable\n','import androidx.compose.runtime.Composable\nimport androidx.compose.runtime.collectAsState\n')
    emit('com/android/purebilibili/feature/video/ui/components/SkeletonComponents.kt',body)
    changes.append(dict(source=path,strategy='Full original skeleton renderer/body, animation and color policies; sole Home card settings owner replaces Android SettingsManager read.',originalSha256=hashlib.sha256(original[path].encode()).hexdigest(),generatedSha256=hashlib.sha256(body.encode()).hexdigest()))
    path=BASE+'feature/video/ui/components/RelatedVideoItem.kt';raw=original[path]
    constant=re.search(r'(?m)^internal const val RELATED_VIDEO_CARD_COVER_ASPECT_RATIO = .+$',raw);assert constant
    body='package com.android.purebilibili.feature.video.ui.components\n\n'+constant.group()+'\n'
    emit('com/android/purebilibili/feature/video/ui/components/DesktopRelatedVideoSkeletonGeometry.kt',body)
    changes.append(dict(source=path,strategy='Exact original shared cover-ratio constant consumed by the complete original SkeletonComponents renderer; no related-video UI selected in this lane.',originalSha256=hashlib.sha256(raw.encode()).hexdigest(),generatedSha256=hashlib.sha256(body.encode()).hexdigest()))


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--repo',type=Path,required=True);parser.add_argument('--output',type=Path);parser.add_argument('--inventory',action='store_true');args=parser.parse_args()
    if args.inventory:print(json.dumps(inventory(args.repo),ensure_ascii=False,indent=2));return
    assert args.output is not None;generate(args.repo,args.output)
OLD_BGM_EMOTE_MEMBERS = '    suspend fun getBgmEmotePackages(): Result<List<EmotePackage>> = result { read {\n        val response = api.getEmotes(mutableMapOf("business" to "reply"))\n        if (response.code == 0) response.data?.packages ?: response.data?.all_packages ?: emptyList()\n        else throw Exception(response.message)\n    } }\n'

def bgm_emote_members(repo):
    import importlib.util, re, textwrap
    def load(name,path):
        spec=importlib.util.spec_from_file_location(name,path);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module
    identity=load('bgm_emote_original_identity',repo/'desktop/tools/extract-upstream-dynamic-reply-protocol.py')
    path='app/src/main/java/com/android/purebilibili/data/repository/CommentRepository.kt'
    sources,identities=identity.load_pinned_sources(repo,[path]);source=sources[path]
    host=load('bgm_emote_original_host',repo/'desktop/tools/extract-upstream-plugins.py');media=host.media_extractor(repo);parser=media.parser_for(repo)
    original=media.function(source,'getEmotePackages',parser)
    body=original;adaptations=[]
    def adapt(before,after):
        nonlocal body
        assert body.count(before)==1,before
        index=body.index(before);adaptations.append(dict(index=index,before=before,after=after));body=body[:index]+after+body[index+len(before):]
    adapt('suspend fun getEmotePackages()', 'private suspend fun originalBgmEmotePackages()')
    adapt('val response = api.getEmotes(mapOf("business" to "reply"))', 'val response = api.getEmotes(mapOf("business" to "reply"))\n        coroutineContext.ensureActive(); assertOwned()')
    adapt('        val details = api.getEmotePackageDetails(', '        coroutineContext.ensureActive(); assertOwned()\n        val details = api.getEmotePackageDetails(')
    adapt('        if (details.code != 0) {', '        coroutineContext.ensureActive(); assertOwned()\n        if (details.code != 0) {')
    inverse=body
    for row in reversed(adaptations):
        index=row['index'];after=row['after'];assert inverse[index:index+len(after)]==after
        inverse=inverse[:index]+row['before']+inverse[index+len(after):]
    assert inverse==original
    standard=re.findall(r'(?m)^private val COMMENT_STANDARD_EMOTE_IDS = .+$',source);assert len(standard)==1
    merge=media.function(source,'mergeCommentEmotePackages',parser)
    assert merge.startswith('internal fun ');merge=merge.replace('internal fun ','private fun ',1)
    new='    suspend fun getBgmEmotePackages(): Result<List<EmotePackage>> = result { read { originalBgmEmotePackages().getOrThrow() } }\n'
    new+=textwrap.indent(standard[0]+'\n\n'+merge+'\n\n'+body,'    ')+'\n'
    old=OLD_BGM_EMOTE_MEMBERS
    assert BGM_OPS_MEMBERS.count(old)==1
    members=BGM_OPS_MEMBERS.replace(old,new,1)
    return members,dict(identities=identities,originalFunctionSha256LF=hashlib.sha256(original.encode()).hexdigest(),adaptations=adaptations,exactInverse=True,completeOriginalStandardEmoteMerge=True,sameOperationsReadOwner=True,newApiClients=0)


if __name__=='__main__':main()
