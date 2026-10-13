"""Validate a frozen source cohort on the personal public standard GitHub runner.

Local use is check-only. Compilation never runs on the user's PC through this tool.
The real main Gradle graph retains its reviewed native prerequisites. No cache/artifact
upload, publication, deployment, separate native pipeline or GUI launch is owned here.
"""
from pathlib import Path
import argparse, base64, gzip, hashlib, json, os, re, subprocess, sys, time
import urllib.request
import xml.etree.ElementTree as ET

PLAN = '.github/windows-source-validation-plan.json'
REPOSITORY = 'tonysuper666-creator/BiliPai-Windows'
CLOUD_GRADLE_HEAP = '8g'  # Explicit fixed profile for the public standard 16 GiB Windows runner.

def digest(raw): return hashlib.sha256(raw).hexdigest()
def read(path):
    value = str(path.resolve())
    if os.name == 'nt' and not value.startswith('\\\\?\\'):
        value = '\\\\?\\' + value
    return Path(value).read_bytes()
def source_digest(repo, row):
    raw = read(repo / row['path'])
    if row['normalization'] == 'lf': raw = raw.replace(b'\r\n', b'\n')
    elif row['normalization'] != 'raw': raise ValueError('Unknown source normalization')
    return digest(raw)
def git(repo, *args):
    return subprocess.run(['git', '-c', 'core.autocrlf=false', '-C', str(repo), *args],
                          check=True, capture_output=True).stdout.decode().strip()

