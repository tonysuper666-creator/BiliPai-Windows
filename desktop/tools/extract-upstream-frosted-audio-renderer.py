"""Complete original stable Frosted navigation and audio bar source closure."""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import argparse,hashlib,importlib.util,json,re,subprocess
PIN='79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40'
BASE='app/src/main/java/com/android/purebilibili/'
DIRECT=['feature/home/components/'+n for n in ['BottomBarUiSkin','BottomBarTypographySpec','BottomBarChromeMotionSpec','SideBarRendererPolicy','SideBarMotionSpec']]+['feature/audio/screen/'+n for n in ['AudioNowPlayingBar','AudioNowPlayingBarMotionPolicy','AudioNowPlayingVisibilityPolicy','MusicPlayerChromePolicy','MusicArtworkRotationPolicy','MusicPlayerLayoutPolicy']]+['core/ui/transition/NowPlayingBarHandoff','feature/audio/player/AudioNowPlayingSession']
SOURCES=[BASE+p+'.kt' for p in DIRECT]+[BASE+'feature/home/components/BottomBar.kt',BASE+'core/store/SettingsManager.kt',BASE+'feature/home/components/TopTabStylePolicy.kt',BASE+'core/store/home/LiquidGlassSettingsStore.kt']
SHARED=['iosIndicatorSpecular','AndroidNativeBottomBarTuning','resolveAndroidNativeBottomBarTuning','resolveAndroidNativeBottomBarContainerColor','resolveAndroidNativeFloatingBottomBarContainerColor','resolveAndroidNativeBottomBarGlassEnabled','shouldUseAndroidNativeFloatingHazeBlur','shouldRenderBottomBarLiquidGlassEffects','biliPaiMiuixFloatingDockSurface','resolveBiliPaiBottomBarContainerColor','resolveBottomBarDarkTheme','BOTTOM_BAR_INDICATOR_DRAG_SCALE_TARGET','resolveBottomBarCaptureSafeInsetDp','resolveBottomBarSurfaceColor','resolveBiliPaiBottomBarShellColor','BottomBarItemMotionVisual','resolveBottomBarItemCoverage','resolveBottomBarItemMotionScale','resolveBottomBarItemMotionVisual']
LINKED=['BottomNavItem','SharedFloatingBottomBarIconStyle','normalizeBottomBarLabelMode','resolveBiliPaiFloatingBottomBarWidth','resolveMaterialBottomBarIcon','resolveHomeNavigationBarIcon','resolveSharedBottomBarIcon','BiliPaiBottomBarSearchVisualContent','shouldRequestBottomBarSearchIme','resolveBottomNavItemLabel','resolveBottomNavItemContentDescription','resolveBottomNavItemLookupKeys']
EXISTING=['resolveSharedBottomBarCapsuleShape']
def safe(p):
 v=str(Path(p).absolute());return Path(v if v.startswith('\\\\?\\') else '\\\\?\\'+v)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def write(p,t):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(t,encoding='utf-8',newline='\n')
def sha(t):return hashlib.sha256(t.encode()).hexdigest()
def load(p,n):
 s=importlib.util.spec_from_file_location(n,p);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
def declarations(parser,source):
 tokens=parser.kotlin_tokens(source);depth=parens=brackets=0;starts=[]
 for i,(t,a,b) in enumerate(tokens):
  if depth==parens==brackets==0 and t in ('fun','val','var','class','object','interface'):
   if t=='fun':
    j=i+1
    while tokens[j][0]!='(':j+=1
    name=tokens[j-1][0]
   else:name=tokens[i+1][0]
   start=source.rfind('\n',0,a)+1
   while start>0:
    previous=source.rfind('\n',0,start-1)+1
    if source[previous:start].strip().startswith('@'):start=previous
    else:break
   starts.append((name,start))
  depth+=(t=='{')-(t=='}');parens+=(t=='(')-(t==')');brackets+=(t=='[')-(t==']')
 return [(n,source[a:(starts[i+1][1] if i+1<len(starts) else len(source))].rstrip()+'\n') for i,(n,a) in enumerate(starts)]
