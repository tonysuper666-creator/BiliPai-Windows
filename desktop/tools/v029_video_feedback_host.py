"""Complete original decorative blocks, adapted to immutable Windows receipts.
The original whole source remains fixed and archived; phone geometry, shared
BVID listeners, toasts and resume dialogs are deliberately not emitted here.
"""
import hashlib
from v029_video_feedback import sources, BASE, COMMIT

ORIGIN = BASE + "screen/VideoDetailFeedbackOverlayAdapter.kt"
OUTPUT_MOTION = "com/bilipai/desktop/ui/DesktopWindowsVideoFeedbackMotionContent.kt"


def sha(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def feedback_motion_source():
    _, raw = sources()
    full = raw[ORIGIN]
    like_start = full.index("            // 以事件 ID 为 key：")
    like_end = full.index("\n        }\n    }\n\n    val tripleCelebrationPlacement", like_start)
    triple_start = full.index("            key(engagementState.tripleCelebrationId) {")
    triple_end = full.index("\n        }\n    }\n\n    val popupMessage", triple_start)
    anchor_start = full.index("        val anchoredOffset: IntOffset? = likeIconBounds?.let { bounds ->")
    anchor_end = full.index("\n        Box(", anchor_start)
    originals = dict(likeMaid=full[like_start:like_end], triple=full[triple_start:triple_end],
                     anchor=full[anchor_start:anchor_end])
    code = dict(originals)
    changes = []

    def change(part, before, after, label):
        assert code[part].count(before) == 1, (label, code[part].count(before))
        code[part] = code[part].replace(before, after, 1)
        changes.append(dict(part=part, label=label, before=before, after=after, occurrenceCount=1))

    change("likeMaid", "            val action = engagementState.maidAction\n",
           "            val action = when (maidOrigin?.kind) {\n"
           "                DesktopWindowsVideoFeedbackKind.DISLIKE -> VideoMaidAction.DISLIKE\n"
           "                DesktopWindowsVideoFeedbackKind.SHARE -> VideoMaidAction.SHARE\n"
           "                DesktopWindowsVideoFeedbackKind.COIN -> VideoMaidAction.COIN\n"
           "                else -> null\n            }\n",
           "Visual kind comes from the confirmed receipt, never a newer collected state")
    change("likeMaid", "key(action, engagementState.maidActionId, engagementState.likeBurstId)",
           "key(action, maidOrigin, likeOrigin)", "Exact receipt references preserve the original per-instance key")
    change("likeMaid", "LikeBurstAnimation(", "DesktopWindowsConfirmedLikeBurstAnimation(",
           "Existing whole original desktop Like implementation")
    change("likeMaid", "{ engagementViewModel.dismissLikeBurst(engagementState.likeBurstId) }",
           "{ binding.completeFeedback(checkNotNull(likeOrigin)) }", "Original Like completion through exact source/instance permit")
    change("likeMaid", "{ engagementViewModel.dismissMaidAction(engagementState.maidActionId) }",
           "{ binding.completeFeedback(checkNotNull(maidOrigin)) }", "Original Maid completion through exact source/instance permit")
    change("triple", "key(engagementState.tripleCelebrationId)", "key(tripleOrigin)",
           "Exact original Triple instance receipt key")
    change("triple", "TripleSuccessAnimation(", "DesktopWindowsConfirmedTripleSuccessAnimation(",
           "Existing whole original desktop Triple implementation")
    change("triple", "engagementViewModel.completeTripleCelebration(engagementState.tripleCelebrationId)",
           "binding.completeFeedback(checkNotNull(tripleOrigin))", "Completion cannot dismiss a successor source or Triple instance")

    for part, original in originals.items():
        restored = code[part]
        for row in reversed(changes):
            if row["part"] == part:
                assert restored.count(row["after"]) == 1
                restored = restored.replace(row["after"], row["before"], 1)
        assert restored == original
    prefix = '''package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.BlueSnowMaidAnimation
import com.android.purebilibili.core.ui.MaidAnimation
import com.android.purebilibili.feature.video.viewmodel.VideoMaidAction
import com.android.purebilibili.feature.video.ui.components.DesktopWindowsConfirmedLikeBurstAnimation
import com.android.purebilibili.feature.video.ui.components.DesktopWindowsConfirmedTripleSuccessAnimation

/** Complete fixed original decorative blocks. Windows owns only viewport
 * placement and the existing exact-source completion permission. */
@Composable
internal fun DesktopWindowsVideoFeedbackMotionContent(
    binding: DesktopWindowsVideoEngagementBinding,
    likeOrigin: DesktopWindowsVideoFeedbackOrigin?,
    maidOrigin: DesktopWindowsVideoFeedbackOrigin?,
    tripleOrigin: DesktopWindowsVideoFeedbackOrigin?,
    likeSize: Dp,
    celebrationSize: Dp,
    compactCelebration: Boolean,
    reducedMotion: Boolean,
    videoBounds: Rect,
    likeIconBounds: Rect?,
) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maidSizePx = with(density) { likeSize.toPx() }
        val anchoredOffset = desktopWindowsOriginalLikeFeedbackOffset(likeIconBounds, maidSizePx,
            constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(),
            0f, 0f, 0f, with(density) { 4.dp.toPx() }, with(density) { 12.dp.toPx() })
        val likeFrame = if (anchoredOffset != null) Rect(0f, 0f, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()) else videoBounds
        if (likeOrigin != null || maidOrigin != null) {
            DesktopWindowsFeedbackRectFrame(likeFrame) {
                Box(modifier = if (anchoredOffset != null) Modifier.offset { anchoredOffset }
                    else Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
'''
    middle = '''
                }
            }
        }
        if (tripleOrigin != null) {
            DesktopWindowsFeedbackRectFrame(videoBounds) {
                Box(Modifier.align(if (compactCelebration) Alignment.Center else Alignment.BottomEnd).padding(16.dp)) {
'''
    suffix = '''
                }
            }
        }
    }
}
'''
    anchor_prefix = '''
/** Full unchanged original formula; Windows supplies actual client/button
 * pixels and has no Android system-bar inset. */
internal fun desktopWindowsOriginalLikeFeedbackOffset(
    likeIconBounds: Rect?, maidSizePx: Float, screenWidthPx: Float, screenHeightPx: Float,
    topSafePx: Float, bottomSafePx: Float, sideSafePx: Float,
    horizontalLeadPx: Float, verticalOverlapPx: Float,
): IntOffset? {
'''
    anchor_suffix = "\n    return anchoredOffset\n}\n"
    generated = prefix + code["likeMaid"] + middle + code["triple"] + suffix + anchor_prefix + code["anchor"] + anchor_suffix
    proof = dict(schema=1, fixedCommit=COMMIT, origin=ORIGIN, originalFullRawSha256=sha(full),
                 originalExcerptRanges=dict(likeMaid=[like_start, like_end], triple=[triple_start, triple_end], anchor=[anchor_start, anchor_end]),
                 originalExcerpts=originals, generatedExcerpts=code, countedAdaptations=changes,
                 wrapper=dict(prefix=prefix, middle=middle, suffix=suffix, anchorPrefix=anchor_prefix, anchorSuffix=anchor_suffix),
                 generatedSha256LF=sha(generated), excerptInverseExact=True,
                 coordinateAuthority="Actual client/Like button pixels; original video fallback/Triple frame; no Android insets/global registry",
                 callbackAuthority="Existing original confirmed state/immutable receipt and original account/source permit",
                 mobileListenerOrToastOrResumeMounted=False)
    return generated, proof


def apply_feedback_lifetime(path, text):
    """Only final buffered event consumption: state/IO already uses context.

    Restore the complete pre-host owned VM exactly; fixed v029 raw is unchanged.
    """
    from v029_video_feedback import OUTPUT_VM
    if path != OUTPUT_VM:
        return text, None
    before_all = text
    before = "else presentation.admit(check)"
    after = "else presentation.admitFeedback(check)"
    assert text.count(before) == 1, "queued original event source-lifetime anchor"
    text = text.replace(before, after, 1)
    edit = dict(label="A confirmed queued event uses the captured source/account lifetime, not current window visibility",
                before=before, after=after, occurrenceCount=1)
    assert text.count(after) == 1 and text.replace(after, before, 1) == before_all
    return text, dict(schema=1, fixedCommit=COMMIT, beforeSha256LF=sha(before_all), afterSha256LF=sha(text),
                      edits=[edit], inverseWholeExistingVmExact=True,
                      actionStart="Existing presentation interactive admission before original launch",
                      confirmation="Actual active caller and exact source/account/subject/entry lifetime")
