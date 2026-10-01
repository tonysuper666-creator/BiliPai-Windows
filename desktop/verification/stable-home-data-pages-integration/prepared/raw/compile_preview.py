from pathlib import Path
import json,hashlib,subprocess,importlib.util,sys
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith(PREFIX) else PREFIX+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
snap=MAIN/'desktop/.local/stable-product-snapshot-26'
assert sha(snap/'manifest.json')=='ba49995574a32aabb30a07578dcfef7e4ae65ccf2882940ffb8d29272ea0695c'
cp=json.loads(safe(snap/'ordered-runtime-cp.json').read_text(encoding='utf-8'))
for row in cp:assert sha(row['path'])==row['sha256Bytes']
ui=MAIN/'desktop/.local/stable-home-page-parity/classes-full-07'
source=HERE/'prepared/preview-delta/com/android/purebilibili/feature/home/DesktopOriginalHomeScreen.kt'
inputs=[{'path':str(source),'sha256Bytes':sha(source)}]
run=HERE/'preview-compile-01';run.mkdir()
spec=importlib.util.spec_from_file_location('cc',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');cc=importlib.util.module_from_spec(spec);spec.loader.exec_module(cc)
runtime=[str(ui)]+[row['path'] for row in cp]
args=['-Xplugin='+str(cc.PLUGIN),'-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows','-Xfriend-paths='+','.join([str(ui),str(snap/'main-kotlin.jar')]),'-cp',';'.join(runtime),'-d',str(run/'classes'),str(source)]
safe(run/'compile.args').write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args),encoding='utf-8')
r=subprocess.run([str(cc.JAVA),'-Dfile.encoding=UTF-8','-Xmx2g','-cp',';'.join(map(str,cc.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(run/'compile.args')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=120)
safe(run/'compile.log').write_text(r.stdout+r.stderr,encoding='utf-8')
for row in inputs:assert sha(row['path'])==row['sha256Bytes']
safe(run/'sourcebinding.json').write_text(json.dumps({'actualMainAcceptance':False,'explicitPreparedHomeUi':str(ui),'snapshotManifest':str(snap/'manifest.json'),'sources':inputs,'exitCode':r.returncode,'nativePreviewOrMountedHomeAcceptance':False},indent=2)+'\n',encoding='utf-8')
print((r.stdout+r.stderr).encode('ascii','backslashreplace').decode());sys.exit(r.returncode)
