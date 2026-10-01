from pathlib import Path
import hashlib, json, importlib.util, subprocess, sys, zipfile, os

HERE=Path(__file__).resolve().parent
MAIN=HERE.parents[2]
PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
    s=os.path.abspath(p)
    return Path(s if s.startswith(PREFIX) else PREFIX+s)
def sha(p): return hashlib.sha256(safe(p).read_bytes()).hexdigest()
SNAPSHOT=MAIN/'desktop/.local/stable-product-snapshot-40'
assert sha(SNAPSHOT/'manifest.json')=='568846a4ca40019b33ef554a4c679cbe79296d8e2760173352da8a09a9d81903'
assert sha(SNAPSHOT/'ordered-runtime-cp.json')=='3f3dc7c6fd682df07afa523fc31c0c43804549c0e1c0dbea81cf264a6062738d'
cp=json.loads(safe(SNAPSHOT/'ordered-runtime-cp.json').read_text(encoding='utf-8'))
assert len(cp)==97
for row in cp: assert sha(row['path'])==row['sha256Bytes'],row['path']
sources=list((HERE/'generated').rglob('*.kt'))+list((HERE/'prepared/manual').rglob('*.kt'))
run=HERE/('compile-'+sys.argv[1] if len(sys.argv)>1 else 'compile-01')
safe(run).mkdir(parents=True,exist_ok=False)
spec=importlib.util.spec_from_file_location('cc',MAIN/'desktop/.local/source9-appearance/compile-miuix.py')
cc=importlib.util.module_from_spec(spec);spec.loader.exec_module(cc)
inputs={'actualMain':str(SNAPSHOT),'manifestSha':sha(SNAPSHOT/'manifest.json'),
 'cpSha':sha(SNAPSHOT/'ordered-runtime-cp.json'),'cpEntries':len(cp),
 'sources':[{'path':str(p),'sha256Bytes':sha(p)} for p in sources],
 'prospectiveSourceOnly':True,'mainIntegration':False,'productSourceOverrides':0}
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows',
 '-Xfriend-paths='+str(SNAPSHOT/'main-kotlin.jar'),'-Xplugin='+str(cc.PLUGIN),
 '-cp',';'.join(r['path'] for r in cp),'-d',str(run/'classes')]+[str(p) for p in sources]
argfile=run/'compile.args'
safe(argfile).write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args),encoding='utf-8')
r=subprocess.run([str(cc.JAVA),'-Dfile.encoding=UTF-8','-Xmx3g','-cp',';'.join(map(str,cc.COMPILER)),
 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(argfile)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=240)
safe(run/'compile.log').write_text(r.stdout+r.stderr,encoding='utf-8')
inputs['exit']=r.returncode
safe(run/'inputs.json').write_text(json.dumps(inputs,indent=2)+'\n',encoding='utf-8')
if r.returncode==0:
    candidate={p.relative_to(run/'classes').as_posix() for p in (run/'classes').rglob('*.class')}
    actual=set()
    for row in cp:
        with zipfile.ZipFile(safe(row['path'])) as z: actual.update(n for n in z.namelist() if n.endswith('.class'))
    overlap=sorted(candidate&actual)
    safe(run/'class-overlap.json').write_text(json.dumps({'candidateClassCount':len(candidate),'all97ClassOverlap':overlap},indent=2)+'\n')
    assert not overlap,overlap
for row in inputs['sources']: assert sha(row['path'])==row['sha256Bytes']
for row in cp: assert sha(row['path'])==row['sha256Bytes']
print((r.stdout+r.stderr).encode('ascii','backslashreplace').decode())
print('exit',r.returncode,'sources',len(sources));sys.exit(r.returncode)
