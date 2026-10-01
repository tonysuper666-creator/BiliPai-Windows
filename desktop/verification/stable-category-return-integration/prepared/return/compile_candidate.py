from pathlib import Path
import hashlib, json, importlib.util, subprocess, sys, zipfile

HERE=Path(__file__).resolve().parent
MAIN=HERE.parents[2]
PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
    s=str(Path(p).absolute())
    return Path(s if s.startswith(PREFIX) else PREFIX+s)
def sha(p): return hashlib.sha256(safe(p).read_bytes()).hexdigest()
SNAPSHOT=MAIN/'desktop/.local/stable-product-snapshot-36'
assert sha(SNAPSHOT/'manifest.json')=='a445ed0f4944ba6ac581b06401576aec23638ef242aa1da442a6baead60b637d'
assert sha(SNAPSHOT/'ordered-runtime-cp.json')=='2fa3092ec985e32bf6b1b046266e6b84994adeeb023615549a9422eddb0aa493'
cp=json.loads(safe(SNAPSHOT/'ordered-runtime-cp.json').read_text())
for r in cp: assert sha(r['path'])==r['sha256Bytes'],r['path']
sources=list((HERE/'prepared/direct').rglob('*.kt'))+list((HERE/'prepared/generated').rglob('*.kt'))+list((HERE/'prepared/manual').rglob('*.kt'))
run=HERE/('compile-'+sys.argv[1] if len(sys.argv)>1 else 'compile-02')
safe(run).mkdir(parents=True,exist_ok=False)
spec=importlib.util.spec_from_file_location('cc',MAIN/'desktop/.local/source9-appearance/compile-miuix.py')
cc=importlib.util.module_from_spec(spec);spec.loader.exec_module(cc)
plugin=cc.jar('org.jetbrains.kotlin','kotlin-serialization-compiler-plugin-embeddable','2.4.0')
inputs={'actualMain':str(SNAPSHOT),'manifestSha':sha(SNAPSHOT/'manifest.json'),
 'cpSha':sha(SNAPSHOT/'ordered-runtime-cp.json'),'cpEntries':len(cp),
 'sources':[{'path':str(p),'sha256Bytes':sha(p)} for p in sources],
 'compilerSerializationPlugin':{'path':str(plugin),'sha256Bytes':sha(plugin)},
 'prospectiveSourceOnly':True,'mainIntegration':False}
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows',
 '-Xfriend-paths='+str(SNAPSHOT/'main-kotlin.jar'),'-Xplugin='+str(plugin),
 '-cp',';'.join(r['path'] for r in cp),'-d',str(run/'classes')]+[str(p) for p in sources]
argfile=run/'compile.args'
safe(argfile).write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args),encoding='utf-8')
r=subprocess.run([str(cc.JAVA),'-Dfile.encoding=UTF-8','-Xmx2g','-cp',';'.join(map(str,cc.COMPILER)),
 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(argfile)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=180)
safe(run/'compile.log').write_text(r.stdout+r.stderr,encoding='utf-8')
inputs['exit']=r.returncode
safe(run/'inputs.json').write_text(json.dumps(inputs,indent=2)+'\n',encoding='utf-8')
if r.returncode==0:
    candidate=set()
    for p in (run/'classes').rglob('*.class'): candidate.add(p.relative_to(run/'classes').as_posix())
    actual=set()
    for row in cp:
        with zipfile.ZipFile(safe(row['path'])) as z: actual.update(n for n in z.namelist() if n.endswith('.class'))
    overlap=sorted(candidate&actual)
    safe(run/'class-overlap.json').write_text(json.dumps({'candidateClassCount':len(candidate),'all97ClassOverlap':overlap},indent=2)+'\n')
    assert not overlap,overlap
for row in inputs['sources']: assert sha(row['path'])==row['sha256Bytes']
print((r.stdout+r.stderr).encode('ascii','backslashreplace').decode())
print('exit',r.returncode,'sources',len(sources));sys.exit(r.returncode)
