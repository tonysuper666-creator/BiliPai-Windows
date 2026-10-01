"""Read exact actual50 dependency bytecode/JDK sources. Compile only a headless lazy-load diagnostic."""
from pathlib import Path
import hashlib, json, subprocess, sys, zipfile
sys.dont_write_bytecode=True
sys.stdout.reconfigure(encoding='utf-8')
lane=Path(__file__).resolve().parent
main=lane.parents[3]
tools=main.parent/'toolchain'
jdk=tools/'jdk/jdk-21.0.12.1+1'
def safe(p):
    s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92)
    return Path(s if s.startswith(prefix) else prefix+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,v):safe(p).write_text(v if isinstance(v,str) else json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
snapshot=main/'desktop/.local/stable-product-snapshot-50'
assert sha(snapshot/'manifest.json')=='d9803560314da73036a848cf1be86c5877ff24b3993584379a5e9516165de4eb'
assert sha(snapshot/'ordered-runtime-cp.json')=='c4615263c430a5b75f86085499bcf3dae27239a005a59e258cdff172bbf75680'
cp=json.loads(safe(snapshot/'ordered-runtime-cp.json').read_text(encoding='utf-8'));assert len(cp)==97
for r in cp:assert sha(r['path'])==r['sha256Bytes']
skiko=next(r for r in cp if 'skiko-awt-0.150.1.jar' in r['path'])
classes=['org.jetbrains.skiko.PlatformOperationsKt$platformOperations$2$2','org.jetbrains.skiko.FullscreenAdapter','org.jetbrains.skiko.HardwareLayer']
for name in classes:
    result=subprocess.run([str(jdk/'bin/javap.exe'),'-private','-c','-l','-cp',skiko['path'],name],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=20)
    write(lane/(name.replace('.','_')+'.javap.txt'),result.stdout+result.stderr);assert result.returncode==0
src=jdk/'lib/src.zip'
paths=['java.desktop/sun/awt/Win32GraphicsDevice.java','java.desktop/java/awt/GraphicsDevice.java','java.desktop/java/awt/Component.java','java.desktop/sun/awt/windows/WComponentPeer.java','java.desktop/sun/awt/windows/WWindowPeer.java']
with zipfile.ZipFile(safe(src)) as z:
    for p in paths:write(lane/Path(p).name,z.read(p).decode('utf-8'))
compiled=subprocess.run([str(jdk/'bin/javac.exe'),'-d',str(lane/'classes'),str(lane/'WinRtLoadProbe.java')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=20)
write(lane/'compile.log',compiled.stdout+compiled.stderr);assert compiled.returncode==0
result=subprocess.run([str(jdk/'bin/java.exe'),'-Djava.awt.headless=true','-Dfile.encoding=UTF-8','-cp',str(lane/'classes')+';'+';'.join(r['path'] for r in cp),'WinRtLoadProbe'],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=30)
write(lane/'winrt-load.log',result.stdout+result.stderr)
for r in cp:assert sha(r['path'])==r['sha256Bytes']
write(lane/'inputs.json',{'diagnosticOnly':True,'actualSnapshot':50,'runtimeEntries':97,'productionOverrides':0,'headlessNoWindow':True,'UIInput':False,'sameActualWinRtLazyOnly':True,'skiko':skiko,'jdkSource':{'path':str(src),'sha256Bytes':sha(src)},'orderedCP':cp,'probeExit':result.returncode})
print(result.stdout+result.stderr)
