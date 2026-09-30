"""Only the viewport fixture changes; all frozen product/renderer/initial evidence remain exact."""
from pathlib import Path
import hashlib, importlib.util, json, os, subprocess, sys, tempfile
sys.dont_write_bytecode=True
sys.stdout.reconfigure(encoding='utf-8',errors='replace')
HERE=Path(__file__).resolve().parent;PROOF=HERE.parent
def safe(p):
 value=str(Path(p).absolute());return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,v):p.write_text(v,encoding='utf-8',newline='\n')
assert sha(PROOF/'frozen-handoff.json')=='4fcbafc0c0ec4600586fcd8ea073751fad3ed0eb9f42d1a39327a1b5d24012b6'
frozen=json.loads((PROOF/'frozen-handoff.json').read_text(encoding='utf-8'))
for i in frozen['files']:assert sha(PROOF/i['path'])==i['sha256Bytes'],i['path']
items=json.loads((PROOF/'dependency-identities.json').read_text(encoding='utf-8'))
for i in items:assert sha(i['path'])==i['sha256Bytes'],i['path']
spec=importlib.util.spec_from_file_location('viewport_compiler',PROOF.parent/'source9-appearance/compile-miuix.py')
compiler=importlib.util.module_from_spec(spec);spec.loader.exec_module(compiler)
cp=[i['path'] for i in items];output=HERE/'classes';output.mkdir(exist_ok=True)
source=HERE/'ViewportProxyUiFixture.kt'
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(cp),'-Xplugin='+str(compiler.PLUGIN),
 '-Xfriend-paths='+','.join(cp[:3]),'-module-name','actual_product_proxy_viewport_fixture','-d',str(output),str(source)]
write(HERE/'compiler.args','\n'.join('"'+str(v).replace('\\','/')+'"' for v in args))
run=subprocess.run([str(compiler.JAVA),'-Dfile.encoding=UTF-8','-Xmx1g','-cp',';'.join(map(str,compiler.COMPILER)),
 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(HERE/'compiler.args')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90)
write(HERE/'compile.log',run.stdout+run.stderr);print((run.stdout+run.stderr)[-7000:]);run.check_returncode()
proof=HERE/'proof';proof.mkdir(exist_ok=True)
# ICU's native data-file loader cannot handle the unnecessarily deeper Windows
# child path. Keep this new owned child beside the initial proof's sandbox paths.
sandbox=Path(tempfile.mkdtemp(prefix='vp-env-',dir=PROOF/'proof'))
environment=os.environ.copy()
for name in ['LOCALAPPDATA','APPDATA','USERPROFILE','HOME','TEMP','TMP']:environment[name]=str(sandbox)
args=['-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-Djava.net.useSystemProxies=false',
 '-XX:ErrorFile='+str(HERE/'diagnostics/hs_err_pid%p.log'),'-Duser.home='+str(sandbox),
 '-Djava.io.tmpdir='+str(sandbox),'-cp',str(output)+';'+';'.join(cp),
 'com.bilipai.desktop.settings.ViewportProxyUiFixtureKt',str(proof),str(sandbox)]
write(HERE/'runtime.args','\n'.join('"'+v.replace('\\','/')+'"' for v in args))
run=subprocess.run([str(compiler.JAVA),'@'+str(HERE/'runtime.args')],env=environment,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90)
write(HERE/'runtime.log',run.stdout+run.stderr);print((run.stdout+run.stderr)[-10000:]);run.check_returncode()
for i in items:assert sha(i['path'])==i['sha256Bytes'],i['path']
for i in frozen['files']:assert sha(PROOF/i['path'])==i['sha256Bytes'],i['path']
write(HERE/'compile-evidence.json',json.dumps(dict(passed=True,fixtureSourceSha256=sha(source),fixtureSourceCount=1,
 originalFrozenEvidenceUntouched=True,rendererReplaced=False,immutableDependencyIdentities=items,
 childRoot=str(sandbox),sharedGradleInvoked=False,mainModified=False,nativeWindowCreated=False,
 accountOrHttpRequests=False),indent=2))
