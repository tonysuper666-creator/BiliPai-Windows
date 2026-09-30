package com.bilipai.desktop.network

import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/** Original connectFailed hook, with URI/address/error-message data deliberately discarded. */
internal val desktopProxyConnectionFailureCount=AtomicLong()
internal fun recordDesktopProxyConnectionFailure(error:IOException?) {
    if(error!=null)desktopProxyConnectionFailureCount.incrementAndGet()
}
