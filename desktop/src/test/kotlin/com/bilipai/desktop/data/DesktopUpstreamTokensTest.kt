package com.bilipai.desktop.data

import android.content.Context
import android.content.SharedPreferences
import com.android.purebilibili.core.network.NetworkModule
import com.android.purebilibili.core.network.buildSpaceAggregateParams
import com.android.purebilibili.core.store.AccountSessionStore
import com.android.purebilibili.core.store.TokenManager
import com.bilipai.desktop.compat.UnsupportedDesktopCapability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** TON-1084 (TON-1071 B2): upstream TokenManager reads the session of the owner that started the operation, captured
 * in the owner's Store admission, and never writes or keeps a process-wide session. Synthetic accounts only. */
class DesktopUpstreamTokensTest {
    private val repositories = mutableListOf<DesktopRepository>()
    @AfterTest fun close() { repositories.forEach { runCatching { it.logout() } } }

    @Test fun everyReaderWithoutAnOwnerFailsLoudly() {
        assertNull(DesktopUpstreamCalls.currentOwner())
        val readers: List<Pair<String, () -> Any?>> = listOf(
            "sessDataCache" to { TokenManager.sessDataCache }, "csrfCache" to { TokenManager.csrfCache },
            "midCache" to { TokenManager.midCache }, "buvid3Cache" to { TokenManager.buvid3Cache },
            "accessTokenCache" to { TokenManager.accessTokenCache }, "refreshTokenCache" to { TokenManager.refreshTokenCache },
            "accessTokenPlatformCache" to { TokenManager.accessTokenPlatformCache }, "isVipCache" to { TokenManager.isVipCache },
            "accountIdentity" to { TokenManager.accountIdentity.value }, "getSessData" to { TokenManager.getSessData(context()) })
        for ((name, read) in readers) {
            val failure = assertFailsWith<UnsupportedDesktopCapability>(name) { read() }
            assertTrue("TokenManager.$name" in failure.message.orEmpty(), failure.message)
        }
        // The same in a coroutine that carries no owner, on another thread.
        assertFailsWith<UnsupportedDesktopCapability> { runBlocking(Dispatchers.IO) { TokenManager.sessDataCache } }
    }

    @Test fun accountOwnerReadsItsCapturedSnapshot() = runBlocking {
        val (repository, sessions) = repository(11L to "sess-a", 22L to "sess-b")
        val owner = repository.upstreamOwner(DesktopUpstreamPurpose.Main, { true })
        DesktopUpstreamCalls.withOwnerScope(owner) {
            withContext(Dispatchers.IO) {
                assertEquals("sess-b", TokenManager.sessDataCache)
                assertEquals("csrf-sess-b", TokenManager.csrfCache)
                assertEquals(22L, TokenManager.midCache)
                assertEquals("access-22", TokenManager.accessTokenCache)
                assertEquals("refresh-22", TokenManager.refreshTokenCache)
                assertEquals(TokenManager.ACCESS_TOKEN_PLATFORM_ANDROID, TokenManager.accessTokenPlatformCache)
                assertTrue(TokenManager.isVipCache)
                assertEquals(com.android.purebilibili.core.store.AccountSessionIdentity(22L, owner.epoch), TokenManager.accountIdentity.value)
                assertEquals(com.android.purebilibili.core.store.AccountSessionIdentity(22L, owner.epoch), TokenManager.accountIdentity.first())
                assertEquals("sess-b", TokenManager.getSessData(context()).first())
            }
        }
        // A profile flag update keeps the epoch: the captured snapshot does not change underneath the owner.
        assertTrue(sessions.updateHomeNavIdentity(owner.epoch, 22L, 22L, isVip = false))
        assertTrue(owner.isCurrent())
        DesktopUpstreamCalls.withOwner(owner) { assertTrue(TokenManager.isVipCache) }
    }

