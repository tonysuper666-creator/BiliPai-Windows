"""Sole stable shared tab/liquid original renderer producer; task-only prepare."""
from pathlib import Path
import hashlib,importlib.util,json,re,subprocess,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
MAIN=next(p for p in HERE.parents if (p/'.git').exists());REPO=MAIN.parent/'BiliPai-v023'
COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589';BASE='app/src/main/java/com/android/purebilibili/';HOME=BASE+'feature/home/components/'
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def write(p,s):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def load(n,p):
 spec=importlib.util.spec_from_file_location(n,p);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
def one(s,a,b):assert s.count(a)==1,(s.count(a),a[:100]);return s.replace(a,b,1)
def main():
 parser=load('liquid_selected_parser',REPO/'desktop/tools/sync-upstream.py')
 def declarations(source):
  tokens=parser.kotlin_tokens(source);depth=parens=brackets=0;starts=[]
  for i,(token,start,end) in enumerate(tokens):
   if depth==parens==brackets==0 and token in ('fun','val','var','class','object','interface'):
    if token=='fun':
     j=i+1
     while tokens[j][0]!='(':j+=1
     name=tokens[j-1][0]
    else:name=tokens[i+1][0]
    line=source.rfind('\n',0,start)+1
    while line>0:
     before=source.rfind('\n',0,line-1)+1
     if source[before:line].strip().startswith('@'):line=before
     else:break
    starts.append((name,line))
   depth+=(token=='{')-(token=='}');parens+=(token=='(')-(token==')');brackets+=(token=='[')-(token==']')
  return [(name,source[start:(starts[i+1][1] if i+1<len(starts) else len(source))].rstrip()+'\n') for i,(name,start) in enumerate(starts)]
 records=[]
 def emit(path,names=None,imports='',transforms=()):
  source=read(REPO/path);original=subprocess.check_output(['git','show',COMMIT+':'+path],cwd=REPO).decode().replace('\r\n','\n');assert source==original,path
  write(HERE/'original-source'/path,source)
  package=re.search(r'(?m)^package (\S+)',source).group(1)
  selected=[]
  if names is None:text=source
  else:
   decls=declarations(source)
   for name in dict.fromkeys(names):
    values=[value for n,value in decls if n==name];assert values,(path,name)
    selected += [dict(name=name,sha256LF=sha(v),body=v) for v in values]
   text='package '+package+'\n'+imports+'\n\n'+'\n'.join(x['body'] for x in selected)
  adaptations=[]
  for a,b in transforms:text=one(text,a,b);adaptations.append(dict(original=a,replacement=b))
  output=HERE/'generated'/package.replace('.','/')/Path(path).name
  write(output,'// OriginalSource: '+path+'\n// OriginalSHA256: '+sha(source)+'\n'+text)
  records.append(dict(path=path,sha256LF=sha(source),output=str(output.relative_to(HERE)),mode='direct' if names is None else 'selected',declarations=[{k:v for k,v in x.items() if k!='body'} for x in selected],adaptations=adaptations))
 inv=json.loads(read(HERE/'initial-closure-inventory.json'))
 for row in inv['sources']:
  p=row['path'];t=[]
  if p.endswith('LiquidGlassAdaptiveReadability.kt'):continue
  s=read(REPO/p)
  selected_names=None;selected_imports=''
  if p.endswith('BottomBarMatchedLiquidChrome.kt'):
   selected_names=[]
   for n,_ in declarations(s):
    selected_names.append(n)
    if n=='BottomBarMatchedReusableLiquidDock':break
   selected_imports='\n'.join(line for line in s.splitlines() if line.startswith('import '))
  if 'import androidx.compose.ui.platform.LocalConfiguration' in s:t.append(('import androidx.compose.ui.platform.LocalConfiguration','import com.bilipai.desktop.ui.DesktopDynamicWindowConfiguration as LocalConfiguration'))
  if 'import android.os.Build\n' in s:t.append(('import android.os.Build\n','import com.bilipai.desktop.ui.desktopDetailRenderEffectsSupported\n'))
  if p.endswith('BottomBarLiquidSegmentedControl.kt'):
   t += [('import com.android.purebilibili.core.ui.blur.shouldAllowHomeChromeLiquidGlass\n',''),('    sdkInt: Int = Build.VERSION.SDK_INT,\n','    renderEffectsSupported: Boolean = desktopDetailRenderEffectsSupported(),\n'),('!shouldAllowHomeChromeLiquidGlass(sdkInt)','!renderEffectsSupported')]
  if p.endswith('FloatingBottomBar.kt'):t.append(('Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU','top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported()'))
  if p.endswith('BottomBarFloatingSegmentedControl.kt') or p.endswith('BottomBarLiquidSegmentedControl.kt'):
   t += [('import androidx.compose.ui.platform.LocalContext','import com.bilipai.desktop.settings.LocalDesktopLiquidTabSettings'),('import com.android.purebilibili.core.store.HomeSettings','import com.bilipai.desktop.settings.DesktopLiquidTabHomePreferences as HomeSettings'),('import com.android.purebilibili.core.store.SettingsManager\n',''),('val context = LocalContext.current','val context = LocalDesktopLiquidTabSettings.current'),('SettingsManager\n        .getHomeSettings(context)','context.homeSettings')]
  if p.endswith('miuix/DampedDragAnimation.kt'):t += [('import android.os.SystemClock\n',''),('SystemClock.uptimeMillis()','System.nanoTime() / 1_000_000L')]
  if p.endswith('miuix/InteractiveHighlight.kt'):
   t += [('import android.annotation.SuppressLint\n',''),('import android.graphics.RuntimeShader','import top.yukonga.miuix.kmp.shader.RuntimeShader\nimport top.yukonga.miuix.kmp.shader.asBrush'),('@SuppressLint("NewApi")\n',''),('ShaderBrush(shader)','shader.asBrush()'),('InteractiveHighlightPalette.Content.copy(0.12f * progress).toArgb(),','Color(InteractiveHighlightPalette.Content.copy(0.12f * progress).toArgb()),')]
  emit(p,selected_names,selected_imports,transforms=t)
 emit('design-system/src/main/java/com/android/purebilibili/core/ui/animation/DampedDragAnimation.kt',transforms=[('import android.os.SystemClock\n',''),('import kotlinx.coroutines.android.awaitFrame','import androidx.compose.runtime.withFrameNanos'),('awaitFrame()','withFrameNanos { it }'),('SystemClock.uptimeMillis()','System.nanoTime() / 1_000_000L')])
 emit(HOME+'BottomBarGlassMaterialPolicy.kt')
 emit('design-system/src/main/java/com/android/purebilibili/core/ui/motion/BottomBarMotionSpec.kt')
 emit(HOME+'HomeSelectionIndicatorPolicy.kt')
 emit('design-system/src/main/java/com/android/purebilibili/core/ui/OpticalContrastPalette.kt')
 emit(BASE+'feature/home/HomeVisualPalette.kt')
 emit(HOME+'SideBarMotionSpec.kt',['FloatingBottomBarSelectionScale','resolveNavigationIconCrossScale'])
 emit(BASE+'core/store/LiquidGlassReadabilityMode.kt')
 emit(BASE+'core/store/SettingsManager.kt',['LiquidGlassStyle','LiquidGlassMode','LiquidGlassAdvancedPreset','LiquidGlassAdvancedSettings','normalizeLiquidGlassAdvancedValue','resolveLiquidGlassAdvancedPreset','resolveLiquidGlassAdvancedSettings','resolveLegacyLiquidGlassMode','resolveDefaultLiquidGlassStrength','normalizeLiquidGlassStrength','normalizeLiquidGlassProgress','resolveLegacyLiquidGlassProgress','resolveStoredLiquidGlassProgress','resolveLiquidGlassModeFromProgress','resolveLiquidGlassStrengthFromProgress','BottomBarLiquidGlassPreset'])
 imports=read(MAIN/'desktop/.local/dynamic-detail-reply-parity/detail-container-next/liquid-next/generated/com/android/purebilibili/feature/home/components/DesktopOriginalDetailLiquidDockSurface.kt').split('package com.android.purebilibili.feature.home.components\n',1)[1].split('private val iosIndicatorSpecular',1)[0]
 imports+='\nimport kotlin.math.abs\n'
 emit(HOME+'BottomBar.kt',['iosIndicatorSpecular','AndroidNativeBottomBarTuning','resolveAndroidNativeBottomBarTuning','resolveAndroidNativeBottomBarContainerColor','resolveAndroidNativeFloatingBottomBarContainerColor','resolveAndroidNativeBottomBarGlassEnabled','shouldUseAndroidNativeFloatingHazeBlur','shouldRenderBottomBarLiquidGlassEffects','biliPaiMiuixFloatingDockSurface','resolveBiliPaiBottomBarContainerColor','resolveBottomBarDarkTheme','BOTTOM_BAR_INDICATOR_DRAG_SCALE_TARGET','resolveBottomBarCaptureSafeInsetDp','resolveBottomBarSurfaceColor','resolveBiliPaiBottomBarShellColor','BottomBarItemMotionVisual','resolveBottomBarItemCoverage','resolveBottomBarItemMotionScale','resolveBottomBarItemMotionVisual'],imports=imports,transforms=[('    sdkInt: Int = Build.VERSION.SDK_INT\n): Boolean = liquidGlassEnabled && shouldAllowHomeChromeLiquidGlass(sdkInt)','): Boolean = liquidGlassEnabled && desktopDetailRenderEffectsSupported()'),('    sdkInt: Int = Build.VERSION.SDK_INT\n): Boolean = blurEnabled &&\n    !glassEnabled &&\n    hasHazeState &&\n    shouldAllowRenderEffectBackedHazeEffect(sdkInt)','): Boolean = blurEnabled &&\n    !glassEnabled &&\n    hasHazeState &&\n    desktopDetailRenderEffectsSupported()')])
 # Full original adaptive policy/state/UI kept; only Android capture/owner types map.
 adaptive=read(REPO/(HOME+'LiquidGlassAdaptiveReadability.kt'))
 vals=[n for n,_ in declarations(adaptive) if n not in ['adaptiveReadabilityPixelCopyHandler','sampleWindowLuminance','findLiquidGlassHostActivity']]
 emit(HOME+'LiquidGlassAdaptiveReadability.kt',vals,imports='''import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.pow
import kotlin.math.roundToInt
import com.bilipai.desktop.ui.DesktopLiquidSampleBitmap as Bitmap
import com.bilipai.desktop.ui.DesktopLiquidSampleRect as Rect
import com.bilipai.desktop.ui.LocalDesktopLiquidReadabilityEnvironment
import com.bilipai.desktop.ui.DesktopLiquidReadabilityEnvironment
''',transforms=[('val context = LocalContext.current','val context = LocalDesktopLiquidReadabilityEnvironment.current'),('val activity = remember(context) { context.findLiquidGlassHostActivity() }','val activity = context'),('Build.VERSION.SDK_INT < Build.VERSION_CODES.O','!activity.isSupported'),('val decorView = activity.window.decorView','val decorView = activity')])
 adaptive_path=HERE/'generated/com/android/purebilibili/feature/home/components/LiquidGlassAdaptiveReadability.kt'
 write(adaptive_path,read(adaptive_path)+'''\n/** Windows capture only; averaging/hysteresis above are the original bodies. */
private suspend fun sampleWindowLuminance(activity: DesktopLiquidReadabilityEnvironment, sourceBounds: Rect): Float? =
    activity.sampleBitmap(sourceBounds, ADAPTIVE_READABILITY_SAMPLE_WIDTH, ADAPTIVE_READABILITY_SAMPLE_HEIGHT)
        ?.averageRelativeLuminance()
''')
 write(HERE/'source-inventory.json',json.dumps(dict(originalCommit=COMMIT,sourceCount=len(records),sources=records,explicitActualReuse=['resolveSharedBottomBarCapsuleShape','BILIPAI_PROGRESSIVE_TOP_BLUR_START_FRACTION','desktopDetailRenderEffectsSupported','DesktopDynamicWindowConfiguration','AppNativeTabRow','AppNativeSegmentedControl','AppSegmentOption','resolveReadableNativeTabMinWidth'],noNewHomeSettings=True,noNewSettingsManager=True,noNewStore=True,noNewClient=True),ensure_ascii=False,indent=2)+'\n')
 print(json.dumps(dict(preparedSources=len(records))))
if __name__=='__main__':main()
