"""Complete pinned v042 Search and shared glass owners; existing Windows boundaries only."""
from pathlib import Path
import hashlib
import json
import re

from v029_comment_search import whole_proof
from v041_search_all import SCREEN, FILTER_SHEET, baseline, apply_recipe, replace_once

COMMIT = '5c34a82418b33bf88acea01784bb3c6b179d6491'
ARCHIVE = Path('desktop/upstream-slices/v042-search-layout')
MANIFEST_SHA256 = 'e2ee6a1fe8ba14bfe6f677e224f98c9be6076690b62b7385b1b936eceeb00c69'
APP = 'app/src/main/java/com/android/purebilibili/'
SEARCH_HOST = APP + 'feature/search/SearchResultContainerTransition.kt'
SHARED_HOST = APP + 'core/ui/transition/SharedCardContainerHost.kt'
CONTROLS = {APP + 'feature/home/components/' + leaf for leaf in ('BottomBarLiquidSegmentedControl.kt', 'BottomBarFloatingSegmentedControl.kt')}
PATHS = (SCREEN, FILTER_SHEET, SEARCH_HOST, SHARED_HOST, APP + 'feature/home/components/BottomBarLiquidSegmentedControl.kt', APP + 'feature/home/components/BottomBarFloatingSegmentedControl.kt', 'app/src/test/java/com/android/purebilibili/feature/search/SearchTabLabelPolicyTest.kt', 'app/src/test/java/com/android/purebilibili/feature/search/SearchCardTargetRestoreTest.kt')


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def fixed_sources(repo):
    root = Path(repo) / ARCHIVE
    raw = (root / 'manifest.json').read_bytes()
    if digest(raw) != MANIFEST_SHA256:
        raise ValueError('Unreviewed v042 Search layout manifest')
    manifest = json.loads(raw)
    if manifest['upstreamCommit'] != COMMIT or manifest['hashNormalization'] != 'raw' or tuple(row['path'] for row in manifest['files']) != PATHS:
        raise ValueError('Unknown v042 Search layout identity')
    if {path.name for path in root.iterdir()} != {'manifest.json', *(row['filename'] for row in manifest['files'])}:
        raise ValueError('Unexpected v042 Search layout archive leaf')
    sources = {}
    for row in manifest['files']:
        raw = (root / row['filename']).read_bytes()
        blob = hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()
        if len(raw) != row['bytes'] or digest(raw) != row['sha256'] or blob != row['gitBlob']:
            raise ValueError('Modified complete v042 Search source: ' + row['path'])
        sources[row['path']] = raw.decode('utf-8')
    return sources, manifest


def adapt_page(repo, item, canonical):
    path = item['path']
    if path not in (SCREEN, FILTER_SHEET):
        return None
    if baseline(repo, path) != canonical:
        raise ValueError('v042 Search canonical producer mismatch')
    selected = fixed_sources(repo)[0][path]
    recipe = dict(item)
    operations = list(item['operations'])
    if path == SCREEN:
        # The latest original removed this unused duplicated import; keep its body exact.
        obsolete = 'import com.android.purebilibili.core.ui.isMiuixNonGlassEnabled\nimport com.android.purebilibili.core.ui.isMiuixNonGlassEnabled'
        assert selected.count(obsolete) == 0
        assert selected.count('import com.android.purebilibili.core.ui.isMiuixNonGlassEnabled\n') == 0
        assert sum(op['before'] == obsolete for op in operations) == 1
        operations = [op for op in operations if op['before'] != obsolete]
    recipe['operations'] = operations
    generated = apply_recipe(repo, selected, recipe)
    return generated, dict(upstreamCommit=COMMIT, path=path, wholeOriginalSelected=True,
        selectedOriginalSha256LF=digest(selected.encode()), originalToPlatform=whole_proof(selected, generated),
        canonicalToSelected=whole_proof(canonical, selected), unrelatedV041ScreenMaterialParity=True,
        completeCurrentOriginalScreen=path == SCREEN)


