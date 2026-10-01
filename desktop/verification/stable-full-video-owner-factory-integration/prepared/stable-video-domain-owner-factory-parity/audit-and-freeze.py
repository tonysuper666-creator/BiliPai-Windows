from pathlib import Path
import hashlib,json,subprocess,zipfile
P=Path(__file__).resolve().parent; MAIN=P.parents[2]; CANDIDATE=MAIN.with_name('BiliPai-v023'); SNAP=MAIN/'desktop/.local/stable-product-snapshot-69'
COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def read(p):return wide(p).read_bytes()
def sha(p):return hashlib.sha256(read(p)).hexdigest()
def lf(b):return b.replace(b'\r\n',b'\n')
def save(p,v):
 wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes((json.dumps(v,ensure_ascii=False,indent=2)+'\n').encode())
manifest=json.loads(read(SNAP/'manifest.json'));assert sha(SNAP/'manifest.json')=='4524b0c8b5e4e97bb88223a6a6f71c126bc17586111d50a10fb49f186a3d695d'
registered={r['path'].replace('\\','/'):r['sha256Bytes'] for r in manifest['inputs']}
paths=[
 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoEngagementBindings.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoSupplementEnvironment.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoComposerEnvironment.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalCommentRootBindings.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopVideoCommentRoot.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopDynamicCardOperations.kt',
 'desktop/build/generated/original-video-detail-units/com/android/purebilibili/feature/video/viewmodel/VideoEngagementViewModel.kt',
 'desktop/build/generated/original-video-detail-units/com/android/purebilibili/feature/video/viewmodel/DesktopOriginalVideoCoinBalanceLoader.kt',
 'desktop/build/generated/original-video-state-core/com/android/purebilibili/feature/video/viewmodel/DesktopOriginalVideoSupplementViewModel.kt',
 'desktop/build/generated/original-video-state-core/com/android/purebilibili/feature/video/viewmodel/DesktopOriginalVideoSuccessExtensions.kt',
 'desktop/build/generated/bgm-detail/com/android/purebilibili/feature/video/viewmodel/DesktopVideoCommentRequests.kt',
 'desktop/build/generated/bgm-detail/com/android/purebilibili/feature/video/viewmodel/VideoCommentViewModel.kt',
]
# Snapshot pins manual sources/producers, not every regenerated output. Record
# current generated source independently; actual69 class presence and the final
# factory compile prove ABI use, without inventing a snapshot generated-file pin.
for suffix in ['viewmodel/VideoComposerViewModel.kt','screen/VideoDetailDomainEffects.kt']:
 paths.append('desktop/build/generated/original-video-detail-holder-full/com/android/purebilibili/feature/video/'+suffix)
actual=[]
for rel in paths:
 b=read(CANDIDATE/rel);digest=hashlib.sha256(lf(b)).hexdigest()
 if rel in registered:assert registered[rel]==digest,(rel,registered[rel],digest)
 text=lf(b).decode();markers=['class ','interface ','fun create','fun bindSubject','fun close','fun init(','fun clearForVideoChange','fun originalVideo','fun commit','DesktopOriginalCommentRootBindings','val viewModel = remember']
 actual.append(dict(path=rel,sha256LF=digest,sha256Bytes=hashlib.sha256(b).hexdigest(),bytes=len(b),matchesActual69Input=True if rel in registered else None,
 sourcePinScope='actual69 registered source' if rel in registered else 'current Candidate generated-source read; not individually pinned by snapshot manifest',
 anchors=[dict(line=n,text=line.strip())for n,line in enumerate(text.splitlines(),1)if any(m in line for m in markers)]))
original=[]
for leaf in ['VideoEngagementViewModel.kt','VideoSupplementViewModel.kt','VideoCommentViewModel.kt','VideoComposerViewModel.kt']:
 rel='app/src/main/java/com/android/purebilibili/feature/video/viewmodel/'+leaf
 b=subprocess.check_output(['git','-C',str(CANDIDATE),'show',COMMIT+':'+rel]);b=lf(b)
 dst=P/'reference/original-stable'/Path(rel+'.txt');wide(dst).parent.mkdir(parents=True,exist_ok=True);wide(dst).write_bytes(b)
 original.append(dict(commit=COMMIT,path=rel,sha256LF=hashlib.sha256(b).hexdigest(),lines=len(b.splitlines()),referencePath=str(dst.relative_to(P)),
 anchors=[dict(line=n,text=line.strip())for n,line in enumerate(b.decode().splitlines(),1)if any(m in line for m in ['class ','interface ','EmptyVideoSupplementLoader','fun bindSubject','fun onCleared','fun init(','fun clearForVideoChange'])]))
