from pathlib import Path
import hashlib,json,sys
sys.dont_write_bytecode=True
LANE=Path(__file__).resolve().parent;MAIN=LANE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def read(p):return wide(p).read_bytes()
def sha(p):return hashlib.sha256(read(p)).hexdigest()
def write(p,b):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(b.encode()if isinstance(b,str)else b)
def save(p,v):write(p,json.dumps(v,ensure_ascii=False,indent=2)+'\n')
inventory=json.loads(read(LANE/'source-inventory.json'))
compile=json.loads(read(LANE/'runs/12/compile-result.json'));proof=json.loads(read(LANE/'proof/03/accepted-result.json'))
assert compile['passed']and proof['status']=='PASS'and proof['assertions']==26
for root in ['generated','prepared/manual']:
 for p in wide(LANE/root).rglob('*.kt'):
  relative=p.relative_to(wide(LANE));assert read(p)==read(LANE/'runs/12/source-inputs'/relative),str(relative)
assert json.loads(read(LANE/'install-audit-02/symbol-audit.json'))['passed']
assert json.loads(read(LANE/'install-audit-02/source-body-audit.json'))['passed']
outputs=inventory['outputs'];direct=[r for r in outputs if r['mode']=='direct'];selected=[r for r in outputs if r['mode']!='direct']
identities=[]
registry={r['path']:r for r in json.loads(read(REPO/'desktop/upstream-sources.json'))['sources']}
for r in inventory['sourceIdentities']:
 owners=[o for o in outputs if o['origin']==r['path']]
 mode='reference'if not owners else'direct'if any(o['mode']=='direct'for o in owners)else'selected'
 identities.append(dict(**r,requestedMode=mode,existingRecord=registry.get(r['path']),mergeOnly=True,neverOverwriteOtherOwner=True,outputPaths=[o['path']for o in owners]))