def selected_shared_source(repo, path, previous):
    return fixed_sources(repo)[0][path] if path in CONTROLS else previous


def shared_identity(repo, path):
    if path not in CONTROLS:
        return None
    _, manifest = fixed_sources(repo)
    row = next(row for row in manifest['files'] if row['path'] == path)
    return dict(pinnedCommit=COMMIT, originalPath=path, path=str(ARCHIVE / row['filename']).replace('\\', '/'), sha256Bytes=row['sha256'], gitBlobOid=row['gitBlob'], bytes=row['bytes'])


def adapt_foundation(path, original):
    source = original
    if path == SHARED_HOST:
        source = replace_once(source, 'import androidx.activity.compose.PredictiveBackHandler', 'import com.bilipai.desktop.ui.DesktopSubscriptionPredictiveBackHandler as PredictiveBackHandler')
        # The existing Windows NavigationEvent owner supplies the actual progress scalar.
        source = replace_once(source, 'event.progress.coerceIn(0f, 1f)', 'event.coerceIn(0f, 1f)')
    elif path == SEARCH_HOST:
        source = replace_once(source, 'import androidx.compose.runtime.rememberCoroutineScope', 'import androidx.compose.runtime.rememberCoroutineScope\nimport androidx.compose.runtime.DisposableEffect\nimport androidx.compose.runtime.getValue\nimport androidx.compose.runtime.rememberUpdatedState\nimport com.bilipai.desktop.ui.ArticleNavigationTarget\nimport com.bilipai.desktop.ui.DesktopSearchCaller\nimport com.bilipai.desktop.ui.LocalDesktopOriginalSearchEnvironment\nimport com.bilipai.desktop.ui.LocalDesktopOriginalSearchActive')
        source = replace_once(source, '    val scope = rememberCoroutineScope()', '    val environment = LocalDesktopOriginalSearchEnvironment.current\n    val active = LocalDesktopOriginalSearchActive.current\n    val latestActive by rememberUpdatedState(active)\n    val scope = rememberCoroutineScope { DesktopSearchCaller() }')
        source = replace_once(source, '    val articleRequest = remember { SearchArticleOpenRequest() }', '    val articleRequest = remember { SearchArticleOpenRequest() }\n    DisposableEffect(environment, active) {\n        if (!active) articleRequest.job?.cancel()\n        onDispose { articleRequest.job?.cancel() }\n    }')
        source = replace_once(source, 'val target = resolveArticleNavigationTarget(id)', 'val target = environment.articleResolver.resolve(id)')
        for kind in ('UP', 'BANGUMI'):
            statement = 'open(SearchCardTarget(SearchCardKind.' + kind + ', id, key))'
            source = replace_once(source, statement, 'environment.commit { if (latestActive) ' + statement + ' }')
        source = replace_once(source, '                            when (target) {', '                            environment.commit { if (latestActive) when (target) {')
        source = replace_once(source, '                                null -> Unit\n                            }', '                                null -> Unit\n                            } }')
    else:
        raise ValueError('Unknown current Search foundation')
    return source, whole_proof(original, source)


def emit_foundations(repo, output, test_output=None):
    sources, _ = fixed_sources(repo)
    rows = []
    for path in (SHARED_HOST, SEARCH_HOST):
        original = sources[path]
        body, proof = adapt_foundation(path, original)
        package = re.search(r'(?m)^package (\S+)', body).group(1)
        target = Path(output) / package.replace('.', '/') / Path(path).name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(body, encoding='utf-8', newline='\n')
        rows.append(dict(path=path, output=str(target), originalToPlatform=proof, upstreamCommit=COMMIT, wholeOriginalInverseRecoverable=True))
    if test_output is not None:
        for path in PATHS:
            if '/test/java/' not in path:
                continue
            target = Path(test_output) / path.split('/test/java/', 1)[1]
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(sources[path].encode('utf-8'))
    (Path(output) / 'v042-search-foundation-source-proof.json').write_text(json.dumps(rows, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    return rows
