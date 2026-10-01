from pathlib import Path
import hashlib, json, subprocess

LANE=Path(__file__).resolve().parent
MAIN=LANE.parents[2]
REPO=MAIN.parent/'BiliPai-v023'
COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'

def safe(p):
    s=str(Path(p).absolute()); prefix=chr(92)*2+'?'+chr(92)
    return Path(s if s.startswith(prefix) else prefix+s)
def sha(p): return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def textsha(s): return hashlib.sha256(s.encode('utf-8')).hexdigest()
def read(p): return safe(p).read_text(encoding='utf-8')
def load(p): return json.loads(read(p))
def emit(p,value):
    safe(LANE/p).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
def row(p): return {'path':p.as_posix(),'sha256Bytes':sha(LANE/p)}

assert not (LANE/'frozen-handoff.json').exists()
inventory=load(LANE/'source-inventory.json')
for r in inventory:
    blob=subprocess.run(['git','-C',str(REPO),'show',COMMIT+':'+r['path']],capture_output=True,check=True).stdout
    assert hashlib.sha256(blob).hexdigest()==r['gitBlobSHA256']
    assert sha(LANE/'original-stable'/r['path'])==r['gitBlobSHA256']
receipt=load(LANE/'generation-audit-final/source-receipt.json')
assert receipt['fixedCommit']==COMMIT and receipt['completeOriginalUiLines']==1225
assert receipt['originalFourDeclarations']==['GestureMode','OfflineVideoPlayerScreen','ProgressInfo','OfflineProgressBar']
assert len(receipt['exactUiDelta'])==60
generation=[]
for p in sorted((LANE/'prepared/generated').rglob('*.kt')):
    rel=p.relative_to(LANE/'prepared/generated')
    assert sha(p)==sha(LANE/'generation-audit-final'/rel)
    generation.append({'path':rel.as_posix(),'sha256Bytes':sha(p)})
assert len(generation)==4
raw=read(LANE/'original-stable'/inventory[0]['path'])
adapted=read(LANE/'prepared/generated/com/android/purebilibili/feature/download/OfflineVideoPlayerScreen.kt')
ui=load(LANE/'ui-platform-delta.json'); assert ui==receipt['exactUiDelta']
replay=raw
for c in ui:
    i=c['index']; assert replay[i:i+len(c['before'])]==c['before']
    replay=replay[:i]+c['after']+replay[i+len(c['before']):]
assert replay==adapted
reverse=adapted
for c in reversed(ui):
    i=c['index']; assert reverse[i:i+len(c['after'])]==c['after']
    reverse=reverse[:i]+c['before']+reverse[i+len(c['after']):]
assert reverse==raw

deltas=[]
for filename,directory,expected in [('bridge-delta.json','bridge',5),('overlay-delta.json','overlay',16)]:
    d=load(LANE/filename); assert len(d['hunks'])==expected
    candidate=read(LANE/'compile-inputs'/directory/d['path'])
    assert textsha(candidate)==d['candidateLfSha256']
    reverse=candidate
    for h in reversed(d['hunks']):
        assert reverse.count(h['after'])==1,h['label']
        reverse=reverse.replace(h['after'],h['before'])
    assert textsha(reverse)==d['baseLfSha256']
    replay=reverse
    for h in d['hunks']:
        assert replay.count(h['before'])==1,h['label']
        replay=replay.replace(h['before'],h['after'])
    assert replay==candidate
    if directory=='bridge':
        frozen=MAIN/'desktop/.local/stable-offline-task-player-parity'
        assert sha(frozen/'frozen-handoff.json')==d['baseFrozenBridge']
        assert read(frozen/'prepared'/d['path'])==reverse
    else:
        assert read(REPO/d['path'])==reverse,'Root must rebase local hunks if actual Overlay changed'
    deltas.append({'file':filename,'path':d['path'],'hunks':expected,'baseLfSha256':d['baseLfSha256'],'candidateLfSha256':d['candidateLfSha256'],'exactReplayAndInverse':True})

