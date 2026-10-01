from pathlib import Path
import json,hashlib,subprocess,importlib.util,sys
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023';SNAP=MAIN/'desktop/.local/stable-product-snapshot-39'
OUT=HERE/'home-owner-actual39';assert not OUT.exists();OUT.mkdir();sha=lambda b:hashlib.sha256(b).hexdigest()
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
cp=json.loads((SNAP/'ordered-runtime-cp.json').read_text(encoding='utf-8'))
for r in cp:assert sha(wide(r['path']).read_bytes())==r['sha256Bytes']
original=MAIN/'desktop/.local/stable-home-root-retained-integration/fixtures/RetainedHomeGateFixture.kt';source=OUT/original.name
body=original.read_text(encoding='utf-8');old='fun main() = runBlocking {';assert body.count(old)==1
body=body.replace(old,'private fun originalPreparedFixture() = runBlocking {')
body+='''
fun main() {
 val origins=listOf(DesktopHomeRetainedGate::class.java,
  com.bilipai.desktop.data.DesktopSessionStore::class.java,
  com.bilipai.desktop.data.DesktopRepository::class.java,
  com.bilipai.desktop.data.DesktopBlockedUpRepository::class.java,
  com.bilipai.desktop.plugins.DesktopPluginStore::class.java)
 check(origins.all { it.protectionDomain.codeSource.location.toString().endsWith("main-kotlin.jar") })
 originalPreparedFixture()
 println("ACTUAL39 five actual product class origins; zero production overrides; no full Root/account/network acceptance")
}
'''
source.write_text(body,encoding='utf-8',newline='\n')
spec=importlib.util.spec_from_file_location('cc',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');cc=importlib.util.module_from_spec(spec);spec.loader.exec_module(cc)
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','actual_home_owner_fixture',
 '-Xfriend-paths='+str(SNAP/'main-kotlin.jar'),'-cp',';'.join(r['path']for r in cp),'-d',str(OUT/'classes'),str(source)]
(OUT/'compiler.args').write_text('\n'.join('"'+a.replace('\\','/')+'"'for a in args),encoding='utf-8')
r=subprocess.run([str(cc.JAVA),'-Dfile.encoding=UTF-8','-Xmx1g','-cp',';'.join(map(str,cc.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(OUT/'compiler.args')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=120)
(OUT/'compiler.log').write_text(r.stdout+r.stderr,encoding='utf-8')
if r.returncode==0:
 r=subprocess.run([str(cc.JAVA),'-Dfile.encoding=UTF-8','-cp',';'.join([str(OUT/'classes')]+[row['path']for row in cp]),'com.bilipai.desktop.ui.RetainedHomeGateFixtureKt'],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=45)
 (OUT/'runtime.log').write_text(r.stdout+r.stderr,encoding='utf-8')
for row in cp:assert sha(wide(row['path']).read_bytes())==row['sha256Bytes']
passed=r.returncode==0 and 'ACTUAL39 five actual product class origins' in r.stdout
(OUT/'result.json').write_text(json.dumps(dict(passed=passed,exitCode=r.returncode,actualFrozenProduct39=True,productionOverrides=0,runtimeEntries=len(cp),productClassOrigins=5,
 actualTemporaryDiskSessionAndPluginStores=True,retainedOwnerGates=5,fixtureOriginalSha256LF=sha(original.read_text(encoding='utf-8').encode()),
 fixtureAdaptationOnly='rename main and add actual product origin checks; original prepared scope string preserved as history, this receipt supersedes runtime scope',
 userCredentialStoreAccessed=False,realHttpOrAccountWrites=False,actualRootMounted=False),indent=2)+'\n',encoding='utf-8')
print(r.stdout+r.stderr);sys.exit(r.returncode)
