"""Full stable linked dock/search controls; same actual glass/navigation actors."""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse,hashlib,importlib.util,json,re,subprocess,textwrap,xml.etree.ElementTree as ET
PIN='79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40';BASE='app/src/main/java/com/android/purebilibili/'
SOURCES=[BASE+n+'.kt' for n in ['feature/home/components/LinkedBottomDock','feature/home/components/LinkedDockPolicy','feature/home/components/BottomBar','feature/home/components/HomeNavigationIconPolicy','feature/home/HomeScrollOffsetPolicy','feature/home/HomeScreen','feature/audio/screen/AudioNowPlayingBarPresenceMotionPolicy','core/store/SettingsManager','navigation/SearchSubmitPolicy','navigation/AppNavigation']]
ASSETS=['ms_home_fill_24','ms_history_fill_24','ms_rss_feed_24','bp_nav_home_outline_24','bp_nav_history_outline_24']
def safe(p):
 v=str(Path(p).absolute());return Path(v if v.startswith('\\\\?\\') else '\\\\?\\'+v)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def write(p,t):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(t,encoding='utf-8',newline='\n')
def mod(p,n):
 s=importlib.util.spec_from_file_location(n,p);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
def inventory(repo):return [dict(path=p,mode='policy-extract',features=['stable-linked-dock-search'],sha256=hashlib.sha256(read(_desktop_canonical_source(repo, p)).encode()).hexdigest()) for p in SOURCES]
def local_callback(parser,source,name):
 tokens=parser.kotlin_tokens(source);matches=[i for i,t in enumerate(tokens[:-1]) if t[0]=='val' and tokens[i+1][0]==name];assert len(matches)==1,name
 i=matches[0];begin=source.rfind('\n',0,tokens[i][1])+1;j=next(k for k in range(i,len(tokens)) if tokens[k][0]=='{');depth=0
 for k in range(j,len(tokens)):
  depth+=(tokens[k][0]=='{')-(tokens[k][0]=='}')
  if depth==0:return textwrap.dedent(source[begin:tokens[k][2]])
 raise AssertionError(name)
