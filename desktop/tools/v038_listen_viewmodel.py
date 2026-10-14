"""Complete fixed upstream ListenVideo VM, mounted through its sole Root producer."""
from pathlib import Path
import hashlib
import json
import re

COMMIT = 'b69ce4c21bd1bc2f33c5a4f3076b571f9b6d0f14'
ARCHIVE = 'desktop/upstream-slices/v038-listen-viewmodel'
SOURCE = 'app/src/main/java/com/android/purebilibili/feature/audio/viewmodel/ListenVideoViewModel.kt'
SOURCE_SHA256 = 'aeda55b35e7c4480bcc871c58646a8fa4fc1ed2bfb0e80a5ff6a7af5ac88b829'
SOURCE_BLOB = 'b5947815ea07e659257e416c11bf7c34cbc93fea'
SOURCE_BYTES = 14897
MANIFEST_SHA256 = '5c66e0b2b71cc227de942603acabfba404b64c174f51d0d3fe4832b3c38c137b'


def sha(raw): return hashlib.sha256(raw).hexdigest()


def read(repo):
    root = repo / ARCHIVE
    manifest_raw = (root / 'manifest.json').read_bytes()
    if sha(manifest_raw) != MANIFEST_SHA256: raise ValueError('ListenVideo VM manifest changed')
    manifest = json.loads(manifest_raw)
    if manifest['commit'] != COMMIT or manifest['repository'] != 'jay3-yy/BiliPai':
        raise ValueError('ListenVideo VM source identity changed')
    raw = (root / 'ListenVideoViewModel.kt').read_bytes()
    blob = hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()
    if (sha(raw), blob, len(raw)) != (SOURCE_SHA256, SOURCE_BLOB, SOURCE_BYTES):
        raise ValueError('ListenVideo VM source bytes changed')
    row = manifest['files']
    if len(row) != 1 or row[0]['path'] != SOURCE or (row[0]['sha256'], row[0]['gitBlob'], row[0]['bytes']) != (SOURCE_SHA256, SOURCE_BLOB, SOURCE_BYTES):
        raise ValueError('ListenVideo VM source inventory changed')
    return raw.decode('utf-8').replace('\r\n', '\n')


def block_end(text, opening):
    """Scan fixed Kotlin brace blocks without treating comments/strings as structure."""
    if text[opening] != '{': raise ValueError('Expected Kotlin block')
    depth = 0
    i = opening
    while i < len(text):
        if text.startswith('//', i):
            end = text.find('\n', i+2)
            i = len(text) if end < 0 else end+1
            continue
        if text.startswith('/*', i):
            nesting = 1
            i += 2
            while nesting and i < len(text):
                if text.startswith('/*', i): nesting += 1; i += 2
                elif text.startswith('*/', i): nesting -= 1; i += 2
                else: i += 1
            if nesting: raise ValueError('Unclosed Kotlin comment')
            continue
        if text.startswith('"""', i):
            end = text.find('"""', i+3)
            if end < 0: raise ValueError('Unclosed Kotlin raw string')
            i = end+3
            continue
        if text[i] in ('"', "'", '`'):
            quote = text[i]; i += 1
            while i < len(text):
                if text[i] == '\\' and quote != '`': i += 2
                elif text[i] == quote: i += 1; break
                else: i += 1
            continue
        if text[i] == '{': depth += 1
        elif text[i] == '}':
            depth -= 1
            if depth == 0: return i+1
        i += 1
    raise ValueError('Unclosed fixed Kotlin block')


