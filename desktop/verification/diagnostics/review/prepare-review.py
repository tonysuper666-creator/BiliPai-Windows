"""Prepare an isolated byte-pinned integration draft; never modifies product/frozen inputs."""
from pathlib import Path
import difflib, hashlib, importlib.util, json, subprocess, sys
sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
PRODUCER = HERE.parent / 'settings-diagnostics-parity'
PRODUCER_PIN = 'e04f8a6ec2b533695c3f988b4d67733d500089d49c238bbb1b976a8b930be5f7'

def safe(p):
    value = str(Path(p).absolute())
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\' + value)
def sha(p): return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def read(p): return safe(p).read_text(encoding='utf-8').replace('\r\n', '\n')
def write(p, content):
    safe(p.parent).mkdir(parents=True, exist_ok=True)
    safe(p).write_bytes(content if isinstance(content, bytes) else content.encode('utf-8'))
def dump(p, value): write(p, json.dumps(value, ensure_ascii=False, indent=2) + '\n')
def load(p, name):
    spec = importlib.util.spec_from_file_location(name, p)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module
def replace_once(text, old, new):
    if text.count(old) != 1: raise ValueError('Non-unique integration anchor: ' + old[:80])
    return text.replace(old, new)

assert sha(PRODUCER / 'verified-artifacts.json') == PRODUCER_PIN
frozen = json.loads(read(PRODUCER / 'verified-artifacts.json'))
frozen_checks = [dict(path=str(PRODUCER / e['path']), sha256Bytes=e['sha256Bytes']) for e in frozen['artifacts']]
frozen_checks.append(dict(path=str(PRODUCER / 'verified-artifacts.json'), sha256Bytes=PRODUCER_PIN))
for e in frozen_checks: assert sha(e['path']) == e['sha256Bytes'], e['path']
dependencies = json.loads(read(PRODUCER / 'dependency-identities.json'))
for e in dependencies: assert sha(e['path']) == e['sha256Bytes'], e['path']
originals = json.loads(read(PRODUCER / 'source-inventory.json')) + json.loads(read(PRODUCER / 'resource-inventory.json'))
for e in originals: assert hashlib.sha256(read(REPO / e['path']).encode()).hexdigest() == e['sha256'], e['path']

helper_paths = ['desktop/tools/extract-upstream-plugins.py', 'desktop/tools/extract-upstream-media.py',
    'desktop/tools/extract-upstream-api.py', 'desktop/tools/sync-upstream.py',
    'desktop/tools/extract-upstream-settings-search.py', 'desktop/tools/extract-upstream-settings-categories.py',
    'desktop/src/main/kotlin/com/bilipai/desktop/update/UpdateStorage.kt', 'LICENSE',
    'desktop/third-party/miuix5157/dependency-pins.json']
pins = json.loads(read(REPO / helper_paths[-1]))
for notice in pins['notices']:
    relative = 'desktop/src/main/resources/' + notice['resource']
    assert sha(REPO / relative) == notice['sha256'], relative
    helper_paths.append(relative)
platform = [dict(path=p, sha256Bytes=sha(REPO / p)) for p in helper_paths]

tool = load(PRODUCER / 'extract-upstream-diagnostics.py', 'reviewdiagnostics')
outputs = tool.generate(REPO, HERE / 'generated-review')
assert len(outputs) == 6
generated = []
for p in outputs:
    relative = p.relative_to(HERE / 'generated-review').as_posix()
    assert sha(p) == sha(PRODUCER / 'generated' / relative), relative
    generated.append(dict(path=relative, sha256Bytes=sha(p), identicalToFrozenGenerated=True))
assert len({e['path'] for e in generated}) == 6

payload = HERE / 'payload'
files = []
def stage(relative, content, origin):
    destination = payload / relative
    write(destination, content)
    current = REPO / relative
    base = sha(current) if safe(current).exists() else None
    files.append(dict(path=relative, baselineSha256Bytes=base, payloadSha256Bytes=sha(destination), origin=origin))

for name in ['DesktopDiagnostics.kt', 'DesktopDiagnosticSettingsSection.kt', 'DesktopLocalDiagnosticViewer.kt',
             'DesktopDiagnosticRuntimeBindings.kt', 'DesktopDiagnosticFileChooser.kt']:
    stage('desktop/src/main/kotlin/com/bilipai/desktop/diagnostics/' + name,
          safe(PRODUCER / name).read_bytes(), 'producer-verbatim')
for e in json.loads(read(PRODUCER / 'bridge-baselines.json')):
    assert sha(REPO / e['path']) == e['baselineSha256Bytes'], e['path']
    stage(e['path'], safe(PRODUCER / 'bridge-draft' / Path(e['path']).name).read_bytes(), 'producer-verbatim')
stage('desktop/tools/extract-upstream-diagnostics.py', safe(PRODUCER / 'extract-upstream-diagnostics.py').read_bytes(), 'producer-verbatim')

