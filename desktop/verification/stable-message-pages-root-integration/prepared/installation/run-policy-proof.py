from pathlib import Path
import hashlib,importlib.util,json,os,subprocess,sys
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf8')
P=Path(__file__).resolve().parent;M=P.parents[2];S=M/'desktop/.local/stable-product-snapshot-86'
def wide(p):
 s=str(p);return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+os.path.abspath(s))
def sha(p):return hashlib.sha256(wide(p).read_bytes()).hexdigest()
cp=json.loads(wide(S/'ordered-runtime-cp.json').read_text(encoding='utf8'))
def pins():
 assert sha(S/'manifest.json')=='dc4119b471c0560c6e92f457b1b307d195809010f4591a6c6cb7d9b2754136b4'
 assert sha(S/'ordered-runtime-cp.json')=='7eeb6577effaab7fb9d212b883e4fe6613c3c5ab0875453124578ec739f15c1a'
 assert len(cp)==101
 for row in cp:assert sha(row['path'])==row['sha256Bytes']
pins();out=P/('policy-proof-'+sys.argv[1]);out.mkdir(exist_ok=False)
spec=importlib.util.spec_from_file_location('compiler',M/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
main=next(row['path']for row in cp if row.get('source')=='desktop/build/classes/kotlin/main')
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows',
 '-Xfriend-paths='+main,'-cp',';'.join(row['path']for row in cp),'-d',str(out/'classes'),str(P/'MessageEntryPolicyProof.kt')]
(out/'compiler.args').write_text('\n'.join('"'+a.replace('\\','/')+'"'for a in args),encoding='utf8')
r=subprocess.run([str(c.JAVA),'-Xmx2g','-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')],capture_output=True,timeout=120)
(out/'compile.log').write_bytes(r.stdout+r.stderr)
if r.returncode:print((r.stdout+r.stderr).decode('utf8',errors='replace'));sys.exit(r.returncode)
r=subprocess.run([str(c.JAVA),'-Djava.awt.headless=true','-Dfile.encoding=UTF-8','-cp',';'.join([str(out/'classes')]+[row['path']for row in cp]),
 'com.bilipai.desktop.ui.MessageEntryPolicyProofKt',str(out/'report.json')],capture_output=True,timeout=30)
(out/'runtime.log').write_bytes(r.stdout+r.stderr);pins()
(out/'identity.json').write_text(json.dumps(dict(passed=r.returncode==0,snapshot=86,sourceSha256=sha(P/'MessageEntryPolicyProof.kt'),
 runtimeEntries=101,productionOverrides=0,productionWrites=0,rootWindowAccepted=False),indent=2),encoding='utf8')
print((r.stdout+r.stderr).decode('utf8',errors='replace'));sys.exit(r.returncode)
