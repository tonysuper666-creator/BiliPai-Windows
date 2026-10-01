from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys,zipfile
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];SNAP=MAIN/'desktop/.local/stable-product-snapshot-43'
def safe(p):return Path('\\\\?\\'+str(Path(p).absolute()))
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def save(p,v):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
def main():
 assert sha(SNAP/'manifest.json')=='6252d7e16a2f413d48de0265dc7debb315b2eaefca0b58cde0d34d9258feb362'
 assert sha(SNAP/'ordered-runtime-cp.json')=='0785ee7cbe88b2615d9193882310a1fe36c368c71f9180b2a418a17136ab8440'
 cp=json.loads((SNAP/'ordered-runtime-cp.json').read_text());assert len(cp)==97
 for row in cp:assert sha(row['path'])==row['sha256Bytes']
 spec=importlib.util.spec_from_file_location('compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
 serial=c.jar('org.jetbrains.kotlin','kotlin-serialization-compiler-plugin-embeddable','2.4.0')
 out=HERE/'runs'/sys.argv[1];assert not out.exists();out.mkdir(parents=True)
 sources=[]
 for p in sorted(list((HERE/'prepared').rglob('*.kt'))+list((HERE/'proof-only').rglob('*.kt'))):
  target=out/'source-inputs'/p.relative_to(HERE);safe(target.parent).mkdir(parents=True,exist_ok=True);safe(target).write_bytes(safe(p).read_bytes());sources.append(target)
 java_sources=[]
 for p in sorted(list((HERE/'prepared').rglob('*.java'))+list((HERE/'proof-only').rglob('*.java'))):
  target=out/'source-inputs'/p.relative_to(HERE);safe(target.parent).mkdir(parents=True,exist_ok=True);safe(target).write_bytes(safe(p).read_bytes());java_sources.append(target)
 java_out=out/'java-classes';java_out.mkdir()
 javac=c.JAVA.with_name('javac.exe')
 tools=[c.JAVA,javac,serial,c.PLUGIN]+c.COMPILER
 pins=dict(runtime=cp,tools=[dict(path=str(p),sha256Bytes=sha(p)) for p in tools],sources=[dict(path=str(p),language='Java' if p.suffix=='.java' else 'Kotlin',sha256Bytes=sha(p)) for p in sources+java_sources]);save(out/'pins-before.json',pins)
 java_run=subprocess.run([str(javac),'--release','21','-cp',str(java_out)+';'+';'.join(row['path'] for row in cp),'-d',str(java_out)]+list(map(str,java_sources)),capture_output=True,text=True,encoding='utf-8',timeout=90)
 safe(out/'javac.log').write_text(java_run.stdout+java_run.stderr,encoding='utf-8')
 if java_run.returncode:print(java_run.stdout+java_run.stderr);return java_run.returncode
 target=out/'prepared-playback-final-publication.jar'
 args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',str(java_out)+';'+';'.join(row['path'] for row in cp),'-Xplugin='+str(serial),'-Xplugin='+str(c.PLUGIN),'-Xfriend-paths='+str(SNAP/'main-kotlin.jar'),'-module-name','com.bilipai.desktop_bilipai-windows','-d',str(target)]+list(map(str,sources))
 safe(out/'compiler.args').write_text('\n'.join('"'+str(a).replace('\\','/')+'"' for a in args)+'\n',encoding='utf-8',newline='\n')
 run=subprocess.run([str(c.JAVA),'-Xmx3g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')],capture_output=True,text=True,encoding='utf-8',timeout=180)
 safe(out/'compiler.log').write_text(run.stdout+run.stderr,encoding='utf-8',newline='\n')
 result=dict(status='PASS' if run.returncode==0 else 'FAIL',exitCode=run.returncode,sourceInputs=pins['sources'],runtimeEntries=97,snapshotManifest=sha(SNAP/'manifest.json'),CP=sha(SNAP/'ordered-runtime-cp.json'),prospectiveExistingProductionOverrides=True,actualRuntimeAcceptance=False,HTTP=False,HWND=False)
 if not run.returncode:
  with zipfile.ZipFile(safe(target),'a') as jar:
   for p in java_out.rglob('*.class'):jar.write(safe(p),p.relative_to(java_out).as_posix())
  with zipfile.ZipFile(safe(target)) as jar:own={n for n in jar.namelist() if n.endswith('.class')};bad=[n for n in own if b'NON_LOCAL_RETURN' in jar.read(n)]
  actual=set()
  for row in cp:
   with zipfile.ZipFile(safe(row['path'])) as jar:actual.update(n for n in jar.namelist() if n.endswith('.class'))
  result.update(jarSha256=sha(target),classes=sorted(own),productionClassIntersections=sorted(own&actual),illegalMarkerClasses=bad);assert not bad
  for row in cp:assert sha(row['path'])==row['sha256Bytes']
  for row in pins['tools']:assert sha(row['path'])==row['sha256Bytes']
  for row in pins['sources']:assert sha(row['path'])==row['sha256Bytes']
  save(out/'pins-after.json',pins)
 save(out/'compile-result.json',result)
 print(run.stdout+run.stderr if run.returncode else json.dumps({k:v for k,v in result.items() if k not in {'classes','productionClassIntersections','sourceInputs'}},indent=2))
 return run.returncode
if __name__=='__main__':raise SystemExit(main())
