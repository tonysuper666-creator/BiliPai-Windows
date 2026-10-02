from pathlib import Path
import hashlib,json,sys
sys.stdout.reconfigure(encoding='utf-8');sys.dont_write_bytecode=True
H=Path(__file__).resolve().parent
def wide(p):
    s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
def dump(p,v):wide(p).write_bytes((json.dumps(v,indent=2)+'\n').encode())
assert not wide(H/'frozen-handoff.json').exists()
result=json.loads(read(H/'runs/03/result.json'));assert result['passed'] and result['assertions']==33 and result['productClassOverlap']==[]
hunk=json.loads(read(H/'producer-hunk.json'));assert len(hunk['edits'])==1
source='manual/com/bilipai/desktop/ui/DesktopOriginalAudioLyricsBinding.kt'
dump(H/'install-contract.json',dict(copyWhitelist=[dict(source=source,
    destination='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalAudioLyricsBinding.kt',sha256Bytes=sha(read(H/source)),kind='new')],
    exactProducerHunks='producer-hunk.json',existingProducerFamilies=1,exactHunks=1,
    generatedSourcesNeverInstallationPayload=True,canonicalMusicUiStateRepositoryModelsAndCachePreserved=True,
    originalSelectedSource='app/src/main/java/com/android/purebilibili/feature/audio/viewmodel/MusicViewModel.kt',
    originalSha256LF='d9705a700bbe14722b79d9bb484d70da0afb97ae1c9df393e7a271e4a0173d0c',
    newOriginalIdentities=0,newDependencies=0,newPlayerCacheOrClient=False,
    sourceLeaseConstructor='DesktopOriginalMusicSourceLease(bvid,cid,owns,commitIfCurrent)',
    bindingConstructor='DesktopOriginalAudioLyricsBinding(settings,sameLyricsRepository,actualEntryScope,captureSource)',
    requiredRootCapture='Same original subjectSnapshot object, BV/CID, captured epoch, Assembly and entry lifetime; quality or decoder changes can retain a logical subject.',
    requiredRootAdmission='Only short memory/UI/private query publication; same Store -> entry order. No repository/HTTP/cache IO inside admission.',
    requiredRootClose='Await closeCancelJoin outside all gates. False means jobs still alive; preserve that real failure and do not claim a completed drain.',
    portMethods=6,originalDomainMethodsSelected=5,originalPrivateLoadSelected=True,
    initPlayerPlatformOnly='Root already supplies its existing Repository. No Android MiniPlayer/client/cache is constructed.',
    underlyingBlockingHttpCancellationClaimed=False,wholeMusicViewModelOrAuPlayerEmitted=False,
    completeRootMounted=False,desktopExeReplaced=False))
rows=[];excluded=[]
for p in sorted(wide(H).rglob('*')):
    if not p.is_file():continue
    path=p.relative_to(wide(H)).as_posix();data=p.read_bytes()
    if p.suffix in ['.jar','.class','.pyc']:
        excluded.append(dict(path=path,sha256Bytes=sha(data),bytes=len(data),reason='Rebuildable verification output; not installation payload.'))
    else:rows.append(dict(path=path,sha256Bytes=sha(data),bytes=len(data)))
packet=dict(frozen=True,actualBase=76,upstreamCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',
    artifacts=rows,excludedRebuildableOutputs=excluded,prospectiveOnly=True,completeRootMounted=False,desktopExeReplaced=False)
raw=(json.dumps(packet,indent=2)+'\n').encode();wide(H/'frozen-handoff.json').write_bytes(raw)
print(json.dumps(dict(raw=len(rows),sha256=sha(raw),assertions=33)))
