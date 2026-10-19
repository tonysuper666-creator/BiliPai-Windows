"""Whole v0.4.6 episode and part selector files.

Roadmap slice lst-episode-selector-collection (upstream 0.3.5, 0.3.8 and 0.3.9, final form at v0.4.6). The six upstream
files live in desktop/upstream-slices/v046-episode-selector exactly as they stand at v0.4.6
(d6bf8b1655ae910999860bf13e65c8fcea5b345b). Nothing here copies a hunk: every Windows producer that owns an earlier copy of
one of these files now reads the v0.4.6 file (hash pinned, and so is the predecessor it replaces) and keeps its own
platform adaptations.

What each file feeds, and what stays on its earlier pin:

- PagesSelector.kt feeds extract-upstream-video-player-full-controls.py. The producer used to read the v0.3.7 copy of the
  v037-player-closure archive and now reads the v0.4.6 file, the PAGES-ONLY final form: a horizontal part-card row with a
  beat indicator, and an expandable part list built from PageCoverList (cover thumbnails, search, chapter chips, sort
  order and top, bottom and current buttons). The 0.3.8 form that listed the collection episodes inside the part selector
  (e0c7c4ee) is NOT carried: 0.3.9 (b491ed2) removed it and v0.4.6 keeps that revert, so this file never mentions a
  collection episode. The Windows rewrites of the producer (display metrics instead of Android configuration) are
  unchanged and still apply to every line they matched before.
- PagePreviewNestedScrollTest.kt is the original v0.4.6 test of PagePreviewNestedScrollConnection. It needs no Android,
  Windows, GPU or network and is written into the test root of the same producer.
- CollectionSheet.kt feeds extract-stable-collection-sheet.py. The producer used to read the vendored v0.2.5 file, apply
  seven Windows hunks and then project the explicit tab role. v0.4.6 already declares the tab role, so the projection is
  a no-op on the new text; the seven hunks apply unchanged. The sheet gains the shared EpisodeListNavigationActions,
  the collection intro that only offers expansion when it overflows (introCanExpand), AppLazyColumn and the theme
  colour tokens.
- PageCoverList.kt and EpisodeListNavigationActions.kt are new files. The same producer writes them unchanged into its
  output (the producer has no output identity set that would have to change); the part selector and the collection sheet
  share them, which is why one producer carries both.
- VideoDetailResponse.kt is a direct-mode vendored file that the video detail units producer already replaces with the
  whole v0.3.1 file (v031_repost_coin.direct_outputs, canonical exclude in prepareUpstreamSources). This slice advances
  that output to the whole v0.4.6 file: the only difference is Page.firstFrame, the per-part thumbnail the part list shows.
  No new exclude line is needed.

Call sites. Every compiled PagesSelector caller keeps its Windows file and gets exactly one adaptation that makes the call
match v0.4.6. Each adaptation is COUNTED, not hash pinned: the producer asserts that its anchor occurs exactly once in the
text it received and records a label; nothing pins a predecessor hash of the enclosing body.

- VideoContentSection (the Windows file DesktopOriginalVideoContentSection.kt, derived from the v0.3.6 archive and layered
  by v046_comment_list): the detail page passes the video cover and the playing state (producer
  extract-upstream-video-content-full.py).
- VideoPlayerOverlay (derived from the v0.3.7 closure): the landscape page sheet passes the video cover (producer
  extract-upstream-video-player-full-controls.py).
- TabletVideoLayout, TabletCinemaLayout and AudioModeMusicPlayer pass the video cover too (producers
  extract-upstream-video-tablet-full.py and extract-upstream-video-audio-full.py; both are main source roots of the
  Windows build, generated/video-tablet-full and generated/video-audio-full). The TabletVideoLayout call is in
  ScrollableVideoInfoSection, which TabletVideoInfoPane renders for LargeScreenVideoLayout whenever the window is wide and
  horizontally adapted (the 4K and 5K desktop path) and for TabletVideoLayout itself (hinge path). TabletCinemaLayout is
  compiled and made to match v0.4.6, but the pinned v0.2.5 tree has no caller of it. Without these three edits a part with a
  blank first_frame would show only the play placeholder on the large-screen layout.

Layer order. Each edit is the LAST layer of its output (playbook 5.5, P7), so no earlier proof changes: the content edit runs
inside emit() after v046_comment_list.advance_content_comments, the overlay edit runs inside emit() after the follow
confirmation, and the tablet edit runs inside emit() after advance_bottom_calls and advance_tablet_avatars. The audio edit is
the last replace() of its file. Two older tests compare a FINAL output with the receipt of the layer below and therefore peel
exactly this one edit first (playbook 6.3): test_v046_comment_list_source (tablet and cinema avatar receipt) and
test_v046_engagement_state_safety_source (follow confirmation receipt of the overlay). The whole-file re-pin of
VideoContentSection belongs to the layout slices.

Not part of this slice (proposed follow-up): PagesSelectorLayoutPolicyTest reads source files by path and is not carried.
"""
from pathlib import Path
import hashlib
import json
import os
import stat

