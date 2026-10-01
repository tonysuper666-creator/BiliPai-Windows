"""Freeze this prepared-only stable preview source/compile cohort."""
from pathlib import Path
import hashlib, json, shutil, sys
HERE = Path(__file__).resolve().parent
ROOT = next(p for p in HERE.parents if (p / '.git').exists())
COMP = 'app/src/main/java/com/android/purebilibili/feature/dynamic/components/'

def safe(path):
    value = str(Path(path).absolute())
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\' + value)

def sha(path):
    return hashlib.sha256(safe(path).read_bytes()).hexdigest()

def read(path):
    return safe(path).read_text(encoding='utf-8').replace('\r\n', '\n')

def save(path, value):
    safe(path.parent).mkdir(parents=True, exist_ok=True)
    safe(path).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')

assert not safe(HERE / 'evidence-manifest.json').exists(), 'Frozen cohorts are never overwritten'
compiled = json.loads(read(HERE / 'compile-01/compile-result.json'))
audited = json.loads(read(HERE / 'source-contract-result.json'))
assert compiled['status'] == audited['status'] == 'PASS'
assert compiled['runtimeEntries'] == 92 and len(compiled['sourceInputs']) == 6
assert audited['exactChecks'] == 24 and audited['completePlatformDiffRows'] == 33
assert audited['reviewedPlatformContractSha256Bytes'] == sha(HERE / 'reviewed-platform-adaptation-contract.json')
for row in compiled['sourceInputs']:
    assert sha(row['preparedSource']) == sha(row['compileInput']) == row['sha256Bytes']
tool_inputs = ['desktop/tools/sync-upstream.py', 'desktop/tools/extract-upstream-media.py',
    'desktop/tools/extract-upstream-plugins.py', 'desktop/tools/extract-appearance-platform.py',
    'desktop/.local/source9-appearance/compile-miuix.py']
tool_rows = []
for path in tool_inputs:
    source = ROOT / path
    copy = HERE / 'tool-inputs' / source.name
    safe(copy.parent).mkdir(parents=True, exist_ok=True)
    safe(copy).write_bytes(safe(source).read_bytes())
    tool_rows.append(dict(source=path, retainedCopy=str(copy.relative_to(HERE)), sha256Bytes=sha(copy)))
registry_path = ROOT / 'desktop/.local/source-reuse-measurement/inputs/upstream-sources-main04.json'
registry = json.loads(read(registry_path))
registry_rows = {row['path']: row for row in registry['sources']}
inventory = []
for name in ['ImagePreviewDialog', 'ImagePreviewSourceAnchor', 'ImagePreviewTransitionPolicy', 'ImagePreviewDecodePolicy', 'ZoomableImage']:
    path = COMP + name + '.kt'
    original = HERE / 'original-v023' / path
    row = registry_rows[path]
    outputs = ['generated/com/android/purebilibili/feature/dynamic/components/DesktopOriginalImagePreviewRenderer.kt',
        'generated/com/android/purebilibili/feature/dynamic/components/DesktopOriginalImageUrlPolicy.kt'] if name == 'ImagePreviewDialog' else ['direct-original/' + path]
    inventory.append(dict(path=path, originalTag='v0.2.3', originalCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',
        sha256Lf=hashlib.sha256(read(original).encode()).hexdigest(), existingMain04RegistryRow=row,
        stableMode=row['mode'], existingOwnerReplacementOnly=True,
        outputs=[dict(path=output, sha256Bytes=sha(HERE / output),
            sha256Lf=hashlib.sha256(read(HERE / output).encode()).hexdigest()) for output in outputs]))
save(HERE / 'stable-source-owner-inventory.json', dict(schema='stable-preview-existing-owner-inventory-v1', sources=inventory,
    newRegistryPaths=[], noSpaceCallerOrSecondDialog=True,
    previousRegistryInputSha256Bytes=sha(registry_path), toolInputs=tool_rows))
