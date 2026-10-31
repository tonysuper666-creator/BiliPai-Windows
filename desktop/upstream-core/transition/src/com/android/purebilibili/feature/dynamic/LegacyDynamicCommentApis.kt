// Transition compat (M4b, TON-1003): upstream folded the dynamic inline comment box into its shared comment dialog and
// removed these two helpers; the legacy DesktopOriginalDynamicInlineReplyUi copy still calls them with the v0.2.5 behavior.
// Delete together with that copy.
package com.android.purebilibili.feature.dynamic

internal fun resolveDynamicCommentComposerHint(replyUname: String? = null): String {
    val uname = replyUname?.trim().orEmpty()
    return if (uname.isNotEmpty()) "回复 @$uname" else "发一条友善的评论"
}

internal fun resolveDynamicCommentImeSubmission(text: String): String? =
    text.trim().takeIf(String::isNotEmpty)
