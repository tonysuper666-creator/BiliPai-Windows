"""Freeze raw test/evidence only. Runtime/task data are indexed exclusions, not payload."""
from pathlib import Path
import hashlib, json, sys
sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
PREFIX = chr(92)*2+'?'+chr(92)
def safe(p):
    value = str(Path(p).absolute()); return Path(value if value.startswith(PREFIX) else PREFIX+value)
def sha(p): return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p, value): safe(p).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8', newline='\n')
assert not (HERE/'frozen-handoff.json').exists(), 'Do not overwrite frozen evidence'
latest = json.loads(safe(HERE/'latest-success.json').read_text(encoding='utf-8'))
success = Path(latest['attempt'])
evidence = json.loads(safe(success/'compile-runtime-evidence.json').read_text(encoding='utf-8'))
assert evidence['exitCode'] == 0 and evidence['productClassOverlap'] == 0 and evidence['productOverrides'] == 0
for item in evidence['immutableRuntime']: assert sha(item['path']) == item['sha256Bytes']
sources = {
    'app/src/main/java/com/android/purebilibili/feature/settings/screen/SettingsSearchScreen.kt': [(100,119)],
    'app/src/main/java/com/android/purebilibili/feature/settings/SettingsRootCategoryPolicy.kt': [(98,151)],
    'app/src/main/java/com/android/purebilibili/feature/settings/SettingsSearchNavigationPolicy.kt': [(1,43)],
    'app/src/main/java/com/android/purebilibili/feature/settings/screen/SettingsCategoryScreen.kt': [(12,58)],
    'app/src/main/java/com/android/purebilibili/feature/settings/screen/SettingsScreen.kt': [(187,197),(293,315),(578,631)],
    'app/src/main/java/com/android/purebilibili/navigation/AppNavigation.kt': [(3148,3195)],
}
registry = json.loads(safe(REPO/'desktop/upstream-sources.json').read_text(encoding='utf-8'))
pins = {i['path']:i['sha256'] for i in registry['sources']}
routing = []
for path, ranges in sources.items():
    data = safe(REPO/path).read_bytes(); text = data.decode('utf-8').replace('\r\n','\n').replace('\r','\n')
    digest = hashlib.sha256(text.encode('utf-8')).hexdigest()
    if path in pins: assert pins[path] == digest, path
    if path.endswith('/screen/SettingsScreen.kt'):
        assert 'SettingsSearchFocusController' not in text
        assert 'focusRequest' not in text
        assert 'val onImageSavePathAction = { showImageSavePathDialog = true }' in text
    if path.endswith('/SettingsSearchNavigationPolicy.kt'):
        assert 'BiliPaiNavKey.SettingsCategory(category)' in text
    lines = text.splitlines()
    routing.append(dict(path=path, sha256Bytes=hashlib.sha256(data).hexdigest(), sha256Lf=digest,
        sourceRegistryIdentityPresent=path in pins,
        snippets=[dict(firstLine=a,lastLine=b,text='\n'.join(lines[a-1:b])) for a,b in ranges]))
diagnostic = json.loads(safe(HERE/'attempts/run-03/proof/material3-failure-diagnostics.json').read_text(encoding='utf-8'))
assert 'target=IMAGE_SAVE_PATH' in diagnostic['results'] and 'target=DATA_BACKUP' not in diagnostic['results']
write(HERE/'original-routing-evidence.json', dict(upstreamTag=registry['upstreamTag'], upstreamCommit=registry['upstreamCommit'],
    sources=routing, actualMain04HistoricalSearchDiagnostic=diagnostic, productCorrectionRequired=False,
    correctedFixtureExpectation='original search result -> STORAGE_BACKUP category -> actual row -> original dialog',
    initialSceneCausalExplanationCorrect=False, finalSceneMembershipConclusion='IMAGE_SAVE_PATH is not a scene target',
    rawOriginalSettingsScreenHasFocusControllerConsumer=False, searchDirectDetailAccepted=False))
write(HERE/'metadata-correction.json', dict(failedExpectation='search click directly reaches IMAGE_SAVE_PATH Detail/dialog',
    run01='Private Skiko ICU native-readable resource-path initialization failure before UI acceptance.',
    run02='Detail wait failed after storage row/null chooser passed; no stage dump in this earlier attempt.',
    run03='Actual one IMAGE_SAVE_PATH result followed owning category; timeout expectation was incorrect. Semantics/navigation retained.',
    run04='Same Main04 product bytes; original verified category/row path passed in both styles.',
    originalSourceChange=False, productSourceChange=False, changedFixtureExpectationOnly=True,
    imperfectInitialMiuixCapture='First successful storage dialog PNG captures enter animation near lower edge; later dialog complete.',
    nativeWindowOrSettledNativeCaptureClaim=False))
artifacts = []; exclusions = []
for path in sorted(safe(HERE).rglob('*')):
    if not path.is_file(): continue
    relative = path.relative_to(safe(HERE)).as_posix()
    if relative == 'frozen-handoff.json': continue
    item = dict(path=relative, bytes=path.stat().st_size, sha256Bytes=sha(path))
    if '/private-environment/' in '/'+relative or '/native/' in '/'+relative or path.suffix.lower() in ['.jar','.dll','.class','.dat']:
        item['reason'] = 'Runtime binary or temporary task backing; evidence only, never product/install/commit payload.'
        exclusions.append(item)
    else: artifacts.append(item)
write(HERE/'excluded-runtime-artifacts.json', dict(artifacts=exclusions))
artifacts.append(dict(path='excluded-runtime-artifacts.json',bytes=(HERE/'excluded-runtime-artifacts.json').stat().st_size,
    sha256Bytes=sha(HERE/'excluded-runtime-artifacts.json')))
result = json.loads(safe(success/'proof/result.json').read_text(encoding='utf-8'))
assert result['passed'] and len(result['flows']) == 2
assert sum(i['actualPointerPairs'] for i in result['flows']) == 12
assert sum(i['actualSearchEditorActions'] for i in result['flows']) == 2
write(HERE/'frozen-handoff.json',dict(frozen=True, scope='Actual Main04 mounted original image-save SettingsTree/row/dialog/global-prefs proof',
    artifacts=artifacts,rawArtifactCount=len(artifacts),excludedRuntimeArtifactCount=len(exclusions),
    productOverrides=0, actualMainSnapshotManifestSha256=evidence['snapshotManifestSha256'],
    actualMainOrderedCpSha256=evidence['orderedRuntimeCpSha256'], runtimeCpCount=len(evidence['immutableRuntime']),
    successfulAttempt='attempts/run-04',twoThemeFlows=2,actualPointerPairs=12,actualSearchEditorActions=2,
    productCorrectionRequired=False, searchDirectDetailAccepted=False, actualSystemChooser=False,
    fullShellMounted=False, nativeWindow=False, packagedExecutableAccepted=False))
print('FROZEN', len(artifacts), 'raw artifacts; SHA256', sha(HERE/'frozen-handoff.json'))
