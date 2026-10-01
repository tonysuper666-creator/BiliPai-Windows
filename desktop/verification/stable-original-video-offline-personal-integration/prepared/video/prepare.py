"""Full stable Video information/action/engagement and shared gesture closure.
Task-only producer; no shared source, registry or build mutation.
"""
from pathlib import Path
import hashlib, importlib.util, json, re, subprocess, sys
sys.dont_write_bytecode=True
LANE=Path(__file__).resolve().parent
MAIN=LANE.parents[2]
REPO=MAIN.parent/'BiliPai-v023'
COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
BASE='app/src/main/java/com/android/purebilibili/'
def wide(p):
    s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def sha(t):return hashlib.sha256(t.encode() if isinstance(t,str) else t).hexdigest()
def write(p,t):
    wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(t.encode() if isinstance(t,str) else t)
def save(p,v):write(p,json.dumps(v,ensure_ascii=False,indent=2)+'\n')
def module(name,path):
    spec=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
parser=module('video_full_tokens',REPO/'desktop/tools/sync-upstream.py')
media=module('video_full_functions',REPO/'desktop/tools/extract-upstream-media.py')
selector=module('video_full_decls',REPO/'desktop/tools/extract-appearance-platform.py')
SOURCES={};OUTPUTS=[];ADAPTATIONS=[]
def read(path):
    if path not in SOURCES:
        raw=subprocess.check_output(['git','show',COMMIT+':'+path],cwd=REPO).replace(b'\r\n',b'\n')
        SOURCES[path]=dict(text=raw.decode('utf-8'),sha256LF=sha(raw),gitBlob=subprocess.check_output(['git','rev-parse',COMMIT+':'+path],cwd=REPO,text=True).strip())
        write(LANE/'original-stable'/path,raw)
    return SOURCES[path]['text']
def adapt(t,a,b,label):
    assert t.count(a)==1,(label,t.count(a));ADAPTATIONS.append(dict(label=label,before=a,after=b));return t.replace(a,b,1)
def emit(path,t,origin,mode):
    write(LANE/'generated'/path,t);OUTPUTS.append(dict(path=path,origin=origin,mode=mode,sha256LF=sha(t),physicalLines=len(t.splitlines())))
def function_range(t,name):
    matches=list(re.finditer(r'(?m)^[ \t]*(?:(?:internal|private|suspend|inline)\s+)*fun\s+(?:[\w.]+\.)?'+re.escape(name)+r'\s*\(',t));assert len(matches)==1,(name,len(matches))
    m=matches[0];tokens=parser.kotlin_tokens(t);i=next(i for i,(_,a,_)in enumerate(tokens) if a>=m.start())
    while tokens[i][0]!='(':i+=1
    depth=1
    while depth:i+=1;depth+=(tokens[i][0]=='(')-(tokens[i][0]==')')
    while tokens[i][0]!='{':i+=1
    depth=1
    while depth:i+=1;depth+=(tokens[i][0]=='{')-(tokens[i][0]=='}')
    return m.start(),tokens[i][2]
def func(t,name,annotations=True):
    a,b=function_range(t,name);s=t[a:b]
    if annotations:
        previous=t[:a].rstrip('\n');lines=[]
        while previous.split('\n')[-1].startswith('@'):
            lines.insert(0,previous.split('\n')[-1]);previous=previous[:previous.rfind('\n')]
        return '\n'.join(lines)+'\n'+s
    return s
def selected(t,names):return selector.declarations(parser,t,names)
def remove_function(t,name):
    i,end=function_range(t,name)
    start=t.rfind('@Composable',0,i)
    if start>=0 and not t[start+len('@Composable'):i].strip():return t[:start]+t[end:]
    return t[:i]+t[end:]

