from pathlib import Path
import hashlib,json,subprocess,importlib.util,zipfile,sys
LANE=Path(__file__).resolve().parent;ROOT=LANE.parents[4];MAIN=ROOT/'work/BiliPai'
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
spec=importlib.util.spec_from_file_location('profilecompiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
# Root froze all previously prospective VM/raw/page declarations in actual39. Zero overrides.
snapshot=MAIN/'desktop/.local/stable-product-snapshot-39';previous=snapshot/'ordered-runtime-cp.json'
assert sha(snapshot/'manifest.json')=='4840845d18a6fd1171c32a03ba397306a0f509b6510ca9544ceac935befe46ae'
assert sha(previous)=='e9b8db57c428c214b243e2ea41761d0745c3b48ff386fe8d38901d892760fd8f'
cp=json.loads(safe(previous).read_text(encoding='utf-8'));assert len(cp)==97
for row in cp:assert sha(row['path'])==row['sha256Bytes']
safe(LANE/'input-audit.json').write_text(json.dumps({'inputReceiptPath':str(previous),'inputReceiptSha256Bytes':sha(previous),'verifiedDependencyPins':cp,'runtimeSourceOverrides':0,'actualRoot':False},ensure_ascii=False,indent=2),encoding='utf-8')
number=sys.argv[1] if len(sys.argv)>1 else '01';out=LANE/('compile-'+number);safe(out).mkdir(exist_ok=True)
source=sorted(safe(LANE/'prepared/generated').rglob('*.kt'))+sorted(safe(LANE/'prepared/manual').rglob('*.kt'))
classpath=[r['path'] for r in cp]
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(classpath),'-Xfriend-paths='+cp[1]['path'],'-Xplugin='+str(c.PLUGIN),'-module-name','prepared_original_profile_main','-d',str(out/'classes')]+list(map(str,source))
argfile=out/'compiler.args';safe(argfile).write_text('\n'.join('"'+str(a).replace('\\','/')+'"' for a in args),encoding='utf-8')
r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Xmx3g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(argfile)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=240)
safe(out/'compile.log').write_text(r.stdout+r.stderr,encoding='utf-8')
if r.returncode:print((r.stdout+r.stderr).encode('ascii',errors='backslashreplace').decode());raise SystemExit(r.returncode)
jar=out/'original-profile-main.jar';actualclasses=set()
for row in cp:
 with zipfile.ZipFile(safe(row['path'])) as z:actualclasses.update(n for n in z.namelist() if n.endswith('.class'))
overlap=[];n=0
with zipfile.ZipFile(safe(jar),'w',zipfile.ZIP_DEFLATED) as z:
 for p in sorted(safe(out/'classes').rglob('*')):
  if p.is_file():
   rel=p.relative_to(safe(out/'classes')).as_posix();z.writestr(rel,p.read_bytes());n+=rel.endswith('.class')
   if rel.endswith('.class') and rel in actualclasses:overlap.append(rel)
assert not overlap,overlap
result={'exitCode':r.returncode,'sources':len(source),'sourcePins':[{'path':str(p),'sha256Bytes':sha(p)} for p in source],'actualProductEntries':97,'declaredProspectiveEntries':0,'runtimeSourceOverrides':0,'classFqnOverlap':overlap,'classes':n,'jar':str(jar),'jarSha256Bytes':sha(jar),'sharedGradleRun':False,'actualRootRuntime':False}
safe(out/'compile-result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({k:v for k,v in result.items() if k not in ['sourcePins','prospectiveOwnerNetworkPayload']},indent=2))
