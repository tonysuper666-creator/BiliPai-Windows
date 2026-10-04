"""Sole stable shared tab/liquid original renderer producer; task-only prepare."""
from v025_source_paths import canonical_source as _desktop_canonical_source
from pathlib import Path
import hashlib,importlib.util,json,re,subprocess,sys
sys.dont_write_bytecode=True
COMMIT='79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40';BASE='app/src/main/java/com/android/purebilibili/';HOME=BASE+'feature/home/components/'
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def write(p,s):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def load(n,p):
 spec=importlib.util.spec_from_file_location(n,p);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
def one(s,a,b):assert s.count(a)==1,(s.count(a),a[:100]);return s.replace(a,b,1)
V027_LENS_COMMIT='e5a6b59a69ed2de9ea4e69dc7675ea05de495ebf'
V027_LENS_PATH=HOME+'liquid/Lens.kt'
V027_LENS_ARCHIVE=Path('desktop/upstream-slices/v027-liquid-glass-lens')
V027_LENS_SHA256='ebf283f428d780760f311daf5f44f3efbc4f448ec2f97049595e8ab622cb431d'
V027_LENS_BLOB='f968235238d14c6563ca9424c70ac433967058cd'
V027_LENS_BYTES=8134
LENS_PREFLIGHT_CONSTANTS=('ROUNDED_RECT_REFRACTION_SHADER','ROUNDED_RECT_REFRACTION_WITH_DISPERSION_SHADER')

def fixed_v027_lens(repo):
 root=Path(repo)/V027_LENS_ARCHIVE
 manifest=json.loads(safe(root/'manifest.json').read_bytes())
 expected=dict(schemaVersion=1,fixedUpstreamCommit=V027_LENS_COMMIT,originalPath=V027_LENS_PATH,
  archiveFile='Lens.kt',gitBlobOid=V027_LENS_BLOB,sha256Bytes=V027_LENS_SHA256,
  sha256LF=V027_LENS_SHA256,bytes=V027_LENS_BYTES)
 assert manifest==expected,'Changed fixed v027 Lens manifest identity'
 blob=safe(root/'Lens.kt').read_bytes()
 assert len(blob)==V027_LENS_BYTES and hashlib.sha256(blob).hexdigest()==V027_LENS_SHA256,'Changed fixed v027 Lens bytes'
 assert hashlib.sha1(b'blob '+str(len(blob)).encode()+b'\0'+blob).hexdigest()==V027_LENS_BLOB,'Changed fixed v027 Lens blob OID'
 normalized=blob.replace(b'\r\n',b'\n')
 assert hashlib.sha256(normalized).hexdigest()==V027_LENS_SHA256,'Changed fixed v027 Lens normalized identity'
 return normalized.decode('utf-8'),dict(pinnedCommit=V027_LENS_COMMIT,gitBlobOid=V027_LENS_BLOB,
  path=(V027_LENS_ARCHIVE/'Lens.kt').as_posix(),previousPath=V027_LENS_PATH,
  sha256Bytes=V027_LENS_SHA256,sha256LF=V027_LENS_SHA256,bytes=V027_LENS_BYTES,
  mode='explicit-whole-v027-lens-slice-not-overall-canonical-advance')

def lens_preflight_visibility_transforms():
 # Render and capability preflight compile the SAME two original shader values.
 # No shader algorithm/string is copied, edited or independently maintained.
 return [('private const val '+name+' =','internal const val '+name+' =') for name in LENS_PREFLIGHT_CONSTANTS]

