"""Roadmap slice dmk-command-merge-hot-banners: v0.4.6 counted hot banners and attention command cards.

Upstream f31c3b83 (0.3.8, fit counted banners and exclude duplicate rendering) and 26ea45ac/1f2c3872 (0.3.8, anchor
merge windows and reflect command card state), at v0.4.6 (d6bf8b1655ae910999860bf13e65c8fcea5b345b). The danmaku
merger (DanmakuMerger.kt), the 15 s window default and the one second floor are already carried by v038-danmaku-merge
and the Windows DanmakuSettings; what this module adds on the existing producers is:

* the three whole v0.4.6 hot-banner files (policy, bar, count text) and the policy test, as one layer on the v027 hot
  danmaku producer (one hash-pinned Windows port each, none for the policy and its test);
* the whole v0.4.6 command overlay as one layer on the v029 vote producer, with the same hash-pinned vote ports, plus
  the v0.4.6 canSubmitVideoTripleAction guard as its own generated file (the follow guard is already emitted by the
  v042 owner follow layer);
* three counted hunks on the generated player section (hot bar presentation callback, no fixed bar height, real
  like/coin/favorite state for the command cards);
* the v0.4.6 isTripleSubmitting state and the guarded doTripleAction on the generated engagement view model.

Android-only parts are not carried: the ByteDance engine hunks (DanmakuVisibility and the draw/hit tests), the
DanmakuManager hunks and the Android layouts. The Windows renderer applies the same hide rule in
desktop/src/main/kotlin/com/bilipai/desktop/danmaku/DesktopHotDanmakuSelection.kt, and
DesktopHotPresentationRelay.kt offers a presentation report again when the overlay rejected it (the v0.4.6 bar reports
once and drops identical reports).

Deferred, with the follow-up slice each would need (nothing here depends on them):

* The f31c3b83 text hunk of VideoSettingsPanel.kt (title "顶部计数弹幕" becomes "显示计数弹幕" and both subtitles change
  to "按内容长度自动显示 1～3 条完整横幅，均匀排布" / "已关闭，普通弹幕照常显示") is NOT carried. VideoSettingsPanel.kt is the
  whole v037 player-closure file with its own pins in the v037 player-controls producer and verifier, so a layer on
  that chain belongs to its own slice. Until then the in-player settings panel keeps the old wording while the Windows
  danmaku panel (v038) already shows the new label for the same setting.
* The 26ea45ac plumbing that carries isFollowSubmitting and isTripleSubmitting through the player layouts,
  VideoDetailScreenStateHolder and VideoPlayerSectionContracts is NOT carried. The native command popup forwards both
  flags; the in-player Section overlay keeps their false defaults, so its card cannot show the in-flight state (double
  submits are still blocked by the binding and the view model guards). A follow-up slice adds the two fields to the
  section state and forwards them in a fourth counted section hunk.

Source only. Nothing here compiles or runs a JVM.
"""
from pathlib import Path
import hashlib
import json

from v044_favorite_detail import inverse_proof

COMMIT = 'd6bf8b1655ae910999860bf13e65c8fcea5b345b'
ARCHIVE = 'desktop/upstream-slices/v046-danmaku-hot-command'
MANIFEST_SHA256 = '097217edeff6588ba4f0c47c149912d8869754763714a86f2c9711c5324467cb'
EXPECTED = {'HotDanmakuPolicy.kt', 'HotDanmakuBar.kt', 'AnimatedCountText.kt', 'HotDanmakuPolicyTest.kt',
            'CommandDanmakuOverlay.kt', 'CommandDanmakuOverlayPolicyTest.kt', 'VideoActionFeedbackPolicy.kt'}
