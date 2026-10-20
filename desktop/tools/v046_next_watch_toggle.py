"""v0.4.6 next-watch: the overlay's content colour revision and the setter of its on/off switch.

Narrow, hash-pinned layer over the two outputs that the sole full-controls producer already emits from the
v0.3.4 next-watch archive (desktop/upstream-slices/v034-next-watch-ui):

* NextWatchOverlay.kt takes the two v0.4.6 differences: the AppContentColors import and the card text colour
  AppContentColors.primaryText(). Replaying the v0.3.4 adaptation recipe over the whole v0.4.6 file gives this
  exact output (checked in test_v046_next_watch_toggle_source.py against the local upstream objects).
* DesktopOriginalNextWatchHintSettings.kt gains the original setNextWatchHintEnabled declaration. The pinned
  v0.3.4 SettingsManager.kt already holds the byte-identical key, getter and setter that v0.4.6 has, so the
  declaration comes from that verified archive leaf and only the Windows store binding is adapted.

No archive is added: NextWatchSuggestion.kt and the view model resolve/play methods are unchanged between
v0.3.4 and v0.4.6, and the whole PlaybackSettingsScreen.kt cannot be carried (its item-level search anchors
do not exist on Windows). The proof JSON records every counted edit and its full inverse.
"""
from pathlib import Path
import hashlib
import json

from v034_next_watch_ui import COMMIT as ARCHIVE_COMMIT, MANIFEST_SHA as ARCHIVE_MANIFEST_SHA, wide

UPSTREAM_COMMIT = 'd6bf8b1655ae910999860bf13e65c8fcea5b345b'
OVERLAY_UPSTREAM_PATH = 'app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/NextWatchOverlay.kt'
OVERLAY_UPSTREAM_BLOB = 'a49cbb0aadc7c83ee995be5d7dbbbc455836aadb'
SETTINGS_UPSTREAM_PATH = 'app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt'
SETTINGS_UPSTREAM_BLOB = '5f89ecaa3f945d131f62174d525e717feb0b91bc'
SUGGESTION_UPSTREAM_PATH = 'app/src/main/java/com/android/purebilibili/feature/video/playback/next/NextWatchSuggestion.kt'
SUGGESTION_UPSTREAM_BLOB = '48a819ae4bf973dcf7f2bfbdf31e13e13aa43985'
ARCHIVE = 'desktop/upstream-slices/v034-next-watch-ui'
ARCHIVE_SETTINGS_LEAF = 'SettingsManager.kt'

OVERLAY_RELATIVE = 'com/android/purebilibili/feature/video/ui/overlay/NextWatchOverlay.kt'
SETTINGS_RELATIVE = 'com/android/purebilibili/core/store/DesktopOriginalNextWatchHintSettings.kt'
PROOF_NAME = 'v046-next-watch-toggle-proof.json'

# Predecessors: the afterSha256 of the two v0.3.4 recipes in the v034-next-watch-ui manifest.
OVERLAY_PREDECESSOR_SHA256 = '5b78ca9e7f69f0b3924eb6abbcb2a00ea645cd2d5f969c9b548e3eeb53e82eed'
SETTINGS_PREDECESSOR_SHA256 = '255ddf2f1b38c8156d72dd3b0150ed6e56a10cd5a9452b3c928b93850b0a9586'
# Results of the two sets of counted edits, asserted on every run.
OVERLAY_ADVANCED_SHA256 = 'c3f3bf5afcd178a98eb17ecb44c89cf2c7fc86dfe6734896edece4909e31cf43'
SETTINGS_ADVANCED_SHA256 = 'bfed59dcd04184ef7da228468571ab6a6645afcbed7e7a0d5ae1834e651e4e84'

# Exact v0.4.6 text this layer takes (each also present, byte for byte, at the pinned upstream blob).
UPSTREAM_OVERLAY_IMPORT_BLOCK = ('package com.android.purebilibili.feature.video.ui.overlay\n\n'
                                 'import com.android.purebilibili.core.ui.AppContentColors\n\n')
UPSTREAM_OVERLAY_IMPORT_BLOCK_SHA256 = 'd1b7207d20907a03842b3b457ae8140de4b5a79fcea4ecd91b0535be890fe3fa'
UPSTREAM_OVERLAY_COLOR_CONTEXT = (
    '    val content = if (miuix) top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onSurface\n'
    '        else AppContentColors.primaryText()\n')
