package com.android.purebilibili.core.coroutines

import com.android.purebilibili.core.store.AccountSessionIdentity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Root's account identity is the account MID plus Root's session epoch (the formula that
 * DesktopFavoritesRootEntry builds for its subscription ports). These cases pin how the carried upstream
 * owner behaves on that identity. No window, no GPU and no network are involved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopAccountScopedRequestsRootIdentityTest {
    private fun rootIdentity(mid: Long?, epoch: Long) = AccountSessionIdentity(mid?.takeIf { it > 0L }, epoch)

    @Test
    fun `an epoch bump for the same account retires requests that are still pending`() = runTest {
        val identities = MutableStateFlow(rootIdentity(101L, 1L))
        val requests = AccountScopedRequests(backgroundScope, identities)
        var resets = 0
        requests.observe { resets += 1 }
        var cancelled = false
        requests.launch {
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        runCurrent()

        // Re-login with replaced credentials keeps the MID but advances Root's epoch.
        identities.value = rootIdentity(101L, 2L)
        runCurrent()

        assertTrue(cancelled)
        assertEquals(1, resets)
        assertEquals(rootIdentity(101L, 2L), requests.identity)
    }

    @Test
    fun `a request queued before a same account epoch bump never starts`() = runTest {
        val identities = MutableStateFlow(rootIdentity(101L, 1L))
        val requests = AccountScopedRequests(backgroundScope, identities)
        requests.observe { }
        runCurrent()
        var started = false
        requests.launch { started = true }

        identities.value = rootIdentity(101L, 2L)
        runCurrent()

        assertFalse(started)
        assertFalse(requests.isCurrent(rootIdentity(101L, 1L)))
        assertTrue(requests.isCurrent(rootIdentity(101L, 2L)))
    }

    @Test
    fun `a logged out Root identity has no MID and owns the requests launched after the reset`() = runTest {
        val identities = MutableStateFlow(rootIdentity(101L, 1L))
        val requests = AccountScopedRequests(backgroundScope, identities)
        requests.observe { }
        runCurrent()

        identities.value = rootIdentity(0L, 2L)
        runCurrent()
        var result = ""
        requests.launch { result = "signed out data" }
        runCurrent()

        assertEquals(AccountSessionIdentity(null, 2L), requests.identity)
        assertEquals("signed out data", result)
    }
}