compile_result=load(LANE/'compile-05/compile-result.json')
assert compile_result['status']=='PASS_PREPARED_SOURCE'
assert sha(LANE/'compile-05/original-offline-player.jar')=='5e03d94f6e95391470eb9f6c6192e370393008ce3e80af759ad19011affc243f'
for r in compile_result['sourcePins']: assert sha(r['path'])==r['sha256Bytes']
proof=load(LANE/'proof-04/proof-result.json')
assert proof['status']=='PASS' and proof['assertions']==24 and proof['groups']==3 and proof['pointerPairs']==1
assert sha(LANE/'proof-04/offline-original-fixture.jar')==proof['fixtureJarSha256Bytes']
assert load(LANE/'proof-04/pins-before.json')==load(LANE/'proof-04/pins-after.json')
snapshot=MAIN/'desktop/.local/stable-product-snapshot-47'
assert sha(snapshot/'manifest.json')=='72e862d74e18c2b388853634f0ef47c48ca1adba25b5d074ecd23fc6ecb7c3e0'
assert sha(snapshot/'ordered-runtime-cp.json')=='9fecec31b818a1b1d8be25f628ff6ec69e354a5c179ca42445aa1a1db3d52e94'
cp=load(snapshot/'ordered-runtime-cp.json'); assert len(cp)==97
for r in cp: assert sha(r['path'])==r['sha256Bytes']

child=MAIN/'desktop/.local/stable-video-detail-full-ui-parity'
assert sha(child/'frozen-stage1.json')=='6019bfa5ffdd3ca51b413add293c6dd724867e5f9c9731d3dbba2f515181e564'
surface=child/'prepared/manual/com/bilipai/desktop/ui/DesktopOriginalPlayerSurface.kt'
assert sha(surface)=='332bc71f6a56647e7c913e92c52478dd34f127bda8ccc593598ae3795eedc79b'
body=read(surface)
for token in ['internal fun DesktopOriginalPlayerSurface(', 'player: MpvPlayer', 'sourceVersion: Long?', 'isOwned: () -> Boolean', 'modifier: Modifier', 'foreground: @Composable () -> Unit', 'DesktopShapedVideoCommandPopup', 'desktopCommandHitRegion']:
    assert token in body,token
dependency={
    'soleOwner':'/root/player_parity/dynamic_action_review',
    'frozenStage1':{'path':str(child/'frozen-stage1.json'),'sha256Bytes':sha(child/'frozen-stage1.json')},
    'surfaceSource':{'path':str(surface),'sha256Bytes':sha(surface),'referenceOnlyNotCopiedOrInstalledHere':True},
    'signature':'@Composable internal fun DesktopOriginalPlayerSurface(player:MpvPlayer,sourceVersion:Long?,isOwned:()->Boolean,modifier:Modifier,foreground:@Composable()->Unit)',
    'delegate':'DesktopOriginalPlayerSurface(control.nativePlayer,control.sourceVersion,control::isForegroundOwned,modifier,foreground)',
    'loadingForegroundDoesNotGrantNativeWrites':True,
    'gestureCompileProofReference':compile_result['sharedGestureReference'],
    'noBinaryFallbackOrNewDependency':True,
    'rootRequired':['Install child sole original GestureLevelOverlay/source closure and Surface, never this proof JAR.','Consume actual PiP active state so only one MPV Canvas/popup carrier attaches.','Actual HWND pointer input and Root window/SMTC/PiP acceptance remain required.']
}
emit('shared-surface-contract.json',dependency)
emit('source-audit.json',{'fixedCommit':COMMIT,'fourSourceGitBlobPinsVerified':True,'sourceInventory':inventory,'completeOriginalUiLines':1225,'fullDeclarations':receipt['originalFourDeclarations'],'uiEdits':len(ui),'exactPositionalReplayAndInverse':True,'selectedBodies':receipt['sources'][1:],'soleDirect':receipt['soleDirectReference'],'generation':generation,'localDeltas':deltas,'actualRuntimePins':97,'noLiveMutationOrGradle':True})

