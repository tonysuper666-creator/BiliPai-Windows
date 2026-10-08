"""Full v0.2.3 message bodies; required existing Root/account/transport platform seams."""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse, hashlib, importlib.util, json, re
import v029_chat_sources
BASE='app/src/main/java/com/android/purebilibili/'
DIRECT=[BASE+'feature/message/'+n+'.kt' for n in ['ChatMessageMutationPolicy','MessageGlassSurfacePolicy','MessagePreviewParser']]+[BASE+'feature/message/feed/SystemNoticeContentPolicy.kt']
PATHS=DIRECT+[BASE+'feature/message/MessageCenterPolicy.kt']+[BASE+'feature/message/'+n+'.kt' for n in ['InboxViewModel','ChatViewModel','InboxScreen','ChatScreen','MessageCenterScreen','MessageGlassSurface']]+[BASE+'feature/message/feed/'+n+'.kt' for n in ['ReplyMeScreen','AtMeScreen','LikeMeScreen','SystemNoticeScreen','MessageFeedCommon']]+[BASE+'data/repository/MessageRepository.kt',BASE+'feature/message/MessageAppScaffold.kt']
MESSAGE_SCAFFOLD_SOURCE_SHA='1d23437ce71fdd275f1129cfa6b0d2c70a4787058c10573a451a2de96a630882'
MESSAGE_SCAFFOLD_OUTPUT_SHA='dd080776317163e8dca9c04e40893e4878f1430d4fb0d2b1ad741f3696ad594d'
MESSAGE_SCAFFOLD_LEAVES=[{'before': 'import androidx.compose.ui.platform.LocalContext', 'after': 'import com.bilipai.desktop.ui.LocalDesktopMessagePageOwner', 'count': 1, 'afterOffsets': [503]}, {'before': 'import androidx.lifecycle.compose.collectAsStateWithLifecycle', 'after': 'import androidx.compose.runtime.collectAsState as collectAsStateWithLifecycle\nimport kotlinx.coroutines.flow.map', 'count': 1, 'afterOffsets': [562]}, {'before': 'import com.android.purebilibili.core.store.SettingsManager\n', 'after': '', 'count': 1, 'afterOffsets': [742]}, {'before': '    val context = LocalContext.current', 'after': '    val context = LocalDesktopMessagePageOwner.current\n    if (!context.isOwned()) return', 'count': 1, 'afterOffsets': [1401]}, {'before': 'SettingsManager.getHomeWallpaperUri(context)', 'after': 'context.home.settings.homeWallpaperUri', 'count': 1, 'afterOffsets': [1581]}, {'before': 'SettingsManager.getSplashWallpaperUri(context)', 'after': 'context.home.settings.splashWallpaperUri', 'count': 1, 'afterOffsets': [1697]}, {'before': 'SettingsManager.getHomeWallpaperEffectMode(context)', 'after': 'remember(context) { context.home.settings.homeSettings.map { it.homeWallpaperEffectMode } }', 'count': 1, 'afterOffsets': [1810]}, {'before': 'SettingsManager.isDataSaverActive(context)', 'after': 'context.home.settings.isDataSaverActive()', 'count': 1, 'afterOffsets': [2144]}, {'before': 'initialValue =', 'after': 'initial =', 'count': 3, 'afterOffsets': [1657, 1770, 1929]}]

def message_scaffold_source(repo):
    path=BASE+'feature/message/MessageAppScaffold.kt';original=read(repo,path)
    assert hashlib.sha256(original.encode()).hexdigest()==MESSAGE_SCAFFOLD_SOURCE_SHA
    value=original;changes=[]
    for e in MESSAGE_SCAFFOLD_LEAVES:
        assert value.count(e['before'])==e['count'],e['before']
        cursor=0;positions=[]
        for _ in range(e['count']):
            at=value.index(e['before'],cursor);positions.append(at);value=value[:at]+e['after']+value[at+len(e['before']):];cursor=at+len(e['after'])
        changes.append((positions,e))
    inverse=value
    for positions,e in reversed(changes):
        for at in reversed(positions):
            assert inverse[at:at+len(e['after'])]==e['after'];inverse=inverse[:at]+e['before']+inverse[at+len(e['after']):]
    assert inverse==original
    assert hashlib.sha256(value.encode()).hexdigest()==MESSAGE_SCAFFOLD_OUTPUT_SHA
    return value

