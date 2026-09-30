"""Own one invisible ComposeWindow to read its HWND owner; never call any share API."""
from pathlib import Path
import hashlib,importlib.util,json,os,subprocess,sys,tempfile,zipfile
sys.dont_write_bytecode=True
sys.stdout.reconfigure(encoding='utf-8',errors='replace')
HERE=Path(__file__).resolve().parent;BASE=HERE.parent/'native-share-main-product-snapshot-01'
SNAP='4e97c4c054a7ec0ed8f064319009bcfb9b68f9c191ef089a6bf677f163df959f'
CP='a733a3435e18c5dac83ff155023c36e6d0d1997822b0a6b77ba7d4efeff63fcc'
def ext(p):
    s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def sha(p):return hashlib.sha256(ext(p).read_bytes()).hexdigest()
def save(p,v):ext(p).write_text(json.dumps(v,indent=2)+'\n',encoding='utf-8',newline='\n')
def args(p,values):ext(p).write_text('\n'.join('"'+str(v).replace('\\','/')+'"' for v in values)+'\n',encoding='utf-8',newline='\n')
assert sha(BASE/'manifest.json')==SNAP and sha(BASE/'ordered-runtime-cp.json')==CP
doc=json.loads((BASE/'manifest.json').read_bytes());raw=json.loads((BASE/'ordered-runtime-cp.json').read_bytes());rows=raw if isinstance(raw,list) else raw['entries']
assert len(rows)==89
for r in rows:assert sha(r['path'])==r['sha256Bytes']
all_entries=set();pins=[]
wanted={'androidx.compose.ui.awt.ComposeWindow','com.sun.jna.Native','com.sun.jna.ptr.IntByReference'}
for row in rows:
    with zipfile.ZipFile(ext(row['path'])) as jar:
        entries=set(jar.namelist());all_entries.update(entries)
        for n in list(wanted):
            e=n.replace('.','/')+'.class'
            if e in entries:pins.append({'class':n,'codeSource':row['path'],'sha256Bytes':hashlib.sha256(jar.read(e)).hexdigest()});wanted.remove(n)
assert not wanted
module=HERE.parent/'source9-appearance/compile-miuix.py';spec=importlib.util.spec_from_file_location('owner_probe_compiler',module);c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
out=HERE/'attempt03';assert not out.exists(),'Probe attempts are immutable';out.mkdir()
source=HERE/'HwndOwnerProbe.kt';fixture=out/'fixture-only.jar'
frozen=out/'frozen-fixture-sources';frozen.mkdir()
for file in [source,HERE/'runner.py']:(frozen/file.name).write_bytes(file.read_bytes())
save(out/'input-pins.json',{'snapshotSha256Bytes':SNAP,'runtimeCpSha256Bytes':CP,'orderedDependencies':rows,'fixtureSourceSha256Bytes':sha(source),'runnerSha256Bytes':sha(HERE/'runner.py'),'nativeCppGuardSourceSha256Bytes':sha(HERE.parents[2]/'desktop/native/diagnostic-share/DesktopDiagnosticShare.cpp'),'MainOrShellExecution':False})
save(out/'product-and-library-class-pins.json',pins)
cp=';'.join(r['path'] for r in rows)
args(out/'compile.args',['-no-stdlib','-no-reflect','-jvm-target','21','-cp',cp,'-Xplugin='+str(c.PLUGIN),'-module-name','native_share_hwnd_owner_probe','-d',fixture,source])
r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Xmx2g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compile.args')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90)
(out/'compile.log').write_text(r.stdout+r.stderr,encoding='utf-8');print(r.stdout+r.stderr);r.check_returncode()
with zipfile.ZipFile(ext(fixture)) as jar:
    classes=[n for n in jar.namelist() if n.endswith('.class')];assert classes and not(set(classes)&all_entries)
    assert all(n.startswith('com/bilipai/desktop/diagnostics/hwndownerprobe/') for n in classes)
    save(out/'fixture-class-identities.json',[{'entry':n,'sha256Bytes':hashlib.sha256(jar.read(n)).hexdigest()} for n in jar.namelist()])
sandbox=Path(tempfile.mkdtemp(prefix='bp-hwnd-'));env=os.environ.copy()
for key in ['APPDATA','LOCALAPPDATA','USERPROFILE','TEMP','TMP']:
    d=sandbox/key.lower();d.mkdir();env[key]=str(d)
save(out/'task-owned-environment.json',{'path':str(sandbox),'shortSkikoHome':True,'fixtureJarSha256Bytes':sha(fixture),'productionOverrides':0})
proof=out/'proof';proof.mkdir()
args(out/'run.args',['-Dfile.encoding=UTF-8','-Dsun.stdout.encoding=UTF-8','-Dsun.stderr.encoding=UTF-8','-Djava.awt.headless=false','-Dskiko.renderApi=SOFTWARE','-Djava.security.manager=allow','-Duser.home='+str(sandbox),'-Djava.io.tmpdir='+str(sandbox/'temp'),'-cp',';'.join([str(fixture),cp]),'com.bilipai.desktop.diagnostics.hwndownerprobe.HwndOwnerProbeKt',proof,out/'product-and-library-class-pins.json'])
with (out/'run.log').open('wb') as log:
    process=subprocess.Popen([str(c.JAVA),'@'+str(out/'run.args')],stdout=log,stderr=subprocess.STDOUT,env=env,cwd=HERE)
    save(out/'task-process.json',{'pid':process.pid,'java':str(c.JAVA),'ownJVMOnly':True})
    try:
        code=process.wait(timeout=20)
    except subprocess.TimeoutExpired:
        stack=subprocess.run([str(c.JAVA.with_name('jstack.exe')),str(process.pid)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=15)
        (out/'own-jvm-stack.log').write_text(stack.stdout+stack.stderr,encoding='utf-8')
        try:code=process.wait(timeout=25)
        except subprocess.TimeoutExpired:
            process.kill();process.wait();save(out/'run-timeout.json',{'passed':False,'ownJVMKilled':True,'ownJVMOnly':True,'ShareShow':False,'reason':'Hidden window fixture did not complete; inspect stage and own JVM stack.'});code=-999
print((out/'run.log').read_text(encoding='utf-8',errors='replace'));assert code==0,('Probe JVM did not complete',code)
for row in rows:assert sha(row['path'])==row['sha256Bytes']
result=json.loads((proof/'result.json').read_bytes());assert result['passed']
print('PASS own invisible ComposeWindow HWND owner probe; sameThread =',result['sameThread'])