payloads=[Path('prepared/desktop/tools/extract-upstream-offline-player.py')]+[p.relative_to(LANE) for p in sorted((LANE/'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui').glob('*.kt'))]
assert len(payloads)==4
contract={
    'status':'READY_SOURCE_ONLY','fixedCommit':COMMIT,'payloads':[row(p) for p in payloads],
    'localDeltas':deltas,'registryDelta':row(Path('registry-delta.json')),
    'newOriginalIdentities':2,'existingIdentityFeatureMerges':2,
    'productionGeneratorSelectedFiles':3,'soleDirectSyncFiles':1,
    'canonicalLongPressGetter':'com.android.purebilibili.core.store.player.DesktopOriginalLongPressSpeedSettings.getLongPressSpeed(DesktopPluginContext):Flow<Float>',
    'host':'DesktopOriginalOfflinePlayerHost(taskId:String,bindings:DesktopOriginalOfflinePlayerBindings,onBack:()->Unit)',
    'requiredBindings':'DesktopOriginalOfflinePlayerBindings(context,backend,entryScope,preferences,presentation,window,surface,media,feedback)',
    'sharedSurfaceContract':row(Path('shared-surface-contract.json')),
    'overlayOwnerPredicate':'Read-only nonblocking native token/entry Job/volatile epoch/gate::owns. Do not acquire Store/entry locks or write while Overlay invokes it under its request lock.',
    'compileEvidence':row(Path('compile-05/compile-result.json')),'compileJar':row(Path('compile-05/original-offline-player.jar')),
    'proofEvidence':row(Path('proof-04/proof-result.json')),'proof':{'groups':3,'assertions':24,'pointerPairs':1,'actualSnapshot':47,'actualRuntimeEntries':97},
    'integrationRecipe':row(Path('ROOT-INTEGRATION.md')),
    'rootRequired':['Actual taskId leaf + same manager/retained MPV/overlay/global original prefs and captured entry epoch.','Replace initial leaf player content with full original Host after sole shared source installation.','Root precise SMTC retained-source command priority and original offline title/artist/bvid loop.','Root global PiP action and single-carrier exclusion while actual PiP owns the native surface.','Actual current-theme/fullscreen/window/presentation ports and native foreground acceptance.'],
    'differences':['Viewport dim is video brightness, not Windows display brightness.','Volume is actual MPV application volume, not system stream.','WindowsMediaSession has no OS cover thumbnail ABI; original full cover UI remains.'],
    'rootMounted':False,'installedJar':False,'sharedGradleRun':False
}
emit('install-contract.json',contract)

files=[]
for p in sorted(LANE.rglob('*')):
    if not p.is_file(): continue
    rel=p.relative_to(LANE)
    if any(x in {'classes','__pycache__','local-fixture'} for x in rel.parts): continue
    if p.suffix not in {'.py','.kt','.txt','.md','.json','.jar','.args','.log'}: continue
    if rel.name=='frozen-handoff.json': continue
    files.append(row(rel))
manifest={'status':'FROZEN_SOURCE_ONLY','fixedCommit':COMMIT,'artifactCount':len(files),'artifacts':files,'installContract':row(Path('install-contract.json')),'finalSourceAudit':row(Path('source-audit.json')),'finalCompile':'compile-05','finalProof':'proof-04','groups':3,'assertions':24,'pointerPairs':1,'runtimeSnapshot':47,'runtimeEntries':97,'sharedSourceDependency':dependency,'wholeRootNativeAcceptancePending':True,'historyUnmodified':['3146 initial bridge','DownloadList58','Download actual47 proof771e'],'noLiveMutationOrGradle':True}
emit('frozen-handoff.json',manifest)
print(json.dumps({'frozenHandoffSha256':sha(LANE/'frozen-handoff.json'),'installContractSha256':sha(LANE/'install-contract.json'),'sourceAuditSha256':sha(LANE/'source-audit.json'),'payloads':contract['payloads'],'bridgeHunks':5,'overlayHunks':16,'groups':3,'assertions':24,'pointerPairs':1,'artifacts':len(files)},ensure_ascii=False,indent=2))
