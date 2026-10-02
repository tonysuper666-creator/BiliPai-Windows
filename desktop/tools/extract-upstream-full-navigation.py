from pathlib import Path
import importlib.util,sys,re,json,hashlib,textwrap,argparse
sys.dont_write_bytecode=True
args_parser=argparse.ArgumentParser(description="Original full BottomBar and applicable Animation controls; Windows preference/icon/lifecycle bindings only.")
args_parser.add_argument('--repo',type=Path,default=Path(__file__).resolve().parents[2])
args_parser.add_argument('--output',type=Path,required=True)
args_parser.add_argument('--inventory',type=Path)
args_parser.add_argument('--resource-inventory',type=Path)
args_parser.add_argument('--policy-only',action='store_true',help='Exclude exact direct source bodies already emitted by prepareUpstreamSources.')
args=args_parser.parse_args()
REPO=args.repo.resolve()
MAIN=REPO
out=args.output.resolve()
def load(name,path):
    spec=importlib.util.spec_from_file_location(name,path); mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod);return mod
def wide(path):
    value=str(path.absolute())
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def write(path,value):
    wide(path.parent).mkdir(parents=True,exist_ok=True);wide(path).write_text(value,encoding='utf-8',newline='\n')
parser=load('full_parser',MAIN/'desktop/tools/sync-upstream.py')
media=load('full_method',MAIN/'desktop/tools/extract-upstream-media.py')
def source(path):return (REPO/path).read_text(encoding='utf-8').replace('\r\n','\n')
prefix='app/src/main/java/com/android/purebilibili/'
bottomPath=prefix+'feature/settings/screen/BottomBarSettingsScreen.kt'
managerPath=prefix+'core/store/SettingsManager.kt'
navPath=prefix+'core/store/navigation/NavigationSettingsStore.kt'
iconPath=prefix+'feature/settings/screen/SettingsNavigationIconPreviewPolicy.kt'
searchPath=prefix+'feature/search/SearchScreen.kt'
bottom,manager,nav,icons,search=[source(p) for p in [bottomPath,managerPath,navPath,iconPath,searchPath]]
animationPath=prefix+'feature/settings/screen/AnimationSettingsScreen.kt'
animation=source(animationPath)
content=media.function(bottom,'BottomBarSettingsContent',parser)
names=sorted(set(re.findall(r'SettingsManager\s*\.\s*((?:get|set|clear)\w+)\(',content)))
def adaptTypes(body):
    for old,new in [('BottomBarVisibilityMode','com.bilipai.desktop.ui.DesktopFavoriteNavigationTypes.BottomBarVisibilityMode'),('TopTabLabelMode','com.bilipai.desktop.ui.DesktopOriginalHomeSettingConstants.TopTabLabelMode')]:
        body=body.replace('SettingsManager.'+old,new)
    return re.sub(r'\bSettingsManager\b','DesktopOriginalFullNavigationSettings',body)
