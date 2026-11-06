"""Pinned upstream UP-list preference on the existing desktop settings/Host chain.

Only Android context/DataStore imports and the settings renderer seam change.
No second preference backing or runtime state owner is introduced.
"""
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json

ARCHIVE = Path(__file__).resolve().parent.parent / 'upstream-slices/dynamic-up-list'
MANIFEST_SHA256 = '1279af04cb976428015a2f852f07bcdc0bcbb07bc886270f6cc991a826a6bb13'
COMMIT = '6f0696eb1389728b5a090125d32c1aae470f72f1'
STORE_OUTPUT = 'com/android/purebilibili/core/store/DesktopDynamicUpListSettings.kt'
FIELD_OUTPUT = 'com/android/purebilibili/feature/settings/DesktopDynamicUpListSettingsField.kt'


def sha(raw):
    return hashlib.sha256(raw.encode() if isinstance(raw, str) else raw).hexdigest()


def originals():
    raw = (ARCHIVE / 'manifest.json').read_bytes()
    if sha(raw) != MANIFEST_SHA256:
        raise ValueError('UP-list manifest changed')
    manifest = json.loads(raw)
    if manifest['commit'] != COMMIT or {p.name for p in ARCHIVE.iterdir()} != {
        'manifest.json', 'DynamicSettingsStore.kt', 'HomeFeedSettingsSection.kt', 'LICENSE'
    }:
        raise ValueError('UP-list source identity changed')
    sources = {}
    for row in manifest['files']:
        path = ARCHIVE / row['filename']
        raw = path.read_bytes()
        blob = hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()
        if path.is_symlink() or sha(raw) != row['sha256'] or blob != row['gitBlob']:
            raise ValueError('UP-list original changed: ' + row['filename'])
        sources[row['filename']] = raw.decode('utf8')
    return sources, manifest


def selections(repo):
    spec = importlib.util.spec_from_file_location('up_list_host', repo / 'desktop/tools/extract-upstream-plugins.py')
    host = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(host)
    media = host.media_extractor(repo)
    parser = media.parser_for(repo)
    sources, manifest = originals()
    source = sources['DynamicSettingsStore.kt']
    key = 'internal val dynamicUpListVisiblePreferencesKey = booleanPreferencesKey("dynamic_up_list_visible")'
    if source.count(key) != 1:
        raise ValueError('UP-list original key changed')
    store = key + '\n\nobject DesktopDynamicUpListSettings {\n' + '\n\n'.join(
        media.function(source, name, parser) for name in ('observeUpListVisible', 'setUpListVisible')) + '\n}\n'
    ui = media.function(sources['HomeFeedSettingsSection.kt'], 'DynamicUpListVisibilityPreference', parser)
    start = ui.index('        com.android.purebilibili.core.ui.components.AppSwitchPreference(')
    # Select the complete original control, leaving the Android-only lifecycle/anchor outside.
    control = ui[start:ui.rindex('\n    }')].strip()
    edits = [
        ('settingItemTitle("home.dynamic_up_list_visible")', '"显示动态UP主头像栏"'),
        ('rememberSettingsSemanticIcon(SettingsIconRole.DYNAMIC_UP_LIST)', 'com.android.purebilibili.core.ui.rememberAppDynamicIcon()'),
        ('{ value -> scope.launch { DynamicSettingsStore.setUpListVisible(context, value) } }', 'onVisibilityChange'),
    ]
    adapted = control
    for before, after in edits:
        if adapted.count(before) != 1:
            raise ValueError('UP-list control seam changed: ' + before)
        adapted = adapted.replace(before, after, 1)
    return store, control, adapted, edits, manifest


def bodies(repo):
    store, control, adapted, edits, manifest = selections(repo)
    return {
        STORE_OUTPUT: '''package com.android.purebilibili.core.store
import com.bilipai.desktop.plugins.DesktopPluginContext as Context
import com.bilipai.desktop.plugins.booleanPreferencesKey
import com.bilipai.desktop.settings.dynamicSettingsDataStore as settingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

''' + store,
        FIELD_OUTPUT: '''package com.android.purebilibili.feature.settings
import androidx.compose.runtime.Composable

@Composable internal fun DesktopDynamicUpListSettingsField(visible: Boolean, onVisibilityChange: (Boolean) -> Unit) {
''' + adapted + '\n}\n',
    }


def generate(repo, output):
    store, control, adapted, edits, manifest = selections(repo)
    expected = bodies(repo)
    files = []
    for relative, body in expected.items():
        file = output / relative
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text('// GENERATED from upstream ' + COMMIT + '; dynamic-up-list/manifest.json\n' + body, encoding='utf8', newline='\n')
        files.append(file)
    receipt = dict(commit=COMMIT, sources=manifest['files'], storeSelection=store,
                   originalControl=control, adaptedControl=adapted,
                   controlEdits=[dict(before=a, after=b) for a, b in edits],
                   outputs={p: sha((output / p).read_bytes()) for p in expected})
    (output / 'dynamic-up-list-proof.json').write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + '\n', encoding='utf8', newline='\n')
    verify(repo, output)
    return files


def verify(repo, output):
    """Read the actual CLI outputs; reject drift and reverse every control adaptation."""
    store, control, adapted, edits, manifest = selections(repo)
    receipt = json.loads((output / 'dynamic-up-list-proof.json').read_text(encoding='utf8'))
    if receipt['commit'] != COMMIT or receipt['sources'] != manifest['files']:
        raise ValueError('UP-list receipt identity changed')
    if receipt['controlEdits'] != [dict(before=a, after=b) for a, b in edits]:
        raise ValueError('UP-list control seam receipt changed')
    expected = bodies(repo)
    for relative in (STORE_OUTPUT, FIELD_OUTPUT):
        raw = (output / relative).read_bytes()
        if sha(raw) != receipt['outputs'][relative]:
            raise ValueError('UP-list actual output changed: ' + relative)
        body = '// GENERATED from upstream ' + COMMIT + '; dynamic-up-list/manifest.json\n' + expected[relative]
        if raw != body.encode('utf8'):
            raise ValueError('UP-list output wrapper or body changed: ' + relative)
    actual_store = (output / STORE_OUTPUT).read_text(encoding='utf8')
    if actual_store.count(store) != 1 or receipt['storeSelection'] != store:
        raise ValueError('UP-list original store selection not preserved')
    # Reconstruct the entire emitted field from its wrapper, then undo exact seams.
    actual = (output / FIELD_OUTPUT).read_text(encoding='utf8').split(') {\n', 1)[1].removesuffix('\n}\n')
    if actual != adapted or receipt['adaptedControl'] != adapted or receipt['originalControl'] != control:
        raise ValueError('UP-list actual control changed')
    for before, after in reversed(edits):
        if actual.count(after) != 1:
            raise ValueError('UP-list inverse seam is ambiguous')
        actual = actual.replace(after, before, 1)
    if actual != control:
        raise ValueError('UP-list control inverse mismatch')
    return dict(storeSelectionSha256=sha(store), controlInverseSha256=sha(actual), outputs=receipt['outputs'])


if __name__ == '__main__':
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument('--repo', type=Path, required=True)
    cli.add_argument('--output', type=Path, required=True)
    cli.add_argument('--verify', action='store_true')
    args = cli.parse_args()
    if not args.verify:
        generate(args.repo.resolve(), args.output.resolve())
    print(json.dumps(verify(args.repo.resolve(), args.output.resolve()), indent=2))
