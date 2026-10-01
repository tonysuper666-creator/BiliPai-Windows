from pathlib import Path
import hashlib,json,subprocess,importlib.util,zipfile,sys
sys.stdout.reconfigure(encoding='utf-8')
LANE=Path(__file__).resolve().parent;MAIN=LANE.parents[2]
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def read(p):return safe(p).read_text(encoding='utf-8')
spec=importlib.util.spec_from_file_location('compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
snapshot=MAIN/'desktop/.local/stable-product-snapshot-44';receipt=snapshot/'ordered-runtime-cp.json'
assert sha(snapshot/'manifest.json')=='e5ba1d8de17cd400dde006ab3a75a1d5facdd68b8dba4bb52c9c51db52e5f31a'
assert sha(receipt)=='b4d72f39a19b3704e657a9edb8c8e8d18c10a1a352481c34a96f0ad208e17141'
cp=json.loads(read(receipt));assert len(cp)==97
for row in cp:assert sha(row['path'])==row['sha256Bytes'],row['path']
safe(LANE/'input-audit.json').write_text(json.dumps({'actualSnapshot':44,'entries':97,'verifiedDependencyPins':cp,'prospectiveOverrides':[],'actualRootRuntime':False},indent=2),encoding='utf-8')
number=sys.argv[1] if len(sys.argv)>1 else '01';out=LANE/('compile-'+number);safe(out).mkdir(exist_ok=True)
kotlin=sorted(safe(LANE/'prepared/desktop/src/main/kotlin').rglob('*.kt'));assert len(kotlin)==2
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(r['path'] for r in cp),'-Xfriend-paths='+cp[1]['path'],'-Xplugin='+str(c.PLUGIN),'-module-name','com_bilipai_desktop_bilipai_windows','-d',str(out/'classes')]+list(map(str,kotlin))
argfile=out/'kotlin.args';safe(argfile).write_text('\n'.join('"'+str(a).replace('\\','/')+'"' for a in args),encoding='utf-8')
r=subprocess.run([str(c.JAVA),'-Xmx3g','-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(argfile)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=120)
safe(out/'kotlin.log').write_text(r.stdout+r.stderr,encoding='utf-8')
if r.returncode:print(r.stderr);sys.exit(r.returncode)
actual=set()
for row in cp:
 with zipfile.ZipFile(safe(row['path'])) as z:actual.update(n for n in z.namelist() if n.endswith('.class'))
jar=out/'offline-task-player.jar';count=0
with zipfile.ZipFile(safe(jar),'w',zipfile.ZIP_DEFLATED) as z:
 for p in sorted(safe(out/'classes').rglob('*')):
  if p.is_file():
   name=p.relative_to(safe(out/'classes')).as_posix()
   if name.endswith('.class'):assert name not in actual,name;count+=1
   z.writestr(name,p.read_bytes())
result={'actualSnapshot':44,'actualRuntimeEntries':97,'sources':len(kotlin),'classes':count,'jar':str(jar),'jarSha256Bytes':sha(jar),'declaredProspectiveOverrideFamilies':[],'newFqnDuplicate':0,'sourcePins':[{'path':str(p),'sha256Bytes':sha(p)} for p in kotlin],'sharedGradleRun':False,'rootMounted':False,'rootRuntimeAccepted':False}
safe(out/'compile-result.json').write_text(json.dumps(result,indent=2),encoding='utf-8');print(json.dumps({k:v for k,v in result.items() if k!='sourcePins'},indent=2))
