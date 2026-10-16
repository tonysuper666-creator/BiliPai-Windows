"""Pinned original comprehensive search, through the existing Windows owners."""
from pathlib import Path
import hashlib
import importlib.util
import json
import re

from v025_source_paths import canonical_source
from v029_comment_search import whole_proof

COMMIT = 'd18fb20e31adb11bbec9fae6dfce7d705d556217'
ARCHIVE = Path('desktop/upstream-slices/v041-search-all')
MANIFEST_SHA256 = 'bdbc6b5337f19bfcd8897a65c608804d492c0908bd64bb1ffbd68d331eb3ec1c'
CORE = 'core-data/src/main/java/com/android/purebilibili/'
APP = 'app/src/main/java/com/android/purebilibili/'
REPOSITORY = CORE + 'data/repository/SearchRepository.kt'
API = CORE + 'core/network/ApiClient.kt'
VIEW_MODEL = APP + 'feature/search/SearchViewModel.kt'
SCREEN = APP + 'feature/search/SearchScreen.kt'
LOAD_POLICY = CORE + 'data/repository/SearchLoadPolicy.kt'
EMPTY_POLICY = APP + 'feature/search/SearchEmptyStatePolicy.kt'
FILTER_SHEET = APP + 'feature/search/SearchVideoFilterSheet.kt'
PATHS = (REPOSITORY, VIEW_MODEL, SCREEN, LOAD_POLICY, EMPTY_POLICY, API, FILTER_SHEET)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def fixed_sources(repo):
    manifest_bytes = (repo / ARCHIVE / 'manifest.json').read_bytes()
    if digest(manifest_bytes) != MANIFEST_SHA256:
        raise ValueError('Comprehensive-search source manifest changed')
    manifest = json.loads(manifest_bytes)
    if (manifest['upstreamCommit'] != COMMIT or manifest['hashNormalization'] != 'raw'
            or tuple(row['path'] for row in manifest['files']) != PATHS):
        raise ValueError('Comprehensive-search source identity changed')
    if {p.name for p in (repo / ARCHIVE).iterdir()} != {'manifest.json', *[row['filename'] for row in manifest['files']]}:
        raise ValueError('Comprehensive-search archive leaves changed')
    sources = {}
    for row in manifest['files']:
        data = (repo / ARCHIVE / row['filename']).read_bytes()
        blob = hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()
        if len(data) != row['bytes'] or digest(data) != row['sha256'] or blob != row['gitBlob']:
            raise ValueError('Comprehensive-search raw source changed: ' + row['path'])
        sources[row['path']] = data.decode('utf-8')
    return sources, manifest


def baseline(repo, path):
    _, manifest = fixed_sources(repo)
    row = next(row for row in manifest['files'] if row['path'] == path)
    text = canonical_source(repo, path).read_text(encoding='utf-8')
    if digest(text.encode()) != row['canonicalSha256LF']:
        raise ValueError('Comprehensive-search canonical input changed: ' + path)
    return text


