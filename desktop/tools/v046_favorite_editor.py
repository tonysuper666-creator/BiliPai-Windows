"""Original v0.4.6 favorite-folder metadata/editor on the existing Windows owners."""
from pathlib import Path
import hashlib, importlib.util, json, textwrap
from v044_favorite_detail import inverse_proof
from v044_favorite_subscription import parser, span, declaration, bind_vm_member, need

COMMIT = 'd6bf8b1655ae910999860bf13e65c8fcea5b345b'
ARCHIVE = 'desktop/upstream-slices/v046-favorite-editor'
MANIFEST_SHA256 = 'd735f17a1955b40ce306ac1c099331ad348d7d20f3e9d84ab8ff701b92c43107'
EXPECTED = {'ApiClient.kt', 'FavoriteModels.kt', 'FavoriteRepository.kt', 'ListViewModel.kt',
            'CommonListScreen.kt', 'FavoriteManagementPolicy.kt',
            'FavoriteManagementPolicyTest.kt', 'FavoriteModelsMappingTest.kt'}


def sha(value):
    return hashlib.sha256(value.encode('utf8') if isinstance(value, str) else value).hexdigest()


def fixed(repo):
    root = Path(repo)/ARCHIVE
    need(root.is_dir() and not root.is_symlink(), 'Missing original favorite editor inputs')
    need({p.name for p in root.iterdir()} == EXPECTED | {'manifest.json', 'LICENSE'}, 'Favorite editor input leaves changed')
    raw = (root/'manifest.json').read_bytes()
    need(sha(raw) == MANIFEST_SHA256, 'Favorite editor manifest changed')
    manifest = json.loads(raw)
    need((manifest['repository'], manifest['tag'], manifest['commit'], manifest['hashNormalization']) ==
         ('jay3-yy/BiliPai', 'v0.4.6', COMMIT, 'raw'), 'Favorite editor source identity changed')
    need(len(manifest['files']) == 8 and {r['filename'] for r in manifest['files']} == EXPECTED,
         'Favorite editor source set changed')
    sources = {}
    for row in manifest['files']:
        file = root/row['filename']
        need(file.is_file() and not file.is_symlink() and Path(row['path']).name == file.name,
             'Linked or ambiguous favorite editor source')
        raw = file.read_bytes()
        need(len(raw) == row['bytes'] and sha(raw) == row['sha256'], 'Favorite editor bytes changed: '+file.name)
        need(hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest() == row['gitBlob'],
             'Favorite editor Git blob changed: '+file.name)
        sources[file.name] = raw.decode('utf8')
    license = root/'LICENSE'
    need(not license.is_symlink() and sha(license.read_bytes()) == manifest['license']['sha256'],
         'Favorite editor license changed')
    return sources, manifest


def validate_outputs(repo, *outputs):
    fixed(repo)
    root = (Path(repo)/ARCHIVE).resolve()
    for output in outputs:
        if output is None:
            continue
        target = Path(output).resolve()
        need(not (target.is_relative_to(root) or root.is_relative_to(target)), 'Output overlaps original favorite editor inputs')


def restore(value, proof):
    need(sha(value) == proof['afterSHA256'], 'Favorite editor final output differs from its receipt')
    lines = value.splitlines(keepends=True)
    for hunk in reversed(proof['hunks']):
        first, last = hunk['afterLines']
        need(''.join(lines[first:last]) == hunk['after'], 'Favorite editor inverse span changed')
        lines[first:last] = hunk['before'].splitlines(keepends=True)
    result = ''.join(lines)
    need(sha(result) == proof['beforeSHA256'], 'Favorite editor predecessor inverse failed')
    return result


def _before(repo, relative, body):
    sources, manifest = fixed(repo)
    need(sha(body) == manifest['beforeFinalOutputs'][relative]['sha256'],
         'Favorite editor predecessor changed: '+relative)
    return sources, manifest


def _replace_once(body, old, new, label):
    need(body.count(old) == 1, 'Favorite editor anchor changed: '+label)
    return body.replace(old, new, 1)


