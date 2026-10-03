"""Complete original video CommentTab/search/composer; Root owns all authority."""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse, hashlib, importlib.util, json, re, textwrap
PIN='79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40'
BASE='app/src/main/java/com/android/purebilibili/'
CONTENT=BASE+'feature/video/screen/VideoContentSection.kt'
PLAYBACK=BASE+'feature/video/viewmodel/VideoPlaybackViewModel.kt'
SOURCES=[CONTENT,PLAYBACK]+[BASE+n+'.kt' for n in ['feature/video/screen/VideoCommentPerformancePolicy','feature/video/ui/components/CommentSearchSheet','feature/video/ui/components/SubReplyDetailComponents','feature/video/viewmodel/VideoComposerDraftState','feature/video/screen/VideoDetailInputOverlayAdapter','feature/video/screen/VideoDetailCommentFraudOverlayAdapter','core/store/SettingsManager','data/repository/CommentRepository','core/ui/blur/FloatingChromeBackdrop']]
def mod(p,n):
    s=importlib.util.spec_from_file_location(n,p);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
def replace_once(t,a,b):
    assert t.count(a)==1,(a,t.count(a));return t.replace(a,b)
def inventory(repo):
    return [dict(path=p,mode='policy-extract',features=['stable-video-original-comments'],sha256=hashlib.sha256((_desktop_canonical_source(repo, p)).read_text(encoding='utf-8').replace('\r\n','\n').encode()).hexdigest()) for p in SOURCES]
def generate(repo,output):
    shared=mod(Path(__file__).with_name('extract-upstream-dynamic-reply-protocol.py'),'original_video_comment_identity')
    original,identities=shared.load_pinned_sources(repo,SOURCES)
    selections=[]
    def emit(path,body,origin,strategy):
        p=output/'com/android/purebilibili'/path;v=str(p.absolute());p=Path('\\\\?\\'+v) if not v.startswith('\\\\?\\') else p
        p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,encoding='utf-8',newline='\n')
        selections.append(dict(source=origin,path=str(path),strategy=strategy,generatedLfSha256=hashlib.sha256(body.encode()).hexdigest()))
    def function(text,name):
        mask=shared.masked(text);matches=list(re.finditer(r'(?m)^[ \t]*(?:(?:internal|private|public|override|inline|suspend)\s+)*fun\s+'+re.escape(name)+r'\s*\(',mask));assert len(matches)==1,name
        m=matches[0];endparams=shared.balanced(mask,mask.index('(',m.start()));opening=mask.index('{',endparams);end=shared.balanced(mask,opening,'{','}')
        start=m.start();annotation=text.rfind('@Composable',0,start)
        if annotation>=0 and not text[annotation+len('@Composable'):start].strip():start=annotation
        return text[start:end]
    tab=function(original[CONTENT],'VideoCommentTab').replace('com.android.purebilibili.core.ui.animation.MaybeDissolvableVideoCard(','com.bilipai.desktop.ui.DesktopReplyDissolvableContainer(')
    header='''package com.android.purebilibili.feature.video.screen
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.ui.components.*
import com.android.purebilibili.feature.video.viewmodel.CommentSortMode
import com.android.purebilibili.feature.dynamic.components.ImagePreviewSourceAnchor
import com.android.purebilibili.feature.dynamic.components.ImagePreviewTextContent
'''
    emit('feature/video/screen/DesktopOriginalVideoCommentTab.kt',header+'\n'+tab+'\n',CONTENT,'Entire original VideoCommentTab, including exact pagination/vote/raw rich replies/timestamps/top/fraud/delete/dissolve/identities/header. Only existing desktop dissolve platform name maps.')
    path=BASE+'feature/video/screen/VideoCommentPerformancePolicy.kt'
    emit('feature/video/screen/VideoCommentPerformancePolicy.kt',original[path],path,'Full original LF-identical pure policy file.')
    path=BASE+'core/ui/blur/FloatingChromeBackdrop.kt'
    emit('core/ui/blur/FloatingChromeBackdrop.kt',original[path],path,'Full original nullable backdrop CompositionLocal; source search uses its original null fallback without cropping UI branches.')
    path=BASE+'feature/video/ui/components/CommentSearchSheet.kt';body=original[path]
    body=body.replace('import android.widget.Toast\n','').replace('import androidx.compose.ui.platform.LocalClipboardManager\n','').replace('import androidx.compose.ui.platform.LocalContext','import coil3.compose.LocalPlatformContext as LocalContext\nimport com.bilipai.desktop.ui.LocalDesktopCommentBindings')
    body=replace_once(body,'    val clipboardManager = LocalClipboardManager.current','    val platform = LocalDesktopCommentBindings.current')
    body=replace_once(body,'clipboardManager.setText(AnnotatedString(entry.reply.content.message))','platform.copyText(entry.reply.content.message, "评论")')
    body=replace_once(body,'Toast.makeText(context, "评论已复制", Toast.LENGTH_SHORT).show()','platform.showFeedback("评论已复制")')
    emit('feature/video/ui/components/CommentSearchSheet.kt',body,path,'Full original local loaded-raw-reply search/filter/highlight/sort/search result UI and helpers. Existing clipboard/feedback and Coil context platform seams only; upstream glassActive=false is retained verbatim.')
    path=BASE+'feature/video/viewmodel/VideoComposerDraftState.kt';body=original[path].replace('import android.net.Uri\n','').replace('List<Uri>','List<String>')
    emit('feature/video/viewmodel/VideoComposerDraftState.kt',body,path,'Full original draft schema/key; Android Uri only maps to selected opaque file URI Strings. Ephemeral same-video state, no persistence or second player.')
    generate_composer(original,shared,emit,function)
    generate_input_and_fraud(original,shared,emit,function)
    p=output/'video-comment-source-identities.json';p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(dict(upstreamCommit=PIN,sources=identities,selections=selections),indent=2,ensure_ascii=False)+'\n',encoding='utf-8',newline='\n')
    for path,raw in original.items():
        p=output/'original-retained'/(path+'.txt');v=str(p.absolute());p=Path('\\\\?\\'+v);p.parent.mkdir(parents=True,exist_ok=True);p.write_text(raw,encoding='utf-8',newline='\n')

