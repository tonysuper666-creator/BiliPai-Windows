from pathlib import Path
import hashlib,json,importlib.util,subprocess,sys,zipfile
sys.dont_write_bytecode=True
P=Path(__file__).resolve().parent;MAIN=P.parents[2];S=MAIN/'desktop/.local/stable-product-snapshot-50'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def sha(p):return hashlib.sha256(wide(p).read_bytes()).hexdigest()
def save(p,v):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes((json.dumps(v,indent=2)+'\n').encode())
assert sha(S/'manifest.json')=='d9803560314da73036a848cf1be86c5877ff24b3993584379a5e9516165de4eb'
assert sha(S/'ordered-runtime-cp.json')=='c4615263c430a5b75f86085499bcf3dae27239a005a59e258cdff172bbf75680'
core=MAIN/'desktop/.local/stable-video-state-holder-parity/install-compile-01/candidate.jar'
assert sha(MAIN/'desktop/.local/stable-video-state-holder-parity/frozen-handoff.json')=='b51df19b3c3bdc4507cc06ff7c3b7ffb1ef259152d503275c16f5ace43247377'
coreSha=sha(core)
deps=[dict(path=str(core),sha256Bytes=coreSha)]
cp=json.loads(wide(S/'ordered-runtime-cp.json').read_bytes());assert len(cp)==97
spec=importlib.util.spec_from_file_location('cc',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
out=P/('compile-runs/'+sys.argv[1]);wide(out).mkdir(parents=True,exist_ok=False)
sources=sorted(wide(P/'proof-only').rglob('*.kt'))+sorted(wide(P/'prepared/manual').rglob('*.kt'))
for p in sources:
 q=wide(out/'source-inputs')/p.relative_to(wide(P));q.parent.mkdir(parents=True,exist_ok=True);q.write_bytes(p.read_bytes())
def pins():
 for r in cp+deps:assert sha(r['path'])==r['sha256Bytes']
 return dict(runtime=cp,explicitPreparedDependencies=deps,sourceInputs=[dict(path=str(p),sha256Bytes=sha(p))for p in sources],tools=[dict(path=str(p),sha256Bytes=sha(p))for p in [c.JAVA,c.PLUGIN]+c.COMPILER])
save(out/'pins-before.json',pins())
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows','-Xplugin='+str(c.PLUGIN),'-Xfriend-paths='+','.join([str(core),str(S/'main-kotlin.jar')]),'-cp',';'.join(r['path']for r in deps+cp),'-d',str(out/'candidate.jar')]+list(map(str,sources))
wide(out/'compile.args').write_bytes(('\n'.join('"'+a.replace('\\','/')+'"'for a in args)+'\n').encode())
r=subprocess.run([str(c.JAVA),'-Xmx3g','-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compile.args')],capture_output=True,timeout=180)
wide(out/'compile.log').write_bytes(r.stdout+r.stderr)
overlap=[];classes=[]
if r.returncode==0:
 with zipfile.ZipFile(wide(out/'candidate.jar'))as z:classes=[n for n in z.namelist()if n.endswith('.class')]
 existing=set()
 for row in cp[:3]:
  with zipfile.ZipFile(wide(row['path']))as z:existing.update(z.namelist())
 overlap=sorted(set(classes)&existing)
 allowed=('com/bilipai/desktop/data/DesktopRepository','com/bilipai/desktop/data/DesktopSessionEpoch','com/bilipai/desktop/data/DesktopPlaybackCache')
 assert all(n.startswith(allowed)for n in overlap),overlap
save(out/'pins-after.json',pins());assert wide(out/'pins-before.json').read_bytes()==wide(out/'pins-after.json').read_bytes()
save(out/'result.json',dict(passed=r.returncode==0,compileExit=r.returncode,sources=len(sources),classes=len(classes),explicitProductOverrides=['DesktopRepository exact five hunks','DesktopPlaybackCache exact three timing/capacity hunks'],productClassOverlap=overlap,unpermittedOverlap=[],coreJarSha256Bytes=coreSha,candidateJarSha256Bytes=sha(out/'candidate.jar')if r.returncode==0 else None,actual50CP=sha(S/'ordered-runtime-cp.json'),runtimeEntries=len(cp),preparedOnly=True,network=False,native=False,fullVmAcceptance=False))
print((r.stdout+r.stderr).decode('utf-8',errors='replace')[:14000]);sys.exit(r.returncode)
