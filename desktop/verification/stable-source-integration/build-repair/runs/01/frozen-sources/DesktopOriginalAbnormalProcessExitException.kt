// GENERATED from app/src/main/java/com/android/purebilibili/core/performance/Android17Diagnostics.kt; do not edit.
// LF-normalized SHA-256: 056db2c4d628e38dbfc3b7be5e6dd6b21bd9ff97597f5385f666579a0924785e
package com.android.purebilibili.core.performance
import java.io.PrintWriter
import java.io.PrintStream
class AbnormalProcessExitException(
    message: String,
    val nativeTrace: String? = null
) : RuntimeException(message) {
    init {
        // 清空由当前进程启动合成异常时产生的虚假 Java 堆栈，避免排查时误导用户
        stackTrace = emptyArray()
    }

    override fun printStackTrace(s: PrintWriter) {
        s.println(super.toString())
        if (!nativeTrace.isNullOrBlank()) {
            s.println()
            s.println("----- 系统异常回溯 (Tombstone / Trace) -----")
            s.println(nativeTrace)
        }
    }

    override fun printStackTrace(s: PrintStream) {
        s.println(super.toString())
        if (!nativeTrace.isNullOrBlank()) {
            s.println()
            s.println("----- 系统异常回溯 (Tombstone / Trace) -----")
            s.println(nativeTrace)
        }
    }
}