def generate(repo,output):
 identity=mod(repo/'desktop/tools/extract-upstream-dynamic-reply-protocol.py','dock_identity');original,ids=identity.load_pinned_sources(repo,SOURCES)
 parser=mod(repo/'desktop/tools/sync-upstream.py','dock_tokens');decl=mod(repo/'desktop/tools/extract-appearance-platform.py','dock_declarations')
 records=[]
 def emit(path,body,origin,selected=None,adaptations=None):
  write(output/'com/android/purebilibili'/path,body);records.append(dict(path=str(path),source=origin,selected=selected,adaptations=adaptations or [],sha256LF=hashlib.sha256(body.encode()).hexdigest()))
 p=BASE+'feature/home/components/LinkedBottomDock.kt';body=original[p]
 body=body.replace('import androidx.activity.compose.BackHandler','import com.android.purebilibili.core.ui.LocalNavigationBackHandler as BackHandler')
 body=body.replace('import com.android.purebilibili.core.ui.motion.rememberSystemReduceMotion','import com.bilipai.desktop.ui.rememberDesktopDynamicReduceMotion as rememberSystemReduceMotion')
 emit('feature/home/components/LinkedBottomDock.kt',body,p,adaptations=['Android BackHandler to actual existing NavigationEvent bridge; Root must mount actual owner','Android system motion read to existing actual Windows animation preference; original all transitions preserved'])
 for rel in ['feature/home/components/LinkedDockPolicy','feature/home/HomeScrollOffsetPolicy','feature/audio/screen/AudioNowPlayingBarPresenceMotionPolicy','navigation/SearchSubmitPolicy']:
  p=BASE+rel+'.kt'
  registry={r['path']:r for r in json.loads(read(repo/'desktop/upstream-sources.json'))['sources']}
  if registry[p].get('mode')!='direct':emit(rel+'.kt',original[p],p)
 p=BASE+'feature/home/HomeScreen.kt';names=['LocalHomeScrollOffset','LocalHomeFeedScrollInProgress']
 if 'stable-favorites' not in registry[p].get('features',[]):
  emit('feature/home/DesktopOriginalLinkedDockScrollLocals.kt','package com.android.purebilibili.feature.home\nimport androidx.compose.runtime.*\n\n'+decl.declarations(parser,original[p],names),p,names)
 p=BASE+'feature/home/components/BottomBar.kt';names=['BottomNavItem','SharedFloatingBottomBarIconStyle','normalizeBottomBarLabelMode','resolveBiliPaiFloatingBottomBarWidth','resolveMaterialBottomBarIcon','resolveHomeNavigationBarIcon','resolveSharedBottomBarIcon','BiliPaiBottomBarSearchVisualContent','shouldRequestBottomBarSearchIme','resolveBottomNavItemLabel','resolveBottomNavItemContentDescription','resolveBottomNavItemLookupKeys']
 body=decl.declarations(parser,original[p],names)
 body=body.replace('@StringRes val labelRes: Int','val labelRes: String').replace('@StringRes val contentDescriptionRes: Int','val contentDescriptionRes: String')
 body=re.sub(r'R\.string\.(\w+)',lambda m:'"'+m.group(1)+'"',body)
 body=body.replace('stringResource(item.labelRes)','com.bilipai.desktop.appearance.LocalDesktopStrings.current[item.labelRes]').replace('stringResource(item.contentDescriptionRes)','com.bilipai.desktop.appearance.LocalDesktopStrings.current[item.contentDescriptionRes]')
 body=body.replace('private fun resolveSharedBottomBarIcon','internal fun resolveSharedBottomBarIcon')
 header='''package com.android.purebilibili.feature.home.components
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.*
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.navigation.ScreenRoutes
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Search
'''
 emit('feature/home/components/DesktopOriginalLinkedDockControls.kt',header+'\n'+body,p,names,['Android string resource IDs become original XML keys read from same Root LocalDesktopStrings','Shared icon helper visibility widened so complete original LinkedBottomDock consumes same unique helper'])
 p=BASE+'feature/home/components/HomeNavigationIconPolicy.kt';body=original[p]
 body=body.replace('import androidx.compose.ui.res.vectorResource\n','').replace('import com.android.purebilibili.R','import com.bilipai.desktop.settings.DesktopLinkedDockSymbols\nimport com.bilipai.desktop.settings.DesktopLinkedDockVectors')
 body=body.replace('R.drawable.','DesktopLinkedDockSymbols.').replace('ImageVector.vectorResource(','DesktopLinkedDockVectors.vector(')
 emit('feature/home/components/HomeNavigationIconPolicy.kt',body,p,adaptations=['Only Android vector resource lookup -> exact original XML geometry Windows vector renderer'])
 for asset in ASSETS:
  path='app/src/main/res/drawable/'+asset+'.xml';raw=read(_desktop_canonical_source(repo, path));blob=subprocess.check_output(['git','show',PIN+':'+path],cwd=repo).decode().replace('\r\n','\n');assert raw==blob
  records.append(dict(source=path,mode='original-vector-paths',sha256LF=hashlib.sha256(raw.encode()).hexdigest()))
  write(output/'original-retained'/(path+'.txt'),raw)
 v=vector_geometry(repo)
 write(output/'com/bilipai/desktop/settings/DesktopLinkedDockVectors.kt',v)
 p=BASE+'core/store/SettingsManager.kt';source=original[p];manager=source[source.index('object SettingsManager {')+len('object SettingsManager {'):source.rfind('}')]
 names=['KEY_BOTTOM_BAR_LABEL_MODE','KEY_BOTTOM_BAR_SEARCH_ENABLED','KEY_LINKED_DOCK_MERGE_ON_SCROLL_ENABLED','KEY_LIST_SCOPED_SEARCH_ENABLED','getBottomBarLabelMode','getBottomBarSearchEnabled','getLinkedDockMergeOnScrollEnabled','getListScopedSearchEnabled']
 body=decl.declarations(parser,manager,names)
 emit('core/store/DesktopOriginalLinkedDockSettings.kt','''package com.android.purebilibili.core.store
import com.bilipai.desktop.plugins.DesktopPluginContext as Context
import com.bilipai.desktop.plugins.booleanPreferencesKey
import com.bilipai.desktop.settings.dynamicIntPreferencesKey as intPreferencesKey
import com.bilipai.desktop.settings.dynamicSettingsDataStore as settingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
/** Original read policies; Root provides its same global preference context. */
internal object DesktopOriginalLinkedDockSettings {
'''+body+'\n}\n',p,names)
 p=BASE+'navigation/AppNavigation.kt';names=['submitSearchKeywordInNavigation3','submitBottomBarSearchKeyword'];body='\n\n'.join(local_callback(parser,original[p],name) for name in names)
 for before,after in [('effectiveHomeSettings.isBottomBarSearchEnabled','bottomBarSearchEnabled'),('effectiveHomeSettings.listScopedSearchEnabled','listScopedSearchEnabled'),('currentNavigation3Key == BiliPaiNavKey.MainHost','isMainHost'),('pushSearchRouteInNavigation3(action.keyword)','onOpenSearch(action.keyword)'),('openBilibiliNativeTargetInNavigation3(action.target)','onOpenNativeTarget(action.target)')]:
  assert before in body,before;body=body.replace(before,after)
 emit('navigation/DesktopOriginalBottomSearchSubmit.kt','''package com.android.purebilibili.navigation
import com.android.purebilibili.core.util.BilibiliNavigationTarget
import com.android.purebilibili.feature.home.components.BottomNavItem
import kotlinx.coroutines.channels.SendChannel
/** Source AppNavigation callback closure; Root owns all channels and navigation. */
internal fun submitDesktopOriginalBottomBarSearchKeyword(
    keyword: String,
    bottomBarSearchEnabled: Boolean,
    listScopedSearchEnabled: Boolean,
    isMainHost: Boolean,
    currentBottomNavItem: BottomNavItem,
    historyListScopedSearchChannel: SendChannel<String>?,
    favoriteListScopedSearchChannel: SendChannel<String>?,
    watchLaterListScopedSearchChannel: SendChannel<String>?,
    onOpenSearch: (String) -> Unit,
    onOpenNativeTarget: (BilibiliNavigationTarget) -> Unit,
    isOwned: () -> Boolean,
) {
    if (!isOwned()) return
'''+body+'\n    submitBottomBarSearchKeyword(keyword)\n}\n',p,names,['Root existing navigation callbacks and owned same-list channels replace Navigation3 captures only; no new nav store/channel','Retired page/account refuses callback before original submit closure'])
 for p,raw in original.items():write(output/'original-retained'/(p+'.txt'),raw)
 write(output/'source-identities.json',json.dumps(dict(commit=PIN,sources=ids,outputs=records,actualOriginalReuse=['resolveSharedBottomBarCapsuleShape','biliPaiFloatingDockShell','LiquidGlassTuning','FloatingBottomBar','ScreenRoutes','BilibiliNavigationTargetParser'],wholeFrostedBottomBarNotClaimed=True,wholeAudioNowPlayingBarNotClaimed=True),ensure_ascii=False,indent=2)+'\n')

