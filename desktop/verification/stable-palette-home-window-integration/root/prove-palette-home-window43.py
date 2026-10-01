from pathlib import Path
import hashlib,importlib.util,json,re,subprocess,sys,zipfile
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];SNAP=MAIN/'desktop/.local/stable-product-snapshot-43';OUT=HERE/'palette-home-window-actual43'
assert not OUT.exists();OUT.mkdir()
sha=lambda b:hashlib.sha256(b).hexdigest()
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
cp=json.loads((SNAP/'ordered-runtime-cp.json').read_bytes());assert len(cp)==97
def pins():
 for r in cp:assert sha(wide(r['path']).read_bytes())==r['sha256Bytes'],r['path']
pins()
spec=importlib.util.spec_from_file_location('compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
sources=[]
palette=wide(MAIN/'desktop/.local/stable-wallpaper-palette-parity/WallpaperPaletteFixture.kt').read_text(encoding='utf-8')
marker='fun main(args:Array<String>)=runBlocking {';assert palette.count(marker)==1
palette=palette.replace(marker,marker+'''
    val kotlinProduct=java.nio.file.Path.of(System.getProperty("actualProductJar")).toUri().toURL()
    val javaProduct=java.nio.file.Path.of(System.getProperty("actualJavaJar")).toUri().toURL()
    for(type in listOf(WallpaperPaletteStore::class.java, DesktopOwnedWallpaperPaletteContext::class.java,
        DesktopWallpaperPaletteRaster::class.java, DesktopWallpaperPaletteLru::class.java)) {
        check(type.protectionDomain.codeSource.location==kotlinProduct)
        println("ORIGIN ${type.name} ${type.protectionDomain.codeSource.location}")
    }
    for(type in listOf(DesktopPaletteTarget::class.java, DesktopWallpaperPaletteScoring::class.java, DesktopPalette::class.java)) {
        check(type.protectionDomain.codeSource.location==javaProduct)
        println("ORIGIN ${type.name} ${type.protectionDomain.codeSource.location}")
    }
''')
assert palette.count('actualSnapshot\\\":41')==1
palette=palette.replace('actualSnapshot\\\":41','actualSnapshot\\\":43',1)
home=wide(MAIN/'desktop/.local/stable-home-root-mount-parity/fixtures/HomeRetirementFixture.kt').read_text(encoding='utf-8')
assert home.count('check(product != candidate)')==1
home=home.replace('check(product != candidate)','''check(product == candidate)
    val expected=Path.of(System.getProperty("actualProductJar")).toUri().toURL()
    check(product==expected)
    for(type in listOf(DesktopHomeRootFactory::class.java, DesktopHomeRootRetainer::class.java,
        DesktopTodayWatchRepository::class.java, DesktopHomeRetainedEntry::class.java)) {
        check(type.protectionDomain.codeSource.location==expected)
        println("ORIGIN ${type.name} ${type.protectionDomain.codeSource.location}")
    }''',1)
home=home.replace('Class.forName(it.name.removeSuffix(".class").replace(\'/\', \'.\'), false, DesktopTodayWatchRepository::class.java.classLoader)',
 '''Class.forName(it.name.removeSuffix(".class").replace('/', '.'), false, DesktopTodayWatchRepository::class.java.classLoader).also { type ->
            check(type.protectionDomain.codeSource.location==expected)
        }''',1)
window=(HERE/'RootWindowNavigationFixture.kt').read_text(encoding='utf-8')
marker='fun main() {';assert window.count(marker)==1
window=window.replace(marker,marker+'''
    val expected=java.nio.file.Path.of(System.getProperty("actualProductJar")).toUri().toURL()
    check(DesktopRootWindowNavigationOwner::class.java.protectionDomain.codeSource.location==expected)
    println("ORIGIN ${DesktopRootWindowNavigationOwner::class.java.name} $expected")
''',1)
for name,source in [('WallpaperPaletteFixture.kt',palette),('HomeRetirementFixture.kt',home),('RootWindowNavigationFixture.kt',window)]:
 p=OUT/name;p.write_text(source,encoding='utf-8',newline='\n');sources.append(p)
jar=OUT/'fixtures.jar';classpath=';'.join(r['path']for r in cp)
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',classpath,'-Xplugin='+str(c.PLUGIN),'-Xfriend-paths='+str(SNAP/'main-kotlin.jar'),'-module-name','com_bilipai_desktop_bilipai_windows','-d',str(jar)]+list(map(str,sources))
argfile=OUT/'compiler.args';argfile.write_text('\n'.join('"'+a.replace('\\','/')+'"'for a in args)+'\n',encoding='utf-8')
r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(argfile)],capture_output=True,text=True,encoding='utf-8',timeout=180)
(OUT/'compiler.log').write_text(r.stdout+r.stderr,encoding='utf-8');assert r.returncode==0,'inspect compiler.log'
with zipfile.ZipFile(jar)as z:
 for product in ['main-kotlin.jar','main-java.jar']:
  with zipfile.ZipFile(SNAP/product)as prod:
   overlap=set(z.namelist()).intersection(prod.namelist())-{'META-INF/MANIFEST.MF','META-INF/com_bilipai_desktop_bilipai_windows.kotlin_module'}
   assert not overlap,sorted(overlap)
