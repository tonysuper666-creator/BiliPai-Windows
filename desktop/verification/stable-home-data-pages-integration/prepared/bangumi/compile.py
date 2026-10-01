from pathlib import Path
import json,hashlib,subprocess,importlib.util,sys
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];PREFIX='\\\\?\\'
def wide(p):
    s=str(Path(p).absolute());return Path(s if s.startswith(PREFIX)else PREFIX+s)
snap=MAIN/'desktop/.local/stable-product-snapshot-36'
cp=json.loads((snap/'ordered-runtime-cp.json').read_text(encoding='utf-8'))
for r in cp:assert hashlib.sha256(wide(r['path']).read_bytes()).hexdigest()==r['sha256Bytes']
vm=MAIN/'desktop/.local/stable-home-viewmodel-parity/classes-vm-06'
sources=list((HERE/'generated').rglob('*.kt'))+list((HERE/'prepared/manual').rglob('*.kt'))+list((HERE/'reference-only').rglob('*.kt'))
run=HERE/f'compile-{1+len(list(HERE.glob("compile-??"))):02}';run.mkdir()
spec=importlib.util.spec_from_file_location('cc',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');cc=importlib.util.module_from_spec(spec);spec.loader.exec_module(cc)
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','original_bangumi_hub',
    '-Xfriend-paths='+','.join([str(snap/'main-kotlin.jar'),str(vm)]),
    '-Xplugin='+str(cc.PLUGIN),'-cp',';'.join([str(vm)]+[r['path']for r in cp]),'-d',str(run/'classes'),*map(str,sources)]
(run/'compiler.args').write_text('\n'.join('"'+a.replace('\\','/')+'"'for a in args),encoding='utf-8')
r=subprocess.run([str(cc.JAVA),'-Dfile.encoding=UTF-8','-Xmx2g','-cp',';'.join(map(str,cc.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(run/'compiler.args')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=180)
(run/'compiler.log').write_text(r.stdout+r.stderr,encoding='utf-8')
(run/'result.json').write_text(json.dumps(dict(exitCode=r.returncode,sourceCount=len(sources),sourceOnly=True,actualMainInstalled=False,homeVmPreparedClassesExplicit=True),indent=2)+'\n',encoding='utf-8')
print(r.stdout+r.stderr);sys.exit(r.returncode)

