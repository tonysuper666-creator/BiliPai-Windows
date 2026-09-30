package com.android.purebilibili.core.util
/** Shared upstream policy bridge; the original capture/consent policy runs in the retained consumer. */
object Logger {
    fun e(tag:String,message:String,cause:Throwable) = android.util.Log.e(tag,message,cause)
    fun d(tag:String,message:String) = android.util.Log.d(tag,message)
}
