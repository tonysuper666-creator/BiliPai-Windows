from pathlib import Path
import hashlib,importlib.util,json,os,subprocess,sys,zipfile
P=Path(__file__).resolve().parent;MAIN=P.parents[2];PFX=chr(92)*2+'?'+chr(92)
def wide(p):
 s=os.path.abspath(p);return Path(s if s.startswith(PFX)else PFX+s)
def sha(p):return hashlib.sha256(wide(p).read_bytes()).hexdigest()
def save(p,v):
 wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_text(json.dumps(v,indent=2)+'\n',encoding='utf8',newline='\n')
compiled=P/'runs/05';base=json.loads(wide(compiled/'pins-before.json').read_text())
cp=base['runtime'];assert len(cp)==101
def check():
 for r in cp:assert sha(r['path'])==r['sha256Bytes'],r['path']
 for r in base['inputs']:assert sha(r['path'])==r['sha256Bytes'],r['path']
check()
jar=compiled/'candidate.jar'
with zipfile.ZipFile(wide(jar))as z:classes=set(n for n in z.namelist()if n.endswith('.class'))
old=set()
for r in cp[:3]:
 with zipfile.ZipFile(wide(r['path']))as z:old.update(z.namelist())
overlap=sorted(classes&old)
prefixes=['com/bilipai/desktop/ui/DesktopOriginalVideoRepositoryBinding','com/bilipai/desktop/data/DesktopRepository','com/bilipai/desktop/data/DesktopSessionEpoch']
assert all(n.startswith('com/android/purebilibili/feature/video/viewmodel/') or any(n==p+'.class' or n.startswith(p+'$')for p in prefixes)for n in overlap)
save(P/'compile05-audit-correction.json',dict(compilerPassed=True,compilerLogSha256Bytes=sha(compiled/'compile.log'),
 classCount=len(classes),declaredSourceFamilyOverrides=overlap,undeclaredOverrides=[],
 previousAuditFailed=True,correction='DesktopSessionEpoch is a declaration in the exact prepared DesktopRepository.kt input; initial allow-list omitted this same-file class.',
 originalCompilerOrProductionSourceChangedForCorrection=False,sourceInputs=base['inputs']))
out=P/'proof-runs'/sys.argv[1];assert not wide(out).exists();wide(out).mkdir(parents=True)
spec=importlib.util.spec_from_file_location('c',MAIN/'desktop/.local/source9-appearance/compile-miuix.py')
c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
source=P/'ActionStatusProof.kt';wide(out/'ActionStatusProof.kt').write_bytes(wide(source).read_bytes())
pins=dict(actual71ManifestSHA256=base['actual71ManifestSHA256'],actual71OrderedCPSHA256=base['actual71OrderedCPSHA256'],
 runtime=cp,assemblyInputJarSHA256Bytes=sha(jar),fixtureSHA256Bytes=sha(source),declaredSourceFamilyOverrides=overlap,
 compiler=[dict(path=str(p),sha256Bytes=sha(p))for p in [c.JAVA,c.PLUGIN]+c.COMPILER])
save(out/'pins-before.json',pins)
fixtureJar=out/'fixture.jar';classpath=[str(jar)]+[r['path']for r in cp]
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows',
 '-Xplugin='+str(c.PLUGIN),'-Xfriend-paths='+str(jar)+','+cp[1]['path'],'-cp',';'.join(classpath),'-d',str(fixtureJar),str(source)]
arg=out/'compile.args';wide(arg).write_text('\n'.join('"'+a.replace('\\','/')+'"'for a in args),encoding='utf8')
r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Xmx1g','-cp',';'.join(map(str,c.COMPILER)),
 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(arg)],capture_output=True,timeout=90)
wide(out/'compile.log').write_bytes(r.stdout+r.stderr)
result=dict(compilePassed=r.returncode==0,compileExitCode=r.returncode,actual71ImmutableRuntime=True,
 runtimeEntries=101,preparedAssemblyRuntime=True,declaredSourceFamilyOverrides=overlap,
 mounted=False,wholeOwnerConstructed=False,native=False,HTTP=False,accountRequests=False)
if r.returncode==0:
 with zipfile.ZipFile(wide(fixtureJar))as z:fc=set(n for n in z.namelist()if n.endswith('.class'))
 assert not fc&old and not fc&classes
 cmd=[str(c.JAVA),'-Dfile.encoding=UTF-8','-cp',str(fixtureJar)+';'+';'.join(classpath),'com.bilipai.desktop.ui.ActionStatusProofKt']
 save(out/'runtime-command.json',dict(command=cmd))
 run=subprocess.run(cmd,capture_output=True,timeout=60);wide(out/'run.log').write_bytes(run.stdout+run.stderr)
 result.update(runtimeExitCode=run.returncode,passed=run.returncode==0,fixtureClassOverlap=0,fixtureJarSHA256Bytes=sha(fixtureJar))
 print((run.stdout+run.stderr).decode('utf8',errors='replace'))
else:
 print((r.stdout+r.stderr).decode('utf8',errors='replace'));result['passed']=False
check();assert sha(jar)==pins['assemblyInputJarSHA256Bytes'];save(out/'pins-after.json',pins);save(out/'result.json',result)
raise SystemExit(0 if result['passed']else 1)
