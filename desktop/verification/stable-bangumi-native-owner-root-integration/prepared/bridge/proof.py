from pathlib import Path
import hashlib, importlib.util, json, os, subprocess, sys
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf8')
P=Path(__file__).resolve().parent;M=P.parents[2]
def wide(p):
 s=os.path.abspath(str(p));return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def sha(p):return hashlib.sha256(wide(p).read_bytes()).hexdigest()
S=M/'desktop/.local/stable-product-snapshot-88';cp=json.loads(wide(S/'ordered-runtime-cp.json').read_text())
def pins():
 assert sha(S/'manifest.json')=='fb097cce71237ce93b6220940a5e930c9672239511f3937d037ebc05af0939d2'
 assert sha(S/'ordered-runtime-cp.json')=='0252dc7a049e87084a0510bf406808188648f759f977cfd453f0803ed7e1a62c'
 assert len(cp)==105
 for row in cp:assert sha(row['path'])==row['sha256Bytes']
pins()
spec=importlib.util.spec_from_file_location('compiler',M/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
run=sys.argv[1];reference=sys.argv[2]if len(sys.argv)>2 else'04'
out=P/'proof-runs'/run;wide(out).mkdir(parents=True,exist_ok=False)
jar=P/'runs'/reference/'prospective.jar';main=next(row['path']for row in cp if row.get('source')=='desktop/build/classes/kotlin/main')
compiled=json.loads(wide(P/'runs'/reference/'result.json').read_text());assert compiled['passed']and compiled['snapshot']==88
classpath=[str(jar)]+[row['path']for row in cp]
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','original_pgc_native_owner_fixture','-Xfriend-paths='+str(jar)+','+main,'-cp',';'.join(classpath),'-d',str(out/'classes'),str(P/'BangumiNativeOwnerProof.kt')]
wide(out/'compiler.args').write_text('\n'.join('"'+x.replace('\\','/')+'"'for x in args),encoding='utf8')
r=subprocess.run([str(c.JAVA),'-Xmx3g','-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')],capture_output=True,timeout=180)
wide(out/'compile.log').write_bytes(r.stdout+r.stderr)
evidence=dict(passed=False,snapshot=88,entries=105,pinsBeforeAfter=True,prospectiveJar=dict(path=str(jar),sha256Bytes=sha(jar)),fixtureSha256Bytes=sha(P/'BangumiNativeOwnerProof.kt'),scope='Complete PGC physical plans plus actual original Store/native-owner negative admissions on unmounted MPV; no native transport command ACK/frame',productionWrites=0,fullScreenPrepared=False,rootRuntimeAccepted=False,httpRuntimeAccepted=False,nativeRuntimeAccepted=False,accountRuntimeAccepted=False,normalProductCompile=False)
if r.returncode==0:
 r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-cp',';'.join([str(out/'classes')]+classpath),'com.bilipai.desktop.ui.BangumiNativeOwnerProofKt',str(out/'isolated')],capture_output=True,timeout=30)
 wide(out/'run.log').write_bytes(r.stdout+r.stderr);evidence['passed']=r.returncode==0
pins();wide(out/'result.json').write_text(json.dumps(evidence,indent=2),encoding='utf8')
print((r.stdout+r.stderr).decode('utf8',errors='replace'));sys.exit(r.returncode)
