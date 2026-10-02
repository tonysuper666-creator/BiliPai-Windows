from pathlib import Path, PurePosixPath
import datetime, hashlib, json, os, re, stat, zipfile

HERE = Path(__file__).resolve().parent
RUN = HERE / 'attempt02'
DESKTOP = Path('C:/Users/TONYS/Desktop').resolve(strict=True)

def wide(p):
    s = str(Path(p).absolute())
    return Path(s if s.startswith('\\\\?\\') else '\\\\?\\' + s)

def sha_stream(stream):
    h = hashlib.sha256()
    while data := stream.read(1024 * 1024):
        h.update(data)
    return h.hexdigest()

def sha(p):
    with wide(p).open('rb') as stream:
        return sha_stream(stream)

def read(p):
    return json.loads(wide(p).read_bytes().decode('utf-8-sig'))

def write(p, value):
    wide(p).write_bytes((json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode())

result = read(RUN / 'package-result.json')
assert result['buildScriptPassed'] and result['allRequestedPackageSmokesPassed']
assert result['zipAllMembersSafe'] and result['zipExactAppImageMatch']
manifest = read(RUN / 'portable-zip-file-manifest.json')
archive = Path(result['portableZip']['path'])
archive_sha = sha(archive)
assert archive_sha == result['portableZip']['sha256Bytes'] == manifest['archive']['sha256Bytes']
expected = {item['path']: item for item in manifest['files']}
assert len(expected) == result['zipFileCount']
version = result['windowsVersion']
destination = DESKTOP / 'BiliPai Windows v0.2.3'
protected_base_exists = destination.exists()
if protected_base_exists:
    destination = DESKTOP / ('BiliPai Windows v0.2.3 (' + version['version'] + '-' + result['codeHead'][:8] + ')')
if destination.exists():
    destination = DESKTOP / (destination.name + ' - ' + datetime.datetime.now().strftime('%Y%m%d_%H%M%S'))
assert not destination.exists()
assert destination.parent == DESKTOP
root = destination.resolve(strict=False)
assert root.parent == DESKTOP and root.name.startswith('BiliPai Windows v0.2.3')

with zipfile.ZipFile(wide(archive)) as package:
    validated = []
    seen = set()
    for info in package.infolist():
        name = info.filename.replace('\\', '/').rstrip('/')
        parts = name.split('/')
        assert name and not name.startswith('/') and not stat.S_ISLNK(info.external_attr >> 16), name
        assert all(part and part not in {'.', '..'} and not part.endswith(('.', ' '))
                   and not re.search(r'[<>:"|?*\x00]', part) for part in parts), name
        assert name == manifest['prefix'] or name.startswith(manifest['prefix'] + '/'), name
        key = name.casefold()
        assert key not in seen, name
        seen.add(key)
        relative = PurePosixPath(name).relative_to(manifest['prefix']).as_posix()
        target = (root / Path(relative)).resolve(strict=False)
        assert target == root or target.is_relative_to(root), (name, target)
        if not info.is_dir():
            assert relative in expected and info.filename == expected[relative]['zipMember'], name
            assert info.file_size == expected[relative]['bytes'], name
        validated.append((info, relative, target))
    assert {relative for info, relative, target in validated if not info.is_dir()} == set(expected)
    # No existing desktop contents are overwritten. Every member target is checked before creation.
    wide(root).mkdir(exist_ok=False)
    for info, relative, target in validated:
        if info.is_dir():
            wide(target).mkdir(parents=True, exist_ok=True)
            continue
        wide(target.parent).mkdir(parents=True, exist_ok=True)
        with package.open(info) as source, wide(target).open('xb') as output:
            while data := source.read(1024 * 1024):
                output.write(data)
        assert wide(target).stat().st_size == expected[relative]['bytes'], relative
        assert sha(target) == expected[relative]['sha256Bytes'], relative

actual = {p.relative_to(wide(root)).as_posix(): p for p in wide(root).rglob('*') if p.is_file()}
assert set(actual) == set(expected) and len(actual) == len(expected)
for relative, p in actual.items():
    assert p.stat().st_size == expected[relative]['bytes'] and sha(p) == expected[relative]['sha256Bytes'], relative
exe = root / 'BiliPai Windows.exe'
assert exe.is_file() and sha(exe) == result['executable']['sha256Bytes']
assert sha(archive) == archive_sha, 'Archive changed while deploying'
deployment = {
    'owner': 'BiliPai.Windows.DesktopDeployment',
    'version': version['version'], 'sourceCommit': result['codeHead'],
    'upstreamTag': version['upstreamTag'], 'upstreamCommit': version['upstreamCommit'],
    'package': str(archive), 'packageSha256': archive_sha,
    'deployedAtUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'deployedDirectory': str(root), 'deployedExecutable': str(exe),
    'deployedExecutableSha256': sha(exe), 'installedPackageFilesVerified': len(actual),
    'everyZipMemberAbsoluteTargetVerifiedWithinNewDirectory': True,
    'allPayloadFilesByteVerifiedAgainstZip': True,
    'packagedNativePlayerSmoke': 'passed', 'packagedNativeDownloadMuxSmoke': 'passed',
    'packagedUpdaterSmoke': 'passed', 'updaterChildrenUsedIsolatedLocalAppData': True,
    'realAccountAcceptance': 'pending_user_test', 'realBilibiliVideoFirstFrameAndPiP': 'pending_real_account_video_test',
    'realRootExternalMedia17Checks': 'reported_passed_by_parent_separate_lane',
    'knownGuestMediaHttpStatus': 412, 'allFeaturesAccepted': False,
    'existingDesktopDirectoriesPreserved': True, 'existingV023BaseDirectoryFoundAndProtected': protected_base_exists,
    'userAccountDataTouchedByDeployment': False, 'userExistingProcessesTouched': False,
    'normalUserProcessStartedByDeployment': False, 'published': False, 'msiCreated': False,
    '说明': '本次离线包检查已通过；真实账号普通视频、视频切换、Root画中画和完整功能仍需用户验收。',
}
info = root / '部署信息.json'
write(info, deployment)
receipt = {**deployment, 'deploymentInfo': str(info), 'deploymentInfoSha256': sha(info),
           'packageResultSha256': sha(RUN / 'package-result.json'),
           'portableZipFileManifestSha256': sha(RUN / 'portable-zip-file-manifest.json'),
           'payloadFileCount': len(actual), 'finalFileCountIncludingDeploymentInfo': len(actual) + 1}
write(RUN / 'deployment-receipt.json', receipt)
print(json.dumps({'directory': str(root), 'executable': str(exe), 'exeSha256': sha(exe),
                  'zipSha256': archive_sha, 'payloadFiles': len(actual),
                  'deploymentReceiptSha256': sha(RUN / 'deployment-receipt.json')}, ensure_ascii=False))
