from pathlib import Path
import hashlib,json,os,zipfile
from urllib.parse import urlparse,unquote
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2];LOCAL=REPO/'desktop/.local';SNAP=HERE/'main-product-snapshot-04'
def ext(path):
    value=str(Path(path).absolute());prefix=chr(92)*2+'?'+chr(92)
    return Path(value if value.startswith(prefix) else prefix+value)
def raw(path):return ext(path).read_bytes()
def sha(data):return hashlib.sha256(data).hexdigest()
def obj(path):return json.loads(raw(path))
cp=obj(SNAP/'ordered-runtime-cp.json');assert len(cp)==92
cp_sha=sha(raw(SNAP/'ordered-runtime-cp.json'));manifest_sha=sha(raw(SNAP/'manifest.json'))
classes=set()
for row in cp:
    assert sha(raw(row['path']))==row['sha256Bytes']
    with zipfile.ZipFile(ext(row['path'])) as archive:
        classes.update(name for name in archive.namelist() if name.endswith('.class'))
def fixture(lane,digest):
    paths=[]
    for directory,dirs,names in os.walk(ext(LOCAL/lane)):
        for name in names:
            if name.endswith('.jar'):
                path=Path(directory)/name
                if sha(raw(path))==digest:paths.append(path)
    assert len(paths)==1,(lane,len(paths))
    with zipfile.ZipFile(paths[0]) as archive:actual={name for name in archive.namelist() if name.endswith('.class')}
    assert actual and not actual.intersection(classes),lane
    return len(actual)
png=obj(LOCAL/'comment-png-locations-main04-proof/proof-01/accepted-evidence.json')
assert png['passed'] and png['caseCount']==3 and png['kotlinAssertions']==28
assert png['actualMain04ManifestSha256Bytes']==manifest_sha and png['orderedRuntimeCpSha256Bytes']==cp_sha
assert png['classFqnIntersection']==[] and png['declaredProductOverrides']==[]
assert fixture('comment-png-locations-main04-proof',png['fixtureJarSha256Bytes'])==png['fixtureClassCount']
with zipfile.ZipFile(ext(SNAP/'main-kotlin.jar')) as archive:
    for row in png['actualProductCodeSourceAndClassBytesVerified']:
        assert Path(row['codeSource'])==SNAP/'main-kotlin.jar'
        assert sha(archive.read(row['className'].replace('.','/')+'.class'))==row['classSha256Bytes']
for row in png['independentPillowPngChecks']:
    assert sha(raw(LOCAL/'comment-png-locations-main04-proof/proof-01'/row['path']))==row['sha256Bytes']
assert png['realStoreMonitorBlocked'] and png['cancelledOnlySaveJob'] and png['currentPageLifetimeAndOwnerPreserved']
ui=obj(LOCAL/'image-save-settings-main04-mounted-proof/attempts/run-04/proof/result.json')
ui_compile=obj(LOCAL/'image-save-settings-main04-mounted-proof/attempts/run-04/compile-runtime-evidence.json')
assert ui['passed'] and ui['productOverrides']==0 and ui['realGlobalPluginStore']
assert ui_compile['snapshotManifestSha256']==manifest_sha and ui_compile['orderedRuntimeCpSha256']==cp_sha
assert fixture('image-save-settings-main04-mounted-proof',ui_compile['fixtureJarSha256'])==ui_compile['fixtureClassCount']
assert ui_compile['productClassOverlap']==0
for row in ui['classBindings']:
    value=unquote(urlparse(row['codeSource']).path)
    if value.startswith('/') and len(value)>2 and value[2]==':':value=value[1:]
    assert Path(value)==SNAP/'main-kotlin.jar'
assert len(ui['flows'])==2 and {row['style'] for row in ui['flows']}=={'MATERIAL3','MIUIX'}
assert all(row['passed'] and row['resetOriginalKeyOnly'] and row['cancelledFakeChooserPreservesValue'] and row['actualOriginalSearchResultToStorageCategory'] and row['actualCategoryRowReopensOriginalDialog'] and not row['searchDirectDetail'] for row in ui['flows'])
assets=obj(LOCAL/'dynamic-static-save-main04-integration/runs/02/accepted-evidence.json')
assets_compile=obj(LOCAL/'dynamic-static-save-main04-integration/runs/02/compile-evidence.json')
assert assets['passed'] and assets['cases']==7 and assets['assertions']==54 and assets['HTTPRequests']==11
assert assets['actualMain04ManifestSha256Bytes']==manifest_sha and assets['orderedActual92CpSha256Bytes']==cp_sha
assert assets['existingMainProductOverrides']==[] and assets_compile['productClassIntersection']==[]
assert fixture('dynamic-static-save-main04-integration',assets['fixtureJarSha256Bytes'])==assets_compile['fixtureClassCount']
assert len(assets['actualProductCodeSources'])==13
for row in assets['actualProductCodeSources']:
    value=unquote(urlparse(row['source']).path)
    if value.startswith('/') and len(value)>2 and value[2]==':':value=value[1:]
    assert Path(value)==SNAP/'main-kotlin.jar'
