"""Current original subscription transactions on the existing Favorites owner."""
from pathlib import Path
import hashlib
import importlib.util
import json

from v044_favorite_detail import inverse_proof

COMMIT = '070ab41608213cd981fd7e51df357295f9a537bf'
ARCHIVE = 'desktop/upstream-slices/v044-favorite-subscription'
MANIFEST_SHA256 = '570143059269b51e3148f6f99d6b8d065451bd07f00d1fc76ee745e4e80d8449'


def sha(value):
    return hashlib.sha256(value.encode('utf8') if isinstance(value, str) else value).hexdigest()


def need(value, message):
    if not value:
        raise ValueError(message)


def fixed(repo):
    root = Path(repo)/ARCHIVE
    need(root.is_dir() and not root.is_symlink(), 'Missing original subscription archive')
    raw = (root/'manifest.json').read_bytes()
    need(sha(raw) == MANIFEST_SHA256, 'Subscription manifest changed')
    manifest = json.loads(raw)
    need((manifest['repository'], manifest['tag'], manifest['commit'], manifest['hashNormalization']) ==
         ('jay3-yy/BiliPai', 'v0.4.4', COMMIT, 'raw'), 'Subscription original identity changed')
    need(len(manifest['files']) == 10 and
         {p.name for p in root.iterdir()} == {row['filename'] for row in manifest['files']} | {'manifest.json'},
         'Subscription original leaves changed')
    sources = {}
    for row in manifest['files']:
        path = root/row['filename']
        need(path.is_file() and not path.is_symlink() and Path(row['path']).name == path.name,
             'Linked or ambiguous subscription original')
        raw = path.read_bytes()
        need(len(raw) == row['bytes'] and sha(raw) == row['sha256'] and
             hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest() == row['gitBlob'],
             'Subscription original bytes changed: '+path.name)
        sources[path.name] = raw.decode('utf8')
    return sources, manifest


