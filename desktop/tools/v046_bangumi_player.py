"""v0.4.6 Bangumi player: view model, follow-session policy and feature state.

Roadmap slice bgm-player-viewmodel-and-features, part 1 (SOURCE only; nothing here was compiled or run on a JVM).

What is carried, all from the v046-bangumi-player archive (verbatim v0.4.6 bytes, d6bf8b16):
- BangumiPlayerViewModel.kt (blob 2bd981f7, the last upstream change is 5a0bd7e2, v0.3.8). The existing sole PGC producer
  (extract-upstream-bangumi-player.py) already turns the pinned v0.2.5 body into the Windows view model through ten
  hash-pinned layers and the v034 initial-detail and audio-download overlays. apply_view_model is the next layer: 48 counted,
  hash-pinned hunks, stored in the archive manifest, that bring in the follow-session convergence of 3b37b7a2, the
  like-status retry and mutex of 5a0bd7e2 and d7ba1077 (the Bangumi hunks that slice vid-engagement-state-safety left here),
  and sendEpisodeDanmaku and saveCover of the 0.3.5 feature commit. Every line the layer adds is upstream text except the
  manifest's windowsLines, which bind the new members to the presenter environment (follow session, ordinary danmaku
  protocol, ordinary cover save, Root account view). The layer asserts that list exactly.
- BangumiFollowStatusPolicy.kt: the independent-pages producer owns the preload half of this file (the other half is owned
  by the media producer and is unchanged between v0.2.5 and v0.4.6). advance_follow_preload swaps the v0.2.5 selection for
  the v0.4.6 head and the v0.4.6 declarations (targetSeasonId, sessionProvider, the shared verification permit,
  BangumiFollowSession) with four counted adaptations: no BangumiRepository default, no TokenManager, a constant default
  session and no default fetchPage. The Windows view model passes its own session and request view.
- BangumiPlayerFeatures.kt: emitted whole as DesktopOriginalBangumiPlayerFeatures.kt by the PGC view model producer with
  counted adaptations. Android settings, the global VideoRepository, LocalContext, LocalConfiguration and the Media3 volume
  listener are replaced by the desktop-owned DesktopOriginalBangumiSubtitlePort and the Windows window height.
- BangumiFollowStatusPolicyTest.kt: emitted with its JUnit4 imports rewritten to kotlin.test.
- likeStatusKnown in the UI: the v0.4.6 overlay (carried by the player overlay slice) shows the like icon as active only while
  likeStatusKnown is true and the retained Windows Bangumi UI bodies never passed it, so apply_retry_ui threads the flag
  exactly like upstream through Screen, Components and OverlayHost (five counted pairs on the sole PGC UI producer).

Not carried here (follow-up slices): the Bangumi screen, content, components and overlay host at v0.4.6 that construct the
features, LandscapeSidePanel closeProgress, DanmakuSendDialog and the v038 glass controls (all UI), the hub pages.
"""
from collections import Counter
from pathlib import Path
import hashlib
import json
import os

from v044_favorite_detail import inverse_proof

COMMIT = 'd6bf8b1655ae910999860bf13e65c8fcea5b345b'
ARCHIVE = 'desktop/upstream-slices/v046-bangumi-player'
MANIFEST_SHA256 = '397fe6bfe7fce8510d0ffab72c841f351b270b22895a07efb5168b684acc2ebe'
VM_LEAF = 'BangumiPlayerViewModel.kt'
FEATURES_LEAF = 'BangumiPlayerFeatures.kt'
POLICY_LEAF = 'BangumiFollowStatusPolicy.kt'
TEST_LEAF = 'BangumiFollowStatusPolicyTest.kt'
EXPECTED = {VM_LEAF, FEATURES_LEAF, POLICY_LEAF, TEST_LEAF}
VM_OUTPUT = 'com/android/purebilibili/feature/bangumi/DesktopOriginalBangumiPlayerViewModel.kt'
POLICY_OUTPUT = 'com/android/purebilibili/feature/bangumi/policy/DesktopOriginalBangumiFollowPreloadPolicy.kt'
FEATURES_OUTPUT = 'com/android/purebilibili/feature/bangumi/ui/player/DesktopOriginalBangumiPlayerFeatures.kt'
TEST_OUTPUT = 'com/android/purebilibili/feature/bangumi/BangumiFollowStatusPolicyTest.kt'
PROOF_DIRECTORY = 'v046-bangumi-player-proofs'
POLICY_PATH = 'app/src/main/java/com/android/purebilibili/feature/bangumi/policy/BangumiFollowStatusPolicy.kt'
FEATURES_PATH = 'app/src/main/java/com/android/purebilibili/feature/bangumi/ui/player/BangumiPlayerFeatures.kt'
VM_HUNK_COUNT = 48
VM_WINDOWS_LINE_COUNT = 16
POLICY_AFTER_SHA256 = 'fcd46d0476e3df38816acc5f0c047ed2d81cf1a47671d04fb0f1d606c48e25e0'
FEATURES_AFTER_SHA256 = '9e88ae0f2a8c17d12fcd809c91ae4ff8a19fa4b4098a5aae05394f8003be8b7b'
TEST_AFTER_SHA256 = 'dd6cff995727e214d77873ddbebc9e0dbfba5d3ed823cb188e29bec925190311'
TEST_COUNT = 16

