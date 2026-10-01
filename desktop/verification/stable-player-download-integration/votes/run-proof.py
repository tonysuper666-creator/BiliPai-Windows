"""Fresh JVM candidate proof; real App* dialog layers, fake API only."""
from pathlib import Path
import importlib.util, json, subprocess, sys
sys.dont_write_bytecode = True
sys.stdout.reconfigure(encoding='utf-8')
HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('vote_task_compile',HERE/'compile.py')
c = importlib.util.module_from_spec(spec); spec.loader.exec_module(c)
name = sys.argv[1] if len(sys.argv)>1 else 'candidate-proof-01'
sources = c.sources()+sorted((HERE/'fixture').glob('*.kt'))
candidate = c.compile(name,sources)
output = HERE/(name+'-output'); output.mkdir(exist_ok=True)
cp = [str(candidate)]+[r['path'] for r in c.ROWS]
args=['-Dfile.encoding=UTF-8','-Dsun.stdout.encoding=UTF-8','-Dsun.stderr.encoding=UTF-8','-cp',';'.join(cp),'com.bilipai.desktop.votefixture.LauncherKt',str(output)]
runtime = HERE/(name+'-runtime.args')
c.write(runtime,'\n'.join('"'+str(a).replace('\\','/')+'"' for a in args))
p=subprocess.run([str(c.compiler.JAVA),'@'+str(runtime)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=150)
c.write(HERE/(name+'-runtime.log'),p.stdout+p.stderr)
print((p.stdout+p.stderr)[-20000:]);p.check_returncode();c.check_cp()
c.write(HERE/(name+'-runtime-evidence.json'),json.dumps(dict(passed=True,mainAcceptance=False,
    classpath=[dict(path=str(candidate),sha256Bytes=c.digest(candidate))]+c.ROWS,
    freshJvm=True, codeSourceProof='Candidate jar is first; explicit Main04 override families in compile evidence.',
    fakeAPI=True,actualAccount=False,HTTP=False,HWND=False,
    fixtureSources=[dict(path=str(s.relative_to(HERE)),sha256Bytes=c.digest(s)) for s in sorted((HERE/'fixture').glob('*.kt'))],
    proofSha256Bytes=c.digest(output/'proof.json')),indent=2))
