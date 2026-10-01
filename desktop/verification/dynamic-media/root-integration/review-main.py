"""Root independent identity and source-delta audit of the executed Main03."""
from pathlib import Path
import hashlib, json, zipfile

HERE=Path(__file__).resolve().parent; REPO=HERE.parents[2]; LOCAL=REPO/'desktop/.local'
SNAP=HERE/'main-product-snapshot-03'
def ext(path):
    value=str(Path(path).absolute()); prefix=chr(92)*2+'?'+chr(92)
    return Path(value if value.startswith(prefix) else prefix+value)
def sha(data): return hashlib.sha256(data).hexdigest()
def read(path): return ext(path).read_bytes()
def obj(path): return json.loads(read(path))
manifest=obj(SNAP/'manifest.json'); cp=obj(SNAP/'ordered-runtime-cp.json')
assert len(cp)==92 and len(manifest['sourceFiles'])==398
for row in cp: assert sha(read(row['path']))==row['sha256Bytes']
for row in manifest['sourceFiles']: assert sha(read(REPO/row['path']).replace(b'\r\n',b'\n'))==row['sha256Lf'], row['path']
prior=obj(LOCAL/'dynamic-detail-container-main-integration/main-product-snapshot-02/ordered-runtime-cp.json')
previous={row['path']:row['sha256Bytes'] for row in prior if 'source' not in row}
current={row['path']:row['sha256Bytes'] for row in cp if 'source' not in row}
assert len(previous)==86 and len(current)==89
assert all(current[name]==digest for name,digest in previous.items())
assert {Path(p).name for p in set(current)-set(previous)}=={'commons-imaging-1.0.0-alpha6.jar','commons-io-2.19.0.jar','commons-lang3-3.17.0.jar'}
pins=obj(REPO/'desktop/third-party/dynamic-media-dependency-pins.json')
for row in pins['dependencies']: assert row['sha256'] in current.values()
with zipfile.ZipFile(ext(SNAP/'main-resources.jar')) as archive:
    for row in pins['notices']: assert sha(archive.read(row['resource']))==row['sha256']

# Existing generated outputs change only at the two reviewed Session/QR seams.
old=obj(LOCAL/'dynamic-detail-container-main-integration/main-product-snapshot-02/manifest.json')
old_files={row['path']:row['sha256Bytes'] for row in old['generatedProductFiles']}
new_files={row['path']:row['sha256Bytes'] for row in manifest['generatedProductFiles']}
changed=sorted(p for p in old_files if old_files[p]!=new_files.get(p))
added=sorted(set(new_files)-set(old_files))
assert changed==[
    'desktop/build/generated/dynamic-detail/com/android/purebilibili/feature/dynamic/DesktopOriginalDynamicReplySession.kt',
    'desktop/build/generated/dynamic-reply/com/android/purebilibili/feature/video/ui/components/DesktopOriginalReplyImageRenderer.kt',
], changed
assert added==[
    'desktop/build/generated/dynamic-gallery-motion-photo/com/android/purebilibili/core/util/DesktopOriginalGalleryResultPolicy.kt',
    'desktop/build/generated/dynamic-gallery-motion-photo/com/android/purebilibili/feature/dynamic/components/DesktopOriginalMotionPhotoPacking.kt',
], added
expected={
    changed[0]:'a2a4eb4ac3a283fba4fe73ce3df0c96a5a5e3c235b4822c06078f032e0592c5c',
    changed[1]:'0866b1077fc93ff5325576f2b96140ca9d54507dab1e14cefb282ea978e32280',
    added[0]:'4c6dff09291ee1c6732fc8af45fdb6d3fd288b228985599fcc47b9dedb45be1a',
    added[1]:'5659afa26c7eea3b21fe59f9cb158e1457b7315b2d2069c04e023dc11543384d',
}
assert all(new_files[path]==digest for path,digest in expected.items())

integration=obj(LOCAL/'actual-main-assets-integration-proof/runs/01/accepted-integration-evidence.json')
assert integration['passed'] and integration['allProductClassesActualMainZeroOverride']
assert integration['productionClassOverlap']==[]
assert integration['actualMainConfig']['manifestSha256Bytes']==sha(read(SNAP/'manifest.json'))
assert integration['actualMainConfig']['orderedCpSha256Bytes']==sha(read(SNAP/'ordered-runtime-cp.json'))
assert integration['cohorts']==dict(download=dict(assertions=55,cases=11),commitcancel=dict(assertions=25,cases=1),gallery=dict(assertions=18,cases=4),qr=dict(assertions=37,cases=8))
actual_classes=set()
for row in cp:
    with zipfile.ZipFile(ext(row['path'])) as archive:
        actual_classes.update(name for name in archive.namelist() if name.endswith('.class'))
fixture=LOCAL/'actual-main-assets-integration-proof'
compile=obj(fixture/'runs/01/compile-evidence.json')
assert compile['productionClassOverlap']==[]
assert integration['fixtureJarSha256Bytes']==compile['fixtureJarSha256Bytes']
found=[]
for path in fixture.rglob('*.jar'):
    if sha(read(path))==integration['fixtureJarSha256Bytes']: found.append(path)
assert len(found)==1
with zipfile.ZipFile(ext(found[0])) as archive:
    fixture_classes={name for name in archive.namelist() if name.endswith('.class')}
assert fixture_classes and not fixture_classes.intersection(actual_classes)

same_count=obj(LOCAL/'dynamic-detail-root-mounted-proof/main03-unchanged-count-next/unchanged-count-result-01.json')
assert same_count['passed'] and not same_count['reproducedDivergence'] and same_count['header33'] and same_count['card33'] and not same_count['card1']
assert same_count['productOverrides']==0 and same_count['noSessionFlowInjection']
assert same_count['pointerPairs']==1 and same_count['originalSortRequestMode']==2
report=dict(actualMainProofsPassed=True,mainSnapshotSha256Bytes=sha(read(SNAP/'manifest.json')),
    actualGraphEntries=92,unchangedPriorExternalArtifacts=86,addedApprovedArtifacts=3,packagedApacheNotices=6,
    originalGeneratedUnchanged=len(old_files)-len(changed),onlyChangedGenerated=changed,onlyAddedGenerated=added,
    zeroProductOverrides=True,fixtureClassOverlap=[],assertions=135,cases=24,
    actualMountedSameCountCasePassed=True,actualMountedNewestPointerPairs=1,
    rootVisualInspection=['Main03 mounted dark detail: both actual comment header and card show 33 after late detail1.',
        'Main03 exported long secondary reply: printed link ends before the intact original QR.'],
    metadataCorrections=['Raw download proof retains a legacy MainInstalledIntegration=false flag; accepted aggregate and class origins verify compiled Main03 integration. Root Window chooser/Shell/deployed EXE remain unaccepted.'],
    sourceMapping=['Chooser parent is the existing Root Window; no new picker/window/account owner.',
        'Assets defaults now use equivalent lambdas after adding an optional parent to the existing chooser.',
        'Card disposal closes the installed save owner before cancelling its operation scope.'],
    scope=dict(realAccount=False,actualChooser=False,completeRootShell=False,nativeLivePhoto=False,
        PhotosRecognition=False,systemShare=False,liquidGlass=False,packaged=False,
        persistentDirectory=False,fullStaticEncoding=False,loadMoreSameCountReceipt=False))
ext(HERE/'root-review.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
print(json.dumps(dict(passed=True,sourceFiles=398,generatedChanged=len(changed),generatedAdded=len(added),mediaCases=24,mountedCase=1)))
