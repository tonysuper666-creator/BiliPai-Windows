from pathlib import Path
import hashlib,json,subprocess,sys,zipfile,struct,difflib
sys.dont_write_bytecode=True
import generate as g
import compile as c
HERE=c.HERE;REPO=c.MAIN.parent/'BiliPai-v023'
def digest(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def save(p,s):g.save(p,s)
prepared=HERE/'prepared'
for p in (HERE/'platform').rglob('*.kt'):
 dest=prepared/'desktop/src/main/kotlin'/p.relative_to(HERE/'platform');dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(p.read_bytes())
tool=prepared/'desktop/tools/extract-upstream-favorite-folder-sheet.py';tool.parent.mkdir(parents=True,exist_ok=True);tool.write_bytes((HERE/'generate.py').read_bytes())
inventory=json.loads((prepared/'generated/source-inventory.json').read_text(encoding='utf-8'))
production=g.generate(REPO,prepared/'production-generated',False)
assert len(inventory['emitted'])==7 and len(production['emitted'])==4
for row in production['emitted']:
 assert (prepared/'production-generated'/row['path']).read_bytes()==(prepared/'generated'/row['path']).read_bytes()
inputs={}
for path in g.PATHS:
 text=subprocess.check_output(['git','show',g.COMMIT+':'+path],cwd=REPO).decode('utf-8').replace('\r\n','\n')
 p=HERE/'original-inputs'/path;g.write(p,text);inputs[path]=text
compiled=json.loads((HERE/'compile-02/compile-result.json').read_text(encoding='utf-8'))
accepted=json.loads((HERE/'proof-02/accepted-result.json').read_text(encoding='utf-8'))
assert compiled['status']==accepted['status']=='PASS' and accepted['assertions']==33
payloads=list((prepared/'generated').rglob('*.kt'))+list((prepared/'desktop/src/main/kotlin').rglob('*.kt'))
assert len(payloads)==9
assert sorted(digest(p) for p in payloads)==sorted(x['sha256LF'] for x in compiled['sourceInputs'])
checks=[]
def check(name,value):assert value,name;checks.append(dict(name=name,passed=True))
for row in inventory['emitted']:
 if row['mode'] in ('direct','selected-full-renderer'):
  adapted=(prepared/'generated'/row['path']).read_text(encoding='utf-8')
  for patch in reversed(row['adaptations']):
   assert adapted.count(patch['after'])==1;adapted=adapted.replace(patch['after'],patch['before'])
  check('original-full-file-reverse:'+row['path'],adapted==inputs[row['origin']])
originalAction=inputs[g.PATHS[5]]
currentProtocol=(prepared/'generated/com/android/purebilibili/data/repository/DesktopOriginalFavoriteFolderProtocol.kt').read_text(encoding='utf-8')
for name in ('getFavoriteFolders','updateFavoriteFolders'):
 original,_,_=g.function(originalAction,name);current,_,_=g.function(currentProtocol,name)
 reverse=current.replace('            } catch (cancelled: kotlinx.coroutines.CancellationException) {\n                throw cancelled\n','')
 reverse='\n'.join(line for line in reverse.split('\n') if line.strip() not in ('assertOwned()','kotlinx.coroutines.currentCoroutineContext().ensureActive()'))
 reverse=reverse.replace('readMid()','TokenManager.midCache').replace('readCsrf()','TokenManager.csrfCache')
 if name=='updateFavoriteFolders':reverse=reverse.replace('            } catch (e: Exception) {\n','            } catch (e: Exception) {\n                android.util.Log.e("ActionRepository", "updateFavoriteFolders failed", e)\n')
 check('protocol-original-reverse:'+name,reverse==original)
 g.write(HERE/'source-diffs'/(name+'.patch'),'\n'.join(difflib.unified_diff(original.splitlines(),current.splitlines(),fromfile='original/'+name,tofile='adapter/'+name))+'\n')
originalVM=inputs[g.PATHS[4]]
currentVM=(prepared/'generated/com/android/purebilibili/feature/video/viewmodel/DesktopOriginalFavoriteFolderSession.kt').read_text(encoding='utf-8')
originalLoad,_,_=g.function(originalVM,'loadFavoriteFolders');currentLoad,_,_=g.function(currentVM,'loadFavoriteFolders')
loadResult=originalLoad[originalLoad.index('            result.fold('):originalLoad.index('            _isFavoriteFoldersLoading.value = false')]
check('load-result-selection-keep-and-fallback-body-exact',loadResult in currentLoad)
originalSave,_,_=g.function(originalVM,'saveFavoriteFolderSelection');currentSave,_,_=g.function(currentVM,'saveFavoriteFolderSelection')
saveReceipt=originalSave[originalSave.index('            result.onSuccess {'):originalSave.index('            _isSavingFavoriteFolders.value = false')]
check('save-receipt-folder-state-count-event-dismiss-feedback-body-exact',saveReceipt in currentSave)
for name in ('resolveFavoriteFolderMutation','resolveFavoriteFolderDialogTargetAid','shouldSyncFavoriteFolderUiState'):
 original,_,_=g.function(originalVM,name,indent='')
 selected=(prepared/'generated/com/android/purebilibili/feature/video/viewmodel/FavoriteFolderSessionPolicy.kt').read_text(encoding='utf-8')
 check('VM-selected-policy-exact:'+name,original in selected)
for name in ('showFavoriteFolderDialog','dismissFavoriteFolderDialog','invalidateFavoriteFolderCache','loadFavoriteFolders','toggleFavoriteFolderSelection','saveFavoriteFolderSelection','applyFavoriteSaveUiState','updateFavoriteUiState','createFavoriteFolder'):
 original,_,_=g.function(originalVM,name);current,_,_=g.function(currentVM,name)
 g.write(HERE/'source-diffs'/('session-'+name+'.patch'),'\n'.join(difflib.unified_diff(original.splitlines(),current.splitlines(),fromfile='original/'+name,tofile='owned-session/'+name))+'\n')
check('compiled-input-payload-byte-identity',True)
check('default-production-does-not-emit-direct-policy',not any(r['mode']=='direct' for r in production['emitted']))

# Public/internal Kotlin top-level callables may collide even with distinct Kt wrapper classes.
def methods(data,entry):
 p=8
 def u2():
  nonlocal p
  v=struct.unpack_from('>H',data,p)[0];p+=2;return v
 def u4():
  nonlocal p
  v=struct.unpack_from('>I',data,p)[0];p+=4;return v
 def attrs():
  nonlocal p
  for _ in range(u2()):u2();length=u4();p+=length
 n=u2();pool=[None]*n;i=1
 while i<n:
  tag=data[p];p+=1
  if tag==1:length=u2();pool[i]=data[p:p+length].decode('utf-8',errors='replace');p+=length
  elif tag in (3,4):p+=4
  elif tag in (5,6):p+=8;i+=1
  elif tag in (7,8,16,19,20):p+=2
  elif tag in (9,10,11,12,17,18):p+=4
  elif tag==15:p+=3
  else:raise ValueError(tag)
  i+=1
 p+=6;interfaces=u2();p+=interfaces*2
 for _ in range(u2()):p+=6;attrs()
 rows=[];package=entry.rsplit('/',1)[0]
 for _ in range(u2()):
  flags=u2();name=pool[u2()];desc=pool[u2()];attrs()
  if flags&1 and flags&8 and not name.startswith('access$'):rows.append((package,name,desc[:desc.index(')')+1],entry))
 return rows
candidate=HERE/'compile-02/original-favorite-folder.jar';actual=c.SNAP/'main-kotlin.jar'
with zipfile.ZipFile(candidate) as own,zipfile.ZipFile(actual) as main:
 ownNames={x for x in own.namelist() if x.endswith('.class')};mainNames={x for x in main.namelist() if x.endswith('.class')}
 ownMethods=[m for e in ownNames if e.endswith('Kt.class') and '$' not in e for m in methods(own.read(e),e)]
 mainMethods=[m for e in mainNames if e.endswith('Kt.class') and '$' not in e for m in methods(main.read(e),e)]
 ownKeys={x[:3] for x in ownMethods};intersections=[x for x in mainMethods if x[:3] in ownKeys]
 check('actual-class-FQN-intersection-zero',not ownNames&mainNames)
 check('actual-same-package-JVM-name-parameter-descriptor-intersection-zero',not intersections)
save(HERE/'symbol-audit.json',dict(status='PASS',candidateJarSha256Bytes=digest(candidate),actualMain15JarSha256Bytes=digest(actual),candidateClasses=len(ownNames),actualClasses=len(mainNames),classIntersections=[],candidateTopLevelPublicStaticMethods=len(ownMethods),actualTopLevelPublicStaticMethods=len(mainMethods),topLevelIntersections=[],matchingRule='same package, JVM name and parameter descriptor; return ignored; private/access helpers excluded'))
save(HERE/'source-audit.json',dict(status='PASS',checks=checks,checkCount=len(checks),preparedOnly=True,
 sessionPlatformAdaptations=['Root aid/count/state callbacks replace VideoPlaybackUiState ownership','Existing caller scope replaces Android viewModelScope; no new ViewModel store','request IDs and owner guards reject cancelled/retired completions','same-request finally releases busy; busy admitted synchronously to avoid queued duplicate mutation','cache invalidation cancels pending old read','all create jobs cancelled on close; atomic closed gate','Original protocols rethrow cancellation instead of converting it to ordinary failure; Android log omitted'],
 originalSaveAlgorithmPreserved=True,RootVideoProjectionStillRequired=True))
save(HERE/'historical-proof01.json',dict(status='NOT_ACCEPTED_PROCESS_TIMEOUT',rawResult='proof-01/scratch/result.json',rawAssertions=31,reason='All assertions completed, but task-only OkHttp executor was not explicitly shut down and JVM did not exit within 60 seconds; original result retained. Fresh02 adds executor shutdown and tests current candidate with 33 assertions.',notUsedForRuntimeAcceptance=True))
save(HERE/'install-contract.json',dict(preparedOnly=True,upstreamCommit=g.COMMIT,originalSourceIdentities=inventory['sources'],
 producer='prepared/desktop/tools/extract-upstream-favorite-folder-sheet.py',producerEntry='generate(repo, output, standalone=False)',
 productionSelectedSourceCount=4,productionDirectReferences=production['requiredDirectReferences'],manualPlatformSourceCount=2,totalClosureSources=9,
 manualPlatformPayloads=[str(p.relative_to(HERE)).replace('\\','/') for p in (prepared/'desktop/src/main/kotlin').rglob('*.kt')],
 requiredExistingMain=['FavFolder/BilibiliApi and serialized models','AppModalBottomSheet, AppAlertDialog and original App primitives','Root same Repository/client and account epoch','Same global DesktopPluginStore.settings'],
 requiredParentEnvironment='DesktopFavoriteEnvironment(api,currentMid(),csrf(),assertOwned()) and actions.createFavFolder; no duplicate Favorites Repository/Actions/DTO',
 protocolConstructor='DesktopOriginalFavoriteFolderProtocol(api, readMid:()->Long?, readCsrf:()->String?, assertOwned:()->Unit)',
 membershipRead='getFavoriteFolders(aid) -> GET created/list-all up_mid=ownedMid,type=2,rid=aid,web_location default 333.1387',
 membershipWrite='updateFavoriteFolders(aid,add:Set<Long>,remove:Set<Long>) -> single dealFavorite rid=aid,type=2,sorted comma add/del,csrf; no empty POST',
 create='Inject parent actions::createFavFolder, preserve original title/intro/privacy; do not call old DesktopSocialRepository.createFavoriteFolder (intro missing)',
 callerOwner='Instantiate session/environment per immutable aid + captured sessionEpoch + page lifetime; stillOwned checks all three, cancel caller child scope and session.close() on disposal. Same MID login replacement is a distinct owner.',
 measuredViewport='Provide LocalDesktopFavoriteFolderViewport(DesktopFavoriteFolderViewport(actual host heightDp)) above modal; original max(72% height,360dp) retained.',
 originalStateFlows=['favoriteFolderDialogVisible','favoriteFolders','isFavoriteFoldersLoading','favoriteSelectedFolderIds','isSavingFavoriteFolders','favoriteFolderSaveEvent'],
 RootProjection='onFavoriteLoaded(bool) / onFavoriteSaved(bool,count) update same current Root video relation and raw favorite count only while owned. currentFavoriteCount reads that authority. Do not feed library.isFavorite local bookmarks into cloud membership.',
 originalSettings=dict(key='favorite_quick_save_default_folder',default=False,source=g.PATHS[6],notProduced=True,globalStoreOnly=True),
 entryPolicy='Original VideoFavoriteActionPolicy: long press and AudioMode always drawer; other taps quick=true default-folder toggle, otherwise drawer. Root supplies actual quick toggle callback; this payload does not claim that entry mounted.',
 oldConsumer=dict(path='desktop/src/main/kotlin/com/bilipai/desktop/ui/VideoEngagementPanel.kt',relationAnchor=27,favoriteClickAnchor=55,dialogCallAnchor=129,oldDialogAnchor=147),
 noOpsWholeFileOrShellReplacement=True,noMainEdits=True,noGradle=True,noHTTP=True,noHWND=True,
 proof=dict(phase='proof-02',groupedCases=4,assertions=33,actualCodeSources=5,zeroSocket=True,actualRootUiMounted=False,actualRootVideoProjectionConsumed=False),
 pending=['Root source registration and same-API/owner injection','Root actual VideoEngagementPanel drawer/button/long-press and quick default-folder consumer','Root actual favorite flag/count projection and save event consumption','Whole product compilation and mounted original sheet/CreateFolderDialog interactions']))
assert not (HERE/'frozen-handoff.json').exists()
rows=[dict(path=str(p.relative_to(HERE)).replace('\\','/'),bytes=p.stat().st_size,sha256Bytes=digest(p)) for p in sorted(HERE.rglob('*')) if p.is_file() and '__pycache__' not in p.parts]
save(HERE/'frozen-handoff.json',dict(status='FROZEN_PREPARED',artifactCount=len(rows),sourceClosureCount=9,selectedProduced=4,directReferenced=3,manualPlatform=2,
 actualMain15ManifestSha256Bytes=accepted['actualMain15ManifestSha256Bytes'],actualCp92Sha256Bytes=accepted['actualCp92Sha256Bytes'],
 compileResult='compile-02/compile-result.json',acceptedResult='proof-02/accepted-result.json',groupedCases=4,assertions=33,actualCodeSources=5,
 noClassOrTopLevelCallableOverlap=True,noMainEdits=True,noGradle=True,noHTTP=True,noHWND=True,notRootUiEndToEndAcceptance=True,artifacts=rows))
print(json.dumps(dict(manifest=str(HERE/'frozen-handoff.json'),sha256Bytes=digest(HERE/'frozen-handoff.json'),artifacts=len(rows),sourceChecks=len(checks),candidateJarSha256Bytes=digest(candidate))))