def advance_api(repo, before):
    sources, _ = fixed(repo)
    syntax = parser(repo)
    spec = importlib.util.spec_from_file_location('favorite_editor_api_selection', Path(repo)/'desktop/tools/extract-upstream-api.py')
    owner = importlib.util.module_from_spec(spec); spec.loader.exec_module(owner)
    raw = owner.extract_method(declaration(sources['ApiClient.kt'], 'interface', 'BilibiliApi', syntax), 'getFavFolderInfo')
    need('suspend fun getFavFolderInfo(' not in before, 'Duplicate favorite metadata API')
    first, last = span(before, 'interface', 'BilibiliApi', syntax)
    body = before[:last-1]+'\n\n'+textwrap.indent(textwrap.dedent(raw), '    ')+'\n'+before[last-1:]
    return body, dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256,
        completeOriginalMethod=True, originalMethod=raw, originalSHA256=sha(raw),
        retainedToCurrent=inverse_proof(before, body))


def emit_response(repo, output):
    validate_outputs(repo, output)
    sources, manifest = fixed(repo); syntax = parser(repo)
    text = sources['FavoriteModels.kt']
    first, last = span(text, 'class', 'FavoriteInfoResponse', syntax, True)
    annotation = '@Serializable\n'
    need(text[:first].endswith(annotation), 'Original metadata response annotation changed')
    first -= len(annotation)
    raw = text[first:last]
    # All prior DTOs retain their sole v041 producer. Removing just this complete
    # new declaration recovers that entire installed original model byte-for-byte.
    old = text[:first]+text[last+2:]
    need(text[last:last+2] == '\n\n' and sha(old) == manifest['beforeFinalOutputs']['data/model/response/FavoriteModels.kt']['sha256'],
         'Unreviewed model changes beyond the single new original response')
    from v041_video_status_labels import emit_models, package_path, FAVORITE_MODELS
    installed = emit_models(repo)[package_path(FAVORITE_MODELS)][0]
    need(installed.decode('utf8') == old, 'Favorite DTO sole producer has changed')
    target = Path(output)/'com/android/purebilibili/data/model/response/FavoriteInfoResponse.kt'
    target.parent.mkdir(parents=True, exist_ok=True)
    body = 'package com.android.purebilibili.data.model.response\n\nimport kotlinx.serialization.Serializable\n\n'+raw+'\n'
    target.write_text(body, encoding='utf8', newline='\n')
    return dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256,
        completeOriginalDeclaration=True, originalSHA256=sha(raw), installedModelsUnchanged=True,
        wholeOriginalModelAfterSingleDeclarationRemoval=True, soleExistingModelProducer='v041_video_status_labels.py')


def advance_repository(repo, before):
    sources, _ = _before(repo, 'data/repository/DesktopOriginalFavoriteRepository.kt', before)
    syntax = parser(repo)
    raw = declaration(sources['FavoriteRepository.kt'], 'fun', 'getFolderInfo', syntax)
    selected = raw.replace('favoriteApiFailure(', 'desktopFavoriteApiFailure(')
    first, last = span(before, 'class', 'DesktopOriginalFavoriteRepository', syntax)
    body = before[:last-1]+'\n'+selected+'\n'+before[last-1:]
    return body, dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256,
        completeOriginalMethods=[dict(name='getFolderInfo', originalSHA256=sha(raw), platformBinding=inverse_proof(raw, selected))],
        retainedToCurrent=inverse_proof(before, body))


