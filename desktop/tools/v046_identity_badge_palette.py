"""Whole v0.4.6 identity badge palette and its original suites.

The five vendored v0.2.5 direct-mode copies (AppBadge, UpBadgeNamePolicy, UserUpBadge,
UserAvatarCornerMark, OfficialVerifyBadgePolicy) are excluded in prepareUpstreamSources.
The home-page producer emits the complete v0.4.6 files instead; UserUpBadge.kt alone drops the
Android-only PlatformTextStyle(includeFontPadding = false) argument. The home refresh wrapper
emits the three upstream suites that exercise them into its original test root.

v0.4.6 resolveUpNameColor takes the annual-VIP colour as its `secondary` argument. Three compiled owners are produced
from originals older than v0.4.6 and still pass the theme secondary; up_name_vip_pink() pins the one line each of
their producers rewrites to userVipPink() (UP_NAME_MIGRATIONS). Every other compiled caller already passes it.
"""
from pathlib import Path
import hashlib
import json
import os
import stat

from v044_favorite_detail import inverse_proof

COMMIT = 'd6bf8b1655ae910999860bf13e65c8fcea5b345b'
ARCHIVE = 'desktop/upstream-slices/v046-identity-badge-palette'
MANIFEST_SHA256 = 'ee6f02a0dde776e44031537abee7e8cead416b4395b049b96c4330005507e3b0'
MAIN_FILES = (
    'AppBadge.kt',
    'OfficialVerifyBadgePolicy.kt',
    'UpBadgeNamePolicy.kt',
    'UserAvatarCornerMark.kt',
    'UserUpBadge.kt',
)
TEST_FILES = (
    'OfficialVerifyBadgePolicyTest.kt',
    'UpBadgeNamePolicyTest.kt',
    'UserAvatarCornerMarkTest.kt',
)
EXPECTED = set(MAIN_FILES) | set(TEST_FILES)
UI = 'com.android.purebilibili.core.ui'
COMPONENTS = UI + '.components'
PACKAGE = {
    'AppBadge.kt': COMPONENTS,
    'OfficialVerifyBadgePolicy.kt': UI,
    'UpBadgeNamePolicy.kt': COMPONENTS,
    'UserAvatarCornerMark.kt': UI,
    'UserUpBadge.kt': COMPONENTS,
    'OfficialVerifyBadgePolicyTest.kt': UI,
    'UpBadgeNamePolicyTest.kt': COMPONENTS,
    'UserAvatarCornerMarkTest.kt': UI,
}
RELATIVE = {name: PACKAGE[name].replace('.', '/') + '/' + name for name in EXPECTED}
DS_MAIN = 'design-system/src/main/java/com/android/purebilibili/core/ui/'
APP_MAIN = 'app/src/main/java/com/android/purebilibili/core/ui/'
DS_TEST = 'design-system/src/test/java/com/android/purebilibili/core/ui/'
APP_TEST = 'app/src/test/java/com/android/purebilibili/core/ui/'
UPSTREAM_PATHS = {
    'AppBadge.kt': DS_MAIN + 'components/AppBadge.kt',
    'OfficialVerifyBadgePolicy.kt': DS_MAIN + 'OfficialVerifyBadgePolicy.kt',
    'UpBadgeNamePolicy.kt': APP_MAIN + 'components/UpBadgeNamePolicy.kt',
    'UserAvatarCornerMark.kt': DS_MAIN + 'UserAvatarCornerMark.kt',
    'UserUpBadge.kt': APP_MAIN + 'components/UserUpBadge.kt',
    'OfficialVerifyBadgePolicyTest.kt': DS_TEST + 'OfficialVerifyBadgePolicyTest.kt',
    'UpBadgeNamePolicyTest.kt': APP_TEST + 'components/UpBadgeNamePolicyTest.kt',
    'UserAvatarCornerMarkTest.kt': DS_TEST + 'UserAvatarCornerMarkTest.kt',
}
DECLARATIONS = {
    'AppBadge.kt': ('fun AppBadge(',),
    'OfficialVerifyBadgePolicy.kt': (
        'enum class OfficialVerifyBadgeTone',
        'fun officialVerifyColor(',
        'data class OfficialVerifyBadgeSpec(',
        'fun resolveOfficialVerifyBadge(',
        'fun resolveOfficialVerifyBadgeFromRole(',
        'fun buildOfficialVerifyBadgeSpec(',
        'fun OfficialVerifyBadge(',
        'fun OfficialVerifyAvatarBadge(',
    ),
    'UpBadgeNamePolicy.kt': (
        'fun resolveUpNameColor(',
        'fun resolveUpStatsText(',
        'fun shouldRenderUpBadgeTrailingSlot(',
        'fun shouldRenderUserUpBadge(',
    ),
    'UserAvatarCornerMark.kt': (
        'enum class UserAvatarCornerMark ',
        'fun resolveUserAvatarCornerMark(',
        'fun UserAvatarCornerMarkBadge(',
    ),
    'UserUpBadge.kt': (
        'data class UserUpBadgeVisualSpec(',
        'fun resolveUserUpBadgeVisualSpec(',
        'fun UserUpBadge(',
    ),
}
TEST_COUNTS = {
    'OfficialVerifyBadgePolicyTest.kt': 5,
    'UpBadgeNamePolicyTest.kt': 6,
    'UserAvatarCornerMarkTest.kt': 2,
}
# The Skia JVM text renderer has no Android font-padding option; the original file only
# uses it to trim the UP label, whose line height and trim style stay in the emitted copy.
USER_UP_BADGE_IMPORT = 'import androidx.compose.ui.text.PlatformTextStyle\n'
USER_UP_BADGE_ARGUMENT = '                platformStyle = PlatformTextStyle(includeFontPadding = false),\n'
# The desktop test classpath is kotlin-test-junit5, which has no org.junit (JUnit 4) classes.
JUNIT_IMPORTS = (
    ('import org.junit.Assert.assertEquals\n', 'import kotlin.test.assertEquals\n'),
    ('import org.junit.Assert.assertFalse\n', 'import kotlin.test.assertFalse\n'),
    ('import org.junit.Assert.assertNull\n', 'import kotlin.test.assertNull\n'),
    ('import org.junit.Assert.assertTrue\n', 'import kotlin.test.assertTrue\n'),
    ('import org.junit.Test\n', 'import kotlin.test.Test\n'),
)
JUNIT_ADAPTED_TEST = 'UpBadgeNamePolicyTest.kt'
PROOF_NAME = 'v046-identity-badge-palette-proof.json'
TESTS_PROOF_NAME = 'v046-identity-badge-palette-tests-proof.json'
# v0.4.6 resolveUpNameColor reads its `secondary` argument as the annual-VIP colour. v0.2.5 callers handed it the
# theme secondary (an annual VIP name was the fixed FB7299 inside the old policy), so a caller that still passes the
# theme colour would paint annual VIP names with the accent colour and lose the monthly highlight. Three compiled
# owners are produced from originals older than v0.4.6 and keep that argument. Each row is the single line the
# owning producer rewrites to the v0.4.6 argument (upstream DynamicCard.kt:552, SpaceScreen.kt:3266 and
# VideoInfoSection.kt:1239). `argument`: the line is the secondary argument itself; `local`: the line binds the value
# that every call passes. No other caller is touched: every other compiled owner already passes userVipPink().
UP_NAME_CALLEE = 'resolveUpNameColor('
UP_NAME_ARGUMENTS = ['vipStatus', 'vipType', 'onSurface', 'secondary']
THEME_SECONDARY = 'MaterialTheme.colorScheme.secondary'
USER_VIP_PINK = 'com.android.purebilibili.core.ui.components.userVipPink()'
UP_NAME_MIGRATIONS = {
    'DynamicCard.kt': dict(
        producer='extract-upstream-dynamic-card.py', form='argument', calls=1,
        before=' ' * 28 + 'secondary = ' + THEME_SECONDARY + ',\n',
        after=' ' * 28 + 'secondary = ' + USER_VIP_PINK + ',\n',
        beforeSHA256='03e2524a4ccb2104670b922a7b20015a7607088e691ca12adb62f91226b2f482',
        afterSHA256='9fe7b933fbbec7d708648aef9bf1a374c2e5c58246589a9e98dd765476be406c'),
    'SpaceScreen.kt': dict(
        producer='extract-upstream-space-pages.py', form='argument', calls=1,
        before=' ' * 24 + 'secondary = ' + THEME_SECONDARY + ',\n',
        after=' ' * 24 + 'secondary = ' + USER_VIP_PINK + ',\n',
        beforeSHA256='8ec9682ff89fda6e497d7e8948724beccaf87c2a873036c6cb54b7e9323519ad',
        afterSHA256='0237279c4c6002af31a99594362a16423cd7b463eb2c4002b4e0cce77fa2b08c'),
    'VideoInfoSection.kt': dict(
        producer='extract-upstream-video-detail-full-units.py', form='local', calls=2,
        before=' ' * 16 + 'val secondaryColor = ' + THEME_SECONDARY + '\n',
        after=' ' * 16 + 'val secondaryColor = ' + USER_VIP_PINK + '\n',
        beforeSHA256='bc7f229f5db6e8090c6239eb322cfa70a5491c2828edff79d3d32157faab8a3e',
        afterSHA256='5199b5dea861546e52a9140f8ef82ffe7df2e47b58387139f87136bfa063311f'),
}


