from pathlib import Path
import hashlib,json,subprocess,os,sys
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023';SNAP=MAIN/'desktop/.local/stable-product-snapshot-15'
TOOLS=MAIN.parent/'toolchain';JAVA=TOOLS/'jdk/jdk-21.0.12.1+1/bin/java.exe';JAVAC=JAVA.with_name('javac.exe')
def sha(b):return hashlib.sha256(b).hexdigest()
assert sha((SNAP/'manifest.json').read_bytes())=='c665e8cc40604866341c9ac5f6ef992ed84b52fe709a1f934337f754012cab87'
raw=(SNAP/'ordered-runtime-cp.json').read_bytes();assert sha(raw)=='bcbc863d545922aaa4308076a4d4651c219aafacf339e8cb3000e59696f9e335';rows=json.loads(raw)
for row in rows:assert sha(Path('\\\\?\\'+row['path']).read_bytes())==row['sha256Bytes']
run=HERE/f'run-{int(sys.argv[1]):02}';assert not run.exists();run.mkdir();classes=run/'classes';classes.mkdir()
source=HERE/'RootStartupFixture.java';compile_args=[str(JAVAC),'-encoding','UTF-8','-d',str(classes),str(source)]
c=subprocess.run(compile_args,capture_output=True,text=True,encoding='utf-8');(run/'compiler.log').write_text(c.stdout+c.stderr,encoding='utf-8');c.check_returncode()
profile=run/'isolated-localappdata';profile.mkdir();env=dict(os.environ,LOCALAPPDATA=str(profile))
args=['-Dfile.encoding=UTF-8','-Dsun.stdout.encoding=UTF-8','-Dsun.stderr.encoding=UTF-8',
    '-Dbilipai.mpv.path='+str(MAIN/'desktop/native/windows-x64/libmpv-2.dll'),
    '-Dcompose.application.resources.dir='+str(REPO/'desktop/resources/common'),
    '-Dbilipai.js.workerResources='+str(REPO/'desktop/build/jw/f3d99d27a55656ad/r'),
    '-cp',';'.join([str(classes)]+[r['path'] for r in rows]),'com.bilipai.desktop.rootfixture.RootStartupFixture',str(run)]
argfile=run/'runtime.args';argfile.write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args),encoding='utf-8')
p=subprocess.run([str(JAVA),'@'+str(argfile)],env=env,capture_output=True,text=True,encoding='utf-8',timeout=60,cwd=REPO)
log=p.stdout+p.stderr
(run/'runtime.log').write_text(log,encoding='utf-8')
result=dict(exitCode=p.returncode,sourceSha256Bytes=sha(source.read_bytes()),snapshotManifestSha256Bytes='c665e8cc40604866341c9ac5f6ef992ed84b52fe709a1f934337f754012cab87',
    classesOverrides=0,profileIsFresh=True,source='actual full Main entry point',accountActions=0,externalGuestHttpMayOccur=True,productRootPageInteractionAccepted=False)
if (run/'observation.json').exists():result['observation']=json.loads((run/'observation.json').read_text())
result['compositionErrorObserved']='Error was captured in composition' in log
result['crashMarkerObserved']=any(profile.rglob('pending_crash.marker'))
result['passed']=p.returncode==0 and result.get('observation',{}).get('windowShown',False) and not (run/'observer-failure.txt').exists() and not result['compositionErrorObserved'] and not result['crashMarkerObserved']
(run/'accepted-evidence.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
print(json.dumps(result));p.check_returncode();assert result['passed']
