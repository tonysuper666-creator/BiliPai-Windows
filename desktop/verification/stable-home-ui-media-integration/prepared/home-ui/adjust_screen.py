from pathlib import Path
import re
HERE=Path(__file__).parent;p=HERE/'prepare.py';s=p.read_text(encoding='utf-8-sig')
s=s.replace("'VideoSharePolicy',",'').replace("'HomeWallpaperBackdrop','HomeNavigationIconPolicy'", "'HomeNavigationIconPolicy'")
anchor=" if name=='HomeHeroCarousel':"
insert=''' if name=='HomeScreen':
  replace('viewModel: HomeViewModel = viewModel(),','viewModel: com.bilipai.desktop.ui.DesktopOriginalHomeStateOwner,')
  replace('import androidx.lifecycle.viewmodel.compose.viewModel\\n','')
  replace('import android.annotation.SuppressLint\\n','')
  replace('import android.content.Context\\n','')
  text=re.sub(r'(?m)^@SuppressLint[^\\n]*\\n','',text)
  replace('val context = LocalContext.current','val context = LocalContext.current\\n    val platform = com.bilipai.desktop.ui.LocalDesktopHomeEnvironment.current')
  replace('val uiSkinState by rememberUiSkinState(context)','val uiSkinState = com.android.purebilibili.core.plugin.skin.LocalUiSkinState.current')
  replace('import com.android.purebilibili.core.plugin.skin.rememberUiSkinState\\n','')
  for key,field in [('HomeTopTabSettings','topTabs'),('HomeSettings','homeSettings'),('AppNavigationSettings','navigation'),('ShowOnlineCount','showOnlineCount'),('HomeFeedCardStyle','homeFeedCardStyle'),('HomeWallpaperUri','homeWallpaperUri'),('SplashWallpaperUri','splashWallpaperUri')]:
   text=re.sub(r'SettingsManager\\s*\\.get'+key+r'\\(context\\)', 'platform.settings.'+field,text)
  # Each Root projection is an already-initialized StateFlow. Keep its actual snapshot, not schema defaults.
  for before in ['initial = com.android.purebilibili.core.store.HomeTopTabSettings(),','initial = com.android.purebilibili.core.store.HomeSettings(),','initial = AppNavigationSettings(),','initial = false','initial = com.android.purebilibili.core.store.HomeFeedCardStyle.BILIPAI,','initial = ""']:
   replace(before,'')
  replace('SettingsManager.isDataSaverActive(context)','platform.settings.isDataSaverActive()')
  replace('com.android.purebilibili.core.store.platform.settings','platform.settings')
  for n in ['setEasterEggEnabled','setTabletUseSidebar','setGridColumnCountCompact','setGridColumnCount']:
   replace('SettingsManager.'+n+'(context,','platform.settings.'+n+'(')
  replace('com.android.purebilibili.core.plugin.PluginStore\\n        .isEnabledFlow(context, com.android.purebilibili.feature.plugin.SubscriptionFeedPlugin.PLUGIN_ID)','platform.subscriptionPluginEnabled')
  replace('context = context,\\n            installedPlugins','context = platform.pluginContext,\\n            installedPlugins')
  replace('com.android.purebilibili.core.util.AnalyticsHelper.logScreenView','platform.analytics.logScreenView')
  replace('com.android.purebilibili.core.util.AnalyticsHelper.logCategoryView','platform.analytics.logCategoryView')
  start=text.index('    val view = androidx.compose.ui.platform.LocalView.current');end=text.index('    // 解构设置值',start)
  before=text[start:end];after='    SideEffect { platform.ensureEdgeToEdge() }\\n\\n';text=text[:start]+after+text[end:];changes.append(dict(before=before,after=after))
  start=text.index('    if (!view.isInEditMode && shouldApplyHomeSystemBars');end=text.index('    val homeCoverRequestSpec',start)
  before=text[start:end];after='    if (shouldApplyHomeSystemBars(isTopLevelActive)) {\\n        SideEffect { platform.applyHomeSystemBars(useDarkStatusBarIcons,isLightBackground) }\\n    }\\n\\n';text=text[:start]+after+text[end:];changes.append(dict(before=before,after=after))
  replace('val prefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }','val prefs = remember(platform) { platform.globalNamespace("app_prefs") }')
  replace('val welcomePrefs = remember { context.getSharedPreferences("app_welcome", Context.MODE_PRIVATE) }','val welcomePrefs = remember(platform) { platform.globalNamespace("app_welcome") }')
  replace('com.android.purebilibili.feature.home.components.cards.WallpaperPaletteStore.currentPalette','platform.wallpaperPalette')
  replace('com.android.purebilibili.feature.home.components.cards.WallpaperPaletteStore.loadWallpaperPalette(\\n                    context = context,\\n                    uri = homeWallpaperUri,\\n                    scope = this\\n                )','platform.loadWallpaperPalette(homeWallpaperUri, this)')
  replace('androidx.lifecycle.compose.LocalLifecycleOwner.current','platform.lifecycleOwner')
  replace('Build.VERSION.SDK_INT <= 31','com.bilipai.desktop.ui.LocalDesktopHomePlatform.current.legacyTopChromeSafetyGapRequired')
  for imported in ['com.android.purebilibili.feature.bangumi.HomeBangumiTabPage','com.android.purebilibili.feature.live.LiveListScreen','com.android.purebilibili.feature.partition.PartitionContent']:
   replace('import '+imported+'\\n','')
  for n in ['PartitionContent','LiveListScreen','HomeBangumiTabPage']:
   text=re.sub(r'(?<![\\w.])'+n+r'\\(', 'platform.pages.'+n+'(',text)
  replace('com.android.purebilibili.feature.home.subscription.SubscriptionFeedPage(','platform.pages.SubscriptionFeedPage(')
  replace('com.android.purebilibili.feature.video.share.VideoShareSheetHost(','platform.overlays.VideoShareSheetHost(')
  replace('com.android.purebilibili.feature.home.components.CrashTrackingConsentDialog(','platform.overlays.CrashTrackingConsentDialog(')
  replace('com.android.purebilibili.feature.download.DownloadManager\\n                                .saveImageToGallery(context, coverUrl, item.title)','platform.saveCover(coverUrl, item.title)')
  text=re.sub(r'android\\.widget\\.Toast\\.makeText\\(context, ([^\\n]+), android\\.widget\\.Toast\\.LENGTH_SHORT\\)\\.show\\(\\)',r'platform.feedback(\\1)',text)
  replace('androidx.activity.compose.BackHandler','com.android.purebilibili.core.ui.LocalNavigationBackHandler')
 if name=='HomeWallpaperBackdrop':
  # Original whole wallpaper visual body; only Android moving-media sink becomes a required native port.
  body='\\n\\n'.join(d for n,d in declarations(s) if n in ['HomeWallpaperBackdrop','isStaticHomeWallpaperUri'])+'\\n'
  text='package '+pkg+'\\n'+ '\\n'.join(x for x in s.splitlines() if x.startswith('import ') and 'videoCardTransitionBackgroundEffect' not in x)+'\\n'+body
  text=text.replace('import androidx.compose.ui.platform.LocalContext','import coil3.compose.LocalPlatformContext as LocalContext').replace('import androidx.compose.ui.platform.LocalConfiguration','import com.bilipai.desktop.ui.DesktopHomeCardWindowMetrics as LocalConfiguration')
  replace('com.android.purebilibili.core.ui.wallpaper.WallpaperMedia(','com.bilipai.desktop.ui.LocalDesktopHomeMediaPorts.current.wallpaperSurface(')
 if name=='VideoSharePolicy':
  text='package '+pkg+'\\n'+ '\\n\\n'.join(d for n,d in declarations(s) if n in ['VideoSharePayload','buildVideoSharePayload'])+'\\n'
'''
s=s.replace(anchor,insert+anchor)
s=s.replace('url, false, {}, Modifier.fillMaxSize()','url, false, null, Modifier.fillMaxSize()')
p.write_text(s,encoding='utf-8',newline='\n')
p=HERE/'prepared/manual/com/bilipai/desktop/ui/DesktopHomeEnvironment.kt';s=p.read_text(encoding='utf-8-sig')
s=s.replace('modifier:Modifier,contentPadding:PaddingValues,hazeState:HazeState?,','contentPadding:PaddingValues,')
s=s.replace(' val shareSheet:@Composable(VideoSharePayload?,onDismiss:()->Unit)->Unit,\n val crashConsentPrompt:@Composable(onDismiss:()->Unit)->Unit,',' val overlays:DesktopHomeOverlayPorts,')
s += '\ninternal interface DesktopHomeOverlayPorts {\n @Composable fun VideoShareSheetHost(payload:VideoSharePayload?,onDismiss:()->Unit)\n @Composable fun CrashTrackingConsentDialog(onDismiss:()->Unit)\n}\n'
p.write_text(s,encoding='utf-8',newline='\n')
p=HERE/'prepared/manual/com/bilipai/desktop/ui/DesktopHomeMediaPorts.kt';s=p.read_text(encoding='utf-8-sig')
s=s.replace('    val feedback:(String)->Unit,','    val feedback:(String)->Unit,\n    val wallpaperSurface:@Composable (uri:String,imageModel:Any,playbackEnabled:Boolean,modifier:Modifier)->Unit,')
# Named Android arguments bind to the original required interface method, not FunctionN.invoke.
s=s.replace('    val wallpaperSurface:@Composable (uri:String,imageModel:Any,playbackEnabled:Boolean,modifier:Modifier)->Unit,','    val wallpaper:DesktopHomeWallpaperPort,')
s += '\ninternal interface DesktopHomeWallpaperPort {\n @Composable fun wallpaperSurface(uri:String,imageModel:Any,playbackEnabled:Boolean,modifier:Modifier)\n}\n'
p.write_text(s,encoding='utf-8',newline='\n')
p=HERE/'prepare.py';s=p.read_text(encoding='utf-8-sig').replace('LocalDesktopHomeMediaPorts.current.wallpaperSurface(','LocalDesktopHomeMediaPorts.current.wallpaper.wallpaperSurface(')
p.write_text(s,encoding='utf-8',newline='\n')
