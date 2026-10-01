from pathlib import Path
import json,hashlib,subprocess,importlib.util,sys
HERE=Path(__file__).resolve().parent; MAIN=HERE.parents[2]
PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith(PREFIX) else PREFIX+s)
snap=MAIN/'desktop/.local/stable-product-snapshot-26'
cp=json.loads(safe(snap/'ordered-runtime-cp.json').read_text(encoding='utf-8'))
assert hashlib.sha256(safe(snap/'manifest.json').read_bytes()).hexdigest()=='ba49995574a32aabb30a07578dcfef7e4ae65ccf2882940ffb8d29272ea0695c'
assert hashlib.sha256(safe(snap/'ordered-runtime-cp.json').read_bytes()).hexdigest()=='8d2f4b6242c30ef4edae74ef4c33ce876a6b1fa6508cd6eadccd321b9451ce94'
for r in cp: assert hashlib.sha256(safe(r['path']).read_bytes()).hexdigest()==r['sha256Bytes']
ccspec=importlib.util.spec_from_file_location('cc',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');cc=importlib.util.module_from_spec(ccspec);ccspec.loader.exec_module(cc)
n=1+len(list(HERE.glob('fixture-??')));run=HERE/f'fixture-{n:02}';run.mkdir()
vm=sorted(HERE.glob('classes-vm-??'))[-1];ui=MAIN/'desktop/.local/stable-home-page-parity/classes-full-07'
runtime=[str(vm),str(ui)]+[r['path'] for r in cp]
source=HERE/'fixtures/HomeVmFixture.kt'
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows','-Xfriend-paths='+','.join([str(vm),str(ui),str(snap/'main-kotlin.jar')]),'-cp',';'.join(runtime),'-d',str(run/'classes'),str(source)]
argfile=run/'compile.args';safe(argfile).write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args),encoding='utf-8')
result=subprocess.run([str(cc.JAVA),'-Dfile.encoding=UTF-8','-Xmx2g','-cp',';'.join(map(str,cc.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(argfile)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=120)
safe(run/'compile.log').write_text(result.stdout+result.stderr,encoding='utf-8');print((result.stdout+result.stderr).encode('ascii','backslashreplace').decode())
if result.returncode:sys.exit(result.returncode)
result=subprocess.run([str(cc.JAVA),'-Dfile.encoding=UTF-8','-cp',';'.join([str(run/'classes')]+runtime),'com.bilipai.desktop.ui.HomeVmFixtureKt',str(run/'task-store')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=40)
safe(run/'run.log').write_text(result.stdout+result.stderr,encoding='utf-8');print((result.stdout+result.stderr).encode('ascii','backslashreplace').decode())
binding={'actualMainRuntime':False,'productBaseManifest':str(snap/'manifest.json'),'productBaseManifestSha':hashlib.sha256(safe(snap/'manifest.json').read_bytes()).hexdigest(),'explicitPreparedVmClasses':str(vm),'explicitPreparedHomeUiClasses':str(ui),'fixtureSourceSha':hashlib.sha256(safe(source).read_bytes()).hexdigest(),'classpath':runtime,'exitCode':result.returncode}
safe(run/'sourcebinding.json').write_text(json.dumps(binding,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
sys.exit(result.returncode)
