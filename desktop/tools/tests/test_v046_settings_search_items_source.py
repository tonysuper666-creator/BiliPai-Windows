"""Roadmap slice set-settings-search-items: the Windows item directory, its matching, the item focus claim and the
search page wiring. Source checks only: no Gradle, no JVM, no network. The Kotlin matcher is mirrored in Python (pinyin
left out) so that the directory DATA is exercised here: the JVM class DesktopSettingsItemSearchTest runs the real code later.

Upstream facts are read from the local v0.4.6 objects (commit d6bf8b16, offline) and are hard failures when the objects
are missing, never skipped."""
from pathlib import Path
import importlib.util
import re
import subprocess
import sys
import unittest

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO/'desktop/tools'
KOTLIN = REPO/'desktop/src/main/kotlin/com/bilipai/desktop'
SETTINGS = KOTLIN/'settings'
DIRECTORY = SETTINGS/'DesktopSettingsItemDirectory.kt'
SEARCH = SETTINGS/'DesktopSettingsItemSearch.kt'
FOCUS = SETTINGS/'DesktopSettingsItemFocus.kt'
RESULTS = SETTINGS/'DesktopSettingsItemResults.kt'
CONTROLLER = SETTINGS/'DesktopSettingsSearchController.kt'
SCREEN = SETTINGS/'DesktopSettingsSearchScreen.kt'
TREE = SETTINGS/'DesktopSettingsTree.kt'
CATALOG = SETTINGS/'DesktopWindowsSettingsCatalog.kt'
NOTIFICATION = SETTINGS/'DesktopMessageNotificationSettingsContent.kt'
PRIVACY = SETTINGS/'DesktopPrivacySection.kt'
PLAYBACK = KOTLIN/'ui/DesktopWindowsPlaybackSettings.kt'
AUDIO = KOTLIN/'ui/DesktopWindowsAudioOutputSettings.kt'
ENHANCEMENT = KOTLIN/'ui/DesktopVideoEnhancementControls.kt'
CONTROLS = KOTLIN/'ui/DesktopWindowsSettingsControls.kt'
APPEARANCE = KOTLIN/'appearance/DesktopAppearanceSettings.kt'
DISPLAY_SCALE = KOTLIN/'appearance/DesktopWindowsDisplayScaleSettings.kt'
JVM_TEST = REPO/'desktop/src/test/kotlin/com/bilipai/desktop/settings/DesktopSettingsItemSearchTest.kt'
FOCUS_POLICY = REPO/'app/src/main/java/com/android/purebilibili/feature/settings/SettingsSearchFocusPolicy.kt'
STRINGS_XML = REPO/'app/src/main/res/values/strings.xml'

UPSTREAM_COMMIT = 'd6bf8b1655ae910999860bf13e65c8fcea5b345b'
UPSTREAM_SETTINGS = 'app/src/main/java/com/android/purebilibili/feature/settings/'
UPSTREAM_BLOBS = {
    'PlaybackSettingDirectory.kt': '355feed21f8cc0975fce3e01a4ca5747eba6f9ef',
    'SettingsAdditionalDirectory.kt': '1396d6344c08834656ecd76cac23b7e4cfb84ddb',
    'SettingsSearchFocusPolicy.kt': 'ef41ac6930adff65ceadc3d1e814d71011278a75',
    'SettingsSearchPolicy.kt': '2e7612e32e48fb918764be08de2f153bee1762a8',
}

# Rows that exist on a Windows page and have no upstream id: the id has the upstream shape and is new.
WINDOWS_ONLY_IDS = {
    'playback.default_quality', 'playback.default_playback_speed', 'playback.comment_default_sort_mode',
    'audio.wasapi_exclusive', 'audio.output_device',
    'video.nvidia_auto_enhancement', 'video.nvidia_enhancement_content', 'video.nvidia_enhancement_intensity',
    'video.nvidia_enhancement_quality',
    'appearance.ui_style', 'appearance.theme_mode', 'appearance.dark_theme_style', 'appearance.app_language',
    'appearance.seed_palette_style', 'appearance.window_scale',
    'privacy.default_search_hint_enabled', 'privacy.personal_recap_enabled',
}

# What DesktopSettingsTree mounts, and who answers for it. Adding a page to the tree fails the coverage test below.
COVERED_TARGETS = {'PLAYBACK', 'APPEARANCE', 'MESSAGE_NOTIFICATION', 'PRIVACY_PERMISSION'}
DEFERRED_TARGETS = {
    'HOME_FEED': 'rows come from the generated whole original home settings page',
    'PLUGINS': 'the plugin center is a screen of its own, not a list of settings rows',
    'BLOCKED_LIST': 'a list of blocked accounts, not settings',
    'TIPS': 'a read-only tips page',
    'OPEN_SOURCE_LICENSES': 'a read-only licence list',
    'WEBDAV_BACKUP': 'backup dialogs, rows come from the generated original backup sections',
    'SETTINGS_SHARE': 'backup dialogs, rows come from the generated original backup sections',
    'DOWNLOAD_PATH': 'storage rows come from the generated original data section',
    'CLEAR_CACHE': 'storage rows come from the generated original data section',
    'IMAGE_SAVE_PATH': 'a path chooser',
    'STORAGE_BACKUP': 'the category page renders the generated original data section',
    'SYSTEM_ABOUT': 'the update and diagnostics page is a route of the shell with generated original rows',
}

