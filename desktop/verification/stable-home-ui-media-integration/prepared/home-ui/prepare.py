from pathlib import Path
import json,re,hashlib,importlib.util
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023';PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith(PREFIX) else PREFIX+s)
def read(p):return safe(p).read_text(encoding='utf-8-sig').replace('\r\n','\n')
def write(p,s):safe(p).parent.mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
def load(n,p):
 s=importlib.util.spec_from_file_location(n,p);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
parser=load('homepage_parser',REPO/'desktop/tools/sync-upstream.py');appearance=load('homepage_decl',REPO/'desktop/tools/extract-appearance-platform.py')
def declarations(s):
 t=parser.kotlin_tokens(s);depth=parens=brackets=0;starts=[]
 for i,(v,a,b) in enumerate(t):
  if depth==0 and parens==0 and brackets==0 and v in ['fun','val','var','class','object','interface','typealias']:
   if v=='fun':
    j=i+1
    while j<len(t) and t[j][0]!='(':j+=1
    name=t[j-1][0] if j<len(t) else '?'
   else:name=t[i+1][0]
   line=s.rfind('\n',0,a)+1
   while line>0:
    prev=s.rfind('\n',0,line-1)+1
    if s[prev:line].strip().startswith('@'):line=prev
    else:break
   starts.append((name,line))
  depth+=(v=='{')-(v=='}');parens+=(v=='(')-(v==')');brackets+=(v=='[')-(v==']')
 return [(name,s[begin:starts[i+1][1] if i+1<len(starts) else len(s)].rstrip()) for i,(name,begin) in enumerate(starts)]

