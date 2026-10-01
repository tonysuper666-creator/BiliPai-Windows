from pathlib import Path
import difflib,hashlib,importlib.util,json,re,struct,subprocess,sys,zipfile
sys.dont_write_bytecode=True
import prepare as g
import compile as c
HERE=g.HERE
def digest(p):return c.sha(p)
def save(p,v):c.save(p,v)
def write(p,s):c.safe(p.parent).mkdir(parents=True,exist_ok=True);c.safe(p).write_text(s,encoding='utf-8',newline='\n')
checks=[]
def check(label,valid):assert valid,label;checks.append(dict(check=label,status='PASS'))

# Freeze an installable single producer, with production DIRECT sources copied once by registry.
tool=HERE/'prepared/desktop/tools/extract-upstream-danmaku-settings.py'
source=(HERE/'prepare.py').read_text(encoding='utf-8');cut=source.index("if __name__=='__main__':")
source=source[:cut]+'''if __name__=='__main__':
 import argparse
 cli=argparse.ArgumentParser();cli.add_argument('--repo',type=Path,default=STABLE);cli.add_argument('--output',type=Path,required=True);cli.add_argument('--standalone',action='store_true');args=cli.parse_args()
 generate(args.repo,args.output,standalone=args.standalone)
'''
write(tool,source)
for p in (HERE/'platform').glob('*.kt'):
 target=HERE/'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui'/p.name;write(target,p.read_text(encoding='utf-8'))
spec=importlib.util.spec_from_file_location('production_tool',tool);production=importlib.util.module_from_spec(spec);spec.loader.exec_module(production)
out=HERE/'audit/production-generated';rows=production.generate(g.STABLE,out,standalone=False)
full=json.loads((HERE/'generated/source-inventory.json').read_text(encoding='utf-8'))
selected=[r for r in full['outputs'] if r['mode']!='direct'];direct=[r for r in full['outputs'] if r['mode']=='direct']
check('production-seven-selected-outputs',len(rows)==len(selected)==7)
check('standalone-three-direct-outputs',len(direct)==3)
for row in selected:
 check('production-byte-equal:'+row['path'],c.safe(out/row['path']).read_bytes()==c.safe(HERE/'generated'/row['path']).read_bytes())
for row in direct:check('production-skips-direct:'+row['path'],not c.safe(out/row['path']).exists())
for row in full['sourceIdentities']:
 raw=subprocess.check_output(['git','show',g.COMMIT+':'+row['path']],cwd=g.STABLE).decode().replace('\r\n','\n')
 check('source-identity:'+row['path'],g.sha(raw)==row['sha256LF'])
panel=next(r for r in full['outputs'] if r['path'].endswith('/DanmakuSettingsPanel.kt'))
original=subprocess.check_output(['git','show',g.COMMIT+':'+panel['origin']],cwd=g.STABLE).decode().replace('\r\n','\n')
adapted=c.safe(HERE/'generated'/panel['path']).read_text(encoding='utf-8');reverse=adapted
for p in reversed(panel['adaptations']):check('panel-adaptation-once:'+p['before'][:70],reverse.count(p['after'])==1);reverse=reverse.replace(p['after'],p['before'])
check('full-panel-reverse-normalized-byte-equal',reverse==original)
write(HERE/'audit/full-panel.patch',''.join(difflib.unified_diff(original.splitlines(True),adapted.splitlines(True),fromfile=panel['origin'],tofile=panel['path'])))

# Original selected models/policies and full method bodies independently identified.
repoPath=g.BASE+'data/repository/DanmakuRepository.kt';repo=subprocess.check_output(['git','show',g.COMMIT+':'+repoPath],cwd=g.STABLE).decode().replace('\r\n','\n')
cloud=c.safe(HERE/'generated/com/android/purebilibili/data/repository/DesktopOriginalDanmakuCloudModels.kt').read_text(encoding='utf-8')
for name in ['DanmakuCloudFilterRule','DanmakuCloudFilterRules','DanmakuCloudSyncSettings','DanmakuCloudConfigPayload']:check('complete-original-schema:'+name,g.schema(repo,name) in cloud)
for name in ['mapDanmakuDisplayAreaRatioToCloudValue','mapDanmakuFontScaleToCloudFontSize','buildDanmakuCloudConfigPayload','isDanmakuCloudSyncSuccessful']:check('original-config-payload-policy:'+name,g.decl(repo,name)[0] in cloud)
playerPath=g.BASE+'feature/video/ui/section/VideoPlayerSection.kt';player=subprocess.check_output(['git','show',g.COMMIT+':'+playerPath],cwd=g.STABLE).decode().replace('\r\n','\n')
bridge=c.safe(HERE/'generated/com/bilipai/desktop/ui/DesktopOriginalDanmakuCloudSyncBinding.kt').read_text(encoding='utf-8')
fragments=[]
for name in ['buildDanmakuCloudSyncSettings','queueDanmakuCloudSync','requestDanmakuCloudSyncNow']:
 raw,start=g.decl(player,name,'        ');changed=raw.replace('            if (!canSyncDanmakuCloud) return','            if (!platform.isOwned() || !canSyncDanmakuCloud) return').replace('android.os.SystemClock.elapsedRealtime()','platform.elapsedRealtimeMillis()')
 check('full-original-config-state-function:'+name,changed in bridge)
 fragments.append(dict(member=name,sourceLine=player[:start].count('\n')+1,originalBodySha256LF=g.sha(raw),adaptedBodySha256LF=g.sha(changed)))