NORMALIZE_PATTERN = r'Regex("[\\s/&+\\-_:：·()（）]+")'

PHONE_WORDS = ('权限', '手势', '竖屏', '底栏', '亮度', '陀螺仪', '截图', '引导', '电池', '常驻')

# Rows of a mounted page that the directory deliberately does not carry (conditional or secondary), checked to exist.
APPEARANCE_NOT_ITEMISED = ('预设颜色', '调色板风格', '对比度', '颜色规范')
AUDIO_SETTING_ROWS = ('WASAPI 独占输出', '输出设备')

KEY_TITLES = {
    '界面预设': 'appearance_ui_preset_title', '主题模式': 'appearance_theme_mode_title',
    '深色风格': 'appearance_dark_theme_style_title', '应用语言': 'appearance_app_language_title',
}

NEW_TOP_LEVEL_NAMES = (
    'DesktopSettingsItem', 'desktopSettingsItemDirectory', 'desktopSettingsItemPath', 'DesktopSettingsItemHit',
    'DESKTOP_SETTINGS_ITEM_SEARCH_LIMIT', 'normalizeDesktopSettingsSearchText', 'desktopSettingsDescriptionSnippet',
    'resolveDesktopSettingsItemSearch', 'desktopSettingsVisibleSectionResults', 'DesktopSettingsItemHighlightTiming',
    'DesktopSettingsItemFocusRequest', 'DesktopSettingsItemFocusRegistry', 'desktopSettingsItemFocus',
    'DesktopSettingsItemSearchStrings', 'DesktopSettingsSearchResultsBody', 'DesktopSettingsItemResultsSection',
)


def text(path):
    return Path(path).read_bytes().decode('utf8').replace('\r\n', '\n')


def git(*args):
    return subprocess.check_output(['git', '-c', 'core.autocrlf=false', '-C', str(REPO), *args])


def upstream(name, commit=UPSTREAM_COMMIT):
    return git('show', commit + ':' + UPSTREAM_SETTINGS + name).decode('utf8')


