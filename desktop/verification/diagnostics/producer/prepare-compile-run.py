from pathlib import Path
import difflib,hashlib,importlib.util,json,subprocess,sys
sys.stdout.reconfigure(encoding='utf-8',errors='replace')
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2]
def load(path,name):
 s=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
def safe(path):return Path('\\\\?\\'+str(path.absolute()))
def write(path,text):path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text,encoding='utf-8',newline='\n')
def sha(path):return hashlib.sha256(safe(path).read_bytes()).hexdigest()
tool=load(HERE/'extract-upstream-diagnostics.py','diag')
sources=tool.generate(REPO,HERE/'generated')
write(HERE/'source-inventory.json',json.dumps(tool.inventory(REPO),indent=2))
write(HERE/'resource-inventory.json',json.dumps(tool.resources(REPO),indent=2))
baseline=[];patch=[]
for name in ['UpstreamLog.kt','UpstreamLogger.kt']:
 relative='desktop/src/main/kotlin/com/bilipai/desktop/data/'+name
 original=(REPO/relative).read_text(encoding='utf-8').replace('\r\n','\n')
 if name=='UpstreamLog.kt':
  desired='''package android.util
/** Original decoder/network call shapes bind only the retained local diagnostic consumer. */
object Log {
    fun d(tag:String,message:String):Int = write("D",tag,message)
    fun i(tag:String,message:String):Int = write("I",tag,message)
    fun e(tag:String,message:String):Int = write("E",tag,message)
    fun e(tag:String,message:String,cause:Throwable):Int = write("E",tag,message,cause)
    fun v(tag:String,message:String):Int = write("V",tag,message)
    fun w(tag:String,message:String):Int = write("W",tag,message)
    private fun write(level:String,tag:String,message:String,cause:Throwable?=null):Int =
        com.bilipai.desktop.diagnostics.DesktopDiagnosticsBridge.record(level,tag,message,cause)
}
'''
 else:
  desired='''package com.android.purebilibili.core.util
/** Shared upstream policy bridge; the original capture/consent policy runs in the retained consumer. */
object Logger {
    fun e(tag:String,message:String,cause:Throwable) = android.util.Log.e(tag,message,cause)
    fun d(tag:String,message:String) = android.util.Log.d(tag,message)
}
'''
 draft=HERE/'bridge-draft'/name;write(draft,desired);sources.append(draft)
 baseline.append(dict(path=relative,baselineSha256Bytes=sha(REPO/relative),baselineSha256Lf=hashlib.sha256(original.encode()).hexdigest(),desiredSha256Lf=hashlib.sha256(desired.encode()).hexdigest()))
 patch.append(''.join(difflib.unified_diff(original.splitlines(True),desired.splitlines(True),fromfile='a/'+relative,tofile='b/'+relative)))
write(HERE/'bridge-baselines.json',json.dumps(baseline,indent=2))
write(HERE/'bridge-integration.patch',''.join(patch))
dependencies=json.loads((HERE.parent/'network-proxy-product-proof/dependency-identities.json').read_text(encoding='utf-8'))
for item in dependencies:assert sha(Path(item['path']))==item['sha256Bytes'],item['path']
write(HERE/'dependency-identities.json',json.dumps(dependencies,indent=2))
cp=[item['path'] for item in dependencies]
sources += [HERE/n for n in ['DesktopDiagnostics.kt','DesktopDiagnosticSettingsSection.kt','DesktopLocalDiagnosticViewer.kt','DesktopDiagnosticRuntimeBindings.kt','DesktopDiagnosticFileChooser.kt','DiagnosticFixture.kt','DiagnosticUiFixture.kt']]
compiler=load(HERE.parent/'source9-appearance/compile-miuix.py','compiler')
out=HERE/'classes-stable';out.mkdir(exist_ok=True)
def args(path,items):write(path,'\n'.join('"'+str(v).replace('\\','/')+'"' for v in items))
argfile=HERE/'compiler.args'
args(argfile,['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(cp),'-Xplugin='+str(compiler.PLUGIN),'-Xfriend-paths='+cp[0],'-module-name','com_bilipai_desktop_bilipai_windows','-d',str(out),*map(str,sources)])
run=subprocess.run([str(compiler.JAVA),'-Dfile.encoding=UTF-8','-Xmx1g','-cp',';'.join(map(str,compiler.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(argfile)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90)
write(HERE/'compile.log',run.stdout+run.stderr);print((run.stdout+run.stderr)[-6500:]);run.check_returncode()
def run_fixture(main,arguments,name):
 argfile=HERE/(name+'.args');args(argfile,['-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-cp',str(out)+';'+';'.join(cp),main,*arguments])
 r=subprocess.run([str(compiler.JAVA),'@'+str(argfile)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=60)
 write(HERE/(name+'.log'),r.stdout+r.stderr);print(r.stdout+r.stderr);r.check_returncode()
run_fixture('com.bilipai.desktop.diagnostics.DiagnosticFixtureKt',[str(HERE/'proof')],'runtime')
run_fixture('com.bilipai.desktop.diagnostics.DiagnosticFixtureKt',['cold',(HERE/'proof/cold-root.txt').read_text()],'fresh-jvm')
write(HERE/'proof/fresh-jvm.json',json.dumps(dict(passed=True,freshProcessSameNamespace=True,accountOrNetworkUsed=False),indent=2))
run_fixture('com.bilipai.desktop.diagnostics.DiagnosticUiFixtureKt',[str(HERE/'ui-proof')],'ui-runtime')
write(HERE/'compile-evidence.json',json.dumps(dict(passed=True,compiledSourceCount=len(sources),activeClassDirectory='classes-stable',historicalAttemptClassDirectories=['classes','classes-final','classes-verified'],sources=[str(p.relative_to(HERE)) for p in sources],productSnapshotJarSha256=[d['sha256Bytes'] for d in dependencies[:3]],sharedGradleInvoked=False,nativeWindowCreated=False),indent=2))
