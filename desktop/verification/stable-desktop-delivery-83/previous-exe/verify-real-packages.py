from pathlib import Path
import hashlib, io, json, zipfile

HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
PREVIOUS = MAIN / 'desktop/build/distributions/BiliPai-Windows-0.2.406.5-x64.zip'
CURRENT = MAIN.parent / 'BiliPai-v023/desktop/build/distributions/BiliPai-Windows-0.2.415.1-x64.zip'
OLD_DESKTOP = Path('C:/Users/TONYS/Desktop/BiliPai Windows')

def sha(p):
    h = hashlib.sha256()
    with p.open('rb') as f:
        while data := f.read(1024 * 1024):
            h.update(data)
    return h.hexdigest()

def nested_version(data):
    with zipfile.ZipFile(io.BytesIO(data)) as jar:
        return json.loads(jar.read('windows-update.json'))

previous_sha = sha(PREVIOUS)
current_sha = sha(CURRENT)
assert previous_sha == '178f5c4dc584853d2da5e2733998987a1a2d13ded21e4d650369aeba1139a3e3'
assert current_sha == '8aac45f341dd04cab4700dde9b537bcc7388d0df334d9be6562f1136f5725cf9'
for archive, digest in [(PREVIOUS, previous_sha), (CURRENT, current_sha)]:
    checksum = Path(str(archive) + '.sha256')
    if checksum.is_file():
        assert checksum.read_text().split()[0].lower() == digest
with zipfile.ZipFile(PREVIOUS) as package:
    files = [name for name in package.namelist() if not name.endswith('/')]
    launchers = [name for name in files if name.endswith('/BiliPai Windows.exe')]
    products = [name for name in files if '/app/bilipai-windows' in name and name.endswith('.jar')]
    assert len(launchers) == len(products) == 1
    prefix = launchers[0].rsplit('/', 1)[0]
    targets = [launchers[0], prefix + '/app/BiliPai Windows.cfg', products[0]]
    comparisons = []
    for member in targets:
        relative = member.removeprefix(prefix + '/')
        desktop = OLD_DESKTOP / relative
        data = package.read(member)
        digest = hashlib.sha256(data).hexdigest()
        assert sha(desktop) == digest and desktop.stat().st_size == len(data), relative
        comparisons.append({'zipMember': member, 'desktopFile': str(desktop), 'bytes': len(data),
                            'sha256Bytes': digest, 'desktopAndArchiveByteIdentical': True})
    previous_version = nested_version(package.read(products[0]))
    desktop_version = nested_version((OLD_DESKTOP / products[0].removeprefix(prefix + '/')).read_bytes())
    assert previous_version == desktop_version and previous_version['version'] == '0.2.406.5'
    old_config = package.read(targets[1]).decode('utf-8')
    assert Path(products[0]).name in old_config
with zipfile.ZipFile(CURRENT) as package:
    products = [name for name in package.namelist() if '/app/bilipai-windows' in name and name.endswith('.jar')]
    assert len(products) == 1
    current_version = nested_version(package.read(products[0]))
    assert current_version['version'] == '0.2.415.1' and current_version['upstreamTag'] == 'v0.2.3'
assert previous_version['windowsReleaseRepository'] == current_version['windowsReleaseRepository']
report = {'previousArchive': str(PREVIOUS), 'previousArchiveSha256': previous_sha,
          'previousWindowsVersion': previous_version, 'previousDesktopComparisons': comparisons,
          'currentArchive': str(CURRENT), 'currentArchiveSha256': current_sha,
          'currentWindowsVersion': current_version,
          'currentPackagedSourceCommit': '6fd5bbd804f272650942f5a445385ba5428ffa9b',
          'actualPreviousPackageUnmodified': True, 'previousDesktopOnlyReadForThreeProductFiles': True,
          'accountDataRead': False, 'previousDesktopExeLaunched': False,
          'allPreflightChecksPassed': True}
(HERE / 'package-preflight.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'previousVersion': previous_version['version'], 'previousZipSha256': previous_sha,
                  'threeOldDesktopProductFilesByteMatch': True, 'currentVersion': current_version['version'],
                  'currentZipSha256': current_sha, 'preflightPassed': True}))
