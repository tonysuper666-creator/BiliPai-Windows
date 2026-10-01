from pathlib import Path
import hashlib,json,importlib.util,subprocess,sys
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2]
PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith(PREFIX) else PREFIX+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
snapshot=MAIN/'desktop/.local/stable-product-snapshot-36'
cp=json.loads(safe(snapshot/'ordered-runtime-cp.json').read_text())
for row in cp:assert sha(row['path'])==row['sha256Bytes']
prod=HERE/'compile-02/classes'
dest=HERE/('proof-'+sys.argv[1] if len(sys.argv)>1 else 'proof-01')
safe(dest).mkdir(parents=True,exist_ok=False)
spec=importlib.util.spec_from_file_location('cc',MAIN/'desktop/.local/source9-appearance/compile-miuix.py')
cc=importlib.util.module_from_spec(spec);spec.loader.exec_module(cc)
files=[HERE/'fixtures/ReturnNavigationFixture.kt']
classpath=';'.join(map(str,[prod]))+';'+';'.join(r['path'] for r in cp)
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows',
 '-Xfriend-paths='+','.join(map(str,[snapshot/'main-kotlin.jar',prod])),
 '-cp',classpath,'-d',str(dest/'classes')]+list(map(str,files))
argfile=dest/'compile.args';safe(argfile).write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args),encoding='utf-8')
r=subprocess.run([str(cc.JAVA),'-Dfile.encoding=UTF-8','-cp',';'.join(map(str,cc.COMPILER)),
 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(argfile)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=180)
safe(dest/'compile.log').write_text(r.stdout+r.stderr,encoding='utf-8')
if r.returncode:print((r.stdout+r.stderr).encode('ascii','backslashreplace').decode());sys.exit(r.returncode)
cmd=[str(cc.JAVA),'-Dfile.encoding=UTF-8','-cp',str(dest/'classes')+';'+classpath,
 'com.bilipai.desktop.ui.ReturnNavigationFixtureKt']
r=subprocess.run(cmd,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=60)
safe(dest/'run.log').write_text(r.stdout+r.stderr,encoding='utf-8')
safe(dest/'inputs.json').write_text(json.dumps({'snapshot':str(snapshot),
 'manifestSha':sha(snapshot/'manifest.json'),'orderedCpSha':sha(snapshot/'ordered-runtime-cp.json'),
 'actualCpEntries':len(cp),'explicitProspectiveSourceOnly':str(prod),
 'fixtureSources':[{'path':str(p),'sha256Bytes':sha(p)} for p in files],
 'command':cmd,'exit':r.returncode,'mainConsumerAccepted':False,'windowAccepted':False},indent=2)+'\n',encoding='utf-8')
print((r.stdout+r.stderr).encode('ascii','backslashreplace').decode());sys.exit(r.returncode)