check('original-700ms-not-modified','kotlinx.coroutines.delay(700)' in bridge)
check('original-pending-comparison-not-modified','if (pendingDanmakuCloudSync == settings)' in bridge)
check('original-manual-version-not-modified','lastHandledDanmakuManualSyncRequestVersion = danmakuManualSyncRequestVersion' in bridge)
normal=json.loads((HERE/'selected-files/source-delta.json').read_text(encoding='utf-8'));baseline=(HERE/'source-inputs/actual20-DanmakuSettings.kt').read_text(encoding='utf-8');current=(HERE/'selected-files/DesktopDanmakuSettings.kt').read_text(encoding='utf-8');reverse=current
for p in reversed(normal['whitelist']):check('normalization-once:'+p['before'],reverse.count(p['after'])==1);reverse=reverse.replace(p['after'],p['before'])
check('normalization-only-eight-declared-lines',len(normal['whitelist'])==8 and reverse==baseline)
opsBase=(HERE/'source-inputs/actual20-DesktopDynamicCardOperations.kt').read_text(encoding='utf-8');opsCandidate=(HERE/'proof-only/DesktopDynamicCardOperations.kt').read_text(encoding='utf-8');fragment=(HERE/'Operations.danmaku-cloud.fragment.kt.txt').read_text(encoding='utf-8')
check('ops-only-four-member-fragment-before-editor',opsCandidate.replace(fragment+'\n','',1)==opsBase)

def methods(data,entry):
 p=8
 def u2():
  nonlocal p
  value=struct.unpack_from('>H',data,p)[0];p+=2;return value
 def u4():
  nonlocal p
  value=struct.unpack_from('>I',data,p)[0];p+=4;return value
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
 result=[];package=entry.rsplit('/',1)[0]
 for _ in range(u2()):
  flags=u2();name=pool[u2()];desc=pool[u2()];attrs()
  if flags&1 and flags&8 and not name.startswith('access$'):result.append((package,name,desc[:desc.index(')')+1],entry))
 return result
candidate=HERE/'compile-06/original-danmaku-settings.jar';actual=c.SNAP/'main-kotlin.jar';compiled=json.loads((HERE/'compile-06/compile-result.json').read_text(encoding='utf-8'))
with zipfile.ZipFile(candidate) as own,zipfile.ZipFile(actual) as main:
 ownNames={n for n in own.namelist() if n.endswith('.class')};mainNames={n for n in main.namelist() if n.endswith('.class')}
 ownMethods=[m for n in ownNames if n.endswith('Kt.class') and '$' not in n for m in methods(own.read(n),n)]
 actualMethods=[m for n in mainNames if n.endswith('Kt.class') and '$' not in n for m in methods(main.read(n),n)]
 keys={m[:3] for m in ownMethods};intersection=[m for m in actualMethods if m[:3] in keys]
 check('public-top-level-symbol-overlap-zero',not intersection)
 check('only-existing-Settings-and-Operations-class-family-overlap',sorted(ownNames&mainNames)==compiled['actualClassOverlap'])
save(HERE/'symbol-audit.json',dict(status='PASS',candidateJarSha256Bytes=digest(candidate),actualMain20JarSha256Bytes=digest(actual),candidateTopLevelMethods=len(ownMethods),actualTopLevelMethods=len(actualMethods),topLevelIntersection=intersection,matchingRule='same package/JVM name/parameter descriptor, return ignored, private/access$ excluded',declaredExistingClassOverlap=compiled['actualClassOverlap']))
save(HERE/'source-audit.json',dict(status='PASS',checks=checks,originalFullPanelLines=len(original.splitlines()),sourceIdentities=full['sourceIdentities'],fullConfigStateFunctions=fragments,cloudMethods=full['cloudProtocolMembers'],settingsSetters=full['settingsSetterMembers'],currentNormalizationDelta=normal,productionSelectedOutputs=[r['path'] for r in selected],productionSkippedDirectOutputs=[r['path'] for r in direct]))

