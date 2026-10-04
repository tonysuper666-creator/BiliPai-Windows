"""Build the reviewed Windows share bridge fresh and stage its trusted asset.

There is no cached-DLL admission path. The source approval is separate from the
fresh output, and a runtime constant is emitted only for the approved digest.
"""
from pathlib import Path
import argparse, hashlib, json, os, subprocess, sys, uuid


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def atomic_write(path, data):
    if os.name == 'nt':
        value = os.path.abspath(os.fspath(path))
        if not value.startswith('\\\\?\\'):
            value = ('\\\\?\\UNC\\' + value[2:]) if value.startswith('\\\\') else ('\\\\?\\' + value)
        path = Path(value)
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.is_file() and path.read_bytes() == data:
        return
    temporary = path.with_name(path.name + '.tmp-' + str(uuid.uuid4()))
    temporary.write_bytes(data)
    os.replace(temporary, path)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--repo', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--asset-dir', type=Path, required=True)
    p.add_argument('--vc-root', type=Path)
    p.add_argument('--sdk-root', type=Path, default=Path(os.environ.get('BILIPAI_NATIVE_SHARE_SDK_ROOT', 'C:/Program Files (x86)/Windows Kits/10')))
    args = p.parse_args()
    if sys.platform != 'win32':
        raise RuntimeError('The Windows diagnostic share bridge requires Windows x64.')
    repo = args.repo.resolve(strict=True)
    source = repo / 'desktop/native/diagnostic-share/DesktopDiagnosticShare.cpp'
    approved_path = source.with_name('approved-development-build.json')
    approval = json.loads(approved_path.read_bytes())
    compiler_script = repo / 'desktop/tools/compile-native-diagnostic-share.py'
    assert sha(source) == approval['sourceSha256Bytes'], 'Native source differs from the reviewed build.'
    assert sha(compiler_script) == approval['controlledCompilerScriptSha256Bytes'], 'Compiler driver changed.'
    if args.vc_root is not None:
        vc = args.vc_root.resolve(strict=True)
    elif os.environ.get('BILIPAI_NATIVE_SHARE_VC_ROOT'):
        vc = Path(os.environ['BILIPAI_NATIVE_SHARE_VC_ROOT']).resolve(strict=True)
    else:
        vswhere = Path('C:/Program Files (x86)/Microsoft Visual Studio/Installer/vswhere.exe')
        instances = json.loads(subprocess.check_output([str(vswhere), '-all', '-products', '*', '-format', 'json', '-utf8']).decode('utf-8-sig'))
        instances.sort(key=lambda row: row.get('productId') != 'Microsoft.VisualStudio.Product.Community')
        candidates = [Path(row['installationPath']) / 'VC/Tools/MSVC' / approval['msvcVersion'] for row in instances if row.get('isComplete')]
        vc = next((path for path in candidates if (path / 'bin/Hostx64/x64/cl.exe').is_file()), None)
        if vc is None:
            raise RuntimeError('Install the reviewed MSVC x64 tools and Windows SDK, or supply BILIPAI_NATIVE_SHARE_VC_ROOT.')
    sdk = args.sdk_root.resolve(strict=True)
    roots = {'vc': vc, 'sdk': sdk}
    def resolve_pin(row):
        root, relative = row['path'].split('/', 1)
        target = (roots[root] / relative).resolve(strict=True)
        assert target.is_relative_to(roots[root]), row['path']
        actual_bytes, actual_sha = target.stat().st_size, sha(target)
        assert actual_bytes == row['bytes'] and actual_sha == row['sha256Bytes'], json.dumps({
            'path': row['path'], 'expectedBytes': row['bytes'], 'actualBytes': actual_bytes,
            'expectedSha256': row['sha256Bytes'], 'actualSha256': actual_sha,
        })
        return target
    for key in ['transitiveHeaders', 'searchedLibrariesConservativePins', 'compilerBinDirectoryConservativePins']:
        for row in approval[key]:
            resolve_pin(row)
    output = args.output.absolute()
    assert output.is_relative_to(repo / 'desktop/build'), 'Output must remain in the desktop build directory.'
    asset_dir = args.asset_dir.absolute()
    assert asset_dir == repo / 'desktop/resources/common/native/windows-x64'
    build = output / 'builds' / str(uuid.uuid4())
    # Always build fresh: new or shadowing headers/libraries cannot hide behind a
    # prior transitive-file cache. The compiler records the actual resolved graph.
    subprocess.run([sys.executable, str(compiler_script), '--source', str(source), '--output', str(build),
        '--vc-root', str(vc), '--sdk-root', str(sdk), '--sdk-version', approval['sdkVersion']], check=True)
    graph = json.loads((build / 'producer-input-graph.json').read_bytes())
    assert graph['command'][1:10] == approval['flags']
    for key in ['transitiveHeaders', 'searchedLibrariesConservativePins', 'compilerBinDirectoryConservativePins']:
        expected = {str(resolve_pin(row)).casefold(): row['sha256Bytes'] for row in approval[key]}
        actual = {str(Path(row['path']).resolve(strict=True)).casefold(): row['sha256Bytes'] for row in graph[key]}
        assert expected == actual, 'Resolved native build graph changed: ' + key
    dll = build / 'bilipai-diagnostic-share.dll'
    assert sha(dll) == approval['dllSha256Bytes'], 'Fresh DLL differs from the reviewed producer output.'
    atomic_write(asset_dir / dll.name, dll.read_bytes())
    kotlin = ('package com.bilipai.desktop.diagnostics\n\n'
        '/** Trusted reviewed source pin; never calculated from an installed DLL. */\n'
        'internal object DesktopNativeDiagnosticShareAssetHash {\n'
        '    const val sha256: String = "' + approval['dllSha256Bytes'] + '"\n}\n').encode()
    atomic_write(output / 'kotlin/com/bilipai/desktop/diagnostics/DesktopNativeDiagnosticShareAssetHash.kt', kotlin)
    record = dict(passed=True, freshBuild=str(build), approvedSourceManifestSha256Bytes=sha(approved_path),
        sourceSha256Bytes=sha(source), compilerDriverSha256Bytes=sha(compiler_script),
        expectedDllSha256Bytes=approval['dllSha256Bytes'], stagedDllSha256Bytes=sha(asset_dir / dll.name),
        actualBuildGraph=str(build / 'producer-input-graph.json'), actualBuildGraphSha256Bytes=sha(build / 'producer-input-graph.json'),
        cachedOutputTrusted=False, freshCompilationOnEveryInvocation=True,
        resolvedGraphMatchesReviewedBuild=True, publicReleaseAuthorizationVerified=False)
    atomic_write(output / 'producer-receipt.json', (json.dumps(record, indent=2) + '\n').encode())
    print(json.dumps(record))


if __name__ == '__main__':
    main()
