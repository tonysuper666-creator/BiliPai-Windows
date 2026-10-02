from pathlib import Path
import hashlib, importlib.util, json, os, subprocess, sys
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf8')
P=Path(__file__).resolve().parent;M=P.parents[2]
def wide(p):
 s=os.path.abspath(str(p));return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def sha(p):return hashlib.sha256(wide(p).read_bytes()).hexdigest()
S=M/'desktop/.local/stable-product-snapshot-90';cp=json.loads(wide(S/'ordered-runtime-cp.json').read_text())
def pins():
 assert sha(S/'manifest.json')=='4cf1bb27f4615aa602a1b37d2fa939acadeb18c2ba9d552dd819cfb1cfe5dd46'
 assert sha(S/'ordered-runtime-cp.json')=='30455d62eb5934c9fed3478e7cfc8486ff79f8ada35cbc80ca1cdb5a66ba8633'
 assert len(cp)==105
 for row in cp:assert sha(row['path'])==row['sha256Bytes']
pins()
spec=importlib.util.spec_from_file_location('compiler',M/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
run=sys.argv[1];reference=sys.argv[2]if len(sys.argv)>2 else'12'
out=P/'proof-runs'/run;wide(out).mkdir(parents=True,exist_ok=False)
jar=P/'runs'/reference/'prospective.jar';main=next(row['path']for row in cp if row.get('source')=='desktop/build/classes/kotlin/main')
compiled=json.loads(wide(P/'runs'/reference/'result.json').read_text());assert compiled['passed']and compiled['snapshot']==90
classpath=[str(jar)]+[row['path']for row in cp]
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','original_pgc_native_owner_fixture','-Xfriend-paths='+str(jar)+','+main,'-cp',';'.join(classpath),'-d',str(out/'classes'),str(P/'BangumiPlayerClosureProof.kt')]
wide(out/'compiler.args').write_text('\n'.join('"'+x.replace('\\','/')+'"'for x in args),encoding='utf8')
r=subprocess.run([str(c.JAVA),'-Xmx3g','-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')],capture_output=True,timeout=180)
wide(out/'compile.log').write_bytes(r.stdout+r.stderr)
evidence=dict(passed=False,snapshot=90,entries=105,pinsBeforeAfter=True,prospectiveJar=dict(path=str(jar),sha256Bytes=sha(jar)),fixtureSha256Bytes=sha(P/'BangumiPlayerClosureProof.kt'),scope='Complete original Player policies/PUGV/final reporter failure, epoch, privacy and cancellation cases plus actual original Store/native-owner admissions on unmounted MPV; no network-success/native transport ACK/frame',productionWrites=0,fullScreenPrepared=True,rootRuntimeAccepted=False,httpRuntimeAccepted=False,nativeRuntimeAccepted=False,accountRuntimeAccepted=False,normalProductCompile=False)
if r.returncode==0:
 r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-cp',';'.join([str(out/'classes')]+classpath),'com.bilipai.desktop.ui.BangumiPlayerClosureProofKt',str(out/'isolated')],capture_output=True,timeout=30)
 wide(out/'run.log').write_bytes(r.stdout+r.stderr);evidence['passed']=r.returncode==0
pins();wide(out/'result.json').write_text(json.dumps(evidence,indent=2),encoding='utf8')
print((r.stdout+r.stderr).decode('utf8',errors='replace'));sys.exit(r.returncode)