def generate_composer(original,shared,emit,function):
    raw=original[PLAYBACK]
    fields=raw[raw.index('    private val _showCommentDialog ='):raw.index('    // 表情包数据',raw.index('    private val _showCommentDialog ='))] if '    // 表情包数据' in raw[raw.index('    private val _showCommentDialog ='):] else ''
    # The first exact block owns comments and drafts only, before the emote block.
    start=raw.index('    private val _showCommentDialog =');end=raw.index('    // 表情包数据',start)
    fields=raw[start:end]
    emote_start=raw.index('    private val _emotePackages =',end);emote_end=raw.index('    // ========== 弹幕发送',emote_start)
    fields+=raw[emote_start:emote_end]
    fields+=function(raw,'updateCommentDraft')+'\n'
    send_start=raw.index('    private val _isSendingComment =');send_end=raw.index('\n',raw.index('    val commentSentEvent =',send_start))
    fields+=raw[send_start:send_end]+'\n'
    # Keep exact original reply/aid/event policies, original literal values included.
    helpers='\n\n'.join(function(raw,n) for n in ['resolveCommentReplyTargets','resolveCommentReplyMessage','resolveCommentSendTargetAid'])
    capacity=re.search(r'(?m)^internal fun resolvePlayerTransientEventChannelCapacity\(\): Int = .+$',raw);assert capacity
    helpers+='\n\n'+capacity.group()
    mask=shared.masked(raw);m=re.search(r'(?m)^data class CommentMentionSearchUiState\(',mask);assert m
    end=shared.balanced(mask,mask.index('(',m.start()));mention=raw[m.start():end]
    fields=fields.replace('List<Uri>','List<String>')
    comment_input=re.search(r'(?m)^    private val _commentInput = .+$',raw);assert comment_input
    fields=comment_input.group()+'\n    val commentInput = _commentInput.asStateFlow()\n'+fields
    fields=replace_once(fields,'val videoId = (_uiState.value as? VideoPlaybackUiState.Success)?.info?.bvid.orEmpty()','val videoId = info()?.bvid.orEmpty()')
    fields=replace_once(fields,'val current = _uiState.value as? VideoPlaybackUiState.Success','val current = info()')
    fields=replace_once(fields,'currentAid = current?.info?.aid','currentAid = current?.aid')
    fields=fields.replace('viewModelScope.launch','launchOwned').replace('return@launch','return@launchOwned')
    fields=fields.replace('com.android.purebilibili.data.repository.CommentRepository.getEmotePackages()','loadEmotePackages()')
    fields=fields.replace('com.android.purebilibili.data.repository.CommentRepository\n                .searchMentionUsers(query)','searchMentionUsers(query)')
    fields=replace_once(fields,'com.android.purebilibili.data.repository.CommentRepository\n                .addComment(','requests.addCommentForSubject(\n                    type = 1,')
    fields=replace_once(fields,'                    aid = sendAid,','                    oid = sendAid,')
    upload=function(fields,'uploadCommentPictures');fields=replace_once(fields,upload,'''    private suspend fun uploadCommentPictures(imageUris: List<String>): Result<List<ReplyPicture>> =
        withContext(Dispatchers.IO) {
            try { Result.success(imageUris.take(9).mapIndexed { index, uri ->
                ensureOwned(); requests.uploadCommentPicture(uri,index).getOrElse { throw it }
            }) } catch (cancelled: CancellationException) { throw cancelled }
            catch (failed: Exception) { ensureOwned(); Result.failure(failed) }
        }''')
    display=function(fields,'queryDisplayName');fields=replace_once(fields,display,'')
    while True:
        mask=shared.masked(fields);log=re.search(r'\bLogger\.[dwei]\s*\(',mask)
        if log is None:break
        end=shared.balanced(mask,mask.index('(',log.start()));fields=fields[:log.start()]+fields[end:]
    while True:
        mask=shared.masked(fields);log=re.search(r'android\.util\.Log\.[dwei]\s*\(',mask)
        if log is None:break
        start=fields.rfind('\n',0,log.start())+1;assert not fields[start:log.start()].strip();end=shared.balanced(mask,mask.index('(',log.start()));fields=fields[:start]+fields[end:]
    fields=re.sub(r'(\.onSuccess\s*\{(?:\s*\w+\s*->)?)',r'\1\n                    ensureOwned()',fields)
    fields=re.sub(r'(\.onFailure\s*\{(?:\s*\w+\s*->)?)',r'\1\n                    ensureOwned()',fields)
    # Windows coroutines can be dispatched after synchronous dismissal; the
    # original immutable reply capture remains before launch. Exact cancellation
    # releases only the matching submission busy state, never emits success.
    fields=replace_once(fields,'        launchOwned {\n            _isSendingComment.value = true','''        if (!requests.isOwned() || _isSendingComment.value) return
        val submission = ++submissionSequence
        _isSendingComment.value = true
        val submissionJob = launchOwned {''')
    send=function(fields,'sendComment')
    send=replace_once(send,'            _isSendingComment.value = false\n        }\n    }','''            _isSendingComment.value = false
        }
        submissionJob.invokeOnCompletion { failure ->
            if (failure is CancellationException && requests.isOwned() && submissionSequence == submission) {
                _isSendingComment.value = false
            }
        }
    }''')
    fields=replace_once(fields,function(fields,'sendComment'),send)
    header='''package com.android.purebilibili.feature.video.viewmodel
import com.android.purebilibili.data.model.response.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
/** Source-selected comment-only state; same Root Ops transport, no player/store. */
internal class DesktopOriginalVideoCommentComposer(
    private val scope: CoroutineScope,
    private val requests: DesktopVideoCommentRequests,
    private val info: () -> ViewInfo?,
    private val loadEmotePackages: suspend () -> Result<List<EmotePackage>>,
    private val searchMentionUsers: suspend (String) -> Result<List<MentionSearchUser>>,
    private val feedback: (String) -> Unit,
) {
    private var submissionSequence = 0L
    private suspend fun ensureOwned() {
        currentCoroutineContext().ensureActive()
        if (!requests.isOwned()) throw CancellationException("Video comment composer retired")
    }
    private fun launchOwned(block: suspend CoroutineScope.() -> Unit): Job = scope.launch { ensureOwned(); block() }
    private fun toast(message: String) { if (requests.isOwned()) feedback(message) }
'''
    emit('feature/video/viewmodel/DesktopOriginalVideoCommentComposer.kt',header+fields+'\n}\n\n'+mention+'\n\n'+helpers+'\n',PLAYBACK,'Original comment-only field/method blocks: full drafts, root/reply capture, lazy emotes, 250ms mention latest-query policy, stream9 send, original receipt channel and selected original reply/aid helpers. Existing required metadata/requests replace Android player lifecycle/context/singletons; matching cancellation releases busy only.')

