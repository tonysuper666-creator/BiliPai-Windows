from pathlib import Path
import hashlib,importlib.util,json,os,subprocess,sys,tempfile
P=Path(__file__).resolve().parent; M=P.parents[2]; S=M/'desktop/.local/stable-product-snapshot-83'
def wide(p):
 s=os.path.abspath(p);prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix)else prefix+s)
def data(p):return wide(p).read_bytes()
def sha(p):return hashlib.sha256(data(p)).hexdigest()
def write(p,b):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(b.encode()if isinstance(b,str)else b)
def dump(p,v):write(p,json.dumps(v,indent=2)+'\n')
assert sha(S/'manifest.json')=='72934edb925c7793294bf504388dfbd1f849fe14d8ea00dd4f333befdabd4157'
assert sha(S/'ordered-runtime-cp.json')=='f82828347f7cfad86cd101dfb13d44677ba88f780b21a9e21df8c06a97d4828b'
cp=json.loads(data(S/'ordered-runtime-cp.json'));assert len(cp)==101
def check():
 for r in cp:assert sha(r['path'])==r['sha256Bytes']
check();out=P/'compile'/sys.argv[1];out.mkdir(parents=True,exist_ok=False)
spec=importlib.util.spec_from_file_location('c',M/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
sources=list((P/'prepared/desktop/src/main/kotlin').rglob('*.kt'))+list((P/'prepared/desktop/build/native-diagnostic-share/kotlin').rglob('*.kt'))
if len(sys.argv)>2:sources+=[P/'ImagePreviewShareFixture.kt']
pins=dict(actual83Manifest=sha(S/'manifest.json'),orderedCP=sha(S/'ordered-runtime-cp.json'),sourceInputs=[dict(path=str(p),sha256Bytes=sha(p))for p in sources],candidateWritten=False)
dump(out/'pins-before.json',pins)
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows','-Xplugin='+str(c.PLUGIN),'-Xplugin='+str(c.jar('org.jetbrains.kotlin','kotlin-serialization-compiler-plugin-embeddable','2.4.0')),'-Xfriend-paths='+cp[1]['path'],'-cp',';'.join(r['path']for r in cp),'-d',str(out/'prospective.jar')]+list(map(str,sources))
write(out/'compile.args','\n'.join('"'+a.replace('\\','/')+'"'for a in args))
r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Xmx4g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compile.args')],capture_output=True,timeout=240)
write(out/'compile.log',r.stdout+r.stderr);check();dump(out/'pins-after.json',pins)
dump(out/'result.json',dict(passed=r.returncode==0,exitCode=r.returncode,inputs=len(sources),actual83Runtime101=True,RootMounted=False,native=False,HTTP=False))
sys.stdout.reconfigure(encoding='utf8');print((r.stdout+r.stderr).decode('utf8',errors='replace'));r.check_returncode()
if len(sys.argv)>2:
 with tempfile.TemporaryDirectory(prefix='bp-image-share-fixture-') as owned:
  ownedPath=Path(owned).resolve();assert ownedPath.is_relative_to(Path(tempfile.gettempdir()).resolve()) and ownedPath.name.startswith('bp-image-share-fixture-')
  r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Dbp.fixture.native='+str(P/'prepared/desktop/resources/common/native/windows-x64/bilipai-diagnostic-share.dll'),'-cp',str(out/'prospective.jar')+';'+';'.join(row['path']for row in cp),'com.bilipai.desktop.ui.ImagePreviewShareFixtureKt',owned,str(out/'fixture-work')],capture_output=True,timeout=120)
  dump(out/'fixture-receipt.json',dict(passed=r.returncode==0,exitCode=r.returncode,ownedTemp=owned,cleanupRestrictedToResolvedOwnedTemp=True,actual83CPUnchanged=True,nativeDLL=sha(P/'prepared/desktop/resources/common/native/windows-x64/bilipai-diagnostic-share.dll'),ShareUI=False,externalReceiver=False,longPathSupported=False,productionClearWired=False))
 write(out/'fixture.log',r.stdout+r.stderr);print((r.stdout+r.stderr).decode('utf8',errors='replace'));r.check_returncode()