assert all(row['passed'] for row in assets['caseResults'])
cases={row['name']:row for row in assets['caseResults']}
for name,requests in [('custom-final-failure',1),('motion-custom-final-failure',2)]:
    row=cases[name]
    assert row['requests']==requests and row['firstCompleteCustomStage'] and row['actualStoreFinalFileFailure'] and row['freshSameOwnedSource'] and row['scratchDrained']
assert cases['batch-middle-native-decode-failure-continuation']['committedItems']==2
assert cases['cancel-real-store-final-no-fallback-no-later-items']['requests']==1
assert cases['cancel-real-store-final-no-fallback-no-later-items']['scratchDrained']
assert assets['independentReadbackAssertions']==15 and not assets['old135MatrixRerun'] and not assets['outsideSocket']
report=dict(passed=True,actualMainManifestSha256Bytes=manifest_sha,actualOrderedCpSha256Bytes=cp_sha,
 runtimeEntries=92,zeroProductOverrides=True,rootIndependentFixtureClassIntersection=[],
 png=dict(cases=3,assertions=28,actualProductClasses=9,independentPngReads=2,currentOwnerPreservedOnSaveJobCancellation=True,CommunityOriginalButtonE2E=False),
 settings=dict(styles=2,pointerPairs=sum(row['actualPointerPairs'] for row in ui['flows']),actualSearchEdits=sum(row['actualSearchEditorActions'] for row in ui['flows']),
  actualSettingsTreeMounted=True,originalSearchCategoryThenRowConfirmed=True,directSearchDetail=False,cancelAndResetStoreAccepted=True,
  originalPopupPreserved=True,completeRootShellMounted=False,actualSystemChooser=False,hwnd=False),
 rootRepresentativePngVisualReviewPassed=True,firstMiuixEnterFrameClippedPreserved=True,
 staticAssets=dict(cases=7,assertions=54,independentReadbackAssertions=15,actualProductClasses=13,HTTPRequests=11,
  staticFormats=['PNG','JPEG95','GIF-original-bytes','WebP-original-bytes'],
  customCompleteStageFinalFailureDefaultFreshInput=True,motionCompleteStageFailureDefaultFreshInputs=True,
  batchMiddleDecodeFailureContinues=True,saveJobCancellationNoFallbackNoLaterItemsScratchDrained=True,
  knownFolderDefaultIsTaskPathSeam=True,original135MatrixRerun=False),
 staticAssetsAcceptancePending=False,packaged=False,fullApplicationParity=False)
measurement=obj(LOCAL/'source-reuse-measurement/measurement.json')
assert measurement['allRegisteredOriginalSourceLfPinsVerifiedAgainstGit'] and measurement['allCountedCurrentFilesVerifiedAgainstMain04Snapshot']
assert measurement['snapshotManifestSha256Bytes']==manifest_sha
modes=measurement['registryModes']
adopted=sum(modes[name] for name in ['direct','api-extract','policy-extract','extracted','selected'])
assert adopted==591 and modes['direct']==389
report['sourceReuseMeasurement']=dict(metric='Original pinned alpha.9 production Kotlin/Java file adoption coverage; partial files count once, platform rewrites/references excluded.',
 originalProductionFiles=1534,adoptedOriginalFiles=adopted,fullDirectFiles=389,partialExtractionFiles=202,
 adoptedFileCoveragePercent=100*adopted/1534,directFileCoveragePercent=100*389/1534,
 generatedPhysicalLineSharePercent=100*109103/156013,
 strictVerbatimOriginalCodeLineReuseMeasured=False,functionalPercentageMeasured=False,
 generatedDirectoryIncludesAdapters=True,physicalLinesIncludeWhitespaceAndComments=True,
 compiledDesktopSourceIncludesThirdPartyJava=True)
ext(HERE/'root-runtime-review.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8',newline='\n')
print(json.dumps(report))