def generate_input_and_fraud(original,shared,emit,function):
    path=BASE+'feature/video/screen/VideoDetailInputOverlayAdapter.kt';raw=original[path]
    begin=raw.index('@Immutable\nprivate data class CommentInputSnapshot(');end=raw.index('@OptIn(ExperimentalLayoutApi::class)',begin)
    types=raw[begin:end].replace('List<Uri>','List<String>')
    body=function(raw,'VideoDetailCommentInputOverlayContent')
    begin=raw.index('    val isSendingComment by viewModel.isSendingComment');end=raw.index('    val screenHeightPx =',begin)
    collect=raw[begin:end].replace('viewModel','composer')
    header='''package com.android.purebilibili.feature.video.screen
import androidx.compose.runtime.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.viewmodel.*
import com.android.purebilibili.feature.video.ui.components.CommentInputDialog
'''
    collect=collect.replace('.collectAsStateWithLifecycle()','.collectAsState()')
    collect='    val showCommentInput by composer.showCommentDialog.collectAsState()\n    val composerDrafts by composer.composerDrafts.collectAsState()\n'+collect
    owner='''@Composable internal fun DesktopOriginalVideoCommentInputOverlay(
    composer: DesktopOriginalVideoCommentComposer,
    commentState: CommentUiState,
    currentVideoPositionMsProvider: () -> Long,
) {
'''+collect+'}\n'
    emit('feature/video/screen/DesktopOriginalVideoCommentInputOverlay.kt',header+'\n'+types+'\n'+owner+'\n'+body+'\n',path,'Entire original comment snapshot/actions/input-content and original comment-only owner call block; existing full CommentInputDialog reused, required original composer/current actual position replace Android VideoPlaybackVM only.')
    path=BASE+'feature/video/screen/VideoDetailCommentFraudOverlayAdapter.kt';body=original[path]
    body=body.replace('import android.content.Context\n','').replace('import android.widget.Toast\n','').replace('import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackViewModel','import com.android.purebilibili.feature.video.viewmodel.DesktopOriginalVideoCommentComposer\nimport com.bilipai.desktop.ui.DesktopCommentPlatform')
    body=replace_once(body,'context: Context,','platform: DesktopCommentPlatform,')
    body=body.replace('playbackViewModel: VideoPlaybackViewModel','playbackViewModel: DesktopOriginalVideoCommentComposer').replace('context.applicationContext','platform')
    body=replace_once(body,'Toast.makeText(context, lightMessage, Toast.LENGTH_SHORT).show()','platform.showFeedback(lightMessage)')
    emit('feature/video/screen/VideoDetailCommentFraudOverlayAdapter.kt',body,path,'Full original receipt-to-genericVM-fraud event chain and result/light-message/delete UI, with required same-owner platform/composer types. Original type1 protocol and policy reused unchanged.')

if __name__=='__main__':
    cli=argparse.ArgumentParser();cli.add_argument('--repo',type=Path,required=True);cli.add_argument('--output',type=Path,required=True);args=cli.parse_args();generate(args.repo,args.output)
