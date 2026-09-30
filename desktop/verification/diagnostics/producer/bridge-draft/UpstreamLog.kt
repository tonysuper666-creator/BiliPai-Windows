package android.util
/** Original decoder/network call shapes bind only the retained local diagnostic consumer. */
object Log {
    fun d(tag:String,message:String):Int = write("D",tag,message)
    fun i(tag:String,message:String):Int = write("I",tag,message)
    fun e(tag:String,message:String):Int = write("E",tag,message)
    fun e(tag:String,message:String,cause:Throwable):Int = write("E",tag,message,cause)
    fun v(tag:String,message:String):Int = write("V",tag,message)
    fun w(tag:String,message:String):Int = write("W",tag,message)
    private fun write(level:String,tag:String,message:String,cause:Throwable?=null):Int =
        com.bilipai.desktop.diagnostics.DesktopDiagnosticsBridge.record(level,tag,message,cause)
}