# The like-status-known flag reaches the carried v0.4.6 overlay (its like icon is active only while the status is known):
# the retained Windows Bangumi UI bodies thread it exactly like v0.4.6. Counted pairs on the outputs of the sole PGC UI
# producer; the upstream lines are pinned in the manifest (uiRetry) and compared by the source test.
SCREEN_OUTPUT = 'com/android/purebilibili/feature/bangumi/DesktopOriginalBangumiPlayerScreen.kt'
COMPONENTS_OUTPUT = 'com/android/purebilibili/feature/bangumi/ui/player/DesktopOriginalBangumiPlayerComponents.kt'
OVERLAY_HOST_OUTPUT = 'com/android/purebilibili/feature/bangumi/ui/player/DesktopOriginalBangumiPlayerOverlayHost.kt'
UI_RETRY_HUNKS = {
    SCREEN_OUTPUT: [
        ('the displayed Success state hands its like-known flag to the player view',
         '                        isLiked = successState?.isLiked ?: false,\n',
         '                        isLiked = successState?.isLiked ?: false,\n'
         '                        likeStatusKnown = successState?.likeStatusKnown == true,\n'),
    ],
    COMPONENTS_OUTPUT: [
        ('the player view declares the flag next to isLiked',
         '    isLiked: Boolean = false,\n',
         '    isLiked: Boolean = false,\n    likeStatusKnown: Boolean = false,\n'),
        ('the player view forwards the flag to the overlay host',
         '            isLiked = isLiked,\n',
         '            isLiked = isLiked,\n            likeStatusKnown = likeStatusKnown,\n'),
    ],
    OVERLAY_HOST_OUTPUT: [
        ('the overlay host declares the flag next to isLiked',
         '    isLiked: Boolean,\n',
         '    isLiked: Boolean,\n    likeStatusKnown: Boolean,\n'),
        ('the overlay host puts the flag into the carried overlay state',
         '            isLiked = isLiked,\n',
         '            isLiked = isLiked,\n            likeStatusKnown = likeStatusKnown,\n'),
    ],
}
UI_RETRY_AFTER_SHA256 = {
    SCREEN_OUTPUT: 'a8157b212cb434f08ad2b2979b62efb9081c56a673cdc22797ced4fbd3ad359f',
    COMPONENTS_OUTPUT: '0df3dac249ebdda3b28b73dba941ab9a30d62c3ac5204e2b52f90f8a72468107',
    OVERLAY_HOST_OUTPUT: '54ed2ba572094476e45ccb8b7a35b93d40bc50200709c4d3eb36805076066de8',
}

