from pathlib import Path
import hashlib,importlib.util,json,os,subprocess,sys
sys.dont_write_bytecode=True
sys.stdout.reconfigure(encoding='utf-8',errors='replace')
HERE=Path(__file__).resolve().parent;ROOT=HERE.parents[2]
def ext(p):
    v=str(p.absolute());return Path(v if v.startswith('\\\\?\\') else '\\\\?\\'+v)
def sha(p):return hashlib.sha256(ext(p).read_bytes()).hexdigest()
def write(n,t):(HERE/n).write_text(t,encoding='utf-8',newline='\n')
snapshot=ROOT/'desktop/.local/blocked-up-final-product-snapshot/manifest.json'
assert sha(snapshot)=='38efc073ee76d2148e700582e0be468d4c0eea24cea1b5111943ac865402a919'
product=json.loads(snapshot.read_text())
producer=ROOT/'desktop/.local/settings-diagnostics-parity'
review=ROOT/'desktop/.local/diagnostics-main-review'
deps=[dict(path=r['path'],sha256Bytes=r['sha256Bytes']) for r in product['artifacts']]+json.loads((producer/'dependency-identities.json').read_text())[3:]
for r in deps:assert sha(Path(r['path']))==r['sha256Bytes'],r['path']
cp=[r['path'] for r in deps];write('dependency-identities.json',json.dumps(deps,indent=2)+'\n')
spec=importlib.util.spec_from_file_location('diag_junit_compiler',ROOT/'desktop/.local/source9-appearance/compile-miuix.py')
c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
originals=[producer/'generated/com/android/purebilibili/core/util'/n for n in ['DesktopDiagnosticPolicy.kt','DesktopDiagnosticCollector.kt','DesktopLocalCrashPolicy.kt']]
originals += [producer/'generated/com/bilipai/desktop/diagnostics/DesktopDiagnosticSettings.kt']
overrides=[review/'boundary-delta/payload/desktop/src/main/kotlin/com/bilipai/desktop/diagnostics/DesktopDiagnostics.kt',
 producer/'DesktopDiagnosticRuntimeBindings.kt',producer/'bridge-draft/UpstreamLog.kt',producer/'bridge-draft/UpstreamLogger.kt']
assert sha(overrides[0])=='72d17a4a65edd569de5f1d620b1a6362cf9cd7aafa7d581dc5de3fbe81ee6205'
test=HERE/'prepared/desktop/src/test/kotlin/com/bilipai/desktop/diagnostics/DesktopDiagnosticsTest.kt'
runner=(ROOT/'desktop/.local/backup-restore-exit-barrier/RunFixture.kt').read_text().replace('package com.bilipai.desktop.backup','package com.bilipai.desktop.diagnostics')
write('RunFixture.kt',runner)
sources=originals+overrides+[test,HERE/'RunFixture.kt']
out=HERE/'classes';out.mkdir(exist_ok=True)
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(cp),'-Xfriend-paths='+','.join(cp[:3]),
      '-module-name','desktop_diagnostics_junit_proof','-d',str(out),*map(str,sources)]
write('compiler.args','\n'.join('"'+a.replace('\\','/')+'"' for a in args))
run=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Xmx2g','-cp',';'.join(map(str,c.COMPILER)),
 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(HERE/'compiler.args')],cwd=ROOT,
 capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90)
write('compile.log',run.stdout+run.stderr);print((run.stdout+run.stderr)[-8500:]);run.check_returncode()
scratch=HERE/'owned-env';scratch.mkdir(exist_ok=True)
env=os.environ.copy()
for key in ['APPDATA','LOCALAPPDATA','USERPROFILE','TEMP','TMP']:
    own=scratch/key.lower();own.mkdir(exist_ok=True);env[key]=str(own)
report=HERE/'junit-report.json'
if report.exists():report.unlink()
args=['-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-Duser.home='+str(scratch),'-Djava.io.tmpdir='+str(scratch/'temp'),
 '-cp',str(out)+';'+';'.join(cp),'com.bilipai.desktop.diagnostics.RunFixtureKt',str(report),'com.bilipai.desktop.diagnostics.DesktopDiagnosticsTest']
write('runtime.args','\n'.join('"'+a.replace('\\','/')+'"' for a in args))
run=subprocess.run([str(c.JAVA),'@'+str(HERE/'runtime.args')],cwd=ROOT,env=env,
 capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=80)
write('runtime.log',run.stdout+run.stderr);print((run.stdout+run.stderr)[-10000:]);run.check_returncode()
result=json.loads(report.read_text());assert result['junitDiscoveredTests']==18 and result['actualAnnotatedMethods']==18 and result['junitSkippedTests']==0
write('compile-evidence.json',json.dumps(dict(passed=True,sharedGradleInvoked=False,nativeWindowCreated=False,accountOrHttpUsed=False,
    snapshotManifestSha256Bytes=sha(snapshot),snapshotPhase=product['phase'],productJarPins=deps[:3],
    noPluginStoreOrPlayerOverride=True,diagnosticOverrides=[dict(path=str(p.relative_to(ROOT)),sha256Bytes=sha(p)) for p in overrides],
    generatedOriginalPolicies=[dict(path=str(p.relative_to(ROOT)),sha256Bytes=sha(p)) for p in originals],
    testSha256Bytes=sha(test),junitDiscovered=18,junitSucceeded=18,junitSkipped=0,junitReportSha256Bytes=sha(report)),indent=2)+'\n')