def parser(repo):
    path = Path(repo)/'desktop/tools/sync-upstream.py'
    spec = importlib.util.spec_from_file_location('subscription_kotlin_structure', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def validate_outputs(repo, *outputs):
    archive = (Path(repo)/ARCHIVE).resolve()
    for output in outputs:
        if output is None:
            continue
        target = Path(output).resolve()
        need(not target.is_relative_to(archive) and not archive.is_relative_to(target),
             'Output overlaps original subscription archive')
    fixed(repo)


def span(source, kind, name, syntax, constructor=False):
    tokens = syntax.kotlin_tokens(source)
    matches = [i for i, token in enumerate(tokens[:-1])
               if token[0] == kind and tokens[i+1][0] == name]
    need(len(matches) == 1, 'Ambiguous original declaration: '+name)
    first = matches[0]
    depth = 0
    for i in range(first+2, len(tokens)):
        word = tokens[i][0]
        depth += (word == '(')-(word == ')')
        if constructor and word == ')' and depth == 0:
            return source.rfind('\n', 0, tokens[first][1])+1, tokens[i][2]
        if word == '{' and depth == 0:
            braces = 1
            for j in range(i+1, len(tokens)):
                braces += (tokens[j][0] == '{')-(tokens[j][0] == '}')
                if braces == 0:
                    return source.rfind('\n', 0, tokens[first][1])+1, tokens[j][2]
            break
        if depth == 0 and (word in {'class', 'object', 'interface', 'fun', 'val', 'var'} or
                           word == '=' and kind != 'fun'):
            break
    raise ValueError('Unbounded original declaration: '+name)


def declaration(source, kind, name, syntax, constructor=False):
    first, last = span(source, kind, name, syntax, constructor)
    return source[first:last]


def bind_vm_member(body):
    body = body.replace('accountRequests.launch', 'viewModelScope.launch')
    body = body.replace('accountRequests.identity', 'environment.favoriteAccountIdentity()')
    body = body.replace('accountRequests.isCurrent(', 'environment.isFavoriteAccountCurrent(')
    body = body.replace('NetworkModule.api.', 'environment.api.')
    body = body.replace('com.android.purebilibili.data.repository.FavoriteRepository', 'environment.favorite')
    body = body.replace('com.android.purebilibili.data.repository.ActionRepository\n                        .setCollectionSubscription',
                        'environment.collectionActions\n                        .setCollectionSubscription')
    before = 'SettingsManager.setCollectionSubscription(\n                        getApplication(), folder.id, subscribed\n                    )'
    body = body.replace(before, 'environment.persistCollectionSubscription(folder.id, subscribed)')
    need('accountRequests.' not in body and 'getApplication(' not in body and 'SettingsManager.' not in body and
         'com.android.purebilibili.data.repository.FavoriteRepository' not in body and 'NetworkModule.' not in body,
         'Unbound original subscription account/settings port')
    return body


def advance_vm(repo, before, drop_logs):
    sources, manifest = fixed(repo)
    need(sha(before) == manifest['beforeFinalOutputs']['feature/list/DesktopOriginalListViewModels.kt']['sha256'],
         'Subscription VM final predecessor changed')
    syntax = parser(repo)
    a, b = span(before, 'class', 'FavoriteViewModel', syntax)
    old = before[a:b]
    original = declaration(sources['ListViewModel.kt'], 'class', 'FavoriteViewModel', syntax)
    selected = old
    # The current original activates startup through setPageActive. Retire the
    # predecessor's eager constructor load while keeping its cover/history logic.
    eager = '    init {\n        loadData()\n    }\n'
    need(selected.count(eager) == 1 and eager not in original,
         'Original favorite activation lifecycle changed')
    selected = selected.replace(eager, '', 1)
    reused = []
    for name in ['launchInitialSubscribedFoldersLoad', 'ensureCurrentUserMid',
                 'loadSubscribedFoldersPage', 'loadMoreSubscribedFolders', 'fetchItems']:
        raw = declaration(original, 'fun', name, syntax)
        adapted = drop_logs(bind_vm_member(raw))
        first, last = span(selected, 'fun', name, syntax)
        selected = selected[:first]+adapted+selected[last:]
        reused.append(dict(kind='fun', name=name, originalSHA256=sha(raw), adaptedSHA256=sha(adapted),
                           platformBinding=inverse_proof(raw, adapted)))
    for name in ['ensureSubscribedFoldersLoaded', 'retrySubscribedFolders',
                 'toggleSubscribedFolderSubscription', 'applySubscribedFolderSubscription', 'setPageActive']:
        raw = declaration(original, 'fun', name, syntax)
        adapted = drop_logs(bind_vm_member(raw))
        selected = selected[:-1]+'\n'+adapted+'\n'+selected[-1:]
        reused.append(dict(kind='fun', name=name, originalSHA256=sha(raw), adaptedSHA256=sha(adapted),
                           platformBinding=inverse_proof(raw, adapted)))
    raw = declaration(original, 'class', 'SubscribedFolderProgressState', syntax, True)
    first, last = span(selected, 'class', 'SubscribedFolderProgressState', syntax, True)
    selected = selected[:first]+raw+selected[last:]
    raw_action = declaration(original, 'class', 'SubscribedFolderActionState', syntax, True)
    start = original.index('    private val _subscribedFolderActions =')
    end_marker = '    val subscribedFolderActions = _subscribedFolderActions.asStateFlow()'
    end = original.index(end_marker, start)+len(end_marker)
    action_fields = original[start:end]
    anchor = '    private var isLoadingSubscribedMore = false\n'
    need(selected.count(anchor) == 1, 'Subscription generation field boundary changed')
    selected = selected.replace(anchor, anchor+'    private var subscribedRequestGeneration = 0L\n', 1)
    active_fields = '    private var startupLoadsActivated = false\n    private var isPageActive = false\n'
    need(active_fields in original and 'startupLoadsActivated' not in old,
         'Original favorite page activation fields changed')
    at = selected.index('{')+1
    selected = selected[:at]+'\n'+active_fields+selected[at:]
    selected = selected[:-1]+'\n'+raw_action+'\n'+action_fields+'\n'+selected[-1:]
    start = original.rfind('    init {')
    need(start > 0 and original[start:].count('FavoriteSubscriptionBus.changes.collect') == 1,
         'Original subscription bus observer boundary changed')
    raw_init = original[start:original.rfind('}')].rstrip()
    selected = selected[:-1]+'\n'+bind_vm_member(raw_init)+'\n'+selected[-1:]
    body = before[:a]+selected+before[b:]
    marker = 'package com.android.purebilibili.feature.list\n'
    need(body.count(marker) == 1, 'Subscription VM package changed')
    body = body.replace(marker, marker+'\nimport com.android.purebilibili.core.refresh.FavoriteSubscriptionBus\n', 1)
    names = [tokens[i+1][0] for tokens in [syntax.kotlin_tokens(old)]
             for i, token in enumerate(tokens[:-1]) if token[0] == 'fun' and 'Cover' in tokens[i+1][0]]
    need(bool(names) and all(declaration(old, 'fun', name, syntax) ==
         declaration(selected, 'fun', name, syntax) for name in names),
         'Root favorite cover request/admission methods changed')
    history = declaration(before, 'class', 'HistoryViewModel', syntax)
    need(history == declaration(body, 'class', 'HistoryViewModel', syntax),
         'Root history request/admission class changed')
    return body, dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256,
        completeOriginalMethods=reused, completeOriginalStateDeclarations=True,
        wholeOriginalBusObserver=True, originalPageActivationOwnsStartup=True,
        originalCoverAndHistoryBodiesPreserved=True,
        preservedRootCoverMethods=names, preservedRootHistorySHA256=sha(history),
        retainedToCurrent=inverse_proof(before, body))