# Preserve entire original content/preview/editor, omit only Android outer Scaffold.
body=bottom[bottom.index('/**\n *  底栏项目配置'):bottom.index('/**\n *  导航设置页面')]
body+='\n@Composable\n'+content+'\n'+bottom[bottom.index('/**\n * 底栏预览组件'):]
body=body.replace('fun BottomBarSettingsContent(', 'internal fun DesktopOriginalBottomBarSettingsContent(')
body=body.replace('modifier: Modifier = Modifier\n)', 'modifier: Modifier = Modifier,\n    onFailure:(Throwable)->Unit,\n)')
body=body.replace('val context = LocalContext.current','val context = checkNotNull(com.bilipai.desktop.settings.LocalDesktopHomeCardPreferences.current).context')
body=body.replace('val scope = rememberCoroutineScope()','val latestFailure by rememberUpdatedState(onFailure)\n    val scope = rememberCoroutineScope { kotlinx.coroutines.CoroutineExceptionHandler { _, error -> latestFailure(error) } }')
body=body.replace('.collectAsStateWithLifecycle(','.collectAsState(').replace('initialValue =','initial =')
body=body.replace('SettingsManager.getTabletUseSidebar(context)','SettingsManager.getTabletUseSidebar(context, isLargeScreenCapable)')
body=adaptTypes(body)
imports=bottom[:bottom.index('/**\n *  底栏项目配置')]
imports='\n'.join(line for line in imports.splitlines() if not any(s in line for s in ['LocalContext','stringResource','SettingsPageScaffold','collectAsStateWithLifecycle','core.store.SettingsManager']))+'\n'
imports=imports.replace('import com.android.purebilibili.R\n','')+'import com.android.purebilibili.core.store.DesktopOriginalFullNavigationSettings\n'
body=re.sub(r'(?:com\.android\.purebilibili\.)?R\.drawable\.(\w+)',lambda m:'"'+m.group(1)+'"',body)
body=body.replace('com.android.purebilibili.feature.settings.rememberMaterialSymbol(', 'rememberDesktopNavigationMaterialSymbol(')
write(out/'com/android/purebilibili/feature/settings/DesktopOriginalBottomBarSettingsContent.kt', imports+body)
iconBody=icons.replace('import androidx.compose.ui.res.vectorResource','import com.android.purebilibili.feature.settings.rememberMaterialSymbol')
iconBody=iconBody.replace('ImageVector.vectorResource(', 'rememberMaterialSymbol(')
iconBody=iconBody.replace('import com.android.purebilibili.R\n','').replace('): Int = when','): String = when')
iconBody=re.sub(r'R\.drawable\.(\w+)',lambda m:'"'+m.group(1)+'"',iconBody)
# The pinned Windows Miuix publication lacks these newer Android extended glyphs.
# Retain original role/material-symbol selection; fallback only these concrete missing glyphs.
for name,symbol in [('FavoritesFill','ms_collections_bookmark_fill_24'),('RecordingTape','ms_live_tv_24'),('WorldClock','ms_watch_later_24'),('FolderFill','ms_extension_fill_24'),('MindMap','ms_smart_toy_24')]:
    iconBody=re.sub(r'MiuixIcons\.(?:Medium\.)?'+name+r'\b','rememberMaterialSymbol("'+symbol+'")',iconBody)
iconBody=iconBody.replace('import com.android.purebilibili.feature.settings.rememberMaterialSymbol','').replace('rememberMaterialSymbol(', 'rememberDesktopNavigationMaterialSymbol(')
write(out/'com/android/purebilibili/feature/settings/DesktopNavigationIconPreviewPolicy.kt',iconBody)
vectorTool=load('full_vectors',MAIN/'desktop/tools/extract-upstream-settings-search.py')
needed=sorted(set(re.findall(r'R\.drawable\.(\w+)',bottom+icons)))
vectorTool.symbol_names=lambda repo:needed
vectorBody=vectorTool.vectors(REPO).replace('DesktopSettingsSymbols','DesktopNavigationSettingsSymbols').replace('DesktopSettingsVectors','DesktopNavigationSettingsVectors')
write(out/'com/bilipai/desktop/settings/DesktopNavigationSettingsVectors.kt',vectorBody)
write(out/'com/android/purebilibili/feature/settings/DesktopNavigationMaterialSymbols.kt','''package com.android.purebilibili.feature.settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
@Composable
internal fun rememberDesktopNavigationMaterialSymbol(name:String):ImageVector=com.bilipai.desktop.settings.DesktopNavigationSettingsVectors.vector(name)
''')
if args.resource_inventory: write(args.resource_inventory,json.dumps([dict(path='app/src/main/res/drawable/'+n+'.xml',sha256=hashlib.sha256(source('app/src/main/res/drawable/'+n+'.xml').encode()).hexdigest(),features=['desktop-full-navigation-settings']) for n in needed],indent=2)+'\n')
# Original search order policy is shared by editor and actual search consumer.
tokens=parser.kotlin_tokens(search)
start=search.index('internal val defaultSearchFilterTabOrder')
end=search.index('internal fun resolveSearchDefaultPlaceholder',start)
write(out/'com/android/purebilibili/feature/search/DesktopOriginalSearchTabOrderPolicy.kt','package com.android.purebilibili.feature.search\nimport com.android.purebilibili.data.model.response.SearchType\n'+search[start:end])
# Existing exact getters/setters, same settings namespace and shared backing atomic edit.
def method(source,name):
    try:return media.function(source,name,parser)
    except ValueError:
        match=re.search(r'^[ \t]*(?:(?:internal|private|suspend) )*fun '+name+r'\([^\n]+\)[^\n=]*=[\s\S]*?(?=\n[ \t]*\n)',source,re.M)
        assert match,name
        return textwrap.dedent(match.group())