COMMIT = 'd6bf8b1655ae910999860bf13e65c8fcea5b345b'
ARCHIVE = 'desktop/upstream-slices/v046-episode-selector'
MANIFEST_SHA256 = '74aca8825fcf0d16019b2564b37f0721caa5e2eda86a7ee4185518d7801a5af4'
LICENSE_SHA256 = '3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986'

APP = 'app/src/main/java/com/android/purebilibili/'
UPSTREAM_PATHS = {
    'PagesSelector.kt': APP + 'feature/video/ui/components/PagesSelector.kt',
    'PageCoverList.kt': APP + 'feature/video/ui/components/PageCoverList.kt',
    'EpisodeListNavigationActions.kt': APP + 'feature/video/ui/components/EpisodeListNavigationActions.kt',
    'CollectionSheet.kt': APP + 'feature/video/ui/components/CollectionSheet.kt',
    'VideoDetailResponse.kt': 'core-data/src/main/java/com/android/purebilibili/data/model/response/VideoDetailResponse.kt',
    'PagePreviewNestedScrollTest.kt':
        'app/src/test/java/com/android/purebilibili/feature/video/ui/components/PagePreviewNestedScrollTest.kt',
}
EXPECTED = set(UPSTREAM_PATHS)
PACKAGE = {
    'PagesSelector.kt': 'com.android.purebilibili.feature.video.ui.components',
    'PageCoverList.kt': 'com.android.purebilibili.feature.video.ui.components',
    'EpisodeListNavigationActions.kt': 'com.android.purebilibili.feature.video.ui.components',
    'CollectionSheet.kt': 'com.android.purebilibili.feature.video.ui.components',
    'VideoDetailResponse.kt': 'com.android.purebilibili.data.model.response',
    'PagePreviewNestedScrollTest.kt': 'com.android.purebilibili.feature.video.ui.components',
}
COMPONENTS = 'com/android/purebilibili/feature/video/ui/components/'
DETAIL_RESPONSE_OUTPUT = 'com/android/purebilibili/data/model/response/VideoDetailResponse.kt'
# Producer-output-relative path of every file this slice writes (the test goes under the producer's test root).
RELATIVE = {
    'PageCoverList.kt': COMPONENTS + 'PageCoverList.kt',
    'EpisodeListNavigationActions.kt': COMPONENTS + 'EpisodeListNavigationActions.kt',
    'PagePreviewNestedScrollTest.kt': COMPONENTS + 'PagePreviewNestedScrollTest.kt',
}
# Leaves written byte for byte (main output of the collection sheet producer, test output of the controls producer).
VERBATIM_COLLECTION_LEAVES = ('PageCoverList.kt', 'EpisodeListNavigationActions.kt')
VERBATIM_TEST_LEAVES = ('PagePreviewNestedScrollTest.kt',)

