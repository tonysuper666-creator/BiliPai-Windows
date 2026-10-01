from pathlib import Path
import hashlib,importlib.util,json,os,subprocess,sys,zipfile
H=Path(__file__).resolve().parent;MAIN=H.parents[2];PREFIX=chr(92)*2+'?'+chr(92)
def wide(p):
 s=os.path.abspath(p);return Path(s if s.startswith(PREFIX) else PREFIX+s)
def sha(p):return hashlib.sha256(wide(p).read_bytes()).hexdigest()
S=MAIN/'desktop/.local/stable-product-snapshot-66'
assert sha(S/'manifest.json')=='e549c7badf5b11208e8b9fb3c2d5480759e9202eb157ae165feb0a8354cbcba3'
assert sha(S/'ordered-runtime-cp.json')=='43b1e432d592dd74088079e60731eda25d26f6c636df3570644a377b200ba9db'
cp=json.loads(wide(S/'ordered-runtime-cp.json').read_text())
for row in cp:assert sha(row['path'])==row['sha256Bytes']
sp=importlib.util.spec_from_file_location('cc',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');cc=importlib.util.module_from_spec(sp);sp.loader.exec_module(cc)
sources=[H/'prepared/metadata/com/android/purebilibili/data/repository/DesktopOriginalVideoOwnerMetadataProtocol.kt',H/'prepared/manual/com/bilipai/desktop/ui/DesktopOriginalVideoMetadataEnvironment.kt',H/'prepared/legacy/com/android/purebilibili/data/repository/DesktopOriginalVideoLoadProtocol.kt',H/'prepared/prerequisites/com/android/purebilibili/core/util/DesktopOriginalVideoDefaultQuality.kt']
out=H/('metadata-compile-'+sys.argv[1]);wide(out).mkdir(exist_ok=False)
for source in sources:
 target=wide(out/'source-inputs')/source.relative_to(H);target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(wide(source).read_bytes())
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows','-Xplugin='+str(cc.PLUGIN),'-Xfriend-paths='+cp[1]['path'],'-cp',';'.join(r['path'] for r in cp),'-d',str(out/'classes')]+list(map(str,sources))
wide(out/'compile.args').write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args),encoding='utf-8')
r=subprocess.run([str(cc.JAVA),'-Dfile.encoding=UTF-8','-Xmx2g','-cp',';'.join(map(str,cc.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compile.args')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=150)
wide(out/'compile.log').write_text(r.stdout+r.stderr,encoding='utf-8')
classes=list(wide(out/'classes').rglob('*.class')) if not r.returncode else [];old=set()
for row in cp[:3]:
 with zipfile.ZipFile(wide(row['path'])) as z:old.update(z.namelist())
overlap=sorted({p.relative_to(wide(out/'classes')).as_posix() for p in classes}&old)
if not r.returncode:
 assert all(n.startswith('com/android/purebilibili/data/repository/DesktopOriginalVideoLoadProtocol') for n in overlap),overlap
 with zipfile.ZipFile(wide(out/'candidate.jar'),'w',compression=zipfile.ZIP_DEFLATED) as z:
  for p in sorted(wide(out/'classes').rglob('*')):
   if p.is_file():z.write(p,p.relative_to(wide(out/'classes')).as_posix())
wide(out/'result.json').write_text(json.dumps(dict(preparedOnly=True,snapshot=S.name,manifest=sha(S/'manifest.json'),cp=sha(S/'ordered-runtime-cp.json'),runtimeEntries=len(cp),compileExit=r.returncode,classes=len(classes),existingOverlap=overlap,overlapScope='Only installed SAME raw protocol visibility getWbiKeys private->internal; no second cache or WBI algorithm',inputs=[dict(path=str(s),sha256Bytes=sha(s)) for s in sources],runtimeAccepted=False),indent=2)+'\n',encoding='utf-8')
for row in cp:assert sha(row['path'])==row['sha256Bytes']
print((r.stdout+r.stderr).encode('ascii','backslashreplace').decode());print('Metadata exit',r.returncode,'classes',len(classes));sys.exit(r.returncode)
