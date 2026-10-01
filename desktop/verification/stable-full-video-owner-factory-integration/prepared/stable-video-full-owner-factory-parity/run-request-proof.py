from pathlib import Path
import hashlib, importlib.util, json, os, subprocess, sys, zipfile

P=Path(__file__).resolve().parent; MAIN=P.parents[2]
SNAP=MAIN/'desktop/.local/stable-product-snapshot-69'
PFX=chr(92)*2+'?'+chr(92)
def wide(p):
 s=os.path.abspath(p);return Path(s if s.startswith(PFX)else PFX+s)
def sha(p):return hashlib.sha256(wide(p).read_bytes()).hexdigest()
def save(p,v):
 wide(p).parent.mkdir(parents=True,exist_ok=True)
 wide(p).write_text(json.dumps(v,indent=2)+'\n',encoding='utf-8',newline='\n')
assert sha(SNAP/'manifest.json')=='4524b0c8b5e4e97bb88223a6a6f71c126bc17586111d50a10fb49f186a3d695d'
assert sha(SNAP/'ordered-runtime-cp.json')=='125c041f103947b5aa4be065a182cc561528bde575afe682db9e9af43a42dc9f'
cp=json.loads(wide(SNAP/'ordered-runtime-cp.json').read_text()); assert len(cp)==101
def check():
 for r in cp:assert sha(r['path'])==r['sha256Bytes'],r['path']
check()
spec=importlib.util.spec_from_file_location('c',MAIN/'desktop/.local/source9-appearance/compile-miuix.py')
c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
out=P/'runs'/sys.argv[1];assert not wide(out).exists();wide(out).mkdir(parents=True)
sources=[P/'prepared/manual/com/bilipai/desktop/ui/DesktopOriginalVideoOwnerRequestRepository.kt',
 P/'prepared/existing/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoRepositoryBinding.kt',
 P/'prepared/existing/desktop/src/main/kotlin/com/bilipai/desktop/player/DesktopSubtitleAssets.kt',P/'RequestRepositoryProof.kt']
for p in sources:
 dest=wide(out/'source-inputs'/p.relative_to(P));dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(wide(p).read_bytes())
pins=dict(snapshotManifestSHA256=sha(SNAP/'manifest.json'),orderedCPSHA256=sha(SNAP/'ordered-runtime-cp.json'),runtime=cp,
 inputs=[dict(path=str(p),sha256Bytes=sha(p))for p in sources],compiler=[dict(path=str(p),sha256Bytes=sha(p))for p in [c.JAVA,c.PLUGIN]+c.COMPILER])
save(out/'pins-before.json',pins)
jar=out/'candidate-proof.jar'
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows',
 '-Xplugin='+str(c.PLUGIN),'-Xfriend-paths='+cp[1]['path'],'-cp',';'.join(r['path']for r in cp),'-d',str(jar)]+list(map(str,sources))
arg=out/'compile.args';wide(arg).write_text('\n'.join('"'+str(a).replace('\\','/')+'"'for a in args),encoding='utf8')
command=[str(c.JAVA),'-Dfile.encoding=UTF-8','-Xmx2g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(arg)]
r=subprocess.run(command,capture_output=True,timeout=180);wide(out/'compile.log').write_bytes(r.stdout+r.stderr)
result=dict(compilePassed=r.returncode==0,compileExitCode=r.returncode,runtimeEntries=101,actual69ImmutableGraph=True,
 preparedFamilies=['DesktopOriginalVideoRepositoryBinding','DesktopSubtitleAssets'],originalVMOverride=False,
 fullOwnerConstructed=False,mounted=False,HTTP=False,native=False,realAccountIO=False)
if r.returncode==0:
 with zipfile.ZipFile(wide(jar))as z:result['classes']=sum(n.endswith('.class')for n in z.namelist())
 result['jarSHA256Bytes']=sha(jar)
 cmd=[str(c.JAVA),'-Dfile.encoding=UTF-8','-cp',str(jar)+';'+';'.join(r['path']for r in cp),'com.bilipai.desktop.ui.RequestRepositoryProofKt']
 save(out/'runtime-command.json',dict(command=cmd))
 run=subprocess.run(cmd,capture_output=True,timeout=60);wide(out/'run.log').write_bytes(run.stdout+run.stderr)
 result.update(runtimeExitCode=run.returncode,passed=run.returncode==0)
 print((run.stdout+run.stderr).decode('utf8',errors='replace'))
else:print((r.stdout+r.stderr).decode('utf8',errors='replace'));result['passed']=False
check();save(out/'pins-after.json',pins);save(out/'result.json',result)
assert wide(out/'pins-before.json').read_bytes()==wide(out/'pins-after.json').read_bytes()
raise SystemExit(0 if result['passed']else 1)
