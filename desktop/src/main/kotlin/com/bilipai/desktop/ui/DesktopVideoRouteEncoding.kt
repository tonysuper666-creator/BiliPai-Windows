package com.bilipai.desktop.ui

/** UTF-8 route encoding with the original Uri.encode unreserved character set. */
internal fun encodeDesktopVideoRouteCover(value:String):String = buildString {
    val hex="0123456789ABCDEF"
    value.toByteArray(Charsets.UTF_8).forEach { raw ->
        val v=raw.toInt() and 255
        val c=v.toChar()
        if(c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "_-!.~'()*") append(c)
        else { append('%');append(hex[v ushr 4]);append(hex[v and 15]) }
    }
}
