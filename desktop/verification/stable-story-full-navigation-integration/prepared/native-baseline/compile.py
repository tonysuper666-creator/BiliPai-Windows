from pathlib import Path
import hashlib, importlib.util, json, os, subprocess, sys, zipfile
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf8')
P=Path(__file__).resolve().parent;M=P.parents[2];B=P.parent/'stable-original-story-pager-root-parity';R=P.parent/'stable-original-story-review-delta'
def wide(p):
 s=str(p);return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+os.path.abspath(s))
def sha(p):return hashlib.sha256(wide(p).read_bytes()).hexdigest()
S=M/'desktop/.local/stable-product-snapshot-83'
assert sha(S/'manifest.json')=='72934edb925c7793294bf504388dfbd1f849fe14d8ea00dd4f333befdabd4157'
assert sha(S/'ordered-runtime-cp.json')=='f82828347f7cfad86cd101dfb13d44677ba88f780b21a9e21df8c06a97d4828b'
cp=json.loads(wide(S/'ordered-runtime-cp.json').read_text(encoding='utf8'));assert len(cp)==101
def pins():
 for r in cp:assert sha(r['path'])==r['sha256Bytes'],r['path']
pins()
spec=importlib.util.spec_from_file_location('compiler',M/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
sources={}
for root in (B/'prepared',R/'prepared',R/'reference',P/'prepared',P/'replay'):
 for f in wide(root).rglob('*.kt'):sources[f.name]=f
nativeReceipt=M.parent/'BiliPai-v023/desktop/verification/stable-image-preview-native-share/root'
assert sha(nativeReceipt/'actual-native-producer-receipt.json')=='a28c5521229934fbdb760a9fb0d2628b87aa2f1715ea0bed8d5e923e997e41f6'
assert sha(nativeReceipt/'actual-generated-native-hash.kt.txt')=='e95b3bc4b9155083498e1df364fe90e17b5e606665e1a6ea52123fdec52914a9'
nativeHash=P/'reference-native/NativeDiagnosticAssetHash.kt';wide(nativeHash).parent.mkdir(parents=True,exist_ok=True)
wide(nativeHash).write_bytes(wide(nativeReceipt/'actual-generated-native-hash.kt.txt').read_bytes())
# This existing caller inlines the trusted constant. Recompile it alongside the
# formal fresh producer hash; no old expected hash is silently bypassed.
ready=P.parent/'stable-image-preview-native-share/baseline/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopReadyOriginalRootMount.kt'
imageContract=json.loads(wide(P.parent/'stable-image-preview-native-share/installation-contract.json').read_text())
readyPin=next(t for t in imageContract['targets']if t['target']=='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopReadyOriginalRootMount.kt')
assert sha(ready)==readyPin['beforeSha256Bytes']
sources[ready.name]=ready;sources[nativeHash.name]=nativeHash
number=sys.argv[1];out=P/'runs'/number;wide(out).mkdir(parents=True,exist_ok=False)
main=next(r['path']for r in cp if r.get('source')=='desktop/build/classes/kotlin/main')
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows','-Xplugin='+str(c.PLUGIN),'-Xfriend-paths='+main,'-cp',';'.join(r['path']for r in cp),'-d',str(out/'classes')]+[str(p)for p in sources.values()]
wide(out/'compiler.args').write_text('\n'.join('"'+a.replace('\\','/')+'"'for a in args),encoding='utf8')
p=subprocess.run([str(c.JAVA),'-Xmx5g','-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')],capture_output=True,timeout=360)
wide(out/'compile.log').write_bytes(p.stdout+p.stderr);pins()
wide(out/'result.json').write_text(json.dumps(dict(passed=p.returncode==0,snapshot=83,entries=101,sourceCount=len(sources),explicitProspectiveSources=[dict(path=str(s),sha256=sha(s))for s in sources.values()],productionWrites=0,fullRootAcceptance=False),ensure_ascii=False,indent=2),encoding='utf8')
if p.returncode==0:
 with zipfile.ZipFile(wide(out/'prospective.jar'),'w',zipfile.ZIP_DEFLATED)as z:
  for f in wide(out/'classes').rglob('*.class'):z.writestr(f.relative_to(wide(out/'classes')).as_posix(),f.read_bytes())
print((p.stdout+p.stderr).decode('utf8',errors='replace'));print('PASS'if p.returncode==0 else 'FAIL',len(sources),'inputs');sys.exit(p.returncode)
