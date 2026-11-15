"""The PGC player title opens the introduction panel below the running player and 收起 returns to the kept tab state.

Runs the real Player UI producer against the pinned .legacy-root that Gradle materializes. From the checkout:
python3 -m unittest desktop.tools.tests.test_bangumi_introduction_panel -v
"""
from pathlib import Path
import importlib.util
import os
import subprocess
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / 'desktop/tools'
sys.path.insert(0, str(TOOLS))
PRODUCER = TOOLS / 'extract-upstream-bangumi-player-ui.py'
CONTENT = 'com/android/purebilibili/feature/bangumi/ui/player/DesktopOriginalBangumiPlayerContent.kt'
PANEL = REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopBangumiIntroductionPanel.kt'
HOOK = "  body,intro_edits=windows_bangumi_introduction_panel_ui(body,r['output'])\n"


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def tree(root):
    return {p.relative_to(root).as_posix(): p.read_bytes() for p in Path(root).rglob('*') if p.is_file()}


def block(syntax, text, opener):
    """Return the text between the brace that ends ``opener`` and its matching close."""
    first = text.index(opener) + len(opener) - 1
    assert text[first] == '{', opener
    depth = 0
    for token, at, _ in syntax.kotlin_tokens(text):
        if at < first:
            continue
        if token == '{':
            depth += 1
        elif token == '}':
            depth -= 1
            if depth == 0:
                return text[first + 1:at], at
    raise AssertionError('unclosed ' + opener)


class BangumiIntroductionPanelTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = REPO if REPO.name == '.legacy-root' else REPO / '.legacy-root'
        if not (cls.source / 'desktop').is_dir():
            raise RuntimeError('Materialize the pinned .legacy-root using the desktop Gradle settings first')
        cls.syntax = load('bp_intro_syntax', TOOLS / 'sync-upstream.py')
        cls.directory = tempfile.TemporaryDirectory(prefix='bp-intro-panel-')
        cls.addClassCleanup(cls.directory.cleanup)
        root = Path(cls.directory.name)
        producer = PRODUCER.read_text(encoding='utf8')
        stage = root / 'stage.py'
        stage.write_text(producer.replace(HOOK, '  intro_edits=[]\n'), encoding='utf8', newline='\n')
        cls.current, cls.stage = root / 'current', root / 'stage'
        for script, output in [(PRODUCER, cls.current), (stage, cls.stage)]:
            env = dict(os.environ, PYTHONDONTWRITEBYTECODE='1', PYTHONUTF8='1', PYTHONPATH=str(TOOLS))
            result = subprocess.run([sys.executable, '-X', 'utf8', '-B', str(script), '--repo', str(cls.source),
                                     '--output', str(output)], cwd=REPO / 'desktop', env=env,
                                    capture_output=True, text=True, timeout=240)
            if result.returncode != 0:
                raise AssertionError(result.stdout + result.stderr)
        cls.content = (cls.current / CONTENT).read_text(encoding='utf8')

    @property
    def panel(self):
        return PANEL.read_text(encoding='utf8')

    def test_title_click_opens_the_panel_for_non_course_seasons(self):
        self.assertEqual(1, self.content.count(
            '    var showDetailPanel by rememberSaveable(detail.seasonId) { mutableStateOf(false) }\n'))
        title, _ = block(self.syntax, self.content, '                0 -> LazyColumn(\n                    state = introductionListState,\n'
                                                     '                    modifier = Modifier.fillMaxSize(),\n'
                                                     '                    contentPadding = PaddingValues(bottom = 32.dp)\n                ) {')
        self.assertIn('                    text = detail.title,\n'
                      '                    modifier = Modifier.clickable(enabled = !isCourse) { showDetailPanel = true },\n', title)
        self.assertEqual(1, self.content.count('showDetailPanel = true'))
        self.assertEqual(1, self.content.count('onDismiss = { showDetailPanel = false }'))

    def test_panel_replaces_only_the_area_below_the_player_and_dialogs_stay_mounted(self):
        outer, outer_end = block(self.syntax, self.content, '    Box(modifier = Modifier.fillMaxSize()) {')
        panel, panel_end = block(self.syntax, outer, '        if (showDetailPanel) {')
        self.assertIn('com.bilipai.desktop.ui.DesktopBangumiIntroductionPanel(', panel)
        self.assertNotIn('HorizontalPager(', panel)
        self.assertTrue(outer.startswith('} else {', panel_end))
        tabs, _ = block(self.syntax, outer[panel_end:], '} else {')
        for kept in ['.layerBackdrop(selectionBackdrop)', 'AppThemeAdaptiveTabRow(', 'HorizontalPager(', 'commentListState']:
            self.assertIn(kept, tabs)
        for mounted in ['if (showFollowStatusDialog) {', 'SubReplySheet(', 'CommentInputDialog(', 'ImagePreviewDialog(']:
            self.assertNotIn(mounted, tabs)
            self.assertIn(mounted, outer)
        # The player is owned by the Screen; Content has no player to pause or rebuild while the panel is open.
        for player in ['exoPlayer', 'ExoPlayer', 'VideoPlayerSection']:
            self.assertNotIn(player, self.content)

    def test_closing_returns_to_the_same_tab_scroll_and_episode_row(self):
        declarations, _ = self.content.split('    Box(modifier = Modifier.fillMaxSize()) {\n        if (showDetailPanel) {', 1)
        for hoisted in ['    val pagerState = rememberPagerState(pageCount = { tabs.size })\n',
                        '    val introductionListState = rememberLazyListState()\n',
                        '    val episodeRowState = rememberLazyListState()\n']:
            self.assertEqual(1, declarations.count(hoisted), hoisted)
        self.assertEqual(1, self.content.count('                    state = introductionListState,\n'))
        self.assertEqual(1, self.content.count('                    LazyRow(\n                        state = episodeRowState,\n'))

    def test_only_content_changes_and_every_edit_inverts_to_the_predecessor(self):
        producer = load('bp_intro_producer', PRODUCER)
        before, after = tree(self.stage), tree(self.current)
        self.assertEqual(set(before), set(after))
        changed = []
        for relative, raw in before.items():
            if not relative.endswith('.kt'):
                self.assertEqual(raw, after[relative], relative)
                continue
            generated, edits = producer.windows_bangumi_introduction_panel_ui(raw.decode('utf8'), relative)
            self.assertEqual(generated.encode('utf8'), after[relative], relative)
            if edits:
                changed.append(relative)
                self.assertEqual(6, len(edits))
                restored = generated
                for edit in reversed(edits):
                    self.assertEqual(1, restored.count(edit['after']))
                    restored = restored.replace(edit['after'], edit['before'], 1)
                self.assertEqual(raw.decode('utf8'), restored)
        self.assertEqual([CONTENT], changed)

    def test_generated_content_and_panel_are_balanced_lf_kotlin(self):
        for name, text in [('content', self.content), ('panel', self.panel)]:
            with self.subTest(name=name):
                self.assertNotIn('\r', text)
                stack = []
                pairs = {')': '(', ']': '[', '}': '{'}
                for token, _, _ in self.syntax.kotlin_tokens(text):
                    if token in '([{':
                        stack.append(token)
                    elif token in pairs:
                        self.assertEqual(pairs[token], stack.pop())
                self.assertEqual([], stack)

    def test_panel_shows_the_upstream_detail_fields_and_a_dismiss_action(self):
        self.assertIn('@Composable internal fun DesktopBangumiIntroductionPanel(\n    detail: BangumiDetail,\n'
                      '    onDismiss: () -> Unit,\n', self.panel)
        self.assertIn('AppTextButton(onClick = onDismiss) { AppText(text = "收起") }', self.panel)
        for shown in ['text = detail.title',
                      '${FormatUtils.formatStat(stat.views)}播放 · ${FormatUtils.formatStat(stat.danmakus)}弹幕',
                      'publish.pubTimeShow.ifBlank { publish.pubTime }', 'detail.newEp?.desc',
                      'detail.areas.orEmpty().map { it.name }', 'detail.styles.orEmpty()',
                      'DesktopBangumiIntroductionSection("简介", detail.evaluate)',
                      'DesktopBangumiIntroductionSection("演职人员", detail.actors)',
                      'DesktopBangumiIntroductionSection("制作人员", detail.staff)']:
            self.assertIn(shown, self.panel)


