package com.bilipai.desktop.player

import com.android.purebilibili.core.network.*
import com.android.purebilibili.data.repository.DesktopOriginalBangumiHubRepository
import com.android.purebilibili.feature.bangumi.BangumiHubViewModel
import com.bilipai.desktop.data.*
import com.bilipai.desktop.ui.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Test
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopBangumiHubOwnedLifetimeTest {
    private inline fun <reified T> reject(): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java),
    ) { _, method, _ -> error("Unexpected request ${T::class.java.simpleName}.${method.name}") } as T

    @Test fun realGateCloseCancelsCompleteHubObserverAndItsSibling() = runTest {
        val store = DesktopSessionStore.temporary()
        val repository = DesktopRepository(store)
        val errors = mutableListOf<Throwable>()
        val parent = SupervisorJob()
        val parentScope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent +
            CoroutineExceptionHandler { _, error -> errors += error })
        val guard = repository.dynamicCacheSessionGuard
        val gate = DesktopHomeRetainedGate(guard, requireNotNull(guard.dynamicCacheOwner()),
            { repository.sessionEpoch }, { repository.account.value?.mid }, { true }, parentScope)
        val lifetime = DesktopHomeEmbeddedLifetime(gate.scope, gate::owns, gate::commit)
        val owned = repository.upstreamOwner(DesktopUpstreamPurpose.Main, lifetime::owns, gate.epoch)
        val identities = DesktopUpstreamCalls.withOwner(owned) {
            DesktopUpstreamCalls.accountIdentity("BangumiHubViewModel")
        }
        val ports = DesktopOriginalBangumiHubRepository(reject<BangumiApi>(), reject<BilibiliApi>(),
            reject<SearchApi>(), { error("Unexpected CSRF read") }, { error("Unexpected MID read") })
        var siblingStarted = false
        var siblingFinished = false
        var observerCompletion: Throwable? = null
        try {
            val instance = DesktopBangumiHubInstance(DesktopBangumiHubEnvironment(ports, lifetime.scope,
                { false }, lifetime::owns, lifetime::commit, identities))
            val vm = instance.viewModel
            assertEquals(gate.epoch, vm.uiState.value.accountGeneration)
            // The complete original VM creates one observer Job and its account request SupervisorJob.
            val observer = instance.environment.scope.coroutineContext[Job]!!.children.single { it.javaClass.simpleName == "StandaloneCoroutine" }
            observer.invokeOnCompletion { observerCompletion = it }
            val sibling = lifetime.scope.launch {
                siblingStarted = true
                try { awaitCancellation() } finally { siblingFinished = true }
            }
            runCurrent()
            assertTrue(siblingStarted)
            assertTrue(observer.isActive)
            gate.close()
            runCurrent()
            lifetime.closeAndJoin()
            gate.closeAndJoin()
            observer.join()
            sibling.join()
            assertTrue(observerCompletion is CancellationException)
            assertTrue(siblingFinished)
            assertTrue(errors.isEmpty(), errors.toString())
            assertFalse(lifetime.owns())
            assertEquals(gate.epoch, vm.uiState.value.accountGeneration)
        } finally {
            gate.close()
            lifetime.close()
            parent.cancel()
            runCurrent()
            parent.join()
        }
    }

    @Test fun mountedReceiptRetirementAndRepeatedRefreshKeepOneHostedVmAndObserver() = runTest {
        val directory = java.nio.file.Files.createTempDirectory("hub-stamp-boundary-")
        val store = DesktopSessionStore.temporary()
        val account = AccountSummary(71L, "synthetic", "")
        val cookies = mapOf("SESSDATA" to "synthetic-session")
        store.saveAccount(cookies, account)
        val repository = DesktopRepository(store)
        val errors = mutableListOf<Throwable>()
        val parent = SupervisorJob()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent +
            CoroutineExceptionHandler { _, e -> errors += e })
        val guard = repository.dynamicCacheSessionGuard
        val gate = DesktopHomeRetainedGate(guard, requireNotNull(guard.dynamicCacheOwner()),
            { repository.sessionEpoch }, { repository.account.value?.mid }, { true }, scope)
        val requests = DesktopHomeRootRequestBinding(repository, DesktopDiscoveryPreferences(directory),
            gate.scope, gate.epoch, gate.mid, gate::owns, gate::commit,
            { error("No authentication request in this fixture") })
        val lifetime = DesktopHomeEmbeddedLifetime(gate.scope, gate::owns, gate::commit)
        val owner = repository.upstreamOwner(DesktopUpstreamPurpose.Main,
            { lifetime.owns() && requests.isMountedSourceCurrent() }, gate.epoch)
        val identities = DesktopUpstreamCalls.withOwner(owner) {
            DesktopUpstreamCalls.accountIdentity("BangumiHubViewModel")
        }
        var httpRequests = 0
        val sealedClient = repository.httpClient.newBuilder().addInterceptor {
            httpRequests++
            error("Retired Hub must not issue HTTP")
        }.build()
        repository.javaClass.getDeclaredField("client").apply { isAccessible = true; set(repository, sealedClient) }
        val api = repository.ownedHomeService(BangumiApi::class.java, "https://api.bilibili.com/",
            gate.epoch, requests::isMountedSourceCurrent)
        val ports = DesktopOriginalBangumiHubRepository(api, reject<BilibiliApi>(),
            reject<SearchApi>(), { error("No CSRF") }, { 71L })
        val instance = DesktopBangumiHubInstance(DesktopBangumiHubEnvironment(ports, lifetime.scope,
            { true }, lifetime::owns, lifetime::commit, identities))
        val child = requireNotNull(instance.environment.scope.coroutineContext[Job])
        var observerEnd: Throwable? = null
        var siblingCompleted = false
        try {
            val vm = instance.viewModel
            val observer = child.children.single { it.javaClass.simpleName == "StandaloneCoroutine" }
            observer.invokeOnCompletion { observerEnd = it }
            runCurrent()
            assertTrue(requests.isMountedSourceCurrent())
            val receipt = store.saveLoginAccount(cookies, account, null, null, gate.epoch) { true }
            assertEquals(gate.epoch, receipt.acceptedEpoch)
            assertFalse(requests.isMountedSourceCurrent())
            runCurrent()
            assertTrue(gate.owns())
            assertTrue(lifetime.scope.isActive)
            assertTrue(observer.isActive, "Receipt invalidation alone has no notification to a child Job")
            assertEquals(gate.epoch, vm.uiState.value.accountGeneration)
            repeat(3) {
                // Same public refresh used by the retained Aggregate's original Home tab.
                assertFailsWith<CancellationException> { instance.viewModel.refreshHome() }
                runCurrent()
                val publicRequests = child.children.filter { it !== observer && it.javaClass.simpleName == "StandaloneCoroutine" }.toList()
                publicRequests.forEach { it.join() }
                assertSame(vm, instance.viewModel)
                assertEquals(listOf(observer), child.children.filter { it.javaClass.simpleName == "StandaloneCoroutine" }.toList())
            }
            assertEquals(0, httpRequests)
            // An explicit cancellation works, but is not evidence that production receives this event.
            child.cancel()
            val sibling = lifetime.scope.launch { siblingCompleted = true }
            runCurrent()
            child.join(); sibling.join()
            assertTrue(observerEnd is CancellationException)
            assertTrue(siblingCompleted)
            assertTrue(lifetime.scope.isActive)
            assertTrue(errors.isEmpty(), errors.toString())
        } finally {
            child.cancel(); requests.close(); gate.close(); lifetime.close(); parent.cancel()
            runCurrent(); parent.join()
            repository.httpClient.dispatcher.executorService.shutdownNow()
            repository.httpClient.connectionPool.evictAll()
            java.nio.file.Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).forEach(java.nio.file.Files::delete) }
        }
    }
}