def advance_repository(repo, before, drop_logs):
    sources, manifest = fixed(repo)
    need(sha(before) == manifest['beforeFinalOutputs']['data/repository/DesktopOriginalFavoriteRepository.kt']['sha256'],
         'Subscription repository predecessor changed')
    syntax = parser(repo)
    raw = declaration(sources['FavoriteRepository.kt'], 'fun', 'setFolderSubscription', syntax)
    body = raw.replace('TokenManager.csrfCache', 'environment.csrf()')
    body = body.replace('TokenManager.sessDataCache.isNullOrBlank()', '(environment.currentMid() ?: 0L) <= 0L')
    body = body.replace('favoriteApiFailure(', 'desktopFavoriteApiFailure(').replace('favoriteHttpFailure(', 'desktopFavoriteHttpFailure(')
    need('TokenManager.' not in body, 'Unbound subscription repository credentials')
    body = drop_logs(body)
    first, last = span(before, 'class', 'DesktopOriginalFavoriteRepository', syntax)
    selected = before[:last-1]+'\n'+body+'\n'+before[last-1:]
    return selected, dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256,
        originalMethodSHA256=sha(raw), completeOriginalMethod=True,
        platformBinding=inverse_proof(raw, body), retainedToCurrent=inverse_proof(before, selected))


def advance_policy(repo, before, remove_function):
    sources, manifest = fixed(repo)
    need(sha(before) == manifest['beforeFinalOutputs']['feature/list/FavoriteCollectionPolicy.kt']['sha256'],
         'Subscription policy predecessor changed')
    selected = remove_function(sources['FavoriteCollectionPolicy.kt'], 'isFavoriteRiskControlError')
    return selected, dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256,
        wholeOriginalPolicyExceptExistingSoleRiskFunction=True, retainedToCurrent=inverse_proof(before, selected))


