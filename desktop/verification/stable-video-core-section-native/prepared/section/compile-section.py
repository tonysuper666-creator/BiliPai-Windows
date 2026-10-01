from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys
sys.dont_write_bytecode=True
P=Path(__file__).resolve().parent;MAIN=P.parents[2];SNAP=MAIN/'desktop/.local/stable-product-snapshot-50'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def read(p):return wide(p).read_bytes()
def sha(p):return hashlib.sha256(read(p)).hexdigest()
def save(p,v):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes((json.dumps(v,indent=2)+'\n').encode())
assert sha(SNAP/'manifest.json')=='d9803560314da73036a848cf1be86c5877ff24b3993584379a5e9516165de4eb'
assert sha(SNAP/'ordered-runtime-cp.json')=='c4615263c430a5b75f86085499bcf3dae27239a005a59e258cdff172bbf75680'
cp=json.loads(read(SNAP/'ordered-runtime-cp.json'));assert len(cp)==97
s=importlib.util.spec_from_file_location('compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(s);s.loader.exec_module(c)
dependencies=[dict(path=str(MAIN/'desktop/.local/stable-video-state-holder-parity/core-compile-03/candidate.jar'),sha256Bytes='dc69724548f0b720e61d9e313efe3cee7e553b99f1a0e8ec3d32d21b084f97d9'),dict(path=str(MAIN/'desktop/.local/stable-video-player-page-parity/content-runs/06/candidate.jar'),sha256Bytes='f7ec9380b81737ed299bf49cecef412a1e9eb58b23f9fc5e80b7a969aaddc135')]
out=P/('section-runs/'+sys.argv[1]);assert not wide(out).exists();wide(out).mkdir(parents=True)
sources=sorted(wide(P/'prepared/manual').rglob('*.kt'))+sorted(wide(P/'generated').rglob('*.kt'))+sorted(wide(P/'section-generated').rglob('*.kt'))+sorted(wide(P/'proof-only').rglob('*.kt'));copied=[]
for p in sources:
 q=out/'source-inputs'/p.relative_to(wide(P));wide(q).parent.mkdir(parents=True,exist_ok=True);wide(q).write_bytes(read(p));copied.append(q)
def pins():
 for r in cp+dependencies:assert sha(r['path'])==r['sha256Bytes']
 return dict(runtime=cp,explicitPreparedDependencies=dependencies,tools=[dict(path=str(p),sha256Bytes=sha(p))for p in [c.JAVA,c.PLUGIN]+c.COMPILER],sources=[dict(path=str(p),sha256Bytes=sha(p))for p in copied])
save(out/'pins-before.json',pins());jar=out/'candidate.jar'
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(r['path']for r in dependencies+cp),'-Xplugin='+str(c.PLUGIN),'-Xfriend-paths='+','.join([str(SNAP/'main-kotlin.jar')]+[r['path']for r in dependencies]),'-module-name','com_bilipai_desktop_bilipai_windows','-d',str(jar),*map(str,copied)]
arg=out/'compile.args';wide(arg).write_bytes(('\n'.join('"'+str(a).replace('\\','/')+'"'for a in args)+'\n').encode())
r=subprocess.run([str(c.JAVA),'-Xmx3g','-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(arg)],capture_output=True,timeout=180)
wide(out/'compile.log').write_bytes(r.stdout+r.stderr);save(out/'pins-after.json',pins());assert read(out/'pins-before.json')==read(out/'pins-after.json')
save(out/'compile-result.json',dict(passed=r.returncode==0,sources=len(copied),exitCode=r.returncode,actual50RuntimeEntries=len(cp),productAcceptance=False,explicitProspectiveOverrides=['DesktopOriginalMpvOverlayControl access/constants/listener hunks','DesktopOriginalPlayerSettingsContext required device/build ports'],explicitPreparedDependencies=dependencies,candidateJarSha256Bytes=sha(jar)if wide(jar).exists()else None))
print((r.stdout+r.stderr).decode('utf-8',errors='replace')[:22000]);raise SystemExit(r.returncode)
