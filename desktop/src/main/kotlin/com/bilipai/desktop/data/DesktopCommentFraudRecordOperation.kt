package com.bilipai.desktop.data

import kotlinx.coroutines.CancellationException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** The page supplies this capability in its coroutine context. Root-originated record
 * capture continues to use the same DAO's credential owner without creating a page. */
internal class DesktopCommentFraudRecordOperation(
    private val owns: () -> Boolean,
    private val admit: ((() -> Unit) -> Boolean),
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DesktopCommentFraudRecordOperation>
    fun checkpoint() {
        if (!owns()) throw CancellationException("Comment fraud record page retired")
    }
    fun <T> withAdmission(block: () -> T): T {
        checkpoint()
        var value: Any? = null
        if (!admit { checkpoint(); value = block(); checkpoint() })
            throw CancellationException("Comment fraud record page admission rejected")
        @Suppress("UNCHECKED_CAST") return value as T
    }
}
