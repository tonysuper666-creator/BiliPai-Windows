"""Compile declared Root/data overlays + unchanged original message bodies against snapshot87.
No Gradle or production output mutation. This is not whole Root runtime acceptance."""
from pathlib import Path
import hashlib, importlib.util, json, os, subprocess, sys, zipfile
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf8')
P=Path(__file__).resolve().parent;M=P.parents[2];F=M/'desktop/.local/stable-original-message-pages-root-parity'
S=M/'desktop/.local/stable-product-snapshot-87'
def wide(p):
 s=str(p);return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+os.path.abspath(s))
def sha(p):return hashlib.sha256(wide(p).read_bytes()).hexdigest()
cp=json.loads(wide(S/'ordered-runtime-cp.json').read_text(encoding='utf8'))
def pins():
 assert sha(S/'manifest.json')=='5aecc4d5a84f83368d01820541fb2beb8b3cf29ececefa17e8a9363ae030bb03'
 assert sha(S/'ordered-runtime-cp.json')=='cf3e0773efdb82b4b4c9fa19654a54b92c16401b675cd746748de8b41141361f'
 assert len(cp)==101
 for row in cp:assert sha(row['path'])==row['sha256Bytes'],row['path']
 assert sha(F/'frozen-handoff.json')=='27a644ddf45fea069b47b601d0728234118561110290aac4ddec5f3800a90ffd'
pins()
frozen=json.loads(wide(F/'frozen-handoff.json').read_text(encoding='utf8'))
packetJar=F/'runs/11/prospective.jar'
expected=next(r['sha256Bytes']for r in frozen['files']if r['path']=='runs/11/prospective.jar')
assert sha(packetJar)==expected # Provenance only, not a current product CP override.
extra=[r for r in json.loads(wide(F/'dependencies/identities.json').read_text(encoding='utf8'))if r['type']=='jar']
for row in extra:assert sha(row['path'])==row['sha256Bytes']
contract=json.loads(wide(P/'root-install-contract.json').read_text(encoding='utf8'))
sources=[]
for row in contract['exactHunkTargets']:
 if row['target'].endswith('.kt'):
  source=P/'prospective'/row['target'];assert sha(source)==row['desiredRawSha256'];sources.append(source)
assert len(sources)==4
for relative in ['prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalMessagePageAdmission.kt',
 'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalMessagePagesRoot.kt']:
 source=F/relative;assert sha(source)==next(row['sha256Bytes']for row in frozen['files']if row['path']==relative);sources.append(source)
for row in json.loads(wide(F/'install-contract.json').read_text(encoding='utf8'))['generatedFiles']:
 source=F/row['path'];assert sha(source)==row['sha256Bytes'];sources.append(source)
assert len(sources)==23
spec=importlib.util.spec_from_file_location('compiler',M/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
out=P/'compile-runs'/sys.argv[1];wide(out).mkdir(parents=True,exist_ok=False)
main=next(row['path']for row in cp if row.get('source')=='desktop/build/classes/kotlin/main')
inputPins=[dict(path=str(f),sha256Bytes=sha(f))for f in sources]
saved=[]
for index,f in enumerate(sources):
 destination=out/'inputs'/str(index)/f.name;wide(destination).parent.mkdir(parents=True,exist_ok=True);wide(destination).write_bytes(wide(f).read_bytes());saved.append(destination)
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows','-Xplugin='+str(c.PLUGIN),
 '-Xfriend-paths='+main,'-cp',';'.join(r['path']for r in cp+extra),'-d',str(out/'classes')]+[str(wide(f))for f in saved]
wide(out/'compiler.args').write_text('\n'.join('"'+a.replace('\\','/')+'"'for a in args),encoding='utf8')
r=subprocess.run([str(c.JAVA),'-Xmx5g','-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')],capture_output=True,timeout=360)
wide(out/'compile.log').write_bytes(r.stdout+r.stderr);pins();assert sha(packetJar)==expected
for row in inputPins:assert sha(row['path'])==row['sha256Bytes']
wide(out/'result.json').write_text(json.dumps(dict(passed=r.returncode==0,snapshot=87,orderedRuntimeEntries=101,
 frozenPacketJar=dict(path=str(packetJar),sha256Bytes=expected,onCompilationClasspath=False),
 explicitProspectiveProductionInputs=23,prospectiveRootInputs=inputPins,
 immutableCompiledInputs=[dict(path=str(f),sha256Bytes=sha(f))for f in saved],
 productionWrites=0,sharedGradleRuns=0,rootRuntimeAccepted=False),indent=2),encoding='utf8')
if r.returncode==0:
 with zipfile.ZipFile(wide(out/'prospective-root.jar'),'w',zipfile.ZIP_DEFLATED)as z:
  for f in wide(out/'classes').rglob('*'):
   if f.is_file():z.writestr(f.relative_to(wide(out/'classes')).as_posix(),f.read_bytes())
print((r.stdout+r.stderr).decode('utf8',errors='replace')[-20000:]);print('PASS'if r.returncode==0 else'FAIL','23 declared inputs including latest rebased data + Root');sys.exit(r.returncode)