def read(repo,p):return(_desktop_canonical_source(repo, p)).read_text(encoding='utf-8').replace('\r\n','\n')
def inventory(repo):return [dict(path=p,mode='direct' if p in DIRECT else 'policy-extract',features=['stable-original-message-pages-root-parity'],sha256=hashlib.sha256(read(repo,p).encode()).hexdigest())for p in PATHS]
MESSAGE_EDITOR_HOLD_EDITS = [{'name': 'register-real-restored-pane-before-render', 'before': '    val pageOwner = LocalDesktopMessagePageOwner.current\n    androidx.compose.runtime.SideEffect { pageOwner.keepPaneChat(activeTalkerId, activeSessionType) }', 'after': '    val pageOwner = LocalDesktopMessagePageOwner.current\n    val retainedPaneChat = activeTalkerId != 0L && pageOwner.retainPaneChat(activeTalkerId, activeSessionType)\n    androidx.compose.runtime.SideEffect { pageOwner.keepPaneChat(activeTalkerId, activeSessionType) }', 'count': 1}, {'name': 'admit-new-pane-before-original-selection', 'before': '                    activeTalkerId = talkerId\n                    activeSessionType = sessionType\n                    activeUserName = userName', 'after': '                    pageOwner.selectPaneChat(talkerId, sessionType) {\n                        activeTalkerId = talkerId\n                        activeSessionType = sessionType\n                        activeUserName = userName\n                    }', 'count': 1}, {'name': 'render-only-the-actual-retained-chat', 'before': '            if (activeTalkerId != 0L) {\n                key(activeTalkerId, activeSessionType) {', 'after': '            if (activeTalkerId != 0L && retainedPaneChat) {\n                key(activeTalkerId, activeSessionType) {', 'count': 1}, {'name': 'compact-exit-retires-only-this-message-center-pane', 'before': '    if (!useTwoPane) {\n        InboxScreen(', 'after': '    if (!useTwoPane) {\n        val compactPageOwner = LocalDesktopMessagePageOwner.current\n        androidx.compose.runtime.SideEffect { compactPageOwner.keepPaneChat(0L, 0) }\n        InboxScreen(', 'count': 1}]
def message_editor_hold(body, out):
 before=body
 for edit in MESSAGE_EDITOR_HOLD_EDITS:
  assert body.count(edit['before'])==edit['count'],edit['name']
  body=body.replace(edit['before'],edit['after'])
 inverse=body
 for edit in reversed(MESSAGE_EDITOR_HOLD_EDITS):
  assert inverse.count(edit['after'])==edit['count'],edit['name']
  inverse=inverse.replace(edit['after'],edit['before'])
 assert inverse==before
 (out/'message-editor-hold-source-inventory.json').write_text(json.dumps({
  'path':'com/android/purebilibili/feature/message/MessageCenterScreen.kt',
  'initialDraftOrMessageBusinessChanged':False,'fullInverse':True,
  'beforeSha256LF':hashlib.sha256(before.encode()).hexdigest(),
  'afterSha256LF':hashlib.sha256(body.encode()).hexdigest(),'edits':MESSAGE_EDITOR_HOLD_EDITS
 },ensure_ascii=True,indent=2)+'\n',encoding='utf8',newline='\n')
 return body

