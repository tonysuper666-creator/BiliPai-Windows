from pathlib import Path
import hashlib,json,subprocess,importlib.util
LANE=Path(__file__).resolve().parent;MAIN=LANE.parents[4]/'work/BiliPai'
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
sp=importlib.util.spec_from_file_location('livecompiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(sp);sp.loader.exec_module(c)
aud=json.loads(safe(LANE/'input-audit.json').read_text(encoding='utf-8'));prod=json.loads(safe(LANE/'compile-02/compile-result.json').read_text(encoding='utf-8'))
cp=[prod['jar'],aud['stable-home-request-ports-parity']['jar']]+[r['path'] for r in aud['verifiedDependencyPins']]
assert sha(prod['jar'])==prod['jarSha256Bytes']
out=LANE/'fixture-01';safe(out).mkdir(exist_ok=True)
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(cp),'-Xfriend-paths='+','.join(cp[:2]+[cp[3]]),'-module-name','live_list_fixture','-d',str(out/'classes'),str(LANE/'LiveListFixture.kt')]
argfile=out/'compiler.args';safe(argfile).write_text('\n'.join('"'+str(a).replace('\\','/')+'"' for a in args),encoding='utf-8')
r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Xmx2g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(argfile)],capture_output=True,timeout=120)
safe(out/'compile.log').write_bytes(r.stdout+r.stderr)
if r.returncode:print((r.stdout+r.stderr).decode('utf-8',errors='replace').encode('ascii',errors='backslashreplace').decode());raise SystemExit(r.returncode)
r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Xmx2g','-cp',str(out/'classes')+';'+';'.join(cp),'com.bilipai.desktop.ui.LiveListFixtureKt',str(out/'results')],capture_output=True,timeout=60)
safe(out/'run.log').write_bytes(r.stdout+r.stderr)
print((r.stdout+r.stderr).decode('utf-8',errors='replace').encode('ascii',errors='backslashreplace').decode());assert r.returncode==0,r.returncode
