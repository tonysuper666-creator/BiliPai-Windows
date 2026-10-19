"""Whole v0.4.6 liquid glass tuning values on the existing shared-liquid-tabs producer.

LiquidGlassTuning.kt and BottomBarGlassMaterialPolicy.kt are emitted by
extract-upstream-shared-liquid-tabs.py. At the v0.2.5 pin both differ from v0.4.6 only by upstream
commit 4e15f578b, so each is replaced by the whole v0.4.6 file. No Windows adaptation is needed: the
files use no Android API. The visual parameters that move are exactly the ones listed in
VISUAL_PARAMETER_CHANGES; every hunk is replayed on the predecessor and must reproduce the archive
bytes. The original LiquidGlassTuningTest is emitted for the Windows test source set with only its
three JUnit imports mapped to kotlin.test. BottomBarGlassMaterialPolicyTest is not carried: it calls
BottomBar.kt declarations that the Windows producer does not select.
"""
from pathlib import Path
import hashlib
import json

COMMIT = 'd6bf8b1655ae910999860bf13e65c8fcea5b345b'
ARCHIVE = 'desktop/upstream-slices/v046-chrome-glass-tuning'
MANIFEST_SHA256 = 'a69ad532fd462d266ffec3bd21eb78cbb66f8d742b3d5147a3b0093fec0bc9b4'
HOME = 'app/src/main/java/com/android/purebilibili/feature/home/components/'
TUNING = HOME + 'LiquidGlassTuning.kt'
MATERIAL = HOME + 'BottomBarGlassMaterialPolicy.kt'
TUNING_TEST = 'app/src/test/java/com/android/purebilibili/feature/home/components/LiquidGlassTuningTest.kt'
MAIN_PATHS = (TUNING, MATERIAL)
UPSTREAM_PATHS = {
    'LiquidGlassTuning.kt': TUNING,
    'BottomBarGlassMaterialPolicy.kt': MATERIAL,
    'LiquidGlassTuningTest.kt': TUNING_TEST,
}
EXPECTED = set(UPSTREAM_PATHS)
PACKAGE_LINE = 'package com.android.purebilibili.feature.home.components\n'
TEST_RELATIVE = 'com/android/purebilibili/feature/home/components/LiquidGlassTuningTest.kt'
TEST_CASES = 18
# LF-normalized sha256 of the v0.2.5 pinned bodies; the same values desktop/upstream-sources.json records.
PREDECESSOR_SHA256 = {
    TUNING: 'beb3ad5b46e00191501dbd36befabf7ad768812f0935cf272501a4bed88ecb81',
    MATERIAL: '2c5e1b8e308165a3e97b1bc57cb5385808735f75f01279a9aeafbda600ecec0b',
}
TEST_IMPORTS = (
    ('import org.junit.Assert.assertEquals\n', 'import kotlin.test.assertEquals\n'),
    ('import org.junit.Assert.assertTrue\n', 'import kotlin.test.assertTrue\n'),
    ('import org.junit.Test\n', 'import kotlin.test.Test\n'),
)

HUNKS = {
    TUNING: (
        dict(id='frost-weight-unused',
             before='    val frostWeight = normalizedProgress\n'
                    '    val configuredReadability = advancedSettings.contentReadability.coerceIn(0f, 1f)\n',
             after='    val configuredReadability = advancedSettings.contentReadability.coerceIn(0f, 1f)\n'),
        dict(id='readability-protection-linear',
             before='    // A smooth fourth-power response keeps the balanced preset subtle while making the whole\n'
                    '    // 0..1 control range effective. The old threshold mapping made every value below 0.62 a\n'
                    '    // visual no-op even though the settings UI exposed that range.\n'
                    '    val readabilityProtection = configuredReadability * configuredReadability *\n'
                    '        configuredReadability * configuredReadability\n',
             after='    // Use the configured strength directly: the former fourth-power curve reduced the\n'
                   '    // default 62% protection to a barely visible scrim over detailed video covers.\n'
                   '    val readabilityProtection = configuredReadability\n'),
        dict(id='readability-scrim-alpha',
             before='    val contentReadabilityScrimAlpha = contentReadabilityBoost *\n'
                    '        (0.12f + readabilityProtection * 0.22f)\n',
             after='    val contentReadabilityScrimAlpha = contentReadabilityBoost * 0.34f\n'),
        dict(id='indicator-chromatic-boost',
             before='        indicatorChromaticBoost = midpointLerp(1.20f, 1f, 0.70f, frostWeight),\n',
             after='        indicatorChromaticBoost = 1f,\n'),
    ),
    MATERIAL: (
        dict(id='material-luminance-import',
             before='import androidx.compose.ui.graphics.Color\n'
                    'import androidx.compose.ui.graphics.luminance\n',
             after='import androidx.compose.ui.graphics.Color\n'),
        dict(id='container-alpha-no-preset-scale',
             before='    val isDarkSurface = surfaceColor.luminance() < 0.5f\n'
                    '    val alphaScale = when (preset) {\n'
                    '        BottomBarLiquidGlassPreset.BILIPAI_TUNED -> if (isDarkSurface) 0.75f else 0.95f\n'
                    '        BottomBarLiquidGlassPreset.IOS26_REFINED -> 1f\n'
                    '    }\n'
                    '    return surfaceColor.copy(alpha = (liquidGlassTuning.surfaceAlpha * alphaScale).coerceIn(0f, 1f))\n',
             after="    // Upstream uses the theme surface at 40% in the balanced material; keep the slider's\n"
                   '    // requested opacity without applying another preset-specific reduction.\n'
                   '    return surfaceColor.copy(alpha = liquidGlassTuning.surfaceAlpha.coerceIn(0f, 1f))\n'),
    ),
}

