from pathlib import Path
import ast,difflib,hashlib,importlib.util,json,re,subprocess,zipfile
HERE=Path(__file__).resolve().parent
REPO=next(p for p in HERE.parents if (p/'.git').exists())
OUT=HERE/'raw-handoff-final'
def safe(p):return Path('\\\\?\\'+str(p.absolute()).removeprefix('\\\\?\\'))
def read(p):return safe(p).read_text(encoding='utf-8')
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,s):
    assert HERE in p.parents
    safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
def relative(p):return Path(str(p).removeprefix('\\\\?\\')).relative_to(HERE).as_posix()
def record(p):return dict(path=relative(p),sha256Bytes=sha(p),bytes=safe(p).stat().st_size)
assert not safe(OUT/'frozen-handoff.json').exists(),'Frozen raw handoff is immutable'
evidence=json.loads(read(HERE/'compile-ui-evidence.json'));assert evidence['activeClasses']=='classes-ui-17'
for item in evidence['sources']:assert sha(Path(item['path']))==item['sha256Bytes'],item['path']
for item in json.loads(read(HERE/'dependency-identities.json')):assert sha(Path(item['path']))==item['sha256Bytes'],item['path']
for name in ['session','lifecycle','image','ui']:assert json.loads(read(HERE/(name+'-result-17.json')))['passed']
assert json.loads(read(HERE/'production-equivalence/evidence.json'))['passed']
assert sha(HERE/'protocol/frozen-manifest.json')=='7823571e997d514383efbf2faf7244b42853f47afd2bcd82149b466c9d8f9d18'
assert sha(HERE/'protocol/detail-prepared/frozen-manifest.json')=='c885806ab7b20245868ccca190812663afd35fcc9803469ee340512c773a33fb'
ops='desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopDynamicCardOperations.kt'
assert sha(REPO/ops)==sha(HERE/'source-baseline/DesktopDynamicCardOperations.kt')=='8130c63b4554ed9970250588144955e6accf04e25223b7218dab7784ec585e53'
prepared=HERE/'prepared'/ops
patch=''.join(difflib.unified_diff(read(HERE/'source-baseline/DesktopDynamicCardOperations.kt').splitlines(True),read(prepared).splitlines(True),fromfile='a/'+ops,tofile='b/'+ops))
write(OUT/'operations.patch',patch)
registry=json.loads(read(REPO/'desktop/upstream-sources.json'));existing={s['path']:s for s in registry['sources']}
sources=set()
for file in [HERE/'prepared/desktop/tools/extract-upstream-dynamic-reply.py',HERE/'prepared/desktop/tools/extract-upstream-dynamic-detail.py']:
    spec=importlib.util.spec_from_file_location(file.stem,file);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    if hasattr(module,'SOURCES'):sources.update(module.SOURCES)
    else:sources.update([module.VM,module.VIDEO_VM,'app/src/main/java/com/android/purebilibili/feature/dynamic/DynamicInteractionPolicy.kt'])
for file in [HERE/'protocol/source-inventory.json',HERE/'protocol/detail-prepared/source-inventory.json']:
    sources.update(s['path'] for s in json.loads(read(file))['sources'])
source_records=[]
for path in sorted(sources):
    body=read(REPO/path).replace('\r\n','\n');digest=hashlib.sha256(body.encode()).hexdigest()
    tag=subprocess.check_output(['git','rev-parse','v0.2.3-alpha.9:'+path],cwd=REPO,text=True).strip()
    current=subprocess.check_output(['git','hash-object','--path='+path,path],cwd=REPO,text=True).strip();assert tag==current,path
    old=existing.get(path)
    if old:assert old['sha256']==digest,path
    source_records.append(dict(path=path,sha256=digest,tagBlob=tag,matchesTag=True,
        mode=old['mode'] if old else 'selected',existingIdentity=old is not None,
        mergeFeatures=['dynamic-detail-reply'],registryAction='mergeExistingFeatures' if old else 'appendSelectedIdentity'))
write(OUT/'original-source-inventory.json',json.dumps(dict(originalTag='v0.2.3-alpha.9',originalCommit='fcf84853b287662e8a9129ea0d38576c36522a34',sources=source_records,
    resourcesAdded=[],runtimeDependenciesAdded=[],directProducerWarning='Every new whole pure policy is registered selected because its renamed generated file is the sole producer; never direct-copy it again.',
    existingEditorAndCardPolicyBodiesReused=True),indent=2)+'\n')
