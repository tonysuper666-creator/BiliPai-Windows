from pathlib import Path
import hashlib,importlib.util,json,os,subprocess,sys,zipfile
H=Path(__file__).resolve().parent;MAIN=H.parents[2];PREFIX=chr(92)*2+'?'+chr(92)
def wide(p):
 s=os.path.abspath(p);return Path(s if s.startswith(PREFIX) else PREFIX+s)
def sha(p):return hashlib.sha256(wide(p).read_bytes()).hexdigest()
S=MAIN/'desktop/.local/stable-product-snapshot-50';cp=json.loads(wide(S/'ordered-runtime-cp.json').read_text())
for r in cp:assert sha(r['path'])==r['sha256Bytes']
child=MAIN/'desktop/.local/stable-video-player-page-parity/content-runs/06/candidate.jar';assert sha(child)=='f7ec9380b81737ed299bf49cecef412a1e9eb58b23f9fc5e80b7a969aaddc135'
spec=importlib.util.spec_from_file_location('cc',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');cc=importlib.util.module_from_spec(spec);spec.loader.exec_module(cc)
out=H/('models-compile-'+sys.argv[1]);wide(out).mkdir(exist_ok=False)
sources=sorted(wide(H/'prepared').rglob('*.kt'));paths=[str(child)]+[r['path'] for r in cp]
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows','-Xfriend-paths='+str(child)+','+cp[1]['path'],'-cp',';'.join(paths),'-d',str(out/'classes')]+list(map(str,sources))
wide(out/'compile.args').write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args),encoding='utf-8')
r=subprocess.run([str(cc.JAVA),'-Dfile.encoding=UTF-8','-Xmx2g','-cp',';'.join(map(str,cc.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compile.args')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=120)
wide(out/'compile.log').write_text(r.stdout+r.stderr,encoding='utf-8')
classes=[p for p in wide(out/'classes').rglob('*.class')] if not r.returncode else []
new={p.relative_to(wide(out/'classes')).as_posix() for p in classes};old=set()
for row in cp[:3]:
 with zipfile.ZipFile(wide(row['path'])) as z:old.update(z.namelist())
overlap=sorted(new&old);assert not overlap,overlap
if not r.returncode:
 with zipfile.ZipFile(wide(out/'candidate.jar'),'w',compression=zipfile.ZIP_DEFLATED) as z:
  for p in classes:z.write(p,p.relative_to(wide(out/'classes')).as_posix())
identity=dict(preparedOnly=True,scope='canonical whole model/Success extensions only; full5464 holder NOT compiled',actual50Manifest=sha(S/'manifest.json'),actual50Cp=sha(S/'ordered-runtime-cp.json'),runtimeEntries=len(cp),explicitChildStage3JarSha=sha(child),compileExit=r.returncode,inputSources=len(sources),classes=len(classes),productOverlaps=overlap,sourceInputs=[dict(path=str(p),sha256Bytes=sha(p)) for p in sources])
wide(out/'result.json').write_text(json.dumps(identity,indent=2)+'\n',encoding='utf-8');print((r.stdout+r.stderr).encode('ascii','backslashreplace').decode());print(json.dumps(identity,ensure_ascii=True)[:450])
for row in cp:assert sha(row['path'])==row['sha256Bytes']
sys.exit(r.returncode)
