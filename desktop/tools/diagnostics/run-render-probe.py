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


def run_case(case, java, classes, jna, dll, output, java_home):
    work = output / (case + '-work')
    work.mkdir()
    data = work / 'private-data'
    data.mkdir()
    case_output = output / case
    command = [str(java), '-cp', str(classes) + os.pathsep + str(jna), 'AwtMpvProbe',
               '--case', case, '--output', str(case_output)]
    if case != 'awt-alpha-only':
        command += ['--mpv', str(dll)]
    started = time.monotonic()
    forced = False
    cleanup_errors = []
    with (output / (case + '-process.log')).open('xb') as log:
        process = subprocess.Popen(command, cwd=work, env=environment(java_home, data),
                                   stdout=log, stderr=subprocess.STDOUT,
                                   creationflags=subprocess.CREATE_NO_WINDOW)
        try:
            code = process.wait(timeout=35 if case == 'awt-alpha-only' else 40)
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
    args = parser.parse_args()
    sys.stdout.reconfigure(encoding='utf-8')
    if os.name != 'nt':
        parser.error('This diagnostic requires Windows')
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    started = time.monotonic()
    summary = {'diagnosticOnly': True, 'productReleaseGatePassed': False, 'cases': [], 'passed': False}
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
        classes = output / 'classes'
        classes.mkdir()
        with (output / 'compile.log').open('xb') as log:
            subprocess.run([str(javac), '-encoding', 'UTF-8', '-cp', str(jna), '-d', str(classes), str(adapter_source), str(source)],
                           cwd=output, env=env, check=True, stdout=log, stderr=subprocess.STDOUT, timeout=20)
        for case in CASES:
            try:
                row = run_case(case, java, classes, jna, dll, output, java_home)
            except Exception as error:
                row = {'case': case, 'passed': False, 'runnerError': type(error).__name__}
            summary['cases'].append(row)
            print(json.dumps({k: v for k, v in row.items() if k != 'result'}), flush=True)
            if row.get('processStillRunning'):
                summary['remainingCasesSkipped'] = 'Previous owned JVM did not terminate'
                break
        summary['passed'] = len(summary['cases']) == len(CASES) and all(row['passed'] for row in summary['cases'])
    except Exception as error:
        summary['error'] = type(error).__name__ + ': ' + str(error)[:1000]
    finally:
        summary['elapsedSeconds'] = round(time.monotonic() - started, 3)
        save(output / 'summary.json', summary)
    print(json.dumps({'passed': summary['passed'], 'diagnosticOnly': True, 'report': str(output / 'summary.json')}))
    return 0 if summary['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