generated=[]
for directory in [HERE/'generated/ui',HERE/'generated/detail',HERE/'production-equivalence/comment/generated',HERE/'production-equivalence/detail/generated']:
    generated.extend(safe(directory).rglob('*.kt'))
with zipfile.ZipFile(Path(evidence['productJars'][1]['path'])) as z:main_names=set(z.namelist())
with zipfile.ZipFile(Path(evidence['productJars'][0]['path'])) as z:main_names.update(z.namelist())
class_root=safe(HERE/'classes-ui-17');classes=list(class_root.rglob('*.class'))
overlap=sorted(p.relative_to(class_root).as_posix() for p in classes if p.relative_to(class_root).as_posix() in main_names)
unexpected=[p for p in overlap if not p.startswith('com/bilipai/desktop/data/DesktopDynamicCardOperations')]
assert not unexpected,unexpected
write(OUT/'producer-manifest.json',json.dumps(dict(generatedCount=len(generated),generated=[record(p) for p in sorted(generated)],
    explicitProductOverride='com.bilipai.desktop.data.DesktopDynamicCardOperations',actualMainOverlapClassEntries=overlap,
    unexpectedClassOverlap=[],newAPIModelStoreCache=False,
    generators=[dict(path='desktop/tools/'+p.name,source=record(p)) for p in sorted((HERE/'prepared/desktop/tools').glob('*.py'))],
    tasks=[dict(tool='extract-upstream-dynamic-reply.py',kotlinSourceDir='<taskOutput>',owns=16),
      dict(tool='extract-upstream-dynamic-detail.py',kotlinSourceDir='<taskOutput>',owns=3),
      dict(tool='extract-upstream-dynamic-reply-protocol.py',kotlinSourceDir='<taskOutput>/generated',owns=3,fragmentOutsideSourceDir=True),
      dict(tool='extract-upstream-dynamic-detail-protocol.py',kotlinSourceDir='<taskOutput>/generated',owns=3,fragmentOutsideSourceDir=True)]),indent=2)+'\n')
payloads=[]
for p in sorted((HERE/'prepared/desktop/tools').glob('*.py')):payloads.append(dict(target='desktop/tools/'+p.name,source=record(p),operation='newFile'))
for p in safe(HERE/'prepared/desktop/src').rglob('*.kt'):
    if p.name=='DesktopDynamicCardOperations.kt':continue
    target=Path(str(p).removeprefix('\\\\?\\')).relative_to(HERE/'prepared').as_posix()
    payloads.append(dict(target=target,source=record(p),operation='newFile'))
payloads.append(dict(target=ops,baseSha256Bytes=sha(HERE/'source-baseline/DesktopDynamicCardOperations.kt'),desiredSha256Bytes=sha(prepared),operation='appendMemberFragmentsOnly',
    patch=record(OUT/'operations.patch'),compiledDesired=record(prepared),retainOriginalEditor=True))
