package com.android.purebilibili.feature.message
import com.android.purebilibili.data.model.response.*

fun totalPrivateUnreadCount(unreadData: MessageUnreadData?): Int {
    if (unreadData == null) return 0
    return unreadData.follow_unread +
        unreadData.unfollow_unread +
        unreadData.dustbin_unread +
        unreadData.custom_unread
}

fun totalMessageUnreadCount(
    unreadData: MessageUnreadData?,
    feedUnread: MessageFeedUnreadData?
): Int {
    val feedUnreadCount = if (feedUnread == null) {
        0
    } else {
        feedUnread.reply + feedUnread.at + feedUnread.like + feedUnread.sysMsg
    }
    return totalPrivateUnreadCount(unreadData) + feedUnreadCount
}
