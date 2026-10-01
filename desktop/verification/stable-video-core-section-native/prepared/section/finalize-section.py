from pathlib import Path
import hashlib,json,subprocess
P=Path(__file__).resolve().parent;MAIN=P.parents[2];REPO=MAIN.parent/'BiliPai-v023';COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def read(p):return wide(p).read_bytes()
def digest(b):return hashlib.sha256(b).hexdigest()
def pin(p):return dict(path=str(p.relative_to(wide(P))).replace('\\','/'),bytes=len(read(p)),sha256Bytes=digest(read(p)),sha256LF=digest(read(p).replace(b'\r\n',b'\n')))
def save(p,v):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes((json.dumps(v,ensure_ascii=False,indent=2)+'\n').encode())
config=json.loads(read(P/'sole-producer-config.json'));compiled=json.loads(read(P/'section-runs/06/compile-result.json'));symbols=json.loads(read(P/'section-runs/06/symbol-audit.json'));production=json.loads(read(P/'section-production-proof-01/result.json'))
assert compiled['passed']and compiled['sources']==21 and symbols['passed']and production['passed']
assert len(config['sourcePins'])==17 and len(config['directSources'])==8
registry=REPO/'desktop/upstream-sources.json';existing={r['path']:r for r in json.loads(read(registry))['sources']}
delta=[];origins={};outputs=[]
for folder in ['generated','section-generated']:
 for p in sorted(wide(P/folder).rglob('*.kt')):
  rel=str(p.relative_to(wide(P/folder))).replace('\\','/');raw=read(p).replace(b'\r\n',b'\n');original='app/src/main/java/'+rel
  if rel.startswith('com/android/purebilibili/core/store/DesktopOriginalPlayer'):original='app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt'
  if rel.endswith('DesktopOriginalApiDimensionPolicy.kt'):original='app/src/main/java/com/android/purebilibili/feature/video/state/VideoPlayerState.kt'
  if rel.endswith('DesktopOriginalVideoPlayerSectionPolicy.kt'):original='app/src/main/java/com/android/purebilibili/feature/video/ui/section/VideoPlayerSectionPolicy.kt'
  direct=original in config['directSources'];outputs.append(dict(path=rel,origin=original,sha256LF=digest(raw),lines=len(raw.splitlines()),mode='direct-complete-original'if direct else'selected-platform',installVia='sole-sync'if direct else'sole-section-producer'));origins.setdefault(original,[]).append(rel)
assert len(outputs)==17
for path,p in config['sourcePins'].items():
 raw=subprocess.check_output(['git','show',COMMIT+':'+path],cwd=REPO).replace(b'\r\n',b'\n');assert digest(raw)==p['sha256LF']
 old=existing.get(path)
 if old:assert old['sha256']==p['sha256LF']
 delta.append(dict(path=path,sha256=p['sha256LF'],gitBlob=p['gitBlob'],feature='stable-video-player-section-full',proposedMode=old['mode']if old else('direct'if path in config['directSources']else'policy-extract'),existingMode=old['mode']if old else None,outputs=origins.get(path,[]),mergeOnly=True,sharedKeyReference=path.endswith('/PlayerSettingsStore.kt')))
