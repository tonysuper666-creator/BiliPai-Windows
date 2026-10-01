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