write(OUT/'installation-payloads.json',json.dumps(dict(payloads=payloads,RootOwnsShellConsumerGradleRegistry=True,noWholeRootConsumerOverwrite=True),indent=2)+'\n')
write(OUT/'ROOT-INTEGRATION.txt','''Raw original DynamicDetail/Reply closure — staged integration, not whole detail acceptance

Base actual Main: dynamic-editor-main-product-snapshot-01 bc6cf9ef / ordered89CP515bf598.
Main Kotlin47d866dc. Current Ops8130c63b is byte-identical to this base; Root navigation
44b9f672 changes no Ops/Reply sources. Final attempt17 uses only this Ops as product
override; all editor/card/policies/models/APIs/shared controls come from actual Main.

Install 4 new generators + 4 new thin platform files; append operations.patch members
to unique existing Ops. Do not replace Community/Shell/editor verifier/global source
manifest/build. Producer task source dirs/counts are in producer-manifest.json; 25
generated Kotlin files, no new dependency or asset. Merge source identities once.
CommentReadAccessPolicy and DynamicDetailFallbackPolicy generated full pure bodies
are selected identities, never also copied as direct sources. Existing editor policy
functions and all raw Reply models are reused. Compile class overlap is exclusively Ops.

Verify editor body boundary before appending: its verifier currently spans editor
marker to class tail. End that span at the comment fragment's unique first line
"// Paste inside existing DesktopDynamicCardOperations". The next unique detail
fragment starts "// Additional members inside the existing DesktopDynamicCardOperations".
Root may wrap each unchanged fragment in independent begin/end comments. No original
editor API/forEditor/auth published-check/upload body may be lost or regenerated twice.

Required route owner setup (same Root repository/session/context; no new account/cache):
  capture immutable epoch = collected accountEpoch; item id key; alive=AtomicBoolean.
  reuse LocalDesktopDynamicCardSession and its .emotes; require matches(repo,epoch).
  operations=DesktopDynamicCardOperations(repo,epoch,{alive.get() && cardSession.isOwned()},cardSession.emotes).
  binding=DesktopDynamicReplyOperationsBinding(operations,
      csrfAvailable={ repo.authCookies()["bili_jct"].orEmpty().isNotBlank() },
      detailLoader={ id -> operations.getDynamicDetail(id, latestCurrentRawItem) }).
  replySession=DesktopOriginalDynamicReplySession(id,parentScope,binding,
      seedItem={latestCurrentRawItem},stillOwned={alive.get() && cardSession.isOwned()}).
  DisposableEffect: alive=false BEFORE close replySession; owns predicate also depends
  on actual repository sessionEpoch. Preserve Root like/repost/delete/unfold version
  counters and mergeDesktopDynamicDetailReadback for pending detail reads; never
  propagate a whole stale detail item into the Root registry/cache.

Ops full getDynamicDetail(id,seed,onArticleViewed?) implements original web/opus/
desktop/rid-type2/article fallback and interaction/media merge. Seed must be current
existing original raw item. Replace web-only community.dynamicDetail+ad hoc opus
sequence with this read and merge DynamicDetailData(item=result) through existing
version guard. Optional existing DesktopArticleHistoryReporter callback may be bound
with captured epoch; null explicitly leaves original article-history side effect unbound.
Inherited plain WORD ranking ties and repeated placeholder desktop read are preserved.

Mount LocalDesktopCommentBindings with ONE DesktopPluginContext from current global
prefs.context; collapsed limit is actual DesktopOriginalReplySettings sync value,
loaded-count flow is its same settings backing. emotes=cardSession.emotes. Feedback,
clipboard and native SHARE are explicit Root user actions; this proof only records
synthetic callbacks. videoTitle=existing repo.videoDetails(bvid).title under owner.
translateReply=operations.translateReply; blockUser=community.blockedUps original
COMMENT relation same store; it is not a fake false/no-op. saveCommentImage must first
select a real user target, then writeDesktopReplyCommentImage(spec,path,owned).
This produces PNG; Windows gallery/native picker/SHARE has not been tested here.
The preserved original save toast says 相册; Root may give truthful Windows feedback
at its platform message boundary rather than claiming a Photos gallery write.

@Composable DesktopOriginalDynamicCommentPanel(item,session,currentMid:Long?,onUserClick,modifier)
requires finite height. It uses exact original LazyListScope commentContent + header,
original raw row/actions/image preview + original bottom input/composer. Open comment
session once for current raw subject; do not reload on each stat mutation/recomposition.
@Composable DesktopOriginalDynamicThreadContent(session,currentMid:Long,onUserClick,
onImagePreview?,modifier) is complete original thread CONTENT; observe .subReplyState
visible and mount it in a real finite dialog. It handles original counts/cursor/sort/
target highlighting/prefetch/loading, full raw decorations and comment actions.
Main raw list callbacks preserve root,parent/rpid and original ReportReason codes.
Thread window drag/predictive back/scrim/underlying blur remain NEXT original container
closure, not a tested AppModalBottomSheet replacement. Do not call content-only full parity.

Session owner fixes retain source reducers and API bodies: foreign id guard before
state writes; actual parent Job active; main-generation refresh retires old thread;
same-rpid like/hate synchronous admission+unique completion holder prevents stale
rollback and queued disposal lock leak; all post-await owner/epoch checks. UI platform
ordinary failure is contained, cancellation rethrown, translation finally releases row.

Proof: final attempt17 compiled; Session8 groups + lifecycle5 deterministic groups +
PNG/QR/max14line/global-store/cancellation actual files + M3/Miuix21 pointer pairs each,
including translation/save adapter failure then retry, raw actions, thread actual
TIME2/HOT3 request and original IME/root-parent post fields. No socket/account mutation,
native clipboard/SHARE/HWND/sharedGradle/Main installation was exercised in parent.
Historical child protocol175 proof12 and detail194 proof6 include actual Retrofit Java
Proxy/raw gRPC fixtures; their freeze manifests remain unchanged. Protocol175's
historical Ops8ce5 input is resolved by additive historical-source-retention, not by
pretending today's parent Ops still has that byte hash.

Remaining full Detail scope: original ImmersiveScaffold/Adaptive top chrome+readiness,
AppSplitLayout/fold host and separate detail/comment scroll, full liquid dock,
CommentThreadDrag+actual dialog/back dispatcher/scrim/predictive blur, Android GL
particles (only exact original failed-capture collapse is adapted now), full conversation
gRPC composer host, comment anti-fraud Room record callback (unbound optional seam),
Windows gallery/picker/SHARE/clipboard owner integration and native acceptance.
No feature count or passing source/isolated tests imply these remaining parts work.
''')
paths=set()
for root in [HERE/'prepared',HERE/'generated',HERE/'classes-ui-17',OUT,HERE/'production-equivalence']:
    paths.update(p for p in safe(root).rglob('*') if p.is_file() and p.suffix!='.pyc')
