from pathlib import Path
import json,importlib.util,subprocess,sys,hashlib
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2]
PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith(PREFIX) else PREFIX+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
snapshot=MAIN/'desktop/.local/stable-product-snapshot-33'
cp=json.loads(safe(snapshot/'ordered-runtime-cp.json').read_text())
for r in cp:assert sha(r['path'])==r['sha256Bytes']
vm=MAIN/'desktop/.local/stable-home-viewmodel-parity/classes-vm-06'
ports=MAIN/'desktop/.local/stable-home-request-ports-parity/classes-ports-05'
owner=sorted(HERE.glob('classes-owner-??'))[-1];fixture=HERE/'fixtures/RetainedHomeGateFixture.kt';run=HERE/f'gate-{len(list(HERE.glob("gate-??")))+1:02}'
safe(run).mkdir(exist_ok=True)
paths=[owner,ports,vm]+[Path(r['path']) for r in cp]
spec=importlib.util.spec_from_file_location('cc',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');cc=importlib.util.module_from_spec(spec);spec.loader.exec_module(cc)
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows',
 '-Xfriend-paths='+','.join(map(str,[owner,ports,vm,snapshot/'main-kotlin.jar'])),
 '-cp',';'.join(map(str,paths)),'-d',str(run/'classes'),str(fixture)]
argfile=run/'compile.args';safe(argfile).write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args),encoding='utf-8')
r=subprocess.run([str(cc.JAVA),'-Dfile.encoding=UTF-8','-Xmx1g','-cp',';'.join(map(str,cc.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(argfile)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=120)
safe(run/'compile.log').write_text(r.stdout+r.stderr,encoding='utf-8')
if r.returncode:print((r.stdout+r.stderr).encode('ascii','backslashreplace').decode());sys.exit(r.returncode)
r=subprocess.run([str(cc.JAVA),'-Dfile.encoding=UTF-8','-cp',str(run/'classes')+';'+';'.join(map(str,paths)),'com.bilipai.desktop.ui.RetainedHomeGateFixtureKt'],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=30)
safe(run/'run.log').write_text(r.stdout+r.stderr,encoding='utf-8');print((r.stdout+r.stderr).encode('ascii','backslashreplace').decode())
safe(run/'scope.json').write_text(json.dumps({'mainSnapshot':str(snapshot),'fixture':{'path':str(fixture),'sha256Bytes':sha(fixture)},'preparedOverrides':[str(owner),str(ports),str(vm)],'actualMainMounted':False,'userAccount':False,'native':False,'http':False,'exit':r.returncode},indent=2)+'\n',encoding='utf-8')
sys.exit(r.returncode)
