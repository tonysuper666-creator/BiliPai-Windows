#!/usr/bin/env python3
"""Run the unchanged production Kotlin patch function and capture whole-body proofs.

Requires a cached Kotlin 2.0.21 compiler; never downloads or invokes Gradle.
Inputs are the raw outputs captured by the real producer CLIs, before and after
moving TON-1343 out of the immutable historical producer.
"""
from pathlib import Path
import argparse
import difflib
import hashlib
import json
import os
import subprocess


def digest(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    args = parser.parse_args()
    repo, evidence = args.repo.resolve(), args.evidence.resolve()
    out = evidence / 'transition'
    out.mkdir(parents=True, exist_ok=True)
    records = []

    def run(name, argv, expected=0):
        result = subprocess.run(argv, cwd=repo, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        (out / (name + '.log')).write_bytes(result.stdout)
        records.append(dict(name=name, argv=argv, cwd=str(repo), exit_code=result.returncode))
        (out / 'commands.json').write_text(json.dumps(records, indent=2) + '\n')
        assert (result.returncode == 0) == (expected == 0), (name, result.returncode, result.stdout.decode())

    # Use the actual function's exact bytes, including its strict unique-match
    # requirement. Only the GradleException class is supplied outside Gradle.
    build = (repo / 'desktop/build.gradle.kts').read_text()
    start = build.index('fun applyUnifiedPatch(')
    function = build[start:build.index('\n}\n', start) + 3]
    source = out / 'ProductionPatch.kt'
    source.write_text('import java.io.File\nclass GradleException(message: String): RuntimeException(message)\n' +
                      function + '\nfun main(args: Array<String>) {\n' +
                      '  require(args.size == 3)\n' +
                      '  File(args[2]).writeText(applyUnifiedPatch(File(args[0]).readText(),\n' +
                      '      File(args[1]).readText(), args[0]))\n}\n')
    cache = Path(os.environ.get('GRADLE_USER_HOME', str(Path.home() / '.gradle'))) / 'caches/modules-2/files-2.1'
    dependencies = [('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.0.21'),
                    ('org.jetbrains.kotlin', 'kotlin-stdlib', '2.0.21'),
                    ('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.0.21'),
                    ('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
                    ('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330'),
                    ('org.jetbrains', 'annotations', '13.0')]
    jars = []
    for group, artifact, version in dependencies:
        found = sorted((cache / group / artifact / version).glob('*/*.jar'))
        assert len(found) == 1, ('Required cached dependency unavailable', group, artifact, version)
        jars += found
    # The compiler itself needs coroutines. Use the cached 1.6.4 release it declares.
    coroutines = sorted((cache / 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.6.4').glob('*/*.jar'))
    if coroutines:
        jars += coroutines
    cp = os.pathsep.join(map(str, jars))
    compiled = out / 'production-patch.jar'
    run('compile-production-function', ['java', '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
         '-no-stdlib', '-no-reflect', '-classpath', cp, '-jvm-target', '17', '-d', str(compiled), str(source)])
    runtime = ['java', '-cp', str(compiled) + os.pathsep + cp, 'ProductionPatchKt']
    proofs = []
    prefix = 'desktop/upstream-core/transition/patches/original-bangumi-player'
    for leaf in ('DesktopOriginalBangumiPlayerViewModel.kt', 'DesktopOriginalBangumiResumePolicy.kt'):
        relative = 'com/android/purebilibili/feature/bangumi/' + leaf
        raw = evidence / 'after/producers/bangumi-player' / relative
        reference = evidence / 'before/producers/bangumi-player' / relative
        patch = repo / prefix / (relative + '.patch')
        final = out / leaf
        run(leaf + '-apply', runtime + [str(raw), str(patch), str(final)])
        # The moved switch block keeps the original blank lines instead of the
        # former replacement's accidental joined trailing spaces. Reject any
        # change to a nonblank line, its indentation or its text.
        nonblank = lambda text: [line.rstrip() for line in text.splitlines() if line.strip()]
        assert nonblank(final.read_text()) == nonblank(reference.read_text()), ('Playback code changed', leaf)
        formatting = ''.join(difflib.unified_diff(reference.read_text().splitlines(True),
            final.read_text().splitlines(True), fromfile='previous-production', tofile='transition-output'))
        (out / (leaf + '.format-only.diff')).write_text(formatting)
        reverse = out / (leaf + '.reverse.patch')
        reverse.write_text(''.join(difflib.unified_diff(final.read_text().splitlines(True),
            raw.read_text().splitlines(True), fromfile='a/' + relative, tofile='b/' + relative)))
        restored = out / (leaf + '.restored')
        run(leaf + '-restore', runtime + [str(final), str(reverse), str(restored)])
        assert restored.read_bytes() == raw.read_bytes(), ('Whole inverse failed', leaf)
        replay = out / (leaf + '.replayed')
        run(leaf + '-replay', runtime + [str(restored), str(patch), str(replay)])
        assert replay.read_bytes() == final.read_bytes(), ('Replay failed', leaf)
        bad_patch = out / (leaf + '.bad.patch')
        lines = patch.read_text().splitlines(True)
        first = next(i for i, line in enumerate(lines) if line.startswith('-') and not line.startswith('---'))
        lines[first] = lines[first].rstrip('\n') + ' INVALID-ANCHOR\n'
        bad_patch.write_text(''.join(lines))
        run(leaf + '-bad-anchor', runtime + [str(raw), str(bad_patch), str(out / 'rejected.kt')], expected=1)
        proofs.append(dict(output=relative, rawSHA256=digest(raw.read_bytes()),
            patchedSHA256=digest(final.read_bytes()), previousProductionSHA256=digest(reference.read_bytes()),
            restoredSHA256=digest(restored.read_bytes()), patchSHA256=digest(patch.read_bytes()),
            wholeInverse=True, replayIdentical=True,
            previousProductionIdentical=final.read_bytes() == reference.read_bytes(),
            previousNonblankLinesIdentical=True, formatOnlyDiff=leaf + '.format-only.diff'))
    run('missing-input', runtime + [str(out / 'missing.kt'), str(patch), str(out / 'rejected.kt')], expected=1)
    (out / 'proofs.json').write_text(json.dumps(dict(productionFunctionSHA256=digest(function.encode()),
                                                  outputs=proofs), indent=2) + '\n')
    print('Production Kotlin patch function: 2 outputs preserve every nonblank line; inverse/replay byte-identical; 3 negatives rejected')


if __name__ == '__main__':
    main()
