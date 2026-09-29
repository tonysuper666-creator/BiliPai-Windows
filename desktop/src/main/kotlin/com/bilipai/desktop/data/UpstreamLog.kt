package android.util

/** Only the upstream video-model warning call is bridged; no Android runtime is included. */
object Log {
    fun w(tag: String, message: String): Int {
        System.err.println("$tag: $message")
        return 0
    }
}