HOT_LEAVES = ('HotDanmakuPolicy.kt', 'HotDanmakuBar.kt', 'AnimatedCountText.kt', 'HotDanmakuPolicyTest.kt')
OVERLAY_RELATIVE = 'com/android/purebilibili/feature/video/ui/overlay/DesktopOriginalCommandDanmakuOverlay.kt'
TRIPLE_POLICY_RELATIVE = 'com/android/purebilibili/feature/video/ui/feedback/DesktopOriginalTripleActionPolicy.kt'
VM_RELATIVE = 'com/android/purebilibili/feature/video/viewmodel/VideoEngagementViewModel.kt'
VM_PROOF_DIRECTORY = 'v046-triple-submission-proofs'
SECTION_PROOF = 'v046-danmaku-hot-command-section-proof.json'
OVERLAY_PROOF = 'v046-command-overlay-proof.json'
POLICY_PROOF = 'v046-triple-action-policy-proof.json'
V037_OVERLAY_COMMIT = '8b47769d22c0d5bc88ae5248cee4aa04ff10d62c'

# Windows ports of the whole hot-banner originals. Each tuple is (before, after, label); count is always one.
HOT_PORTS = {
    'HotDanmakuBar.kt': [
        ('import androidx.media3.common.Player\n',
         'import com.bilipai.desktop.ui.DesktopHotDanmakuPlayer as Player\n',
         'The existing source-owned native clock replaces the Media3 player type'),
        ('import kotlinx.coroutines.isActive\n',
         'import kotlinx.coroutines.isActive\nimport com.bilipai.desktop.ui.desktopCommandHitRegion\n',
         'Hit region import for the native command popup'),
        ('                            modifier = Modifier\n'
         '                                .padding(1.dp),\n'
         '                            verticalAlignment = Alignment.CenterVertically,\n'
         '                        ) {\n'
         '                            AppText(\n',
         '                            modifier = Modifier\n'
         '                                .desktopCommandHitRegion("hot-danmaku-item-" + item.danmakuId)\n'
         '                                .padding(1.dp),\n'
         '                            verticalAlignment = Alignment.CenterVertically,\n'
         '                        ) {\n'
         '                            AppText(\n',
         'Only the laid-out banner entries take mouse input in the shaped native popup; the gaps between them pass through'),
    ],
    'AnimatedCountText.kt': [
        ('import androidx.compose.ui.graphics.asComposeRenderEffect\n',
         'import androidx.compose.ui.graphics.BlurEffect\nimport androidx.compose.ui.graphics.TileMode\n',
         'Public Compose blur effect replaces the Android RenderEffect factory'),
        ('import android.os.Build\n', 'import com.bilipai.desktop.ui.desktopDetailRenderEffectsSupported\n',
         'Windows capability predicate replaces the SDK level check'),
        ('import android.graphics.RenderEffect\n', '// The public Compose/Skia blur effect replaces the Android RenderEffect factory.\n',
         'Android RenderEffect import removed'),
        ('import android.graphics.Shader\n', '// TileMode.Decal preserves the original transparent out-of-bounds sampling.\n',
         'Android Shader import removed'),
        ('Build.VERSION.SDK_INT >= Build.VERSION_CODES.S', 'desktopDetailRenderEffectsSupported()',
         'Windows capability predicate'),
        ('RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.DECAL).asComposeRenderEffect()',
         'BlurEffect(radiusX = radius, radiusY = radius, edgeTreatment = TileMode.Decal)',
         'Compose blur effect with the original decal edge treatment'),
    ],
    'HotDanmakuPolicy.kt': [],
    'HotDanmakuPolicyTest.kt': [],
}

