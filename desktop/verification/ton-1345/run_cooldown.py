#!/usr/bin/env python3
"""Compile/run the real CDN producer and JUnit regression without building the desktop UI.

Uses full retained Kotlin sources and SDK contracts, never another checkout's classes.
API wall-clock and logging bindings apply only inside this verification output.
No substitute policy implementation, HTTP traffic, Gradle task exclusion, or timer sleeps.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--before-fix', action='store_true', help='use immutable base DataSource recipe with the same clock seam/tests')
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[3]
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    cache = Path.home() / '.gradle/caches/modules-2/files-2.1'

    def jar(group, artifact, version):
        return next((cache / group / artifact / version).glob('*/*.jar'))

    cp = [jar(g, a, v) for g, a, v in [
        ('org.jetbrains.kotlin', 'kotlin-stdlib', '2.4.0'),
        ('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.11.0'),
        ('org.jetbrains', 'annotations', '13.0'),
        ('org.jetbrains.kotlinx', 'kotlinx-serialization-core-jvm', '1.11.0'),
        ('com.squareup.okhttp3', 'okhttp', '4.12.0'),
        ('com.squareup.okio', 'okio-jvm', '3.6.0'),
        ('org.junit.jupiter', 'junit-jupiter-api', '5.10.2'),
        ('org.junit.jupiter', 'junit-jupiter-engine', '5.10.2'),
        ('org.junit.platform', 'junit-platform-launcher', '1.10.2'),
        ('org.junit.platform', 'junit-platform-engine', '1.10.2'),
        ('org.junit.platform', 'junit-platform-commons', '1.10.2'),
        ('org.opentest4j', 'opentest4j', '1.3.0'),
        ('org.apiguardian', 'apiguardian-api', '1.1.2'),
    ]]
    calls = []

    def run(argv):
        result = subprocess.run(list(map(str, argv)), cwd=repo, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        calls.append(dict(argv=list(map(str, argv)), exit=result.returncode, output=result.stdout))
        print(result.stdout, end='')
        (out / 'commands.json').write_text(json.dumps(calls, indent=2) + '\n')
        if result.returncode:
            raise SystemExit(result.returncode)

    # Read immutable original bodies through Git, exactly as the registered producer expects.
    spec = json.loads((repo / 'desktop/tools/upstream-cdn-transfer-adaptations.json').read_text())
    producer = out / 'producer'
    producer.mkdir(exist_ok=True)
    script = repo / 'desktop/tools/extract-upstream-cdn-transfer.py'
    (producer / script.name).write_bytes(script.read_bytes())
    baseline = '761af1ac927898804701a31a1103d1d110d8eb36'
    if args.before_fix:
        base_spec = json.loads(subprocess.check_output(
            ['git', 'show', baseline + ':desktop/tools/upstream-cdn-transfer-adaptations.json'], cwd=repo))
        for index, recipe in enumerate(spec['recipes']):
            if recipe['originalPath'].endswith('/CdnPlaybackDataSource.kt'):
                spec['recipes'][index] = next(r for r in base_spec['recipes'] if r['originalPath'] == recipe['originalPath'])
    (producer / 'upstream-cdn-transfer-adaptations.json').write_text(json.dumps(spec, indent=2) + '\n')
    originals = out / 'originals'
    originals.mkdir(exist_ok=True)
    # rev-parse in the producer must address this repository, including when out is external.
    (originals / '.git').write_text('gitdir: ' + subprocess.check_output(
        ['git', 'rev-parse', '--absolute-git-dir'], cwd=repo, text=True).strip() + '\n')
    for item in spec['recipes']:
        body = subprocess.check_output(['git', 'show', spec['officialCommit'] + ':' + item['originalPath']], cwd=repo)
        path = originals / item['originalPath']
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(body)
    generated = out / 'generated'
    run(['python3', producer / script.name, '--repo', originals,
         '--output', generated, '--audit', out / 'producer.json'])
    # M4d compiles Runtime from the merged root and excludes its generated legacy copy.
    # Replay the existing desktop patch producer's input, with Git checking the exact hunk
    # and its reverse; no build registration or original source is changed.
    runtime_path = Path('app/src/main/java/com/android/purebilibili/feature/plugin/CdnTransferRuntime.kt')
    runtime_original = repo / runtime_path
    runtime_patch = repo / 'desktop/upstream-core/patches' / (str(runtime_path) + '.patch')
    active_root = out / 'active-root'
    active_runtime = active_root / runtime_path
    active_runtime.parent.mkdir(parents=True, exist_ok=True)
    active_runtime.write_bytes(runtime_original.read_bytes())
    run(['git', 'apply', '--unsafe-paths', '--directory', active_root, runtime_patch])
    run(['git', 'apply', '--reverse', '--check', '--unsafe-paths', '--directory', active_root, runtime_patch])
    assert active_runtime.read_text().replace('CdnTransferClock.nowMs()', 'System.nanoTime() / 1_000_000') == runtime_original.read_text()
    sources = [generated / 'com/android/purebilibili/feature/plugin' / name for name in
               ['CdnTransferPolicy.kt', 'CdnPlaybackDataSource.kt']]
    sources.append(active_runtime)
    sources += [repo / 'desktop/src/main/kotlin/com/bilipai/desktop/player/cache/DesktopCdnPlaybackPlatform.kt',
                repo / 'desktop/src/test/kotlin/com/android/purebilibili/feature/plugin/DesktopCdnCooldownTest.kt']
    # These complete sources are already direct production inputs, not verification reimplementations.
    sys.path.insert(0, str(repo / 'desktop/tools'))
    from v025_source_paths import canonical_source
    for leaf in ['CdnRegionPolicy', 'CdnOptimizationPolicy']:
        sources.append(canonical_source(repo, 'app/src/main/java/com/android/purebilibili/feature/plugin/' + leaf + '.kt'))
    for leaf in ['PlaybackCdnPlugin', 'Plugin', 'PluginCapabilityManifest']:
        sources.append(repo / 'plugin-sdk/src/main/java/com/android/purebilibili/plugin/sdk' / (leaf + '.kt'))
    # Production's video-core producer retains this complete algorithm, with only its logger import bound.
    # Bind its four wall-clock reads here, outside production, so 60s/2min need no sleeps or product port.
    api_original = canonical_source(repo, 'app/src/main/java/com/android/purebilibili/core/cooldown/PlaybackCooldownManager.kt')
    api_body = api_original.read_text()
    clock_read = 'com.android.purebilibili.feature.plugin.CdnTransferClock.nowMs()'
    assert api_body.count('System.currentTimeMillis()') == 4
    api_generated = out / 'PlaybackCooldownManager.kt'
    api_generated.write_text(api_body.replace('System.currentTimeMillis()', clock_read))
    assert api_generated.read_text().replace(clock_read, 'System.currentTimeMillis()') == api_body
    logger = out / 'VerificationLogger.kt'
    logger.write_text('package com.android.purebilibili.core.util\n'
                      'internal object Logger { fun d(tag: String, message: String) {}\n'
                      'fun w(tag: String, message: String) {} }\n')
    sources += [api_generated, logger, repo / 'desktop/verification/ton-1345/PlaybackApiCooldownBoundaryTest.kt']
    launcher = out / 'RunCooldown.kt'
    launcher.write_text('''import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.launcher.listeners.SummaryGeneratingListener
import java.io.PrintWriter
fun main() {
    val listener = SummaryGeneratingListener()
    val launcher = LauncherFactory.create()
    launcher.registerTestExecutionListeners(listener)
    launcher.execute(LauncherDiscoveryRequestBuilder.request().selectors(
        selectClass("com.android.purebilibili.feature.plugin.DesktopCdnCooldownTest"),
        selectClass("com.android.purebilibili.feature.plugin.PlaybackApiCooldownBoundaryTest")).build())
    listener.summary.printTo(PrintWriter(System.out, true))
    listener.summary.printFailuresTo(PrintWriter(System.out, true))
    check(listener.summary.testsFoundCount > 0)
    if (listener.summary.totalFailureCount > 0) kotlin.system.exitProcess(1)
}
''')
    sources.append(launcher)
    all_cp = ':'.join(map(str, cp))
    compiler = jar('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.4.0')
    compiler_support = [jar('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
                        jar('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.4.0')]
    classes = out / 'classes'
    run(['java', '-Xmx512m', '-cp', ':'.join(map(str, [compiler, *compiler_support, *cp])),
         'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
         '-jvm-target', '17', '-classpath', all_cp,
         '-d', classes, *sources])
    receipt = {'sources': {str(p.relative_to(repo)) if p.is_relative_to(repo) else p.name:
                          hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},
               'beforeFix': args.before_fix, 'baseline': baseline,
               'activeRuntime': {'originalSha256': hashlib.sha256(runtime_original.read_bytes()).hexdigest(),
                                 'patchSha256': hashlib.sha256(runtime_patch.read_bytes()).hexdigest(),
                                 'outputSha256': hashlib.sha256(active_runtime.read_bytes()).hexdigest(),
                                 'inverseOriginalBodyExact': True},
               'apiClockBinding': {'originalSha256': hashlib.sha256(api_body.encode()).hexdigest(), 'reads': 4, 'inverseOriginalBodyExact': True},
               'producerScriptSha256': hashlib.sha256(script.read_bytes()).hexdigest(),
               'recipeSha256': hashlib.sha256((producer / 'upstream-cdn-transfer-adaptations.json').read_bytes()).hexdigest(),
               'jars': {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in [compiler, *compiler_support, *cp]}}
    (out / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    run(['java', '-cp', str(classes) + ':' + all_cp, 'RunCooldownKt'])


if __name__ == '__main__':
    main()
