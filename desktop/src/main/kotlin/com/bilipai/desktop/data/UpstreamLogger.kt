package com.android.purebilibili.core.util
/** Shared upstream policy bridge; the original capture/consent policy runs in the retained consumer.
 * Upstream Logger.kt is desktop-owned (Android logcat, crash files, Firebase): its logging surface lands in the
 * desktop diagnostics recorder through android.util.Log, with the same signatures as upstream. */
object Logger {
    fun d(tag:String,message:String) { android.util.Log.d(tag,message) }
    fun d(tag:String,message:() -> String) {
        if (!areVerboseRuntimeLogsEnabled()) return
        d(tag,message())
    }
    fun i(tag:String,message:String) { android.util.Log.i(tag,message) }
    fun i(tag:String,message:() -> String) {
        if (!areVerboseRuntimeLogsEnabled()) return
        i(tag,message())
    }
    fun w(tag:String,message:String,throwable:Throwable? = null) { android.util.Log.w(tag,message,throwable) }
    fun e(tag:String,message:String,throwable:Throwable? = null) { android.util.Log.e(tag,message,throwable) }
    fun areVerboseRuntimeLogsEnabled():Boolean =
        com.bilipai.desktop.diagnostics.DesktopDiagnosticsBridge.areVerboseRuntimeLogsEnabled()
}
