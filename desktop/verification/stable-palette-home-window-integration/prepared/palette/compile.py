from pathlib import Path
import hashlib,json,subprocess,importlib.util,zipfile,sys
LANE=Path(__file__).resolve().parent;MAIN=LANE.parents[2]
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def read(p):return safe(p).read_text(encoding='utf-8')
sp=importlib.util.spec_from_file_location('wallpapercompiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(sp);sp.loader.exec_module(c)
snapshot=MAIN/'desktop/.local/stable-product-snapshot-41';receipt=snapshot/'ordered-runtime-cp.json'
assert sha(receipt)=='23e35645396f8b5b1d720c9604799bacccb75e3edca551dcf49da54497564abe'
cp=json.loads(read(receipt));assert len(cp)==97
for row in cp:assert sha(row['path'])==row['sha256Bytes']
safe(LANE/'input-audit.json').write_text(json.dumps({'actualSnapshot':41,'entries':97,'verifiedDependencyPins':cp,'overrides':0},indent=2),encoding='utf-8')
number=sys.argv[1] if len(sys.argv)>1 else '01';out=LANE/('compile-'+number);safe(out).mkdir(exist_ok=True)
java=sorted(safe(LANE/'prepared/manual-java').rglob('*.java'));kotlin=sorted(safe(LANE/'prepared/manual').rglob('*.kt'))+sorted(safe(LANE/'prepared/generated').rglob('*.kt'))
classpath=[r['path'] for r in cp]
def run(tool,args,name):
 argfile=out/(name+'.args');safe(argfile).write_text('\n'.join('"'+str(a).replace('\\','/')+'"' for a in args),encoding='utf-8')
 r=subprocess.run([str(tool),'@'+str(argfile)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=120)
 safe(out/(name+'.log')).write_text(r.stdout+r.stderr,encoding='utf-8');assert r.returncode==0,r.stderr
run(c.JAVA.with_name('javac.exe'),['-encoding','UTF-8','-cp',';'.join(classpath),'-d',str(out/'classes')]+[str(p).removeprefix(chr(92)*2+'?'+chr(92)) for p in java],'java')
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join([str(out/'classes')]+classpath),'-Xfriend-paths='+cp[1]['path'],'-Xplugin='+str(c.PLUGIN),'-module-name','prepared_original_wallpaper_palette','-d',str(out/'classes')]+list(map(str,kotlin))
argfile=out/'kotlin.args';safe(argfile).write_text('\n'.join('"'+str(a).replace('\\','/')+'"' for a in args),encoding='utf-8')
r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(argfile)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=120)
safe(out/'kotlin.log').write_text(r.stdout+r.stderr,encoding='utf-8');assert r.returncode==0,r.stderr
actual=set()
for row in cp:
 with zipfile.ZipFile(safe(row['path'])) as z:actual.update(n for n in z.namelist() if n.endswith('.class'))
jar=out/'original-wallpaper-palette.jar';count=0
with zipfile.ZipFile(safe(jar),'w',zipfile.ZIP_DEFLATED) as z:
 for p in safe(out/'classes').rglob('*'):
  if p.is_file():
   name=p.relative_to(safe(out/'classes')).as_posix()
   if name.endswith('.class'):assert name not in actual,name;count+=1
   z.writestr(name,p.read_bytes())
result={'actualSnapshot':41,'actualRuntimeEntries':97,'sources':len(java)+len(kotlin),'javaSources':len(java),'kotlinSources':len(kotlin),'classes':count,'jar':str(jar),'jarSha256Bytes':sha(jar),'overrides':0,'duplicateFqn':0,'sourcePins':[{'path':str(p),'sha256Bytes':sha(p)} for p in java+kotlin],'sharedGradleRun':False,'rootMounted':False}
safe(out/'compile-result.json').write_text(json.dumps(result,indent=2),encoding='utf-8');print(json.dumps({k:v for k,v in result.items() if k!='sourcePins'},indent=2))
