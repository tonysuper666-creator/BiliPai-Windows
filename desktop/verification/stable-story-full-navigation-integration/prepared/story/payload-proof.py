from pathlib import Path
import hashlib,importlib.util,json,os,subprocess,sys
sys.dont_write_bytecode=True
P=Path(__file__).resolve().parent;M=P.parents[2]
def wide(p):
 s=str(p);return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+os.path.abspath(s))
def sha(p):return hashlib.sha256(wide(p).read_bytes()).hexdigest()
S=M/'desktop/.local/stable-product-snapshot-83'
assert sha(S/'manifest.json')=='72934edb925c7793294bf504388dfbd1f849fe14d8ea00dd4f333befdabd4157'
assert sha(S/'ordered-runtime-cp.json')=='f82828347f7cfad86cd101dfb13d44677ba88f780b21a9e21df8c06a97d4828b'
cp=json.loads(wide(S/'ordered-runtime-cp.json').read_text(encoding='utf8'));assert len(cp)==101
def pins():
 for row in cp: assert sha(row['path'])==row['sha256Bytes']
pins()
spec=importlib.util.spec_from_file_location('c',M/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
out=P/'payload-proof'/sys.argv[1];wide(out).mkdir(parents=True,exist_ok=False)
sources=[P/'PayloadFixture.kt',P/'prepared/manual/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalPortraitResolvedPayload.kt']
main=next(row['path'] for row in cp if row.get('source')=='desktop/build/classes/kotlin/main')
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows','-Xfriend-paths='+main,'-cp',';'.join(r['path'] for r in cp),'-d',str(out/'classes')]+list(map(str,sources))
wide(out/'compiler.args').write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args),encoding='utf8')
build=subprocess.run([str(c.JAVA),'-Xmx1g','-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')],capture_output=True,timeout=90)
wide(out/'compile.log').write_bytes(build.stdout+build.stderr)
if build.returncode:
 print((build.stdout+build.stderr).decode('utf8',errors='replace'));sys.exit(build.returncode)
run=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-cp',str(out/'classes')+';'+';'.join(row['path']for row in cp),'com.bilipai.desktop.ui.PayloadFixtureKt'],capture_output=True,timeout=30)
wide(out/'run.log').write_bytes(run.stdout+run.stderr);pins()
wide(out/'result.json').write_text(json.dumps(dict(passed=run.returncode==0,actualSnapshot=83,entries=101,explicitProspectiveProductionInput=dict(path=str(sources[1]),sha256=sha(sources[1])),snapshotPinsUnchanged=True,networkRequests=0,windows=0,seededVmSuccess=0,nativeAckTested=False,rootAcceptance=False),indent=2),encoding='utf8')
print((run.stdout+run.stderr).decode('utf8',errors='replace'));sys.exit(run.returncode)