def inventory(repo):return [dict(path=p,mode='policy-extract',features=['stable-frosted-audio-renderer'],sha256=sha(read(_desktop_canonical_source(repo, p)))) for p in SOURCES]
def generate(repo,output):
 manifest=json.loads(read(repo/'desktop/upstream-sources.json'));assert manifest['upstreamCommit']==PIN
 rows={r['path']:r for r in manifest['sources']};records=[];original={}
 parser=load(repo/'desktop/tools/sync-upstream.py','frosted_audio_tokens')
 for p in SOURCES:
  s=read(_desktop_canonical_source(repo, p));raw=subprocess.check_output(['git','show',PIN+':'+p],cwd=repo).decode().replace('\r\n','\n');assert s==raw,p
  assert p in rows and rows[p]['sha256']==sha(s),p
  original[p]=s
 def emit(p,t,name=None,selected=None,adaptations=()):
  package=re.search(r'(?m)^package (\S+)',t).group(1);target=output/package.replace('.','/')/(name or Path(p).name)
  write(target,t);records.append(dict(source=p,sourceSHA256LF=sha(original[p]),pinnedGitBlob=subprocess.check_output(['git','rev-parse',PIN+':'+p],cwd=repo,text=True).strip(),output=str(target.relative_to(output)),outputSHA256LF=sha(t),selected=selected,adaptations=list(adaptations)))
  write(output/'original-retained'/(p+'.txt'),original[p])
 for rel in DIRECT:
  p=BASE+rel+'.kt';t=original[p];adapt=[]
  if rel.endswith('SideBarMotionSpec'):
   selected=[(n,b) for n,b in declarations(parser,t) if n not in ['FloatingBottomBarSelectionScale','resolveNavigationIconCrossScale']]
   t='\n'.join(l for l in t.splitlines() if l.startswith(('package ','import ')))+'\n\n'+'\n'.join(b for _,b in selected)
   emit(p,t,'DesktopOriginalSideBarMotion.kt',[dict(name=n,sha256LF=sha(b)) for n,b in selected],['Reuse sole shared32 FloatingBottomBarSelectionScale and resolveNavigationIconCrossScale; every other complete original declaration retained'])
   continue
  if 'import androidx.compose.ui.platform.LocalConfiguration' in t:
   t=t.replace('import androidx.compose.ui.platform.LocalConfiguration','import com.android.purebilibili.core.util.LocalWindowSizeClass as LocalConfiguration').replace('configuration.screenWidthDp','configuration.widthDp.value').replace('configuration.screenHeightDp','configuration.heightDp.value');adapt.append('Actual Root LocalWindowSizeClass widthDp/heightDp replace Android Configuration dimensions')
  if 'import com.android.purebilibili.core.ui.motion.rememberSystemReduceMotion' in t:
   t=t.replace('import com.android.purebilibili.core.ui.motion.rememberSystemReduceMotion','import com.bilipai.desktop.ui.rememberDesktopDynamicReduceMotion as rememberSystemReduceMotion');adapt.append('Same existing actual Windows system motion bridge')
  if rel.endswith('AudioNowPlayingBar'):
   # ThanosEffectView attaches an Android Window/GL surface. Windows follows
   # the original unsupported-GL dismissal branch under the same source lease.
   start=t.index('    val dissolveContext = LocalContext.current')
   end=t.index('    val handleExpand = {',start)
   original_android_dissolve=t[start:end]
   assert 'hostWindow == null || !isThanosEffectSupported(dissolveContext)' in original_android_dissolve
   fallback='    val cancelDissolving = false\n    val dissolveContentHidden = false\n    var dissolveRecorded by remember { mutableStateOf(false) }\n    val dissolveLayer = rememberGraphicsLayer()\n    val handleCancelClick: () -> Unit = { if (sourceIsOwned()) onDismiss() }\n\n'
   t=t[:start]+fallback+t[end:]
   adapt.append(dict(platform='Original unsupported Android GL capability branch',original=original_android_dissolve,replacement=fallback,windowsThanosGlSupported=False))
   for platform_import in ['import com.android.purebilibili.core.ui.animation.gl.ThanosEffectView\n','import com.android.purebilibili.core.ui.animation.gl.isThanosEffectSupported\n','import com.android.purebilibili.core.ui.findHostActivity\n','import androidx.compose.ui.graphics.asAndroidBitmap\n','import androidx.compose.ui.platform.LocalContext\n']:
    assert t.count(platform_import)==1;t=t.replace(platform_import,'',1)
   assert t.count('    state: AudioNowPlayingBarState,')==1
   t=t.replace('    state: AudioNowPlayingBarState,','    state: AudioNowPlayingBarState,\n    sourceIsOwned: () -> Boolean,',1)
   t=t.replace('val handleExpand = {','val handleExpand = ownedExpand@{\n        if (!sourceIsOwned()) return@ownedExpand',1)
   t=t.replace('        CardPositionManager.invalidateVideoSourceIfWindowChanged(screenWidthPx, screenHeightPx)','        if (sourceIsOwned()) CardPositionManager.invalidateVideoSourceIfWindowChanged(screenWidthPx, screenHeightPx)',1)
   adapt.append('Required same Root/page/epoch/current-item ownership guard before original global bounds write/compact callback/window invalidation; original complete expansion body unchanged')
  emit(p,t,adaptations=adapt)
 p=BASE+'feature/home/components/BottomBar.kt';source=original[p];decls=declarations(parser,source);excluded=set(SHARED+LINKED+EXISTING)
 assert excluded<=set(n for n,_ in decls),(excluded-set(n for n,_ in decls))
 selected=[(n,b) for n,b in decls if n not in excluded]
 header='\n'.join(l for l in source.splitlines() if l.startswith(('package ','import ')))+'\n\n'
 header+='import com.android.purebilibili.feature.home.components.desktopOriginalBottomBarIosIndicatorSpecular as iosIndicatorSpecular\n'
 t=header+'\n'.join(b for _,b in selected)
 for unused in ['import android.os.Build\n','import android.os.SystemClock\n','import androidx.annotation.StringRes\n','import androidx.compose.ui.res.stringResource\n','import com.android.purebilibili.R\n','import com.android.purebilibili.core.ui.blur.shouldAllowDirectHazeLiquidGlassFallback\n','import com.android.purebilibili.core.ui.blur.shouldAllowHomeChromeLiquidGlass\n','import com.android.purebilibili.core.ui.blur.shouldAllowRenderEffectBackedHazeEffect\n']:
  t=t.replace(unused,'')
 t=t.replace('SystemClock.elapsedRealtime()','(System.nanoTime() / 1_000_000L)')
 t=t.replace('com.android.purebilibili.core.store.HomeSettings = com.android.purebilibili.core.store.HomeSettings()','com.bilipai.desktop.settings.DesktopOriginalFrostedHomePreferences').replace('com.android.purebilibili.core.store.HomeSettings','com.bilipai.desktop.settings.DesktopOriginalFrostedHomePreferences')
 t=re.sub(r'stringResource\(R\.string\.(\w+)\)',lambda m:'com.bilipai.desktop.appearance.LocalDesktopStrings.current["'+m.group(1)+'"]',t)
 emit(p,t,'DesktopOriginalFrostedNavigation.kt',[dict(name=n,sha256LF=sha(b)) for n,b in selected],['Exclude exact sole existing shared32 / LinkedDock / capsule declarations; preserve every other complete original declaration','Required named read-only original getHomeSettings subset replaces Android HomeSettings constructor','Android elapsedRealtime -> JVM monotonic nanoseconds in milliseconds','Sidebar strings -> same Root original XML LocalDesktopStrings'])
 p=BASE+'feature/home/components/TopTabStylePolicy.kt';write(output/'original-retained'/(p+'.txt'),original[p])
 records.append(dict(source=p,sourceSHA256LF=sha(original[p]),mode='reference-only actual17 sole full original Favorites TopTabStylePolicy',output=None))
 p=BASE+'core/store/home/LiquidGlassSettingsStore.kt';write(output/'original-retained'/(p+'.txt'),original[p])
 assert 'intPreferencesKey("liquid_glass_readability_mode")' in original[p]
 records.append(dict(source=p,sourceSHA256LF=sha(original[p]),mode='reference-only exact original readability key; same DesktopPreferenceSnapshot typed lookup',output=None))
 p=BASE+'core/store/SettingsManager.kt';selected=[(n,b) for n,b in declarations(parser,original[p]) if n in ['BottomBarSearchAutoExpandMode','BottomBarSearchLayoutMode']]
 assert len(selected)==2
 emit(p,'package com.android.purebilibili.core.store\n\n'+'\n'.join(b for _,b in selected),'DesktopOriginalBottomSearchModes.kt',[dict(name=n,sha256LF=sha(b)) for n,b in selected])
 settings_preferences(parser,original,emit)
 write(output/'source-inventory.json',json.dumps(dict(commit=PIN,sources=records,excludedBottomBarDeclarations=dict(shared=SHARED,linked=LINKED,actual=EXISTING)),ensure_ascii=False,indent=2)+'\n')
 return records
