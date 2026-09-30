"""Requires Root's explicitly pinned immutable snapshot; compiles fixture sources only."""
from pathlib import Path
import argparse,hashlib,importlib.util,json,os,subprocess,sys,zipfile
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
def safe(p):
    value=str(Path(p).absolute());return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,text):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_bytes(text.encode('utf-8'))
def args(p,items):write(p,'\n'.join('"'+str(x).replace('\\','/')+'"' for x in items))
def load(p,name):
    spec=importlib.util.spec_from_file_location(name,p);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--snapshot',type=Path,required=True)
parser.add_argument('--snapshot-sha',required=True)
parser.add_argument('--worker-root',type=Path,required=True)
parser.add_argument('--run-name',required=True)
options=parser.parse_args()
if not options.run_name.replace('-','').isalnum():raise ValueError('Invalid task-owned run name')
if sha(options.snapshot)!=options.snapshot_sha:raise ValueError('Root snapshot manifest changed')
snapshot=json.loads(safe(options.snapshot).read_text(encoding='utf-8'))
product=snapshot['artifacts']
if len(product)!=3:raise ValueError('Expected exactly three immutable product jars')
for e in product:
    if sha(e['path'])!=e['sha256Bytes']:raise ValueError('Immutable product jar changed')
historical=json.loads((HERE.parent/'settings-diagnostics-parity/dependency-identities.json').read_text(encoding='utf-8'))
dependencies=[dict(path=e['path'],sha256Bytes=e['sha256Bytes']) for e in product]+historical[3:]
for e in dependencies:
    if sha(e['path'])!=e['sha256Bytes']:raise ValueError('Fixed runtime dependency changed')
cp=[e['path'] for e in dependencies]
with zipfile.ZipFile(safe(product[0]['path'])) as jar:
    metadata=[n for n in jar.namelist() if n.startswith('META-INF/') and n.endswith('.kotlin_module')]
    if len(metadata)!=1:raise ValueError('Product Kotlin module identity is ambiguous')
    module=Path(metadata[0]).stem
source=HERE/'DiagnosticProductLifecycleFixture.kt'
if not source.exists():raise ValueError('Product ABI-specific fixture is not prepared yet')
if 'package com.bilipai.desktop.diagnostics.proof' not in source.read_text(encoding='utf-8'):
    raise ValueError('Only the uniquely named fixture package may be compiled')
out=HERE/options.run_name
if out.exists():raise ValueError('Existing evidence run is never overwritten; choose a new reviewed run name')
out.mkdir();classes=out/'classes';classes.mkdir()
write(out/'dependency-identities.json',json.dumps(dependencies,indent=2)+'\n')
compiler=load(HERE.parent/'source9-appearance/compile-miuix.py','productcompiler')
environment=os.environ.copy()
scratch=HERE.parents[3]/'.local'/('dgl-'+options.run_name)
scratch.mkdir(parents=True,exist_ok=True)
for key in ['LOCALAPPDATA','APPDATA','USERPROFILE','HOME','TEMP','TMP']:
    p=scratch/key.lower();p.mkdir(exist_ok=True);environment[key]=str(p)
environment['JAVA_TOOL_OPTIONS']='-Duser.home='+str(scratch/'home').replace('\\','/')
def run(argfile,log,timeout=90):
    r=subprocess.run([str(compiler.JAVA),'@'+str(argfile)],env=environment,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=timeout)
    write(log,r.stdout+r.stderr);print((r.stdout+r.stderr)[-5000:]);r.check_returncode()
argfile=out/'compiler.args'
args(argfile,['-Dfile.encoding=UTF-8','-Xmx1g','-cp',';'.join(map(str,compiler.COMPILER)),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(cp),
    '-Xplugin='+str(compiler.PLUGIN),'-Xfriend-paths='+cp[0],'-module-name',module,'-d',str(classes),str(source)])
run(argfile,out/'compile.log')
cases=['startup','observers','accepted-writer','close-bypass','hook-failure','root-startup-material3','root-startup-miuix']
for case in cases:
    destination=out/case;destination.mkdir()
    runtime=cp[:3]+[str(classes)]+cp[3:]
    argfile=out/(case+'.args')
    args(argfile,['-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-Djava.security.manager=allow','-Djava.io.tmpdir='+str(scratch/'temp'),
        '-XX:ErrorFile='+str(out/'hs_err_pid%p.log'),'-Dbilipai.js.workerResources='+str(options.worker_root.resolve()),
        '-cp',';'.join(runtime),'com.bilipai.desktop.diagnostics.proof.DiagnosticProductLifecycleFixtureKt',case,str(destination)])
    run(argfile,out/(case+'.log'),timeout=90)
write(out/'compile-evidence.json',json.dumps(dict(passed=True,snapshotManifestSha256Bytes=options.snapshot_sha,
    productJarPins=dependencies[:3],productJarsFirstInCompilerAndRuntimeClasspath=True,moduleName=module,
    fixtureSourceSha256Bytes=sha(source),compiledFixtureSourceCount=1,noProductClassOverrides=True,
    productLifecycleAndRuntimeHookExecuted=True,freshJvmPerRuntimeCase=True,scenarioCount=len(cases),
    nativeWindowCreated=False,sharedGradleInvoked=False,realAccountOrHttpUsed=False),indent=2)+'\n')