presence=[]
with zipfile.ZipFile(wide(SNAP/'main-kotlin.jar'))as z:
 for stem in ['com/android/purebilibili/feature/video/viewmodel/VideoEngagementViewModel','com/android/purebilibili/feature/video/viewmodel/VideoSupplementViewModel','com/android/purebilibili/feature/video/viewmodel/VideoCommentViewModel','com/android/purebilibili/feature/video/viewmodel/VideoComposerViewModel','com/bilipai/desktop/ui/DesktopOriginalCommentRootOwner','com/bilipai/desktop/data/DesktopDynamicCardOperations']:
  entry=stem+'.class';b=z.read(entry);presence.append(dict(classEntry=entry,sha256ClassBytes=hashlib.sha256(b).hexdigest(),jar=str(SNAP/'main-kotlin.jar'),jarSHA256Bytes=sha(SNAP/'main-kotlin.jar'),runtimeLoaded=False))
save(P/'source-inventory.json',dict(actualSnapshot=69,actualSnapshotManifestSHA256Bytes=sha(SNAP/'manifest.json'),actualInputs=actual,originalInputs=original,classPresence=presence,productionFilesChanged=[]))
save(P/'install-contract.json',dict(status='SOURCE_READY_COMPILED_NEW_PLATFORM_FACTORY',onlyInstall=['prepared/manual/com/bilipai/desktop/ui/DesktopOriginalVideoDomainOwners.kt'],
 referencesOnly=['actual69 four original VM classes','actual69 three original environments','actual69 DesktopOriginalCommentRootOwner/Requests/Ops','actual69 VideoDetailDomainEffects/Success projections'],
 constructor='DesktopOriginalVideoDomainOwners.create(context, root, capturedEpoch, stillEntryOwned, commitIfEntryCurrent, currentPlaybackState, analytics)',
 properties=['engagement:VideoEngagementViewModel','composer:VideoComposerViewModel','comments:VideoCommentViewModel','supplement:VideoSupplementViewModel','scope:CoroutineScope'],
 requiredRootConsumers=[
  'Construct the existing Common CommentRoot owner at the retained detail-entry boundary above conditional foreground drawing. Keep it alive when video/audio overlays temporarily cover the entry.',
  'Pass real same-global DesktopPluginContext, immutable captured account epoch, live entry predicate and Store -> entry short atomic commit. No I/O/join/native teardown under this commit.',
  'Pass the sole full Playback VM uiState.value getter; no parallel Controller loader or second Playback VM. DomainEffects already binds original subject generations/seeds.',
  'Provide actual interaction analytics capability for all five log methods; no default/empty implementation exists in this packet.',
  'Render the full Holder with these exact four VM instances. Retire the old DesktopVideoCommentRootHost constructor path at this same ordinary detail leaf; it would otherwise create a second CommentVM/composer.',
  'Use the existing Common owner CompositionLocal providers for comments, nine-image picker/stream, fraud records, image gallery/export, clipboard, typed share and dialog host; factory does not replace those providers.',
  'Original Engagement events/folder success, Composer subject, Supplement visible/seeds and original Comment init/route effects remain consumed by full Holder/DomainEffects. Factory does not duplicate them.',
  'Retire the root entry gate before closing domains, then await closeAndJoin outside Store/entry locks. Factory never retires Root/native/global Operations/Store authorities.'
 ],
 supplement='Existing original 300ms deferred job rereads same Success seed only when exact bvid/cid/aid matches. Network/tag/AI/online polling remains in the sole PlaybackVM; no new polling/API/cache.',
 ownership='One child SupervisorJob under existing CommentRoot scope shared by all four original domains. Comment requests are guarded forwarding views; currentMid is read inside required short atomic commit. Cancellation is thrown, never converted to ordinary failure.',
 limits=['Source-only compile, no mounted UI/Root/full-page acceptance','No HTTP/real account/GUI/HWND/native execution','Actual69 class presence and 1-source compiler success are not runtime CodeSource proof','Factory cannot make an unbound Holder/window/analytics/Playback owner capability implemented','No automatic rollback of already admitted remote side effects'],
 registry='New platform factory has no new upstream identity. Preserve existing canonical identities/modes/features; do not produce another VM/model/FQN.'))
assert json.loads(read(P/'runs/02/compile-result.json'))['passed']
rows=[];excluded=[]
for f in wide(P).rglob('*'):
 if not f.is_file():continue
 rel=str(f.relative_to(wide(P))).replace('\\','/')
 if rel=='frozen-handoff.json':continue
 if f.suffix in ['.jar','.class','.pyc'] or '__pycache__'in rel:
  excluded.append(dict(path=rel,reason='compiler binary/cache; not installation or formal raw source evidence'));continue
 rows.append(dict(path=rel,sha256Bytes=sha(f),bytes=f.stat().st_size))
save(P/'frozen-handoff.json',dict(frozen=True,status='SOURCE_READY_COMPILED_ACTUAL69_REFERENCES',actual69ManifestSHA256Bytes=sha(SNAP/'manifest.json'),rawArtifactRows=len(rows),
 artifacts=sorted(rows,key=lambda r:r['path']),excludedArtifacts=sorted(excluded,key=lambda r:r['path']),scope='One new manual same-entry factory only. All original domain/model/request/env declarations referenced from actual69. Historical actual68+Composer prospective compile retained separately; current final compile uses actual69 only.',runtimeAcceptance=False))
for row in rows:assert sha(P/row['path'])==row['sha256Bytes']
print('FROZEN',len(rows),sha(P/'frozen-handoff.json'))
