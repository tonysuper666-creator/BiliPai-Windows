from pathlib import Path
import hashlib
import json
import zipfile

HERE = Path(__file__).resolve().parent
MAIN = next(p for p in HERE.parents if (p / '.git').exists())
SNAP = MAIN / 'desktop/.local/stable-product-snapshot-27'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load(path):
    return json.loads(path.read_text(encoding='utf-8'))


def save(path, value):
    assert not path.exists(), path
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')


def pin(path):
    return {'path': str(path), 'sha256Bytes': sha(path), 'bytes': path.stat().st_size}


def main():
    snapshot_pins = {
        'manifest.json': '908d5cfdb65a99622fd2822771641b17d2bf35efe501a71ffcd231eace8c1c53',
        'ordered-runtime-cp.json': '8dcd9678a9f669793267bfb530fda1c09ef3225c50cf54682a12228636afd772',
        'main-kotlin.jar': '913351acaa508b924f738af0b0d1c3f4ca5a6e33c53388e99c271f37eec1a55f',
    }
    for name, expected in snapshot_pins.items():
        assert sha(SNAP / name) == expected, name
    run = HERE / 'runs/04-actual27'
    accepted = load(run / 'accepted-comparison.json')
    compilation = load(run / 'compile-result.json')
    assert accepted['status'] == 'PASS'
    assert (accepted['cells'], accepted['assertions'], accepted['pointerPairs'], accepted['actualEditableTextActions']) == (2, 32, 58, 6)
    assert accepted['productionClassOverrides'] == compilation['productionSourceInputs'] == 0
    assert compilation['productionClassOverlap'] == []
    assert compilation['status'] == 'PASS'
    assert sha(HERE / 'DanmakuSettingsUiFixture.kt') == compilation['sourceInputs'][0]['sha256Bytes']
    assert sha(run / 'settings-main26-ui-fixture.jar') == compilation['jarSha256Bytes']
    before = load(run / 'runtime-pins-before.json')
    after = load(run / 'runtime-pins-after.json')
    cp = load(SNAP / 'ordered-runtime-cp.json')
    assert len(before) == len(after) == len(cp) == 92
    for a, b, expected in zip(before, after, cp):
        assert a['path'] == b['path'] == expected['path']
        assert a['expected'] == a['actual'] == b['expected'] == b['actual'] == expected['sha256Bytes']
    with zipfile.ZipFile(SNAP / 'main-kotlin.jar') as z:
        for cell in accepted['results']:
            assert cell['status'] == 'PASS' and len(cell['actualCodeSources']) == 11
            assert cell['productionClassOverrides'] == 0
            assert cell['RootOwnerAccountHWNDIntegrated'] is False
            assert cell['inputChooserMode'] == 'explicit-memory-only'
            for row in cell['actualCodeSources']:
                assert row['codeSource'].endswith('/stable-product-snapshot-27/main-kotlin.jar')
                assert hashlib.sha256(z.read(row['class'].replace('.', '/') + '.class')).hexdigest() == row['classSha256Bytes']
    receipt = {
        'status': 'PASS',
        'phase': 'actual-main27-installed-full-settings-ui',
        'targetOriginalCommit': '3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',
        'actualSnapshot': [pin(SNAP / name) for name in snapshot_pins],
        'acceptedComparison': pin(run / 'accepted-comparison.json'),
        'compileResult': pin(run / 'compile-result.json'),
        'fixtureSource': pin(HERE / 'DanmakuSettingsUiFixture.kt'),
        'runnerSource': pin(HERE / 'run.py'),
        'results': {'cells': 2, 'styles': ['MATERIAL3', 'MIUIX'], 'viewport': [1100, 900], 'assertions': 32,
                    'pointerPairs': 58, 'actualEditableTextActions': 6, 'loadedActualClassBytePinsPerCell': 11,
                    'runtimeClasspathPinsVerifiedBeforeAndAfter': 92, 'productionSourceInputs': 0,
                    'productionClassOverrides': 0, 'productionClassOverlap': []},
        'testedActions': [
            'Actual SettingsHost, original full Panel and BlockManager rendered; corrected Host/Root/list Host classes forcibly loaded.',
            'Actual thumb drag for font and opacity, original scope switch, basic/advanced/blocking tabs and scoped switches.',
            'Original keyword add/save, edited draft cancel, delete-and-replace/save and independent portrait/landscape persisted keys in the same actual global Store.',
            'Declared memory-only chooser cancellation and one imported JSON stream partitioned keyword/regex/UID rules through original import confirmation and tabs.',
            'One original close callback; required unused callbacks fail explicitly; fixture account state controls original guest/login cloud visibility.',
        ],
        'boundaries': [
            'Only fixture code compiled; actual Main27 production classes were not replaced.',
            'ImageComposeScene offscreen pointer/scroll/semantics-edit proof, not native HWND or physical IME proof.',
            'Guest/login and three-state presentation are explicit fixture inputs, not Root account/window integration.',
            'Chooser and cloud transport ports are explicit in-memory fixture implementations; no HTTP, real account, native chooser or external application accessed.',
            'Root owner/epoch integration, Android presentation parity and unsupported advanced Windows rendering algorithms are not claimed by this UI proof.',
            'Actual Main27 immutable runtime bytes remain the baseline even if the live fork is upgraded later.',
        ],
        'preservedHistory': [
            {'path': 'runs/01', 'status': 'FAIL', 'reason': 'Fixture-only Kotlin syntax error; no runtime acceptance.'},
            {'path': 'runs/02', 'status': 'FAIL', 'reason': 'Actual Main26 illegal NON_LOCAL_RETURN anonymous method reference raised ClassFormatError before UI draw.'},
            {'path': 'runs/03-actual27', 'status': 'FAIL', 'reason': 'Material3 completed; Miuix slider track tap did not commit. Final fixture uses the original draggable thumb with real pointer moves.'},
            {'path': 'host-nonlocal-return-delta', 'status': 'FROZEN-PROSPECTIVE', 'reason': 'Separate historical minimal Host-only fix and prospective classload proof; not counted as Main27 zero-override UI execution.'},
            {'path': 'host-hash-metadata-correction/correction.json', 'status': 'APPEND-ONLY-CORRECTION', 'reason': 'Corrects two historical source LF metadata hashes affected by implicit Windows text decoding. Original frozen payload/runtime bytes remain unchanged.'},
        ],
        'modifications': 'Only this ignored evidence lane; no shared production source, Gradle, source registry or frozen payload changed.',
    }
    save(HERE / 'acceptance-receipt.json', receipt)
    rows = []
    for p in sorted(HERE.rglob('*')):
        if p.is_file() and p.name != 'frozen-handoff.json':
            row = pin(p)
            row['relativePath'] = p.relative_to(HERE).as_posix()
            rows.append(row)
    frozen = {'status': 'FROZEN', 'cohort': 'actual-main27-full-settings-ui-and-preserved-main26-history',
              'artifactCount': len(rows), 'acceptanceReceipt': pin(HERE / 'acceptance-receipt.json'),
              'acceptedComparison': pin(run / 'accepted-comparison.json'), 'artifactRows': rows,
              'hashSemantics': 'SHA-256 of exact file bytes, no text normalization. UTF-8 is explicit for authored JSON.',
              'historicalNestedFrozenManifest': pin(HERE / 'host-nonlocal-return-delta/frozen-handoff.json')}
    save(HERE / 'frozen-handoff.json', frozen)
    print(json.dumps({'manifest': pin(HERE / 'frozen-handoff.json'), 'artifacts': len(rows),
                      'accepted': pin(run / 'accepted-comparison.json'), 'receipt': pin(HERE / 'acceptance-receipt.json')}, ensure_ascii=False))


if __name__ == '__main__':
    main()
