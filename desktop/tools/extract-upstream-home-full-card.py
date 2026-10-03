"""Full original Elegant/SingleColumn source closure and exact persisted Home card fields.
Android services are explicit JVM bindings; direct originals compile only through shared Sync by default.
Use --standalone for the isolated source proof. No theme/store/network authority is recreated here.
"""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import hashlib,importlib.util,json,re,sys,textwrap
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[1]
def load(name,path):
 s=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m

def write(path,text):
 target=Path('\\\\?\\'+str(path.absolute())) if sys.platform=='win32' and not str(path).startswith('\\\\?\\') else path
 target.parent.mkdir(parents=True,exist_ok=True);target.write_text(text,encoding='utf-8',newline='\n')
def read(path):return (_desktop_canonical_source(REPO, path)).read_text(encoding='utf-8')
APP='app/src/main/java/com/android/purebilibili/'
DS='design-system/src/main/java/com/android/purebilibili/'
DIRECT=[APP+'feature/home/components/cards/'+n+'.kt' for n in [
 'VideoCardScrollLiteVisualPolicy','VideoCardHistoryProgressPolicy','VideoCardDurationBadgeVisualPolicy',
 'VideoCardCoverStatsLayoutPolicy','VideoCardCoverOverlayTextPolicy','VideoCardCoverDurationText',
 'VideoCardAdaptiveTintPolicy','VideoPremiumBadgePolicy','HorizontalVideoCardStats','HorizontalVideoCardLayoutPolicy',
 'HorizontalVideoCardFrame','HomeVideoGlassBadgeStylePolicy']]+[APP+p+'.kt' for p in [
 'core/ui/components/UpBadgeName','core/ui/components/UpBadgeNamePolicy','core/ui/components/UserUpBadge',
 'core/ui/VideoCardTitleVisibility','core/ui/VideoCardLongPressVisibility','core/ui/UpBadgeVisibility',
 'core/ui/SharedTransitionProvider','core/util/Animations','core/util/CardPositionManager',
 'core/ui/transition/VideoCardSourceSnapshot','core/ui/transition/VideoCardShellSharedBounds',
 'core/ui/transition/VideoCardSharedElementContext','core/ui/transition/VideoCardNativeSnapshot',
 'core/ui/transition/MiuixVideoCardTransitionState','core/ui/transition/VideoSharedTransitionPolicy','core/ui/transition/VideoCardTransitionExposure',
 'core/ui/transition/BiliPaiSharedElementKey','core/ui/transition/VideoCardReturnTimeline',
 'navigation/ScreenRoutes','core/util/UrlDecodeCompat',
 'feature/home/components/cards/VideoCardShellReturnChrome','feature/home/HomeCoverRequestPolicy',
 'feature/home/HomeCardEnterAnimationPolicy','feature/home/HomeGlassVisualPolicy','feature/home/HomeWatchLaterPolicy']]+[
 DS+'core/ui/FeedContentTokens.kt',DS+'core/ui/adaptive/MotionTier.kt',DS+'core/ui/adaptive/MotionTierPolicy.kt',
 DS+'core/ui/adaptive/DeviceUiProfile.kt',DS+'core/ui/motion/AppMotionTokens.kt',DS+'core/ui/blur/BlurBudgetPolicy.kt',
 DS+'core/ui/MediaContrastPalette.kt',DS+'core/ui/adaptive/RuntimeVisualGuardPolicy.kt',DS+'core/ui/blur/BlurStyles.kt',DS+'core/ui/blur/BlurIntensityVisualPolicy.kt']