    @Test fun explicitGuestAndSignedOutOwnersReadNullOnly() = runBlocking {
        val (signedIn, _) = repository(22L to "sess-b")
        val guest = signedIn.upstreamOwner(DesktopUpstreamPurpose.Guest, { true })
        val derivedGuest = signedIn.upstreamOwner(DesktopUpstreamPurpose.Main, { true }).withPurpose(DesktopUpstreamPurpose.Guest)
        val (signedOutRepository, _) = repository()
        val signedOut = signedOutRepository.upstreamOwner(DesktopUpstreamPurpose.Main, { true })
        for (owner in listOf(guest, derivedGuest, signedOut)) DesktopUpstreamCalls.withOwnerScope(owner) {
            assertNull(TokenManager.sessDataCache); assertNull(TokenManager.csrfCache); assertNull(TokenManager.midCache)
            assertNull(TokenManager.accessTokenCache); assertNull(TokenManager.refreshTokenCache)
            assertFalse(TokenManager.isVipCache)
            assertEquals(TokenManager.ACCESS_TOKEN_PLATFORM_TV, TokenManager.accessTokenPlatformCache)
            assertNull(TokenManager.accountIdentity.value.mid)
            assertNull(TokenManager.getSessData(context()).first())
        }
        // A guest has its visitor identity only, never the account's.
        DesktopUpstreamCalls.withOwner(guest) { assertEquals(signedIn.upstreamGuestBuvid3, TokenManager.buvid3Cache) }
        DesktopUpstreamCalls.withOwner(derivedGuest) { assertEquals(signedIn.upstreamGuestBuvid3, TokenManager.buvid3Cache) }
    }

    @Test fun guestRequestParametersCarryNoAccountCredential() = runBlocking {
        val (repository, _) = repository(22L to "sess-b")
        val account = repository.upstreamOwner(DesktopUpstreamPurpose.Main, { true })
        // Upstream builds its APP parameters from TokenManager: the account owner signs with its own access key ...
        val signed = DesktopUpstreamCalls.withOwner(account) { buildSpaceAggregateParams(1L, TokenManager.accessTokenCache, TokenManager.accessTokenPlatformCache) }
        assertEquals("access-22", signed["access_key"])
        // ... and the same code under a guest owner of the same window has no access key or csrf to mix in.
        val guest = account.withPurpose(DesktopUpstreamPurpose.Guest)
        val anonymous = DesktopUpstreamCalls.withOwner(guest) { buildSpaceAggregateParams(1L, TokenManager.accessTokenCache, TokenManager.accessTokenPlatformCache) }
        assertFalse("access_key" in anonymous, anonymous.keys.toString())
        assertFalse(anonymous.values.any { "access-22" in it || "sess-b" in it }, anonymous.toString())
        DesktopUpstreamCalls.withOwner(guest) { assertNull(TokenManager.csrfCache) }
    }

    @Test fun readsAfterTheOwnerRetiredAreRefused() = runBlocking {
        val (repository, sessions) = repository(11L to "sess-a", 22L to "sess-b")
        // Account switch.
        val switched = repository.upstreamOwner(DesktopUpstreamPurpose.Main, { true })
        assertTrue(sessions.activateAccount(11))
        assertRefused { DesktopUpstreamCalls.withOwner(switched) { TokenManager.sessDataCache } }
        assertRefused { DesktopUpstreamCalls.withOwner(switched) { TokenManager.accessTokenCache } }
        val identity = DesktopUpstreamCalls.withOwner(repository.upstreamOwner(DesktopUpstreamPurpose.Main, { true })) { TokenManager.accountIdentity }
        // Window close: the flow taken before is refused too.
        var open = true
        val window = repository.upstreamOwner(DesktopUpstreamPurpose.Main, { open })
        val windowIdentity = DesktopUpstreamCalls.withOwner(window) { TokenManager.accountIdentity }
        DesktopUpstreamCalls.withOwner(window) { assertEquals("sess-a", TokenManager.sessDataCache) }
        open = false
        assertRefused { DesktopUpstreamCalls.withOwner(window) { TokenManager.csrfCache } }
        assertRefused { windowIdentity.value }
        assertRefused { DesktopUpstreamCalls.withOwnerScope(window) { TokenManager.midCache } }
        // Logout retires the signed-in owner; its identity flow does not keep the old account.
        assertTrue(identity.value.mid == 11L)
        repository.logout()
        assertRefused { identity.value }
        assertRefused { identity.first() }
    }

