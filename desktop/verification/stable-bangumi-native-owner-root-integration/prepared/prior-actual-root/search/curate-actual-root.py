from pathlib import Path
import hashlib, json, subprocess
P=Path(__file__).resolve().parent
Q=P.parent/'stable-message-root-integration-acceptance'
M=P.parents[2]
C=M.parent/'BiliPai-v023'
def wide(p):
    value=str(p)
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+str(Path(p).absolute()))
def sha(p):return hashlib.sha256(wide(p).read_bytes()).hexdigest()
def load(p):return json.loads(wide(p).read_text(encoding='utf-8-sig'))
def write(p,value):wide(p).write_text(value if isinstance(value,str) else json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf8',newline='\n')
assert load(P/'runs/actual87-07/acceptance-result.json')['actualRootSearchAccepted']
assert load(Q/'runs/actual88-01/acceptance-result.json')['actualRootMessageGuestAccepted']
assert load(P/'final-owned-process-check.json')['remainingOwnedFixtureJvmCount']==0
for lane,attempt,fixture in [(P,'actual87-07','RootSearchFixture.kt'),(Q,'actual88-01','RootMessageFixture.kt')]:
    pins=load(lane/'runs'/attempt/'pins-before.json')
    assert sha(lane/fixture)==pins['sourceSHA']
    assert sha(lane/'run.py')==pins['runnerSHA']
    assert load(lane/'runs'/attempt/'pins-after.json')==pins
    for row in pins['runtime']:assert sha(row['path'])==row['sha256Bytes']
version=load(C/'desktop/upstream-sources.json')['windowsRevision']
head=subprocess.run(['git','rev-parse','HEAD'],cwd=C,capture_output=True,check=True).stdout.decode().strip()
review={'codeHEADObserved':head,'windowsRevision':version,'packageVersionNotBumpedByFixture':True,'buildAndDeployNotRun':True,'readOnlySourcePins':{name:sha(C/name) for name in ['desktop/tools/build.ps1','desktop/build.gradle.kts','desktop/upstream-sources.json','app/build.gradle.kts']},'nativePins':{name:sha(C/'desktop/resources/common/native/windows-x64'/name) for name in ['libmpv-2.dll','bilipai-diagnostic-share.dll']},'actualSearchSnapshot':87,'actualMessageSnapshot':88,'sameVersionOldDesktopPackageExists':True,'uniqueDesktopDirectoryRequired':True,'userDataReadOrChanged':False}
write(P/'delivery-readonly-final-versions.json',review)
write(P/'ACTUAL-ROOT-READOUT.md',r'''Actual Search Root acceptance is PASS on immutable snapshot87 / source01e5d615fa48da8fc73c470ea452f885f51af454. The acceptance runner checks 101 ordered classpath byte pins, the two current native DLL pins before/after, fixture class overlaps=0, eight loaded product classes' exact actual87 JAR origins and class bytes, asynchronous composition failures, timeout, proof status and child exit. It is not based on child exit alone.

The owned offscreen DesktopApp/Window mounted the production Root. Its original full Search VM received 24 actual owned Canvas keyboard events and 30 pointer events. 23 assertions passed: input/Enter/history through the sole Community preferences owner; original UP tab and fans sort dropdown; Settings cover/back retained the same VM/query/filter; typed keyword and popped-entry late-write refusal; original clear returned to landing, then original full-list callback mounted SearchTrending; real isolated guest logout invalidated old Search/Trending/Window owners; fresh VM read the same history; actual shutdown retired old and fresh owners.

Current primary receipt is `runs/actual87-07/acceptance-result.json`; raw checks, class origins and pointer traces are `runs/actual87-07/runtime-evidence/root-search-proof.json`. `curated-evidence-index.json` lists exact recommended raw files/SHA/bytes. It excludes fixture/product JARs, generated class trees and all plugin/session/library/native extraction/cache trees. Only source, commands, pins, logs, proof and two selected owned-window screenshots are listed.

The successful fixture corrected its own input selection: Navigation3 retains departing semantics during transitions. It waits for real transition settlement, selects the active latest field, then divides real pixel bounds by the Skia layer's actual contentScale to deliver logical Canvas coordinates. Each trace records the actual owned Canvas identity and the window's owned native child windows; those child lists were empty for this popup. No Semantics SetText/OnClick action, VM setter, slot mutation, Robot, OS input or unrelated window was used. This is no production input fix.

Failure history remains intact. actual86-01 failed before Root publication because Comment bindings read the image-share Local outside the existing gallery provider; Root applied one exact provider hunk and actual87 includes it. Its secondary failed-composition close error `Original progress writer has not drained` remains recorded; later normal-close acceptance is not a separate proof that every failed-composition cleanup path is fixed. actual86-02 was compile-only. actual87-01 used a deep isolated native/ICU path and timed out. actual87-02 used a short owned temp path but hung in actual Direct3D device creation; its live readonly jstack and timeout remain. actual87-03 mounted/input/history passed five checks, UP pointer failed and then artifact collection failed due a duplicated extended-path prefix; a new derived byte audit preserves the failed UI result. actual87-04 had a failed field hit after applying scale to a retained MainHost field. actual87-05 passed the five input/history checks but used wrong tab coordinates. actual87-06 passed sixteen checks including UP sort and retained typed navigation, then clicked a departing clear node. None of those failed attempts was rewritten as PASS.

Message Root acceptance is separately PASS on immutable snapshot88 / source464f5573331dfc7f7c2f1e47a5bf6bbac8cfd201, with its own 105 ordered runtime pins. The sibling `stable-message-root-integration-acceptance` contains its fixture and receipts; no production overlay from Search or Message preparation is loaded. 57 guest assertions passed for Inbox, ReplyMe, AtMe, LikeMe, SystemNotice and typed Chat: real guest UI, owned Canvas login button -> typed Login -> back, same retained Root while covered, all six actual route entries retained, actual pops removed them, no guest authenticated owners/VMs, real isolated guest logout epoch refused old route/Store callbacks, fresh same-repository guest Root, and actual old/fresh owner shutdown. Thirty pointer events were confined to its owned Canvas. Eleven class origin/byte rows match actual88. Authenticated message screen classes were byte-checked, not authenticated-body runtime accepted.

Both successful fixture invocations deliberately use official `-Dskiko.renderApi=SOFTWARE`, with fresh owned LOCALAPPDATA/APPDATA/USERPROFILE/java user.home/java.io.tmpdir and no existing cookies. Their original proxy settings share the same existing plugin store/client and send requests only to an owned deny proxy which forwards nothing. No successful business API is faked. Real account, hot-keyword business navigation, successful network results, authenticated message paging/content/send/read state, default Direct3D/GPU startup, long native paths, native ordinary playback and new EXE deployment remain false. Final exact class-and-lane process inventory found zero remaining owned fixture JVMs; no user process was stopped.

The existing portable delivery command/outputs, package JAR byte audit and safe whole-payload extraction procedure are in `DELIVERY-READINESS-CURRENT.md`. Its historical source readout is529a; `delivery-readonly-final-versions.json` pins the latest read-only observed build/config bytes and real revision. These fixtures did not build/deploy or bump it. The real revision must distinguish a newly released package from the already deployed source6fd `0.2.415.1`; a directory suffix alone does not change the updater version. Preserve old desktop folders and original data. Final packaging belongs to Root's final clean commit, uses existing build.ps1/native/mux/updater smokes in fresh attempts, and does not treat package smoke as full parity or account playback acceptance.
''')
write(Q/'ACTUAL-ROOT-READOUT.md','Actual88 owned Root guest-only message acceptance passed57 assertions. Read `runs/actual88-01/acceptance-result.json` and `runtime-evidence/root-message-proof.json`. The sibling Search lane ACTUAL-ROOT-READOUT.md records the joint scope and failure history. Authentication/business content, real account, default Direct3D/native ordinary playback and deployment remain unaccepted. No user data/process or Candidate source was modified.\n')
def index(lane,paths,status):
    rows=[]
    for rel in sorted(set(paths)):
        path=lane/rel
        assert wide(path).is_file(),str(path)
        assert path.suffix.lower()!='.jar' and 'classes' not in Path(rel).parts and 'fixture-owned-runtime' not in Path(rel).parts
        rows.append({'relativePath':rel,'path':str(path),'sha256Bytes':sha(path),'bytes':wide(path).stat().st_size})
    data={'status':status,'rootMounted':True,'productionOverlays':0,'userData':False,'binaryJarsOrGeneratedTreesIncluded':False,'rawCount':len(rows),'rawBytes':sum(row['bytes'] for row in rows),'rows':rows}
    write(lane/'curated-evidence-index.json',data)
    return data
def attempt_files(attempt):
    return [f'runs/{attempt}/{name}' for name in ['RootSearchFixture.kt','compiler.args','compile.log','compile-result.json','fixture-jar-pin.json','pins-before.json','pins-after.json','runtime-command.json','runtime-owned-path.json','runtime.log','acceptance-result.json'] if wide(P/'runs'/attempt/name).is_file()]
search=['ACTUAL-ROOT-READOUT.md','RootSearchFixture.kt','run.py','preparation-provenance.json','actual86-first-failure.json','actual87-03-derived-byte-audit.json','final-owned-process-check.json','post-timeout-owned-process-check.json','DELIVERY-READINESS-CURRENT.md','readonly-delivery-provenance.json','delivery-readonly-final-versions.json','curate-actual-root.py']
for attempt in ['actual86-01','actual86-02','actual87-01','actual87-02','actual87-03','actual87-04','actual87-05','actual87-06','actual87-07']:search+=attempt_files(attempt)
for attempt in ['actual87-03','actual87-04','actual87-05','actual87-06','actual87-07']:
    rel=f'runs/{attempt}/runtime-evidence/root-search-proof.json'
    if wide(P/rel).is_file():search.append(rel)
search+=['runs/actual86-01/fixture-owned-runtime/root-search-proof.json','runs/actual87-02/owned-child-live-jstack.txt','runs/actual87-07/runtime-evidence/actual-root-search-up-filters.png','runs/actual87-07/runtime-evidence/actual-root-search-trending.png']
# The failed composition proof is the only explicitly selected file from the owned runtime.
# Do not enumerate or copy any of its adjacent account/cache/native files.
selected86=search.pop(search.index('runs/actual86-01/fixture-owned-runtime/root-search-proof.json'))
index86=load(P/'actual86-first-failure.json')
write(P/'actual86-selected-failed-proof-copy.json',load(P/selected86))
search.append('actual86-selected-failed-proof-copy.json')
a=index(P,search,'PASS_ACTUAL_ROOT_SEARCH_UI_SCOPE')
messages=['ACTUAL-ROOT-READOUT.md','RootMessageFixture.kt','run.py','preparation-provenance.json']+[f'runs/actual88-01/{name}' for name in ['RootMessageFixture.kt','compiler.args','compile.log','compile-result.json','fixture-jar-pin.json','pins-before.json','pins-after.json','runtime-command.json','runtime-owned-path.json','runtime.log','acceptance-result.json','runtime-evidence/root-message-proof.json','runtime-evidence/actual-root-message-guest-0.png','runtime-evidence/actual-root-message-final.png']]
b=index(Q,messages,'PASS_ACTUAL_ROOT_MESSAGE_GUEST_ONLY_SCOPE')
joint={'searchIndex':str(P/'curated-evidence-index.json'),'searchIndexSHA':sha(P/'curated-evidence-index.json'),'messageIndex':str(Q/'curated-evidence-index.json'),'messageIndexSHA':sha(Q/'curated-evidence-index.json'),'searchRawCount':a['rawCount'],'messageRawCount':b['rawCount'],'searchAssertions':23,'messageGuestAssertions':57,'actualSearchSnapshot':87,'actualMessageSnapshot':88,'sourceOverlays':0,'rootMountAccepted':True,'accountAuthenticatedContent':False,'realBusinessNetwork':False,'defaultDirect3DStartup':False,'longNativePath':False,'nativeOrdinaryVideoPlayback':False,'newEXEDeployed':False,'candidateProductEdited':False,'userDataChanged':False}
write(P/'curated-handoff.json',joint)
print(json.dumps(joint,indent=2))
