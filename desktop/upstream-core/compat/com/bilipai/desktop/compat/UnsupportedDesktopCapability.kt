package com.bilipai.desktop.compat

/**
 * Thrown by Android stand-ins for capabilities the desktop does not provide. It is an [Error], not an
 * Exception, on purpose: upstream code often wraps platform calls in `catch (e: Exception)` and turns
 * the failure into null or "" (SessionStorageCipher.encrypt returning null makes TokenManager drop the
 * persisted session). A missing desktop capability must stop the caller, not degrade it silently.
 */
class UnsupportedDesktopCapability(capability: String, desktopOwner: String) :
    Error("$capability is not available on the desktop; $desktopOwner")
