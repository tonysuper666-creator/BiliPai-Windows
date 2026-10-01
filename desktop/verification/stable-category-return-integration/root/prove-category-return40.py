from pathlib import Path
import hashlib,importlib.util,json,subprocess,zipfile,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];SNAP=MAIN/'desktop/.local/stable-product-snapshot-40';OUT=HERE/'category-return-actual40'
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
for lane,name in [('stable-home-category-page-parity','CategoryVmFixture.kt'),('stable-home-return-navigation-parity','ReturnNavigationFixture.kt')]:
 raw=wide(MAIN/'desktop/.local'/lane/'fixtures'/name).read_text(encoding='utf-8');p=OUT/name
 if name=='ReturnNavigationFixture.kt':
  marker='fun main() {';assert raw.count(marker)==1
  raw=raw.replace(marker,marker+'''
    val productJar=java.nio.file.Path.of(System.getProperty("actualProductJar")).toUri().toURL()
    for(type in listOf(DesktopHomeReturnNavigationOwner::class.java,
        com.android.purebilibili.navigation3.BiliPaiReturnSessionState::class.java,
        com.android.purebilibili.navigation3.VideoCardTransitionSession::class.java,
        com.android.purebilibili.core.util.CardPositionManager::class.java,
        com.android.purebilibili.core.ui.transition.VideoCardTransitionClock::class.java)) {
        check(type.protectionDomain.codeSource.location==productJar)
        println("ORIGIN ${type.name} ${type.protectionDomain.codeSource.location}")
    }
''')
 p.write_text(raw,encoding='utf-8',newline='\n');sources.append(p)
jar=OUT/'fixture.jar';classpath=';'.join(r['path']for r in cp)
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',classpath,'-Xfriend-paths='+str(SNAP/'main-kotlin.jar'),'-module-name','com_bilipai_desktop_bilipai_windows','-d',str(jar)]+list(map(str,sources))
argfile=OUT/'compiler.args';argfile.write_text('\n'.join('"'+a.replace('\\','/')+'"'for a in args)+'\n',encoding='utf-8')
r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(argfile)],capture_output=True,text=True,encoding='utf-8',timeout=180)
(OUT/'compiler.log').write_text(r.stdout+r.stderr,encoding='utf-8');assert r.returncode==0,'Fixture compile failed; inspect compiler.log'
with zipfile.ZipFile(jar)as z,zipfile.ZipFile(SNAP/'main-kotlin.jar')as prod:
 overlap=set(z.namelist()).intersection(prod.namelist())-{'META-INF/MANIFEST.MF','META-INF/com_bilipai_desktop_bilipai_windows.kotlin_module'}
 assert not overlap,sorted(overlap)
results=[]
for name,clazz,marker,arguments in [('category','categoryproof.CategoryVmFixtureKt','RESULT assertions=16 groups=3',[str(OUT/'real-global-store')]),('return','com.bilipai.desktop.ui.ReturnNavigationFixtureKt','RESULT 7 groups',[])]:
 r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-DactualProductJar='+str(SNAP/'main-kotlin.jar'),'-cp',str(jar)+';'+classpath,clazz]+arguments,capture_output=True,text=True,encoding='utf-8',timeout=40)
 (OUT/(name+'-runtime.log')).write_text(r.stdout+r.stderr,encoding='utf-8');assert r.returncode==0 and marker in r.stdout,name
 origins=[line for line in r.stdout.splitlines()if line.startswith('ORIGIN ')]
 assert len(origins)==5 and all((SNAP/'main-kotlin.jar').as_uri()in line.replace('file:/C:','file:///C:') for line in origins),(name,origins)
 results.append(dict(group=name,passed=True,productClassOrigins=origins))
pins()
report=dict(actualPhase=40,passed=True,actualRuntimeEntries=97,productionOverrides=0,actualProductClassOrigins=10,categoryAssertions=16,categoryGroups=3,returnGroups=7,originalFixtureBodiesPreserved=True,
 fixtureSources=[dict(path=p.name,sha256Bytes=sha(p.read_bytes()))for p in sources],
 snapshotManifestSha256Bytes=sha((SNAP/'manifest.json').read_bytes()),orderedClasspathSha256Bytes=sha((SNAP/'ordered-runtime-cp.json').read_bytes()),results=results,
 actualRootAccountOrNavigationIntegrated=False,physicalWindowAccepted=False,HTTP=False,realAccountMutation=False)
(OUT/'result.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');print(json.dumps(dict(passed=True,categoryAssertions=16,returnGroups=7,actualClassOrigins=10,productionOverrides=0)))
