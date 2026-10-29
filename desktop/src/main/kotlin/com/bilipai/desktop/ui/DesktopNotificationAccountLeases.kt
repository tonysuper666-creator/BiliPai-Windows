package com.bilipai.desktop.ui

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The notification group `messages-<mid>` is shared by every session of that account, in this process
 * and in the notification center itself. Only the session holding the account may withdraw it, and a
 * group operation that is already inside the surface keeps running after a handoff, so publication has
 * to stay closed until the account's running operations are over.
 *
 * A visitor session has no account (`mid == null`); it is filed under one sentinel so the map never
 * has to accept a null key.
 */
internal object DesktopNotificationAccountLeases {
    private val ANONYMOUS = Any()
    private val byAccount = ConcurrentHashMap<Any, DesktopNotificationSession>()
    private val groupOps = ConcurrentHashMap<Any, AtomicInteger>()

    private fun key(accountId: Long?): Any = accountId ?: ANONYMOUS

    fun claim(accountId: Long?, session: DesktopNotificationSession) {
        val previous = byAccount.put(key(accountId), session)
        if (previous != null && previous !== session) previous.abandonGroupClear()
    }

    /** True when this session may withdraw the group, or when nobody registered one. */
    fun holds(accountId: Long?, session: DesktopNotificationSession): Boolean {
        val owner = byAccount[key(accountId)] ?: return true
        return owner === session
    }

    /** A group withdrawal of this account is inside the surface right now, whichever session started it. */
    fun groupOperationInFlight(accountId: Long?): Boolean = (groupOps[key(accountId)]?.get() ?: 0) > 0

    fun beginGroupOperation(accountId: Long?) {
        groupOps.getOrPut(key(accountId)) { AtomicInteger() }.incrementAndGet()
    }

    fun endGroupOperation(accountId: Long?) {
        groupOps[key(accountId)]?.decrementAndGet()
    }

    internal fun clearForTest() { byAccount.clear(); groupOps.clear() }
}
