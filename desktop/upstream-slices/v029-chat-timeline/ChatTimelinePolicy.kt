package com.android.purebilibili.feature.message

import com.android.purebilibili.data.model.response.PrivateMessageItem

internal fun chatMessageKey(message: PrivateMessageItem): String = when {
    message.msg_key > 0L -> "key_${message.msg_key}"
    message.msg_seqno > 0L -> "seq_${message.msg_seqno}"
    else -> "${message.sender_uid}_${message.receiver_id}_${message.timestamp}_${message.msg_type}_${message.content}"
}

/** Retains loaded history, updates overlaps, and orders messages by their server sequence. */
internal fun mergeChatMessages(
    existing: List<PrivateMessageItem>,
    incoming: List<PrivateMessageItem>,
): List<PrivateMessageItem> {
    val messages = linkedMapOf<String, PrivateMessageItem>()
    existing.forEach { messages[chatMessageKey(it)] = it }
    incoming.forEach { messages[chatMessageKey(it)] = it }
    return if (messages.values.all { it.msg_seqno > 0L }) {
        messages.values.sortedBy { it.msg_seqno }
    } else {
        messages.values.sortedWith(compareBy<PrivateMessageItem> { it.timestamp }.thenBy { it.msg_seqno })
    }
}
