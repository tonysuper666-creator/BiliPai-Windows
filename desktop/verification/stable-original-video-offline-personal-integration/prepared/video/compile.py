from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys,zipfile
sys.dont_write_bytecode=True
LANE=Path(__file__).resolve().parent;MAIN=LANE.parents[2];REPO=MAIN.parent/'BiliPai-v023';SNAP=MAIN/'desktop/.local/stable-product-snapshot-47'
def wide(p):
    s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def read(p):return wide(p).read_bytes()
def sha(p):return hashlib.sha256(read(p)).hexdigest()
def save(p,v):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
assert sha(SNAP/'manifest.json')=='72e862d74e18c2b388853634f0ef47c48ca1adba25b5d074ecd23fc6ecb7c3e0'
assert sha(SNAP/'ordered-runtime-cp.json')=='9fecec31b818a1b1d8be25f628ff6ec69e354a5c179ca42445aa1a1db3d52e94'
cp=json.loads(read(SNAP/'ordered-runtime-cp.json'));assert len(cp)==97
spec=importlib.util.spec_from_file_location('compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
out=LANE/('runs/'+sys.argv[1]);assert not out.exists();out.mkdir(parents=True)
sources=sorted(wide(LANE/'generated').rglob('*.kt'))+sorted(wide(LANE/'prepared/manual').rglob('*.kt'))
metadata=REPO/'desktop/build/generated/stable-video-metadata/com/android/purebilibili/feature/video/ui/section/DesktopOriginalVideoMetadata.kt'
if not metadata.exists():
    choices=list((REPO/'desktop/build/generated').rglob('DesktopOriginalVideoMetadata.kt'));assert len(choices)==1;metadata=choices[0]
text=read(metadata).decode('utf-8').replace('\r\n','\n')
for name in ('VideoDetailBadgeChip','VideoArgueMsgRow','VideoHonorChip'):
    a='private fun '+name;b='internal fun '+name;assert text.count(a)==1;text=text.replace(a,b,1)
buddy=out/'source-inputs/reference-owner/DesktopOriginalVideoMetadata.kt';wide(buddy).parent.mkdir(parents=True,exist_ok=True);wide(buddy).write_text(text,encoding='utf-8');sources.append(buddy)
copied=[]
opsPath='desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopDynamicCardOperations.kt'
ops=REPO/opsPath;opsBase=read(ops)
record=next(r for r in json.loads(read(SNAP/'manifest.json'))['inputs']if r['path']==opsPath)
assert hashlib.sha256(opsBase).hexdigest()==record['sha256Bytes']
opsText=opsBase.decode('utf-8').replace('\r\n','\n');marker='// GENERATED original editor members; do not hand-maintain a second request algorithm.'
assert opsText.count(marker)==1
fragment=read(LANE/'prepared/DesktopDynamicCardOperations.video-members.kt.fragment').decode('utf-8')
opsText=opsText.replace(marker,fragment+'\n'+marker,1)
opsBuddy=out/'source-inputs/reference-owner/DesktopDynamicCardOperations.kt';wide(opsBuddy).write_text(opsText,encoding='utf-8');sources.append(opsBuddy)
save(out/'ops-overlay-base.json',dict(path=str(ops),sha256Bytes=sha(ops),actual47InputByteEqual=True,fragmentSha256Bytes=sha(LANE/'prepared/DesktopDynamicCardOperations.video-members.kt.fragment'),candidateSha256LF=hashlib.sha256(opsText.encode()).hexdigest()))
for p in sources:
    if p in (buddy,opsBuddy):copied.append(p);continue
    q=out/'source-inputs'/p.relative_to(wide(LANE));wide(q).parent.mkdir(parents=True,exist_ok=True);wide(q).write_bytes(read(p));copied.append(q)
tools=[c.JAVA,c.PLUGIN]+c.COMPILER
def pins():
    for r in cp:assert sha(r['path'])==r['sha256Bytes']
    return dict(runtime=cp,tools=[dict(path=str(p),sha256Bytes=sha(p))for p in tools],sources=[dict(path=str(p),sha256Bytes=sha(p))for p in copied])
save(out/'pins-before.json',pins())
jar=out/'candidate.jar';args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(r['path']for r in cp),'-Xplugin='+str(c.PLUGIN),'-Xfriend-paths='+str(SNAP/'main-kotlin.jar'),'-module-name','com_bilipai_desktop_bilipai_windows','-d',str(jar),*map(str,copied)]
arg=out/'compile.args';wide(arg).write_text('\n'.join('"'+str(a).replace('\\','/')+'"'for a in args)+'\n',encoding='utf-8')
r=subprocess.run([str(c.JAVA),'-Xmx3g','-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(arg)],capture_output=True,timeout=180)
wide(out/'compile.log').write_bytes(r.stdout+r.stderr)
save(out/'pins-after.json',pins());assert read(out/'pins-before.json')==read(out/'pins-after.json')
save(out/'compile-result.json',dict(passed=r.returncode==0,sources=len(copied),exitCode=r.returncode,actual47RuntimeEntries=len(cp),productAcceptance=False,candidateJarSha256Bytes=sha(jar)if jar.exists()else None,explicitExistingOverride=['Metadata renderer three visibility adjustments only; original bodies preserved','Actual47 Operations with only the new video member fragment; same original transport/session authority']))
print((r.stdout+r.stderr).decode('utf-8',errors='replace'));raise SystemExit(r.returncode)
