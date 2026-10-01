from pathlib import Path
import json,hashlib,importlib.util,subprocess
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2]
PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith(PREFIX) else PREFIX+s)
snap=MAIN/'desktop/.local/stable-product-snapshot-16'
cp=json.loads(safe(snap/'ordered-runtime-cp.json').read_text());assert len(cp)==92
for r in cp:assert hashlib.sha256(safe(r['path']).read_bytes()).hexdigest()==r['sha256Bytes'],r['path']
parent=MAIN/'desktop/.local/stable-favorites-parity'
extra=[parent/'host-tail-classes',parent/'classes-install-final']
files=list((HERE/'prepared/desktop/src/main/kotlin').rglob('*.kt'))+list((HERE/'fixture').rglob('*.kt'))
spec=importlib.util.spec_from_file_location('compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');cc=importlib.util.module_from_spec(spec);spec.loader.exec_module(cc)
args=['-no-stdlib','-no-reflect','-jvm-target','21','-Xplugin='+str(cc.PLUGIN),'-module-name','com_bilipai_desktop_bilipai_windows',
 '-Xfriend-paths='+str(snap/'main-kotlin.jar')+','+str(parent/'classes-install-final'),'-cp',';'.join(map(str,extra))+';'+ ';'.join(r['path'] for r in cp),
 '-d',str(HERE/'classes')]+list(map(str,files))
argfile=HERE/'compile.args';safe(argfile).write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args),encoding='utf-8')
run_number=1+len(list(HERE.glob('compile-??.log')))
r=subprocess.run([str(cc.JAVA),'-Dfile.encoding=UTF-8','-Xmx2g','-cp',';'.join(map(str,cc.COMPILER)),
 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(argfile)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=150)
safe(HERE/f'compile-{run_number:02}.log').write_text(r.stdout+r.stderr,encoding='utf-8');print(r.stdout+r.stderr);r.check_returncode()
safe(HERE/'compile-evidence.json').write_text(json.dumps(dict(exitCode=r.returncode,mainSnapshotSha256=hashlib.sha256(safe(snap/'manifest.json').read_bytes()).hexdigest(),
 classpathEntries=len(cp),preparedParentOverlay=[str(p) for p in extra],preparedOverrides=['ListenAudioSession.kt'],sourceFiles=[str(p) for p in files],sharedGradle=False,mainModified=False),indent=2),encoding='utf-8')
runtime=[HERE/'classes']+extra+[Path(r['path']) for r in cp]
r=subprocess.run([str(cc.JAVA),'-Djava.awt.headless=true','-Dfile.encoding=UTF-8','-cp',';'.join(map(str,runtime)),
 'com.bilipai.desktop.ui.RootFavoritesWiringFixtureKt',str(HERE/'task-store')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90)
safe(HERE/f'run-{run_number:02}.log').write_text(r.stdout+r.stderr,encoding='utf-8');print(r.stdout+r.stderr);r.check_returncode()
