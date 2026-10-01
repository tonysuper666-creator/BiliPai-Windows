from pathlib import Path
import json,subprocess,importlib.util,hashlib
LANE=Path(__file__).resolve().parent;MAIN=LANE.parents[4]/'work/BiliPai'
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return safe(p).read_text(encoding='utf-8')
sp=importlib.util.spec_from_file_location('share_proof_compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(sp);sp.loader.exec_module(c)
cp=json.loads(read(LANE/'input-audit.json'))['verifiedDependencyPins'];candidate=json.loads(read(LANE/'compile-07/compile-result.json'))
out=LANE/'proof/run-02';safe(out).mkdir(parents=True,exist_ok=True)
classpath=[candidate['jar']]+[r['path'] for r in cp]
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(classpath),'-Xfriend-paths='+candidate['jar']+','+cp[1]['path'],'-d',str(out/'classes'),str(LANE/'proof/VideoShareProof.kt')]
argfile=out/'compiler.args';safe(argfile).write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args),encoding='utf-8')
r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(argfile)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=120)
safe(out/'compile.log').write_text(r.stdout+r.stderr,encoding='utf-8');assert r.returncode==0,r.stderr
args=['-cp',';'.join([str(out/'classes')]+classpath),'com.bilipai.desktop.ui.VideoShareProofKt',str(out/'files')]
argfile=out/'runner.args';safe(argfile).write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args),encoding='utf-8')
r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','@'+str(argfile)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=60)
safe(out/'run.log').write_text(r.stdout+r.stderr,encoding='utf-8');assert r.returncode==0,r.stderr
print(r.stdout)