# The complete list of visual parameters v0.4.6 moves in these two files. Each entry names the hunks that
# implement it; a test proves the hunks and the entries cover each other exactly.
VISUAL_PARAMETER_CHANGES = (
    dict(id='readability-protection-curve', file='LiquidGlassTuning.kt',
         parameter='readabilityProtection (scales contentReadabilityBoost and contentReadabilityScrimAlpha)',
         before='contentReadability to the fourth power', after='contentReadability itself (linear)',
         hunks=('frost-weight-unused', 'readability-protection-linear')),
    dict(id='readability-scrim-alpha', file='LiquidGlassTuning.kt',
         parameter='contentReadabilityScrimAlpha',
         before='contentReadabilityBoost * (0.12 + readabilityProtection * 0.22)',
         after='contentReadabilityBoost * 0.34',
         hunks=('readability-scrim-alpha',)),
    dict(id='indicator-chromatic-boost', file='LiquidGlassTuning.kt',
         parameter='indicatorChromaticBoost',
         before='midpointLerp(1.20, 1.0, 0.70, progress): 1.20 clear, 1.0 balanced, 0.70 frosted',
         after='1.0 at every glass strength',
         hunks=('indicator-chromatic-boost',)),
    dict(id='bottom-bar-container-alpha', file='BottomBarGlassMaterialPolicy.kt',
         parameter='bottom bar glass container color alpha',
         before='surfaceAlpha * scale; BILIPAI_TUNED scale is 0.75 on a dark surface and 0.95 on a light surface, '
                'IOS26_REFINED scale is 1',
         after='surfaceAlpha for every preset and surface',
         hunks=('material-luminance-import', 'container-alpha-no-preset-scale')),
)


def sha(value):
    return hashlib.sha256(value.encode('utf8') if isinstance(value, str) else value).hexdigest()


def need(condition, message):
    if not condition:
        raise ValueError(message)


def fixed(repo):
    root = Path(repo) / ARCHIVE
    need(root.is_dir() and not root.is_symlink(), 'Missing original chrome glass tuning inputs')
    need({p.name for p in root.iterdir()} == EXPECTED | {'manifest.json', 'LICENSE'},
         'Chrome glass tuning input leaves changed')
    manifest_raw = (root / 'manifest.json').read_bytes()
    need(b'\r' not in manifest_raw and sha(manifest_raw) == MANIFEST_SHA256, 'Chrome glass tuning manifest changed')
    manifest = json.loads(manifest_raw)
    need((manifest['repository'], manifest['tag'], manifest['commit'], manifest['hashNormalization']) ==
         ('jay3-yy/BiliPai', 'v0.4.6', COMMIT, 'raw'), 'Chrome glass tuning source identity changed')
    need(len(manifest['files']) == len(EXPECTED) and {row['filename'] for row in manifest['files']} == EXPECTED,
         'Chrome glass tuning source set changed')
    sources = {}
    for row in manifest['files']:
        file = root / row['filename']
        need(file.is_file() and not file.is_symlink() and Path(row['path']).name == file.name,
             'Linked or ambiguous chrome glass tuning source')
        need(row['path'] == UPSTREAM_PATHS[file.name], 'Chrome glass tuning upstream path changed: ' + file.name)
        leaf = file.read_bytes()
        need(b'\r' not in leaf and len(leaf) == row['bytes'] and sha(leaf) == row['sha256'],
             'Chrome glass tuning bytes changed: ' + file.name)
        need(hashlib.sha1(b'blob ' + str(len(leaf)).encode() + b'\0' + leaf).hexdigest() == row['gitBlob'],
             'Chrome glass tuning Git blob changed: ' + file.name)
        text = leaf.decode('utf8')
        need(text.encode('utf8') == leaf and text.startswith(PACKAGE_LINE),
             'Chrome glass tuning text changed: ' + file.name)
        sources[file.name] = text
    need(sources['LiquidGlassTuningTest.kt'].count('\n    @Test\n') == TEST_CASES,
         'Chrome glass tuning test case count changed')
    license_file = root / 'LICENSE'
    need(not license_file.is_symlink() and sha(license_file.read_bytes()) == manifest['license']['sha256'],
         'Chrome glass tuning license changed')
    return sources, manifest


