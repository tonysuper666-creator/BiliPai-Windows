from pathlib import Path
import hashlib,json,os,subprocess,sys,tempfile
P=Path(__file__).resolve().parent;M=P.parents[2];S=M/'desktop/.local/stable-product-snapshot-85';C=M.parent/'BiliPai-v023'
def wide(p):return Path('\\\\?\\'+os.path.abspath(p))
def read(p):return wide(p).read_bytes()
def sha(p):return hashlib.sha256(read(p)).hexdigest()
def dump(p,v):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_text(json.dumps(v,indent=2)+'\n',encoding='utf8',newline='\n')
assert sha(S/'manifest.json')=='417663152a99ecf23e6887e2940b15c7c1cfd06c6c3f0b2b1925430ed4ca4012'
assert sha(S/'ordered-runtime-cp.json')=='6398174d444cf02365bc66480c1c37bb00800a520e681c4649100067af68b419'
cp=json.loads(read(S/'ordered-runtime-cp.json'));assert len(cp)==101
for row in cp:assert sha(row['path'])==row['sha256Bytes']
attempt=P/'runtime'/sys.argv[1];attempt.mkdir(parents=True,exist_ok=False)
jar=P/'compile'/sys.argv[2]/'prospective.jar';assert json.loads(read(jar.parent/'result.json'))['passed']
pins=json.loads(read(jar.parent/'pins-before.json'));assert pins==json.loads(read(jar.parent/'pins-after.json'))
for row in pins['sourceInputs']:assert sha(row['path'])==row['sha256Bytes']
codeInputs=dict(runtimeJar=dict(path=str(jar),sha256Bytes=sha(jar)),compileResult=dict(path=str(jar.parent/'result.json'),sha256Bytes=sha(jar.parent/'result.json')),sourcePinsVerified=True,snapshot85Manifest=sha(S/'manifest.json'),ordered101CP=sha(S/'ordered-runtime-cp.json'))
java=M.parent/'toolchain/jdk/jdk-21.0.12.1+1/bin/java.exe'
with tempfile.TemporaryDirectory(prefix='bp-search-owner-fixture-')as owned:
 root=Path(owned).resolve();assert root.is_relative_to(Path(tempfile.gettempdir()).resolve())and root.name.startswith('bp-search-owner-fixture-')
 args=[str(java),'-Dfile.encoding=UTF-8','-Djava.io.tmpdir='+owned,'-Dcompose.application.resources.dir='+str(C/'desktop/build/processedResources/jvm/main'),'-cp',str(jar)+';'+';'.join(row['path']for row in cp),'com.bilipai.desktop.ui.SearchOwnerFixtureKt',str(attempt),owned]
 dump(attempt/'inputs.json',dict(codeInputs,ownedTemp=owned,argv=args))
 r=subprocess.run(args,capture_output=True,timeout=180)
 wide(attempt/'run.log').write_bytes(r.stdout+r.stderr)
 dump(attempt/'receipt.json',dict(codeInputs,passed=r.returncode==0,exitCode=r.returncode,ownedTemp=owned,onlyOwnedTempDisposed=True,RootMounted=False,nativePlayer=False,userAccount=False,businessNetwork=False))
for row in cp:assert sha(row['path'])==row['sha256Bytes']
sys.stdout.reconfigure(encoding='utf8');print((r.stdout+r.stderr).decode('utf8',errors='replace')[-10000:]);sys.exit(r.returncode)
