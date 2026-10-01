from pathlib import Path
import hashlib,json,re,zipfile
HERE=Path(__file__).resolve().parent
prefix=(HERE/'prepare.py').read_text(encoding='utf-8').split("rows=json.loads",1)[0]
exec(prefix)
rows=json.loads(read(HERE/'producer-inventory.json'))
def emit(path,names=None,filename=None,replacements=(),imports=None,reason='exact original declarations'):
 s=read(REPO/path);pkg=re.search(r'(?m)^package (\S+)',s)[1]
 body=s if names is None else 'package '+pkg+'\n'+(imports if imports is not None else '\n'.join(x for x in s.splitlines() if x.startswith('import ')))+'\n\n'+'\n\n'.join(d for n,d in declarations(s) if n in names)+'\n'
 changes=[]
 for before,after in replacements:
  assert before in body,(path,before[:100]);body=body.replace(before,after);changes.append(dict(before=before,after=after))
 code=re.sub(r'(?m)^import[^\n]*\n','',body);words=set(re.findall(r'\b\w+\b',code))
 def prune(m):
  part=m[0].split('//',1)[0].strip().split();simple=part[-1] if 'as' in part else part[1].split('.')[-1]
  return m[0] if (simple in ['*','getValue','setValue'] or simple in words) and not part[1].startswith('com.airbnb.lottie') else ''
 body=re.sub(r'(?m)^import[^\n]*\n',prune,body)
 out=HERE/'prepared/generated'/pkg.replace('.','/')/(filename or Path(path).name)
 write(out,'// Original source '+path+'\n// LF SHA256 '+hashlib.sha256(s.encode()).hexdigest()+'\n'+body)
 row=dict(path=path,sha256LF=hashlib.sha256(s.encode()).hexdigest(),mode='policy-extract',selectedDeclarations=names,generated=out.relative_to(HERE).as_posix(),changes=changes,reason=reason)
 rows.append(row);return row

# Complete retained depth renderer remains the sole existing home-full-card source owner.
# This prepared file replaces that producer's partial output; not a second producer.
path='app/src/main/java/com/android/purebilibili/core/ui/transition/VideoCardTransitionBackgroundPolicy.kt'
s=read(REPO/path)
names=[n for n,_ in declarations(s) if n!='resolveDeviceDisplayCornerRadiusPx']
emit(path,names,replacements=[
 ('import android.os.Build\n',''),('import androidx.compose.ui.platform.LocalView\n',''),
 ('Build.VERSION.SDK_INT','com.android.purebilibili.feature.home.components.cards.DesktopHomeCardPlatform.androidRenderEffectApiLevel'),
 ('Build.VERSION_CODES.S','31'),
 ('    val view = LocalView.current\n','    val windowPlatform = com.bilipai.desktop.ui.LocalDesktopHomePlatform.current\n'),
 ('deviceCornerRadiusPx = resolveDeviceDisplayCornerRadiusPx(view.rootWindowInsets)','deviceCornerRadiusPx = windowPlatform.deviceCornerRadiusPx'),
 ],reason='existing sole home-full-card output extension: complete original retained GraphicsLayer renderer; only native WindowInsets corner lookup binds required Root window geometry')

path='app/src/main/java/com/android/purebilibili/core/ui/blur/RecoverableVisualEffects.kt'
names=['recoverableBlurGates','recoverableBlurEnabled','hazeSourceCompat','shouldEnableRecoverableHeavyVisualEffects','rememberRecoverableHazeState']
emit(path,names,replacements=[
 ('import android.os.Build\n',''),
 ('import com.android.purebilibili.core.lifecycle.BackgroundManager','import com.bilipai.desktop.ui.LocalDesktopHomePlatform\nimport com.bilipai.desktop.ui.DesktopHomeWindowBackgroundPort'),
 ('    if (!shouldAllowRenderEffectBackedHazeEffect(Build.VERSION.SDK_INT)) return this\n',''),
 ('    sdkInt: Int = Build.VERSION.SDK_INT\n','    platform: com.bilipai.desktop.ui.DesktopHomePlatform = LocalDesktopHomePlatform.current\n'),
 ('    var isAppInBackground by remember { mutableStateOf(BackgroundManager.isInBackground) }','    var isAppInBackground by remember(platform.background) { mutableStateOf(platform.background.isInBackground) }'),
 ('val shouldRecreateState = shouldRecreateRecoverableHazeState(sdkInt)','val shouldRecreateState = platform.recreateHazeOnResume'),
 ('BackgroundManager.BackgroundStateListener','DesktopHomeWindowBackgroundPort.Listener'),
 ('BackgroundManager.addListener(listener)','platform.background.addListener(listener)'),
 ('BackgroundManager.removeListener(listener)','platform.background.removeListener(listener)'),
 ('fun rememberRecoverableHazeState(','internal fun rememberRecoverableHazeState('),
 ],reason='existing sole home-full-card gate/map reused; original recreation/blur state algorithm and WeakHashMap retained; native visibility listeners replace Android BackgroundManager')
emit('app/src/main/java/com/android/purebilibili/core/ui/transition/VideoCardTransitionLiveBlurHitchLogger.kt',replacements=[
 ('import com.android.purebilibili.BuildConfig\n',''),
 ('if (!BuildConfig.DEBUG) return','if (!com.bilipai.desktop.ui.LocalDesktopHomePlatform.current.debugFrameMetricsEnabled) return')])
