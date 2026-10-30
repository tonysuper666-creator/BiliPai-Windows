package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.model.AccountLedgerKind
import com.android.purebilibili.data.model.response.AccountLedgerData
import com.android.purebilibili.data.model.response.AccountLedgerEntry
import com.android.purebilibili.data.model.response.AccountLedgerResponse
import com.android.purebilibili.data.repository.AccountLedgerRepository
import com.android.purebilibili.feature.login.QrAuthorizationSession
import com.android.purebilibili.feature.profile.AccountLedgerViewModel
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.android.purebilibili.navigation3.BiliPaiNavBackStackController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.test.*

/** Real patched upstream model + repository, over a request recorder with synthetic credentials. */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopAccountLedgerTest {
    private val coins = AccountLedgerKind.COINS
    private val experience = AccountLedgerKind.EXPERIENCE
    private val success = AccountLedgerResponse(0, data = AccountLedgerData(listOf(AccountLedgerEntry(reason = "每日登录"))))

    private inner class Visit(val scope: CoroutineScope) {
        var owned = true
        var session = QrAuthorizationSession("page-session", "csrf", 42L, "buvid")
        val sent = CopyOnWriteArrayList<Pair<String, String>>()
        var response = success
        var pending: CompletableDeferred<Continuation<AccountLedgerResponse>>? = null
        private val api = Proxy.newProxyInstance(BilibiliApi::class.java.classLoader, arrayOf(BilibiliApi::class.java)) { _, method, args ->
            check(method.name in setOf("getAccountCoinLedger", "getAccountExperienceLedger"))
            sent += method.name to args[0] as String
            pending?.let {
                @Suppress("UNCHECKED_CAST")
                it.complete(args.last() as Continuation<AccountLedgerResponse>)
                COROUTINE_SUSPENDED
            } ?: response
        } as BilibiliApi
        val environment by lazy {
            DesktopAccountLedgerEnvironment(scope, api, { owned }, { session },
                { block -> if (owned) { block(); true } else false })
        }
        val model by lazy { AccountLedgerViewModel(environment) }
        val repository by lazy { AccountLedgerRepository(environment) }
    }

    @Test
    fun bothKindsUseTheProfileSessionAndKeepIndependentCachedResults() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val visit = Visit(scope)
            for (kind in listOf(coins, experience)) {
                visit.model.load(kind)
                withTimeout(5_000) { visit.model.state.first { it.section(kind).entries != null } }
                visit.model.load(kind)
            }
            assertEquals(listOf("getAccountCoinLedger", "getAccountExperienceLedger"), visit.sent.map { it.first })
            assertTrue(visit.sent.all { it.second == "SESSDATA=page-session; DedeUserID=42" })
            visit.model.load(coins, refresh = true)
            withTimeout(5_000) { visit.model.state.first { !it.section(coins).loading } }
            assertEquals(3, visit.sent.size)
            assertNotNull(visit.model.state.value.section(experience).entries)
        } finally { scope.cancel() }
    }

    @Test
    fun serverFailureKeepsPreviousRowsAndRetryClearsTheError() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val visit = Visit(scope)
            visit.model.load(coins)
            val loaded = withTimeout(5_000) { visit.model.state.first { it.section(coins).entries != null } }
            visit.response = AccountLedgerResponse(-400, "暂时无法查询")
            visit.model.load(coins, refresh = true)
            val failed = withTimeout(5_000) { visit.model.state.first { it.section(coins).error != null } }
            assertEquals("暂时无法查询", failed.section(coins).error)
            assertFalse(failed.section(coins).loading)
            assertEquals(loaded.section(coins).entries, failed.section(coins).entries)
            visit.response = success
            visit.model.load(coins, refresh = true)
            val retried = withTimeout(5_000) { visit.model.state.first { !it.section(coins).loading } }
            assertNull(retried.section(coins).error)
        } finally { scope.cancel() }
    }

    @Test
    fun malformedDataAndMissingServerMessagesHaveExplicitErrors() = runBlocking {
        val visit = Visit(this)
        visit.response = AccountLedgerResponse(0)
        assertEquals("明细数据不完整，请重试", visit.repository.load(visit.environment.identity, coins).exceptionOrNull()?.message)
        visit.response = AccountLedgerResponse(-101)
        assertEquals("加载明细失败（-101）", visit.repository.load(visit.environment.identity, coins).exceptionOrNull()?.message)
    }

    @Test
    fun signedOutOrExpiredSessionsSendNothing() = runTest {
        for (session in listOf(QrAuthorizationSession("", "", null, ""), QrAuthorizationSession("", "", 42L, ""))) {
            val visit = Visit(backgroundScope)
            visit.session = session
            assertTrue(visit.repository.load(visit.environment.identity, coins).isFailure)
            visit.model.load(coins)
            runCurrent()
            assertTrue(visit.sent.isEmpty())
        }
    }

    @Test
    fun aRetiredPageCannotStartAQueuedRequestOrRefresh() = runTest {
        val visit = Visit(backgroundScope)
        visit.model.load(coins)
        visit.owned = false
        runCurrent()
        visit.model.load(experience, refresh = true)
        assertTrue(visit.sent.isEmpty())
        assertFailsWith<CancellationException> { visit.repository.load(visit.environment.identity, coins) }
    }

    @Test
    fun anOldPageCannotBorrowTheNextAccountsCredentials() = runBlocking {
        val visit = Visit(this)
        val identity = visit.environment.identity
        visit.session = QrAuthorizationSession("new-session", "new-csrf", 99L, "")
        assertEquals("账号已切换，请重新加载", visit.repository.load(identity, coins).exceptionOrNull()?.message)
        assertTrue(visit.sent.isEmpty())
    }

    @Test
    fun aSameMidReloginRetiresTheOldPageBeforeNewCredentialsCanBeRead() = runBlocking {
        val visit = Visit(this)
        val identity = visit.environment.identity
        visit.owned = false
        visit.session = QrAuthorizationSession("replacement-session", "", 42L, "")
        assertFailsWith<CancellationException> { visit.repository.load(identity, coins) }
        assertTrue(visit.sent.isEmpty())
    }

    @Test
    fun leavingThePageCancelsItsPendingRequestAndDropsALateResponse() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val visit = Visit(scope)
            val pending = CompletableDeferred<Continuation<AccountLedgerResponse>>()
            visit.pending = pending
            visit.model.load(coins)
            val continuation = withTimeout(5_000) { pending.await() }
            val before = visit.model.state.value
            visit.owned = false
            scope.cancel()
            continuation.resume(success)
            scope.coroutineContext[Job]!!.join()
            assertEquals(before, visit.model.state.value)
            assertNull(visit.model.state.value.section(coins).entries)
            visit.model.load(experience)
            assertEquals(1, visit.sent.size)
        } finally { scope.cancel() }
    }

    @Test
    fun profileMenuCallbacksPushBothTypedLedgerDestinations() {
        val pushed = mutableListOf<BiliPaiNavKey>()
        val commands = Proxy.newProxyInstance(DesktopOriginalRootRouteCommands::class.java.classLoader,
            arrayOf(DesktopOriginalRootRouteCommands::class.java)) { _, method, args ->
            check(method.name == "push")
            pushed += args[0] as BiliPaiNavKey
            true
        } as DesktopOriginalRootRouteCommands
        val navigation = desktopOriginalRootProfileNavigation(commands, {}, {})
        AccountLedgerKind.entries.forEach(navigation.onAccountLedgerClick)
        assertEquals(AccountLedgerKind.entries.map { BiliPaiNavKey.AccountLedger(it) }, pushed)
        pushed.forEach { key ->
            val stack = BiliPaiNavBackStackController(listOf(BiliPaiNavKey.MainHost, BiliPaiNavKey.Profile)).push(key)
            assertEquals(key, stack.currentKey)
            assertEquals(BiliPaiNavKey.Profile, stack.pop().currentKey)
        }
    }
}
