"""Whole v0.4.6 video detail information, action and first-batch plan files.

Roadmap slice vid-info-section-repin. The seven upstream files live in desktop/upstream-slices/v046-video-info-section
exactly as they stand at v0.4.6 (d6bf8b1655ae910999860bf13e65c8fcea5b345b). Nothing here copies a hunk: every Windows
producer that owns a declaration of one of these files now reads the v0.4.6 file and keeps its own counted, hash-pinned
platform adaptations.

What each file feeds, and what stays on its earlier pin:

- VideoInfoSection.kt and VideoInfoDisplayPolicy.kt feed extract-upstream-video-detail-full-units.py through the
  v037_player_interaction.py selection (selected_source_identity), which used to hand it the v0.3.7 copy of
  VideoInfoSection.kt and the v0.2.5 copy of the policy. That producer emits the ordinary information renderers
  (title, sponsor label, title with statistics and description, the new VideoDescriptionAndTags, the owner row with the
  new OwnerStatsRow, the description section), the statistics row of the Windows introduction and the remaining policy
  declarations. v0.4.6 already contains the unfollow confirmation call of the owner row (c4124a06) and the annual VIP
  name colour (44c541f5), so the two Windows hunks that added them to the older copy are retired in
  v046_follow_confirmation.py and v046_identity_badge_palette.py.
- The honor, declaration, creator team and BGM renderers of VideoInfoSection.kt are emitted by other producers from the
  v0.2.5 file (extract-stable-video-metadata.py, extract-upstream-bgm-detail.py). They stay there: only colour tokens
  differ in v0.4.6 and the creator team chip keeps its Windows unfollow hunk. Proposed follow-up slice.
- VideoActionSection.kt, VideoActionCountText.kt and VideoTriplePressState.kt are the v0.4.3 action row: buttons render
  from the first frame, the like and follow state crossfades instead of popping in, counts animate only after an
  explicit local action and the triple press is a small state machine. The same selection hands the producer the whole
  action section; the two new feedback files are emitted verbatim.
- VideoCommentRefreshContainer.kt (v0.3.9 stable refresh box) replaces the v0.3.6 file that
  v036_video_comment_viewport.generate_slots used to write.
- PlaybackPostLoadPlan.kt is a direct-mode vendored file. The v0.4.6 file (tags and the like, follow, favorite and coin
  state are requested in the first batch, 0 ms instead of 450 ms) is emitted whole and the vendored copy is excluded in
  prepareUpstreamSources (Root decision R-T2, precedent SearchLoadPolicy and HomeRefreshUiPolicy).

Not part of this slice (proposed follow-ups, see the hand-off report): SkeletonComponents (its producer layers a
third-party beta2 copy under hash pins of its own), VideoContentSection (v046-comment-list already carries it for the
comment hunks; the whole-file re-pin belongs to the layout slices) and the honor, team and BGM subtree above.
"""
from pathlib import Path
import hashlib
import json
import os
import stat

COMMIT = 'd6bf8b1655ae910999860bf13e65c8fcea5b345b'
ARCHIVE = 'desktop/upstream-slices/v046-video-info-section'
MANIFEST_SHA256 = '7fb458ce28a398e53628dc779bd9382649b324b15505e49ca85fb342b011f6b2'
LICENSE_SHA256 = '3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986'

