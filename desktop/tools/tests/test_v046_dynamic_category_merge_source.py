from pathlib import Path
import hashlib
import importlib.util
import json
import re
import shutil
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True
REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'desktop/tools'))
import v046_dynamic_category_merge as merge

TOOLS = REPO / 'desktop/tools'
MAIN = REPO / 'desktop/src/main/kotlin/com/bilipai/desktop'
TESTS = REPO / 'desktop/src/test/kotlin/com/bilipai/desktop'
UP_TAB_NAMES = ('isDynamicUserTabVisible', 'DynamicUserContentFilter', 'resolveDynamicTabAfterUserSelection',
                'resolveDynamicSelectedUserForTab', 'shouldAutoLoadMoreForUserContentFilter', 'withUserContentFilter',
                'shouldIncludeDynamicItemInUpTab')


def load(name, relative):
    spec = importlib.util.spec_from_file_location(name, REPO / relative)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def sha(text):
    return hashlib.sha256(text.encode('utf8')).hexdigest()


def text_of(path):
    return Path(path).read_bytes().decode('utf8')


class V046DynamicCategoryMergeSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tabs = load('v046_merge_tabs', 'desktop/tools/extract-upstream-dynamic-tabs.py')
        cls.settings = load('v046_merge_settings', 'desktop/tools/extract-upstream-dynamic-settings.py')
        host = cls.tabs.module(REPO, 'v046_merge_host', 'desktop/tools/extract-upstream-plugins.py')
        cls.media = host.media_extractor(REPO)
        cls.parser = cls.media.parser_for(REPO)
        cls.syntax = merge.parser(REPO)
        cls.directory = tempfile.TemporaryDirectory(prefix='bp-dcm-')
        root = Path(cls.directory.name)
        cls.tabs_out = root / 't'
        cls.tabs.generate(REPO, cls.tabs_out)
        cls.tabs_tests = root / 'tt'
        cls.tabs.generate_category_merge_tests(REPO, cls.tabs_tests)
        cls.settings_out = root / 's'
        cls.settings.generate(REPO, cls.settings_out)
        cls.sources, cls.raws, cls.manifest = merge.fixed(REPO)
        cls.befores = {p: cls.tabs.read_selected_before_category_merge(REPO, p) for p in merge.SELECTED_PATHS}
        cls.receipt = merge.receipt(REPO, cls.befores)

    @classmethod
    def tearDownClass(cls):
        cls.directory.cleanup()

    def generated(self, root, name):
        matches = list(Path(root).rglob(name))
        self.assertEqual(1, len(matches), name)
        return matches[0].read_text(encoding='utf-8')

    def body_of(self, root, name):
        return self.generated(root, name).split('\n', 2)[2]

    # --- archive and predecessor pins -------------------------------------------------------------

    def test_every_archive_leaf_manifest_license_and_extra_file_is_pinned(self):
        for leaf in sorted(merge.EXPECTED) + ['manifest.json', 'LICENSE']:
            for mode in ('append', 'delete'):
                with self.subTest(leaf=leaf, mode=mode), tempfile.TemporaryDirectory(prefix='bp-dcm-a-') as tmp:
                    repo = Path(tmp)
                    shutil.copytree(REPO / merge.ARCHIVE, repo / merge.ARCHIVE)
                    target = repo / merge.ARCHIVE / leaf
                    if mode == 'append':
                        target.write_bytes(target.read_bytes() + b'\n')
                    else:
                        target.unlink()
                    with self.assertRaises(ValueError):
                        merge.fixed(repo)
        with tempfile.TemporaryDirectory(prefix='bp-dcm-a-') as tmp:
            repo = Path(tmp)
            shutil.copytree(REPO / merge.ARCHIVE, repo / merge.ARCHIVE)
            (repo / merge.ARCHIVE / 'Extra.kt').write_bytes(b'package x\n')
            with self.assertRaises(ValueError):
                merge.fixed(repo)

    def test_the_pinned_settings_manager_of_the_comment_list_archive_cannot_drift(self):
        text = merge.settings_manager_source(REPO)
        self.assertEqual(merge.SETTINGS_SHA256, sha(text))
        self.assertEqual(merge.SETTINGS_SHA256, self.manifest['externalInputs']['SettingsManager.kt']['sha256'])
        with tempfile.TemporaryDirectory(prefix='bp-dcm-b-') as tmp:
            repo = Path(tmp)
            leaf = repo / merge.SETTINGS_LEAF
            leaf.parent.mkdir(parents=True)
            shutil.copyfile(REPO / merge.SETTINGS_LEAF, leaf)
            self.assertEqual(text, merge.settings_manager_source(repo))
            leaf.write_bytes(leaf.read_bytes() + b'\n')
            with self.assertRaises(ValueError):
                merge.settings_manager_source(repo)
            leaf.unlink()
            with self.assertRaises(ValueError):
                merge.settings_manager_source(repo)

    def test_predecessor_pins_agree_between_generator_and_manifest_and_every_layer_rejects_drift(self):
        self.assertEqual({p: v['sha256'] for p, v in self.manifest['beforeFinalOutputs'].items()}, merge.BEFORE)
        for path in merge.SELECTED_PATHS:
            with self.subTest(path=path):
                self.assertEqual(merge.BEFORE[path], sha(self.befores[path]))
                with self.assertRaises(ValueError):
                    merge.advance_selected(REPO, path, self.befores[path] + '\n// drift\n')
        canonical = self.settings.read(REPO, self.settings.BASE + 'feature/dynamic/DynamicTabPolicy.kt')
        self.assertEqual(merge.BEFORE[merge.PATHS['DynamicTabPolicy.kt']], sha(canonical))
        with self.assertRaises(ValueError):
            merge.tab_policy_source(REPO, canonical + '\n')
        self.assertEqual('body', merge.advance_selected(REPO, 'app/src/main/java/com/android/purebilibili/feature/other/Other.kt', 'body'))
        with self.assertRaises(ValueError):
            merge.receipt(REPO, {})

    # --- layers -----------------------------------------------------------------------------------

    def test_whole_file_layers_return_the_fixed_v046_bytes_and_receipts_name_both_digests(self):
        cases = {
            merge.PATHS['DynamicScreenStatePolicy.kt']: self.sources['DynamicScreenStatePolicy.kt'],
            merge.PATHS['DynamicUserRequestPolicy.kt']: self.sources['DynamicUserRequestPolicy.kt'],
            merge.SETTINGS_PATH: merge.settings_manager_source(REPO),
        }
        for path, expected in cases.items():
            with self.subTest(path=path):
                self.assertEqual(expected, merge.advance_selected(REPO, path, self.befores[path]))
                layer = self.receipt['layers'][path]
                self.assertTrue(layer['wholeFileReplacement'])
                self.assertEqual(sha(self.befores[path]), layer['beforeSHA256'])
                self.assertEqual(sha(expected), layer['afterSHA256'])
                self.assertFalse(layer['jvmTestsExecuted'])
        self.assertFalse(self.receipt['mainCompiled'])
        self.assertEqual(set(merge.SELECTED_PATHS), set(self.receipt['layers']))

    def test_state_policy_layer_changes_exactly_the_documented_declarations(self):
        before = self.befores[merge.PATHS['DynamicScreenStatePolicy.kt']]
        after = self.sources['DynamicScreenStatePolicy.kt']
        for name in merge.STATE_UNCHANGED:
            self.assertEqual(merge.declaration(before, 'fun', name, self.syntax),
                             merge.declaration(after, 'fun', name, self.syntax), name)
        for name in merge.STATE_CHANGED:
            self.assertNotEqual(merge.declaration(before, 'fun', name, self.syntax),
                                merge.declaration(after, 'fun', name, self.syntax), name)
        for name in merge.STATE_REMOVED:
            self.assertIn('fun ' + name + '(', before)
            self.assertNotIn(name, after)
        for name in merge.STATE_ADDED:
            self.assertNotIn(name, before)
            self.assertIn('fun ' + name + '(', after)
        # The cold-start pagination decision of the v040 layer survives unchanged inside the whole v0.4.6 file.
        v040 = (REPO / 'desktop/upstream-slices/v040-dynamic-pagination/DynamicScreenStatePolicy.kt').read_text(encoding='utf-8')
        self.assertEqual(self.media.function(v040, 'shouldLoadMoreDynamicFeed', self.parser),
                         self.media.function(after, 'shouldLoadMoreDynamicFeed', self.parser))
        # normalizeDynamicNotInterestedIds is an expression body: its span selector is the appearance one.
        appearance = self.tabs.module(REPO, 'v046_merge_decl', 'desktop/tools/extract-appearance-platform.py')
        self.assertEqual(appearance.declarations(self.parser, before, ['normalizeDynamicNotInterestedIds']).strip(),
                         appearance.declarations(self.parser, after, ['normalizeDynamicNotInterestedIds']).strip())

    def test_strip_layer_replaces_two_declarations_with_one_pinned_binding_and_inverts_exactly(self):
        path = merge.PATHS['DynamicScreen.kt']
        before = self.befores[path]
        after = merge.advance_selected(REPO, path, before)
        layer = self.receipt['layers'][path]
        self.assertEqual(before, merge.restore(after, layer['retainedToCurrent']))
        self.assertEqual(list(merge.STRIP_FUNCTIONS), [item['name'] for item in layer['replaced']])
        for item in layer['replaced']:
            name = item['name']
            original = merge.declaration(self.sources['DynamicScreen.kt'], 'fun', name, self.syntax)
            windows = merge.declaration(after, 'fun', name, self.syntax)
            if name == 'HorizontalUserList':
                self.assertEqual(1, original.count(merge.SELF_AVATAR_BEFORE))
                self.assertEqual(original.replace(merge.SELF_AVATAR_BEFORE, merge.SELF_AVATAR_AFTER, 1), windows)
                self.assertEqual(original, merge.restore(windows, item['originalToWindows']))
            else:
                self.assertEqual(original, windows)
        remainder = [before, after]
        for index, text in enumerate(remainder):
            for name in merge.STRIP_FUNCTIONS:
                first, last = merge.span(text, 'fun', name, self.syntax)
                text = text[:first] + text[last:]
            remainder[index] = text
        self.assertEqual(remainder[0], remainder[1], 'Nothing outside the two strip declarations changed')
        self.assertNotIn('DynamicUserContentFilter', merge.declaration(after, 'fun', 'DynamicSelectedUserFeedHeader', self.syntax))

    def test_settings_sections_layer_replaces_only_the_tab_visibility_item(self):
        path = merge.PATHS['SettingsSections.kt']
        before = self.befores[path]
        after = merge.advance_selected(REPO, path, before)
        layer = self.receipt['layers'][path]
        self.assertEqual(before, merge.restore(after, layer['retainedToCurrent']))
        self.assertEqual([merge.TAB_ITEM_FUNCTION], [item['name'] for item in layer['replaced']])
        original = merge.declaration(self.sources['SettingsSections.kt'], 'fun', merge.TAB_ITEM_FUNCTION, self.syntax)
        self.assertEqual(original, merge.declaration(after, 'fun', merge.TAB_ITEM_FUNCTION, self.syntax))
        self.assertNotEqual(original, merge.declaration(before, 'fun', merge.TAB_ITEM_FUNCTION, self.syntax))
        self.assertIn('点关注用户可在“全部”中查看其动态', after)
        self.assertNotIn('隐藏 UP 后', after)

    # --- the real producers ------------------------------------------------------------------------

    def test_tabs_producer_emits_the_merged_policies_strip_and_settings_without_the_retired_names(self):
        every = '\n'.join(p.read_text(encoding='utf-8') for p in sorted(self.tabs_out.rglob('*.kt')))
        for name in UP_TAB_NAMES + ('KEY_DYNAMIC_ALL_TAB_HORIZONTAL_USER_LIST_VISIBLE', 'getDynamicAllTabHorizontalUserListVisible',
                                    'dynamicAllTabHorizontalUserListVisible', 'allTabHorizontalUserListVisible'):
            self.assertNotIn(name, every, name)
        state = self.generated(self.tabs_out, 'DesktopOriginalDynamicUserStatePolicy.kt')
        appearance = self.tabs.module(REPO, 'v046_merge_emitted_decl', 'desktop/tools/extract-appearance-platform.py')
        names = re.findall(r'(?m)^internal fun (\w+)\(', state)
        self.assertEqual(16, len(names))
        for name in names:
            if name == 'normalizeDynamicNotInterestedIds':
                self.assertEqual(appearance.declarations(self.parser, self.sources['DynamicScreenStatePolicy.kt'], [name]).strip(),
                                 appearance.declarations(self.parser, state, [name]).strip())
            else:
                self.assertEqual(self.media.function(self.sources['DynamicScreenStatePolicy.kt'], name, self.parser),
                                 self.media.function(state, name, self.parser), name)
        self.assertIn('shouldShowDynamicUpPanel', names)
        self.assertIn('name = "全部动态"', state)
        self.assertEqual(self.sources['DynamicUserRequestPolicy.kt'].strip() + '\n',
                         self.body_of(self.tabs_out, 'DesktopOriginalDynamicUserRequestPolicy.kt'))

    def test_tab_settings_object_keeps_four_original_members_and_the_fixed_defaults(self):
        original = merge.settings_manager_source(REPO)
        actual = self.generated(self.tabs_out, 'DesktopDynamicTabsSettings.kt')
        for name in ('getDynamicTabVisibleTabs', 'setDynamicTabVisibleTabs', 'getDynamicTabOrder', 'setDynamicTabOrder'):
            self.assertEqual(self.media.function(original, name, self.parser), self.media.function(actual, name, self.parser), name)
        self.assertIn('private const val DEFAULT_DYNAMIC_TAB_VISIBLE = "all,video,pgc,article"', actual)
        self.assertIn('private const val DEFAULT_DYNAMIC_TAB_ORDER = "all,video,pgc,article"', actual)
        self.assertIn('.map { if (it == "up") "all" else it }.toSet()', actual)
        self.assertIn('.map { if (it == "up") "all" else it }.distinct()', actual)
        self.assertEqual(4, len(re.findall(r'(?m)^    (?:suspend )?fun ', actual)))
        self.assertNotIn('booleanPreferencesKey', actual)
        self.assertEqual(sha(original), re.search(r'LF-normalized SHA-256: (\w+)', actual).group(1))

    def test_tab_fields_have_no_strip_switch_and_carry_the_whole_v046_item(self):
        actual = self.generated(self.tabs_out, 'DesktopOriginalDynamicTabsFields.kt')
        self.assertEqual(self.media.function(self.sources['SettingsSections.kt'], merge.TAB_ITEM_FUNCTION, self.parser),
                         self.media.function(actual, merge.TAB_ITEM_FUNCTION, self.parser))
        signature = actual[actual.index('internal fun DesktopDynamicTabsSettingsFields('):actual.index(') {')]
        self.assertEqual(['dynamicVisibleTabIds', 'onDynamicTabVisibilityChange', 'dynamicTabOrder', 'onDynamicTabOrderChange'],
                         re.findall(r'(\w+):', signature))
        self.assertNotIn('显示关注用户栏', actual)
        self.assertEqual(1, actual.count('FeedDynamicTabVisibilityItem(\n    icon = visibilityIcon,'))

    def test_strip_controls_are_the_v046_declarations_with_the_windows_self_avatar(self):
        actual = self.generated(self.tabs_out, 'DesktopOriginalDynamicUserControls.kt')
        strip = self.media.function(actual, 'HorizontalUserList', self.parser)
        original = self.media.function(self.sources['DynamicScreen.kt'], 'HorizontalUserList', self.parser)
        expected = original.replace('private fun', 'internal fun', 1).replace(merge.SELF_AVATAR_BEFORE, merge.SELF_AVATAR_AFTER, 1)
        self.assertEqual(expected, strip)
        header = self.media.function(actual, 'DynamicSelectedUserFeedHeader', self.parser)
        self.assertEqual(self.media.function(self.sources['DynamicScreen.kt'], 'DynamicSelectedUserFeedHeader', self.parser)
                         .replace('private fun', 'internal fun', 1), header)
        self.assertEqual(1, actual.count('DynamicSelfAvatar('))
        self.assertEqual(1, actual.count('com.bilipai.desktop.ui.DesktopDynamicSelfAvatar('))
        self.assertIn('isDynamicUpPanelAllShortcut(user.uid)', strip)
        self.assertIn('rememberAppDynamicIcon()', strip)
        # The Windows composable the one binding names exists exactly once, with the upstream contract.
        avatar = text_of(MAIN / 'ui/DesktopDynamicSelfAvatar.kt')
        self.assertEqual(1, avatar.count('internal fun DesktopDynamicSelfAvatar(face: String, isSelected: Boolean)'))
        self.assertNotIn('internal fun DesktopDynamicSelfAvatar(', (MAIN / 'ui/DesktopDynamicTabsHost.kt').read_text(encoding='utf8'))

    def test_producer_output_is_deterministic_and_the_receipt_is_written_next_to_it(self):
        with tempfile.TemporaryDirectory(prefix='bp-dcm-c-') as tmp:
            again = Path(tmp)
            self.tabs.generate(REPO, again)
            left = {p.relative_to(self.tabs_out).as_posix(): p.read_bytes() for p in self.tabs_out.rglob('*') if p.is_file()}
            right = {p.relative_to(again).as_posix(): p.read_bytes() for p in again.rglob('*') if p.is_file()}
            self.assertEqual(left, right)
        proof = json.loads((self.tabs_out / 'v046-dynamic-category-merge-proof.json').read_text(encoding='utf8'))
        self.assertEqual(set(merge.SELECTED_PATHS), set(proof['layers']))
        self.assertEqual(merge.COMMIT, proof['upstreamCommit'])
        self.assertEqual(merge.MANIFEST_SHA256, proof['sourceManifestSHA256'])
        self.assertFalse(proof['runtimeExecuted'])

    def test_dynamic_tab_policy_is_emitted_once_verbatim_by_the_settings_producer(self):
        emitted = list(self.settings_out.rglob('DynamicTabPolicy.kt'))
        self.assertEqual(1, len(emitted))
        self.assertEqual(self.raws['DynamicTabPolicy.kt'], emitted[0].read_bytes())
        self.assertEqual([], list(self.tabs_out.rglob('DynamicTabPolicy.kt')))
        self.assertEqual('com/android/purebilibili/feature/dynamic/DynamicTabPolicy.kt',
                         emitted[0].relative_to(self.settings_out).as_posix())
        self.assertNotIn('"up"', emitted[0].read_text(encoding='utf8'))
        proof = json.loads((self.settings_out / 'v046-dynamic-tab-policy-proof.json').read_text(encoding='utf8'))
        self.assertTrue(proof['wholeFileReplacement'])
        self.assertEqual(sha(self.raws['DynamicTabPolicy.kt'].decode('utf8')), proof['afterSHA256'])
        # The vendored path stays a direct source in the inventory; only its compile copy is excluded.
        self.assertIn('feature/dynamic/DynamicTabPolicy.kt', self.settings.SLICE_OWNED)
        self.assertTrue(set(self.settings.SLICE_OWNED) <= set(self.settings.DIRECT))
        rows = {row['path']: row for row in self.settings.inventory(REPO)}
        self.assertEqual('direct', rows[self.settings.BASE + 'feature/dynamic/DynamicTabPolicy.kt']['mode'])
        sources = json.loads(text_of(REPO / 'desktop/upstream-sources.json'))['sources']
        row = next(s for s in sources if s['path'] == merge.PATHS['DynamicTabPolicy.kt'])
        self.assertEqual('direct', row.get('mode', 'direct'))
        with tempfile.TemporaryDirectory(prefix='bp-dcm-d-') as tmp:
            standalone = Path(tmp)
            self.settings.generate(REPO, standalone, standalone=True)
            self.assertEqual(self.raws['DynamicTabPolicy.kt'], next(standalone.rglob('DynamicTabPolicy.kt')).read_bytes())
            self.assertEqual(14, len(list(standalone.rglob('*.kt'))))

    def test_original_policy_tests_are_emitted_in_the_test_root_only(self):
        emitted = sorted(self.tabs_tests.rglob('*.kt'))
        self.assertEqual(['DynamicTabPolicyTest.kt', 'DynamicUserRequestPolicyTest.kt'], [p.name for p in emitted])
        for name, count in merge.TEST_COUNTS.items():
            body = self.body_of(self.tabs_tests, name)
            self.assertEqual(self.sources[name].strip() + '\n', body)
            self.assertEqual(count, body.count('@Test'))
            self.assertEqual(1, body.count('class ' + name[:-3]))
        self.assertEqual([], list(self.tabs_out.rglob('*Test.kt')))
        self.assertEqual({merge.PATHS[name] for name in merge.TEST_COUNTS}, set(merge.original_tests(REPO)))

    def test_the_reaction_sheet_is_the_whole_v046_file_through_the_existing_adaptation(self):
        reaction = load('v046_merge_reaction', 'desktop/tools/v034_dynamic_reaction_ui.py')
        body = reaction.sheet_body(merge.reaction_sheet_source(REPO))
        for role in ('AppContentColors.secondaryText()', 'AppContentColors.error()'):
            self.assertIn(role, body)
        for retired in ('MaterialTheme.colorScheme.error', 'MaterialTheme.colorScheme.onSurfaceVariant'):
            self.assertNotIn(retired, body)
        identity = merge.reaction_sheet_identity(REPO)
        row = next(r for r in self.manifest['files'] if r['filename'] == 'DynamicReactionSheet.kt')
        self.assertEqual(('v0.4.6', row['gitBlob'], row['sha256']),
                         (identity['pinnedTag'], identity['pinnedGitBlob'], identity['sha256LfUtf8']))
        editor_source = text_of(TOOLS / 'extract-upstream-dynamic-editor.py')
        self.assertIn("p=COMP+'DynamicReactionSheet.kt';s=reaction_sheet_v046_source(repo)", editor_source)
        self.assertNotIn("reaction_ui_source(repo,'DynamicReactionSheet.kt')", editor_source)
        self.assertIn("reaction_ui_source(repo,'DynamicCommentSheet.kt')", editor_source)
        editor = load('v046_merge_editor', 'desktop/tools/extract-upstream-dynamic-editor.py')
        with tempfile.TemporaryDirectory(prefix='bp-dcm-e-') as tmp:
            output = Path(tmp)
            editor.generate(REPO, output)
            actual = next(output.rglob('DesktopOriginalDynamicReactionSheet.kt')).read_text(encoding='utf8')
            self.assertEqual(body.strip() + '\n', actual.split('\n', 2)[2])
            bare = re.compile(r'(?<![\w.])DynamicReactionSheet\(')
            self.assertEqual([], [f.name for f in sorted(output.rglob('*.kt')) if bare.search(f.read_text(encoding='utf8'))])
            identities = json.loads((output / 'source-identity.json').read_text(encoding='utf8'))
            sheets = [i for i in identities if i['path'].endswith('DynamicReactionSheet.kt')]
            self.assertEqual([('v0.4.6', merge.COMMIT)], [(i['pinnedTag'], i['pinnedCommit']) for i in sheets])
            comments = [i for i in identities if i['path'].endswith('DynamicCommentSheet.kt')]
            self.assertEqual({'v0.2.5', 'v0.3.4-beta.2'}, {i['pinnedTag'] for i in comments})

    # --- compile universe --------------------------------------------------------------------------

    def test_every_top_level_name_of_the_merged_policies_has_one_owner(self):
        owned = ['DynamicTabPolicy.kt']
        pattern = re.compile(r'(?m)^(?:internal |public )?(?:data class|enum class|const val|val|fun|class|object) (?:<[^>]*> )?(\w+)')
        emitted = {}
        for root in (self.tabs_out, self.settings_out):
            for path in sorted(root.rglob('*.kt')):
                for name in pattern.findall(path.read_text(encoding='utf8')):
                    emitted.setdefault(name, []).append(path.name)
        merged_names = set()
        for path in sorted(self.tabs_out.rglob('DesktopOriginalDynamic*Polic*.kt')) + list(self.settings_out.rglob('DynamicTabPolicy.kt')):
            merged_names.update(pattern.findall(path.read_text(encoding='utf8')))
        for name in sorted(merged_names):
            self.assertEqual(1, len(emitted[name]), (name, emitted[name]))
        windows = '\n'.join(p.read_text(encoding='utf8') for p in sorted(MAIN.rglob('*.kt')))
        for name in merged_names:
            self.assertEqual(0, len(re.findall(r'(?m)^(?:internal |private |public )?(?:fun|val|class|object) (?:<[^>]*> )?' + re.escape(name) + r'\b', windows)), name)
        build = text_of(REPO / 'desktop/build.gradle.kts')
        path = 'app/src/main/java/com/android/purebilibili/feature/dynamic/' + owned[0]
        self.assertEqual(1, build.count('exclude(canonicalOriginalIdentity("' + path + '"))'))

    def test_retired_names_have_no_owner_left_in_windows_sources_or_tests(self):
        for root in (MAIN, TESTS):
            for path in sorted(root.rglob('*.kt')):
                text = path.read_text(encoding='utf8')
                for name in UP_TAB_NAMES + ('setAllTabUsers', 'initialAllTabUsers', 'allTabUsers'):
                    self.assertNotIn(name, text, (path.name, name))

    def test_the_symbol_gate_lists_the_two_adapted_imports_and_no_longer_the_closed_sheet_gap(self):
        expected = json.loads(text_of(TOOLS / 'symbol-closure-expected-gaps.json'))
        listed = {gap['symbol']: group['closedBy'] for group in expected['groups'] for gap in group['gaps']}
        # The whole v0.4.6 leaf declares the sheet the carried comment archives call, so that roadmap gap closed.
        self.assertIn('fun DynamicReactionSheet(', self.sources['DynamicReactionSheet.kt'])
        self.assertNotIn('com.android.purebilibili.feature.dynamic.components.DynamicReactionSheet', listed)
        # Gate-model closure only: the compiled universe emits the sheet as DesktopOriginalDynamicReactionSheet (the editor
        # test above proves no emitted file declares or calls the bare name) while the carried v041 comment-sheet archive,
        # which is not compiled, still calls it. Compiling the v0.4.6 DynamicCommentSheet later must rewrite that call
        # to DesktopOriginalDynamicReactionSheet.
        self.assertFalse(any(group['closedBy'] == 'dyn-category-merge-and-detail-return' for group in expected['groups']))
        # Two verbatim imports are replaced by their producers on purpose and stay accepted, explained gaps.
        for symbol in ('com.android.purebilibili.core.network.grpc.DynamicReactionApi',
                       'com.android.purebilibili.feature.dynamic.components.DynamicSelfAvatar'):
            self.assertEqual('none (producer adaptation)', listed[symbol], symbol)
        self.assertIn('import com.android.purebilibili.core.network.grpc.DynamicReactionApi', self.sources['DynamicReactionSheet.kt'])
        self.assertIn('import com.android.purebilibili.feature.dynamic.components.DynamicSelfAvatar', self.sources['DynamicScreen.kt'])

    def test_gradle_wiring_and_inventory_rows(self):
        build = text_of(REPO / 'desktop/build.gradle.kts')
        for task in ('extractUpstreamDynamicSettings', 'extractUpstreamDynamicTabs', 'extractUpstreamDynamicEditor'):
            block = build.split('val ' + task + ' by tasks.registering', 1)[1].split('\nval ', 1)[0]
            self.assertEqual(1, block.count('"tools/v046_dynamic_category_merge.py"'), task)
            self.assertEqual(1, block.count('inputs.dir("upstream-slices/v046-dynamic-category-merge")'), task)
        tabs = build.split('val extractUpstreamDynamicTabs by tasks.registering', 1)[1].split('\nval ', 1)[0]
        self.assertEqual(1, tabs.count('inputs.file("upstream-slices/v046-comment-list/SettingsManager.kt")'))
        sync = load('v046_merge_sync', 'desktop/tools/sync-upstream.py')
        rows = [r for r in sync.adapted_compatibility_inputs(REPO) if r['localPath'].startswith(merge.ARCHIVE + '/')]
        self.assertEqual(len(merge.EXPECTED), len(rows))
        self.assertTrue(all(r['upstreamCommit'] == merge.COMMIT and not r['referenceOnly'] for r in rows))
        self.assertEqual({row['path'] for row in self.manifest['files']}, {r['path'] for r in rows})
        catalog = json.loads(text_of(TOOLS / 'compatibility-source-inputs.json'))
        self.assertIn(merge.ARCHIVE, catalog['buildInputRoots'])
        # The editor producer reads the whole v0.4.6 sheet from the archive and no longer reads or verifies the
        # beta2 sheet; its inventory row says so (the file itself stays a task input and is not edited). The beta2
        # comment sheet is still read by the producer and keeps its compiled row.
        by_local = {row['localPath']: row for row in catalog['inputs']}
        sheet = by_local['desktop/third-party/dynamic-reaction-v034/DynamicReactionSheet.kt']
        self.assertTrue(sheet['referenceOnly'])
        self.assertEqual('b2091b1de636e499df80af7e700796b4f52b7f92', sheet['upstreamCommit'])
        self.assertFalse(by_local['desktop/third-party/dynamic-reaction-v034/DynamicCommentSheet.kt']['referenceOnly'])
        self.assertEqual(1, len([row for row in catalog['inputs'] if row['localPath'].endswith('/DynamicReactionSheet.kt')
                                 and row['upstreamCommit'] == merge.COMMIT]))

    # --- Windows owners -----------------------------------------------------------------------------

    def test_windows_owners_use_the_merged_category_model(self):
        state = text_of(MAIN / 'ui/DesktopDynamicUsersState.kt')
        host = text_of(MAIN / 'ui/DesktopDynamicTabsHost.kt')
        prefs = text_of(MAIN / 'settings/DesktopDynamicTabsPreferences.kt')
        timeline = text_of(MAIN / 'ui/DesktopDynamicTimeline.kt')
        for needle in ('private var authorFeedTabEnabled = false', 'private fun authorTargetTab()=if(shouldShowDynamicUpPanel(selectedLogicalTab))selectedLogicalTab else 0',
                       'fun selectUser(uid:Long?) {', 'fun visibleItems(timelineItems:List<DynamicItem> = dynamics)',
                       'if(shouldShowDynamicUpPanel(selectedLogicalTab))requestCompleteFollowingsLoad()',
                       'if(selectedUid==authorMid)clearSelection()'):
            self.assertEqual(1, state.count(needle), needle)
        for retired in ('var filter', 'authorSearchTabEnabled', 'onOpenUser', '"up"', 'selectedLogicalTab==4', 'next!=4'):
            self.assertNotIn(retired, state, retired)
        self.assertEqual(1, state.count('fun selectAuthorSearchResult('))
        self.assertEqual(1, host.count('val selectedAuthorFeed=shouldUseSelectedUserDynamicFeed(users.selectedLogicalTab,users.selectedUid)'))
        self.assertEqual(1, host.count('shouldShowDynamicHorizontalUserList(true,users.selectedLogicalTab)'))
        self.assertEqual(1, host.count('onUserClick=users::selectUser'))
        self.assertEqual(1, host.count('state.visibleItems(categoryTimeline.page.items)'))
        for retired in ('allTabUsers', 'showAllUsers', 'state.filter', 'selectedLogicalTab==4', 'shouldAutoLoadMoreForUserContentFilter'):
            self.assertNotIn(retired, host, retired)
        self.assertEqual(3, prefs.count('allDynamicTabSpecs.size'))
        self.assertNotIn(',5)', prefs)
        self.assertEqual(1, timeline.count('val allowAutomaticLoadMore=true'))
        self.assertEqual(1, timeline.count('val emptyState=resolveDesktopDynamicEmptyState('))
        self.assertNotIn('displayed.isEmpty()&&state.error==null', timeline)
        # The selected-author feed pages automatically and the category filters its rows, so a page without a row is
        # normal: its slot is the loading variant while hasUserMore and the empty text only exists once nothing is left
        # to request (v0.4.6 DynamicScreen). The manual button never shares the slot with the loading variant.
        self.assertEqual(1, host.count('val emptyState=resolveDesktopDynamicAuthorEmptyState('
                                       'loading=state.userLoading,failed=state.userError!=null,'))
        self.assertIn('hasMore=state.hasUserMore,hasDisplayedItems=rows.isNotEmpty())', host)
        self.assertEqual(1, host.count('if(state.userLoading||emptyState==DesktopDynamicEmptyState.LOADING)item('))
        self.assertEqual(1, host.count('if(emptyState==DesktopDynamicEmptyState.LOADING)item('))
        self.assertEqual(1, host.count('if(emptyState==DesktopDynamicEmptyState.EMPTY)item('))
        self.assertEqual(1, host.count('AppText("正在加载该 UP 的动态",'))
        self.assertEqual(1, host.count('AppText("该 UP 暂无动态")'))
        self.assertEqual(1, host.count('AppText("仅显示当前分类的内容",'))
        self.assertEqual(1, host.count('if(!state.userLoading&&state.hasUserMore&&state.userError==null'
                                       '&&emptyState!=DesktopDynamicEmptyState.LOADING)item('
                                       'span=StaggeredGridItemSpan.FullLine){AppPrimaryButton(text="加载更多",onClick=state::loadMoreUser)}'))
        self.assertNotIn('AppText("暂无内容")', host)
        self.assertNotIn('!state.userLoading&&rows.isEmpty()&&state.userError==null', host)
        policy = text_of(MAIN / 'ui/DesktopDynamicEmptyState.kt')
        author = policy[policy.index('internal fun resolveDesktopDynamicAuthorEmptyState('):]
        self.assertIn('hasDisplayedItems || failed || loading -> DesktopDynamicEmptyState.NONE', author)
        self.assertIn('hasMore -> DesktopDynamicEmptyState.LOADING', author)
        self.assertLess(author.index('hasMore -> DesktopDynamicEmptyState.LOADING'), author.index('else -> DesktopDynamicEmptyState.EMPTY'))

    def test_cold_start_empty_state_waits_for_the_first_settled_fetch(self):
        policy = text_of(MAIN / 'ui/DesktopDynamicEmptyState.kt')
        self.assertIn('hasDisplayedItems || failed || busy -> DesktopDynamicEmptyState.NONE', policy)
        self.assertIn('!firstFetchSettled -> DesktopDynamicEmptyState.LOADING', policy)
        timeline = text_of(MAIN / 'ui/DesktopDynamicTimeline.kt')
        self.assertIn('state.busy||emptyState==DesktopDynamicEmptyState.LOADING', timeline)
        # The only reader for the verdict is the policy call; the text slot is gated on its result.
        self.assertIn('emptyState==DesktopDynamicEmptyState.EMPTY||emptyState==DesktopDynamicEmptyState.FILTERED', timeline)
        self.assertIn('firstFetchSettled=state.firstFetchSettled', timeline)
        self.assertNotIn('initialized=state.initialized', timeline)

    def test_the_first_fetch_settled_flag_covers_every_verdict_but_not_a_cancellation(self):
        timeline = text_of(MAIN / 'ui/DesktopDynamicTimeline.kt')
        self.assertEqual(1, timeline.count('var firstFetchSettled by mutableStateOf(false);private set'))
        # Set exactly twice, inside the two publications that carry a verdict: the response (accepted or discarded by the
        # follow-state guard, which leaves `initialized` false) and the failure. The manual load-more button keeps `initialized`.
        self.assertEqual(2, timeline.count('firstFetchSettled=true'))
        fetch = timeline[timeline.index('private suspend fun fetchLocked('):timeline.index('@Composable\ninternal fun DesktopDynamicTimelineFeed(')]
        response = fetch[fetch.index('publish(caller) {\n                firstFetchSettled=true'):fetch.index('return succeeded')]
        self.assertLess(response.index('firstFetchSettled=true'), response.index('if(followRevision!=followStateRevision)'))
        self.assertLess(response.index('initialized=true'), response.index('succeeded=true'))
        self.assertEqual(1, fetch.count('publish(caller){error=failure;firstFetchSettled=true;'))
        cancelled = fetch[fetch.index('catch(cancelled:CancellationException){'):fetch.index('catch(failure:Exception){')]
        self.assertNotIn('firstFetchSettled', cancelled)
        self.assertNotIn('firstFetchSettled', fetch[fetch.index('}finally{'):])
        self.assertIn('if(!state.busy&&state.initialized&&state.page.hasMore&&state.error==null)item(', timeline)

    def test_share_catalog_drops_the_retired_key_and_stays_uniformly_crlf(self):
        raw = (MAIN / 'settings/DesktopSettingsShareWindowsCatalog.kt').read_bytes()
        self.assertEqual(raw.count(b'\n'), raw.count(b'\r\n'))
        catalog = raw.decode('utf8')
        self.assertNotIn('"dynamic_all_tab_horizontal_user_list_visible"', catalog)
        self.assertEqual(1, catalog.count('dynamic_all_tab_horizontal_user_list_visible left the Android share definitions in v0.4.5'))
        for key in ('dynamic_top_bar_collapse_on_scroll', 'dynamic_comment_preview_visible'):
            self.assertEqual(1, catalog.count('"' + key + '"'), key)

    # --- audits of the roadmap entries that need no new code -----------------------------------------

    def test_audit_top_tabs_scroll_is_covered_by_the_native_scrollable_tab_row(self):
        native = self.generated(self.tabs_out, 'DesktopOriginalDynamicNativeTabs.kt')
        self.assertIn('(equalizeMiuixNonGlassItems || scrollable || options.size > 3 ||', native)
        host = text_of(MAIN / 'ui/DesktopDynamicTabsHost.kt')
        row = host[host.index('Row(Modifier.fillMaxWidth().padding(horizontal=20.dp)) {'):host.index('trailing()')]
        self.assertIn('AppNativeTabRow(', row)
        self.assertIn('modifier=Modifier.weight(1f)', row)
        # The tab row owns the width that the search button and the trailing action leave: it cannot overlap them.
        self.assertLess(row.index('AppNativeTabRow('), row.index('AppIconButton('))
        # Four categories still exceed the three-option threshold, so the row keeps scrolling after the merge.
        self.assertGreater(len(re.findall(r'DynamicTabSpec\(', self.sources['DynamicTabPolicy.kt'])) - 1, 3)

    def test_audit_detail_return_records_the_retained_state_and_the_single_creation_sites_of_the_rest(self):
        """AUDITED ONLY: roadmap entry v045-dynamic-detail-return-retention is not implemented by this slice.

        Retained across navigation today: the detail route and every category timeline with its scroll state. Not yet
        retained: the selected author, the strip rows and the author rows (DesktopDynamicUsersState) and the detail
        comments. The follow-up slice keeps those two owners in LocalDesktopBrowseMemory like the timelines; this test
        therefore pins only what stays true after it (the retained facts and one creation site per owner) and does not
        pin how the two owners are currently kept.
        """
        screens = text_of(MAIN / 'ui/CommunityScreens.kt')
        dynamic = text_of(MAIN / 'ui/CommunityDynamicScreens.kt')
        self.assertIn('browseMemory.screen(listOf("community-dynamic-detail", section, userId, query, initialRoute))', screens)
        self.assertIn('memory?.screen(listOf("dynamic-settings-timeline",mountedRoot,mid,capturedEpoch,type),create)', dynamic)
        self.assertIn('val scroll=LazyStaggeredGridState()', text_of(MAIN / 'ui/DesktopDynamicTimeline.kt'))
        # One creation site per owner and one call site of the detail screen: the follow-up changes one place each.
        self.assertEqual(1, dynamic.count('DesktopDynamicUsersState('))
        self.assertEqual(1, dynamic.count('mutableStateOf<DynamicDetailData?>(null)'))
        self.assertEqual(1, screens.count('CommunityDynamicDetail(detail, community, navigation)'))

    # --- hygiene --------------------------------------------------------------------------------------

    def test_changed_kotlin_files_have_balanced_delimiters(self):
        files = [MAIN / 'ui/DesktopDynamicUsersState.kt', MAIN / 'ui/DesktopDynamicTabsHost.kt',
                 MAIN / 'ui/DesktopDynamicTimeline.kt', MAIN / 'ui/DesktopDynamicEmptyState.kt',
                 MAIN / 'ui/DesktopDynamicSelfAvatar.kt', MAIN / 'settings/DesktopDynamicTabsPreferences.kt',
                 MAIN / 'settings/DesktopSettingsShareWindowsCatalog.kt']
        files += [TESTS / 'ui' / name for name in ('DesktopDynamicTabsTest.kt', 'DesktopDynamicAuthorSearchTest.kt',
                                                   'DesktopDynamicStartupTest.kt', 'DesktopDynamicAccountStorageTest.kt',
                                                   'DesktopDynamicEmptyStateTest.kt', 'DesktopDynamicSelfAvatarUrlTest.kt')]
        files.append(TESTS / 'settings/DesktopDynamicCategoryMergeSettingsTest.kt')
        pairs = {'{': '}', '(': ')', '[': ']'}
        for path in files:
            stack = []
            for word, _, _ in self.syntax.kotlin_tokens(path.read_text(encoding='utf8')):
                if word in pairs:
                    stack.append(pairs[word])
                elif word in pairs.values():
                    self.assertTrue(stack and stack.pop() == word, path.name)
            self.assertEqual([], stack, path.name)

    def test_hand_written_jvm_test_counts(self):
        counts = {'ui/DesktopDynamicEmptyStateTest.kt': 7, 'ui/DesktopDynamicSelfAvatarUrlTest.kt': 1,
                  'settings/DesktopDynamicCategoryMergeSettingsTest.kt': 3, 'ui/DesktopDynamicTabsTest.kt': 12,
                  'ui/DesktopDynamicAuthorSearchTest.kt': 8, 'ui/DesktopDynamicStartupTest.kt': 9}
        for relative, expected in counts.items():
            self.assertEqual(expected, len(re.findall(r'(?m)^\s*@Test\b', text_of(TESTS / relative))), relative)

    def test_touched_files_have_no_carriage_returns_except_the_legacy_crlf_catalog(self):
        paths = [TOOLS / 'v046_dynamic_category_merge.py', TOOLS / 'extract-upstream-dynamic-tabs.py',
                 TOOLS / 'extract-upstream-dynamic-settings.py', TOOLS / 'extract-upstream-dynamic-editor.py',
                 TOOLS / 'tests/test_v046_dynamic_category_merge_source.py', REPO / merge.ARCHIVE / 'manifest.json',
                 REPO / 'desktop/build.gradle.kts', TOOLS / 'compatibility-source-inputs.json']
        paths += sorted((REPO / merge.ARCHIVE).glob('*.kt'))
        paths += [MAIN / 'ui' / n for n in ('DesktopDynamicUsersState.kt', 'DesktopDynamicTabsHost.kt', 'DesktopDynamicTimeline.kt',
                                            'DesktopDynamicEmptyState.kt', 'DesktopDynamicSelfAvatar.kt')]
        paths.append(MAIN / 'settings/DesktopDynamicTabsPreferences.kt')
        for path in paths:
            self.assertNotIn(b'\r', path.read_bytes(), str(path))


if __name__ == '__main__':
    unittest.main()