# LF sha256 of the text each layer receives, which this slice was authored against (also recorded in the manifest).
PREDECESSOR_SHA256 = {
    'PagesSelector.kt': '389d4e15914cb607ff54c04e1550e0bf488e4b2990ee57bd85a3ce4cb8ec71d2',
    'CollectionSheet.kt': 'acb0fed81d221b8a4d06564aead150864aaf02e5b04f8ac419d64324c616cb1a',
    'VideoDetailResponse.kt': '0d42125b33ef0b59b94a4073bc47bc223708cfc8a47e230cc38c27d7dee32989',
}
# Producer-visible key of every predecessor in manifest['beforeFinalOutputs'].
PREDECESSOR_KEYS = {
    'PagesSelector.kt': UPSTREAM_PATHS['PagesSelector.kt'],
    'CollectionSheet.kt': UPSTREAM_PATHS['CollectionSheet.kt'],
    'VideoDetailResponse.kt': DETAIL_RESPONSE_OUTPUT,
}
SELECTED_PATHS = {UPSTREAM_PATHS[name]: name for name in ('PagesSelector.kt', 'CollectionSheet.kt')}

# Declarations each file must contain exactly this often. They also pin the shape the Windows producers rewrite.
DECLARATIONS = {
    'PagesSelector.kt': (
        ('internal object PagePreviewNestedScrollConnection', 1), ('fun PagesSelector(', 1),
        ('private fun PlayingEqualizerIcon(', 1), ('private fun PagesHorizontalPreviewRow(', 1),
        ('private fun PagesSelectorFilterBar(', 1), ('PageCoverList(', 2), ('coverUrl: String = ""', 1),
        ('isPlaying: Boolean = false', 1), ('import android.content.res.Configuration\n', 1),
        ('import androidx.compose.ui.platform.LocalConfiguration\n', 1),
        ('configuration.orientation', 2), ('Configuration.ORIENTATION_LANDSCAPE', 1),
    ),
    'PageCoverList.kt': (
        ('internal fun PageCoverList(', 1), ('private fun PageCoverRow(', 1), ('EpisodeListNavigationActions(', 1),
        ('AppSingleChoiceRow(', 1), ('page.firstFrame.ifBlank { coverUrl }', 1), ('minLines = 2,', 1), ('maxLines = 2,', 1),
        ('onResetFilters: () -> Unit = {}', 1),
    ),
    'EpisodeListNavigationActions.kt': (
        ('internal fun RowScope.EpisodeListNavigationActions(', 1), ('AppIconButton(', 3),
        ('"跳至顶部"', 1), ('"跳至底部"', 1), ('"定位当前播放"', 1),
    ),
    'CollectionSheet.kt': (
        ('fun CollectionSheet(', 1), ('internal fun CollectionEpisodeRow(', 1),
        ('var introCanExpand by remember(ugcSeason.id, collectionIntro) { mutableStateOf(false) }', 1),
        ('EpisodeListNavigationActions(', 1), ('AppLazyColumn as LazyColumn', 1),
        ('role = com.android.purebilibili.core.ui.components.AppTabRowRole.SECONDARY,', 1),
        ('import androidx.compose.ui.platform.LocalContext\n', 1), ('val context = LocalContext.current', 1),
        ('ImageRequest.Builder(LocalContext.current)', 1), ('com.android.purebilibili.core.util.ShareUtils.shareCollection(', 1),
        ('SettingsManager.setCollectionSortMode(context, collectionSubscriptionId, nextMode)', 1),
    ),
    'VideoDetailResponse.kt': (
        ('data class Page(', 1), ('@SerialName("first_frame") val firstFrame: String = "",', 1),
        ('val copyright: Int = 1,', 1), ('val isRepost: Boolean', 1),
    ),
    'PagePreviewNestedScrollTest.kt': (('class PagePreviewNestedScrollTest', 1), ('@Test', 3)),
}
# The 0.3.8 form of the part selector listed collection episodes inside it. None of these words may appear in the file.
COLLECTION_IN_PAGES_SELECTOR = ('UgcEpisode', 'ugc_season', 'ugcSeason', 'CollectionEpisode', 'onEpisodeClick', 'CollectionSheet')

