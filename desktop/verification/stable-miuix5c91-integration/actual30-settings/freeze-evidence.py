from pathlib import Path
import hashlib
import json
import zipfile

HERE = Path(__file__).resolve().parent
MAIN = next(p for p in HERE.parents if (p / '.git').exists())
SNAP = MAIN / 'desktop/.local/stable-product-snapshot-30'
PRIOR = MAIN / 'desktop/.local/stable-danmaku-settings-main26-ui-proof'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def safe(path):
    value = str(Path(path).resolve())
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\' + value)


def load(path):
    return json.loads(path.read_text(encoding='utf-8'))


def pin(path):
    return {'path': str(path), 'sha256Bytes': sha(path), 'bytes': path.stat().st_size}


def save(path, value):
    assert not path.exists(), path
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')


def main():
    expected = {'manifest.json': 'e69b7b08805fc7a6aaec8557e8ba1cfd2368b701f99ecb94edc672efd22b6b69',
                'ordered-runtime-cp.json': 'dcab5cb81f9c62929c07966f76fe3bfd54ee0e7b93614296c576b24f218071c0',
                'main-kotlin.jar': '2b98eff5f8d6d796c71cfe99b142e2870740c446df967169235d3aab2b19ceb1'}
    for name, digest in expected.items():
        assert sha(SNAP / name) == digest, name
    contract = load(HERE / 'phase-contract.json')
    assert sha(PRIOR / 'frozen-handoff.json') == contract['previousFrozenManifestSha256Bytes'] == 'f8e3c7bc6317e8bbbe35e857036432da4344bdc109f2e8446f2ec706cb29bf46'
    assert (HERE / 'DanmakuSettingsUiFixture.kt').read_bytes() == (PRIOR / 'DanmakuSettingsUiFixture.kt').read_bytes()
    prior_runner = (PRIOR / 'run.py').read_text(encoding='utf-8')
    for a, b in contract['runnerChangesOnly'].items():
        prior_runner = prior_runner.replace(a, b)
    assert prior_runner == (HERE / 'run.py').read_text(encoding='utf-8')
    run = HERE / 'runs/01-actual30'
    accepted = load(run / 'accepted-comparison.json')
    compilation = load(run / 'compile-result.json')
    assert accepted['status'] == compilation['status'] == 'PASS'
    assert (accepted['cells'], accepted['assertions'], accepted['pointerPairs'], accepted['actualEditableTextActions']) == (2, 32, 58, 6)
    assert accepted['productionClassOverrides'] == compilation['productionSourceInputs'] == 0
    assert compilation['productionClassOverlap'] == []
    assert sha(HERE / 'DanmakuSettingsUiFixture.kt') == compilation['sourceInputs'][0]['sha256Bytes']
    assert sha(run / 'settings-main26-ui-fixture.jar') == compilation['jarSha256Bytes']
    cp = load(SNAP / 'ordered-runtime-cp.json')
    before, after = (load(run / f'runtime-pins-{x}.json') for x in ('before', 'after'))
    assert len(cp) == len(before) == len(after) == 92
    for c, a, b in zip(cp, before, after):
        assert c['path'] == a['path'] == b['path']
        assert c['sha256Bytes'] == a['actual'] == a['expected'] == b['actual'] == b['expected']
    with zipfile.ZipFile(SNAP / 'main-kotlin.jar') as z:
        for cell in accepted['results']:
            assert cell['status'] == 'PASS' and len(cell['actualCodeSources']) == 11
            assert cell['RootOwnerAccountHWNDIntegrated'] is False
            for row in cell['actualCodeSources']:
                assert row['codeSource'].endswith('/stable-product-snapshot-30/main-kotlin.jar')
                assert hashlib.sha256(z.read(row['class'].replace('.', '/') + '.class')).hexdigest() == row['classSha256Bytes']
    library = next(x for x in cp if Path(x['path']).name.startswith('miuix5157-jvm-'))
    assert library['sha256Bytes'] == contract['snapshot30MiuixSha256Bytes'] == 'e574a17de8136c12212f41c90ff35ba7b46818eef25e83b7c9f9386015da843e'
    # This is a static supplier check; the executed production CodeSources are reported separately.
    library_types = ('top/yukonga/miuix/kmp/basic/SliderKt.class', 'top/yukonga/miuix/kmp/theme/MiuixThemeKt.class')
    suppliers = {name: [] for name in library_types}
    for c in cp:
        with zipfile.ZipFile(safe(c['path'])) as z:
            for name in library_types:
                if name in z.namelist():
                    suppliers[name].append({'path': c['path'], 'jarSha256Bytes': c['sha256Bytes'], 'classSha256Bytes': hashlib.sha256(z.read(name)).hexdigest()})
    assert all(len(rows) == 1 and rows[0]['jarSha256Bytes'] == library['sha256Bytes'] for rows in suppliers.values())
    save(HERE / 'library-type-supplier-review.json', {'status': 'STATIC_ACTUAL30_CLASSPATH_REVIEW',
        'types': suppliers, 'scope': 'Exactly one source-fork JAR supplies these Miuix UI types. This static check is not a replacement for loaded-class evidence or native UI proof.'})
    save(HERE / 'acceptance-receipt.json', {
        'status': 'PASS', 'phase': 'actual-main30-same-frozen-settings-ui-regression-after-5c91-library-upgrade',
        'actualSnapshot': [pin(SNAP / n) for n in expected], 'miuix5c91Library': library,
        'priorUnchangedCohort': pin(PRIOR / 'frozen-handoff.json'), 'fixtureByteIdenticalToMain27': True,
        'runnerDifference': 'Only fixed snapshot paths, manifest/CP hashes and Main30 scope label; no behavior changes.',
        'acceptedComparison': pin(run / 'accepted-comparison.json'), 'compileResult': pin(run / 'compile-result.json'),
        'counts': {'styles': 2, 'assertions': 32, 'pointerPairs': 58, 'editableTextActions': 6,
                   'actualLoadedCodeSourceAndClassBytePinsPerCell': 11, 'verifiedRuntimePinsBeforeAndAfter': 92,
                   'productionSourceInputs': 0, 'productionClassOverrides': 0, 'productionClassOverlap': []},
        'scope': accepted['scope'],
        'limits': ['Real offscreen pointer/scroll and editable semantics, not native HWND or physical IME.',
                   'Account/presentation are explicit fixture states, chooser/cloud memory-only ports; no Root account/window/HTTP acceptance.',
                   'Full original Panel/Host actions from the Main27 fixture retained, including scoped settings and full BlockManager add/edit/cancel/import/close.',
                   'Existing Main26 and Main27 failures stay in the prior frozen cohort; Main28/29 build history belongs to Root, not this runtime proof.',
                   'Advanced Windows rendering algorithms and Miuix nav module are not covered by this UI proof.'],
        'sharedModifications': False,
    })
    rows = []
    for p in sorted(HERE.rglob('*')):
        if p.is_file() and p.name != 'frozen-handoff.json':
            rows.append({'relativePath': p.relative_to(HERE).as_posix(), **pin(p)})
    save(HERE / 'frozen-handoff.json', {'status': 'FROZEN', 'cohort': 'actual-main30-same-settings-ui-5c91',
        'artifactCount': len(rows), 'artifactRows': rows, 'acceptanceReceipt': pin(HERE / 'acceptance-receipt.json'),
        'acceptedComparison': pin(run / 'accepted-comparison.json'), 'hashSemantics': 'Exact SHA-256 file bytes; no text normalization.'})
    print(json.dumps({'manifest': pin(HERE / 'frozen-handoff.json'), 'artifacts': len(rows),
                      'accepted': pin(run / 'accepted-comparison.json'), 'receipt': pin(HERE / 'acceptance-receipt.json')}, ensure_ascii=False))


if __name__ == '__main__':
    main()
