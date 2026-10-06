"""Fixed v031 repost coin limits, layered after existing owned adaptations.
Only audited count/schema propagation changes; no new API/session/request owner.
"""
import hashlib
import json
import os
from pathlib import Path

COMMIT = "10c08edadc07c56842402e27048855f4f3c95c38"
MANIFEST_SHA256 = "d54a0d1428ed2cc213cb1be49dcd5483074d956df47b66265fea7265385fe784"
BASE = "com/android/purebilibili/"
VM = BASE + "feature/video/viewmodel/VideoEngagementViewModel.kt"
USECASE = BASE + "feature/video/usecase/VideoInteractionUseCase.kt"
PROTOCOL = BASE + "data/repository/DesktopOriginalVideoEngagementProtocol.kt"
SEED = BASE + "feature/video/viewmodel/DesktopOriginalVideoSuccessExtensions.kt"
TRIPLE = BASE + "feature/video/ui/feedback/TripleActionVisualStatePolicy.kt"
COMMON = BASE + "feature/video/screen/VideoDetailCommonOverlayAdapter.kt"
PAGER = BASE + "feature/video/ui/pager/PortraitVideoPager.kt"
DIRECT = {
    BASE + "data/model/response/VideoDetailResponse.kt": "VideoDetailResponse.kt",
    BASE + "feature/video/ui/components/CoinDialog.kt": "CoinDialog.kt",
}

def wide(path):
    value = str(Path(path).absolute())
    return Path(value if os.name != "nt" or value.startswith("\\\\?\\") else "\\\\?\\" + value)

def sha(raw):
    return hashlib.sha256(raw.encode("utf8") if isinstance(raw, str) else raw).hexdigest()

def sources():
    root = Path(__file__).resolve().parent.parent / "upstream-slices/v031-repost-coin"
    raw = wide(root / "manifest.json").read_bytes()
    assert sha(raw) == MANIFEST_SHA256, "Fixed v031 coin manifest changed"
    manifest = json.loads(raw)
    assert manifest["upstreamCommit"] == COMMIT
    result = {}
    for row in manifest["sources"]:
        name = row["path"]
        assert Path(name).name == name and name not in result
        body = wide(root / name).read_bytes()
        assert len(body) == row["bytes"] and sha(body) == row["sha256"], name
        assert hashlib.sha1(b"blob " + str(len(body)).encode() + b"\0" + body).hexdigest() == row["gitBlob"], name
        result[name] = body.decode("utf8")
    assert set(result) == {"VideoDetailResponse.kt", "CoinDialog.kt", "TripleActionVisualStatePolicy.kt",
                           "VideoEngagementViewModel.kt", "ActionRepository.kt", "VideoInteractionUseCase.kt"}
    return manifest, result

def direct_outputs():
    manifest, fixed = sources()
    rows = {row["path"]: row for row in manifest["sources"]}
    return [(path, fixed[name], rows[name]["origin"]) for path, name in DIRECT.items()]

def undo(text, proof):
    assert sha(text) == proof["afterSha256LF"]
    for edit in reversed(proof["edits"]):
        assert text.count(edit["after"]) == edit["count"], edit["label"]
        text = text.replace(edit["after"], edit["before"], edit["count"])
    assert sha(text) == proof["beforeSha256LF"]
    return text