DIRECT+=[DS+'core/ui/adaptive/RuntimeVisualGuardSignalPolicy.kt']
EXTRACTED={
 APP+'core/util/HomeCoverReturnPrefetch.kt':['HomeCoverReturnPrefetchEntry','HomeCoverReturnPrefetchRegistry','resolveHomeCoverReturnPrefetchCandidates'],
 APP+'core/ui/CompositionLocals.kt':['LocalWallpaperHazeState','LocalDetailedCommentTimeEnabled'],
 APP+'feature/home/HomeScreen.kt':['LocalHomeWallpaperBackdrop','LocalHomeWallpaperBackdropReady','LocalHomeWallpaperIsStatic'],
 APP+'navigation/AppNavigationPlaybackPolicy.kt':['isVideoCardReturnTargetRoute','isVideoDetailRoute'],
 APP+'navigation/AppNavigation.kt':['VideoRoute'],
 APP+'core/ui/adaptive/DeviceUiProfileAdapter.kt':['toAdaptiveWidthClass'],
 APP+'core/util/ModifierExt.kt':['HapticType'],
 APP+'feature/video/ui/section/VideoInfoDisplayPolicy.kt':['resolveCompactPublishTimeRowText'],
 APP+'core/ui/performance/RuntimeVisualGuardLocals.kt':['NormalGuardDecision','LocalRuntimeVisualGuard','isLowBlurBudgetForced'],
 APP+'core/ui/transition/VideoCardTransitionBackgroundPolicy.kt':['VideoCardTransitionBackgroundPhase','VideoCardTransitionBackgroundState','LocalVideoCardTransitionBackgroundState',
 'VideoCardTransitionBackgroundFrame','VideoCardTransitionBackgroundFrameCache','VideoCardTransitionSnapshotLayerState','VideoCardTransitionSnapshotHandle',
 'resolveVideoCardTransitionBackgroundFrame','resolveVideoCardTransitionDepthProgress','resolveVideoCardTransitionBlurStrength',
 'resolveVideoCardTransitionMaxBlurRadiusPx','resolveVideoCardTransitionBlurQuantumPx','quantizeVideoCardTransitionBlurRadius',
 'resolveVideoCardTransitionScrimAlpha','resolveVideoCardTransitionContentScale','resolveVideoCardTransitionBackgroundCornerRadiusDp','resolveVideoCardTransitionBackgroundCornerRadiusPx',
 'resolveVideoCardTransitionBackgroundReturnDurationMs'],
 APP+'core/store/SettingsManager.kt':['HomeDurationStyle','HomeCardBadgeEffectMode','HomeCardInfoGlassMode','HomeWallpaperEffectMode','HomeWallpaperEffectScope'],
 APP+'core/ui/blur/RecoverableVisualEffects.kt':['recoverableBlurGates','recoverableBlurEnabled'],
}
HEADER={
 'CompositionLocals.kt':'import androidx.compose.runtime.compositionLocalOf\nimport androidx.compose.runtime.staticCompositionLocalOf\nimport dev.chrisbanes.haze.HazeState\n',
 'HomeScreen.kt':'import androidx.compose.runtime.staticCompositionLocalOf\nimport top.yukonga.miuix.kmp.blur.Backdrop as MiuixBackdrop\n',
 'VideoInfoDisplayPolicy.kt':'import com.android.purebilibili.core.util.FormatUtils\n',
 'DeviceUiProfileAdapter.kt':'import com.android.purebilibili.core.util.WindowWidthSizeClass\nimport com.android.purebilibili.core.ui.adaptive.AdaptiveWidthClass\n',
 'VideoCardTransitionBackgroundPolicy.kt':'import androidx.compose.runtime.compositionLocalOf\nimport androidx.compose.ui.geometry.Rect\nimport androidx.compose.ui.graphics.BlurEffect\nimport androidx.compose.ui.graphics.Color\nimport androidx.compose.ui.graphics.TileMode\nimport com.android.purebilibili.core.ui.adaptive.MotionTier\nimport com.android.purebilibili.feature.home.components.cards.DesktopHomeCardPlatform\nimport kotlin.math.roundToInt\n',
 'AppNavigation.kt':'import com.bilipai.desktop.ui.encodeDesktopVideoRouteCover\n',
 'RuntimeVisualGuardLocals.kt':'import androidx.compose.runtime.*\nimport com.android.purebilibili.core.ui.adaptive.*\n',
 'RecoverableVisualEffects.kt':'import androidx.compose.runtime.*\nimport dev.chrisbanes.haze.HazeState\nimport java.util.Collections\nimport java.util.WeakHashMap\n',
}
ADAPTED=[APP+'feature/home/components/cards/VideoCard.kt',APP+'feature/home/components/cards/HomeStyleSingleColumnVideoCard.kt',APP+'core/ui/adaptive/AdaptiveInputModifiers.kt',APP+'core/ui/blur/UnifiedBlur.kt',APP+'feature/home/components/cards/VideoCardOnlineCountStore.kt']
def original_dynamic_navigation_body(source, name, token_parser):
 # Select the original DynamicScreen callback, not another screen's similarly
 # named callback. Kotlin tokens keep comments/literals out of brace matching.
 begin=source.index('BiliPaiNavEntryContentRole.DYNAMIC -> DynamicScreen(')
 end=source.index('BiliPaiNavEntryContentRole.SEARCH ->',begin)
 region=source[begin:end]
 matches=list(re.finditer(r'\b'+re.escape(name)+r'\s*=\s*\{',region))
 assert len(matches)==1,name
 opening=matches[0].end()-1
 tokens=token_parser.kotlin_tokens(region[opening:]);depth=0;arrow=None;closing=None
 for index,(value,start,finish) in enumerate(tokens):
  if value=='{':depth+=1
  elif value=='}':
   depth-=1
   if depth==0:closing=start;break
  elif value=='-' and depth==1 and arrow is None and tokens[index+1][0]=='>':arrow=tokens[index+1][2]
 assert arrow is not None and closing is not None,name
 return textwrap.dedent(region[opening+arrow:opening+closing]).strip()

