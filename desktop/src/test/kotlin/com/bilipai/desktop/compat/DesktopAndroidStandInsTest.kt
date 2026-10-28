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
import com.bilipai.desktop.ui.DesktopProfileAccountPort
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Upstream code compiled from the merged tree must not lose user state through a stand-in that "works". */
class DesktopAndroidStandInsTest {
    @Test
    fun `upstream session encryption fails loudly instead of skipping persistence`() {
        DesktopAndroidKeyStoreGuard.install()
        // On a plain JVM upstream catches the missing provider and returns null, after which
        // TokenManager deletes the persisted session fields. The guard turns that into an Error.
        val failure = assertFailsWith<UnsupportedDesktopCapability> { SessionStorageCipher.encrypt("fixture-session") }
        assertTrue("DesktopSessionStore" in failure.message.orEmpty(), failure.message)
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