rows=json.loads(read(HERE/'selected-closure-audit.json'))['paths']
# Android/shared services have explicit required Root ports, never duplicate their clients/stores.
exclude={'BottomBar','ReduceMotion','ModifierExt','VideoShareSheet','SystemBarCompat','WindowSizeUtils','SubscriptionFeedPage','AdaptivePullToRefreshBox','ComfortablePullToRefreshBox','AdaptivePullToRefreshPolicy','PullRefreshUiPolicy','HomeNavigationIconPolicy'}
report=[]
for row in rows:
 path=row['path'];name=Path(path).stem
 if name in exclude:continue
 s=read(REPO/path);pkg=re.search(r'(?m)^package (\S+)',s)[1]
 ns=row['selectedDeclarations']
 if not ns:continue
 # HomeScreen body selected in full but excluded from partial category compile until its required ports are emitted.
 body='\n\n'.join(d for n,d in declarations(s) if n in ns)+'\n'
 imports=[x for x in s.splitlines() if x.startswith('import ')]
 text='package '+pkg+'\n\n'+'\n'.join(imports)+'\n\n'+body
 changes=[]
 def replace(before,after):
  global text
  if before in text:text=text.replace(before,after);changes.append(dict(before=before,after=after))
 for before,after in [
  ('import androidx.compose.ui.res.stringResource','import com.bilipai.desktop.ui.desktopHomeStringResource as stringResource'),
  ('import com.android.purebilibili.R\n',''),
  ('import androidx.compose.ui.platform.LocalContext','import coil3.compose.LocalPlatformContext as LocalContext'),
  ('import androidx.compose.ui.platform.LocalConfiguration','import com.bilipai.desktop.ui.DesktopHomeCardWindowMetrics as LocalConfiguration'),
  ('import androidx.lifecycle.compose.collectAsStateWithLifecycle','import androidx.compose.runtime.collectAsState as collectAsStateWithLifecycle'),
  ('import com.android.purebilibili.core.ui.animation.DissolveAnimationPreset','import com.bilipai.desktop.ui.DissolveAnimationPreset'),
  ('import com.android.purebilibili.core.ui.animation.MaybeDissolvableVideoCard','import com.bilipai.desktop.ui.DesktopReplyDissolvableContainer as MaybeDissolvableVideoCard'),
  ('import com.android.purebilibili.core.ui.animation.DissolvableVideoCard','import com.bilipai.desktop.ui.DesktopReplyDissolvableContainer as DissolvableVideoCard'),
  ('import com.android.purebilibili.core.ui.animation.jiggleOnDissolve','import com.bilipai.desktop.ui.jiggleOnDissolve'),
  ('import android.os.SystemClock','import com.bilipai.desktop.ui.DesktopHomeClock as SystemClock'),
  ('import android.os.Build\n',''),
  ('shouldAllowHomeChromeLiquidGlass(Build.VERSION.SDK_INT)','com.bilipai.desktop.ui.LocalDesktopHomePlatform.current.supportsHomeChromeLiquidGlass'),
  ('shouldAllowDirectHazeLiquidGlassFallback(Build.VERSION.SDK_INT)','com.bilipai.desktop.ui.LocalDesktopHomePlatform.current.supportsDirectHazeLiquidGlassFallback'),
  ('Build.VERSION_CODES.S','31'),
 ]:replace(before,after)
 if name=='SettingsManager':
  # Only original selected data schemas/enums; imports are narrowed, no SettingsManager singleton.
  text='package '+pkg+'\nimport com.android.purebilibili.core.theme.*\nimport com.android.purebilibili.feature.settings.*\nimport com.bilipai.desktop.ui.DesktopFavoriteNavigationTypes\nimport com.android.purebilibili.core.ui.transition.*\n\n'+body
 text=text.replace('SettingsManager.BottomBarVisibilityMode','com.bilipai.desktop.ui.DesktopFavoriteNavigationTypes.BottomBarVisibilityMode')
 text=text.replace('com.android.purebilibili.core.store.com.bilipai.desktop.ui.DesktopFavoriteNavigationTypes','com.bilipai.desktop.ui.DesktopFavoriteNavigationTypes')
 text=text.replace('com.android.purebilibili.core.store.SettingsManager.TopTabLabelMode.TEXT_ONLY','2')
 if name=='HomeScreen':
  replace('initialValue =','initial =')
  replace('rememberDrawerState(initial =','rememberDrawerState(initialValue =')
  replace('animate(\n                initial =','animate(\n                initialValue =')
  replace('animate(\n                    initial =','animate(\n                    initialValue =')
  replace('viewModel: HomeViewModel = viewModel(),','viewModel: com.bilipai.desktop.ui.DesktopOriginalHomeStateOwner,')
  replace('import androidx.lifecycle.viewmodel.compose.viewModel\n','')
  replace('import android.annotation.SuppressLint\n','')
  replace('import android.content.Context\n','')
  text=re.sub(r'(?m)^@SuppressLint[^\n]*\n','',text)
  replace('val context = LocalContext.current','val context = LocalContext.current\n    val platform = com.bilipai.desktop.ui.LocalDesktopHomeEnvironment.current')
  replace('val uiSkinState by rememberUiSkinState(context)','val uiSkinState = com.android.purebilibili.core.plugin.skin.LocalUiSkinState.current')
  replace('import com.android.purebilibili.core.plugin.skin.rememberUiSkinState\n','')
  for key,field in [('HomeTopTabSettings','topTabs'),('HomeSettings','homeSettings'),('AppNavigationSettings','navigation'),('ShowOnlineCount','showOnlineCount'),('HomeFeedCardStyle','homeFeedCardStyle'),('HomeWallpaperUri','homeWallpaperUri'),('SplashWallpaperUri','splashWallpaperUri')]:
   text=re.sub(r'SettingsManager\s*\.get'+key+r'\(context\)', 'platform.settings.'+field,text)
  # Each Root projection is an already-initialized StateFlow. Keep its actual snapshot, not schema defaults.
  for before in ['initial = com.android.purebilibili.core.store.HomeTopTabSettings(),','initial = com.android.purebilibili.core.store.HomeSettings(),','initial = AppNavigationSettings(),','initial = false','initial = com.android.purebilibili.core.store.HomeFeedCardStyle.BILIPAI,','initial = ""']:
   replace(before,'')
  replace('com.android.purebilibili.core.util.AnalyticsHelper.logHomeReturnAnimationPerformance','platform.analytics.logHomeReturnAnimationPerformance')
  replace('shouldAllowRenderEffectBackedHazeEffect(Build.VERSION.SDK_INT)','com.bilipai.desktop.ui.LocalDesktopHomePlatform.current.supportsRenderEffectBackedHaze')
  replace('import coil3.imageLoader','import coil3.SingletonImageLoader')
  replace('context.imageLoader.execute(request)','SingletonImageLoader.get(context).execute(request)')
  replace('SettingsManager.isDataSaverActive(context)','platform.settings.isDataSaverActive()')
  replace('com.android.purebilibili.core.store.platform.settings','platform.settings')
  for n in ['setEasterEggEnabled','setTabletUseSidebar','setGridColumnCountCompact','setGridColumnCount']:
   replace('SettingsManager.'+n+'(context,','platform.settings.'+n+'(')
  replace('com.android.purebilibili.core.plugin.PluginStore\n        .isEnabledFlow(context, com.android.purebilibili.feature.plugin.SubscriptionFeedPlugin.PLUGIN_ID)','platform.subscriptionPluginEnabled')
  replace('context = context,\n            installedPlugins','context = platform.pluginContext,\n            installedPlugins')
  replace('com.android.purebilibili.core.util.AnalyticsHelper.logScreenView','platform.analytics.logScreenView')
  replace('com.android.purebilibili.core.util.AnalyticsHelper.logCategoryView','platform.analytics.logCategoryView')
  start=text.index('    val view = androidx.compose.ui.platform.LocalView.current');end=text.index('    // 解构设置值',start)
  before=text[start:end];after='    SideEffect { platform.ensureEdgeToEdge() }\n\n';text=text[:start]+after+text[end:];changes.append(dict(before=before,after=after))
  start=text.index('    if (!view.isInEditMode && shouldApplyHomeSystemBars');end=text.index('    val homeCoverRequestSpec',start)
  before=text[start:end];after='    if (shouldApplyHomeSystemBars(isTopLevelActive)) {\n        SideEffect { platform.applyHomeSystemBars(useDarkStatusBarIcons,isLightBackground) }\n    }\n\n';text=text[:start]+after+text[end:];changes.append(dict(before=before,after=after))
  replace('val prefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }','val prefs = remember(platform) { platform.globalNamespace("app_prefs") }')
  replace('val welcomePrefs = remember { context.getSharedPreferences("app_welcome", Context.MODE_PRIVATE) }','val welcomePrefs = remember(platform) { platform.globalNamespace("app_welcome") }')
  replace('com.android.purebilibili.feature.home.components.cards.WallpaperPaletteStore.currentPalette','platform.wallpaperPalette')
  replace('com.android.purebilibili.feature.home.components.cards.WallpaperPaletteStore.loadWallpaperPalette(\n                    context = context,\n                    uri = homeWallpaperUri,\n                    scope = this\n                )','platform.loadWallpaperPalette(homeWallpaperUri, this)')
  replace('androidx.lifecycle.compose.LocalLifecycleOwner.current','platform.lifecycleOwner')
  replace('Build.VERSION.SDK_INT <= 31','com.bilipai.desktop.ui.LocalDesktopHomePlatform.current.legacyTopChromeSafetyGapRequired')
  for imported in ['com.android.purebilibili.feature.bangumi.HomeBangumiTabPage','com.android.purebilibili.feature.live.LiveListScreen','com.android.purebilibili.feature.partition.PartitionContent']:
   replace('import '+imported+'\n','')
  for n in ['PartitionContent','LiveListScreen','HomeBangumiTabPage']:
   text=re.sub(r'(?<![\w.])'+n+r'\(', 'platform.pages.'+n+'(',text)
  replace('com.android.purebilibili.feature.home.subscription.SubscriptionFeedPage(','platform.pages.SubscriptionFeedPage(')
  replace('com.android.purebilibili.feature.video.share.VideoShareSheetHost(','platform.overlays.VideoShareSheetHost(')
  replace('com.android.purebilibili.feature.home.components.CrashTrackingConsentDialog(','platform.overlays.CrashTrackingConsentDialog(')
  replace('com.android.purebilibili.feature.download.DownloadManager\n                                .saveImageToGallery(context, coverUrl, item.title)','platform.saveCover(coverUrl, item.title)')
  text=re.sub(r'android\.widget\.Toast\.makeText\(context, ([^\n]+), android\.widget\.Toast\.LENGTH_SHORT\)\.show\(\)',r'platform.feedback(\1)',text)
  replace('androidx.activity.compose.BackHandler','com.android.purebilibili.core.ui.LocalNavigationBackHandler')
 if name=='HomeWallpaperBackdrop':
  # Original whole wallpaper visual body; only Android moving-media sink becomes a required native port.
  body='\n\n'.join(d for n,d in declarations(s) if n in ['HomeWallpaperBackdrop','isStaticHomeWallpaperUri'])+'\n'
  text='package '+pkg+'\n'+ '\n'.join(x for x in s.splitlines() if x.startswith('import ') and 'videoCardTransitionBackgroundEffect' not in x)+'\n'+body
  text=text.replace('import androidx.compose.ui.platform.LocalContext','import coil3.compose.LocalPlatformContext as LocalContext').replace('import androidx.compose.ui.platform.LocalConfiguration','import com.bilipai.desktop.ui.DesktopHomeCardWindowMetrics as LocalConfiguration')
  replace('com.android.purebilibili.core.ui.wallpaper.WallpaperMedia(','com.bilipai.desktop.ui.LocalDesktopHomeMediaPorts.current.wallpaper.wallpaperSurface(')
 if name=='VideoSharePolicy':
  text='package '+pkg+'\n'+ '\n\n'.join(d for n,d in declarations(s) if n in ['VideoSharePayload','buildVideoSharePayload'])+'\n'
 if name=='HomeHeroCarousel':
  text=re.sub(r'(?m)^import (android\.net\.Uri|androidx\.media3\.[\w.]+|androidx\.compose\.ui\.viewinterop\.AndroidView)\n','',text)
  original=next(d for n,d in declarations(s) if n=='MutedHeroVideoPlayer')
  after='''@Composable
private fun MutedHeroVideoPlayer(url:String) {
    var hasRenderedFirstFrame by remember(url) { mutableStateOf(false) }
    com.bilipai.desktop.ui.LocalDesktopHomeMediaPorts.current.previewSurface(
        url, true, { hasRenderedFirstFrame = true },
        Modifier.fillMaxSize().graphicsLayer { alpha = resolveHomeHeroCarouselPreviewAlpha(hasRenderedFirstFrame) })
}'''
  assert original in text;text=text.replace(original,after);changes.append(dict(before=original,after=after,reason='actual independent owned MPV surface; same original first-frame alpha policy'))
 if name=='VideoPreviewDialog':
  replace('    val context = LocalContext.current','    val context = LocalContext.current\n    val mediaPorts = com.bilipai.desktop.ui.LocalDesktopHomeMediaPorts.current')
  text=re.sub(r'(?m)^import (androidx\.media3\.[\w.]+|androidx\.compose\.ui\.viewinterop\.AndroidView)\n','',text)
  text=re.sub(r'(?m)^@androidx\.annotation\.OptIn.*\n','',text)
  original=next(d for n,d in declarations(text) if n=='DisposableVideoPlayer')
  after='''@Composable
fun DisposableVideoPlayer(url:String) {
    com.bilipai.desktop.ui.LocalDesktopHomeMediaPorts.current.previewSurface(url, false, null, Modifier.fillMaxSize())
}'''
  assert original in text;text=text.replace(original,after);changes.append(dict(before=original,after=after,reason='actual owned MPV sink replaces Android PlayerView only'))
  replace('android.widget.Toast.makeText(context, "无法获取预览地址", android.widget.Toast.LENGTH_SHORT).show()','mediaPorts.feedback("无法获取预览地址")')
 if name=='HomeCategoryPage':
  replace('initialValue =','initial =')
  replace('com.android.purebilibili.feature.audio.player\n            .AudioNowPlayingSession.barOverlayVisible','com.bilipai.desktop.ui.LocalDesktopHomeMediaPorts.current.musicOverlayVisible')
 if name=='TopBar':
  replace('SettingsManager.MAX_TOP_TABS','com.bilipai.desktop.ui.DesktopOriginalHomeSettingConstants.MAX_TOP_TABS')
  replace('SettingsManager.TopTabLabelMode.TEXT_ONLY','com.bilipai.desktop.ui.DesktopOriginalHomeSettingConstants.TopTabLabelMode.TEXT_ONLY')
  replace('import androidx.compose.ui.res.vectorResource\n','')
  replace('ImageVector.vectorResource(\n                com.android.purebilibili.R.drawable.ms_rss_feed_24\n            )','com.bilipai.desktop.settings.DesktopLinkedDockVectors.vector(com.bilipai.desktop.settings.DesktopLinkedDockSymbols.ms_rss_feed_24)')
 # Remove unused imports from a selected declaration-only body, including unavailable service imports.
 # Wildcards stay; concrete import names absent from body are not part of this declaration closure.
 bodywords=set(re.findall(r'\b\w+\b',text[text.index('\n\n')+2:]))
 text=re.sub(r'R\.string\.(\w+)',lambda m:'\"'+m[1]+'\"',text)
 if name=='HomeUiState':text=re.sub(r'(fun resolve\w+LabelRes\([^\n]*\)): Int',r'\1: String',text)
 text=re.sub(r'(?m)^import com\.android\.purebilibili\.core\.store\.SettingsManager[^\n]*\n','',text)
 # Narrow imports after all platform rewrites. Unused Android/service imports are not dependencies.
 code=re.sub(r'(?m)^import[^\n]*\n','',text)
 words=set(re.findall(r'\b\w+\b',code))
 def prune(m):
  line=m[0];path=line.split('//',1)[0].strip().split()
  simple=path[-1] if 'as' in path else path[1].split('.')[-1]
  return line if simple in ['*','getValue','setValue'] or simple in words else ''
 text=re.sub(r'(?m)^import[^\n]*\n',prune,text)
 out=HERE/'prepared/generated'/pkg.replace('.','/')/('DesktopOriginalHomeScreen.kt' if name=='HomeScreen' else Path(path).name)
 write(out,'// Original source '+path+'\n// LF SHA256 '+row['sha256LF']+'\n'+text)
 report.append(dict(**row,mode='policy-extract',generated=out.relative_to(HERE).as_posix(),changes=changes))
