from pathlib import Path
import argparse, hashlib, importlib.util, json, re, textwrap, difflib, sys
sys.dont_write_bytecode = True

HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
DEFAULT = MAIN.parent / 'BiliPai-v023'

def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

def sha(data): return hashlib.sha256(data).hexdigest()
def wide(path):
    value = str(path.absolute())
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\' + value)
def write(path, value):
    wide(path.parent).mkdir(parents=True, exist_ok=True)
    wide(path).write_text(value, encoding='utf-8', newline='\n')

def balanced(tokens, index, opening, closing):
    assert tokens[index][0] == opening
    depth = 1
    while depth:
        index += 1
        depth += (tokens[index][0] == opening) - (tokens[index][0] == closing)
    return index

def exact_call(source, marker, name, parser):
    point = source.index(marker)
    start = source.rfind(name + '(', 0, point)
    assert start >= 0
    tokens = parser.kotlin_tokens(source[start:])
    opening = next(i for i, token in enumerate(tokens) if token[0] == '(')
    ending = balanced(tokens, opening, '(', ')')
    return source[start:start + tokens[ending][2]]

def generate(repo):
    parser = load('ni_tokens', MAIN / 'desktop/tools/sync-upstream.py')
    media = load('ni_methods', MAIN / 'desktop/tools/extract-upstream-media.py')
    prefix = 'app/src/main/java/com/android/purebilibili/'
    paths = [prefix + 'feature/settings/ui/SettingsSections.kt',
             prefix + 'feature/settings/screen/BottomBarSettingsScreen.kt',
             prefix + 'feature/settings/screen/AnimationSettingsScreen.kt',
             prefix + 'core/store/SettingsManager.kt']
    sources = {path: (repo / path).read_text(encoding='utf-8').replace('\r\n', '\n') for path in paths}
    sections, bottom, animation, manager = [sources[path] for path in paths]
    selected = []
    branch = re.search(r'SettingsRootCategory\.NAVIGATION_INTERACTION\s*->\s*\{', sections)
    assert branch is not None
    tokens = parser.kotlin_tokens(sections)
    opening = next(i for i, token in enumerate(tokens) if token[1] == branch.end() - 1)
    ending = balanced(tokens, opening, '{', '}')
    body = sections[tokens[opening][2]:tokens[ending][1]]
    # The existing Tree owns the category Column/scroll host; retain the two exact entry groups.
    tokens = parser.kotlin_tokens(body)
    wrappers = [i for i, token in enumerate(tokens[:-1]) if token[0] == 'SettingsRootCategoryEntranceSection' and tokens[i+1][0] == '{']
    assert len(wrappers) == 2
    removals = []
    for index in wrappers:
        end = balanced(tokens, index + 1, '{', '}')
        removals += [(tokens[index][1], tokens[index+1][2]), (tokens[end][1], tokens[end][2])]
    for start, end in sorted(removals, reverse=True): body = body[:start] + body[end:]
    body = textwrap.dedent(body).strip().replace('actions.onBottomBarClick', 'onBottomBarClick').replace('actions.onAnimationClick', 'onAnimationClick')
    assert not re.search(r'\b(?:actions|state)\.', body)
    write(HERE / 'prepared/desktop/build/generated/navigation-interaction/com/android/purebilibili/feature/settings/DesktopNavigationInteractionCategoryEntries.kt',
          '''package com.android.purebilibili.feature.settings
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
/** Exact original NAVIGATION_INTERACTION two entry groups; existing Tree owns scrolling. */
@Composable
internal fun SettingsNavigationInteractionCategoryEntrySection(onBottomBarClick:()->Unit,onAnimationClick:()->Unit) {
''' + textwrap.indent(body, '    ') + '\n}\n')
    fields = [
        ('悬浮底栏', 'isBottomBarFloating', 'getBottomBarFloating', 'setBottomBarFloating'),
        ('导航图标交叉缩放', 'navigationIconCrossScaleEnabled', 'getNavigationIconCrossScaleEnabled', 'setNavigationIconCrossScaleEnabled'),
        ('底栏搜索联动', 'bottomBarSearchEnabled', 'getBottomBarSearchEnabled', 'setBottomBarSearchEnabled'),
        ('下滑合体', 'linkedDockMergeOnScrollEnabled', 'getLinkedDockMergeOnScrollEnabled', 'setLinkedDockMergeOnScrollEnabled'),
        ('列表精简搜索', 'listScopedSearchEnabled', 'getListScopedSearchEnabled', 'setListScopedSearchEnabled'),
    ]
    ui_calls = []
    for title, state, getter, setter in fields:
        call = exact_call(bottom, 'title = "' + title + '"', 'AppSwitchPreference', parser)
        pattern = r'scope\.launch\s*\{\s*SettingsManager\.' + setter + r'\(context, enabled\)\s*\}'
        call, count = re.subn(pattern, setter + '(enabled)', call)
        assert count == 1, setter
        ui_calls.append(call)
        selected += [getter, setter]
    # Preserve the original conditional membership: the latter two only appear with dock search.
    navigation_body = '\nAppPreferenceDivider()\n'.join(ui_calls[:3])
    navigation_body += '\nif (bottomBarSearchEnabled) {\nAppPreferenceDivider()\n' + '\nAppPreferenceDivider()\n'.join(ui_calls[3:]) + '\n}\n'
    animation_calls = []
    for title, state, getter, setter, old in [
        ('进场动画', 'cardAnimationEnabled', 'getCardAnimationEnabled', 'setCardAnimationEnabled', 'toggleCardAnimation'),
        ('过渡动画', 'cardTransitionEnabled', 'getCardTransitionEnabled', 'setCardTransitionEnabled', 'toggleCardTransition')]:
        call = exact_call(animation, 'title = "' + title + '"', 'AppSwitchPreference', parser)
        call = call.replace('state.' + state, state).replace('viewModel.' + old + '(it)', setter + '(it)')
        animation_calls.append(call)
        selected += [getter, setter]
    imports = '''package com.android.purebilibili.feature.settings
import androidx.compose.runtime.Composable
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.core.theme.*
'''
    def field_function(name, states, callbacks, body):
        return '@Composable\ninternal fun ' + name + '(\n' + ''.join('    ' + n + ':Boolean,\n' for n in states) + ''.join('    ' + n + ':(Boolean)->Unit,\n' for n in callbacks) + ') {\n    AppPreferenceGroup {\n' + textwrap.indent(body, '        ') + '\n    }\n}\n'
    write(HERE / 'prepared/desktop/build/generated/navigation-interaction/com/android/purebilibili/feature/settings/DesktopOriginalNavigationInteractionFields.kt',
          imports + field_function('DesktopOriginalNavigationBehaviorFields', [x[1] for x in fields], [x[3] for x in fields], navigation_body) + '\n' +
          field_function('DesktopOriginalCardMotionFields', ['cardAnimationEnabled', 'cardTransitionEnabled'], ['setCardAnimationEnabled', 'setCardTransitionEnabled'], '\nAppPreferenceDivider()\n'.join(animation_calls)))
    methods = [media.function(manager, name, parser) for name in selected]
    keys = sorted(set(re.findall(r'\bKEY_[A-Z_]+\b', '\n'.join(methods))))
    declarations = []
    for key in keys:
        matches = re.findall(r'private val ' + key + r'\s*=\s*booleanPreferencesKey\("[^"]+"\)', manager)
        assert len(matches) == 1, key
        declarations.append(matches[0])
    write(HERE / 'prepared/desktop/build/generated/navigation-interaction/com/android/purebilibili/core/store/DesktopOriginalNavigationInteractionSettings.kt',
          '''package com.android.purebilibili.core.store
import com.bilipai.desktop.plugins.DesktopPluginContext as Context
import com.bilipai.desktop.settings.homeCardVisualBooleanKey as booleanPreferencesKey
import com.bilipai.desktop.settings.homeCardVisualDataStore as settingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
/** Original individual SettingsManager getters/setters, same Root global settings backing. */
internal object DesktopOriginalNavigationInteractionSettings {
''' + textwrap.indent('\n'.join(declarations) + '\n\n' + '\n\n'.join(methods), '    ') + '\n}\n')
    tree_path = 'desktop/src/main/kotlin/com/bilipai/desktop/settings/DesktopSettingsTree.kt'
    raw = (repo / tree_path).read_bytes()
    tree = raw.decode('utf-8').replace('\r\n', '\n')
    tree = tree.replace('SettingsSearchTarget.APPEARANCE, SettingsSearchTarget.PLUGINS, SettingsSearchTarget.BLOCKED_LIST)',
                        'SettingsSearchTarget.APPEARANCE, SettingsSearchTarget.PLUGINS, SettingsSearchTarget.BLOCKED_LIST,\n                            SettingsSearchTarget.BOTTOM_BAR, SettingsSearchTarget.ANIMATION)')
    marker = '                                    SettingsRootCategory.SYSTEM_ABOUT -> systemContent()'
    assert tree.count(marker) == 1
    tree = tree.replace(marker, '''                                    SettingsRootCategory.NAVIGATION_INTERACTION -> SettingsNavigationInteractionCategoryEntrySection(
                                        onBottomBarClick = { navigator.openDetail(SettingsSearchTarget.BOTTOM_BAR,
                                            SettingsSearchFocusController.request.value?.focusId) },
                                        onAnimationClick = { navigator.openDetail(SettingsSearchTarget.ANIMATION,
                                            SettingsSearchFocusController.request.value?.focusId) })
''' + marker)
    marker = '                                    SettingsSearchTarget.HOME_FEED -> DesktopHomeRecommendationSettings(discovery, onFailure)'
    assert tree.count(marker) == 1
    tree = tree.replace(marker, '''                                    SettingsSearchTarget.BOTTOM_BAR -> DesktopNavigationInteractionSettings(SettingsSearchTarget.BOTTOM_BAR, onFailure)
                                    SettingsSearchTarget.ANIMATION -> DesktopNavigationInteractionSettings(SettingsSearchTarget.ANIMATION, onFailure)
''' + marker)
    write(HERE / 'prepared' / tree_path, tree)
    write(HERE / 'tree.patch', ''.join(difflib.unified_diff(raw.decode('utf-8').splitlines(keepends=True), tree.splitlines(keepends=True), fromfile=tree_path, tofile=tree_path)))
    inventory = [dict(path=path, mode='policy-extract', features=['desktop-navigation-interaction-settings'], sha256=sha(sources[path].encode())) for path in paths]
    write(HERE / 'source-inventory.json', json.dumps(inventory, ensure_ascii=False, indent=2) + '\n')
    write(HERE / 'base-identity.json', json.dumps(dict(candidate=str(repo), treeBaseSha256Bytes=sha(raw), treePreparedSha256Bytes=sha(tree.encode()), sources=inventory), indent=2) + '\n')

if __name__ == '__main__':
    cli = argparse.ArgumentParser()
    cli.add_argument('--repo', type=Path, default=DEFAULT)
    generate(cli.parse_args().repo)