def desktop_dynamic_navigation(source, token_parser):
 changes=[];functions=[]
 for callback,signature,key,replacement in [
  ('onCollectionClick','internal fun navigateOriginalDynamicCollection(mediaId:Long, ownerMid:Long, title:String, url:String,\n onFavorite:(String,Long,Long,String)->Unit, onWeb:(String,String)->Unit)',
   'SeasonSeriesDetail','onFavorite("favorite", mediaId, ownerMid, title)'),
  ('onCourseClick','internal fun navigateOriginalDynamicCourse(url:String, title:String,\n onPlayer:(Long,Long,Boolean)->Unit, onWeb:(String,String)->Unit)',
   'BangumiPlayer','onPlayer(courseNav.seasonId, courseNav.epId, true)'),
 ]:
  original=original_dynamic_navigation_body(source,callback,token_parser);body=original
  pattern=r'pushNavigation3Key\(\s*BiliPaiNavKey\.'+key+r'\(\s*[^()]*?\)\s*\)'
  matches=list(re.finditer(pattern,body));assert len(matches)==1,callback
  before=matches[0].group()
  reviewed=('pushNavigation3Key(BiliPaiNavKey.SeasonSeriesDetail(type="favorite", id=mediaId, mid=ownerMid, title=title,))'
   if key=='SeasonSeriesDetail' else 'pushNavigation3Key(BiliPaiNavKey.BangumiPlayer(seasonId=courseNav.seasonId, epId=courseNav.epId, isCourse=true))')
  assert [t[0]for t in token_parser.kotlin_tokens(before)]==[t[0]for t in token_parser.kotlin_tokens(reviewed)],callback
  body=body.replace(before,replacement)
  changes.append(dict(callback=callback,before=before,after=replacement))
  before='pushNavigation3Key(BiliPaiNavKey.Web(url = url, title = title))'
  assert body.count(before)==1,callback
  body=body.replace(before,'onWeb(url, title)')
  changes.append(dict(callback=callback,before=before,after='onWeb(url, title)'))
  functions.append(signature+' {\n'+textwrap.indent(body,'    ')+'\n}')
 return '\n\n'.join(functions),changes