    @Test fun flowsTakenEarlierAreCheckedAgainOnEveryLateCollection() = runBlocking {
        // Logout and window close, each with a flow whose first collection only happens after its owner retired.
        for (retire in listOf("logout", "window close")) {
            val (repository, _) = repository(11L to "sess-a")
            val (other, _) = repository(22L to "sess-b")
            var open = true
            val owner = repository.upstreamOwner(DesktopUpstreamPurpose.Main, { open })
            val session = DesktopUpstreamCalls.withOwner(owner) { TokenManager.getSessData(context()) }
            val identity = DesktopUpstreamCalls.withOwner(owner) { TokenManager.accountIdentity }
            // Collected repeatedly while the owner is current, inside or outside its scope.
            assertEquals("sess-a", session.first())
            assertEquals("sess-a", withContext(Dispatchers.IO) { session.first() })
            assertEquals(11L, identity.first().mid)
            if (retire == "logout") repository.logout() else open = false
            assertFalse(owner.isCurrent())
            // Outside any owner, and inside another, current owner: neither may revive the retired one.
            assertRefused { session.first() }
            assertRefused { identity.first() }
            val current = other.upstreamOwner(DesktopUpstreamPurpose.Main, { true })
            assertRefused { DesktopUpstreamCalls.withOwnerScope(current) { session.first() } }
            assertRefused { DesktopUpstreamCalls.withOwnerScope(current) { identity.value } }
            DesktopUpstreamCalls.withOwner(current) { assertEquals("sess-b", runBlocking { TokenManager.getSessData(context()).first() }) }
        }
    }

    @Test fun playbackOwnerReadsTheMainAccountOfItsEpochAndRetiresWithItsRevision() = runBlocking {
        val (repository, sessions) = repository(22L to "sess-playback", 11L to "sess-main")
        assertTrue(sessions.setPlaybackAccountMid(22, sessions.generation) { true })
        val authorization = repository.capturePlaybackAuthorization(repository.sessionEpoch) { true }
        val playback = repository.upstreamOwner(DesktopUpstreamPurpose.Playback(authorization), { true })
        // Android's TokenManager is the main account; the playback account is resolved separately.
        DesktopUpstreamCalls.withOwner(playback) {
            assertEquals("sess-main", TokenManager.sessDataCache)
            assertEquals(11L, TokenManager.midCache)
        }
        assertTrue(sessions.setPlaybackAccountMid(null, sessions.generation) { true })
        assertRefused { DesktopUpstreamCalls.withOwner(playback) { TokenManager.sessDataCache } }
    }

    @Test fun sameEpochInOtherStoresAndWindowsNeverMixes() = runBlocking {
        val (first, firstSessions) = repository(11L to "sess-a")
        val (second, secondSessions) = repository(22L to "sess-b")
        assertEquals(firstSessions.generation, secondSessions.generation)
        var firstWindowOpen = true
        val a = first.upstreamOwner(DesktopUpstreamPurpose.Main, { firstWindowOpen })
        val b = second.upstreamOwner(DesktopUpstreamPurpose.Main, { true })
        val otherWindowOfFirst = first.upstreamOwner(DesktopUpstreamPurpose.Main, { true })
        assertEquals(a.epoch, b.epoch)
        DesktopUpstreamCalls.withOwnerScope(a) {
            assertEquals("sess-a", TokenManager.sessDataCache)
            DesktopUpstreamCalls.withOwnerScope(b) {
                withContext(Dispatchers.Default) { assertEquals("sess-b", TokenManager.sessDataCache); assertEquals(22L, TokenManager.midCache) }
            }
            withContext(Dispatchers.IO) { assertEquals(11L, TokenManager.midCache) }
            // A new independent scope has no owner, so it cannot read anyone's session.
            assertFailsWith<UnsupportedDesktopCapability> { CoroutineScope(Dispatchers.IO).async { TokenManager.midCache }.await() }
        }
        // Closing one window retires only that window's owner.
        firstWindowOpen = false
        assertRefused { DesktopUpstreamCalls.withOwner(a) { TokenManager.sessDataCache } }
        DesktopUpstreamCalls.withOwner(otherWindowOfFirst) { assertEquals("sess-a", TokenManager.sessDataCache) }
        DesktopUpstreamCalls.withOwner(b) { assertEquals("sess-b", TokenManager.sessDataCache) }
    }

