from pathlib import Path
import hashlib,json,os
P=Path(__file__).resolve().parent
def wide(p):
 s=str(p);return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+os.path.abspath(p))
def sha(p):return hashlib.sha256(wide(p).read_bytes()).hexdigest()
def write(p,t):wide(p).write_text(json.dumps(t,ensure_ascii=False,indent=2)+'\n'if not isinstance(t,str)else t,encoding='utf8',newline='\n')
assert json.loads(wide(P/'runs/04/result.json').read_text())['passed']
result=json.loads(wide(P/'native-runs/04/result.json').read_text());assert result['passed']and len(result['checks'])==11
write(P/'ROOT-INTEGRATION.md','''Install AFTER unchanged Story89 and its 5-hunk review delta. Apply only the 3 anchors in exact-hunks.json (two existing families); copyWhitelist is empty. No Gradle/registry/resource/actor change.

capturePageRequest preserves original beginDesktopPortraitLoad/flush/save first. It then calls the actual clearCapturedPagePlayback(expectedToken, callerJob) in the existing Assembly.environment Store -> entry admission. This checks the exact Session token and true original caller cancellation BEFORE reading/stopping the accepted source. Existing NativeOwner.admitPlaybackDispatch takes the actual Mpv snapshot lock. stopIfSourceVersion -> stop synchronously retires requestedSource/sourceVersion and queues Stop; only then does capturePlaybackRequest capture the new baseline. Pager no longer stops after capture. The subsequent real native Load is queued on that SAME FIFO actor; no awaited fictitious stop ACK/new player/session is introduced.

currentCid on adoption uses the original setter playbackSessionStore.updateCurrentMedia(currentBvid,cid); beginLoadRequest intentionally only publishes currentRequest/currentBvid. No fake resolved CID is added.

compile04 PASS 33 explicit source inputs against immutable actual83/101. Native04 used the real DesktopApp/Root/Assembly/Portrait Binding/Session/Section/MPV/Canvas with explicitly declared frozen Story89/review/delta overrides. It proved native local first frame, version retirement BEFORE capture, actual Stop then replacement ACK/first frame, cancelled-before-clear, replaced-token-before-clear, later-native-publication refusal, cancelled publish refusal, one actual Canvas and Root close/drain (11 checks). It never seeds original Success or proxies a Bilibili API; raw adoption/ordinary HTTP/Story UI remain NOT accepted by this test.

Root image native delta is separately confirmed: fresh DLL22b363...dd7d3. The fixture explicitly compiles the reviewed generated hash text e95b3bc4...14a9 and the exact image-before ReadyRoot caller (its inlined trusted constant), rather than relaxing validation/relabeling actual83. All 101 immutable JVM CP pins remain unchanged, loaded bytes checked. Source/runtime overrides are explicit prospective inputs, never installable binaries.

Failure history retained: native01 refused obsolete DLL pin before run; native02 bundled ffmpeg lacks libx264 (fixture changed to bundled mpeg4); native03 first native frame passed then fixture incorrectly equated pending requestedCID with resolved currentCid; native04 corrected that assertion to the original currentRequest.cid and passed. compile03 current ReadyRoot source had required rootPublished ABI absent in83; compile04 uses fixed original83/image-before caller only for pin inlining. Earlier runs are historical and not accepted graphs.
''')
write(P/'install-contract.json',dict(task='story-native-baseline',copyWhitelist=[],exactHunks='exact-hunks.json',hunkCount=3,existingFamilies=2,targets=json.loads(wide(P/'targets.json').read_text()),prerequisites=['unchanged Story89','unchanged Story89 review5'],newRegistryIdentities=0,sourceBody='sole Pager replay only removes the postcapture Stop; original call/body selection otherwise unchanged',nativeEvidence='native-runs/04/result.json',actualRootMountAccepted=False,ordinaryDetailSuccessAccepted=False,productionWrites=0))
files=[]
for f in sorted(wide(P).rglob('*')):
 if not f.is_file():continue
 rel=f.relative_to(wide(P)).as_posix()
 if rel=='frozen-handoff.json' or '__pycache__'in rel or '/classes/'in rel or f.suffix in('.jar','.class','.mp4','.png','.dll'):continue
 if '/runtime/'in rel and not rel.endswith('/root-media-proof.json'):continue
 files.append(dict(path=rel,sha256Bytes=sha(f),bytes=f.stat().st_size))
write(P/'frozen-handoff.json',dict(task='story-native-baseline-delta',rawCount=len(files),files=files,copyWhitelist=[],exactHunks='exact-hunks.json',compile04=True,native04Checks=11,productionWrites=0))
print(len(files),'raw manifest',sha(P/'frozen-handoff.json'));print('contract',sha(P/'install-contract.json'))
