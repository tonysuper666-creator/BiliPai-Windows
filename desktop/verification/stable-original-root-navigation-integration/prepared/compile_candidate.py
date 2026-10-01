from pathlib import Path
import hashlib,json,importlib.util,os,subprocess,sys,zipfile
HERE=Path(__file__).resolve().parent
MAIN=HERE.parents[2]
PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
    s=os.path.abspath(p);return Path(s if s.startswith(PREFIX) else PREFIX+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
SNAPSHOT=MAIN/'desktop/.local/stable-product-snapshot-43'
assert sha(SNAPSHOT/'manifest.json')=='6252d7e16a2f413d48de0265dc7debb315b2eaefca0b58cde0d34d9258feb362'
assert sha(SNAPSHOT/'ordered-runtime-cp.json')=='0785ee7cbe88b2615d9193882310a1fe36c368c71f9180b2a418a17136ab8440'
cp=json.loads(safe(SNAPSHOT/'ordered-runtime-cp.json').read_text(encoding='utf-8'))
assert len(cp)==97
for row in cp:assert sha(row['path'])==row['sha256Bytes'],row['path']
sources=list((HERE/'prepared/manual').rglob('*.kt'))+list((HERE/'generated/com').rglob('*.kt'))
sources+=list((HERE/'prepared/existing/desktop/src/main').rglob('*.kt'))
sources+=[HERE/'generated-protocol/com/android/purebilibili/data/repository/DesktopOriginalHomeVideoProtocol.kt']
run=HERE/('compile-'+(sys.argv[1] if len(sys.argv)>1 else '01'))
safe(run).mkdir(parents=True,exist_ok=False)
spec=importlib.util.spec_from_file_location('cc',MAIN/'desktop/.local/source9-appearance/compile-miuix.py')
cc=importlib.util.module_from_spec(spec);spec.loader.exec_module(cc)
inputs={'actualSnapshot':str(SNAPSHOT),'manifestSha':sha(SNAPSHOT/'manifest.json'),
 'orderedCpSha':sha(SNAPSHOT/'ordered-runtime-cp.json'),'cpEntries':97,
 'sources':[{'path':str(p),'sha256Bytes':sha(p)} for p in sources],
 'prepared':True,'rootMounted':False,'mainIntegration':False,'productOverrides':'EXPLICIT same original VideoProtocol + existing interface/facade + captured Palette factory seam'}
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows',
 '-Xfriend-paths='+str(SNAPSHOT/'main-kotlin.jar'),'-Xplugin='+str(cc.PLUGIN),
 '-cp',';'.join(r['path'] for r in cp),'-d',str(run/'classes')]+[str(p) for p in sources]
safe(run/'compile.args').write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args),encoding='utf-8')
r=subprocess.run([str(cc.JAVA),'-Dfile.encoding=UTF-8','-Xmx3g','-cp',';'.join(map(str,cc.COMPILER)),
 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(run/'compile.args')],capture_output=True,text=True,
 encoding='utf-8',errors='replace',timeout=240)
safe(run/'compile.log').write_text(r.stdout+r.stderr,encoding='utf-8')
inputs['exit']=r.returncode
safe(run/'inputs.json').write_text(json.dumps(inputs,indent=2)+'\n',encoding='utf-8')
if r.returncode==0:
    actual=set()
    for row in cp:
        with zipfile.ZipFile(safe(row['path'])) as z:actual.update(n for n in z.namelist() if n.endswith('.class'))
    files=[p for p in (run/'classes').rglob('*') if safe(p).is_file()]
    classes={p.relative_to(run/'classes').as_posix() for p in files if p.suffix=='.class'}
    overlap=sorted(classes&actual)
    safe(run/'class-overlap.json').write_text(json.dumps({'candidateClasses':len(classes),'all97Overlap':overlap},indent=2)+'\n')
    families=['com/android/purebilibili/data/repository/DesktopOriginalHomeVideoProtocol',
        'com/bilipai/desktop/ui/DesktopHomeDataEnvironment','com/bilipai/desktop/ui/DesktopHomeVideoRequests',
        'com/bilipai/desktop/ui/DesktopHomeHistoryRequests','com/bilipai/desktop/ui/DesktopHomeLiveRequests',
        'com/bilipai/desktop/ui/DesktopHomeMessageRequests','com/bilipai/desktop/ui/DesktopHomeActionRequests',
        'com/bilipai/desktop/ui/DesktopHomeFollowRequests','com/bilipai/desktop/ui/DesktopHomeBlockedRequests',
        'com/bilipai/desktop/ui/DesktopHomeFollowingRequests','com/bilipai/desktop/ui/DesktopHomeIdentityAnalytics',
        'com/bilipai/desktop/ui/DesktopHomeRequestPorts','com/bilipai/desktop/ui/DesktopHomeRootFactory',
        'com/bilipai/desktop/ui/DesktopHomeRootWindowBindings','com/bilipai/desktop/ui/DesktopHomeRootReturnPorts',
        'com/bilipai/desktop/ui/DesktopHomeRetainedRoot']
    undeclared=[name for name in overlap if not any(name==prefix+'.class' or name.startswith(prefix+'$') for prefix in families)]
    assert not undeclared,undeclared
    safe(run/'declared-family-overlap.json').write_text(json.dumps({'exactOriginalFamilyOverrides':overlap,'undeclared':undeclared},indent=2)+'\n')
    with zipfile.ZipFile(safe(run/'candidate.jar'),'w',zipfile.ZIP_DEFLATED) as z:
        for p in files:z.writestr(p.relative_to(run/'classes').as_posix(),safe(p).read_bytes())
for row in cp:assert sha(row['path'])==row['sha256Bytes']
for row in inputs['sources']:assert sha(row['path'])==row['sha256Bytes']
print((r.stdout+r.stderr).encode('ascii','backslashreplace').decode());print('exit',r.returncode);sys.exit(r.returncode)
