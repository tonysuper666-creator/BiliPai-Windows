"""Prepare isolated reviewed payloads; never install into the product or alter frozen evidence."""
from pathlib import Path
import difflib, hashlib, importlib.util, json, subprocess, sys
sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
PRODUCER = HERE.parent / 'settings-blocked-up-parity'
BASE = 'ae57dc66884da3243a7f1d5c24bb734674069483'
CONTRACT = '5dac0250ccf68fd1e51993327d03c97c23f1773fc79994b2b63ae126069f5f36'
FROZEN = '8793d8cb7860406ac02891dc7d2c3253407e297043f25f12984709544b6fb039'

def safe(path):
    value = str(Path(path).absolute())
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\' + value)

def raw(path): return safe(path).read_bytes()
def sha_bytes(value): return hashlib.sha256(value).hexdigest()
def sha(path): return sha_bytes(raw(path))
def read_json(path): return json.loads(raw(path))
def write(relative, content):
    path = HERE / relative
    safe(path.parent).mkdir(parents=True, exist_ok=True)
    safe(path).write_bytes(content.encode('utf-8') if isinstance(content, str) else content)
def dump(relative, value): write(relative, json.dumps(value, ensure_ascii=False, indent=2) + '\n')
def replace_once(text, before, after):
    if text.count(before) != 1: raise ValueError('Exact baseline anchor changed: ' + before)
    return text.replace(before, after, 1)

