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

ui=MAIN/'desktop/.local/stable-home-page-parity/classes-full-07'
files=list((HERE/'prepared/generated').rglob('*.kt'))+list((HERE/'prepared/manual').rglob('*.kt'))+list((HERE/'prepared/replacements').rglob('*.kt'))
ccspec=importlib.util.spec_from_file_location('cc',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');cc=importlib.util.module_from_spec(ccspec);ccspec.loader.exec_module(cc)
number=1+len(list(HERE.glob('compile-vm-??.log')))
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows','-Xfriend-paths='+str(snap/'main-kotlin.jar')+','+str(ui),'-cp',str(ui)+';'+';'.join(r['path'] for r in cp),'-d',str(HERE/f'classes-vm-{number:02}')]+list(map(str,files))
identities=[{'path':str(p),'sha256Bytes':hashlib.sha256(safe(p).read_bytes()).hexdigest()} for p in files]
safe(HERE/f'compile-vm-{number:02}-source-identities.json').write_text(json.dumps(identities,indent=2)+'\n',encoding='utf-8')
argfile=HERE/f'compile-vm-{number:02}.args';safe(argfile).write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args),encoding='utf-8')
r=subprocess.run([str(cc.JAVA),'-Dfile.encoding=UTF-8','-Xmx2g','-cp',';'.join(map(str,cc.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(argfile)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=180)
for identity in identities: assert hashlib.sha256(safe(identity['path']).read_bytes()).hexdigest()==identity['sha256Bytes'],identity['path']
safe(HERE/f'compile-vm-{number:02}.log').write_text(r.stdout+r.stderr,encoding='utf-8');print((r.stdout+r.stderr).encode('ascii','backslashreplace').decode());print('exit',r.returncode,'sources',len(files));sys.exit(r.returncode)
