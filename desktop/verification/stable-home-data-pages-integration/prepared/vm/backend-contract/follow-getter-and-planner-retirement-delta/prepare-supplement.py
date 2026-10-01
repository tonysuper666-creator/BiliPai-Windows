from pathlib import Path
import difflib
import hashlib
import importlib.util
import json
import subprocess
import sys
import textwrap

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
WORK = next(p for p in HERE.parents if p.name == 'work')
REPO = WORK / 'BiliPai-v023'
COMMIT = '3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
ORIGINAL = 'app/src/main/java/com/android/purebilibili/data/repository/DynamicRepository.kt'
TOOL = 'desktop/tools/extract-upstream-dynamic-settings.py'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def normalized(path):
    return path.read_bytes().replace(b'\r\n', b'\n')


def write(name, content):
    p = HERE / name
    assert not p.exists(), p
    p.write_text(content, encoding='utf-8', newline='\n')


def save(name, data):
    write(name, json.dumps(data, ensure_ascii=False, indent=2) + '\n')


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded


def main():
    blob = subprocess.run(['git', 'show', COMMIT + ':' + ORIGINAL], cwd=REPO, capture_output=True, check=True).stdout
    original = blob.decode('utf-8').replace('\r\n', '\n')
    assert normalized(REPO / ORIGINAL) == original.encode('utf-8')
    parser = module('follow_supplement_parser', REPO / 'desktop/tools/sync-upstream.py')
    media = module('follow_supplement_media', REPO / 'desktop/tools/extract-upstream-media.py')
    # The existing media.function supports blocks, not this original expression getter.
    # Select its complete original declaration with two exact anchors and no replacement body.
    start_anchor = '    fun currentUpdateBaseline('
    end_anchor = '    ): String = feedPagination.updateBaseline(scope, type)'
    assert original.count(start_anchor) == original.count(end_anchor) == 1
    begin = original.index(start_anchor)
    end = original.index(end_anchor, begin) + len(end_anchor)
    baseline = textwrap.dedent(original[begin:end])
    more = media.function(original, 'hasMoreData', parser)
    fragment = baseline + '\n\n' + more + '\n'
    assert 'feedPagination.updateBaseline(scope, type)' in baseline
    assert 'return feedPagination.hasMore(scope, type)' in more
    write('original-follow-getters.memberfragment.kt', fragment)
    current = normalized(REPO / TOOL).decode('utf-8')
    anchor = " methods=[media.function(source,name,parser) for name in ['getDynamicFeed','syncPaginationAfterRefresh','fetchDynamicFeedPageWithRetry']]\n"
    assert current.count(anchor) == 1
    addition = ''' # Both getters read the existing sole feedPagination registry, including HOME_FOLLOW/video.
 baselineStart='    fun currentUpdateBaseline('
 baselineEnd='    ): String = feedPagination.updateBaseline(scope, type)'
 assert source.count(baselineStart)==source.count(baselineEnd)==1
 begin=source.index(baselineStart);end=source.index(baselineEnd,begin)+len(baselineEnd)
 methods.extend([textwrap.dedent(source[begin:end]),media.function(source,'hasMoreData',parser)])
'''
    candidate = current.replace(anchor, anchor + addition)
    patch = ''.join(difflib.unified_diff(current.splitlines(True), candidate.splitlines(True), fromfile='a/' + TOOL, tofile='b/' + TOOL))
    write('sole-producer-two-getters.patch', patch)
    # Validate the proposed selector, independently of production generation/compilation.
    selected = textwrap.dedent(original[begin:end])
    assert selected == baseline
    assert [(x[0]) for x in parser.kotlin_tokens(baseline)] == [(x[0]) for x in parser.kotlin_tokens(original[begin:end])]
    paths = {
        'runtime': 'desktop/src/main/kotlin/com/bilipai/desktop/plugins/DesktopPluginRuntime.kt',
        'view': 'desktop/src/main/kotlin/com/bilipai/desktop/ui/PluginProviderScreens.kt',
        'controller': 'desktop/src/main/kotlin/com/bilipai/desktop/DesktopPlaybackController.kt',
        'oldPlanner': 'desktop/src/main/kotlin/com/bilipai/desktop/plugins/DesktopTodayWatchRepository.kt',
    }
    source_pins = [{'path': n, 'sha256Bytes': sha((REPO / n).read_bytes()), 'sha256LF': sha(normalized(REPO / n))} for n in paths.values()]
    def location(key, exact):
        s = normalized(REPO / paths[key]).decode('utf-8')
        assert s.count(exact) == 1, exact
        return {'path': paths[key], 'line': s[:s.index(exact)].count('\n') + 1, 'exactCurrentSource': exact}
    seams = [
        {**location('runtime', 'val recommendations = DesktopTodayWatchRepository(this, repository, discovery)'),
         'action': 'Replace this constructor with the sole HomeVM delegate. Do not instantiate the old planner/refill coordinator alongside HomeVM.'},
        {**location('runtime', 'repository.account.collect { recommendations.accountChanged(repository.sessionEpoch) }'),
         'action': 'Delegate epoch replacement to retirement/recreation of the same HomeVM owner. Observe actual sessionEpochFlow, so same-MID credential replacement also retires work; account-value-only Flow is insufficient.'},
        {**location('runtime', 'recommendations.shutdownForRestore()'),
         'action': 'Retain an awaited retirement point before the shared Store freezes. Reject admission and cancel/join owned VM/rebuild/refill work; do not leave a constructor-owned old coordinator running.'},
        {**location('view', 'val state by runtime.recommendations.state.collectAsState()'),
         'action': 'Map original HomeUiState.todayWatchPlan/loading/error through a read-only derived StateFlow. No second plan/cache/consumed set.'},
        {**location('view', 'LaunchedEffect(runtime, config.refreshTriggerToken, config.currentMode, config.recommendationStrategy, config.candidatePoolMode) { runtime.recommendations.reload() }'),
         'action': 'Delegate to one original VM refresh/rebuild queue using its current Home feed snapshot, refreshIdx, caches and generation.'},
        {**location('view', 'scope.launch { runtime.recommendations.reload(forceHistory = true) }'),
         'action': 'Expose a typed original VM bridge for explicit history invalidation and original rebuild; do not invoke another repository actor.'},
        {**location('controller', 'if (owns(context) && context.accountEpoch == playback.sessionEpoch) plugins.recommendations.consume(context.details.bvid)'),
         'action': 'Keep native READY and immutable playback ownership checks plus recommendationConsumed once flag. Resolve this BVID to the actual same-epoch original VM plan VideoItem, then call original markTodayWatchVideoOpened; no global/current replacement item fallback.'},
    ]
    save('planner-retirement-contract.json', {
        'status': 'READ_ONLY_SOURCE_CONTRACT_NOT_INSTALLED_OR_COMPILED',
        'originalCommit': COMMIT,
        'sourcePins': source_pins,
        'callSites': seams,
        'oldPlannerAuthorities': ['generation', 'mutation/rebuilding Mutex', 'consumed', 'accountEpoch', 'historyCache/historyLoadedAt/historyExhausted', 'expandedCache', 'baseCandidates', 'DesktopTodayWatchRefillCoordinator', '_state'],
        'originalVmAnchors': [
            {'path': 'app/src/main/java/com/android/purebilibili/feature/home/HomeViewModel.kt', 'line': 605, 'name': 'rebuildTodayWatchPlan'},
            {'path': 'app/src/main/java/com/android/purebilibili/feature/home/HomeViewModel.kt', 'line': 626, 'name': 'performTodayWatchPlanRebuild'},
            {'path': 'app/src/main/java/com/android/purebilibili/feature/home/HomeViewModel.kt', 'line': 959, 'name': 'markTodayWatchVideoOpened'},
            {'path': 'app/src/main/java/com/android/purebilibili/feature/home/HomeViewModel.kt', 'line': 1693, 'name': 'fetchFollowFeed HOME_FOLLOW/video'},
        ],
        'boundary': 'Exact read-only current sibling source anchors and proposed delegation. No VM/actor, production generator or shared source written; no Gradle/HTTP/HWND.'
    })
    save('source-checks.json', {
        'status': 'SOURCE_ONLY_PASS',
        'original': {'path': ORIGINAL, 'commit': COMMIT, 'sha256LF': sha(original.encode('utf-8')), 'rawBlobSha256': sha(blob)},
        'producer': {'path': TOOL, 'baseSha256Bytes': sha((REPO / TOOL).read_bytes()), 'baseSha256LF': sha(current.encode('utf-8')), 'candidateSha256LF': sha(candidate.encode('utf-8'))},
        'selectedOriginalGetters': [
            {'name': 'currentUpdateBaseline', 'line': 199, 'sha256SelectedLF': sha(baseline.encode('utf-8')), 'body': 'feedPagination.updateBaseline(scope, type)', 'selector': 'Exact original expression declaration; existing media.function cannot select this expression body.'},
            {'name': 'hasMoreData', 'line': 426, 'sha256SelectedLF': sha(more.encode('utf-8')), 'body': 'return feedPagination.hasMore(scope, type)', 'selector': 'Existing media.function block selector.'},
        ],
        'effect': 'Two members appended to the existing DesktopOriginalDynamicTimelineRepository class; reuse its sole private feedPagination. No new cursor/cache/account/API authority.',
        'classMemberVisibility': 'Original public signatures/default scope/type retained; HOME_FOLLOW with type=video supplied by HomeVM.',
        'productionMutation': False, 'compilerOrRuntimeAcceptance': False,
    })
    rows = [{'relativePath': p.name, 'sha256Bytes': sha(p.read_bytes()), 'bytes': p.stat().st_size} for p in sorted(HERE.iterdir()) if p.is_file()]
    save('evidence-manifest.json', {'status': 'FROZEN_SOURCE_ONLY_SUPPLEMENT', 'artifactRows': rows,
        'artifactCount': len(rows), 'productionWrites': 0, 'parentReportUntouched': '../evidence-manifest.json'})
    print(json.dumps({'manifest': str(HERE / 'evidence-manifest.json'), 'sha256Bytes': sha((HERE / 'evidence-manifest.json').read_bytes()), 'artifacts': len(rows)}, ensure_ascii=False))


if __name__ == '__main__':
    main()