notes = '''Prepared stable preview migration (single existing producer)

Base: original v0.2.3 / 3d5d19a2f994daccd0e2f8b5f522b6d82f43d589.
The retained copied extractor differs from the frozen Main04 extractor only
inside its existing ImagePreviewDialog preview/URL-policy selection. Other
full-card selections still require Root's separate stable rebase. The narrow
probe executes that exact producer block; it does not implement another dialog.

Integration recipe:
1. Merge producer.patch's preview-only changes into the single card extractor
   being rebased by Root. Do not overwrite Root's other newly rebased selections
   with this baseline copy. Add original resolveImagePreviewPlaceholderCacheKey;
   retain the complete prefix before the new URL-policy documentation marker;
   remove only obsolete Quad/BlurEffectCache selection and the new Android-only
   animateWindowNavigationBarColor helper whose lifecycle is excluded already.
2. Refresh the existing direct owners ImagePreviewSourceAnchor,
   ImagePreviewTransitionPolicy, ImagePreviewDecodePolicy and ZoomableImage from
   stable. Production generate(..., standalone=False) does not emit DIRECT; the
   existing prepareUpstreamSources direct-copy authority copies them once.
   Refresh the existing five registry identities; this slice adds no path.
3. Regenerate the existing DesktopOriginalImagePreviewRenderer and URLPolicy;
   retain the unique Root ImagePreviewOverlayHost and current platform interface,
   Assets/globalLocations/client/store/lifetime ownership. No second dialog,
   cache, account, model, or preview platform schema is needed.
4. Recompile stable DrawGrid/DynamicCard/Space caller owners together. The
   sourceKey default parameter changes JVM signatures, and ZoomableImage has new
   Offset drag / Float release-velocity / resetZoomTrigger signatures. Old
   Main04 binaries are a compile dependency only, not a stable runtime proof.
   Parent separately owns Space caller/owner adaptation.

Entry contract:
ImagePreviewDialog adds sourceKey:String?=null after sourceRects. No sessionKey.
isImagePreviewSourceHidden(bounds,sourceKey=null) prefers matching non-null keys;
otherwise it uses active source-rect geometry. prepareImagePreviewSourceTransition
(rect,sourceKey=null) stages geometry/key before show. The original controller
publishes active rect after the overlay's SideEffect, restores source before
window removal, and rejects stale token updates/dismissals.
The original requestToken remember keys do not include sourceKey, platform,
MID or epoch. Callers require their real owner key and immutable opened URL/list
capture; this slice retains the upstream renderer's exact token algorithm.

Evidence and limits:
Six selected sources compiled once against immutable actual Main04's 92-entry
runtime classpath, whose hashes passed before/after. Candidate overrides only
existing preview owners and helpers; no Store/Ops/Assets/Repository source is
compiled. Twenty-four source-contract checks passed, including complete selected
prefix equality after reversing 33 independently reviewed exact platform pairs.
Four direct bodies are full stable originals. Original transition, shape, zoom
reset, velocity, controller handoff, placeholder/crossfade and live readiness
behavior are retained. Original save functions are not copied into a new backend.
Android Activity/navigation bar/dim/permission/MediaStore/predictive gestures
remain explicit Windows platform boundaries. Existing desktop save/share/haptic/
clipboard/feedback/live-player adapters remain. No runtime, HTTP, native window,
caller button E2E, stable GPU/render acceptance, Gradle or Main edits occurred.
'''
safe(HERE / 'integration-notes.txt').write_text(notes, encoding='utf-8', newline='\n')
save(HERE / 'handoff.json', dict(schema='prepared-stable-preview-handoff-v1', status='READY_PREPARED_COMPILE_ONLY',
    originalTag='v0.2.3', originalCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',
    producer='prepared/desktop/tools/extract-upstream-dynamic-card.py', producerSha256Bytes=sha(HERE / 'prepared/desktop/tools/extract-upstream-dynamic-card.py'),
    candidateJar='compile-01/prepared-stable-preview.jar', candidateJarSha256Bytes=sha(HERE / 'compile-01/prepared-stable-preview.jar'),
    actualSnapshotManifestSha256Bytes=compiled['actualSnapshotManifestSha256Bytes'],
    orderedRuntimeCpSha256Bytes=compiled['orderedRuntimeCpSha256Bytes'], runtimeEntries=92,
    compileResult='compile-01/compile-result.json', compileResultSha256Bytes=sha(HERE / 'compile-01/compile-result.json'),
    sourceContract='source-contract-result.json', sourceContractSha256Bytes=sha(HERE / 'source-contract-result.json'),
    exactSourceChecks=24, reviewedPlatformPairs=33, sourceInputs=6, ownClasses=compiled['ownClassCount'],
    declaredSameOwnerClassOverrideCount=len(compiled['declaredSameOwnerClassOverrides']),
    MainChanged=False, registryChanged=False, sharedGradle=False, HTTP=False, HWND=False, runtime=False,
    limits=['Preview selection only; Root independently migrates other full-card producer selections.',
        'Prepared compile against fixed Main04; stable callers must recompile together.',
        'No stable renderer runtime/window or button E2E acceptance.',
        'Existing platform save backend remains, with explicit Android window/predictive provider boundary.']))
artifacts = []
for path in sorted(safe(HERE).rglob('*')):
    if path.is_file() and '__pycache__' not in path.parts and path.name != 'evidence-manifest.json':
        artifacts.append(dict(path=str(path.relative_to(safe(HERE))).replace('\\', '/'), bytes=safe(path).stat().st_size, sha256Bytes=sha(path)))
save(HERE / 'evidence-manifest.json', dict(schema='prepared-stable-preview-frozen-evidence-v1', frozen=True,
    preparedOnly=True, sourceTag='v0.2.3', sourceCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',
    artifactRows=len(artifacts), artifacts=artifacts))
print(json.dumps(dict(manifest=str(HERE / 'evidence-manifest.json'), sha256Bytes=sha(HERE / 'evidence-manifest.json'),
    artifactRows=len(artifacts), sourceContractSha256Bytes=sha(HERE / 'source-contract-result.json'),
    handoffSha256Bytes=sha(HERE / 'handoff.json'))))