# Kotlin text that the follow-session policy keeps from the retained Windows selection, and the exact
# v0.4.6 text it is replaced with (one counted pair per adaptation).
HEAD_END = 'internal const val BANGUMI_FOLLOW_STATUS_UNFOLLOW'
PAGE_COUNT_START = 'internal fun resolveFollowPreloadPageCount('
PRELOAD_START = 'internal suspend fun preloadFollowedSeasonsForType('
POLICY_ADAPTATIONS = [
    ('Windows requests go through the owner request view, not a repository default',
     'import com.android.purebilibili.data.repository.BangumiRepository\n',
     '// Root requires the exact existing follow-request delegate.\n'),
    ('Windows has no process-wide TokenManager',
     'import com.android.purebilibili.core.store.TokenManager\n',
     '// Windows has no process-wide TokenManager: an owner passes its own session identity.\n'),
    ('the process-wide default session is the constant "no account tracking" identity',
     'internal fun currentBangumiFollowSession() = BangumiFollowSession(\n'
     '    TokenManager.midCache, TokenManager.sessDataCache\n)\n',
     '/** Windows has no process-wide account cache. This default means "no account tracking": callers that pass no\n'
     ' * sessionProvider (the hub pages) keep their previous behaviour, and a Bangumi presenter passes its own session. */\n'
     'internal fun currentBangumiFollowSession() = BangumiFollowSession(null, null)\n'),
    ('fetchPage has no repository default: the caller supplies its owner request view',
     '    fetchPage: suspend (type: Int, page: Int, pageSize: Int) -> Result<MyFollowBangumiData> =\n'
     '        { requestType, page, ps ->\n'
     '            BangumiRepository.getMyFollowBangumi(type = requestType, page = page, pageSize = ps)\n'
     '        }\n',
     '    fetchPage: suspend (type: Int, page: Int, pageSize: Int) -> Result<MyFollowBangumiData>\n'),
]
JUNIT_IMPORTS = [
    ('import org.junit.Assert.assertNull\n', 'import kotlin.test.assertNull\n'),
    ('import org.junit.Assert.assertSame\n', 'import kotlin.test.assertSame\n'),
    ('import org.junit.Assert.fail\n', 'import kotlin.test.fail\n'),
    ('import org.junit.Assert.assertEquals\n', 'import kotlin.test.assertEquals\n'),
    ('import org.junit.Assert.assertFalse\n', 'import kotlin.test.assertFalse\n'),
    ('import org.junit.Assert.assertTrue\n', 'import kotlin.test.assertTrue\n'),
    ('import org.junit.Test\n', 'import kotlin.test.Test\n'),
]
# Feature state: each pair is replaced exactly once. The upstream data classes are untouched.
FEATURES_ADAPTATIONS = [
    ('LocalContext has no Windows counterpart; settings come through the subtitle port',
     'import androidx.compose.ui.platform.LocalContext\n', ''),
    ('Media3 Player is the existing MPV overlay control (position and play state only)',
     'import androidx.media3.common.Player\n',
     'import com.bilipai.desktop.appearance.DesktopWindowConfiguration\n'
     'import com.bilipai.desktop.ui.DesktopOriginalBangumiSubtitlePort\n'
     'import com.bilipai.desktop.ui.DesktopOriginalMpvOverlayControl as Player\n'),
    ('Android SettingsManager is replaced by the port', 'import com.android.purebilibili.core.store.SettingsManager\n', ''),
    ('the global VideoRepository is replaced by the owner requests behind the port',
     'import com.android.purebilibili.data.repository.VideoRepository\n', ''),
    ('rememberBangumiSubtitles reads preferences, mute state and requests from the port',
     'internal fun rememberBangumiSubtitles(bvid: String, cid: Long, player: Player): BangumiSubtitleState {\n'
     '    val context = LocalContext.current\n'
     '    val scope = rememberCoroutineScope()\n'
     '    val preference by SettingsManager.getSubtitleAutoPreference(context)\n'
     '        .collectAsStateWithLifecycle(initialValue = SubtitleAutoPreference.OFF)\n'
     '    val positionLocked by SettingsManager.getSubtitlePositionLocked(context)\n'
     '        .collectAsStateWithLifecycle(initialValue = true)\n'
     '    var isMuted by remember(player) { mutableStateOf(player.volume == 0f) }\n'
     '    DisposableEffect(player) {\n'
     '        val listener = object : Player.Listener {\n'
     '            override fun onVolumeChanged(volume: Float) { isMuted = volume == 0f }\n'
     '        }\n'
     '        player.addListener(listener)\n'
     '        onDispose { player.removeListener(listener) }\n'
     '    }\n',
     'internal fun rememberBangumiSubtitles(bvid: String, cid: Long, port: DesktopOriginalBangumiSubtitlePort): BangumiSubtitleState {\n'
     '    val scope = rememberCoroutineScope()\n'
     '    val preference by port.autoPreference\n'
     '        .collectAsStateWithLifecycle(initialValue = SubtitleAutoPreference.OFF)\n'
     '    val positionLocked by port.positionLocked\n'
     '        .collectAsStateWithLifecycle(initialValue = true)\n'
     '    val isMuted by port.isMuted\n'
     '        .collectAsStateWithLifecycle(initialValue = false)\n'),
    ('player info through the owner request view',
     'VideoRepository.getPlayerInfo(bvid, cid)', 'port.getPlayerInfo(bvid, cid)'),
    ('subtitle cues through the owner request view',
     'VideoRepository.getSubtitleCues(', 'port.getSubtitleCues('),
    ('position lock is written through the port',
     'onPositionLockedChange = { scope.launch { SettingsManager.setSubtitlePositionLocked(context, it) } },',
     'onPositionLockedChange = { scope.launch { port.setPositionLocked(it) } },'),
    ('the overlay receives the port next to its state',
     '    player: Player,\n    state: BangumiSubtitleState,\n',
     '    player: Player,\n    state: BangumiSubtitleState,\n    port: DesktopOriginalBangumiSubtitlePort,\n'),
    ('the stored vertical offset is read through the port',
     '    val context = LocalContext.current\n'
     '    val scope = rememberCoroutineScope()\n'
     '    val savedOffset by SettingsManager.getSubtitleVerticalOffsetFraction(context)\n'
     '        .collectAsStateWithLifecycle(initialValue = 0f)\n',
     '    val scope = rememberCoroutineScope()\n'
     '    val savedOffset by port.verticalOffsetFraction\n'
     '        .collectAsStateWithLifecycle(initialValue = 0f)\n'),
    ('the fallback viewport height is the Windows window height',
     'androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp.toPx()',
     'DesktopWindowConfiguration.current.screenHeightDp.dp.toPx()'),
    ('the dragged offset is written through the port',
     'scope.launch { SettingsManager.setSubtitleVerticalOffsetFraction(context, releasedOffset) }',
     'scope.launch { port.setVerticalOffsetFraction(releasedOffset) }'),
]