def check(repo):
    plan = json.loads(read(repo / PLAN))
    assert plan['repository'] == REPOSITORY and plan['schemaVersion'] == 1
    assert plan['cloudGradleHeapMaximum'] == CLOUD_GRADLE_HEAP
    assert sum(plan['classes'].values()) == plan['expectedTests']
    paths = [row['path'] for row in plan['sources']]
    assert len(paths) == len(set(paths)) and PLAN not in paths
    for row in plan['sources']:
        path = Path(row['path'])
        assert not path.is_absolute() and '..' not in path.parts
        assert source_digest(repo, row) == row['sha256'], row['path']
    properties = read(repo / 'desktop/gradle.properties').decode()
    assert 'org.gradle.workers.max=1' in properties
    assert 'org.gradle.parallel=false' in properties
    assert 'org.gradle.daemon=false' in properties
    assert 'kotlin.compiler.execution.strategy=in-process' in properties
    assert '-Xmx4g' in properties
    return plan

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--check-only', action='store_true')
    mode.add_argument('--run', action='store_true')
    parser.add_argument('--repo', type=Path, required=True)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    repo = args.repo.resolve()
    plan = check(repo)
    if args.check_only:
        print(json.dumps(dict(passed=True, sourcePaths=len(plan['sources']),
                              expectedTests=plan['expectedTests'], buildExecuted=False)))
        return 0
    assert os.environ.get('GITHUB_ACTIONS') == 'true', 'Compilation requires GitHub Actions'
    assert os.environ.get('GITHUB_REPOSITORY') == REPOSITORY
    assert os.environ.get('BILIPAI_RUNNER_ENVIRONMENT') == 'github-hosted'
    assert os.environ.get('GITHUB_REF', '').startswith('refs/heads/desktop/source-validation-')
    sha = git(repo, 'rev-parse', 'HEAD')
    assert sha == os.environ['GITHUB_SHA']
    assert git(repo, 'rev-parse', 'HEAD^') == plan['baseHead']
    changed = set(git(repo, 'diff', '--name-only', plan['baseHead'], 'HEAD').splitlines())
    assert changed == {row['path'] for row in plan['sources']} | {PLAN}
    headers = {'Accept': 'application/vnd.github+json', 'User-Agent': 'BiliPai-Windows-source-validation'}
    token = os.environ.get('GITHUB_TOKEN')
    if token: headers['Authorization'] = 'Bearer ' + token
    request = urllib.request.Request('https://api.github.com/repos/' + REPOSITORY, headers=headers)
    with urllib.request.urlopen(request, timeout=30) as response: remote = json.load(response)
    assert remote['full_name'] == REPOSITORY and remote['private'] is False
    assert args.output is not None
    output = args.output.resolve()
    assert not output.exists()
    output.mkdir(parents=True)
    java_home = Path(os.environ['JAVA_HOME'])
    assert 'JAVA_VERSION="21.0.12.1"' in read(java_home / 'release').decode()
    before = {row['path']: source_digest(repo, row) for row in plan['sources']}
    environment = dict(os.environ, PYTHON_EXECUTABLE=sys.executable,
                       PYTHONDONTWRITEBYTECODE='1', PYTHONIOENCODING='utf-8')
    environment.pop('GITHUB_TOKEN', None)
    environment.pop('GH_TOKEN', None)
    command = ['cmd.exe', '/d', '/c', str(repo / 'gradlew.bat'), '-p', 'desktop',
               '--no-daemon', '--console=plain', '--max-workers=1', '--no-parallel',
               '--no-build-cache', '--gradle-user-home', str(output / 'gradle-home'),
               '-Dorg.gradle.jvmargs=-Xmx' + CLOUD_GRADLE_HEAP + ' -XX:MaxMetaspaceSize=1g -XX:ReservedCodeCacheSize=256m -Dfile.encoding=UTF-8',
               '-Pkotlin.compiler.execution.strategy=in-process',
               '-Porg.gradle.java.installations.auto-detect=false',
               '-Porg.gradle.java.installations.auto-download=false',
               '-Porg.gradle.java.installations.paths=' + str(java_home),
               'compileKotlin', 'compileTestKotlin', 'test']
    for name in plan['classes']: command += ['--tests', name]
    started = time.time()
    result = subprocess.run(command, cwd=repo, env=environment)
    suites = []
    raw_xml = {}
    passed = result.returncode == 0
    for name, count in plan['classes'].items():
        path = repo / 'desktop/build/test-results/test' / ('TEST-' + name + '.xml')
        row = dict(name=name, expected=count, exists=path.is_file())
        if path.is_file():
            raw = read(path)
            root = ET.fromstring(raw)
            row.update(tests=int(root.attrib['tests']), failures=int(root.attrib['failures']),
                       errors=int(root.attrib['errors']), skipped=int(root.attrib['skipped']),
                       actualName=root.attrib['name'], fresh=path.stat().st_mtime >= started - 2,
                       xmlSha256=digest(raw))
            row['backgroundError'] = any(marker in (root.findtext('system-err') or '')
                for marker in ('Exception in thread', 'CompletionHandlerException', 'java.nio.file.AccessDeniedException'))
            passed &= (row['actualName'] == name and row['tests'] == count and row['fresh']
                       and not (row['failures'] or row['errors'] or row['skipped'] or row['backgroundError']))
            raw_xml[name] = raw.decode('utf-8')
        else: passed = False
        suites.append(row)
    after = {row['path']: source_digest(repo, row) for row in plan['sources']}
    passed &= before == after
    receipt = dict(passed=bool(passed), sourceSha=sha, baseHead=plan['baseHead'],
        windowsVersion=plan['windowsVersion'], sourceBefore=before, sourceAfter=after,
        sourceUnchanged=before == after, exitCode=result.returncode, suites=suites,
        expectedTests=plan['expectedTests'], standardRunner='windows-latest',
        cloudGradleHeapMaximum=CLOUD_GRADLE_HEAP, localGradleHeapMaximum='4g',
        applicationExecuted=False, hardwareExecuted=False, packageBuilt=False, published=False)
    (output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
    payload = json.dumps(dict(receipt=receipt, rawXml=raw_xml), ensure_ascii=True).encode()
    encoded = base64.b64encode(gzip.compress(payload, mtime=0)).decode()
    print('BILIPAI_SOURCE_RECEIPT_BEGIN ' + digest(payload))
    for offset in range(0, len(encoded), 8000): print(encoded[offset:offset + 8000])
    print('BILIPAI_SOURCE_RECEIPT_END')
    print(json.dumps(dict(passed=bool(passed), sourceSha=sha, expectedTests=plan['expectedTests'],
                          actualTests=sum(row.get('tests', 0) for row in suites))))
    return 0 if passed else 1

if __name__ == '__main__': sys.exit(main())