animationMethods=['getUiEntranceAnimationEnabled','setUiEntranceAnimationEnabled','getGlobalTextTapCopyEnabled','setGlobalTextTapCopyEnabled',
    'getCardAnimationEnabled','setCardAnimationEnabled','getCardTransitionEnabled','setCardTransitionEnabled',
    'getRelatedVideoTransitionEnabled','setRelatedVideoTransitionEnabled','getLiveSurfaceCardTransitionEnabled','setLiveSurfaceCardTransitionEnabled',
    'getVideoTransitionRealtimeBlurEnabled','setVideoTransitionRealtimeBlurEnabled','getFullScreenSwipeBackEnabled','setFullScreenSwipeBackEnabled',
    'setPredictiveBackEnabled','setPredictiveBackAnimationStyle','setPredictiveBackExitDirection','setMiuixTransitionBlurEnabled',
    'setMiuixPredictiveBackMaxProgressPercent','setVideoSharedReturnGestureFollowEnabled','setVideoSharedTransitionSpeed','setVideoSharedTransitionCustomDurationMillis',
    'getHeaderBlurEnabled','setHeaderBlurEnabled','getProgressiveTopBlurEnabled','setProgressiveTopBlurEnabled',
    'getProgressiveTopFadeEnabled','setProgressiveTopFadeEnabled','getBottomBarBlurEnabled','setBottomBarBlurEnabled','getBlurIntensity','setBlurIntensity']
names=sorted(set(names+animationMethods))
methods=[method(manager,name) for name in names]
methods += [media.function(manager,name,parser) for name in ['parseBottomBarItemColors','normalizeBottomBarColorItemId']]
navNames=sorted(set(re.findall(r'NavigationSettingsStore\.(\w+)\(', '\n'.join(methods))))
navMethods=[media.function(nav,name,parser) for name in navNames]
navMethods += [method(nav,'normalizeBottomBarLabelItemId'),method(nav,'normalizeBottomBarCustomLabel')]
# parseBottomBarItemLabels is already an original shared consumer declaration in product.
methods=[m.replace('NavigationSettingsStore.','DesktopOriginalFullNavigationStore.') for m in methods]
methods=[m.replace('fun getTabletUseSidebar(context: Context)', 'fun getTabletUseSidebar(context: Context, isLargeScreenCapable: Boolean)').replace('defaultTabletUseSidebar(isLargeScreenOrFoldableConfiguration(context))','defaultTabletUseSidebar(isLargeScreenCapable)') for m in methods]
methodBody='\n\n'.join(methods)
keys=sorted(set(re.findall(r'\bKEY_[A-Z_]+\b',methodBody)))
keyDecl=[]
for key in keys:
    match=re.search(r'private val '+key+r'\s*=\s*(?:boolean|int|string)PreferencesKey\("[^"]+"\)',manager)
    assert match,key;keyDecl.append(match.group())