def load(filename):
    spec = importlib.util.spec_from_file_location('settings_items_' + filename.replace('-', '_').replace('.', '_'), TOOLS/filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


# --- the directory, parsed from its Kotlin source -----------------------------------------------------------------

CONSTANT = re.compile(r'^private const val (\w+) = "([^"]*)"$', re.M)
CALL = re.compile(r'^    (playbackItem|appearanceItem|notificationItem|privacyItem)\((.*)\),$', re.M)
ARGUMENT = re.compile(r'"([^"]*)"|\b(null)\b|([A-Za-z_][\w.]*)')
FUNCTION_TARGETS = {
    'playbackItem': ('PLAYBACK', 'PLAYBACK_QUALITY'),
    'appearanceItem': ('APPEARANCE', 'APPEARANCE_THEME'),
    'notificationItem': ('MESSAGE_NOTIFICATION', 'PRIVACY_PERMISSION'),
    'privacyItem': ('PRIVACY_PERMISSION', 'PRIVACY_PERMISSION'),
}


def focus_id_constants():
    return dict(re.findall(r'const val (\w+) = "([^"]*)"', text(FOCUS_POLICY)))


def parse_directory():
    source = text(DIRECTORY)
    constants = dict(CONSTANT.findall(source))
    focus_ids = focus_id_constants()

    def resolve(token):
        literal, null, name = token
        if null:
            return None
        if name:
            if name.startswith('SettingsSearchFocusIds.'):
                return focus_ids[name.split('.', 1)[1]]
            return constants[name]
        return literal

    items = []
    for match in CALL.finditer(source):
        function, body = match.groups()
        args = [resolve(token) for token in ARGUMENT.findall(body)]
        target, category = FUNCTION_TARGETS[function]
        if function == 'playbackItem':
            ident, title, description, group, focus, *aliases = args
        elif function == 'appearanceItem':
            ident, title, description, focus, *aliases = args
            group = None
        else:
            ident, title, description, *aliases = args
            focus = None
            group = constants['ITEM_GROUP_NOTIFICATION' if function == 'notificationItem' else 'ITEM_GROUP_SEARCH_HISTORY']
        items.append(dict(id=ident, title=title, description=description, target=target, category=category, group=group,
                          focus=focus, aliases=aliases, kind=function))
    return items


# --- a Python mirror of resolveDesktopSettingsItemSearch (everything but pinyin) -----------------------------------

def normalize(value):
    return re.sub(r'[\s/&+\-_:：·()（）]+', '', value.strip().lower())


def score_against(item, normalized):
    if not normalized:
        return None
    title = normalize(item['title'])
    aliases = [a for a in (normalize(x) for x in item['aliases']) if a]
    if title == normalized:
        return (7, len(normalized))
    if normalized in aliases:
        return (6, len(normalized))
    if title.startswith(normalized) or any(a.startswith(normalized) for a in aliases):
        return (4, len(normalized))
    if normalized in title or any(normalized in a for a in aliases):
        return (3, len(normalized))
    # (pinyin tier 2 is not mirrored)
    texts = [normalize(t) for t in (item['description'], item['group']) if t is not None]
    if any(normalized in t for t in texts):
        return (1, len(normalized))
    return None


def score_item(item, query):
    whole = score_against(item, normalize(query))
    if whole is not None:
        return whole
    words = [w for w in re.split(r'\s+', query) if w]
    if len(words) < 2:
        return None
    parts = []
    for word in words:
        part = score_against(item, normalize(word))
        if part is None:
            return None
        parts.append(part)
    return (min(p[0] for p in parts), sum(p[1] for p in parts))


def mirror_search(items, query, limit=20):
    trimmed = query.strip()
    if not trimmed or limit <= 0:
        return []
    scored = []
    for index, item in enumerate(items):
        match = score_item(item, trimmed)
        if match is not None:
            scored.append((match, index, item))
    scored.sort(key=lambda row: (-row[0][0], -row[0][1], row[1]))
    return [(row[2]['id'], row[0][0]) for row in scored[:limit]]


# --- Kotlin text helpers -------------------------------------------------------------------------------------------

def mask(source):
    """The source with comments, string and char literal contents blanked (delimiters kept), same length."""
    out = []
    i, n = 0, len(source)
    while i < n:
        two = source[i:i + 2]
        if two == '//':
            j = source.find('\n', i)
            j = n if j < 0 else j
            out.append(' ' * (j - i))
            i = j
        elif two == '/*':
            depth, j = 1, i + 2
            while j < n and depth:
                if source[j:j + 2] == '/*':
                    depth, j = depth + 1, j + 2
                elif source[j:j + 2] == '*/':
                    depth, j = depth - 1, j + 2
                else:
                    j += 1
            out.append(''.join(c if c == '\n' else ' ' for c in source[i:j]))
            i = j
        elif source[i] in '"\'':
            quote, j = source[i], i + 1
            while j < n and source[j] != quote:
                j += 2 if source[j] == '\\' else 1
            out.append(quote + ''.join(c if c == '\n' else ' ' for c in source[i + 1:j]) + quote)
            i = j + 1
        else:
            out.append(source[i])
            i += 1
    return ''.join(out)


def balanced(source):
    stack = []
    pairs = {')': '(', ']': '[', '}': '{'}
    for char in mask(source):
        if char in '([{':
            stack.append(char)
        elif char in ')]}':
            if not stack or stack.pop() != pairs[char]:
                return False
    return not stack


def top_level_declarations(source, name):
    pattern = re.compile(r'^(?:@\w+\s+)*(?:(?:internal|private|public|inline|const|data)\s+)*'
                         r'(?:fun|val|var|class|object|interface)\s+(?:<[^>]*>\s+)?(?:[\w.<>?, ]+\.)?' + re.escape(name) + r'\b', re.M)
    return pattern.findall(mask(source))


def block_after(source, header):
    """An expression-bodied declaration: from its header to the next blank line."""
    start = source.index(header)
    return source[start:source.index('\n\n', start)]


def function_text(source, header):
    start = source.index(header)
    depth, i = 0, source.index('{', start)
    while True:
        if source[i] == '{':
            depth += 1
        elif source[i] == '}':
            depth -= 1
            if depth == 0:
                return source[start:i + 1]
        i += 1


# --- the tests -----------------------------------------------------------------------------------------------------

class DirectoryDataTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.items = parse_directory()
        cls.source = text(DIRECTORY)

    def test_every_entry_line_is_parsed_and_only_the_four_helpers_build_items(self):
        entry_lines = [line for line in self.source.splitlines()
                       if re.match(r'^    (playbackItem|appearanceItem|notificationItem|privacyItem)\(', line)]
        self.assertEqual(len(entry_lines), len(self.items))
        self.assertEqual(56, len(self.items))
        self.assertEqual(4, self.source.count('DesktopSettingsItem(id, title, description, SettingsSearchTarget.'))
        for function, (target, category) in FUNCTION_TARGETS.items():
            helper = block_after(self.source, 'private fun ' + function + '(')
            self.assertIn('SettingsSearchTarget.' + target + ', SettingsRootCategory.' + category, helper, function)
        # The only way to build a DesktopSettingsItem is a helper above: no stray constructor call in the list.
        listing = self.source[self.source.index('internal val desktopSettingsItemDirectory'):self.source.index('/** The breadcrumb of a row')]
        self.assertNotIn('DesktopSettingsItem(', listing)

    def test_ids_have_the_upstream_shape_and_titles_and_ids_are_unique(self):
        ids = [item['id'] for item in self.items]
        self.assertEqual(len(ids), len(set(ids)))
        for item in self.items:
            self.assertRegex(item['id'], r'^[a-z0-9_]+(\.[a-z0-9_]+)+$')
            self.assertTrue(item['title'].strip())
        titles = [normalize(item['title']) for item in self.items]
        self.assertEqual(len(titles), len(set(titles)))

    def test_every_item_belongs_to_a_page_the_tree_mounts_and_to_one_of_its_real_anchors(self):
        playback = {'windows_playback_quality', 'windows_playback_speed_subtitle', 'windows_playback_behavior',
                    'windows_playback_comments', 'windows_audio_output', 'windows_video_enhancement'}
        appearance = {'appearance_theme', 'appearance_display', 'windows_display_scale'}
        self.assertEqual(COVERED_TARGETS, {item['target'] for item in self.items})
        for item in self.items:
            allowed = {'PLAYBACK': playback, 'APPEARANCE': appearance}.get(item['target'], set())
            self.assertTrue(item['focus'] is None or item['focus'] in allowed, item['id'])
            if item['target'] == 'PLAYBACK':
                self.assertIsNotNone(item['focus'], item['id'])
                self.assertIsNotNone(item['group'], item['id'])
            else:
                self.assertIn(item['target'], COVERED_TARGETS)
        # Only the group anchors that the existing catalog advertises are used; the catalog file itself is not edited.
        catalog = text(CATALOG)
        for focus in playback | {'windows_display_scale'}:
            self.assertIn('"%s"' % focus, catalog, focus)

    def test_aliases_are_clean_and_never_another_rows_title(self):
        titles = {normalize(item['title']): item['id'] for item in self.items}
        for item in self.items:
            aliases = item['aliases']
            self.assertEqual(len(aliases), len(set(aliases)), item['id'])
            for alias in aliases:
                self.assertTrue(alias and alias == alias.strip(), item['id'])
                owner = titles.get(normalize(alias))
                self.assertTrue(owner is None or owner == item['id'], '%s: alias %s is the title of %s' % (item['id'], alias, owner))

    def test_no_row_names_a_removed_phone_control_and_no_string_has_a_template_or_escape(self):
        for item in self.items:
            parts = [item['title'], item['description']] + item['aliases']
            for word in PHONE_WORDS:
                self.assertFalse([p for p in parts if word in p], '%s names %s' % (item['id'], word))
            for part in parts + [item['group'] or '']:
                self.assertNotIn('$', part)
                self.assertNotIn('\\', part)
                self.assertNotIn('\n', part)
            self.assertLessEqual(len(item['description']), 140, item['id'])
        for forbidden in ('Anime4K', 'FSR', 'CNN'):
            self.assertNotIn(forbidden, self.source)

    def test_the_catalog_is_not_part_of_this_slice(self):
        catalog = text(CATALOG)
        for name in ('desktopSettingsItem', 'DesktopSettingsItem', 'resolveDesktopSettingsItemSearch'):
            self.assertNotIn(name, catalog)
            self.assertNotIn(name, text(TREE))
            self.assertNotIn(name, text(CONTROLS))


class MirroredSearchTest(unittest.TestCase):
    """The data run through a Python mirror of the Kotlin matcher (the JVM class runs the real one, with pinyin)."""

    @classmethod
    def setUpClass(cls):
        cls.items = parse_directory()

    def ids(self, query, **kwargs):
        return [row[0] for row in mirror_search(self.items, query, **kwargs)]

    def test_every_title_finds_its_row_first_at_the_top_tier_and_every_alias_finds_its_row(self):
        for item in self.items:
            hits = mirror_search(self.items, item['title'])
            self.assertEqual((item['id'], 7), hits[0], item['title'])
            for alias in item['aliases']:
                self.assertIn(item['id'], self.ids(alias, limit=500), '%s by %s' % (item['id'], alias))

    def test_pinned_queries_reach_the_rows_the_pages_mount(self):
        first = lambda query: self.ids(query)[0]
        self.assertEqual('playback.background_playback_enabled', first('后台播放'))
        self.assertEqual('playback.video_codec_preference', first(' HEVC '))
        self.assertEqual('playback.subtitle_auto_preference', first('字幕'))
        self.assertEqual('playback.next_watch_hint_enabled', first('接下来观看'))
        self.assertEqual('audio.wasapi_exclusive', first('wasapi 独占'))
        self.assertEqual('appearance.effective_liquid_glass_enabled', first('液态玻璃'))
        self.assertEqual('notification.notify_at_me', first('@我'))
        self.assertEqual('privacy.privacy_mode_enabled', first('无痕'))
        self.assertEqual('playback.auto_play_enabled', first('自动播放下一个'))
        self.assertEqual('playback.external_playlist_auto_continue_enabled', first('列表连续播放'))
        audio = mirror_search(self.items, '  wAsApI  输出 ')
        self.assertEqual({'audio.wasapi_exclusive', 'audio.output_device'}, {row[0] for row in audio})
        self.assertEqual([('playback.detailed_comment_time_enabled', 1)], mirror_search(self.items, '相对时间规则'))
        by_id = {item['id']: item for item in self.items}
        self.assertTrue(all(by_id[row[0]]['focus'] == 'windows_audio_output' for row in audio))

    def test_blank_unknown_and_removed_queries_find_nothing_and_the_limit_and_order_hold(self):
        self.assertEqual([], self.ids('   '))
        self.assertEqual([], self.ids('播放', limit=0))
        self.assertEqual([], self.ids('不存在的zqwxj设置'))
        for query in ('权限', '通知权限', '手势', '三指截图', '竖屏', '横竖屏', '陀螺仪', '底栏', '亮度', '重新引导', '电池', '常驻服务',
                      'Anime4K', 'FSR', 'CNN'):
            self.assertEqual([], self.ids(query), query)
        broad = mirror_search(self.items, '播放')
        self.assertGreater(len(broad), 3)
        self.assertLessEqual(len(broad), 20)
        self.assertEqual(3, len(mirror_search(self.items, '播放', limit=3)))
        tiers = [row[1] for row in broad]
        self.assertEqual(sorted(tiers, reverse=True), tiers)

    def test_a_privacy_word_does_not_list_every_row_of_the_privacy_pages(self):
        # The rail title is not searched: 屏蔽 names the page, and the page rows are not what it asks for.
        self.assertEqual([], self.ids('屏蔽'))
        self.assertEqual([], self.ids('外观与显示'))


class SourceLinkTest(unittest.TestCase):
    """Every directory row is a row of a mounted page, and every setting row of the itemised pages is in the directory."""

    @classmethod
    def setUpClass(cls):
        cls.items = parse_directory()
        cls.sources = {name: text(path) for name, path in dict(
            playback=PLAYBACK, audio=AUDIO, enhancement=ENHANCEMENT, appearance=APPEARANCE, scale=DISPLAY_SCALE,
            notification=NOTIFICATION, privacy=PRIVACY).items()}
        cls.strings = text(STRINGS_XML)

    def owners(self, item):
        if item['target'] == 'PLAYBACK':
            if item['group'] == '音频':
                return ['playback', 'audio']
            if item['group'] == '视频增强':
                return ['enhancement']
            return ['playback']
        return {'APPEARANCE': ['appearance', 'scale'], 'MESSAGE_NOTIFICATION': ['notification'],
                'PRIVACY_PERMISSION': ['privacy']}[item['target']]

    def has_label(self, source, title):
        if '"%s"' % title in source or '"%s · ' % title in source:
            return True
        key = KEY_TITLES.get(title)
        return bool(key) and 'strings["%s"]' % key in source and '<string name="%s">%s</string>' % (key, title) in self.strings

    def test_every_title_is_a_label_of_a_row_of_its_page(self):
        for item in self.items:
            self.assertTrue(any(self.has_label(self.sources[o], item['title']) for o in self.owners(item)),
                            '%s: no row labelled %s' % (item['id'], item['title']))

    def test_every_description_is_a_static_description_of_that_page(self):
        for item in self.items:
            if item['description']:
                self.assertTrue(any(item['description'] in self.sources[o] for o in self.owners(item)),
                                '%s: %s' % (item['id'], item['description']))

    def group_rows(self, group):
        return {i['title'] for i in self.items if i['target'] == 'PLAYBACK' and i['group'] == group}

    def test_the_playback_pane_has_no_row_the_directory_forgot_and_each_row_sits_under_its_own_anchor(self):
        source = self.sources['playback']
        group = re.compile(r'DesktopWindowsSettingsGroup\("([^"]+)"(?:, modifier = Modifier\.desktopSettingsSearchFocusAnchor\(\s*'
                           r'SettingsSearchTarget\.PLAYBACK, "([^"]+)"\))?\) \{')
        starts = list(group.finditer(source))
        self.assertEqual(['解码与画质', '倍速与字幕', '播放行为', '评论', '音频', '视频增强', '桌面操作'], [m.group(1) for m in starts])
        row = re.compile(r'DesktopWindowsSettings(?:Switch|Choice)\("([^"]+)"')
        for index, start in enumerate(starts):
            end = starts[index + 1].start() if index + 1 < len(starts) else len(source)
            rows = set(row.findall(source[start.end():end]))
            title, anchor = start.group(1), start.group(2)
            expected = {i['title'] for i in self.items if i['target'] == 'PLAYBACK' and i['group'] == title and i['id'] not in
                        ('audio.wasapi_exclusive', 'audio.output_device', 'video.nvidia_auto_enhancement',
                         'video.nvidia_enhancement_content', 'video.nvidia_enhancement_intensity', 'video.nvidia_enhancement_quality')}
            if title == '视频增强':
                self.assertEqual(set(), rows)  # hosted panel: its rows are checked below
            elif title == '桌面操作':
                self.assertEqual((set(), None, set()), (rows, anchor, expected))
            else:
                self.assertEqual(expected, rows, title)
            for item in self.items:
                if item['target'] == 'PLAYBACK' and item['group'] == title:
                    self.assertEqual(anchor, item['focus'], item['id'])

    def test_the_hosted_audio_and_enhancement_panels_have_no_setting_row_the_directory_forgot(self):
        audio = re.findall(r'(?:AppSwitchPreference|SettingsSingleChoicePreference)\(\s*title = "([^"]+)"', self.sources['audio'])
        self.assertEqual(list(AUDIO_SETTING_ROWS), audio)
        enhancement = re.findall(r'(?:AppSwitchPreference\(title = |DesktopWindowsSettingsChoice\()"([^"]+)"', self.sources['enhancement'])
        self.assertEqual(['NVIDIA 自动增强', '增强内容', '效果强度', '质量上限'], enhancement)
        self.assertEqual(set(AUDIO_SETTING_ROWS) | {'默认音质'}, self.group_rows('音频'))
        self.assertEqual(set(enhancement), self.group_rows('视频增强'))

    def test_notification_and_privacy_rows_are_exactly_the_mounted_rows(self):
        mounted = re.findall(r'(?:AppSwitchPreference|AppPreference)\(title = "([^"]+)"', self.sources['notification'])
        self.assertEqual(10, len(mounted))
        self.assertEqual(set(mounted), {i['title'] for i in self.items if i['target'] == 'MESSAGE_NOTIFICATION'})
        privacy = re.findall(r'DesktopWindowsSettingsSwitch\("([^"]+)"', self.sources['privacy'])
        self.assertEqual(4, len(privacy))
        self.assertEqual(set(privacy), {i['title'] for i in self.items if i['target'] == 'PRIVACY_PERMISSION'})
        self.assertIn('DesktopWindowsSettingsGroup("搜索与历史")', self.sources['privacy'])
        # The privacy category page mounts no group anchor, so its rows open the page.
        self.assertNotIn('desktopSettingsSearchFocusAnchor', self.sources['privacy'])
        self.assertNotIn('desktopSettingsSearchFocusAnchor', self.sources['notification'])

    def test_appearance_rows_sit_in_the_card_of_their_anchor(self):
        source = self.sources['appearance']
        starts = [m.start() for m in re.finditer(r'DesktopAppearanceCard\(', source)] + [len(source)]
        focus_ids = focus_id_constants()
        blocks = []
        for begin, end in zip(starts, starts[1:]):
            block = source[begin:end]
            anchor = re.match(r'DesktopAppearanceCard\(Modifier\.fillMaxWidth\(\)\.desktopSettingsSearchFocusAnchor\('
                              r'SettingsSearchTarget\.APPEARANCE, SettingsSearchFocusIds\.(\w+)\)\)', block)
            blocks.append((focus_ids[anchor.group(1)] if anchor else None, block))
        self.assertEqual(['appearance_theme', 'appearance_display'], [b[0] for b in blocks if b[0]])
        for item in self.items:
            if item['target'] != 'APPEARANCE' or item['focus'] == 'windows_display_scale':
                continue
            holders = [anchor for anchor, block in blocks if self.has_label(block, item['title'])]
            self.assertEqual(1, len(holders), item['id'])
            self.assertEqual(item['focus'], holders[0], item['id'])
        scale = self.sources['scale']
        self.assertEqual(1, scale.count('desktopSettingsSearchFocusAnchor(SettingsSearchTarget.APPEARANCE, "windows_display_scale")'))
        self.assertTrue(self.has_label(scale, '窗口缩放'))
        for title in APPEARANCE_NOT_ITEMISED:
            self.assertIn('strings.desktop("%s"' % title, source)
            self.assertNotIn(title, {i['title'] for i in self.items})

    def test_the_tree_mounts_exactly_the_covered_and_the_deferred_targets(self):
        tree = text(TREE)
        detail = tree[tree.index('is DesktopSettingsPage.Detail -> when (page.target) {'):tree.index('is DesktopSettingsPage.Category -> DesktopSettingsTreePage {')]
        targets = set(re.findall(r'SettingsSearchTarget\.(\w+)', detail))
        category = tree[tree.index('is DesktopSettingsPage.Category -> DesktopSettingsTreePage {'):]
        categories = set(re.findall(r'SettingsRootCategory\.(\w+)', category))
        mounted = targets | categories
        self.assertEqual(set(COVERED_TARGETS) | set(DEFERRED_TARGETS),
                         mounted - {'PLAYBACK_QUALITY', 'HOME_RECOMMENDATION', 'APPEARANCE_THEME', 'PLUGINS_EXTENSIONS'})
        self.assertEqual(set(), COVERED_TARGETS & set(DEFERRED_TARGETS))
        self.assertEqual(COVERED_TARGETS, {item['target'] for item in self.items})


class UpstreamAlignmentTest(unittest.TestCase):
    def test_the_upstream_objects_are_the_pinned_v046_blobs(self):
        self.assertEqual(b'commit', git('cat-file', '-t', UPSTREAM_COMMIT).strip())
        for name, blob in UPSTREAM_BLOBS.items():
            self.assertEqual(blob, git('rev-parse', UPSTREAM_COMMIT + ':' + UPSTREAM_SETTINGS + name).decode().strip(), name)

    def test_a_row_keeps_the_upstream_id_unless_it_is_listed_as_windows_only(self):
        upstream_ids = set()
        for name in ('PlaybackSettingDirectory.kt', 'SettingsAdditionalDirectory.kt'):
            upstream_ids |= set(re.findall(r'PlaybackSettingItem\(\s*"([\w.]+)"', upstream(name)))
        self.assertGreater(len(upstream_ids), 190)
        ids = {item['id'] for item in parse_directory()}
        self.assertEqual(WINDOWS_ONLY_IDS & ids, WINDOWS_ONLY_IDS)
        for ident in sorted(ids - WINDOWS_ONLY_IDS):
            self.assertIn(ident, upstream_ids, ident)
        for ident in sorted(WINDOWS_ONLY_IDS):
            self.assertNotIn(ident, upstream_ids, ident)

    def test_the_highlight_timing_is_the_upstream_timing_and_the_tiers_are_upstream_tiers(self):
        policy = upstream('SettingsSearchFocusPolicy.kt')
        upstream_timing = dict(re.findall(r'const val (HOLD_MILLIS|FADE_MILLIS|UNCLAIMED_TIMEOUT_MILLIS) = ([\d_]+)L?', policy))
        ours = dict(re.findall(r'const val (HOLD_MILLIS|FADE_MILLIS|UNCLAIMED_TIMEOUT_MILLIS) = ([\d_]+)L?', text(FOCUS)))
        self.assertEqual({'HOLD_MILLIS': '1_600', 'FADE_MILLIS': '280', 'UNCLAIMED_TIMEOUT_MILLIS': '5_000'}, upstream_timing)
        self.assertEqual(upstream_timing, ours)
        scoring = upstream('SettingsSearchPolicy.kt')
        scoring = scoring[scoring.index('private fun scoreSettingsSearchMatch'):scoring.index('private fun normalizeSettingsSearchText')]
        self.assertEqual(['7', '6', '5', '4', '3', '2', '1'], re.findall(r'SettingsMatch\((\d)', scoring))
        search = text(SEARCH)
        self.assertEqual(['7', '6', '4', '3', '2', '1'], re.findall(r'ItemMatch\((\d),', function_text(search, 'private fun scoreAgainst(')))
        # The normalisation is upstream's, character for character.
        self.assertEqual(1, upstream('SettingsSearchPolicy.kt').count(NORMALIZE_PATTERN))
        self.assertEqual(1, search.count('ITEM_SEPARATOR_PATTERN = ' + NORMALIZE_PATTERN))
        # The snippet window is upstream's: 140 characters, 40 of lead.
        upstream_snippet = upstream('SettingsSearchPolicy.kt')
        self.assertIn('matched.length <= 140', upstream_snippet)
        self.assertIn('(queryIndex - 40).coerceAtLeast(0)', upstream_snippet)
        self.assertIn('ITEM_SNIPPET_LIMIT = 140', search)
        self.assertIn('ITEM_SNIPPET_LEAD = 40', search)


class WiringTest(unittest.TestCase):
    ORIGINAL_ACTIVATE = '''    /** Exact original SearchScreen dispatch; opening an action result does not perform it. */
    fun activate(result: SettingsSearchResult, onCategoryClick: (SettingsRootCategory) -> Unit,
        onResultClick: (SettingsSearchResult) -> Unit) {
        val category = resolveSettingsRootCategoryForSearchTarget(result.target)
        if (isSceneSettingsSearchTarget(result.target) && category != null) {
            onCategoryClick(category)
        } else {
            SettingsSearchFocusController.submit(result.target, result.focusId)
            onResultClick(result)
        }
    }
'''

    def test_the_controller_keeps_the_section_results_and_the_original_dispatch_and_adds_the_rows(self):
        controller = text(CONTROLLER)
        self.assertEqual(1, controller.count(self.ORIGINAL_ACTIVATE))
        self.assertEqual(1, controller.count('        mutableResults.value = resolveDesktopWindowsSettingsSearchResults(query)\n'))
        self.assertEqual(1, controller.count('        mutableItemResults.value = resolveDesktopSettingsItemSearch(query)\n'))
        self.assertIn('internal val itemResults: StateFlow<List<DesktopSettingsItemHit>> = mutableItemResults.asStateFlow()', controller)
        self.assertIn('val results: StateFlow<List<SettingsSearchResult>> = mutableResults.asStateFlow()', controller)
        self.assertIn('class DesktopSettingsSearchController(private val repository: DesktopSettingsSearchRepository) {', controller)
        activate_item = function_text(controller, 'internal fun activateItem(')
        self.assertLess(activate_item.index('desktopSettingsItemFocus.submit(hit.item, query.value)'),
                        activate_item.index('activate(hit.toSearchResult(), onCategoryClick, onResultClick)'))
        self.assertEqual(1, controller.count('desktopSettingsItemFocus.submit('))

    def test_the_screen_lists_rows_through_one_body_and_the_anchor_claims_before_it_clears(self):
        screen = text(SCREEN)
        self.assertEqual(1, screen.count('DesktopSettingsSearchResultsBody(query, itemResults, results,'))
        self.assertNotIn('SettingsSearchResultsSection(', screen)
        self.assertEqual(1, screen.count('controller.activate(result, onCategoryClick, onResultClick)'))
        self.assertEqual(1, screen.count('controller.activateItem(row, onCategoryClick, onResultClick)'))
        self.assertIn('fun Modifier.desktopSettingsSearchFocusAnchor(target: SettingsSearchTarget, focusId: String): Modifier {', screen)
        anchor = function_text(screen, 'fun Modifier.desktopSettingsSearchFocusAnchor(')
        order = ['desktopSettingsItemFocus.claim(target, focusId)', 'requester.bringIntoView()',
                 'SettingsSearchFocusController.clear(current.token)', 'if (item != null) highlighted = item']
        positions = [anchor.index(piece) for piece in order]
        self.assertEqual(sorted(positions), positions)
        # The hold is its own effect: clearing the request restarts the first one.
        self.assertEqual(2, anchor.count('LaunchedEffect('))
        self.assertIn('LaunchedEffect(highlighted) {', anchor)
        self.assertIn('delay(DesktopSettingsItemHighlightTiming.HOLD_MILLIS)', anchor)
        self.assertIn('tween(DesktopSettingsItemHighlightTiming.FADE_MILLIS)', anchor)
        self.assertIn('desktopSettingsItemFocus.finish(item)', anchor)

    def test_results_body_with_no_rows_is_the_original_section_and_rows_come_first(self):
        results = text(RESULTS)
        body = function_text(results, 'internal fun DesktopSettingsSearchResultsBody(')
        self.assertLess(body.index('if (rows.isEmpty()) {'), body.index('DesktopSettingsItemResultsSection(query, rows, onRowClick)'))
        self.assertIn('SettingsSearchResultsSection(sections, onSectionClick)\n        return', body)
        self.assertIn('desktopSettingsVisibleSectionResults(sections, rows)', body)
        section = function_text(results, 'internal fun DesktopSettingsItemResultsSection(')
        self.assertIn('LocalAppTextSearchQuery provides query', section)
        self.assertIn('subtitle = row.subtitle', section)
        self.assertIn('key(row.item.settingId)', section)

    def test_only_the_slice_files_use_the_new_names_and_the_reserved_pages_are_not_edited(self):
        users = {path.relative_to(KOTLIN).as_posix() for path in KOTLIN.rglob('*.kt')
                 if re.search(r'desktopSettingsItemDirectory|DesktopSettingsItemHit|desktopSettingsItemFocus|resolveDesktopSettingsItemSearch|'
                              r'DesktopSettingsItemFocusRegistry', text(path))}
        self.assertEqual({'settings/DesktopSettingsItemDirectory.kt', 'settings/DesktopSettingsItemSearch.kt',
                          'settings/DesktopSettingsItemFocus.kt', 'settings/DesktopSettingsItemResults.kt',
                          'settings/DesktopSettingsSearchController.kt', 'settings/DesktopSettingsSearchScreen.kt'}, users)
        # The anchors the pages already call stay called exactly as before.
        playback = text(PLAYBACK)
        for anchor in ('windows_playback_quality', 'windows_playback_speed_subtitle', 'windows_playback_behavior',
                       'windows_playback_comments', 'windows_audio_output', 'windows_video_enhancement'):
            self.assertEqual(1, playback.count('"%s"' % anchor), anchor)

    def test_every_new_top_level_name_is_declared_exactly_once_in_the_main_sources(self):
        sources = {path: text(path) for path in KOTLIN.rglob('*.kt')}
        for name in NEW_TOP_LEVEL_NAMES:
            declared = sum(len(top_level_declarations(source, name)) for source in sources.values())
            self.assertEqual(1, declared, name)

    def test_kotlin_files_are_balanced_lf_only_and_the_jvm_class_is_self_contained(self):
        for path in (DIRECTORY, SEARCH, FOCUS, RESULTS, CONTROLLER, SCREEN, JVM_TEST):
            self.assertTrue(balanced(text(path)), path.name)
            self.assertNotIn(b'\r', path.read_bytes(), path.name)
        self.assertNotIn(b'\r', Path(__file__).read_bytes())
        jvm = text(JVM_TEST)
        self.assertEqual(10, jvm.count('@Test fun '))
        self.assertIn('class DesktopSettingsItemSearchTest {', jvm)
        for forbidden in ('java.net', 'HttpClient', 'okhttp', 'ProcessBuilder', 'Runtime.getRuntime', 'System.getenv', 'Thread.sleep',
                          'DesktopWindowsGpu', 'MpvPlayer', 'LibMpv', 'awt'):
            self.assertNotIn(forbidden, jvm, forbidden)
        self.assertEqual(1, jvm.count('Files.createTempDirectory("bilipai-settings-item-search-")'))
        self.assertIn('@AfterEach fun clearSharedFocusState()', jvm)
        self.assertEqual(1, len(re.findall(r'^\s*class ', jvm, re.M)))

    def test_the_new_files_are_in_the_settings_package_and_no_page_or_chrome_file_was_given_a_new_import(self):
        for path in (DIRECTORY, SEARCH, FOCUS, RESULTS):
            self.assertTrue(text(path).startswith('package com.bilipai.desktop.settings\n'), path.name)
        for path in (PLAYBACK, AUDIO, ENHANCEMENT, APPEARANCE, DISPLAY_SCALE, NOTIFICATION, PRIVACY, TREE, CATALOG, CONTROLS):
            self.assertNotRegex(text(path), r'DesktopSettingsItem|desktopSettingsItem', path.name)


if __name__ == '__main__':
    unittest.main()
