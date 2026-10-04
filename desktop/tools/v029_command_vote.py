"""Fixed v029 command-card source selection; canonical v025 identities stay intact."""
from pathlib import Path
import hashlib
import json
import re

COMMIT = "a4b77f894d0a2dd26c0b9fc144b8adb88ac05480"
BASE = "app/src/main/java/com/android/purebilibili/"
POLICY = BASE + "feature/video/danmaku/CommandDanmakuPolicy.kt"
OVERLAY = BASE + "feature/video/ui/overlay/CommandDanmakuOverlay.kt"
STATE = BASE + "feature/video/ui/overlay/CommandDanmakuOverlayState.kt"
SECTION = BASE + "feature/video/ui/section/VideoPlayerSection.kt"
REPOSITORY = BASE + "data/repository/DanmakuRepository.kt"
SUMMARY = "core-data/src/main/java/com/android/purebilibili/data/model/response/SendDanmakuResponse.kt"
PINS = {
    POLICY: (18038, "755f80408756e2214cb964902dc92cda71466599626cdfbed6f71b02d31b4001", "49d6f620dbf8bbd634d79fbe12baccf6cdaac401"),
    OVERLAY: (41379, "99eb78b037a3e9bab080de813eb9232d0548d12ff76fce10e26666a14b2677b8", "35d4e97ed166fa8ac1d5dcee58bb865c7f4e66ea"),
    STATE: (3267, "e272a64513b7b65f2904db285c8a8e4f9058e6cbd1a093c8f0d6dc5e62270e76", "39aeb9b5c3ac68ac7f87538d6270395e391d422e"),
    SECTION: (310684, "b6de3ebf7a828092c103922a830a576d7a4ed3075513032956cf50fea234c8f9", "4856f6450972098a241723b0ff6b6673ceaf2d62"),
    REPOSITORY: (37240, "18cffbda990c2e513c4434e68219d75f96b5cdfccdfa4366bb2481005c6d03a1", "0d27799985b3ad8c5e8734df3fe6c088c12fd6c1"),
    SUMMARY: (4296, "d87a096556e6b37f7ab8f7cbfa51f7c8dfbbb3d1f7cce1abf848d92a2b912329", "3bde3109afcfcafaba499b64dd94f7cdf504aab7"),
}


def read(repo, path):
    root = Path(repo) / "desktop/upstream-slices/v029-command-vote"
    manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
    assert manifest["schemaVersion"] == 1 and manifest["upstreamCommit"] == COMMIT
    assert manifest["hashNormalization"] == "raw"
    rows = manifest["files"]
    assert len(rows) == len(PINS) and {r["path"] for r in rows} == set(PINS)
    for row in rows:
        size, sha, blob = PINS[row["path"]]
        assert (row["bytes"], row["sha256"], row["gitBlob"], row["commit"]) == (size, sha, blob, COMMIT)
    target = root / path
    assert not target.is_symlink() and target.resolve().is_relative_to(root.resolve())
    raw = target.read_bytes()
    size, sha, blob = PINS[path]
    assert len(raw) == size and hashlib.sha256(raw).hexdigest() == sha, path + " raw source changed"
    assert hashlib.sha1(b"blob " + str(size).encode() + b"\0" + raw).hexdigest() == blob
    return raw.decode("utf-8").replace("\r\n", "\n")


def replace(text, before, after, edits, count=1):
    assert text.count(before) == count, before
    edits.append((before, after, count))
    return text.replace(before, after, count)


def inverse(text, edits):
    for before, after, count in reversed(edits):
        assert text.count(after) == count, after
        text = text.replace(after, before, count)
    return text


def adapt_overlay(original, edits):
    text = replace(original, "import androidx.media3.common.Player", "import com.bilipai.desktop.player.MpvPlayer", edits)
    text = replace(text, "player: Player,", "player: MpvPlayer,", edits)
    text = replace(text, "player.currentPosition", "(player.state.value.positionSeconds * 1000.0).toLong()", edits, 2)
    text = replace(text, "import com.android.purebilibili.data.repository.DynamicVoteRepository\n",
        "import com.bilipai.desktop.ui.LocalDesktopWindowsCommandVotePlatform\n"
        "import com.bilipai.desktop.ui.desktopCommandHitRegion\n"
        "import kotlinx.coroutines.currentCoroutineContext\nimport kotlinx.coroutines.job\n", edits)
    text = replace(text, "            .onSizeChanged { measuredCardHeightPx = it.height }\n",
        "            .onSizeChanged { measuredCardHeightPx = it.height }\n            .desktopCommandHitRegion(item.id)\n", edits)
    text = replace(text, "    val voteId = item.voteId.toLongOrNull()\n",
        "    val platform = LocalDesktopWindowsCommandVotePlatform.current\n"
        "    val pendingKey = remember { Any() }\n    val voteId = item.voteId.toLongOrNull()\n", edits)
    text = replace(text, "        isLoading = true\n        loadError = null\n",
        "        val caller = currentCoroutineContext().job\n"
        "        val pending = platform.capturePending(pendingKey)\n"
        "        if (!platform.publish(caller) { isLoading = true; loadError = null }) return@LaunchedEffect\n", edits)
    text = replace(text, "DynamicVoteRepository.getVoteInfo(voteId)", "platform.getVoteInfo(voteId)", edits)
    text = replace(text, "onSuccess = { loadedInfo = it },", "onSuccess = { result -> platform.publish(caller) { loadedInfo = result } },", edits)
    text = replace(text, 'onFailure = { loadError = it.message ?: "投票选项加载失败" },',
        'onFailure = { error -> platform.publish(caller) { loadError = error.message ?: "投票选项加载失败" } },', edits)
    text = replace(text, "            isLoading = false\n", "            platform.releasePending(pending) { isLoading = false }\n", edits)
    assert inverse(text, edits) == original
    return text


