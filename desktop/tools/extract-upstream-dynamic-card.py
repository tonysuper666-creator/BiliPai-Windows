"""Full original dynamic card and pure component closure; Windows platform seams.

Production direct inputs are copied only by prepareUpstreamSources. Isolated
compilation explicitly asks for standalone output of those exact bodies.
"""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse, hashlib, importlib.util, json
BASE = 'app/src/main/java/com/android/purebilibili/'
COMP = BASE+'feature/dynamic/components/'
DIRECT = [COMP+n+'.kt' for n in ['DynamicArchiveBadgePolicy','DynamicAdditionalCardPolicy','DynamicMajorCardPolicy','DynamicCardFoldPolicy','DynamicTimePolicy','DrawGridLayoutPolicy','ImagePreviewSourceAnchor','ZoomableImage','ZoomableImageGesturePolicy','ZoomableImageScalePolicy','DynamicOpusLinkCard','ImagePreviewTransitionPolicy','ImagePreviewDecodePolicy','ImagePreviewFeedbackPolicy']]+[BASE+'feature/dynamic/DynamicStatusPalette.kt',BASE+'feature/dynamic/DynamicLikeCountPolicy.kt',BASE+'feature/dynamic/DynamicForwardCountPolicy.kt','design-system/src/main/java/com/android/purebilibili/core/ui/UserAvatarCornerMark.kt',BASE+'core/ui/VideoCardTitleVisibility.kt']
SHARED=['design-system/src/main/java/com/android/purebilibili/core/ui/'+n+'.kt'for n in ['MediaContrastPalette','FeedContentTokens']]+[BASE+'feature/home/components/cards/'+n+'.kt'for n in ['HorizontalVideoCardStats','HorizontalVideoCardLayoutPolicy','VideoCardCoverOverlayTextPolicy']]
ADAPTED = [COMP+n+'.kt' for n in ['DynamicCard','DynamicCardClickPolicy','DrawGrid','ForwardedContent','LiveCard','ActionButton','VideoCards','DynamicVoteDialog','DynamicMenuActionPolicy']]+[BASE+'feature/dynamic/DynamicInteractionPolicy.kt']
PATHS = DIRECT+ADAPTED+SHARED+[BASE+'feature/dynamic/DynamicDeletePolicy.kt',BASE+'feature/dynamic/DynamicLayoutPolicy.kt',BASE+'feature/dynamic/model/LiveContentModels.kt',BASE+'core/store/SettingsManager.kt',BASE+'core/ui/common/TextSelectionPolicy.kt',BASE+'core/ui/common/TextSelectionBottomSheet.kt',BASE+'core/util/ModifierExt.kt',BASE+'data/repository/DynamicRepository.kt',BASE+'data/repository/DynamicVoteRepository.kt',BASE+'data/repository/SearchRepository.kt',BASE+'feature/dynamic/DynamicScreen.kt',COMP+'RepostDialog.kt',COMP+'DynamicEmoteCatalog.kt',COMP+'ImagePreviewDialog.kt',COMP+'LivePhotoPlayback.kt',COMP+'DynamicRichTextPolicy.kt']
def module(repo,name,path):
 s=importlib.util.spec_from_file_location(name,repo/path);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
