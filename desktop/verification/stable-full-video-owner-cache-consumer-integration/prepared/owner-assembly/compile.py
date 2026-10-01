from pathlib import Path
import hashlib, importlib.util, json, os, subprocess, sys, zipfile
P=Path(__file__).resolve().parent;MAIN=P.parents[2]
SNAP=MAIN/'desktop/.local/stable-product-snapshot-71'
PFX=chr(92)*2+'?'+chr(92)
def wide(p):
 s=os.path.abspath(p);return Path(s if s.startswith(PFX)else PFX+s)
def sha(p):return hashlib.sha256(wide(p).read_bytes()).hexdigest()
def save(p,v):
 wide(p).parent.mkdir(parents=True,exist_ok=True)
 wide(p).write_text(json.dumps(v,indent=2)+'\n',encoding='utf-8',newline='\n')
assert sha(SNAP/'manifest.json')=='416f1ec5fdf577f13c607d52248ca3c97d39420d363bac78f500d119053e6ad1'
assert sha(SNAP/'ordered-runtime-cp.json')=='6ec3765f3ced6095ac01d2c2908550e9487bfcd586c1008ff4a582920f84f55f'
cp=json.loads(wide(SNAP/'ordered-runtime-cp.json').read_text());assert len(cp)==101
def check():
 for r in cp:assert sha(r['path'])==r['sha256Bytes'],r['path']
check()
spec=importlib.util.spec_from_file_location('c',MAIN/'desktop/.local/source9-appearance/compile-miuix.py')
c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
out=P/'runs'/sys.argv[1];assert not wide(out).exists();wide(out).mkdir(parents=True)
sources=sorted((P/'prepared/manual').rglob('*.kt'))
if (P/'generated').exists():sources+=sorted((P/'generated').rglob('*.kt'))
if (P/'prepared/existing/desktop/src').exists():sources+=sorted((P/'prepared/existing/desktop/src').rglob('*.kt'))
if (P/'compile-reference/VideoPlaybackViewModel.kt').exists():sources += [P/'compile-reference/VideoPlaybackViewModel.kt']
if '--fixture' in sys.argv:sources+=[P/'ActionStatusProof.kt']
for p in sources:
 dest=wide(out/'source-inputs'/p.relative_to(P));dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(wide(p).read_bytes())
pins=dict(actual71ManifestSHA256=sha(SNAP/'manifest.json'),actual71OrderedCPSHA256=sha(SNAP/'ordered-runtime-cp.json'),
 runtime=cp,inputs=[dict(path=str(p),sha256Bytes=sha(p))for p in sources],
 compiler=[dict(path=str(p),sha256Bytes=sha(p))for p in [c.JAVA,c.PLUGIN]+c.COMPILER])
save(out/'pins-before.json',pins);jar=out/'candidate.jar'
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows',
 '-Xplugin='+str(c.PLUGIN),'-Xfriend-paths='+cp[1]['path'],'-cp',';'.join(r['path']for r in cp),'-d',str(jar)]+list(map(str,sources))
arg=out/'compile.args';wide(arg).write_text('\n'.join('"'+str(a).replace('\\','/')+'"'for a in args),encoding='utf8')
r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Xmx2g','-cp',';'.join(map(str,c.COMPILER)),
 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(arg)],capture_output=True,timeout=180)
wide(out/'compile.log').write_bytes(r.stdout+r.stderr);check();save(out/'pins-after.json',pins)
assert wide(out/'pins-before.json').read_bytes()==wide(out/'pins-after.json').read_bytes()
classes=[];overlap=[]
if r.returncode==0:
 with zipfile.ZipFile(wide(jar))as z:classes=sorted(n for n in z.namelist()if n.endswith('.class'))
 old=set()
 for row in cp[:3]:
  with zipfile.ZipFile(wide(row['path']))as z:old.update(z.namelist())
 overlap=sorted(set(classes)&old)
 allowed=['com/bilipai/desktop/ui/DesktopOriginalVideoRepositoryBinding','com/bilipai/desktop/data/DesktopRepository','com/bilipai/desktop/data/DesktopSessionEpoch','com/android/purebilibili/feature/video/viewmodel/']
 assert all(any(n==a+'.class' or n.startswith(a+'$') or (a.endswith('/') and n.startswith(a))for a in allowed)for n in overlap),overlap
save(out/'compile-result.json',dict(passed=r.returncode==0,exitCode=r.returncode,inputs=len(sources),classes=len(classes),
 runtimeEntries=101,actual71ProductSnapshot=True,preparedAssemblyOnly=True,declaredFamilyOverrides=overlap,
 undeclaredProductionOverrides=[],wholeOriginalVMReadonlyProjectionOverlay=True,wholeHolderFromActual71=True,
 RootFactoryRuntimeAccepted=False,HTTP=False,native=False,wholeOwnerConstructed=False,
 candidateJarSHA256Bytes=sha(jar)if r.returncode==0 else None))
sys.stdout.reconfigure(encoding='utf8',errors='replace')
print((r.stdout+r.stderr).decode('utf8',errors='replace'));print('Narrow assembly compile',r.returncode,'classes',len(classes))
raise SystemExit(r.returncode)
