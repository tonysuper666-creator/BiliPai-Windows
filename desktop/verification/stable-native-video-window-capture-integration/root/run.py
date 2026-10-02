from pathlib import Path
import hashlib, importlib.util, json, re, subprocess, sys, zipfile
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
sys.dont_write_bytecode = True
H = Path(__file__).resolve().parent
MAIN = H.parents[2]
S = MAIN/'desktop/.local/stable-product-snapshot-79'
RUN = int(sys.argv[1]) if len(sys.argv)>1 else 1
OUT = H/f'runs/{RUN:02}'
assert not OUT.exists()
OUT.mkdir(parents=True)

def wide(p):
    s=str(Path(p).absolute()); prefix=chr(92)*2+'?'+chr(92)
    return Path(s if s.startswith(prefix) else prefix+s)
def read(p): return wide(p).read_bytes()
def sha(p): return hashlib.sha256(read(p)).hexdigest()
def write(p,b):
    wide(p).parent.mkdir(parents=True, exist_ok=True)
    wide(p).write_bytes(b)
def dump(p,v): write(p,(json.dumps(v,indent=2)+'\n').encode())

assert sha(S/'manifest.json')=='9f489f0e107470b79209ce8fa6a54e34050faadef335c831351afc8cc2813b8e'
assert sha(S/'ordered-runtime-cp.json')=='66b099ef5a956392dbb4672f8f77ace7aee33bd73f1c434f34d23f0b6b5d0d9b'
cp=json.loads(read(S/'ordered-runtime-cp.json'))
assert len(cp)==101
for row in cp: assert sha(row['path'])==row['sha256Bytes']
source=OUT/'inputs/CaptureOwnerProof.kt'
write(source,read(H/'CaptureOwnerProof.kt'))
spec=importlib.util.spec_from_file_location('cc',MAIN/'desktop/.local/source9-appearance/compile-miuix.py')
cc=importlib.util.module_from_spec(spec); spec.loader.exec_module(cc)
pins=dict(actualSnapshot=79,manifest=sha(S/'manifest.json'),orderedCP=sha(S/'ordered-runtime-cp.json'),runtime=cp,
    inputs=[dict(path=str(source),sha256Bytes=sha(source))],
    compiler=[dict(path=str(p),sha256Bytes=sha(p)) for p in [cc.JAVA]+cc.COMPILER])
dump(OUT/'pins-before.json',pins)
output=OUT/'fixture.jar'
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows',
    '-Xfriend-paths='+cp[1]['path'],'-cp',';'.join(r['path'] for r in cp),'-d',str(output),str(source)]
write(OUT/'compile.args','\n'.join('"'+a.replace('\\','/')+'"' for a in args).encode())
command=[str(cc.JAVA),'-Xmx1g','-Dfile.encoding=UTF-8','-cp',';'.join(map(str,cc.COMPILER)),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(OUT/'compile.args')]
dump(OUT/'compile-command.json',command)
r=subprocess.run(command,capture_output=True,timeout=120)
write(OUT/'compile.log',r.stdout+r.stderr)
if r.returncode:
    dump(OUT/'result.json',dict(exit=r.returncode,compilerPassed=False,productionOverrides=0))
    print((r.stdout+r.stderr).decode('utf-8',errors='replace')); raise SystemExit(r.returncode)

exercised=['com/bilipai/desktop/ui/'+n+'.class' for n in
    ['DesktopWindowsVideoCaptureOwner','DesktopWindowsVideoCaptureState']]
product=set(); origins={}
for row in cp:
    with zipfile.ZipFile(wide(row['path'])) as archive:
        names=set(archive.namelist()); product.update(n for n in names if n.endswith('.class'))
        for name in exercised:
            if name in names: origins.setdefault(name,[]).append(row)
with zipfile.ZipFile(wide(output)) as archive:
    classes={n for n in archive.namelist() if n.endswith('.class')}
assert product.isdisjoint(classes)
assert len(origins)==len(exercised) and all(len(v)==1 and v[0]['sha256Bytes']==cp[1]['sha256Bytes'] for v in origins.values())
dump(OUT/'class-origins.json',origins)
dump(OUT/'overlap.json',dict(productClassOverlap=[],fixtureClasses=len(classes)))
command=[str(cc.JAVA),'-Djava.awt.headless=false','-Dfile.encoding=UTF-8','-cp',str(output)+';'+';'.join(r['path'] for r in cp),
    'com.bilipai.desktop.ui.CaptureOwnerProofKt']
dump(OUT/'run-command.json',command)
r=subprocess.run(command,capture_output=True,timeout=40)
log=r.stdout+r.stderr; write(OUT/'run.log',log)
for row in cp: assert sha(row['path'])==row['sha256Bytes']
for row in pins['inputs']+pins['compiler']: assert sha(row['path'])==row['sha256Bytes']
dump(OUT/'pins-after.json',pins)
match=re.search(rb'CaptureOwnerProof PASS (\d+) assertions;',log)
passed=r.returncode==0 and match is not None
report=dict(passed=passed,exit=r.returncode,assertions=int(match[1]) if passed else 0,
    actualSnapshot=79,runtimeEntries=101,productionOverrides=0,fixtureSourceCount=1,fixtureClasses=len(classes),
    uniqueInstalledProductClassOrigins=len(origins),pinsUnchanged=True,realWindowsDisplayAffinityApi=True,
    realOwnedHiddenHwndReadback=True,osInputInjected=False,foreignApplicationWindowsOperated=False,
    fullWindowPortRuntimeAccepted=False,physicalCapturePixelsAccepted=False,completeRootMounted=False,
    desktopExeReplaced=False)
dump(OUT/'result.json',report)
print(json.dumps({k:report[k] for k in ['passed','assertions','productionOverrides','realOwnedHiddenHwndReadback','fixtureClasses']}))
if not passed: print(log.decode('utf-8',errors='replace'))
raise SystemExit(0 if passed else 1)