def sha(value):
    return hashlib.sha256(value.encode('utf8') if isinstance(value, str) else value).hexdigest()


def need(condition, message):
    if not condition:
        raise ValueError(message)


def fixed(repo):
    root = Path(repo) / ARCHIVE
    need(root.is_dir() and not root.is_symlink(), 'Missing original identity badge palette inputs')
    need({p.name for p in root.iterdir()} == EXPECTED | {'manifest.json', 'LICENSE'},
         'Identity badge palette input leaves changed')
    manifest_raw = (root / 'manifest.json').read_bytes()
    need(b'\r' not in manifest_raw and sha(manifest_raw) == MANIFEST_SHA256,
         'Identity badge palette manifest changed')
    manifest = json.loads(manifest_raw)
    need((manifest['repository'], manifest['tag'], manifest['commit'], manifest['hashNormalization']) ==
         ('jay3-yy/BiliPai', 'v0.4.6', COMMIT, 'raw'), 'Identity badge palette source identity changed')
    need(len(manifest['files']) == len(EXPECTED) and {row['filename'] for row in manifest['files']} == EXPECTED,
         'Identity badge palette source set changed')
    sources, raws = {}, {}
    for row in manifest['files']:
        file = root / row['filename']
        need(file.is_file() and not file.is_symlink() and Path(row['path']).name == file.name,
             'Linked or ambiguous identity badge palette source')
        need(row['path'] == UPSTREAM_PATHS[file.name] and row['path'].endswith(file.name),
             'Identity badge palette upstream path changed: ' + file.name)
        leaf = file.read_bytes()
        need(b'\r' not in leaf and len(leaf) == row['bytes'] and sha(leaf) == row['sha256'],
             'Identity badge palette bytes changed: ' + file.name)
        need(hashlib.sha1(b'blob ' + str(len(leaf)).encode() + b'\0' + leaf).hexdigest() == row['gitBlob'],
             'Identity badge palette Git blob changed: ' + file.name)
        text = leaf.decode('utf8')
        need(text.encode('utf8') == leaf and text.endswith('\n') and
             ('package ' + PACKAGE[file.name] + '\n') in text[:400],
             'Identity badge palette text changed: ' + file.name)
        for token in DECLARATIONS.get(file.name, ()):
            need(text.count(token) == 1, 'Identity badge palette declaration changed: ' + token)
        if file.name in TEST_COUNTS:
            need(text.count('@Test') == TEST_COUNTS[file.name],
                 'Identity badge palette original test count changed: ' + file.name)
        sources[file.name] = text
        raws[file.name] = leaf
    license_file = root / 'LICENSE'
    need(not license_file.is_symlink() and sha(license_file.read_bytes()) == manifest['license']['sha256'],
         'Identity badge palette license changed')
    return sources, raws, manifest