def vector_geometry(repo):
 ns='{http://schemas.android.com/apk/res/android}';branches=[]
 def number(v):return str(float(v))+'f'
 def brush(v):
  v={'@android:color/white':'#FFFFFFFF','@android:color/transparent':'#00000000'}.get(v,v)
  if re.fullmatch(r'#[0-9a-fA-F]{6}',v):v='#FF'+v[1:]
  assert re.fullmatch(r'#[0-9a-fA-F]{8}',v),v
  return 'SolidColor(Color(0x'+v[1:]+'))'
 for name in ASSETS:
  root=ET.fromstring(read(_desktop_canonical_source(repo, 'app/src/main/res/drawable/' + name + '.xml')));assert root.tag=='vector'
  assert not set(k.removeprefix(ns) for k in root.attrib)-{'width','height','viewportWidth','viewportHeight','tint','autoMirrored'}
  body=[]
  for node in root:
   assert node.tag=='path';a={k.removeprefix(ns):v for k,v in node.attrib.items()}
   assert not set(a)-{'pathData','fillColor','fillAlpha','fillType','strokeColor','strokeWidth','strokeLineCap','strokeLineJoin','strokeAlpha','strokeMiterLimit'}
   args=['pathData=addPathNodes('+json.dumps(a['pathData'])+')','fill='+brush(a.get('fillColor','#000000')),'fillAlpha='+number(a.get('fillAlpha','1')),'pathFillType=PathFillType.'+('EvenOdd' if a.get('fillType')=='evenOdd' else 'NonZero')]
   if 'strokeColor' in a:args+=['stroke='+brush(a['strokeColor']),'strokeLineWidth='+number(a.get('strokeWidth','0')),'strokeAlpha='+number(a.get('strokeAlpha','1')),'strokeLineCap=StrokeCap.'+{'butt':'Butt','round':'Round','square':'Square'}[a.get('strokeLineCap','butt')],'strokeLineJoin=StrokeJoin.'+{'miter':'Miter','round':'Round','bevel':'Bevel'}[a.get('strokeLineJoin','miter')],'strokeLineMiter='+number(a.get('strokeMiterLimit','4'))]
   body.append('addPath('+','.join(args)+')')
  size=lambda k:number(root.attrib[ns+k].removesuffix('dp'))+'.dp'
  branches+=['"'+name+'" -> ImageVector.Builder(name="'+name+'",defaultWidth='+size('width')+',defaultHeight='+size('height')+',viewportWidth='+number(root.attrib[ns+'viewportWidth'])+',viewportHeight='+number(root.attrib[ns+'viewportHeight'])+',autoMirror='+str(root.attrib.get(ns+'autoMirrored','false')).lower()+').apply {\n'+'\n'.join(body)+'\n}.build()']
 return '''package com.bilipai.desktop.settings
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.*
import androidx.compose.ui.unit.dp
internal object DesktopLinkedDockSymbols {
'''+''.join('const val '+n+'="'+n+'"\n' for n in ASSETS)+'''}
/** Exact source XML paths, fills, strokes, caps, joins and viewports; Root icon tint owns color. */
internal object DesktopLinkedDockVectors {
 @Composable fun vector(name:String):ImageVector=remember(name){load(name)}
 fun load(name:String):ImageVector=when(name) {
'''+ '\n'.join(branches)+'\nelse->error("Unregistered original dock vector: $name")\n}\n}\n'
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--repo',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();generate(a.repo,a.output)