save(P/'section-registry-delta.json',dict(commit=COMMIT,identities=17,mergeExistingIdentityFeatureUnion=True,doNotReplaceRegistry=True,observedRegistrySha256Bytes=digest(read(registry)),sources=delta))
manual=[pin(p)for p in sorted(wide(P/'prepared/manual').rglob('*.kt'))];tools=[pin(p)for p in sorted(wide(P/'prepared/desktop/tools').glob('*.py'))]
save(P/'section-source-inventory.json',dict(commit=COMMIT,sources=config['sourcePins'],outputs=outputs,manual=manual,prospectiveProofFamilyHunks=[pin(wide(P/'overlay-control-local-hunks.json')),pin(wide(P/'settings-context-local-hunks.json'))],originalPolicySelection='section-policy-selection.json',originalSettingsSelection='section-settings-selection.json',wholeHelperBodies='section-helper-inventory.json',originalFullRendererPlatformDiff='section-production-proof-01/inverse',actual50ReferencesOnly=True))
# Preserve the earlier six-source result verbatim; add an explicit correction record.
history=P/'native-state-runs/06/compile-result.json'
save(P/'native-state-06-metadata-correction.json',dict(historicalResult=pin(wide(history)),preservedUnchanged=True,correction='The historical description "3 access-only hunks" was stale. Actual captured inputs include OverlayControl access/repeat/listener family (five hunks) and SettingsContext family (two hunks). This corrects only scope metadata, not raw compile or Jar bytes.',historicalJarSha256Bytes='91aefe3349fafb20502275ea188e2344e1196f202f36783e2646b4c7046c615d',currentFullClosure='section-runs/06/compile-result.json',nativeControlManualUnchangedFromHistorical06=digest(read(P/'prepared/manual/com/bilipai/desktop/ui/DesktopOriginalMpvVideoPlayerState.kt'))==digest(read(P/'native-state-runs/06/source-inputs/prepared/manual/com/bilipai/desktop/ui/DesktopOriginalMpvVideoPlayerState.kt')),currentSettingsClosureExpandedFrom70To94Members=True,currentSettingsContextMayNotBeClaimedAsHistorical06Bytes=True))
save(P/'SECTION-INSTALL-RECIPE.json',dict(stage='Full original VideoPlayerSection/Contracts renderer plus sole native-state/settings/helper closure',commit=COMMIT,baseline='actual50/97 + explicitly prospective parent core03 + frozen Stage3; source-only',generator='generate(repo,output,standalone=False)',soleTools=tools,generatedDirectory='build/generated/original-video-player-section-full',directCopyOnce=[r for r in outputs if r['mode']=='direct-complete-original'],selectedOutputs=[r for r in outputs if r['mode']!='direct-complete-original'],manualPayloads=manual,existingSourceHunks=['overlay-control-local-hunks.json','settings-context-local-hunks.json'],doNotCopyWholeProofSources=True,registryDelta='section-registry-delta.json',compilerEvidence='section-runs/06/compile-result.json',symbolEvidence='section-runs/06/symbol-audit.json',sourceProof='section-production-proof-01/result.json',rootContract='SECTION-ROOT-CONTRACT.md',sourceCounts=dict(identities=17,standalone=17,direct=8,productionSelected=9,manual=2,compiled=21),noWholeOpsControllerListenShellMpvPayload=True,sourceOnlyRequiredRootBindings=True,acceptance='No page/UI/native/HWND/fullscreen/HTTP/account runtime acceptance'))
rows=[];excluded=[]
for p in sorted(wide(P).rglob('*')):
 if not p.is_file():continue
 rel=str(p.relative_to(wide(P))).replace('\\','/')
 if rel=='frozen-section.json':continue
 b=read(p);r=dict(path=rel,bytes=len(b),sha256Bytes=digest(b))
 if p.suffix.lower()in['.jar','.class','.pyc']or'__pycache__/'in rel:excluded.append(dict(**r,reason='Binary/compiler artifact excluded from raw formal evidence and installation'))
 else:rows.append(r)
save(P/'frozen-section.json',dict(frozen=True,scope='Complete original Section source closure; required same-owner Root transports explicitly pending mounting',commit=COMMIT,rawArtifacts=rows,excludedArtifacts=excluded,identities=17,standaloneOutputs=17,directOutputs=8,productionSelectedOutputs=9,manualSources=2,existingFamiliesExactHunks=7,compiledSources=21,compilePassed=True,candidateClasses=278,unpermittedClassOverlaps=0,unpermittedTopLevelMethodIntersections=0,declaredProofFamilyClassOverlaps=14,declaredProofFamilyMethodIntersections=5,illegalNonLocalReturnClasses=0,productionDefaultStandaloneByteEqual=True,wholeSourceInverseFiles=13,explicitRuntimeAcceptance=False,remainingFullPage=['Parent full StateHolder/VM/Tablet/Cinema/Audio assembly and business authority retirement','Required same-source Root platform implementations','Physical fullscreen/PiP/viewport/input/Texture transitions','Native frame/screenshot and actual page UI acceptance']))
print(json.dumps(dict(frozenManifest=str(P/'frozen-section.json'),sha256Bytes=digest(read(P/'frozen-section.json')),rawArtifacts=len(rows),excludedArtifacts=len(excluded),recipeSHA=digest(read(P/'SECTION-INSTALL-RECIPE.json')),producerSHA=digest(read(P/'prepared/desktop/tools/extract-upstream-video-player-section-full.py')))))
