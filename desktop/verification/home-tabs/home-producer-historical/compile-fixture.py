from pathlib import Path
import hashlib,importlib.util,json,os,subprocess,sys,tempfile,zipfile
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf-8',errors='replace')
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2];ATTEMPT=sys.argv[1] if len(sys.argv)>1 else '1'
def ext(p):
 p=str(Path(p).absolute());return Path(p if p.startswith('\\\\?\\') else '\\\\?\\'+p)
def sha(p):return hashlib.sha256(ext(p).read_bytes()).hexdigest()
def write(p,v):ext(p.parent).mkdir(parents=True,exist_ok=True);ext(p).write_text(v,encoding='utf-8',newline='\n')
def args(p,v):write(p,'\n'.join('"'+str(a).replace('\\','/')+'"' for a in v)+'\n')
snapshot=HERE.parent/'dynamic-crash-main-product-snapshot/manifest.json';assert sha(snapshot)=='c01330bf9345f8f5d1be2fa70bce6d7c1c980179448ee22f2ad0f467292a6345'
deps=[dict(path=r['path'],sha256Bytes=r['sha256Bytes']) for r in json.loads(snapshot.read_text())['artifacts']]+json.loads((HERE.parent/'settings-diagnostics-parity/dependency-identities.json').read_text())[3:]
assert len(deps)==234
def verify():
 for row in deps:assert sha(Path(row['path']))==row['sha256Bytes'],row['path']
 for row in json.loads((HERE/'target-baselines.json').read_text()):assert sha(REPO/row['path'])==row['baseSha256Bytes'],row['path']