# Counted hunks on the generated player section (after the v037 hot bar mapping and the v029 command port).
SECTION_HUNKS = [
    ('import com.android.purebilibili.feature.video.ui.overlay.HOT_DANMAKU_BAR_HEIGHT_DP\n',
     '// The v0.4.6 bar measures itself; the fixed HOT_DANMAKU_BAR_HEIGHT_DP constant no longer exists.\n',
     'The v0.4.6 bar measures itself, so the fixed bar height constant no longer exists'),
    ('                            onVisibilityChange = { visible ->\n'
     '                                danmakuManager.reserveHotDanmakuBarHeight(\n'
     '                                    if (visible) HOT_DANMAKU_BAR_HEIGHT_DP * density.density else 0f\n'
     '                                )\n'
     '                            },\n',
     '                            onPresentationChange = danmakuManager::updateHotDanmakuPresentation,\n',
     'v0.4.6 hot bar reports its measured height with the shown entries (f31c3b83 VideoPlayerSection hunk)'),
    ('                    isFollowing = isFollowed,\n'
     '                    followStatusKnown = followStatusKnown,\n'
     '                    likeStatusKnown = likeStatusKnown,\n'
     '                    modifier = Modifier.fillMaxSize()\n',
     '                    isFollowing = isFollowed,\n'
     '                    followStatusKnown = followStatusKnown,\n'
     '                    likeStatusKnown = likeStatusKnown,\n'
     '                    isLiked = isLiked,\n'
     '                    isCoined = isCoined,\n'
     '                    isFavorited = isFavorited,\n'
     '                    modifier = Modifier.fillMaxSize()\n',
     'Attention cards reflect the real like, coin and favorite state of the section (26ea45ac VideoPlayerSection hunk)'),
]

# The engagement view model: v0.4.6 isTripleSubmitting and the guarded doTripleAction (26ea45ac), on the Windows
# admission/commit pattern that the follow submission already uses.
VM_IMPORT_BEFORE = 'import com.android.purebilibili.feature.video.ui.feedback.canSubmitVideoFollowAction\n'
VM_IMPORT_AFTER = VM_IMPORT_BEFORE + 'import com.android.purebilibili.feature.video.ui.feedback.canSubmitVideoTripleAction\n'
VM_STATE_BEFORE = '    val isFollowSubmitting: Boolean = false,\n'
VM_STATE_AFTER = VM_STATE_BEFORE + '    val isTripleSubmitting: Boolean = false,\n'
VM_HEAD_BEFORE = '''        val session = interactionSession()
        viewModelScope.launch(presentation?.context ?: EmptyCoroutineContext) {
            currentCoroutineContext().ensureActive(); environment.assertOwned();

            likeToggleMutex.withLock {
            if (!isCurrent(state, session) || !_uiState.value.likeStatusKnown) return@withLock
            val actionState = _uiState.value
            emitMessage("正在三连")
'''
VM_HEAD_AFTER = '''        if (!canSubmitVideoTripleAction(
                statusKnown = state.likeStatusKnown,
                isLiked = state.isLiked,
                isCoined = state.coinCount > 0,
                isFavorited = state.isFavorited,
                isSubmitting = state.isTripleSubmitting,
            )
        ) return
        val session = interactionSession()
        var tripleAdmitted = false
        val beginTriple: () -> Unit = {
            environment.commit {
                if (isCurrent(state, session) && !_uiState.value.isTripleSubmitting) {
                    updateOwned { it.copy(isTripleSubmitting = true) }
                    tripleAdmitted = true
                }
            }
        }
        if (presentation == null) beginTriple() else presentation.admit(beginTriple)
        if (!tripleAdmitted) return
        val tripleFinished = java.util.concurrent.atomic.AtomicBoolean(false)
        fun completeTripleSubmission() {
            if (!tripleFinished.compareAndSet(false, true)) return
            environment.completeTripleSubmission(_uiState, state.subject)
        }
        val tripleJob = try { viewModelScope.launch(presentation?.context ?: EmptyCoroutineContext) {
            currentCoroutineContext().ensureActive(); environment.assertOwned();
            try {
            likeToggleMutex.withLock {
            if (!isCurrent(state, session) || !_uiState.value.likeStatusKnown) return@withLock
            val latest = _uiState.value
            if (!canSubmitVideoTripleAction(
                    statusKnown = latest.likeStatusKnown,
                    isLiked = latest.isLiked,
                    isCoined = latest.coinCount > 0,
                    isFavorited = latest.isFavorited,
                    isSubmitting = false,
                )
            ) return@withLock
            val actionState = _uiState.value
            emitMessage("正在三连")
'''
VM_TAIL_BEFORE = '''emitMessage(it.message ?: "三连失败")
                }
            }
        }
    }
'''
VM_TAIL_AFTER = '''emitMessage(it.message ?: "三连失败")
                }
            }
            } finally {
                completeTripleSubmission()
            }
        } } catch (failure: Throwable) {
            completeTripleSubmission()
            throw failure
        }
        tripleJob.invokeOnCompletion { completeTripleSubmission() }
    }
'''
VM_HUNKS = [
    (VM_IMPORT_BEFORE, VM_IMPORT_AFTER, 'Original triple submit guard import'),
    (VM_STATE_BEFORE, VM_STATE_AFTER, 'Original isTripleSubmitting state field'),
    (VM_HEAD_BEFORE, VM_HEAD_AFTER, 'Original pre-launch and in-lock triple submit guards, admitted start of the flag'),
    (VM_TAIL_BEFORE, VM_TAIL_AFTER, 'Original finally block that clears the flag, also for a job cancelled before it starts'),
]


