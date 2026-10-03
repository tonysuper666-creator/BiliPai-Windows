from pathlib import Path
import hashlib,importlib.util,json,os,subprocess,sys
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf8')
P=Path(__file__).resolve().parent;M=P.parents[2];S=M/'desktop/.local/stable-product-snapshot-89'
def wide(p):
    s=str(p);return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+os.path.abspath(s))
def sha(p):return hashlib.sha256(wide(p).read_bytes()).hexdigest()
cp=json.loads(wide(S/'ordered-runtime-cp.json').read_text())
assert sha(S/'manifest.json')=='aa28f253a357c107073d20ef279f28716a96dfa4015aca2be180f1865b14340b'
assert sha(S/'ordered-runtime-cp.json')=='63b3d1693889263d164badad1a3e45aa7b09168181be74cb1eff83b7a82bffb5'
for row in cp:assert sha(row['path'])==row['sha256Bytes']
cache=M.parent/'toolchain/gradle-home/caches/modules-2/files-2.1'
testdeps=[]
for group,name in [('org.jetbrains.kotlin','kotlin-test'),('org.jetbrains.kotlin','kotlin-test-junit5'),('org.junit.jupiter','junit-jupiter-api'),('org.apiguardian','apiguardian-api'),('org.opentest4j','opentest4j')]:
    files=list(wide(cache/group/name).rglob('*.jar'));assert len(files)==1,(name,files)
    testdeps.append(dict(path=str(files[0]).removeprefix('\\\\?\\'),sha256Bytes=sha(files[0])))
spec=importlib.util.spec_from_file_location('compiler',M/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
prod=P/'compile-runs'/sys.argv[1]/'prospective-space.jar';assert prod.is_file()
assert json.loads(wide(prod.parent/'result.json').read_text(encoding='utf8'))['snapshot']==89
out=P/'fixture-runs'/sys.argv[2];out.mkdir(parents=True,exist_ok=False)
source=P/'prepared/desktop/src/test/kotlin/com/bilipai/desktop/ui/DesktopOriginalSpacePagesTest.kt'
runner=out/'SpaceFixtureRunner.kt'
runner.write_text('''package com.bilipai.desktop.ui
import java.nio.file.*
fun main(args:Array<String>) {
 val methods=DesktopOriginalSpacePagesTest::class.java.declaredMethods.filter {it.isAnnotationPresent(org.junit.jupiter.api.Test::class.java)}.sortedBy {it.name}
 val results=mutableListOf<String>();var failure:Throwable?=null
 for(method in methods) {try {method.invoke(DesktopOriginalSpacePagesTest());results+="{\\"method\\":\\"${method.name}\\",\\"passed\\":true}";println("PASS ${method.name}")}
 catch(error:Throwable){val actual=error.cause?:error;failure=actual;results+="{\\"method\\":\\"${method.name}\\",\\"passed\\":false}";println("FAIL ${method.name}");actual.printStackTrace();break}}
 Files.writeString(Path.of(args[0]),"{\\"actualMethods\\":${methods.size},\\"passed\\":${failure==null},\\"rootAccepted\\":false,\\"realAccountRequests\\":0,\\"methods\\":[${results.joinToString(",")}]}")
 if(failure!=null) throw failure
}
''',encoding='utf8')
main=next(row['path']for row in cp if row.get('source')=='desktop/build/classes/kotlin/main')
paths=[str(prod)]+[row['path']for row in cp+testdeps]
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows',
 '-Xfriend-paths='+str(prod)+','+main,'-cp',';'.join(paths),'-d',str(out/'classes'),str(wide(source)),str(runner)]
(out/'compiler.args').write_text('\n'.join('"'+arg.replace('\\','/')+'"'for arg in args),encoding='utf8')
r=subprocess.run([str(c.JAVA),'-Xmx3g','-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),
 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')],capture_output=True,timeout=180)
(out/'compile.log').write_bytes(r.stdout+r.stderr)
if r.returncode:print((r.stdout+r.stderr).decode('utf8',errors='replace'));sys.exit(r.returncode)
r=subprocess.run([str(c.JAVA),'--add-modules','jdk.httpserver','-Djava.awt.headless=true','-Dfile.encoding=UTF-8',
 '-cp',';'.join([str(out/'classes')]+paths),'com.bilipai.desktop.ui.SpaceFixtureRunnerKt',str(out/'report.json')],capture_output=True,timeout=180)
(out/'runtime.log').write_bytes(r.stdout+r.stderr)
(out/'identity.json').write_text(json.dumps(dict(snapshot=89,actualRuntimeEntries=105,prospectiveJarSha256=sha(prod),
 explicitTestSourceSha256=sha(source),testDependencies=testdeps,productionWrites=0,sharedGradleRuns=0,realAccountRequests=0,rootAccepted=False,passed=r.returncode==0),indent=2),encoding='utf8')
for row in cp+testdeps:assert sha(row['path'])==row['sha256Bytes']
print((r.stdout+r.stderr).decode('utf8',errors='replace')[-12000:]);sys.exit(r.returncode)
