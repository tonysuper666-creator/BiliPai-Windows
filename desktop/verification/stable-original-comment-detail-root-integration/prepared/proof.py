from pathlib import Path
import hashlib,importlib.util,json,os,subprocess,sys
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf8')
P=Path(__file__).resolve().parent;M=P.parents[2]
def wide(p):
 s=str(p);return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+os.path.abspath(s))
def sha(p):return hashlib.sha256(wide(p).read_bytes()).hexdigest()
S=M/'desktop/.local/stable-product-snapshot-84';cp=json.loads(wide(S/'ordered-runtime-cp.json').read_text())
def pins():
 assert sha(S/'manifest.json')=='f70a306e3dc42df93c00d536163ff4a1f1953cbc94d36586c9500281983351b2'
 assert sha(S/'ordered-runtime-cp.json')=='4b1de9cce18526162113e1336c0252b37b3113d1f0b8832b68953fc3ec5df8d9'
 assert len(cp)==101
 for row in cp:assert sha(row['path'])==row['sha256Bytes']
pins()
spec=importlib.util.spec_from_file_location('compiler',M/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
run=sys.argv[1];reference=sys.argv[2] if len(sys.argv)>2 else '01'
out=P/'proof-runs'/run;wide(out).mkdir(parents=True,exist_ok=False)
jar=P/'runs'/reference/'prospective.jar';main=next(row['path']for row in cp if row.get('source')=='desktop/build/classes/kotlin/main')
classpath=[str(jar)]+[row['path']for row in cp]
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','original_comment_fixture','-Xfriend-paths='+str(jar)+','+main,'-cp',';'.join(classpath),'-d',str(out/'classes'),str(P/'CommentDetailProof.kt')]
wide(out/'compiler.args').write_text('\n'.join('"'+x.replace('\\','/')+'"'for x in args),encoding='utf8')
r=subprocess.run([str(c.JAVA),'-Xmx3g','-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')],capture_output=True,timeout=180)
wide(out/'compile.log').write_bytes(r.stdout+r.stderr)
if r.returncode:
 print((r.stdout+r.stderr).decode('utf8',errors='replace'));sys.exit(r.returncode)
r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-cp',';'.join([str(out/'classes')]+classpath),'com.bilipai.desktop.ui.CommentDetailProofKt'],capture_output=True,timeout=30)
wide(out/'run.log').write_bytes(r.stdout+r.stderr);pins()
wide(out/'result.json').write_text(json.dumps(dict(passed=r.returncode==0,snapshot=84,entries=101,pinsBeforeAfter=True,prospectiveJar=dict(path=str(jar),sha256Bytes=sha(jar)),fixtureSha256Bytes=sha(P/'CommentDetailProof.kt'),scope='Original typed dispatcher and original comment VM, explicit fixture requests; no Root HTTP/native/Window acceptance',productionWrites=0),indent=2),encoding='utf8')
print((r.stdout+r.stderr).decode('utf8',errors='replace'));sys.exit(r.returncode)
