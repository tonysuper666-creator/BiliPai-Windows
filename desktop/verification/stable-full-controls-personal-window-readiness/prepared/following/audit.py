from pathlib import Path
import hashlib,importlib.util,json,re,subprocess,zipfile
H=Path(__file__).resolve().parent;MAIN=H.parents[2];R=MAIN.parent/'BiliPai-v023';OUT=H/'audit-04';OUT.mkdir(exist_ok=False)
def lf(p):return Path(p).read_text(encoding='utf-8').replace('\r\n','\n')
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def shas(s):return hashlib.sha256(s.encode()).hexdigest()
def module(name,path):
 spec=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
tool=module('following',H/'prepared/tools/extract-upstream-following.py')
rows=tool.generate(R,OUT/'standalone',True);tool.generate(R,OUT/'production',False)
checks=[]
def check(v,text):assert v,text;checks.append(text)
check(len(rows)==5,'five original identities, full UI/VM plus full cache and three pure policies')
check(len(list((OUT/'production').rglob('*.kt')))==2,'production emits two selected files, Sync solely supplies three DIRECT policies')
for row in rows:
 check(lf(OUT/'standalone'/row['output']['path'])==lf(H/'prepared/selected'/row['output']['path']),'standalone replay '+row['path'])
 check(shas(lf(R/row['path']))==row['sha256LfUtf8'],'pinned original '+row['path'])
 if row['output']['mode']=='direct':check(lf(R/row['path'])==lf(H/'prepared/selected'/row['output']['path']),'DIRECT unchanged '+row['path'])
original=lf(R/'app/src/main/java/com/android/purebilibili/feature/following/FollowingListScreen.kt')
selected=lf(H/'prepared/selected/com/android/purebilibili/feature/following/FollowingListScreen.kt')
funs=lambda s:re.findall(r'\bfun\s+(\w+)\s*\(',s)
check([f for f in funs(selected) if f!='checkRequest']==funs(original),'every original UI/VM function retained in original order, one owned request helper')
check(len(original.splitlines())==1330 and len(selected.splitlines())>1300,'complete original screen and VM retained, not policy subset')
check(not any(s in selected for s in ['NetworkModule.','ActionRepository.','viewModel()','ViewModel()']),'sole real owned API/actions/entry scope, no Android globals/default VM')
cacheOriginal=lf(R/'app/src/main/java/com/android/purebilibili/core/store/FollowingCacheStore.kt')
cacheSelected=lf(H/'prepared/selected/com/android/purebilibili/core/store/FollowingCacheStore.kt')
check(cacheSelected.replace('import com.android.purebilibili.feature.following.DesktopFollowingCacheContext as Context','import android.content.Context')==cacheOriginal,'entire original cache schema/codec/2000 bound and MID normalization unchanged')
# Existing Favorites outputs are unchanged except the sole actions output.
old=module('old',R/'desktop/tools/extract-upstream-favorites.py');old.generate(R,OUT/'old-favorites',False)
new=module('new',H/'prepared/existing/desktop/tools/extract-upstream-favorites.py');new.generate(R,OUT/'new-favorites',False)
changed=[]
for p in (OUT/'old-favorites').rglob('*.kt'):
 rel=p.relative_to(OUT/'old-favorites');other=OUT/'new-favorites'/rel
 if lf(p)!=lf(other):changed.append(rel.as_posix())
check(changed==['com/android/purebilibili/data/repository/DesktopOriginalFavoriteActions.kt'],'existing sole producer changes only actions output; all previous repositories/UI/VM unchanged')
parser=module('parser',R/'desktop/tools/extract-upstream-dynamic-reply-protocol.py');old.parser=parser
actionOriginal=lf(R/'app/src/main/java/com/android/purebilibili/data/repository/ActionRepository.kt');actionSelected=lf(H/'prepared/actions/com/android/purebilibili/data/repository/DesktopOriginalFavoriteActions.kt')
names=['createFavFolder','favoriteVideo','getDefaultFolderId','toggleWatchLater','normalizeRelationTagIds','normalizeRelationTags','chunkFollowGroupTargetMids','isFollowGroupRetryableError','addUsersToRelationTagsWithRetry','followUser','getFollowGroupTags','getUserFollowGroupIds','getFollowGroupMemberMids','getAllFollowGroupUsers','getFollowGroupUsers','overwriteFollowGroupIds']
for name in names:
 a,b=parser.fun_span(actionOriginal,name);body=old.drop_logs(actionOriginal[a:b]).replace('NetworkModule.api.','environment.api.').replace('TokenManager.csrfCache','environment.csrf()').replace('TokenManager.midCache','environment.currentMid()').replace('WatchLaterRefreshBus.notifyChanged()','environment.watchLaterChanged()').replace('val response = api.','val response = environment.api.').replace('                    api.','                    environment.api.').replace('_followStateChanges.tryEmit(FollowStateChange(mid = mid, isFollowing = follow))','environment.confirmFollow(FollowStateChange(mid = mid, isFollowing = follow))')
 a,b=parser.fun_span(actionSelected,name)
 check(body==actionSelected[a:b],'exact selected original protocol/algorithm '+name)
