from pathlib import Path
import importlib.util, json, subprocess, sys
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf-8')
HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('vote_compile',HERE/'compile.py')
c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
consumer=HERE/'candidate-consumers-01.jar'
assert c.digest(consumer)=='794095cc62f3171a5a01f71475e643e9f2c2fe8e19ec54590ccf07987865b29d'
cp=[str(consumer)]+[r['path'] for r in c.ROWS]
source=HERE/'fixture/OwnedGradeFixture.kt'
fixture=c.compile('owned-grade-fixture-01',[source],extra=['-cp',';'.join(cp),
    '-Xfriend-paths='+','.join([str(consumer),c.ROWS[1]['path']])])
args=['-Dfile.encoding=UTF-8','-Dsun.stdout.encoding=UTF-8','-Dsun.stderr.encoding=UTF-8','-cp',
      ';'.join([str(fixture)]+cp),'com.bilipai.desktop.votefixture.OwnedGradeLauncher',str(HERE/'owned-grade-proof.json')]
c.write(HERE/'owned-grade-runtime.args','\n'.join('"'+a.replace('\\','/')+'"' for a in args))
p=subprocess.run([str(c.compiler.JAVA),'@'+str(HERE/'owned-grade-runtime.args')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=60)
c.write(HERE/'owned-grade-runtime.log',p.stdout+p.stderr);print((p.stdout+p.stderr)[-15000:]);p.check_returncode();c.check_cp()
c.write(HERE/'owned-grade-runtime-evidence.json',json.dumps(dict(passed=True,candidateOnly=True,MainAcceptance=False,
    sourceSha256Bytes=c.digest(source), fixtureSha256Bytes=c.digest(fixture),
    candidateConsumersSha256Bytes=c.digest(consumer),main04CP=c.CP_PIN,HTTP=False,HWND=False,
    realAccount=False,mutableClientField='test-only BilibiliApi proxy replacement; visitorInitialized/generation warmed to forbid visitor HTTP'),indent=2))
