"""Fixed original Listen UI adapters; actual collection route mounting is separate."""
from pathlib import Path
import hashlib
import json
from v025_source_paths import canonical_source
import v044_favorite_search as favorite_search
from v038_listen_viewmodel import adapt_screen_lifecycle, block_end

COMMIT = 'b69ce4c21bd1bc2f33c5a4f3076b571f9b6d0f14'
ARCHIVE = 'desktop/upstream-slices/v038-listen-screen'
MANIFEST_SHA256 = '687f26307fe1e04ff051c12e11b8466ef06a92d7763fccbcb6a462af732793a1'
BASE_RECIPE_SHA256 = '6ec77b206dc4620d9d30c2ca36ad5a50cf4091c5eee65bcad244608dcc18d443'
NAV_PATH = 'app/src/main/java/com/android/purebilibili/navigation3/BiliPaiNavKey.kt'
BASE_NAV_SHA256_LF = '1637952c78fccb2677ee73640b70ab876746d693fbca438365d285812fa76c85'
PINS = {
    'BiliPaiNavKey.kt': (NAV_PATH, '67d8c9c33f503bfdf09714ec9dee239c00976765d510fe8b37145d0c888cca9e',
        'd97203ddd91865d74fcc4a8361ca7125779d37d6', 14337),
    'ListenVideoCollectionPlayer.kt': ('app/src/main/java/com/android/purebilibili/feature/audio/screen/ListenVideoCollectionPlayer.kt',
        'e5926d337f393201a179f7c5e1ee95b06392e5b08b71b547ad5c3bfcf5179033', '1b2e35d55d16ccb4ecce476d7f0cba00d1a57380', 3030),
    'ListenVideoScreen.kt': ('app/src/main/java/com/android/purebilibili/feature/audio/screen/ListenVideoScreen.kt',
        'd7488b4d58407fe36c57d06deec3b4797b3707211101a3b8f966b39bfe0ea3a4', '9da3d48ede131bf394c0f4530275c1ac34ca7935', 33733),
}


def sha(raw): return hashlib.sha256(raw).hexdigest()


def fixed_sources(repo):
    root = repo / ARCHIVE
    raw = (root / 'manifest.json').read_bytes()
    if sha(raw) != MANIFEST_SHA256: raise ValueError('Listen UI manifest changed')
    manifest = json.loads(raw)
    if manifest['repository'] != 'jay3-yy/BiliPai' or manifest['commit'] != COMMIT:
        raise ValueError('Listen UI source identity changed')
    rows = {row['archiveFile']: row for row in manifest['files']}
    if len(manifest['files']) != len(PINS) or set(rows) != set(PINS):
        raise ValueError('Listen UI source inventory changed')
    result = {}
    for name, (path, digest, blob, size) in PINS.items():
        raw = (root / name).read_bytes()
        actual_blob = hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()
        row = rows[name]
        if (sha(raw), actual_blob, len(raw)) != (digest, blob, size) or (
            row['path'], row['sha256'], row['gitBlob'], row['bytes']) != (path, digest, blob, size):
            raise ValueError('Listen UI source bytes changed: ' + name)
        result[name] = raw.decode('utf-8').replace('\r\n', '\n')
    return result


class Adaptation:
    def __init__(self, original):
        self.original = original
        self.body = original
        self.edits = []

    def replace(self, before, after, label, expected=1):
        if self.body.count(before) != expected:
            raise ValueError('Listen UI platform anchor changed: ' + label)
        at = self.body.index(before)
        self.edits.append(dict(offset=at, before=before, after=after, label=label))
        self.body = self.body[:at] + after + self.body[at + len(before):]

    def finish(self):
        inverse = self.body
        for edit in reversed(self.edits):
            at = edit['offset']
            if inverse[at:at + len(edit['after'])] != edit['after']:
                raise ValueError('Listen UI inverse seam changed')
            inverse = inverse[:at] + edit['before'] + inverse[at + len(edit['after']):]
        if inverse != self.original: raise ValueError('Complete original Listen UI inverse failed')
        return self.body, self.edits


def adapt_screen(original, base_recipe):
    recipe_digest = sha(json.dumps(base_recipe, ensure_ascii=False, sort_keys=True,
        separators=(',', ':')).encode())
    if recipe_digest != BASE_RECIPE_SHA256:
        raise ValueError('Existing sole Listen screen platform recipe changed')
    adaptation = Adaptation(original)
    replaced_upstream_anchors = {
        'only actually active physical MainHost page initially launches the original refresh',
        'actual composition parent/active physical page rejects old onPlaylistSelected',
        'actual composition parent/active physical page rejects old onAlbumSelected',
        'actual composition parent/active physical page rejects old onArtistSelected',
    }
    missing = set()
    for edit in base_recipe['edits']:
        count = adaptation.body.count(edit['before'])
        if count == 0 and edit['label'] in replaced_upstream_anchors:
            missing.add(edit['label'])
        else:
            adaptation.replace(edit['before'], edit['after'], edit['label'])
    if missing != replaced_upstream_anchors:
        raise ValueError('Latest original Listen screen lifecycle/callback shape changed')
    latest_lifecycle = '''    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        viewModel.resumeBackgroundLoading()
    }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_PAUSE) {
        viewModel.pauseBackgroundLoading()
    }'''
    existing_root_lifecycle = '''    LaunchedEffect(viewModel, page.isActive()) {
        if (page.isCurrentUi() && viewModel.uiState.value.generation == 0L) {
            viewModel.refresh()
        }
    }'''
    adaptation.replace(latest_lifecycle, existing_root_lifecycle,
        'map original resume/pause through the existing physical Root page adapter')
    for name, argument in (('onPlaylistSelected', 'playlist'), ('onAlbumSelected', 'album'), ('onArtistSelected', 'artist')):
        marker = name + ' = { ' + argument + ' ->'
        if adaptation.body.count(marker) != 1: raise ValueError('Original collection callback changed')
        at = adaptation.body.index(marker)
        opening = at + marker.index('{')
        end = block_end(adaptation.body, opening)
        before = adaptation.body[at:end]
        after = marker + ' page.runUi {' + adaptation.body[at + len(marker):end - 1] + '} }'
        adaptation.replace(before, after, 'retain complete original ' + name + ' under actual UI caller admission')
    body, lifecycle = adapt_screen_lifecycle(adaptation.body)
    adaptation.body = body
    adaptation.edits.extend(lifecycle['edits'])
    return adaptation.finish()


