"""Authenticated original native fans page on the existing Windows list owner."""
from pathlib import Path
import hashlib
import json
from v044_favorite_detail import inverse_proof

COMMIT = '070ab41608213cd981fd7e51df357295f9a537bf'
ARCHIVE = 'desktop/upstream-slices/v044-following-fans'
MANIFEST_SHA256 = 'bbe07a94f22f2bc56a1d65d89933cb01d7e7eb424db84b1cfd0912c943f06f47'
EXPECTED = {'FansListScreen.kt', 'FollowingListScreen.kt', 'FollowingGroupPolicy.kt'}

def sha(raw):
    return hashlib.sha256(raw.encode('utf8') if isinstance(raw, str) else raw).hexdigest()

def need(value, message):
    if not value:
        raise ValueError(message)

def fixed(repo):
    root = Path(repo)/ARCHIVE
    need(root.is_dir() and not root.is_symlink(), 'Missing or linked native fans originals')
    need({p.name for p in root.iterdir()} == EXPECTED | {'manifest.json'}, 'Native fans source leaves changed')
    raw = (root/'manifest.json').read_bytes()
    need(sha(raw) == MANIFEST_SHA256, 'Native fans manifest changed')
    manifest = json.loads(raw)
    need((manifest['repository'], manifest['tag'], manifest['commit'], manifest['hashNormalization']) ==
         ('jay3-yy/BiliPai', 'v0.4.4', COMMIT, 'raw'), 'Native fans original identity changed')
    need(len(manifest['files']) == len(EXPECTED) and {r['filename'] for r in manifest['files']} == EXPECTED,
         'Native fans source inventory changed')
    sources = {}
    for row in manifest['files']:
        file = root/row['filename']
        need(file.is_file() and not file.is_symlink() and Path(row['path']).name == file.name, 'Ambiguous native fans source')
        raw = file.read_bytes()
        need(len(raw) == row['bytes'] and sha(raw) == row['sha256'], 'Native fans source bytes changed')
        need(hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest() == row['gitBlob'], 'Native fans Git blob changed')
        sources[file.name] = raw.decode('utf8')
    return sources

def screen_source(repo):
    original = fixed(repo)['FansListScreen.kt']
    body = original
    def replace(before, after):
        nonlocal body
        need(body.count(before) == 1, 'Native fans platform seam changed: '+before)
        body = body.replace(before, after, 1)
    for line in ['import androidx.lifecycle.ViewModel\n', 'import androidx.lifecycle.viewModelScope\n',
                 'import androidx.lifecycle.viewmodel.compose.viewModel\n', 'import androidx.lifecycle.viewmodel.initializer\n',
                 'import androidx.lifecycle.viewmodel.viewModelFactory\n', 'import com.android.purebilibili.core.network.NetworkModule\n']:
        replace(line, '')
    replace('import androidx.lifecycle.compose.collectAsStateWithLifecycle\n',
            'import androidx.compose.runtime.collectAsState as collectAsStateWithLifecycle\n')
    replace('internal class FansListViewModel(private val mid: Long) : ViewModel() {',
            'internal class FansListViewModel(private val mid: Long, environment: DesktopFollowingEnvironment) : DesktopFollowingScopedOwner(environment) {')
    replace('    fun load(refresh: Boolean = false) {', '    fun load(refresh: Boolean = false) {\n        environment.assertOwned()')
    replace('NetworkModule.api.getFollowers(vmid = mid, pn = requestPage)',
            'environment.getFollowers(vmid = mid, pn = requestPage)')
    replace('fun FansListScreen(mid: Long, onBack: () -> Unit, onUserClick: (Long) -> Unit, onFansClick: (Long) -> Unit) {',
            'internal fun FansListScreen(mid: Long, onBack: () -> Unit, onUserClick: (Long) -> Unit, onFansClick: (Long) -> Unit, model: FansListViewModel, active: Boolean) {')
    replace('''    val model: FansListViewModel = viewModel(
        key = "fans_$mid",
        factory = viewModelFactory { initializer { FansListViewModel(mid) } },
    )
''', '')
    replace('    LaunchedEffect(model) { model.load() }',
            '    LaunchedEffect(model, active) { if (active) model.load() }')
    replace('    FansListContent(state, onBack, onUserClick, onFansClick, onRefresh = { model.load(true) }, onLoadMore = { model.load() })',
            '    FansListContent(state, onBack, onUserClick, onFansClick, onRefresh = { if (active) model.load(true) }, onLoadMore = { if (active) model.load() })')
    return body, dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256,
        wholeOriginalStateViewModelAndUiRetained=True, rootEntryOwnsViewModelAndScope=True,
        existingOwnedApiUsed=True, androidViewModelStoreCreated=False, followingCacheReusedForFans=False,
        originalToWindows=inverse_proof(original, body))

