package com.bilipai.desktop.data

import com.sun.jna.*
import com.sun.jna.win32.StdCallLibrary
import java.util.Base64

/** Windows DPAPI ties saved credentials to the current Windows user, matching the upstream protected store. */
internal object DesktopCredentialCipher {
    private const val PREFIX = "dpapi:v1:"
    private val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    interface Crypt32 : StdCallLibrary {
        fun CryptProtectData(input: Blob, description: WString?, entropy: Pointer?, reserved: Pointer?, prompt: Pointer?, flags: Int, output: Blob): Boolean
        fun CryptUnprotectData(input: Blob, description: Pointer?, entropy: Pointer?, reserved: Pointer?, prompt: Pointer?, flags: Int, output: Blob): Boolean
    }
    interface Kernel32 : StdCallLibrary { fun LocalFree(memory: Pointer): Pointer? }
    @Structure.FieldOrder("cbData", "pbData")
    class Blob : Structure() { @JvmField var cbData: Int = 0; @JvmField var pbData: Pointer? = null }
    private val crypt by lazy { Native.load("Crypt32", Crypt32::class.java) }
    private val kernel by lazy { Native.load("Kernel32", Kernel32::class.java) }

    fun protect(value: String): String {
        if (value.isEmpty() || !windows) return value
        return PREFIX + Base64.getEncoder().encodeToString(transform(value.toByteArray(Charsets.UTF_8), encrypt = true))
    }

    fun unprotect(value: String): String {
        if (!value.startsWith(PREFIX)) return value // Legacy plaintext is migrated on the next successful store write.
        check(windows) { "此账号凭证需要原 Windows 用户解密" }
        return String(transform(Base64.getDecoder().decode(value.removePrefix(PREFIX)), encrypt = false), Charsets.UTF_8)
    }

    private fun transform(bytes: ByteArray, encrypt: Boolean): ByteArray {
        val memory = Memory(bytes.size.toLong()).apply { write(0, bytes, 0, bytes.size) }
        val input = Blob().apply { cbData = bytes.size; pbData = memory; write() }
        val output = Blob()
        try {
            val success = if (encrypt) crypt.CryptProtectData(input, WString("BiliPai account"), null, null, null, 1, output)
                else crypt.CryptUnprotectData(input, null, null, null, null, 1, output)
            check(success) { "Windows 凭证保护失败 (${Native.getLastError()})" }
            output.read()
            return requireNotNull(output.pbData).getByteArray(0, output.cbData)
        } finally {
            output.pbData?.let { kernel.LocalFree(it) }
            memory.clear(); memory.close()
            bytes.fill(0)
        }
    }
}
