from pathlib import Path
import hashlib,importlib.util,json,os,subprocess,sys,tempfile
sys.dont_write_bytecode=True
sys.stdout.reconfigure(encoding='utf8')
P=Path(__file__).resolve().parent;M=P.parents[2];C=M.parent/'BiliPai-v023';S=M/'desktop/.local/stable-product-snapshot-84'
def wide(p):
 s=os.path.abspath(p);prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix)else prefix+s)
def data(p):return wide(p).read_bytes()
def sha(p):return hashlib.sha256(data(p)).hexdigest()
def write(p,b):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(b.encode()if isinstance(b,str)else b)
def dump(p,v):write(p,json.dumps(v,indent=2)+'\n')
assert sha(S/'manifest.json')=='f70a306e3dc42df93c00d536163ff4a1f1953cbc94d36586c9500281983351b2'
assert sha(S/'ordered-runtime-cp.json')=='4b1de9cce18526162113e1336c0252b37b3113d1f0b8832b68953fc3ec5df8d9'
cp=json.loads(data(S/'ordered-runtime-cp.json'));assert len(cp)==101
res=C/'desktop/resources/common';native=res/'native/windows-x64/libmpv-2.dll';share=res/'native/windows-x64/bilipai-diagnostic-share.dll'
clip=M/'desktop/.local/stable-full-video-root-external-actual-proof83/local-fixture-media.mp4'
assets=[dict(path=str(p),sha256Bytes=sha(p))for p in(native,share,clip)]
assert sha(share)=='22b3636176561782b055345f3c663b5674247bd9cda37f5d94945886f60dd7d3'
def check():
 for row in cp+assets:assert sha(row['path'])==row['sha256Bytes'],row['path']
check();out=P/'native'/sys.argv[1];out.mkdir(parents=True,exist_ok=False)
spec=importlib.util.spec_from_file_location('c',M/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
sources=list((P/'prepared/desktop/src/main/kotlin').rglob('*.kt'))+list((P/'generated04').rglob('*.kt'))+[P/'StorageNativeOwnerFixture.kt']
pins=dict(actual84Manifest=sha(S/'manifest.json'),orderedCP=sha(S/'ordered-runtime-cp.json'),runtimeEntries=101,nativeAndClip=assets,sourceInputs=[dict(path=str(p),sha256Bytes=sha(p))for p in sources],candidateWritten=False)
dump(out/'pins-before.json',pins)
classpath=[Path(r['path'])for r in cp]
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows','-Xplugin='+str(c.PLUGIN),'-Xplugin='+str(c.jar('org.jetbrains.kotlin','kotlin-serialization-compiler-plugin-embeddable','2.4.0')),'-Xfriend-paths='+','.join(map(str,[Path(cp[1]['path'])])),'-cp',';'.join(map(str,classpath)),'-d',str(out/'prospective.jar')]+list(map(str,sources))
write(out/'compile.args','\n'.join('"'+a.replace('\\','/')+'"'for a in args))
r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Xmx4g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compile.args')],capture_output=True,timeout=240)
write(out/'compile.log',r.stdout+r.stderr);check();dump(out/'compile-result.json',dict(passed=r.returncode==0,exitCode=r.returncode,fullShellCompiled=True,inputs=len(sources),runtimeEntries=101))
print((r.stdout+r.stderr).decode('utf8',errors='replace'));r.check_returncode()
with tempfile.TemporaryDirectory(prefix='bp-storage-native-')as owned:
 root=Path(owned).resolve();assert root.is_relative_to(Path(tempfile.gettempdir()).resolve())and root.name.startswith('bp-storage-native-')
 write(root/'local-media.mp4',data(clip));assert sha(root/'local-media.mp4')==sha(clip)
 env=os.environ.copy();local=root/'local-appdata';local.mkdir();env['LOCALAPPDATA']=str(local)
 command=[str(c.JAVA),'-Dfile.encoding=UTF-8','-Djava.io.tmpdir='+owned,'-Dcompose.application.resources.dir='+str(res),'-Dbp.fixture.native='+str(share),'-cp',str(out/'prospective.jar')+';'+';'.join(map(str,classpath)),'com.bilipai.desktop.settings.StorageNativeOwnerFixtureKt',owned,str(out/'fixture-result.json')]
 dump(out/'runtime-command.json',dict(command=command,isolatedLocalAppData=str(local),hiddenOwnedCanvas=True))
 try:
  r=subprocess.run(command,capture_output=True,timeout=100,env=env)
  write(out/'runtime.log',r.stdout+r.stderr)
  dump(out/'runtime-receipt.json',dict(passed=r.returncode==0,exitCode=r.returncode,ownedTemp=owned,cleanupRestrictedToResolvedOwnedTemp=True,actual84CPUnchanged=True,nativeUnchanged=True,productionInstalled=False,RootMounted=False,systemReceiverPassed=False,longPathSupported=False,detachTimeoutBoundaryPassed=False))
  print((r.stdout+r.stderr).decode('utf8',errors='replace'))
 finally:check();dump(out/'pins-after.json',pins)
 r.check_returncode()
