"""Complete original modal effect and owner registry on actual Windows content layer."""
from pathlib import Path
import hashlib,json
COMMIT='8b47769d22c0d5bc88ae5248cee4aa04ff10d62c'
MANIFEST_SHA='f021dfe0928269619b8553d41979768e74b9dafee56c5854cf3e5135c0e5dea7'
PINS=[{'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/AppWindowBlurBehind.kt', 'filename': 'AppWindowBlurBehind.kt', 'bytes': 6130, 'sha256Bytes': '7c8a0875951b3c27338ca4ea48327ac109ce6c4f2df003c9316fe77f6572c5f5', 'gitBlob': '0eecb8f80b279062454a715a5254716bdc31ee19'}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/ui/AppSheetComponents.kt', 'filename': 'AppSheetComponents.kt', 'bytes': 17722, 'sha256Bytes': 'a8610387b35385aeb61f67959ea4c32d91721dba9b34d7c541047148df3bac92', 'gitBlob': '42624489b12087f8f9106246e2515153225f1868'}]
def fixed_original(repo,filename):
 root=Path(repo)/'desktop/upstream-slices/v037-window-modal-blur'
 data=(root/'manifest.json').read_bytes();assert hashlib.sha256(data).hexdigest()==MANIFEST_SHA
 manifest=json.loads(data);assert manifest['fixedUpstreamCommit']==COMMIT and manifest['files']==PINS
 rows=[row for row in PINS if row['filename']==filename];assert len(rows)==1,filename
 row=rows[0];raw=(root/filename).read_bytes()
 assert len(raw)==row['bytes'] and hashlib.sha256(raw).hexdigest()==row['sha256Bytes'],'Fixed modal bytes changed: '+filename
 assert hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()==row['gitBlob']
 return raw.decode('utf-8'),dict(row,fixedCommit=COMMIT)

def adapt_sheet(original):
 """Retain the whole original modal on Windows coordinates and actual popup hosts."""
 text=original;edits=[]
 def change(before,after):
  nonlocal text
  assert text.count(before)==1,(before,text.count(before))
  text=text.replace(before,after,1);edits.append({'before':before,'after':after})
 change('import androidx.compose.ui.platform.LocalConfiguration','import com.android.purebilibili.core.util.LocalWindowSizeClass\nimport com.bilipai.desktop.ui.DesktopCommentDialogNavigationHost')
 for line in ['import androidx.compose.ui.platform.LocalView\n','import androidx.navigationevent.findViewTreeNavigationEventDispatcherOwner\n']:
  change(line,'')
 begin=text.index('    val owner = LocalView.current.findViewTreeNavigationEventDispatcherOwner()');end=text.index('\n}\n',begin)
 change(text[begin:end],'''    DesktopCommentDialogNavigationHost {
        val backState = rememberNavigationEventState(NavigationEventInfo.None)
        NavigationBackHandler(state=backState,isBackEnabled=dismissOnBackPress,onBackCompleted=onDismissRequest)
        content()
    }''')
 change('val configuration = LocalConfiguration.current','val configuration = LocalWindowSizeClass.current')
 # Desktop Windows has no Android physical hinge. Keep the same centered host.
 change('    val hingeSafeRegions = LocalHingeSafeOverlayRegions.current.sheet\n','')
 change('if (miuix && hingeSafeRegions == null)','if (miuix)')
 change('if (hingeSafeRegions != null || centered)','if (centered)')
 change('''                if (hingeSafeRegions != null) {
                    HingeSafeOverlayHost(
                        regionProvider = hingeSafeRegions,
                        modifier = Modifier.fillMaxSize().imePadding(),
                        onDismissRequest = onDismissRequest,
                    ) { surface() }
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { surface() }
                }''','''                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { surface() }''')
 for before,after in [('configuration.screenWidthDp','configuration.widthDp.value.toInt()'),('configuration.screenHeightDp','configuration.heightDp.value')]:
  count=text.count(before);assert count>0
  change(text, text.replace(before,after))
 change('                decorFitsSystemWindows = false,\n','')
 change('    val view = LocalView.current\n','')
 change('    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }','    val coordinates = remember { mutableStateOf<LayoutCoordinates?>(null, androidx.compose.runtime.neverEqualPolicy()) }')
 change('import androidx.compose.ui.layout.onGloballyPositioned\n','import androidx.compose.ui.layout.onGloballyPositioned\nimport androidx.compose.ui.layout.findRootCoordinates\n')
 change(text,text.replace('coordinates[0]','coordinates.value'))
 change('view.rootView.height','layout.findRootCoordinates().size.height')
 from v029_comment_search import whole_proof
 proof=whole_proof(original,text)
 proof.update(wholeOriginalSheetRetained=True,originalAndroidHingeExcludedForWindows=True,actualWindowCoordinates=True,snapshotProgressFollowsActualPositionCallbacks=True,idleFrameLoopAdded=False,sourceQualified=False,pixelsValidated=False)
 return text,proof

def emit(output,outputs):
 from v029_comment_search import whole_proof
 import importlib.util
 original,row=fixed_original(Path(__file__).resolve().parents[2],'AppWindowBlurBehind.kt')
 def load(name,path):
  spec=importlib.util.spec_from_file_location(name,path);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module
 tools=Path(__file__).resolve().parent
 parser=load('modal_tokens',tools/'sync-upstream.py')
 selector=load('modal_declarations',tools/'extract-appearance-platform.py')
 function=selector.declarations(parser,original,['ModalWindowBlurBehindEffect'])
 registry=original[original.index('private object ModalContentBlur {'):]
 replacement='''@Composable
fun ModalWindowBlurBehindEffect(
    enabled: Boolean = true,
    radius: Dp = 24.dp,
    progress: () -> Float = { 1f },
) {
    val target = LocalDesktopOriginalModalContentBlurTarget.current
    val density = LocalDensity.current
    val latestProgress by rememberUpdatedState(progress)
    DisposableEffect(target, enabled, radius, density) {
        if (!enabled || target == null) return@DisposableEffect onDispose { }
        val owner = Any()
        val maxRadius = with(density) { radius.toPx() }
        fun updateBlur() {
            val fraction = latestProgress().let { if (it.isFinite()) it.coerceIn(0f, 1f) else 0f }
            ModalContentBlur.acquire(target, owner, maxRadius * fraction)
        }
        updateBlur()
        val observation = target.observeProgress({ latestProgress() }, ::updateBlur)
        onDispose {
            observation.close()
            ModalContentBlur.release(target, owner)
        }
    }
}
'''
 selected=function+'\n\n'+registry
 adapted=registry.replace('private object ModalContentBlur {','internal object ModalContentBlur {').replace('WeakHashMap<View,','WeakHashMap<DesktopOriginalModalContentBlurTarget,').replace('view: View','view: DesktopOriginalModalContentBlurTarget')
 gate='        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return\n';assert adapted.count(gate)==1;adapted=adapted.replace(gate,'',1)
 before='''        view.setRenderEffect(
            if (radius > 0f) RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
            else null
        )''';assert adapted.count(before)==1;adapted=adapted.replace(before,'        view.setBlurRadiusPx(radius)',1)
 text='''package com.android.purebilibili.core.ui
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bilipai.desktop.ui.LocalDesktopOriginalModalContentBlurTarget
import com.bilipai.desktop.ui.DesktopOriginalModalContentBlurTarget
'''+replacement+'\n'+adapted
 rel='com/android/purebilibili/core/ui/AppWindowBlurBehind.kt'
 target=Path(output)/rel;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(text.encode())
 proof=whole_proof(selected,replacement+'\n\n'+adapted)
 proof.update(originalPath=row['path'],fixedCommit=COMMIT,originalOwnerMaxRadiusRegistryRetained=True,parentContentTargetRequired=True,androidWindowFlagsUsed=False,sourceQualified=False,pixelsValidated=False)
 p=Path(output)/'v037-window-modal-blur-platform-proof.json';p.write_bytes((json.dumps(proof,ensure_ascii=False,indent=2)+'\n').encode())
 assert not any(x['path']==rel for x in outputs)
 outputs.append({'path':rel,'origin':row['path'],'fixedCommit':COMMIT,'generated':True,'mode':'complete-original-modal-effect-and-owner-registry-Windows-render-target','sha256LF':hashlib.sha256(text.encode()).hexdigest()})
