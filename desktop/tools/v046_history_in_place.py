"""Original v0.4.6 History browsing session and in-place progress on the existing Windows History VM.

No archive of its own: the complete original `HistoryViewModel` declarations are read from the
already carried, hash-pinned v0.4.6 `ListViewModel.kt` of `v046-favorite-editor` through that
slice's own `fixed()`. This layer sits after the favorite-editor layer on the generated
`feature/list/DesktopOriginalListViewModels.kt`, so every earlier layer and its predecessor pin is
untouched. Upstream commits 4e792ad (v0.3.9) and c42851ae (v0.4.0, a merge carrying the same hunk)
moved the History list from "reload on every activation and every refresh signal" to "one server
read per browsing session, progress updated in place".
"""
import hashlib

from v044_favorite_detail import inverse_proof
from v044_favorite_subscription import parser, span, need
from v046_favorite_editor import fixed, COMMIT, MANIFEST_SHA256

# LF SHA-256 of the generated body this layer was authored against: the output of the
# favorite-editor layer (the producer's last VM layer before this one).
PREDECESSOR_SHA256 = '3ace7a1a3869d5688fdbb1923eafeac856c48a8aa75975bbdf8a76bddb58b2f8'

WINDOWS_HEAD = 'class HistoryViewModel(environment: DesktopFavoriteEnvironment) : BaseListViewModel(environment, "历史记录") {\n'
# The predecessor's two members that this layer retires: the constructor-time pause-state
# read (the original moved it to the first page activation) and the collector that reloaded the
# whole list from the server on every refresh signal.
WINDOWS_REFRESH = ('    init {\n        loadHistoryPauseState()\n        observeHistoryRefresh()\n    }\n\n'
                   '    private fun observeHistoryRefresh() {\n        viewModelScope.launch {\n'
                   '            environment.historyChanges.collect {\n                loadData(showLoading = false)\n'
                   '            }\n        }\n    }')
ORIGINAL_INIT = '    init {\n        observeHistoryRefresh()\n    }'
ORIGINAL_BUS = 'HistoryRefreshBus.changes.collect {'
WINDOWS_BUS = 'environment.historyChanges.collect {'
UPDATE_DOC = '    /** Refresh progress in place so watching a video cannot reorder the current list. */\n'

# Result of the roadmap audit for the two upstream entries (read offline from the local upstream
# git objects, and from the generated Windows output before this layer). c42851ae is the v0.4.0
# merge: its three History files are the very same blobs as 4e792ad, so the v0.4.0 entry is the
# v0.3.9 hunk again, not a separate change. v0.4.6 left both refresh files at those blobs and
# only rewrote ListViewModel.kt further (account-scoped requests, not part of this slice). The
# two bus/suppression blobs differ from the vendored v0.2.5 files in KDoc only (diffed offline),
# so they carry no behaviour to pin here.
AUDIT = dict(
    v039=dict(commit='4e792adaf11b8c01ab07c0fe420d95f6bd77f389', blobs={
        'ListViewModel.kt': '389ab2ff08fad30bc8b98afbfd9bb8b61723e2b1',
        'HistoryRefreshBus.kt': 'e9c79d24b44d07dc52ceb86cc96c30e029b7911e',
        'HistoryRefreshSuppression.kt': 'b978d8d870b9f323f83afb804e3c844c85458b1c'}),
    v040=dict(commit='c42851ae9532fe3566fdb77dd34a2555032499c2', blobs={
        'ListViewModel.kt': '389ab2ff08fad30bc8b98afbfd9bb8b61723e2b1',
        'HistoryRefreshBus.kt': 'e9c79d24b44d07dc52ceb86cc96c30e029b7911e',
        'HistoryRefreshSuppression.kt': 'b978d8d870b9f323f83afb804e3c844c85458b1c'}),
    carriedSessionMembers=['enterBrowsingSession', 'setPageActive', 'updateCachedHistoryProgress'],
    windowsStatusBeforeThisLayer=dict(v039='missing', v040='missing'),
    windowsFindingsBeforeThisLayer=[
        'generated HistoryViewModel had no enterBrowsingSession, setPageActive or updateCachedHistoryProgress',
        'generated observeHistoryRefresh collected environment.historyChanges and called loadData(showLoading = false)',
        'the personal-list host called loadData on every isCurrentPage activation'])


def sha(value):
    return hashlib.sha256(value.encode('utf8') if isinstance(value, str) else value).hexdigest()


def _once(text, old, new, label):
    need(text.count(old) == 1, 'History in-place anchor changed: ' + label)
    return text.replace(old, new, 1)


def restore(value, proof):
    """Apply an inverse proof backwards, exactly as the favorite-editor layer does."""
    need(sha(value) == proof['afterSHA256'], 'History in-place final output differs from its receipt')
    lines = value.splitlines(keepends=True)
    for hunk in reversed(proof['hunks']):
        first, last = hunk['afterLines']
        need(''.join(lines[first:last]) == hunk['after'], 'History in-place inverse span changed')
        lines[first:last] = hunk['before'].splitlines(keepends=True)
    result = ''.join(lines)
    need(sha(result) == proof['beforeSHA256'], 'History in-place predecessor inverse failed')
    return result