def settings_preferences(parser,original,emit):
 p=BASE+'core/store/SettingsManager.kt';s=original[p];manager=s[s.index('object SettingsManager {')+len('object SettingsManager {'):s.rfind('}')];decls=declarations(parser,manager)
 # Selected original return arguments, without recreating the unrelated HomeSettings orchestration.
 start=s.index('        return HomeSettings(');end=s.index('            homeHeaderCollapseMode =',start)
 prefix=s[s.index('        val liquidGlassProgress =',s.index('val legacyLiquidGlassEnabled')):start]
 argsource=s[start:end];fields={'navigationIconCrossScaleEnabled':'Boolean','isBottomBarSearchEnabled':'Boolean','linkedDockMergeOnScrollEnabled':'Boolean','bottomBarSearchAutoExpandMode':'BottomBarSearchAutoExpandMode','bottomBarSearchLayoutMode':'BottomBarSearchLayoutMode','androidNativeLiquidGlassEnabled':'Boolean','liquidGlassProgress':'Float','liquidGlassReadabilityMode':'LiquidGlassReadabilityMode','liquidGlassAdvancedSettings':'LiquidGlassAdvancedSettings'}
 tokens=parser.kotlin_tokens(argsource);depth=0;segments=[];last=next(i for i,x in enumerate(tokens) if x[0]=='(')+1
 for i in range(last,len(tokens)):
  v=tokens[i][0];depth+=(v in ('(','{','['))-(v in (')','}',']'))
  if v==',' and depth==0:
   segment=argsource[tokens[last][1]:tokens[i][1]].strip();segments.append(segment);last=i+1
 args=[a for a in segments if a.split('=',1)[0].strip() in fields];assert len(args)==len(fields),(args,fields)
 chosen=prefix+'\n        return DesktopOriginalFrostedHomePreferences(\n            '+',\n            '.join(args)+',\n            bottomBarLiquidGlassPreset = BottomBarLiquidGlassPreset.BILIPAI_TUNED,\n        )'
 keys=set(re.findall(r'\bKEY_\w+\b',chosen));keybodies=[b for n,b in decls if n in keys];assert len(keybodies)==len(keys)
 body='''package com.bilipai.desktop.settings
import com.android.purebilibili.core.store.*
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPreferenceSnapshot
import com.bilipai.desktop.plugins.booleanPreferencesKey
import com.bilipai.desktop.plugins.DesktopPreferenceKey
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import com.bilipai.desktop.settings.dynamicIntPreferencesKey as intPreferencesKey
import com.bilipai.desktop.settings.dynamicSettingsDataStore as settingsDataStore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
/** Original getHomeSettings 10-field projection. Root supplies its sole application store context. */
data class DesktopOriginalFrostedHomePreferences(
'''+''.join('    val '+n+':'+kind+',\n' for n,kind in fields.items())+'''    val bottomBarLiquidGlassPreset:BottomBarLiquidGlassPreset,
)
private fun floatPreferencesKey(name:String)=DesktopPreferenceKey(name){(it as? JsonPrimitive)?.floatOrNull}
internal class DesktopOriginalFrostedSettings(context:DesktopPluginContext) {
    val preferences=context.settingsDataStore.data.map { decodeOriginalFrostedHomePreferences(it) }.distinctUntilChanged()
}
'''+''.join(keybodies)+'''private fun decodeOriginalFrostedHomePreferences(preferences:DesktopPreferenceSnapshot):DesktopOriginalFrostedHomePreferences {
'''+chosen.replace('preferences[liquidGlassReadabilityModePreferencesKey]','preferences[intPreferencesKey("liquid_glass_readability_mode")]')+'\n}\n'
 emit(p,body,'DesktopOriginalFrostedSettings.kt',selected=[dict(name=n,sha256LF=sha(b)) for n,b in decls if n in keys],adaptations=['Exact original getHomeSettings liquid locals, keys and 9 selected return expressions projected into a distinctly named required readonly view; fixed preset matches original HomeSettings field default'])
if __name__=='__main__':
 a=argparse.ArgumentParser();a.add_argument('--repo',type=Path,required=True);a.add_argument('--output',type=Path,required=True);v=a.parse_args();generate(v.repo,v.output)
