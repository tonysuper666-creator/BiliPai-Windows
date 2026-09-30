"""Retry only the unchanged compiled UI under a short task-owned ICU extraction directory."""
from pathlib import Path
import hashlib,importlib.util,json,os,subprocess,sys,tempfile
sys.dont_write_bytecode=True
sys.stdout.reconfigure(encoding='utf-8',errors='replace')
HERE=Path(__file__).resolve().parent
def safe(p):
 value=str(Path(p).absolute());return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,v):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(v,encoding='utf-8',newline='\n')
deps=json.loads((HERE/'dependency-identities.json').read_text())
for r in deps:assert sha(Path(r['path']))==r['sha256Bytes'],r['path']
spec=importlib.util.spec_from_file_location('short_ui_compiler',HERE.parent/'source9-appearance/compile-miuix.py')
c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
out=HERE/'classes-attempt1';before={str(p.relative_to(safe(out))):sha(p) for p in safe(out).rglob('*') if p.is_file()}
# Java Skiko resource extraction uses native paths; keep its task-specific HOME comfortably below MAX_PATH.
sandbox=Path(tempfile.mkdtemp(prefix='bpd-ui-'));env=os.environ.copy()
for name in ['LOCALAPPDATA','APPDATA','USERPROFILE','HOME','TEMP','TMP']:
 p=sandbox/name.lower();p.mkdir();env[name]=str(p)
argfile=HERE/'original-ui-short-runtime.args'
args=['-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-Djava.net.useSystemProxies=false',
 '-Duser.home='+str(sandbox/'home'),'-Djava.io.tmpdir='+str(sandbox/'tmp'),'-cp',str(out)+';'+';'.join(r['path'] for r in deps),
 'com.bilipai.desktop.ui.DiscoveryStorageUiFixtureKt',str(HERE/'proof/ui-short')]
write(argfile,'\n'.join('"'+str(v).replace('\\','/')+'"' for v in args)+'\n')
r=subprocess.run([str(c.JAVA),'@'+str(argfile)],cwd=HERE,env=env,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=45)
write(HERE/'original-ui-short-runtime.log',r.stdout+r.stderr);print(r.stdout+r.stderr);r.check_returncode()
after={str(p.relative_to(safe(out))):sha(p) for p in safe(out).rglob('*') if p.is_file()};assert before==after
for r in deps:assert sha(Path(r['path']))==r['sha256Bytes'],r['path']
write(HERE/'ui-short-runtime-evidence.json',json.dumps(dict(passed=True,unchangedClasses=True,sandbox=str(sandbox),
 earlierLongSandboxICUExtractionFatalRetained=True,HWND=False,sharedGradle=False,externalHTTP=False),indent=2)+'\n')
