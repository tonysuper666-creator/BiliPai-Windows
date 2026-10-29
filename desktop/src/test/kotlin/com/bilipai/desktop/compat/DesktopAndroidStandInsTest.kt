package com.bilipai.desktop.compat

import android.content.ContentResolver
import android.net.Uri
import android.security.keystore.DesktopAndroidKeyStoreGuard
import androidx.datastore.core.MultiProcessDataStoreFactory
import androidx.datastore.core.Serializer
import com.android.purebilibili.core.store.SessionStorageCipher
import com.android.purebilibili.core.store.StoredAccountSession
import com.android.purebilibili.data.model.response.NavData
import com.android.purebilibili.data.repository.legacyMemberSignCookie
import com.bilipai.desktop.data.DesktopCredentialCipher
import com.bilipai.desktop.data.DesktopCredentialProtectionException
import com.bilipai.desktop.data.UnavailableCredentialProtection
import com.bilipai.desktop.ui.DesktopProfileAccountPort
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Upstream code compiled from the merged tree must not lose user state through a stand-in that "works". */
class DesktopAndroidStandInsTest {
    @Test
    fun `android keystore requests fail loudly instead of looking like a missing key`() {
        DesktopAndroidKeyStoreGuard.install()
        // On a plain JVM a missing provider is a KeyStoreException, which upstream catch blocks turn into
        // "skip persistence". The guard turns any remaining Android Keystore use into an Error.
        val failure = assertFailsWith<UnsupportedDesktopCapability> { java.security.KeyStore.getInstance("AndroidKeyStore") }
        assertTrue("DesktopSessionStore" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `upstream session encryption uses the desktop credential protection and never skips persistence`() {
        DesktopAndroidKeyStoreGuard.install()
        // Upstream answers an encryption failure with null, after which TokenManager deletes the persisted session
        // fields. The desktop patch seals through the platform protection and throws on failure instead.
        val sealed = assertNotNull(SessionStorageCipher.encrypt("fixture-session"))
        assertFalse("fixture-session" in sealed)
        assertTrue(DesktopCredentialCipher.isProtected(sealed), sealed)
        assertEquals("fixture-session", SessionStorageCipher.decrypt(sealed))
        val platform = DesktopCredentialCipher.protection
        try {
            DesktopCredentialCipher.protection = UnavailableCredentialProtection("TestOS")
            assertFailsWith<DesktopCredentialProtectionException> { SessionStorageCipher.encrypt("fixture-session") }
            assertFailsWith<DesktopCredentialProtectionException> { SessionStorageCipher.decrypt(sealed) }
        } finally { DesktopCredentialCipher.protection = platform }
        // An Android Keystore value cannot be opened on the desktop; it does not read as a logged-out "".
        assertFailsWith<DesktopCredentialProtectionException> { SessionStorageCipher.decrypt("v1:AAAA:BBBB") }
    }

    @Test
    fun `multi-process DataStore is refused instead of degraded to one process`() {
        assertFailsWith<UnsupportedDesktopCapability> {
            MultiProcessDataStoreFactory.create(TextSerializer) { File("unused.preferences_pb") }
        }
    }

    @Test
    fun `content resolver reads local files and refuses other schemes`(@TempDir directory: Path) {
        val image = directory.resolve("cover.png").toFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val resolver = ContentResolver()
        assertEquals(listOf<Byte>(1, 2, 3), resolver.openInputStream(Uri.fromFile(image)).use { it.readBytes().toList() })
        assertFailsWith<UnsupportedDesktopCapability> { resolver.openInputStream(Uri.parse("content://media/external/images/1")) }
    }

    @Test
    fun `member sign cookie comes from the active desktop account`() = runBlocking {
        val accounts = FixtureAccounts(active = 7, listOf(StoredAccountSession(mid = 3, sessData = "other"), StoredAccountSession(mid = 7, sessData = "fixture-sess")))
        assertEquals("SESSDATA=fixture-sess; bili_jct=fixture-csrf; DedeUserID=7", legacyMemberSignCookie(accounts, "fixture-csrf"))
        val signedOut = assertFailsWith<IllegalArgumentException> { legacyMemberSignCookie(FixtureAccounts(active = null, emptyList()), "fixture-csrf") }
        assertEquals("请先登录", signedOut.message)
    }

    private object TextSerializer : Serializer<String> {
        override val defaultValue = ""
        override suspend fun readFrom(input: java.io.InputStream) = input.readBytes().decodeToString()
        override suspend fun writeTo(t: String, output: java.io.OutputStream) = output.write(t.encodeToByteArray())
    }

    private class FixtureAccounts(private val active: Long?, private val accounts: List<StoredAccountSession>) : DesktopProfileAccountPort {
        override suspend fun getAccounts() = accounts
        override suspend fun getActiveAccountMid() = active
        override suspend fun getPlaybackAccountMid(): Long? = null
        override suspend fun setPlaybackAccountMid(mid: Long?) = false
        override fun currentMid() = active
        override fun hasSession() = active != null
        override fun accessTokenCredentials(): Pair<String?, String> = null to ""
        override fun qrAuthorizationSession() = throw UnsupportedOperationException()
        override suspend fun saveMid(mid: Long) = Unit
        override suspend fun saveVipStatus(vip: Boolean) = Unit
        override suspend fun upsertCurrentAccount(nav: NavData?) = Unit
        override suspend fun clearCurrentSession() = Unit
        override suspend fun clearActiveAccount() = Unit
        override suspend fun activateAccount(mid: Long) = false
        override fun removeAccount(mid: Long) = false
    }
}
