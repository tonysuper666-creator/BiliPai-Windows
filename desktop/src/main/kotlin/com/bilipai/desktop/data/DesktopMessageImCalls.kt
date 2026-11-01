package com.bilipai.desktop.data

import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** The IM channel of the message page child whose launch runs this coroutine: its Root-gated gRPC call (that child's
 * cookies, token and call permit) and the message Root's read event. It owns no client, token or scope. */
internal class DesktopMessageImTransport(
    val request: suspend (String, ByteArray) -> ByteArray,
    val readStateChanged: suspend () -> Unit,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DesktopMessageImTransport>
}

/** Upstream MessageImRepository's transport on Windows. There is no process-wide gRPC client: a call outside a message
 * page child fails loudly instead of using another account's credentials. */
internal object DesktopMessageImCalls {
    suspend fun request(path: String, message: ByteArray): ByteArray = transport().request(path, message)
    suspend fun readStateChanged() = transport().readStateChanged()
    private suspend fun transport(): DesktopMessageImTransport = currentCoroutineContext()[DesktopMessageImTransport]
        ?: throw IllegalStateException("私信请求只能在消息页内发起")
}
