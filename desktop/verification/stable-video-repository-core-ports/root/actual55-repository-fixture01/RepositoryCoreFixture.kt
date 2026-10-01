package com.bilipai.desktop.ui

import com.bilipai.desktop.data.*
import com.bilipai.desktop.player.PlayerPreferences
import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.model.response.PlayUrlData
import kotlinx.coroutines.*
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

object RepositoryCoreFixture {
    private var assertions = 0
    private val checks = mutableListOf<String>()
    private fun verify(value: Boolean, label: String) { check(value) { label }; assertions++; checks += label }
    private fun cancelled(label: String, block: () -> Unit) {
        try { block(); error("Expected cancellation: $label") }
        catch (_: CancellationException) { assertions++; checks += label }
    }
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val data = PlayUrlData(quality = 80, format = "full-raw-fixture", acceptQuality = listOf(80, 64), curLanguage = "original")
        val cache = DesktopPlaybackCache()
        fun key(index: Int) = DesktopPlaybackCache.Key(0, "BV-memory-$index", index.toLong(), 80, "hev1", authorizationRevision = 0)
        cache.put(key(1), data, 80, now = 1_000)
        verify(cache.get(key(1), now = 601_000)?.data === data, "Original strict expiry: equality remains cached at 10 minutes")
        verify(cache.get(key(1), now = 601_001) == null, "Original expiry: one millisecond beyond 10 minutes removes entry")
        repeat(80) { cache.put(key(it), data, 80, now = 2_000) }
        verify((0 until 80).all { cache.get(key(it), now = 2_001) != null }, "Single cache retains original 80 entries")
        cache.get(key(0), now = 2_002); cache.put(key(80), data, 80, now = 2_002)
        verify(cache.get(key(1), now = 2_003) == null && cache.get(key(0), now = 2_003) != null, "Existing access LRU preserves touched entry and evicts oldest")
        val changed = key(81).copy(authorizationRevision = 1)
        cache.put(changed, data, 80, now = 2_004)
        verify(cache.get(key(0), now = 2_005) == null, "Single cache removes prior authorization revision")

        val store = DesktopSessionStore.temporary()
        val repository = DesktopRepository(store)
        val entryJob = Job()
        var current = true
        var commits = 0
        suspend fun capture() = DesktopOriginalVideoRepositoryBinding.capture(repository, store.generation, entryJob,
            { current }, { block -> if (!current) false else { commits++; block(); true } },
            PlayerPreferences(videoCodecPreference = "hev1", videoSecondCodecPreference = "avc1"), null, emptySet(),
            av1Supported = false, auto1080pEnabled = { true }, directedTrafficEnabled = { false },
            isMobileData = { false }, canRefreshPrimaryToken = { false },
            refreshPrimaryToken = { _, _ -> error("Guest fixture must not refresh token") })
        val firstReady = CompletableDeferred<DesktopOriginalVideoRepositoryBinding>()
        val firstWorker = launch { firstReady.complete(capture()); awaitCancellation() }
        val first = firstReady.await()
        first.environment.cache.put("BV-single", 88, data, 80)
        val actualCache = DesktopRepository::class.java.getDeclaredField("playbackCache").let { it.isAccessible = true; it.get(repository) as DesktopPlaybackCache }
        val actualKey = repository.originalVideoCacheKey(repository.capturePlaybackAuthorization(store.generation) { true },
            "BV-single", 88, 80, PlayerPreferences().normalized(), null, emptySet(), false)
        verify(actualCache.get(actualKey)?.data === data, "Canonical RawCache writes exact existing Repository cache, full raw DTO identity retained")
        verify(first.environment.cache.get("BV-single", 88, 80) === data, "Canonical RawCache reads full raw DTO without PlaybackSource flattening")
        verify(!actualKey.av1Supported, "Cache key consumes required actual AV1 capability")
        first.environment.state.appApiCooldownUntilMs = 12_345
        first.environment.state.last412Time = 54_321
        verify(first.environment.state.appApiCooldownUntilMs == 12_345L && first.environment.state.last412Time == 54_321L,
            "Canonical cooldown and 412 use admitted single Repository diagnostics")
        val wbi = "a".repeat(32) to "b".repeat(32)
        val timestamp = System.currentTimeMillis()
        first.environment.state.wbiKeys = wbi
        first.environment.state.wbiKeysTimestamp = timestamp
        var navCalls = 0
        val noSocketApi = Proxy.newProxyInstance(BilibiliApi::class.java.classLoader, arrayOf(BilibiliApi::class.java)) { _, method, _ ->
            if (method.name == "getNavInfo") navCalls++
            error("No network/API method expected: ${method.name}")
        } as BilibiliApi
        verify(repository.homeWbiKeys(store.generation, { current }, noSocketApi).getOrThrow() == wbi && navCalls == 0,
            "Existing Home signer reads same original-protocol WBI fields without nav fetch")
        verify(first.environment.state.wbiKeysTimestamp == timestamp, "Original WBI timestamp roundtrips the same expiry field")
        val expiry = DesktopRepository::class.java.getDeclaredField("wbiExpiresAt").let { it.isAccessible = true; it.getLong(repository) }
        verify(expiry - timestamp == 1_800_000L, "Shared original WBI lifetime is exactly 30 minutes")
        verify(first.environment.auto1080pEnabled() && !first.environment.isMobileData(), "Required preference/platform closures are consumed, not synthesized by bridge")
        verify(!first.rawRepository.isPlaybackLoggedIn(), "Real temporary Store guest state projects original playback auth")