_WIDE_PREFIX = chr(92) * 2 + '?' + chr(92)


def wide(path):
    """Extended-length form on Windows: the generated tree sits deep below the checkout (MAX_PATH)."""
    path = Path(path).resolve()
    return Path(_WIDE_PREFIX + str(path)) if os.name == 'nt' and not str(path).startswith(_WIDE_PREFIX) else path


def sha(value):
    return hashlib.sha256(value.encode('utf8') if isinstance(value, str) else value).hexdigest()


def need(condition, message):
    if not condition:
        raise ValueError(message)


def once(text, old, new, label):
    need(text.count(old) == 1, 'Bangumi player anchor changed: ' + label)
    return text.replace(old, new, 1)


def fixed(repo):
    root = Path(repo) / ARCHIVE
    need(root.is_dir() and not root.is_symlink(), 'Missing Bangumi player originals')
    need({p.name for p in root.iterdir()} == EXPECTED | {'manifest.json', 'LICENSE'}, 'Bangumi player input leaves changed')
    raw = (root / 'manifest.json').read_bytes()
    need(sha(raw) == MANIFEST_SHA256, 'Bangumi player manifest changed')
    manifest = json.loads(raw)
    need((manifest['repository'], manifest['tag'], manifest['commit'], manifest['hashNormalization']) ==
         ('jay3-yy/BiliPai', 'v0.4.6', COMMIT, 'raw'), 'Bangumi player source identity changed')
    need(len(manifest['files']) == len(EXPECTED) and {r['filename'] for r in manifest['files']} == EXPECTED,
         'Bangumi player source set changed')
    sources = {}
    for row in manifest['files']:
        file = root / row['filename']
        need(file.is_file() and not file.is_symlink() and Path(row['path']).name == file.name,
             'Linked or ambiguous Bangumi player source')
        raw = file.read_bytes()
        need(len(raw) == row['bytes'] and sha(raw) == row['sha256'], 'Bangumi player bytes changed: ' + file.name)
        need(hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest() == row['gitBlob'],
             'Bangumi player Git blob changed: ' + file.name)
        need(b'\r' not in raw, 'Bangumi player source must be LF only: ' + file.name)
        sources[file.name] = raw.decode('utf8')
    licence = root / 'LICENSE'
    need(not licence.is_symlink() and sha(licence.read_bytes()) == manifest['license']['sha256'],
         'Bangumi player license changed')
    return sources, manifest