# Call-site adaptations: COUNTED by the producer that applies them (its anchor must occur exactly once, a label is recorded).
# VideoContentSection (the Windows file DesktopOriginalVideoContentSection.kt): v0.4.6 passes the cover and the playing state.
CONTENT_PAGES_BEFORE = (
    '        if (info.pages.size > 1) {\n'
    '            PagesSelector(\n'
    '                pages = info.pages,\n'
    '                currentPageIndex = currentPageIndex,\n'
    '                onPageSelect = onPageSelect\n'
    '            )\n'
    '        }\n'
)
CONTENT_PAGES_AFTER = (
    '        if (info.pages.size > 1) {\n'
    '            PagesSelector(\n'
    '                pages = info.pages,\n'
    '                coverUrl = info.pic,\n'
    '                currentPageIndex = currentPageIndex,\n'
    '                isPlaying = isVideoPlaying,\n'
    '                onPageSelect = onPageSelect\n'
    '            )\n'
    '        }\n'
)
# VideoPlayerOverlay: v0.4.6 passes the video cover to the part list of the landscape page sheet.
OVERLAY_PAGES_BEFORE = (
    '                        PagesSelector(\n'
    '                            pages = pages,\n'
    '                            currentPageIndex = currentPageIndex,\n'
)
OVERLAY_PAGES_AFTER = (
    '                        PagesSelector(\n'
    '                            pages = pages,\n'
    '                            coverUrl = coverUrl,\n'
    '                            currentPageIndex = currentPageIndex,\n'
)
# The large-screen callers (compiled through generated/video-tablet-full and generated/video-audio-full): v0.4.6 passes the
# video cover right after `pages = ...`, which is what the part list falls back to for a part without a first_frame.
TABLET_PAGES_BEFORE = (
    '                PagesSelector(\n'
    '                    pages = info.pages,\n'
    '                    currentPageIndex = currentPageIndex,\n'
)
TABLET_PAGES_AFTER = (
    '                PagesSelector(\n'
    '                    pages = info.pages,\n'
    '                    coverUrl = info.pic,\n'
    '                    currentPageIndex = currentPageIndex,\n'
)
CINEMA_PAGES_BEFORE = (
    '                            PagesSelector(\n'
    '                                pages = success.info.pages,\n'
    '                                currentPageIndex = currentPageIndex,\n'
)
CINEMA_PAGES_AFTER = (
    '                            PagesSelector(\n'
    '                                pages = success.info.pages,\n'
    '                                coverUrl = success.info.pic,\n'
    '                                currentPageIndex = currentPageIndex,\n'
)
AUDIO_PAGES_BEFORE = (
    '            PagesSelector(\n'
    '                pages = info.pages,\n'
    '                currentPageIndex = info.pages.indexOfFirst { it.cid == info.cid }.coerceAtLeast(0),\n'
)
AUDIO_PAGES_AFTER = (
    '            PagesSelector(\n'
    '                pages = info.pages,\n'
    '                coverUrl = info.pic,\n'
    '                currentPageIndex = info.pages.indexOfFirst { it.cid == info.cid }.coerceAtLeast(0),\n'
)
CALL_SITE_LABELS = {
    'content': 'Detail page passes the video cover and the playing state to the part selector as in v0.4.6',
    'overlay': 'Player page sheet passes the video cover to the part list as in v0.4.6',
    'tablet': 'Tablet info column passes the video cover to the part selector as in v0.4.6',
    'cinema': 'Cinema meta panel passes the video cover to the part selector as in v0.4.6',
    'audio': 'Audio mode page sheet passes the video cover to the part selector as in v0.4.6',
}


def sha(value):
    return hashlib.sha256(value.encode('utf8') if isinstance(value, str) else value).hexdigest()


def need(condition, message):
    if not condition:
        raise ValueError(message)


def default_repo():
    return Path(__file__).resolve().parents[2]


