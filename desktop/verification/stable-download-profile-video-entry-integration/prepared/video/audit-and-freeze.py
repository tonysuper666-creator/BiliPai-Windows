from pathlib import Path
import hashlib,json,re,subprocess
LANE=Path(__file__).resolve().parent;MAIN=LANE.parents[2];CANDIDATE=MAIN.parent/'BiliPai-v023';SNAP=MAIN/'desktop/.local/stable-product-snapshot-45'
def wide(p):
    s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
def save(name,v):
    p=LANE/name;wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
changes=json.loads(read(LANE/'local-hunks.json'));assert len(changes['changes'])==2 and sum(len(r['hunks'])for r in changes['changes'])==10
inverse=[]
for row in changes['changes']:
    old=read(LANE/'source-baselines'/row['path']);new=read(LANE/'proof-only'/row['path']);text=new.decode('utf-8')
    assert sha(old)==row['baseSha256Bytes'] and sha(new)==row['candidateSha256Lf']
    for h in reversed(row['hunks']):assert text.count(h['after'])==1;text=text.replace(h['after'],h['before'],1)
    assert text.encode()==old.replace(b'\r\n',b'\n')
    inverse.append(dict(path=row['path'],hunks=len(row['hunks']),reverseNormalizedByteEqual=True,baseSha256Lf=row['baseSha256Lf'],candidateSha256Lf=row['candidateSha256Lf']))
commit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
paths=['app/src/main/java/com/android/purebilibili/navigation3/BiliPaiNavKey.kt',
 'app/src/main/java/com/android/purebilibili/feature/video/screen/VideoDetailScreenStateHolder.kt',
 'app/src/main/java/com/android/purebilibili/feature/video/viewmodel/VideoCommentViewModel.kt']
origins=[]
for path in paths:
    blob=subprocess.run(['git','show',commit+':'+path],cwd=CANDIDATE,capture_output=True,check=True).stdout
    target=LANE/'original-stable'/path;wide(target).parent.mkdir(parents=True,exist_ok=True);wide(target).write_bytes(blob)
    origins.append(dict(path=path,sha256Bytes=sha(blob),sha256Lf=sha(blob.replace(b'\r\n',b'\n'))))
holder=read(LANE/'original-stable'/paths[1]).decode('utf-8').replace('\r\n','\n')
observerStart=holder.index('    val rootReply = commentState.replies.firstOrNull { it.rpid == openCommentRootRpidFromRoute }')
observerEnd=holder.index('\n    }\n    val commentDefaultSortMode',observerStart)
observer=holder[observerStart:observerEnd].strip()
host=read(LANE/'proof-only'/changes['changes'][1]['path']).decode('utf-8')
candidateStart=host.index('                val rootReply = commentState.replies.firstOrNull { it.rpid == initialCommentRootRpid }')
candidateEnd=host.index('\n            }\n        }\n        LaunchedEffect(owner)',candidateStart)
candidate=host[candidateStart:candidateEnd].strip()
normalize=lambda s:re.sub(r'\s+','',s)
expected=observer.replace('commentViewModel','viewModel').replace('openCommentRootRpidFromRoute','initialCommentRootRpid').replace('openCommentTargetRpidFromRoute','initialCommentTargetRpid')
originalTokens=normalize(expected)
braceFormat='if(openStarted){hasHandledCommentRootFromRoute=true}'
assert originalTokens.count(braceFormat)==1
assert originalTokens.replace(braceFormat,'if(openStarted)hasHandledCommentRootFromRoute=true')==normalize(candidate)
vm=read(LANE/'original-stable'/paths[2]).decode('utf-8').replace('\r\n','\n')
method=vm[vm.index('    fun openSubReplyFromRoute('):vm.index('    fun closeSubReply()',vm.index('    fun openSubReplyFromRoute('))]
manifest=json.loads(read(SNAP/'manifest.json'));inputs={r['path']:r for r in manifest['inputs']}
support=['desktop/src/main/kotlin/com/bilipai/desktop/Main.kt','desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalCommentRootBindings.kt','desktop/src/main/kotlin/com/bilipai/desktop/player/MpvPlayer.kt','desktop/src/main/kotlin/com/bilipai/desktop/player/DesktopPlaybackPublication.kt']
supportRows=[]
for path in support:
    raw=read(CANDIDATE/path);assert sha(raw)==inputs[path]['sha256Bytes'],path
    target=LANE/'source-baselines'/path;wide(target).parent.mkdir(parents=True,exist_ok=True);wide(target).write_bytes(raw)
    supportRows.append(dict(path=path,sha256Bytes=sha(raw),matchesActual45Input=True))