def validate_outputs(repo, *outputs):
    fixed(repo)
    root = (Path(repo) / ARCHIVE).resolve()
    for output in outputs:
        if output is None:
            continue
        target = Path(output).resolve()
        need(not (target.is_relative_to(root) or root.is_relative_to(target)),
             'Output overlaps original identity badge palette inputs')


def restore(value, proof):
    need(sha(value) == proof['afterSHA256'], 'Identity badge palette output differs from its receipt')
    lines = value.splitlines(keepends=True)
    for hunk in reversed(proof['hunks']):
        first, last = hunk['afterLines']
        need(''.join(lines[first:last]) == hunk['after'], 'Identity badge palette inverse span changed')
        lines[first:last] = hunk['before'].splitlines(keepends=True)
    result = ''.join(lines)
    need(sha(result) == proof['beforeSHA256'], 'Identity badge palette original inverse failed')
    return result


def windows_user_up_badge(original):
    need(original.count(USER_UP_BADGE_IMPORT) == 1, 'UserUpBadge font-padding import changed')
    need(original.count(USER_UP_BADGE_ARGUMENT) == 1, 'UserUpBadge font-padding argument changed')
    need(original.count('PlatformTextStyle') == 2 and original.count('includeFontPadding') == 1,
         'UserUpBadge font-padding use changed')
    adapted = original.replace(USER_UP_BADGE_IMPORT, '', 1).replace(USER_UP_BADGE_ARGUMENT, '', 1)
    need('PlatformTextStyle' not in adapted and 'includeFontPadding' not in adapted,
         'UserUpBadge font-padding adaptation incomplete')
    proof = inverse_proof(original, adapted)
    need(restore(adapted, proof) == original, 'UserUpBadge original inverse failed')
    return adapted, proof