def generate(repo, output, standalone=False):
 global HERE,REPO,parser,appearance,media
 HERE=output;REPO=repo
 parser=load('hc_parser',repo/'desktop/tools/sync-upstream.py')
 appearance=load('hc_declarations',repo/'desktop/tools/extract-appearance-platform.py')
 media=load('hc_media',repo/'desktop/tools/extract-upstream-media.py')
 rows=[]
 for path in DIRECT+list(EXTRACTED)+ADAPTED:
  source=read(path);pkg=re.search(r'(?m)^package (\S+)',source).group(1);body=source
  changes=[]
  if path in {APP+'core/ui/transition/VideoCardTransitionBackgroundPolicy.kt',APP+'core/ui/blur/RecoverableVisualEffects.kt'}:
   # This producer remains the single owner of the map/state/renderer output.
   # The full page producer only supplies the source-preserving expansion body.
   full_home=load('hc_home_page',repo/'desktop/tools/extract-upstream-home-page.py')
   expanded=full_home.existing_home_card_body(repo,path)
   out=HERE/'generated'/pkg.replace('.','/')/Path(path).name
   write(out,expanded)
   rows.append(dict(path=path,mode='policy-extract',features=['home-full-card','home-page'],sha256=hashlib.sha256(source.encode()).hexdigest(),sha256Bytes=hashlib.sha256((_desktop_canonical_source(REPO, path)).read_bytes()).hexdigest(),changes=[dict(adapter='complete original depth/recoverable body, required actual Windows window boundary')],generated=str(out.relative_to(HERE))))
   continue
  if path.endswith('VideoCardTransitionBackgroundPolicy.kt'):
   EXTRACTED[path]=list(dict.fromkeys(EXTRACTED[path]+re.findall(r'(?m)^(?:internal |private )?(?:const )?val (VIDEO_CARD_\w+)',source)))
  if path in EXTRACTED:
   declarations='\n\n'.join(media.function(source,name,parser) if name=='toAdaptiveWidthClass' else appearance.declarations(parser,source,[name]) for name in EXTRACTED[path])
   body='package '+pkg+'\n\n'+HEADER.get(Path(path).name,'')+'\n'+declarations
  if path in ADAPTED:
   for before,after in [
    ('import android.os.Build\n',''),
    ('import androidx.compose.ui.platform.LocalContext','import coil3.PlatformContext'),
    ('import androidx.compose.ui.platform.LocalConfiguration','import com.bilipai.desktop.ui.DesktopHomeCardWindowMetrics'),
    ('import com.android.purebilibili.core.player.PlaybackProgressManager','import com.bilipai.desktop.ui.LocalDesktopHomeCardProgress'),
    ('import com.android.purebilibili.core.store.SettingsManager\n',''),
    ('LocalContext.current','PlatformContext.INSTANCE'),
    ('LocalConfiguration.current','DesktopHomeCardWindowMetrics.current'),
    ('Build.VERSION.SDK_INT','DesktopHomeCardPlatform.androidRenderEffectApiLevel'),
    ('val playbackProgressManager = remember(context) {\n        PlaybackProgressManager.getInstance(context)\n    }','val playbackProgressManager = LocalDesktopHomeCardProgress.current'),
    ('playbackProgressManager.getCachedPosition(video.bvid, video.cid)','playbackProgressManager?.invoke(video.bvid, video.cid) ?: 0L'),
    ('import com.android.purebilibili.core.util.LocalAppWindowAdaptiveInfo\n',''),
    ('enabled: Boolean = resolveInputDevicePolicy(LocalAppWindowAdaptiveInfo.current).enableHoverEffects','enabled: Boolean = true'),
    ('    if (!shouldAllowRenderEffectBackedHazeEffect(Build.VERSION.SDK_INT)) return this\n','    // Haze JVM uses Skia RenderEffect; Android API gating is not an OS capability check here.\n'),
    ('    if (!shouldAllowRenderEffectBackedHazeEffect(DesktopHomeCardPlatform.androidRenderEffectApiLevel)) return this\n','    // Haze JVM uses Skia RenderEffect; Android API gating is not an OS capability check here.\n'),
   ]:
    if before in body:body=body.replace(before,after);changes.append(dict(before=before,after=after))
  if path.endswith('/VideoCardTransitionBackgroundPolicy.kt'):
   for before,after in [('Build.VERSION.SDK_INT','DesktopHomeCardPlatform.androidRenderEffectApiLevel'),('Build.VERSION_CODES.S','31')]:
    if before in body:body=body.replace(before,after);changes.append(dict(before=before,after=after))
  if path.endswith('/AppNavigation.kt'):
   before='Uri.encode(coverUrl)';after='encodeDesktopVideoRouteCover(coverUrl)'
   assert body.count(before)==1
   body=body.replace(before,after);changes.append(dict(before=before,after=after))
   callbacks,callback_changes=desktop_dynamic_navigation(source,parser)
   body+='\n\n'+callbacks+'\n';changes.extend(callback_changes)
  if path.endswith('/VideoCardOnlineCountStore.kt'):
   before='import com.android.purebilibili.core.network.NetworkModule';after='import com.bilipai.desktop.ui.LocalDesktopVideoCardOnlineStore\nimport kotlinx.coroutines.CancellationException'
   body=body.replace(before,after);changes.append(dict(before=before,after=after))
   begin=body.index('private val defaultVideoCardOnlineCountStore by lazy');end=body.index('@Composable',begin)
   changes.append(dict(before=body[begin:end],after=''))
   body=body[:begin]+body[end:]
   before='        } catch (_: Exception) {';after='        } catch (cancelled: CancellationException) {\n            throw cancelled\n        } catch (_: Exception) {'
   body=body.replace(before,after);changes.append(dict(before=before,after=after))
   before='    val onlineCountFlow = remember(bvid, cid) {';after='    val defaultVideoCardOnlineCountStore = LocalDesktopVideoCardOnlineStore.current ?: return ""\n    val onlineCountFlow = remember(defaultVideoCardOnlineCountStore, bvid, cid) {'
   body=body.replace(before,after);changes.append(dict(before=before,after=after))
   body=body.replace('LaunchedEffect(bvid, cid)', 'LaunchedEffect(defaultVideoCardOnlineCountStore, bvid, cid)')
  out=HERE/'generated'/pkg.replace('.','/')/Path(path).name
  if path not in DIRECT or standalone:write(out,'// Original source '+path+'\n// LF SHA256 '+hashlib.sha256(source.encode()).hexdigest()+'\n'+body)
  rows.append(dict(path=path,mode='direct' if path in DIRECT else 'policy-extract',features=['home-full-card'],sha256=hashlib.sha256(source.encode()).hexdigest(),sha256Bytes=hashlib.sha256((_desktop_canonical_source(REPO, path)).read_bytes()).hexdigest(),changes=changes,generated=str(out.relative_to(HERE))))
 write(HERE/'source-inventory.json',json.dumps(rows,indent=2)+'\n')


