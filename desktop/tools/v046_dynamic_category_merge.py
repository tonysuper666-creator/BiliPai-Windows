"""Whole v0.4.6 dynamic category merge on the existing dynamic tabs, settings and editor producers.

The UP tab folds into All, the follow strip gains the All dynamics entry, an author selection
filters the current category, and the persisted "All page strip" key is gone. The sources are
the fixed v0.4.6 files of the archive; every layer asserts the exact predecessor it receives.
"""
from pathlib import Path
import hashlib
import json
import os
import stat

from v044_favorite_detail import inverse_proof
from v044_favorite_subscription import declaration, need, parser, span

COMMIT = 'd6bf8b1655ae910999860bf13e65c8fcea5b345b'
ARCHIVE = 'desktop/upstream-slices/v046-dynamic-category-merge'
MANIFEST_SHA256 = 'ccd0fc5d1d5d1dc03ce1a0adf9ee99d5cec9043311a2ded64c00aadb2fd3fce7'
BASE = 'app/src/main/java/com/android/purebilibili/'
DYNAMIC = BASE + 'feature/dynamic/'
TEST = 'app/src/test/java/com/android/purebilibili/feature/dynamic/'
PATHS = {
    'DynamicTabPolicy.kt': DYNAMIC + 'DynamicTabPolicy.kt',
    'DynamicScreenStatePolicy.kt': DYNAMIC + 'DynamicScreenStatePolicy.kt',
    'DynamicUserRequestPolicy.kt': DYNAMIC + 'DynamicUserRequestPolicy.kt',
    'DynamicScreen.kt': DYNAMIC + 'DynamicScreen.kt',
    'DynamicReactionSheet.kt': DYNAMIC + 'components/DynamicReactionSheet.kt',
    'SettingsSections.kt': BASE + 'feature/settings/ui/SettingsSections.kt',
    'DynamicTabPolicyTest.kt': TEST + 'DynamicTabPolicyTest.kt',
    'DynamicUserRequestPolicyTest.kt': TEST + 'DynamicUserRequestPolicyTest.kt',
}
EXPECTED = set(PATHS)
TEST_COUNTS = {'DynamicTabPolicyTest.kt': 7, 'DynamicUserRequestPolicyTest.kt': 5}
# SettingsManager.kt of the same commit is carried by the comment-list archive; this slice pins its bytes.
SETTINGS_PATH = BASE + 'core/store/SettingsManager.kt'
SETTINGS_LEAF = 'desktop/upstream-slices/v046-comment-list/SettingsManager.kt'
SETTINGS_SHA256 = 'ab7ceccdb70212f9fbf8b35f723b47cf1f2715e66c5b6f53b0b0c9e0b7ac837c'
SETTINGS_BLOB = '5f89ecaa3f945d131f62174d525e717feb0b91bc'
# LF SHA-256 of the exact body each layer receives (also recorded in manifest beforeFinalOutputs).
BEFORE = {
    PATHS['DynamicTabPolicy.kt']: '727beb11ad857e3745383e8fb90a2df4712f701f141d1d49a1c13e919294cef0',
    PATHS['DynamicScreenStatePolicy.kt']: 'cf08da0f4a2332cea17de66dbc772c9a0789295fe9af1ab57092bbefaa6fc597',
    PATHS['DynamicUserRequestPolicy.kt']: '46e70da832bf1a6b1a0a9388606386fba02b47311051055cd02ca880c1e32371',
    PATHS['DynamicScreen.kt']: '5a2a9773123be8af865b30a199dcc049f711da957b33a2e73a8e76e9372ed587',
    PATHS['SettingsSections.kt']: 'e306f73ee7cf49877248eb01063816d50f43f61b47c9d41a52ab372917de54f7',
    SETTINGS_PATH: '5799bb8802992594ae9494b48d6357ee00ecc7be03d97ed0dcb5fede7774328c',
}
# The paths the dynamic tabs producer reads through its selected-source chain.
SELECTED_PATHS = tuple(path for path in BEFORE if path != PATHS['DynamicTabPolicy.kt'])
STRIP_FUNCTIONS = ('HorizontalUserList', 'DynamicSelectedUserFeedHeader')
TAB_ITEM_FUNCTION = 'FeedDynamicTabVisibilityItem'
# The only platform adaptation of the copied strip: the self avatar is a Windows composable.
SELF_AVATAR_BEFORE = 'DynamicSelfAvatar(face = user.face, isSelected = isSelected)'
SELF_AVATAR_AFTER = 'com.bilipai.desktop.ui.DesktopDynamicSelfAvatar(face = user.face, isSelected = isSelected)'
# Policy functions the tabs producer keeps from the state policy; these must stay byte-identical across the layer.
STATE_UNCHANGED = ('isDynamicUpPanelAllShortcut', 'isDynamicUpPanelShortcut', 'isDynamicUpPanelItemSelected',
                   'resolveDynamicSelectedTab', 'resolveDynamicFeedRequestType',
                   'resolveHorizontalUserListVerticalPaddingDp', 'extractUsersFromDynamicItems',
                   'resolveMergedFollowedUsers', 'isDynamicItemRealUser', 'shouldLoadMoreDynamicFeed')