verify();write(HERE/'dependency-identities.json',json.dumps(deps,indent=2)+'\n')
spec=importlib.util.spec_from_file_location('hc_compiler',HERE.parent/'source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
sources=list((HERE/'generated').rglob('*.kt'))+list((HERE/'prepared/desktop/src').rglob('*.kt'))+[HERE/'JunitFixture.kt']
# Root's new immutable product already contains both exact original grid/prepend classes.
reused=[]
if (HERE/'UiFixture.kt').exists():sources+=[HERE/'UiFixture.kt']
if (HERE/'ColdDiskReader.kt').exists():sources+=[HERE/'ColdDiskReader.kt']
cp=[r['path'] for r in deps];out=HERE/('classes-attempt'+ATTEMPT);out.mkdir(exist_ok=False)
file=HERE/('compiler-'+ATTEMPT+'.args');args(file,['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(cp),'-Xfriend-paths='+cp[0],'-Xplugin='+str(c.PLUGIN),'-module-name','com_bilipai_desktop_bilipai_windows','-d',out]+sources)
r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Xmx2g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(file)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=80)
write(HERE/('compile-'+ATTEMPT+'.log'),r.stdout+r.stderr);print(r.stdout+r.stderr);r.check_returncode()
# Explicitly enumerate every overlapping product class with long-path-safe traversal.
with zipfile.ZipFile(cp[0]) as jar:product=set(jar.namelist())
pins=[]
with zipfile.ZipFile(cp[0]) as jar:
 for name in ['com.bilipai.desktop.plugins.DesktopPluginStore','com.bilipai.desktop.plugins.DesktopPluginContext',
  'com.bilipai.desktop.appearance.DesktopAppearanceThemeKt','com.bilipai.desktop.appearance.DesktopWindowConfiguration',
  'com.android.purebilibili.core.ui.components.AppSingleChoiceRowKt','com.android.purebilibili.core.ui.components.AppSegmentOption',
  'com.android.purebilibili.core.ui.components.FeedVerticalStaggeredGridKt','com.android.purebilibili.core.ui.components.FeedPrependScrollPolicyKt',
  'com.bilipai.desktop.data.DesktopRepository','com.bilipai.desktop.data.DesktopDiscoveryRepository','com.bilipai.desktop.ui.CommunityFeedMemory','com.bilipai.desktop.ui.CommunityFeedState']:
  entry=name.replace('.','/')+'.class';pins.append(dict(**{'class':name},jar=cp[0],sha256Bytes=hashlib.sha256(jar.read(entry)).hexdigest()))
write(HERE/'actual-main-class-pins.json',json.dumps(pins,indent=2)+'\n')
entries=[]
for directory,_,files in os.walk(ext(out)):
 for name in files:
  p=Path(directory)/name;entry=str(p)[len(str(ext(out)))+1:].replace('\\','/')
  entries.append(dict(entry=entry,sha256Bytes=sha(p),shadowsProduct=entry in product))
write(HERE/'prepared-class-identities.json',json.dumps(entries,indent=2)+'\n')
write(HERE/'product-class-overlap.json',json.dumps([r for r in entries if r['shadowsProduct']],indent=2)+'\n')
sandbox=Path(tempfile.mkdtemp(prefix='bp-hc-'));(sandbox/'fixture-owner.json').write_text(json.dumps(dict(task='settings-home-card-parity',attempt=ATTEMPT)))
env=os.environ.copy()
for key in ['APPDATA','LOCALAPPDATA','USERPROFILE','TEMP','TMP']:
 p=sandbox/key.lower();p.mkdir();env[key]=str(p)
output=HERE/'proof'/ATTEMPT;output.mkdir(parents=True,exist_ok=False)
mains=[('junit','com.bilipai.desktop.settings.proof.JunitFixtureKt',[str(output/'junit.json')])]
if (HERE/'UiFixture.kt').exists():mains+=[('ui','com.bilipai.desktop.ui.proof.UiFixtureKt',[str(output),str(HERE/'actual-main-class-pins.json')])]
for name,main,tail in mains:
 file=HERE/(name+'-'+ATTEMPT+'.args');args(file,['-XX:ErrorFile='+str(output/'hs-err-%p.log'),'-Dfile.encoding=UTF-8','-Dsun.stdout.encoding=UTF-8','-Dsun.stderr.encoding=UTF-8','-Djava.awt.headless=true','-Duser.home='+str(sandbox),'-Djava.io.tmpdir='+str(sandbox/'temp'),'-cp',str(out)+';'+';'.join(cp),main]+tail)
 r=subprocess.run([str(c.JAVA),'@'+str(file)],cwd=HERE,env=env,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90)
 write(HERE/(name+'-'+ATTEMPT+'.log'),r.stdout+r.stderr);print(r.stdout+r.stderr);r.check_returncode()
if (output/'ui.json').exists():
 cold=[]
 for case in json.loads((output/'ui.json').read_text())['checks']:
  root=output/case['case']/'actual-task-global';file=HERE/('cold-'+case['case']+'-'+ATTEMPT+'.args')
  args(file,['-Dfile.encoding=UTF-8','-Duser.home='+str(sandbox),'-cp',str(out)+';'+';'.join(cp),'com.bilipai.desktop.settings.proof.ColdDiskReaderKt',str(root)]+(['compact'] if case.get('compactMemoryFlow') else []))
  r=subprocess.run([str(c.JAVA),'@'+str(file)],cwd=HERE,env=env,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=15)
  write(HERE/('cold-'+case['case']+'-'+ATTEMPT+'.log'),r.stdout+r.stderr);print(r.stdout+r.stderr);r.check_returncode();cold.append(dict(case=case['case'],passed=True,actualColdJvm=True))
 write(output/'cold-disk.json',json.dumps(cold,indent=2)+'\n')
verify();write(HERE/'compile-evidence.json',json.dumps(dict(passed=True,activeClasses=out.name,acceptedProof=str(output.relative_to(HERE)),
 productSnapshotManifestSha256Bytes=sha(snapshot),currentProductJars=deps[:3],mainFilesChanged=False,sharedGradle=False,HWND=False,HTTP=False,
 explicitExistingConsumerOverlay='DiscoveryScreens.kt',reusedSiblingOriginalSources=[dict(path=str(p),sha256Bytes=sha(p)) for p in reused],
 sources=[dict(path=str(p),sha256Bytes=sha(p)) for p in sources]),indent=2)+'\n')