def main():
    # Complete shared gesture renderers and policies, not a reduced offline alias.
    paths=['feature/video/ui/gesture/GestureLevelOverlay.kt','feature/video/ui/gesture/GestureLevelOverlayPolicy.kt','feature/video/ui/gesture/Md3GestureLevelHapticPolicy.kt','feature/video/ui/components/AnimatedGesturePercentText.kt','feature/video/ui/components/CircularGesturePercentText.kt','feature/video/ui/components/CircularGesturePercentMotionPolicy.kt']
    for rel in paths:
        p=BASE+rel;t=read(p)
        t=t.replace('import android.os.SystemClock\n','import com.bilipai.desktop.ui.DesktopOriginalVideoGestureClock as SystemClock\n')
        if 'import android.os.Build\n' in t:
            t=t.replace('import android.os.Build\n','import com.bilipai.desktop.ui.desktopDetailRenderEffectsSupported\n')
            t=adapt(t,'Build.VERSION.SDK_INT >= Build.VERSION_CODES.S','desktopDetailRenderEffectsSupported()','Circular numeral actual desktop RenderEffect capability')
        emit('com/android/purebilibili/'+rel,t,p,'full-source-platform-adapt' if t!=read(p) else 'direct')
    p=BASE+'feature/video/ui/section/VideoPlayerSectionPolicy.kt';t=read(p)
    emit('com/android/purebilibili/feature/video/ui/section/DesktopOriginalVideoGestureMotion.kt','package com.android.purebilibili.feature.video.ui.section\nimport com.android.purebilibili.feature.video.ui.components.GesturePercentMotionDefaults\n'+selected(t,['VideoGestureMode','VideoGestureMotionSpec','resolveVideoGestureMotionSpec'])+'\n',p,'selected-original-motion')
    # Full action renderer: the already installed public TripleProgressIcon is referenced.
    p=BASE+'feature/video/ui/section/VideoActionSection.kt';t=read(p)
    t=remove_function(t,'TripleProgressIcon')
    emit('com/android/purebilibili/feature/video/ui/section/DesktopOriginalVideoActionSection.kt',t,p,'full-renderer-reference-existing-TripleProgressIcon')
    # Information: all original ordinary info functions. Existing BGM/team/honor renderers are sole owners.
    p=BASE+'feature/video/ui/section/VideoInfoSection.kt';original=read(p)
    header=original[:original.index('private val useMiuixSpring')]
    for line in ['import com.android.purebilibili.data.repository.VideoRepository\n','import com.android.purebilibili.data.repository.ViewGrpcRepository\n','import com.android.purebilibili.feature.video.screen.buildVideoNavigationOptions\n','import com.android.purebilibili.core.store.SettingsManager\n','import androidx.compose.ui.platform.LocalContext\n']:
        header=header.replace(line,'')
    header+='import coil3.compose.LocalPlatformContext\nimport com.bilipai.desktop.ui.LocalDesktopOriginalVideoInfoBindings\nimport com.android.purebilibili.core.store.DesktopOriginalVideoInfoSettings as SettingsManager\n'
    pure=original[original.index('private val useMiuixSpring'):original.index('private const val BGM_DISCOVERY_LOAD_DELAY_MS')]
    bodies=[]
    for name in ['VideoTitleSection','VideoDetailSponsorLabelChip','VideoTitleWithDesc','UpInfoSection','DescriptionSection']:
        body=func(original,name)
        body=body.replace('(String, android.os.Bundle?) -> Unit','(String, Long) -> Unit')
        body=body.replace('com.android.purebilibili.core.store.SettingsManager','SettingsManager')
        body=body.replace('val context = LocalContext.current','val context = LocalDesktopOriginalVideoInfoBindings.current.context')
        body=body.replace('getPlayerControlVisibilitySettings(LocalContext.current)','getPlayerControlVisibilitySettings(LocalDesktopOriginalVideoInfoBindings.current.context)')
        body=body.replace('ImageRequest.Builder(LocalContext.current)','ImageRequest.Builder(LocalPlatformContext.current)')
        body=body.replace('VideoRepository.getCreatorCardStats(info.owner.mid)','LocalDesktopOriginalVideoInfoBindings.current.getCreatorCardStats(info.owner.mid)')
        # CompositionLocals must be captured during composition, not inside produceState coroutine.
        if name=='UpInfoSection':
            body=adapt(body,'    val playerControlVisibility by','    val VideoRepository = LocalDesktopOriginalVideoInfoBindings.current\n    val playerControlVisibility by','Capture actual info request binding')
            body=body.replace('LocalDesktopOriginalVideoInfoBindings.current.getCreatorCardStats(info.owner.mid)','VideoRepository.getCreatorCardStats(info.owner.mid)')
        body=body.replace('InlineBgmSection(','DesktopOriginalInlineBgmSection(')
        if name=='VideoTitleWithDesc':
            body=body.replace('SettingsManager\n        .getVideoArgueMsgShown(context)','com.android.purebilibili.core.store.DesktopOriginalVideoMetadataSettings\n        .getVideoArgueMsgShown(context)')
        bodies.append(body)
    emit('com/android/purebilibili/feature/video/ui/section/DesktopOriginalVideoInfoSection.kt',header+pure+'\n\n'.join(bodies)+'\n',p,'full-ordinary-info-renderers-and-rich-description')
    p=BASE+'feature/video/ui/section/VideoInfoDisplayPolicy.kt';t=read(p)
    for name in ['resolveVideoHonorChipText','resolveVideoHonorJumpUrl','shouldShowCreatorTeamSection','resolveVideoDetailBadges','resolveCompactPublishTimeRowText']:
        t=remove_function(t,name)
    emit('com/android/purebilibili/feature/video/ui/section/DesktopOriginalVideoInfoDisplayPolicy.kt',t,p,'remaining-original-display-policy-reference-sole-shared')
    # Actual47 already owns this full original policy; reference it rather than emit duplicate FQNs.
    p=BASE+'feature/video/ui/VideoFollowVisualPolicy.kt';read(p)
    p='design-system/src/main/java/com/android/purebilibili/core/ui/AppPlayerChromeProfile.kt';emit('com/android/purebilibili/core/ui/AppPlayerChromeProfile.kt',read(p),p,'direct')
    p=BASE+'feature/video/ui/section/VideoInfoSection.kt';t=read(p)
    emit('com/android/purebilibili/feature/video/ui/section/DesktopOriginalVideoBgmTag.kt','package com.android.purebilibili.feature.video.ui.section\nimport com.android.purebilibili.data.model.response.BgmInfo\n'+selected(t,['resolveBgmTagInfo'])+'\n',p,'original-tag-helper-no-existing-emitter')
    p=BASE+'feature/video/ui/section/OwnerDecoratedAvatar.kt';t=read(p)
    t=t.replace('import androidx.compose.ui.platform.LocalContext','import coil3.compose.LocalPlatformContext').replace('import com.android.purebilibili.data.repository.VideoRepository','import com.bilipai.desktop.ui.LocalDesktopOriginalVideoInfoBindings')
    t=adapt(t,'    var card by remember(ownerMid)','    val VideoRepository = LocalDesktopOriginalVideoInfoBindings.current\n    var card by remember(ownerMid)','Capture current original avatar request owner')
    t=t.replace('LocalContext.current','LocalPlatformContext.current')
    emit('com/android/purebilibili/feature/video/ui/section/OwnerDecoratedAvatar.kt',t,p,'full-original-avatar-platform-adapt')
    p=BASE+'core/ui/common/Modifiers.kt';t=read(p)
    copy_body='''package com.android.purebilibili.core.ui.common
import androidx.compose.foundation.clickable
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.bilipai.desktop.ui.LocalDesktopOriginalVideoInfoBindings
'''+t[t.index('fun Modifier.copyOnLongPress('):t.index(': Modifier = this',t.index('fun Modifier.copyOnLongPress('))+len(': Modifier = this')]+'\n\n'+func(t,'copyOnClick',False)+'\n'
    handler=func(t,'rememberClipboardCopyHandler',annotations=True)
    handler=handler.replace('val context = LocalContext.current','val context = LocalDesktopOriginalVideoInfoBindings.current')
    handler=handler.replace('copyPlainTextToClipboard(context, text, label ?: "BiliPai")','context.copyText(text, label ?: "BiliPai")')
    a='''                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                    Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
                } else if (label != null) {
                    Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
                }'''
    handler=adapt(handler,a,'                context.showFeedback(toastMsg)','Windows feedback replaces Android clipboard toast capability branch')
    # Reply's existing clipboard helper owns the original same-package public name.
    # Only this local helper/callsite name changes; the original copy semantics stay intact.
    copy_body=copy_body.replace('rememberClipboardCopyHandler()','rememberVideoInfoClipboardCopyHandler()')
    handler=handler.replace('rememberClipboardCopyHandler','rememberVideoInfoClipboardCopyHandler')
    emit('com/android/purebilibili/core/ui/common/DesktopOriginalVideoCopyModifiers.kt',copy_body+handler+'\n',p,'complete-original-copy-modifier-and-platform-feedback')
    p=BASE+'data/repository/VideoRepository.kt';t=read(p)
    emit('com/android/purebilibili/data/repository/DesktopOriginalCreatorCardStats.kt','package com.android.purebilibili.data.repository\n'+selected(t,['CreatorCardStats'])+'\n',p,'original-model')
    # Same existing global settings adapter: original keys, getters, defaults and mirror cache.
    p=BASE+'core/store/SettingsManager.kt';t=read(p);manager=t[t.index('object SettingsManager {')+len('object SettingsManager {'):t.rfind('}')]
    names=['KEY_VIDEO_INFO_DEFAULT_EXPANDED','KEY_VIDEO_TAG_SIZE_PRESET','KEY_SHOW_PLAYER_CAST_BUTTON','KEY_SHOW_VIDEO_FOLLOW_BUTTON','KEY_COMPACT_PLAYER_CHROME','KEY_TRIPLE_JUMP_ENABLED','KEY_EASTER_EGG_ENABLED','getVideoInfoDefaultExpanded','setVideoInfoDefaultExpanded','getVideoTagSizePreset','setVideoTagSizePreset','getPlayerControlVisibilitySettings','setShowPlayerCastButton','setShowVideoFollowButton','setCompactPlayerChrome','getTripleJumpEnabled','getEasterEggEnabled','setEasterEggEnabled','isEasterEggEnabledSync']
    settings='''package com.android.purebilibili.core.store
import com.bilipai.desktop.plugins.DesktopPluginContext as Context
import com.bilipai.desktop.plugins.booleanPreferencesKey
import com.bilipai.desktop.settings.dynamicIntPreferencesKey as intPreferencesKey
import com.bilipai.desktop.settings.dynamicSettingsDataStore as settingsDataStore
import com.android.purebilibili.core.ui.components.AppTagChipSize
import kotlinx.coroutines.flow.*
'''+selected(t,['PlayerControlVisibilitySettings'])+'\ninternal object DesktopOriginalVideoInfoSettings {\n'+selected(manager,names)+'\n}\n'
    emit('com/android/purebilibili/core/store/DesktopOriginalVideoInfoSettings.kt',settings,p,'original-key-read-write-bridge-same-global-store')
    engagement()
    read(BASE+'core/util/AnalyticsHelper.kt') # Original event/default/privacy contract for the local diagnostics platform port.
    save(LANE/'source-inventory.json',dict(pinnedCommit=COMMIT,sourceIdentities=[dict(path=p,**{k:v for k,v in r.items() if k!='text'}) for p,r in SOURCES.items()],outputs=OUTPUTS,adaptations=ADAPTATIONS,scope='Complete original ordinary information/actions and shared gesture units. Full ordinary player overlays and page assembly are next, not yet claimed.',existingReferenceOwners=['metadata honor/team','BGM discovery full subtree','profile public TripleProgressIcon','Home resolveCompactPublishTimeRowText'],actualProductAcceptance=False))
    print(json.dumps(dict(sources=len(SOURCES),outputs=len(OUTPUTS)),ensure_ascii=False))