STATE_CHANGED = ('resolveDynamicUpPanelUsers', 'resolveDynamicSelectedUserIdAfterClick',
                 'shouldUseSelectedUserDynamicFeed', 'shouldShowDynamicHorizontalUserList')
STATE_REMOVED = ('resolveDynamicSelectedUserForTab', 'resolveDynamicTabAfterUserSelection')
STATE_ADDED = ('shouldShowDynamicUpPanel',)
TAB_POLICY_RELATIVE = 'com/android/purebilibili/feature/dynamic/DynamicTabPolicy.kt'


def sha(value):
    return hashlib.sha256(value.encode('utf8') if isinstance(value, str) else value).hexdigest()


def blob_oid(raw):
    return hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()


def fixed(repo):
    root = Path(repo) / ARCHIVE
    need(root.is_dir() and not root.is_symlink(), 'Missing original dynamic category merge inputs')
    need({p.name for p in root.iterdir()} == EXPECTED | {'manifest.json', 'LICENSE'},
         'Dynamic category merge input leaves changed')
    manifest_raw = (root / 'manifest.json').read_bytes()
    need(b'\r' not in manifest_raw and sha(manifest_raw) == MANIFEST_SHA256, 'Dynamic category merge manifest changed')
    manifest = json.loads(manifest_raw)
    need((manifest['repository'], manifest['tag'], manifest['commit'], manifest['hashNormalization']) ==
         ('jay3-yy/BiliPai', 'v0.4.6', COMMIT, 'raw'), 'Dynamic category merge source identity changed')
    need(len(manifest['files']) == len(EXPECTED) and {row['filename'] for row in manifest['files']} == EXPECTED,
         'Dynamic category merge source set changed')
    need({path: row['sha256'] for path, row in manifest['beforeFinalOutputs'].items()} == BEFORE,
         'Dynamic category merge predecessor pins differ from the manifest')
    external = manifest['externalInputs']['SettingsManager.kt']
    need((external['archive'], external['path'], external['sha256'], external['gitBlob']) ==
         (SETTINGS_LEAF.rsplit('/', 1)[0], SETTINGS_PATH, SETTINGS_SHA256, SETTINGS_BLOB),
         'Dynamic category merge external settings pin changed')
    sources, raws = {}, {}
    for row in manifest['files']:
        file = root / row['filename']
        need(file.is_file() and not file.is_symlink() and Path(row['path']).name == file.name and
             row['path'] == PATHS[file.name], 'Linked or ambiguous dynamic category merge source: ' + file.name)
        leaf = file.read_bytes()
        need(b'\r' not in leaf and len(leaf) == row['bytes'] and sha(leaf) == row['sha256'],
             'Dynamic category merge bytes changed: ' + file.name)
        need(blob_oid(leaf) == row['gitBlob'], 'Dynamic category merge Git blob changed: ' + file.name)
        text = leaf.decode('utf8')
        need(text.encode('utf8') == leaf and text.endswith('\n'), 'Dynamic category merge text changed: ' + file.name)
        sources[file.name] = text
        raws[file.name] = leaf
    for name, count in TEST_COUNTS.items():
        need(sources[name].count('@Test') == count, 'Original dynamic category merge test count changed: ' + name)
    licence = root / 'LICENSE'
    need(not licence.is_symlink() and sha(licence.read_bytes()) == manifest['license']['sha256'],
         'Dynamic category merge license changed')
    return sources, raws, manifest


def settings_manager_source(repo):
    leaf = Path(repo) / SETTINGS_LEAF
    need(leaf.is_file() and not leaf.is_symlink(), 'Missing the v0.4.6 SettingsManager input')
    raw = leaf.read_bytes()
    need(sha(raw) == SETTINGS_SHA256 and blob_oid(raw) == SETTINGS_BLOB and b'\r' not in raw,
         'The v0.4.6 SettingsManager input changed')
    return raw.decode('utf8')