def advance_vm(repo, before):
    sources, _ = _before(repo, 'feature/list/DesktopOriginalListViewModels.kt', before)
    syntax = parser(repo); original = sources['ListViewModel.kt']; reused = []
    selected = {}
    for name in ['loadFavoriteFolderForEditing', 'editFavoriteFolder']:
        raw = declaration(original, 'fun', name, syntax)
        body = bind_vm_member(raw)
        if name == 'loadFavoriteFolderForEditing':
            body = _replace_once(body, '        val identity = environment.favoriteAccountIdentity()',
                '        if (!isFavoriteEditorOwned() || !isPageActive) throw CancellationException("收藏页已退出")\n        val identity = environment.favoriteAccountIdentity()', 'metadata initial page admission')
            body = _replace_once(body, '        return result',
                '        if (!isFavoriteEditorOwned() || !isPageActive) throw CancellationException("收藏页已退出")\n        return result', 'metadata completion page admission')
        else:
            body = _replace_once(body, '        if (_isFavoriteManagingState.value || title.isBlank()) return',
                '        if (!isFavoriteEditorOwned() || !isPageActive || _isFavoriteManagingState.value || title.isBlank()) return', 'editor initial page admission')
            body = _replace_once(body, '        viewModelScope.launch {',
                '        viewModelScope.launch {\n            if (!isFavoriteEditorOwned() || !isPageActive) { _isFavoriteManagingState.value = false; return@launch }', 'queued editor page admission')
        reused.append(dict(name=name, originalSHA256=sha(raw), platformBinding=inverse_proof(raw, body)))
        selected[name] = body
    first, last = span(before, 'fun', 'editSelectedFavoriteFolder', syntax)
    body = before[:first]+selected['loadFavoriteFolderForEditing']+'\n\n'+selected['editFavoriteFolder']+before[last:]
    first, last = span(body, 'class', 'FavoriteViewModel', syntax)
    port = '\n    /** Read-only Root/account lifetime; the real requests additionally require the original active page. */\n    internal fun isFavoriteEditorOwned(): Boolean = environment.isOwned()\n'
    body = body[:last-1]+port+body[last-1:]
    need('editSelectedFavoriteFolder(' not in body, 'Old selection-dependent editor survived')
    return body, dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256,
        completeOriginalMethods=reused, currentRootAccountAndScopeReused=True,
        noSecondQueueOrStore=True, retainedToCurrent=inverse_proof(before, body))


def _action_span(source, label, syntax):
    marker = 'label = "'+label+'"'
    need(source.count(marker) == 1, 'Favorite editor action label changed: '+label)
    at = source.index(marker); start = source.rfind('AppWindowAction(', 0, at)
    need(start >= 0, 'Favorite editor action constructor missing')
    first = source.rfind('\n', 0, start)+1
    tokens = syntax.kotlin_tokens(source[start:]); depth = 0; opened = False
    for word, _, end in tokens:
        opened = opened or word == '('
        depth += (word == '(')-(word == ')')
        if opened and depth == 0:
            return first, start+end
    raise ValueError('Unclosed favorite editor action')


def _guard_lambda(body, marker, prefix, syntax):
    need(body.count(marker) == 1, 'Favorite editor callback changed: '+marker)
    opening = body.index('{', body.index(marker)); depth = 0
    for word, start, end in syntax.kotlin_tokens(body[opening:]):
        depth += (word == '{')-(word == '}')
        if depth == 0:
            close = opening+start
            line = body.rfind('\n', 0, opening)+1
            indent = body[line:opening].split('onClick', 1)[0]
            head = textwrap.dedent(prefix).strip('\n')
            inner = textwrap.dedent(body[opening+1:close]).strip('\n')
            guarded = '\n'+textwrap.indent(head, indent+'    ')+'\n'+textwrap.indent(inner, indent+'        ')
            return body[:opening+1]+guarded+'\n'+indent+'    }\n'+indent+body[close:]
    raise ValueError('Unclosed editor callback')