def generate_preferences():
 home=load('full_calls',REPO/'desktop/tools/extract-upstream-settings-home.py')
 source=read(APP+'core/store/SettingsManager.kt')
 mapping={
  'cardAnimationEnabled':('Boolean','KEY_CARD_ANIMATION_ENABLED','setCardAnimationEnabled'),
  'compactVideoStatsOnCover':('Boolean','KEY_COMPACT_VIDEO_STATS_ON_COVER','setCompactVideoStatsOnCover'),
  'showHomeUpBadges':('Boolean','KEY_HOME_UP_BADGES_VISIBLE','setHomeUpBadgesVisible'),
  'showHomeUpAvatars':('Boolean','KEY_HOME_UP_AVATARS_VISIBLE','setHomeUpAvatarsVisible'),
  'showHomePublishTime':('Boolean','KEY_HOME_PUBLISH_TIME_VISIBLE','setHomePublishTimeVisible'),
  'showFullVideoCardContent':('Boolean','KEY_FULL_VIDEO_CARD_CONTENT_VISIBLE','setFullVideoCardContentVisible'),
  'videoCardLongPressActionEnabled':('Boolean','KEY_VIDEO_CARD_LONG_PRESS_ACTION_ENABLED','setVideoCardLongPressActionEnabled'),
  'homeCardDynamicTintEnabled':('Boolean','KEY_HOME_CARD_DYNAMIC_TINT_ENABLED','setHomeCardDynamicTintEnabled'),
  'homeCardFrostedGlassEnabled':('Boolean','KEY_HOME_CARD_FROSTED_GLASS_ENABLED',None),
  'homeDurationStyle':('HomeDurationStyle','KEY_HOME_DURATION_STYLE','setHomeDurationStyle'),
 }
 begin=source.index('    internal fun mapHomeSettingsFromPreferences(preferences: Preferences): HomeSettings {');end=source.index('\n    fun getHomeSettings(',begin)
 body=source[begin:end];body=body[body.index('        return HomeSettings(')+len('        return HomeSettings('):body.rindex('\n        )')]
 matches=list(re.finditer(r'^            (\w+) =[ \t]*',body,re.M));expressions={}
 for i,m in enumerate(matches):
  if m.group(1) in mapping:expressions[m.group(1)]=re.sub(r'\n\s*//[^\n]*','',body[m.end():matches[i+1].start() if i+1<len(matches) else len(body)]).strip().rstrip(',')
 keys=list(dict.fromkeys([r[1] for r in mapping.values()]+['KEY_SHOW_ONLINE_COUNT','KEY_HOME_VIDEO_DURATION_BADGES_VISIBLE']))
 lines=[]
 for key in keys:
  found=re.findall(r'private val '+key+r'\s*=\s*(?:boolean|int)PreferencesKey\("[^"]+"\)',source);assert len(found)==1,key;lines+=found
 setters=[media.function(source,r[2],parser) for r in mapping.values() if r[2]]+[media.function(source,'setShowOnlineCount',parser)]
 frost=appearance.declarations(parser,source,['resolveHomeCardFrostedGlassEnabled']) if False else None
 frostpath=_desktop_canonical_source(REPO,'app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt')
 frostsource=frostpath.read_text(encoding='utf-8');frost=appearance.declarations(parser,frostsource,['resolveHomeCardFrostedGlassEnabled'])
 content='''package com.android.purebilibili.core.store
import com.bilipai.desktop.plugins.DesktopPluginContext as Context
import com.bilipai.desktop.plugins.DesktopPreferenceSnapshot as Preferences
import com.bilipai.desktop.settings.homeCardVisualDataStore as settingsDataStore
import com.bilipai.desktop.settings.homeCardVisualBooleanKey as booleanPreferencesKey
import com.bilipai.desktop.settings.homeCardVisualIntKey as intPreferencesKey
import kotlinx.coroutines.flow.map

'''+frost+'''\n
/** No independent constructor defaults: the original persisted mapper is the only default source. */
data class DesktopHomeCardVisualSettings(
'''+''.join(' val '+n+':'+r[0]+',\n' for n,r in mapping.items())+' val showOnlineCount:Boolean,\n)\n\nobject DesktopOriginalHomeCardVisualSettings {\n'+textwrap.indent('\n'.join(lines),'    ')+'''
    fun decode(preferences:Preferences):DesktopHomeCardVisualSettings = DesktopHomeCardVisualSettings(
'''+''.join('        '+field+' = '+textwrap.indent(expr,'        ').lstrip()+',\n' for field,expr in expressions.items())+'''
        showOnlineCount = preferences[KEY_SHOW_ONLINE_COUNT] ?: false,
    )
    fun getSettings(context:Context)=context.settingsDataStore.data.map(::decode)
'''+textwrap.indent('\n\n'.join(setters),'    ')+'\n}\n'
 out=HERE/'generated/com/android/purebilibili/core/store/DesktopOriginalHomeCardVisualSettings.kt';write(out,content)
 appearance_source=read(APP+'feature/settings/screen/AppearanceSettingsScreen.kt')
 titles=['标题完整显示','数据贴在封面上','首页视频时长：','发布时间','UP主头像','UP主标识','显示观看人数','长按视频卡片','卡片动态取色']
 calls=[]
 for title in titles:
  marker=appearance_source.index('title = "'+title)
  typename='SettingsSingleChoicePreference' if title=='首页视频时长：' else 'AppSwitchPreference'
  begin=appearance_source.rfind(typename+'(',0,marker)
  tokens=parser.kotlin_tokens(appearance_source[begin:]);opening=next(i for i,t in enumerate(tokens) if t[0]=='(')
  depth=0;end=None
  for t in tokens[opening:]:
   if t[0]=='(':depth+=1
   elif t[0]==')':
    depth-=1
    if depth==0:end=begin+t[2];break
  assert end is not None,title
  call=home.call(appearance_source[begin:end],typename,parser)
  call=re.sub(r'scope\.launch\s*\{\s*SettingsManager\.(\w+)\(context, it\)\s*\}',r'onChange(\1, it)',call)
  names={r[2]:r[2] for r in mapping.values() if r[2]};names['setShowOnlineCount']='setShowOnlineCount'
  # Typed callbacks, each original method stays separate and retains its original mutation body.
  for name in names:call=call.replace('onChange('+name+', it)',name+'(it)')
  for before,after in [('compactVideoStatsOnCover','state.compactVideoStatsOnCover'),('homeDurationStyle','state.homeDurationStyle'),('homePublishTimeVisible','state.showHomePublishTime'),('homeUpAvatarsVisible','state.showHomeUpAvatars'),('homeUpBadgesVisible','state.showHomeUpBadges'),('showOnlineCount','state.showOnlineCount'),('videoCardLongPressActionEnabled','state.videoCardLongPressActionEnabled'),('fullVideoCardContentVisible','state.showFullVideoCardContent'),('homeCardDynamicTintEnabled','state.homeCardDynamicTintEnabled')]:
   call=re.sub(r'\b'+before+r'\b',after,call)
  # Windows wording states the actual mounted consumer, not all original Android routes.
  call=call.replace('首页、搜索等视频卡片和视频页显示“xx人正在看”','推荐、热门、分区等卡片显示“xx人正在看”').replace('关闭后隐藏卡片和视频页的同时观看人数','关闭后隐藏这些卡片的同时观看人数')
  call=call.replace('首页和相关推荐','推荐、热门、分区').replace('卡片信息区跟随壁纸或封面颜色','卡片信息区跟随封面颜色')
  calls.append(call)
 body='''package com.android.purebilibili.feature.settings
import androidx.compose.runtime.Composable
import com.android.purebilibili.core.store.*
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.core.theme.*

@Composable
internal fun DesktopOriginalHomeCardVisualFields(state:DesktopHomeCardVisualSettings,
'''+',\n'.join(' '+name+':('+(('HomeDurationStyle' if name=='setHomeDurationStyle' else 'Boolean'))+')->Unit' for name in names)+''') {
 SettingsCardGroup {
'''+('\n SettingsAdaptiveDivider()\n'.join(calls))+'\n }\n}\n'
 assert 'scope.launch' not in body and 'SettingsManager.' not in body
 write(HERE/'generated/com/android/purebilibili/feature/settings/DesktopOriginalHomeCardVisualFields.kt',body)
 rows=[dict(path=p,mode='policy-extract',features=['home-full-card-settings'],sha256=hashlib.sha256(read(p).encode()).hexdigest()) for p in [APP+'core/store/SettingsManager.kt',str(frostpath.relative_to(REPO)).replace('\\','/'),APP+'feature/settings/screen/AppearanceSettingsScreen.kt']]
 write(HERE/'preferences-source-inventory.json',json.dumps(rows,indent=2)+'\n')

