"""Fixed v029 chat bodies; the existing message producer is their sole producer."""
from pathlib import Path
import difflib
import hashlib
import json

COMMIT = 'a4b77f894d0a2dd26c0b9fc144b8adb88ac05480'
PINS = {
    'ChatTimelinePolicy.kt': ('07c546fb7606cbad712bb891d9c6646fe59464b858944449bef67070c69a8ed0', '7df65fe0ef0aeac2ee06ae1a6e7529dd013c6c03', 1082),
    'ChatViewModel.kt': ('4ff7fff77b68f81d26161b08b0a17a21f276617ecae1d8da29cc96e944c04c2e', 'de285bcf822fa148d2545baba2caea5427fafdd5', 20503),
    'ChatScreen.kt': ('1a680aa9a922dca724c943f7c6cadbe1f1691c123995e3e9530e554ba0aa6b2f', '5906cd542df5e37a2bc29ec1aca0e0e0639861ca', 65055),
    'ChatTimelinePolicyTest.kt': ('139ad0a5bfe33388153a2c949fd737ee3b634552d236a8e6802f3061c010283b', 'cb1db0725445d40341080eb0b72515a15a134122', 1638),
    'ListLoadError.kt': ('6fc1d252d9d1d02eff83cf1d9eabd6ff0a645c4876aab81fa225148119a31a32', 'eb7f6c04af7910b7a5c4ee4b0b494c33a4a028bd', 1150),
}


def read(repo, name):
    root = Path(repo) / 'desktop/upstream-slices/v029-chat-timeline'
    manifest = json.loads((root / 'manifest.json').read_text('utf8'))
    assert manifest['commit'] == COMMIT and manifest['hashNormalization'] == 'raw'
    assert manifest['canonicalBaselineAdvanced'] is False
    assert {r['archiveFile'] for r in manifest['files']} == set(PINS)
    sha, blob, size = PINS[name]
    raw = (root / name).read_bytes()
    row = next(r for r in manifest['files'] if r['archiveFile'] == name)
    assert (row['sha256Raw'], row['gitBlob'], row['bytes']) == (sha, blob, size)
    assert len(raw) == size and hashlib.sha256(raw).hexdigest() == sha
    assert hashlib.sha1(b'blob ' + str(size).encode() + b'\0' + raw).hexdigest() == blob
    return raw.decode('utf8').replace('\r\n', '\n')


def replace(text, before, after, count=1):
    assert text.count(before) == count, (before, text.count(before), count)
    return text.replace(before, after, count)


def adapt_refresh(text):
    # The visible Compose coroutine borrows the retained message child's real request
    # context. Closing/hiding it cancels the same HTTP call and its final publication.
    text = replace(text,
        '        loadLatestMessages(showLoading = false, scrollToLatest = false)',
        '        owner.awaitRead("chat-refresh") {\n            loadLatestMessages(showLoading = false, scrollToLatest = false)\n        }')
    # The desktop mutation gate serializes rapid clicks and can refuse a hidden page.
    # Set the pending flag only after that real gate has accepted the original body.
    return replace(text,
        '        _uiState.update { it.copy(isSending = true, sendError = null) }\n        \n        owner.launchMutation("chat-send") {',
        '        owner.launchMutation("chat-send") {\n            _uiState.update { it.copy(isSending = true, sendError = null) }')


def adapt_screen(text, function):
    text = replace(text, '''    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {''', '''    val refreshOwner = LocalDesktopMessagePageOwner.current
    val windowFocused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    val pageVisible = refreshOwner.isVisible()
    LaunchedEffect(viewModel, refreshOwner, pageVisible, windowFocused) {
        if (pageVisible && windowFocused) {''')
    # Windows PiP is a separate native window; this page has no mobile dock occlusion.
    body = function(text, 'resolveChatMiniPlayerBottomAvoidance')
    text = replace(text, body, '\nprivate fun resolveChatMiniPlayerBottomAvoidance(): Dp {\n    return AppSpacingTokens.None\n}')
    # The installed canonical field renderer predates these two Miuix-only arguments.
    # Keep its existing shell shape/colors; this does not claim the new field styling.
    text = replace(text, '                miuixCornerRadius = CHAT_INPUT_DOCK_HEIGHT / 2,\n', '')
    text = replace(text, '                miuixContainerColor = Color.Transparent,\n', '')
    return text


def record_adaptation(repo, out, name, adapted):
    """Keep an exact reviewable edit ledger and prove full original reconstruction.

    This is provenance, not runtime validation. The existing platform mappings are
    deliberately shared with the sole message producer, not a second VM generator.
    """
    original = read(repo, name)
    left, right = original.splitlines(True), adapted.splitlines(True)
    changes = [dict(originalStart=a, originalEnd=b, adaptedStart=c, adaptedEnd=d,
                    before=''.join(left[a:b]), after=''.join(right[c:d]))
               for tag, a, b, c, d in difflib.SequenceMatcher(None, left, right, autojunk=False).get_opcodes()
               if tag != 'equal']
    inverse = right[:]
    for row in reversed(changes):
        assert ''.join(inverse[row['adaptedStart']:row['adaptedEnd']]) == row['after']
        inverse[row['adaptedStart']:row['adaptedEnd']] = row['before'].splitlines(True)
    assert ''.join(inverse) == original
    target = Path(out) / (name + '.v029-adaptation.json')
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(dict(commit=COMMIT, source=name,
        originalSha256LF=hashlib.sha256(original.encode()).hexdigest(),
        adaptedSha256LF=hashlib.sha256(adapted.encode()).hexdigest(), edits=changes), indent=2) + '\n', encoding='utf8')
