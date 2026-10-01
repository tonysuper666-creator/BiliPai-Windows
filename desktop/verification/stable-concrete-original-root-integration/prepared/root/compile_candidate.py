from pathlib import Path
import hashlib,json,importlib.util,os,subprocess,sys,zipfile
HERE=Path(__file__).resolve().parent
MAIN=HERE.parents[2]
PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
    s=os.path.abspath(p);return Path(s if s.startswith(PREFIX) else PREFIX+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
SNAPSHOT=MAIN/'desktop/.local/stable-product-snapshot-47'
assert sha(SNAPSHOT/'manifest.json')=='72e862d74e18c2b388853634f0ef47c48ca1adba25b5d074ecd23fc6ecb7c3e0'
assert sha(SNAPSHOT/'ordered-runtime-cp.json')=='9fecec31b818a1b1d8be25f628ff6ec69e354a5c179ca42445aa1a1db3d52e94'
cp=json.loads(safe(SNAPSHOT/'ordered-runtime-cp.json').read_text(encoding='utf-8'))
assert len(cp)==97
for row in cp:assert sha(row['path'])==row['sha256Bytes'],row['path']
run=HERE/('compile-'+(sys.argv[1] if len(sys.argv)>1 else '01'))
safe(run).mkdir(parents=True,exist_ok=False)
offline=MAIN/'desktop/.local/stable-offline-task-player-parity/prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui'
sources=list(offline.glob('DesktopOfflineTaskPlayer*.kt'))+list((HERE/'prepared/manual').rglob('*.kt'))+list((HERE/'prepared/direct').rglob('*.kt'))+list((HERE/'prepared/existing/desktop/src/main').rglob('*.kt'))
spec=importlib.util.spec_from_file_location('cc',MAIN/'desktop/.local/source9-appearance/compile-miuix.py')
cc=importlib.util.module_from_spec(spec);spec.loader.exec_module(cc)
inputs={'actualSnapshot':str(SNAPSHOT),'manifestSha':sha(SNAPSHOT/'manifest.json'),
 'orderedCpSha':sha(SNAPSHOT/'ordered-runtime-cp.json'),'cpEntries':97,
 'sources':[{'path':str(p),'sha256Bytes':sha(p)} for p in sources],
 'prepared':True,'rootMounted':False,'mainIntegration':False,
 'productOverrides':'Explicit existing families + concrete Shell; actual47 A/Profile/Download reused, two Offline new sources'}
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows',
 '-Xfriend-paths='+str(SNAPSHOT/'main-kotlin.jar'),'-Xplugin='+str(cc.PLUGIN),
 '-cp',';'.join(r['path'] for r in cp),'-d',str(run/'classes')]+[str(p) for p in sources]
safe(run/'compile.args').write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args),encoding='utf-8')
r=subprocess.run([str(cc.JAVA),'-Dfile.encoding=UTF-8','-Xmx3g','-cp',';'.join(map(str,cc.COMPILER)),
 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(run/'compile.args')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=240)
safe(run/'compile.log').write_text(r.stdout+r.stderr,encoding='utf-8')
inputs['exit']=r.returncode
safe(run/'inputs.json').write_text(json.dumps(inputs,indent=2)+'\n',encoding='utf-8')
if r.returncode==0:
    files=[p for p in (run/'classes').rglob('*') if safe(p).is_file()]
    with zipfile.ZipFile(safe(run/'candidate.jar'),'w',zipfile.ZIP_DEFLATED) as z:
        for p in files:z.writestr(p.relative_to(run/'classes').as_posix(),safe(p).read_bytes())
for row in cp:assert sha(row['path'])==row['sha256Bytes']
for row in inputs['sources']:assert sha(row['path'])==row['sha256Bytes']
print((r.stdout+r.stderr).encode('ascii','backslashreplace').decode());print('exit',r.returncode);sys.exit(r.returncode)
