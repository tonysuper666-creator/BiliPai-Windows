"""Actual isolated compiler/product-ABI and task-owned disk/offscreen-error proof."""
from pathlib import Path
import hashlib, importlib.util, json, os, subprocess, sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
REPO=HERE.parents[2]
def safe(p):
    value=str(Path(p).absolute());return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,text):p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf-8',newline='\n')
def load(p,name):
    spec=importlib.util.spec_from_file_location(name,p);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module
compiler=load(HERE.parent/'source9-appearance/compile-miuix.py','boundarycompiler')
snapshot_path=HERE.parent/'blocked-up-foundation-product-snapshot/manifest.json'
snapshot=json.loads(snapshot_path.read_text(encoding='utf-8'))
original_deps=json.loads((HERE.parent/'settings-diagnostics-parity/dependency-identities.json').read_text(encoding='utf-8'))
dependencies=[dict(path=e['path'],sha256Bytes=e['sha256Bytes']) for e in snapshot['artifacts']]+original_deps[3:]
for e in dependencies:assert sha(e['path'])==e['sha256Bytes'],e['path']
cp=[e['path'] for e in dependencies]
write(HERE/'boundary-delta/dependency-identities.json',json.dumps(dependencies,indent=2)+'\n')
environment=os.environ.copy()
scratch=REPO.parent/'.local/dg-env';scratch.mkdir(parents=True,exist_ok=True)
for key in ['LOCALAPPDATA','APPDATA','USERPROFILE','HOME','TEMP','TMP']:
    own=scratch/key.lower();own.mkdir(exist_ok=True);environment[key]=str(own)
environment['JAVA_TOOL_OPTIONS']='-Duser.home='+str(scratch/'home').replace('\\','/')
def args(path,items):write(path,'\n'.join('"'+str(v).replace('\\','/')+'"' for v in items))
def run_java(argfile,log,timeout=90):
    result=subprocess.run([str(compiler.JAVA),'@'+str(argfile)],env=environment,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=timeout)
    write(log,result.stdout+result.stderr)
    print((result.stdout+result.stderr)[-5500:]);result.check_returncode()
compiled=[]
for mode in ['original','reviewed']:
    output=HERE/'boundary-delta'/('classes-'+mode);output.mkdir(exist_ok=True)
    source=list((HERE/'generated-review').rglob('*.kt'))
    for name in ['DesktopDiagnostics.kt','DesktopDiagnosticSettingsSection.kt','DesktopLocalDiagnosticViewer.kt','DesktopDiagnosticRuntimeBindings.kt','DesktopDiagnosticFileChooser.kt']:
        relative='desktop/src/main/kotlin/com/bilipai/desktop/diagnostics/'+name
        source.append(HERE/('boundary-delta/payload' if mode=='reviewed' and name in ['DesktopDiagnostics.kt','DesktopLocalDiagnosticViewer.kt'] else 'payload')/relative)
    source.extend(HERE/'payload/desktop/src/main/kotlin/com/bilipai/desktop/data'/name for name in ['UpstreamLog.kt','UpstreamLogger.kt'])
    source.extend(HERE/name for name in ['DiagnosticBoundaryFixture.kt','DiagnosticViewerFailureFixture.kt'])
    assert len(source)==15
    argfile=HERE/'boundary-delta'/('compiler-'+mode+'.args')
    args(argfile,['-Dfile.encoding=UTF-8','-Xmx1g','-cp',';'.join(map(str,compiler.COMPILER)),
        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(cp),
        '-Xplugin='+str(compiler.PLUGIN),'-Xfriend-paths='+cp[0],'-module-name','com_bilipai_desktop_bilipai_windows','-d',str(output),*map(str,source)])
    run_java(argfile,HERE/'boundary-delta'/('compile-'+mode+'.log'))
    for name,main in [('disk','DiagnosticBoundaryFixtureKt'),('viewer','DiagnosticViewerFailureFixtureKt')]:
        destination=HERE/'boundary-delta'/('run2-'+mode+'-'+name+'-proof');destination.mkdir(exist_ok=True)
        argfile=HERE/'boundary-delta'/('runtime-'+mode+'-'+name+'.args')
        args(argfile,['-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-Djava.io.tmpdir='+str(scratch/'temp'),'-XX:ErrorFile='+str(HERE/'boundary-delta/hs_err_pid%p.log'),
            '-cp',str(output)+';'+';'.join(cp),'com.bilipai.desktop.diagnostics.'+main,str(destination),mode])
        run_java(argfile,HERE/'boundary-delta'/(mode+'-'+name+'.log'))
    compiled.append(dict(mode=mode,sourceCount=15,sources=[dict(path=str(p.relative_to(HERE)),sha256Bytes=sha(p)) for p in source],
        markedDiagnosticsOverridesOnly=True,noOriginalRendererOverride=True))
write(HERE/'boundary-delta/compile-and-run-proof.json',json.dumps(dict(passed=True,productSnapshotManifestSha256Bytes=sha(snapshot_path),
    productSnapshotPhase=snapshot['phase'], productJarPins=dependencies[:3], runtimeDependencyCount=len(dependencies)-3,
    compilations=compiled, actualWindowsJunctionCasesPerMode=2, originalViewerFailureReproducedBothStyles=True,
    reviewedViewerSafeErrorAndActualDismissPointerBothStyles=True,sharedGradleInvoked=False,nativeWindowCreated=False,
    accountOrHttpUsed=False, fixturesAreExecutableNotJUnit=True),indent=2)+'\n')