def validate_outputs(repo, *outputs):
    fixed(repo)
    root = (Path(repo) / ARCHIVE).resolve()
    for output in outputs:
        if output is None:
            continue
        target = Path(output).resolve()
        need(not (target.is_relative_to(root) or root.is_relative_to(target)),
             'Output overlaps original chrome glass tuning inputs')


def replay(text, hunks):
    for hunk in hunks:
        need(text.count(hunk['before']) == 1, 'Chrome glass tuning anchor changed: ' + hunk['id'])
        text = text.replace(hunk['before'], hunk['after'], 1)
    return text


def unreplay(text, hunks):
    for hunk in reversed(hunks):
        need(text.count(hunk['after']) == 1, 'Chrome glass tuning receipt changed: ' + hunk['id'])
        text = text.replace(hunk['after'], hunk['before'], 1)
    return text


def advance(repo, path, before):
    """The whole v0.4.6 file for a pinned predecessor body, with the receipt that recovers the predecessor."""
    need(path in MAIN_PATHS, 'Not a chrome glass tuning path: ' + path)
    sources, manifest = fixed(repo)
    leaf = Path(path).name
    predecessor = before.replace('\r\n', '\n')
    need(sha(predecessor) == PREDECESSOR_SHA256[path], 'Chrome glass tuning predecessor changed: ' + leaf)
    body = sources[leaf]
    hunks = HUNKS[path]
    need(replay(predecessor, hunks) == body, 'Chrome glass tuning hunks do not reproduce the v0.4.6 file: ' + leaf)
    need(unreplay(body, hunks) == predecessor, 'Chrome glass tuning inverse failed: ' + leaf)
    row = next(row for row in manifest['files'] if row['path'] == path)
    return body, dict(
        path=path, upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256, archive=ARCHIVE + '/' + leaf,
        wholeOriginal=True, windowsAdaptations=0, originalBytesSHA256=row['sha256'], originalGitBlob=row['gitBlob'],
        predecessorSHA256=sha(predecessor), originalSHA256=sha(body),
        hunks=[dict(id=h['id'], before=h['before'], after=h['after']) for h in hunks])


def restore(value, proof):
    """Recover the predecessor from an emitted body and its receipt."""
    need(sha(value) == proof['originalSHA256'], 'Chrome glass tuning output differs from its receipt')
    result = unreplay(value, proof['hunks'])
    need(sha(result) == proof['predecessorSHA256'], 'Chrome glass tuning predecessor inverse failed')
    return result


def selected_source(repo, path, previous):
    return advance(repo, path, previous)[0] if path in MAIN_PATHS else previous


def identity_for(repo, path):
    if path not in MAIN_PATHS:
        return None
    _, manifest = fixed(repo)
    row = next(row for row in manifest['files'] if row['path'] == path)
    return dict(pinnedCommit=COMMIT, originalPath=path, path=ARCHIVE + '/' + row['filename'],
                sha256Bytes=row['sha256'], gitBlobOid=row['gitBlob'], bytes=row['bytes'])


def adapt_test(raw):
    """Only the three JUnit4 imports change: the Windows test source set runs kotlin.test on JUnit 5."""
    need(raw.count('\n    @Test\n') == TEST_CASES, 'Chrome glass tuning test case count changed')
    text = raw
    for old, new in TEST_IMPORTS:
        need(text.count(old) == 1 and text.count(new) == 0, 'Chrome glass tuning test import changed: ' + old.strip())
        text = text.replace(old, new, 1)
    restored = text
    for old, new in TEST_IMPORTS:
        restored = restored.replace(new, old, 1)
    need(restored == raw, 'Original chrome glass tuning test inverse failed')
    return text


def test_files(repo):
    """Relative test path -> body for the Windows test source set (the producer writes them)."""
    raw = fixed(repo)[0]['LiquidGlassTuningTest.kt']
    return {TEST_RELATIVE: adapt_test(raw)}


def proof_text(repo):
    """The receipt the producer writes beside its inventory: hunks, visual parameter table, test mapping."""
    from v025_source_paths import canonical_source
    rows = []
    for path in MAIN_PATHS:
        before = canonical_source(Path(repo), path).read_text(encoding='utf-8').replace('\r\n', '\n')
        rows.append(advance(repo, path, before)[1])
    raw = fixed(repo)[0]['LiquidGlassTuningTest.kt']
    adapted = adapt_test(raw)
    document = dict(
        upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256, archive=ARCHIVE,
        changedByUpstreamCommit='4e15f578b4eccd79d2762ebb7056f63c421e3635',
        files=rows, visualParameterChanges=[dict(c, hunks=list(c['hunks'])) for c in VISUAL_PARAMETER_CHANGES],
        originalTest=dict(path=TUNING_TEST, emittedAs=TEST_RELATIVE, cases=TEST_CASES, originalSHA256=sha(raw),
                          emittedSHA256=sha(adapted), importMapping=[list(pair) for pair in TEST_IMPORTS]),
        notCarried=['BottomBarGlassMaterialPolicyTest'],
        kotlinCompiled=False, jvmTestsExecuted=False)
    return json.dumps(document, ensure_ascii=False, indent=2) + '\n'
