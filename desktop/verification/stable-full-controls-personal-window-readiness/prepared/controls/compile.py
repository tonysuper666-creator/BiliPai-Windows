from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys
sys.dont_write_bytecode=True
LANE=Path(__file__).resolve().parent;MAIN=LANE.parents[2];SNAP=MAIN/'desktop/.local/stable-product-snapshot-47';FOUNDATION=MAIN/'desktop/.local/stable-video-detail-full-ui-parity/runs/12/candidate.jar'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def read(p):return wide(p).read_bytes()
def sha(p):return hashlib.sha256(read(p)).hexdigest()
def save(p,v):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
assert sha(SNAP/'manifest.json')=='72e862d74e18c2b388853634f0ef47c48ca1adba25b5d074ecd23fc6ecb7c3e0'
assert sha(SNAP/'ordered-runtime-cp.json')=='9fecec31b818a1b1d8be25f628ff6ec69e354a5c179ca42445aa1a1db3d52e94'
assert sha(FOUNDATION)=='5beaaedff287e7ce61161be31fd01c40583fd59ee783352963819c9c2c8d2a31'
cp=json.loads(read(SNAP/'ordered-runtime-cp.json'));assert len(cp)==97
spec=importlib.util.spec_from_file_location('compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
out=LANE/('runs/'+sys.argv[1]);assert not wide(out).exists();wide(out).mkdir(parents=True)
sources=sorted(wide(LANE/'generated').rglob('*.kt'))+sorted(wide(LANE/'prepared/manual').rglob('*.kt'));copied=[]
external=MAIN/'desktop/.local/stable-original-offline-player-parity/prepared/generated/com/android/purebilibili/core/store/player/DesktopOriginalLongPressSpeedSettings.kt'
for p in sources:
 q=out/'source-inputs'/p.relative_to(wide(LANE));wide(q).parent.mkdir(parents=True,exist_ok=True);wide(q).write_bytes(read(p));copied.append(q)
q=out/'source-inputs/explicit-reference/DesktopOriginalLongPressSpeedSettings.kt';wide(q).parent.mkdir(parents=True,exist_ok=True);wide(q).write_bytes(read(external));copied.append(q)
tools=[c.JAVA,c.PLUGIN]+c.COMPILER
def pins():
 for r in cp:assert sha(r['path'])==r['sha256Bytes']
 return dict(runtime=cp,prospectiveFoundationJarSha256Bytes=sha(FOUNDATION),tools=[dict(path=str(p),sha256Bytes=sha(p))for p in tools],sources=[dict(path=str(p),sha256Bytes=sha(p))for p in copied])
save(out/'pins-before.json',pins())
jar=out/'candidate.jar';args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join([str(FOUNDATION)]+[r['path']for r in cp]),'-Xplugin='+str(c.PLUGIN),'-Xfriend-paths='+','.join([str(FOUNDATION),str(SNAP/'main-kotlin.jar')]),'-module-name','com_bilipai_desktop_bilipai_windows','-d',str(jar),*map(str,copied)]
arg=out/'compile.args';wide(arg).write_text('\n'.join('"'+str(a).replace('\\','/')+'"'for a in args)+'\n',encoding='utf-8')
r=subprocess.run([str(c.JAVA),'-Xmx3g','-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(arg)],capture_output=True,timeout=180)
wide(out/'compile.log').write_bytes(r.stdout+r.stderr);save(out/'pins-after.json',pins());assert read(out/'pins-before.json')==read(out/'pins-after.json')
save(out/'compile-result.json',dict(passed=r.returncode==0,sources=len(copied),exitCode=r.returncode,actual47RuntimeEntries=len(cp),productAcceptance=False,explicitProspectiveFoundationJar=str(FOUNDATION),candidateJarSha256Bytes=sha(jar)if wide(jar).exists()else None))
print((r.stdout+r.stderr).decode('utf-8',errors='replace'));raise SystemExit(r.returncode)
