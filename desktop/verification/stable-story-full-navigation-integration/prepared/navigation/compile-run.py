from pathlib import Path
import importlib.util,sys,json,hashlib,subprocess,zipfile,os
sys.dont_write_bytecode=True
sys.stdout.reconfigure(encoding='utf-8')
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2]
SNAP=MAIN/'desktop/.local/stable-product-snapshot-83'
def wide(p):
    value=str(p.absolute());return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(p):return hashlib.sha256(wide(p).read_bytes()).hexdigest()
spec=importlib.util.spec_from_file_location('compile_full',MAIN/'desktop/.local/source9-appearance/compile-miuix.py')
c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
cp=json.loads((SNAP/'ordered-runtime-cp.json').read_text(encoding='utf-8-sig'))
for row in cp:assert sha(Path(row['path']))==row['sha256Bytes'],row['path']
run=HERE/'runs'/sys.argv[1];run.mkdir(parents=True,exist_ok=False)
sources=list(wide(HERE/'generated').rglob('*.kt'))+[p for p in wide(HERE/'prepared').rglob('*.kt') if '/src/test/' not in str(p).replace(chr(92),'/')]
sources += list(HERE.glob('*Fixture.kt'))
inputs=run/'inputs';inputs.mkdir();actual=[];identities=[]
for path in sources:
    target=inputs/path.name;assert not wide(target).exists()
    wide(target).write_bytes(wide(path).read_bytes());actual.append(wide(target))
    identities.append(dict(path=str(path),sha256Bytes=sha(path)))
product=next(row['path'] for row in cp if Path(row['path']).name=='main-kotlin.jar')
paths=[row['path'] for row in cp]
jar=run/'classes.jar'
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows',
    '-Xfriend-paths='+product,'-Xplugin='+str(c.PLUGIN),'-cp',';'.join(paths),'-d',str(jar)]+list(map(str,actual))
argsfile=run/'compiler.args';argsfile.write_text('\n'.join('"'+str(a).replace('\\','/')+'"' for a in args)+'\n',encoding='utf-8')
result=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Xmx2g','-cp',';'.join(map(str,c.COMPILER)),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(argsfile)],capture_output=True,timeout=160)
(run/'compile.log').write_bytes(result.stdout+result.stderr)
print((result.stdout+result.stderr).decode('utf-8',errors='replace')[-9500:]);result.check_returncode()
with zipfile.ZipFile(wide(jar)) as z:entries=set(z.namelist())
with zipfile.ZipFile(wide(Path(product))) as z:overlap=sorted(entries.intersection(z.namelist()))
allowed=('com/bilipai/desktop/ui/CommunitySearchScreensKt','com/bilipai/desktop/settings/DesktopNavigationInteractionSettingsKt','com/bilipai/desktop/settings/ComposableSingletons$DesktopNavigationInteractionSettingsKt',
    'com/bilipai/desktop/appearance/DesktopAppearanceThemeKt','com/bilipai/desktop/appearance/DesktopAppearancePalette',
    'com/bilipai/desktop/appearance/DesktopThemePrefs','com/bilipai/desktop/appearance/DesktopThemeSettings',
    'com/bilipai/desktop/ui/CommunitySearchState','com/bilipai/desktop/ui/CommunitySearchRow','com/bilipai/desktop/ui/ComposableSingletons$CommunitySearchScreensKt')
assert all(p.startswith(allowed) for p in overlap),overlap
executed=[]
for fixture in HERE.glob('*Fixture.kt'):
    env=os.environ.copy();app=run/'task-localappdata';app.mkdir(exist_ok=True);env['LOCALAPPDATA']=str(app)
    p=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-cp',str(jar)+';'+';'.join(paths),
        'com.bilipai.desktop.settings.'+fixture.stem+'Kt',str(run)],capture_output=True,timeout=140,env=env)
    (run/(fixture.stem+'.log')).write_bytes(p.stdout+p.stderr)
    print((p.stdout+p.stderr).decode('utf-8',errors='replace'));p.check_returncode();executed.append(fixture.name)
for row in cp:assert sha(Path(row['path']))==row['sha256Bytes']
(run/'result.json').write_text(json.dumps(dict(passed=True,snapshotManifestSha256Bytes=sha(SNAP/'manifest.json'),
    runtimeCpReference=str(SNAP/'ordered-runtime-cp.json'),runtimeCpSha256Bytes=sha(SNAP/'ordered-runtime-cp.json'),
    sources=identities,declaredProductOverrides=overlap,fixtureExecutables=executed,
    scope='actual83 immutable product with explicit prospective owned overlays; no entire Root/native/HWND/network proof'),ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