def sha(value):
    return hashlib.sha256(value.encode('utf8') if isinstance(value, str) else value).hexdigest()


def need(condition, message):
    if not condition:
        raise ValueError(message)


def fixed(repo):
    """The archive, byte-exact: leaf set, manifest digest, identity, per-leaf length/digest/blob, licence."""
    root = Path(repo) / ARCHIVE
    need(root.is_dir() and not root.is_symlink(), 'Missing v0.4.6 hot banner and command card inputs')
    need({p.name for p in root.iterdir()} == EXPECTED | {'manifest.json', 'LICENSE'},
         'Hot banner and command card input leaves changed')
    raw = (root / 'manifest.json').read_bytes()
    need(sha(raw) == MANIFEST_SHA256, 'Hot banner and command card manifest changed')
    manifest = json.loads(raw)
    need((manifest['repository'], manifest['tag'], manifest['commit'], manifest['hashNormalization']) ==
         ('jay3-yy/BiliPai', 'v0.4.6', COMMIT, 'raw'), 'Hot banner and command card source identity changed')
    need(len(manifest['files']) == len(EXPECTED) and {r['filename'] for r in manifest['files']} == EXPECTED,
         'Hot banner and command card source set changed')
    sources = {}
    for row in manifest['files']:
        file = root / row['filename']
        need(file.is_file() and not file.is_symlink() and Path(row['path']).name == file.name,
             'Linked or ambiguous hot banner source')
        body = file.read_bytes()
        need(len(body) == row['bytes'] and sha(body) == row['sha256'], 'Hot banner source bytes changed: ' + file.name)
        need(hashlib.sha1(b'blob ' + str(len(body)).encode() + b'\0' + body).hexdigest() == row['gitBlob'],
             'Hot banner source Git blob changed: ' + file.name)
        sources[file.name] = body.decode('utf8').replace('\r\n', '\n')
    licence = root / 'LICENSE'
    need(licence.is_file() and not licence.is_symlink() and sha(licence.read_bytes()) == manifest['license']['sha256'],
         'Hot banner licence changed')
    return sources, manifest


def validate_outputs(repo, *outputs):
    fixed(repo)
    root = (Path(repo) / ARCHIVE).resolve()
    for output in outputs:
        if output is None:
            continue
        target = Path(output).resolve()
        need(not (target.is_relative_to(root) or root.is_relative_to(target)),
             'Output overlaps the v0.4.6 hot banner and command card inputs')


def restore(value, proof):
    """Apply an inverse_proof backwards: the exact predecessor of an advanced body."""
    need(sha(value) == proof['afterSHA256'], 'Advanced body differs from its receipt')
    lines = value.splitlines(keepends=True)
    for hunk in reversed(proof['hunks']):
        first, last = hunk['afterLines']
        need(''.join(lines[first:last]) == hunk['after'], 'Advanced body inverse span changed')
        lines[first:last] = hunk['before'].splitlines(keepends=True)
    result = ''.join(lines)
    need(sha(result) == proof['beforeSHA256'], 'Advanced body predecessor inverse failed')
    return result


