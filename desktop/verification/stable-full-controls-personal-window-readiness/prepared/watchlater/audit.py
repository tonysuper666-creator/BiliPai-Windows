from pathlib import Path
import hashlib,json,os,difflib,importlib.util,zipfile,subprocess,re,sys
H=Path(__file__).resolve().parent;MAIN=H.parents[2];R=MAIN.parent/'BiliPai-v023'
PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):return Path(PREFIX+os.path.abspath(p))
def raw(p):return safe(p).read_bytes()
def lf(p):return raw(p).decode('utf-8').replace('\r\n','\n')
def sha(p):return hashlib.sha256(raw(p)).hexdigest()
def shas(s):return hashlib.sha256(s.encode()).hexdigest()
out=H/('audit-'+(sys.argv[1] if len(sys.argv)>1 else '02'));out.mkdir(exist_ok=False)
spec=importlib.util.spec_from_file_location('watch',H/'prepared/tools/extract-upstream-watchlater.py');tool=importlib.util.module_from_spec(spec);spec.loader.exec_module(tool)
rows=tool.generate(R,out/'standalone',True);prod=tool.generate(R,out/'production',False)
checks=[]
def check(v,text):
 assert v,text
 checks.append(text)
check(len(rows)==4,'4 unique complete original input identities')
check(len(list((out/'production').rglob('*.kt')))==2,'production only full UI/VM and repository, DIRECT2 skipped')
check(len(list((out/'standalone').rglob('*.kt')))==4,'standalone source replay retains all4 original outputs')
for r in rows:
 check(raw(out/'standalone'/r['output']['path'])==raw(H/'prepared/selected'/r['output']['path']),'replay '+r['path'])
 check(shas(lf(R/r['path']))==r['sha256LfUtf8'],'original pinned LF '+r['path'])
 if r['output']['mode']=='direct':check(lf(R/r['path'])==lf(H/'prepared/selected'/r['output']['path']),'DIRECT body unchanged '+r['path'])
orig=lf(R/'app/src/main/java/com/android/purebilibili/feature/watchlater/WatchLaterScreen.kt')
selected=lf(H/'prepared/selected/com/android/purebilibili/feature/watchlater/WatchLaterScreen.kt')
funs=lambda s:re.findall(r'\bfun\s+([A-Za-z_][A-Za-z_0-9]*)\s*\(',s)
check(funs(orig)==funs(selected),'whole original UI/VM function sequence retained')
check(len(orig.splitlines())>1600 and len(selected.splitlines())>1600,'entire screen/VM body not policy/card subset')
check(orig.count('PlaylistManager.setExternalPlaylist(')==selected.count('platform.openQueue('),'all3 external queue admissions bound once')
check(all(s not in selected for s in ['AppScope.','NetworkModule.','TokenManager.']),'no separate Android global/account/IO authority')
with zipfile.ZipFile(safe(H/'compile-04/candidate.jar')) as z:new={n for n in z.namelist() if n.endswith('.class')}
with zipfile.ZipFile(safe(MAIN/'desktop/.local/stable-product-snapshot-49/main-kotlin.jar')) as z:old={n for n in z.namelist() if n.endswith('.class')}
overlap=sorted(new&old)
extra={'com/bilipai/desktop/DesktopSection.class','com/bilipai/desktop/ComposableSingletons$DesktopShellKt.class',
 'com/bilipai/desktop/data/DesktopSessionEpoch.class','com/bilipai/desktop/ui/ComposableSingletons$DesktopReadyOriginalRootMountKt.class'}
check(all(n in extra or n.startswith(('com/bilipai/desktop/DesktopShell','com/bilipai/desktop/data/DesktopRepository',
 'com/bilipai/desktop/ui/DesktopPersonalList','com/bilipai/desktop/ui/DesktopReadyOriginalRoot')) for n in overlap),
 'only four declared existing adapter families overlap actual49')
check(not any(n.startswith('com/android/purebilibili/feature/watchlater/WatchLaterPlayback') for n in new),
 'already-installed canonical PlaybackPolicy comes only from actual49')
(H/'source-inventory.json').write_text(json.dumps(rows,indent=2)+'\n',encoding='utf-8')
bases=[];patch=[]
for p in sorted((H/'prepared/existing').rglob('*.kt')):
 rel=p.relative_to(H/'prepared/existing').as_posix();a=lf(R/rel);b=lf(p)
 bases.append(dict(path=rel,baseSha256LfUtf8=shas(a),desiredSha256LfUtf8=shas(b)))
 patch+=list(difflib.unified_diff(a.splitlines(True),b.splitlines(True),fromfile='a/'+rel,tofile='b/'+rel))
(H/'consumer.patch').write_text(''.join(patch),encoding='utf-8',newline='\n')
(H/'consumer-bases.json').write_text(json.dumps(bases,indent=2)+'\n',encoding='utf-8')
registry=json.loads(raw(R/'desktop/upstream-sources.json'))['sources'];paths={r['path']:r for r in registry}
fresh=[r for r in rows if r['path'] not in paths];existing=[r for r in rows if r['path'] in paths]
check(len(fresh)==3 and len(existing)==1,'3 new identities plus existing DIRECT PlaybackPolicy feature union')
check(paths[existing[0]['path']]['mode']=='direct' and paths[existing[0]['path']]['sha256']==existing[0]['sha256LfUtf8'],
 'existing direct owner exact mode/body identity preserved')
delta=dict(newSources=[dict(path=r['path'],sha256=r['sha256LfUtf8'],mode='direct' if r['output']['mode']=='direct' else 'selected',features=['stable-personal-watchlater']) for r in fresh],
 mergeFeatures=[dict(path=r['path'],requiredExistingSha256=r['sha256LfUtf8'],preserveExistingMode=True,addFeatures=['stable-personal-watchlater']) for r in existing]+
 [dict(path='app/src/main/java/com/android/purebilibili/navigation/AppNavigation.kt',requiredExistingSha256='218267eba2d04714c57d0a67d319d6c11856c7fca4471cee294ed9e999aefa59',preserveExistingMode=True,addFeatures=['stable-personal-watchlater'])],
 referenceOnly=['app/src/main/java/com/android/purebilibili/core/network/ApiClient.kt','app/src/main/java/com/android/purebilibili/data/model/response/FavoriteModels.kt','app/src/main/java/com/android/purebilibili/core/refresh/WatchLaterRefreshBus.kt'],noWholeManifestReplacement=True)
(H/'registry-delta.json').write_text(json.dumps(delta,indent=2)+'\n',encoding='utf-8')
r=subprocess.run(['git','apply','--check',str(H/'consumer.patch')],cwd=R,capture_output=True,text=True)
check(r.returncode==0,'consumer exact patch applies current candidate')
(out/'patch-check.log').write_text(r.stdout+r.stderr,encoding='utf-8')
(out/'result.json').write_text(json.dumps(dict(passed=True,count=len(checks),checks=checks,sourceRows=4,newRegistryRows=3,
 productionEmits=2,standaloneEmits=4,originalFunctionDeclarations=len(funs(orig)),explicitExistingClassOverlaps=overlap),indent=2)+'\n',encoding='utf-8')
print('PASS',len(checks),'source/closure checks; new3/existing1; adapter-overlap',len(overlap))
