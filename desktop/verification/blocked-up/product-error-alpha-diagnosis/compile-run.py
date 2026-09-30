from pathlib import Path
import hashlib,importlib.util,json,os,subprocess,sys,tempfile,zipfile
sys.dont_write_bytecode=True
sys.stdout.reconfigure(encoding='utf-8',errors='replace')
HERE=Path(__file__).resolve().parent;OLD=HERE.parent/'discovery-storage-product-ui-proof'
def safe(p):
 value=str(Path(p).absolute());return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,v):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(v,encoding='utf-8',newline='\n')
def verify_old():
 assert sha(OLD/'verified-artifacts.json')=='78b134f0e122e02c626934f826dcf31762fc4dd19a6d5feee1c9bbb48f5515c6'
 for r in json.loads((OLD/'verified-artifacts.json').read_text(encoding='utf-8'))['files']:assert sha(OLD/r['path'])==r['sha256Bytes'],r['path']
verify_old();deps=json.loads((OLD/'dependency-identities.json').read_text(encoding='utf-8'))
def verify_deps():
 for r in deps:assert sha(Path(r['path']))==r['sha256Bytes'],r['path']
verify_deps();write(HERE/'dependency-identities.json',json.dumps(deps,indent=2)+'\n')
from PIL import Image
from collections import Counter
analyses=[]
for p in sorted((OLD/'proof').glob('*startup*.png'))+sorted((OLD/'proof').glob('*stopped*.png')):
 image=Image.open(p);pixels=Counter(image.get_flattened_data());analyses.append(dict(path=str(p),sha256Bytes=sha(p),
  mode=image.mode,alphaBoundingBox=image.getbbox(),transparentPixels=sum(n for rgba,n in pixels.items() if rgba[3]==0),
  nonTransparentPixels=sum(n for rgba,n in pixels.items() if rgba[3]>0),distinctRgbValues=sorted(set(rgba[:3] for rgba in pixels)),
  mostCommon=[dict(rgba=list(k),count=n) for k,n in pixels.most_common(6)]))
write(HERE/'original-frozen-alpha-analysis.json',json.dumps(analyses,indent=2)+'\n')
spec=importlib.util.spec_from_file_location('alpha_compiler',HERE.parent/'source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
cp=[r['path'] for r in deps];out=HERE/'classes';safe(out).mkdir(parents=True,exist_ok=False)
def args(p,values):write(p,'\n'.join('"'+str(v).replace('\\','/')+'"' for v in values)+'\n')
file=HERE/'compiler.args';args(file,['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(cp),'-Xplugin='+str(c.PLUGIN),'-Xfriend-paths='+cp[0],
 '-module-name','com_bilipai_desktop_bilipai_windows','-d',str(out),str(HERE/'StoppedCaptureFixture.kt')])
r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Xmx1g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(file)],
 capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=45)
write(HERE/'compile.log',r.stdout+r.stderr);print(r.stdout+r.stderr);r.check_returncode()
names={str(p.relative_to(safe(out))).replace('\\','/') for p in safe(out).rglob('*.class')}
with zipfile.ZipFile(Path(cp[0])) as z:assert not names.intersection(z.namelist()),'Fixture shadows actual product'
sandbox=Path(tempfile.mkdtemp(prefix='bpd-alpha-'));env=os.environ.copy()
for name in ['LOCALAPPDATA','APPDATA','USERPROFILE','HOME','TEMP','TMP']:
 p=sandbox/name.lower();p.mkdir();env[name]=str(p)
file=HERE/'runtime.args';args(file,['-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-Djava.net.useSystemProxies=false','-Duser.home='+str(sandbox/'home'),
 '-Djava.io.tmpdir='+str(sandbox/'tmp'),'-cp',str(out)+';'+str(OLD/'classes-attempt2')+';'+';'.join(cp),
 'com.bilipai.desktop.ui.StoppedCaptureFixtureKt',str(HERE/'proof')])
r=subprocess.run([str(c.JAVA),'@'+str(file)],cwd=HERE,env=env,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=45)
write(HERE/'runtime.log',r.stdout+r.stderr);print(r.stdout+r.stderr);r.check_returncode();verify_old();verify_deps()
write(HERE/'compile-evidence.json',json.dumps(dict(passed=True,pureFixtureSourceSha256Bytes=sha(HERE/'StoppedCaptureFixture.kt'),activeClasses='classes',
 originalFrozenManifestSha256Bytes=sha(OLD/'verified-artifacts.json'),originalProductClassesUnchanged=True,productSnapshotJars=deps[:3],
 productionSourceOverrides=0,explicitAppSurfaceFixtureControl=True,mainAppSurfaceFixClaimed=False,HWND=False,sharedGradle=False,HTTP=False),indent=2)+'\n')
