from pathlib import Path
import hashlib,json,importlib.util,subprocess,sys,zipfile
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2]
PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith(PREFIX) else PREFIX+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
snapshot=MAIN/'desktop/.local/stable-product-snapshot-39'
assert sha(snapshot/'manifest.json')=='4840845d18a6fd1171c32a03ba397306a0f509b6510ca9544ceac935befe46ae'
assert sha(snapshot/'ordered-runtime-cp.json')=='e9b8db57c428c214b243e2ea41761d0745c3b48ff386fe8d38901d892760fd8f'
cp=json.loads(safe(snapshot/'ordered-runtime-cp.json').read_text())
for row in cp:assert sha(row['path'])==row['sha256Bytes'],row['path']
spec=importlib.util.spec_from_file_location('cc',MAIN/'desktop/.local/source9-appearance/compile-miuix.py')
cc=importlib.util.module_from_spec(spec);spec.loader.exec_module(cc)
mode=sys.argv[1] if len(sys.argv)>1 else 'compile-01'
run=HERE/mode;safe(run).mkdir(parents=True,exist_ok=False)
files=list((HERE/'prepared/manual').rglob('*.kt')) if mode.startswith('compile') else list((HERE/'fixtures').rglob('*.kt'))
candidate=HERE/'compile-02/classes'
classpath=';'.join(r['path'] for r in cp)
friend=str(snapshot/'main-kotlin.jar')
if mode.startswith('proof'):classpath=str(candidate)+';'+classpath;friend+=','+str(candidate)
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows',
 '-Xfriend-paths='+friend,'-Xplugin='+str(cc.PLUGIN),'-cp',classpath,'-d',str(run/'classes')]+list(map(str,files))
argfile=run/'compiler.args';safe(argfile).write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args),encoding='utf-8')
inputs={'snapshot':str(snapshot),'manifestSha':sha(snapshot/'manifest.json'),
 'orderedCpSha':sha(snapshot/'ordered-runtime-cp.json'),'cpEntries':len(cp),'preparedOverrides':[],
 'sources':[{'path':str(p),'sha256Bytes':sha(p)} for p in files],
 'explicitNewCandidate':str(candidate) if mode.startswith('proof') else None,
 'RootMounted':False,'actualUi':False,'actualHttp':False}
r=subprocess.run([str(cc.JAVA),'-Dfile.encoding=UTF-8','-Xmx2g','-cp',';'.join(map(str,cc.COMPILER)),
 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(argfile)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=180)
safe(run/'compile.log').write_text(r.stdout+r.stderr,encoding='utf-8');inputs['compileExit']=r.returncode
if r.returncode==0 and mode.startswith('compile'):
 names={p.relative_to(run/'classes').as_posix() for p in (run/'classes').rglob('*.class')};actual=set()
 for row in cp:
  with zipfile.ZipFile(safe(row['path']))as z:actual.update(n for n in z.namelist() if n.endswith('.class'))
 inputs['candidateClassCount']=len(names);inputs['classOverlap']=sorted(names&actual)
 assert not inputs['classOverlap']
if r.returncode==0 and mode.startswith('proof'):
 command=[str(cc.JAVA),'-Dfile.encoding=UTF-8','-cp',str(run/'classes')+';'+classpath,'com.bilipai.desktop.ui.EmbeddedLifetimeFixtureKt']
 r=subprocess.run(command,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90)
 safe(run/'run.log').write_text(r.stdout+r.stderr,encoding='utf-8');inputs['command']=command;inputs['runExit']=r.returncode
safe(run/'inputs.json').write_text(json.dumps(inputs,indent=2)+'\n',encoding='utf-8')
print((r.stdout+r.stderr).encode('ascii','backslashreplace').decode());print('exit',r.returncode,'sources',len(files));sys.exit(r.returncode)
