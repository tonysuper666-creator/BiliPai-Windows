from pathlib import Path
import hashlib, importlib.util, json, os, subprocess, sys, zipfile
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf8')
P=Path(__file__).resolve().parent;M=P.parents[2]
def wide(p):
 s=os.path.abspath(str(p));return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def sha(p):return hashlib.sha256(wide(p).read_bytes()).hexdigest()
S=M/'desktop/.local/stable-product-snapshot-88';cp=json.loads(wide(S/'ordered-runtime-cp.json').read_text())
def pins():
 assert sha(S/'manifest.json')=='fb097cce71237ce93b6220940a5e930c9672239511f3937d037ebc05af0939d2'
 assert sha(S/'ordered-runtime-cp.json')=='0252dc7a049e87084a0510bf406808188648f759f977cfd453f0803ed7e1a62c'
 assert len(cp)==105
 for r in cp:assert sha(r['path'])==r['sha256Bytes'],r['path']
pins()
spec=importlib.util.spec_from_file_location('compiler',M/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
sources={}
for root in (P/'generated',P/'prepared'):
 for f in wide(root).rglob('*.kt'):
  assert f.name not in sources,f.name;sources[f.name]=f
f=wide(P/'generated-video/com/android/purebilibili/feature/video/viewmodel/VideoPlaybackViewModel.kt');sources[f.name]=f
number=sys.argv[1];out=P/'runs'/number;wide(out).mkdir(parents=True,exist_ok=False)
main=next(r['path']for r in cp if r.get('source')=='desktop/build/classes/kotlin/main')
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows','-Xplugin='+str(c.PLUGIN),'-Xfriend-paths='+main,'-cp',';'.join(r['path']for r in cp),'-d',str(out/'classes')]+[str(f)for f in sources.values()]
wide(out/'compiler.args').write_text('\n'.join('"'+a.replace('\\','/')+'"'for a in args),encoding='utf8')
r=subprocess.run([str(c.JAVA),'-Xmx5g','-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')],capture_output=True,timeout=360)
wide(out/'compile.log').write_bytes(r.stdout+r.stderr);pins()
evidence=dict(passed=r.returncode==0,entries=105,snapshot=88,sourceCount=len(sources),
 explicitProspectiveSources=[dict(path=str(f),sha256Bytes=sha(f))for f in sources.values()],
 intentionalExistingSourceOverlays=['DesktopOriginalVideoRepositoryBinding.kt','DesktopOriginalPortraitPlatformBinding.kt',
 'DesktopOriginalVideoRootFactory.kt','DesktopOriginalVideoRootAssembler.kt','DesktopOriginalVideoRootTransport.kt',
 'DesktopOriginalVideoNativeOwner.kt','VideoPlaybackViewModel.kt'],
 productionWrites=0,fullScreenPrepared=False,rootRuntimeAccepted=False,nativeRuntimeAccepted=False,normalProductCompile=False)
if r.returncode==0:
 with zipfile.ZipFile(wide(out/'prospective.jar'),'w',zipfile.ZIP_DEFLATED)as z:
  for f in wide(out/'classes').rglob('*'):
   if f.is_file():z.writestr(f.relative_to(wide(out/'classes')).as_posix(),f.read_bytes())
 with zipfile.ZipFile(wide(main))as z: actual=set(z.namelist())
 with zipfile.ZipFile(wide(out/'prospective.jar'))as z: prospective=set(z.namelist())
 evidence['actualClassFqnIntersections']=sorted(x for x in actual & prospective if x.endswith('.class'))
wide(out/'result.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2),encoding='utf8')
print((r.stdout+r.stderr).decode('utf8',errors='replace')[-16000:]);print('PASS'if r.returncode==0 else'FAIL',len(sources),'inputs;',len(evidence.get('actualClassFqnIntersections',[])),'actual overlay class entries');sys.exit(r.returncode)
