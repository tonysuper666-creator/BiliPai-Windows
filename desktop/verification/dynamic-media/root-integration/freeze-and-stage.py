"""Preserve reviewed raw evidence and exact actual Main identities, no binaries."""
from pathlib import Path
import hashlib, json, os, subprocess

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
LOCAL = REPO/'desktop/.local'
DEST = REPO/'desktop/verification/dynamic-media'
SNAP = HERE/'main-product-snapshot-03'
records = {}
excluded = []

def ext(path):
    value = str(Path(path).absolute()); prefix = chr(92)*2+'?'+chr(92)
    return Path(value if value.startswith(prefix) else prefix+value)
def sha(path): return hashlib.sha256(ext(path).read_bytes()).hexdigest()
def save(path, value):
    ext(path).parent.mkdir(parents=True, exist_ok=True)
    ext(path).write_text(json.dumps(value, ensure_ascii=False, indent=2)+'\n', encoding='utf-8', newline='\n')
def copy(source, relative, expected=None):
    assert Path(relative).suffix not in {'.jar','.class','.kotlin_module','.dll','.exe','.zip','.tar','.gz'}, relative
    raw=ext(source).read_bytes(); digest=hashlib.sha256(raw).hexdigest()
    if expected is not None: assert digest==expected, source
    target=DEST/relative; ext(target).parent.mkdir(parents=True, exist_ok=True); ext(target).write_bytes(raw)
    path=target.relative_to(REPO).as_posix()
    row=dict(path=path, sha256Bytes=digest, bytes=len(raw))
    if path in records: assert records[path]==row
    records[path]=row
def frozen(source, label, manifest_name, expected):
    manifest=source/manifest_name; assert sha(manifest)==expected, manifest
    content=json.loads(ext(manifest).read_bytes())
    rows=content.get('artifacts', content.get('files')); assert isinstance(rows, list)
    copied = 0
    for row in rows:
        path=Path(row['path'])
        relative=path.relative_to(source).as_posix() if path.is_absolute() else row['path'].replace('\\','/')
        assert sha(source/relative)==row['sha256Bytes'], relative
        if Path(relative).suffix in {'.jar','.class','.kotlin_module','.dll','.exe','.zip','.tar','.gz'} or 'private-native-home' in Path(relative).parts:
            excluded.append(dict(cohort=label, originalPath=relative, sha256Bytes=row['sha256Bytes'], reason='Compiled binary or task-private native extraction cache; locally hash-verified, not committed.'))
            continue
        copy(source/relative, label+'/'+relative, row['sha256Bytes'])
        copied += 1
    copy(manifest, label+'/'+manifest_name, expected)
    return dict(declared=len(rows), archived=copied, excluded=len(rows)-copied)

registry=json.loads((REPO/'desktop/upstream-sources.json').read_bytes())
assert len(registry['sources'])==len({r['path'] for r in registry['sources']})==622
assert sha(SNAP/'manifest.json')=='f9d91763db17acaa59753f5dccad4feb88512c8ad2984062520ed867943df6a6'
assert sha(SNAP/'ordered-runtime-cp.json')=='3abb7e2fecccb5bd0693e6b0868d002b576fc8f689d734d86bd5737fe1c39cac'
snapshot=json.loads(ext(SNAP/'manifest.json').read_bytes())
for row in snapshot['sourceFiles']:
    assert hashlib.sha256(ext(REPO/row['path']).read_bytes().replace(b'\r\n',b'\n')).hexdigest()==row['sha256Lf'], row['path']
for row in json.loads(ext(SNAP/'ordered-runtime-cp.json').read_bytes()): assert sha(row['path'])==row['sha256Bytes'], row['path']
cohorts={}
for lane,label,name,pin in [
    ('dynamic-gallery-motion-photo-parity','gallery-motion-photo-prepared','frozen-handoff.json','08da9ef3efd871724d1cc58482dc5945e18c9312229e4749f75e0dc405826c18'),
    ('dynamic-motion-photo-download-owner-parity','anonymous-assets-prepared','frozen-handoff.json','bd97df3e2aa1ea4529fa49f9196b8f95db7e0100dfcc20aff79d0b091a14a503'),
    ('comment-qr-windows-clip-parity','qr-clip-prepared','frozen-handoff.json','382b64f3b20ab5b0d330a78bcedc6a74a7a2d38a8be32f946dfa4905f25b5f57'),
    ('dynamic-detail-reply-parity/detail-container-next/motion-photo-commit-cancel-delta','motion-photo-commit-cancel-prepared','evidence-manifest.json','af0187e4604340a71bc5bd3c7ece5065836e83ea1f2e2014eb28e8fad22d41a3'),
    ('dynamic-detail-root-mounted-proof/mounted-main02-acceptance-final','actual-main02-mounted-detail','evidence-manifest.json','323d66e4031b0a3cc31e251a1c9bba65f34cb33c32e97099c1c0c1f04635cc16'),
    ('dynamic-detail-root-mounted-proof/main02-unchanged-count-failure-final','actual-main02-same-count-failure','evidence-manifest.json','3e1ec8eedfe3e56738362672841151188ef084e7d7cc76f3b9c0e94648488818'),
    ('dynamic-detail-root-mounted-proof/comment-confirmation-receipt-delta','comment-confirmation-source-delta','evidence-manifest.json','af93f3e64052f4650b9460b7ca6f8efc6523141969c90607a2c182f72d12fb84'),
    ('dynamic-detail-root-mounted-proof/receipt-offline-proof-final','comment-confirmation-prepared-proof','evidence-manifest.json','f8d575ff3d163e94bfabbfa7b36fe2d03d121c28175042048695d134b65389c3'),
]: cohorts[label]=frozen(LOCAL/lane,label,name,pin)

