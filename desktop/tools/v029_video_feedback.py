"""Fixed v029 feedback metadata/methods on the existing complete owned VM.
No network, Store, event manager, mobile gesture or presentation host is created.
"""
import hashlib
import json
import re
from pathlib import Path

COMMIT = "a4b77f894d0a2dd26c0b9fc144b8adb88ac05480"
MANIFEST_SHA256 = "058db2e36e4efe6c92ffe434da119127576b8d825fc24279debdb171c4ac359a"
BASE = "app/src/main/java/com/android/purebilibili/feature/video/"
VM = BASE + "viewmodel/VideoEngagementViewModel.kt"
ANIMATIONS = BASE + "ui/components/CelebrationAnimations.kt"
OUTPUT_VM = "com/android/purebilibili/feature/video/viewmodel/VideoEngagementViewModel.kt"
OUTPUT_ANIMATIONS = "com/android/purebilibili/feature/video/ui/components/DesktopWindowsConfirmedCelebrationAnimations.kt"


def wide(path):
    value = str(Path(path).absolute())
    return Path(value if value.startswith("\\\\?\\") else "\\\\?\\" + value)


def sha(raw):
    return hashlib.sha256(raw.encode("utf-8") if isinstance(raw, str) else raw).hexdigest()


def sources():
    root = Path(__file__).resolve().parent.parent / "upstream-slices/v029-video-feedback"
    manifest_raw = wide(root / "manifest.json").read_bytes()
    assert sha(manifest_raw) == MANIFEST_SHA256, "Fixed v029 feedback manifest changed"
    manifest = json.loads(manifest_raw)
    assert manifest["upstreamCommit"] == COMMIT
    result = {}
    for row in manifest["sources"]:
        path = row["path"]
        assert path not in result and not Path(path).is_absolute() and ".." not in Path(path).parts
        raw = wide(root / path).read_bytes()
        assert len(raw) == row["bytes"] and sha(raw) == row["sha256"], path
        assert hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest() == row["gitBlob"], path
        result[path] = raw.decode("utf-8")
    assert len(result) == 8 and VM in result and ANIMATIONS in result
    return manifest, result


def function(text, name, parser):
    matches = list(re.finditer(r"(?m)^    fun " + re.escape(name) + r"\s*\(", text))
    assert len(matches) == 1, name
    start = matches[0].start()
    tokens = parser.kotlin_tokens(text)
    index = next(i for i, (_, a, _) in enumerate(tokens) if a >= start)
    while tokens[index][0] != "(":
        index += 1
    depth = 1
    while depth:
        index += 1
        depth += (tokens[index][0] == "(") - (tokens[index][0] == ")")
    while tokens[index][0] != "{":
        index += 1
    depth = 1
    while depth:
        index += 1
        depth += (tokens[index][0] == "{") - (tokens[index][0] == "}")
    return text[start:tokens[index][2]]


