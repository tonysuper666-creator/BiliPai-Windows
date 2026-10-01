from pathlib import Path
import json,hashlib,subprocess,importlib.util,sys
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith(PREFIX) else PREFIX+s)
snap=MAIN/'desktop/.local/stable-product-snapshot-26';cp=json.loads(safe(snap/'ordered-runtime-cp.json').read_text())
assert hashlib.sha256(safe(snap/'manifest.json').read_bytes()).hexdigest()=='ba49995574a32aabb30a07578dcfef7e4ae65ccf2882940ffb8d29272ea0695c'
assert hashlib.sha256(safe(snap/'ordered-runtime-cp.json').read_bytes()).hexdigest()=='8d2f4b6242c30ef4edae74ef4c33ce876a6b1fa6508cd6eadccd321b9451ce94'
assert len(cp)==92
for r in cp:assert hashlib.sha256(safe(r['path']).read_bytes()).hexdigest()==r['sha256Bytes'],r['path']
rows=json.loads(safe(HERE/'producer-inventory.json').read_text(encoding='utf-8'))
files=[HERE/r['generated'] for r in rows if 'generated' in r and r.get('install',True)]
files += [HERE/'prepared/generated/com/android/purebilibili/core/ui/performance/DesktopHomeJankTracking.kt',HERE/'prepared/generated/com/android/purebilibili/core/util/DesktopHomeCardTapEffect.kt']
files += [HERE/'prepared/generated/com/android/purebilibili/core/store/navigation/DesktopHomeNavigationLabels.kt',HERE/'prepared/generated/com/android/purebilibili/core/store/DesktopHomeNavigationMigration.kt']
files+=list((HERE/'prepared/manual').rglob('*.kt'))+list((HERE/'prepared/fork-icons').rglob('*.kt'))
assert len(files)==len(set(files))
ccspec=importlib.util.spec_from_file_location('cc',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');cc=importlib.util.module_from_spec(ccspec);ccspec.loader.exec_module(cc)
number=1+len(list(HERE.glob('compile-full-??.log')))
args=['-no-stdlib','-no-reflect','-jvm-target','21','-Xplugin='+str(cc.PLUGIN),'-module-name','com_bilipai_desktop_bilipai_windows','-Xfriend-paths='+str(snap/'main-kotlin.jar'),'-cp',';'.join(r['path'] for r in cp),'-d',str(HERE/f'classes-full-{number:02}')]+list(map(str,files))
argfile=HERE/f'compile-full-{number:02}.args';safe(argfile).write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args),encoding='utf-8')
r=subprocess.run([str(cc.JAVA),'-Dfile.encoding=UTF-8','-Xmx3g','-cp',';'.join(map(str,cc.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(argfile)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=180)
safe(HERE/f'compile-full-{number:02}.log').write_text(r.stdout+r.stderr,encoding='utf-8');print((r.stdout+r.stderr[-20000:]).encode('ascii','backslashreplace').decode());print('exit',r.returncode,'sources',len(files));sys.exit(r.returncode)

