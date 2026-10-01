from pathlib import Path
import importlib.util,json,subprocess,sys
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf-8')
HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('vote_compile',HERE/'compile.py')
c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
source=HERE/'fixture/CommandOwnerFixture.kt'
jar=c.compile('command-owner-candidate-01',c.sources()+[source])
args=['-Dfile.encoding=UTF-8','-Dsun.stdout.encoding=UTF-8','-Dsun.stderr.encoding=UTF-8',
      '-cp',';'.join([str(jar)]+[r['path'] for r in c.ROWS]),
      'com.bilipai.desktop.votefixture.CommandOwnerLauncher',str(HERE/'command-owner-proof.json')]
c.write(HERE/'command-owner-runtime.args','\n'.join('"'+a.replace('\\','/')+'"' for a in args))
p=subprocess.run([str(c.compiler.JAVA),'@'+str(HERE/'command-owner-runtime.args')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=50)
c.write(HERE/'command-owner-runtime.log',p.stdout+p.stderr);print((p.stdout+p.stderr)[-15000:]);p.check_returncode();c.check_cp()
c.write(HERE/'command-owner-runtime-evidence.json',json.dumps(dict(passed=True,candidateOnly=True,MainAcceptance=False,
    fixtureSha256Bytes=c.digest(source),candidateSha256Bytes=c.digest(jar),main04CP=c.CP_PIN,
    HTTP=False,HWND=False,mpvNative=False),indent=2))
