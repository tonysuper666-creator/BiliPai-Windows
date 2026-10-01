from pathlib import Path
import json,hashlib,subprocess,importlib.util,sys
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith(PREFIX) else PREFIX+s)
snap=MAIN/'desktop/.local/stable-product-snapshot-25';cp=json.loads(safe(snap/'ordered-runtime-cp.json').read_text())
assert hashlib.sha256(safe(snap/'manifest.json').read_bytes()).hexdigest()=='03b33e828f3e754a1e2937f4bfefb1aa2c1c1b6796516ed6ab47914e65a7c2a0'
assert hashlib.sha256(safe(snap/'ordered-runtime-cp.json').read_bytes()).hexdigest()=='18d13ab6a21303dc338f184a4e108e172f2948e5a0b46f99c99c611508ac30dc'
assert len(cp)==92
for r in cp:assert hashlib.sha256(safe(r['path']).read_bytes()).hexdigest()==r['sha256Bytes'],r['path']

ccspec=importlib.util.spec_from_file_location('cc',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');cc=importlib.util.module_from_spec(ccspec);ccspec.loader.exec_module(cc)
run=HERE/'prefs-proof-01';run.mkdir(exist_ok=True)
classes=HERE/'classes-full-06'
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','home_prefs_fixture','-Xfriend-paths='+str(snap/'main-kotlin.jar')+','+str(classes),'-cp',str(classes)+';'+';'.join(r['path'] for r in cp),'-d',str(run/'classes'),str(HERE/'fixtures/HomePreferencesFixture.kt')]
argfile=run/'compile.args';safe(argfile).write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args),encoding='utf-8')
r=subprocess.run([str(cc.JAVA),'-Dfile.encoding=UTF-8','-Xmx2g','-cp',';'.join(map(str,cc.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(argfile)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=120)
safe(run/'compile.log').write_text(r.stdout+r.stderr,encoding='utf-8');print(r.stdout+r.stderr);assert r.returncode==0
cpPaths=[str(run/'classes'),str(classes)]+[r['path'] for r in cp]
safe(run/'classpath.txt').write_text(';'.join(cpPaths),encoding='utf-8')
r=subprocess.run([str(cc.JAVA),'-Dfile.encoding=UTF-8','-cp',';'.join(cpPaths),'com.bilipai.desktop.ui.HomePreferencesFixtureKt',str(run)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=40)
safe(run/'run.log').write_text(r.stdout+r.stderr,encoding='utf-8');print((r.stdout+r.stderr).encode('ascii','backslashreplace').decode());print('exit',r.returncode);sys.exit(r.returncode)
