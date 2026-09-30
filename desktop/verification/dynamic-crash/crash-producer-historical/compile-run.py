"""Compile only the unique prepared crash-prompt slice and offline fixture, no Gradle."""
from pathlib import Path
import argparse,hashlib,importlib.util,json,os,subprocess,sys,zipfile
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
def safe(p):
    value=str(Path(p).absolute());return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,t):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_bytes(t.encode('utf-8'))
def args(p,items):write(p,'\n'.join('"'+str(i).replace('\\','/')+'"' for i in items))
def load(p,n):
    s=importlib.util.spec_from_file_location(n,p);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
p=argparse.ArgumentParser();p.add_argument('--run-name',required=True);a=p.parse_args();assert a.run_name.replace('-','').isalnum()
snapshot=HERE.parent/'diagnostics-viewer-product-snapshot/manifest.json'
snapshotPin='5065fe8052e80c0c73f6342a4afc03fd62814fb0a144b6de46feeb5ddce1d3ae';assert sha(snapshot)==snapshotPin
entries=json.loads(safe(snapshot).read_text(encoding='utf-8'))['artifacts']
dependencies=entries+json.loads((HERE.parent/'settings-diagnostics-parity/dependency-identities.json').read_text())[3:]
for e in dependencies:assert sha(e['path'])==e['sha256Bytes'],e['path']
cp=[e['path'] for e in dependencies]
sources=sorted((HERE/'generated/sources').rglob('*.kt'))+sorted((HERE/'prepared').glob('*.kt'))+[HERE/'CrashPromptFixture.kt']
assert len(sources)==5
with zipfile.ZipFile(safe(cp[0])) as jar:
    names=set(jar.namelist());module=Path(next(n for n in names if n.startswith('META-INF/') and n.endswith('.kotlin_module'))).stem
    for candidate in ['com/android/purebilibili/CrashLogPromptAction.class','com/android/purebilibili/DesktopCrashLogPromptPolicyKt.class','com/android/purebilibili/DesktopPendingCrashLogPromptKt.class','com/bilipai/desktop/diagnostics/DesktopCrashPromptController.class','com/bilipai/desktop/diagnostics/DesktopCrashPromptHostKt.class']:assert candidate not in names,candidate
out=HERE/a.run_name;assert not out.exists(),'Never overwrite evidence';out.mkdir();classes=out/'classes';classes.mkdir()
compiler=load(HERE.parent/'source9-appearance/compile-miuix.py','crashcompiler')
scratch=HERE.parents[3]/'.local'/('cp-'+a.run_name);scratch.mkdir(parents=True,exist_ok=True);environment=os.environ.copy()
for key in ['LOCALAPPDATA','APPDATA','USERPROFILE','HOME','TEMP','TMP']:
    value=scratch/key.lower();value.mkdir(exist_ok=True);environment[key]=str(value)
environment['JAVA_TOOL_OPTIONS']='-Duser.home='+str(scratch/'home').replace('\\','/')
def run(file,log):
    r=subprocess.run([str(compiler.JAVA),'@'+str(file)],env=environment,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=100)
    write(log,r.stdout+r.stderr);print((r.stdout+r.stderr)[-6000:]);r.check_returncode()
file=out/'compile.args';args(file,['-Dfile.encoding=UTF-8','-Xmx1g','-cp',';'.join(map(str,compiler.COMPILER)),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(cp),
    '-Xplugin='+str(compiler.PLUGIN),'-Xfriend-paths='+cp[0],'-module-name',module,'-d',str(classes)]+sources)
run(file,out/'compile.log')
for case in ['disk','material3','miuix']:
    destination=out/case;destination.mkdir();file=out/(case+'.args')
    args(file,['-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-Djava.security.manager=allow','-Djava.io.tmpdir='+str(scratch/'temp'),
        '-XX:ErrorFile='+str(out/'hs_err_pid%p.log'),'-cp',';'.join(cp[:3]+[str(classes)]+cp[3:]),
        'com.bilipai.desktop.diagnostics.crashproof.CrashPromptFixtureKt',case,str(destination)])
    run(file,out/(case+'.log'))
write(out/'dependency-identities.json',json.dumps(dependencies,indent=2)+'\n')
write(out/'compile-evidence.json',json.dumps(dict(passed=True,snapshotManifestSha256Bytes=snapshotPin,productJarsFirst=True,
    preparedUniqueSourceCount=4,fixtureSourceCount=1,existingProductClassesOverridden=False,
    originalGeneratedSources=2,sourcePins=[dict(path=str(s.relative_to(HERE)),sha256Bytes=sha(s)) for s in sources],
    noSharedGradle=True,noHWND=True,noAccountsOrNetworking=True),indent=2)+'\n')