def validate_outputs(repo, *outputs):
    fixed(repo)
    root = (Path(repo) / ARCHIVE).resolve()
    for output in outputs:
        if output is None:
            continue
        target = Path(output).resolve()
        need(not (target.is_relative_to(root) or root.is_relative_to(target)), 'Output overlaps Bangumi player originals')


def restore(value, proof):
    need(sha(value) == proof['afterSHA256'], 'Bangumi player final output differs from its receipt')
    lines = value.splitlines(keepends=True)
    for hunk in reversed(proof['hunks']):
        first, last = hunk['afterLines']
        need(''.join(lines[first:last]) == hunk['after'], 'Bangumi player inverse span changed')
        lines[first:last] = hunk['before'].splitlines(keepends=True)
    result = ''.join(lines)
    need(sha(result) == proof['beforeSHA256'], 'Bangumi player predecessor inverse failed')
    return result


def windows_lines(hunks, original):
    """Lines the hunks add that the v0.4.6 original does not contain: the Windows bindings."""
    known = set(original.splitlines())
    found = []
    for hunk in hunks:
        added = Counter(hunk['after'].splitlines()) - Counter(hunk['before'].splitlines())
        for line in hunk['after'].splitlines():
            if added.get(line, 0) > 0:
                added[line] -= 1
                if line not in known:
                    found.append(line)
    return found


def _save(output, name, data):
    target = wide(Path(output) / PROOF_DIRECTORY / (name + '.json'))
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf8', newline='\n')


def apply_hunks(body, hunks):
    """Apply counted hunks in order (each anchor must occur once) and return the text and the byte trace."""
    trace = []
    for hunk in hunks:
        need(body.count(hunk['before']) == 1, 'Bangumi player VM anchor changed: ' + hunk['label'])
        at = body.index(hunk['before'])
        body = body[:at] + hunk['after'] + body[at + len(hunk['before']):]
        trace.append((at, hunk['before'], hunk['after']))
    return body, trace


def invert_trace(body, trace):
    for at, before, after in reversed(trace):
        need(body[at:at + len(after)] == after, 'Bangumi player VM inverse seam changed')
        body = body[:at] + before + body[at + len(after):]
    return body


def apply_view_model(repo, relative, body, output):
    """The next layer of the sole PGC producer: only the view model output is advanced."""
    if relative != VM_OUTPUT:
        return body
    sources, manifest = fixed(repo)
    spec = manifest['viewModel']
    need(spec['output'] == VM_OUTPUT and len(spec['hunks']) == VM_HUNK_COUNT, 'Bangumi player VM recipe changed')
    need(sha(body) == manifest['beforeFinalOutputs'][VM_OUTPUT]['sha256'] == spec['beforeSha256LF'],
         'Bangumi player VM predecessor changed')
    after, trace = apply_hunks(body, spec['hunks'])
    need(sha(after) == spec['afterSha256LF'], 'Bangumi player VM output changed')
    need(invert_trace(after, trace) == body, 'Bangumi player VM whole inverse failed')
    adaptations = windows_lines(spec['hunks'], sources[VM_LEAF])
    need(adaptations == spec['windowsLines'] and len(adaptations) == VM_WINDOWS_LINE_COUNT,
         'Bangumi player VM carries a line that is neither upstream text nor a declared Windows binding')
    _save(output, 'view-model', dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256, output=VM_OUTPUT,
        hunkCount=len(spec['hunks']), windowsLines=adaptations, retainedToCurrent=inverse_proof(body, after)))
    return after