def engagement():
    p=BASE+'feature/video/viewmodel/VideoSubjectSnapshot.kt';t=read(p)
    emit('com/android/purebilibili/feature/video/viewmodel/VideoSubjectSnapshot.kt','package com.android.purebilibili.feature.video.viewmodel\nimport androidx.compose.runtime.Immutable\n'+selected(t,['VideoSubjectSnapshot'])+'\n',p,'complete-original-subject-schema')
    p=BASE+'feature/video/viewmodel/VideoDomainBinding.kt';emit('com/android/purebilibili/feature/video/viewmodel/VideoDomainBinding.kt',read(p),p,'direct')
    p=BASE+'feature/video/viewmodel/VideoEngagementViewModel.kt';t=read(p)
    header=t[:t.index('data class VideoEngagementSeed')]
    header=header.replace('import android.content.Context','import com.bilipai.desktop.plugins.DesktopPluginContext as Context')
    for s in ['import androidx.lifecycle.ViewModel\n','import androidx.lifecycle.viewModelScope\n','import com.android.purebilibili.core.store.TokenManager\n','import com.android.purebilibili.core.network.NetworkModule\n']:
        header=header.replace(s,'')
    header=header.replace('import com.android.purebilibili.core.store.SettingsManager','import com.android.purebilibili.core.store.DesktopOriginalVideoInfoSettings as SettingsManager')
    header+='import com.bilipai.desktop.ui.DesktopOriginalVideoEngagementEnvironment\nimport kotlinx.coroutines.*\n'
    models=t[t.index('data class VideoEngagementSeed'):t.index('private val DefaultVideoCoinBalanceLoader')]
    actions=t[t.index('interface VideoEngagementActions'):t.index('class VideoEngagementViewModel(')]
    actions=actions.replace('private val useCase: VideoInteractionUseCase = VideoInteractionUseCase()','private val useCase: VideoInteractionUseCase')
    actions=actions.replace('private class DefaultVideoEngagementActions','internal class DefaultVideoEngagementActions',1)
    body=t[t.index('class VideoEngagementViewModel('):t.index('internal fun VideoPlaybackUiState.Success.toEngagementSeed')]
    before='''class VideoEngagementViewModel(
    private val actions: VideoEngagementActions = DefaultVideoEngagementActions(),
    private val coinBalanceLoader: VideoCoinBalanceLoader = DefaultVideoCoinBalanceLoader
) : ViewModel() {'''
    after='''internal class VideoEngagementViewModel(private val environment: DesktopOriginalVideoEngagementEnvironment) {
    private val actions get() = environment.actions
    private val coinBalanceLoader get() = environment.coinBalanceLoader
    private val viewModelScope get() = environment.scope
    private fun updateOwned(transform: (VideoEngagementUiState) -> VideoEngagementUiState) =
        environment.commit { _uiState.update(transform) }
    private suspend fun sendOwned(event: VideoEngagementEvent) {
        currentCoroutineContext().ensureActive(); environment.assertOwned()
        _events.send(event)
    }
'''
    body=adapt(body,before,after,'Full EngagementVM uses required existing entry scope/actions/current owner')
    body=body.replace('private var appContext: Context? = null','private var appContext: Context? = environment.context')
    body=adapt(body,'private val _events = Channel<VideoEngagementEvent>(Channel.BUFFERED)','private val _events = Channel<Pair<Long?, VideoEngagementEvent>>(Channel.BUFFERED)','Original events retain transient subject generation in the existing channel')
    body=adapt(body,'val events = _events.receiveAsFlow()','val events = _events.receiveAsFlow().mapNotNull { (generation, event) ->\n        if (environment.isOwned() && _uiState.value.subject?.generation == generation) event else null\n    }','Reject queued old-subject events without changing original public event schema')
    body=body.replace('_uiState.update {','updateOwned {')
    body=body.replace('_events.send(', 'sendOwned(')
    # Undo the substitution in the one transport helper's actual channel send.
    body=body.replace('        sendOwned(event)\n    }','        _events.send(_uiState.value.subject?.generation to event)\n    }',1)
    body=body.replace('viewModelScope.launch {','viewModelScope.launch {\n            currentCoroutineContext().ensureActive(); environment.assertOwned();\n')
    body=body.replace('.onSuccess { result ->','.onSuccess { result ->\n                    currentCoroutineContext().ensureActive(); environment.assertOwned()')
    for n in ['following','liked','disliked','favorited','inWatchLater']:
        body=body.replace('.onSuccess { '+n+' ->','.onSuccess { '+n+' ->\n                    currentCoroutineContext().ensureActive(); environment.assertOwned()')
    body=body.replace('.onSuccess {\n','.onSuccess {\n                    currentCoroutineContext().ensureActive(); environment.assertOwned()\n')
    body=body.replace('.onFailure { emitMessage(it.message', '.onFailure { if (it is CancellationException) throw it; environment.assertOwned(); if (_uiState.value.subject?.generation == state.subject?.generation) emitMessage(it.message')
    body=body.replace('onResult?.invoke(liked)','environment.commit { if (_uiState.value.subject?.generation == state.subject?.generation) onResult?.invoke(liked) }').replace('onResult?.invoke(result)','environment.commit { if (_uiState.value.subject?.generation == state.subject?.generation) onResult?.invoke(result) }')
    # Original bind replaces the full seed atomically, and only under the same account/entry gate.
    a='        _uiState.value = VideoEngagementUiState(';b='        environment.commit { _uiState.value = VideoEngagementUiState('
    body=adapt(body,a,b,'Atomic initial engagement seed publication')
    body=adapt(body,'            followingMids = seed.followingMids\n        )','            followingMids = seed.followingMids\n        ) }','Close seed publication gate')
    # Coin balance is an async subject read, so do not write a replacement video's open dialog.
    body=adapt(body,'    fun openCoinDialog() {','    fun openCoinDialog() {\n        val capturedSubject = _uiState.value.subject','Capture coin balance subject generation')
    body=adapt(body,'            val balance = coinBalanceLoader.load()','            val balance = coinBalanceLoader.load()\n            currentCoroutineContext().ensureActive(); environment.assertOwned()\n            if (_uiState.value.subject?.generation != capturedSubject?.generation) return@launch','Reject coin balance after old subject retires')
    body=adapt(body,'    internal fun emitMessage(message: String) {','    internal fun emitMessage(message: String) {\n        val capturedGeneration = _uiState.value.subject?.generation','Capture delayed original feedback subject')
    body=adapt(body,'environment.assertOwned();\n sendOwned(VideoEngagementEvent.Message(message))','environment.assertOwned();\n            if (_uiState.value.subject?.generation != capturedGeneration) return@launch\n            sendOwned(VideoEngagementEvent.Message(message))','Reject replacement subject during original feedback launch')
    header+='import kotlinx.coroutines.flow.mapNotNull\n'
    emit('com/android/purebilibili/feature/video/viewmodel/VideoEngagementViewModel.kt',header+models+actions+body,p,'complete-original-engagement-state-actions-algorithm-owned-platform')
    factory='''internal fun originalVideoEngagementActions(useCase: VideoInteractionUseCase): VideoEngagementActions = DefaultVideoEngagementActions(useCase)
'''
    emit('com/android/purebilibili/feature/video/viewmodel/DesktopOriginalVideoEngagementActionsFactory.kt','package com.android.purebilibili.feature.video.viewmodel\nimport com.android.purebilibili.feature.video.usecase.VideoInteractionUseCase\n'+factory,p,'thin-existing-original-usecase-delegate')
    # Original default loader becomes a required owned API-backed loader, preserving sentinel/timeout policy.
    loader=t[t.index('private val DefaultVideoCoinBalanceLoader'):t.index('interface VideoEngagementActions')]
    loader=loader.replace('private val DefaultVideoCoinBalanceLoader = VideoCoinBalanceLoader {','internal fun originalVideoCoinBalanceLoader(api: com.android.purebilibili.core.network.BilibiliApi, hasSession: () -> Boolean, assertOwned: () -> Unit): VideoCoinBalanceLoader = VideoCoinBalanceLoader {\n    currentCoroutineContext().ensureActive(); assertOwned()')
    loader=loader.replace('TokenManager.sessDataCache.isNullOrEmpty()','!hasSession()').replace('NetworkModule.api.getNavInfo()','api.getNavInfo().also { currentCoroutineContext().ensureActive(); assertOwned() }')
    loader=loader.replace('} catch (_: Exception) {','} catch (_: Exception) {\n            assertOwned()')
    emit('com/android/purebilibili/feature/video/viewmodel/DesktopOriginalVideoCoinBalanceLoader.kt','package com.android.purebilibili.feature.video.viewmodel\nimport kotlinx.coroutines.*\n'+loader,p,'original-coin-read-timeout-policy-existing-owned-api')
    # Preserve the complete use case and its real required analytics/transport callbacks.
    p=BASE+'feature/video/usecase/VideoInteractionUseCase.kt';t=read(p)
    t=t.replace('import com.android.purebilibili.core.util.AnalyticsHelper','import com.bilipai.desktop.ui.DesktopOriginalVideoInteractionAnalytics')
    t=t.replace('import com.android.purebilibili.core.util.Logger','import com.bilipai.desktop.ui.DesktopOriginalVideoInteractionLog as Logger')
    t=t.replace('import com.android.purebilibili.data.repository.ActionRepository','import com.android.purebilibili.data.repository.DesktopOriginalVideoEngagementProtocol')
    t=adapt(t,'class VideoInteractionUseCase {','internal class VideoInteractionUseCase(\n    private val ActionRepository: DesktopOriginalVideoEngagementProtocol,\n    private val AnalyticsHelper: DesktopOriginalVideoInteractionAnalytics,\n) {','Original complete interaction usecase on existing owned transport and required analytics')
    emit('com/android/purebilibili/feature/video/usecase/VideoInteractionUseCase.kt',t,p,'complete-original-usecase-required-owned-dependencies')
    p=BASE+'feature/video/ui/feedback/TripleActionVisualStatePolicy.kt';emit('com/android/purebilibili/feature/video/ui/feedback/TripleActionVisualStatePolicy.kt',read(p),p,'direct')
    p=BASE+'feature/video/ui/feedback/VideoActionFeedbackMessagePolicy.kt';emit('com/android/purebilibili/feature/video/ui/feedback/VideoActionFeedbackMessagePolicy.kt',read(p),p,'direct')
    protocol()