def prepare():
    assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip() == BASE
    assert sha(PRODUCER / 'store-contract.json') == CONTRACT
    assert sha(PRODUCER / 'store-artifact-manifest.json') == FROZEN
    frozen = read_json(PRODUCER / 'store-artifact-manifest.json')['files']
    assert len(frozen) == 118
    checks = []
    for row in frozen:
        path = PRODUCER / row['path']
        assert sha(path) == row['sha256Bytes'], row['path']
        assert len(raw(path)) == row['bytes'], row['path']
        checks.append(dict(path=str(path), sha256Bytes=row['sha256Bytes']))
    checks.append(dict(path=str(PRODUCER / 'store-artifact-manifest.json'), sha256Bytes=FROZEN))
    dependencies = read_json(PRODUCER / 'dependency-identities.json')
    for row in dependencies: assert sha(Path(row['path'])) == row['sha256Bytes'], row['path']
    inventory = read_json(PRODUCER / 'source-inventory.json')
    for row in inventory:
        assert sha_bytes(raw(ROOT / row['path']).decode('utf-8').replace('\r\n', '\n').encode()) == row['sha256'], row['path']
    platforms = []
    for relative in ['desktop/tools/extract-discovery-platform.py', 'desktop/tools/sync-upstream.py',
                     'desktop/src/main/kotlin/com/bilipai/desktop/plugins/DesktopPluginStore.kt',
                     'desktop/src/main/kotlin/com/bilipai/desktop/plugins/DesktopPluginRuntime.kt',
                     'desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt',
                     'desktop/src/main/kotlin/com/bilipai/desktop/Main.kt']:
        platforms.append(dict(path=relative, sha256Bytes=sha(ROOT / relative)))
    files = []
    for row in read_json(PRODUCER / 'owned-files.json'):
        previous = sha(ROOT / row['path']) if safe(ROOT / row['path']).exists() else None
        assert previous == row.get('baseSha256'), row['path']
        data = raw(PRODUCER / 'prepared' / row['path'])
        assert sha_bytes(data) == row['sha256'], row['path']
        write('payload/' + row['path'], data)
        files.append(dict(path=row['path'], payloadSha256Bytes=sha_bytes(data), baselineSha256Bytes=previous))
    # REVIEW DELTA: classify cached destination errors honestly. No reload/unfreeze behavior is introduced.
    relative = 'desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopBlockedUpStore.kt'
    original = raw(HERE / 'payload' / relative).decode('utf-8').replace('\r\n', '\n')
    revised = replace_once(original, '本地黑名单资料无法读取，原数据保持不变；请修复文件后重试。',
                            '本地黑名单资料无法读取，原数据保持不变；请修复文件后重新启动应用。')
    revised = replace_once(revised, 'fun migrateLegacyDiscoveryMids(): Result<Int> = synchronized(mutationLock) {\n        try {',
                            'fun migrateLegacyDiscoveryMids(): Result<Int> = synchronized(mutationLock) {\n        var readingDestination = true\n        try {')
    revised = replace_once(revised, '            val previous = readRecords()\n            val paths = legacyDiscoveryFiles()',
                            '            val previous = readRecords()\n            readingDestination = false\n            val paths = legacyDiscoveryFiles()')
    revised = replace_once(revised, '            val error = IllegalStateException("旧屏蔽名单迁移失败，原文件和现有黑名单保持不变；请修复旧文件后重试。")',
                            '            val error = IllegalStateException(if (readingDestination)\n                "本地黑名单资料无法读取，原数据保持不变；请修复文件后重新启动应用。"\n                else "旧屏蔽名单迁移失败，原文件和现有黑名单保持不变；请修复旧文件后重试。")')
    write('review-delta/' + relative, revised)
    write('patches/cached-destination-error.patch', ''.join(difflib.unified_diff(original.splitlines(True), revised.splitlines(True),
          fromfile='frozen-stage1/' + relative, tofile='review-delta/' + relative)))
    # Root requested this independently tested cache-error wording delta; the frozen producer remains untouched.
    evidence = read_json(HERE / 'review-evidence.json')
    assert evidence['passed'] and evidence['reviewDeltaErrorCopyVerified']
    write('payload/' + relative, revised)
    for row in files:
        if row['path'] == relative:
            row['producerOriginalSha256Bytes'] = row['payloadSha256Bytes']
            row['payloadSha256Bytes'] = sha_bytes(revised.encode())
    manifest_relative = 'desktop/upstream-sources.json'
    baseline_manifest = raw(ROOT / manifest_relative)
    manifest = json.loads(baseline_manifest)
    assert manifest['hashNormalization'] == 'lf'
    before_sources = len(manifest['sources']); before_resources = len(manifest.get('resources', []))
    by_path = {row['path']: row for row in manifest['sources']}
    assert len(by_path) == before_sources
    added = []; merged = []
    for row in inventory:
        previous = by_path.get(row['path'])
        if previous is None:
            manifest['sources'].append(row.copy()); by_path[row['path']] = row; added.append(row['path'])
        else:
            assert previous['sha256'] == row['sha256'] and previous['mode'] == row['mode'], row['path']
            previous['features'] = list(dict.fromkeys(previous.get('features', []) + row['features']))
            merged.append(row['path'])
    assert len(added) == 2 and len(merged) == 1
    assert len(manifest['sources']) == before_sources + 2 and len(manifest.get('resources', [])) == before_resources
    manifest_payload = (json.dumps(manifest, ensure_ascii=False, indent=2) + '\n').encode()
    write('payload/' + manifest_relative, manifest_payload)
    files.append(dict(path=manifest_relative, baselineSha256Bytes=sha_bytes(baseline_manifest), payloadSha256Bytes=sha_bytes(manifest_payload)))
    build_relative = 'desktop/build.gradle.kts'
    baseline_build = raw(ROOT / build_relative)
    build = baseline_build.decode('utf-8')
    newline = '\r\n' if '\r\n' in build else '\n'
    block = '''val extractUpstreamBlockedUp by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractUpstreamDiscovery)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-blocked-up-platform.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/blocked-up").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-blocked-up-platform.py", "tools/extract-discovery-platform.py", "tools/sync-upstream.py")
    inputs.files(sources.filter { "settings-blocked-up" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/blocked-up"))
}

'''.replace('\n', newline)
    assert 'extractUpstreamBlockedUp' not in build and 'generated/blocked-up' not in build
    build = replace_once(build, 'val extractUpstreamNetworkProxy by tasks.registering(Exec::class) {', block + 'val extractUpstreamNetworkProxy by tasks.registering(Exec::class) {')
    build = replace_once(build, '    kotlin.srcDir(layout.buildDirectory.dir("generated/network-proxy"))',
                         '    kotlin.srcDir(layout.buildDirectory.dir("generated/blocked-up"))' + newline + '    kotlin.srcDir(layout.buildDirectory.dir("generated/network-proxy"))')
    build = replace_once(build, 'tasks.named("compileKotlin") { dependsOn(extractUpstreamNetworkProxy) }',
                         'tasks.named("compileKotlin") { dependsOn(extractUpstreamBlockedUp, extractUpstreamNetworkProxy) }')
    build_payload = build.encode()
    write('payload/' + build_relative, build_payload)
    files.append(dict(path=build_relative, baselineSha256Bytes=sha_bytes(baseline_build), payloadSha256Bytes=sha_bytes(build_payload)))
    for row in files:
        if row['baselineSha256Bytes'] is not None:
            previous = raw(ROOT / row['path']).decode('utf-8')
            next_text = raw(HERE / 'payload' / row['path']).decode('utf-8')
            write('patches/' + Path(row['path']).name + '.patch', ''.join(difflib.unified_diff(previous.splitlines(True), next_text.splitlines(True),
                  fromfile='a/' + row['path'], tofile='b/' + row['path'])))
    plan = dict(schemaVersion=1, baseCommit=BASE, frozenArtifactManifestSha256=FROZEN, files=files,
                frozenChecks=checks, dependencies=dependencies, originalInputs=inventory, platformInputs=platforms,
                sourceCounts=dict(before=before_sources, after=len(manifest['sources'])),
                resourceCounts=dict(before=before_resources, after=before_resources),
                addedSourceIdentities=added, mergedSourceIdentities=merged, newDependencies=[], newResources=[],
                reviewDeltaIncludedInInstaller=True,
                reviewedDeltaEvidenceSha256Bytes=sha(HERE / 'review-evidence.json'))
    dump('install-plan.json', plan)
    compile_evidence = read_json(PRODUCER / 'store-compile-evidence.json')
    for relative, digest in compile_evidence['compiledSourceIdentities'].items(): assert sha(PRODUCER / relative) == digest
    assert read_json(PRODUCER / 'store-proof/result.json')['junitMethodsPassed'] == 8
    test_source = raw(PRODUCER / 'prepared/desktop/src/test/kotlin/com/bilipai/desktop/data/DesktopBlockedUpStoreTest.kt').decode()
    assert test_source.count('@Test fun ') == 8 and test_source.count('(): Unit {') == 8
    dump('input-verification.json', dict(passed=True, baseCommit=BASE, producerArtifactsVerified=len(frozen),
         runtimeDependencyEntriesVerified=len(dependencies), originalSourcesVerified=len(inventory),
         modifiedDiscoveryBaselinesVerified=3, installerTargetCount=len(files), sourceCounts=plan['sourceCounts'],
         resourceCounts=plan['resourceCounts'], preservedModes=True, duplicateSourceIdentities=False,
         actualProducerMethodsPassed=8, junitEngineInvokedByProducer=False,
         producerRunner='Direct invocation of eight executable @Test Unit methods against marked production overrides',
         currentProductTestsExecuted=False, mainEdited=False, sharedGradleInvoked=False, nativeWindowCreated=False,
         accountOrUserFilesRead=False, networkRequests=False))
    print(json.dumps(dict(passed=True, artifacts=len(frozen), dependencies=len(dependencies), sources=plan['sourceCounts'], resources=plan['resourceCounts'], installerTargets=len(files))))

if __name__ == '__main__': prepare()