def apply(path, text):
    if path not in {VM, USECASE, PROTOCOL, SEED, TRIPLE, COMMON, PAGER}:
        return text, None
    manifest, fixed = sources()
    before_all = text
    edits = []
    def replace(before, after, label, count=1):
        nonlocal text
        assert text.count(before) == count, (path, label, text.count(before), count)
        text = text.replace(before, after, count)
        edits.append(dict(label=label, before=before, after=after, count=count))
    if path == VM:
        replace("    val isInWatchLater: Boolean = false,", "    val isRepost: Boolean = false,\n    val isInWatchLater: Boolean = false,", "fixed repost seed and UI state field", 2)
        replace("    val desktopTripleFeedbackOrigin: com.bilipai.desktop.ui.DesktopWindowsVideoFeedbackOrigin? = null\n)",
                "    val desktopTripleFeedbackOrigin: com.bilipai.desktop.ui.DesktopWindowsVideoFeedbackOrigin? = null\n) {\n    val coinLimit: Int get() = if (isRepost) 1 else 2\n}", "fixed per-video original/repost coin limit")
        replace("    suspend fun doTripleAction(aid: Long): Result<TripleActionResult>", "    suspend fun doTripleAction(aid: Long, coinCount: Int): Result<TripleActionResult>", "carry count through existing action interface")
        replace("    override suspend fun doTripleAction(aid: Long) = useCase.doTripleAction(aid)", "    override suspend fun doTripleAction(aid: Long, coinCount: Int) = useCase.doTripleAction(aid, coinCount)", "same default owned usecase delegation")
        replace("            isInWatchLater = seed.isInWatchLater,", "            isRepost = seed.isRepost,\n            isInWatchLater = seed.isInWatchLater,", "initial accepted seed copyright")
        replace("                isInWatchLater = if (VideoEngagementField.WATCH_LATER in locallyModifiedFields) current.isInWatchLater else seed.isInWatchLater,", "                isRepost = seed.isRepost,\n                isInWatchLater = if (VideoEngagementField.WATCH_LATER in locallyModifiedFields) current.isInWatchLater else seed.isInWatchLater,", "refresh repost metadata without removing existing watch-later synchronization")
        replace("        val capturedSubject = _uiState.value.subject\n        if (_uiState.value.coinCount >= 2) {\n            emitMessage(\"已投满2个硬币\")", "        val state = _uiState.value\n        val capturedSubject = state.subject\n        if (state.coinCount >= state.coinLimit) {\n            emitMessage(if (state.isRepost) \"转载视频最多投1个硬币\" else \"已投满2个硬币\")", "fixed cap and message with unchanged balance subject guard")
        replace("        val subject = state.subject ?: return\n        if (presentation == null) setCoinDialogVisible(false)", "        val subject = state.subject ?: return\n        // Reject stale/invalid choices before launching the sole mutation; never send zero coins.\n        if (count !in 1..(state.coinLimit - state.coinCount).coerceIn(0, state.coinLimit)) return\n        if (presentation == null) setCoinDialogVisible(false)", "desktop ordinary coin choice must remain inside captured remaining allowance")
        replace("coinCount = minOf(current.coinCount + count, 2),", "coinCount = minOf(current.coinCount + count, current.coinLimit),", "fixed successful ordinary coin state cap")
        replace("actions.doTripleAction(targetAid)", "actions.doTripleAction(targetAid, state.coinLimit)", "fixed triple cap; deliberately not ordinary remaining amount")
        replace("                            favoriteSuccess = result.favoriteSuccess\n                        )", "                            favoriteSuccess = result.favoriteSuccess,\n                            attemptedCoinCount = state.coinLimit\n                        )", "fixed triple visual amount from same captured state")
    elif path == USECASE:
        replace("suspend fun doTripleAction(aid: Long): Result<TripleActionResult>", "suspend fun doTripleAction(aid: Long, coinCount: Int = 2): Result<TripleActionResult>", "fixed usecase optional count")
        replace('Logger.d(TAG, "doTripleAction: aid=$aid")', 'Logger.d(TAG, "doTripleAction: aid=$aid, coinCount=$coinCount")', "fixed usecase diagnostic count")
        replace("ActionRepository.tripleAction(aid).map", "ActionRepository.tripleAction(aid, coinCount).map", "fixed same protocol delegation")
    elif path == PROTOCOL:
        replace('34005 -> Result.failure(Exception("已投满2个硬币"))', '34005 -> Result.failure(Exception("已投满该视频的硬币额度"))', "Windows limit message retains the original classification without claiming two coins for reposts")
        replace("suspend fun tripleAction(aid: Long): Result<TripleResult>", "suspend fun tripleAction(aid: Long, coinCount: Int = 2): Result<TripleResult>", "fixed protocol count")
        replace("// 2. 投币 (2个，同时点赞)\n            val coinResult = coinVideo(aid, 2, true)", "// 2. 投币 (按上限，同时点赞)\n            val coinResult = coinVideo(aid, coinCount, true)", "fixed sole coin call; original like/coin/favorite order retained")
    elif path == SEED:
        replace("        coinCount = coinCount,", "        coinCount = coinCount,\n        isRepost = info.isRepost,", "accepted original detail copyright reaches engagement seed")
    elif path == TRIPLE:
        replace(text, fixed["TripleActionVisualStatePolicy.kt"], "complete fixed original triple visual policy")
    else:
        replace("        userBalance = engagementState.userCoinBalance,", "        userBalance = engagementState.userCoinBalance,\n        maxCoins = engagementState.coinLimit,", "same original dialog receives accepted video's cap")
    proof = dict(upstreamCommit=COMMIT, manifestSha256=MANIFEST_SHA256, path=path,
                 beforeSha256LF=sha(before_all), afterSha256LF=sha(text), edits=edits,
                 fullPreviousOwnedInverse=True, watchLaterSyncPreserved=True,
                 newNetworkOwner=False, scope="Fixed v031 repost coin slice on existing owned Windows paths")
    assert undo(text, proof) == before_all
    return text, proof

def apply_and_record(path, text, output):
    changed, proof = apply(path, text)
    if proof is not None:
        target = wide(Path(output) / (Path(path).name + ".repost-coin-proof.json"))
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes((json.dumps(proof, ensure_ascii=False, indent=2) + "\n").encode("utf8"))
    return changed, proof