def protocol():
    p=BASE+'data/repository/ActionRepository.kt';t=read(p);methods=[]
    names=['followUser','favoriteVideo','getDefaultFolderId','likeVideo','dislikeVideo','coinVideo','tripleAction','toggleWatchLater','checkLikeStatus','checkFavoriteStatus','checkFollowStatus','checkCoinStatus']
    for name in names:
        body=func(t,name,False)
        body=body.replace('TokenManager.csrfCache','readCsrf()').replace('TokenManager.midCache','readMid()').replace('TokenManager.sessDataCache','readSessData()').replace('TokenManager.accessTokenCache','readAccessToken()')
        body=body.replace('com.android.purebilibili.core.util.Logger','Logger').replace('android.util.Log.e','Logger.e')
        body=body.replace('_followStateChanges.tryEmit(FollowStateChange(mid = mid, isFollowing = follow))','confirmFollow(FollowStateChange(mid = mid, isFollowing = follow))')
        body=body.replace('withContext(Dispatchers.IO) {','withContext(Dispatchers.IO) {\n            currentCoroutineContext().ensureActive(); assertOwned()')
        # Guard response publication and any original success bus before updating UI consumers.
        body=body.replace('                if (response.code == 0) {','                currentCoroutineContext().ensureActive(); assertOwned()\n                if (response.code == 0) {')
        body=body.replace('                when (response.code) {','                currentCoroutineContext().ensureActive(); assertOwned()\n                when (response.code) {')
        body=body.replace('                when {','                currentCoroutineContext().ensureActive(); assertOwned()\n                when {')
        if name=='getDefaultFolderId':
            body=adapt(body,'            val response = api.getFavFolders(mid)','            currentCoroutineContext().ensureActive(); assertOwned()\n            val response = api.getFavFolders(mid)\n            currentCoroutineContext().ensureActive(); assertOwned()','Original default-folder read guarded before response consumption')
        body=body.replace('} catch (e: Exception) {','} catch (cancelled: CancellationException) {\n                throw cancelled\n            } catch (e: Exception) {\n                assertOwned()')
        methods.append(body)
    triple=selected(t[t.index('object ActionRepository {')+len('object ActionRepository {'):t.rfind('}')],['TripleResult'])
    header='''package com.android.purebilibili.data.repository
import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.model.response.FavFolder
import com.android.purebilibili.core.refresh.WatchLaterRefreshBus
import com.bilipai.desktop.ui.DesktopOriginalVideoInteractionLog as Logger
import kotlinx.coroutines.*

/** Original action bodies on the already-owned Root API; no network/session/store is built. */
internal class DesktopOriginalVideoEngagementProtocol(
    private val api: BilibiliApi,
    private val readCsrf: () -> String?,
    private val readMid: () -> Long?,
    private val readSessData: () -> String?,
    private val readAccessToken: () -> String?,
    private val assertOwned: () -> Unit,
    private val confirmFollow: (FollowStateChange) -> Unit,
    private val folderProtocol: DesktopOriginalFavoriteFolderProtocol,
) {
    suspend fun getFavoriteFolders(aid:Long?=null):Result<List<FavFolder>> = folderProtocol.getFavoriteFolders(aid)
    suspend fun updateFavoriteFolders(aid:Long,addFolderIds:Set<Long>,removeFolderIds:Set<Long>):Result<Boolean> = folderProtocol.updateFavoriteFolders(aid,addFolderIds,removeFolderIds)
'''
    emit('com/android/purebilibili/data/repository/DesktopOriginalVideoEngagementProtocol.kt',header+triple+'\n\n'+'\n\n'.join(methods)+'\n}\n',p,'original-raw-protocol-original-fields-errors-sequential-triple')
    # The original creator card cache has exactly one owner, within this current Ops/detail entry.
    p=BASE+'data/repository/VideoRepository.kt';t=read(p);body=func(t,'getCreatorCardStats',False)
    body=body.replace('withContext(Dispatchers.IO) {','withContext(Dispatchers.IO) {\n        currentCoroutineContext().ensureActive(); assertOwned()')
    body=body.replace('            if (response.code == 0 && data != null) {','            currentCoroutineContext().ensureActive(); assertOwned()\n            if (response.code == 0 && data != null) {')
    body=body.replace('} catch (e: Exception) {','} catch (cancelled: CancellationException) {\n            throw cancelled\n        } catch (e: Exception) {\n            assertOwned()')
    emit('com/android/purebilibili/data/repository/DesktopOriginalVideoCreatorCard.kt','''package com.android.purebilibili.data.repository
import com.android.purebilibili.core.network.BilibiliApi
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
internal class DesktopOriginalVideoCreatorCard(private val api:BilibiliApi,private val assertOwned:()->Unit) {
    private val creatorCardStatsCache = ConcurrentHashMap<Long, CreatorCardStats>()
'''+body+'\n}\n',p,'original-creator-read-cache-single-current-entry-authority')
    fragment='''// Desktop original complete video engagement/info binding
    private val videoCreatorCard by lazy { com.android.purebilibili.data.repository.DesktopOriginalVideoCreatorCard(api, ::assertOwned) }
    suspend fun getVideoCreatorCardStats(mid:Long):Result<com.android.purebilibili.data.repository.CreatorCardStats> = result {
        read { videoCreatorCard.getCreatorCardStats(mid).getOrThrow() }
    }
    internal fun originalVideoCoinBalanceLoader():com.android.purebilibili.feature.video.viewmodel.VideoCoinBalanceLoader =
        com.android.purebilibili.feature.video.viewmodel.originalVideoCoinBalanceLoader(api,
            { assertOwned(); !repository.authCookies()["SESSDATA"].isNullOrEmpty() }, ::assertOwned)
    internal fun originalVideoEngagementActions(analytics:com.bilipai.desktop.ui.DesktopOriginalVideoInteractionAnalytics):com.android.purebilibili.feature.video.viewmodel.VideoEngagementActions {
        val owner = repository.dynamicCacheSessionGuard.dynamicCacheOwner()
        val protocol = com.android.purebilibili.data.repository.DesktopOriginalVideoEngagementProtocol(api,
            { assertOwned(); repository.requireCsrf() },
            { assertOwned(); repository.account.value?.mid },
            { assertOwned(); repository.authCookies()["SESSDATA"] },
            { assertOwned(); repository.accessTokenCredentials().first }, ::assertOwned,
            { change -> assertOwned(); repository.followStateEvents.confirm(checkNotNull(owner),change) },
            com.android.purebilibili.data.repository.DesktopOriginalFavoriteFolderProtocol(api,
                { assertOwned(); repository.account.value?.mid }, { assertOwned(); repository.requireCsrf() }, ::assertOwned))
        val original = com.android.purebilibili.feature.video.viewmodel.originalVideoEngagementActions(
            com.android.purebilibili.feature.video.usecase.VideoInteractionUseCase(protocol,analytics))
        return object : com.android.purebilibili.feature.video.viewmodel.VideoEngagementActions {
            override suspend fun toggleFollow(mid:Long,currentlyFollowing:Boolean)=result { mutate { original.toggleFollow(mid,currentlyFollowing).getOrThrow() } }
            override suspend fun toggleLike(aid:Long,currentlyLiked:Boolean,bvid:String)=result { mutate { original.toggleLike(aid,currentlyLiked,bvid).getOrThrow() } }
            override suspend fun toggleDislike(aid:Long,currentlyDisliked:Boolean,bvid:String)=result { mutate { original.toggleDislike(aid,currentlyDisliked,bvid).getOrThrow() } }
            override suspend fun toggleFavorite(aid:Long,currentlyFavorited:Boolean,bvid:String)=result { mutate { original.toggleFavorite(aid,currentlyFavorited,bvid).getOrThrow() } }
            override suspend fun toggleWatchLater(aid:Long,currentlyInWatchLater:Boolean,bvid:String)=result { mutate { original.toggleWatchLater(aid,currentlyInWatchLater,bvid).getOrThrow() } }
            override suspend fun doCoin(aid:Long,count:Int,alsoLike:Boolean,bvid:String)=result { mutate { original.doCoin(aid,count,alsoLike,bvid).getOrThrow() } }
            override suspend fun doTripleAction(aid:Long)=result { mutate { original.doTripleAction(aid).getOrThrow() } }
        }
    }
'''
    write(LANE/'prepared/DesktopDynamicCardOperations.video-members.kt.fragment',fragment)
if __name__=='__main__':main()