        val revisionBefore = first.receipt.revision
        verify(store.setPlaybackAccountMid(null, store.generation) { true }, "Actual Store playback selection mutation succeeds without changing primary account")
        verify(repository.playbackAuthorizationRevision.value > revisionBefore, "Same epoch selection change invalidates captured authorization revision")
        cancelled("Old receipt rejects cache write") { first.environment.cache.put("BV-stale", 99, data, 80) }
        cancelled("Old receipt rejects diagnostics mutation") { first.environment.state.last412Time = 999 }
        cancelled("Old receipt rejects credential/config read") { first.environment.playbackAccessToken() }
        cancelled("Old receipt rejects raw environment before API") { first.environment.assertOwned() }
        firstWorker.cancelAndJoin()

        val secondReady = CompletableDeferred<DesktopOriginalVideoRepositoryBinding>()
        val secondWorker = launch { secondReady.complete(capture()); awaitCancellation() }
        val second = secondReady.await()
        verify(second.receipt.revision > revisionBefore, "New load captures current receipt rather than mutating old binding")
        second.environment.cache.put("BV-new", 100, data, 80)
        verify(second.environment.cache.get("BV-new", 100, 80) === data, "New load remains usable after prior mutation cancellation")
        secondWorker.cancelAndJoin()
        verify(entryJob.isActive && current, "Cancelling request keeps page entry and same Store alive")
        cancelled("Cancelled caller Job cannot read cache") { second.environment.cache.get("BV-new", 100, 80) }
        cancelled("Cancelled caller Job cannot update WBI") { second.environment.state.wbiKeys = null }
        current = false
        cancelled("Retired entry refuses old environment") { first.assertCurrent() }
        verify(commits > 0, "All cache/state accesses pass actual Store then supplied entry commit gate")
        entryJob.cancel()

        val origins = listOf(DesktopRepository::class.java, DesktopPlaybackCache::class.java, DesktopSessionStore::class.java,
            DesktopPlaybackAuthorizationReceipt::class.java, DesktopOriginalVideoRepositoryBinding::class.java,
            DesktopOriginalVideoLoadProtocolEnvironment::class.java, PlayUrlData::class.java).map { cls ->
            val bytes = requireNotNull(cls.getResourceAsStream("/" + cls.name.replace('.', '/') + ".class")).use { it.readBytes() }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            "{\"class\":\"${cls.name}\",\"codeSource\":\"${cls.protectionDomain.codeSource.location.toURI()}\",\"sha256ClassBytes\":\"$hash\"}"
        }
        val output = "{\"passed\":true,\"assertions\":$assertions,\"checks\":[${checks.joinToString(",") { "\"$it\"" }}],\"origins\":[${origins.joinToString(",")}],\"scope\":\"prepared Repository/cache overrides; actual Store/receipt/DTO; no socket/account/network/native/fullVM\"}\n"
        Files.writeString(Path.of(args.single()), output)
        println("PASS $assertions focused Store/cache/request assertions; no sockets/native")
    }
}