defaults=[]
for name in sorted(set(re.findall(r'\bDEFAULT_[A-Z_]+\b',methodBody))):
    match=re.search(r'(?:private )?const val '+name+r'\s*=\s*[^\n]+',manager);assert match,name;defaults.append(match.group())
navBody='\n\n'.join(navMethods)
navKeys=sorted(set(re.findall(r'\b(?:key[A-Z]\w*|bottomBarItemLabelsPreferencesKey|miuixPredictiveBackMaxProgressPercentPreferencesKey)\b',navBody)))
navDecl=[]
for key in navKeys:
    match=re.search(r'(?:(?:internal|private) )?val '+key+r'\s*=\s*(?:boolean|int|string)PreferencesKey\("[^"]+"\)',nav);assert match,key;navDecl.append(match.group().replace('internal val','private val'))
bindings='''package com.android.purebilibili.core.store
import com.bilipai.desktop.plugins.DesktopPluginContext as Context
import com.bilipai.desktop.settings.fullNavigationBooleanKey as booleanPreferencesKey
import com.bilipai.desktop.settings.fullNavigationIntKey as intPreferencesKey
import com.bilipai.desktop.settings.fullNavigationStringKey as stringPreferencesKey
import com.bilipai.desktop.settings.fullNavigationDataStore as settingsDataStore
import com.android.purebilibili.core.store.navigation.parseBottomBarItemLabels
import com.bilipai.desktop.ui.DesktopFavoriteNavigationTypes.BottomBarVisibilityMode
import com.bilipai.desktop.ui.DesktopOriginalHomeSettingConstants.TopTabLabelMode
import com.android.purebilibili.core.ui.blur.BlurIntensity
import com.android.purebilibili.core.ui.transition.*
import kotlinx.coroutines.flow.*
'''
methodBody=methodBody.replace('SettingsManager.','DesktopOriginalFullNavigationSettings.')
sidebarDefault=re.search(r'^internal fun defaultTabletUseSidebar\([^\n]+',manager,re.M);assert sidebarDefault
methodBody += '\n\n'+sidebarDefault.group()+'\n'
methodBody += '\nfun getAppNavigationSettings(context:Context):Flow<AppNavigationSettings> = context.settingsDataStore.data.map { com.bilipai.desktop.ui.decodeDesktopOriginalHomeNavigation(it,false) }\n'
maxTop=re.search(r'const val MAX_TOP_TABS\s*=\s*[^\n]+',manager);assert maxTop
maxTopValue=maxTop.group().split('=',1)[1].strip()
write(out/'com/android/purebilibili/core/store/DesktopOriginalFullNavigationSettings.kt',bindings+'internal object DesktopOriginalFullNavigationSettings {\n    const val MAX_TOP_TABS='+maxTopValue+'\n'+textwrap.indent('\n'.join(keyDecl+defaults)+'\n'+methodBody,'    ')+'\n}\n\nprivate object DesktopOriginalFullNavigationStore {\n'+textwrap.indent('\n'.join(navDecl)+'\n'+navBody,'    ')+'\n}\n')
fullAnimation=media.function(animation,'AnimationSettingsContent',parser)
fullAnimation=fullAnimation[:fullAnimation.index('    val importSession = pendingLiquidGlassImport')]+'}\n'
fullAnimation=fullAnimation.replace('fun AnimationSettingsContent(', 'internal fun DesktopOriginalAnimationSettingsContent(')
fullAnimation=fullAnimation.replace('state: SettingsUiState,\n    viewModel: SettingsViewModel','onFailure:(Throwable)->Unit')
fullAnimation=fullAnimation.replace('val context = LocalContext.current','val context = checkNotNull(com.bilipai.desktop.settings.LocalDesktopHomeCardPreferences.current).context\n    val settingsSnapshot by context.store.snapshot("settings").collectAsState()\n    val state = com.bilipai.desktop.ui.decodeDesktopOriginalHomeSettings(settingsSnapshot)')
begin=fullAnimation.index('    val liquidGlassShareService');end=fullAnimation.index('    val listState',begin)
fullAnimation=fullAnimation[:begin]+fullAnimation[end:]
begin=fullAnimation.index('    val isLiquidGlassAvailable');end=fullAnimation.index('    val uiEntranceAnimationEnabled',begin)
fullAnimation=fullAnimation[:begin]+fullAnimation[end:]
begin=fullAnimation.index('    val previewBottomBarItems');end=fullAnimation.index('    val videoTransitionRealtimeBlurEnabled',begin)
fullAnimation=fullAnimation[:begin]+fullAnimation[end:]
def removeBlock(body,marker):
    begin=body.index(marker);tokens=parser.kotlin_tokens(body[begin:]);opening=next(i for i,t in enumerate(tokens) if t[0]=='{');depth=1;ending=opening
    while depth:ending+=1;depth+=(tokens[ending][0]=='{')-(tokens[ending][0]=='}')
    return body[:begin]+body[begin+tokens[ending][2]:]