def apply_video_feedback(path, text, parser):
    if path != OUTPUT_VM:
        return text, None
    manifest, raw = sources()
    original = raw[VM]
    before_all = text
    edits = []

    def replace(before, after, label):
        nonlocal text
        assert text.count(before) == 1, (label, text.count(before))
        edits.append({"label": label, "before": before, "after": after})
        text = text.replace(before, after, 1)

    # Exact original state fields and enum; existing whole seed/actions/VM remain.
    model = original[original.index("enum class VideoMaidAction"):original.index("sealed interface VideoEngagementEvent")]
    old_model = text[text.index("data class VideoEngagementUiState"):text.index("sealed interface VideoEngagementEvent")]
    replace(old_model, model, "fixed complete v029 feedback state fields and VideoMaidAction")
    replace("    private var locallyModifiedFields: Set<VideoEngagementField> = emptySet()",
            "    private var locallyModifiedFields: Set<VideoEngagementField> = emptySet()\n    private var tripleCelebrationSequence = 0L",
            "original v029 per-VM celebration sequence")
    replace("        environment.commit { _uiState.value = VideoEngagementUiState(\n            subject = subject,",
            "        environment.commit {\n            val previous = _uiState.value\n            _uiState.value = VideoEngagementUiState(\n            subject = subject,\n            likeBurstId = previous.likeBurstId,\n            maidActionId = previous.maidActionId,",
            "original bind preserves instance counters across subjects under existing owner")
    replace("                            likeBurstVisible = liked\n",
            "                            likeBurstVisible = liked,\n                            maidAction = null,\n                            likeBurstId = if (liked) current.likeBurstId + 1 else current.likeBurstId\n",
            "original confirmed-like feedback fields only after real onSuccess")
    replace("                            isDisliked = disliked,\n",
            "                            isDisliked = disliked,\n                            likeBurstVisible = false,\n                            maidAction = if (disliked) VideoMaidAction.DISLIKE else null,\n                            maidActionId = if (disliked) current.maidActionId + 1 else current.maidActionId,\n",
            "original confirmed-dislike maid action")
    replace("                            coinCount = minOf(current.coinCount + count, 2),\n",
            "                            coinCount = minOf(current.coinCount + count, 2),\n                            likeBurstVisible = false,\n                            maidAction = VideoMaidAction.COIN,\n                            maidActionId = current.maidActionId + 1,\n",
            "original confirmed-coin maid action")
    replace("                    environment.commit {\n                        val visual = resolveTripleActionVisualState(",
            "                    var celebrationId = 0L\n                    environment.commit {\n                        celebrationId = ++tripleCelebrationSequence\n                        val visual = resolveTripleActionVisualState(",
            "original sequence increments only in accepted triple result publication")
    replace("                                tripleCelebrationVisible = result.allSuccess\n",
            "                                tripleCelebrationVisible = result.allSuccess,\n                                maidAction = null,\n                                likeBurstVisible = false,\n                                tripleCelebrationId = celebrationId,\n                                tripleCelebrationFinished = false\n",
            "original partial triple remains uncelebrated and resets completion")
    # A bounded completion wait is the original v029 contract, replacing v025's
    # blind 2s jump. IO/delay remains outside the existing bounded state permits.
    old_wait = """                    val context = appContext
                    if (result.allSuccess && context != null && SettingsManager.getTripleJumpEnabled(context).first()) {
                        delay(2_000L)
                        if (_uiState.value.subject?.generation == state.subject?.generation) {
                            sendOwned(VideoEngagementEvent.LoadVideo("BV1JsK5eyEuB"))
                        }
                    }"""
    start = original.index("                    if (result.allSuccess && tripleJumpEnabled(appContext))")
    end = original.index("\n                }\n                .onFailure", start)
    new_wait = original[start:end].replace("tripleJumpEnabled(appContext)", "appContext?.let { SettingsManager.getTripleJumpEnabled(it).first() } == true")
    new_wait = new_wait.replace("_events.send(", "sendOwned(")
    new_wait = new_wait.replace("                    if (result.allSuccess", "                    currentCoroutineContext().ensureActive(); environment.assertOwned()\n                    if (result.allSuccess", 1)
    replace(old_wait, new_wait, "full original bounded completed-current celebration jump lifecycle")
    old_methods = function(text, "dismissLikeBurst", parser) + "\n\n" + function(text, "dismissTripleCelebration", parser)
    names = ["dismissLikeBurst", "showShareFeedback", "dismissMaidAction", "dismissTripleCelebration", "completeTripleCelebration", "cancelTripleCelebration"]
    new_methods = "\n\n".join(function(original, name, parser).replace("_uiState.update {", "updateOwned {") for name in names)
    replace(old_methods, new_methods, "six complete fixed original ID-aware dismiss/share/complete/cancel methods with existing owner updater")

    # Keep public old signatures. New ordinary dispatch uses the same domain but
    # captures its existing exact accepted-source presentation context per child.
    for name, parameters, arguments in [
        ("toggleLike", "aid: Long? = null, bvid: String? = null, currentlyLiked: Boolean? = null, onResult: ((Boolean) -> Unit)? = null", "aid, bvid, currentlyLiked, onResult"),
        ("toggleDislike", "aid: Long? = null, bvid: String? = null, currentlyDisliked: Boolean? = null", "aid, bvid, currentlyDisliked"),
        ("doCoin", "count: Int, alsoLike: Boolean", "count, alsoLike"),
    ]:
        body = function(text, name, parser)
        owned_name = name + "WithDesktopPresentation"
        owned = body.replace("    fun " + name + "(", "    internal fun " + owned_name + "(", 1)
        boundary = owned.index(") {")
        owned = owned[:boundary].rstrip() + ",\n        presentation: DesktopOriginalVideoEngagementPresentation? = null\n    " + owned[boundary:]
        assert owned.count("viewModelScope.launch {") == 1
        owned = owned.replace("viewModelScope.launch {", "viewModelScope.launch(presentation?.context ?: EmptyCoroutineContext) {", 1)
        if name == "doCoin":
            owned = owned.replace("        setCoinDialogVisible(false)", "        if (presentation == null) setCoinDialogVisible(false)\n        else if (!presentation.admit { setCoinDialogVisible(false) }) return", 1)
        wrapper = "    fun " + name + "(" + parameters + ") {\n        " + owned_name + "(" + arguments + ", presentation = null)\n    }\n\n"
        replace(body, wrapper + owned, "preserve public " + name + " API and add same-domain captured-presentation dispatch")

    restored = text
    for edit in reversed(edits):
        assert restored.count(edit["after"]) == 1, edit["label"]
        restored = restored.replace(edit["after"], edit["before"], 1)
    assert restored == before_all
    return text, {"schema": 1, "fixedCommit": COMMIT, "manifestSha256": MANIFEST_SHA256,
                  "sources": manifest["sources"], "beforeSha256LF": sha(before_all), "afterSha256LF": sha(text),
                  "edits": edits, "inverseWholeExistingVmExact": True,
                  "notImplemented": ["native decorative presentation host", "actual ordinary button mount", "global share broadcast"],
                  "canonicalBaselineChanged": False}


def celebration_source():
    manifest, raw = sources()
    before = raw[ANIMATIONS]
    result = before
    edits = []
    changes = [
        ("import com.android.purebilibili.core.plugin.skin.UiSkinAnimatedAsset", "import com.bilipai.desktop.ui.DesktopOriginalPlayerSkinAsset as UiSkinAnimatedAsset"),
        ("fun LikeBurstAnimation(", "fun DesktopWindowsConfirmedLikeBurstAnimation("),
        ("fun TripleSuccessAnimation(", "fun DesktopWindowsConfirmedTripleSuccessAnimation("),
        ("fun CoinSuccessAnimation(", "fun DesktopWindowsConfirmedCoinSuccessAnimation("),
    ]
    for old, new in changes:
        assert result.count(old) == 1
        result = result.replace(old, new, 1)
        edits.append({"before": old, "after": new})
    inverse = result
    for row in reversed(edits):
        assert inverse.count(row["after"]) == 1
        inverse = inverse.replace(row["after"], row["before"], 1)
    assert inverse == before
    return result, {"fixedCommit": COMMIT, "origin": ANIMATIONS, "beforeSha256": sha(before), "afterSha256LF": sha(result),
                    "edits": edits, "fullOriginalBodyInverseExact": True, "sources": manifest["sources"]}
