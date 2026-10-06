"""Bounded Windows rendering diagnosis; never builds or publishes the application.

Each Java child owns its own windows and mpv handle. A failed observation remains
a failed case; all cases still run so one failure cannot hide another path.
Only fixed local-fixture images, measurements and bounded logs are collected.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import urllib.request

JNA_SHA = 'b3a9408e7c51e08ef0e3bfcc08f443f6ec0f6191ba8cd7c18d53d2b22e5bdbc0'
ARCHIVE_SHA = 'fac135c68a35b7639e39d72c0c365104edbaebdea39a0dfdd8c36e8c8e80faef'
DLL_SHA = '673e6397920ab64a9c5b3a618f7f16d38854efe72b58665f1f84e4e873b763a4'
JNA_URL = 'https://repo.maven.apache.org/maven2/net/java/dev/jna/jna/5.17.0/jna-5.17.0.jar'
ARCHIVE_URLS = [
    'https://github.com/tonysuper666-creator/BiliPai-Windows/releases/download/runtime-mpv-20260903/mpv-dev-20260903-x64.7z',
    'https://github.com/shinchiro/mpv-winbuild-cmake/releases/download/20260903/mpv-dev-x86_64-20260903-git-69e63f425a.7z',
]
CASES = ('awt-alpha-only', 'mpv-default-flip', 'mpv-bitblt', 'mpv-adaptive')
SURFACE_DEBUG_CASES = ('mpv-default-flip', 'mpv-bitblt', 'mpv-default-debug', 'mpv-default-flip-panscan1',
                       'mpv-default-flip-panscan0', 'mpv-default-flip-panscan1-clear', 'mpv-default-flip-zoom-equivalent')
LOAD_ORDER_CASES = ('mpv-default-flip-panscan1-immediate', 'mpv-default-flip-panscan1',
                    'mpv-default-flip-panscan1-immediate-dumb')
SHADER_CASES = ('shader-clear-default-retained', 'shader-clear-default-seek',
                'shader-clear-nodumb-retained', 'shader-clear-nodumb-seek')
SHADER_INPUT_PINS = {
    'desktop/tools/extract-upstream-plugins.py': 'ea953855350a7077dca7295e16a9b7819ee838de2f8e01f3481a6aab20864685',
    'app/src/main/java/com/android/purebilibili/feature/anime4k/Anime4KConfig.kt': '2bdfd821d4f0d03f1a7dad1c06779df254d7cfc5763c36e917ea981f7dd712b7',
    'app/src/main/java/com/android/purebilibili/feature/anime4k/gl/Anime4KShaderRepository.kt': '4bda89a5f97a455931ec77c225ea0a7a2f5a725f58ca3655314c2679a9e4f89b',
}
SHADER_PINS = {
    'Anime4K_AutoDownscalePre_x2.glsl': '8c58291740146bd766a4d73f132775a797fe80f7d07919b5d767e27a5dc85656',
    'Anime4K_AutoDownscalePre_x4.glsl': '5af62d8cd844916dc1126613e13bad3beab195787f93a71200b47c6ec78f2e41',
    'Anime4K_Clamp_Highlights.glsl': '6dafe6d4ccaed8f1675d1b5b13e2d1a981f1f65849f54ea71b897f2f439ecfed',
    'Anime4K_Restore_CNN_M.glsl': '67ea3ed26539e8de3b7d307688535d2ff17e8d147e11dda0247da7770dbecf41',
    'Anime4K_Restore_CNN_S.glsl': '97c24dc370ab300c108bfaa09db7f175aeff343674842c299cf3940a3d330427',
    'Anime4K_Restore_CNN_VL.glsl': '35036722733305cd4d4e57660b883bbe2569ba2914033c254327107d7b77e35e',
    'Anime4K_Upscale_CNN_x2_M.glsl': '716e02098a68f0d648761f2b96b4dd139e1cb09b174bb369fca3aa34328fff7e',
    'Anime4K_Upscale_CNN_x2_S.glsl': '4c53ec2e287908f7ee7bcb266b0170421626d663576468b7d7dafc62962649a4',
    'Anime4K_Upscale_CNN_x2_VL.glsl': '5638fe31c37c151a3443fea3451a3ef91af073f4dbb9615f6c0d1e29db11493d',
}


def digest(path):
    with Path(path).open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def save(path, value):
    with path.open('x', encoding='utf-8') as stream:
        json.dump(value, stream, indent=2, ensure_ascii=True)
        stream.write('\n')


def verified(path, size, sha):
    path = Path(path).resolve(strict=True)
    if not path.is_file() or path.stat().st_size != size or digest(path) != sha:
        raise ValueError('Fixed dependency bytes differ: ' + path.name)
    return path


def dependency(local, urls, destination, size, sha, deadline):
    if local:
        source = verified(local, size, sha)
        shutil.copyfile(source, destination)
        verified(destination, size, sha)
        return {'kind': 'verified-local-copy', 'sha256': sha, 'bytes': size}
    failures = []
    for index, url in enumerate(urls):
        attempt = destination.with_name(destination.name + '.attempt-' + str(index))
        try:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError('Dependency preparation deadline expired')
            request = urllib.request.Request(url, headers={'User-Agent': 'BiliPai-render-diagnostic'})
            with urllib.request.urlopen(request, timeout=min(45, remaining)) as response, attempt.open('xb') as target:
                total = 0
                while block := response.read(1024 * 1024):
                    total += len(block)
                    if total > size or time.monotonic() >= deadline:
                        raise ValueError('Dependency size/time limit exceeded')
                    target.write(block)
            verified(attempt, size, sha)
            attempt.rename(destination)
            return {'kind': 'fixed-download', 'url': url, 'sha256': sha, 'bytes': size}
        except Exception as error:
            # Keep partial attempts private. Never print signed redirect URLs.
            failures.append(type(error).__name__)
    raise RuntimeError('No verified download: ' + destination.name + ' (' + ', '.join(failures) + ')')


def environment(java_home, data):
    env = os.environ.copy()
    for key in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'BILIPAI_MPV_PATH',
                'BILIPAI_FFMPEG_PATH', 'BILIPAI_FFPROBE_PATH', 'SKIKO_RENDER_API', 'GH_TOKEN', 'GITHUB_TOKEN'):
        env.pop(key, None)
    env.update(JAVA_HOME=str(java_home), LOCALAPPDATA=str(data))
    env['PATH'] = str(java_home / 'bin') + os.pathsep + env.get('PATH', '')
    return env


def command_info(executable, args, env, cwd):
    result = subprocess.run([str(executable), *args], cwd=cwd, env=env, capture_output=True,
                            text=True, encoding='utf-8', errors='replace', timeout=15, check=True)
    return {'path': str(executable), 'sha256': digest(executable),
            'versionOutput': (result.stdout + result.stderr)[:4096]}


def prepare_shaders(repo, destination):
    """Read only fixed sole-producer inputs; no generation or network source retrieval."""
    source_rows = []
    for relative, expected in SHADER_INPUT_PINS.items():
        source = repo / relative
        if source.is_symlink() or not source.resolve(strict=True).is_relative_to(repo):
            raise ValueError('Shader source escapes checkout')
        raw = source.read_bytes()
        normalized = raw.replace(b'\r\n', b'\n')
        if hashlib.sha256(normalized).hexdigest() != expected:
            raise ValueError('Fixed shader recipe/source differs: ' + relative)
        source_rows.append({'path': relative, 'sha256': expected, 'rawSha256': hashlib.sha256(raw).hexdigest(), 'normalization': 'LF'})
    # Validate order against the fixed original repository body, not generated build output.
    config = (repo / 'app/src/main/java/com/android/purebilibili/feature/anime4k/Anime4KConfig.kt').read_text(encoding='utf-8')
    preset_chains = dict(re.findall(r'Anime4KPreset\.(FAST|QUALITY) -> Anime4KRenderProfile\(\s*shaderChain\s*=\s*Anime4KShaderChain\.(\w+)\s*\)', config))
    if preset_chains != {'FAST': 'KAZUMI_EFFICIENCY', 'QUALITY': 'KAZUMI_QUALITY'}:
        raise ValueError('Fixed original preset-to-chain mapping differs')
    original = (repo / 'app/src/main/java/com/android/purebilibili/feature/anime4k/gl/Anime4KShaderRepository.kt').read_text(encoding='utf-8')
    chains = {}
    for name in ('KAZUMI_EFFICIENCY', 'KAZUMI_QUALITY'):
        match = re.search(r'Anime4KShaderChain\.' + name + r' -> listOf\((.*?)\)', original, re.S)
        if not match:
            raise ValueError('Original shader chain missing')
        chains[name] = re.findall(r'"(Anime4K_[^"\r\n]+\.glsl)"', match.group(1))
    java_text = Path(__file__).with_name('AwtMpvProbe.java').read_text(encoding='utf-8')
    for java_name, name in (('FAST', 'KAZUMI_EFFICIENCY'), ('QUALITY', 'KAZUMI_QUALITY')):
        match = re.search(r'List<String> ' + java_name + r' = List\.of\((.*?)\);', java_text, re.S)
        if not match or re.findall(r'"([^"\r\n]+\.glsl)"', match.group(1)) != chains[name]:
            raise ValueError('Diagnostic preset order differs from fixed original')
    destination.mkdir()
    assets = []
    for name, expected in SHADER_PINS.items():
        source = repo / 'app/src/main/assets/anime4k' / name
        if source.is_symlink() or not source.resolve(strict=True).is_relative_to(repo) or source.stat().st_size > 1_048_576:
            raise ValueError('Original shader path/size rejected')
        raw = source.read_bytes().replace(b'\r\n', b'\n')
        if hashlib.sha256(raw).hexdigest() != expected or b'//!HOOK ' not in raw or b'//!DESC ' not in raw:
            raise ValueError('Fixed original shader bytes differ: ' + name)
        (destination / name).write_bytes(raw)
        assets.append({'file': name, 'sha256': expected, 'bytes': len(raw)})
    return {'sourceInputs': source_rows, 'assets': assets, 'originalPresetChains': preset_chains, 'originalChains': chains}



def read_reported_runtime_modules(observation, env, cwd):
    """Bounded file metadata for actual own-JVM module paths, never a module-load/device probe."""
    allowed = {'d3d10warp.dll', 'd3d11.dll', 'dxgi.dll', 'd3d11sdklayers.dll', 'dxgidebug.dll'}
    rows = []
    windows = Path(next(value for key, value in env.items() if key.casefold() == 'systemroot')).resolve(strict=True)
    for module in observation.get('modules', []):
        name = module['name']
        assert name in allowed and type(module['loaded']) is bool
        row = dict(name=name, loaded=module['loaded']); rows.append(row)
        if not module['loaded'] or not module.get('path'):
            row['metadataStatus'] = 'No observed loaded path'; continue
        path = Path(module['path'])
        if (not path.is_absolute() or path.name.lower() != name or path.is_symlink() or
                not path.resolve(strict=True).is_relative_to(windows)):
            row['metadataStatus'] = 'Observed module path outside approved Windows directory'; continue
        path = path.resolve(strict=True)
        assert path.is_file() and 0 < path.stat().st_size <= 128 * 1024 * 1024
        row.update(path=str(path), bytes=path.stat().st_size, sha256=digest(path))
    script = r"""[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$request = [Console]::In.ReadToEnd() | ConvertFrom-Json
$rows = @($request.paths | ForEach-Object {
    $file = Get-Item -LiteralPath $_ -ErrorAction Stop
    @{path=$file.FullName; fileVersion=$file.VersionInfo.FileVersion; productVersion=$file.VersionInfo.ProductVersion}
})
$os = Get-ItemProperty -LiteralPath 'Registry::HKEY_LOCAL_MACHINE\SOFTWARE\Microsoft\Windows NT\CurrentVersion' -ErrorAction Stop
@{modules=$rows; windowsRegistryBuild=@{currentBuildNumber=$os.CurrentBuildNumber; ubr=$os.UBR; displayVersion=$os.DisplayVersion}} | ConvertTo-Json -Depth 5 -Compress
"""
    result = subprocess.run(['powershell.exe', '-NoProfile', '-NonInteractive', '-Command', script],
        input=json.dumps({'paths': [r['path'] for r in rows if 'path' in r]}), cwd=cwd, env=env,
        text=True, encoding='utf-8', errors='strict', capture_output=True, check=True, timeout=10,
        creationflags=subprocess.CREATE_NO_WINDOW)
    assert len(result.stdout) <= 32768
    details = json.loads(result.stdout)
    versions = {r['path'].casefold(): r for r in details['modules']}
    for row in rows:
        if 'path' in row:
            version = versions[row['path'].casefold()]
            row.update(fileVersion=version['fileVersion'], productVersion=version['productVersion'], metadataStatus='Measured file metadata')
    return dict(modules=rows, windowsRegistryBuild=details['windowsRegistryBuild'],
        scope='Actual loaded paths sampled at worker retirement; filesystem versions/hashes read after exit; registry build metadata; not D3D device/debug ACK')


def run_case(case, java, classes, jna, dll, output, java_home, shaders=None, debug_observations=False, load_order_observations=False):
    work = output / (case + '-work')
    work.mkdir()
    data = work / 'private-data'
    data.mkdir()
    case_output = output / case
    command = [str(java), '-cp', str(classes) + os.pathsep + str(jna), 'AwtMpvProbe',
               '--case', case, '--output', str(case_output)]
    if case != 'awt-alpha-only':
        command += ['--mpv', str(dll)]
    if debug_observations:
        assert case in SURFACE_DEBUG_CASES + LOAD_ORDER_CASES
        command += ['--surface-debug-observations', 'true']
    if load_order_observations:
        assert debug_observations and case in LOAD_ORDER_CASES
        command += ['--load-order-observations', 'true']
    if case in SHADER_CASES:
        command += ['--shader-root', str(shaders)]
    started = time.monotonic()
    forced = False
    cleanup_errors = []
    process_log = work / 'process.log' if case in SHADER_CASES else output / (case + '-process.log')
    with process_log.open('xb') as log:
        process = subprocess.Popen(command, cwd=work, env=environment(java_home, data),
                                   stdout=log, stderr=subprocess.STDOUT,
                                   creationflags=subprocess.CREATE_NO_WINDOW)
        try:
            code = process.wait(timeout=100 if case in SHADER_CASES else 35 if case == 'awt-alpha-only' else 40)
        except subprocess.TimeoutExpired:
            forced = True
        except Exception as error:
            cleanup_errors.append('wait: ' + type(error).__name__)
            forced = True
        finally:
            # A failed cleanup must not let another GUI case cover this process.
            # These are only the exact direct JVM handles started above.
            for stop in (process.terminate, process.kill):
                if process.poll() is not None:
                    break
                forced = True
                try:
                    stop()
                    process.wait(timeout=3)
                except Exception as error:
                    cleanup_errors.append(type(error).__name__)
            code = process.poll()
    row = {'case': case, 'processId': process.pid, 'exitCode': code,
           'elapsedSeconds': round(time.monotonic() - started, 3),
           'forcedTermination': forced, 'processStillRunning': code is None,
           'cleanupErrors': cleanup_errors, 'passed': False, 'diagnosticOnly': True}
    receipt = case_output / 'result.json'
    if receipt.is_file():
        try:
            result = json.loads(receipt.read_text(encoding='utf-8'))
            assert result['case'] == case and type(result['passed']) is bool
            assert result['diagnosticOnly'] is True and result['exitCode'] == code
            assert result['passed'] == (code == 0)
            if code == 0:
                assert result['cleanupGraceful'] is True and result['windowDisposed'] is True
                if case != 'awt-alpha-only':
                    assert result['nativeClosed'] is True
            row.update(receipt=str(receipt), receiptSha256=digest(receipt),
                       passed=not forced and code == 0, result=result)
            if debug_observations:
                try:
                    row['runtimeFileObservations'] = read_reported_runtime_modules(result['loadedRuntimeModules'], environment(java_home, data), work)
                except Exception as error:
                    row['runtimeObservationError'] = type(error).__name__ + ': ' + str(error)[:500]
        except Exception as error:
            row['receiptError'] = type(error).__name__
    else:
        row['receiptError'] = 'No normal case receipt; retain timeout/process evidence'
    return row


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--mpv-archive', type=Path)
    parser.add_argument('--jna-jar', type=Path)
    parser.add_argument('--suite', choices=('surface', 'shader-clear', 'surface-debug', 'surface-load-order'), default='surface')
    args = parser.parse_args()
    sys.stdout.reconfigure(encoding='utf-8')
    if os.name != 'nt':
        parser.error('This diagnostic requires Windows')
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    started = time.monotonic()
    cases = (LOAD_ORDER_CASES if args.suite == 'surface-load-order' else SHADER_CASES if args.suite == 'shader-clear'
             else SURFACE_DEBUG_CASES if args.suite == 'surface-debug' else CASES)
    summary = {'diagnosticOnly': True, 'productReleaseGatePassed': False, 'suite': args.suite, 'cases': [], 'passed': False}
    try:
        java_home = args.java_home.resolve(strict=True)
        java, javac = java_home / 'bin/java.exe', java_home / 'bin/javac.exe'
        data = output / 'preparation-data'
        data.mkdir()
        env = environment(java_home, data)
        java_info = command_info(java, ['-version'], env, output)
        javac_info = command_info(javac, ['-version'], env, output)
        if not re.search(r'\bversion "21\.', java_info['versionOutput']) or not re.search(r'\bjavac 21\.', javac_info['versionOutput']):
            raise ValueError('Java and javac 21 required')
        summary['java'] = java_info
        summary['javac'] = javac_info
        dependencies = output / 'dependencies'
        dependencies.mkdir()
        jna = dependencies / 'jna-5.17.0.jar'
        archive = dependencies / 'mpv-dev-20260903-x64.7z'
        deadline = started + 150
        summary['jna'] = dependency(args.jna_jar, [JNA_URL], jna, 2002589, JNA_SHA, deadline)
        summary['mpvArchive'] = dependency(args.mpv_archive, ARCHIVE_URLS, archive, 31363218, ARCHIVE_SHA, deadline)
        tar = Path(shutil.which('tar.exe') or '')
        if not tar.is_file():
            raise ValueError('Windows tar.exe required')
        summary['extractor'] = command_info(tar, ['--version'], env, output)
        native = dependencies / 'native'
        native.mkdir()
        subprocess.run([str(tar), '-xf', str(archive), '-C', str(native), 'libmpv-2.dll'],
                       check=True, cwd=output, env=env, timeout=20, capture_output=True)
        dll = verified(native / 'libmpv-2.dll', 120342528, DLL_SHA)
        summary['dllSha256'] = digest(dll)
        source = Path(__file__).with_name('AwtMpvProbe.java').resolve(strict=True)
        adapter_source = (source.parents[2] / 'src/main/java/com/bilipai/desktop/player/DesktopWindowsDxgiAdapters.java').resolve(strict=True)
        summary['sourceSha256'] = digest(source)
        summary['productionAdapterSourceSha256'] = digest(adapter_source)
        summary['runnerSha256'] = digest(__file__)
        shaders = None
        if args.suite == 'shader-clear':
            shaders = dependencies / 'original-shaders'
            summary['shaderResources'] = prepare_shaders(source.parents[3], shaders)
            summary['audioOutputMode'] = 'CI timed null output; no local audio/hardware acceptance'
        classes = output / 'classes'
        classes.mkdir()
        with (output / 'compile.log').open('xb') as log:
            subprocess.run([str(javac), '-encoding', 'UTF-8', '-cp', str(jna), '-d', str(classes), str(adapter_source), str(source)],
                           cwd=output, env=env, check=True, stdout=log, stderr=subprocess.STDOUT, timeout=20)
        for case in cases:
            try:
                row = run_case(case, java, classes, jna, dll, output, java_home, shaders,
                               debug_observations=args.suite in ('surface-debug', 'surface-load-order'),
                               load_order_observations=args.suite == 'surface-load-order')
            except Exception as error:
                row = {'case': case, 'passed': False, 'runnerError': type(error).__name__}
            summary['cases'].append(row)
            print(json.dumps({k: v for k, v in row.items() if k != 'result'}), flush=True)
            if row.get('processStillRunning'):
                summary['remainingCasesSkipped'] = 'Previous owned JVM did not terminate'
                break
        summary['passed'] = len(summary['cases']) == len(cases) and all(row['passed'] for row in summary['cases'])
    except Exception as error:
        summary['error'] = type(error).__name__ + ': ' + str(error)[:1000]
    finally:
        summary['elapsedSeconds'] = round(time.monotonic() - started, 3)
        save(output / 'summary.json', summary)
    print(json.dumps({'passed': summary['passed'], 'diagnosticOnly': True, 'report': str(output / 'summary.json')}))
    return 0 if summary['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
