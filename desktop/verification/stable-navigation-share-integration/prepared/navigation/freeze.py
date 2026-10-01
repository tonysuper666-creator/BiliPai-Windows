from pathlib import Path
import hashlib, json, os, subprocess

HERE=Path(__file__).resolve().parent
MAIN=HERE.parents[2]
REPO=MAIN.parent/'BiliPai-v023'
PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
    s=os.path.abspath(p)
    return Path(s if s.startswith(PREFIX) else PREFIX+s)
def sha(p): return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p, value): safe(p).write_text(json.dumps(value,indent=2)+'\n',encoding='utf-8')
receipt=json.loads(safe(HERE/'source-receipt.json').read_text(encoding='utf-8'))
for row in receipt['sources']+receipt['references']:
    data=subprocess.check_output(['git','show',receipt['target']+':'+row['path']],cwd=REPO)
    normalized=data.decode('utf-8').replace('\r\n','\n').encode('utf-8')
    assert hashlib.sha256(normalized).hexdigest()==row['sha256LF']
    target=HERE/'original-stable'/row['path']
    safe(target.parent).mkdir(parents=True,exist_ok=True)
    safe(target).write_bytes(normalized)
compile_input=json.loads(safe(HERE/'compile-06/inputs.json').read_text(encoding='utf-8'))
assert compile_input['exit']==0 and compile_input['cpEntries']==97
for row in compile_input['sources']: assert sha(row['path'])==row['sha256Bytes']
proof=json.loads(safe(HERE/'proof-05/source-binding.json').read_text(encoding='utf-8'))
assert proof['compileExit']==0 and proof['runExit']==0 and proof['productOverrideCount']==0
assert sha(HERE/'fixtures/NavigationHostFixture.kt')==proof['fixtureSha']
assert sha(HERE/'compile-06/candidate.jar')==proof['candidateSha']
overlap=json.loads(safe(HERE/'compile-06/class-overlap.json').read_text(encoding='utf-8'))
assert overlap['candidateClassCount']==173 and not overlap['all97ClassOverlap']
audit=json.loads(safe(HERE/'source-audit.json').read_text(encoding='utf-8'))
assert audit['checkCount']==81
cp_path=Path(compile_input['actualMain'])/'ordered-runtime-cp.json'
for row in json.loads(safe(cp_path).read_text(encoding='utf-8')): assert sha(row['path'])==row['sha256Bytes']
whitelist=[]
for path,destination in [
 ('prepared/tools/extract-upstream-navigation3-host.py','desktop/tools/extract-upstream-navigation3-host.py'),
 ('prepared/manual/com/bilipai/desktop/ui/DesktopNavigationHostEnvironment.kt','desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopNavigationHostEnvironment.kt'),
 ('prepared/manual/com/bilipai/desktop/ui/DesktopOriginalNavigationHost.kt','desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalNavigationHost.kt')]:
    whitelist.append({'source':path,'destination':destination,'sha256Bytes':sha(HERE/path)})
write(HERE/'install-whitelist.json',{'installOnly':whitelist,'wholeSharedSourceReplacement':False,
  'rootOwns':['Gradle','registry','Shell','actual parent window lifecycle/ViewModel/Event owners','typed destination callbacks','planner retirement']})
write(HERE/'history-boundaries.json',{
 'compile01':'missing original small closure, not acceptance',
 'compile02':'partial failed generation followed by unsuccessful compile; historical only',
 'compile03':'task-only Density local shadowing; fixed to platformDensity',
 'compile04':'compiler succeeded but FQN audit rejected duplicate existing AppNavigationAppearance; reference-only corrected',
 'compile05':'173 source classes compiled successfully before adding mandatory actual Root NavigationEvent owner seam',
 'proof01':'fixture CLI friend-list separator error; no runtime acceptance',
 'proof02_03':'task Jar longpath is_file omitted Companion entries; missing method was harness packaging, not original enum; original incomplete Jar retained',
 'proof04':'completed pre-final event-owner-seam source runtime proof; historical successful scope, not final source pin',
 'final':'compile06/proof05/source-audit81; source-only, no windows/NavDisplay compose/HTTP/account/EXE',
})
binary=[]
for folder in (HERE/'compile-06',HERE/'proof-05'):
    for p in folder.rglob('*'):
        if safe(p).is_file() and p.suffix in ('.class','.jar'):
            binary.append({'path':p.relative_to(HERE).as_posix(),'sha256Bytes':sha(p),'install':False,'git':False})
old=HERE/'compile-05/candidate-incomplete-longpath.jar'
binary.append({'path':old.relative_to(HERE).as_posix(),'sha256Bytes':sha(old),'install':False,'git':False,'historicalIncompleteJar':True})
write(HERE/'excluded-runtime-artifacts.json',{'artifacts':binary,'reason':'task compiled classes/Jars are local proof only, never install or Git'})
artifacts=[]
for p in HERE.rglob('*'):
    if not safe(p).is_file() or '__pycache__' in p.parts or p.name=='frozen-handoff.json': continue
    if p.suffix.lower() not in ('.py','.kt','.json','.md','.log','.args'): continue
    artifacts.append({'path':p.relative_to(HERE).as_posix(),'sha256Bytes':sha(p)})
artifacts.sort(key=lambda x:x['path'])
manifest={'target':receipt['target'],'artifactCount':len(artifacts),'artifacts':artifacts,
 'installWhitelistSha':sha(HERE/'install-whitelist.json'),'registryDeltaSha':sha(HERE/'registry-delta.json'),
 'actualMainManifest':compile_input['manifestSha'],'actualMainCp':compile_input['cpSha'],'actualCpCount':97,
 'candidateInputCount':31,'candidateClassCount':173,'classOverlap':0,'sourceAuditChecks':81,
 'originalNewIdentities':24,'existingFeatureMerges':6,'productionSelectedOutputs':7,'directSyncCopyCount':22,
 'mainEdited':False,'candidateSharedEdited':False,'nativeWindow':False,'navDisplayMounted':False,
 'accountOrNetworkRequest':False,'packageOrExeAccepted':False,'fullAppNavigationDestinationsComplete':False}
write(HERE/'frozen-handoff.json',manifest)
for row in artifacts: assert sha(HERE/row['path'])==row['sha256Bytes']
print('FROZEN',len(artifacts),'raw artifacts',sha(HERE/'frozen-handoff.json'))