manifest = json.loads(read(REPO / 'desktop/upstream-sources.json'))
before = dict(sources=len(manifest['sources']), resources=len(manifest['resources']))
assert len({e['path'] for e in manifest['sources']}) == before['sources']
assert len({e['path'] for e in manifest['resources']}) == before['resources']
merges = []
for array, incoming in [('sources', tool.inventory(REPO)), ('resources', tool.resources(REPO))]:
    for e in incoming:
        matches = [x for x in manifest[array] if x['path'] == e['path']]
        if matches:
            current = matches[0]
            assert current['sha256'] == e['sha256']
            if 'mode' in e: assert current['mode'] == e['mode'], e['path']
            previous = list(current['features'])
            current['features'] = list(dict.fromkeys(previous + e['features']))
            merges.append(dict(path=e['path'], array=array, added=False, previousFeatures=previous,
                               finalFeatures=current['features'], preservedMode=current.get('mode')))
        else:
            manifest[array].append(e)
            merges.append(dict(path=e['path'], array=array, added=True, finalFeatures=e['features'], preservedMode=e.get('mode')))
assert all(len({e['path'] for e in manifest[a]}) == len(manifest[a]) for a in ['sources', 'resources'])
stage('desktop/upstream-sources.json', json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', 'review-manifest-feature-union')

gradle = read(REPO / 'desktop/build.gradle.kts')
assert 'extractUpstreamDiagnostics' not in gradle and 'generated/diagnostics' not in gradle
task = '''val extractUpstreamDiagnostics by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractUpstreamSettingsCategories)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-diagnostics.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/diagnostics/sources").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-diagnostics.py", "tools/extract-upstream-plugins.py",
        "tools/extract-upstream-media.py", "tools/extract-upstream-api.py",
        "tools/extract-upstream-settings-search.py", "tools/sync-upstream.py")
    inputs.files(sources.filter { "settings-local-diagnostics-parity" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    inputs.files(originalResources.filter { "settings-local-diagnostics-symbol" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/diagnostics"))
    // Own the reference-only output too; the network-proxy producer has its own copy.
}

'''
gradle = replace_once(gradle, 'val extractNativeMusicRoot by tasks.registering(Exec::class) {', task + 'val extractNativeMusicRoot by tasks.registering(Exec::class) {')
gradle = replace_once(gradle, '    kotlin.srcDir(layout.buildDirectory.dir("generated/network-proxy"))',
                      '    kotlin.srcDir(layout.buildDirectory.dir("generated/network-proxy"))\n    kotlin.srcDir(layout.buildDirectory.dir("generated/diagnostics/sources"))')
gradle = replace_once(gradle, 'sourceSets.named("main") { resources.srcDir(generatedAppearanceResources) }',
                      'tasks.named("compileKotlin") { dependsOn(extractUpstreamDiagnostics) }\nsourceSets.named("main") { resources.srcDir(generatedAppearanceResources) }')
stage('desktop/build.gradle.kts', gradle, 'review-unique-producer-sourceSet-task')

snapshot_path = HERE.parent / 'blocked-up-foundation-product-snapshot/manifest.json'
snapshot = json.loads(read(snapshot_path))
snapshot_checks = [dict(path=str(snapshot_path), sha256Bytes=sha(snapshot_path))]
for e in snapshot['artifacts']:
    assert sha(e['path']) == e['sha256Bytes']
    snapshot_checks.append(dict(path=e['path'], sha256Bytes=e['sha256Bytes']))
dump(HERE / 'current-seam-baselines.json', [dict(path=p, sha256Bytes=sha(REPO / p)) for p in [
    'desktop/src/main/kotlin/com/bilipai/desktop/Main.kt', 'desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt',
    'desktop/src/main/kotlin/com/bilipai/desktop/plugins/DesktopPluginRuntime.kt',
    'desktop/src/main/kotlin/com/bilipai/desktop/plugins/DesktopPluginStore.kt']])
plan = dict(schemaVersion=1, rootOnlyApply=True, windowsBaseCommit=subprocess.check_output(['git','rev-parse','HEAD'], cwd=REPO, text=True).strip(),
            frozenChecks=frozen_checks, dependencies=dependencies + snapshot_checks, originalInputs=originals,
            platformInputs=platform, files=files, generatedOutputs=generated, sourceMerges=merges,
            countsBefore=before, countsAfter=dict(sources=len(manifest['sources']), resources=len(manifest['resources'])),
            mainAndShellSnippetOnly=True, producerVerbatimFileCount=8, compileOrRuntimeExecuted=False)
dump(HERE / 'install-plan.json', plan)
patch = ''
for e in files:
    base = read(REPO / e['path']) if e['baselineSha256Bytes'] else ''
    final = read(payload / e['path'])
    patch += ''.join(difflib.unified_diff(base.splitlines(True), final.splitlines(True), fromfile='a/'+e['path'], tofile='b/'+e['path']))
write(HERE / 'integration.patch', patch)
dump(HERE / 'prepare-proof.json', dict(passed=True, frozenArtifactsVerified=frozen['artifactCount'], dependenciesVerified=len(dependencies),
    originalSourceCount=4, originalResourceCount=1, rootGplLicenseVerified=True, existingAppearanceNoticesVerified=len(pins['notices']),
    generatedSixIdenticalToFrozen=True, sharedGradleInvoked=False, mainFilesModified=False, frozenFilesModified=False,
    filesStaged=len(files), countsBefore=before, countsAfter=plan['countsAfter'], installPlanSha256Bytes=sha(HERE/'install-plan.json')))
print(json.dumps(dict(passed=True, files=len(files), before=before, after=plan['countsAfter'], planSha256Bytes=sha(HERE/'install-plan.json'))))
