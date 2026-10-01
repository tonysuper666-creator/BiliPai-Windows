from pathlib import Path
import hashlib, json, zipfile
HERE=Path(__file__).resolve().parent; REPO=HERE.parents[2];LOCAL=REPO/'desktop/.local'
SNAP=HERE/'main-product-snapshot-04'
def ext(path):
    value=str(Path(path).absolute());prefix=chr(92)*2+'?'+chr(92)
    return Path(value if value.startswith(prefix) else prefix+value)
def raw(path): return ext(path).read_bytes()
def sha(data): return hashlib.sha256(data).hexdigest()
def obj(path): return json.loads(raw(path))
manifest=obj(SNAP/'manifest.json'); cp=obj(SNAP/'ordered-runtime-cp.json')
assert sha(raw(SNAP/'manifest.json'))=='7bc5ac8875dadf38510120db6619bd6e748e7f790a47afc11374e14114f25865'
assert sha(raw(SNAP/'ordered-runtime-cp.json'))=='7a3141310fa355d8618c5bc2be90f36a2c13c8ed36f2d981bf4a14c2232165e2'
assert len(cp)==92 and len(manifest['sourceFiles'])==405
for row in cp:assert sha(raw(row['path']))==row['sha256Bytes'],row['path']
for row in manifest['sourceFiles']:assert sha(raw(REPO/row['path']).replace(b'\r\n',b'\n'))==row['sha256Lf'],row['path']
old=obj(LOCAL/'dynamic-media-main-integration/main-product-snapshot-03/manifest.json')
old_cp=obj(LOCAL/'dynamic-media-main-integration/main-product-snapshot-03/ordered-runtime-cp.json')
prior_external={row['path']:row['sha256Bytes'] for row in old_cp if 'source' not in row}
current_external={row['path']:row['sha256Bytes'] for row in cp if 'source' not in row}
assert len(current_external)==89 and current_external==prior_external
prior_generated={row['path']:row['sha256Bytes'] for row in old['generatedProductFiles']}
current_generated={row['path']:row['sha256Bytes'] for row in manifest['generatedProductFiles']}
assert all(current_generated.get(path)==digest for path,digest in prior_generated.items())
added=sorted(set(current_generated)-set(prior_generated))
assert added==[
 'desktop/build/generated/dynamic-static-image-codec/com/android/purebilibili/feature/dynamic/components/DesktopOriginalStaticGalleryFormat.kt',
 'desktop/build/generated/settings-image-save-path/com/android/purebilibili/feature/settings/DesktopOriginalImageSavePathDialog.kt',
 'desktop/build/generated/settings-image-save-path/com/android/purebilibili/feature/settings/SettingsImageSavePathEntry.kt',
],added
expected={added[0]:LOCAL/'dynamic-detail-reply-parity/detail-container-next/static-save-codec-next/generated/com/android/purebilibili/feature/dynamic/components/DesktopOriginalStaticGalleryFormat.kt',
 added[1]:LOCAL/'settings-image-save-path-parity/generated/com/android/purebilibili/feature/settings/DesktopOriginalImageSavePathDialog.kt',
 added[2]:LOCAL/'settings-image-save-path-parity/generated/com/android/purebilibili/feature/settings/SettingsImageSavePathEntry.kt'}
for path,candidate in expected.items():assert current_generated[path]==sha(raw(candidate)),path
registry=obj(REPO/'desktop/upstream-sources.json')
assert len(registry['sources'])==len({r['path'] for r in registry['sources']})==622
report=dict(actualGraphEntries=92,unchangedExternalArtifacts=89,addedDependencies=0,sourceFiles=405,registryCount=622,
 previousGeneratedFilesUnchanged=len(prior_generated),onlyAddedGenerated=added,
 actualMainManifestSha256Bytes=sha(raw(SNAP/'manifest.json')),actualOrderedCpSha256Bytes=sha(raw(SNAP/'ordered-runtime-cp.json')),
 originalPreparedGeneratedBytesMatch=True,compiledMainConsumers=True,packaged=False,
 actualRuntimeConsumerAcceptancePending=True,fullApplicationParity=False)
ext(HERE/'root-source-review.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8',newline='\n')
print(json.dumps(report))
