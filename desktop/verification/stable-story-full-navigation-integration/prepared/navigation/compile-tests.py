from pathlib import Path
import importlib.util,sys,json,hashlib,subprocess,os
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf-8')
here=Path(__file__).resolve().parent;main=here.parents[2];candidate=main.parent/'BiliPai-v023';snap=main/'desktop/.local/stable-product-snapshot-83'
def wide(p):
 value=str(p.absolute());prefix=chr(92)*2+'?'+chr(92);return Path(value if value.startswith(prefix) else prefix+value)
def sha(p):return hashlib.sha256(wide(p).read_bytes()).hexdigest()
spec=importlib.util.spec_from_file_location('nav_test_compiler',main/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
cp=json.loads((snap/'ordered-runtime-cp.json').read_text(encoding='utf-8-sig'))
for r in cp:assert sha(Path(r['path']))==r['sha256Bytes']
run=here/'test-runs'/sys.argv[1];run.mkdir(parents=True,exist_ok=False)
base=here/'runs/20/classes.jar';base_sha=sha(base)
cache=main.parent/'toolchain/gradle-home/caches/modules-2/files-2.1'
deps=[next(cache.rglob(n+'.jar')) for n in ['junit-jupiter-api-5.10.1','junit-jupiter-engine-5.10.1','junit-platform-launcher-1.10.1','junit-platform-engine-1.10.1','junit-platform-commons-1.10.1','opentest4j-1.3.0','apiguardian-api-1.1.2']]
classpath=[base]+[Path(r['path']) for r in cp]+deps
sources=list((here/'prepared/desktop/src/test').rglob('*.kt'))+[main/'desktop/.local/settings-navigation-interaction-parity/producer-probe/com/android/purebilibili/core/store/DesktopOriginalNavigationInteractionSettings.kt']
inputs=run/'inputs';inputs.mkdir();actual=[]
for p in sources:
 to=inputs/p.name;wide(to).write_bytes(wide(p).read_bytes());actual.append(to)
jar=run/'tests.jar';args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows','-Xfriend-paths='+str(base)+','+str(snap/'main-kotlin.jar'),'-cp',';'.join(map(str,classpath)),'-d',str(jar)]+list(map(str,actual))
af=run/'compiler.args';af.write_text('\n'.join('"'+str(a).replace('\\','/')+'"' for a in args)+'\n',encoding='utf-8')
p=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(af)],capture_output=True,timeout=100);(run/'compile.log').write_bytes(p.stdout+p.stderr);print((p.stdout+p.stderr).decode('utf-8',errors='replace'));p.check_returncode()
runner=inputs/'NavigationJUnitRunner.java';runner.write_text((here/runner.name).read_text(encoding='utf-8-sig'),encoding='utf-8')
classes=run/'runner';classes.mkdir();subprocess.run([str(c.JAVA.parent/'javac.exe'),'-encoding','UTF-8','-cp',';'.join(map(str,classpath)),'-d',str(classes),str(runner)],check=True,timeout=30)
env=os.environ.copy();app=run/'task-localappdata';app.mkdir();env['LOCALAPPDATA']=str(app)
runtime=[classes,jar]+classpath
p=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-cp',';'.join(map(str,runtime)),'NavigationJUnitRunner',str(run/'junit-result.json')],capture_output=True,timeout=90,env=env);(run/'execute.log').write_bytes(p.stdout+p.stderr);print((p.stdout+p.stderr).decode('utf-8',errors='replace'));p.check_returncode()
assert sha(base)==base_sha
for r in cp:assert sha(Path(r['path']))==r['sha256Bytes']
(run/'proof.json').write_text(json.dumps(dict(passed=True,immutableProductReference=str(snap/'manifest.json'),immutableProductSha256Bytes=sha(snap/'manifest.json'),overlayReference=str(base),overlaySha256Bytes=base_sha,testSources=[dict(path=str(p),sha256Bytes=sha(p)) for p in sources],testDependencies=[dict(path=str(p),sha256Bytes=sha(p)) for p in deps],scope='14 focused original setters/store/Home/theme methods plus earlier 6; product actual83 with declared prospective overlays'),indent=2)+'\n',encoding='utf-8')
