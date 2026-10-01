from pathlib import Path
import hashlib,importlib.util,json,subprocess
HERE=Path(__file__).resolve().parent; MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith(PREFIX) else PREFIX+s)
snap=MAIN/'desktop/.local/stable-product-snapshot-15'
assert hashlib.sha256(safe(snap/'manifest.json').read_bytes()).hexdigest()=='c665e8cc40604866341c9ac5f6ef992ed84b52fe709a1f934337f754012cab87'
assert hashlib.sha256(safe(snap/'ordered-runtime-cp.json').read_bytes()).hexdigest()=='bcbc863d545922aaa4308076a4d4651c219aafacf339e8cb3000e59696f9e335'
cp=json.loads(safe(snap/'ordered-runtime-cp.json').read_text());assert len(cp)==92
for r in cp:assert hashlib.sha256(safe(r['path']).read_bytes()).hexdigest()==r['sha256Bytes'],r['path']
spec=importlib.util.spec_from_file_location('compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');cc=importlib.util.module_from_spec(spec);spec.loader.exec_module(cc)
files=list((HERE/'prepared/generated').rglob('*.kt'))+list((HERE/'prepared/desktop/src/main/kotlin').rglob('*.kt'))+list((HERE/'fixture').rglob('*.kt'))
args=['-no-stdlib','-no-reflect','-jvm-target','21','-Xplugin='+str(cc.PLUGIN),'-module-name','com_bilipai_desktop_bilipai_windows','-Xfriend-paths='+str(snap/'main-kotlin.jar'),'-cp',';'.join(r['path'] for r in cp),'-d',str(HERE/'classes-install-final')]+list(map(str,files))
argfile=HERE/'compile-final.args';argfile.write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args),encoding='utf-8')
r=subprocess.run([str(cc.JAVA),'-Dfile.encoding=UTF-8','-Xmx3g','-cp',';'.join(map(str,cc.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(argfile)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=180)
(HERE/'compile-final.log').write_text(r.stdout+r.stderr,encoding='utf-8');print(('Compiler exit '+str(r.returncode)+': '+str((r.stdout+r.stderr).count('error:'))+' diagnostics'));r.check_returncode()
(HERE/'compile-final-evidence.json').write_text(json.dumps(dict(productSnapshotSha='c665e8cc40604866341c9ac5f6ef992ed84b52fe709a1f934337f754012cab87',entries=92,sourceCount=len(files),productSourceCount=len(files)-1,fixtureSourceCount=1,mainOverrides=False,preparedCandidate=True,compiler='Kotlin2.4.0/Compose2.4.0'),indent=2),encoding='utf-8')