APP = 'app/src/main/java/com/android/purebilibili/'
UPSTREAM_PATHS = {
    'VideoInfoSection.kt': APP + 'feature/video/ui/section/VideoInfoSection.kt',
    'VideoInfoDisplayPolicy.kt': APP + 'feature/video/ui/section/VideoInfoDisplayPolicy.kt',
    'VideoActionSection.kt': APP + 'feature/video/ui/section/VideoActionSection.kt',
    'VideoActionCountText.kt': APP + 'feature/video/ui/feedback/VideoActionCountText.kt',
    'VideoTriplePressState.kt': APP + 'feature/video/ui/feedback/VideoTriplePressState.kt',
    'VideoCommentRefreshContainer.kt': APP + 'feature/video/screen/VideoCommentRefreshContainer.kt',
    'PlaybackPostLoadPlan.kt': APP + 'feature/video/playback/policy/PlaybackPostLoadPlan.kt',
}
EXPECTED = set(UPSTREAM_PATHS)
PACKAGE = {
    'VideoInfoSection.kt': 'com.android.purebilibili.feature.video.ui.section',
    'VideoInfoDisplayPolicy.kt': 'com.android.purebilibili.feature.video.ui.section',
    'VideoActionSection.kt': 'com.android.purebilibili.feature.video.ui.section',
    'VideoActionCountText.kt': 'com.android.purebilibili.feature.video.ui.feedback',
    'VideoTriplePressState.kt': 'com.android.purebilibili.feature.video.ui.feedback',
    'VideoCommentRefreshContainer.kt': 'com.android.purebilibili.feature.video.screen',
    'PlaybackPostLoadPlan.kt': 'com.android.purebilibili.feature.video.playback.policy',
}
RELATIVE = {name: PACKAGE[name].replace('.', '/') + '/' + name for name in EXPECTED}
# Leaf files the producers write unchanged. The three selected sources (info, policy, action) are not emitted whole.
VERBATIM_LEAVES = ('VideoActionCountText.kt', 'VideoTriplePressState.kt', 'PlaybackPostLoadPlan.kt')
# The files that replace the older selection of v037_player_interaction.selected_source_identity.
SELECTED_LEAVES = ('VideoInfoSection.kt', 'VideoInfoDisplayPolicy.kt', 'VideoActionSection.kt')
SELECTED_PATHS = {UPSTREAM_PATHS[name]: name for name in SELECTED_LEAVES}
DECLARATIONS = {
    'VideoInfoSection.kt': (
        ('fun VideoTitleSection(', 1), ('fun VideoDetailSponsorLabelChip(', 1), ('fun VideoTitleWithDesc(', 1),
        ('fun VideoDescriptionAndTags(', 1), ('fun UpInfoSection(', 1), ('fun DescriptionSection(', 1),
        ('private fun OwnerStatsRow(', 1), ('internal fun buildVideoDescriptionAnnotatedString(', 2),
        ('private val VIDEO_DESCRIPTION_TOPIC_PATTERN', 1), ('private val useMiuixSpring', 1),
        ('private const val BGM_DISCOVERY_LOAD_DELAY_MS', 1), ('internal fun resolveBgmTagInfo(', 1),
    ),
    'VideoInfoDisplayPolicy.kt': (
        ('internal fun resolveVideoDetailOwnerStatsLabels(', 1), ('internal fun resolveVideoHonorChipText(', 1),
        ('internal fun resolveVideoHonorJumpUrl(', 1), ('internal fun shouldShowCreatorTeamSection(', 1),
        ('internal fun resolveVideoDetailBadges(', 1), ('internal fun resolveCompactPublishTimeRowText(', 1),
    ),
    'VideoActionSection.kt': (('fun ActionButtonsRow(', 1), ('fun TripleProgressIcon(', 1)),
    'VideoActionCountText.kt': (
        ('internal class VideoActionCountRequests', 1), ('internal fun shouldAnimateVideoActionCountChange(', 1),
        ('internal fun VideoActionCountText(', 1),
    ),
    'VideoTriplePressState.kt': (
        ('internal class VideoTriplePressState', 1), ('internal fun rememberVideoTriplePressState(', 1),
        ('internal fun resolveVideoTripleHoldProgress(', 1),
    ),
    'VideoCommentRefreshContainer.kt': (('internal fun VideoCommentRefreshContainer(', 1),),
    'PlaybackPostLoadPlan.kt': (('internal fun buildPlaybackPostLoadPlan(', 1), ('delayMs = 0L', 2), ('delayMs = 450L', 2)),
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
    need(root.is_dir() and not root.is_symlink(), 'Missing v046 video info section inputs')
    need({p.name for p in root.iterdir()} == EXPECTED | {'manifest.json', 'LICENSE'},
         'v046 video info section input leaves changed')
    manifest_raw = (root / 'manifest.json').read_bytes()
    need(b'\r' not in manifest_raw and sha(manifest_raw) == MANIFEST_SHA256, 'v046 video info section manifest changed')
    manifest = json.loads(manifest_raw)
    need((manifest['repository'], manifest['tag'], manifest['commit'], manifest['hashNormalization']) ==
         ('jay3-yy/BiliPai', 'v0.4.6', COMMIT, 'raw'), 'v046 video info section source identity changed')
    need(len(manifest['files']) == len(EXPECTED) and {row['filename'] for row in manifest['files']} == EXPECTED,
         'v046 video info section source set changed')
    sources, raws = {}, {}
    for row in manifest['files']:
        file = root / row['filename']
        need(file.is_file() and not file.is_symlink() and Path(row['path']).name == file.name,
             'Linked or ambiguous v046 video info section source')
        need(row['path'] == UPSTREAM_PATHS[file.name], 'v046 video info section upstream path changed: ' + file.name)
        leaf = file.read_bytes()
        need(b'\r' not in leaf and len(leaf) == row['bytes'] and sha(leaf) == row['sha256'],
             'v046 video info section bytes changed: ' + file.name)
        need(hashlib.sha1(b'blob ' + str(len(leaf)).encode() + b'\0' + leaf).hexdigest() == row['gitBlob'],
             'v046 video info section Git blob changed: ' + file.name)
        text = leaf.decode('utf8')
        need(text.encode('utf8') == leaf and text.endswith('\n') and ('package ' + PACKAGE[file.name] + '\n') in text[:600],
             'v046 video info section text changed: ' + file.name)
        for token, count in DECLARATIONS[file.name]:
            need(text.count(token) == count, 'v046 video info section declaration changed: ' + token)
        sources[file.name] = text
        raws[file.name] = leaf
    license_file = root / 'LICENSE'
    need(not license_file.is_symlink() and sha(license_file.read_bytes()) == manifest['license']['sha256'] == LICENSE_SHA256,
         'v046 video info section license changed')
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
             'Output overlaps v046 video info section inputs')


