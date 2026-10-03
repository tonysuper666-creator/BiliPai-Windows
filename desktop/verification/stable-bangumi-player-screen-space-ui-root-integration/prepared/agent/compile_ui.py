from pathlib import Path
import hashlib,importlib.util,json,os,subprocess,sys,zipfile
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf8')
P=Path(__file__).resolve().parent;M=P.parents[2]
def wide(p):
 s=os.path.abspath(str(p));return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def sha(p):return hashlib.sha256(wide(p).read_bytes()).hexdigest()
S=M/'desktop/.local/stable-product-snapshot-90';cp=json.loads(wide(S/'ordered-runtime-cp.json').read_text())
def pins():
 assert sha(S/'manifest.json')=='4cf1bb27f4615aa602a1b37d2fa939acadeb18c2ba9d552dd819cfb1cfe5dd46'
 assert sha(S/'ordered-runtime-cp.json')=='30455d62eb5934c9fed3478e7cfc8486ff79f8ada35cbc80ca1cdb5a66ba8633'
 assert len(cp)==105
 for r in cp:assert sha(r['path'])==r['sha256Bytes']
pins()
spec=importlib.util.spec_from_file_location('compiler',M/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
sources=[]
for root in(P/'generated',P/'prepared'):
 sources.extend(wide(root).rglob('*.kt'))
lifecycleVm=P/'lifecycle-generated/com/android/purebilibili/feature/bangumi/DesktopOriginalBangumiPlayerViewModel.kt'
if wide(lifecycleVm).exists():sources.append(wide(lifecycleVm))
run=sys.argv[1];out=P/'runs'/run;wide(out).mkdir(parents=True,exist_ok=False)
main=next(r['path']for r in cp if r.get('source')=='desktop/build/classes/kotlin/main')
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows','-Xplugin='+str(c.PLUGIN),'-Xfriend-paths='+main,'-cp',';'.join([r['path']for r in cp]),'-d',str(out/'classes')]+[str(f)for f in sources]
wide(out/'compiler.args').write_text('\n'.join('"'+a.replace('\\','/')+'"'for a in args),encoding='utf8')
r=subprocess.run([str(c.JAVA),'-Xmx5g','-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')],capture_output=True,timeout=240)
wide(out/'compile.log').write_bytes(r.stdout+r.stderr);pins()
candidate=M.parent/'BiliPai-v023'
overlays=[]
for f in sources:
 if f==wide(lifecycleVm):overlays.append(dict(path=str(f),reason='Exact original PGC lifecycle delta: retired listener cleanup; complete business body retained'))
 elif 'prepared' in f.parts:
  rel=f.relative_to(wide(P/'prepared'))
  if wide(candidate/rel).exists():overlays.append(dict(path=str(f),existingPath=rel.as_posix(),reason='Declared exact fragment changes only; complete current cd8317/Space body is a narrow compiler input, never a full-file installation'))
evidence=dict(passed=r.returncode==0,snapshot=90,orderedCpEntries=105,sourceCount=len(sources),explicitProspectiveSources=[dict(path=str(f),sha256Bytes=sha(f))for f in sources],declaredProspectiveBridgeJar=None,intentionalExistingSourceOverlayFamilies=len(overlays),declaredExistingSourceOverlays=overlays,productionWrites=0,fullPlayerScreenPrepared=any(f.name=='DesktopOriginalBangumiPlayerScreen.kt'for f in sources),physicalRootLeafPrepared=any(f.name=='DesktopOriginalBangumiPlayerPhysicalLeaf.kt'for f in sources),normalProductCompile=False,actualRootConsumer=False,nativeAccepted=False,accountAccepted=False)
if r.returncode==0:
 with zipfile.ZipFile(wide(out/'prospective.jar'),'w',zipfile.ZIP_DEFLATED)as z:
  for f in wide(out/'classes').rglob('*'):
   if f.is_file():z.writestr(f.relative_to(wide(out/'classes')).as_posix(),f.read_bytes())
 with zipfile.ZipFile(wide(main))as z:actual=set(z.namelist())
 with zipfile.ZipFile(wide(out/'prospective.jar'))as z:
  evidence['actual90ClassIntersection']=sorted(actual.intersection(z.namelist()))
wide(out/'result.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2),encoding='utf8')
print((r.stdout+r.stderr).decode('utf8',errors='replace')[-20000:]);print('PASS'if r.returncode==0 else'FAIL',len(sources),'full UI source inputs');sys.exit(r.returncode)
