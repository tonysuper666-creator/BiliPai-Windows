package android.util

/** Desktop stand-in for android.util.Log; the desktop diagnostics recorder installs [sink].
 *  Until then warnings and errors go to stderr rather than nowhere. */
object Log {
    fun interface Sink { fun write(level: String, tag: String, message: String, cause: Throwable?): Int }
    @Volatile @JvmStatic var sink: Sink = Sink { level, tag, message, cause ->
        if (level == "W" || level == "E") System.err.println("$level/$tag: $message" + (cause?.let { "\n" + it.stackTraceToString() } ?: ""))
        0
    }
    @JvmStatic fun v(tag: String, msg: String): Int = sink.write("V", tag, msg, null)
    @JvmStatic fun d(tag: String, msg: String): Int = sink.write("D", tag, msg, null)
    @JvmStatic fun d(tag: String, msg: String, tr: Throwable?): Int = sink.write("D", tag, msg, tr)
    @JvmStatic fun i(tag: String, msg: String): Int = sink.write("I", tag, msg, null)
    @JvmStatic fun w(tag: String, msg: String): Int = sink.write("W", tag, msg, null)
    @JvmStatic fun w(tag: String, msg: String, tr: Throwable?): Int = sink.write("W", tag, msg, tr)
    @JvmStatic fun w(tag: String, tr: Throwable?): Int = sink.write("W", tag, "", tr)
    @JvmStatic fun e(tag: String, msg: String): Int = sink.write("E", tag, msg, null)
    @JvmStatic fun e(tag: String, msg: String, tr: Throwable?): Int = sink.write("E", tag, msg, tr)
    @JvmStatic fun getStackTraceString(tr: Throwable?): String = tr?.stackTraceToString() ?: ""
}
