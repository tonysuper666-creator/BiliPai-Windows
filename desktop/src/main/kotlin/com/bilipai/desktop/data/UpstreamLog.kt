package android.util

/** JVM logging bridge for unchanged upstream models and the protobuf decoder. */
object Log {
    private val logger = java.util.logging.Logger.getLogger("BiliPai.upstream")
    fun d(tag: String, message: String): Int = write(java.util.logging.Level.FINE, tag, message)
    fun i(tag: String, message: String): Int = write(java.util.logging.Level.INFO, tag, message)
    fun e(tag: String, message: String): Int = write(java.util.logging.Level.SEVERE, tag, message)
    fun e(tag: String, message: String, cause: Throwable): Int = write(java.util.logging.Level.SEVERE, tag, message, cause)
    fun v(tag: String, message: String): Int = write(java.util.logging.Level.FINER, tag, message)
    private fun write(level: java.util.logging.Level, tag: String, message: String, cause: Throwable? = null): Int {
        logger.log(level, "$tag: $message", cause)
        return 0
    }
    fun w(tag: String, message: String): Int = write(java.util.logging.Level.WARNING, tag, message)
}
