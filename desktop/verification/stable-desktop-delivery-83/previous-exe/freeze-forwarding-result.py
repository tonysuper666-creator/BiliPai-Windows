from pathlib import Path
import datetime, hashlib, json, xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent

def sha(p):
    h = hashlib.sha256()
    with p.open('rb') as f:
        while data := f.read(1024 * 1024):
            h.update(data)
    return h.hexdigest()

def read(p):
    return json.loads(p.read_bytes().decode('utf-8-sig'))

def write(p, value):
    p.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

preflight = read(HERE / 'package-preflight.json')
run = read(HERE / 'run-result.json')
report_path = HERE / 'reports/updater-smoke.json'
report = read(report_path) if report_path.is_file() else {}
forwarding = report.get('actualPreviousExeForwarding', {})
passed = (run.get('gradleExitCode') == 0 and run.get('passed') is True and report.get('passed') is True
          and forwarding.get('passed') is True and report.get('trackedProcessesStopped') is True)
for path_key, sha_key in [('previousArchive', 'previousArchiveSha256'), ('currentArchive', 'currentArchiveSha256')]:
    assert sha(Path(preflight[path_key])) == preflight[sha_key], path_key
if passed:
    assert forwarding['previousVersion'] == preflight['previousWindowsVersion']['version'] == '0.2.406.5'
    assert forwarding['previousZipSha256'] == preflight['previousArchiveSha256']
    assert report['windowsVersion'] == preflight['currentWindowsVersion']['version'] == '0.2.415.1'
    assert report['portableZipSha256'] == preflight['currentArchiveSha256']
    case_root = Path(report['caseRoot']).resolve()
    assert Path(forwarding['previousExecutable']).resolve().is_relative_to(case_root)
    assert Path(report['isolatedLocalAppData']).resolve().is_relative_to(case_root)
    assert Path(report['successActivation']['executable']).resolve().is_relative_to(case_root)
    assert len(forwarding['newProcessIds']) > 0 and forwarding['shortcutTested'] is False
xml_path = HERE / 'TEST-com.bilipai.desktop.update.DesktopUpdaterIntegrationTest.xml'
junit = {}
if xml_path.is_file():
    suite = ET.parse(xml_path).getroot()
    junit = dict(suite.attrib)
    if passed:
        assert int(suite.get('tests')) == 1 and int(suite.get('failures')) == int(suite.get('errors')) == int(suite.get('skipped')) == 0
summary = {'status': 'passed' if passed else 'failed', 'passed': passed,
           'harnessCodeHead': run['harnessCodeHead'], 'packagedCodeHead': run['packagedCodeHead'],
           'previousWindowsVersion': preflight['previousWindowsVersion']['version'],
           'previousZip': preflight['previousArchive'], 'previousZipSha256': preflight['previousArchiveSha256'],
           'currentWindowsVersion': preflight['currentWindowsVersion']['version'],
           'currentZip': preflight['currentArchive'], 'currentZipSha256': preflight['currentArchiveSha256'],
           'previousExeCfgAndProductJarByteMatchUserOldDesktop': True,
           'bothOriginalArchivesUnchanged': True, 'actualPreviousExeForwarding': forwarding,
           'isolatedLocalAppData': report.get('isolatedLocalAppData'),
           'trackedFixtureProcessesStopped': report.get('trackedProcessesStopped'),
           'rawReport': 'reports/updater-smoke.json',
           'rawReportSha256': sha(report_path) if report_path.is_file() else None,
           'junit': junit, 'gradleExitCode': run.get('gradleExitCode'),
           'error': report.get('error', run.get('failure')),
           'sourceStatusBefore': run['sourceStatusBefore'], 'sourceStatusAfter': run['sourceStatusAfter'],
           'productionOrTestEdits': False, 'repackaged': False, 'nativeMuxRerun': False,
           'desktopPayloadOrMetadataChanged': False, 'userAccountDataReadOrTouched': False,
           'userDesktopExeOrOldProcessesStartedOrControlled': False,
           'shortcutTested': False, 'realBilibiliAccountPlaybackAccepted': False,
           'allFeaturesAccepted': False}
write(HERE / 'forwarding-summary.json', summary)
readme = '# Actual previous EXE forwarding boundary\n\nThis updater-only run uses the original 0.2.406.5 ZIP and the validated/deployed 0.2.415.1 ZIP from source 6fd5bbd804f272650942f5a445385ba5428ffa9b. Preflight proves the old ZIP EXE, launcher cfg and product JAR match the three corresponding old desktop program files by bytes and embedded version. No account data was read and the desktop EXE was not run.\n\nOnly existing updaterSmoke ran, with updatePreviousPackage pointing to the original old ZIP. Children and registry files use the existing test temporary case root and isolated LocalAppData. The original previous launcher was extracted into that case root, without editing it or the old ZIP. Its real result appears in forwarding-summary.json and the unchanged raw report. Existing negative fixtures do not replace the real old package.\n\nNeither archive, desktop payload/metadata, production source nor tests were changed. No repackaging, native mux rerun, public release or actual user shortcut test occurred. Real account playback and whole feature parity are separate. This new receipt supplements attempt 02 without rewriting its historical tested=false evidence.\n'
(HERE / 'README.md').write_text(readme, encoding='utf-8')
files = [p for p in sorted(HERE.rglob('*')) if p.is_file() and p.name != 'frozen-receipt.json']
receipt = {'status': summary['status'], 'createdUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
           'summary': 'forwarding-summary.json', 'files': [{'path': p.relative_to(HERE).as_posix(),
             'bytes': p.stat().st_size, 'sha256Bytes': sha(p)} for p in files]}
write(HERE / 'frozen-receipt.json', receipt)
assert all(sha(HERE / item['path']) == item['sha256Bytes'] for item in receipt['files'])
print(json.dumps({'passed': passed, 'actualPreviousExeForwarding': forwarding,
                  'summarySha256': sha(HERE / 'forwarding-summary.json'),
                  'rawReportSha256': summary['rawReportSha256'],
                  'frozenReceiptSha256': sha(HERE / 'frozen-receipt.json'), 'files': len(files)}, ensure_ascii=False))