def advance_ui(repo, before):
    sources, _ = _before(repo, 'feature/list/CommonListScreen.kt', before)
    original = sources['CommonListScreen.kt']; syntax = parser(repo); body = before
    if 'import kotlinx.coroutines.ensureActive\n' not in body:
        body = _replace_once(body, 'import kotlinx.coroutines.Job\n',
            'import kotlinx.coroutines.Job\nimport kotlinx.coroutines.ensureActive\n', 'current coroutine cancellation check')
    start = '    var favoriteFolderEditorMode by'; end = '    var showFavoriteFolderDeleteConfirm by'
    a, b = body.index(start), body.index(end); x, y = original.index(start), original.index(end)
    fields = original[x:y].replace('remember { mutableIntStateOf(0) }', 'remember { androidx.compose.runtime.mutableIntStateOf(0) }')
    fields += '    var favoriteFolderEditorStamp by remember { mutableStateOf(Any()) }\n'
    fields += '    val favoriteEditorForeground by androidx.compose.runtime.rememberUpdatedState(isCurrentPage)\n'
    fields += '    val favoriteEditorOwner by androidx.compose.runtime.rememberUpdatedState(favoriteViewModel)\n'
    body = body[:a]+fields+body[b:]
    # Current upstream no longer lets asynchronous empty catalog loads overwrite
    # the section explicitly selected by the user or navigation route.
    start = '    LaunchedEffect(foldersState.size, subscribedFoldersState.size) {'
    a = body.index(start); b = body.index('    val isSubscribedBrowse =', a)
    body = body[:a]+'    // Entry and user selection own the browse section, including while catalogs load.\n'+body[b:]
    for label in ['新建收藏夹', '编辑收藏夹']:
        a, b = _action_span(body, label, syntax); x, y = _action_span(original, label, syntax)
        raw = original[x:y]
        guarded = _guard_lambda(raw, 'onClick = {', '\n                        if (favoriteEditorForeground && favoriteEditorOwner === favoriteViewModel && favoriteViewModel?.isFavoriteEditorOwned() == true) {\n                            favoriteFolderEditorStamp = Any()\n', syntax)
        body = body[:a]+guarded+body[b:]
    for old, new in [('selectedFavoriteFolder?.attr == 0 && !isFavoriteManaging',
                      'selectedFavoriteFolder?.let { !isFavoriteFolderPrivate(it.attr) } == true && !isFavoriteManaging')]:
        need(body.count(old) == original.count(new) == 2, 'Favorite privacy caller count changed')
        body = body.replace(old, new)
    start = '    favoriteFolderEditorMode?.let { mode ->'; end = '\n    if (showFavoriteFolderDeleteConfirm'
    a, b = body.index(start), body.index(end, body.index(start)); x, y = original.index(start), original.index(end, original.index(start))
    raw = original[x:y]; dialog = raw
    dialog = _replace_once(dialog, start, '''    favoriteFolderEditorMode?.takeIf { isCurrentPage }?.let { mode ->
        val editorStamp = favoriteFolderEditorStamp
        val editorViewModel = favoriteViewModel
        fun editorCurrent(): Boolean = favoriteEditorForeground && favoriteEditorOwner === editorViewModel &&
            editorViewModel?.isFavoriteEditorOwned() == true && favoriteFolderEditorStamp === editorStamp &&
            favoriteFolderEditorMode == mode
'''.rstrip(), 'actual editor owner binding')
    dialog = dialog.replace('favoriteViewModel?', 'editorViewModel?')
    dialog = _replace_once(dialog, 'favoriteFolderEditorRetry, accountIdentity)',
        'favoriteFolderEditorRetry, editorViewModel, editorStamp, isCurrentPage)', 'effect source keys')
    dialog = _replace_once(dialog, '            if (mode != "edit" || favoriteFolderEditorLoaded) return@LaunchedEffect',
        '''            if (mode != "edit" || favoriteFolderEditorLoaded || !editorCurrent()) return@LaunchedEffect
            val expectedMediaId = favoriteFolderEditorMediaId
            val expectedRetry = favoriteFolderEditorRetry''', 'metadata capture')
    dialog = _replace_once(dialog, '            val info = result?.getOrNull()',
        '''            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (!editorCurrent() || favoriteFolderEditorMediaId != expectedMediaId ||
                favoriteFolderEditorRetry != expectedRetry) return@LaunchedEffect
            val info = result?.getOrNull()''', 'late metadata admission')
    dialog = _replace_once(dialog, 'loadFavoriteFolderForEditing(favoriteFolderEditorMediaId)',
        'loadFavoriteFolderForEditing(expectedMediaId)', 'captured metadata subject')
    dialog = dialog.replace('onDismissRequest = { favoriteFolderEditorMode = null }',
        'onDismissRequest = { if (editorCurrent()) { favoriteFolderEditorMode = null; favoriteFolderEditorStamp = Any() } }')
    dialog = dialog.replace('AppTextButton(onClick = { favoriteFolderEditorRetry++ })',
        'AppTextButton(onClick = { if (editorCurrent()) favoriteFolderEditorRetry++ })')
    for field in ['Title', 'Intro']:
        dialog = dialog.replace('onValueChange = { favoriteFolderEditor'+field+' = it }',
            'onValueChange = { if (editorCurrent()) favoriteFolderEditor'+field+' = it }')
    dialog = dialog.replace('onCheckedChange = { favoriteFolderEditorPrivate = it }',
        'onCheckedChange = { if (editorCurrent()) favoriteFolderEditorPrivate = it }')
    dialog = dialog.replace('enabled = favoriteFolderEditorLoaded && favoriteFolderEditorTitle.isNotBlank() && isFavoriteManaging.not(),',
        'enabled = editorCurrent() && favoriteFolderEditorLoaded && favoriteFolderEditorTitle.isNotBlank() && isFavoriteManaging.not(),')
    marker = '                    onClick = {\n                        if (mode == "create")'
    dialog = _replace_once(dialog, marker,
        '                    onClick = {\n                        if (!editorCurrent() || !favoriteFolderEditorLoaded || isFavoriteManaging) return@AppTextButton\n                        if (mode == "create")', 'save admission')
    dialog = _replace_once(dialog, '                        favoriteFolderEditorMode = null\n',
        '                        favoriteFolderEditorMode = null\n                        favoriteFolderEditorStamp = Any()\n', 'consume save identity')
    dialog = dialog.replace('AppTextButton(onClick = { favoriteFolderEditorMode = null })',
        'AppTextButton(onClick = { if (editorCurrent()) { favoriteFolderEditorMode = null; favoriteFolderEditorStamp = Any() } })')
    body = body[:a]+dialog+body[b:]
    return body, dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256,
        completeOriginalEditorDialog=True, originalDialogSHA256=sha(raw),
        originalToWindows=inverse_proof(raw, dialog),
        originalMetadataAndCapturedMediaId=True, foregroundAndOpaqueEditorStamp=True,
        currentRootIdentityAndScopeReused=True, canonicalAccountSingletonAdded=False,
        retainedToCurrent=inverse_proof(before, body))


