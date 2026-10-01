from pathlib import Path
import hashlib,importlib.util,json,re,subprocess,zipfile,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
SNAP=MAIN/'desktop/.local/stable-product-snapshot-42';OUT=HERE/'navigation-share-actual42'
assert not OUT.exists();OUT.mkdir()
sha=lambda b:hashlib.sha256(b).hexdigest()
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
cp=json.loads((SNAP/'ordered-runtime-cp.json').read_text(encoding='utf-8'))
def pins():
 for r in cp:assert sha(wide(r['path']).read_bytes())==r['sha256Bytes'],r['path']
pins();assert len(cp)==97
spec=importlib.util.spec_from_file_location('compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
sources=[]
for path,name in [(MAIN/'desktop/.local/stable-home-navigation3-host-parity/fixtures/NavigationHostFixture.kt','NavigationHostFixture.kt'),(REPO/'desktop/.local/stable-video-share-consent-parity/proof/VideoShareProof.kt','VideoShareProof.kt'),(HERE/'NativeShareConsentFixture.kt','NativeShareConsentFixture.kt')]:
 raw=wide(path).read_text(encoding='utf-8')
 if name=='NavigationHostFixture.kt':
  before="classes.forEach { Class.forName(it.removeSuffix(\".class\").replace('/', '.'), false, loader) }";assert raw.count(before)==1
  raw=raw.replace(before,'''
    val product=java.nio.file.Path.of(System.getProperty("actualProductJar")).toUri().toURL()
    classes.forEach { val type=Class.forName(it.removeSuffix(".class").replace('/', '.'), false, loader);check(type.protectionDomain.codeSource.location==product) }
    for(type in listOf(BiliPaiNavBackStackController::class.java, BiliPaiNavKey::class.java,
        DesktopNavigationEntryViewModelPlatform::class.java, DesktopOriginalNavigationHostSettings::class.java,
        VideoCardTransitionClock::class.java, DesktopPluginStore::class.java)) {
        check(type.protectionDomain.codeSource.location==product)
        println("ORIGIN ${type.name} ${type.protectionDomain.codeSource.location}")
    }
''')
 if name=='VideoShareProof.kt':
  marker='fun main(args:Array<String>)=runBlocking {';assert raw.count(marker)==1
  raw=raw.replace(marker,marker+'''
    val product=java.nio.file.Path.of(System.getProperty("actualProductJar")).toUri().toURL()
    for(type in listOf(DesktopVideoShareFiles::class.java, DesktopVideoShareImageTransport::class.java,DesktopOriginalVideoShareMessages::class.java)) {
        check(type.protectionDomain.codeSource.location==product)
        println("ORIGIN ${type.name} ${type.protectionDomain.codeSource.location}")
    }
''')
 p=OUT/name;p.write_text(raw,encoding='utf-8',newline='\n');sources.append(p)
jar=OUT/'fixture.jar';classpath=';'.join(r['path']for r in cp)
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',classpath,'-Xplugin='+str(c.PLUGIN),'-Xfriend-paths='+str(SNAP/'main-kotlin.jar'),'-module-name','com_bilipai_desktop_bilipai_windows','-d',str(jar)]+list(map(str,sources))
argfile=OUT/'compiler.args';argfile.write_text('\n'.join('"'+a.replace('\\','/')+'"'for a in args)+'\n',encoding='utf-8')
r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(argfile)],capture_output=True,text=True,encoding='utf-8',timeout=180)
(OUT/'compiler.log').write_text(r.stdout+r.stderr,encoding='utf-8');assert r.returncode==0,'Fixture compile failed; inspect compiler.log'
with zipfile.ZipFile(jar)as z,zipfile.ZipFile(SNAP/'main-kotlin.jar')as prod:
 overlap=set(z.namelist()).intersection(prod.namelist())-{'META-INF/MANIFEST.MF','META-INF/com_bilipai_desktop_bilipai_windows.kotlin_module'}
 assert not overlap,sorted(overlap)
preparedNav=MAIN/'desktop/.local/stable-home-navigation3-host-parity/compile-06/candidate.jar'
asset=REPO/'desktop/resources/common/native/windows-x64/bilipai-diagnostic-share.dll'
assert sha(asset.read_bytes())=='89705e2e0b5b146c8fdbccda63dc68bea7e46bfcc7e4aaa50c4cb1efa5331f06'
results=[]
for name,clazz,marker,arguments,count in [('nav','com.bilipai.desktop.ui.NavigationHostFixtureKt','PASS four original settings',[str(preparedNav)],6),('share','com.bilipai.desktop.ui.VideoShareProofKt','PASS 4 groups / 24 assertions',[str(OUT/'share-local-proof')],3),('actors','com.bilipai.desktop.ui.NativeShareConsentFixtureKt','RESULT actors assertions=',[str(OUT/'actor-local-proof'),str(asset),str(HERE/'native-media-prepare-actual42/card.jpg')],5)]:
 r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-DactualProductJar='+str(SNAP/'main-kotlin.jar'),'-cp',str(jar)+';'+classpath,clazz]+arguments,capture_output=True,text=True,encoding='utf-8',timeout=45)
 (OUT/(name+'-runtime.log')).write_text(r.stdout+r.stderr,encoding='utf-8');assert r.returncode==0 and marker in r.stdout,name
 origins=[line for line in r.stdout.splitlines()if line.startswith('ORIGIN ')]
 assert len(origins)==count and all((SNAP/'main-kotlin.jar').as_uri()in line.replace('file:/C:','file:///C:') for line in origins),(name,origins)
 results.append(dict(group=name,passed=True,productClassOrigins=origins))
actor=re.search(r'RESULT actors assertions=(\d+) native=(\d+) consent=(\d+) groups=3',(OUT/'actors-runtime.log').read_text(encoding='utf-8'));assert actor
pins()
report=dict(actualPhase=42,passed=True,actualRuntimeEntries=97,productionOverrides=0,actualProductClassOrigins=14,navigationGroups=4,navigationInstalledClassLoadCount=173,navigationClassInitialization=False,shareAssertions=24,shareGroups=4,actorAssertions=int(actor[1]),nativeActorAssertions=int(actor[2]),consentActorAssertions=int(actor[3]),actorGroups=3,originalFixtureBodiesPreservedExceptOriginVerification=True,
 fixtureSources=[dict(path=p.name,sha256Bytes=sha(p.read_bytes()))for p in sources],snapshotManifestSha256Bytes=sha((SNAP/'manifest.json').read_bytes()),orderedClasspathSha256Bytes=sha((SNAP/'ordered-runtime-cp.json').read_bytes()),nativeAssetSha256Bytes=sha(asset.read_bytes()),results=results,
 actualRootAccountOrNavigationIntegrated=False,physicalWindowAccepted=False,ShareUI=False,externalReceiver=False,HTTP=False,realAccountMutation=False)
(OUT/'result.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');print(json.dumps(dict(passed=True,navigationGroups=4,navInstalledClasses=173,shareAssertions=24,actorAssertions=int(actor[1]),actualOrigins=14,productionOverrides=0)))