results=[]
for name,clazz,marker,parameters,origins,headless in [
 ('palette','com.bilipai.desktop.ui.WallpaperPaletteFixtureKt','WALLPAPER_PALETTE_PROOF',[str(OUT/'generated-five-band-fixture')],7,True),
 ('home','com.bilipai.desktop.ui.HomeRetirementFixtureKt','PASS shutdown cancels unbound startup',[str(MAIN/'desktop/.local/stable-home-root-mount-parity/compile-04/candidate.jar')],6,True),
 ('window','com.bilipai.desktop.ui.RootWindowNavigationFixtureKt','PASS actual physical-window root owner',[],1,False)]:
 cmd=[str(c.JAVA),'-Dfile.encoding=UTF-8','-DactualProductJar='+str(SNAP/'main-kotlin.jar'),'-DactualJavaJar='+str(SNAP/'main-java.jar'),'-cp',str(jar)+';'+classpath,clazz]+parameters
 if headless:cmd.insert(1,'-Djava.awt.headless=true')
 r=subprocess.run(cmd,capture_output=True,text=True,encoding='utf-8',timeout=45)
 (OUT/(name+'-runtime.log')).write_text(r.stdout+r.stderr,encoding='utf-8');assert r.returncode==0 and marker in r.stdout,(name,r.stdout+r.stderr)
 observed=[line for line in r.stdout.splitlines()if line.startswith('ORIGIN ')];assert len(observed)==origins,(name,observed)
 results.append(dict(group=name,passed=True,productOrigins=observed))
pal=json.loads(re.search(r'WALLPAPER_PALETTE_PROOF (\{[^\n]+\})',(OUT/'palette-runtime.log').read_text(encoding='utf-8'))[1]);assert pal['assertions']==34 and pal['actualSnapshot']==43
windowCount=int(re.search(r'/ (\d+) assertions',(OUT/'window-runtime.log').read_text(encoding='utf-8'))[1]);assert windowCount==17
pins()
report=dict(passed=True,actualPhase=43,actualRuntimeEntries=97,productionOverrides=0,productOrigins=14,paletteGroups=3,paletteAssertions=34,homeRetirementGroups=4,homeInstalledClasses=86,physicalWindowAssertions=17,focusedResumedOrMinimize=False,predictive=False,RootNavDisplayMounted=False,fullHomeRootMounted=False,externalHttp=False,personalWallpaperRead=False,realAccountMutation=False,results=results,fixtureSources=[dict(path=p.name,sha256Bytes=sha(p.read_bytes()))for p in sources],snapshotManifestSha256Bytes=sha((SNAP/'manifest.json').read_bytes()),orderedClasspathSha256Bytes=sha((SNAP/'ordered-runtime-cp.json').read_bytes()))
(OUT/'result.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');print(json.dumps(dict(passed=True,productionOverrides=0,origins=14,paletteAssertions=34,homeRetirementGroups=4,physicalWindowAssertions=17)))