def generate(repo,out,standalone=False):
 spec=importlib.util.spec_from_file_location('message_decl',repo/'desktop/tools/extract-appearance-platform.py');decl=importlib.util.module_from_spec(spec);spec.loader.exec_module(decl)
 spec=importlib.util.spec_from_file_location('message_host',repo/'desktop/tools/extract-upstream-plugins.py');host=importlib.util.module_from_spec(spec);spec.loader.exec_module(host);media=host.media_extractor(repo)
 parser=media.parser_for(repo);files=[]
 def emit(path,s):
  dest=out/path;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_text(s,encoding='utf-8');files.append(str(dest))
 def function(s,n):
  matches=list(re.finditer(r'(?m)^[ \t]*(?:(?:internal|private|suspend)\s+)*fun\s+(?:[\w.]+\.)?'+re.escape(n)+r'\s*\(',s));assert len(matches)==1,(n,len(matches))
  match=matches[0];tokens=parser.kotlin_tokens(s);i=next(i for i,(_,begin,_)in enumerate(tokens)if begin>=match.start())
  while tokens[i][0]!='(':i+=1
  depth=1
  while depth:i+=1;depth+=(tokens[i][0]=='(')-(tokens[i][0]==')')
  while tokens[i][0]!='{':i+=1
  depth=1
  while depth:i+=1;depth+=(tokens[i][0]=='{')-(tokens[i][0]=='}')
  return s[match.start():tokens[i][2]]
 def rmfun(s,n):return s.replace(function(s,n),'',1)
 def ui(s):
  s='\n'.join(l for l in s.splitlines()if not l.startswith(('import android.','import androidx.activity.','import androidx.lifecycle.')))+'\n'
  s=s.replace('import androidx.compose.ui.platform.LocalContext','import coil3.compose.LocalPlatformContext').replace('import androidx.compose.ui.platform.LocalConfiguration','import com.bilipai.desktop.ui.desktopMessageWindowConfiguration')
  s=s.replace('collectAsStateWithLifecycle','collectAsState').replace('LocalContext.current','LocalPlatformContext.current').replace('LocalConfiguration.current','desktopMessageWindowConfiguration()')
  for before,after in [('import com.android.purebilibili.core.ui.animation.jiggleOnDissolve', 'import com.bilipai.desktop.ui.jiggleOnDissolve'), ('com.android.purebilibili.core.ui.animation.MaybeDissolvableVideoCard(', 'com.bilipai.desktop.ui.DesktopReplyDissolvableContainer('), ('com.android.purebilibili.core.ui.animation.DissolveAnimationPreset.', 'com.bilipai.desktop.ui.DissolveAnimationPreset.')]:s=s.replace(before,after)
  s=re.sub(r'(?m)^fun (InboxScreen|ChatScreen|MessageCenterScreen|ReplyMeScreen|AtMeScreen|LikeMeScreen|SystemNoticeScreen)\(',r'internal fun \1(',s)
  package=re.search(r'^package [^\n]+',s,re.M).group()
  s=s.replace(package,package+'\nimport androidx.compose.runtime.collectAsState\nimport com.bilipai.desktop.ui.LocalDesktopMessagePageOwner\nimport com.bilipai.desktop.ui.rememberDesktopMessageImagePicker\nimport com.android.purebilibili.core.ui.LocalNavigationBackHandler as BackHandler',1)
  return s
 for p in DIRECT:
  if standalone:emit('com/android/purebilibili/'+p[len(BASE):],read(repo,p))
 # Home's existing sole producer owns the two unread count functions.
 s=read(repo,BASE+'feature/message/MessageCenterPolicy.kt')
 for name in ['totalPrivateUnreadCount','totalMessageUnreadCount']:s=rmfun(s,name)
 emit('com/android/purebilibili/feature/message/MessageCenterPolicy.kt',s)
 p=BASE+'data/repository/MessageRepository.kt';s=read(repo,p)
 s=s.replace('import com.android.purebilibili.core.network.NetworkModule','import com.android.purebilibili.core.network.MessageApi\nimport com.android.purebilibili.core.network.BilibiliApi\nimport com.bilipai.desktop.ui.DesktopMessagePageAdmission')
 s=s.replace('import com.android.purebilibili.core.store.TokenManager','')
 s=s.replace('object MessageRepository {','internal class DesktopOriginalMessageRepository(private val api: MessageApi, private val bilibiliApi: BilibiliApi, private val owner: DesktopMessagePageAdmission, private val deviceId: () -> String) {')
 s=s.replace('    private val api = NetworkModule.messageApi\n    private val bilibiliApi = NetworkModule.api\n','')
 s=s.replace('    private var deviceIdCache: String? = null','')
 body=function(s,'getDeviceId');s=s.replace(body,'    private fun getDeviceId(): String = deviceId()',1)
 s=s.replace('TokenManager.csrfCache','owner.csrf()').replace('TokenManager.midCache','owner.mid')
 s=re.sub(r'^\s*android\.util\.Log\.[^\n]*\n','\n',s,flags=re.M)
 s=re.sub(r'^\s*com\.android\.purebilibili\.core\.util\.Logger\.d\([^\n]*\n','\n',s,flags=re.M)
 begin=s.index('            // [Debug]');end=s.index('            if (response.code',begin);s=s[:begin]+s[end:]
 s=re.sub(r'catch \((\w+): Exception\) \{',lambda m:m.group(0)+'\n            if ('+m.group(1)+' is CancellationException) throw '+m.group(1),s)
 # Same original Result/error mapping; actual Call factory and post-HTTP owner checks remain mandatory.
 s=s.replace('withContext(Dispatchers.IO) {','withContext(Dispatchers.IO) {\n        owner.assertCurrent()')
 s=s.replace('Result.success(','owner.success(').replace('runCatching {','owner.runCatching {')
 if 'import kotlinx.coroutines.CancellationException' not in s:s=s.replace('import kotlinx.coroutines.Dispatchers','import kotlinx.coroutines.CancellationException\nimport kotlinx.coroutines.Dispatchers')
 emit('com/android/purebilibili/data/repository/DesktopOriginalMessageRepository.kt',s)
 for name in ['InboxViewModel','ChatViewModel']:
  p=BASE+'feature/message/'+name+'.kt';s=v029_chat_sources.read(repo,name+'.kt') if name=='ChatViewModel' else read(repo,p)
  s='\n'.join(l for l in s.splitlines()if not l.startswith(('import android.','import androidx.lifecycle.','import com.android.purebilibili.core.store.TokenManager','import com.android.purebilibili.data.repository.MessageRepository','import com.android.purebilibili.data.repository.VideoRepository')))+'\n'
  s=s.replace('package com.android.purebilibili.feature.message','package com.android.purebilibili.feature.message\nimport com.bilipai.desktop.ui.*')
  if name=='InboxViewModel':
   begin=s.index('data class UserBasicInfo(');end=s.index('data class InboxUiState',begin);s=s[:begin]+s[end:]
   s=s.replace('class InboxViewModel : ViewModel() {','internal class InboxViewModel(private val owner: DesktopMessagePageAdmission) {')
   s=s.replace('MessageUserInfoLoader.fetch','owner.fetchUserInfo')
  else:
   s=s.replace('class ChatViewModel(', 'internal class ChatViewModel(',1)
   s=s.replace('    private val sessionType: Int\n) : ViewModel() {','    private val sessionType: Int,\n    private val owner: DesktopMessagePageAdmission\n) {')
   s=s.replace('TokenManager.midCache ?: 0','owner.mid')
   s=s.replace('VideoRepository.getVideoDetails','owner.getVideoDetails').replace('result.onSuccess { (viewInfo, _) ->','result.onSuccess { viewInfo ->')
   s=re.sub(r'^\s*android\.util\.Log\.[^\n]*\n','\n',s,flags=re.M)
   s=s.replace('catch (e: Exception) {\n','catch (e: Exception) {\n                if (e is kotlinx.coroutines.CancellationException) throw e\n')
   s=rmfun(s,'queryDisplayName');begin=s.index('    class Factory(');s=s[:begin]+'}\n'
   s=s.replace('fun sendImageMessage(context: Context, imageUri: Uri)','fun sendImageMessage(image: DesktopMessageLocalImage)')
   begin=s.index('                    val bytes = context.contentResolver');end=s.index('                    if (bytes.isEmpty())',begin)
   s=s[:begin]+'                    val bytes = image.readBytes()\n\n'+s[end:]
   s=s.replace('context.contentResolver.getType(imageUri) ?: "image/jpeg"','image.mimeType')
   s=s.replace('queryDisplayName(context, imageUri)\n                        ?: "message_${System.currentTimeMillis()}.jpg"','image.fileName')
   s=s.replace('runCatching {\n                    val bytes','owner.runCatching {\n                    val bytes')
  s=s.replace('MutableStateFlow(InboxUiState())','owner.stateFlow(InboxUiState())').replace('MutableStateFlow(ChatUiState())','owner.stateFlow(ChatUiState())')
  s=s.replace('viewModelScope.launch','owner.launch').replace('MessageRepository.','owner.requests.')
  reads={'loadSessions':'inbox-list','refresh':'inbox-list','loadMoreSessions':'inbox-more'} if name=='InboxViewModel' else {'loadMessages':'chat-latest','loadMoreMessages':'chat-more','loadSessionControlInfo':'chat-control'}
  writes={'toggleTop','removeSession','toggleDnd','toggleIntercept','markDustbinRead','clearDustbinSessions'} if name=='InboxViewModel' else {'sendMessage','sendImageMessage','updateSessionControl','withdrawMessage','markAsRead'}
  for method,channel in reads.items():
   try:body=function(s,method)
   except Exception:continue
   dep=', dependsOn = "inbox-list"' if name=='InboxViewModel' and 'More' in method else ''
   new=body.replace('owner.launch {','owner.launchRead("'+channel+'"'+dep+') {',1)
   if 'More' not in method:new=new.replace('copy(isLoading = true, error = null)','copy(isLoading = true, isLoadingMore = false, isRefreshing = false, error = null)').replace('copy(isRefreshing = true, error = null)','copy(isLoading = false, isLoadingMore = false, isRefreshing = true, error = null)')
   if name=='ChatViewModel':new=new.replace('isRefreshing = false, ','')
   s=s.replace(body,new,1)
  for method in writes:
   try:body=function(s,method)
   except Exception:continue
   channel='chat-send' if method in {'sendMessage','sendImageMessage'} else method
   s=s.replace(body,body.replace('owner.launch {','owner.launchMutation("'+channel+'") {',1),1)
  if name=='ChatViewModel':
   s=v029_chat_sources.adapt_refresh(s)
   v029_chat_sources.record_adaptation(repo,out,name+'.kt',s)
  emit('com/android/purebilibili/feature/message/'+name+'.kt',s)
 emit('com/android/purebilibili/feature/message/ChatTimelinePolicy.kt',v029_chat_sources.read(repo,'ChatTimelinePolicy.kt'))
 from v029_history_failure_login import apply_history_first_login_source
 errorBody,_firstLoginAudit=apply_history_first_login_source(v029_chat_sources.read(repo,'ListLoadError.kt'),'loadError')
 v029_chat_sources.record_adaptation(repo,out,'ListLoadError.kt',errorBody)
 emit('com/android/purebilibili/feature/common/ListLoadError.kt',errorBody)
 for name in ['ReplyMeScreen','AtMeScreen','LikeMeScreen','SystemNoticeScreen']:
  p=BASE+'feature/message/feed/'+name+'.kt';s=ui(read(repo,p));vm=name.replace('Screen','ViewModel')
  s=s.replace('import com.android.purebilibili.data.repository.MessageRepository','import com.bilipai.desktop.ui.DesktopMessagePageAdmission')
  s=s.replace('class '+vm+' : ViewModel() {','internal class '+vm+'(private val owner: DesktopMessagePageAdmission) {')
  s=s.replace('MutableStateFlow(', 'owner.stateFlow(').replace('viewModelScope.launch','owner.launch').replace('MessageRepository.','owner.requests.')
  s=s.replace(vm+' = viewModel()',vm+' = LocalDesktopMessagePageOwner.current.'+name.replace('Screen','')[0].lower()+name.replace('Screen','')[1:])
  for method in ['loadInitial','refresh','loadMore','remove','toggleNotice','markAsRead']:
   try:body=function(s,method)
   except Exception:continue
   target='owner.launchRead("'+vm+'-list") {' if method in {'loadInitial','refresh'} else 'owner.launchRead("'+vm+'-more", dependsOn = "'+vm+'-list") {' if method=='loadMore' else 'owner.launchMutation("'+vm+'-'+method+'") {'
   new=body.replace('owner.launch {',target,1)
   if method in {'loadInitial','refresh'}:new=new.replace('copy(isLoading = true, error = null)','copy(isLoading = true, isLoadingMore = false, isRefreshing = false, error = null)').replace('copy(isRefreshing = true, error = null)','copy(isLoading = false, isLoadingMore = false, isRefreshing = true, error = null)')
   s=s.replace(body,new,1)
  emit('com/android/purebilibili/feature/message/feed/'+name+'.kt',s)
 p=BASE+'feature/message/feed/MessageFeedCommon.kt';s=ui(read(repo,p))
 # The installed BGM generator solely owns MessageFeedError. All other original declarations are retained.
 removed=function(s,'MessageFeedError');s=s.replace('@Composable\n'+removed,'',1)
 emit('com/android/purebilibili/feature/message/feed/DesktopOriginalMessageFeedCommon.kt',s)
 for name in ['InboxScreen','ChatScreen','MessageCenterScreen','MessageGlassSurface']:
  p=BASE+'feature/message/'+name+'.kt';s=ui(v029_chat_sources.read(repo,name+'.kt') if name=='ChatScreen' else read(repo,p))
  s=s.replace('InboxViewModel = viewModel()','InboxViewModel = LocalDesktopMessagePageOwner.current.inbox')
  s=s.replace('ChatViewModel = viewModel(factory = ChatViewModel.Factory(talkerId, sessionType))','ChatViewModel = LocalDesktopMessagePageOwner.current.chat(talkerId, sessionType)')
  if name=='ChatScreen':
   s=s.replace('import com.android.purebilibili.core.store.SettingsManager','')
   s=s.replace('import com.android.purebilibili.feature.home.components.cards.WallpaperPaletteStore','')
   s=s.replace('import com.android.purebilibili.core.util.PickGalleryVisualMedia','').replace('import com.android.purebilibili.core.ui.blur.shouldAllowRenderEffectBackedHazeEffect','')
   begin=s.index('    val imagePickerLauncher = rememberLauncherForActivityResult(');end=s.index('    val latestMessageKey =',begin)
   s=s[:begin]+'    val imagePickerLauncher = rememberDesktopMessageImagePicker { image -> image?.let(viewModel::sendImageMessage) }\n    \n'+s[end:]
   s=re.sub(r'imagePickerLauncher\.launch\(\s*PickVisualMediaRequest\(ActivityResultContracts\.PickVisualMedia\.ImageOnly\)\s*\)','imagePickerLauncher.launch()',s)
   s=s.replace('Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU','LocalDesktopMessagePageOwner.current.supportsRenderEffects').replace('shouldAllowRenderEffectBackedHazeEffect(Build.VERSION.SDK_INT)','LocalDesktopMessagePageOwner.current.supportsRenderEffects')
   s=s.replace('SettingsManager.getHomeWallpaperUri(context)','LocalDesktopMessagePageOwner.current.home.settings.homeWallpaperUri').replace('SettingsManager.getSplashWallpaperUri(context)','LocalDesktopMessagePageOwner.current.home.settings.splashWallpaperUri')
   s=s.replace('SettingsManager.getHomeWallpaperEffectMode(context)','LocalDesktopMessagePageOwner.current.wallpaperEffectMode').replace('SettingsManager.isDataSaverActive(context)','LocalDesktopMessagePageOwner.current.home.settings.isDataSaverActive()')
   s=s.replace('WallpaperPaletteStore.currentPalette','LocalDesktopMessagePageOwner.current.home.wallpaperPalette')
   s=s.replace('WallpaperPaletteStore.loadWallpaperPalette(\n            context = context,\n            uri = wallpaperUri,\n            scope = this,\n        )','LocalDesktopMessagePageOwner.current.home.loadWallpaperPalette(wallpaperUri, this)')
   begin=s.index('                                val intent = Intent(');end=s.index('\n                            }',begin)
   s=s[:begin]+'                                pageOwner.openLink(url)'+s[end:]
   s=s.replace('android.util.Log.e("RichMessageText", "Failed to open URL: $url", e)','pageOwner.feedback("无法打开链接")')
   for method in ['ChatScreen','ChatWallpaperHost','RichMessageText']:
    body=function(s,method);new=body.replace('    val context = LocalPlatformContext.current','    val pageOwner = LocalDesktopMessagePageOwner.current\n    val context = LocalPlatformContext.current',1)
    pos=new.index('    val pageOwner =');new=new[:pos]+new[pos:].replace('LocalDesktopMessagePageOwner.current','pageOwner');new=new.replace('val pageOwner = pageOwner','val pageOwner = LocalDesktopMessagePageOwner.current',1)
    new=new.replace('.collectAsState(initialValue = "")','.collectAsState()')
    new=new.replace('    val wallpaperEffectMode by pageOwner.wallpaperEffectMode\n        .collectAsState(initialValue = HomeWallpaperEffectMode.SOFT_BLUR)','    val homeSettings by pageOwner.home.settings.homeSettings.collectAsState()\n    val wallpaperEffectMode = homeSettings.homeWallpaperEffectMode')
    s=s.replace(body,new,1)
  if name=='MessageCenterScreen':
   s=s.replace('    val windowAdaptiveInfo = currentWindowAdaptiveInfoV2()','    val pageOwner = LocalDesktopMessagePageOwner.current\n    androidx.compose.runtime.SideEffect { pageOwner.keepPaneChat(activeTalkerId, activeSessionType) }\n    val windowAdaptiveInfo = currentWindowAdaptiveInfoV2()',1)
   s=message_editor_hold(s,out)
  if name=='ChatScreen':
   s=v029_chat_sources.adapt_screen(s,function)
   v029_chat_sources.record_adaptation(repo,out,name+'.kt',s)
  emit('com/android/purebilibili/feature/message/'+name+'.kt',s)
 emit('com/android/purebilibili/feature/message/MessageAppScaffold.kt',message_scaffold_source(repo))
 return files
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--repo',type=Path,required=True);p.add_argument('--out',type=Path);p.add_argument('--inventory',action='store_true');p.add_argument('--standalone',action='store_true');a=p.parse_args()
 if a.inventory:print(json.dumps(inventory(a.repo),ensure_ascii=False,indent=2))
 else:
  assert a.out;print(json.dumps(generate(a.repo,a.out,a.standalone),ensure_ascii=False,indent=2))
