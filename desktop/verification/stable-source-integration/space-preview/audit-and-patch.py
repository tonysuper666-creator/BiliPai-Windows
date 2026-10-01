"""Source identity, exact selected bodies and narrow integration diff audit."""
from pathlib import Path
import difflib,hashlib,importlib.util,json,subprocess,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent; REPO=HERE.parents[2]
PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith(PREFIX) else PREFIX+s)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n').replace('\r','\n')
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def h(text):return hashlib.sha256(text.encode()).hexdigest()
def write(p,text):
 p=safe(p);p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf-8',newline='\n')
def js(p,o):write(p,json.dumps(o,indent=2,ensure_ascii=False))
source=read(HERE.parent/'space-avatar-save-v023-parity/original/SpaceScreen.kt')
assert h(source)=='2c7063c8c9b112b10f7b5394b34ddfc4362fcf2984d3eae3a3364469cc3557ca'
spec=importlib.util.spec_from_file_location('space_consumer_audit_parser',REPO/'desktop/tools/sync-upstream.py')
parser=importlib.util.module_from_spec(spec);spec.loader.exec_module(parser)
def select(text,anchor):
 ts=parser.kotlin_tokens(text); a=text.index(anchor); start=next(i for i,t in enumerate(ts) if t[1]>=a)
 end=start
 while ts[end][0]!='(':end+=1
 depth=1
 while depth:end+=1;depth+=(ts[end][0]=='(')-(ts[end][0]==')')
 while ts[end][0]!='{':end+=1
 depth=1
 while depth:end+=1;depth+=(ts[end][0]=='{')-(ts[end][0]=='}')
 return text[ts[start][1]:ts[end][2]]
generated=read(HERE/'generated/com/android/purebilibili/feature/space/DesktopOriginalSpaceImagePreviewCallers.kt')
banner=select(source,'private fun SpaceHeaderBanner(')
badge=select(source,'private fun SpaceHeaderTitleBadge(')
assert h(banner)=='386487c3e118a2889c0d38bd8854a8d0b16b76ba3fc6a689990d51e1d2a4ff82'
assert h(badge)=='4d8533cc5c499094f0406fd6a772aae870af7ce0b136cc42500f8f4ebd85de12'
assert select(generated,'internal fun DesktopOriginalSpaceHeaderBanner(')==banner.replace(
 'private fun SpaceHeaderBanner(','internal fun DesktopOriginalSpaceHeaderBanner(',1).replace('LocalContext.current','LocalPlatformContext.current')
assert select(generated,'private fun SpaceHeaderTitleBadge(')==badge
write(HERE/'original-selected/SpaceHeaderBanner.kt.reference.txt',banner)
write(HERE/'original-selected/SpaceHeaderTitleBadge.kt.reference.txt',badge)
originalRows=[dict(originalPath='app/src/main/java/com/android/purebilibili/feature/space/SpaceScreen.kt',
 declaration=n,sha256Lf=h(b),fullFunctionBodyPreserved=True) for n,b in [('SpaceHeaderBanner',banner),('SpaceHeaderTitleBadge',badge)]]

contract=json.loads(read(HERE/'preparation-contract.json'))
consumers=HERE/'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui'
patch=''; bindingChecks=[]
for row in contract['baselineConsumers']:
 name=Path(row['path']).name; baseline=read(HERE/'baseline'/name); desired=read(consumers/name)
 assert h(baseline)==row['sha256Lf']
 assert sha(REPO/row['path'])==row['actualBytesSha256'], 'Do not replace a changed Main source: '+name
 patch+=''.join(difflib.unified_diff(baseline.splitlines(True),desired.splitlines(True),
  fromfile='a/'+row['path'],tofile='b/'+row['path']))
 bindingChecks.append(dict(path=row['path'],baseSha256Lf=h(baseline),desiredSha256Lf=h(desired),
  desiredSha256Bytes=sha(consumers/name),baseMatchesCurrentMain=True))
write(HERE/'consumer.patch',patch)
check=subprocess.run(['git','apply','--check','--ignore-space-change','--ignore-whitespace',str(HERE/'consumer.patch')],
 cwd=REPO,capture_output=True,text=True,encoding='utf-8',errors='replace')
write(HERE/'consumer-patch-check.log',check.stdout+check.stderr);check.check_returncode()