def advance_api(repo, before):
    sources, _ = fixed(repo)
    syntax = parser(repo)
    original = declaration(sources['ApiClient.kt'], 'interface', 'BilibiliApi', syntax)
    methods = []
    for name in ['favoriteFolder', 'unfavoriteFolder']:
        marker = '    suspend fun '+name+'('
        need(original.count(marker) == 1, 'Ambiguous original subscription API')
        first = original.index(marker)
        tokens = syntax.kotlin_tokens(original[first:])
        opening = next(i for i, token in enumerate(tokens) if token[0] == '(')
        depth = 0
        closing = None
        for token in tokens[opening:]:
            depth += (token[0] == '(')-(token[0] == ')')
            if depth == 0:
                closing = first+token[2]
                break
        need(closing is not None, 'Unclosed original subscription API arguments')
        last = original.index('\n', closing)
        need(original[closing:last].strip() == ': SimpleApiResponse',
             'Original subscription API response type changed')
        annotations = original.rfind('    @retrofit2.http.FormUrlEncoded', 0, first)
        need(annotations >= 0 and original[annotations:last].count('suspend fun ') == 1,
             'Subscription API original annotation boundary changed')
        need('suspend fun '+name+'(' not in before, 'Duplicate subscription API producer')
        methods.append(original[annotations:last])
    first, last = span(before, 'interface', 'BilibiliApi', syntax)
    selected = before[:last-1]+'\n'+'\n\n'.join(methods)+'\n'+before[last-1:]
    return selected, dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256,
        completeOriginalRetrofitMethods=2, declarationsSHA256=sha('\n\n'.join(methods)),
        retainedToCurrent=inverse_proof(before, selected))


def additional_sources(repo):
    sources, manifest = fixed(repo)
    syntax = parser(repo)
    identity = declaration(sources['TokenManager.kt'], 'class', 'AccountSessionIdentity', syntax, True)
    need(identity == 'data class AccountSessionIdentity(val mid: Long? = null, val generation: Long = 0L)',
         'Original account identity declaration changed')
    for name, relative, body in [
        ('TokenManager.kt', 'core/store/DesktopFavoriteAccountIdentity',
         'package com.android.purebilibili.core.store\n\n'+identity+'\n'),
        ('FavoriteSubscriptionBus.kt', 'core/refresh/FavoriteSubscriptionBus', sources['FavoriteSubscriptionBus.kt']),
        ('WallpaperMediaCardSurface.kt', 'core/ui/components/WallpaperMediaCardSurface', material_source(repo)[0]),
    ]:
        row = next(row for row in manifest['files'] if row['filename'] == name)
        yield relative, body, dict(path=ARCHIVE+'/'+name, upstreamCommit=COMMIT,
            sha256LfUtf8=sha(sources[name]), rawSha256Bytes=row['sha256'], gitBlob=row['gitBlob'],
            v044FavoriteSubscriptionOriginal=True, sourceManifestSHA256=MANIFEST_SHA256)


def material_source(repo):
    original = fixed(repo)[0]['WallpaperMediaCardSurface.kt']
    body = original
    changes = [('import android.os.Build\n', ''),
               ('Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU',
                'com.bilipai.desktop.ui.desktopFavoriteWallpaperEffectsSupported()')]
    for before, after in changes:
        need(body.count(before) == 1, 'Original wallpaper material platform seam changed')
        body = body.replace(before, after, 1)
    return body, dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256,
        wholeOriginalMaterial=True, originalBlurRadiusAndAlphaUnchanged=True,
        existingRootWallpaperLayerBorrowed=True, rendererOrBackgroundSourceCreated=False,
        originalToWindows=inverse_proof(original, body))


def emit_original_policy_tests(repo, output):
    """Emit the entire pure original suite; Android ViewModel mocks stay reference-only."""
    validate_outputs(repo, output)
    original = fixed(repo)[0]['FavoriteFolderAggregationPolicyTest.kt']
    need(original.count('@Test') == 21, 'Original subscription policy suite changed')
    target = Path(output)/'com/android/purebilibili/feature/list/FavoriteFolderAggregationPolicyTest.kt'
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(original, encoding='utf8', newline='\n')
    return dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256,
                wholeOriginalSuite=True, originalCasesSelected=21, testsExecuted=False)


def composable_span(source, name, syntax):
    first, last = span(source, 'fun', name, syntax)
    annotation = '@Composable\n'
    need(source[:first].endswith(annotation), 'Original composable annotation changed: '+name)
    return first-len(annotation), last


def subscribed_call_span(source, syntax):
    marker = '                    FavoriteSubscribedFolderList(\n'
    need(source.count(marker) == 1, 'Subscription list caller changed')
    first = source.index(marker)
    tokens = syntax.kotlin_tokens(source[first:])
    opening = next(i for i, token in enumerate(tokens) if token[0] == '(')
    depth = 0
    for token in tokens[opening:]:
        depth += (token[0] == '(')-(token[0] == ')')
        if depth == 0:
            return first, first+token[2]
    raise ValueError('Unclosed subscription list caller')


