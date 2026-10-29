package com.bilipai.desktop.data

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Saved logins survive a restart, are never written in plaintext, and a protection failure keeps the old session. */
class DesktopCredentialPersistenceTest {
    private val platform = DesktopCredentialCipher.protection

    @AfterTest fun restoreProtection() { DesktopCredentialCipher.protection = platform }

    private fun sessionFile(): Path = Files.createTempDirectory("bilipai-credentials-").resolve("session.json")
    private fun DesktopSessionStore.sentSessData(): String? =
        loadForRequest("https://api.bilibili.com/x/web-interface/nav".toHttpUrl()).firstOrNull { it.name == "SESSDATA" }?.value

    private val navUrl = "https://api.bilibili.com/x/web-interface/nav".toHttpUrl()
    private fun DesktopSessionStore.sentCookies(): Map<String, String> = loadForRequest(navUrl).associate { it.name to it.value }
    private fun serverCookie(name: String, value: String) =
        Cookie.Builder().name(name).value(value).domain("bilibili.com").path("/").secure().build()

    private fun child(vararg args: String): String {
        val java = ProcessHandle.current().info().command().orElseThrow()
        val command = listOf(java, "-cp", System.getProperty("java.class.path"),
            "-D${DesktopCredentialProtection.TEST_PROPERTY}=${System.getProperty(DesktopCredentialProtection.TEST_PROPERTY).orEmpty()}",
            "com.bilipai.desktop.data.DesktopCredentialProcessProbeKt") + args
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "probe timed out")
        assertEquals(0, process.exitValue(), output)
        return output
    }

    private object FailingProtection : DesktopCredentialProtection {
        override val id = platform().id
        override fun protect(plain: ByteArray): ByteArray = throw DesktopCredentialProtectionException("模拟：系统凭据保护暂不可用")
        override fun unprotect(sealed: ByteArray): ByteArray = throw DesktopCredentialProtectionException("模拟：系统凭据保护暂不可用")
        private fun platform() = DesktopCredentialProtection.forCurrentPlatform()
    }

    @Test fun `a login written by one process is read back by a new process through the platform protection`() {
        val file = sessionFile()
        child("write", file.toString(), "round-trip-sessdata", "4242")
        val content = Files.readString(file)
        assertFalse("round-trip-sessdata" in content, "SESSDATA must not be stored in plaintext")
        assertFalse("probe-csrf" in content, "bili_jct must not be stored in plaintext")
        assertTrue(Regex("\"(dpapi|test-only):v1:").containsMatchIn(content), content)
        val read = child("read", file.toString())
        assertTrue("protection=${DesktopCredentialCipher.protectionId}" in read, read)
        assertTrue("account=4242" in read, read)
        assertTrue("sessdata=round-trip-sessdata" in read, read)
        assertTrue("restoreFailure=null" in read, read)
    }

    @Test fun `an encryption failure keeps the previous session on disk and in memory and says why`() {
        val file = sessionFile()
        val store = DesktopSessionStore(file)
        store.saveAccount(mapOf("SESSDATA" to "old-sessdata", "bili_jct" to "old-csrf"), AccountSummary(1, "old", ""))
        val before = Files.readAllBytes(file)
        DesktopCredentialCipher.protection = FailingProtection
        val failure = assertFailsWith<DesktopCredentialProtectionException> {
            store.saveAccount(mapOf("SESSDATA" to "new-sessdata"), AccountSummary(2, "new", ""))
        }
        assertTrue("暂不可用" in failure.message.orEmpty())
        assertEquals(1L, store.account.value?.mid)
        assertEquals("old-sessdata", store.sentSessData())
        assertContentEquals(before, Files.readAllBytes(file))
        assertFailsWith<DesktopCredentialProtectionException> { store.logout() }
        assertEquals("old-sessdata", store.sentSessData())
        DesktopCredentialCipher.protection = platform
        val restarted = DesktopSessionStore(file)
        assertEquals(1L, restarted.account.value?.mid)
        assertEquals("old-sessdata", restarted.sentSessData())
    }

    @Test fun `an encryption failure during a login keeps every request cookie of the previous session`() {
        val file = sessionFile()
        val store = DesktopSessionStore(file)
        store.saveAccount(mapOf("SESSDATA" to "old-sessdata"), AccountSummary(1, "old", ""),
            snapshot = DesktopSessionStore.CookieSnapshot(listOf(serverCookie("bp_t", "old-server")), emptyMap()))
        val sentBefore = store.sentCookies()
        val currentBefore = store.currentCookies()
        val before = Files.readAllBytes(file)
        DesktopCredentialCipher.protection = FailingProtection
        assertFailsWith<DesktopCredentialProtectionException> {
            store.saveLoginAccount(mapOf("SESSDATA" to "new-sessdata", "bili_jct" to "new-csrf"), AccountSummary(2, "new", ""), null,
                DesktopSessionStore.CookieSnapshot(listOf(serverCookie("bili_jct", "new-csrf"), serverCookie("bp_t", "new-server")), emptyMap()),
                store.generation) { true }
        }
        assertEquals(1L, store.account.value?.mid)
        assertEquals(sentBefore, store.sentCookies())
        assertEquals(currentBefore, store.currentCookies())
        assertContentEquals(before, Files.readAllBytes(file))
    }

    @Test fun `an encryption failure while saving response cookies keeps the request cookies and the file`() {
        val file = sessionFile()
        val store = DesktopSessionStore(file)
        store.saveAccount(mapOf("SESSDATA" to "old-sessdata"), AccountSummary(1, "old", ""),
            snapshot = DesktopSessionStore.CookieSnapshot(listOf(serverCookie("bp_t", "old-server")), emptyMap()))
        val sentBefore = store.sentCookies()
        val generationBefore = store.generation
        val before = Files.readAllBytes(file)
        DesktopCredentialCipher.protection = FailingProtection
        assertFailsWith<DesktopCredentialProtectionException> {
            store.saveFromResponse(navUrl, listOf(serverCookie("bili_jct", "new-csrf"), serverCookie("bp_t", "new-server")))
        }
        assertEquals(sentBefore, store.sentCookies())
        assertEquals(generationBefore, store.generation)
        assertContentEquals(before, Files.readAllBytes(file))
        DesktopCredentialCipher.protection = platform
        store.saveFromResponse(navUrl, listOf(serverCookie("bili_jct", "new-csrf")))
        assertEquals("new-csrf", store.sentCookies()["bili_jct"])
    }

    @Test fun `a saved credential with an unsupported protection or version is refused and the file is kept`() {
        for (unsupported in listOf("dpapi:v2:AAAA", "test-only:v2:AAAA", "other:v1:AAAA", "dpapi:AAAA")) {
            val file = sessionFile()
            Files.writeString(file, """{"cookies":{"SESSDATA":"$unsupported"},"account":{"mid":9,"name":"x","avatar":""},"schemaVersion":3}""")
            val before = Files.readAllBytes(file)
            val store = DesktopSessionStore(file)
            assertNotNull(store.credentialRestoreFailure, unsupported)
            assertNull(store.account.value, unsupported)
            assertNull(store.sentSessData(), unsupported)
            assertContentEquals(before, Files.readAllBytes(file), unsupported)
            assertFailsWith<DesktopCredentialProtectionException>(unsupported) { DesktopCredentialCipher.unprotect(unsupported) }
        }
    }

    @Test fun `a decryption failure at startup keeps the file, reports the reason and recovers on the next start`() {
        val file = sessionFile()
        DesktopSessionStore(file).saveAccount(mapOf("SESSDATA" to "kept-sessdata"), AccountSummary(7, "kept", ""))
        val before = Files.readAllBytes(file)
        DesktopCredentialCipher.protection = FailingProtection
        val failed = DesktopSessionStore(file)
        assertNull(failed.account.value)
        assertTrue("暂不可用" in assertNotNull(failed.credentialRestoreFailure))
        // Automatic writes during the failed start do not replace the unreadable login.
        failed.saveSpiCookies(mapOf("buvid3" to "visitor"))
        failed.saveLoginIdentityBuvid("visitor-login")
        assertContentEquals(before, Files.readAllBytes(file))
        DesktopCredentialCipher.protection = platform
        val recovered = DesktopSessionStore(file)
        assertNull(recovered.credentialRestoreFailure)
        assertEquals(7L, recovered.account.value?.mid)
        assertEquals("kept-sessdata", recovered.sentSessData())
    }

    @Test fun `a new login after an unreadable restore sets the old file aside instead of deleting it`() {
        val file = sessionFile()
        Files.writeString(file, """{"cookies":{"SESSDATA":"dpapi:v1:AAAA"},"account":{"mid":9,"name":"other","avatar":""},"schemaVersion":3}""")
        val before = Files.readAllBytes(file)
        val store = DesktopSessionStore(file)
        if (DesktopCredentialCipher.protectionId != "dpapi") assertTrue("dpapi" in assertNotNull(store.credentialRestoreFailure))
        else assertNotNull(store.credentialRestoreFailure)
        store.saveAccount(mapOf("SESSDATA" to "fresh-sessdata"), AccountSummary(10, "fresh", ""))
        val kept = Files.list(file.parent).use { files -> files.filter { it.fileName.toString().startsWith("session.unreadable-") }.toList() }
        assertEquals(1, kept.size)
        assertContentEquals(before, Files.readAllBytes(kept.single()))
        val restarted = DesktopSessionStore(file)
        assertEquals(10L, restarted.account.value?.mid)
        assertEquals("fresh-sessdata", restarted.sentSessData())
    }

    @Test fun `logout, account switch and removal leave no old credential after a restart`() {
        val file = sessionFile()
        val store = DesktopSessionStore(file)
        store.saveAccount(mapOf("SESSDATA" to "first-sessdata"), AccountSummary(1, "first", ""))
        store.saveAccount(mapOf("SESSDATA" to "second-sessdata"), AccountSummary(2, "second", ""))
        DesktopSessionStore(file).let { restarted ->
            assertEquals(2L, restarted.account.value?.mid)
            assertEquals("second-sessdata", restarted.sentSessData())
        }
        store.logout()
        DesktopSessionStore(file).let { restarted ->
            assertNull(restarted.account.value)
            assertNull(restarted.sentSessData())
        }
        // Saved accounts stay switchable after logout, as upstream's AccountSessionStore keeps them; removal drops them.
        store.removeAccount(1); store.removeAccount(2)
        DesktopSessionStore(file).let { restarted ->
            assertNull(restarted.account.value)
            assertNull(restarted.sentSessData())
            assertTrue(restarted.storedAccountSessions().isEmpty())
        }
    }

    @Test fun `a host without system credential protection refuses to save instead of writing plaintext`() {
        val unavailable = UnavailableCredentialProtection("TestOS")
        DesktopCredentialCipher.protection = unavailable
        val failure = assertFailsWith<DesktopCredentialProtectionException> { DesktopCredentialCipher.protect("secret") }
        assertTrue("不保存明文" in failure.message.orEmpty())
        val file = sessionFile()
        assertFailsWith<DesktopCredentialProtectionException> {
            DesktopSessionStore(file).saveAccount(mapOf("SESSDATA" to "secret"), AccountSummary(3, "x", ""))
        }
        assertFalse(Files.exists(file))
    }

    @Test fun `a value sealed by another protection is never returned as plaintext`() {
        DesktopCredentialCipher.protection = TestOnlyCredentialProtection
        val sealed = DesktopCredentialCipher.protect("secret")
        assertTrue(sealed.startsWith("test-only:v1:"))
        assertEquals("secret", DesktopCredentialCipher.unprotect(sealed))
        DesktopCredentialCipher.protection = UnavailableCredentialProtection("TestOS")
        assertFailsWith<DesktopCredentialProtectionException> { DesktopCredentialCipher.unprotect(sealed) }
        assertFailsWith<DesktopCredentialProtectionException> { DesktopCredentialCipher.unprotect("dpapi:v1:AAAA") }
        // Pre-encryption plaintext from older builds is still read once and re-sealed on the next write.
        assertEquals("legacy", DesktopCredentialCipher.unprotect("legacy"))
    }
}