def validate_outputs(repo, *outputs):
    fixed(repo)
    roots = [(Path(repo) / ARCHIVE).resolve()]
    for output in outputs:
        if output is None:
            continue
        target = Path(output).resolve()
        for root in roots:
            need(not (target.is_relative_to(root) or root.is_relative_to(target)),
                 'Output overlaps original dynamic category merge inputs')


def _pin(body, path):
    need(sha(body) == BEFORE[path], 'Dynamic category merge predecessor changed: ' + path)


def _whole_receipt(path, before, after):
    return dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256, path=path, wholeFileReplacement=True,
                beforeSHA256=sha(before), afterSHA256=sha(after), jvmTestsExecuted=False)


def _functions(before, original, names, adapt, syntax):
    after, replaced = before, []
    for name in names:
        new = declaration(original, 'fun', name, syntax)
        if name in adapt:
            old_text, new_text = adapt[name]
            need(new.count(old_text) == 1, 'Original dynamic strip seam changed: ' + name)
            adapted = new.replace(old_text, new_text, 1)
            need(adapted.replace(new_text, old_text, 1) == new, 'Whole original strip inverse failed: ' + name)
        else:
            adapted = new
        first, last = span(after, 'fun', name, syntax)
        after = after[:first] + adapted + after[last:]
        replaced.append(dict(name=name, originalSHA256=sha(new), windowsSHA256=sha(adapted),
                             originalToWindows=inverse_proof(new, adapted)))
    return after, replaced


def advance_state_policy(repo, before):
    sources, _, _ = fixed(repo)
    path = PATHS['DynamicScreenStatePolicy.kt']
    _pin(before, path)
    after = sources['DynamicScreenStatePolicy.kt']
    syntax = parser(repo)
    for name in STATE_UNCHANGED:
        need(declaration(before, 'fun', name, syntax) == declaration(after, 'fun', name, syntax),
             'Original state policy declaration is not unchanged: ' + name)
    for name in STATE_CHANGED:
        need(declaration(before, 'fun', name, syntax) != declaration(after, 'fun', name, syntax),
             'Original state policy declaration did not change: ' + name)
    for name in STATE_REMOVED:
        need(name in before and name not in after, 'Original state policy removal changed: ' + name)
    for name in STATE_ADDED:
        need(name not in before and name in after, 'Original state policy addition changed: ' + name)
    return after, _whole_receipt(path, before, after)


def advance_request_policy(repo, before):
    sources, _, _ = fixed(repo)
    path = PATHS['DynamicUserRequestPolicy.kt']
    _pin(before, path)
    after = sources['DynamicUserRequestPolicy.kt']
    need('DynamicUserContentFilter' in before and 'DynamicUserContentFilter' not in after and
         'withUserContentFilter' in before and 'withUserContentFilter' not in after,
         'Original user content filter retirement changed')
    return after, _whole_receipt(path, before, after)


def advance_settings_manager(repo, before):
    after = settings_manager_source(repo)
    _pin(before, SETTINGS_PATH)
    for name in ('KEY_DYNAMIC_ALL_TAB_HORIZONTAL_USER_LIST_VISIBLE', 'getDynamicAllTabHorizontalUserListVisible',
                 'setDynamicAllTabHorizontalUserListVisible'):
        need(name in before and name not in after, 'Original settings key retirement changed: ' + name)
    return after, _whole_receipt(SETTINGS_PATH, before, after)


def advance_screen(repo, before):
    sources, _, _ = fixed(repo)
    path = PATHS['DynamicScreen.kt']
    _pin(before, path)
    syntax = parser(repo)
    adapt = {'HorizontalUserList': (SELF_AVATAR_BEFORE, SELF_AVATAR_AFTER)}
    after, replaced = _functions(before, sources['DynamicScreen.kt'], STRIP_FUNCTIONS, adapt, syntax)
    return after, dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256, path=path,
                       replaced=replaced, retainedToCurrent=inverse_proof(before, after), jvmTestsExecuted=False)


def advance_sections(repo, before):
    sources, _, _ = fixed(repo)
    path = PATHS['SettingsSections.kt']
    _pin(before, path)
    syntax = parser(repo)
    after, replaced = _functions(before, sources['SettingsSections.kt'], (TAB_ITEM_FUNCTION,), {}, syntax)
    return after, dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256, path=path,
                       replaced=replaced, retainedToCurrent=inverse_proof(before, after), jvmTestsExecuted=False)