def main_bodies(repo):
    sources, _raws, _manifest = fixed(repo)
    bodies, adapted_proofs = {}, {}
    for name in MAIN_FILES:
        if name == 'UserUpBadge.kt':
            text, proof = windows_user_up_badge(sources[name])
            adapted_proofs[name] = dict(originalToWindows=proof)
        else:
            text = sources[name]
        bodies[name] = text.encode('utf8')
    receipt = dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256,
                   verbatimFiles={name: sha(bodies[name]) for name in MAIN_FILES if name not in adapted_proofs},
                   adaptedFiles=adapted_proofs, vendoredDirectCopiesExcluded=True)
    return bodies, receipt


def test_bodies(repo):
    sources, _raws, _manifest = fixed(repo)
    bodies, adapted_proofs = {}, {}
    for name in TEST_FILES:
        text = sources[name]
        if name == JUNIT_ADAPTED_TEST:
            adapted = text
            for old, new in JUNIT_IMPORTS:
                need(adapted.count(old) == 1, 'Original JUnit import changed: ' + old.strip())
                adapted = adapted.replace(old, new, 1)
            need('org.junit' not in adapted, 'Original JUnit import left behind')
            need(adapted.count('@Test') == TEST_COUNTS[name], 'Original test count changed after import rewrite')
            proof = inverse_proof(text, adapted)
            need(restore(adapted, proof) == text, 'Original test inverse failed')
            adapted_proofs[name] = dict(originalToWindows=proof)
            text = adapted
        else:
            need('org.junit' not in text, 'Original suite acquired a JUnit 4 import: ' + name)
        bodies[name] = text.encode('utf8')
    receipt = dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256, testCounts=dict(TEST_COUNTS),
                   verbatimFiles={name: sha(bodies[name]) for name in TEST_FILES if name not in adapted_proofs},
                   adaptedFiles=adapted_proofs, jvmTestsExecuted=False)
    return bodies, receipt


def _target(root, relative):
    base = Path(root).resolve()
    target = base / relative
    # A leaf symlink must stay unresolved; resolve() would follow it and write through it.
    if not target.is_symlink():
        target = target.resolve()
        need(target.is_relative_to(base), 'Identity badge palette target escapes the output root: ' + relative)
    return target


def _require_regular_or_absent(target, relative):
    try:
        info = os.lstat(target)
    except FileNotFoundError:
        return
    need(stat.S_ISREG(info.st_mode), 'Identity badge palette target is not a regular file: ' + relative)


def _write_all(output, rows):
    targets = []
    for relative, leaf in rows:
        target = _target(output, relative)
        _require_regular_or_absent(target, relative)
        targets.append((target, relative, leaf))
    written = []
    for target, relative, leaf in targets:
        target.parent.mkdir(parents=True, exist_ok=True)
        _require_regular_or_absent(target, relative)
        target.write_bytes(leaf)
        readback = target.read_bytes()
        need(readback == leaf, 'Identity badge palette readback differs: ' + relative)
        written.append({'path': relative, 'sha256': sha(readback)})
    return written