def _before(repo, key, body):
    sources, manifest = fixed(repo)
    need(sha(body) == manifest['beforeFinalOutputs'][key]['sha256'], 'Hot banner or command card predecessor changed: ' + key)
    return sources


def _once(text, old, new, label):
    need(text.count(old) == 1, 'Hot banner or command card anchor changed: ' + label)
    return text.replace(old, new, 1)


def _edits(original, ports):
    text, edits = original, []
    for old, new, label in ports:
        text = _once(text, old, new, label)
        edits.append(dict(before=old, after=new, label=label))
    recovered = text
    for edit in reversed(edits):
        recovered = _once(recovered, edit['after'], edit['before'], edit['label'])
    need(recovered == original, 'Whole original inverse failed')
    return text, edits


def hot_prefix(upstream_path):
    return ('// Fixed original ' + COMMIT + ':' + upstream_path + '\n'
            '// Complete original file; only explicit reversible Windows platform ports follow.\n')


def advance_hot(repo, leaf, before):
    """The v027 adapted body of one hot-banner file becomes the whole v0.4.6 file with its Windows port."""
    sources = _before(repo, 'hot/' + leaf, before)
    original = sources[leaf]
    adapted, edits = _edits(original, HOT_PORTS[leaf])
    return adapted, dict(
        upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256, wholeOriginal=True,
        originalSHA256=sha(original), adaptedSHA256=sha(adapted), windowsEdits=edits,
        originalToWindows=inverse_proof(original, adapted), retainedToCurrent=inverse_proof(before, adapted),
        jvmTestsExecuted=False)


def advance_command_overlay(repo, before):
    """The v029 adapted v0.3.7 overlay becomes the whole v0.4.6 overlay with the same hash-pinned vote ports."""
    import v029_command_vote as vote
    sources = _before(repo, 'votes/DesktopOriginalCommandDanmakuOverlay.kt', before)
    original = sources['CommandDanmakuOverlay.kt']
    edits = []
    adapted = vote.adapt_overlay(original, edits)
    need(vote.inverse(adapted, edits) == original, 'Whole v0.4.6 command overlay inverse failed')
    for token in ('canSubmitVideoFollowAction(', 'canSubmitVideoTripleAction(', 'isTripleSubmitting: Boolean'):
        need(token in adapted, 'The v0.4.6 command overlay lost ' + token)
    return adapted, dict(
        upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256, wholeOriginal=True,
        originalSHA256=sha(original), adaptedSHA256=sha(adapted),
        windowsEdits=[dict(before=b, after=a, count=c) for b, a, c in edits],
        originalToWindows=inverse_proof(original, adapted), retainedToCurrent=inverse_proof(before, adapted),
        jvmTestsExecuted=False)


def emit_command_overlay(repo, output, body, proof):
    """One overlay file, with the v0.4.6 identity in the header and the carried v0.3.7 layer named as its predecessor."""
    sources, manifest = fixed(repo)
    row = next(r for r in manifest['files'] if r['filename'] == 'CommandDanmakuOverlay.kt')
    target = Path(output) / OVERLAY_RELATIVE
    target.parent.mkdir(parents=True, exist_ok=True)
    header = ('// GENERATED from ' + row['path'] + '; do not edit.\n'
              '// Fixed upstream ' + COMMIT + '; raw SHA-256: ' + row['sha256'] + '; Git blob: ' + row['gitBlob'] + '\n'
              '// Predecessor layer: the v0.3.7 carry ' + V037_OVERLAY_COMMIT + ' (v037_command_overlay.py), advanced whole.\n')
    target.write_text(header + body, encoding='utf8', newline='\n')
    record = dict(proof, output=OVERLAY_RELATIVE, header=header, outputFileSHA256=sha(header + body))
    (Path(output) / OVERLAY_PROOF).write_text(json.dumps(record, ensure_ascii=False, indent=2) + '\n',
                                              encoding='utf8', newline='\n')
    return record