def instrument_launch(original, alias):
    # Every asynchronous publication captures the actual original launched Job.
    # Mapping/withContext remains outside the short Root publication admission.
    text = original
    assignments = list(re.finditer(
        r'(?m)^([ \t]*)(ownedFolders|collectedFolders|indexedResources|indexCompleted|isFoldersLoading) = ([^\n]+)', text))
    for match in reversed(assignments):
        indent, field, rhs = match.groups()
        start = match.start(); end = match.end()
        if '{' in rhs:
            opening = text.index('{', match.start(3), match.end())
            end = block_end(text, opening)
        before = text[start:end]
        if rhs.startswith('withContext('):
            temporary = 'desktopMappedResources'
            mapped = before.replace(field+' = ', 'val '+temporary+' = ', 1)
            after = mapped+'\n'+indent+alias+'.commit { '+field+' = '+temporary+' }'
        else:
            assignment = before[len(indent):]
            after = indent+alias+'.commit { '+assignment+' }'
        text = text[:start]+after+text[end:]
    text = text.replace('_uiState.update {', alias+'.updateState(_uiState) {')
    # The async refresh must still own its launched caller before starting work
    # in another original root-scoped job. Cancellation/launch stay outside gate.
    if alias == 'desktopRefreshCaller':
        before = 'if (_uiState.value.section != ListenVideoSection.PLAYLISTS) indexLibrary()'
        if text.count(before) != 1: raise ValueError('Original async index entry changed')
        text = text.replace(before, alias+'.check()\n                '+before, 1)
    return '\n            val '+alias+' = environment.request(currentCoroutineContext())\n            '+alias+'.check()'+text


