package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.viewmodel.DesktopVideoCommentRequests
import com.android.purebilibili.feature.video.viewmodel.VideoSubjectSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive

/** The same retained detail-entry scope and atomic Store-to-entry gate.
 * It constructs no network, account, player, settings or draft persistence authority.
 * Root must close the original Composer domain before replacing this entry.
 */
internal class DesktopOriginalVideoComposerEnvironment(
    val scope: CoroutineScope,
    private val stillCurrent: () -> Boolean,
    private val commitIfCurrent: ((() -> Unit) -> Boolean),
    val commentRequests: DesktopVideoCommentRequests,
    val commentInfo: () -> ViewInfo?,
    val loadCommentEmotePackages: suspend () -> Result<List<EmotePackage>>,
    val searchCommentMentionUsers: suspend (String) -> Result<List<MentionSearchUser>>,
    val commentFeedback: (String) -> Unit,
) {
    fun isCurrent(): Boolean = scope.isActive && stillCurrent()
    fun assertCurrent() {
        if (!isCurrent()) throw CancellationException("Original video Composer entry retired")
    }
    fun commit(block: () -> Unit): Boolean = isCurrent() && commitIfCurrent {
        assertCurrent()
        block()
    }
}

/** UI identity only; actual entry/account/source permission belongs to the
 * already captured presentation and DomainOwners ports. No draft Store. */
internal class DesktopOriginalVideoCommentComposerStamp(
    val presentation: DesktopWindowsCommentPresentation,
    val subject: VideoSubjectSnapshot,
)

/** An already authorized send keeps its immutable original typed subject,
 * including when a later UI mount has moved to another AID. */
internal data class DesktopOriginalVideoCommentSentReceipt(val aid: Long, val reply: ReplyItem?)