def audio_declaration(source):
    marker = '    @Serializable\n    data class AudioMode('
    if source.count(marker) != 1: raise ValueError('Original AudioMode declaration changed')
    start = source.index(marker)
    opening = source.index('{', start)
    return source[start:block_end(source, opening)]


def adapt_navigation(repo, latest):
    baseline = canonical_source(repo, NAV_PATH).read_text(encoding='utf-8').replace('\r\n', '\n')
    if sha(baseline.encode()) != BASE_NAV_SHA256_LF:
        raise ValueError('Canonical baseline navigation source changed')
    adaptation = Adaptation(baseline)
    from v044_fans import current_nav_key
    adaptation.replace(baseline, current_nav_key(repo, baseline, latest),
        'complete current pure original Nav3 key including collection audio, subscribed search and fans identity')
    body, edits = adaptation.finish()
    return body, edits, baseline


def adapt_player(original):
    adaptation = Adaptation(original)
    adaptation.replace('import androidx.lifecycle.viewmodel.compose.viewModel\n', '', 'existing destination owns the VM')
    adaptation.replace('    viewModel: ListenVideoCollectionViewModel = viewModel(),',
        '    viewModel: ListenVideoCollectionViewModel,', 'require actual retained collection VM')
    adaptation.replace('    onBack: () -> Unit,\n',
        '    onBack: () -> Unit,\n    onRetry: () -> Unit,\n    active: Boolean,\n',
        'require actual Root guarded retry and physical page visibility')
    adaptation.replace('LaunchedEffect(mediaId, seasonId) { viewModel.open(mediaId, seasonId) }',
        'LaunchedEffect(mediaId, seasonId, active) { if (active) viewModel.open(mediaId, seasonId) }',
        'only actual active destination starts the original retained pagination')
    for remaining in (2, 1):
        adaptation.replace('onClick = viewModel::retry', 'onClick = onRetry',
            'actual Root guarded retry callback', expected=remaining)
    return adaptation.finish()


def emit(repo, output, base_recipe):
    from v044_fans import validate_outputs
    validate_outputs(repo, output)
    sources = fixed_sources(repo)
    screen, screen_edits = adapt_screen(sources['ListenVideoScreen.kt'], base_recipe)
    nav, nav_edits, nav_baseline = adapt_navigation(repo, sources['BiliPaiNavKey.kt'])
    player, player_edits = adapt_player(sources['ListenVideoCollectionPlayer.kt'])
    outputs = [
        ('com/android/purebilibili/feature/audio/screen/ListenVideoScreen.kt', screen, screen_edits,
            'ListenVideoScreen.kt', sources['ListenVideoScreen.kt']),
        ('com/android/purebilibili/navigation3/BiliPaiNavKey.kt', nav, nav_edits, 'BiliPaiNavKey.kt', nav_baseline),
        ('com/android/purebilibili/feature/audio/screen/ListenVideoCollectionPlayer.kt', player, player_edits,
            'ListenVideoCollectionPlayer.kt', sources['ListenVideoCollectionPlayer.kt']),
    ]
    receipts = []
    for path, body, edits, name, inverse_original in outputs:
        target = output / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(body, encoding='utf-8', newline='\n')
        receipts.append(dict(output=path, source=PINS[name][0], upstreamCommit=COMMIT,
            fullInverseOriginalSha256LF=sha(inverse_original.encode()), adaptedSha256LF=sha(body.encode()),
            wholeOriginalInverse=True, inverseBase='v025 canonical navigation' if name == 'BiliPaiNavKey.kt' else 'v038 whole original',
            edits=edits))
        if name == 'BiliPaiNavKey.kt':
            receipts[-1].update(upstreamCommit=favorite_search.COMMIT, sourceManifestSHA256=favorite_search.MANIFEST_SHA256,
                fullCurrentOriginalUnadapted=True, fullCurrentOriginalSHA256=sha(body.encode()))
    mapping = favorite_search.emit_mapping(repo, output)
    proof = dict(upstreamCommit=COMMIT, sources=receipts, favoriteSearchMapping=mapping,
        favoriteSearchUpstreamCommit=favorite_search.COMMIT, favoriteSearchManifestSHA256=favorite_search.MANIFEST_SHA256,
        completeLatestListenScreenAndPlayerRetained=True,
        completeLatestAudioModeDeclarationRetained=True, wholeCurrentOriginalNavigationKeyRetained=True,
        existingRootPageLifecycleAndUiCallerUsed=True, originalCollectionAndArtistCallbacksRetained=True,
        actualCollectionRouteMounted=False, actualVideoBootstrapTargetVerified=False,
        soleProducerWiringVerified=False, mainCompiled=False, runtimeExecuted=False)
    (output / 'v038-listen-screen-source-proof.json').write_text(
        json.dumps(proof, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    return proof
