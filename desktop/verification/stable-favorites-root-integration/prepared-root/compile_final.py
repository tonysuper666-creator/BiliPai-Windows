from pathlib import Path
import json,hashlib,importlib.util,subprocess,sys
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2]
PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith(PREFIX) else PREFIX+s)
snap=MAIN/'desktop/.local/stable-product-snapshot-17'
assert hashlib.sha256(safe(snap/'manifest.json').read_bytes()).hexdigest()=='3b56a4a47fbd1d06b1d3b4be274bc3f69597dea669f04358eb7282292ab51e44'
assert hashlib.sha256(safe(snap/'ordered-runtime-cp.json').read_bytes()).hexdigest()=='25533b03785344f3a4a360a6db292775ad370824449c7a9c3a5b98702e3812d6'
cp=json.loads(safe(snap/'ordered-runtime-cp.json').read_text());assert len(cp)==92
for r in cp:assert hashlib.sha256(safe(r['path']).read_bytes()).hexdigest()==r['sha256Bytes'],r['path']
files=list((HERE/'prepared/desktop/src/main/kotlin').rglob('*.kt'))+list((HERE/'reference/desktop/src/main/kotlin').rglob('*.kt'))+list((HERE/'fixture').rglob('*.kt'))
spec=importlib.util.spec_from_file_location('compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');cc=importlib.util.module_from_spec(spec);spec.loader.exec_module(cc)
args=['-no-stdlib','-no-reflect','-jvm-target','21','-Xplugin='+str(cc.PLUGIN),'-module-name','com_bilipai_desktop_bilipai_windows',
 '-Xfriend-paths='+str(snap/'main-kotlin.jar'),'-cp',';'.join(r['path'] for r in cp),
 '-d',str(HERE/'classes-final')]+list(map(str,files))
number=1+len(list(HERE.glob('compile-final-??.log')))
argfile=HERE/f'compile-final-{number:02}.args';safe(argfile).write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args),encoding='utf-8')
r=subprocess.run([str(cc.JAVA),'-Dfile.encoding=UTF-8','-Xmx3g','-cp',';'.join(map(str,cc.COMPILER)),
 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(argfile)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=180)
safe(HERE/f'compile-final-{number:02}.log').write_text(r.stdout+r.stderr,encoding='utf-8');print(r.stdout+r.stderr);r.check_returncode()
safe(HERE/'compile-final-evidence.json').write_text(json.dumps(dict(exitCode=r.returncode,mainSnapshotSha256=hashlib.sha256(safe(snap/'manifest.json').read_bytes()).hexdigest(),
 classpathEntries=len(cp),parentPreparedOverlay=False,preparedOverrides=['DesktopShell.kt','ListenAudioSession.kt','DesktopOriginalFavoritesHost.kt'],
 sourceFiles=[dict(path=str(p),sha256LF=hashlib.sha256(safe(p).read_bytes()).hexdigest()) for p in files],sharedGradle=False,mainModified=False),indent=2),encoding='utf-8')
if '--compile-only' in sys.argv:
 print('Final prepared source compile PASS; unchanged 4/28 actor fixture not repeated');sys.exit(0)
r=subprocess.run([str(cc.JAVA),'-Djava.awt.headless=true','-Dfile.encoding=UTF-8','-cp',str(HERE/'classes-final')+';'+ ';'.join(r['path'] for r in cp),
 'com.bilipai.desktop.ui.RootFavoritesWiringFixtureKt',str(HERE/f'task-store-final-{number:02}')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90)
safe(HERE/f'run-final-{number:02}.log').write_text(r.stdout+r.stderr,encoding='utf-8');print(r.stdout+r.stderr);r.check_returncode()