def validate_outputs(repo, *outputs):
    fixed(repo)
    archive = (Path(repo)/ARCHIVE).resolve()
    for output in outputs:
        if output is None:
            continue
        target = Path(output).resolve()
        need(not target.is_relative_to(archive) and not archive.is_relative_to(target), 'Native fans output overlaps originals')

def emit(repo, output):
    body, proof = screen_source(repo)
    target = Path(output)/'com/android/purebilibili/feature/following/FansListScreen.kt'
    archive = (Path(repo)/ARCHIVE).resolve()
    need(not target.resolve().is_relative_to(archive) and not archive.is_relative_to(target.resolve()), 'Native fans output overlaps originals')
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(body, encoding='utf8', newline='\n')
    return dict(path='app/src/main/java/com/android/purebilibili/feature/following/FansListScreen.kt', upstreamCommit=COMMIT,
        sha256LfUtf8=sha(fixed(repo)['FansListScreen.kt']), sourceManifestSHA256=MANIFEST_SHA256,
        output=dict(path='com/android/purebilibili/feature/following/FansListScreen.kt', mode='selected', sha256LfUtf8=sha(body)),
        nativeFansPlatformBinding=proof)

def append_api(repo, before):
    from v044_favorite_subscription import fixed as subscription_sources, parser, declaration, span
    original = subscription_sources(repo)[0]['ApiClient.kt']
    syntax = parser(repo)
    interface = declaration(original, 'interface', 'BilibiliApi', syntax)
    marker = '    @GET("x/relation/fans")'
    need(interface.count(marker) == 1 and 'suspend fun getFollowers(' not in before, 'Native fans API owner is missing or duplicated')
    first = interface.index(marker)
    last = interface.index('    ): FollowingsResponse', first)+len('    ): FollowingsResponse')
    method = interface[first:last]
    need(method.count('suspend fun ') == 1 and 'suspend fun getFollowers(' in method, 'Original native fans Retrofit method boundary changed')
    _, end = span(before, 'interface', 'BilibiliApi', syntax)
    body = before[:end-1]+'\n'+method+'\n'+before[end-1:]
    return body, dict(upstreamCommit=COMMIT, originalApiSourceSHA256=sha(original),
        wholeOriginalRetrofitMethod=True, originalMethodSHA256=sha(method), originalMethod=method,
        existingApiInterfaceAndOwnedTransportUsed=True, retainedToCurrent=inverse_proof(before, body))

def current_nav_key(repo, baseline, earlier_audio_key):
    from v044_favorite_search import fixed as navigation_sources
    from v038_listen_screen import audio_declaration
    current = navigation_sources(repo)['BiliPaiNavKey.kt']
    need(sha(baseline) == '1637952c78fccb2677ee73640b70ab876746d693fbca438365d285812fa76c85', 'Canonical Nav3 key predecessor changed')
    need(audio_declaration(current) == audio_declaration(earlier_audio_key), 'Existing complete collection audio key changed')
    need('val fans: Boolean = false' in current and 'if (fans) "fans" else "following"' in current,
         'Original typed fans identity changed')
    return current

def finish_mapping(repo, before):
    from v044_favorite_search import fixed as navigation_sources, between
    need(sha(before) == 'cf046ea4a22bf9bd7c3055eda106414e85035162257dc441d023339603c86c43', 'Current subscribed/audio mapping predecessor changed')
    original = navigation_sources(repo)['BiliPaiNavKeyMappingPolicy.kt']
    body = before
    selected = []
    for first, last in [
        ('        is BiliPaiNavKey.Following ->', '        is BiliPaiNavKey.UpowerRank ->'),
        ('        segments.firstOrNull() == "following" && segments.size >= 2 -> {', '        routeBase == "upower_rank" ->'),
    ]:
        old = between(body, first, last)
        new_first = first.replace('== "following"', 'in setOf("following", "fans")') if 'segments.firstOrNull()' in first else first
        new = between(original, new_first, last)
        body = body.replace(old, new, 1)
        selected.append(dict(originalOffset=original.index(new), originalSHA256=sha(new), originalText=new))
    need(body == original, 'The sole generated Nav3 mapping must equal the complete current original')
    return body, dict(upstreamCommit=COMMIT, fullCurrentOriginalMappingUnadapted=True,
        wholeOriginalMappingSHA256=sha(original), originalFansBranches=selected, existingFavoriteAndAudioRoutesPreserved=True,
        retainedToCurrent=inverse_proof(before, body))
