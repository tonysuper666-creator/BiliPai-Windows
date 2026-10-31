package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.PassportApi
import com.android.purebilibili.data.model.response.NavResponse
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.bilipai.desktop.data.*
import java.awt.EventQueue
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlin.test.*

class DesktopHomeLoginInstallationConsumerTest {
    @Test fun sameEpochCommitAfterNavigationCancellationRebuildsRootAdmission(): Unit = runBlocking {
        val directory = Files.createTempDirectory("bp-root-login-consumer-")
        val store = DesktopSessionStore(directory.resolve("session.json"))
        val summary = AccountSummary(42, "fixture", "")
        val cookies = mapOf("SESSDATA" to "isolated-root-install")
        store.saveAccount(cookies, summary, imported = true)
        val repository = DesktopRepository(store)
        val response = Json { ignoreUnknownKeys = true }.decodeFromString<NavResponse>(
            """{"code":0,"data":{"isLogin":true,"mid":42,"uname":"fixture","face":""}}""")
        val api = Proxy.newProxyInstance(PassportApi::class.java.classLoader, arrayOf(PassportApi::class.java)) { _, method, _ ->
            check(method.name == "validateCookieSession")
            response
        } as PassportApi
        DesktopRepository::class.java.getDeclaredField("validationPassportApi").apply { isAccessible = true }.set(repository, api)
        val epoch = repository.sessionEpoch
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val preferences = DesktopDiscoveryPreferences(directory.resolve("preferences"))
        fun newGate() = DesktopHomeRetainedGate(repository.dynamicCacheSessionGuard,
            requireNotNull(repository.dynamicCacheSessionGuard.dynamicCacheOwner()),
            { repository.sessionEpoch }, { repository.account.value?.mid }, { true }, parent)
        fun newRequests(gate: DesktopHomeRetainedGate) = DesktopHomeRootRequestBinding(repository, preferences,
            gate.scope, epoch, summary.mid, gate::owns, gate::commit, { error("No HTTP in this fixture") })
        var gate = newGate()
        var requests = newRequests(gate)
        val oldGate = gate
        val oldRequests = requests
        val navigation = DesktopLoginReturnCoordinator { true }
        lateinit var binding: DesktopLoginReturnBinding
        EventQueue.invokeAndWait { binding = requireNotNull(navigation.beginLoginReturn(epoch, summary.mid,
            BiliPaiNavKey.History, DesktopLoginReturnOrigin.MODAL)) }
        val received = CompletableDeferred<DesktopLoginInstallationEvent>()
        val consumeNotification = CompletableDeferred<Unit>()
        val rootConsumer = launch(start = CoroutineStart.UNDISPATCHED) {
            val installation = repository.loginInstallation.filterNotNull().first()
            received.complete(installation)
            consumeNotification.await()
            // The same production Root predicate, actual entry gate and actual Store request binding.
            if (shouldRetireHomeForLoginInstallation(epoch, summary.mid, navigation.installedLoginReturn.value,
                    installation, requests.isMountedSourceCurrent())) {
                gate.close()
                requests.close()
                gate = newGate()
                requests = newRequests(gate)
            }
        }
        val committed = CompletableDeferred<Unit>()
        val returnToNavigation = CountDownLatch(1)
        val install = async(Dispatchers.Default, start = CoroutineStart.LAZY) {
            repository.installLogin(cookies, expectedLoginEpoch = epoch, stillOwned = binding.owns,
                onLoginInstalled = { receipt ->
                    committed.complete(Unit)
                    check(returnToNavigation.await(10, TimeUnit.SECONDS))
                    binding.installed(receipt)
                })
        }
        try {
            assertTrue(requests.isMountedSourceCurrent())
            install.start()
            withTimeout(10_000) { committed.await(); received.await() }
            assertEquals(epoch, repository.sessionEpoch)
            assertEquals(summary, DesktopSessionStore(directory.resolve("session.json")).account.value)
            assertFalse(oldRequests.isMountedSourceCurrent())
            assertTrue(oldGate.owns())
            EventQueue.invokeAndWait { binding.cancel() }
            returnToNavigation.countDown()
            install.await()
            assertNull(navigation.installedLoginReturn.value)
            consumeNotification.complete(Unit)
            withTimeout(10_000) { rootConsumer.join() }
            println("rootReceived=true; epoch=${repository.sessionEpoch}; navigationCancelled=${!binding.owns()}; " +
                "oldGateActive=${oldGate.owns()}; newMountedSource=${requests.isMountedSourceCurrent()}")
            assertFalse(oldGate.owns(), "Root must retire the stale same-epoch gate after a committed installation")
            assertNotSame(oldGate, gate)
            assertTrue(gate.owns())
            assertTrue(requests.isMountedSourceCurrent(), "Replacement Root must capture the committed Store stamp")
            assertFalse(oldRequests.isMountedSourceCurrent())
        } finally {
            returnToNavigation.countDown()
            consumeNotification.complete(Unit)
            install.join(); rootConsumer.cancelAndJoin()
            requests.close(); oldRequests.close(); gate.close(); oldGate.close()
            parent.cancel(); navigation.close()
            repository.httpClient.dispatcher.executorService.shutdownNow()
            repository.httpClient.connectionPool.evictAll()
            directory.toFile().deleteRecursively()
        }
    }

    @Test fun notificationOnlyRetiresItsMatchingInvalidatedRoot() {
        val event = DesktopLoginInstallationEvent(DesktopLoginInstallationReceipt(4, 42, 4, 42))
        assertTrue(shouldRetireHomeForLoginInstallation(4, 42, null, event, false))
        assertFalse(shouldRetireHomeForLoginInstallation(4, 42, null, event, true), "Replayed state must keep a fresh Root")
        assertFalse(shouldRetireHomeForLoginInstallation(5, 42, null, event, false), "A later epoch cannot consume an old install")
        assertFalse(shouldRetireHomeForLoginInstallation(4, 43, null, event, false), "Another MID cannot consume this install")
        assertFalse(shouldRetireHomeForLoginInstallation(4, null, null, event, false))
        assertFalse(shouldRetireHomeForLoginInstallation(4, 42, null, null, false))
    }

    @Test fun pendingNavigationReturnKeepsItsExistingSameEpochRetirementRule() {
        val navigation = DesktopLoginReturnCoordinator { true }
        try {
            lateinit var binding: DesktopLoginReturnBinding
            EventQueue.invokeAndWait { binding = requireNotNull(navigation.beginLoginReturn(4, 42,
                BiliPaiNavKey.History, DesktopLoginReturnOrigin.MODAL)) }
            binding.installed(DesktopLoginInstallationReceipt(4, 42, 4, 42))
            val pending = assertNotNull(navigation.installedLoginReturn.value)
            assertTrue(shouldRetireHomeForLoginInstallation(4, 42, pending, null, true))
            assertEquals(BiliPaiNavKey.History, navigation.consumeLoginReturn(pending, 4, 42))
            assertNull(navigation.consumeLoginReturn(pending, 4, 42), "Navigation is still consumed once")
        } finally { navigation.close() }
    }

}
