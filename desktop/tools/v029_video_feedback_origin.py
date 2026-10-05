"""Windows immutable confirmed-source metadata after the fixed whole VM slice.
No rendering, shared listener, network or second domain state is introduced.
"""
import hashlib

OUTPUT_VM = "com/android/purebilibili/feature/video/viewmodel/VideoEngagementViewModel.kt"
PREFIX = "com.bilipai.desktop.ui."


def sha(text):
    return hashlib.sha256(text.encode()).hexdigest()


def apply_feedback_origin(path, text):
    if path != OUTPUT_VM:
        return text, None
    before_all = text
    edits = []

    def replace(before, after, label):
        nonlocal text
        assert text.count(before) == 1, (label, text.count(before))
        text = text.replace(before, after, 1)
        edits.append({"label": label, "before": before, "after": after})

    replace("    val tripleCelebrationFinished: Boolean = false\n)",
            "    val tripleCelebrationFinished: Boolean = false,\n"
            "    val desktopLikeFeedbackOrigin: " + PREFIX + "DesktopWindowsVideoFeedbackOrigin? = null,\n"
            "    val desktopMaidFeedbackOrigin: " + PREFIX + "DesktopWindowsVideoFeedbackOrigin? = null,\n"
            "    val desktopTripleFeedbackOrigin: " + PREFIX + "DesktopWindowsVideoFeedbackOrigin? = null\n)",
            "Read-only Windows metadata beside original per-channel confirmed state")

    def capture(kind, id_expression):
        return PREFIX + "DesktopWindowsVideoFeedbackOrigin.capture(" + PREFIX + "DesktopWindowsVideoFeedbackKind." + kind + ", " + id_expression + ", current.subject)"

    replace("                            maidAction = null,\n                            likeBurstId = if (liked) current.likeBurstId + 1 else current.likeBurstId\n",
            "                            maidAction = null,\n                            likeBurstId = if (liked) current.likeBurstId + 1 else current.likeBurstId,\n"
            "                            desktopMaidFeedbackOrigin = null,\n"
            "                            desktopLikeFeedbackOrigin = if (liked) " + capture("LIKE", "current.likeBurstId + 1") + " else null\n",
            "Confirmed Like source captured in the same owned CAS; unlike clears its receipt")
    replace("                            maidActionId = if (disliked) current.maidActionId + 1 else current.maidActionId,\n",
            "                            maidActionId = if (disliked) current.maidActionId + 1 else current.maidActionId,\n"
            "                            desktopLikeFeedbackOrigin = null,\n"
            "                            desktopMaidFeedbackOrigin = if (disliked) " + capture("DISLIKE", "current.maidActionId + 1") + " else null,\n",
            "Confirmed Dislike source metadata; no optimistic receipt")
    replace("                            maidActionId = current.maidActionId + 1,\n",
            "                            maidActionId = current.maidActionId + 1,\n"
            "                            desktopLikeFeedbackOrigin = null,\n"
            "                            desktopMaidFeedbackOrigin = " + capture("COIN", "current.maidActionId + 1") + ",\n",
            "Confirmed Coin source metadata in original protocol-success commit")
    replace("                                tripleCelebrationFinished = false\n",
            "                                tripleCelebrationFinished = false,\n"
            "                                desktopLikeFeedbackOrigin = null,\n"
            "                                desktopMaidFeedbackOrigin = null,\n"
            "                                desktopTripleFeedbackOrigin = if (result.allSuccess) " + capture("TRIPLE", "celebrationId") + " else null\n",
            "Only actual allSuccess triple obtains the captured accepted source receipt")
    replace("if (expectedId == null || it.likeBurstId == expectedId) it.copy(likeBurstVisible = false) else it",
            "if (expectedId == null || it.likeBurstId == expectedId) it.copy(likeBurstVisible = false, desktopLikeFeedbackOrigin = null) else it",
            "Original expected-id Like dismissal retires only that channel receipt")
    replace("                maidActionId = it.maidActionId + 1\n",
            "                maidActionId = it.maidActionId + 1,\n"
            "                desktopLikeFeedbackOrigin = null,\n"
            "                desktopMaidFeedbackOrigin = " + PREFIX + "DesktopWindowsVideoFeedbackOrigin.capture(" + PREFIX + "DesktopWindowsVideoFeedbackKind.SHARE, it.maidActionId + 1, it.subject)\n",
            "Only a stamped share presentation can produce renderable share metadata; no global listener")
    replace("if (it.maidActionId == expectedId) it.copy(maidAction = null) else it",
            "if (it.maidActionId == expectedId) it.copy(maidAction = null, desktopMaidFeedbackOrigin = null) else it",
            "Original expected-id Maid dismissal retires matching metadata")
    replace("it.copy(tripleCelebrationVisible = false, tripleCelebrationFinished = true)",
            "it.copy(tripleCelebrationVisible = false, tripleCelebrationFinished = true, desktopTripleFeedbackOrigin = null)",
            "Original completed-current Triple retires only matching origin")
    replace("it.copy(tripleCelebrationVisible = false, tripleCelebrationFinished = false)",
            "it.copy(tripleCelebrationVisible = false, tripleCelebrationFinished = false, desktopTripleFeedbackOrigin = null)",
            "Original cancellation does not mark completion")
    restored = text
    for edit in reversed(edits):
        assert restored.count(edit["after"]) == 1, edit["label"]
        restored = restored.replace(edit["after"], edit["before"], 1)
    assert restored == before_all
    return text, {"schema": 1, "beforeSha256LF": sha(before_all), "afterSha256LF": sha(text),
        "edits": edits, "wholeVmInverseExact": True, "runtimeHostMounted": False,
        "originAuthority": "Existing domain state, stamped existing presentation and exact accepted publication only"}
