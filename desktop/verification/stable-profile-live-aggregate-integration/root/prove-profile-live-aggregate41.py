from pathlib import Path
import hashlib,importlib.util,json,subprocess,zipfile,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
SNAP=MAIN/'desktop/.local/stable-product-snapshot-41';OUT=HERE/'profile-live-aggregate-actual41'
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
for lane,name in [('stable-home-live-navigation-parity','LiveNavigationFixture.kt'),('stable-home-four-page-aggregate-parity','EmbeddedLifetimeFixture.kt')]:
 raw=wide(MAIN/'desktop/.local'/lane/'fixtures'/name).read_text(encoding='utf-8');p=OUT/name
 if name=='EmbeddedLifetimeFixture.kt':
  marker='fun main() = runBlocking {';assert raw.count(marker)==1
  raw=raw.replace(marker,marker+'''
    val productJar=java.nio.file.Path.of(System.getProperty("actualProductJar")).toUri().toURL()
    for(type in listOf(DesktopHomeEmbeddedLifetime::class.java, DesktopHomeRetainedGate::class.java,
        DesktopSessionStore::class.java, PartitionFeedViewModel::class.java)) {
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
for name,clazz,marker,arguments,count in [('live','liveproof.LiveNavigationFixtureKt','RESULT assertions=25 groups=3',[str(OUT/'real-global-store')],10),('aggregate','com.bilipai.desktop.ui.EmbeddedLifetimeFixtureKt','RESULT 4 lifecycle groups',[],4)]:
 r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-DactualProductJar='+str(SNAP/'main-kotlin.jar'),'-cp',str(jar)+';'+classpath,clazz]+arguments,capture_output=True,text=True,encoding='utf-8',timeout=40)
 (OUT/(name+'-runtime.log')).write_text(r.stdout+r.stderr,encoding='utf-8');assert r.returncode==0 and marker in r.stdout,name
 origins=[line for line in r.stdout.splitlines()if line.startswith('ORIGIN ')]
 assert len(origins)==count and all((SNAP/'main-kotlin.jar').as_uri()in line.replace('file:/C:','file:///C:') for line in origins),(name,origins)
 results.append(dict(group=name,passed=True,productClassOrigins=origins))
prepared=REPO/'desktop/.local/stable-profile-main-parity/compile-06/original-profile-main.jar'
with zipfile.ZipFile(prepared)as z:names=sorted(n[:-6].replace('/','.')for n in z.namelist()if n.endswith('.class'))
assert len(names)==180
(OUT/'profile-installed-class-names.txt').write_text('\n'.join(names)+'\n',encoding='utf-8',newline='\n')
java=OUT/'ActualProfileClassLoadProof.java'
java.write_text('''public final class ActualProfileClassLoadProof {
 public static void main(String[] args) throws Exception {
  java.net.URL expected=java.nio.file.Path.of(args[1]).toUri().toURL();int count=0;
  for(String name:java.nio.file.Files.readAllLines(java.nio.file.Path.of(args[0]))) {
   Class<?> type=Class.forName(name,false,ActualProfileClassLoadProof.class.getClassLoader());
   if(!expected.equals(type.getProtectionDomain().getCodeSource().getLocation()))throw new AssertionError(name);
   count++;
  }
  if(count!=180)throw new AssertionError(count);
  System.out.println("PASS actual41/97 installed Profile classes="+count+" initialized=false overrides=0");
 }
}
''',encoding='utf-8',newline='\n')
classes=OUT/'java-classes';classes.mkdir()
r=subprocess.run([str(c.JAVA.with_name('javac.exe')),'-encoding','UTF-8','-d',str(classes),str(java)],capture_output=True,text=True,encoding='utf-8',timeout=30)
(OUT/'profile-classload-compiler.log').write_text(r.stdout+r.stderr,encoding='utf-8');assert r.returncode==0
r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-cp',str(classes)+';'+classpath,'ActualProfileClassLoadProof',str(OUT/'profile-installed-class-names.txt'),str(SNAP/'main-kotlin.jar')],capture_output=True,text=True,encoding='utf-8',timeout=40)
(OUT/'profile-classload-runtime.log').write_text(r.stdout+r.stderr,encoding='utf-8');assert r.returncode==0 and 'classes=180 initialized=false overrides=0'in r.stdout
pins()
report=dict(actualPhase=41,passed=True,actualRuntimeEntries=97,productionOverrides=0,actualProductClassOrigins=14,liveAssertions=25,liveGroups=3,aggregateLifecycleGroups=4,
 profileInstalledClassLoadCount=180,profileClassInitialization=False,originalFixtureBodiesPreserved=True,fixtureSources=[dict(path=p.name,sha256Bytes=sha(p.read_bytes()))for p in sources],
 snapshotManifestSha256Bytes=sha((SNAP/'manifest.json').read_bytes()),orderedClasspathSha256Bytes=sha((SNAP/'ordered-runtime-cp.json').read_bytes()),results=results,
 actualRootAccountOrNavigationIntegrated=False,physicalWindowAccepted=False,HTTP=False,realAccountMutation=False,aggregateRuntimeFactoryConstructed=False)
(OUT/'result.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');print(json.dumps(dict(passed=True,liveAssertions=25,aggregateGroups=4,actualClassOrigins=14,profileLoaded=180,productionOverrides=0)))