def read(repo,path):return (_desktop_canonical_source(repo, path)).read_text(encoding='utf-8').replace('\r\n','\n')
def messages(repo):
 path=Path(__file__).with_name('extract-dynamic-message-share.py');spec=importlib.util.spec_from_file_location('dynamic_message_share',path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
def inventory(repo):return [dict(path=p,mode='platform-adapter-reference' if p in SHARED+[BASE+'core/util/ModifierExt.kt'] else 'direct' if p in DIRECT else 'policy-extract',features=['settings-dynamic-full-card-parity'],sha256=hashlib.sha256(read(repo,p).encode()).hexdigest()) for p in PATHS]+messages(repo).inventory(repo)
def generate(repo,output,standalone=False,shared_closure=False):
 host=module(repo,'full_card_host','desktop/tools/extract-upstream-plugins.py');media=host.media_extractor(repo);parser=media.parser_for(repo)
 appearance=module(repo,'full_card_decl','desktop/tools/extract-appearance-platform.py')
 output.mkdir(parents=True,exist_ok=True);files=[]
 def emit(path,body,name):files.append(host.write(output,path,read(repo,path),body,name))
 def replace(source,old,new):return host.substitute(source,old,new)
 def fun(s,n):return media.function(s,n,parser)
 def decl(s,n):
  tokens=parser.kotlin_tokens(s);found=[i for i,t in enumerate(tokens) if t[0] in ['class','interface'] and tokens[i+1][0]==n]
  assert len(found)==1,n
  start=found[0];index=start
  while tokens[index][0]!='{':index+=1
  depth=1
  while depth:
   index+=1;depth+=(tokens[index][0]=='{')-(tokens[index][0]=='}')
  begin=s.rfind('\n',0,tokens[start][1])+1
  return s[begin:tokens[index][2]]
 for p in DIRECT:
  if standalone:emit(p,read(repo,p),'DesktopOriginal'+Path(p).name)
 for p in SHARED:
  if shared_closure:emit(p,read(repo,p),'DesktopShared'+Path(p).name)
 # Original emote catalog and vote request builder retain the original cache,
 # retry and merge rules. Instance lifetime and live credentials are supplied
 # by a single captured desktop API owner rather than Android process globals.
 p=COMP+'DynamicEmoteCatalog.kt';s=read(repo,p)
 s=s.replace('import com.android.purebilibili.core.network.NetworkModule','import com.android.purebilibili.core.network.BilibiliApi')
 s=s.replace('import com.android.purebilibili.core.store.TokenManager','import kotlinx.coroutines.ensureActive\nimport kotlin.coroutines.coroutineContext')
 s=s.replace('internal object DynamicEmoteCatalog {','internal class DesktopDynamicEmoteCatalog(private val api: BilibiliApi, private val mid: () -> Long, private val authenticated: () -> Boolean, private val stillOwned: () -> Boolean) : com.bilipai.desktop.ui.DesktopDynamicEmotes {')
 s=s.replace('fun snapshot():','override fun snapshot():').replace('fun currentSessionKey():','override fun currentSessionKey():').replace('suspend fun ensureLoaded():','override suspend fun ensureLoaded():')
 s=s.replace('TokenManager.midCache ?: 0L','mid()').replace('!TokenManager.sessDataCache.isNullOrBlank()','authenticated()').replace('NetworkModule.api.getEmotes','api.getEmotes')
 s=s.replace('        loadedSessionKey == currentSessionKey()','        stillOwned() && loadedSessionKey == currentSessionKey()')
 s=s.replace('        val requestedSessionKey = currentSessionKey()','        coroutineContext.ensureActive()\n        if (!stillOwned()) throw kotlinx.coroutines.CancellationException("Dynamic emote owner retired")\n        val requestedSessionKey = currentSessionKey()')
 s=s.replace('        return mutex.withLock {','        return mutex.withLock {\n            coroutineContext.ensureActive()\n            if (!stillOwned()) throw kotlinx.coroutines.CancellationException("Dynamic emote owner retired")')
 s=s.replace('            cached = loadResult.entries','            coroutineContext.ensureActive()\n            if (!stillOwned()) throw kotlinx.coroutines.CancellationException("Dynamic emote owner retired")\n            cached = loadResult.entries')
 emit(p,s,'DesktopDynamicEmoteCatalog.kt')
 p=BASE+'data/repository/DynamicVoteRepository.kt';s=read(repo,p)
 s=s.replace('import com.android.purebilibili.core.network.NetworkModule','import com.android.purebilibili.core.network.DynamicApi').replace('import com.android.purebilibili.core.store.TokenManager','')
 s=s.replace('object DynamicVoteRepository {','internal class DesktopDynamicVoteRepository(private val dynamicApi: DynamicApi, private val csrfValue: () -> String, private val mid: () -> Long) {')
 s=s.replace('NetworkModule.dynamicApi','dynamicApi').replace('TokenManager.csrfCache.orEmpty()','csrfValue()').replace('TokenManager.midCache ?: 0L','mid()')
 emit(p,s,'DesktopDynamicVoteRepository.kt')
 p=BASE+'data/repository/SearchRepository.kt';s=read(repo,p)
 body='package com.android.purebilibili.data.repository\nimport com.android.purebilibili.core.network.SearchApi\nimport com.android.purebilibili.data.model.response.*\nimport com.android.purebilibili.data.repository.SearchRepository.SearchPageInfo\nimport kotlinx.coroutines.*\ninternal class DesktopDynamicUpSearch(private val api:SearchApi,private val sign:suspend(Map<String,String>)->Map<String,String>) {\n'
 for n in ['searchTypeParams','createPageInfo','createSearchError','searchUp']:
  f=fun(s,n).replace('signWithWbi(params)','sign(params)')
  if n=='searchUp':
   import re
   f=re.sub(r'\s*com\.android\.purebilibili\.core\.util\.Logger\.d\(\s*"SearchRepo",\s*" search\(up\):[^\n]+\n\s*\)','',f)
   f='\n'.join(l for l in f.splitlines()if'e.printStackTrace()'not in l and'Logger.e('not in l)
   f=f.replace('        Result.failure(e)','        if(e is CancellationException)throw e\n        Result.failure(e)')
  body+=f+'\n'
 emit(p,body+'}\n','DesktopDynamicUpSearch.kt')
 p=COMP+'RepostDialog.kt';emit(p,read(repo,p),'DesktopOriginalRepostDialog.kt')
 p=BASE+'feature/dynamic/DynamicScreen.kt';s=read(repo,p)
 begin=s.index('    pendingReport?.let { reportAction ->');end=s.index('    pendingMessageShare?.let',begin)
 body=s[begin:end].rstrip();body=body[body.index('\n')+1:body.rfind('\n    }')]
 body=body.replace('pendingReport = null','onDismiss()').replace('viewModel.reportDynamic(','onReport(').replace('android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()','onResult(msg)')
 body=body.replace('action = reportAction,','reportAction,').replace('reasonType = selectedReason.type,','selectedReason.type,').replace('reasonDesc = otherDesc','otherDesc')
 imports='import androidx.compose.runtime.*\nimport androidx.compose.foundation.clickable\nimport androidx.compose.foundation.layout.Column\nimport androidx.compose.ui.Modifier\nimport com.android.purebilibili.core.ui.*\nimport com.android.purebilibili.core.ui.components.*\n'
 emit(p,'package com.android.purebilibili.feature.dynamic.components\n'+imports+'@Composable\ninternal fun DesktopOriginalDynamicReportDialog(reportAction:DynamicManageAction.Report,onDismiss:()->Unit,onResult:(String)->Unit,onReport:(action:DynamicManageAction.Report,reasonType:Int,reasonDesc:String,onComplete:(Boolean,String)->Unit)->Unit) {\n'+body+'\n}\n','DesktopOriginalDynamicReportDialog.kt')
 for p in ADAPTED:
  s=read(repo,p)
  if p.endswith('DynamicInteractionPolicy.kt'):
   body=appearance.declarations(parser,s,['DynamicCommentTarget','DESKTOP_DYNAMIC_COMMENT_TYPES'])+'\n'+'\n'.join(fun(s,n)for n in ['toPositiveLongOrNull','resolveCommentTargetFromBasic','resolveDynamicCommentTarget','resolveDynamicCommentTargets'])
   emit(p,'package com.android.purebilibili.feature.dynamic\nimport com.android.purebilibili.data.model.response.*\n'+body,'DesktopOriginalDynamicCommentTargetPolicy.kt')
   continue
  s=s.replace('import androidx.compose.ui.platform.LocalContext','import coil3.compose.LocalPlatformContext as LocalContext')
  s=s.replace('import androidx.compose.foundation.isSystemInDarkTheme','import com.bilipai.desktop.appearance.isDesktopInDarkTheme as isSystemInDarkTheme')
  s=s.replace('import androidx.lifecycle.compose.collectAsStateWithLifecycle','import androidx.compose.runtime.collectAsState as collectAsStateWithLifecycle')
  s=s.replace('import coil3.imageLoader','import coil3.SingletonImageLoader').replace('context.imageLoader','SingletonImageLoader.get(context)')
  s=s.replace('import com.android.purebilibili.core.store.SettingsManager.DynamicDetailImageLayout','import com.android.purebilibili.core.store.DesktopDynamicCardSettings.DynamicDetailImageLayout')
  s=s.replace('import com.android.purebilibili.core.store.SettingsManager\n','')
  s=s.replace('import com.android.purebilibili.data.repository.DynamicRepository','import com.bilipai.desktop.ui.DesktopDynamicCardBindings as DynamicRepository')
  if p.endswith('DynamicCard.kt'):
   s=s.replace('import com.android.purebilibili.core.store.TokenManager','')
   s=s.replace('import com.android.purebilibili.data.repository.SearchRepository','')
   s=s.replace('TokenManager.midCache','null /* Windows host supplies its captured account MID. */')
   s=s.replace('    val onVideoClick = actions.navigation.onVideoClick','    val platform = com.bilipai.desktop.ui.LocalDesktopDynamicCardBindings.current\n    val onVideoClick = actions.navigation.onVideoClick',1)
   rich_start=s.index('fun RichTextContent(')
   s=s[:rich_start]+s[rich_start:].replace('    val context = LocalContext.current','    val platform = com.bilipai.desktop.ui.LocalDesktopDynamicCardBindings.current\n    val context = LocalContext.current',1)
   s=s.replace('DynamicEmoteCatalog.snapshot()','platform.emotes.snapshot()').replace('DynamicEmoteCatalog.currentSessionKey()','platform.emotes.currentSessionKey()').replace('DynamicEmoteCatalog.ensureLoaded()','platform.emotes.ensureLoaded()')
   s=s.replace('SearchRepository.searchUp(action.name)','platform.searchUp(action.name)')
   s=s.replace('    context: android.content.Context,','    context: coil3.PlatformContext,\n    platform: com.bilipai.desktop.ui.DesktopDynamicCardPlatform,')
   s=s.replace('            context = context,\n            uriHandler = uriHandler,','            context = context,\n            platform = platform,\n            uriHandler = uriHandler,')
   s=s.replace('com.android.purebilibili.core.ui.transition.videoPlayerSharedElementKey','com.bilipai.desktop.ui.dynamicVideoSharedElementKey')
   s=s.replace('import com.android.purebilibili.core.ui.transition.LocalDynamicImagePreviewTextVisible','import com.bilipai.desktop.ui.LocalDynamicImagePreviewTextVisible')
   # Platform clipboard/share/actions remain explicit user interactions.
   start=s.index('                                        val clipboard = context.getSystemService(');end=s.index('\n                                    },',start)
   s=s[:start]+'                                        platform.copyText(dynamicUrl)'+s[end:]
   start=s.index('                                        val shareIntent = android.content.Intent(');end=s.index('\n                                    },',start)
   s=s[:start]+'                                        platform.shareText(shareText)'+s[end:]
   # Toast is the Android feedback surface. Actual platform host presents feedback
   # through its owner-scoped AppAlertDialog rather than logging user content.
   import re
   s=re.sub(r'android\.widget\.Toast\.makeText\(\s*context,\s*(.*?),\s*android\.widget\.Toast\.LENGTH_SHORT,?\s*\)\.show\(\)',r'platform.showFeedback(\1)',s,flags=re.S)
   s=s.replace('context: android.content.Context','context: coil3.PlatformContext')
   # Android internal Intent fallback is one Windows route bridge; original
   # parser/target dispatch and callback ordering stay in this exact source.
   s=re.sub(r'val inAppIntent = android\.content\.Intent\(.*?\.addFlags\(android\.content\.Intent\.FLAG_ACTIVITY_NEW_TASK\)',lambda m:'val inAppIntent = '+('rawUrl' if 'parse(rawUrl)' in m.group() else 'searchUrl'),s,flags=re.S)
   s=s.replace('context.startActivity(inAppIntent)','platform.openLink(inAppIntent)')
   old=fun(s,'openDynamicRichTextLinkExternally')
   s=replace(s,old,'''private fun openDynamicRichTextLinkExternally(context: coil3.PlatformContext, url: String, uriHandler: androidx.compose.ui.platform.UriHandler, platform: com.bilipai.desktop.ui.DesktopDynamicCardPlatform) {
    if(platform.isOwned())platform.openLink(url)
}''')
   s=s.replace('openDynamicRichTextLinkExternally(context, searchUrl, uriHandler)','openDynamicRichTextLinkExternally(context, searchUrl, uriHandler, platform)')
   s=s.replace('                                    uriHandler\n                                )','                                    uriHandler, platform\n                                )').replace('                                uriHandler\n                            )','                                uriHandler, platform\n                            )')
  elif p.endswith('VideoCards.kt'):
   # Original static cover/info are preserved. Android's shared-element capture
   # remains an explicit missing platform seam, not an invented transition.
   start=s.index('    val configuration = LocalConfiguration.current');end=s.index('    val stationaryCoverRequest',start)
   s=s[:start]+s[end:]
   start=s.index('    val triggerClick = {');end=s.index('        VideoCardLargeCover(',start)
   s=s[:start]+'''    val coverShape = RoundedCornerShape(10.dp)
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
'''+s[end:]
   s=s.replace('overlayModifier = nativeCardSnapshot.coverOverlayModifier,','overlayModifier = Modifier,')
   start=s.index('            modifier = Modifier\n                .videoCardShellReturnCoverAlpha');end=s.index('\n        )',start)
   s=s[:start]+'            modifier = Modifier,'+s[end:]
   start=s.index('        Column(\n            modifier = Modifier.videoCardShellReturnChromeAlpha');end=s.index('            Spacer(',start)
   s=s[:start]+'        Column {\n'+s[end:]
   s='\n'.join(line for line in s.splitlines() if not any(x in line for x in ['import androidx.compose.ui.platform.LocalConfiguration','import com.android.purebilibili.core.ui.LocalAnimated','import com.android.purebilibili.core.ui.LocalShared','import com.android.purebilibili.core.ui.transition.','import com.android.purebilibili.core.util.CardPositionManager','import com.android.purebilibili.feature.home.components.cards.videoCardShellReturn']))+'\n'
  elif p.endswith('DynamicVoteDialog.kt'):
   s=s.replace('import com.android.purebilibili.data.repository.DynamicVoteRepository','import com.bilipai.desktop.ui.LocalDesktopDynamicCardBindings')
   s=s.replace('    val scope = rememberCoroutineScope()','    val platform = LocalDesktopDynamicCardBindings.current\n    val scope = rememberCoroutineScope()')
   s=s.replace('DynamicVoteRepository.getVoteInfo','platform.getVoteInfo').replace('DynamicVoteRepository.submitVote','platform.submitVote')
  elif p.endswith('ForwardedContent.kt'):
   s=s.replace('    val context = LocalContext.current','    val platform = com.bilipai.desktop.ui.LocalDesktopDynamicCardBindings.current\n    val context = LocalContext.current')
   s=s.replace('com.android.purebilibili.data.repository.DynamicRepository.rememberDynamicDetailSeed','com.bilipai.desktop.ui.DesktopDynamicCardBindings.rememberDynamicDetailSeed')
   import re
   s=re.sub(r'val inAppIntent = android\.content\.Intent\(.*?\.addFlags\(android\.content\.Intent\.FLAG_ACTIVITY_NEW_TASK\)','val inAppIntent = searchUrl',s,flags=re.S)
   s=s.replace('context.startActivity(inAppIntent)','platform.openLink(inAppIntent)')
  emit(p,s,'DesktopOriginal'+Path(p).name)
 p=BASE+'feature/dynamic/DynamicDeletePolicy.kt';emit(p,read(repo,p),Path(p).name)
 p=BASE+'feature/dynamic/model/LiveContentModels.kt';emit(p,read(repo,p),Path(p).name)
 p=BASE+'core/store/SettingsManager.kt';s=read(repo,p)
 enum=decl(s,'DynamicDetailImageLayout')
 body='''package com.android.purebilibili.core.store
import com.bilipai.desktop.plugins.DesktopPluginContext as Context
import com.bilipai.desktop.plugins.booleanPreferencesKey
import com.bilipai.desktop.settings.dynamicIntPreferencesKey as intPreferencesKey
import com.bilipai.desktop.settings.dynamicSettingsDataStore as settingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
object DesktopDynamicCardSettings {
'''+enum+'\n'
 lines=s.splitlines()
 for name in ['KEY_DYNAMIC_IMAGE_PREVIEW_TEXT_VISIBLE','KEY_DYNAMIC_DETAIL_IMAGE_LAYOUT','KEY_IMAGE_PREVIEW_LONG_PRESS_SAVE_ENABLED','KEY_IMAGE_PREVIEW_3D_PAGE_ENABLED']:
  i=next(i for i,line in enumerate(lines) if line.strip().startswith('private val '+name+' ='))
  body+=lines[i]+'\n'+(lines[i+1]+'\n' if lines[i].rstrip().endswith('=') else '')
 for name in ['DYNAMIC_DETAIL_IMAGE_LAYOUT_PREFS','CACHE_KEY_DYNAMIC_DETAIL_IMAGE_LAYOUT']:
  body+=next(line for line in s.splitlines() if line.strip().startswith('private const val '+name+' ='))+'\n'
 body+='    @Volatile private var dynamicDetailImageLayoutMemoryCache: DynamicDetailImageLayout? = null\n'
 for n in ['dynamicDetailImageLayoutPrefs','cacheDynamicDetailImageLayout','peekDynamicDetailImageLayout','getDynamicImagePreviewTextVisible','setDynamicImagePreviewTextVisible','getDynamicDetailImageLayout','setDynamicDetailImageLayout','getImagePreviewLongPressSaveEnabled','getImagePreview3dPageEnabled']:
  if n=='dynamicDetailImageLayoutPrefs':
   begin=s.index('    private fun dynamicDetailImageLayoutPrefs');end=s.index('\n\n',begin);f=s[begin:end]
  else:f=fun(s,n)
  f=f.replace('android.content.SharedPreferences','com.bilipai.desktop.plugins.DesktopPluginPreferences')
  body+='\n'+f+'\n'
 body+='}\n';emit(p,body,'DesktopDynamicCardSettings.kt')
 p=BASE+'core/ui/common/TextSelectionPolicy.kt';s=read(repo,p)
 body='\n'.join(line for line in s.splitlines() if line.startswith('import ') and not line.startswith('import android.'))
 methods=['resolveTitle','resolveCopyFeedbackMessage','formatInPlaceCopyFeedback','shouldShowActions','extractSelectedText','resolveShareTarget','copyTextToClipboard']
 # Only pure selection policy and the exact gesture loop. Clipboard is supplied
 # by the Windows AppText clipboard facade in the selection sheet host.
 methods=['resolveTitle','resolveCopyFeedbackMessage','formatInPlaceCopyFeedback','shouldShowActions','extractSelectedText','isPartialSelection','resolveSelectionHint']
 body='package com.android.purebilibili.core.ui.common\n'+body+'\nobject TextSelectionPolicy {\n'+'\n'.join(fun(s,n) for n in methods)+'\n}\n'+fun(s,'detectTapWithSelectionFriendly')+'\n'
 emit(p,body,'DesktopOriginalTextSelectionPolicy.kt')
 p=BASE+'core/ui/common/TextSelectionBottomSheet.kt';s=read(repo,p)
 imports='\n'.join(l for l in s.splitlines() if l.startswith('import ') and not l.startswith('import android.') and 'LocalContext'not in l)
 imports=imports.replace('import com.android.purebilibili.core.ui.AppModalBottomSheet','import com.bilipai.desktop.ui.DesktopDynamicTextSelectionSheet as AppModalBottomSheet')
 f=fun(s,'TextSelectionBottomSheet').replace('    val context = LocalContext.current','    val platform = com.bilipai.desktop.ui.LocalDesktopDynamicCardBindings.current')
 import re
 f=re.sub(r'copyPlainTextToClipboard\(context, (selectedText|text), ("所选内容"|resolvedTitle)\)',r'platform.copyText(\1)',f)
 f=re.sub(r'\s*if \(Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2\) \{\s*Toast.makeText\(context, "([^"]+)", Toast.LENGTH_SHORT\).show\(\)\s*\}',r'\n                            platform.showFeedback("\1")',f)
 f=f.replace('val shareIntent = TextSelectionPolicy.createShareIntent(shareTarget, resolvedTitle)\n                            context.startActivity(shareIntent)','platform.shareText(shareTarget)')
 emit(p,'@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)\npackage com.android.purebilibili.core.ui.common\n'+imports+'\n@Composable\n'+f+'\n','DesktopOriginalTextSelectionBottomSheet.kt')
 p=COMP+'ImagePreviewDialog.kt';s=read(repo,p)
 emit(p,'package com.android.purebilibili.feature.dynamic.components\n'+fun(s,'normalizeImageUrl')+'\n'+fun(s,'resolveImagePreviewPlaceholderCacheKey')+'\n'+fun(s,'resolveImageShareMimeType')+'\n','DesktopOriginalImageUrlPolicy.kt')
 # Stable removes the obsolete Quad footer. Select the complete renderer prefix
 # before the URL-policy functions, retaining every original preview declaration.
 body=s[:s.index('/**\n *  规范化图片 URL')]
 # Android navigation-bar animation has no existing desktop equivalent. Its
 # Activity/window lifecycle is removed below, so remove only this new helper.
 body=replace(body,fun(s,'animateWindowNavigationBarColor'),'')
 body='\n'.join(l for l in body.splitlines() if not (l.startswith('import android.') or l.startswith('import androidx.core.') or l.startswith('import androidx.navigationevent') or any(l.startswith('import '+x) for x in ['androidx.compose.ui.platform.LocalView','androidx.compose.ui.window.DialogWindowProvider','androidx.compose.ui.graphics.asComposeRenderEffect','com.android.purebilibili.core.ui.setWindowNavigationBarColor','com.android.purebilibili.core.ui.LocalPredictiveBackGestureEnabled','com.android.purebilibili.core.util.rememberHapticFeedback','androidx.media3.common.Player','coil3.imageLoader'])))+'\n'
 body=body.replace('import androidx.compose.ui.platform.LocalContext','import coil3.compose.LocalPlatformContext as LocalContext').replace('import androidx.lifecycle.compose.collectAsStateWithLifecycle','import androidx.compose.runtime.collectAsState as collectAsStateWithLifecycle')
 body=body.replace('.collectAsStateWithLifecycle(initialValue =','.collectAsStateWithLifecycle(initial =')
 body=body.replace('import com.android.purebilibili.core.store.SettingsManager','import com.android.purebilibili.core.store.DesktopDynamicCardSettings as SettingsManager')
 body+='\n@Composable\n'+fun(s,'LivePhotoIcon')+'\n@Composable\n'+fun(s,'LivePhotoOffIcon')+'\n'+fun(s,'resolveLivePhotoVideoUrl')+'\n'
 body=body.replace('    val token: Long,','    val token: Long,\n    val platform: com.bilipai.desktop.ui.DesktopDynamicCardPlatform,',1)
 body=body.replace('    val latestOnDismiss by rememberUpdatedState(onDismiss)','    val platform = com.bilipai.desktop.ui.LocalDesktopDynamicCardBindings.current\n    val latestOnDismiss by rememberUpdatedState(onDismiss)',1)
 body=body.replace('                token = requestToken,','                token = requestToken,\n                platform = platform,',1)
 body=body.replace('                decorFitsSystemWindows = false','')
 start=body.index('            val dialogView = LocalView.current');end=body.index('            ImagePreviewOverlayContent(',start)
 body=body[:start]+'            CompositionLocalProvider(com.bilipai.desktop.ui.LocalDesktopDynamicCardBindings provides request.platform) {\n'+body[end:]
 host_start=body.index('fun ImagePreviewOverlayHost(');host_end=body.index('\n@Composable\nprivate fun ImagePreviewOverlayContent',host_start)
 fragment=body[host_start:host_end];last=fragment.rfind('\n}');fragment=fragment[:last]+'\n    }'+fragment[last:];body=body[:host_start]+fragment+body[host_end:]
 body=body.replace('    val haptic = rememberHapticFeedback()','    val platform = com.bilipai.desktop.ui.LocalDesktopDynamicCardBindings.current\n    val haptic = com.bilipai.desktop.ui.rememberDynamicPlatformHaptic()')
 start=body.index('    //  获取 Activity 和 Window');end=body.index('    //  动画状态控制',start);body=body[:start]+body[end:]
 start=body.index('    val backEventState =');end=body.index('    var isDismissing',start);body=body[:start]+'    val backProgress = 0f // Android predictive back has no Windows gesture provider.\n'+body[end:]
 body=body.replace('SettingsManager.getImagePreviewLongPressSaveEnabled(context)','SettingsManager.getImagePreviewLongPressSaveEnabled(platform.context)').replace('SettingsManager.getImagePreview3dPageEnabled(context)','SettingsManager.getImagePreview3dPageEnabled(platform.context)')
 body=body.replace('context.imageLoader','coil3.SingletonImageLoader.get(context)')
 body=body.replace('mutableStateOf<Player?>','mutableStateOf<com.bilipai.desktop.ui.DesktopDynamicLivePhotoPlayer?>')
 start=body.index('    var pendingSaveAction');end=body.index('    // 当前页的图片 URL',start)
 body=body[:start]+'''    fun saveOwned(operation:suspend ()->Boolean,successMessage:String="图片已保存到所选文件") {
        if(isSaving)return
        isSaving=true
        scope.launch {
            try{handleImageSaveResult(operation(),successMessage)}
            catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}
            catch(error:Exception){platform.showFeedback(error.message?:"保存失败，请重试")}
            finally{isSaving=false}
        }
    }
    fun requestSaveCurrentImage(imageUrl:String) {
        if(imageUrl.isEmpty()||isSaving)return
        if(onImageLongPress!=null){onImageLongPress(imageUrl);return}
        saveOwned({platform.saveImage(imageUrl)})
    }
    fun requestSaveMotionPhoto(imageUrl:String,videoUrl:String) = saveOwned({platform.saveMotionPhoto(imageUrl,videoUrl)},"实况照片已保存到所选文件")
    fun requestSaveLivePhotoVideo(videoUrl:String) = saveOwned({platform.saveLivePhotoVideo(videoUrl)},"实况视频已保存到所选文件")
    fun requestSaveAllImages()=saveOwned({platform.saveImages(images.map(::normalizeImageUrl).filter(String::isNotEmpty))})
    fun requestShareCurrentImage(imageUrl:String) {
        if(imageUrl.isEmpty()||isSharing)return
        isSharing=true
        scope.launch {
            try{handleImageShareResult(platform.shareImage(imageUrl))}
            catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}
            catch(error:Exception){platform.showFeedback(error.message?:"分享失败，请重试")}
            finally{isSharing=false}
        }
    }
'''+body[end:]
 # Remove only Android predictive-back provider call; original dialog dismissal,
 # vertical drag, pager transforms and return morph remain intact.
 start=body.index('            NavigationBackHandler(');tokens=parser.kotlin_tokens(body);i=next(i for i,t in enumerate(tokens)if t[1]>=start)
 while tokens[i][0]!='(':i+=1
 depth=1
 while depth:i+=1;depth+=(tokens[i][0]=='(')-(tokens[i][0]==')')
 body=body[:start]+body[tokens[i][2]:]
 import re
 body=re.sub(r'Toast.makeText\(\s*context,\s*(.*?),\s*Toast.LENGTH_SHORT\s*\)\.show\(\)',r'platform.showFeedback(\1)',body,flags=re.S)
 body=body.replace('                            val clipboard = context.getSystemService(ClipboardManager::class.java)\n                            clipboard?.setPrimaryClip(ClipData.newPlainText("图片链接", currentImageUrl))','                            platform.copyText(currentImageUrl)')
 body=body.replace('LivePhotoPlayback(','com.bilipai.desktop.ui.DesktopDynamicLivePhotoPlayback(')
 emit(p,body,'DesktopOriginalImagePreviewRenderer.kt')
 # HapticType is now already produced by the installed original Home closure.
 # This lane only references that actual product enum, never redefines it.
 p=COMP+'LivePhotoPlayback.kt';s=read(repo,p)
 emit(p,'package com.android.purebilibili.feature.dynamic.components\n'+fun(s,'normalizeLivePhotoVideoUrl')+'\n','DesktopOriginalLivePhotoUrlPolicy.kt')
 p=COMP+'DynamicRichTextPolicy.kt';s=read(repo,p)
 # Existing topic source owns these exact declarations; no duplicate class/schema.
 for n in ['resolveDynamicRichTextTopicId','resolveDynamicRichTextLinkAction','resolveDynamicRichTextNodeToken','appendDynamicRichTextTopic']:
  s=replace(s,fun(s,n),'')
 for n in ['DYNAMIC_TOPIC_QUERY_ID_PATTERN','DYNAMIC_TOPIC_PATH_ID_PATTERN']:
  import re
  s=re.sub(r'private val '+n+r' =\n\s*[^\n]+\n','',s)
 prefixes=[line for line in s.splitlines()if line.startswith('internal const val DYNAMIC_RICH_TEXT_LINK_')]
 assert len(prefixes)==6,'Original rich-text payload prefix closure changed'
 for line in prefixes:s=replace(s,line+'\n','')
 s=replace(s,decl(s,'DynamicRichTextLinkAction'),'')
 # The existing topic producer owns this exact expression-bodied helper too.
 # Root exposes it internally so both original consumers use one declaration.
 annotation_start=s.index('private fun dynamicRichTextLinkAnnotation(')
 annotation_end=s.index('\n/** 动态富文本链接动作',annotation_start)
 s=replace(s,s[annotation_start:annotation_end],'')
 emit(p,s,'DesktopOriginalDynamicRichText.kt')
 p=BASE+'data/repository/DynamicRepository.kt';s=read(repo,p)
 begin=s.index('    private val detailSeeds =');end=s.index('\n    fun rememberDynamicDetailSeed',begin)
 emit(p,'package com.bilipai.desktop.ui\nimport com.android.purebilibili.data.model.response.DynamicItem\ninternal object DesktopDynamicCardBindings {\n'+s[begin:end]+'\n'+fun(s,'rememberDynamicDetailSeed')+'\n'+fun(s,'peekDynamicDetailSeed')+'\n}\n','DesktopOriginalDynamicDetailSeeds.kt')
 p=BASE+'feature/dynamic/DynamicLayoutPolicy.kt';s=read(repo,p)
 body=appearance.declarations(parser,s,['resolveDynamicCardOuterPadding','resolveDynamicCardContentPadding','resolveDynamicActionButtonSlotWeight','resolveDynamicActionButtonSpacing','resolveDynamicActionButtonText','formatDynamicActionCount'])
 emit(p,'package com.android.purebilibili.feature.dynamic\nimport androidx.compose.ui.unit.Dp\nimport androidx.compose.ui.unit.dp\n'+body,'DesktopOriginalDynamicCardLayout.kt')
 files+=messages(repo).generate(repo,output,standalone)
 return files
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--repo',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--standalone',action='store_true');a=p.parse_args();generate(a.repo,a.output,a.standalone)
