from pathlib import Path
import hashlib,importlib.util,json,os,subprocess,sys
P=Path(__file__).resolve().parent;M=P.parents[2]
def wide(p):return Path('\\\\?\\'+os.path.abspath(p))
def sha(p):return hashlib.sha256(wide(p).read_bytes()).hexdigest()
S=M/'desktop/.local/stable-product-snapshot-85';cp=json.loads((S/'ordered-runtime-cp.json').read_text())
assert sha(S/'manifest.json')=='417663152a99ecf23e6887e2940b15c7c1cfd06c6c3f0b2b1925430ed4ca4012'
for r in cp:assert sha(r['path'])==r['sha256Bytes']
extra=[r for r in json.loads((P/'dependencies/identities.json').read_text())if r['type']=='jar']
for r in extra:assert sha(r['path'])==r['sha256Bytes']
spec=importlib.util.spec_from_file_location('compiler',M/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
prod=P/'runs'/sys.argv[1]/'prospective.jar';assert prod.is_file();out=P/'fixture-runs'/sys.argv[2];out.mkdir(parents=True,exist_ok=False)
main=next(r['path']for r in cp if r.get('source')=='desktop/build/classes/kotlin/main');paths=[str(prod)]+[r['path']for r in cp+extra]
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows','-Xplugin='+str(c.PLUGIN),'-Xfriend-paths='+str(prod)+','+main,'-cp',';'.join(paths),'-d',str(out/'classes')]+[str(f)for f in(P/'fixture').glob('*.kt')]
(out/'compiler.args').write_text('\n'.join('"'+a.replace('\\','/')+'"'for a in args),encoding='utf8')
r=subprocess.run([str(c.JAVA),'-Xmx3g','-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')],capture_output=True,timeout=150)
(out/'compile.log').write_bytes(r.stdout+r.stderr)
if r.returncode:print((r.stdout+r.stderr).decode('utf8',errors='replace'));sys.exit(r.returncode)
ui=len(sys.argv)>3 and sys.argv[3]=='ui'
entry='com.bilipai.desktop.ui.DesktopOriginalMessagePagesUiFixtureKt'if ui else'com.bilipai.desktop.ui.DesktopOriginalMessagePagesFixtureKt'
r=subprocess.run([str(c.JAVA),'--add-modules','jdk.httpserver','-Djava.awt.headless=true','-Dfile.encoding=UTF-8','-cp',';'.join([str(out/'classes')]+paths),entry,str(out/'ui'if ui else out/'report.json')],capture_output=True,timeout=150)
(out/'runtime.log').write_bytes(r.stdout+r.stderr)
(out/'identity.json').write_text(json.dumps(dict(snapshot=85,prospectiveJarSha256=sha(prod),explicitFixtureSources=[dict(path=str(f),sha256Bytes=sha(f))for f in(P/'fixture').glob('*.kt')],externalCpCount=101,extraDependencies=extra,productProductionWrites=0,realAccountRequests=0,rootAccepted=False,passed=r.returncode==0),indent=2),encoding='utf8')
print((r.stdout+r.stderr).decode('utf8',errors='replace')[-18000:]);sys.exit(r.returncode)
