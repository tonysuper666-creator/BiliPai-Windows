from pathlib import Path
import hashlib,json,os,subprocess,sys,time,uuid,zipfile
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
phase=int(sys.argv[1]);snapshot=MAIN/f'desktop/.local/stable-product-snapshot-{phase}'
OUT=HERE/f'physical-root-startup-{phase}-attempt{sys.argv[4]}';assert not OUT.exists();OUT.mkdir()
sha=lambda b:hashlib.sha256(b).hexdigest()
def wide(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
assert sha((snapshot/'manifest.json').read_bytes())==sys.argv[2]
assert sha((snapshot/'ordered-runtime-cp.json').read_bytes())==sys.argv[3]
rows=json.loads((snapshot/'ordered-runtime-cp.json').read_bytes());assert len(rows)==97
def pins():
 for r in rows:assert sha(wide(r['path']).read_bytes())==r['sha256Bytes'],r['path']
pins()
jdk=MAIN.parent/'toolchain/jdk/jdk-21.0.12.1+1/bin';source=HERE/(sys.argv[5] if len(sys.argv)>5 else 'PhysicalRootStartupFixture.java')
exact=OUT/source.name;exact.write_bytes(source.read_bytes());classes=OUT/'fixture-classes';classes.mkdir()
productJar=snapshot/'main-kotlin.jar'
with (OUT/'compile.log').open('wb')as log:
 code=subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','-cp',str(productJar),'-d',str(classes),str(exact)],stdout=log,stderr=subprocess.STDOUT).returncode
assert code==0,'Fixture-only javac failed'
fixtureNames={p.relative_to(classes).as_posix()for p in classes.rglob('*.class')}
allProduct=set()
for r in rows:
 with zipfile.ZipFile(wide(r['path']))as jar:allProduct.update(n for n in jar.namelist()if n.endswith('.class'))
assert len(fixtureNames)==1 and not (fixtureNames&allProduct)
state=OUT/'private-user-data';state.mkdir();root=state/'BiliPai/updates';stage=root/('staged-physical-root-'+uuid.uuid4().hex);launch=stage/('launch-'+str(uuid.uuid4()));wide(launch).mkdir(parents=True)
health=launch/'startup-health.txt';token=str(uuid.uuid4())
resourceDir=REPO/'desktop/resources/common'
assets={}
for name in ['libmpv-2.dll','ffmpeg.exe','ffprobe.exe','bilipai-diagnostic-share.dll']:
 p=resourceDir/'native/windows-x64'/name;assert p.is_file();assets[name]=sha(p.read_bytes())
assert assets['libmpv-2.dll']=='673e6397920ab64a9c5b3a618f7f16d38854efe72b58665f1f84e4e873b763a4'
assert assets['ffmpeg.exe']=='9c60da6c0b083110d59084ea39f60ae149aa3e031c3b4bb4f573fafa1c1e7cea'
cp=';'.join([classes.as_posix()]+[str(r['path']).replace('\\','/')for r in rows])
args=['-Dfile.encoding=UTF-8','-Dcompose.application.resources.dir='+resourceDir.as_posix(),'-cp',cp,source.stem,health.as_posix(),token,OUT.as_posix()]
argfile=OUT/'launch.args';argfile.write_text('\n'.join(json.dumps(a,ensure_ascii=False)for a in args)+'\n',encoding='utf-8')
environment=os.environ.copy();environment['LOCALAPPDATA']=str(state);environment['PYTHONUTF8']='1'
report=dict(passed=False,actualProductSnapshot=phase,actualRuntimeEntries=97,productionOverrides=0,fixtureProductClassIntersections=[],fixtureClasses=1,isolatedUserData=True,unchangedProductMain=True,physicalWindow=True,externalGuestHttpPossible=True,realAccountUsed=False,nativeAssets=assets,fullRouteOrPlayerAcceptance=False,newExeDeployed=False)
with (OUT/'stdout.raw.log').open('wb')as out,(OUT/'stderr.raw.log').open('wb')as err:
 process=subprocess.Popen([str(jdk/'java.exe'),'@'+str(argfile)],cwd=REPO,env=environment,stdout=out,stderr=err,creationflags=subprocess.CREATE_NO_WINDOW)
 try:
  exitCode=process.wait(timeout=65)
  report['processExitCode']=exitCode
  assert exitCode==0,'Product fixture exited '+str(exitCode)+'; inspect preserved stderr.raw.log'
  observed=json.loads((OUT/'physical-window.json').read_bytes());report['windowObservation']=observed
  assert exitCode==0 and observed['normalCloseRequested'] and observed['startupHealthAcknowledged']
  if source.stem=='PhysicalRootRoutesFixture':
   routes=json.loads((OUT/'route-actions.json').read_bytes());assert routes['passed'] and routes['semanticActions']==3
   report['actualRootRouteActions']=routes;report['mouseOrNativePlayerInputAccepted']=False
  assert wide(health).read_text(encoding='utf-8').strip()==token
  report['actualPackagedResourceVersion']=wide(launch/'startup-version.txt').read_text(encoding='utf-8').strip()
  assert b'Exception in thread'not in (OUT/'stderr.raw.log').read_bytes()
  report['passed']=True
 except Exception as failure:report['failure']=str(failure)
 finally:
  if process.poll()is None:process.terminate();process.wait(timeout=10);report['fixtureForcedTermination']=True
pins()
for name,pin in assets.items():assert sha((resourceDir/'native/windows-x64'/name).read_bytes())==pin
report['runtimeAndNativeAssetPinsBeforeAfterEqual']=True
(OUT/'result.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
print(json.dumps(report));raise SystemExit(0 if report['passed']else 1)