UPSTREAM_OVERLAY_COLOR_CONTEXT_SHA256 = 'd1d341662a768dbd235ae6a0133f316d85117bca60b4db77b89e3bc9c005fcd8'
UPSTREAM_KEY = '    private val KEY_NEXT_WATCH_HINT_ENABLED = booleanPreferencesKey("next_watch_hint_enabled")\n'
UPSTREAM_KEY_SHA256 = '83ffbd0a30e659493279d3f6ae2647bb6dede8782efcde78cd6b96d980fafa5f'
UPSTREAM_GETTER = ('    fun getNextWatchHintEnabled(context: Context): Flow<Boolean> = context.settingsDataStore.data\n'
                   '        .map { preferences -> preferences[KEY_NEXT_WATCH_HINT_ENABLED] ?: true }\n')
UPSTREAM_GETTER_SHA256 = '39426c920a5650c5ca4bed930aec3a150c7fe9f88592b404d79a201b358ca6c1'
UPSTREAM_SETTER = ('    suspend fun setNextWatchHintEnabled(context: Context, enabled: Boolean) {\n'
                   '        context.settingsDataStore.edit { preferences -> preferences[KEY_NEXT_WATCH_HINT_ENABLED] = enabled }\n'
                   '    }\n')
UPSTREAM_SETTER_SHA256 = 'fd1a00819bd2541314a8675a00d9ad234cfa2544636f03f99079bf6d6e1cefcc'

# Windows binding of the declarations, identical to the three adapters the v0.3.4 recipe uses for the getter.
SETTER_ADAPTERS = (
    ('context: Context', 'context: DesktopOriginalPlayerSettingsContext', 1),
    ('context.settingsDataStore.edit', 'DesktopOriginalPlayerSettingsDataStore(context).edit', 1),
)
GETTER_ADAPTERS = (
    ('context: Context', 'context: DesktopOriginalPlayerSettingsContext', 1),
    ('context.settingsDataStore.data', 'DesktopOriginalPlayerSettingsDataStore(context).data', 1),
)
KEY_ADAPTERS = (('booleanPreferencesKey', 'playerBooleanPreferencesKey', 1),)

OVERLAY_IMPORT_ANCHOR = 'package com.android.purebilibili.feature.video.ui.overlay\n\n'
OVERLAY_COLOR_BEFORE = '        else MaterialTheme.colorScheme.onSurface\n'
OVERLAY_COLOR_AFTER = '        else AppContentColors.primaryText()\n'
SETTINGS_KDOC_BEFORE = '/** Original key/getter over the existing Root Store; no independent preference cache. */\n'
SETTINGS_KDOC_AFTER = '/** Original key, getter and setter over the existing Root Store; no independent preference cache. */\n'
SETTINGS_OBJECT_END = '        .map { preferences -> preferences[KEY_NEXT_WATCH_HINT_ENABLED] ?: true }\n}\n'


def sha(value):
    return hashlib.sha256(value.encode('utf8') if isinstance(value, str) else value).hexdigest()


def need(condition, message):
    if not condition:
        raise ValueError(message)


def assert_pins():
    pins = (
        (UPSTREAM_OVERLAY_IMPORT_BLOCK, UPSTREAM_OVERLAY_IMPORT_BLOCK_SHA256, 'overlay import block'),
        (UPSTREAM_OVERLAY_COLOR_CONTEXT, UPSTREAM_OVERLAY_COLOR_CONTEXT_SHA256, 'overlay colour context'),
        (UPSTREAM_KEY, UPSTREAM_KEY_SHA256, 'key'),
        (UPSTREAM_GETTER, UPSTREAM_GETTER_SHA256, 'getter'),
        (UPSTREAM_SETTER, UPSTREAM_SETTER_SHA256, 'setter'),
    )
    for text, expected, label in pins:
        need(sha(text) == expected, 'Next-watch upstream ' + label + ' pin is inconsistent')
    need(all(len(value) == 40 for value in (UPSTREAM_COMMIT, ARCHIVE_COMMIT, OVERLAY_UPSTREAM_BLOB,
                                            SETTINGS_UPSTREAM_BLOB, SUGGESTION_UPSTREAM_BLOB)),
         'Next-watch upstream identity pin is inconsistent')
    need(OVERLAY_COLOR_AFTER in UPSTREAM_OVERLAY_COLOR_CONTEXT and OVERLAY_IMPORT_ANCHOR in UPSTREAM_OVERLAY_IMPORT_BLOCK,
         'Next-watch overlay edit does not match the upstream text')
    need(UPSTREAM_OVERLAY_IMPORT_BLOCK == OVERLAY_IMPORT_ANCHOR + 'import com.android.purebilibili.core.ui.AppContentColors\n\n',
         'Next-watch overlay import edit does not match the upstream text')


