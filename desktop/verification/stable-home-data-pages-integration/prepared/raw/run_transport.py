from pathlib import Path
import json,hashlib,subprocess,importlib.util,sys
HERE=Path(__file__).resolve().parent; MAIN=HERE.parents[2]
PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith(PREFIX) else PREFIX+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
snap=MAIN/'desktop/.local/stable-product-snapshot-26'
cp=json.loads(safe(snap/'ordered-runtime-cp.json').read_text(encoding='utf-8'))
assert sha(snap/'manifest.json')=='ba49995574a32aabb30a07578dcfef7e4ae65ccf2882940ffb8d29272ea0695c'
assert sha(snap/'ordered-runtime-cp.json')=='8d2f4b6242c30ef4edae74ef4c33ce876a6b1fa6508cd6eadccd321b9451ce94'
for r in cp:assert sha(r['path'])==r['sha256Bytes']
spec=importlib.util.spec_from_file_location('cc',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');cc=importlib.util.module_from_spec(spec);spec.loader.exec_module(cc)
kind=sys.argv[1] if len(sys.argv)>1 else 'transport'
assert kind in ['transport','protocol','analytics']
run=HERE/f'{kind}-{1+len(list(HERE.glob(kind+"-??"))):02}';run.mkdir()
ports=sorted(HERE.glob('classes-ports-??'))[-1]
vm=MAIN/'desktop/.local/stable-home-viewmodel-parity/classes-vm-06'
ui=MAIN/'desktop/.local/stable-home-page-parity/classes-full-07'
runtime=[str(ports),str(vm),str(ui)]+[r['path'] for r in cp]
source=HERE/'fixtures'/({'transport':'TransportFixture.kt','protocol':'ProtocolFixture.kt','analytics':'AnalyticsFixture.kt'}[kind])
inputs=[{'path':str(p),'sha256Bytes':sha(p)} for root in [ports,vm,ui] for p in root.rglob('*.class')]
inputs.append({'path':str(source),'sha256Bytes':sha(source)})
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows','-Xfriend-paths='+','.join(runtime[:3]+[str(snap/'main-kotlin.jar')]),'-cp',';'.join(runtime),'-d',str(run/'classes'),str(source)]
safe(run/'compile.args').write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args),encoding='utf-8')
r=subprocess.run([str(cc.JAVA),'-Dfile.encoding=UTF-8','-Xmx2g','-cp',';'.join(map(str,cc.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(run/'compile.args')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=100)
safe(run/'compile.log').write_text(r.stdout+r.stderr,encoding='utf-8');print((r.stdout+r.stderr).encode('ascii','backslashreplace').decode())
if not r.returncode:
 entry={'transport':'com.bilipai.desktop.data.TransportFixtureKt','protocol':'com.bilipai.desktop.ui.ProtocolFixtureKt','analytics':'com.bilipai.desktop.ui.AnalyticsFixtureKt'}[kind]
 r=subprocess.run([str(cc.JAVA),'-Dfile.encoding=UTF-8','-Djava.net.useSystemProxies=false','-cp',';'.join([str(run/'classes')]+runtime),entry,str(run/'task-store')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=40)
 safe(run/'run.log').write_text(r.stdout+r.stderr,encoding='utf-8');print((r.stdout+r.stderr).encode('ascii','backslashreplace').decode())
for row in inputs:assert sha(row['path'])==row['sha256Bytes']
for row in cp:assert sha(row['path'])==row['sha256Bytes']
safe(run/'sourcebinding.json').write_text(json.dumps({'actualMainAcceptance':False,'explicitPreparedPorts':str(ports),'explicitFrozenPreparedVm':str(vm),'explicitFrozenPreparedUi':str(ui),'productBaseManifest':str(snap/'manifest.json'),'productBaseManifestSha':sha(snap/'manifest.json'),'sourceAndClasses':inputs,'actualCp':cp,'exitCode':r.returncode},indent=2)+'\n',encoding='utf-8')
sys.exit(r.returncode)
