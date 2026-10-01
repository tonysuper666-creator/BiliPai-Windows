from pathlib import Path
import hashlib,json,importlib.util,subprocess,sys,zipfile
sys.dont_write_bytecode=True
P=Path(__file__).resolve().parent;MAIN=P.parents[2];S=MAIN/'desktop/.local/stable-product-snapshot-50'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def sha(p):return hashlib.sha256(wide(p).read_bytes()).hexdigest()
def save(p,v):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes((json.dumps(v,indent=2)+'\n').encode())
out=P/('fixture-runs/'+sys.argv[1]);wide(out).mkdir(parents=True,exist_ok=False)
cp=json.loads(wide(S/'ordered-runtime-cp.json').read_bytes());assert len(cp)==97
candidate=P/'compile-runs/02/candidate.jar';core=MAIN/'desktop/.local/stable-video-state-holder-parity/install-compile-01/candidate.jar'
bridge=MAIN/'desktop/.local/stable-video-repository-core-ports-parity/compile-runs/01/candidate.jar'
deps=[dict(path=str(candidate),sha256Bytes=sha(candidate)),dict(path=str(bridge),sha256Bytes=sha(bridge)),dict(path=str(core),sha256Bytes=sha(core))]
sources=sorted(wide(P/'fixture').glob('*.kt'))
spec=importlib.util.spec_from_file_location('cc',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
def pins():
 for r in cp+deps:assert sha(r['path'])==r['sha256Bytes']
 return dict(runtime=cp,explicitPreparedDependencies=deps,sources=[dict(path=str(p),sha256Bytes=sha(p))for p in sources])
save(out/'pins-before.json',pins());jar=out/'fixture.jar';runtime=[r['path']for r in deps+cp]
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows','-Xfriend-paths='+','.join([str(candidate),str(bridge),str(core),str(S/'main-kotlin.jar')]),'-cp',';'.join(runtime),'-d',str(jar)]+list(map(str,sources))
wide(out/'compile.args').write_bytes(('\n'.join('"'+a.replace('\\','/')+'"'for a in args)+'\n').encode())
r=subprocess.run([str(c.JAVA),'-Xmx2g','-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compile.args')],capture_output=True,timeout=120)
wide(out/'compile.log').write_bytes(r.stdout+r.stderr)
if r.returncode:print((r.stdout+r.stderr).decode('utf-8',errors='replace'));sys.exit(r.returncode)
with zipfile.ZipFile(wide(jar))as z:names=set(n for n in z.namelist()if n.endswith('.class'))
prod=set()
for row in deps+cp[:3]:
 with zipfile.ZipFile(wide(row['path']))as z:prod.update(z.namelist())
assert not names&prod,sorted(names&prod)
with wide(out/'run.log').open('wb')as log:
 p=subprocess.Popen([str(c.JAVA),'-Dfile.encoding=UTF-8','-cp',';'.join([str(jar)]+runtime),'com.bilipai.desktop.ui.OwnedTokenRefreshFixture',str(out/'result.json')],stdout=log,stderr=log)
 save(out/'own-process.json',dict(pid=p.pid,entry='OwnedTokenRefreshFixture'))
 try:code=p.wait(timeout=35)
 except subprocess.TimeoutExpired:
  diagnostic=subprocess.run([str(c.JAVA.parent/'jcmd.exe'),str(p.pid),'Thread.print'],capture_output=True,timeout=8)
  wide(out/'own-thread-diagnostic.log').write_bytes(diagnostic.stdout+diagnostic.stderr)
  p.kill();p.wait();code=124
save(out/'pins-after.json',pins());assert wide(out/'pins-before.json').read_bytes()==wide(out/'pins-after.json').read_bytes()
save(out/'runner-result.json',dict(compileExit=0,runExit=code,fixtureClassProductOverlap=[],fixtureJarSha256Bytes=sha(jar),explicitPreparedProductOverrides=['DesktopRepository','DesktopLoginRepository','bridge41 DesktopPlaybackCache dependency'],actual50Runtime=97,noExternalHTTP=True,memoryRetrofit=True,noNative=True,productAcceptance=False))
print(wide(out/'run.log').read_bytes().decode('utf-8',errors='replace')[:8000]);sys.exit(code)
