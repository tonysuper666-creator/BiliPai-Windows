package android.security.keystore

import com.bilipai.desktop.compat.UnsupportedDesktopCapability
import java.security.spec.AlgorithmParameterSpec

/** Compile surface only: the desktop has no Android Keystore (see DesktopAndroidKeyStoreGuard). */
class KeyGenParameterSpec private constructor() : AlgorithmParameterSpec {
    class Builder(@Suppress("UNUSED_PARAMETER") alias: String, @Suppress("UNUSED_PARAMETER") purposes: Int) {
        fun setBlockModes(vararg modes: String) = this
        fun setEncryptionPaddings(vararg paddings: String) = this
        fun setKeySize(size: Int) = this
        fun setRandomizedEncryptionRequired(required: Boolean) = this
        fun setUserAuthenticationRequired(required: Boolean) = this
        fun build(): KeyGenParameterSpec = throw DesktopAndroidKeyStoreGuard.unsupported()
    }
}

object KeyProperties {
    const val PURPOSE_ENCRYPT = 1; const val PURPOSE_DECRYPT = 2
    const val BLOCK_MODE_GCM = "GCM"; const val ENCRYPTION_PADDING_NONE = "NoPadding"; const val KEY_ALGORITHM_AES = "AES"
}

/**
 * Upstream SessionStorageCipher asks the JDK for an "AndroidKeyStore" provider. A plain JVM answers
 * with KeyStoreException, which upstream catches and turns into "skip persistence". Installing this
 * provider makes every Android Keystore request fail with [UnsupportedDesktopCapability] instead.
 * Desktop credentials are persisted by DesktopSessionStore (Windows DPAPI), never through upstream
 * TokenManager.
 */
object DesktopAndroidKeyStoreGuard {
    private const val NAME = "AndroidKeyStore"

    fun unsupported() = UnsupportedDesktopCapability(
        "Android Keystore", "desktop credentials are stored by DesktopSessionStore (DPAPI), not upstream TokenManager")

    @Synchronized
    fun install() {
        if (java.security.Security.getProvider(NAME) != null) return
        // Lowest priority, one service: nothing else on the JVM offers a KeyStore of this type,
        // so ordinary crypto (TLS, AES, RSA) never reaches this provider.
        java.security.Security.addProvider(Provider())
    }

    private class Provider : java.security.Provider(NAME, "1.0", "Desktop: Android Keystore is unavailable") {
        init {
            putService(object : Service(this, "KeyStore", NAME, Provider::class.java.name, null, null) {
                override fun newInstance(constructorParameter: Any?): Any = throw unsupported()
            })
        }
    }
}