# Android SAF/content-URI/share-intent preview/import closure is not falsely represented by ZIP backup.
fullAnimation=removeBlock(fullAnimation,'if (isLiquidGlassAvailable && state.androidNativeLiquidGlassEnabled)')
# Desktop has no vibration hardware provider. Do not ship a live switch claiming one.
def exact_call(body,marker,name,parser):
    point=body.index(marker);start=body.rfind(name+'(',0,point);assert start>=0
    tokens=parser.kotlin_tokens(body[start:]);opening=next(i for i,t in enumerate(tokens) if t[0]=='(')
    depth=1;ending=opening
    while depth:
        ending+=1;depth+=(tokens[ending][0]=='(')-(tokens[ending][0]==')')
    return body[start:start+tokens[ending][2]]
haptic=exact_call(fullAnimation,'title = "触感反馈"','AppSwitchPreference',parser)
fullAnimation=fullAnimation.replace(haptic,'AppText("Windows 当前没有振动反馈设备绑定。")')
fullAnimation=fullAnimation.replace('val scope = rememberCoroutineScope()','val latestFailure by rememberUpdatedState(onFailure)\n    val scope = rememberCoroutineScope { kotlinx.coroutines.CoroutineExceptionHandler { _, error -> latestFailure(error) } }')
fullAnimation=fullAnimation.replace('.collectAsStateWithLifecycle(','.collectAsState(').replace('initialValue =','initial =')
for prop in ['headerBlurEnabled','progressiveTopBlurEnabled','progressiveTopFadeEnabled','bottomBarBlurEnabled','blurIntensity']:
    fullAnimation=fullAnimation.replace('state.'+prop,prop)
extra='\n'.join('    val '+prop+' by DesktopOriginalFullNavigationSettings.'+getter+'(context).collectAsState(initial='+value+')' for prop,getter,value in [
    ('headerBlurEnabled','getHeaderBlurEnabled','true'),('progressiveTopBlurEnabled','getProgressiveTopBlurEnabled','false'),('progressiveTopFadeEnabled','getProgressiveTopFadeEnabled','true'),('bottomBarBlurEnabled','getBottomBarBlurEnabled','false'),('blurIntensity','getBlurIntensity','BlurIntensity.THIN')])+'\n'
fullAnimation=fullAnimation.replace('    val scope =',extra+'    val scope =',1)
for old,new in [('toggleCardAnimation','setCardAnimationEnabled'),('toggleCardTransition','setCardTransitionEnabled'),('toggleLiveSurfaceCardTransition','setLiveSurfaceCardTransitionEnabled'),('toggleVideoTransitionRealtimeBlur','setVideoTransitionRealtimeBlurEnabled'),('toggleHeaderBlur','setHeaderBlurEnabled'),('toggleProgressiveTopBlur','setProgressiveTopBlurEnabled'),('toggleProgressiveTopFade','setProgressiveTopFadeEnabled'),('toggleBottomBarBlur','setBottomBarBlurEnabled'),('setBlurIntensity','setBlurIntensity'),('setVideoSharedTransitionCustomDurationMillis','setVideoSharedTransitionCustomDurationMillis')]:
    fullAnimation=re.sub(r'viewModel\.'+old+r'\(([^()]*)\)',lambda m:'scope.launch { DesktopOriginalFullNavigationSettings.'+new+'(context, '+m.group(1)+') }',fullAnimation)
