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
spec=importlib.util.spec_from_file_location('cc',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');cc=importlib.util.module_from_spec(spec);spec.loader.exec_module(cc)
out=H/('whole-compile-'+sys.argv[1]);resume='--audit-existing' in sys.argv
if not resume:wide(out).mkdir(exist_ok=False)
sources=[H/'prepared/whole/com/android/purebilibili/feature/video/viewmodel/VideoPlaybackViewModel.kt',H/'prepared/manual/com/bilipai/desktop/ui/DesktopOriginalVideoPlaybackOwnerEnvironment.kt',H/'prepared/notes/com/android/purebilibili/data/repository/DesktopOriginalVideoNoteProtocol.kt',H/'prepared/manual/com/bilipai/desktop/ui/DesktopOriginalVideoNoteEnvironment.kt',H/'prepared/direct/com/android/purebilibili/feature/video/playback/resolver/NextPlaybackResolver.kt']
sources+=sorted((H/'prepared/prerequisites').rglob('*.kt'))
sources+=[H/'prepared/legacy/com/bilipai/desktop/ui/DesktopOriginalPlayerSettingsContext.kt']
sources+=[H/'prepared/legacy/com/android/purebilibili/core/store/DesktopOriginalVideoControlSettings.kt']
sources+=[H/'prepared/legacy/com/bilipai/desktop/ui/DesktopOriginalVideoPlaybackInvocation.kt',H/'prepared/manual/com/bilipai/desktop/ui/DesktopOriginalVideoOwnerRepositoryView.kt']
for p in sources:
 rel=p.relative_to(H) if p.is_relative_to(H) else Path('explicit-child-reference')/p.relative_to(MAIN/'desktop/.local')
 target=wide(out/'source-inputs')/rel
 if resume:assert target.read_bytes()==wide(p).read_bytes(),str(p)
 else:target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(wide(p).read_bytes())
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows','-Xplugin='+str(cc.PLUGIN),'-Xfriend-paths='+cp[1]['path'],'-cp',';'.join(r['path'] for r in cp),'-d',str(out/'classes')]+list(map(str,sources))
if resume:
 from types import SimpleNamespace
 log=wide(out/'compile.log').read_text(encoding='utf-8')
 assert ': error:' not in log and wide(out/'classes/com/android/purebilibili/feature/video/viewmodel/VideoPlaybackViewModel.class').is_file()
 r=SimpleNamespace(returncode=0,stdout='',stderr=log)
else:
 wide(out/'compile.args').write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args),encoding='utf-8')
 r=subprocess.run([str(cc.JAVA),'-Dfile.encoding=UTF-8','-Xmx3g','-cp',';'.join(map(str,cc.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compile.args')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=180)
 wide(out/'compile.log').write_text(r.stdout+r.stderr,encoding='utf-8')
classes=list(wide(out/'classes').rglob('*.class')) if not r.returncode else [];old=set()
for row in cp[:3]:
 with zipfile.ZipFile(wide(row['path'])) as z:old.update(z.namelist())
overlap=sorted({p.relative_to(wide(out/'classes')).as_posix() for p in classes}&old)
if not r.returncode:
 expected_families=['com/bilipai/desktop/ui/DesktopOriginalPlayerSettingsContext','com/bilipai/desktop/ui/DesktopOriginalPlayerMirrorPreferences','com/bilipai/desktop/ui/DesktopOriginalPlayerPreferenceValues','com/bilipai/desktop/ui/DesktopOriginalPlayerSettingsDataStore','com/bilipai/desktop/ui/DesktopOriginalPlayerSettingsContextKt','com/android/purebilibili/core/store/DesktopOriginalVideoControlSettings']
 expected_families+=['com/bilipai/desktop/ui/DesktopOriginalVideoPlaybackInvocation','com/bilipai/desktop/ui/DesktopOriginalVideoPlaybackStatus','com/bilipai/desktop/ui/DesktopOriginalVideoPlaybackInvocationPorts']
 unexpected=[name for name in overlap if not any(name==prefix+'.class' or name.startswith(prefix+'$') for prefix in expected_families)]
 assert not unexpected,unexpected
 with zipfile.ZipFile(wide(out/'candidate.jar'),'w',compression=zipfile.ZIP_DEFLATED) as z:
  for p in sorted(wide(out/'classes').rglob('*')):
   if p.is_file():z.write(p,p.relative_to(wide(out/'classes')).as_posix())
wide(out/'result.json').write_text(json.dumps(dict(preparedOnly=True,actualProductSnapshot=S.name,actualManifest=sha(S/'manifest.json'),actualCp=sha(S/'ordered-runtime-cp.json'),runtimeEntries=len(cp),compileExit=r.returncode,classes=len(classes),overlap=overlap,overlapExplanation='Only explicit existing family hunks: Context StringSet pair, ControlSettings Sync reader shares existing cache, Invocation readonly current-request accessor. No parallel authorities installed.',resumedPostCompileAudit=resume,sourceInputs=[dict(path=str(p),sha256Bytes=sha(p)) for p in sources],fullVmNotAccepted=True),indent=2)+'\n',encoding='utf-8')
for row in cp:assert sha(row['path'])==row['sha256Bytes']
print((r.stdout+r.stderr).encode('ascii','backslashreplace').decode());print('Whole compile exit',r.returncode,'classes',len(classes))
sys.exit(r.returncode)