def _blob(raw):
    return hashlib.sha1(('blob ' + str(len(raw)) + '\0').encode() + raw).hexdigest()


def archive_settings_text(repo):
    """The v0.3.4 SettingsManager.kt leaf, verified against its manifest exactly as the sole emitter does."""
    root = Path(repo) / ARCHIVE
    raw = wide(root / 'manifest.json').read_bytes()
    need(sha(raw) == ARCHIVE_MANIFEST_SHA, 'Next-watch archive manifest changed')
    manifest = json.loads(raw)
    need(manifest['upstreamCommit'] == ARCHIVE_COMMIT, 'Next-watch archive commit changed')
    row = next(r for r in manifest['sources'] if r['file'] == ARCHIVE_SETTINGS_LEAF)
    leaf = wide(root / ARCHIVE_SETTINGS_LEAF).read_bytes()
    need(len(leaf) == row['bytes'] and sha(leaf) == row['sha256'] and _blob(leaf) == row['gitBlob'],
         'Next-watch archived SettingsManager.kt changed')
    return leaf.decode('utf8')


def adapt(text, adapters):
    for before, after, count in adapters:
        need(text.count(before) == count, 'Next-watch adapter anchor changed: ' + before)
        text = text.replace(before, after)
    return text


def unadapt(text, adapters):
    for before, after, count in reversed(adapters):
        need(text.count(after) == count, 'Next-watch adapter result changed: ' + after)
        text = text.replace(after, before)
    return text


def adapted_setter():
    return adapt(UPSTREAM_SETTER, SETTER_ADAPTERS)


def _swap(source, edits, before, after, label):
    need(source.count(before) == 1, 'Next-watch anchor changed: ' + label)
    at = source.index(before)
    edits.append(dict(offset=at, before=before, after=after, count=1, label=label))
    return source[:at] + after + source[at + len(before):]


def restore(current, edits):
    """Peels counted edits off in reverse order; every edit must still sit exactly where it was recorded."""
    for edit in reversed(edits):
        at = edit['offset']
        need(current[at:at + len(edit['after'])] == edit['after'], 'Next-watch inverse boundary changed')
        current = current[:at] + edit['before'] + current[at + len(edit['after']):]
    return current


def advance_overlay(before):
    assert_pins()
    need(sha(before) == OVERLAY_PREDECESSOR_SHA256, 'Next-watch overlay predecessor changed')
    need(before.count('AppContentColors') == 0, 'Next-watch overlay already carries AppContentColors')
    edits = []
    after = _swap(before, edits, OVERLAY_IMPORT_ANCHOR, UPSTREAM_OVERLAY_IMPORT_BLOCK,
                  'Import AppContentColors where v0.4.6 places it, directly after the package line')
    after = _swap(after, edits, OVERLAY_COLOR_BEFORE, OVERLAY_COLOR_AFTER,
                  'Card text colour is AppContentColors.primaryText() as in v0.4.6')
    need(after.count('AppContentColors') == 2 and after.count('MaterialTheme.colorScheme.onSurface') == 0,
         'Next-watch overlay edit disturbed another reference')
    need(after.count(UPSTREAM_OVERLAY_COLOR_CONTEXT) == 1 and after.count(UPSTREAM_OVERLAY_IMPORT_BLOCK) == 1,
         'Next-watch overlay differs from the v0.4.6 text it takes')
    need(restore(after, edits) == before, 'Next-watch overlay input was not preserved')
    need(sha(after) == OVERLAY_ADVANCED_SHA256, 'Next-watch overlay result changed')
    return after, dict(path=OVERLAY_RELATIVE, upstreamPath=OVERLAY_UPSTREAM_PATH, upstreamBlob=OVERLAY_UPSTREAM_BLOB,
                       upstreamImportBlockSHA256=UPSTREAM_OVERLAY_IMPORT_BLOCK_SHA256,
                       upstreamColorContextSHA256=UPSTREAM_OVERLAY_COLOR_CONTEXT_SHA256,
                       inputSHA256=sha(before), outputSHA256=sha(after), fullInputInverseRecovered=True, edits=edits)