    @Test fun windowSessionReadsTheActiveAccountAtCallTime() = runBlocking {
        val (repository, sessions) = repository(11L to "sess-a", 22L to "sess-b")
        var open = true
        val window = DesktopUpstreamSession(repository) { open }
        assertEquals("sess-b", window.tokens("test").sessData)
        assertTrue(sessions.activateAccount(11))
        assertEquals("sess-a", window.tokens("test").sessData)
        assertEquals(11L, window.tokens("test").mid)
        open = false
        assertRefused { window.tokens("test") }
        val missing = assertFailsWith<UnsupportedDesktopCapability> { DesktopUpstreamSession.require(null, "TokenManager read") }
        assertTrue("LocalDesktopUpstreamSession" in missing.message.orEmpty() || "window" in missing.message.orEmpty(), missing.message)
    }

    @Test fun messageProfileCacheBelongsToTheEntryAccountItIsGiven() {
        // Inbox and chat pass their entry's Root account; no owner and no process-wide identity is involved.
        val a = com.android.purebilibili.core.store.AccountSessionIdentity(11L, 1L)
        val b = com.android.purebilibili.core.store.AccountSessionIdentity(22L, 2L)
        val loader = com.android.purebilibili.feature.message.MessageUserInfoLoader
        val user = com.android.purebilibili.feature.message.UserBasicInfo(5L, "seen by A", "https://i0.hdslb.com/a.jpg")
        assertEquals("seen by A", loader.remember(user, a)?.name)
        assertEquals("seen by A", loader.cached(5L, a)?.name)
        // Another account never reads A's cache, and A's entries are gone once B used it.
        assertNull(loader.cached(5L, b))
        assertNull(loader.cached(5L, a))
        // Without an identity it is upstream code, which must run inside an owner.
        assertFailsWith<UnsupportedDesktopCapability> { loader.cached(5L) }
    }