for path in ['design-system/src/main/java/com/android/purebilibili/core/ui/AdaptivePullToRefreshBox.kt','design-system/src/main/java/com/android/purebilibili/core/ui/ComfortablePullToRefreshBox.kt']:
 emit(path)
emit('app/src/main/java/com/android/purebilibili/core/ui/wallpaper/WallpaperMedia.kt',['isVideoWallpaper'],filename='DesktopHomeWallpaperType.kt')
emit('app/src/main/java/com/android/purebilibili/core/util/ModifierExt.kt',['iOSTapEffect'],filename='DesktopHomeTapEffect.kt',imports='\n'.join(x for x in read(REPO/'app/src/main/java/com/android/purebilibili/core/util/ModifierExt.kt').splitlines() if x.startswith('import ') and not x.startswith('import android.')))
# Required Windows system preference is the existing real SPI consumer, never an Android setting shim.
for r in rows:
 if 'generated' not in r:continue
 p=HERE/r['generated'];text=read(p)
 text=text.replace('import com.android.purebilibili.core.ui.motion.rememberSystemReduceMotion','import com.bilipai.desktop.ui.rememberDesktopDynamicReduceMotion as rememberSystemReduceMotion')
 write(p,text)

# The original error UI keeps its visual, interactions and URL. Only platform Lottie decoding is a required Root consumer.
emit('app/src/main/java/com/android/purebilibili/core/ui/LottieComponents.kt',['ErrorState'],filename='DesktopHomeOriginalErrorState.kt',replacements=[
 ('LottieAnimation(\n            url = LottieUrls.ERROR,\n            size = 120.dp,\n            iterations = 1\n        )','com.bilipai.desktop.ui.LocalDesktopHomeErrorAnimation.current(LottieUrls.ERROR,120.dp,1)')])
# LottieUrls is the actual BGM producer's sole existing object; reuse it.

# Temporary, reference-only missing shared32 declarations. Root must extend its sole producer.
row=emit('app/src/main/java/com/android/purebilibili/feature/home/components/BottomBarMatchedLiquidChrome.kt',['BottomBarMatchedDockVisibility'],filename='DesktopHomeMatchedDockVisibilityReference.kt')
row['install']=False;row['reason']='reference-only selected2; existing shared32 sole producer must append these declarations, never install a second facade'

emit('design-system/src/main/java/com/android/purebilibili/core/ui/AdaptivePullToRefreshPolicy.kt',['resolveMiuixPullToRefreshTexts'],filename='DesktopHomeOriginalPullRefreshText.kt')
emit('design-system/src/main/java/com/android/purebilibili/core/ui/PullRefreshUiPolicy.kt',['resolvePullRefreshThresholdDp'],filename='DesktopHomeOriginalPullThreshold.kt')
emit('app/src/main/java/com/android/purebilibili/core/ui/transition/VideoCardTransitionHostDepthLayer.kt',['shouldInvalidateSnapshotOnSourceDispose'],filename='DesktopHomeDepthOwnershipPolicy.kt')
emit('app/src/main/java/com/android/purebilibili/core/ui/transition/VideoCardTransitionDiagnostics.kt',replacements=[
 ('import android.os.Trace\n',''),('import android.os.Build\n',''),
 ('Log.isLoggable(TAG, Log.DEBUG)','java.util.logging.Logger.getLogger(TAG).isLoggable(java.util.logging.Level.FINE)'),
 ('if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {\n            Trace.setCounter(name, value)\n        }','java.util.logging.Logger.getLogger(TAG).fine("$name=$value")'),
 ],reason='original counter/phase algorithm preserved; Android Perfetto emission maps only to real JVM local FINE logging, no telemetry client/upload')
arc=MAIN/'desktop/.local/source9-appearance/original/5157b503e86e2bfc2db61db00fff5df41326394a.zip'
assert hashlib.sha256(safe(arc).read_bytes()).hexdigest()=='e6b9f53eff9cfce98580d70a1a8e2ba447038af5f46e8392c96ec843ce0b9e6b'
with zipfile.ZipFile(safe(arc)) as z:
 path='miuix-icons/src/commonMain/kotlin/top/yukonga/miuix/kmp/icon/extended/Messages.kt'
 matches=[n for n in z.namelist() if n.endswith('/'+path)];assert len(matches)==1
 data=z.read(matches[0]);out=HERE/'prepared/fork-icons/miuix-icons/src/commonMain/kotlin/icon/extended/Messages.kt'
 safe(out).parent.mkdir(parents=True,exist_ok=True);safe(out).write_bytes(data)
 write(HERE/'miuix-messages-source-evidence.json',json.dumps(dict(archive=str(arc),archiveSha256='e6b9f53eff9cfce98580d70a1a8e2ba447038af5f46e8392c96ec843ce0b9e6b',source=path,prepared=out.relative_to(HERE).as_posix(),sha256Bytes=hashlib.sha256(data).hexdigest(),soleFork=True),indent=2)+'\n')
write(HERE/'producer-inventory.json',json.dumps(rows,ensure_ascii=False,indent=2)+'\n')
print('total rows',len(rows))
