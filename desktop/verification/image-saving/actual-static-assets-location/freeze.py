"""Freeze passed Main04 proofs; preserve sources/logs/media as raw, binaries/caches as references only."""
from pathlib import Path
import hashlib, json, shutil, sys, zipfile
sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
def safe(path):
    value = str(Path(path).resolve())
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\' + value)
def sha(path): return hashlib.sha256(safe(path).read_bytes()).hexdigest()
def save(path, value):
    assert path.resolve().is_relative_to(HERE)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
out = HERE / 'runs/02'
accepted_path = out / 'accepted-evidence.json'
accepted = json.loads(accepted_path.read_text())
compiled = json.loads((out / 'compile-evidence.json').read_text())
assert accepted['passed'] and accepted['assertions'] == 54 and accepted['cases'] == 7
assert accepted['HTTPRequests'] == 11 and accepted['independentReadbackAssertions'] == 15
assert accepted['existingMainProductOverrides'] == [] and compiled['productClassIntersection'] == []
assert sha(out / 'fixture-only.jar') == accepted['fixtureJarSha256Bytes']
assert sha(out / 'frozen-sources/IntegrationFixture.kt') == compiled['compiledFixtureSha256Bytes']
cp = compiled['orderedActual92Classpath']; assert len(cp) == 92
for row in cp: assert sha(row['path']) == row['sha256Bytes']
main = next(row for row in cp if row.get('source') == 'desktop/build/classes/kotlin/main')
identities = []
with zipfile.ZipFile(safe(main['path'])) as archive:
    for row in accepted['actualProductCodeSources']:
        name = row['className'].replace('.', '/') + '.class'
        data = archive.read(name)
        identities.append({**row, 'classResource': name, 'actualClassBytesSha256Bytes': hashlib.sha256(data).hexdigest(), 'actualMainJarSha256Bytes': main['sha256Bytes']})
fixture_classes = []
with zipfile.ZipFile(out / 'fixture-only.jar') as archive:
    for name in archive.namelist():
        if name.endswith('.class'):
            fixture_classes.append(dict(classResource=name, classBytesSha256Bytes=hashlib.sha256(archive.read(name)).hexdigest()))
identity_path = out / 'actual-product-class-identities.json'
save(identity_path, dict(actualProductClassIdentities=identities, actualMainJarSha256Bytes=main['sha256Bytes'],
                       fixtureJarSha256Bytes=accepted['fixtureJarSha256Bytes'], fixtureClassIdentities=fixture_classes,
                       productClassIntersection=[], existingMainProductOverrides=[]))
save(HERE / 'attempt-history.json', {
    'compile01': {'accepted': False, 'fixtureOnly': True, 'reason': 'Fixture JPEG byte constants needed parentheses before toByte; no product source changed.', 'logSha256Bytes': sha(HERE / 'runs/01/compile.log')},
    'compile02': {'accepted': True, 'fixtureOnly': True, 'logSha256Bytes': sha(out / 'compile.log')},
    'run02': {'accepted': True, 'runtimeAssertions': 54, 'cases': 7, 'independentReadbackAssertions': 15, 'runLogSha256Bytes': sha(out / 'run.log'),
              'nativeLoaderNotice': 'SkLoadICU emitted missing icudtl.dat notices in private task-home; executed PNG/JPEG/motion outputs passed independent Pillow/XML readback.'}
})
save(HERE / 'integration-boundaries.json', {
    'actualMain04IntegratedRuntime': True, 'fixtureOnlyJar': True, 'productOverrides': [],
    'ownedDownloadedSourcesAndImageMotionStagesDrained': True,
    'customFailureSentinelTargetsRetainedUnchanged': True,
    'staticFallbackHTTPRequests': 1, 'motionFallbackHTTPRequests': 2,
    'freshInputMeaning': 'Each destination attempt reopens the same downloaded owned file; no second download and no reused exhausted InputStream.',
    'motionPreferencesScope': 'Only composed Motion Photo image destination uses remembered/default image location; this fixture does not alter or exercise standalone MP4 Save As.',
    'standaloneVideoDestinationChangedByFixture': False,
    'defaultDirectorySeam': 'Actual product Locations constructor resolver supplied only a task-owned default-base/BiliPai Path result.',
    'stageSchedulingSeam': 'Fixture stillOwned callback delegates actual Ops ownership and briefly gates only after a complete real encoded stage is observed.',
    'finalFailureInjection': 'Actual SessionStore monitor blocks final commit; custom already-selected filename is occupied by fixture sentinel without moving directory or corrupting stage.',
    'cancellationInjection': 'Cancel actual save Job while blocked on actual SessionStore final admission; no default fallback or later batch HTTP item.',
    'sharedGradle': False, 'MainWrites': False, 'HWND': False, 'old135MatrixRerun': False, 'outsideSocket': False,
    'AndroidBitmapParityProven': False,
})
raw_sources = ['runner.py', 'IntegrationFixture.kt', 'freeze.py', 'PLAN.json', 'attempt-history.json', 'integration-boundaries.json',
               'runs/01/compile.log', 'runs/01/compile-failure.json',
               'runs/02/frozen-sources/IntegrationFixture.kt', 'runs/02/compiler.args', 'runs/02/compile.log', 'runs/02/compile-evidence.json',
               'runs/02/run.args', 'runs/02/run.log', 'runs/02/runtime-process.json', 'runs/02/accepted-evidence.json',
               'runs/02/independent-readback.json', 'runs/02/actual-product-class-identities.json', 'runs/02/proof/result.json']