    @Test fun everySessionWriteIsRefusedBeforeItTouchesStateOrStorage() = runBlocking {
        val (repository, sessions) = repository(22L to "sess-b")
        val owner = repository.upstreamOwner(DesktopUpstreamPurpose.Main, { true })
        val context = context()
        val generation = sessions.generation
        val writes: List<Pair<String, suspend () -> Unit>> = listOf(
            "TokenManager.saveCookies" to { TokenManager.saveCookies(context, "sess-x") },
            "TokenManager.saveCsrf" to { TokenManager.saveCsrf(context, "csrf-x") },
            "TokenManager.saveMid" to { TokenManager.saveMid(context, 99L) },
            "TokenManager.saveAccessToken" to { TokenManager.saveAccessToken(context, "access-x", "refresh-x") },
            "TokenManager.clearAccessToken" to { TokenManager.clearAccessToken(context) },
            "TokenManager.saveVipStatus" to { TokenManager.saveVipStatus(true) },
            "TokenManager.saveBuvid3" to { TokenManager.saveBuvid3(context, "buvid-x") },
            "TokenManager.applyStoredSession" to { TokenManager.applyStoredSession(context, "sess-x", "csrf-x", 99L, "a", "r", buvid3 = "b", isVip = true) },
            "TokenManager.clear" to { TokenManager.clear(context) },
            "TokenManager.init" to { TokenManager.init(context) },
            "TokenManager.refreshServiceSession" to { TokenManager.refreshServiceSession(context) },
            "TokenManager.observeServiceSession" to { TokenManager.observeServiceSession(context) { fail("observer ran") } },
            "TokenManager.buvid3Cache" to { TokenManager.buvid3Cache = "buvid-x" },
            "TokenManager.csrfCache" to { TokenManager.csrfCache = "csrf-x" },
            "TokenManager.isVipCache" to { TokenManager.isVipCache = true },
            "AccountSessionStore.setPlaybackAccountMid" to { AccountSessionStore.setPlaybackAccountMid(context, 22L) },
            "AccountSessionStore.clearActiveAccount" to { AccountSessionStore.clearActiveAccount(context) },
            "AccountSessionStore.removeAccount" to { AccountSessionStore.removeAccount(context, 22L) },
            "AccountSessionStore.upsertCurrentAccount" to { AccountSessionStore.upsertCurrentAccount(context) },
            "AccountSessionStore.updateAccountAvatar" to { AccountSessionStore.updateAccountAvatar(context, 22L, "face") },
            "AccountSessionStore.activateAccount" to { AccountSessionStore.activateAccount(context, 22L) },
            "AccountSessionStore.importTransferredSession" to { AccountSessionStore.importTransferredSession(context, bundle()) },
            "NetworkModule.clearRuntimeCookies" to { NetworkModule.clearRuntimeCookies() },
            "NetworkModule.clearPlaybackAccountClient" to { NetworkModule.clearPlaybackAccountClient() },
        )
        // Refused with an owner as well as without one: no upstream path may write a session.
        for (withOwner in listOf(false, true)) for ((name, write) in writes) {
            val failure = assertFailsWith<UnsupportedDesktopCapability>(name) {
                if (withOwner) DesktopUpstreamCalls.withOwnerScope(owner) { write() } else write()
            }
            assertTrue(name in failure.message.orEmpty(), "$name: ${failure.message}")
        }
        // Nothing changed: same epoch, same account, the owner is still current and reads what it captured.
        assertEquals(generation, sessions.generation)
        assertEquals(22L, sessions.activeAccountMid())
        assertEquals("sess-b", sessions.currentCookies()["SESSDATA"])
        assertTrue(owner.isCurrent())
        DesktopUpstreamCalls.withOwner(owner) { assertEquals("sess-b", TokenManager.sessDataCache); assertEquals("csrf-sess-b", TokenManager.csrfCache) }
    }

    private suspend fun assertRefused(block: suspend () -> Any?) {
        val failure = try { block(); null } catch (failure: Throwable) { failure }
        assertNotNull(failure, "a read after the owner retired must be refused")
        assertTrue(failure is CancellationException || (failure is BiliApiException && failure.apiCode == -101),
            "expected a retired-owner refusal, got $failure")
    }

    private fun repository(vararg accounts: Pair<Long, String>): Pair<DesktopRepository, DesktopSessionStore> {
        val sessions = DesktopSessionStore.temporary()
        accounts.forEach { (mid, sessData) ->
            sessions.saveAccount(mapOf("SESSDATA" to sessData, "bili_jct" to "csrf-$sessData"), AccountSummary(mid, "Fixture $mid", "", isVip = true),
                credentials = DesktopAppCredentials("access-$mid", "refresh-$mid", TokenManager.ACCESS_TOKEN_PLATFORM_ANDROID))
        }
        return (DesktopRepository(sessions).also(repositories::add)) to sessions
    }

    /** A Context whose storage fails the test: a refused write must not reach it. */
    private fun context(): Context = object : Context(Files.createTempDirectory("ton1084-context").toFile()) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = fail("upstream session storage was touched: $name")
    }

    private fun bundle() = com.android.purebilibili.data.session.BiliPaiSessionBundle(
        mid = 99L, sessData = "sess-x", csrf = "csrf-x", accessToken = "a", refreshToken = "r",
        accessTokenPlatform = TokenManager.ACCESS_TOKEN_PLATFORM_TV, buvid3 = "b", isVip = false)
}