class BangumiPaginationReturnTest(unittest.TestCase):
    """Check the actual generated consumer and composition ownership, without claiming a UI run."""

    @classmethod
    def setUpClass(cls):
        BangumiIntroductionPanelTest.setUpClass.__func__(cls)
        cls.declarations, cls.visible = cls.content.split(
            '    Box(modifier = Modifier.fillMaxSize()) {\n        if (showDetailPanel) {', 1)

    def test_selection_and_playing_episode_effect_outlive_detail_and_lazy_items(self):
        self.assertIn('var selectedPage by rememberSaveable(detail.seasonId) {', self.declarations)
        self.assertIn('mutableIntStateOf(maxOf(currentEpisodeIndex, 0) / episodesPerPage)', self.declarations)
        effect, _ = block(self.syntax, self.declarations,
                          'LaunchedEffect(detail.seasonId, currentEpisode.id, currentEpisodeIndex) {')
        self.assertIn('if (currentEpisodeIndex >= 0)', effect)
        self.assertIn('selectedPage = currentEpisodeIndex / episodesPerPage', effect)
        self.assertNotIn('showDetailPanel', effect)
        self.assertNotIn('LaunchedEffect(currentEpisodeIndex)', self.visible)
        self.assertNotIn('var selectedPage', self.visible)

    def test_both_real_pagination_rows_consume_state_owned_outside_visibility(self):
        self.assertIn('val episodeRangeRowState = key(detail.seasonId) { rememberLazyListState() }',
                      self.declarations)
        self.assertIn('val pagedEpisodeRowState = key(detail.seasonId, selectedPage) { rememberLazyListState() }',
                      self.declarations)
        for state, items in [('episodeRangeRowState', 'items(totalPages, key = { it })'),
                             ('pagedEpisodeRowState', 'items(pageEpisodes, key = { it.id })')]:
            layout = ('                        horizontalArrangement = Arrangement.spacedBy(6.dp),\n'
                      '                        modifier = Modifier.padding(bottom = 8.dp)\n'
                      if state == 'episodeRangeRowState' else
                      '                        horizontalArrangement = Arrangement.spacedBy(8.dp)\n')
            opener = 'LazyRow(\n                        state = ' + state + ',\n' + (
                '                        contentPadding = PaddingValues(horizontal = 16.dp),\n'
                + layout + '                    ) {')
            row, _ = block(self.syntax, self.visible, opener)
            self.assertIn(items, row)
            self.assertNotIn('remember', row)

    def test_range_click_and_sublist_use_the_preserved_selection_only_for_long_seasons(self):
        long_season, end = block(self.syntax, self.visible,
                                '// 对于超长剧集，添加范围选择器\n'
                                '            if (detail.episodes.orEmpty().size > 50) {')
        self.assertIn('onClick = { selectedPage = page }', long_season)
        self.assertIn('isCurrentPage = page == selectedPage', long_season)
        self.assertIn('val pageStart = selectedPage.coerceIn(0, totalPages - 1) * episodesPerPage', long_season)
        self.assertIn('val pageEnd = minOf(pageStart + episodesPerPage, detail.episodes.orEmpty().size)', long_season)
        self.assertIn('val pageEpisodes = detail.episodes.orEmpty().subList(pageStart, pageEnd)', long_season)
        self.assertIn('val episodesPerPage = 50', self.declarations)
        self.assertNotIn('remember', long_season)
        self.assertNotIn('LaunchedEffect', long_season)
        self.assertIn('state = episodeRowState,', self.visible[end:])

    def pagination_predecessor(self):
        producer = load('bp_pagination_producer', PRODUCER)
        content = (self.stage / CONTENT).read_text(encoding='utf8')
        # Obtain the real predecessor by running the same CLI without either final hook.
        root = Path(self.directory.name)
        script = root / 'without-pagination.py'
        source = PRODUCER.read_text(encoding='utf8').replace(HOOK, '  intro_edits=[]\n')
        pagination_hook = "  body,pagination_edits=windows_bangumi_pagination_state_ui(body,r['output'])\n"
        self.assertEqual(1, source.count(pagination_hook))
        script.write_text(source.replace(pagination_hook, '  pagination_edits=[]\n'), encoding='utf8')
        output = root / 'without-pagination'
        result = subprocess.run([sys.executable, '-B', str(script), '--repo', str(self.source),
                                 '--output', str(output)], cwd=REPO / 'desktop',
                                env=dict(os.environ, PYTHONDONTWRITEBYTECODE='1', PYTHONUTF8='1',
                                         PYTHONPATH=str(TOOLS)), capture_output=True,
                                text=True, timeout=240)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        return producer, output, content

    def test_only_real_content_changes_and_all_five_edits_invert(self):
        producer, predecessor, content = self.pagination_predecessor()
        before, after = tree(predecessor), tree(self.stage)
        self.assertEqual(set(before), set(after))
        changed = []
        for relative, raw in before.items():
            if not relative.endswith('.kt'):
                self.assertEqual(raw, after[relative], relative)
                continue
            generated, edits = producer.windows_bangumi_pagination_state_ui(raw.decode('utf8'), relative)
            self.assertEqual(after[relative], generated.encode('utf8'), relative)
            if edits:
                changed.append(relative)
                self.assertEqual(5, len(edits))
                inverse = generated
                for edit in reversed(edits):
                    self.assertEqual(1, inverse.count(edit['after']))
                    inverse = inverse.replace(edit['after'], edit['before'], 1)
                self.assertEqual(raw.decode('utf8'), inverse)
        self.assertEqual([CONTENT], changed)
        self.assertEqual(after[CONTENT].decode('utf8'), content)

    def test_missing_duplicate_and_drifted_pagination_anchors_are_rejected(self):
        producer, predecessor, _ = self.pagination_predecessor()
        source = (predecessor / CONTENT).read_text(encoding='utf8')
        _, edits = producer.windows_bangumi_pagination_state_ui(source, CONTENT)
        for edit in edits:
            before = edit['before']
            for kind, broken in [('missing', source.replace(before, '', 1)),
                                 ('duplicate', source + before),
                                 ('drift', source.replace(before, before.replace(' ', '\t', 1), 1))]:
                with self.subTest(anchor=edit['label'], kind=kind):
                    with self.assertRaises(AssertionError):
                        producer.windows_bangumi_pagination_state_ui(broken, CONTENT)


if __name__ == '__main__':
    unittest.main()