assert 'seekTo(resumePositionMs / 1000.0)'in read(LANE/'proof-only'/changes['changes'][0]['path']).decode('utf-8')
save('source-audit.json',dict(passed=True,originalCommit=commit,originalSources=origins,originalRoutedReplyMethod=dict(sha256Lf=sha(method.encode()),originalStartLine=vm[:vm.index('    fun openSubReplyFromRoute(')].count('\n')+1),originalObserverSha256Lf=sha(observer.encode()),candidateObserverSha256Lf=sha(candidate.encode()),observerSourceEquivalentUnderDeclaredAliasesIndentAndSingleStatementBraceFormat=True,observerBraceNormalization=dict(original=braceFormat,candidate='if(openStarted)hasHandledCommentRootFromRoute=true',occurrences=1),observerAliases={'commentViewModel':'viewModel','openCommentRootRpidFromRoute':'initialCommentRootRpid','openCommentTargetRpidFromRoute':'initialCommentTargetRpid'},exactInverseHunks=inverse,actual45SupportingInputs=supportRows,declaredAdapters=['Structured owned outer guards replace original early return in routed comment effect','Owner identity includes original route root/target and openId; old owner disposal uses the existing common scope, selected-images, assets and exact epoch authority','Original immediate init routed open and state observer fallback remain original VM calls, no simplified REST wrapper','Milliseconds are request-local Double seconds, with existing history fallback/duration policy untouched','Matching route retention requires actual current native snapshot/sourceVersion and same receipt atomic admission; queue/source/pause remain unchanged','Positive retained seek requires real codec presence accepted by existing Mpv.seekTracked; otherwise ordinary route load preserves requested millisecond position. No native seek completion is fabricated']))
result=json.loads(read(LANE/'run-04/result.json'));assert result['passed']and result['assertions']==24 and result['cpToolsSourcesPrePostByteEqual']
save('installation-recipe.json',dict(baseActualSnapshot=45,existingExactHunks='local-hunks.json',sharedFamilies=2,hunks=10,newProductionFiles=0,newUpstreamSourceIdentities=0,wholeProofOnlyFilesMustNotBeInstalled=True,signatures=['fun open(card:VideoCard,resumePositionMs:Long)','fun openVideoDetail(card:VideoCard,resumePositionMs:Long,keepMatchingSource:Boolean):Boolean','DesktopVideoCommentRootHost(...,initialCommentRootRpid:Long=0L,initialCommentTargetRpid:Long=0L,commentRouteOpenId:Long=0L)'],rootContract=['Preserve the immutable original BiliPaiNavKey.VideoDetail, including sourceRoute/openId, in the existing Root stack. VideoCard only carries playback metadata/CID to the existing Controller; it is not a replacement route schema','Root gate must check immutable nav-entry/account/source ownership before calling Controller. The new method does not create a root-route authority','Set keepMatchingSource from actual compatible normal-native route flags, including !startAudio; controller checks exact nonzero preferredCID/BVID, held native source and receipt. Unknown CID zero uses original saved-history fallback','Return true means current source retained. False means ordinary route-open path or closed Controller; it is not a claim that an asynchronous load completed','Root passes key.commentRootRpid/commentTargetRpid/openId to Host and selects original comments tab when root>0','Root owns audio/fullscreen/portrait/return/clock effects. Do not substitute toggles, fake flags, window ratio, or a second player'],fullscreenRequiredPort=dict(currentSource='desktop/src/main/kotlin/com/bilipai/desktop/Main.kt:206-208',getter='isFullscreen:()->Boolean = { windowState.placement == WindowPlacement.Fullscreen }',setter='setFullscreen:(Boolean)->Unit = { enabled -> windowState.placement = if(enabled) WindowPlacement.Fullscreen else WindowPlacement.Floating }',originalExistingToggleRetainedForF11=True,rootConsumer='For a fullscreen entry, call the setter only if actual getter reports false; a false route flag does not fabricate an Android portrait/fullscreen presentation'),scope=result,actualRootMountAndNativeSeekPending=True))
save('result.json',dict(passed=True,phase='prepared-video-detail-admission-on-actual45-runtime',groups=2,assertions=24,existingProductionFamiliesOverridden=2,actualProductAcceptance=False,HTTP=False,NativeDLL=False,HostUIRuntime=False,RootMounted=False,cpPrePostByteEqual=True,acceptedRun='run-04',preservedFailureRuns=['run-01','run-02','run-03'],failures={'run-01':'Production compile: resolved data PlaybackSource was incompatible with native publication transport. Candidate now takes same current native snapshot rather than rebuilding/copying credentials.','run-02':'Fixture incorrectly expected immediate seek position in memory-only Mpv without a native session/ACK. Production also gained codec admission so a positive resume cannot be silently dropped by an unavailable native seek.','run-03':'Fixture incorrectly expected CID zero to reset to first part; unchanged original library fallback selected the saved second part. Run-04 preserves that actual original behavior.'},limitations=['Host compiles and JVM class loads; no actual Host Compose UI mounting was run','Original VM routed request and cancel guards are actual45; test uses explicit memory requests, no socket','Native source/queue/paused/authorization bookkeeping and millisecond load mapping were tested without native DLL. Actual native seek completion/window playback remains pending']))
assert not (LANE/'frozen-handoff.json').exists();rows=[];excluded=[]
for p in sorted(wide(LANE).rglob('*')):
    if not p.is_file():continue
    rel=p.relative_to(wide(LANE)).as_posix()
    if p.suffix in ('.jar','.class','.pyc')or '/private-memory/'in '/'+rel or '__pycache__'in rel:
        excluded.append(dict(path=rel,reason='private temporary memory library or binary',sha256Bytes=sha(p.read_bytes())));continue
    rows.append(dict(path=rel,bytes=p.stat().st_size,sha256Bytes=sha(p.read_bytes())))
save('frozen-handoff.json',dict(frozen=True,phase='source-only-video-detail-final-admission',baseActualSnapshot=45,artifacts=rows,excludedPrivateAndBinary=excluded,installation='installation-recipe.json',proof=dict(groups=2,assertions=24,productionOverrides=2,fixtureClassIntersections=0,actual45RuntimeEntries=97),oldFrozenPacketsUnchanged=['FinalPublication428','Playback84','ProfileAccount56'],limitations=['No actual Root mounting, native DLL, native seek ACK, original portrait renderer, navigation transition, HTTP or account operations','Install exact hunks only, preserving all Root changes outside snippets']))
print(json.dumps(dict(frozenHandoff=str(LANE/'frozen-handoff.json'),sha256Bytes=sha(read(LANE/'frozen-handoff.json')),artifacts=len(rows),excluded=len(excluded),localHunksSha256Bytes=sha(read(LANE/'local-hunks.json')),installationRecipeSha256Bytes=sha(read(LANE/'installation-recipe.json')))))
