"""Fixed complete original theme roles and compositing policy; no parallel theme store."""
from pathlib import Path
import hashlib
import json
from v025_source_paths import canonical_source

COMMIT = '415d01ec5bc3ac82521a9ac47bcc449b8e202460'
ARCHIVE = 'desktop/upstream-slices/v040-theme-roles'
MANIFEST_SHA256 = '978e9bb2961d155b0220f5c48b3e14e847dabc1df47c319bd51c24162872d344'
PINS = [{'path': 'app/src/main/java/com/android/purebilibili/core/theme/ThemeRoleOverridePolicy.kt', 'archiveFile': 'ThemeRoleOverridePolicy.kt', 'bytes': 8168, 'sha256Bytes': 'adfefc29f8a2b33039919b29ccf673d4f21b52f5f4149dee68dd41ead06ac117', 'gitBlob': 'e7fe365276759eae6a49e44bec7c975c84127e69'}, {'path': 'design-system/src/main/java/com/android/purebilibili/core/theme/ThemeContrastPolicy.kt', 'archiveFile': 'ThemeContrastPolicy.kt', 'bytes': 7045, 'sha256Bytes': '6e5d3dea746bcbb34e7e3523d044901c350e1252216942539b836eb9b67c27bd', 'gitBlob': 'f3a60df850d9624ba00e7a85d231ba9df0448cd6'}, {'path': 'app/src/test/java/com/android/purebilibili/core/theme/ThemeRoleOverridePolicyTest.kt', 'archiveFile': 'ThemeRoleOverridePolicyTest.kt', 'bytes': 7308, 'sha256Bytes': 'c06b8168d08bac4d07ad2ea7f58b83e3858d04529c82f381b112e27e8a0341d5', 'gitBlob': '2ff21871607b08a84d0d4b543d67e7de7c9fb268'}, {'path': 'design-system/src/test/java/com/android/purebilibili/core/theme/ThemeContrastPolicyTest.kt', 'archiveFile': 'ThemeContrastPolicyTest.kt', 'bytes': 6299, 'sha256Bytes': 'b89b4cfeb8b9bfb9a5b143300841bc205698f7758502a2ddd9c80d87a0d471cb', 'gitBlob': '6414c517a699faee8c0db1c511fcef227c8b74bb'}]
MAIN_PATHS = tuple(row['path'] for row in PINS if '/main/java/' in row['path'])


def sha(raw): return hashlib.sha256(raw).hexdigest()


def fixed_sources(repo):
    root = Path(repo)/ARCHIVE
    raw = (root/'manifest.json').read_bytes()
    if sha(raw) != MANIFEST_SHA256:
        raise ValueError('Unreviewed theme-role source manifest')
    manifest = json.loads(raw)
    if manifest['fixedUpstreamCommit'] != COMMIT or manifest['files'] != PINS:
        raise ValueError('Unreviewed theme-role source identity')
    result = {}
    for row in PINS:
        raw = (root/row['archiveFile']).read_bytes()
        blob = hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()
        if len(raw) != row['bytes'] or sha(raw) != row['sha256Bytes'] or blob != row['gitBlob']:
            raise ValueError('Modified fixed theme-role source: '+row['path'])
        result[row['path']] = raw
    return result


def selected_source(repo, path):
    # Validate both the newer archive and the retained canonical input identity.
    previous = canonical_source(Path(repo), path).read_bytes()
    return fixed_sources(repo)[path] if path in MAIN_PATHS else previous


def selected_test_source(path, raw):
    """Keep originals; bind three legacy color assumptions to the pinned SPEC_2025 oracle.

    Production policies remain untouched. The independent JVM contracts must confirm
    this oracle before acceptance; the original failed run is retained separately.
    """
    text = raw.decode('utf-8')
    original = text
    edits = []
    if path.endswith('/ThemeRoleOverridePolicyTest.kt'):
        changes = [
            ('import kotlin.test.Test\n', 'import kotlin.test.Test\nimport com.materialkolor.PaletteStyle\nimport com.materialkolor.dynamicColorScheme\nimport com.materialkolor.dynamiccolor.ColorSpec\n'),
            ('assertEquals(Color.White, result.onPrimary)', 'assertEquals(spec2025ControlOnPrimary(overrides.light.controlAccentHex), result.onPrimary)'),
            ('assertEquals(Color.White, light.onPrimary)', 'assertEquals(spec2025ControlOnPrimary(overrides.light.controlAccentHex), light.onPrimary)'),
            ('assertEquals(light.primaryFixed, dark.primaryFixed)',
             'assertEquals(spec2025ControlScheme(overrides.light.controlAccentHex, false).primaryFixed, light.primaryFixed)\n        assertEquals(spec2025ControlScheme(overrides.dark.controlAccentHex, true).primaryFixed, dark.primaryFixed)'),
        ]
        for before, after in changes:
            if text.count(before) != 1:
                raise ValueError('Unreviewed original theme-role test expectation')
            at = text.index(before)
            edits.append(dict(offset=at, before=before, after=after))
            text = text[:at] + after + text[at + len(before):]
        extra = '''
// The production default explicitly requests SPEC_2025. Compare the exact
// pinned library role instead of imposing a hardcoded pure-white color.
private fun spec2025ControlOnPrimary(seedHex: String): Color = spec2025ControlScheme(seedHex, false).onPrimary

// SPEC_2025 can generate different tonal palettes in the two display modes.
private fun spec2025ControlScheme(seedHex: String, dark: Boolean): androidx.compose.material3.ColorScheme = dynamicColorScheme(
    seedColor = parseMd3CustomColorHex(seedHex),
    isDark = dark,
    style = PaletteStyle.TonalSpot,
    specVersion = ColorSpec.SpecVersion.SPEC_2025,
)
'''
        edits.append(dict(offset=len(text), before='', after=extra))
        text += extra
    inverse = text
    for edit in reversed(edits):
        at, after = edit['offset'], edit['after']
        if inverse[at:at + len(after)] != after:
            raise ValueError('Theme-role test inverse boundary changed')
        inverse = inverse[:at] + edit['before'] + inverse[at + len(after):]
    if inverse != original:
        raise ValueError('Complete original theme-role test was not retained')
    return text.encode(), dict(sourcePath=path, fixedCommit=COMMIT,
        originalSHA256=sha(raw), adaptedSHA256=sha(text.encode()), edits=edits,
        fullOriginalInverseRecovered=True, productSourceChanged=False,
        directPinnedLibraryContractNotYetExecuted=True)


def emit_tests(repo, output):
    for path, raw in fixed_sources(repo).items():
        if '/test/java/' not in path:
            continue
        relative = path.split('/test/java/', 1)[1]
        target = Path(output)/relative
        target.parent.mkdir(parents=True,exist_ok=True)
        selected, proof = selected_test_source(path, raw)
        target.write_bytes(selected)
        proof_path = Path(output) / 'v040-theme-test-expectation-proofs' / (Path(path).name + '.json')
        proof_path.parent.mkdir(parents=True, exist_ok=True)
        proof_path.write_text(json.dumps(proof, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