proof = out / 'proof'
for path in (proof / 'inputs').iterdir():
    if path.is_file(): raw_sources.append(path.relative_to(HERE).as_posix())
for row in accepted['independentOutputs']: raw_sources.append((proof / row['path']).relative_to(HERE).as_posix())
for directory in ['batch-middle-decode-failure']:
    for path in (proof / directory).glob('BiliPai_*'):
        if path.is_file(): raw_sources.append(path.relative_to(HERE).as_posix())
for name in ['custom-final-failure-first-complete-stage.png', 'motion-custom-final-failure-first-complete-stage.bin', 'reference-quality95.jpg']:
    raw_sources.append((proof / name).relative_to(HERE).as_posix())
raw_sources = sorted(set(raw_sources))
formal = HERE / 'formal-raw'; assert not formal.exists(); formal.mkdir()
raw_inventory = []
for value in raw_sources:
    source = HERE / value; target = formal / value
    assert source.is_file() and source.suffix.lower() not in ['.jar', '.dll', '.dat', '.lock', '.x']
    target.parent.mkdir(parents=True, exist_ok=True); shutil.copyfile(source, target)
    raw_inventory.append(dict(path=value, rawPath=str(target), sha256Bytes=sha(target), bytes=target.stat().st_size,
                              scope='formal raw evidence', malformedFixtureInput=value.endswith('decode-failure.png')))
runtime_inventory = []
for path in (HERE / 'runs').rglob('*'):
    if path.is_file() and path.relative_to(HERE).as_posix() not in raw_sources:
        runtime_inventory.append(dict(path=path.relative_to(HERE).as_posix(), sha256Bytes=sha(path), bytes=path.stat().st_size,
                                      scope='evidenceOnly runtime artifact; excluded from formal raw'))
manifest = {
    'frozen': True, 'status': 'actual immutable Main04 narrow integration accepted',
    'assertions': 54, 'cases': 7, 'independentReadbackAssertions': 15, 'actualLoopbackHTTPRequests': 11,
    'actualMain04Manifest': compiled['snapshot'] + '/manifest.json', 'actualMain04ManifestSha256Bytes': accepted['actualMain04ManifestSha256Bytes'],
    'orderedActual92Cp': compiled['snapshot'] + '/ordered-runtime-cp.json', 'orderedActual92CpSha256Bytes': accepted['orderedActual92CpSha256Bytes'],
    'actualMainKotlinJarSha256Bytes': main['sha256Bytes'], 'fixtureJarSha256Bytes': accepted['fixtureJarSha256Bytes'],
    'fixtureOnly': True, 'productClassIntersection': [], 'existingMainProductOverrides': [],
    'actualProductClassIdentities': identities, 'acceptedEvidence': str(accepted_path), 'acceptedEvidenceSha256Bytes': sha(accepted_path),
    'formalRaw': raw_inventory, 'runtimeReferencesOnly': runtime_inventory,
    'formalRawExcludes': ['JAR', 'DLL', 'native caches', 'dat/lock/x', 'compiler/task homes', 'stores', 'collision sentinel runtime state', 'Boolean callback artificial output'],
    'ownedSourcesAndStagesDrained': True, 'customSentinelTargetsRetainedUnchanged': True,
    'motionImageFallbackUsesTwoOriginalHTTPInputs': True, 'standaloneMP4SaveAsUntouchedAndNotExercised': True,
    'MainWrites': False, 'sharedGradle': False, 'HWND': False, 'old135MatrixRerun': False, 'outsideSocket': False,
}
manifest_path = HERE / 'evidence-manifest.json'; save(manifest_path, manifest)
save(HERE / 'frozen-handoff.json', dict(frozen=True, status=manifest['status'], acceptedEvidence=str(accepted_path), acceptedEvidenceSha256Bytes=sha(accepted_path),
    evidenceManifest=str(manifest_path), evidenceManifestSha256Bytes=sha(manifest_path), formalRaw=str(formal), actualMain04ManifestSha256Bytes=accepted['actualMain04ManifestSha256Bytes'],
    orderedActual92CpSha256Bytes=accepted['orderedActual92CpSha256Bytes'], actualMainKotlinJarSha256Bytes=main['sha256Bytes'], fixtureJarSha256Bytes=accepted['fixtureJarSha256Bytes'],
    assertions=54, cases=7, independentReadbackAssertions=15, actualLoopbackHTTPRequests=11, productClassIntersection=[], existingMainProductOverrides=[]))
print(json.dumps(dict(acceptedEvidence=str(accepted_path), acceptedEvidenceSha256Bytes=sha(accepted_path), evidenceManifest=str(manifest_path), evidenceManifestSha256Bytes=sha(manifest_path),
    frozenHandoff=str(HERE / 'frozen-handoff.json'), frozenHandoffSha256Bytes=sha(HERE / 'frozen-handoff.json'), formalRawFiles=len(raw_inventory), runtimeReferencesOnly=len(runtime_inventory)), indent=2))