def emit_policy(repo, output):
    validate_outputs(repo, output)
    body = fixed(repo)[0]['FavoriteManagementPolicy.kt']
    file = Path(output)/'com/android/purebilibili/feature/list/FavoriteManagementPolicy.kt'
    file.parent.mkdir(parents=True, exist_ok=True); file.write_text(body, encoding='utf8', newline='\n')


def policy_source(repo):
    sources, manifest = fixed(repo); name = 'FavoriteManagementPolicy.kt'
    row = next(row for row in manifest['files'] if row['filename'] == name)
    return sources[name], dict(path=ARCHIVE+'/'+name, upstreamCommit=COMMIT,
        sha256LfUtf8=sha(sources[name]), rawSha256Bytes=row['sha256'], gitBlob=row['gitBlob'],
        v046FavoriteEditorOriginal=True, sourceManifestSHA256=MANIFEST_SHA256)


def emit_tests(repo, output):
    validate_outputs(repo, output)
    sources, _ = fixed(repo)
    for name, package, count in [('FavoriteManagementPolicyTest.kt', 'feature/list', 6),
                                  ('FavoriteModelsMappingTest.kt', 'data/model/response', 7)]:
        raw = sources[name]; body = raw
        for a, b in [('import org.junit.Assert.assertEquals', 'import kotlin.test.assertEquals'),
                     ('import org.junit.Assert.assertTrue', 'import kotlin.test.assertTrue'),
                     ('import org.junit.Test', 'import kotlin.test.Test')]:
            body = body.replace(a, b)
        need(raw.count('@Test') == count, 'Original favorite editor test count changed')
        file = Path(output)/'com/android/purebilibili'/package/name
        file.parent.mkdir(parents=True, exist_ok=True); file.write_text(body, encoding='utf8', newline='\n')