def advance_hint_settings(repo, before):
    assert_pins()
    need(sha(before) == SETTINGS_PREDECESSOR_SHA256, 'Next-watch settings predecessor changed')
    need('setNextWatchHintEnabled' not in before, 'Next-watch settings already carries the setter')
    archived = archive_settings_text(repo)
    for text in (UPSTREAM_KEY, UPSTREAM_GETTER, UPSTREAM_SETTER):
        need(archived.count(text) == 1, 'Next-watch archived original declaration changed')
    # The retained key and getter are still exactly the adapted originals, so the setter joins the same trio.
    need(adapt(UPSTREAM_KEY, KEY_ADAPTERS) in before and adapt(UPSTREAM_GETTER, GETTER_ADAPTERS) in before,
         'Next-watch retained key or getter drifted from the original declarations')
    setter = adapted_setter()
    need(unadapt(setter, SETTER_ADAPTERS) == UPSTREAM_SETTER, 'Next-watch setter inverse failed')
    edits = []
    after = _swap(before, edits, SETTINGS_KDOC_BEFORE, SETTINGS_KDOC_AFTER,
                  'The object now carries the original setter as well as the key and getter')
    after = _swap(after, edits, SETTINGS_OBJECT_END, SETTINGS_OBJECT_END[:-len('}\n')] + '\n' + setter + '}\n',
                  'Original setNextWatchHintEnabled with the Windows settings store binding')
    need(after.count('fun setNextWatchHintEnabled(') == 1 and after.count('KEY_NEXT_WATCH_HINT_ENABLED') == 3,
         'Next-watch settings edit disturbed another declaration')
    need(restore(after, edits) == before, 'Next-watch settings input was not preserved')
    need(sha(after) == SETTINGS_ADVANCED_SHA256, 'Next-watch settings result changed')
    return after, dict(path=SETTINGS_RELATIVE, upstreamPath=SETTINGS_UPSTREAM_PATH, upstreamBlob=SETTINGS_UPSTREAM_BLOB,
                       archive=ARCHIVE, archiveLeaf=ARCHIVE_SETTINGS_LEAF, archiveManifestSHA256=ARCHIVE_MANIFEST_SHA,
                       upstreamKeySHA256=UPSTREAM_KEY_SHA256, upstreamGetterSHA256=UPSTREAM_GETTER_SHA256,
                       upstreamSetterSHA256=UPSTREAM_SETTER_SHA256, adaptedSetterSHA256=sha(setter),
                       inputSHA256=sha(before), outputSHA256=sha(after), fullInputInverseRecovered=True, edits=edits)


def advance_outputs(repo, output):
    """Advances both emitted files in place and writes one proof JSON. Returns {relative path: final sha256}."""
    root = Path(output)
    steps = ((SETTINGS_RELATIVE, lambda text: advance_hint_settings(repo, text)),
             (OVERLAY_RELATIVE, advance_overlay))
    planned, results, proofs = [], {}, []
    for relative, advance in steps:
        target = wide(root / relative)
        need(target.is_file(), 'Next-watch output is missing: ' + relative)
        before = target.read_bytes().decode('utf8')
        need('\r' not in before, 'Next-watch output has carriage returns: ' + relative)
        after, proof = advance(before)
        planned.append((target, after))
        results[relative] = proof['outputSHA256']
        proofs.append(proof)
    # Nothing is written unless both advances succeeded.
    for target, after in planned:
        target.write_bytes(after.encode('utf8'))
    summary = dict(schemaVersion=1, upstreamCommit=UPSTREAM_COMMIT, archiveCommit=ARCHIVE_COMMIT,
                   suggestionUnchangedSinceArchive=dict(path=SUGGESTION_UPSTREAM_PATH, blob=SUGGESTION_UPSTREAM_BLOB),
                   outputs=proofs, mainCompiled=False, applicationExecuted=False)
    wide(root / PROOF_NAME).write_bytes((json.dumps(summary, ensure_ascii=False, indent=2) + '\n').encode('utf8'))
    return results
