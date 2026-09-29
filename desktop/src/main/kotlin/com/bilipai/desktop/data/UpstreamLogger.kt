package com.android.purebilibili.core.util

/** Platform logging adapter used by unchanged upstream signing/stream-selection policies. */
object Logger {
    // Debug messages in upstream network policies can contain signed query data; discard them.
    fun d(tag: String, message: String) = Unit
}
