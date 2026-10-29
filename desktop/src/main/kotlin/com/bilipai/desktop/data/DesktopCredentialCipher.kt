package com.bilipai.desktop.data

import com.sun.jna.*
import com.sun.jna.win32.StdCallLibrary
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A desktop credential protection failed. Never turned into null, "" or plaintext: callers keep their previous
 * state (the saved file is not rewritten) and the message says why. */
internal class DesktopCredentialProtectionException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** Platform interface for protecting saved credentials. Each implementation tags its output, so a value is only ever
 * opened by the implementation that sealed it. */
internal interface DesktopCredentialProtection {
    /** Stable tag stored in front of every protected value, e.g. `dpapi`. */
    val id: String
    fun protect(plain: ByteArray): ByteArray
    fun unprotect(sealed: ByteArray): ByteArray

    companion object {
        /** Selects the test-only implementation on a non-Windows host (set by the Gradle test task, never by a package). */
        const val TEST_PROPERTY = "bilipai.credentialProtection"
        const val TEST_VALUE = "test-only"

        fun forCurrentPlatform(): DesktopCredentialProtection = when {
            System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> WindowsDpapiCredentialProtection
            System.getProperty(TEST_PROPERTY) == TEST_VALUE -> TestOnlyCredentialProtection
            else -> UnavailableCredentialProtection(System.getProperty("os.name"))
        }
    }
}

/** Windows DPAPI (CryptProtectData, current-user scope): saved credentials open only for the same Windows user. */
internal object WindowsDpapiCredentialProtection : DesktopCredentialProtection {
    override val id = "dpapi"
    interface Crypt32 : StdCallLibrary {
        fun CryptProtectData(input: Blob, description: WString?, entropy: Pointer?, reserved: Pointer?, prompt: Pointer?, flags: Int, output: Blob): Boolean
        fun CryptUnprotectData(input: Blob, description: Pointer?, entropy: Pointer?, reserved: Pointer?, prompt: Pointer?, flags: Int, output: Blob): Boolean
    }
    interface Kernel32 : StdCallLibrary { fun LocalFree(memory: Pointer): Pointer? }
    @Structure.FieldOrder("cbData", "pbData")
    class Blob : Structure() { @JvmField var cbData: Int = 0; @JvmField var pbData: Pointer? = null }
    private val crypt by lazy { Native.load("Crypt32", Crypt32::class.java) }
    private val kernel by lazy { Native.load("Kernel32", Kernel32::class.java) }
    private const val CRYPTPROTECT_UI_FORBIDDEN = 1

    override fun protect(plain: ByteArray): ByteArray = transform(plain, encrypt = true)
    override fun unprotect(sealed: ByteArray): ByteArray = transform(sealed, encrypt = false)

    private fun transform(bytes: ByteArray, encrypt: Boolean): ByteArray {
        if (bytes.isEmpty()) throw DesktopCredentialProtectionException("Windows 凭据保护收到空输入")
        val memory = Memory(bytes.size.toLong()).apply { write(0, bytes, 0, bytes.size) }
        val input = Blob().apply { cbData = bytes.size; pbData = memory; write() }
        val output = Blob()
        try {
            val success = if (encrypt) crypt.CryptProtectData(input, WString("BiliPai account"), null, null, null, CRYPTPROTECT_UI_FORBIDDEN, output)
                else crypt.CryptUnprotectData(input, null, null, null, null, CRYPTPROTECT_UI_FORBIDDEN, output)
            if (!success) throw DesktopCredentialProtectionException(
                (if (encrypt) "Windows 凭据保护（DPAPI）加密失败" else "Windows 凭据保护（DPAPI）解密失败，可能不是保存它的 Windows 用户") +
                    "（错误码 ${Native.getLastError()}）")
            output.read()
            return requireNotNull(output.pbData).getByteArray(0, output.cbData)
        } finally {
            output.pbData?.let { kernel.LocalFree(it) }
            memory.clear(); memory.close()
        }
    }
}

/** TEST ONLY. A fixed, public AES-GCM key: it exercises the sealed format and the failure paths on a development
 * host without a Windows user profile. It protects nothing and is selected only by [DesktopCredentialProtection.TEST_PROPERTY]. */
internal object TestOnlyCredentialProtection : DesktopCredentialProtection {
    override val id = "test-only"
    private val key = SecretKeySpec(ByteArray(32) { (it * 7 + 3).toByte() }, "AES")
    private val random = SecureRandom()

    override fun protect(plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        return iv + cipher.doFinal(plain)
    }

    override fun unprotect(sealed: ByteArray): ByteArray = try {
        require(sealed.size > 12)
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, 12)) }
            .doFinal(sealed, 12, sealed.size - 12)
    } catch (failure: Exception) {
        throw DesktopCredentialProtectionException("测试用凭据保护解密失败", failure)
    }
}

/** No system credential protection on this host: saving or opening a credential fails loudly instead of writing plaintext. */
internal class UnavailableCredentialProtection(private val osName: String) : DesktopCredentialProtection {
    override val id = "unavailable"
    override fun protect(plain: ByteArray): ByteArray = throw unavailable()
    override fun unprotect(sealed: ByteArray): ByteArray = throw unavailable()
    private fun unavailable() = DesktopCredentialProtectionException(
        "$osName 上没有可用的系统凭据保护（桌面版只在 Windows 上用 DPAPI 保存登录凭据），不保存明文")
}

/** Text form of protected credentials: `<id>:v1:<base64>`. The legacy `dpapi:v1:` values written by earlier builds
 * are exactly this form. Values without a known tag are pre-encryption plaintext, read once and re-sealed on the
 * next successful write. */
internal object DesktopCredentialCipher {
    private const val VERSION = ":v1:"
    private val knownIds = setOf(WindowsDpapiCredentialProtection.id, TestOnlyCredentialProtection.id)

    @Volatile
    internal var protection: DesktopCredentialProtection = DesktopCredentialProtection.forCurrentPlatform()

    /** Which implementation protects credentials in this process (for diagnostics; contains no secret). */
    val protectionId: String get() = protection.id

    fun protect(value: String): String {
        if (value.isEmpty()) return value
        val current = protection
        val plain = value.toByteArray(StandardCharsets.UTF_8)
        try {
            return current.id + VERSION + Base64.getEncoder().encodeToString(current.protect(plain))
        } finally { plain.fill(0) }
    }

    fun unprotect(value: String): String {
        val id = value.substringBefore(VERSION, missingDelimiterValue = "")
        if (id !in knownIds) return value
        val current = protection
        if (current.id != id) throw DesktopCredentialProtectionException(
            "这份凭据由「$id」保护，当前进程使用「${current.id}」，无法解密")
        val sealed = try { Base64.getDecoder().decode(value.substring(id.length + VERSION.length)) }
            catch (failure: IllegalArgumentException) { throw DesktopCredentialProtectionException("凭据数据已损坏", failure) }
        val plain = current.unprotect(sealed)
        try { return String(plain, StandardCharsets.UTF_8) } finally { plain.fill(0) }
    }

    /** True when [value] is sealed by a known protection (used by tests and the restore record). */
    fun isProtected(value: String): Boolean = value.substringBefore(VERSION, missingDelimiterValue = "") in knownIds
}
