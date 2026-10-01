package com.bilipai.desktop.ui

import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.model.response.SimpleApiResponse
import com.android.purebilibili.data.repository.BilibiliBlockedListRemoteStatus
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private fun originalPreparedFixture() = runBlocking {
    val root = Files.createTempDirectory("home-retained-gate-")
    val store = DesktopSessionStore(root.resolve("session.json"), persistent = false)
    val global = DesktopPluginStore(root.resolve("global"))
    val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    fun gate(): DesktopHomeRetainedGate {
        val captured = requireNotNull(store.dynamicCacheOwner())
        return DesktopHomeRetainedGate(store, captured, { store.generation }, { store.account.value?.mid }, { true }, parent)
    }
    val results = mutableListOf<String>()
    val original = gate()
    check(original.owns())
    check(original.commit { global.update("proof", mapOf("value" to JsonPrimitive(1))) })
    // Drawing a covered video/favorite/audio has no admission dependency in this owner.
    repeat(3) { check(original.owns()) }
    original.closeAndJoin()
    check(!original.commit { global.update("proof", mapOf("value" to JsonPrimitive(2))) })
    check(global.preferences("proof")["value"] == JsonPrimitive(1))
    results += "retained entry does not depend on current drawing; close rejects future global commit"

    store.saveAccount(mapOf("SESSDATA" to "fixture-one", "bili_jct" to "fixture-csrf"), AccountSummary(71, "fixture", ""))
    val sameMid = gate()
    val oldEpoch = sameMid.epoch
    store.saveAccount(mapOf("SESSDATA" to "fixture-two", "bili_jct" to "fixture-csrf"), AccountSummary(71, "fixture", ""))
    check(store.generation != oldEpoch)
    check(!sameMid.owns())
    check(!sameMid.commit { error("old same-MID owner committed") })
    sameMid.closeAndJoin()
    results += "same-MID credential replacement rejects old owner before commit"

    val active = gate()
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val closingStarted = CountDownLatch(1)
    val closed = CountDownLatch(1)
    val mutation = thread(name = "home-proof-commit") {
        check(active.commit { entered.countDown(); check(release.await(3, TimeUnit.SECONDS))
            global.update("proof", mapOf("value" to JsonPrimitive(3))) })
    }
    check(entered.await(3, TimeUnit.SECONDS))
    val retiring = thread(name = "home-proof-retire") { closingStarted.countDown(); active.close(); closed.countDown() }
    check(closingStarted.await(3, TimeUnit.SECONDS))
    check(!closed.await(100, TimeUnit.MILLISECONDS))
    release.countDown(); mutation.join(3000); retiring.join(3000)
    check(!mutation.isAlive && !retiring.isAlive)
    check(global.preferences("proof")["value"] == JsonPrimitive(3))
    check(!active.commit { global.update("proof", mapOf("value" to JsonPrimitive(4))) })
    active.closeAndJoin()
    results += "accepted Store-to-entry commit completes atomically before retirement; queued old writes rejected"

    val waiting = gate()
    val childStarted = CompletableDeferred<Unit>()
    var reachedAfterCancellation = false
    val child = waiting.scope.launch { childStarted.complete(Unit); awaitCancellation(); reachedAfterCancellation = true }
    childStarted.await(); waiting.closeAndJoin()
    check(child.isCompleted && !reachedAfterCancellation && !waiting.owns())
    results += "close-and-join drains owned child tasks before replacement construction"

    val repository = DesktopRepository(store)
    val blocked = DesktopBlockedUpStore(DesktopPluginContext(global))
    val actions = DesktopBlockedUpRepository(repository, blocked)
    var calls = 0
    val api = java.lang.reflect.Proxy.newProxyInstance(BilibiliApi::class.java.classLoader,
        arrayOf(BilibiliApi::class.java)) { _, method, args ->
        check(method.name == "modifyRelation")
        check(args[1] == 5 && args[3] == 11)
        calls++; SimpleApiResponse(code = -400, message = "fixture failure")
    } as BilibiliApi
    val blockGate = gate()
    val failedRemote = actions.blockUpWithBilibiliSync(901, "fixture", "", expectedSessionEpoch = blockGate.epoch,
        stillOwned = blockGate::owns, commitLocal = blockGate::commit, ownedApi = api,
        ownedCsrf = { "fixture-csrf" }, ensureOwnedSession = { blockGate.assertOwned() })
    check(failedRemote.localChanged && failedRemote.remoteStatus == BilibiliBlockedListRemoteStatus.FAILED)
    check(blocked.records.value.any { it.mid == 901L } && calls == 1)
    blockGate.closeAndJoin()
    val rejected = runCatching { actions.blockUpWithBilibiliSync(902, "fixture", "", expectedSessionEpoch = blockGate.epoch,
        stillOwned = blockGate::owns, commitLocal = blockGate::commit, ownedApi = api,
        ownedCsrf = { "fixture-csrf" }, ensureOwnedSession = { blockGate.assertOwned() }) }.exceptionOrNull()
    check(rejected is CancellationException && blocked.records.value.none { it.mid == 902L } && calls == 1)
    results += "same block store keeps original local-first remote-failure result and rejects retired mutation before local/API"
    parent.cancel()
    println("RESULT " + results.joinToString(" | "))
    println("SCOPE source-only prepared gate; actual temp SessionStore/global PluginStore; no Shell mount, API, account or native window")
}

fun main() {
 val origins=listOf(DesktopHomeRetainedGate::class.java,
  com.bilipai.desktop.data.DesktopSessionStore::class.java,
  com.bilipai.desktop.data.DesktopRepository::class.java,
  com.bilipai.desktop.data.DesktopBlockedUpRepository::class.java,
  com.bilipai.desktop.plugins.DesktopPluginStore::class.java)
 check(origins.all { it.protectionDomain.codeSource.location.toString().endsWith("main-kotlin.jar") })
 originalPreparedFixture()
 println("ACTUAL39 five actual product class origins; zero production overrides; no full Root/account/network acceptance")
}
