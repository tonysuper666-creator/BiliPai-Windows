package com.bilipai.desktop.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Windows lifetime boundary for the original Search host's superseded-article Job.
 * The retained Search VM and the existing owner-tagged HEAD resolver remain authoritative.
 * Cancelling a selection cancels its actual Call without retiring the retained Search. */
internal class DesktopSearchArticleOpenRequest(
    private val scope: CoroutineScope,
    private val resolver: DesktopPersonalArticleResolver,
) : AutoCloseable {
    private val lock = Any()
    private var request: Job? = null
    private var closed = false

    fun open(articleId: Long, title: String, resolved: (ArticleNavigationTarget, String) -> Unit): Job? {
        val next = scope.launch(start = CoroutineStart.LAZY) {
            val target = resolver.resolve(articleId)
            val context = currentCoroutineContext()
            context.ensureActive()
            val selected = synchronized(lock) { !closed && request === context[Job] }
            if (target != null && selected) resolved(target, title)
        }
        var previous: Job? = null
        val accepted = synchronized(lock) {
            if (closed) false else {
                previous = request
                request = next
                true
            }
        }
        if (!accepted) {
            next.cancel()
            return null
        }
        next.invokeOnCompletion {
            synchronized(lock) { if (request === next) request = null }
        }
        // Cancellation can synchronously call the transport; do not hold the state lock.
        previous?.cancel()
        next.start()
        return next
    }

    fun cancel() {
        val previous = synchronized(lock) { request.also { request = null } }
        previous?.cancel()
    }

    override fun close() {
        val previous = synchronized(lock) {
            closed = true
            request.also { request = null }
        }
        previous?.cancel()
    }
}
