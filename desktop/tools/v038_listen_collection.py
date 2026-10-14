"""Whole fixed upstream collection pagination, using the existing Root source/queue."""
from pathlib import Path
import hashlib
import json
from v038_listen_viewmodel import block_end

COMMIT = 'b69ce4c21bd1bc2f33c5a4f3076b571f9b6d0f14'
ARCHIVE = 'desktop/upstream-slices/v038-listen-collection'
SOURCE = 'app/src/main/java/com/android/purebilibili/feature/audio/viewmodel/ListenVideoCollectionViewModel.kt'
SOURCE_SHA256 = 'cfa6458c4778567b219ac6b49098c771c922b24f4ffae35aa3936a9876dadefb'
SOURCE_BLOB = '834696431f4a8bb912f4d5774e716808ec4eb9de'
SOURCE_BYTES = 4860
MANIFEST_SHA256 = '58cbb752b6ff1e6514cc8be5f45363da037f550016621115223f93f3285a6a56'


def sha(raw): return hashlib.sha256(raw).hexdigest()


def read(repo):
    root = repo / ARCHIVE
    manifest_raw = (root / 'manifest.json').read_bytes()
    if sha(manifest_raw) != MANIFEST_SHA256:
        raise ValueError('Listen collection manifest changed')
    manifest = json.loads(manifest_raw)
    if manifest['commit'] != COMMIT or manifest['repository'] != 'jay3-yy/BiliPai':
        raise ValueError('Listen collection source identity changed')
    raw = (root / 'ListenVideoCollectionViewModel.kt').read_bytes()
    blob = hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()
    if (sha(raw), blob, len(raw)) != (SOURCE_SHA256, SOURCE_BLOB, SOURCE_BYTES):
        raise ValueError('Listen collection source bytes changed')
    rows = manifest['files']
    if len(rows) != 1 or rows[0]['path'] != SOURCE or (
        rows[0]['sha256'], rows[0]['gitBlob'], rows[0]['bytes']
    ) != (SOURCE_SHA256, SOURCE_BLOB, SOURCE_BYTES):
        raise ValueError('Listen collection source inventory changed')
    return raw.decode('utf-8').replace('\r\n', '\n')


def adapt(full):
    body = full
    edits = []

    def replace(before, after, label):
        nonlocal body
        if body.count(before) != 1:
            raise ValueError('Listen collection platform anchor changed: ' + label)
        at = body.index(before)
        edits.append(dict(offset=at, before=before, after=after, label=label))
        body = body[:at] + after + body[at + len(before):]

    for line in ('import androidx.lifecycle.ViewModel\n', 'import androidx.lifecycle.viewModelScope\n',
                 'import com.android.purebilibili.feature.audio.library.BilibiliListenVideoLibraryDataSource\n',
                 'import com.android.purebilibili.feature.video.player.PlaylistManager\n'):
        replace(line, '', 'remove Android/global authority: ' + line.strip())
    replace('import kotlinx.coroutines.launch\n',
            'import kotlinx.coroutines.launch\nimport kotlinx.coroutines.currentCoroutineContext\n'
            'import com.bilipai.desktop.ui.DesktopOriginalListenVideoCollectionEnvironment\n',
            'existing Root destination environment')
    before = '''internal class ListenVideoCollectionViewModel(
    source: ListenVideoLibraryDataSource = BilibiliListenVideoLibraryDataSource(),
) : ViewModel() {
    private val loader = ListenVideoLibraryLoader(source)'''
    after = '''internal class ListenVideoCollectionViewModel(
    private val environment: DesktopOriginalListenVideoCollectionEnvironment,
) {
    private val viewModelScope get() = environment.scope
    private val loader = ListenVideoLibraryLoader(environment.dataSource)'''
    replace(before, after, 'same original Loader and existing Favorites source')
    for signature in ('fun open(mediaId: Long, seasonId: Long)', 'fun retry()', 'private fun load()'):
        replace('    ' + signature + ' {\n', '    ' + signature + ' {\n        environment.assertOwned()\n',
                'admit original entry ' + signature)
    replace('''        request = nextRequest
        session = null
        _state.value = ListenVideoCollectionState()''',
            '''        environment.commit {
            request = nextRequest
            session = null
            _state.value = ListenVideoCollectionState()
        }''', 'publish original new collection state in one admission')
    marker = 'loadJob = viewModelScope.launch {'
    if body.count(marker) != 1: raise ValueError('Original collection load Job changed')
    start = body.index(marker)
    opening = start + len(marker) - 1
    end = block_end(body, opening)
    original_launch = body[start:end]
    inner = body[opening + 1:end - 1]
    inner = inner.replace('_state.update {', 'desktopCaller.updateState(_state) {')
    if inner.count('val activeSession = session') != 1:
        raise ValueError('Original collection session read changed')
    inner = inner.replace('val activeSession = session', 'val activeSession = desktopCaller.read { session }')
    before = '''                            session = PlaylistManager.setExternalPlaylist(
                                selection.items, selection.startIndex, ExternalPlaylistSource.FAVORITE,
                            )'''
    after = '''                            val admittedSession = desktopCaller.setExternalPlaylist(
                                selection.items, selection.startIndex, ExternalPlaylistSource.FAVORITE,
                            )
                            desktopCaller.commit { session = admittedSession }'''
    if inner.count(before) != 1: raise ValueError('Original collection initial queue changed')
    inner = inner.replace(before, after)
    before = 'PlaylistManager.addAllToPlaylistIfCurrent(selection.items, activeSession)'
    if inner.count(before) != 1: raise ValueError('Original collection append changed')
    inner = inner.replace(before, 'desktopCaller.appendPlaylist(selection.items, activeSession)')
    after = marker + '\n            val desktopCaller = environment.request(currentCoroutineContext())\n' + \
        '            desktopCaller.check()' + inner + '}'
    replace(original_launch, after, 'actual original load Job owns state and queue publication')
    replace('        _state.update { it.copy(isLoading = true, error = null) }',
            '        environment.updateState(_state) { it.copy(isLoading = true, error = null) }',
            'original synchronous retry state admission')
    inverse = body
    for edit in reversed(edits):
        at = edit['offset']
        if inverse[at:at + len(edit['after'])] != edit['after']:
            raise ValueError('Listen collection inverse seam changed')
        inverse = inverse[:at] + edit['before'] + inverse[at + len(edit['after']):]
    if inverse != full: raise ValueError('Complete original collection VM inverse failed')
    return body, edits


def emit(repo, output):
    full = read(repo)
    body, edits = adapt(full)
    target = output / 'com/android/purebilibili/feature/audio/viewmodel/ListenVideoCollectionViewModel.kt'
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(body, encoding='utf-8', newline='\n')
    proof = dict(source=SOURCE, output=target.relative_to(output).as_posix(), upstreamCommit=COMMIT,
        fullOriginalSha256LF=sha(full.encode()), selectedOriginalSha256LF=sha(full.encode()),
        adaptedSha256LF=sha(body.encode()), fullSelectedInverse=True, edits=edits,
        wholeOriginalCollectionVmRetained=True, existingFavoritesSourceAndRootPlaylistUsed=True,
        originalCumulativePageCallbackRetained=True, originalSessionConditionalAppendRetained=True,
        actualDestinationAndLoadCallerAdmission=True, originalPartialFailureRetryRetained=True,
        collectionPlayerUiMounted=False, mainCompiled=False, runtimeExecuted=False)
    (output / 'v038-listen-collection-source-proof.json').write_text(
        json.dumps(proof, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    return proof
