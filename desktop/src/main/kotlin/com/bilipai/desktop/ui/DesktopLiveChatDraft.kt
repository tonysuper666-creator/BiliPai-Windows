package com.bilipai.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import com.android.purebilibili.feature.live.LiveDanmakuItem

/** Screen state only; the existing DesktopLiveSession owns sending and account admission.
 * v0.3.0 clears only the submitted text and the same reply instance after success.
 * Revisions preserve edits or reselections which return to the submitted values. */
internal class DesktopLiveChatDraft {
    var message by mutableStateOf("")
        private set
    var reply by mutableStateOf<LiveDanmakuItem?>(null, referentialEqualityPolicy())
        private set
    private var messageRevision = 0L
    private var replyRevision = 0L

    fun editMessage(value: String) {
        if (message != value) { message = value; messageRevision++ }
    }
    fun selectReply(value: LiveDanmakuItem?) {
        if (reply !== value) { reply = value; replyRevision++ }
    }

    class Submission internal constructor(
        internal val owner: DesktopLiveChatDraft,
        val message: String,
        val reply: LiveDanmakuItem?,
        internal val messageRevision: Long,
        internal val replyRevision: Long,
    )
    fun capture() = Submission(this, message, reply, messageRevision, replyRevision)
    fun complete(submission: Submission) {
        check(submission.owner === this) { "A live draft belongs to its captured session" }
        if (messageRevision == submission.messageRevision && message == submission.message) editMessage("")
        if (replyRevision == submission.replyRevision && reply === submission.reply) selectReply(null)
    }
}
