from pathlib import Path
import hashlib, importlib.util, json, subprocess, sys, zipfile
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
MAIN=HERE.parents[2]
SNAP=MAIN/'desktop/.local/stable-product-snapshot-30'
HOME=MAIN/'desktop/.local/stable-home-page-parity/classes-full-07'
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,s):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
def save(p,o):write(p,json.dumps(o,ensure_ascii=False,indent=2)+'\n')
def main():
 assert sha(SNAP/'manifest.json')=='e69b7b08805fc7a6aaec8557e8ba1cfd2368b701f99ecb94edc672efd22b6b69'
 assert sha(SNAP/'ordered-runtime-cp.json')=='dcab5cb81f9c62929c07966f76fe3bfd54ee0e7b93614296c576b24f218071c0'
 cp=json.loads((SNAP/'ordered-runtime-cp.json').read_text(encoding='utf-8'))
 assert len(cp)==92
 for x in cp:assert sha(x['path'])==x['sha256Bytes'],x['path']
 out=HERE/'runs'/sys.argv[1];assert not out.exists();out.mkdir(parents=True)
 spec=importlib.util.spec_from_file_location('compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
 sources=sorted((HERE/'prepared').rglob('*.kt'))
 copied=[]
 for source in sources:
  target=out/'source-inputs'/source.relative_to(HERE/'prepared');safe(target.parent).mkdir(parents=True,exist_ok=True);safe(target).write_bytes(source.read_bytes());copied.append(target)
 homeclasses=[dict(path=str(p.relative_to(HOME)),sha256Bytes=sha(p)) for p in sorted(HOME.rglob('*.class'))]
 save(out/'dependency-pins-before.json',dict(runtime=cp,preparedHomeClassOverlay=homeclasses))
 target=out/'prepared-home-platform.jar'
 args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',str(HOME)+';'+';'.join(x['path'] for x in cp),'-Xplugin='+str(c.PLUGIN),'-Xfriend-paths='+str(SNAP/'main-kotlin.jar')+','+str(HOME),'-module-name','prepared_home_platform_media','-d',str(target)]+list(map(str,copied))
 write(out/'compiler.args','\n'.join('"'+str(a).replace('\\','/')+'"' for a in args)+'\n')
 r=subprocess.run([str(c.JAVA),'-Xmx3g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')],capture_output=True,text=True,encoding='utf-8',timeout=180)
 write(out/'compiler.log',r.stdout+r.stderr)
 result=dict(status='PASS' if r.returncode==0 else 'FAIL',exitCode=r.returncode,sourceInputs=[dict(path=str(p),sha256Bytes=sha(p)) for p in copied],actualMain30Manifest=sha(SNAP/'manifest.json'),explicitPreparedHomeClassOverlay=str(HOME),scope='SOURCE-ONLY candidate compile, no MPV/native/window/playback execution or installed runtime acceptance')
 if r.returncode:save(out/'compile-result.json',result);print(r.stdout+r.stderr);return r.returncode
 with zipfile.ZipFile(target) as z:own={n for n in z.namelist() if n.endswith('.class')}
 actual=set()
 for x in cp[:3]:
  with zipfile.ZipFile(safe(x['path'])) as z:actual.update(n for n in z.namelist() if n.endswith('.class'))
 overlaps=sorted(own&actual)
 allowed=['MpvPlayer','MpvNative','MpvNodes','MpvCallException']
 assert all(any(n=='com/bilipai/desktop/player/'+a+'.class' or n.startswith('com/bilipai/desktop/player/'+a+'$') or n=='com/bilipai/desktop/player/'+a+'Kt.class' for a in allowed) for n in overlaps),overlaps
 result.update(jarSha256Bytes=sha(target),classCount=len(own),declaredProductionClassOverrides=overlaps,newClasses=sorted(own-actual))
 save(out/'compile-result.json',result)
 for x in cp:assert sha(x['path'])==x['sha256Bytes'],x['path']
 assert homeclasses==[dict(path=str(p.relative_to(HOME)),sha256Bytes=sha(p)) for p in sorted(HOME.rglob('*.class'))]
 save(out/'dependency-pins-after.json',dict(runtime=cp,preparedHomeClassOverlay=homeclasses))
 print(json.dumps({k:v for k,v in result.items() if k not in ['sourceInputs','newClasses','declaredProductionClassOverrides']},indent=2));return 0
if __name__=='__main__':raise SystemExit(main())