def advance_ui(repo, before):
    sources, manifest = fixed(repo)
    need(sha(before) == manifest['beforeFinalOutputs']['feature/list/CommonListScreen.kt']['sha256'],
         'Subscription UI final predecessor changed')
    original = sources['CommonListScreen.kt']
    syntax = parser(repo)
    body = before
    reused = []
    for name in ['FavoriteSubscribedFolderList', 'FavoriteSubscribedFolderRow', 'FavoriteSubscribedFolderPreview']:
        first, last = composable_span(original, name, syntax)
        selected = original[first:last]
        a, b = composable_span(body, name, syntax)
        body = body[:a]+selected+body[b:]
        reused.append(dict(name=name, originalOffset=first, originalSHA256=sha(selected),
                           completeOriginalDeclaration=True))
    a, b = subscribed_call_span(original, syntax)
    first, last = subscribed_call_span(body, syntax)
    body = body[:first]+original[a:b]+body[last:]
    start = original.index('    val subscribedFolderActions by')
    end = original.index('    val selectedFolderIndex by', start)
    marker = '    val selectedFolderIndex by'
    need(body.count(marker) == 1, 'Subscription action state caller changed')
    body = body.replace(marker, original[start:end]+marker, 1)
    first = original.index('    DisposableEffect(favoriteViewModel, isCurrentPage) {')
    last = original.index('    val historyViewModel =', first)
    marker = '    val favoriteViewModel = viewModel as? FavoriteViewModel\n'
    need(body.count(marker) == 1, 'Favorite page activation caller changed')
    body = body.replace(marker, marker+original[first:last], 1)
    first = original.index('    LaunchedEffect(favoriteViewModel, isSubscribedBrowse, isCurrentPage, accountIdentity) {')
    last = original.index('    val loadMoreOwner =', first)
    effect = original[first:last].replace(', accountIdentity)', ')', 1)
    marker = '    val loadMoreOwner ='
    need(body.count(marker) == 1, 'Subscription initial load caller changed')
    body = body.replace(marker, effect+marker, 1)
    first = original.index('    val loadingChromeContent = when {')
    last = original.index('    val commonListChromeSource =', first)
    a = body.index('    val loadingChromeContent = when {')
    b = body.index('    val commonListChromeSource =', a)
    body = body[:a]+original[first:last]+body[b:]
    old = '                favoriteViewModel != null && isSearchDestination ->\n                    CommonListScrollState.Grid(primaryGridState)'
    new = old.replace('isSearchDestination ->', 'isSearchDestination && !isSubscribedBrowse ->')
    need(body.count(old) == 1 and new in original, 'Subscription scroll owner seam changed')
    body = body.replace(old, new, 1)
    old = '                    isSearchDestination &&\n                    favoriteSection == FavoriteSection.VIDEO &&'
    new = old.replace('                    favoriteSection', '                    !isSubscribedBrowse &&\n                    favoriteSection')
    need(body.count(old) == 1 and new in original, 'Subscription search destination seam changed')
    body = body.replace(old, new, 1)
    for line in ['import com.android.purebilibili.core.ui.components.wallpaperMediaCardSurface\n',
                 'import com.android.purebilibili.core.ui.components.PageAwareAsyncImage\n',
                 'import com.android.purebilibili.core.ui.rememberAppFolderIcon\n']:
        need(original.count(line) == 1, 'Original subscription UI import changed')
        if line not in body:
            marker = 'package com.android.purebilibili.feature.list\n'
            need(body.count(marker) == 1, 'Subscription UI package changed')
            body = body.replace(marker, marker+'\n'+line, 1)
    from v044_favorite_search import advance_common_list
    body, search = advance_common_list(repo, body, original)
    return body, dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256, originalSearchNavigation=search,
        completeOriginalComposables=reused, wholeOriginalListCall=True, originalActionStateCaller=True,
        accountEffectUsesRootRetiredViewModelIdentity=True, originalSearchAndScrollOwners=True,
        retainedToCurrent=inverse_proof(before, body))