with zipfile.ZipFile(H/'compile-06/candidate.jar') as z:newclasses={n for n in z.namelist() if n.endswith('.class')}
with zipfile.ZipFile(MAIN/'desktop/.local/stable-product-snapshot-49/main-kotlin.jar') as z:oldclasses={n for n in z.namelist() if n.endswith('.class')}
overlap=sorted(newclasses & oldclasses)
extra={'com/bilipai/desktop/DesktopSection.class','com/bilipai/desktop/ComposableSingletons$DesktopShellKt.class','com/android/purebilibili/feature/list/DesktopFavoriteScopedOwner.class'}
check(all(n in extra or n.startswith(('com/bilipai/desktop/DesktopShell','com/bilipai/desktop/ui/DesktopPersonalList','com/android/purebilibili/data/repository/DesktopOriginalFavoriteActions','com/android/purebilibili/feature/list/DesktopFavoriteEnvironment')) for n in overlap),'only declared existing adapter/source families overlap immutable actual49')
check('_followStateChanges' not in actionSelected and 'environment.confirmFollow(' in actionSelected,'one existing Root follow-state bus, not a page-local parallel bus')
check('com/android/purebilibili/data/repository/FollowStateChange.class' not in newclasses and 'com/android/purebilibili/data/repository/FollowStateChange.class' in oldclasses,'sole original FollowStateChange type comes only from actual49 Home producer')
registry={r['path']:r for r in json.loads(lf(R/'desktop/upstream-sources.json'))['sources']}
check(all(row['path'] not in registry for row in rows),'five new identities; no second owner for existing policies')
actionPath='app/src/main/java/com/android/purebilibili/data/repository/ActionRepository.kt'
delta=dict(newSources=[dict(path=r['path'],sha256=r['sha256LfUtf8'],mode=r['output']['mode'],features=['stable-personal-following']) for r in rows],
 mergeFeatures=[dict(path=actionPath,requiredExistingSha256=registry[actionPath]['sha256'],preserveExistingMode=True,addFeatures=['stable-personal-following'])],
 references=['core/network/ApiClient.kt','data/model/response/ListModels.kt','core/util/PinyinUtils.kt','navigation3/BiliPaiNavKey.kt'],noWholeRegistryReplacement=True)
(H/'registry-delta.json').write_text(json.dumps(delta,indent=2)+'\n',encoding='utf-8')
# Consumer base is frozen WatchLater75 desired, not old Main49. Prove applicability against
# exactly that prerequisite, leaving live Candidate/Main untouched.
baseroot=OUT/'watchlater-base';watch=MAIN/'desktop/.local/stable-personal-watchlater-parity/prepared/existing'
for rel in ['desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopPersonalListsRoot.kt','desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt']:
 p=baseroot/rel;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(lf(watch/rel),encoding='utf-8',newline='\n')
rel='desktop/src/main/kotlin/com/android/purebilibili/feature/list/DesktopFavoriteEnvironment.kt'
p=baseroot/rel;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(lf(R/rel),encoding='utf-8',newline='\n')
p=subprocess.run(['git','apply','--check','--unsafe-paths',str(H/'consumer.patch')],cwd=baseroot,capture_output=True,text=True)
check(p.returncode==0,'consumer patch matches exact prerequisite WatchLater75 bases')
(OUT/'consumer-patch.log').write_text(p.stdout+p.stderr,encoding='utf-8')
p=subprocess.run(['git','apply','--check',str(H/'favorite-actions-producer.patch')],cwd=R,capture_output=True,text=True)
check(p.returncode==0,'sole Favorites actions patch matches current Candidate tool')
(OUT/'producer-patch.log').write_text(p.stdout+p.stderr,encoding='utf-8')
(OUT/'result.json').write_text(json.dumps(dict(passed=True,count=len(checks),checks=checks,originalFunctions=len(funs(original)),existingClassOverlap=overlap,newRegistryRows=5,productionEmits=2,standaloneEmits=5,prepared=True,mainIntegration=False),indent=2)+'\n',encoding='utf-8')
print('PASS',len(checks),'source/closure checks')
