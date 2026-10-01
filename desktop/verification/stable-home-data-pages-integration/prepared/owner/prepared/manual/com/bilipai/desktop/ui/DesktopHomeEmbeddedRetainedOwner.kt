package com.bilipai.desktop.ui

/** Root's mandatory aggregate of the FOUR complete original embedded-page consumers.
 * It is constructed by the retained entry factory, before conditional page drawing. Root
 * delegates Partition/Subscription/Live/Bangumi methods to their sole original UI/VM adapters.
 * No default, placeholder page, flat browser fallback or second repository is supplied here.
 * Child task scopes must descend from the supplied Home gate. close cancels only these page
 * bindings/VMs; it must not close Runtime's shared subscriptions or the shared HTTP client. */
internal interface DesktopHomeEmbeddedRetainedOwner : DesktopHomeEmbeddedPages, AutoCloseable {
    suspend fun closeAndJoin()
}