fullAnimation=fullAnimation.replace('viewModel::setVideoSharedTransitionSpeed','{ value -> scope.launch { DesktopOriginalFullNavigationSettings.setVideoSharedTransitionSpeed(context,value) } }')
fullAnimation=re.sub(r'\bSettingsManager\b','DesktopOriginalFullNavigationSettings',fullAnimation)
fullAnimation=fullAnimation.replace('val skeletonBreathingEnabled = com.android.purebilibili.core.ui.skeleton.rememberSkeletonBreathingEnabled()', 'val skeletonBreathingEnabled by com.android.purebilibili.core.store.SkeletonSettingsStore.breathingEnabled(context).collectAsState(initial = true)')
fullAnimation=re.sub(r'(?:com\.android\.purebilibili\.)?R\.drawable\.(\w+)',lambda m:'"'+m.group(1)+'"',fullAnimation)
fullAnimation=fullAnimation.replace('com.android.purebilibili.feature.settings.rememberMaterialSymbol(', 'rememberDesktopNavigationMaterialSymbol(')
# Platform budget/local provider remains authoritative; the original switch copy's API-33 qualifier is Android-only.
fullAnimation=fullAnimation.replace('顶栏模糊随滚动渐变（需 Android 13+）','顶栏模糊随滚动渐变')
animationImports=animation[:animation.index('@Composable')]
animationImports='\n'.join(line for line in animationImports.splitlines() if line.startswith(('package ','import ')) and not any(s in line for s in ['import android.','androidx.activity','LocalContext','stringResource','lifecycle','purebilibili.R','core.store.SettingsManager','feature.settings.share','SettingsPageScaffold','shouldAllowHomeChromeLiquidGlass','LiquidGlassSettingsStore']))+'\n'
animationImports += 'import com.android.purebilibili.core.store.DesktopOriginalFullNavigationSettings\n'
write(out/'com/android/purebilibili/feature/settings/DesktopOriginalAnimationSettingsContent.kt',animationImports+'@Composable\n'+fullAnimation)
for path in ([] if args.policy_only else [prefix+'feature/settings/ui/SettingsComponents.kt',prefix+'feature/settings/SettingsEntranceMotionPolicy.kt']):
    write(out/('com/android/purebilibili/feature/settings/'+Path(path).name),source(path))
entrancePath=prefix+'core/ui/animation/AppEntrance.kt'
motionPath='design-system/src/main/java/com/android/purebilibili/core/ui/motion/AppEntranceMotion.kt'
if not args.policy_only:
    write(out/'com/android/purebilibili/core/ui/animation/AppEntrance.kt',source(entrancePath))
    write(out/'com/android/purebilibili/core/ui/motion/AppEntranceMotion.kt',source(motionPath))
if args.inventory: write(args.inventory,json.dumps([dict(path=p,sha256=hashlib.sha256(source(p).encode()).hexdigest(),mode='policy-extract' if p not in [entrancePath,motionPath,prefix+'feature/settings/ui/SettingsComponents.kt',prefix+'feature/settings/SettingsEntranceMotionPolicy.kt'] else 'direct',features=['desktop-full-navigation-settings']) for p in [bottomPath,managerPath,navPath,iconPath,searchPath,animationPath,entrancePath,motionPath,prefix+'feature/settings/ui/SettingsComponents.kt',prefix+'feature/settings/SettingsEntranceMotionPolicy.kt']],ensure_ascii=False,indent=2)+'\n')