# Windows binds original frame/scroll metric state changes to the Root's real metric sink.
path='app/src/main/java/com/android/purebilibili/core/ui/performance/JankTracking.kt';s=read(REPO/path)
text='package com.android.purebilibili.core.ui.performance\nimport androidx.compose.runtime.*\nimport androidx.compose.foundation.gestures.ScrollableState\nimport kotlinx.coroutines.CoroutineScope\nimport com.bilipai.desktop.ui.*\n\n'+ '\n\n'.join(d for n,d in declarations(s) if n in ['rememberMetricsStateHolder','TrackJank','TrackScrollJank','TrackJankStateValue','TrackJankStateFlag'])
start=text.index('@Composable');end=text.index('@Composable',start+1)
text=text[:start]+'@Composable\nfun rememberMetricsStateHolder(): DesktopHomeMetricHolder = LocalDesktopHomeMetricHolder.current\n\n'+text[end:]
text=re.sub(r'\bHolder\b','DesktopHomeMetricHolder',text)
write(HERE/'prepared/generated/com/android/purebilibili/core/ui/performance/DesktopHomeJankTracking.kt',text)
report.append(dict(path=path,sha256LF=hashlib.sha256(s.encode()).hexdigest(),mode='policy-extract',selectedDeclarations=['rememberMetricsStateHolder','TrackJank','TrackScrollJank','TrackJankStateValue','TrackJankStateFlag'],reason='only Android hierarchy Holder lookup becomes required current Windows window metric port; original state changes/scroll collectors retained'))
path='app/src/main/java/com/android/purebilibili/core/util/ModifierExt.kt';s=read(REPO/path)
text='package com.android.purebilibili.core.util\nimport androidx.compose.animation.core.*\nimport androidx.compose.foundation.*\nimport androidx.compose.foundation.interaction.*\nimport androidx.compose.runtime.*\nimport androidx.compose.ui.Modifier\nimport androidx.compose.ui.composed\nimport com.android.purebilibili.core.theme.*\nimport androidx.compose.ui.graphics.graphicsLayer\n'+ '\n\n'.join(d for n,d in declarations(s) if n=='iOSCardTapEffect')
write(HERE/'prepared/generated/com/android/purebilibili/core/util/DesktopHomeCardTapEffect.kt',text)
report.append(dict(path=path,sha256LF=hashlib.sha256(s.encode()).hexdigest(),mode='policy-extract',selectedDeclarations=['iOSCardTapEffect'],reason='missing exact original modifier; existing HapticType/feedback implementation sole reused'))
write(HERE/'producer-inventory.json',json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print(len(report),'source outputs')

