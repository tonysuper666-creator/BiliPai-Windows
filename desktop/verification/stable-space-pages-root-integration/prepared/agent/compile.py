"""Compile only declared new Space bindings/bodies against immutable actual89.
No Candidate write, shared Gradle, network or HWND action.
"""
from pathlib import Path
import hashlib, importlib.util, json, os, subprocess, sys, zipfile
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf8')
P=Path(__file__).resolve().parent;M=P.parents[2];S=M/'desktop/.local/stable-product-snapshot-89'
def wide(p):
    s=str(p);return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+os.path.abspath(s))
def sha(p):return hashlib.sha256(wide(p).read_bytes()).hexdigest()
cp=json.loads(wide(S/'ordered-runtime-cp.json').read_text(encoding='utf8'))
def pins():
    assert sha(S/'manifest.json')=='aa28f253a357c107073d20ef279f28716a96dfa4015aca2be180f1865b14340b'
    assert sha(S/'ordered-runtime-cp.json')=='63b3d1693889263d164badad1a3e45aa7b09168181be74cb1eff83b7a82bffb5'
    assert len(cp)==105
    for row in cp:assert sha(row['path'])==row['sha256Bytes'],row['path']
pins()
spec=importlib.util.spec_from_file_location('compiler',M/'desktop/.local/source9-appearance/compile-miuix.py')
c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
sources=sorted(wide(P/'generated').rglob('*.kt'))+sorted(wide(P/'prepared/desktop/src/main').rglob('*.kt'))
if '--root-hooks' in sys.argv:
    sources+=sorted(wide(P/'root-hooks/compiler-targets').rglob('*.kt'))
out=P/'compile-runs'/sys.argv[1];wide(out).mkdir(parents=True,exist_ok=False)
saved=[]
for index,source in enumerate(sources):
    destination=out/'inputs'/str(index)/source.name;wide(destination).parent.mkdir(parents=True,exist_ok=True)
    wide(destination).write_bytes(source.read_bytes());saved.append(destination)
main=next(row['path']for row in cp if row.get('source')=='desktop/build/classes/kotlin/main')
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows',
      '-Xplugin='+str(c.PLUGIN),'-Xfriend-paths='+main,'-cp',';'.join(row['path']for row in cp),
      '-d',str(out/'classes')]+[str(wide(f))for f in saved]
wide(out/'compiler.args').write_text('\n'.join('"'+arg.replace('\\','/')+'"'for arg in args),encoding='utf8')
result=subprocess.run([str(c.JAVA),'-Xmx5g','-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')],capture_output=True,timeout=360)
wide(out/'compile.log').write_bytes(result.stdout+result.stderr);pins()
wide(out/'result.json').write_text(json.dumps(dict(passed=result.returncode==0,snapshot=89,orderedRuntimeEntries=105,
    explicitPreparedInputs=[dict(path=str(f),sha256Bytes=sha(f))for f in saved],
    productionWrites=0,sharedGradleRuns=0,rootRuntimeAccepted=False),indent=2),encoding='utf8')
if result.returncode==0:
    with zipfile.ZipFile(wide(out/'prospective-space.jar'),'w',zipfile.ZIP_DEFLATED)as jar:
        for f in wide(out/'classes').rglob('*'):
            if f.is_file():jar.writestr(f.relative_to(wide(out/'classes')).as_posix(),f.read_bytes())
print((result.stdout+result.stderr).decode('utf8',errors='replace')[-24000:]);sys.exit(result.returncode)