def fixed(repo):
    """Verified text and bytes of every carried leaf. Raises when the archive differs from its pins in any way."""
    root = Path(repo) / ARCHIVE
    need(root.is_dir() and not root.is_symlink(), 'Missing v046 episode selector inputs')
    need({p.name for p in root.iterdir()} == EXPECTED | {'manifest.json', 'LICENSE'},
         'v046 episode selector input leaves changed')
    manifest_raw = (root / 'manifest.json').read_bytes()
    need(b'\r' not in manifest_raw and sha(manifest_raw) == MANIFEST_SHA256, 'v046 episode selector manifest changed')
    manifest = json.loads(manifest_raw)
    need((manifest['repository'], manifest['tag'], manifest['commit'], manifest['hashNormalization']) ==
         ('jay3-yy/BiliPai', 'v0.4.6', COMMIT, 'raw'), 'v046 episode selector source identity changed')
    need(len(manifest['files']) == len(EXPECTED) and {row['filename'] for row in manifest['files']} == EXPECTED,
         'v046 episode selector source set changed')
    before = manifest['beforeFinalOutputs']
    need({key: before[key]['sha256'] for key in before} ==
         {PREDECESSOR_KEYS[name]: PREDECESSOR_SHA256[name] for name in PREDECESSOR_SHA256},
         'v046 episode selector predecessor pins changed')
    sources, raws = {}, {}
    for row in manifest['files']:
        file = root / row['filename']
        need(file.is_file() and not file.is_symlink() and Path(row['path']).name == file.name,
             'Linked or ambiguous v046 episode selector source')
        need(row['path'] == UPSTREAM_PATHS[file.name], 'v046 episode selector upstream path changed: ' + file.name)
        leaf = file.read_bytes()
        need(b'\r' not in leaf and len(leaf) == row['bytes'] and sha(leaf) == row['sha256'],
             'v046 episode selector bytes changed: ' + file.name)
        need(hashlib.sha1(b'blob ' + str(len(leaf)).encode() + b'\0' + leaf).hexdigest() == row['gitBlob'],
             'v046 episode selector Git blob changed: ' + file.name)
        text = leaf.decode('utf8')
        need(text.encode('utf8') == leaf and text.endswith('\n') and ('package ' + PACKAGE[file.name] + '\n') in text[:600],
             'v046 episode selector text changed: ' + file.name)
        for token, count in DECLARATIONS[file.name]:
            need(text.count(token) == count, 'v046 episode selector declaration changed: ' + token)
        sources[file.name] = text
        raws[file.name] = leaf
    # Pages-only final form: the 0.3.8 collection episodes in the part selector were reverted by 0.3.9 and stay reverted.
    for word in COLLECTION_IN_PAGES_SELECTOR:
        need(word not in sources['PagesSelector.kt'], 'v046 episode selector carries the reverted 0.3.8 form: ' + word)
    license_file = root / 'LICENSE'
    need(not license_file.is_symlink() and sha(license_file.read_bytes()) == manifest['license']['sha256'] == LICENSE_SHA256,
         'v046 episode selector license changed')
    return sources, raws, manifest


def validate_outputs(repo, *outputs):
    """Call first in every producer that hooks this slice: archive intact and no output inside or around it."""
    fixed(repo)
    root = (Path(repo) / ARCHIVE).resolve()
    for output in outputs:
        if output is None:
            continue
        target = Path(output).resolve()
        need(not (target.is_relative_to(root) or root.is_relative_to(target)),
             'Output overlaps v046 episode selector inputs')


def selected_source(path, predecessor, repo=None):
    """The text a producer should use for `path`: the whole v0.4.6 file for the two selected sources (after proving that
    `predecessor` is exactly the text this slice was authored against), `predecessor` itself for every other path."""
    name = SELECTED_PATHS.get(path)
    if name is None:
        return predecessor
    sources, _raws, _manifest = fixed(Path(repo) if repo is not None else default_repo())
    need(sha(predecessor.replace('\r\n', '\n')) == PREDECESSOR_SHA256[name],
         'v046 episode selector predecessor changed: ' + name)
    return sources[name]


