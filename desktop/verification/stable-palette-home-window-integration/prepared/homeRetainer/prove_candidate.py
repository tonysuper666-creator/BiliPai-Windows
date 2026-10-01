from pathlib import Path
import hashlib, json, importlib.util, subprocess, zipfile, os, sys

HERE=Path(__file__).resolve().parent
MAIN=HERE.parents[2]
PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
    s=os.path.abspath(p)
    return Path(s if s.startswith(PREFIX) else PREFIX+s)
def sha(p): return hashlib.sha256(safe(p).read_bytes()).hexdigest()
snap=MAIN/'desktop/.local/stable-product-snapshot-42'
assert sha(snap/'manifest.json')=='d84593c1a3dc908015f0526ae17375027082d91a9eba12e81b70dadde9779d54'
assert sha(snap/'ordered-runtime-cp.json')=='6fb3e971ee12e0f77c7a7dd6e54a4af64456b3e8bab8446660c1f10947b2d63c'
cp=json.loads(safe(snap/'ordered-runtime-cp.json').read_text(encoding='utf-8'))
for r in cp: assert sha(r['path'])==r['sha256Bytes']
spec=importlib.util.spec_from_file_location('cc',MAIN/'desktop/.local/source9-appearance/compile-miuix.py')
cc=importlib.util.module_from_spec(spec);spec.loader.exec_module(cc)
candidate=HERE/'compile-04/candidate.jar'
with zipfile.ZipFile(safe(candidate),'w',zipfile.ZIP_DEFLATED) as z:
    root=HERE/'compile-04/classes'
    for p in root.rglob('*'):
        if safe(p).is_file(): z.write(safe(p),p.relative_to(root).as_posix())
run=HERE/('proof-'+sys.argv[1] if len(sys.argv)>1 else 'proof-02');run.mkdir(exist_ok=False)
source=HERE/'fixtures/HomeRetirementFixture.kt'
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows',
 '-Xfriend-paths='+str(snap/'main-kotlin.jar')+','+str(candidate),
 '-cp',str(candidate)+';'+ ';'.join(r['path'] for r in cp),'-d',str(run/'classes'),str(source)]
safe(run/'compile.args').write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args),encoding='utf-8')
r=subprocess.run([str(cc.JAVA),'-Dfile.encoding=UTF-8','-Xmx2g','-cp',';'.join(map(str,cc.COMPILER)),
 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(run/'compile.args')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=180)
safe(run/'compile.log').write_text(r.stdout+r.stderr,encoding='utf-8')
binding={'candidateSha':sha(candidate),'actualManifestSha':sha(snap/'manifest.json'),'cpSha':sha(snap/'ordered-runtime-cp.json'),
 'fixtureSha':sha(source),'compileExit':r.returncode,'productOverrideCount':2,'prospectiveInputCount':1,'allowedProductFamilies':['DesktopTodayWatchRepository','DesktopPluginRuntime'],
 'mainMounted':False,'nativeWindow':False,'originalBusinessRequest':False}
if r.returncode==0:
    command=[str(cc.JAVA),'-Dfile.encoding=UTF-8','-cp',str(run/'classes')+';'+str(candidate)+';'+ ';'.join(r['path'] for r in cp),
      'com.bilipai.desktop.ui.HomeRetirementFixtureKt',str(candidate)]
    rr=subprocess.run(command,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90)
    safe(run/'run.log').write_text(rr.stdout+rr.stderr,encoding='utf-8')
    binding['runExit']=rr.returncode
    print((rr.stdout+rr.stderr).encode('ascii','backslashreplace').decode())
else: print((r.stdout+r.stderr).encode('ascii','backslashreplace').decode())
safe(run/'source-binding.json').write_text(json.dumps(binding,indent=2)+'\n',encoding='utf-8')
for row in cp: assert sha(row['path'])==row['sha256Bytes']
assert r.returncode==0 and binding.get('runExit')==0,binding
