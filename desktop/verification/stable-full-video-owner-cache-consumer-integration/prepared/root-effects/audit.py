from pathlib import Path
import hashlib, json, subprocess

HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
CANDIDATE = MAIN.parent / 'BiliPai-v023'
COMMIT = '3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'

def row(path):
    data = Path(path).read_bytes().replace(b'\r\n', b'\n')
    return {'path': str(Path(path).absolute()), 'sha256LF': hashlib.sha256(data).hexdigest(), 'bytesLF': len(data)}

def main():
    originals = ['app/src/main/java/com/android/purebilibili/core/util/NetworkUtils.kt',
                 'app/src/main/java/com/android/purebilibili/core/util/AnalyticsHelper.kt',
                 'app/src/main/java/com/android/purebilibili/core/util/CrashReporter.kt',
                 'app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt']
    registry = json.loads((CANDIDATE / 'desktop/upstream-sources.json').read_text(encoding='utf-8-sig'))
    existing = {value['path']: value for value in registry['sources']}
    pins = []
    checks = []
    for path in originals:
        data = subprocess.check_output(['git', '-C', str(CANDIDATE), 'show', COMMIT + ':' + path]).replace(b'\r\n', b'\n')
        digest = hashlib.sha256(data).hexdigest()
        assert existing[path]['sha256'] == digest
        pins.append({'path': path, 'commit': COMMIT, 'sha256LF': digest, 'bytesLF': len(data),
                     'existingRegistryMode': existing[path]['mode'], 'action': 'feature union only; preserve mode/body/owner'})
        checks.append({'name': 'fixed original identity ' + path, 'passed': True})
    source_paths = [
        'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoPlaybackOwnerEnvironment.kt',
        'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopHomeWindowsPreferencesPlatform.kt',
        'desktop/src/main/kotlin/com/bilipai/desktop/diagnostics/DesktopDiagnostics.kt',
        'desktop/src/main/kotlin/com/bilipai/desktop/plugins/DesktopPluginStore.kt']
    input_pins = [row(CANDIDATE / path) for path in source_paths]
    snapshot = MAIN / 'desktop/.local/stable-product-snapshot-71'
    recorded = {value['path']: value['sha256Bytes'] for value in json.loads((snapshot / 'manifest.json').read_text(encoding='utf-8-sig'))['inputs']}
    # Store is being serially changed by Root72 and is NOT this packet's compiled input.
    # The actual71 JAR pins remain the authoritative dependency, current Store is read-only context.
    for path in source_paths[:3]:
        assert hashlib.sha256((CANDIDATE / path).read_bytes()).hexdigest() == recorded[path]
        checks.append({'name': 'unchanged actual71 source dependency ' + path, 'passed': True})
    sdk = Path('C:/Program Files (x86)/Windows Kits/10/Include/10.0.26100.0/shared/ipifcons.h')
    sdk_text = sdk.read_text(encoding='utf-8-sig')
    for name, value in [('IF_TYPE_IEEE80211', '71'), ('IF_TYPE_WWANPP', '243'), ('IF_TYPE_WWANPP2', '244')]:
        assert any(line.split()[:3] == ['#define', name, value] for line in sdk_text.splitlines())
        checks.append({'name': 'official local SDK ' + name + '=' + value, 'passed': True})
    prepared = HERE / 'prepared/manual/com/bilipai/desktop/ui'
    network = (prepared / 'DesktopOriginalVideoOwnerNetworkBinding.kt').read_text(encoding='utf-8')
    diagnostics = (prepared / 'DesktopOriginalVideoOwnerDiagnosticsBinding.kt').read_text(encoding='utf-8')
    assertions = {
        'no network false fallback': 'catch' not in network and 'platform.currentNetwork()' in network,
        'network before/after entry guard': network.count('if (!stillOwned())') == 2,
        'same original owner interfaces': ': DesktopOriginalVideoOwnerNetwork' in network and ': DesktopOriginalVideoOwnerAnalytics, DesktopOriginalVideoOwnerCrash' in diagnostics,
        'same global preference keys': all(key in diagnostics for key in ['analytics_enabled', 'crash_tracking_enabled']),
        'original event names and non-sensitive quality payload': all(value in diagnostics for value in ['event=video_play', 'event=quality_change', 'from_quality=', 'to_quality=']),
        'no identity payload': '$videoId' not in diagnostics and '$title' not in diagnostics and '$author' not in diagnostics,
        'no wait/fatal/write/flush in effect consumer': all(value not in diagnostics for value in ['.await(', '.get(', '.flush(', '.persistLocalCrash(', 'Files.', '.update(', 'Thread(', 'CoroutineScope(']),
        'queue only existing diagnostic actor': 'consumer.record(level, tag, safe)' in diagnostics and 'private val diagnostics: DesktopDiagnostics?' in diagnostics,
        'no new client/firebase/notification transport': all(value not in diagnostics + network for value in ['OkHttpClient(', 'Native.load(', 'FirebaseAnalytics', 'FirebaseCrashlytics']),
    }
    for name, passed in assertions.items():
        assert passed, name
        checks.append({'name': name, 'passed': passed})
    result = {'passed': True, 'scope': 'source checks + one actual71 narrow compilation; no execution/Root/native/analytics upload claims',
              'checks': checks, 'checkCount': len(checks), 'originalSourceIdentities': pins,
              'currentReadOnlyDependencies': input_pins, 'officialLocalSdk': row(sdk),
              'selectedPolicyAnchors': {
                  originals[0]: {'isWifi': [28, 33], 'isMobileData': [38, 43], 'originalQualityMapping': [60, 73]},
                  originals[1]: {'privacy': [17, 43], 'videoPlay': [315, 345], 'qualityChange': [704, 716]},
                  originals[2]: {'sensitiveVideoId': [17, 25], 'headroom': [57, 74], 'videoError': [399, 429], 'ratePolicy': [117, 118, 594, 607]},
                  originals[3]: {'defaultTrue': [96, 97], 'keysGetters': [6237, 6244, 6280, 6285]},
              },
              'queueAdmissionReview': {'path': source_paths[2], 'recordLines': [75, 87], 'submitLines': [51, 64],
                  'finding': 'record sanitizes and synchronously enqueues on the existing writer; collector.add/file IO is within the submitted lambda. No blocking future.get/flush/close/persistLocalCrash is called by this adapter. Accepted enqueue is in-flight; owner retirement does not revoke an already accepted diagnostic write.'},
              'platformBoundaries': ['no Firebase/Crashlytics transport or custom-key/session/last-event backend',
                  'I events need original analytics_enabled AND current DesktopDiagnostics enhanced-local-I policy; E non-fatal errors need original crash_tracking_enabled',
                  'original non-fatal rate/headroom policy is retained in this entry effect view; no global remote dedupe/session claim',
                  'Wi-Fi is the preferred real WinRT profile IANA71; WWAN uses the same original Root observation; absence false, failures typed unavailable',
                  'original getDefaultQualityId is inherited unchanged from existing owner interface; no new formula'],
              'runtimeProof': False}
    (HERE / 'source-audit.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print('PASS', len(checks), 'source checks')

if __name__ == '__main__':
    main()