_ADVANCE = {
    PATHS['DynamicScreenStatePolicy.kt']: advance_state_policy,
    PATHS['DynamicUserRequestPolicy.kt']: advance_request_policy,
    PATHS['DynamicScreen.kt']: advance_screen,
    PATHS['SettingsSections.kt']: advance_sections,
    SETTINGS_PATH: advance_settings_manager,
}


def advance_selected(repo, path, before):
    """The layer of the dynamic tabs producer's selected-source chain; other paths pass through."""
    layer = _ADVANCE.get(path)
    return before if layer is None else layer(repo, before)[0]


def receipt(repo, befores):
    """Receipt of every layer for the producer output; `befores` maps each selected path to its predecessor."""
    need(set(befores) == set(SELECTED_PATHS), 'Dynamic category merge receipt needs every selected predecessor')
    return dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256,
                layers={path: _ADVANCE[path](repo, befores[path])[1] for path in SELECTED_PATHS},
                actualWindowsCallerVerified=False, mainCompiled=False, runtimeExecuted=False)


def tab_policy_source(repo, canonical):
    sources, _, _ = fixed(repo)
    _pin(canonical, PATHS['DynamicTabPolicy.kt'])
    after = sources['DynamicTabPolicy.kt']
    need('"up"' in canonical and '"up"' not in after and 'isDynamicUserTabVisible' in canonical and
         'isDynamicUserTabVisible' not in after, 'Original dynamic tab list changed')
    return after


def tab_policy_receipt(repo, canonical):
    after = tab_policy_source(repo, canonical)
    return _whole_receipt(PATHS['DynamicTabPolicy.kt'], canonical, after)


def _target(output):
    base = Path(output).resolve()
    target = base / TAB_POLICY_RELATIVE
    if not target.is_symlink():
        target = target.resolve()
        need(target.is_relative_to(base), 'Dynamic tab policy target escapes the output root')
    return target


def _regular_or_absent(target):
    try:
        info = os.lstat(target)
    except FileNotFoundError:
        return
    need(stat.S_ISREG(info.st_mode), 'Dynamic tab policy target is not a regular file')


def emit_tab_policy(repo, output, canonical):
    """The whole v0.4.6 tab policy, verbatim, replacing the excluded direct copy."""
    validate_outputs(repo, output)
    tab_policy_source(repo, canonical)
    _, raws, _ = fixed(repo)
    target = _target(output)
    _regular_or_absent(target)
    target.parent.mkdir(parents=True, exist_ok=True)
    _regular_or_absent(target)
    target.write_bytes(raws['DynamicTabPolicy.kt'])
    need(target.read_bytes() == raws['DynamicTabPolicy.kt'], 'Dynamic tab policy readback differs')
    return target


def original_tests(repo):
    sources, _, _ = fixed(repo)
    return {PATHS[name]: sources[name] for name in TEST_COUNTS}


def reaction_sheet_source(repo):
    sources, _, _ = fixed(repo)
    return sources['DynamicReactionSheet.kt']


def reaction_sheet_identity(repo):
    _, _, manifest = fixed(repo)
    row = next(item for item in manifest['files'] if item['filename'] == 'DynamicReactionSheet.kt')
    return dict(path=PATHS['DynamicReactionSheet.kt'], requestedPath=PATHS['DynamicReactionSheet.kt'],
                pinnedCommit=COMMIT, pinnedTag='v0.4.6', pinnedGitBlob=row['gitBlob'],
                currentGitBlob=row['gitBlob'], sha256LfUtf8=row['sha256'], matchesPinnedCommit=True)


def restore(value, proof):
    """Apply a retainedToCurrent receipt backwards; the final body must match the receipt."""
    need(sha(value) == proof['afterSHA256'], 'Dynamic category merge final output differs from its receipt')
    lines = value.splitlines(keepends=True)
    for hunk in reversed(proof['hunks']):
        first, last = hunk['afterLines']
        need(''.join(lines[first:last]) == hunk['after'], 'Dynamic category merge inverse span changed')
        lines[first:last] = hunk['before'].splitlines(keepends=True)
    result = ''.join(lines)
    need(sha(result) == proof['beforeSHA256'], 'Dynamic category merge predecessor inverse failed')
    return result
