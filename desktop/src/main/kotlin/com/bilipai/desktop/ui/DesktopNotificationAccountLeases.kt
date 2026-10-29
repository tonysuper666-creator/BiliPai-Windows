package com.bilipai.desktop.ui

import java.util.concurrent.ConcurrentHashMap

/**
 * The notification group `messages-<mid>` is shared by every session of that account, in this process
 * and in the notification center itself. Only the session that currently holds the account may
 * withdraw the whole group: when the user comes back to an account, an older session's pending
 * `RemoveGroup` would otherwise delete the notifications published after the return.
 *
 * The newest session for an account takes the lease and the previous holder abandons its group
 * withdrawal (it keeps its own per-delivery withdrawals, which are tag-unique).
 */
internal object DesktopNotificationAccountLeases {
    private val byAccount = ConcurrentHashMap<Long?, DesktopNotificationSession>()

    fun claim(accountId: Long?, session: DesktopNotificationSession) {
        val previous = byAccount.put(accountId, session)
        if (previous != null && previous !== session) previous.abandonGroupClear()
    }

    /** True when this session may withdraw the group, or when nobody registered one. */
    fun holds(accountId: Long?, session: DesktopNotificationSession): Boolean {
        val owner = byAccount[accountId] ?: return true
        return owner === session
    }

    internal fun clearForTest() { byAccount.clear() }
}