save(LANE/'prepared/registry-delta.json',dict(pinnedCommit=inventory['pinnedCommit'],identities=identities,identityCount=len(identities),identityModesMustMerge=True,notAnInstalledRegistry=True))
manual=[dict(path=str(p.relative_to(wide(LANE/'prepared/manual'))).replace('\\','/'),sha256LF=sha(p))for p in sorted(wide(LANE/'prepared/manual').rglob('*.kt'))]
recipe=dict(stage='Complete original ordinary Info/Action/Engagement and shared gesture units, not full ordinary VideoDetail',baseline='actual47 immutable97CP, prospective only',soleTools=[dict(path='prepared/desktop/tools/extract-upstream-video-detail-full-units.py',sha256Bytes=sha(LANE/'prepared/desktop/tools/extract-upstream-video-detail-full-units.py')),dict(path='prepared/desktop/tools/verify-upstream-video-detail-full-units.py',sha256Bytes=sha(LANE/'prepared/desktop/tools/verify-upstream-video-detail-full-units.py'))],generator='generate(repo, output, standalone=False); default produces selected18 + member fragment; original direct8 copy once by existing sync source roots',selectedOutputs=selected,directOutputs=direct,manualOutputs=manual,metadataVisibilityLocalHunk=dict(path='prepared/metadata-visibility-hunk.json',sha256Bytes=sha(LANE/'prepared/metadata-visibility-hunk.json')),operationsMemberFragment=dict(path='prepared/DesktopDynamicCardOperations.video-members.kt.fragment',sha256Bytes=sha(LANE/'prepared/DesktopDynamicCardOperations.video-members.kt.fragment'),insertBefore='// GENERATED original editor members; do not hand-maintain a second request algorithm.',installWholeProofOperations=False),sourceDirs='Add sole generated directory to existing Kotlin source roots; preserve direct copy roots and all existing producer tasks/gates',verification='Existing five protocol/editor gates untouched; new exact member verifier additionally compares video marker→EDITOR marker with the new generated fragment',requiredRoot=['same detail/comment captured account epoch and nav-entry owner','same existing DesktopDynamicCardOperations instance, Repository/API/SessionStore, no client construction','same global DesktopPluginContext and PluginStore','same UI-dispatcher entry scope and atomic Root admission for EngagementEnvironment','LocalDesktopOriginalVideoInfoBindings via DesktopOriginalVideoInfoRootBinding, same existing CardPlatform clipboard/feedback','real DesktopOriginalVideoAnalyticsBinding(globalStore, actualDiagnosticsOrExplicitUnavailable, stillOwned); no Firebase claim','same actual native player Canvas; one command carrier only; no simultaneous old PlayerPanel carrier','actual PiP owner excludes simultaneous Canvas/Popup attachment; root binding still required','all original Info/Action callbacks supplied by future full detail assembly: comment/download/share/folder/coin/follow/BGM/tag/description/navigation'],pending=['Full VideoScreenStateHolder5464 and original Success extensions','Full VideoPlayerSection5838 and VideoContentSection2209 assembly','Full BottomControlBar1936, TopControlBar501, FullscreenPlayerOverlay1724 and VideoPlayerOverlay3526','Ordinary/portrait player settings/actions/chapters physical mounted consumer','Actual native Popup pointer/focus/fullscreen/PiP acceptance and texture clipping/haze platform differences'],noMainEdits=True,noGradle=True,noHTTP=True,noHWND=True)
save(LANE/'INSTALL-RECIPE.json',recipe)
write(LANE/'ROOT-CONTRACT.md','''Complete original video units — stage1 prepared only

The sole producer preserves the full ordinary information functions, remaining full action renderer, full Engagement class/default action implementation, full UseCase, original raw action protocols and full shared GestureLevelOverlay. Existing metadata/team/BGM/TripleProgressIcon/FollowVisualPolicy remain sole references. The generated directory is not a second full VideoPlaybackViewModel.

Install only the source-only tool, five manual platform sources, eight original direct sources copied once, eighteen generated sources and the exact new Operations member fragment. Apply the three visibility changes through the existing metadata producer. Do not copy the proof-only whole Operations or metadata buddy from runs. Preserve the existing editor/comment/detail/grade/fraud/BGM verifiers; the new video verifier is additional.

DesktopOriginalVideoEngagementEnvironment requires context, same entry UI scope, existing Operations.originalVideoEngagementActions(analytics), originalVideoCoinBalanceLoader(), stillOwned, and Root atomic commit. Bind immutable original VideoSubjectSnapshot with original generation and complete real seed. Keep subject replacement on the same UI dispatcher. The existing channel pairs original public events with transient generation, rejects old queued events, and never becomes a second cache. Raw dislike code65007/65005, APP-101, coin34005, WatchLater90001/90003 and original sequential triple are retained. Native/account success cannot be invented from UI state.

DesktopOriginalVideoInfoRootBinding(context,operations,sameCardPlatform) supplies owned creator metadata, clipboard and feedback. Same original settings keys/defaults live in the one global store, including the original easter_egg mirror. DesktopOriginalVideoAnalyticsBinding uses existing local diagnostics and analytics_enabled; Firebase is unavailable and sensitive ID parameters remain omitted as upstream.

@Composable DesktopOriginalPlayerSurface(player:MpvPlayer,sourceVersion:Long?,isOwned:()->Boolean,modifier:Modifier,foreground:@Composable()->Unit) supplies the sole existing shapedPopup foreground carrier. Nullable version means explicit owned loading/cover presentation, not permission to write native state. Offline must pass control::isForegroundOwned and separately retain strict control::isOwned for writes. Original full foreground root black background may map to transparent over the same actual black Canvas; original cover, scrims and controls remain. Root must retire old PlayerPanel carrier and exclude actual PiP surface ownership to avoid simultaneous Canvas attachment. Leaving this composition does not stop or close MPV.

Evidence: actual47/97CP fixed dependency graph with declared prospective new classes + existing Operations appended-member buddy + metadata visibility buddy. Final compile12 passed33 sources, fixture03 passed3 grouped cases/26 assertions,7 origins, zero fixture/product class overlap. Both compiled overrides are explicitly prospective, not actual Main runtime acceptance. Symbol audit has only two declared existing families, no unexpected FQN/top-level signature conflict or NON_LOCAL_RETURN reference. Eight direct full files, five Info bodies, two gesture whole files and action remaining file have strict equality/inverse proof; owned full VM/protocol diffs are retained separately and are not called byte-identical.

Ordinary complete player page/control overlays and actual native input/PiP/window proof are still pending. No broad matrix was added. Main/shared Gradle/source registry/HTTP/accounts/HWND were untouched.
''')
manifest=LANE/'frozen-stage1.json';assert not wide(manifest).exists()
excluded=[];artifacts=[]
for p in sorted(wide(LANE).rglob('*')):
 if not p.is_file():continue
 relative=p.relative_to(wide(LANE)).as_posix()
 if p.suffix in ('.jar','.class','.pyc') or 'private-scratch/'in relative or '__pycache__/'in relative:
  excluded.append(dict(path=relative,sha256Bytes=sha(p),reason='private temporary synthetic store/result or compiled binary; not registered raw'))
 else:artifacts.append(dict(path=relative,bytes=p.stat().st_size,sha256Bytes=sha(p)))
save(manifest,dict(status='FROZEN_PREPARED_STAGE1',pinnedOriginalCommit=inventory['pinnedCommit'],actual47DependencyManifestSha256Bytes='72e862d74e18c2b388853634f0ef47c48ca1adba25b5d074ecd23fc6ecb7c3e0',actual47CpSha256Bytes='9fecec31b818a1b1d8be25f628ff6ec69e354a5c179ca42445aa1a1db3d52e94',sourceIdentities=24,generatedOutputs=26,directCopiedOnce=8,selectedOutputs=18,manualSources=5,compileSources=33,compilePhase='runs/12',fixturePhase='proof/03',groupedCases=3,assertions=26,loadedOrigins=7,fixtureProductClassOverlap=0,prospectiveAcceptance=True,actualMainAcceptance=False,sourceOnlyRecipeSha256Bytes=sha(LANE/'INSTALL-RECIPE.json'),sourceBodyAuditSha256Bytes=sha(LANE/'install-audit-02/source-body-audit.json'),symbolAuditSha256Bytes=sha(LANE/'install-audit-02/symbol-audit.json'),artifacts=artifacts,excluded=excluded,historicalFailuresRetained=True,pending=recipe['pending']))
print(json.dumps(dict(path=str(manifest),sha256Bytes=sha(manifest),registeredRawRows=len(artifacts),excludedRows=len(excluded),recipeSha256Bytes=sha(LANE/'INSTALL-RECIPE.json'))))
