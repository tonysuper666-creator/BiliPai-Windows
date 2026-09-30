package com.android.purebilibili.core.util

/** Platform logging adapter used by unchanged upstream signing/stream-selection policies. */
object Logger {
    fun e(tag: String, message: String, cause: Throwable) = android.util.Log.e(tag, message, cause)
    // Debug messages in upstream network policies can contain signed query data; discard them.
    fun d(tag: String, message: String) = Unit
}