def apply_retry_ui(repo, relative, body, output):
    """The next layer of the sole PGC UI producer: five counted pairs thread likeStatusKnown through the Windows bodies."""
    if relative not in UI_RETRY_HUNKS:
        return body
    sources, manifest = fixed(repo)
    need(sha(body) == manifest['beforeFinalOutputs'][relative]['sha256'], 'Bangumi like-status UI predecessor changed: ' + relative)
    after = body
    for label, old, new in UI_RETRY_HUNKS[relative]:
        after = once(after, old, new, label)
    need(sha(after) == UI_RETRY_AFTER_SHA256[relative], 'Bangumi like-status UI output changed: ' + relative)
    need(body.count('likeStatusKnown') == 0 and after.count('likeStatusKnown') ==
         sum(new.count('likeStatusKnown') for _, _, new in UI_RETRY_HUNKS[relative]),
         'Bangumi like-status UI added something else')
    _save(output, 'ui-retry-' + Path(relative).stem, dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256,
        output=relative, provenance=manifest['uiRetry']['outputs'][relative], retainedToCurrent=inverse_proof(body, after)))
    return after


def follow_preload_text(original):
    need(original.count(HEAD_END) == 1 and original.count(PAGE_COUNT_START) == 1 and original.count(PRELOAD_START) == 1,
         'Bangumi follow policy declarations changed')
    head = original[:original.index(HEAD_END)]
    middle = original[original.index(PAGE_COUNT_START):original.index(PRELOAD_START)]
    preload = original[original.index(PRELOAD_START):]
    need(preload.rstrip().endswith('}') and 'internal ' not in preload[len(PRELOAD_START):],
         'The v0.4.6 preload is no longer the last declaration of the follow policy file')
    selected = head + middle + preload
    adapted = selected
    for label, old, new in POLICY_ADAPTATIONS:
        adapted = once(adapted, old, new, label)
    return selected, adapted


def advance_follow_preload(repo, before, output):
    """before: the whole text the independent-pages producer wrote (header included, newlines folded to LF)."""
    sources, manifest = fixed(repo)
    before = before.replace('\r\n', '\n')
    need(sha(before) == manifest['beforeFinalOutputs'][POLICY_OUTPUT]['sha256'], 'Bangumi follow preload predecessor changed')
    original = sources[POLICY_LEAF]
    selected, adapted = follow_preload_text(original)
    row = next(r for r in manifest['files'] if r['filename'] == POLICY_LEAF)
    after = '// GENERATED from ' + POLICY_PATH + '; v0.4.6 ' + COMMIT[:8] + '; raw SHA-256 ' + row['sha256'] + '\n' + adapted
    need(sha(after) == POLICY_AFTER_SHA256, 'Bangumi follow preload output changed')
    _save(output, 'follow-preload', dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256, output=POLICY_OUTPUT,
        originalToWindows=inverse_proof(selected, adapted), retainedToCurrent=inverse_proof(before, after)))
    return after


def features_text(original):
    adapted = original
    for label, old, new in FEATURES_ADAPTATIONS:
        adapted = once(adapted, old, new, label)
    return adapted


def emit_features(repo, output):
    sources, manifest = fixed(repo)
    validate_outputs(repo, output)
    original = sources[FEATURES_LEAF]
    adapted = features_text(original)
    row = next(r for r in manifest['files'] if r['filename'] == FEATURES_LEAF)
    text = '// GENERATED from ' + FEATURES_PATH + '; v0.4.6 ' + COMMIT[:8] + '; raw SHA-256 ' + row['sha256'] + '\n' + adapted
    need(sha(text) == FEATURES_AFTER_SHA256, 'Bangumi player features output changed')
    target = wide(Path(output) / FEATURES_OUTPUT)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding='utf8', newline='\n')
    _save(output, 'features', dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256, output=FEATURES_OUTPUT,
        originalToWindows=inverse_proof(original, adapted)))
    return target


def emit_follow_preload_test(repo, test_root):
    sources, _ = fixed(repo)
    validate_outputs(repo, test_root)
    original = sources[TEST_LEAF]
    need(original.count('@Test') == TEST_COUNT, 'Original Bangumi follow policy test count changed')
    body = original
    for old, new in JUNIT_IMPORTS:
        body = once(body, old, new, old.strip())
    need('org.junit' not in body, 'A JUnit4 reference survived the kotlin.test rewrite')
    need(sha(body) == TEST_AFTER_SHA256, 'Bangumi follow policy test output changed')
    target = wide(Path(test_root) / TEST_OUTPUT)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(body, encoding='utf8', newline='\n')
    _save(test_root, 'follow-policy-test', dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256,
        output=TEST_OUTPUT, originalToWindows=inverse_proof(original, body)))
    return target