def generate(repo: Path, output: Path, standalone: bool = False):
 REPO=Path(repo);HERE=Path(output)
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
  identity=None
  if path==V027_LENS_PATH:source,identity=fixed_v027_lens(REPO)
  else:
   source=read(_desktop_canonical_source(REPO, path));original=subprocess.check_output(['git','show',COMMIT+':'+path],cwd=REPO).decode().replace('\r\n','\n');assert source==original,path
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
  write(output,'// OriginalSource: '+path+'\n// OriginalSHA256: '+sha(source)+'\n'+('// OriginalCommit: '+identity['pinnedCommit']+'\n' if identity else '')+text)
  record=dict(path=path,sha256LF=sha(source),output=str(output.relative_to(HERE)),mode='direct' if names is None else 'selected',declarations=[{k:v for k,v in x.items() if k!='body'} for x in selected],adaptations=adaptations)
  if identity:record['sourceIdentity']=identity
  records.append(record)
 inv={'sources': [{'path': 'app/src/main/java/com/android/purebilibili/core/ui/components/AppLiquidAwareTabRow.kt'}, {'path': 'app/src/main/java/com/android/purebilibili/core/ui/components/TabSelectionScroll.kt'}, {'path': 'app/src/main/java/com/android/purebilibili/core/ui/components/LiquidDockViewport.kt'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/home/components/BottomBarLiquidSegmentedControl.kt'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/home/components/BottomBarFloatingSegmentedControl.kt'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/home/components/FloatingBottomBar.kt'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/home/components/FloatingBottomBarGeometry.kt'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/home/components/BottomBarMatchedLiquidChrome.kt'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/home/components/FloatingDockChrome.kt'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/home/components/LiquidGlassTuning.kt'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/home/components/LiquidGlassShader.kt'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/home/components/LiquidGlassSelectionContentPolicy.kt'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/home/components/LiquidGlassAdaptiveReadability.kt'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/home/components/miuix/InteractiveHighlight.kt'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/home/components/miuix/InteractiveHighlightPalette.kt'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/home/components/miuix/InteractiveHighlightMotionSpec.kt'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/home/components/miuix/DragGestureInspector.kt'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/home/components/miuix/DampedDragAnimation.kt'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/home/components/liquid/Lens.kt'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/home/components/liquid/Vibrancy.kt'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/home/components/liquid/InnerShadow.kt'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/home/components/liquid/CombinedBackdrop.kt'}]}
 for row in inv['sources']:
  p=row['path'];t=[]
  if p.endswith('LiquidGlassAdaptiveReadability.kt'):continue
  s=fixed_v027_lens(REPO)[0] if p==V027_LENS_PATH else read(_desktop_canonical_source(REPO, p))
  if p==V027_LENS_PATH:t+=lens_preflight_visibility_transforms()
  if p.endswith('AppLiquidAwareTabRow.kt'):
   t += [('import androidx.compose.ui.Modifier','import androidx.compose.ui.Modifier\nimport com.bilipai.desktop.ui.excludeFromLiquidBackground'),('        modifier = modifier,\n        enabled = enabled,','        modifier = modifier.excludeFromLiquidBackground(),\n        enabled = enabled,')]
  selected_names=None;selected_imports=''
  if p.endswith('BottomBarMatchedLiquidChrome.kt'):
   selected_names=[]
   for n,_ in declarations(s):
    selected_names.append(n)
    if n=='BottomBarMatchedReusableLiquidDock':break
   selected_names.append('BottomBarMatchedDockVisibility')
   selected_names.append('BottomBarMatchedLiquidIndicator')
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
 imports='\nimport androidx.compose.runtime.*\nimport androidx.compose.foundation.background\nimport androidx.compose.foundation.shape.RoundedCornerShape\nimport androidx.compose.ui.Modifier\nimport androidx.compose.ui.composed\nimport androidx.compose.ui.draw.clip\nimport androidx.compose.ui.draw.dropShadow\nimport androidx.compose.ui.graphics.Color\nimport androidx.compose.ui.graphics.luminance\nimport androidx.compose.ui.graphics.shadow.Shadow as ComposeShadow\nimport androidx.compose.ui.unit.*\nimport androidx.compose.ui.util.lerp\nimport com.android.purebilibili.core.store.BottomBarLiquidGlassPreset\nimport com.android.purebilibili.core.ui.*\nimport com.android.purebilibili.core.ui.adaptive.MotionTier\nimport com.android.purebilibili.core.ui.blur.*\nimport com.android.purebilibili.feature.home.HomeVisualPalette\nimport com.android.purebilibili.feature.home.components.liquid.InnerShadow as MiuixInnerShadow\nimport com.android.purebilibili.feature.home.components.liquid.innerShadow as miuixInnerShadow\nimport com.android.purebilibili.feature.home.components.liquid.lens as miuixLens\nimport com.android.purebilibili.feature.home.components.liquid.vibrancy as miuixVibrancy\nimport com.bilipai.desktop.ui.desktopDetailRenderEffectsSupported\nimport dev.chrisbanes.haze.HazeState\nimport top.yukonga.miuix.kmp.blur.Backdrop as MiuixBackdrop\nimport top.yukonga.miuix.kmp.blur.blur as miuixBlur\nimport top.yukonga.miuix.kmp.blur.drawBackdrop as miuixDrawBackdrop\nimport top.yukonga.miuix.kmp.blur.highlight.*\nimport top.yukonga.miuix.kmp.blur.highlight.Highlight as MiuixHighlight\n\n'
 imports+='\nimport kotlin.math.abs\n'
 emit(HOME+'BottomBar.kt',['iosIndicatorSpecular','AndroidNativeBottomBarTuning','resolveAndroidNativeBottomBarTuning','resolveAndroidNativeBottomBarContainerColor','resolveAndroidNativeFloatingBottomBarContainerColor','resolveAndroidNativeBottomBarGlassEnabled','shouldUseAndroidNativeFloatingHazeBlur','shouldRenderBottomBarLiquidGlassEffects','biliPaiMiuixFloatingDockSurface','resolveBiliPaiBottomBarContainerColor','resolveBottomBarDarkTheme','BOTTOM_BAR_INDICATOR_DRAG_SCALE_TARGET','resolveBottomBarCaptureSafeInsetDp','resolveBottomBarSurfaceColor','resolveBiliPaiBottomBarShellColor','BottomBarItemMotionVisual','resolveBottomBarItemCoverage','resolveBottomBarItemMotionScale','resolveBottomBarItemMotionVisual'],imports=imports,transforms=[('private val iosIndicatorSpecular','internal val desktopOriginalBottomBarIosIndicatorSpecular'),('rememberBiliPaiGravityHighlight(iosIndicatorSpecular, extraDegrees = -45f)','rememberBiliPaiGravityHighlight(desktopOriginalBottomBarIosIndicatorSpecular, extraDegrees = -45f)'),('    sdkInt: Int = Build.VERSION.SDK_INT\n): Boolean = liquidGlassEnabled && shouldAllowHomeChromeLiquidGlass(sdkInt)','): Boolean = liquidGlassEnabled && desktopDetailRenderEffectsSupported()'),('    sdkInt: Int = Build.VERSION.SDK_INT\n): Boolean = blurEnabled &&\n    !glassEnabled &&\n    hasHazeState &&\n    shouldAllowRenderEffectBackedHazeEffect(sdkInt)','): Boolean = blurEnabled &&\n    !glassEnabled &&\n    hasHazeState &&\n    desktopDetailRenderEffectsSupported()')])
 # Full original adaptive policy/state/UI kept; only Android capture/owner types map.
 adaptive=read(_desktop_canonical_source(REPO, HOME + 'LiquidGlassAdaptiveReadability.kt'))
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
 write(HERE/'source-inventory.json',json.dumps(dict(originalCommit=COMMIT,sourceCount=len(records),sources=records,sourceOverrides=[row['sourceIdentity'] for row in records if 'sourceIdentity' in row],explicitActualReuse=['resolveSharedBottomBarCapsuleShape','BILIPAI_PROGRESSIVE_TOP_BLUR_START_FRACTION','desktopDetailRenderEffectsSupported','DesktopDynamicWindowConfiguration','AppNativeTabRow','AppNativeSegmentedControl','AppSegmentOption','resolveReadableNativeTabMinWidth'],noNewHomeSettings=True,noNewSettingsManager=True,noNewStore=True,noNewClient=True),ensure_ascii=False,indent=2)+'\n')
 return sorted(safe(HERE/'generated').rglob('*.kt'))
if __name__=='__main__':
 import argparse
 parser=argparse.ArgumentParser();parser.add_argument('--repo',required=True);parser.add_argument('--output',required=True);parser.add_argument('--standalone',action='store_true');args=parser.parse_args()
 generate(Path(args.repo),Path(args.output),args.standalone)