def inventory(repo):
 paths=list(dict.fromkeys(DIRECT+list(EXTRACTED)+ADAPTED+[
  APP+'core/store/SettingsManager.kt',APP+'core/store/HomeCardAppearancePolicy.kt',APP+'feature/settings/screen/AppearanceSettingsScreen.kt',
  APP+'feature/home/components/cards/VideoCardCoverColorStore.kt']))
 # The frozen original frost resolver is in this source, not a desktop synthetic policy.
 paths=[p if not p.endswith('HomeCardAppearancePolicy.kt') else frost_source_path(repo) for p in paths]
 return [dict(path=p,mode='direct' if p in DIRECT else 'policy-extract',features=['home-full-card'],sha256=hashlib.sha256((_desktop_canonical_source(repo, p)).read_text(encoding='utf-8').encode()).hexdigest()) for p in dict.fromkeys(paths)]
def frost_source_path(repo):
 source=_desktop_canonical_source(repo,'app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt')
 assert source.read_text(encoding='utf-8').count('internal fun resolveHomeCardFrostedGlassEnabled(')==1
 return source.relative_to(Path(repo).resolve()).as_posix()
if __name__=='__main__':
 import argparse
 ap=argparse.ArgumentParser();ap.add_argument('--repo',type=Path,required=True);ap.add_argument('--output',type=Path);ap.add_argument('--inventory',action='store_true');ap.add_argument('--standalone',action='store_true');args=ap.parse_args()
 if args.inventory:print(json.dumps(inventory(args.repo.resolve()),indent=2))
 else:
  assert args.output is not None
  generate(args.repo.resolve(),args.output.resolve(),args.standalone);generate_preferences()