def original_members(repo):
    """The complete original declarations this layer reuses, in the original's own spelling."""
    sources, _ = fixed(repo)
    syntax = parser(repo)
    original = sources['ListViewModel.kt']
    first, last = span(original, 'class', 'HistoryViewModel', syntax)
    cls = original[first:last]
    head = cls[:cls.index('\n') + 1]
    need(head.startswith('class HistoryViewModel(application: Application)'), 'Original History class header changed')
    _, set_end = span(cls, 'fun', 'setPageActive', syntax)
    # Four session fields, enterBrowsingSession and setPageActive are one contiguous original run.
    session = cls[len(head):set_end]
    need(session.startswith('    private var isPageActive = false\n    private var startupLoadsActivated = false\n'
                            '    private var browsingSessionStarted = false\n    private var browsingSessionKey: Any? = null\n'),
         'Original History session fields changed')
    for name in ('enterBrowsingSession', 'setPageActive'):
        span(session, 'fun', name, syntax)  # fails closed when a member is missing, duplicated or unbounded
    need(session.count('fun ') == 2 and 'accountRequests' not in session and 'onAccountChanged' not in session,
         'Original History session run now holds account-scoped members')
    update_first, update_last = span(cls, 'fun', 'updateCachedHistoryProgress', syntax)
    need(cls[:update_first].endswith(UPDATE_DOC), 'Original in-place progress documentation changed')
    update = UPDATE_DOC + cls[update_first:update_last]
    need('accountRequests' not in update and 'HistoryRefreshBus' not in update, 'Original in-place progress member changed')
    need(cls.count(ORIGINAL_INIT) == 1, 'Original History init changed')
    init_first = cls.index(ORIGINAL_INIT)
    _, observe_last = span(cls, 'fun', 'observeHistoryRefresh', syntax)
    refresh = cls[init_first:observe_last]
    need(refresh.count(ORIGINAL_BUS) == 1 and 'if (isPageActive) updateCachedHistoryProgress()' in refresh and
         'loadData' not in refresh, 'Original History refresh collector changed')
    return dict(session=session, update=update, refresh=refresh, syntax=syntax)


def advance_vm(repo, before):
    need(sha(before) == PREDECESSOR_SHA256, 'History in-place predecessor changed')
    parts = original_members(repo)
    syntax = parts['syntax']
    first, last = span(before, 'class', 'HistoryViewModel', syntax)
    current = before[first:last]
    need(current.startswith(WINDOWS_HEAD), 'Windows History class header changed')
    for token in ('isPageActive', 'browsingSession', 'setPageActive', 'updateCachedHistoryProgress', 'startupLoadsActivated'):
        need(token not in current, 'Windows History already owns: ' + token)
    # 1. The original session fields, enterBrowsingSession and setPageActive, right after the class header.
    current = WINDOWS_HEAD + parts['session'] + '\n\n' + current[len(WINDOWS_HEAD):]
    # 2. The original in-place progress member, directly after the progress enrichment it reuses.
    _, enrich_last = span(current, 'fun', 'enrichHistoryProgress', syntax)
    current = current[:enrich_last] + '\n\n' + parts['update'] + current[enrich_last:]
    # 3. The original constructor and bus collector replace the reload-on-every-signal pair. The
    #    only platform binding is the existing Windows bus port, as everywhere in this producer.
    refresh = _once(parts['refresh'], ORIGINAL_BUS, WINDOWS_BUS, 'Windows history change port')
    need(current.count(WINDOWS_REFRESH) == 1, 'Windows History refresh collector changed')
    current = current.replace(WINDOWS_REFRESH, refresh, 1)
    body = before[:first] + current + before[last:]
    need('loadData(showLoading = false)' not in current and 'loadHistoryPauseState()' in current,
         'History in-place layer left the reload collector behind')
    sources, _ = fixed(repo)
    originals = []
    for name, selected, adapted in (
            ('sessionFieldsEnterBrowsingSessionSetPageActive', parts['session'], parts['session']),
            ('updateCachedHistoryProgress', parts['update'], parts['update']),
            ('initAndObserveHistoryRefresh', parts['refresh'], refresh)):
        originals.append(dict(name=name, originalSHA256=sha(selected), adaptedSHA256=sha(adapted),
                              wholeOriginalRun=True, platformBinding=inverse_proof(selected, adapted)))
        need(selected in sources['ListViewModel.kt'], 'History in-place member is not an original run: ' + name)
    return body, dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256,
        upstreamChanges=['4e792adaf11b8c01ab07c0fe420d95f6bd77f389', 'c42851ae9532fe3566fdb77dd34a2555032499c2'],
        predecessorSHA256=PREDECESSOR_SHA256, audit=AUDIT, completeOriginalRuns=originals,
        reloadOnEveryHistorySignalRetired=True, serverReadOncePerBrowsingSession=True,
        constructorPauseStateReadMovedToFirstActivation=True, noSecondProgressStoreOrQueue=True,
        retainedToCurrent=inverse_proof(before, body))
