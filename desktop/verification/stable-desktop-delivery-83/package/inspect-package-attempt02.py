from pathlib import Path, PurePosixPath
import datetime, hashlib, json, re, shutil, stat, subprocess, zipfile

HERE = Path(__file__).resolve().parent
RUN = HERE / 'attempt02'
REPO = HERE.parents[3] / 'BiliPai-v023'
HEAD = '6fd5bbd804f272650942f5a445385ba5428ffa9b'

def wide(p):
    s = str(Path(p).absolute())
    return Path(s if s.startswith('\\\\?\\') else '\\\\?\\' + s)

def sha_stream(stream):
    h = hashlib.sha256()
    while c := stream.read(1024 * 1024):
        h.update(c)
    return h.hexdigest()

def sha(p):
    with wide(p).open('rb') as stream:
        return sha_stream(stream)

def row(p, relative=None):
    return {'path': str(p).removeprefix('\\\\?\\') if relative is None else relative,
            'bytes': wide(p).stat().st_size, 'sha256Bytes': sha(p)}

def read(p):
    return json.loads(wide(p).read_bytes().decode('utf-8-sig'))

def save(name, value):
    wide(RUN / name).write_bytes((json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode())

def safe_zip_name(info):
    name = info.filename.replace('\\', '/')
    assert name and not name.startswith('/'), name
    parts = name.rstrip('/').split('/')
    assert all(part and part not in {'.', '..'} and ':' not in part for part in parts), name
    assert not stat.S_ISLNK(info.external_attr >> 16), name
    return PurePosixPath(*parts).as_posix()

run = read(RUN / 'run-result.json')
assert run['codeHead'] == HEAD and run['buildScriptPassed'] is True, run
assert subprocess.check_output(['git', '-C', str(REPO), 'rev-parse', 'HEAD']).decode().strip() == HEAD
changes = read(RUN / 'input-change-audit.json')
assert changes['diagnosticDllUnchanged'] is True
production_changes = [item for item in changes['trackedChanges']
                      if not item['path'].startswith('desktop/resources/common/notices/')]
assert not production_changes, production_changes
notice_changes = []
for item in changes['trackedChanges']:
    previous = subprocess.check_output(['git', '-C', str(REPO), 'show', HEAD + ':' + item['path']])
    current = wide(REPO / item['path']).read_bytes()
    same_lf = previous.replace(b'\r\n', b'\n') == current.replace(b'\r\n', b'\n')
    assert same_lf, item['path']
    notice_changes.append({**item, 'onlyCRLFToLF': same_lf})

executables = list(wide(REPO / 'desktop/build/compose/binaries/main/app').rglob('BiliPai Windows.exe'))
assert len(executables) == 1
exe = executables[0]
app = exe.parent
files = [row(p, p.relative_to(app).as_posix()) for p in sorted(app.rglob('*')) if p.is_file()]
file_map = {item['path']: item for item in files}
jars = list((app / 'app').glob('bilipai-windows*.jar'))
assert len(jars) == 1
with zipfile.ZipFile(jars[0]) as jar:
    version = json.loads(jar.read('windows-update.json'))
    upstream = jar.read('upstream-version.txt').decode()
    classes = sum(name.endswith('.class') for name in jar.namelist())
assert version['version'] == '0.2.415.1'
assert version['upstreamTag'] == 'v0.2.3'
assert version['upstreamCommit'] == '3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
assert upstream.splitlines() == [version['upstreamTag'], version['upstreamCommit']]
assert (app / 'runtime/bin/server/jvm.dll').is_file()
config = (app / 'app/BiliPai Windows.cfg').read_text(encoding='utf-8')
classpath = [line.partition('=')[2] for line in config.splitlines() if line.startswith('app.classpath=')]
classpath_audit = read(RUN / 'packaged-classpath-audit.json')
assert classpath_audit['passed'] is True
assert classpath_audit['frozenJavaKotlinAndResourcesMatchByteForByte'] is True
assert classpath_audit['allRuntimeDependenciesAccountedFor'] is True
assert len(classpath) == classpath_audit['launcherClasspathEntries']
for cp in classpath:
    assert cp.startswith('$APPDIR\\'), cp
    assert (app / 'app' / cp.removeprefix('$APPDIR\\').replace('\\', '/')).is_file(), cp

archive = REPO / ('desktop/build/distributions/BiliPai-Windows-' + version['version'] + '-x64.zip')
archive_sha = sha(archive)
assert wide(Path(str(archive) + '.sha256')).read_text().split()[0].lower() == archive_sha
zip_files = []
with zipfile.ZipFile(wide(archive)) as package:
    infos = package.infolist()
    names = [safe_zip_name(info) for info in infos]
    assert len({name.casefold() for name in names}) == len(names), 'ZIP member collision'
    launchers = [name for info, name in zip(infos, names) if not info.is_dir() and name.endswith('/BiliPai Windows.exe')]
    assert len(launchers) == 1
    prefix = PurePosixPath(launchers[0]).parent.as_posix()
    assert prefix == 'BiliPai Windows', prefix
    for info, name in zip(infos, names):
        assert name == prefix or name.startswith(prefix + '/'), name
        if info.is_dir():
            continue
        relative = name.removeprefix(prefix + '/')
        assert relative in file_map, relative
        with package.open(info) as stream:
            item_sha = sha_stream(stream)
        item = {'path': relative, 'zipMember': info.filename, 'bytes': info.file_size, 'sha256Bytes': item_sha}
        assert item['bytes'] == file_map[relative]['bytes'] and item_sha == file_map[relative]['sha256Bytes'], relative
        zip_files.append(item)
assert len(zip_files) == len(files) and {item['path'] for item in zip_files} == set(file_map)

started = datetime.datetime.fromisoformat(run['startedAtUtc']).timestamp()
finished = datetime.datetime.fromisoformat(run['finishedAtUtc']).timestamp()
reports = {}
for key, glob, name in [('nativeMux', 'native-mux-*', 'native-download-mux.json'),
                         ('nativePlayer', 'native-smoke-*', 'native-player-smoke.json'),
                         ('updater', 'updater-smoke-*', 'updater-smoke.json')]:
    matches = [p / name for p in wide(REPO / 'desktop/build/reports').glob(glob)
               if (p / name).is_file() and started - 2 <= (p / name).stat().st_mtime <= finished + 2]
    assert len(matches) == 1, (key, matches)
    report = read(matches[0])
    assert report['passed'] is True, (key, report)
    output = key + '-smoke.json'
    wide(RUN / output).write_bytes(matches[0].read_bytes())
    reports[key] = {'originalPath': str(matches[0]).removeprefix('\\\\?\\'),
                    'evidence': output, 'sha256Bytes': sha(RUN / output), 'passed': True, 'report': report}
native = app / 'app/resources/native/windows-x64'
critical = [row(native / name, name) for name in ['libmpv-2.dll', 'ffmpeg.exe', 'ffprobe.exe', 'bilipai-diagnostic-share.dll']]
assert critical[-1]['sha256Bytes'] == '89705e2e0b5b146c8fdbccda63dc68bea7e46bfcc7e4aaa50c4cb1efa5331f06'
assert reports['nativeMux']['report']['ffmpegSha256'] == critical[1]['sha256Bytes']
assert reports['nativeMux']['report']['ffprobeSha256'] == critical[2]['sha256Bytes']
for check in ['dualTrackCopyMux', 'audioOnlyCopyMux', 'progressiveCopyMux', 'multiSegmentCopyMux',
              'audioOnlyMultiSegmentCopyMux', 'videoOnlyMultiSegmentCopyMux', 'decodedAllOutputs']:
    assert reports['nativeMux']['report'][check] is True, check
assert len(reports['nativeMux']['report']['outputs']) == 6
assert reports['updater']['report']['portableZipSha256'] == archive_sha
assert reports['updater']['report']['windowsVersion'] == version['version']
assert reports['updater']['report']['evidenceType'] == 'isolated-packaged-updater'

save('package-image-file-manifest.json', {'codeHead': HEAD, 'root': str(app).removeprefix('\\\\?\\'), 'files': files})
save('portable-zip-file-manifest.json', {'codeHead': HEAD, 'archive': row(archive), 'prefix': prefix,
                                      'allMembersSafe': True, 'allMemberCRCsReadAndVerified': True,
                                      'exactAppImageMatch': True, 'files': zip_files})
save('tracked-notices-review.json', {'codeHead': HEAD, 'trackedNoticeChanges': notice_changes,
                                   'noProductionSourceChanges': True, 'automaticRevert': False, 'automaticCommit': False})
report = {'codeHead': HEAD, 'windowsVersion': version, 'upstreamVersionText': upstream,
          'executable': row(exe), 'productJar': row(jars[0]), 'productJarClassCount': classes,
          'launcherClasspathFiles': len(classpath), 'frozenGradleRuntimeEntries': classpath_audit['frozenRuntimeEntries'],
          'classpathAudit': 'packaged-classpath-audit.json',
          'classpathAuditSha256Bytes': sha(RUN / 'packaged-classpath-audit.json'),
          'productMainEntriesMatchFrozen83': True, 'classpathDifferenceAccountedFor': True,
          'duplicateClassGroups': classpath_audit['duplicateClassGroups'],
          'appImageFiles': len(files), 'portableZip': row(archive),
          'zipAllMembersSafe': True, 'zipExactAppImageMatch': True, 'zipFileCount': len(zip_files),
          'packagedRuntimePresent': True, 'packagedNativeCriticalFiles': critical,
          'nativeMuxSmoke': {k: v for k, v in reports['nativeMux'].items() if k != 'report'},
          'nativePlayerSmoke': {k: v for k, v in reports['nativePlayer'].items() if k != 'report'},
          'updaterSmoke': {k: v for k, v in reports['updater'].items() if k != 'report'},
          'buildScriptPassed': True, 'allRequestedPackageSmokesPassed': True,
          'sourceBeforeRecordSha256Bytes': sha(RUN / 'before-inputs.json'),
          'sourceAfterRecordSha256Bytes': sha(RUN / 'after-inputs.json'),
          'sourceChangeAuditSha256Bytes': sha(RUN / 'input-change-audit.json'),
          'diagnosticNativeDllUnchanged': True, 'noProductionSourceChanges': True,
          'trackedNoticeChanges': len(notice_changes), 'allTrackedNoticeChangesOnlyCRLFToLF': True,
          'allFeaturesAccepted': False, 'realAccountAcceptancePending': True,
          'realBilibiliVideoFirstFrameAndPiPAccepted': False,
          'realRootExternalMedia17Checks': 'reported_passed_by_parent_separate_lane',
          'knownGuestMediaHttpStatus': 412,
          'deployed': False, 'published': False, 'msiCreated': False, 'releaseGatePassed': False}
save('package-result.json', report)
print(json.dumps({'packageResultSha256Bytes': sha(RUN / 'package-result.json'), 'version': version,
                  'executable': report['executable'], 'portableZip': report['portableZip'],
                  'fileCount': len(files), 'smokesPassed': True, 'trackedNoticeChanges': len(notice_changes)}, ensure_ascii=False))
