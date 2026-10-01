"""One narrow Kotlin/Compose compile against immutable actual stable classes11."""
from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys,zipfile
sys.dont_write_bytecode=True
sys.stdout.reconfigure(encoding='utf-8')
HERE=Path(__file__).resolve().parent
REPO=HERE.parents[2]
PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith(PREFIX) else PREFIX+s)
def digest(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,s):
 p=safe(p);p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s,encoding='utf-8',newline='\n')
CP=REPO/'desktop/.local/stable-product-snapshot-11/ordered-runtime-cp.json'
CP_PIN='a158698f0c336c7c28d63efc3b07f830576a70c1432a877d5ca2f92aaa3237fa'
assert digest(CP)==CP_PIN
ROWS=json.loads(safe(CP).read_text(encoding='utf-8'));assert len(ROWS)==92
def check_cp():
 for row in ROWS:assert digest(row['path'])==row['sha256Bytes'],row['path']
spec=importlib.util.spec_from_file_location('weekly_compiler',REPO/'desktop/.local/source9-appearance/compile-miuix.py')
c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
sources=sorted((HERE/'generated').rglob('*.kt'))+sorted((HERE/'prepared/desktop/src/main/kotlin').rglob('*.kt'))+sorted((HERE/'references').glob('*.kt'))+sorted((HERE/'fixture').glob('*.kt'))
output=HERE/'candidate-weekly-01.jar'
check_cp()
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(r['path'] for r in ROWS),
 '-Xplugin='+str(c.PLUGIN),'-Xfriend-paths='+ROWS[1]['path'],'-module-name','com_bilipai_desktop_bilipai_windows',
 '-d',str(output)]+list(map(str,sources))
write(HERE/'compile-01.args','\n'.join('"'+str(a).replace('\\','/')+'"' for a in args))
compiled=subprocess.run([str(c.JAVA),'-Xmx3g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(HERE/'compile-01.args')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=180)
write(HERE/'compile-01.log',compiled.stdout+compiled.stderr)
print((compiled.stdout+compiled.stderr)[-16000:]);compiled.check_returncode()
check_cp()
product=set().union(*(set(zipfile.ZipFile(safe(r['path'])).namelist()) for r in ROWS[:3]))
overlap=sorted(n for n in set(zipfile.ZipFile(safe(output)).namelist())&product if n.endswith('.class'))
write(HERE/'compile-01-evidence.json',json.dumps(dict(passed=True,preparedOnly=True,candidateAcceptance=False,classpathSha256Bytes=CP_PIN,
 candidateJarSha256Bytes=digest(output),productClassOverrides=overlap,
 sources=[dict(path=str(p.relative_to(HERE)),sha256Bytes=digest(p)) for p in sources],
 sourcesArePreparedOverlay=True,existingLeavesAndModelsFromActualClasses11=True,sharedGradle=False,HWND=False),indent=2))
destination=HERE/'runtime-01-output';destination.mkdir(exist_ok=True)
runtime=['-Dfile.encoding=UTF-8','-Dsun.stdout.encoding=UTF-8','-Dsun.stderr.encoding=UTF-8','-cp',';'.join([str(output)]+[r['path'] for r in ROWS]),'com.bilipai.desktop.weeklyfixture.WeeklyFixtureKt',str(destination)]
write(HERE/'runtime-01.args','\n'.join('"'+str(a).replace('\\','/')+'"' for a in runtime))
result=subprocess.run([str(c.JAVA),'@'+str(HERE/'runtime-01.args')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=150)
write(HERE/'runtime-01.log',result.stdout+result.stderr)
print((result.stdout+result.stderr)[-18000:]);result.check_returncode();check_cp()
write(HERE/'runtime-01-evidence.json',json.dumps(dict(passed=True,freshJVM=True,preparedOnly=True,candidateAcceptance=False,
 fakeAPI=True,sockets=False,sharedGradle=False,HWND=False,classpath=[dict(path=str(output),sha256Bytes=digest(output))]+ROWS,
 proofSha256Bytes=digest(destination/'proof.json')),indent=2))