def advance_video_detail_response(repo, predecessor):
    """The whole v0.4.6 VideoDetailResponse.kt for the v0.3.1 body that v031_repost_coin.direct_outputs hands over."""
    sources, _raws, _manifest = fixed(Path(repo))
    need(sha(predecessor.replace('\r\n', '\n')) == PREDECESSOR_SHA256['VideoDetailResponse.kt'],
         'v046 episode selector predecessor changed: VideoDetailResponse.kt')
    return sources['VideoDetailResponse.kt']


def source_identity(name, repo=None):
    """Upstream identity of one carried leaf for a producer's bookkeeping rows."""
    _sources, raws, manifest = fixed(Path(repo) if repo is not None else default_repo())
    row = next(item for item in manifest['files'] if item['filename'] == name)
    return dict(upstreamPath=row['path'], upstreamCommit=COMMIT, upstreamGitBlob=row['gitBlob'],
                sourceManifestSha256=MANIFEST_SHA256, sourceSha256=sha(raws[name]),
                localPath=ARCHIVE + '/' + name)


def _target(root, name):
    base = Path(root).resolve()
    relative = RELATIVE[name]
    target = base / relative
    # A leaf symlink must stay unresolved; resolve() would follow it and write through it.
    if not target.is_symlink():
        target = target.resolve()
        need(target.is_relative_to(base), 'v046 episode selector target escapes the output root: ' + relative)
    return target, relative


def _require_regular_or_absent(target, relative):
    try:
        info = os.lstat(target)
    except FileNotFoundError:
        return
    need(stat.S_ISREG(info.st_mode), 'v046 episode selector target is not a regular file: ' + relative)


def _wide(path):
    """Extended-length form on Windows so a deep Gradle output path cannot reach MAX_PATH; unchanged elsewhere."""
    path = Path(path).absolute()
    if os.name != 'nt':
        return path
    text = str(path)
    prefix = chr(92) * 2 + '?' + chr(92)
    return Path(text if text.startswith(prefix) else prefix + text)


def _write_leaves(repo, output, names):
    validate_outputs(repo, output)
    _sources, raws, _manifest = fixed(Path(repo))
    planned = []
    for name in names:
        target, relative = _target(output, name)
        _require_regular_or_absent(target, relative)
        planned.append((name, target, relative))
    # Every target is checked before the first byte is written.
    for name, target, relative in planned:
        leaf = raws[name]
        _wide(target).parent.mkdir(parents=True, exist_ok=True)
        _wide(target).write_bytes(leaf)
        need(_wide(target).read_bytes() == leaf, 'v046 episode selector readback differs: ' + relative)
    return planned


def emit_collection_leaves(repo, output, records):
    """Write the part list and the shared list navigation buttons unchanged under the collection sheet output and append
    their rows to that producer's `source-bindings.json` list. A path that is already claimed there fails closed."""
    for name in VERBATIM_COLLECTION_LEAVES:
        need(not any(str(row.get('output', '')).replace(chr(92), '/') == RELATIVE[name] for row in records),
             'v046 episode selector leaf already emitted: ' + RELATIVE[name])
    _sources, raws, _manifest = fixed(Path(repo))
    for name, target, relative in _write_leaves(repo, output, VERBATIM_COLLECTION_LEAVES):
        identity = source_identity(name, repo)
        records.append({'original': identity['upstreamPath'], 'output': relative,
                        'originalSha256Lf': sha(raws[name]), 'generatedSha256': sha(_wide(target).read_bytes()),
                        'upstreamCommit': COMMIT, 'sourceManifestSha256': MANIFEST_SHA256, 'wholeOriginal': True})
    return records


def emit_original_tests(repo, test_output):
    """Write the original v0.4.6 test of the nested scroll boundary under the controls producer's test root."""
    return [target for _name, target, _relative in _write_leaves(repo, test_output, VERBATIM_TEST_LEAVES)]
