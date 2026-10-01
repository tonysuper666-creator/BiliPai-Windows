from pathlib import Path
import hashlib, importlib.util, json, subprocess, sys, zipfile
sys.stdout.reconfigure(encoding='utf-8', errors='replace'); sys.dont_write_bytecode = True
H = Path(__file__).resolve().parent; MAIN = H.parents[2]
S = MAIN/'desktop/.local/stable-product-snapshot-75'
LANE = MAIN/'desktop/.local/stable-original-progress-storage-parity'
OUT = H/'runs/01'; assert not OUT.exists(); OUT.mkdir(parents=True)

def wide(p):
    s = str(Path(p).absolute()); prefix = chr(92)*2+'?'+chr(92)
    return Path(s if s.startswith(prefix) else prefix+s)

def sha(p): return hashlib.sha256(wide(p).read_bytes()).hexdigest()
def write(p, b):
    wide(p).parent.mkdir(parents=True, exist_ok=True); wide(p).write_bytes(b)
def dump(p, value): write(p, (json.dumps(value,indent=2)+'\n').encode())

assert sha(S/'manifest.json') == '548d3f0d96db04be54e2e22d4e2d42ebca3a83c806fd6fa55d400f83c7d4f1b9'
assert sha(S/'ordered-runtime-cp.json') == '4f2f3212c1ca85bc2bc6208f834bc6bc34b10c533d56da904139918dd5a2253a'
cp = json.loads(wide(S/'ordered-runtime-cp.json').read_bytes()); assert len(cp) == 101
for row in cp: assert sha(row['path']) == row['sha256Bytes'], row['path']
source = OUT/'inputs/ProgressStorageProof.kt'
write(source, wide(LANE/'ProgressStorageProof.kt').read_bytes())
spec = importlib.util.spec_from_file_location('cc',MAIN/'desktop/.local/source9-appearance/compile-miuix.py')
cc = importlib.util.module_from_spec(spec); spec.loader.exec_module(cc)
pins = dict(snapshot=75,manifest=sha(S/'manifest.json'),orderedCP=sha(S/'ordered-runtime-cp.json'),runtime=cp,
    inputs=[dict(path=str(source),sha256Bytes=sha(source),exactFrozenFixture=True)],
    compiler=[dict(path=str(p),sha256Bytes=sha(p)) for p in [cc.JAVA]+cc.COMPILER])
dump(OUT/'pins-before.json', pins)
fixtureJar = OUT/'fixture.jar'
args = ['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows',
    '-Xfriend-paths='+cp[1]['path'],'-cp',';'.join(r['path'] for r in cp),'-d',str(fixtureJar),str(source)]
write(OUT/'compile.args','\n'.join('"'+a.replace('\\','/')+'"' for a in args).encode())
command = [str(cc.JAVA),'-Xmx1g','-Dfile.encoding=UTF-8','-cp',';'.join(map(str,cc.COMPILER)),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(OUT/'compile.args')]
dump(OUT/'compile-command.json', command)
r = subprocess.run(command,capture_output=True,timeout=120)
write(OUT/'compile.log',r.stdout+r.stderr)
if r.returncode:
    dump(OUT/'result.json',dict(exit=r.returncode,compilerPassed=False,productionOverrides=0))
    print((r.stdout+r.stderr).decode('utf-8',errors='replace')); raise SystemExit(r.returncode)
origins = {}
exercised = ['com/android/purebilibili/feature/video/controller/PlaybackProgressManager.class',
    'com/bilipai/desktop/ui/DesktopOriginalProgressWriter.class',
    'com/bilipai/desktop/ui/DesktopOriginalProgressPreferences.class',
    'com/bilipai/desktop/plugins/DesktopPluginStore.class']
productClasses = set()
for row in cp:
    with zipfile.ZipFile(wide(row['path'])) as archive:
        names = set(archive.namelist()); productClasses.update(n for n in names if n.endswith('.class'))
        for name in exercised:
            if name in names: origins.setdefault(name,[]).append(row)
with zipfile.ZipFile(wide(fixtureJar)) as archive:
    classes = {n for n in archive.namelist() if n.endswith('.class')}
assert productClasses.isdisjoint(classes)
assert len(origins) == 4 and all(len(v)==1 and v[0]['sha256Bytes']==cp[1]['sha256Bytes'] for v in origins.values())
dump(OUT/'class-origins.json',origins); dump(OUT/'overlap.json',dict(productClassOverlap=[],fixtureClasses=len(classes)))
command = [str(cc.JAVA),'-Djava.awt.headless=true','-Dfile.encoding=UTF-8','-cp',
    str(fixtureJar)+';'+';'.join(row['path'] for row in cp),'com.bilipai.desktop.ui.ProgressStorageProofKt']
dump(OUT/'run-command.json',command)
r = subprocess.run(command,capture_output=True,timeout=30)
log = r.stdout+r.stderr; write(OUT/'run.log',log)
for row in cp: assert sha(row['path']) == row['sha256Bytes'], row['path']
assert sha(source) == pins['inputs'][0]['sha256Bytes']; dump(OUT/'pins-after.json',pins)
passed = r.returncode == 0 and b'ProgressStorageProof PASS 22 assertions;' in log
dump(OUT/'result.json',dict(passed=passed,exit=r.returncode,snapshot=75,runtimeEntries=101,
    productionOverrides=0,fixtureSourceCount=1,fixtureClasses=len(classes),uniqueProductClassOrigins=4,
    assertions=22 if passed else 0,pinsUnchanged=True,actualInstalledProgressAndStore=True,
    globalRootProgressMounted=False,wholeRootAccepted=False,nativeOrWindowAccepted=False,desktopExeReplaced=False))
print(json.dumps(dict(passed=passed,assertions=22 if passed else 0,productionOverrides=0,snapshot=75)))
if not passed: print(log.decode('utf-8',errors='replace'))
raise SystemExit(0 if passed else 1)