for pattern in ['*Fixture.kt','*-result-17.json','*-run-17.log','*.png','*-sort-diagnostic.txt']:
    paths.update(safe(HERE).glob(pattern))
for name in ['compile-ui-17.log','compiler-ui-17.args','compile-ui-evidence.json','dependency-identities.json','baseline.json','prepare.py','compile-ui.py','run-fixtures.py','prepare-production-protocol.py','freeze-raw-handoff.py']:
    paths.add(safe(HERE/name))
for name in ['protocol/frozen-manifest.json','protocol/source-inventory.json','protocol/historical-source-retention/retention.json','protocol/historical-source-retention/DesktopDynamicCardOperations.kt','protocol/detail-prepared/frozen-manifest.json','protocol/detail-prepared/source-inventory.json','protocol/detail-prepared/detail-contract.txt','protocol/detail-chrome-thread-review/review.txt','source-baseline/DesktopDynamicCardOperations.kt']:
    paths.add(safe(HERE/name))
artifacts=[record(p) for p in sorted(paths)]
manifest=dict(formatVersion=1,scope='Prepared raw Reply/session/content/full detail protocol closure; original Chrome/thread/native integration still pending',
    actualMainSnapshotSha256Bytes='bc6cf9ef064ba68f28f36c3bbf2b59721629405567c7ced7e4799a844ff7b062',
    actualOrdered89CpSha256Bytes='515bf598f56b27de45aa04d63493e1f8ad38b7e4710ef358fea97b9fc9d620c1',
    compiledAttempt=17,explicitProductOverride=['com.bilipai.desktop.data.DesktopDynamicCardOperations'],
    artifactCount=len(artifacts),artifacts=artifacts,sourceCount=len(source_records),newSources=sum(not s['existingIdentity'] for s in source_records),
    MainIntegration=False,sharedGradle=False,HTTP=False,HWND=False,realAccount=False,
    actualNativeClipboardOrSHARE=False,fullDetailParity=False,
    childProtocolHistoricalFreezeSha256Bytes='7823571e997d514383efbf2faf7244b42853f47afd2bcd82149b466c9d8f9d18',
    childDetailFreezeSha256Bytes='c885806ab7b20245868ccca190812663afd35fcc9803469ee340512c773a33fb')
write(OUT/'frozen-handoff.json',json.dumps(manifest,indent=2)+'\n')
for entry in artifacts:assert sha(HERE/entry['path'])==entry['sha256Bytes']
print(json.dumps(dict(passed=True,artifacts=len(artifacts),sources=len(source_records),generated=len(generated),payloads=len(payloads),sha256Bytes=sha(OUT/'frozen-handoff.json')),indent=2))