registryPath=g.STABLE/'desktop/upstream-sources.json';registry=json.loads(registryPath.read_text(encoding='utf-8'));existing={r['path']:r for r in registry['sources']}
delta=[]
for row in full['sourceIdentities']:
 path=row['path'];before=existing.get(path);mode=next((r['mode'] for r in direct if r['origin']==path),None)
 if mode is None:mode='policy-extract' if path.endswith(('SettingsManager.kt','VideoPlayerSectionPolicy.kt')) else 'selected'
 delta.append(dict(path=path,sha256=row['sha256LF'],proposedMode=before['mode'] if before else mode,operation='merge-features-preserve-mode' if before else 'append-source-identity',features=['stable-danmaku-settings-panel'],existing=before))
save(HERE/'registry-delta.json',dict(reviewedRegistrySha256Bytes=digest(registryPath),sources=delta,sourceIdentityCount=len(delta),directOutputCount=3,selectedOutputCount=7,noWholeRegistryReplacement=True))
pending=['Actual original Panel pointer/modal/file chooser not mounted by this proof.','Actual Root/Shell/Controller projected preference wiring and supported native monitor presentation require Root consumer integration.','Original independent portraitFullScreen renderer/UI owner remains unsupported; physical monitor orientation map is a declared Windows presentation adapter.','Original fixedVelocity/staticToScroll/massive/weightFilter/smartOcclusion/SCREEN_TOP fields persist completely but current Main20 Window model/Scheduler has no consuming pipeline.','Current Scheduler raw-duration math differs from original Android DanmakuConfig effective duration clamps; 34 assertions verify current Scheduler receives original stored bounds, not Android-engine parity.','Original panel geometry native overlay/hit test and smart-occlusion mask need real Windows layout/render owner.','Cloud protocol runtime here uses existing Retrofit models/API through an application interceptor, no socket or account. Ops four new public members were compiled, not executed with a real account.']
contract=dict(preparedOnly=True,upstreamCommit=g.COMMIT,actualDependencyManifestSha256Bytes=digest(c.SNAP/'manifest.json'),original92CpSha256Bytes=digest(c.SNAP/'ordered-runtime-cp.json'),relocation=c.runtime()[1],producer=dict(path=str(tool),sha256LF=digest(tool),entry='generate(repo,output,standalone=False)',selectedOutputs=7,directSkipped=3),manualSourcePayloads=[dict(path=str(p),sha256LF=digest(p)) for p in sorted((HERE/'prepared/desktop/src').rglob('*.kt'))],sourceDelta=dict(path=str(HERE/'selected-files/DesktopDanmakuSettings.kt'),sha256LF=digest(HERE/'selected-files/DesktopDanmakuSettings.kt'),baseActual20Sha256Bytes=normal['actualMain20Sha256Bytes'],exactEightLinePatch=str(HERE/'selected-files/DanmakuSettings.normalization.patch')),opsFragment=dict(path=str(HERE/'Operations.danmaku-cloud.fragment.kt.txt'),sha256LF=digest(HERE/'Operations.danmaku-cloud.fragment.kt.txt'),anchor='// GENERATED original editor members; do not hand-maintain a second request algorithm.',baseSha256Bytes=digest(HERE/'source-inputs/actual20-DesktopDynamicCardOperations.kt'),copyWholeProofOnlyOperations=False),settingsAuthority='ONE supplied existing global DesktopPluginStore.settings namespace; installed sole DesktopDanmakuBlockPreferences; no MID namespace/new client/second cache',presentation='Required DesktopDanmakuPresentationBinding.currentPresentation() from actual WindowState.placement and actual fullscreen monitor orientation. INLINE always original PORTRAIT; no video/window aspect inference.',initialization='Await owned absent-only importLegacyWindowsDanmakuIfAbsent(existing Windows preferences); then forceDanmakuDefaults uses original version5 migration and does not reset imported existing values. Observe getDanmakuSettings(scope) and getDanmakuCloudSyncEnabled() actual flows. Root may wait for first emissions; do not fake a false cloud flag.',projection='EVERY initial and subsequent Overlay.applySettings call, including old dialog/toggle callbacks, must use projectOriginalDanmakuRendererSettings from the same original scope Flow. Legacy Windows PlayerPreferences is only migration input and unrelated player settings, not the source for projected danmaku controls.',cloudOwner='rememberDesktopOriginalDanmakuCloudSyncBinding must mount under the same original page/player owner outside the transient settings popup; lifetime keyed by CID/sourceVersion/epoch/native/page ownership. Complete700ms/manual-version/equality cleanup from original source, no second state/store authority.',cloudPorts='same owned Operations get/add/delete/syncDanmakuCloudConfig(api/read/mutate/result), real login snapshot, original same-key cloud enabled Flow, actual monotonic elapsed time, owned failure log port.',cloudCallbacks='Only enabled,opacity,fontScale,speed,displayArea and allowScroll/Top/Bottom/Colorful/Special queue original cloud config, matching original callsite. Disable clears pending/UI. Other setters do not add remote actions.',fileImport='Required suspend openRuleInput(fileURI) captures current import caller CoroutineContext; every read/skip checks cancellation and same owner. Owned real Root chooser returns file URI or null. No Http stream/new client.',lateRuleAppend='Existing menu Host must read current fullPrefs.currentSettings(scope).blockRulesRaw initially AND at append click; do not append against collectAsState("") before first persisted emission.',recipe=['Install ONLY prepared producer and three manual platform sources; retain logical source basenames.','Register generate task output once; copy the three direct original policies once from pinned sources through registry. Merge eight identities/features, preserve existing mode/other features.','Apply selected Windows Settings exact eight-line normalization hunk to the sole existing class; no constructor or serialized-field change.','Insert ONLY four-method Ops fragment before existing original editor marker; preserve all other Ops families and verifier slices. Never copy proof-only whole Ops.','Use video_save_alignment Root common owner/provider exact wiring; no duplicate CommentVM/gallery/API/text-selection authority.','Compile whole product and run actual original settings UI plus real native renderer consumer acceptance separately.'],proof=dict(compile06Sha256Bytes=digest(HERE/'compile-06/compile-result.json'),candidateJarSha256Bytes=digest(candidate),proof04AcceptedSha256Bytes=digest(HERE/'proof-04/accepted-result.json'),groupedCases=3,assertions=34,actualCodeSources=11,actualOriginalPanelMounted=False,actualRootConsumerWiring=False),pending=pending)
save(HERE/'install-contract.json',contract)
save(HERE/'history.json',dict(compilerFailures=['01 Windows long path pin read','02 long path source input copy','03 original object consts moved into companion for the thin instance adapter'],historicalPasses=['compile04 original13 closure before fourth config method','compile05 full15 sources but copied filename prefixes changed physical Kt wrappers'],fixtureFailures=['proof01 standalone runtime missing Snapshot.apply notifications','proof02 asserted UI status before recompose frame','proof03 all state assertions complete but Class.forName assumed logical Kt name while prefixed copied source had a different wrapper'],final='compile06 canonical logical filenames+same-byte relocated92CP; proof04 three groups34 checks and11 code sources PASS',noFailuresOverwritten=True))
rows=[]
for p in sorted(HERE.rglob('*')):
 if p.is_file() and p.name!='frozen-handoff.json':rows.append(dict(path=str(p.relative_to(HERE)).replace('\\','/'),sha256Bytes=digest(p),size=c.safe(p).stat().st_size))
save(HERE/'frozen-handoff.json',dict(frozen=True,preparedOnly=True,upstreamCommit=g.COMMIT,artifactCount=len(rows),artifacts=rows,sourceAuditSha256Bytes=digest(HERE/'source-audit.json'),symbolAuditSha256Bytes=digest(HERE/'symbol-audit.json'),installContractSha256Bytes=digest(HERE/'install-contract.json'),compile06Sha256Bytes=digest(HERE/'compile-06/compile-result.json'),proof04AcceptedSha256Bytes=digest(HERE/'proof-04/accepted-result.json'),directSourceCount=3,selectedSourceCount=7,manualPlatformSourceCount=3,existingScalarSourceDeltaCount=1,OpsMemberFragmentCount=4,productionOverridesAllowedOnly=['sole existing DanmakuSettings 8 lines','sole existing Operations 4 methods'],pending=pending))
print(json.dumps(dict(status='PASS',checks=len(checks),artifactCount=len(rows),manifestSha256Bytes=digest(HERE/'frozen-handoff.json'),contractSha256Bytes=digest(HERE/'install-contract.json'),sourceAuditSha256Bytes=digest(HERE/'source-audit.json'),symbolAuditSha256Bytes=digest(HERE/'symbol-audit.json'))))
