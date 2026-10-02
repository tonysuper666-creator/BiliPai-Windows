from pathlib import Path
import hashlib,importlib.util,json,os,subprocess,sys,tempfile,zipfile
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf8')
N=Path(__file__).resolve().parent;M=N.parents[2];P=M/'desktop/.local/stable-settings-storage-owner-parity';C=M.parent/'BiliPai-v023';S=M/'desktop/.local/stable-product-snapshot-84'
def wide(p):return Path('\\\\?\\'+os.path.abspath(p))
def read(p):return wide(p).read_bytes()
def sha(p):return hashlib.sha256(read(p)).hexdigest()
def write(p,b):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(b.encode('utf8')if isinstance(b,str)else b)
def dump(p,v):write(p,json.dumps(v,indent=2)+'\n')
cp=json.loads(read(S/'ordered-runtime-cp.json'));assert len(cp)==101
testOwners=P/'native/01/prospective.jar';ownerPin=json.loads(read(P/'evidence-scope.json'))['nativeProspectiveJarSHA'];assert sha(testOwners)==ownerPin
pins=json.loads(read(P/'native/01/pins-before.json'));assets=pins['nativeAndClip'];source=N/'StorageNativeOwnerFixture.kt'
def check():
 assert sha(S/'ordered-runtime-cp.json')=='4b1de9cce18526162113e1336c0252b37b3113d1f0b8832b68953fc3ec5df8d9'
 assert sha(testOwners)==ownerPin
 for row in cp+assets:assert sha(row['path'])==row['sha256Bytes']
check();out=N/'native-corrected01';out.mkdir(exist_ok=False)
spec=importlib.util.spec_from_file_location('c',M/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
classpath=[str(testOwners)]+[r['path']for r in cp];jar=out/'fixture.jar'
compileArgs=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','storage_native_corrected_fixture','-Xfriend-paths='+str(testOwners)+','+cp[1]['path'],'-cp',';'.join(classpath),'-d',str(jar),str(source)]
write(out/'compile.args','\n'.join('"'+a.replace('\\','/')+'"'for a in compileArgs))
r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Xmx2g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compile.args')],capture_output=True,timeout=120)
write(out/'compile.log',r.stdout+r.stderr);dump(out/'compile-result.json',dict(passed=r.returncode==0,exitCode=r.returncode,fixtureOnly=True,sourceSHA=sha(source),testedOwnersSHA=ownerPin));r.check_returncode()
with zipfile.ZipFile(wide(jar))as z:
 names=[p for p in z.namelist()if p.endswith('.class')]
 assert all(p.startswith('com/bilipai/desktop/settings/StorageNativeOwnerFixture')for p in names),names
dump(out/'pins-before.json',dict(testedOwnersSHA=ownerPin,fixtureSourceSHA=sha(source),fixtureJarSHA=sha(jar),actual84CP101=sha(S/'ordered-runtime-cp.json'),assets=assets,productionClassesInFixture=0))
with tempfile.TemporaryDirectory(prefix='bp-storage-native-')as owned:
 root=Path(owned).resolve();assert root.is_relative_to(Path(tempfile.gettempdir()).resolve())and root.name.startswith('bp-storage-native-')
 clip=Path(assets[2]['path']);write(root/'local-media.mp4',read(clip));assert sha(root/'local-media.mp4')==sha(clip)
 local=root/'local-appdata';local.mkdir();env=os.environ.copy();env['LOCALAPPDATA']=str(local)
 command=[str(c.JAVA),'-Dfile.encoding=UTF-8','-Djava.io.tmpdir='+owned,'-Dcompose.application.resources.dir='+str(C/'desktop/resources/common'),'-Dbp.fixture.native='+assets[1]['path'],'-cp',str(jar)+';'+';'.join(classpath),'com.bilipai.desktop.settings.StorageNativeOwnerFixtureKt',owned,str(out/'fixture-result.json')]
 dump(out/'runtime-command.json',dict(command=command,isolatedLocalAppData=str(local),ownedHiddenCanvas=True))
 r=subprocess.run(command,capture_output=True,timeout=100,env=env);write(out/'runtime.log',r.stdout+r.stderr)
 dump(out/'runtime-receipt.json',dict(passed=r.returncode==0,exitCode=r.returncode,fixtureOnly=True,existingSRTInputVerified=True,productionClassesInFixture=0,
     ownersByteIdenticalToNative01=True,ownedTemp=owned,cleanupRestrictedToResolvedOwnedTemp=True,RootMounted=False,systemReceiver=False,longPathSupported=False))
 print((r.stdout+r.stderr).decode('utf8',errors='replace'));check();r.check_returncode()
dump(out/'pins-after.json',json.loads(read(out/'pins-before.json')))
