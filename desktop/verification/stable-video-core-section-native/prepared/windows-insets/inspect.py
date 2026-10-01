"""Read-only installed JDK/Skiko and closed actual53 evidence. No product/UI calls."""
from pathlib import Path
import hashlib,json,subprocess,zipfile
LANE=Path(__file__).resolve().parent;MAIN=LANE.parents[2];WORK=MAIN.parent
def safe(p):
    s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92)
    return Path(s if s.startswith(prefix) else prefix+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def row(p):return {'path':str(p),'sha256Bytes':sha(p),'size':safe(p).stat().st_size}
def put(name,b):
    p=LANE/name;safe(p).parent.mkdir(parents=True,exist_ok=True);safe(p).write_bytes(b);return row(p)
def js(name,v):return put(name,(json.dumps(v,ensure_ascii=False,indent=2)+'\n').encode())
snapshot=MAIN/'desktop/.local/stable-product-snapshot-53'
assert sha(snapshot/'manifest.json')=='d7a05a5ffe7457e7b25ad1d644d111ec6b58465fcd71db14ec0da22930361d2c'
assert sha(snapshot/'ordered-runtime-cp.json')=='b1bc18718b6f4564bfa0c7f37658de2e01c3079d57bef20f7dc482061a98d97d'
cp=json.loads(safe(snapshot/'ordered-runtime-cp.json').read_text(encoding='utf-8'));assert len(cp)==101
for v in cp:assert sha(v['path'])==v['sha256Bytes']
jdk=WORK/'toolchain/jdk/jdk-21.0.12.1+1';src=jdk/'lib/src.zip'
files=['java.desktop/java/awt/Component.java','java.desktop/java/awt/Container.java','java.desktop/java/awt/GraphicsDevice.java',
       'java.desktop/java/awt/Frame.java','java.desktop/sun/awt/windows/WWindowPeer.java',
       'java.desktop/sun/awt/windows/WFramePeer.java','java.desktop/sun/awt/windows/WPanelPeer.java',
       'java.desktop/sun/awt/windows/WComponentPeer.java','java.desktop/sun/awt/Win32GraphicsDevice.java',
       'java.desktop/javax/swing/JRootPane.java','java.desktop/javax/swing/JFrame.java',
       'java.desktop/sun/java2d/d3d/D3DGraphicsDevice.java']
outputs=[]
with zipfile.ZipFile(safe(src)) as z:
    for name in files:outputs.append(put('jdk/'+name,z.read(name)))
skiko=[v for v in cp if Path(v['path']).name=='skiko-awt-0.150.1.jar'];assert len(skiko)==1
compose=[v for v in cp if Path(v['path']).name.startswith('ui-desktop-')];assert len(compose)==1
for cls in ['org.jetbrains.skiko.FullscreenAdapter','org.jetbrains.skiko.PlatformOperationsKt$platformOperations$2$2','org.jetbrains.skiko.HardwareLayer',
            'androidx.compose.ui.awt.ComposeWindow','androidx.compose.ui.awt.ComposeWindowPanel',
            'com.bilipai.desktop.ui.DesktopWindowsFullscreenControl']:
    result=subprocess.run([str(jdk/'bin/javap.exe'),'-p','-c','-l','-classpath',';'.join(v['path'] for v in [cp[1],skiko[0],compose[0]]),cls],capture_output=True,check=True)
    outputs.append(put('bytecode/'+cls+'.txt',result.stdout+result.stderr))
controller=WORK/'BiliPai-v023/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopWindowsFullscreenControl.kt'
outputs.append(put('observed-source/DesktopWindowsFullscreenControl.kt',safe(controller).read_bytes()))
geometry=MAIN/'desktop/.local/stable-offline-native-integration-proof51/runs/actual53-01-normal/fixture-owned-runtime/owned-geometries.json'
raw=json.loads(safe(geometry).read_text(encoding='utf-8'));selected=[]
for v in raw:
    if v['observation'] in ['Clean first full RootHost entry after actual native output','After Root sky step 1: 退出全屏','After Root sky step 2: 全屏','Before Root sky step 3: 退出全屏','After Root sky step 3: 退出全屏','Actual same Canvas returned from PiP','Programmatic full leaf disposal']:
        selected.append({k:v.get(k) for k in ['observation','elapsedMs','main','anchor','nativeVideoCanvas','foregroundWindows']})
js('geometry-selected.json',selected)
js('source-inputs.json',{'scope':'Readonly post-closed53 JDK/Skiko/source analysis; no UI/actor execution',
    'actualSnapshot':53,'manifest':row(snapshot/'manifest.json'),'orderedCp':row(snapshot/'ordered-runtime-cp.json'),
    'jdkJava':row(jdk/'bin/java.exe'),'jdkAwtNative':row(jdk/'bin/awt.dll'),'jdkRelease':row(jdk/'release'),'jdkSources':row(src),
    'skikoJar':row(skiko[0]['path']),'composeUiJar':row(compose[0]['path']),
    'productJar':row(cp[1]['path']),'controllerObservedSource':row(controller),
    'closedGeometry':row(geometry),'rawArtifacts':outputs,
    'all101RuntimePinsVerified':True,'candidateMutation':False,'Gradle':False,'nativeWindowRun':False})
print(json.dumps({'sources':len(files),'bytecodeClasses':6,'selectedGeometryRecords':len(selected)},indent=2))
