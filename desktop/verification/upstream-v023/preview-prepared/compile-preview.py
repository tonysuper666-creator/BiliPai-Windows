"""Prepared stable preview only, compiled against immutable actual Main04.
No Gradle, runtime, HTTP, native window, Main source or registry writes.
"""
from pathlib import Path
import hashlib, importlib.util, json, subprocess, sys, zipfile
sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
ROOT = next(p for p in HERE.parents if (p / '.git').exists())
SNAPSHOT = ROOT / 'desktop/.local/image-save-main-integration/main-product-snapshot-04'
COMP = 'app/src/main/java/com/android/purebilibili/feature/dynamic/components/'

def safe(path):
    value = str(Path(path).absolute())
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\' + value)

def sha(path):
    return hashlib.sha256(safe(path).read_bytes()).hexdigest()

def write(path, value):
    safe(path.parent).mkdir(parents=True, exist_ok=True)
    safe(path).write_text(value, encoding='utf-8', newline='\n')

def save(path, value):
    write(path, json.dumps(value, ensure_ascii=False, indent=2) + '\n')

def class_entries(path):
    with zipfile.ZipFile(safe(path)) as archive:
        return {name for name in archive.namelist() if name.endswith('.class')}

def main():
    attempt = sys.argv[1] if len(sys.argv) > 1 else '01'
    output = HERE / ('compile-' + attempt)
    assert not safe(output).exists(), 'Attempt paths are immutable after creation'
    safe(output).mkdir()
    manifest = SNAPSHOT / 'manifest.json'
    cp_json = SNAPSHOT / 'ordered-runtime-cp.json'
    assert sha(manifest) == '7bc5ac8875dadf38510120db6619bd6e748e7f790a47afc11374e14114f25865'
    assert sha(cp_json) == '7a3141310fa355d8618c5bc2be90f36a2c13c8ed36f2d981bf4a14c2232165e2'
    rows = json.loads(safe(cp_json).read_text(encoding='utf-8'))
    assert len(rows) == 92
    before = [dict(path=row['path'], expected=row['sha256Bytes'], actual=sha(row['path'])) for row in rows]
    assert all(row['actual'] == row['expected'] for row in before)
    save(output / 'runtime-pins-before.json', before)
    safe(output / 'input-ordered-runtime-cp.json').write_bytes(safe(cp_json).read_bytes())
    safe(output / 'input-main04-manifest.json').write_bytes(safe(manifest).read_bytes())
    helper = ROOT / 'desktop/.local/source9-appearance/compile-miuix.py'
    spec = importlib.util.spec_from_file_location('readonly_existing_compiler', helper)
    compiler = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(compiler)
    originals = [HERE / 'direct-original' / (COMP + name + '.kt') for name in [
        'ImagePreviewSourceAnchor', 'ImagePreviewTransitionPolicy', 'ImagePreviewDecodePolicy', 'ZoomableImage']]
    originals += sorted((HERE / 'generated').rglob('*.kt'))
    assert len(originals) == 6
    sources = []
    source_rows = []
    for source in originals:
        copy = output / 'source-inputs' / source.name
        safe(copy.parent).mkdir(parents=True, exist_ok=True)
        safe(copy).write_bytes(safe(source).read_bytes())
        source_rows.append(dict(preparedSource=str(source), compileInput=str(copy), sha256Bytes=sha(copy)))
        sources.append(copy)
    main_kotlin = SNAPSHOT / 'main-kotlin.jar'
    target = output / 'prepared-stable-preview.jar'
    args = ['-no-stdlib', '-no-reflect', '-jvm-target', '21', '-cp',
            ';'.join(row['path'] for row in rows), '-Xfriend-paths=' + str(main_kotlin),
            '-Xplugin=' + str(compiler.PLUGIN), '-module-name', 'prepared_stable_image_preview',
            '-d', str(target)] + list(map(str, sources))
    write(output / 'compiler.args', '\n'.join('"' + str(arg).replace('\\', '/') + '"' for arg in args) + '\n')
    tools = [compiler.JAVA, *compiler.COMPILER, compiler.PLUGIN]
    save(output / 'compiler-inputs.json', [dict(path=str(path), sha256Bytes=sha(path)) for path in tools])
    process = subprocess.run([str(compiler.JAVA), '-Xmx3g', '-cp', ';'.join(map(str, compiler.COMPILER)),
        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '@' + str(output / 'compiler.args')],
        capture_output=True, text=True, encoding='utf-8', timeout=180)
    write(output / 'compiler.log', process.stdout + process.stderr)
    after = [dict(path=row['path'], expected=row['sha256Bytes'], actual=sha(row['path'])) for row in rows]
    assert all(row['actual'] == row['expected'] for row in after)
    save(output / 'runtime-pins-after.json', after)
    result = dict(schema='prepared-stable-preview-narrow-compile-v1', status='PASS' if process.returncode == 0 else 'FAIL',
        exitCode=process.returncode, actualSnapshotManifestSha256Bytes=sha(manifest),
        orderedRuntimeCpSha256Bytes=sha(cp_json), runtimeEntries=len(rows), sourceInputs=source_rows,
        preparedOnly=True, originalTag='v0.2.3', originalCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',
        compiledAgainst='Actual immutable Main04 alpha.9 ABI; stable callers require coordinated recompilation.',
        noMainEdits=True, noRegistryEdits=True, noGradle=True, noHTTP=True, noRuntime=True, noHWND=True)
    if process.returncode == 0:
        own = class_entries(target)
        baseline = set().union(*(class_entries(row['path']) for row in rows))
        overlap = sorted(own & baseline)
        allowed = 'com/android/purebilibili/feature/dynamic/components/'
        assert all(name.startswith(allowed) for name in own)
        result.update(artifactSha256Bytes=sha(target), ownClassCount=len(own),
            declaredSameOwnerClassOverrides=overlap, newOriginalOrCompilerClasses=sorted(own - baseline),
            productAuthorityOverrides=['Existing original preview renderer/URL policy and four original preview helpers only.'],
            noStoreOpsAssetsRepositoryOverrides=True)
    save(output / 'compile-result.json', result)
    print(json.dumps(dict(status=result['status'], sources=len(sources), output=str(output),
        resultSha256Bytes=sha(output / 'compile-result.json'))))
    print((process.stdout + process.stderr)[-10000:])
    return process.returncode

if __name__ == '__main__':
    raise SystemExit(main())
