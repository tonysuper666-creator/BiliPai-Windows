"""Byte-identical frozen pure fixture, ONLY actual33 product + official ordered runtime graph.
Original fixture's historical prepared/25 prose is preserved; the outer receipt corrects scope.
No production/prepared classes are compiled or prefixed. Root may run this task-only script.
"""
from pathlib import Path
import json,hashlib,subprocess,importlib.util,sys
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith(PREFIX) else PREFIX+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
snap=MAIN/'desktop/.local/stable-product-snapshot-33'
assert sha(snap/'manifest.json')=='a94cf303442cc285a81650237f0cc1a3910c47e4f3b1a43e42a23e36e0c63883'
assert sha(snap/'ordered-runtime-cp.json')=='e76eb70649106c7acc0d446c539e6b971fcf578daaca68221a5897fda2fdb09d'
cp=json.loads(safe(snap/'ordered-runtime-cp.json').read_text(encoding='utf-8'));assert len(cp)==97
for row in cp:assert sha(row['path'])==row['sha256Bytes']
source=MAIN/'desktop/.local/stable-home-page-parity/fixtures/HomePreferencesFixture.kt';sourceSha=sha(source)
run=HERE/f'actual33-prefs-{1+len(list(HERE.glob("actual33-prefs-??"))):02}';run.mkdir()
spec=importlib.util.spec_from_file_location('cc',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');cc=importlib.util.module_from_spec(spec);spec.loader.exec_module(cc)
runtime=[row['path'] for row in cp]
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','home_prefs_fixture','-Xfriend-paths='+str(snap/'main-kotlin.jar'),'-cp',';'.join(runtime),'-d',str(run/'classes'),str(source)]
safe(run/'compile.args').write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args),encoding='utf-8')
r=subprocess.run([str(cc.JAVA),'-Dfile.encoding=UTF-8','-Xmx2g','-cp',';'.join(map(str,cc.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(run/'compile.args')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=120)
safe(run/'compile.log').write_text(r.stdout+r.stderr,encoding='utf-8')
if not r.returncode:
 r=subprocess.run([str(cc.JAVA),'-Dfile.encoding=UTF-8','-cp',';'.join([str(run/'classes')]+runtime),'com.bilipai.desktop.ui.HomePreferencesFixtureKt',str(run/'task-store')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=45)
 safe(run/'run.log').write_text(r.stdout+r.stderr,encoding='utf-8')
for row in cp:assert sha(row['path'])==row['sha256Bytes']
assert sha(source)==sourceSha
safe(run/'sourcebinding.json').write_text(json.dumps({'actualMainPrefsStoreAcceptance':r.returncode==0,'fullMountedHomeOrRuntimeAcceptance':False,'productOverrideCount':0,'snapshotManifestSha':sha(snap/'manifest.json'),'cpSha':sha(snap/'ordered-runtime-cp.json'),'orderedEntries':97,'pureFixtureSource':str(source),'pureFixtureSourceSha':sourceSha,'historicalFixtureScopeMetadataPreservedAndCorrectedByThisReceipt':True,'actualScope':'actual33 same-global prefs/store six gates only','exitCode':r.returncode},indent=2)+'\n',encoding='utf-8')
print((r.stdout+r.stderr).encode('ascii','backslashreplace').decode());sys.exit(r.returncode)
