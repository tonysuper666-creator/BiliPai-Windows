from pathlib import Path
import hashlib,importlib.util,json,os,subprocess,zipfile
HERE=Path(__file__).resolve().parent;BASE=HERE.parents[2];REPO=BASE.parent/'BiliPai-v023'
SNAPSHOT=BASE/'desktop/.local/image-save-main-integration/main-product-snapshot-04'
def ext(path):
    value=str(Path(path).absolute());prefix=chr(92)*2+'?'+chr(92)
    return Path(value if value.startswith(prefix) else prefix+value)
def sha(path):return hashlib.sha256(ext(path).read_bytes()).hexdigest()
def save(path,value):Path(path).write_text(json.dumps(value,indent=2)+'\n',encoding='utf-8')
assert sha(SNAPSHOT/'manifest.json')=='7bc5ac8875dadf38510120db6619bd6e748e7f790a47afc11374e14114f25865'
assert sha(SNAPSHOT/'ordered-runtime-cp.json')=='7a3141310fa355d8618c5bc2be90f36a2c13c8ed36f2d981bf4a14c2232165e2'
cp=json.loads((SNAPSHOT/'ordered-runtime-cp.json').read_text(encoding='utf-8'))
for row in cp:assert sha(row['path'])==row['sha256Bytes']
spec=importlib.util.spec_from_file_location('root_consumer_compiler',BASE/'desktop/.local/source9-appearance/compile-miuix.py')
compiler=importlib.util.module_from_spec(spec);spec.loader.exec_module(compiler)
paths=['desktop/src/main/kotlin/com/bilipai/desktop/download/DesktopDownloadManager.kt',
    'desktop/src/main/kotlin/com/android/purebilibili/feature/home/components/cards/DesktopVideoCardPalette.kt',
    'app/src/main/java/com/android/purebilibili/feature/download/DownloadQueuePolicy.kt',
    'app/src/main/java/com/android/purebilibili/feature/download/DownloadListNavigationPolicy.kt',
    'app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/ViewPointSegmentBarPolicy.kt']
out=HERE/'run-01';assert not out.exists();out.mkdir()
frozen=out/'sources';frozen.mkdir()
sources=[]
for source in [REPO/p for p in paths]+[HERE/'ConsumerFixture.kt']:
    target=frozen/source.name;target.write_bytes(source.read_bytes().replace(b'\r\n',b'\n'));sources.append(target)
main=next(row['path'] for row in cp if row.get('source')=='desktop/build/classes/kotlin/main')
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(row['path'] for row in cp),
    '-Xfriend-paths='+main,'-module-name','com_bilipai_desktop_bilipai_windows','-d',str(out/'candidate.jar')]+list(map(str,sources))
def argfile(path,values):Path(path).write_text('\n'.join('"'+str(v).replace('\\','/')+'"' for v in values)+'\n',encoding='utf-8')
argfile(out/'compile.args',args)
environment=os.environ.copy();environment['PYTHONDONTWRITEBYTECODE']='1'
home=out/'home';home.mkdir();temp=out/'temp';temp.mkdir()
environment['TEMP']=str(temp);environment['TMP']=str(temp)
command=[str(compiler.JAVA),'-Dfile.encoding=UTF-8','-Duser.home='+str(home),'-Djava.io.tmpdir='+str(temp),'-Xmx1g',
    '-cp',';'.join(map(str,compiler.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compile.args')]
result=subprocess.run(command,env=environment,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90)
(out/'compile.log').write_text(result.stdout+result.stderr,encoding='utf-8');print(result.stdout+result.stderr);result.check_returncode()
existing=set()
for row in cp:
    with zipfile.ZipFile(ext(row['path'])) as archive:existing.update(archive.namelist())
with zipfile.ZipFile(out/'candidate.jar') as archive:own={n for n in archive.namelist() if n.endswith('.class')}
overlap=sorted(own&existing)
argfile(out/'run.args',['-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-Duser.home='+str(home),
    '-Djava.io.tmpdir='+str(temp),'-Djna.tmpdir='+str(temp),'-cp',
    ';'.join([str(out/'candidate.jar')]+[row['path'] for row in cp]),'com.bilipai.desktop.download.ConsumerFixtureKt',str(out/'proof')])
result=subprocess.run([str(compiler.JAVA),'@'+str(out/'run.args')],env=environment,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=60)
(out/'run.log').write_text(result.stdout+result.stderr,encoding='utf-8');print(result.stdout+result.stderr);result.check_returncode()
proof=json.loads((out/'proof/result.json').read_text(encoding='utf-8'));assert proof['passed'] and proof['cases']==3
save(out/'accepted-evidence.json',dict(**proof,candidateJarSha256=sha(out/'candidate.jar'),
    existingMain04ProductClassOverrides=overlap,sourceFiles=[dict(path=str(p),sha256Bytes=sha(p)) for p in sources],
    immutableMain04RuntimeClasspath=cp,candidateSourceInstalled=True,actualWholeStableRuntimeAccepted=False))
print(json.dumps(dict(passed=True,productClassOverrides=len(overlap),cases=proof['cases'],assertions=proof['assertions'])))
