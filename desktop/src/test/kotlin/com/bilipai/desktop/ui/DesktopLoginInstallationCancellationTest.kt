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
import kotlinx.serialization.json.Json
import kotlin.test.*

class DesktopLoginInstallationCancellationTest {
    @Test fun committedSameEpochInstallSurvivesNavigationCancellation(): Unit = runBlocking {
        val file = Files.createTempDirectory("bp-login-install-").resolve("session.json")
        val store = DesktopSessionStore(file)
        val response = Json { ignoreUnknownKeys = true }.decodeFromString<NavResponse>(
            """{"code":0,"data":{"isLogin":true,"mid":42,"uname":"fixture","face":""}}""")
        val summary = AccountSummary(42, "fixture", "")
        val cookies = mapOf("SESSDATA" to "isolated-test-session")
        store.saveAccount(cookies, summary, imported = true)
        val repository = DesktopRepository(store)
        // Only the external validation API is isolated; the installer, persistence and intent are real.
        val api = Proxy.newProxyInstance(PassportApi::class.java.classLoader, arrayOf(PassportApi::class.java)) { _, method, _ ->
            check(method.name == "validateCookieSession") { "Unexpected API ${method.name}" }
            response
        } as PassportApi
        DesktopRepository::class.java.getDeclaredField("validationPassportApi").apply { isAccessible = true }.set(repository, api)
        val epoch = store.generation
        val mounted = repository.captureHomeNavRequest(epoch, 42) { true }
        val owner = DesktopLoginReturnCoordinator { true }
        lateinit var binding: DesktopLoginReturnBinding
        EventQueue.invokeAndWait { binding = requireNotNull(owner.beginLoginReturn(epoch, 42,
            BiliPaiNavKey.MainHost, DesktopLoginReturnOrigin.MODAL)) }
        val committed = CompletableDeferred<Unit>()
        val releaseNotification = CountDownLatch(1)
        val install = async(Dispatchers.Default) {
            repository.installLogin(cookies, expectedLoginEpoch = epoch, stillOwned = binding.owns,
                onLoginInstalled = { receipt ->
                    // This callback is after Store monitor release, before the real navigation receiver.
                    committed.complete(Unit)
                    check(releaseNotification.await(10, TimeUnit.SECONDS)) { "Notification barrier timed out" }
                    binding.installed(receipt)
                })
        }
        try {
            withTimeout(10_000) { committed.await() }
            assertEquals(epoch, store.generation)
            assertEquals(summary, DesktopSessionStore(file).account.value, "Save really reached disk")
            assertFalse(repository.withCurrentHomeNavRequest(mounted, { true }) {})
            EventQueue.invokeAndWait { binding.cancel() }
            assertFalse(binding.owns())
            releaseNotification.countDown()
            install.await()
            assertNull(owner.installedLoginReturn.value, "Cancelled navigation must stay cancelled")
            println("committedEpoch=${store.generation}; originalEpoch=$epoch; mountedCurrent=" +
                repository.withCurrentHomeNavRequest(mounted, { true }) {} +
                "; navigationNotification=${owner.installedLoginReturn.value}; storeNotification=${repository.loginInstallation.value}")
            assertNotNull(repository.loginInstallation.value?.receipt ?: owner.installedLoginReturn.value?.installation,
                "Committed login lost every installation notification after navigation cancellation")
            val event = assertNotNull(repository.loginInstallation.value)
            assertEquals(epoch, event.receipt.acceptedEpoch)
            assertEquals(42L, event.receipt.acceptedMid)
            val fresh = repository.captureHomeNavRequest(epoch, 42) { true }
            assertTrue(repository.withCurrentHomeNavRequest(fresh, { true }) {})
        } finally {
            releaseNotification.countDown()
            install.join()
            owner.close()
            repository.httpClient.dispatcher.executorService.shutdownNow()
            repository.httpClient.connectionPool.evictAll()
            file.parent.toFile().deleteRecursively()
        }
    }
}
