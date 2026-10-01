from pathlib import Path
import hashlib, importlib.util, json, re, subprocess, sys, zipfile
sys.stdout.reconfigure(encoding='utf-8', errors='replace'); sys.dont_write_bytecode = True
H = Path(__file__).resolve().parent; MAIN = H.parents[2]
S = MAIN/'desktop/.local/stable-product-snapshot-75'
OUT = H/('runs/'+(sys.argv[1] if len(sys.argv)>1 else '01')); assert not OUT.exists(); OUT.mkdir(parents=True)
def wide(p):
    s = str(Path(p).absolute()); prefix = chr(92)*2+'?'+chr(92)
    return Path(s if s.startswith(prefix) else prefix+s)
def sha(p): return hashlib.sha256(wide(p).read_bytes()).hexdigest()
def write(p,b): wide(p).parent.mkdir(parents=True,exist_ok=True); wide(p).write_bytes(b)
def dump(p,v): write(p,(json.dumps(v,indent=2)+'\n').encode())
assert sha(S/'manifest.json') == '548d3f0d96db04be54e2e22d4e2d42ebca3a83c806fd6fa55d400f83c7d4f1b9'
assert sha(S/'ordered-runtime-cp.json') == '4f2f3212c1ca85bc2bc6208f834bc6bc34b10c533d56da904139918dd5a2253a'
cp=json.loads(wide(S/'ordered-runtime-cp.json').read_bytes()); assert len(cp)==101
for row in cp: assert sha(row['path'])==row['sha256Bytes'],row['path']
hunks=json.loads(wide(H/'exact-hunks.json').read_bytes()); sources=[]
for row in hunks['families']:
    source=H/'prepared'/row['target']; assert sha(source)==row['desiredSHA256LF']
    target=OUT/'inputs'/Path(row['target']).name; write(target,wide(source).read_bytes()); sources.append(target)
fixture=OUT/'inputs/SettingsIoProof.kt'; write(fixture,wide(H/'SettingsIoProof.kt').read_bytes()); sources.append(fixture)
spec=importlib.util.spec_from_file_location('cc',MAIN/'desktop/.local/source9-appearance/compile-miuix.py')
cc=importlib.util.module_from_spec(spec);spec.loader.exec_module(cc)
pins=dict(snapshot=75,manifest=sha(S/'manifest.json'),orderedCP=sha(S/'ordered-runtime-cp.json'),runtime=cp,
    inputs=[dict(path=str(p),sha256Bytes=sha(p)) for p in sources],
    compiler=[dict(path=str(p),sha256Bytes=sha(p)) for p in [cc.JAVA,cc.PLUGIN]+cc.COMPILER],
    exactHunks=sha(H/'exact-hunks.json'))
dump(OUT/'pins-before.json',pins)
output=OUT/'prospective.jar'
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows',
    '-Xfriend-paths='+cp[1]['path'],'-Xplugin='+str(cc.PLUGIN),'-cp',';'.join(r['path'] for r in cp),'-d',str(output)]+list(map(str,sources))
write(OUT/'compile.args','\n'.join('"'+a.replace('\\','/')+'"' for a in args).encode())
command=[str(cc.JAVA),'-Xmx2g','-Dfile.encoding=UTF-8','-cp',';'.join(map(str,cc.COMPILER)),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(OUT/'compile.args')]
dump(OUT/'compile-command.json',command)
r=subprocess.run(command,capture_output=True,timeout=120);write(OUT/'compile.log',r.stdout+r.stderr)
if r.returncode:
    dump(OUT/'result.json',dict(exit=r.returncode,compilerPassed=False,prospectiveProductFamilies=2,actualInstalled=False))
    print((r.stdout+r.stderr).decode('utf-8',errors='replace'));raise SystemExit(r.returncode)
origins={}; product=set()
for row in cp:
    with zipfile.ZipFile(wide(row['path'])) as archive:
        names={n for n in archive.namelist() if n.endswith('.class')};product.update(names)
        for name in names: origins.setdefault(name,[]).append(row)
with zipfile.ZipFile(wide(output)) as archive: classes={n for n in archive.namelist() if n.endswith('.class')}
overlap=classes&product
allowedPrefixes=['com/bilipai/desktop/plugins/'+name for name in ['DesktopPluginStore','DesktopPreferenceKey','DesktopPreferenceSnapshot',
    'DesktopPreferenceEditor','DesktopPluginDataStore','DesktopPluginContext','DesktopPluginPreferences']]+[
    'com/bilipai/desktop/ui/'+name for name in ['DesktopOriginalPlayerSettingsContext','DesktopOriginalPlayerSettingsDataStore',
    'DesktopOriginalPlayerPreferenceValues','DesktopOriginalPlayerMirrorPreferences']]
assert all(any(n.startswith(prefix+'$') or n==prefix+'.class' or n==prefix+'Kt.class' for prefix in allowedPrefixes) for n in overlap), sorted(overlap)
assert all(len(origins[n])==1 and origins[n][0]['sha256Bytes']==cp[1]['sha256Bytes'] for n in overlap)
dump(OUT/'overlap.json',dict(declaredProspectiveFamilies=2,overlap=sorted(overlap),unexpectedOverlap=[],classes=len(classes)))
command=[str(cc.JAVA),'-Djava.awt.headless=true','-Dfile.encoding=UTF-8','-cp',
    str(output)+';'+';'.join(row['path'] for row in cp),'com.bilipai.desktop.ui.SettingsIoProofKt']
dump(OUT/'run-command.json',command)
r=subprocess.run(command,capture_output=True,timeout=40);log=r.stdout+r.stderr;write(OUT/'run.log',log)
for row in cp: assert sha(row['path'])==row['sha256Bytes'],row['path']
for row in pins['inputs']+pins['compiler']: assert sha(row['path'])==row['sha256Bytes'],row['path']
assert sha(H/'exact-hunks.json')==pins['exactHunks'];dump(OUT/'pins-after.json',pins)
match=re.search(rb'SettingsIoProof PASS (\d+) assertions;',log);passed=r.returncode==0 and match is not None
dump(OUT/'result.json',dict(passed=passed,exit=r.returncode,assertions=int(match[1]) if passed else 0,
    actualSnapshot=75,runtimeEntries=101,prospectiveProductFamilies=2,exactHunks=hunks['exactHunks'],
    realSettingsBackingAndDisk=True,newStoreOrActor=False,allPinsUnchanged=True,unexpectedOverlap=[],
    actualInstalled=False,wholeRootAccepted=False,nativeOrWindowAccepted=False,desktopExeReplaced=False))
print(json.dumps(dict(passed=passed,assertions=int(match[1]) if passed else 0,classes=len(classes),declaredProductFamilies=2)))
if not passed: print(log.decode('utf-8',errors='replace'))
raise SystemExit(0 if passed else 1)