def selected_source_identity(path, repo=None):
    """Identity (with text) of a selected leaf in the shape v037_player_interaction.selected_source_identity returns,
    or None when `path` is not one of the three re-pinned selection sources."""
    name = SELECTED_PATHS.get(path)
    if name is None:
        return None
    repo = Path(repo) if repo is not None else default_repo()
    sources, _raws, manifest = fixed(repo)
    row = next(item for item in manifest['files'] if item['filename'] == name)
    text = sources[name]
    return dict(row, text=text, sha256LF=sha(text), upstreamCommit=COMMIT, sourceManifestSha256=MANIFEST_SHA256,
                localPath=ARCHIVE + '/' + name)


def refresh_container_bytes(repo=None):
    """The v0.4.6 stable comment refresh box, byte for byte (written by v036_video_comment_viewport.generate_slots)."""
    _sources, raws, _manifest = fixed(Path(repo) if repo is not None else default_repo())
    return raws['VideoCommentRefreshContainer.kt']


def _target(root, name):
    base = Path(root).resolve()
    relative = RELATIVE[name]
    target = base / relative
    # A leaf symlink must stay unresolved; resolve() would follow it and write through it.
    if not target.is_symlink():
        target = target.resolve()
        need(target.is_relative_to(base), 'v046 video info section target escapes the output root: ' + relative)
    return target, relative


def _require_regular_or_absent(target, relative):
    try:
        info = os.lstat(target)
    except FileNotFoundError:
        return
    need(stat.S_ISREG(info.st_mode), 'v046 video info section target is not a regular file: ' + relative)


def _wide(path):
    """Extended-length form on Windows so a deep Gradle output path cannot reach MAX_PATH; unchanged elsewhere."""
    path = Path(path).absolute()
    if os.name != 'nt':
        return path
    text = str(path)
    prefix = chr(92) * 2 + '?' + chr(92)
    return Path(text if text.startswith(prefix) else prefix + text)


def emit_verbatim_leaves(repo, output, outputs):
    """Write the three whole leaves unchanged under the producer output and append their inventory rows.

    `outputs` is the producer's own OUTPUTS list (source-bindings and the readback receipt); a path that is already
    claimed there fails closed, which is what keeps these files single-owner."""
    validate_outputs(repo, output)
    _sources, raws, _manifest = fixed(repo)
    planned = []
    for name in VERBATIM_LEAVES:
        target, relative = _target(output, name)
        need(not any(row['path'] == relative for row in outputs), 'v046 video info section leaf already emitted: ' + relative)
        _require_regular_or_absent(target, relative)
        planned.append((name, target, relative))
    # Every claim and every target is checked before the first byte is written.
    for name, target, relative in planned:
        leaf = raws[name]
        _wide(target).parent.mkdir(parents=True, exist_ok=True)
        _wide(target).write_bytes(leaf)
        need(_wide(target).read_bytes() == leaf, 'v046 video info section readback differs: ' + relative)
        outputs.append(dict(path=relative, origin=UPSTREAM_PATHS[name], mode='complete-original-v046-video-info-section',
                            fixedCommit=COMMIT, sha256LF=sha(leaf), physicalLines=len(leaf.decode('utf8').splitlines()),
                            generated=True))
    return outputs
