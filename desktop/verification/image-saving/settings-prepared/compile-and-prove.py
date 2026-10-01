"""Compile selected original UI against Main03; use existing real Store with task temp fixtures."""
from pathlib import Path
import hashlib, importlib.util, json, subprocess, sys, zipfile
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2]
PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
    p=str(p);return p if p.startswith(PREFIX) else PREFIX+p
def sha(p):
    with open(safe(p),'rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def read(p):
    with open(safe(p),encoding='utf-8-sig') as f:return f.read()
def write(p,s):
    with open(safe(p),'w',encoding='utf-8',newline='\n') as f:f.write(s)
def dump(p,s):write(p,json.dumps(s,indent=2,ensure_ascii=False)+'\n')
snapshot=REPO/'desktop/.local/dynamic-media-main-integration/main-product-snapshot-03'
assert sha(snapshot/'manifest.json')=='f9d91763db17acaa59753f5dccad4feb88512c8ad2984062520ed867943df6a6'
assert sha(snapshot/'ordered-runtime-cp.json')=='3abb7e2fecccb5bd0693e6b0868d002b576fc8f689d734d86bd5737fe1c39cac'
cp=json.loads(read(snapshot/'ordered-runtime-cp.json'));assert len(cp)==92
for p in cp:assert sha(p['path'])==p['sha256Bytes'],p['path']
spec=importlib.util.spec_from_file_location('compile_base',REPO/'desktop/.local/source9-appearance/compile-miuix.py');mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod)
bridge=REPO/'desktop/.local/dynamic-detail-reply-parity/detail-container-next/static-save-location-next/prepared/desktop/src/main/kotlin/com/bilipai/desktop/settings/DesktopImageSaveLocationPreferences.kt'
assert sha(bridge)=='83df4f8424f22c8ee00d81f3de4a79dc658206c1bf5aa65b683f3ed9a372d370'
sources=list((HERE/'generated').rglob('*.kt'))+list((HERE/'prepared/desktop/src').rglob('*.kt'))+[bridge,HERE/'SettingsActionFixture.kt']
assert len(sources)==5
output=HERE/'settings-image-path-candidate.jar'
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(p['path'] for p in cp),'-Xplugin='+str(mod.PLUGIN),'-Xfriend-paths='+cp[1]['path'],'-module-name','com_bilipai_desktop_bilipai_windows','-d',str(output)]+list(map(str,sources))
write(HERE/'compile-01.args','\n'.join('"'+str(a).replace('\\','/')+'"' for a in args)+'\n')
cmd=[str(mod.JAVA),'-Xmx2g','-cp',';'.join(map(str,mod.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(HERE/'compile-01.args')]
r=subprocess.run(cmd,capture_output=True,text=True,encoding='utf-8',timeout=120);write(HERE/'compile-01.log',r.stdout+r.stderr);print(r.stdout+r.stderr);r.check_returncode()
with zipfile.ZipFile(safe(output)) as z:entries=set(n for n in z.namelist() if n.endswith('.class'))
overlap=[]
for p in cp[:3]:
    with zipfile.ZipFile(safe(p['path'])) as z:overlap+=sorted(entries&set(z.namelist()))
assert not overlap,overlap
dump(HERE/'compile-evidence.json',{'mainManifestSha256':sha(snapshot/'manifest.json'),'runtimeCpSha256':sha(snapshot/'ordered-runtime-cp.json'),'candidateJarSha256':sha(output),'compiledSourceCount':len(sources),'classFqnOverlap':overlap,'sources':[{'path':str(p),'sha256Bytes':sha(p)} for p in sources],'actualMainAcceptance':False,'dependenciesAdded':False})
runtime=';'.join([str(output)]+[p['path'] for p in cp]);temp=HERE/'task-store'
cmd=[str(mod.JAVA),'-cp',runtime,'com.bilipai.desktop.settings.SettingsActionFixtureKt',str(temp)]
r=subprocess.run(cmd,capture_output=True,text=True,encoding='utf-8',timeout=30);write(HERE/'action-01.log',r.stdout+r.stderr);dump(HERE/'action-process.json',{'command':cmd,'exitCode':r.returncode});print(r.stdout+r.stderr);r.check_returncode();dump(HERE/'action-proof.json',json.loads(r.stdout.strip()))
for p in cp:assert sha(p['path'])==p['sha256Bytes'],p['path']
print('PASS: 5 action/store cases; actual chooser/UI window not tested; Main03 immutable 92CP unchanged.')
