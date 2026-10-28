package android.util

object Base64 {
    const val DEFAULT = 0; const val NO_PADDING = 1; const val NO_WRAP = 2; const val URL_SAFE = 8
    private fun enc(flags: Int) = (if (flags and URL_SAFE != 0) java.util.Base64.getUrlEncoder() else java.util.Base64.getEncoder())
        .let { if (flags and NO_PADDING != 0) it.withoutPadding() else it }
    @JvmStatic fun encodeToString(input: ByteArray, flags: Int): String = enc(flags).encodeToString(input)
    @JvmStatic fun encode(input: ByteArray, flags: Int): ByteArray = enc(flags).encode(input)
    @JvmStatic fun decode(str: String, flags: Int): ByteArray =
        (if (flags and URL_SAFE != 0) java.util.Base64.getUrlDecoder() else java.util.Base64.getMimeDecoder()).decode(str.trim())
}
