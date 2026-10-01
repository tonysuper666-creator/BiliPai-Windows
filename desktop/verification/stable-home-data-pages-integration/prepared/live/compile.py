from pathlib import Path
import hashlib,json,subprocess,importlib.util,zipfile
LANE=Path(__file__).resolve().parent
ROOT=LANE.parents[4];MAIN=ROOT/'work/BiliPai'
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
spec=importlib.util.spec_from_file_location('livecompiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
aud=json.loads(safe(LANE/'input-audit.json').read_text(encoding='utf-8'));cp=aud['verifiedDependencyPins'];assert len(cp)==97
for row in cp:assert sha(row['path'])==row['sha256Bytes']
raw=aud['stable-home-request-ports-parity'];assert sha(raw['jar'])==raw['jarSha256Bytes']
out=LANE/'compile-02';safe(out).mkdir(exist_ok=True)
source=sorted(safe(LANE/'prepared/generated').rglob('*.kt'))+sorted(safe(LANE/'prepared/manual').rglob('*.kt'))
classpath=[raw['jar']]+[r['path'] for r in cp]
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(classpath),'-Xfriend-paths='+raw['jar']+','+cp[1]['path'],'-Xplugin='+str(c.PLUGIN),'-module-name','prepared_original_home_live_list','-d',str(out/'classes')]+list(map(str,source))
argfile=out/'compiler.args';safe(argfile).write_text('\n'.join('"'+str(a).replace('\\','/')+'"' for a in args),encoding='utf-8')
r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Xmx3g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(argfile)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=240)
safe(out/'compile.log').write_text(r.stdout+r.stderr,encoding='utf-8')
if r.returncode:print((r.stdout+r.stderr).encode('ascii',errors='backslashreplace').decode());raise SystemExit(r.returncode)
jar=out/'original-live-list.jar';actualclasses=set()
for row in cp:
 with zipfile.ZipFile(safe(row['path'])) as z:actualclasses.update(n for n in z.namelist() if n.endswith('.class'))
overlap=[];n=0
with zipfile.ZipFile(safe(jar),'w',zipfile.ZIP_DEFLATED) as z:
 for p in sorted(safe(out/'classes').rglob('*')):
  if p.is_file():
   rel=p.relative_to(safe(out/'classes')).as_posix();z.writestr(rel,p.read_bytes());n+=rel.endswith('.class')
   if rel.endswith('.class') and rel in actualclasses:overlap.append(rel)
assert not overlap,overlap
result={'exitCode':r.returncode,'sources':len(source),'sourcePins':[{'path':str(p),'sha256Bytes':sha(p)} for p in source],'actualProductEntries':97,'declaredProspectiveEntries':1,'prospectiveOwnerNetworkPayload':raw,'classFqnOverlap':overlap,'classes':n,'jar':str(jar),'jarSha256Bytes':sha(jar),'sharedGradleRun':False,'actualRootRuntime':False,'usesVM287':False}
safe(out/'compile-result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({k:v for k,v in result.items() if k not in ['sourcePins','prospectiveOwnerNetworkPayload']},indent=2))
