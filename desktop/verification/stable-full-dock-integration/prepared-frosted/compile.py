from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys,zipfile,struct
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf-8',errors='replace')
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2];PRIMARY=REPO.parent/'BiliPai';EXT=chr(92)*2+'?'+chr(92)
def safe(p):
 v=str(Path(p).absolute());return Path(v if v.startswith(EXT) else EXT+v)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,t):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(t,encoding='utf-8',newline='\n')
def save(p,x):write(p,json.dumps(x,ensure_ascii=False,indent=2)+'\n')
def mod(p,n):
 s=importlib.util.spec_from_file_location(n,p);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
snapshot=PRIMARY/'desktop/.local/stable-product-snapshot-17';assert sha(snapshot/'manifest.json')=='3b56a4a47fbd1d06b1d3b4be274bc3f69597dea669f04358eb7282292ab51e44'
assert sha(snapshot/'ordered-runtime-cp.json')=='25533b03785344f3a4a360a6db292775ad370824449c7a9c3a5b98702e3812d6'
cp=json.loads(read(snapshot/'ordered-runtime-cp.json'));assert len(cp)==92
for r in cp:assert sha(r['path'])==r['sha256Bytes']
main=next(r['path'] for r in cp if r.get('source')=='desktop/build/classes/kotlin/main')
out=HERE/'runs'/sys.argv[1];assert not out.exists();out.mkdir(parents=True)
sources=list(safe(HERE/'generated/com').rglob('*.kt'))
linked=REPO/'desktop/.local/stable-linked-dock-parity'
assert sha(linked/'frozen-handoff.json')=='e5eb03ec2e44d0b20e6817bcd53eab30851ff60682f7467cb19ce2475e4a9602'
sources+=[p for p in safe(linked/'generated/com').rglob('*.kt') if p.name not in ['HomeScrollOffsetPolicy.kt','DesktopOriginalLinkedDockScrollLocals.kt']]
sources+=list(safe(linked/'prepared/desktop/third-party/miuix5157/upstream').rglob('*.kt'))
sources+=list(safe(linked/'prepared/desktop/src').rglob('*.kt'))
shared=PRIMARY/'desktop/.local/stable-shared-liquid-tabs-parity/generated/com/android/purebilibili/feature/home/components/BottomBar.kt'
visibility=HERE/'declared-prospective-existing-producer/BottomBar.kt'
originalShared=read(shared);assert originalShared.count('private val iosIndicatorSpecular')==1
write(visibility,originalShared.replace('private val iosIndicatorSpecular','internal val iosIndicatorSpecular',1))
sources+=[visibility]
# Exact original direct policy is owned and installed solely by the Favorites lane.
policy=REPO/'app/src/main/java/com/android/purebilibili/feature/list/ListScopedSearchPolicy.kt'
blob=subprocess.check_output(['git','show','3d5d19a2f994daccd0e2f8b5f522b6d82f43d589:app/src/main/java/com/android/purebilibili/feature/list/ListScopedSearchPolicy.kt'],cwd=REPO).decode().replace('\r\n','\n');assert read(policy)==blob
# Actual17 already owns the sole Favorites direct ListScopedSearchPolicy.
if (HERE/'prepared/desktop/src').exists():sources+=list(safe(HERE/'prepared/desktop/src').rglob('*.kt'))
if (HERE/'FrostedAudioFixture.kt').exists():sources+=[HERE/'FrostedAudioFixture.kt']
inputs=[]
for p in sources:
 target=out/'sources'/p.name;assert not target.exists();write(target,read(p));inputs.append(dict(path=str(target),sha256Bytes=sha(target),source=str(p)))
compiler=mod(PRIMARY/'desktop/.local/source9-appearance/compile-miuix.py','original_dock_compile');serial=compiler.jar('org.jetbrains.kotlin','kotlin-serialization-compiler-plugin-embeddable','2.4.0')
home=out/'compiler-home';home.mkdir();jar=out/'candidate.jar';args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(r['path'] for r in cp),'-Xfriend-paths='+main,'-Xplugin='+str(compiler.PLUGIN),'-Xplugin='+str(serial),'-module-name','com_bilipai_desktop_bilipai_windows','-d',jar]+[r['path'] for r in inputs]
write(out/'compile.args','\n'.join('"'+str(x).replace('\\','/')+'"' for x in args)+'\n')
p=subprocess.run([str(compiler.JAVA),'-Dfile.encoding=UTF-8','-Duser.home='+str(home),'-Xmx2g','-cp',';'.join(map(str,compiler.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compile.args')],cwd=HERE,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90)
write(out/'compile.log',p.stdout+p.stderr);print(p.stdout+p.stderr)
e=dict(passed=p.returncode==0,exitCode=p.returncode,snapshotManifest=sha(snapshot/'manifest.json'),snapshotCP=sha(snapshot/'ordered-runtime-cp.json'),sourceRecords=inputs,orderedRuntimeCP=cp,compileLogSha256=sha(out/'compile.log'),MainWritten=False,sharedGradle=False)
if p.returncode==0:
 with zipfile.ZipFile(safe(main)) as z:existing={n for n in z.namelist() if n.endswith('.class')}
 with zipfile.ZipFile(safe(jar)) as z:classes={n for n in z.namelist() if n.endswith('.class')}
 e.update(candidateJarSha256=sha(jar),classOverlap=sorted(classes&existing),candidateClassCount=len(classes))
save(out/'compile-evidence.json',e);sys.exit(p.returncode)
