from pathlib import Path
import hashlib, importlib.util, json, subprocess, sys, zipfile
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2]
SNAP=MAIN/'desktop/.local/stable-product-snapshot-39'
def safe(path):
 value=str(Path(path).absolute());return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(path):return hashlib.sha256(safe(path).read_bytes()).hexdigest()
def save(path,value):
 safe(path.parent).mkdir(parents=True,exist_ok=True);safe(path).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
def main():
 assert sha(SNAP/'manifest.json')=='4840845d18a6fd1171c32a03ba397306a0f509b6510ca9544ceac935befe46ae'
 assert sha(SNAP/'ordered-runtime-cp.json')=='e9b8db57c428c214b243e2ea41761d0745c3b48ff386fe8d38901d892760fd8f'
 cp=json.loads((SNAP/'ordered-runtime-cp.json').read_text(encoding='utf-8'));assert len(cp)==97
 for item in cp:assert sha(item['path'])==item['sha256Bytes'],item['path']
 spec=importlib.util.spec_from_file_location('compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
 out=HERE/'runs'/sys.argv[1];assert not out.exists();out.mkdir(parents=True)
 sources=[]
 for path in sorted(list((HERE/'prepared').rglob('*.kt'))+list((HERE/'reference-only').rglob('*.kt'))):
  target=out/'source-inputs'/path.relative_to(HERE);safe(target.parent).mkdir(parents=True,exist_ok=True);safe(target).write_bytes(safe(path).read_bytes());sources.append(target)
 pins=dict(runtime=cp,toolchain=[dict(path=str(path),sha256Bytes=sha(path)) for path in [c.JAVA,c.PLUGIN]+c.COMPILER],sources=[dict(path=str(path),sha256Bytes=sha(path)) for path in sources])
 save(out/'dependency-pins-before.json',pins)
 target=out/'prepared-live-navigation.jar'
 args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(item['path'] for item in cp),'-Xplugin='+str(c.PLUGIN),'-Xfriend-paths='+str(SNAP/'main-kotlin.jar'),'-module-name','prepared_live_navigation','-d',str(target)]+list(map(str,sources))
 safe(out/'compiler.args').write_text('\n'.join('"'+str(arg).replace('\\','/')+'"' for arg in args)+'\n',encoding='utf-8',newline='\n')
 run=subprocess.run([str(c.JAVA),'-Xmx3g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')],capture_output=True,text=True,encoding='utf-8',timeout=180)
 safe(out/'compiler.log').write_text(run.stdout+run.stderr,encoding='utf-8',newline='\n')
 value=dict(status='PASS' if run.returncode==0 else 'FAIL',exitCode=run.returncode,sourceOnly=True,actualRuntimeAcceptance=False,nativeOrAnimationExecuted=False,HTTP=False,sourceInputs=pins['sources'],snapshotManifestSha256Bytes=sha(SNAP/'manifest.json'),orderedCpSha256Bytes=sha(SNAP/'ordered-runtime-cp.json'),runtimeEntries=97)
 if run.returncode:save(out/'compile-result.json',value);print(run.stdout+run.stderr);return run.returncode
 with zipfile.ZipFile(target) as jar:own={name for name in jar.namelist() if name.endswith('.class')}
 actual=set()
 for item in cp:
  with zipfile.ZipFile(safe(item['path'])) as jar:actual.update(name for name in jar.namelist() if name.endswith('.class'))
 overlap=sorted(own&actual);assert not overlap,overlap
 value.update(candidateJarSha256Bytes=sha(target),classCount=len(own),productionClassIntersection=overlap,classes=sorted(own))
 save(out/'compile-result.json',value)
 for item in cp:assert sha(item['path'])==item['sha256Bytes'],item['path']
 assert pins['toolchain']==[dict(path=str(path),sha256Bytes=sha(path)) for path in [c.JAVA,c.PLUGIN]+c.COMPILER]
 save(out/'dependency-pins-after.json',pins)
 print(json.dumps({k:v for k,v in value.items() if k not in ['sourceInputs','classes']},indent=2));return 0
if __name__=='__main__':raise SystemExit(main())