def _proof_bytes(receipt):
    return (json.dumps(receipt, ensure_ascii=False, indent=2) + '\n').encode('utf8')


def emit(repo, output):
    validate_outputs(repo, output)
    bodies, receipt = main_bodies(repo)
    rows = [(RELATIVE[name], bodies[name]) for name in MAIN_FILES] + [(PROOF_NAME, _proof_bytes(receipt))]
    return {'files': _write_all(output, rows)}


def emit_tests(repo, output):
    validate_outputs(repo, output)
    bodies, receipt = test_bodies(repo)
    rows = [(RELATIVE[name], bodies[name]) for name in TEST_FILES] + [(TESTS_PROOF_NAME, _proof_bytes(receipt))]
    return {'files': _write_all(output, rows)}


def _closing_parenthesis(text, open_at):
    """Index of the parenthesis that closes the one at open_at; string and character literals are skipped."""
    depth, index, quote = 0, open_at, None
    while index < len(text):
        char = text[index]
        if quote:
            if char == '\\':
                index += 1
            elif char == quote:
                quote = None
        elif char in ('"', "'"):
            quote = char
        elif char == '(':
            depth += 1
        elif char == ')':
            depth -= 1
            if depth == 0:
                return index
        index += 1
    raise ValueError('Unbalanced ' + UP_NAME_CALLEE + ' call')


def _top_level_arguments(body):
    parts, depth, current, quote = [], 0, '', None
    for char in body:
        if quote:
            current += char
            if char == quote:
                quote = None
        elif char in ('"', "'"):
            quote = char
            current += char
        elif char == ',' and depth == 0:
            parts.append(current)
            current = ''
        else:
            depth += (char in '([{') - (char in ')]}')
            current += char
    parts.append(current)
    return [part.strip() for part in parts if part.strip()]


def up_name_calls(text):
    """Every call of resolveUpNameColor in text as [(argument label, argument value)]; the declaration is skipped."""
    calls, start = [], 0
    while True:
        at = text.find(UP_NAME_CALLEE, start)
        if at < 0:
            return calls
        start = at + len(UP_NAME_CALLEE)
        if text[max(0, at - 4):at] == 'fun ':
            continue
        body = text[start:_closing_parenthesis(text, start - 1)]
        arguments = []
        for part in _top_level_arguments(body):
            need('=' in part, 'Positional resolveUpNameColor argument: ' + part)
            label, value = part.split('=', 1)
            arguments.append((label.strip(), value.strip()))
        calls.append(arguments)


def up_name_vip_pink(name, text):
    """(before, after) of the one line that makes the named owner pass userVipPink() as the annual-VIP colour.

    The caller applies the pair with its own counted, inverse-checked replacement. This fails closed when the pinned
    line, its hashes, the call count, the argument labels or the use of the bound value differ from what was reviewed.
    """
    row = UP_NAME_MIGRATIONS.get(name)
    need(row is not None, 'Unknown resolveUpNameColor owner: ' + str(name))
    before, after = row['before'], row['after']
    need(sha(before) == row['beforeSHA256'] and sha(after) == row['afterSHA256'],
         'resolveUpNameColor rewrite pin changed: ' + name)
    need(text.count(before) == 1 and text.count(after) == 0, 'resolveUpNameColor anchor changed: ' + name)
    calls = up_name_calls(text)
    need(len(calls) == row['calls'], 'resolveUpNameColor call count changed: ' + name)
    for arguments in calls:
        need([label for label, _value in arguments] == UP_NAME_ARGUMENTS,
             'resolveUpNameColor arguments changed: ' + name)
    secondary = [dict(arguments)['secondary'] for arguments in calls]
    if row['form'] == 'argument':
        need(secondary == [THEME_SECONDARY], 'resolveUpNameColor secondary argument changed: ' + name)
    else:
        need(row['form'] == 'local' and secondary == ['secondaryColor'] * row['calls'] and
             text.count('secondaryColor') == row['calls'] + 1,
             'resolveUpNameColor secondary binding changed: ' + name)
    return before, after
