"""v0.4.6 next-watch revision and its on/off switch: the pinned layer on the sole full-controls producer, the
mounted Windows pane, both catalogs and the JVM regression class. Source checks only: no Gradle, no JVM."""
from pathlib import Path
import difflib
import importlib.util
import json
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO/'desktop/tools'
sys.path.insert(0, str(TOOLS))
import v046_next_watch_toggle as toggle

ARCHIVE = REPO/toggle.ARCHIVE
KOTLIN = REPO/'desktop/src/main/kotlin/com/bilipai/desktop'
PANE = KOTLIN/'ui/DesktopWindowsPlaybackSettings.kt'
SHARE_CATALOG = KOTLIN/'settings/DesktopSettingsShareWindowsCatalog.kt'
SEARCH_CATALOG = KOTLIN/'settings/DesktopWindowsSettingsCatalog.kt'
JVM_TEST = REPO/'desktop/src/test/kotlin/com/bilipai/desktop/settings/DesktopNextWatchHintSettingsTest.kt'
VIEW_MODEL = 'app/src/main/java/com/android/purebilibili/feature/video/viewmodel/VideoPlaybackViewModel.kt'
BASE = 'com/android/purebilibili/'


def git(*args):
    return subprocess.check_output(['git', '-c', 'core.autocrlf=false', '-C', str(REPO), *args])


def upstream(path, commit=toggle.UPSTREAM_COMMIT):
    return git('show', commit + ':' + path).decode('utf8')


