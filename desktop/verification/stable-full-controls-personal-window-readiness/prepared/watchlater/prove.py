from pathlib import Path
import hashlib,importlib.util,json,os,subprocess,sys,zipfile
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2]
PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
 s=os.path.abspath(p);return Path(s if s.startswith(PREFIX) else PREFIX+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
S=MAIN/'desktop/.local/stable-product-snapshot-49';cp=json.loads(safe(S/'ordered-runtime-cp.json').read_text())
for r in cp:assert sha(r['path'])==r['sha256Bytes']
core=HERE/('compile-'+(sys.argv[2] if len(sys.argv)>2 else '03')+'/candidate.jar');assert safe(core).exists()
with zipfile.ZipFile(safe(core)) as z: new={n for n in z.namelist() if n.endswith('.class')}
with zipfile.ZipFile(safe(S/'main-kotlin.jar')) as z: old={n for n in z.namelist() if n.endswith('.class')}
assert any(n.startswith('com/bilipai/desktop/ui/DesktopPersonalListsRoot') for n in new&old)
# Four declared existing Controller/Listen/Bridge/Shell source families are prospective.

run=HERE/('proof-'+(sys.argv[1] if len(sys.argv)>1 else '01'));safe(run).mkdir(parents=True,exist_ok=False)
sources=list((HERE/'fixtures').rglob('*.kt'))
spec=importlib.util.spec_from_file_location('cc',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');cc=importlib.util.module_from_spec(spec);spec.loader.exec_module(cc)
queue=MAIN/'desktop/.local/stable-personal-queue-start-parity/compile-02/candidate.jar'
paths=[str(core),str(queue)]+[r['path'] for r in cp]
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows',
 '-Xfriend-paths='+str(core)+','+str(queue)+','+str(S/'main-kotlin.jar'),'-Xplugin='+str(cc.PLUGIN),'-cp',';'.join(paths),'-d',str(run/'classes')]+[str(p) for p in sources]
safe(run/'compile.args').write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args),encoding='utf-8')
r=subprocess.run([str(cc.JAVA),'-Dfile.encoding=UTF-8','-Xmx2g','-cp',';'.join(map(str,cc.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(run/'compile.args')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=120)
safe(run/'compile.log').write_text(r.stdout+r.stderr,encoding='utf-8')
ident=dict(actualSnapshot=str(S),manifestSha=sha(S/'manifest.json'),orderedCpSha=sha(S/'ordered-runtime-cp.json'),preparedOnly=True,
 coreJarSha=sha(core),declaredProductFamilyOverrides='PersonalListsRoot/Repository/RootMount/Shell plus explicit frozen queue-start candidate',coreNewClasses=len(new),sources=[dict(path=str(p),sha=sha(p)) for p in sources],compileExit=r.returncode)
if r.returncode==0:
 p=subprocess.run([str(cc.JAVA),'-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-cp',';'.join([str(run/'classes')]+paths),
 'com.bilipai.desktop.ui.WatchLaterFixtureKt',str(run/'temporary')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90)
 safe(run/'run.log').write_text(p.stdout+p.stderr,encoding='utf-8');ident['runExit']=p.returncode
 print((p.stdout+p.stderr).encode('ascii','backslashreplace').decode())
else:print((r.stdout+r.stderr).encode('ascii','backslashreplace').decode())
safe(run/'proof.json').write_text(json.dumps(ident,indent=2)+'\n',encoding='utf-8')
for row in cp:assert sha(row['path'])==row['sha256Bytes']
sys.exit(ident.get('runExit',r.returncode))
