package android.os

/** android.os.Trace when no system trace is being recorded, which is always the case on the desktop: every call is a
 * cheap no-op and isEnabled() is false, exactly as on an Android device without an active trace session. */
object Trace {
    @JvmStatic fun isEnabled(): Boolean = false
    @JvmStatic fun beginSection(sectionName: String) = Unit
    @JvmStatic fun endSection() = Unit
    @JvmStatic fun beginAsyncSection(methodName: String, cookie: Int) = Unit
    @JvmStatic fun endAsyncSection(methodName: String, cookie: Int) = Unit
    @JvmStatic fun setCounter(counterName: String, counterValue: Long) = Unit
}