def load(filename):
    spec = importlib.util.spec_from_file_location('next_watch_toggle_' + filename.replace('-', '_').replace('.', '_'), TOOLS/filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def text(path):
    return Path(path).read_bytes().decode('utf8')


def tree(root):
    return {p.relative_to(root).as_posix(): p.read_bytes() for p in Path(root).rglob('*') if p.is_file()}


ENTRY = re.compile(r'DesktopWindowsSettingsSearchEntry\(SettingsSearchTarget\.(\w+), "([^"]*)", "([^"]*)", "([^"]*)", '
                   r'listOf\(([^)]*)\)(?:, "([^"]*)")?\)')


def parse_search_entries(source):
    return [(m.group(1), m.group(2), m.group(3), m.group(4), re.findall(r'"([^"]*)"', m.group(5)), m.group(6))
            for m in ENTRY.finditer(source)]


def search_entries(entries, query):
    """resolveDesktopWindowsSettingsSearchResults: trimmed, split on whitespace, every term hits one searchable text."""
    trimmed = query.strip()
    if not trimmed:
        return []
    terms = re.split(r'\s+', trimmed)
    found = []
    for target, title, subtitle, section, words, focus in entries:
        searchable = [title, subtitle, section] + words
        if all(any(term.lower() in s.lower() for s in searchable) for term in terms):
            found.append((target, title, focus))
    return found[:20]


class NextWatchToggleSource(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='bp-nw-')
        root = Path(cls.temp.name)
        cls.producer = load('extract-upstream-video-player-full-controls.py')
        cls.producer.generate(REPO, root/'actual')
        cls.rows = list(cls.producer.OUTPUTS)
        cls.producer.generate(REPO, root/'again')
        # The same producer with the v0.4.6 layer patched out: only the intended outputs may differ.
        with patch.object(cls.producer, '_advance_next_watch_v046', side_effect=lambda repo, output: {
                toggle.SETTINGS_RELATIVE: toggle.SETTINGS_PREDECESSOR_SHA256,
                toggle.OVERLAY_RELATIVE: toggle.OVERLAY_PREDECESSOR_SHA256}):
            cls.producer.generate(REPO, root/'before')
        cls.actual, cls.again, cls.before = root/'actual', root/'again', root/'before'
        cls.manifest = json.loads(text(ARCHIVE/'manifest.json'))
        cls.proof = json.loads(text(cls.actual/toggle.PROOF_NAME))

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def out(self, relative, root=None):
        return text((root or self.actual)/relative)

    def proof_for(self, relative):
        return next(row for row in self.proof['outputs'] if row['path'] == relative)

    # --- pins and provenance (hard failures when the local upstream objects are missing, never skipped) ---------

    def test_upstream_pins_match_their_hashes_and_the_local_v046_objects(self):
        toggle.assert_pins()
        self.assertEqual(b'commit', git('cat-file', '-t', toggle.UPSTREAM_COMMIT).strip())
        self.assertEqual('d6bf8b1655ae910999860bf13e65c8fcea5b345b', toggle.UPSTREAM_COMMIT)
        for path, blob in ((toggle.OVERLAY_UPSTREAM_PATH, toggle.OVERLAY_UPSTREAM_BLOB),
                           (toggle.SETTINGS_UPSTREAM_PATH, toggle.SETTINGS_UPSTREAM_BLOB),
                           (toggle.SUGGESTION_UPSTREAM_PATH, toggle.SUGGESTION_UPSTREAM_BLOB)):
            self.assertEqual(blob, git('rev-parse', toggle.UPSTREAM_COMMIT + ':' + path).decode().strip(), path)
        overlay = upstream(toggle.OVERLAY_UPSTREAM_PATH)
        self.assertEqual(1, overlay.count(toggle.UPSTREAM_OVERLAY_IMPORT_BLOCK))
        self.assertEqual(1, overlay.count(toggle.UPSTREAM_OVERLAY_COLOR_CONTEXT))
        settings = upstream(toggle.SETTINGS_UPSTREAM_PATH)
        for snippet in (toggle.UPSTREAM_KEY, toggle.UPSTREAM_GETTER, toggle.UPSTREAM_SETTER):
            self.assertEqual(1, settings.count(snippet), snippet)
        # v0.4.6 shares the switch under PLAYBACK as a boolean, which the Windows share catalog mirrors.
        self.assertIn('BooleanShareablePreferenceDefinition(KEY_NEXT_WATCH_HINT_ENABLED, SettingsShareSection.PLAYBACK)', settings)

    def test_the_v034_archive_already_holds_the_same_key_getter_and_setter_bytes(self):
        archived = toggle.archive_settings_text(REPO)
        for snippet in (toggle.UPSTREAM_KEY, toggle.UPSTREAM_GETTER, toggle.UPSTREAM_SETTER):
            self.assertEqual(1, archived.count(snippet), snippet)
        self.assertEqual(toggle.ARCHIVE_COMMIT, self.manifest['upstreamCommit'])
        self.assertEqual(toggle.ARCHIVE_MANIFEST_SHA, toggle.sha((ARCHIVE/'manifest.json').read_bytes()))

    def test_suggestion_policy_and_view_model_lookahead_are_unchanged_since_the_archives(self):
        carried = json.loads(text(REPO/'desktop/upstream-slices/v034-next-watch/manifest.json'))
        row = carried['sources'][0]
        self.assertEqual(toggle.SUGGESTION_UPSTREAM_PATH, row['originalPath'])
        self.assertEqual(toggle.SUGGESTION_UPSTREAM_BLOB, row['gitBlob'])
        self.assertEqual(row['sha256'], toggle.sha((REPO/'desktop/upstream-slices/v034-next-watch/NextWatchSuggestion.kt').read_bytes()))

        def lookahead(commit):
            source = upstream(VIEW_MODEL, commit)
            start = source.index('    /** Read-only lookahead.')
            end = source.index('\n    }\n', source.index('internal fun playNextWatchSuggestion', start)) + 7
            return source[start:end]
        old, new = lookahead(toggle.ARCHIVE_COMMIT), lookahead(toggle.UPSTREAM_COMMIT)
        self.assertEqual(old, new)
        self.assertIn('internal fun resolveNextWatchSuggestion()', new)
        self.assertIn('internal fun playNextWatchSuggestion(', new)

    # --- the overlay revision ----------------------------------------------------------------------------------

    def test_v046_overlay_differs_from_the_archived_overlay_by_exactly_the_two_pinned_hunks(self):
        old = upstream(toggle.OVERLAY_UPSTREAM_PATH, toggle.ARCHIVE_COMMIT)
        new = upstream(toggle.OVERLAY_UPSTREAM_PATH)
        archived = (ARCHIVE/'NextWatchOverlay.kt').read_bytes().decode('utf8')
        self.assertEqual(old, archived)
        a, b = old.splitlines(keepends=True), new.splitlines(keepends=True)
        changes = [(tag, ''.join(a[i1:i2]), ''.join(b[j1:j2]))
                   for tag, i1, i2, j1, j2 in difflib.SequenceMatcher(None, a, b, autojunk=False).get_opcodes() if tag != 'equal']
        self.assertEqual([('insert', '', '\nimport com.android.purebilibili.core.ui.AppContentColors\n'),
                          ('replace', '        else MaterialTheme.colorScheme.onSurface\n', '        else AppContentColors.primaryText()\n')],
                         changes)

    def test_replaying_the_v034_recipe_over_the_whole_v046_overlay_gives_exactly_the_emitted_overlay(self):
        recipe = self.manifest['outputs'][toggle.OVERLAY_RELATIVE]
        replayed = upstream(toggle.OVERLAY_UPSTREAM_PATH)
        for edit in recipe['edits']:
            self.assertEqual(1, replayed.count(edit['before']), edit['before'][:60])
            replayed = replayed.replace(edit['before'], edit['after'], 1)
        self.assertEqual(replayed, self.out(toggle.OVERLAY_RELATIVE))
        self.assertEqual(toggle.OVERLAY_ADVANCED_SHA256, toggle.sha(replayed))
        # And the layer applied to the archived predecessor reaches the same bytes.
        self.assertEqual(recipe['afterSha256'], toggle.OVERLAY_PREDECESSOR_SHA256)

    def test_emitted_overlay_uses_the_v046_content_colour_and_keeps_the_rest_of_the_card_unchanged(self):
        overlay = self.out(toggle.OVERLAY_RELATIVE)
        before = self.out(toggle.OVERLAY_RELATIVE, self.before)
        self.assertEqual(1, overlay.count('AppContentColors.primaryText()'))
        self.assertEqual(1, overlay.count('import com.android.purebilibili.core.ui.AppContentColors\n'))
        self.assertEqual(0, overlay.count('MaterialTheme.colorScheme.onSurface'))
        self.assertEqual(0, before.count('AppContentColors'))
        self.assertTrue(overlay.startswith('package com.android.purebilibili.feature.video.ui.overlay\n\nimport com.android.purebilibili.core.ui.AppContentColors\n\n'))
        # The overlay still reads its switch from the one owner and still returns early when it is off.
        self.assertEqual(1, overlay.count('DesktopOriginalNextWatchHintSettings.getNextWatchHintEnabled(settingsContext)'))
        self.assertIn('if (!hintEnabled) return', overlay)
        self.assertEqual(1, overlay.count('fun BoxScope.NextWatchOverlay('))
        self.assertEqual(toggle.OVERLAY_ADVANCED_SHA256, toggle.sha(overlay))

    def test_the_content_colour_definer_is_in_the_compile_universe(self):
        colors = text(REPO/'desktop/upstream-slices/v042-search-dependencies/AppContentColors.kt')
        self.assertIn('package com.android.purebilibili.core.ui\n', colors)
        self.assertEqual(1, colors.count('object AppContentColors {'))
        self.assertIn('fun primaryText(): Color', colors)
        emitter = text(TOOLS/'extract-upstream-danmaku-settings.py')
        self.assertIn("emit('com/android/purebilibili/core/ui/AppContentColors.kt'", emitter)
        build = text(REPO/'desktop/build.gradle.kts')
        self.assertEqual(1, build.count('kotlin.srcDir(layout.buildDirectory.dir("generated/original-danmaku-settings/com"))'))

    # --- the setter --------------------------------------------------------------------------------------------

    def test_emitted_settings_object_carries_the_original_key_getter_and_setter_over_the_windows_store(self):
        settings = self.out(toggle.SETTINGS_RELATIVE)
        self.assertEqual(toggle.SETTINGS_ADVANCED_SHA256, toggle.sha(settings))
        self.assertIn(toggle.adapt(toggle.UPSTREAM_KEY, toggle.KEY_ADAPTERS), settings)
        self.assertIn(toggle.adapt(toggle.UPSTREAM_GETTER, toggle.GETTER_ADAPTERS), settings)
        self.assertEqual(1, settings.count(toggle.adapted_setter()))
        self.assertEqual(1, settings.count('suspend fun setNextWatchHintEnabled('))
        self.assertEqual(3, settings.count('KEY_NEXT_WATCH_HINT_ENABLED'))
        self.assertEqual(1, settings.count('"next_watch_hint_enabled"'))
        self.assertIn('?: true', settings)
        self.assertEqual(1, settings.count('internal object DesktopOriginalNextWatchHintSettings {'))
        for android_port in ('settingsDataStore', 'context: Context', 'booleanPreferencesKey(', 'android.content'):
            self.assertNotIn(android_port, settings, android_port)
        self.assertEqual(toggle.UPSTREAM_SETTER, toggle.unadapt(toggle.adapted_setter(), toggle.SETTER_ADAPTERS))

    def test_the_setter_joins_the_existing_single_owner_and_no_second_definer_exists(self):
        everything = ''.join(text(p) for p in self.actual.rglob('*.kt'))
        self.assertEqual(1, everything.count('object DesktopOriginalNextWatchHintSettings'))
        self.assertEqual(1, everything.count('fun setNextWatchHintEnabled('))
        self.assertEqual(1, everything.count('"next_watch_hint_enabled"'))
        owned = ''.join(text(p) for p in KOTLIN.rglob('*.kt'))
        self.assertEqual(0, owned.count('object DesktopOriginalNextWatchHintSettings'))
        self.assertEqual(0, owned.count('fun setNextWatchHintEnabled('))
        # The whole-playback producer selects only members its v0.2.5 screen names, so it never defines these.
        with tempfile.TemporaryDirectory(prefix='bp-nwp-') as temp:
            load('extract-upstream-whole-playback-settings.py').main(REPO, Path(temp))
            preferences = ''.join(text(p) for p in Path(temp).rglob('DesktopOriginalPlaybackSettingsPreferences.kt'))
            self.assertTrue(preferences)
            self.assertNotIn('NextWatchHint', preferences)
            self.assertNotIn('next_watch_hint_enabled', preferences)

    # --- proofs, layer position and fail-closed drift ----------------------------------------------------------

    def test_inverse_proofs_recover_the_v034_predecessors_exactly(self):
        for relative, predecessor in ((toggle.SETTINGS_RELATIVE, toggle.SETTINGS_PREDECESSOR_SHA256),
                                      (toggle.OVERLAY_RELATIVE, toggle.OVERLAY_PREDECESSOR_SHA256)):
            proof = self.proof_for(relative)
            restored = toggle.restore(self.out(relative), proof['edits'])
            self.assertEqual(predecessor, toggle.sha(restored))
            self.assertEqual(restored, self.out(relative, self.before))
            self.assertEqual(predecessor, proof['inputSHA256'])
            self.assertEqual(toggle.sha(self.out(relative)), proof['outputSHA256'])
            self.assertTrue(proof['fullInputInverseRecovered'])
            self.assertTrue(all(edit['count'] == 1 for edit in proof['edits']))
        self.assertEqual(2, len(self.proof_for(toggle.SETTINGS_RELATIVE)['edits']))
        self.assertEqual(2, len(self.proof_for(toggle.OVERLAY_RELATIVE)['edits']))
        self.assertFalse(self.proof['mainCompiled'])
        self.assertFalse(self.proof['applicationExecuted'])
        # The pinned predecessors are exactly what the v0.3.4 recipes in the archive emit.
        for relative, predecessor in ((toggle.SETTINGS_RELATIVE, toggle.SETTINGS_PREDECESSOR_SHA256),
                                      (toggle.OVERLAY_RELATIVE, toggle.OVERLAY_PREDECESSOR_SHA256)):
            self.assertEqual(predecessor, self.manifest['outputs'][relative]['afterSha256'])

    def test_only_the_intended_outputs_differ_when_the_layer_is_patched_out(self):
        actual, before = tree(self.actual), tree(self.before)
        self.assertEqual(set(before) | {toggle.PROOF_NAME}, set(actual))
        differing = {name for name in before if before[name] != actual[name]}
        self.assertEqual({BASE + 'feature/video/ui/overlay/NextWatchOverlay.kt', BASE + 'core/store/DesktopOriginalNextWatchHintSettings.kt',
                          'v037-complete-controls-source-readback.json'}, differing)
        rows = {row['path']: row for row in json.loads(actual['v037-complete-controls-source-readback.json'])['outputs']}
        earlier = {row['path']: row for row in json.loads(before['v037-complete-controls-source-readback.json'])['outputs']}
        self.assertEqual(set(earlier), set(rows))
        changed = {path for path in rows if rows[path] != earlier[path]}
        self.assertEqual({toggle.OVERLAY_RELATIVE, toggle.SETTINGS_RELATIVE}, changed)
        for relative, result in ((toggle.OVERLAY_RELATIVE, toggle.OVERLAY_ADVANCED_SHA256), (toggle.SETTINGS_RELATIVE, toggle.SETTINGS_ADVANCED_SHA256)):
            self.assertEqual(result, rows[relative]['sha256LF'])
            self.assertTrue(rows[relative]['v046NextWatchToggleAdvance'])
            self.assertEqual(rows[relative]['predecessorSha256LF'], earlier[relative]['sha256LF'])
        self.assertEqual(2, sum(1 for row in self.rows if row.get('v046NextWatchToggleAdvance')))

    def test_generation_is_deterministic(self):
        first, second = tree(self.actual), tree(self.again)
        self.assertEqual(first.keys(), second.keys())
        for name in first:
            self.assertEqual(first[name], second[name], name)

    def test_drift_and_anchor_changes_fail_closed_before_anything_is_written(self):
        overlay = self.out(toggle.OVERLAY_RELATIVE, self.before)
        settings = self.out(toggle.SETTINGS_RELATIVE, self.before)
        with self.assertRaises(ValueError):
            toggle.advance_overlay('unrelated body')
        with self.assertRaises(ValueError):
            toggle.advance_overlay(self.out(toggle.OVERLAY_RELATIVE))
        with self.assertRaises(ValueError):
            toggle.advance_hint_settings(REPO, 'unrelated body')
        with self.assertRaises(ValueError):
            toggle.advance_hint_settings(REPO, self.out(toggle.SETTINGS_RELATIVE))
        # A body that passes the predecessor pin but whose anchor moved, vanished or doubled must still be refused.
        for moved in (overlay.replace('else MaterialTheme.colorScheme.onSurface', 'else MaterialTheme.colorScheme.onSurfaceVariant'),
                      overlay.replace('ui.overlay\n\nimport', 'ui.overlay\nimport', 1),
                      overlay + '        else MaterialTheme.colorScheme.onSurface\n'):
            with patch.object(toggle, 'OVERLAY_PREDECESSOR_SHA256', toggle.sha(moved)), self.assertRaisesRegex(ValueError, 'anchor'):
                toggle.advance_overlay(moved)
        for moved in (settings.replace('?: true', '?: false'), settings + settings):
            with patch.object(toggle, 'SETTINGS_PREDECESSOR_SHA256', toggle.sha(moved)), self.assertRaises(ValueError):
                toggle.advance_hint_settings(REPO, moved)
        moved = settings.replace('playerBooleanPreferencesKey("next_watch_hint_enabled")',
                                 'playerBooleanPreferencesKey("next_watch_hint_on")')
        with patch.object(toggle, 'SETTINGS_PREDECESSOR_SHA256', toggle.sha(moved)), self.assertRaisesRegex(ValueError, 'drifted'):
            toggle.advance_hint_settings(REPO, moved)
        with tempfile.TemporaryDirectory(prefix='bp-nwd-') as temp:
            root = Path(temp)
            target = root/toggle.SETTINGS_RELATIVE
            target.parent.mkdir(parents=True)
            target.write_bytes(settings.encode('utf8'))
            broken = root/toggle.OVERLAY_RELATIVE
            broken.parent.mkdir(parents=True)
            broken.write_bytes(b'not the overlay')
            with self.assertRaises(ValueError):
                toggle.advance_outputs(REPO, root)
            self.assertEqual(settings.encode('utf8'), target.read_bytes())
            self.assertFalse((root/toggle.PROOF_NAME).exists())
            broken.write_bytes(overlay.replace('\n', '\r\n').encode('utf8'))
            with self.assertRaises(ValueError):
                toggle.advance_outputs(REPO, root)
            self.assertEqual(settings.encode('utf8'), target.read_bytes())

    def test_archive_tampering_fails_closed(self):
        for name in ('SettingsManager.kt', 'manifest.json'):
            with self.subTest(name=name), tempfile.TemporaryDirectory(prefix='bp-nwa-') as temp:
                repo = Path(temp)
                shutil.copytree(ARCHIVE, repo/toggle.ARCHIVE)
                leaf = repo/toggle.ARCHIVE/name
                leaf.write_bytes(leaf.read_bytes() + b'\n')
                with self.assertRaises(ValueError):
                    toggle.archive_settings_text(repo)
        with tempfile.TemporaryDirectory(prefix='bp-nwa-') as temp:
            repo = Path(temp)
            shutil.copytree(ARCHIVE, repo/toggle.ARCHIVE)
            self.assertEqual(toggle.archive_settings_text(REPO), toggle.archive_settings_text(repo))

    # --- mounted Windows pane, catalogs, Gradle wiring and the JVM class ---------------------------------------

    def test_mounted_windows_pane_writes_the_switch_through_the_single_owner_before_the_auto_play_row(self):
        pane = text(PANE)
        group = pane[pane.index('DesktopWindowsSettingsGroup("播放行为"'):pane.index('DesktopWindowsSettingsGroup("评论"')]
        resume = group.index('DesktopWindowsSettingsSwitch("历史续播提示"')
        card = group.index('DesktopWindowsSettingsSwitch("接下来观看卡片"')
        auto = group.index('DesktopWindowsSettingsSwitch("自动播放下一个"')
        self.assertTrue(resume < card < auto)
        self.assertEqual(1, pane.count('import com.android.purebilibili.core.store.DesktopOriginalNextWatchHintSettings\n'))
        self.assertEqual(1, pane.count('desktopWindowsSettingsValue(remember(context) { DesktopOriginalNextWatchHintSettings.getNextWatchHintEnabled(context) })'))
        self.assertEqual(1, pane.count('writer.launch { DesktopOriginalNextWatchHintSettings.setNextWatchHintEnabled(context, enabled) }'))
        self.assertEqual(1, pane.count('DesktopWindowsSettingsSwitch("接下来观看卡片", nextWatchHint,'))
        self.assertIn('最后 5 秒提示下一集或相关推荐，关闭不影响自动续播', group)
        self.assertNotIn('Preferences.getNextWatchHintEnabled', pane)
        self.assertNotIn('Preferences.setNextWatchHintEnabled', pane)
        # The auto-continue gates keep their own owner untouched.
        self.assertEqual(1, pane.count('writer.launch { Preferences.setAutoPlay(context, enabled) }'))
        self.assertEqual(1, pane.count('writer.launch { Preferences.setExternalPlaylistAutoContinue(context, enabled) }'))

    def test_the_generated_original_playback_screen_is_not_the_mounted_page(self):
        host = text(KOTLIN/'ui/DesktopOriginalPlaybackSettingsRootHost.kt')
        self.assertEqual(1, host.count('DesktopWindowsPlaybackSettings(bindings, onBack = ownedBack)'))
        self.assertEqual(1, host.count('PlaybackSettingsScreen'))  # the unused import is its only mention
        self.assertEqual(1, host.count('import com.android.purebilibili.feature.settings.PlaybackSettingsScreen\n'))

    def test_share_catalog_allows_the_boolean_once_in_the_playback_booleans_and_keeps_its_line_endings(self):
        raw = SHARE_CATALOG.read_bytes()
        self.assertEqual(raw.count(b'\n'), raw.count(b'\r\n'), 'mixed line endings in the CRLF share catalog')
        catalog = raw.decode('utf8').replace('\r\n', '\n')
        self.assertEqual(1, catalog.count('"next_watch_hint_enabled"'))
        playback = re.search(r'booleans\(playback, ([^)]*)\)', catalog)
        keys = re.findall(r'"([^"]+)"', playback.group(1))
        self.assertEqual(['next_watch_hint_enabled', 'auto_play'], keys[:2])
        self.assertEqual(len(keys), len(set(keys)))
        self.assertIn('next_watch_hint_enabled is a v0.3.4 key too', catalog)

    def test_search_catalog_words_reach_the_switch_only_through_the_playback_behaviour_entry(self):
        catalog = text(SEARCH_CATALOG)
        line = next(row for row in catalog.splitlines() if '"windows_playback_behavior"' in row)
        for word in ('接下来观看', '接下来观看卡片', '下一集提示', '相关推荐提示'):
            self.assertEqual(1, line.count('"' + word + '"'), word)
            self.assertEqual(1, catalog.count('"' + word + '"'), word)
        # Replay the controller's matching (every whitespace term must hit title, subtitle, section or a word, case
        # blind) for every string the existing catalog JVM test uses as a query: the four new words change none.
        entries = parse_search_entries(catalog)
        new_words = {'接下来观看', '接下来观看卡片', '下一集提示', '相关推荐提示'}
        old_entries = [(target, title, sub, section, [w for w in words if w not in new_words], focus)
                       for target, title, sub, section, words, focus in entries]
        self.assertEqual(15, len(entries))
        queries = set(re.findall(r'"([^"\n]+)"', text(REPO/'desktop/src/test/kotlin/com/bilipai/desktop/settings/DesktopWindowsSettingsCatalogTest.kt')))
        queries |= {'  wAsApI  输出 ', '自动播放下一个', '列表连续播放', '收藏夹'}
        self.assertGreater(len(queries), 60)
        for query in sorted(queries - new_words):
            self.assertEqual(search_entries(old_entries, query), search_entries(entries, query), query)
        for query in sorted(new_words):
            self.assertEqual([('PLAYBACK', '播放行为', 'windows_playback_behavior')], search_entries(entries, query), query)
            self.assertEqual([], search_entries(old_entries, query), query)

    def test_gradle_wiring_adds_only_the_generator_input_to_the_sole_producer(self):
        build = text(REPO/'desktop/build.gradle.kts')
        self.assertEqual(1, build.count('tasks.named("extractOriginalPlayerFullControls") { inputs.file("tools/v046_next_watch_toggle.py") }'))
        self.assertEqual(1, build.count('inputs.file("tools/v034_next_watch_ui.py")'))
        self.assertEqual(1, build.count('inputs.dir("upstream-slices/v034-next-watch-ui")'))
        self.assertNotIn('upstream-slices/v046-next-watch', build)
        self.assertFalse((REPO/'desktop/upstream-slices/v046-next-watch-toggle').exists())
        self.assertEqual(0, build.count('exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/NextWatchOverlay.kt"))'))
        producer = text(TOOLS/'extract-upstream-video-player-full-controls.py')
        self.assertEqual(1, producer.count('from v046_next_watch_toggle import advance_outputs as _advance_next_watch_v046\n'))
        self.assertEqual(1, producer.count('advanced_next_watch=_advance_next_watch_v046(REPO,OUTPUT)'))
        self.assertLess(producer.index('_emit_original_next_watch_ui(REPO,OUTPUT)'), producer.index('_advance_next_watch_v046(REPO,OUTPUT)'))

    def test_jvm_regression_class_is_self_contained_and_balanced(self):
        source = text(JVM_TEST)
        self.assertEqual(7, source.count('@Test fun '))
        self.assertIn('class DesktopNextWatchHintSettingsTest {', source)
        for forbidden in ('java.net', 'HttpClient', 'okhttp', 'ProcessBuilder', 'Runtime.getRuntime', 'System.getenv', 'Thread.sleep',
                          'DesktopWindowsGpu', 'MpvPlayer', 'LibMpv'):
            self.assertNotIn(forbidden, source, forbidden)
        self.assertEqual(1, source.count('Files.createTempDirectory("bp-next-watch-hint-settings-")'))
        parser = load('sync-upstream.py')
        for file in (JVM_TEST, PANE, SEARCH_CATALOG, SHARE_CATALOG):
            stack = []
            for token, first, _ in parser.kotlin_tokens(text(file)):
                if token in ('(', '[', '{'):
                    stack.append(token)
                elif token in (')', ']', '}'):
                    self.assertTrue(stack, (file.name, first))
                    self.assertEqual({')': '(', ']': '[', '}': '{'}[token], stack.pop(), (file.name, first))
            self.assertEqual([], stack, file.name)

    def test_new_and_lf_only_files_carry_no_carriage_return(self):
        for path in (TOOLS/'v046_next_watch_toggle.py', Path(__file__), JVM_TEST, PANE, SEARCH_CATALOG,
                     TOOLS/'extract-upstream-video-player-full-controls.py', REPO/'desktop/build.gradle.kts'):
            self.assertNotIn(b'\r', path.read_bytes(), path.name)


if __name__ == '__main__':
    unittest.main()
