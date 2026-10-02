from pathlib import Path
import hashlib,importlib.util,json,os,subprocess,sys
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf8')
P=Path(__file__).resolve().parent;M=P.parents[2]
def wide(p):
 s=str(p);return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+os.path.abspath(s))
def sha(p):return hashlib.sha256(wide(p).read_bytes()).hexdigest()
S=M/'desktop/.local/stable-product-snapshot-85';cp=json.loads(wide(S/'ordered-runtime-cp.json').read_text())
def pins():
 assert sha(S/'manifest.json')=='417663152a99ecf23e6887e2940b15c7c1cfd06c6c3f0b2b1925430ed4ca4012'
 assert sha(S/'ordered-runtime-cp.json')=='6398174d444cf02365bc66480c1c37bb00800a520e681c4649100067af68b419'
 assert len(cp)==101
 for row in cp:assert sha(row['path'])==row['sha256Bytes']
pins()
spec=importlib.util.spec_from_file_location('compiler',M/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
run=sys.argv[1];reference=sys.argv[2] if len(sys.argv)>2 else '01'
out=P/'proof-runs'/run;wide(out).mkdir(parents=True,exist_ok=False)
jar=P/'runs'/reference/'prospective.jar';main=next(row['path']for row in cp if row.get('source')=='desktop/build/classes/kotlin/main')
classpath=[str(jar)]+[row['path']for row in cp]
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','original_bangumi_fixture','-Xfriend-paths='+str(jar)+','+main,'-cp',';'.join(classpath),'-d',str(out/'classes'),str(P/'BangumiPagesProof.kt')]
wide(out/'compiler.args').write_text('\n'.join('"'+x.replace('\\','/')+'"'for x in args),encoding='utf8')
r=subprocess.run([str(c.JAVA),'-Xmx3g','-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')],capture_output=True,timeout=180)
wide(out/'compile.log').write_bytes(r.stdout+r.stderr)
if r.returncode:
 print((r.stdout+r.stderr).decode('utf8',errors='replace'));sys.exit(r.returncode)
r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-cp',';'.join([str(out/'classes')]+classpath),'com.bilipai.desktop.ui.BangumiPagesProofKt'],capture_output=True,timeout=30)
wide(out/'run.log').write_bytes(r.stdout+r.stderr);pins()
wide(out/'result.json').write_text(json.dumps(dict(passed=r.returncode==0,snapshot=85,entries=101,pinsBeforeAfter=True,prospectiveJar=dict(path=str(jar),sha256Bytes=sha(jar)),fixtureSha256Bytes=sha(P/'BangumiPagesProof.kt'),scope='Original complete Bangumi detail VM and original season/review requests, explicit fixture requests; no Root HTTP/native/Window acceptance',productionWrites=0),indent=2),encoding='utf8')
print((r.stdout+r.stderr).decode('utf8',errors='replace'));sys.exit(r.returncode)

