from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys,zipfile
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023';SNAP=MAIN/'desktop/.local/stable-product-snapshot-42';OUT=HERE/'root-window-navigation-prepared43-02'
assert not OUT.exists();OUT.mkdir()
sha=lambda b:hashlib.sha256(b).hexdigest()
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
cp=json.loads((SNAP/'ordered-runtime-cp.json').read_bytes());assert len(cp)==97
for r in cp:assert sha(wide(r['path']).read_bytes())==r['sha256Bytes']
spec=importlib.util.spec_from_file_location('compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
sources=[REPO/'desktop/src/main/kotlin/com/bilipai/desktop/ui'/name for name in ['DesktopRootWindowNavigationOwner.kt','DesktopNavigationHostEnvironment.kt','DesktopOriginalNavigationHost.kt']]
sources.append(HERE/'RootWindowNavigationFixture.kt');jar=OUT/'candidate-fixture.jar';classpath=';'.join(r['path']for r in cp)
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',classpath,'-Xplugin='+str(c.PLUGIN),'-Xfriend-paths='+str(SNAP/'main-kotlin.jar'),'-module-name','com_bilipai_desktop_bilipai_windows','-d',str(jar)]+list(map(str,sources))
argfile=OUT/'compiler.args';argfile.write_text('\n'.join('"'+a.replace('\\','/')+'"'for a in args)+'\n',encoding='utf-8')
r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(argfile)],capture_output=True,text=True,encoding='utf-8',timeout=180)
(OUT/'compiler.log').write_text(r.stdout+r.stderr,encoding='utf-8');assert r.returncode==0,'inspect compiler.log'
r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-cp',str(jar)+';'+classpath,'com.bilipai.desktop.ui.RootWindowNavigationFixtureKt'],capture_output=True,text=True,encoding='utf-8',timeout=40)
(OUT/'runtime.log').write_text(r.stdout+r.stderr,encoding='utf-8');assert r.returncode==0 and 'PASS actual physical-window root owner' in r.stdout,'inspect runtime.log'
overrides=[]
with zipfile.ZipFile(jar)as z,zipfile.ZipFile(SNAP/'main-kotlin.jar')as prod:
 overrides=sorted(set(z.namelist()).intersection(prod.namelist())-{'META-INF/MANIFEST.MF','META-INF/com_bilipai_desktop_bilipai_windows.kotlin_module'})
 assert all(name.startswith(('com/bilipai/desktop/ui/DesktopNavigationHostEnvironment','com/bilipai/desktop/ui/DesktopNavigationEntryViewModelPlatform','com/bilipai/desktop/ui/DesktopNavigationHostEnvironmentKt','com/bilipai/desktop/ui/DesktopOriginalNavigationHostKt'))for name in overrides),overrides
(OUT/'result.json').write_text(json.dumps(dict(passed=True,actualDependencyCount=97,prospectiveProductionSources=3,declaredExistingOverrideClasses=overrides,physicalUnfocusedWindow=True,realShownHiddenEvents=True,actualViewModelSavedStateAndBackInput=True,focusedResumed=False,minimize=False,predictive=False,RootNavDisplayMounted=False,sourceInputs=[dict(path=str(p),sha256Bytes=sha(p.read_bytes()))for p in sources]),indent=2)+'\n',encoding='utf-8')
print(r.stdout)