def submission_body(original, edits):
    start = "                    onVoteSubmit = { item, option, optionIndex ->\n"
    end = "                    },\n                    isFollowing = isFollowed,"
    assert original.count(start) == original.count(end) == 1
    body = original.split(start, 1)[1].split(end, 1)[0]
    # Keep every original branch, accepted flag, finally block and message.
    first_line = "                        val success = uiState as? VideoPlaybackUiState.Success\n"
    text = replace(body, first_line,
        "                        val pending = platform.capturePending(commandState to item.id)\n" + first_line, edits)
    text = replace(text, "com.android.purebilibili.data.repository.DanmakuRepository.submitGradeDanmaku(", "platform.submitGradeDanmaku(", edits)
    text = replace(text, "com.android.purebilibili.data.repository.DanmakuRepository.getGradeDanmakuSummary(", "platform.getGradeDanmakuSummary(", edits)
    text = replace(text, "com.android.purebilibili.data.repository.DynamicVoteRepository.submitVote(", "platform.submitVote(", edits)
    # Both original launches enter finally even when cancelled before dispatch.
    text = replace(text, "settingsScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {\n",
        "settingsScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {\n"
        "                                    val caller = kotlinx.coroutines.currentCoroutineContext().job\n", edits, 2)
    for old, new in [
        ("commandState.completeGradeSubmission(item, option)", "platform.publish(caller) { commandState.completeGradeSubmission(item, option) }"),
        ("commandState.updateGradeSummary(item.id, it)", "platform.publish(caller) { commandState.updateGradeSummary(item.id, it) }"),
        ("commandState.completeVoteSubmission(item, option)", "platform.publish(caller) { commandState.completeVoteSubmission(item, option) }"),
        ("commandState.endSubmission(item.id)", "platform.releasePending(pending) { commandState.endSubmission(item.id) }"),
    ]:
        text = replace(text, old, new, edits, 4 if "endSubmission" in old else 1)
    # Platform feedback is bounded admission; cancellation never becomes a toast.
    pattern = re.compile(r'android\.widget\.Toast\.makeText\(\n\s+context,\n(?P<message>.*?)\s*,\n\s+android\.widget\.Toast\.LENGTH_SHORT\n\s+\)\.show\(\)', re.S)
    matches = list(pattern.finditer(text))
    assert len(matches) == 6
    for match in reversed(matches):
        old = match.group(0)
        message = match.group("message").strip()
        # Two validation branches run before launch; the other four are child requests.
        has_caller = "缺少有效" not in message and "播放信息不可用" not in message
        new = "platform.feedback(" + message + (", caller)" if has_caller else ", null)")
        text = replace(text, old, new, edits)
    assert inverse(text, edits) == body
    return body, text


def section_delta(text, edits):
    before = "        val commandState = com.android.purebilibili.feature.video.ui.overlay.rememberCommandDanmakuOverlayState(\n            bvid to (uiState as? VideoPlaybackUiState.Success)?.info?.cid\n        )"
    after = "        val candidateCommandPort = platform.commandVotePlatform()\n        val desktopCommandPort = remember(platform, candidateCommandPort?.sourceLease) { candidateCommandPort }\n        val commandState = com.android.purebilibili.feature.video.ui.overlay.rememberCommandDanmakuOverlayState(\n            desktopCommandPort?.sourceLease\n        )"
    text = replace(text, before, after, edits)
    text = replace(text, "                com.android.purebilibili.feature.video.ui.overlay.CommandDanmakuOverlay(\n",
        "                com.bilipai.desktop.ui.DesktopWindowsSectionCommandOverlay(\n                    commandPort = desktopCommandPort,\n", edits)
    start = "                    onVoteSubmit = { item, option, optionIndex ->\n"
    end = "                    },\n                    isFollowing = isFollowed,"
    assert text.count(start) == text.count(end) == 1
    old = start + text.split(start, 1)[1].split(end, 1)[0] + "                    },\n"
    new = "                    onVoteSubmit = { item, option, optionIndex ->\n                        desktopCommandPort?.let { commandPort ->\n                            com.android.purebilibili.feature.video.ui.overlay.submitOriginalDesktopCommandVote(\n                                item, option, optionIndex, uiState, commandState, settingsScope, commandPort)\n                        }\n                    },\n"
    return replace(text, old, new, edits)
