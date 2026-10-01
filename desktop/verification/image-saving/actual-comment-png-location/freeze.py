"""Freeze this task-only successful focused proof, preserving the exact first run."""
from pathlib import Path
import hashlib, json
HERE = Path(__file__).resolve().parent
ROOT = next(p for p in HERE.parents if (p / '.git').exists())
def safe(p):
    s = str(Path(p).absolute())
    return Path(s if s.startswith('\\\\?\\') else '\\\\?\\' + s)
def sha(p): return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def save(p, value):
    safe(p).write_text(json.dumps(value, ensure_ascii=False, indent=2)+'\n', encoding='utf-8', newline='\n')

accepted = HERE/'proof-01/accepted-evidence.json'
assert sha(accepted) == '13a1cedf53063a5c2d588ddc5849564ba74c24e59b1572a4bc41bfdcc0b943a9'
result = json.loads(safe(accepted).read_text(encoding='utf-8'))
assert result['passed'] and result['caseCount'] == 3 and result['kotlinAssertions'] == 28
assert result['runtimeCpCount'] == 92 and result['declaredProductOverrides'] == []
source_review = ROOT/'desktop/.local/image-save-source-review/evidence-manifest.json'
assert sha(source_review) == 'a43c2ca3932bc72b91bde37a427cf0f4f4047b1660ca0e3b99ab0537a8de74ce'
save(HERE/'proof-contract.json', dict(schema='actual-main04-comment-png-locations-focused-proof-v1',
    passed=True, actualMainManifest='desktop/.local/image-save-main-integration/main-product-snapshot-04/manifest.json',
    actualMainManifestSha256Bytes=result['actualMain04ManifestSha256Bytes'],
    orderedRuntimeCpSha256Bytes=result['orderedRuntimeCpSha256Bytes'],actualRuntimeCpCount=92,actualExternalCpCount=89,
    productSourceReviewManifest='desktop/.local/image-save-source-review/evidence-manifest.json',
    productSourceReviewManifestSha256Bytes=sha(source_review),
    fixtureEntry='com.bilipai.desktop.ui.commentlocationsproof.CommentLocationsFixtureKt',
    declarationOverrides=[],actualCodeSourcesAndClassBytesVerified=9,fixtureAssertions=28,cases=[
        dict(name='custom-directory-success',
            observed='Actual global preference sync and Flow select custom file URI; actual Locations and original PNG renderer/writer produce independently decoded 1080px PNG; default callback never runs.'),
        dict(name='custom-false-default-success',
            observed='Explicit existing write callback returns false for custom attempt; actual Locations invokes default attempt once and actual PNG writer succeeds. Preference remains custom; no stage remains.'),
        dict(name='cancel-waiting-final-store-gate',
            observed='Actual original PNG has completed in private scratch. Reflection obtains existing real SessionStore monitor only to schedule its final commit wait. Only the saving Job is canceled; actual owner and application lifetime stay live. After release the original filename sentinel is unchanged, collision target is absent, no default fallback occurs and scratch is deleted.')],
    independentPillowChecks=2,originalGlobalPreferenceKey='image_save_tree_uri',
    actualStoreImplementation=True,actualWriterImplementation=True,actualLocationsImplementation=True,
    taskSchedulingSeams=['Default-directory resolver callback returns a task-only existing directory/BiliPai.',
        'Write callback intentionally returns custom false in case 2; default invokes actual writer.',
        'Task page lock and actual guest Store captured-owner gate compose the callback; the Root Community Composable is not mounted.',
        'Reflection accesses only existing SessionStore monitor for deterministic waiting; no product method is replaced.'],
    boundaries=dict(CommunityOriginalButtonClicked=False,RootComposeMounted=False,accountCredentialsUsed=False,
        actualKnownFolderCalled=False,actualDirectoryChooser=False,HTTP=False,HWND=False,EXE=False,
        originalAndroidBitmapExecuted=False,QRMatrixRepeated=False,MainChanged=False,sharedGradle=False,
        sourceRegistryChanged=False,userPicturesOrCustomDirectoriesWritten=False),
    runHistory=['proof-01 compile and fresh JVM run both succeeded; no repair or rerun was needed.']))
target = HERE/'evidence-manifest.json'
assert not safe(target).exists()
rows = []
for p in sorted(safe(HERE).rglob('*')):
    if p.is_file():
        rel = str(p.relative_to(safe(HERE))).replace('\\', '/')
        rows.append(dict(path=rel,sha256Bytes=sha(p),byteCount=p.stat().st_size))
save(target, dict(schema='frozen-actual-main04-comment-png-locations-proof-v1',frozen=True,
    acceptedEvidence='proof-01/accepted-evidence.json',acceptedEvidenceSha256Bytes=sha(accepted),
    sourceReviewManifestSha256Bytes=sha(source_review),caseCount=3,kotlinAssertions=28,
    productOverrides=[],artifactCount=len(rows),artifacts=rows))
for row in rows: assert sha(HERE/row['path']) == row['sha256Bytes']
print(json.dumps(dict(manifest=str(target),sha256Bytes=sha(target),artifacts=len(rows))))