def emit(repo, output):
    full = read(repo)
    body = full
    edits = []
    def replace(before, after, label):
        nonlocal body
        if body.count(before) != 1: raise ValueError('ListenVideo VM platform anchor changed: '+label)
        at = body.index(before)
        edits.append(dict(offset=at, before=before, after=after, label=label))
        body = body[:at]+after+body[at+len(before):]
    for line in ('import androidx.lifecycle.ViewModel\n', 'import androidx.lifecycle.viewModelScope\n',
                 'import com.android.purebilibili.core.store.TokenManager\n',
                 'import com.android.purebilibili.feature.audio.library.BilibiliListenVideoLibraryDataSource\n'):
        replace(line, '', 'remove Android lifecycle/global authority')
    replace('import kotlinx.coroutines.launch\n', 'import kotlinx.coroutines.launch\nimport kotlinx.coroutines.currentCoroutineContext\nimport com.bilipai.desktop.ui.DesktopOriginalListenVideoEnvironment\n', 'bind existing Root environment')
    constructor = 'internal class ListenVideoViewModel(\n    private val dataSource: ListenVideoLibraryDataSource = BilibiliListenVideoLibraryDataSource(),\n    private val currentMid: () -> Long = { TokenManager.midCache ?: 0L }\n) : ViewModel() {'
    replace(constructor, 'internal class ListenVideoViewModel(\n    private val environment: DesktopOriginalListenVideoEnvironment\n) {\n    private val dataSource: ListenVideoLibraryDataSource = environment.dataSource\n    private val currentMid: () -> Long = environment::currentMid\n    private val viewModelScope get() = environment.scope', 'same retained VM scope and Favorites source')
    for name, parameters in (
        ('refresh',''), ('pauseBackgroundLoading',''), ('resumeBackgroundLoading',''),
        ('selectSection','section: ListenVideoSection'), ('openPlaylist','playlist: ListenVideoPlaylist'),
        ('openAlbum','album: ListenVideoAlbum'), ('openArtist','artist: ListenVideoArtist'),
        ('closeDetail',''), ('retryDetail',''), ('retryFailedIndex',''),
    ):
        marker = '    fun '+name+'('+parameters+') {\n'
        replace(marker, marker+'        environment.assertOwned()\n', 'admit original '+name+' entry')
    marker = '    private fun indexLibrary(onlyFailed: Boolean = false) {\n'
    replace(marker, marker+'        environment.assertOwned()\n', 'admit original private index entry')
    marker = '    ) {\n        lastDetailTitle = title\n'
    replace(marker, '    ) {\n        environment.assertOwned()\n        lastDetailTitle = title\n', 'admit original detail entry')
    for job, alias in (('previewJob','desktopPreviewCaller'), ('refreshJob','desktopRefreshCaller'),
                       ('indexJob','desktopIndexCaller'), ('detailJob','desktopDetailCaller')):
        marker = job+' = viewModelScope.launch {'
        if body.count(marker) != 1: raise ValueError('Original launch changed: '+job)
        start = body.index(marker)
        opening = start+len(marker)-1
        end = block_end(body, opening)
        before = body[start:end]
        inner = body[opening+1:end-1]
        after = marker+instrument_launch(inner, alias)+'}'
        replace(before, after, 'capture actual launched '+job+' and admit its publications')
    guest = '            _uiState.value = ListenVideoUiState(\n                generation = generation,\n                isLoggedIn = false\n            )'
    replace(guest, guest.replace('_uiState.value = ', 'environment.setState(_uiState, ') + ')', 'original guest state publication')
    logged_in = '        _uiState.value = ListenVideoUiState(\n            generation = generation,\n            section = _uiState.value.section,\n            isLoggedIn = true,\n            isLoading = true\n        )'
    replace(logged_in, logged_in.replace('_uiState.value = ', 'environment.setState(_uiState, ') + ')', 'original refresh state publication')
    while '_uiState.update {' in body:
        before = '_uiState.update {'; at = body.index(before)
        after = 'environment.updateState(_uiState) {'
        edits.append(dict(offset=at,before=before,after=after,label='admit original synchronous state publication'))
        body = body[:at]+after+body[at+len(before):]
    if '_uiState.value =' in body: raise ValueError('Unowned VM state assignment')
    inverse = body
    for edit in reversed(edits):
        at = edit['offset']
        if inverse[at:at+len(edit['after'])] != edit['after']: raise ValueError('ListenVideo VM inverse seam changed')
        inverse = inverse[:at]+edit['before']+inverse[at+len(edit['after']):]
    if inverse != full: raise ValueError('Complete original ListenVideo VM inverse failed')
    target = output / 'com/android/purebilibili/feature/audio/viewmodel/ListenVideoViewModel.kt'
    target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text(body,encoding='utf-8',newline='\n')
    proof = dict(source=SOURCE,output='com/android/purebilibili/feature/audio/viewmodel/ListenVideoViewModel.kt',
                 upstreamCommit=COMMIT,fullOriginalSha256LF=sha(full.encode()),selectedOriginalSha256LF=sha(full.encode()),
                 adaptedSha256LF=sha(body.encode()),fullSelectedInverse=True,edits=edits,
                 actualOriginalLaunchesOwned=4,wholeOriginalClassRetained=True,existingRootEnvironmentUsed=True,
                 incrementalResourcesCallbackRetained=True,backgroundPauseResumeRetained=True,
                 loaderCompatibilityAdaptation=None,mainCompiled=False,collectionPlayerUiMounted=False)
    (output / 'v038-listen-viewmodel-source-proof.json').write_text(json.dumps(proof,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    return proof


def adapt_screen_lifecycle(body):
    """Map the existing physical Root page visibility to the original VM lifecycle."""
    original = body
    edits = []
    before = '''    LaunchedEffect(viewModel, page.isActive()) {
        if (page.isCurrentUi() && viewModel.uiState.value.generation == 0L) {
            viewModel.refresh()
        }
    }'''
    after = '''    LaunchedEffect(viewModel, page.isActive()) {
        currentCoroutineContext().ensureActive()
        if (!page.entry.owns()) return@LaunchedEffect
        if (page.isActive()) {
            page.runUi { viewModel.resumeBackgroundLoading() }
        } else {
            viewModel.pauseBackgroundLoading()
        }
    }
    DisposableEffect(viewModel) {
        onDispose {
            if (page.entry.owns()) {
                try { viewModel.pauseBackgroundLoading() }
                catch (_: CancellationException) { /* The same Root retired during cleanup. */ }
            }
        }
    }'''
    imports_before = 'import kotlinx.coroutines.launch\n'
    imports_after = imports_before + 'import kotlinx.coroutines.CancellationException\nimport kotlinx.coroutines.currentCoroutineContext\nimport kotlinx.coroutines.ensureActive\nimport androidx.compose.runtime.DisposableEffect\n'
    for before, after in ((imports_before, imports_after), (before, after)):
        if body.count(before) != 1: raise ValueError('Original Root Listen screen lifecycle anchor changed')
        at = body.index(before)
        edits.append(dict(offset=at,before=before,after=after,label='same physical Root page lifecycle'))
        body = body[:at]+after+body[at+len(before):]
    inverse = body
    for edit in reversed(edits):
        at = edit['offset']
        if inverse[at:at+len(edit['after'])] != edit['after']: raise ValueError('Listen screen lifecycle inverse changed')
        inverse = inverse[:at]+edit['before']+inverse[at+len(edit['after']):]
    if inverse != original: raise ValueError('Complete original Root screen inverse changed')
    return body, dict(edits=edits,completeInverse=True,originalInitialRefreshRetainedViaResume=True,
                      actualPhysicalPageVisibilityUsed=True,backgroundPauseOnDispose=True)
