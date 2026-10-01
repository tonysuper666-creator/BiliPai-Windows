from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys,zipfile
sys.dont_write_bytecode=True
P=Path(__file__).resolve().parent;MAIN=P.parents[2];SNAP=MAIN/'desktop/.local/stable-product-snapshot-69'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def read(p):return wide(p).read_bytes()
def sha(p):return hashlib.sha256(read(p)).hexdigest()
def save(p,v):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes((json.dumps(v,indent=2)+'\n').encode())
assert sha(SNAP/'manifest.json')=='4524b0c8b5e4e97bb88223a6a6f71c126bc17586111d50a10fb49f186a3d695d'
assert sha(SNAP/'ordered-runtime-cp.json')=='125c041f103947b5aa4be065a182cc561528bde575afe682db9e9af43a42dc9f'
cp=json.loads(read(SNAP/'ordered-runtime-cp.json'));assert len(cp)==101
paths=[P/'prepared/desktop/src/main/kotlin/com/bilipai/desktop/plugins'/n for n in ['DesktopPluginRuntime.kt','DesktopPluginStore.kt','DesktopPlayerPluginWriteAdmission.kt']]+[P/'generated/com/android/purebilibili/feature/plugin'/n for n in ['CdnRegionPlugin.kt','SponsorBlockInsightPolicy.kt']]
spec=importlib.util.spec_from_file_location('compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
SERIAL=c.jar('org.jetbrains.kotlin','kotlin-serialization-compiler-plugin-embeddable','2.4.0')
out=P/'compile'/sys.argv[1];assert not wide(out).exists();wide(out).mkdir(parents=True)
sources=[]
for i,p in enumerate(paths):
 frozen=out/'inputs'/p.name;wide(frozen).parent.mkdir(parents=True,exist_ok=True);wide(frozen).write_bytes(read(p));sources.append(frozen)
def pins():
 for r in cp:assert sha(r['path'])==r['sha256Bytes']
 return dict(snapshotManifestSHA256Bytes=sha(SNAP/'manifest.json'),orderedRuntimeSHA256Bytes=sha(SNAP/'ordered-runtime-cp.json'),runtime=cp,sources=[dict(path=str(p),sha256Bytes=sha(p),bytes=len(read(p)))for p in sources],tools=[dict(path=str(p),sha256Bytes=sha(p))for p in [c.JAVA,c.PLUGIN,SERIAL]+c.COMPILER])
save(out/'pins-before.json',pins());jar=out/'candidate.jar'
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(r['path']for r in cp),'-Xplugin='+str(c.PLUGIN),'-Xplugin='+str(SERIAL),'-Xfriend-paths='+str(SNAP/'main-kotlin.jar'),'-module-name','com_bilipai_desktop_bilipai_windows','-d',str(jar),*map(str,sources)]
arg=out/'compile.args';wide(arg).write_bytes(('\n'.join('"'+str(a).replace('\\','/')+'"'for a in args)+'\n').encode())
r=subprocess.run([str(c.JAVA),'-Xmx3g','-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(arg)],capture_output=True,timeout=180)
wide(out/'compile.log').write_bytes(r.stdout+r.stderr);save(out/'pins-after.json',pins());assert read(out/'pins-before.json')==read(out/'pins-after.json')
result=dict(passed=r.returncode==0,exitCode=r.returncode,sourceInputs=len(sources),prospectiveOnly=True,productionOverridesExplicit=['DesktopPluginRuntime family','DesktopPluginStore family','CdnRegionPlugin family','SponsorBlockInsightPolicy family'],newManualHelper='DesktopPlayerPluginWriteAdmission',actual69CPEntries=101)
if r.returncode==0:
 with zipfile.ZipFile(wide(jar))as z:entries={e for e in z.namelist()if e.endswith('.class')}
 with zipfile.ZipFile(wide(SNAP/'main-kotlin.jar'))as z:actual={e for e in z.namelist()if e.endswith('.class')}
 result.update(classes=len(entries),jarSHA256Bytes=sha(jar),declaredActualClassOverrides=sorted(entries&actual),newClasses=sorted(entries-actual))
save(out/'result.json',result);sys.stdout.reconfigure(encoding='utf8');print((r.stdout+r.stderr).decode('utf8',errors='replace'));print(json.dumps({k:v for k,v in result.items()if not isinstance(v,list)},indent=2));raise SystemExit(r.returncode)