def triple_policy(repo):
    """The complete original canSubmitVideoTripleAction declaration."""
    sources, _ = fixed(repo)
    text = sources['VideoActionFeedbackPolicy.kt']
    start = 'internal fun canSubmitVideoTripleAction('
    need(text.count(start) == 1, 'Original triple submit guard anchor changed')
    begin = text.index(start)
    declaration = text[begin:text.index('\n\n', begin)]
    need(declaration.count('fun ') == 1 and declaration.endswith('!(isLiked && isCoined && isFavorited)'),
         'Original triple submit guard declaration changed')
    return declaration


def emit_triple_policy(repo, output):
    declaration = triple_policy(repo)
    _, manifest = fixed(repo)
    row = next(r for r in manifest['files'] if r['filename'] == 'VideoActionFeedbackPolicy.kt')
    body = ('// GENERATED from ' + row['path'] + '; do not edit.\n'
            '// Fixed upstream ' + COMMIT + '; raw SHA-256: ' + row['sha256'] + '; Git blob: ' + row['gitBlob'] + '\n'
            'package com.android.purebilibili.feature.video.ui.feedback\n\n' + declaration + '\n')
    target = Path(output) / TRIPLE_POLICY_RELATIVE
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(body, encoding='utf8', newline='\n')
    proof = dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256, output=TRIPLE_POLICY_RELATIVE,
                 originalDeclarationSHA256=sha(declaration), outputFileSHA256=sha(body), wholeDeclaration=True,
                 jvmTestsExecuted=False)
    (Path(output) / POLICY_PROOF).write_text(json.dumps(proof, ensure_ascii=False, indent=2) + '\n',
                                             encoding='utf8', newline='\n')
    return proof


def advance_section(repo, text, output):
    """Counted hunks on the generated player section; receipts are written next to it."""
    fixed(repo)
    before, edits = text, []
    for old, new, label in SECTION_HUNKS:
        text = _once(text, old, new, label)
        edits.append(dict(before=old, after=new, label=label))
    recovered = text
    for edit in reversed(edits):
        recovered = _once(recovered, edit['after'], edit['before'], edit['label'])
    need(recovered == before, 'Player section hunks were not reversible')
    proof = dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256, edits=edits, inputSHA256=sha(before),
                 outputSHA256=sha(text), fullInputInverseRecovered=True, jvmTestsExecuted=False,
                 notForwardedToTheSection=['isFollowSubmitting', 'isTripleSubmitting'])
    (Path(output) / SECTION_PROOF).write_text(json.dumps(proof, ensure_ascii=False, indent=2) + '\n',
                                              encoding='utf8', newline='\n')
    return text


def apply_triple_submission(repo, relative, text, output):
    """v0.4.6 isTripleSubmitting on the generated engagement view model; every other output is returned unchanged."""
    if relative != VM_RELATIVE:
        return text
    _before(repo, 'units/VideoEngagementViewModel.kt', text)
    need('isTripleSubmitting' not in text and 'canSubmitVideoTripleAction' not in text,
         'The engagement view model already carries the triple submit guard')
    result, edits = _edits(text, VM_HUNKS)
    need(result.count('isTripleSubmitting = true') == 1 and result.count('canSubmitVideoTripleAction(') == 2,
         'Triple submit guard result changed')
    proof = dict(upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256, upstreamPath=(
        'app/src/main/java/com/android/purebilibili/feature/video/viewmodel/VideoEngagementViewModel.kt'),
        edits=edits, inputSHA256=sha(text), outputSHA256=sha(result), fullInputInverseRecovered=True,
        windowsAdaptation='The flag starts inside the admitted presentation commit and is released by '
                          'DesktopOriginalVideoEngagementEnvironment.completeTripleSubmission, like the follow flag.',
        mainCompiled=False, applicationExecuted=False)
    target = Path(output) / VM_PROOF_DIRECTORY / (Path(relative).name + '.json')
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(proof, ensure_ascii=False, indent=2) + '\n', encoding='utf8', newline='\n')
    return result
