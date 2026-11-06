package com.bilipai.desktop.platform.share

/** Copy a link exactly as supplied; failures are reported to the caller. */
fun interface DesktopShare {
    fun copyLink(link: String)
}
