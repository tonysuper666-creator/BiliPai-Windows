package com.bilipai.desktop.data

import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

class DesktopLoginInstallationEventTest {
    private val account = AccountSummary(42, "fixture", "")
    private val cookies = mapOf("SESSDATA" to "isolated-event-test")

    @Test fun repeatedIdenticalInstallsNotifyActiveAndLateCollectorsWithoutChangingEpoch(): Unit = runBlocking {
        val store = DesktopSessionStore.temporary()
        store.saveAccount(cookies, account, imported = true)
        val epoch = store.generation
        assertNull(store.loginInstallation.value)
        val first = store.saveLoginAccount(cookies, account, null, null, epoch) { true }
        val firstEvent = assertNotNull(store.loginInstallation.value)
        assertSame(first, firstEvent.receipt)
        val next = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5_000) { store.loginInstallation.first { it != null && it !== firstEvent } }
        }
        val second = store.saveLoginAccount(cookies, account, null, null, epoch) { true }
        assertEquals(epoch, store.generation)
        assertEquals(first, second, "Receipts may have identical fields")
        assertNotSame(firstEvent, next.await(), "Identical receipts must still notify")
        assertSame(second, store.loginInstallation.first { it != null }?.receipt, "Late collector receives latest commit")
    }

    @Test fun retiredIntentAndStaleEpochDoNotPublishOrInvalidateTheCurrentMount() {
        val store = DesktopSessionStore.temporary()
        store.saveAccount(cookies, account, imported = true)
        val epoch = store.generation
        val mounted = store.captureHomeNavRequest(epoch, account.mid) { true }
        assertFailsWith<CancellationException> { store.saveLoginAccount(cookies, account, null, null, epoch) { false } }
        assertFailsWith<CancellationException> { store.saveLoginAccount(cookies, account, null, null, epoch - 1) { true } }
        assertNull(store.loginInstallation.value)
        assertTrue(store.withCurrentHomeNavRequest(mounted, { true }) {})
    }

    @Test fun failedPersistenceKeepsTheLastNotificationAndMountedCredentials() {
        val file = Files.createTempDirectory("bp-install-failure-").resolve("session.json")
        val store = DesktopSessionStore(file)
        val originalProtection = DesktopCredentialCipher.protection
        try {
            store.saveAccount(cookies, account, imported = true)
            val epoch = store.generation
            store.saveLoginAccount(cookies, account, null, null, epoch) { true }
            val committed = store.loginInstallation.value
            val mounted = store.captureHomeNavRequest(epoch, account.mid) { true }
            val savedBytes = Files.readAllBytes(file)
            DesktopCredentialCipher.protection = object : DesktopCredentialProtection {
                override val id = originalProtection.id
                override fun protect(plain: ByteArray): ByteArray = throw DesktopCredentialProtectionException("isolated protection failure")
                override fun unprotect(sealed: ByteArray): ByteArray = originalProtection.unprotect(sealed)
            }
            assertFailsWith<DesktopCredentialProtectionException> {
                store.saveLoginAccount(mapOf("SESSDATA" to "new-isolated-session"), account, null, null, epoch) { true }
            }
            assertSame(committed, store.loginInstallation.value)
            assertEquals(epoch, store.generation)
            assertContentEquals(savedBytes, Files.readAllBytes(file))
            assertTrue(store.withCurrentHomeNavRequest(mounted, { true }) {})
        } finally {
            DesktopCredentialCipher.protection = originalProtection
            file.parent.toFile().deleteRecursively()
        }
    }
}