def parser(repo):
    spec = importlib.util.spec_from_file_location('comprehensive_search_structure', repo / 'desktop/tools/sync-upstream.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def declaration(repo, source, kind, name, *, annotation=False):
    structure = parser(repo)
    tokens = structure.kotlin_tokens(source)
    start, end = structure.kotlin_structure(tokens, kind, name)
    if kind == 'interface':
        marker = list(re.finditer(r'(?m)^interface ' + re.escape(name) + r'\s*\{', source))
        if len(marker) != 1:
            raise ValueError('Ambiguous original interface: ' + name)
        begin = marker[0].start()
    else:
        begin = source.rfind('\n', 0, tokens[start][1]) + 1
    if annotation:
        previous = source.rfind('\n', 0, begin - 1) + 1
        if source[previous:begin] != '@Composable\n':
            raise ValueError('Original composable annotation changed: ' + name)
        begin = previous
    return source[begin:tokens[end][2]]


def replace_once(source, before, after):
    if source.count(before) != 1:
        raise ValueError('Comprehensive-search anchor is not unique: ' + before[:100])
    return source.replace(before, after, 1)


def original_screen_delta(repo, canonical, latest):
    """Adopt actual ALL rendering/filter/paging bodies; preserve unrelated full UI."""
    source = canonical
    for name in ('resolveSearchFilterTabs', 'resolveSearchTypeForPagerPage', 'resolveSearchFilterControls'):
        source = replace_once(source, declaration(repo, canonical, 'fun', name),
                              declaration(repo, latest, 'fun', name))
    marker = 'internal val defaultSearchFilterTabOrder: List<SearchType> = listOf('
    def tabs(text):
        begin = text.index(marker)
        return text[begin:text.index('\n)', begin) + 2]
    source = replace_once(source, tabs(canonical), tabs(latest))
    start = '                            com.android.purebilibili.data.model.response.SearchType.ALL -> {'
    end = '                            com.android.purebilibili.data.model.response.SearchType.VIDEO -> {'
    branch = latest[latest.index(start):latest.index(end, latest.index(start))]
    source = replace_once(source, end, branch + end)
    start = '                        if (targetSearchType == SearchType.ALL && targetSearchType == state.searchType) {'
    end = '                        LaunchedEffect(scrollToTopRequestId, scrollToTopSearchType, targetSearchType) {'
    paging = latest[latest.index(start):latest.index(end, latest.index(start))]
    source = replace_once(source, end, paging + end)
    loading = '                                SearchType.ALL -> ContentMediaListSkeleton('
    video = '                                SearchType.VIDEO -> {'
    branch = latest[latest.index(loading):latest.index(video, latest.index(loading))]
    marker = '                            when (targetSearchType) {\n' + video
    source = replace_once(source, marker, '                            when (targetSearchType) {\n' + branch + video)
    # The retained combined filter bar already exposes all original video filters.
    # Preserve its real callbacks while allowing the new ALL -> video/type fallback.
    before = 'if (state.searchType == SearchType.VIDEO) {\n                                            SearchVideoFilterBar('
    after = 'if (state.searchType == SearchType.VIDEO || state.searchType == SearchType.ALL) {\n                                            SearchVideoFilterBar('
    source = replace_once(source, before, after)
    before = 'SearchVideoFilterBar(\n                                                singleColumn = listLayout.singleColumn,\n                                                onLayoutToggle = listLayout.toggle,'
    flag = 'showLayoutToggle = state.searchType == SearchType.VIDEO,'
    if latest.count(flag) != 1:
        raise ValueError('Original ALL layout visibility contract changed')
    source = replace_once(source, before, before + '\n                                                ' + flag)
    cards = '\n\n'.join(declaration(repo, latest, 'fun', name, annotation=True)
                         for name in ('SearchAllVideoResultCard', 'SearchAllRelatedVideoCard'))
    source += '\n\n' + cards + '\n'
    for line in ('import com.android.purebilibili.core.ui.AppContentColors',
                 'import androidx.compose.foundation.horizontalScroll'):
        if line not in source:
            if line not in latest:
                raise ValueError('Original comprehensive-search import disappeared')
            source = source.replace('\nimport ', '\n' + line + '\nimport ', 1)
    return source


def original_filter_delta(repo, canonical, latest):
    # Keep the full retained material/host body and the original default for VIDEO.
    # The new ALL list must hide a control whose action only changes the video grid.
    parameter = '    showLayoutToggle: Boolean = true,\n'
    if latest.count(parameter) != 1 or latest.count('        if (showLayoutToggle) {') != 1:
        raise ValueError('Original filter layout visibility contract changed')
    source = replace_once(canonical, '    onLayoutToggle: () -> Unit,\n',
                          '    onLayoutToggle: () -> Unit,\n' + parameter)
    mark = source.index('            VideoListLayoutToggle(')
    begin = source.rfind('        BottomBarMatchedReusableLiquidDock(', 0, mark)
    end = source.index('        }\n', mark) + len('        }\n')
    if begin < 0:
        raise ValueError('Retained layout toggle dock changed')
    original = source[begin:end]
    wrapped = '        if (showLayoutToggle) {\n' + ''.join('    ' + line for line in original.splitlines(keepends=True)) + '        }\n'
    return source[:begin] + wrapped + source[end:]


def apply_recipe(repo, source, item, *, repository=False):
    """Reuse the reviewed platform substitutions, with explicit new owner guards."""
    operations = item['operations'][:21] if repository else item['operations']
    seen = set()
    for op in operations:
        before, after = op['before'], op['after']
        pair = (before, after)
        if pair in seen:
            continue
        seen.add(pair)
        if repository and before.startswith('object SearchRepository {'):
            before = before.replace('    private val navApi',
                '    private val searchJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }\n    private val navApi')
            after += '\n    private val searchJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }'
        if before not in source:
            raise ValueError('Reviewed search platform recipe no longer applies: ' + before[:120])
        source = source.replace(before, after)
    if repository:
        source = source.replace('withContext(Dispatchers.IO) {', 'withContext(Dispatchers.IO) {\n        checked()')
        source = source.replace('} catch (e: Exception) {', '} catch (e: Exception) {\n            checked()')
        source = replace_once(source, '            val response = api.searchAll(params)',
            '            val response = api.searchAll(params)\n            checked()')
        # Every original success value crosses the same captured Root request lease.
        # Process right-to-left so nested parentheses and multiline DTOs stay exact.
        tokens = parser(repo).kotlin_tokens(source)
        positions = [i for i in range(len(tokens) - 3)
                     if [token[0] for token in tokens[i:i + 4]] == ['Result', '.', 'success', '(']]
        for start in reversed(positions):
            end, depth = start + 4, 1
            while depth and end < len(tokens):
                depth += (tokens[end][0] == '(') - (tokens[end][0] == ')')
                end += 1
            if depth:
                raise ValueError('Unclosed original search success expression')
            begin, finish = tokens[start][1], tokens[end - 1][2]
            source = source[:begin] + 'run { checked(); ' + source[begin:finish] + ' }' + source[finish:]
    else:
        needle = 'val nativeFiltered = aggregate.videos.filter { it.owner.mid !in blockedMids }'
        if item['path'] == VIEW_MODEL:
            if source.count(needle) != 2:
                raise ValueError('Original comprehensive-search result guards changed')
            source = source.replace(needle, 'application.assertCurrent()\n                            ' + needle)
    return source


def adapt_page(repo, item, canonical):
    path = item['path']
    if path not in (REPOSITORY, VIEW_MODEL, SCREEN, FILTER_SHEET):
        return None
    latest, _ = fixed_sources(repo)
    if baseline(repo, path) != canonical:
        raise ValueError('Search page canonical producer mismatch')
    if path == SCREEN:
        selected = original_screen_delta(repo, canonical, latest[path])
    elif path == FILTER_SHEET:
        selected = original_filter_delta(repo, canonical, latest[path])
    else:
        selected = latest[path]
    generated = apply_recipe(repo, selected, item, repository=path == REPOSITORY)
    return generated, dict(upstreamCommit=COMMIT, path=path,
        wholeOriginalSelected=path in (REPOSITORY, VIEW_MODEL),
        selectedOriginalSha256LF=digest(selected.encode()),
        originalToPlatform=whole_proof(selected, generated),
        canonicalToSelected=whole_proof(canonical, selected),
        unrelatedV041ScreenMaterialParity=False)


def advance_api(repo, source, output):
    latest, _ = fixed_sources(repo)
    old = declaration(repo, baseline(repo, API), 'interface', 'SearchApi')
    new = declaration(repo, latest[API], 'interface', 'SearchApi')
    generated = replace_once(source, old, new)
    (output / 'v041-search-api-source-proof.json').write_text(json.dumps(dict(
        upstreamCommit=COMMIT, selectedInterface='SearchApi',
        wholeOriginalInterfaceExact=True, canonicalToGenerated=whole_proof(source, generated)),
        ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    return generated


def emit_policies(repo, output):
    latest, _ = fixed_sources(repo)
    for path in (LOAD_POLICY, EMPTY_POLICY):
        body = latest[path]
        package = re.search(r'(?m)^package (\S+)', body).group(1)
        target = output / package.replace('.', '/') / Path(path).name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(body.encode('utf-8'))