# Agents append exact newly accepted handoffs here after actual Main execution.
actual=json.loads((HERE/'actual-main-handoffs.json').read_bytes())
for entry in actual:
    cohorts[entry['label']]=frozen(LOCAL/entry['lane'],entry['label'],entry['manifest'],entry['sha256Bytes'])
for name in ['install-source.py','source-install-receipt.json','snapshot-main.py','record-runtime.init.gradle','freeze-and-stage.py','review-main.py','actual-main-runtime-paths.json','actual-main-handoffs.json','gradle-main-01.log','root-review.json']:
    copy(HERE/name,'root-integration/'+name)
for name in ['manifest.json','ordered-runtime-cp.json']:
    copy(SNAP/name,'root-integration/main-product-snapshot-03/'+name)
for name in ['producer-receipt.json','xmp-three-line-adaptation.diff']:
    copy(REPO/'desktop/build/generated/dynamic-gallery-motion-photo'/name,'root-integration/generated/'+name)
copy(REPO/'desktop/build/generated/dynamic-media-verification.json','root-integration/generated/dynamic-media-verification.json')

review=json.loads((HERE/'root-review.json').read_bytes())
assert review['actualMainProofsPassed'] is True
save(DEST/'excluded-runtime-artifacts.json',dict(locallyVerified=True,binariesCommitted=False,artifacts=excluded))
path=(DEST/'excluded-runtime-artifacts.json').relative_to(REPO).as_posix()
records[path]=dict(path=path,sha256Bytes=sha(DEST/'excluded-runtime-artifacts.json'),bytes=ext(DEST/'excluded-runtime-artifacts.json').stat().st_size)
report=dict(baseCommit='e10471a960e8d7dde10d0395c4f5f6c07dbcb4f7', upstreamTag=registry['upstreamTag'], upstreamCommit=registry['upstreamCommit'],
    registryBefore=621, registryAfter=622, sourceWindowsVersion='0.2.406.9', deployedWindowsVersion='0.2.406.5', packaged=False,
    actualMainManifestSha256Bytes=sha(SNAP/'manifest.json'), actualOrderedCpSha256Bytes=sha(SNAP/'ordered-runtime-cp.json'), runtimeEntries=92,
    compiledSourceFiles=len(snapshot['sourceFiles']), cohorts=cohorts, excludedRuntimeArtifacts=len(excluded), review=review,
    changes=['Install sole original Gallery result and Motion Photo JPEG/XMP producer with the exact approved three duplicate expanded-attribute omissions.',
        'Stream anonymous image/video downloads, drain callbacks before cleanup and admit final writes under the existing SessionStore owner.',
        'Recheck save Job cancellation inside the final Motion Photo commit gate.',
        'Wire the existing card saveMotionPhoto and original editor Gallery result to the installed operations.',
        'Use the existing Root Window as parent of the single save chooser.',
        'Clip only the printed footer URL at the original QR boundary; complete QR URL and all QR matrix pixels stay original.',
        'Protect same-value selected-comment confirmations against detail responses started earlier.'],
    scope=dict(actualChooserAccepted=False, completeRootShellAccepted=False, nativeLivePhotoPlaybackAccepted=False,
        PhotosRecognitionAccepted=False, systemShareAccepted=False, liquidGlassEnabled=False,
        fullStaticImageEncodingParity=False, persistentSaveDirectoryParity=False, loadMoreSameCountReceiptWindowAccepted=False,
        originalBatchAllAttemptsParity=False,
        fullDetailParity=False, fullApplicationParity=False))
save(DEST/'integration-report.json',report)
path=(DEST/'integration-report.json').relative_to(REPO).as_posix()
records[path]=dict(path=path,sha256Bytes=sha(DEST/'integration-report.json'),bytes=ext(DEST/'integration-report.json').stat().st_size)
save(DEST/'artifact-manifest.json',dict(artifactCount=len(records),artifacts=sorted(records.values(),key=lambda r:r['path']),
    binariesCommitted=False,scope='Reviewed prepared source plus actual Main03 narrow consumer proofs; whole application parity remains incomplete.'))
paths=list(records)+[(DEST/'artifact-manifest.json').relative_to(REPO).as_posix()]
notice_pins=json.loads((REPO/'desktop/third-party/dynamic-media-dependency-pins.json').read_bytes())
paths.extend('desktop/src/main/resources/'+entry['resource'] for entry in notice_pins['notices'])
for arguments in [['diff','--name-only','-z'],['diff','--cached','--name-only','-z'],['ls-files','--others','--exclude-standard','-z']]:
    paths.extend(p for p in subprocess.check_output(['git','-c','core.longpaths=true']+arguments,cwd=REPO).decode().split(chr(0)) if p)
paths=sorted(set(paths)); assert all(not p.startswith('desktop/.local/') and 'private-native-home' not in Path(p).parts for p in paths)
assert all(Path(p).suffix not in {'.jar','.class','.kotlin_module','.dll','.exe','.zip','.tar','.gz'} for p in paths)
ext(HERE/'staging-paths.txt').write_bytes(chr(0).join(paths).encode()+b'\x00')
print(json.dumps(dict(rawArtifacts=len(records),stagePaths=len(paths),manifestSha256Bytes=sha(DEST/'artifact-manifest.json'),reportSha256Bytes=sha(DEST/'integration-report.json'))))