overview=read(consumers/'DesktopSpaceOverviewScreens.kt'); complete=read(consumers/'DesktopCompleteSpaceScreen.kt')
legacy=read(consumers/'DesktopSpaceScreens.kt'); owner=read(consumers/'DesktopSpaceImagePreviews.kt')
checks={
 'originalBannerWholeBodyOnlyPlatformRename':True,
 'originalBadgeWholeBodyUnchanged':True,
 'customBannerDialogRemoved':'Dialog(' not in overview,
 'fullOriginalBannerCalled':'DesktopOriginalSpaceHeaderBanner(topImages = topItems' in overview,
 'currentRealBannerUrlForwarded':'onTopPhotoPreview?.invoke(topPhotoRect.value, currentBannerUrl)' in overview,
 'skinUsesActualGlobalAuthority':'LocalUiSkinState.current' in overview and 'UiSkinSurface.PROFILE in it.manifest.surfaces' in overview,
 'skinPriorityDisablesRemoteImagePreview':'skinSpaceBackgroundPaths.isEmpty()' in overview,
 'previewSourceKeyPrepared':'prepareImagePreviewSourceTransition(rect, avatarPreviewUrl)' in owner and
  'prepareImagePreviewSourceTransition(rect, currentBannerUrl)' in owner,
 'originalCallerSourceKeyForwarded':generated.count('sourceKey = sourceKey,')==2,
 'sameOwnerOriginalProviderKeyed':'if (owned()) key(alive)' in owner,
 'immutableOpenedAvatar':'openedAvatarUrl = avatarPreviewUrl' in owner,
 'immutableOpenedTopImages':'openedTopImages = topImages.toList()' in owner,
 'existingAssetsOnly':'DesktopDynamicImageAssets(repository.httpClient' in owner and 'DesktopDynamicCardOperations(repository' in owner,
 'noCompetingStore':'DesktopPluginStore(' not in owner and 'DesktopImageSavePreferences(' not in owner,
 'requiredGlobalLocations':'checkNotNull(LocalDesktopImageSaveLocations.current)' in owner,
 'completeFallbackFeedback':'onTopicKeyword = onTopicKeyword, onImagePreviewFeedback = onImagePreviewFeedback)' in complete,
 'completeLeafFeedback':'state.leaf, onTopic, onTopicKeyword, onImagePreviewFeedback)' in complete,
 'legacySameHost':'DesktopSpaceImagePreviews(repository, mid, user.avatar, emptyList(), "", onImagePreviewFeedback)' in legacy,
 'stableCoilCacheKeyAndCrossfadeBoth':all('resolveImagePreviewPlaceholderCacheKey' in x and '.crossfade(false)' in x for x in [overview,legacy]),
 'stableCircularAvatarBoth':all('Modifier.size(80.dp)' in x and '.clip(CircleShape)' in x and '.border(2.dp' in x for x in [overview,legacy]),
 'avatarHiddenIdentityBoth':all('isImagePreviewSourceHidden(avatarRect.value, user.' in x for x in [overview,legacy]),
 'noSavedImageProtocolDuplication':'Call.execute' not in owner and 'fun saveImage(url' in owner and 'assets.saveImage(url)' in owner,
}
assert all(checks.values()), checks
inventory=dict(originalTag='v0.2.3',originalCommit=contract['originalCommit'],preparedOnly=True,
 originalSourceIdentities=[dict(path=contract['originalSource'],sha256Lf=contract['originalSourceSha256Lf'],
  mode='extract',producer='prepared/desktop/tools/extract-space-image-preview-callers.py',
  output='com/android/purebilibili/feature/space/DesktopOriginalSpaceImagePreviewCallers.kt',
  integration='Merge existing stable SpaceScreen identity and feature registration; retain its other producers. No duplicate row.')],
 selectedOriginalDeclarations=originalRows,
 sharedReferences=['feature/space/SpaceHeaderPresentationPolicy.kt original banner policies',
  'data/model/response/SpaceModels.kt original top image/title models','core/ui/components/AppText.kt',
  'core/ui/components/AppProgressIndicator.kt','core/plugin/skin/UiSkinState.kt and UiSkinModels.kt existing global source'],
 childSolePreviewOwner=dict(directory=str(HERE.parent/'stable-image-preview-producer-parity'),
  manifestSha256='aa2f0fded76142156e7470d40d999939e78fe9ea3a86300353ab8e1e805a92d9',
  selectedSourceCount=6,rendererIsNotProducedHere=True),
 additionalResources=[],additionalDependencies=[],parallelModelOrStore=False,
 payloadTargets=[dict(candidate=str(p.relative_to(HERE)),target='desktop/'+str(p.relative_to(HERE/'prepared/desktop')).replace('\\','/'),
  sha256Bytes=sha(p)) for p in [*sorted(consumers.glob('*.kt')),HERE/'prepared/desktop/tools/extract-space-image-preview-callers.py']],
 consumerChanges=bindingChecks,sourceContractChecks=checks,sourceContractCheckCount=len(checks),
 fullOuterHeaderStillPending=['Original hero/parallax/collapse/shared transition/top scrim and complete header appearance',
  'Actual stable Main recompilation and mounted preview/save/owner tests','Native popup/system chooser/Photos/EXE acceptance'])
js(HERE/'source-inventory.json',inventory)
js(HERE/'source-audit.json',dict(status='PASS',sourceIdentityChecks=originalRows,consumerChecks=checks,
 sourceContractCheckCount=len(checks),consumerPatchApplyCheck=True,checksAreSourceNotRuntime=True,
 noMainWrites=True,noGradle=True,noHWND=True,noHTTP=True))
js(HERE/'compile-attempt-history.json',dict(attempts=[
 dict(directory='compile-01',compiler='FAIL',cause='Candidate AST extraction stopped at default callback lambda instead of function parameter closing. Fixed only new generator, no original body edits.'),
 dict(directory='compile-02',compiler='PASS',evidenceAssertion='FAIL',cause='Class-overlap allowlist omitted Compose compiler ComposableSingletons for the same three consumer files. Historical outputs retained.'),
 dict(directory='compile-03',compiler='PASS',classOwnershipAudit='PASS',proof='compile-03/compile-result.json')],noOldFrozenEvidenceChanged=True))
print(f'SPACE STABLE SOURCE AUDIT PASS: {len(checks)} source checks; 3-consumer patch applies; original full Banner+Badge retained.')
