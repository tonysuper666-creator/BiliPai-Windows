package com.bilipai.desktop.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.IdentityHashMap

/** Reversible delayed UI callbacks over THIS composition's child scope. Callback
 * target ports must also admit their own navigation/native writes atomically.
 * There is no global scheduler, new native authority or lock around callbacks. */
internal class DesktopOriginalVideoEntryScheduler(
    private val scope: CoroutineScope,
    private val stillOwned: () -> Boolean,
) {
    private val requests = IdentityHashMap<Runnable, Job>()
    fun removeCallbacks(callback: Runnable) {
        synchronized(requests) { requests.remove(callback) }?.cancel()
    }
    fun postDelayed(callback: Runnable, delayMs: Long): Boolean {
        if (!scope.isActive || !stillOwned()) return false
        removeCallbacks(callback)
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val mine = kotlinx.coroutines.currentCoroutineContext()[Job]!!
            try {
                delay(delayMs.coerceAtLeast(0))
                if (isActive && stillOwned()) callback.run()
            } finally {
                synchronized(requests) { if (requests[callback] === mine) requests.remove(callback) }
            }
        }
        synchronized(requests) { requests[callback] = job }
        job.start()
        return true
    }
}
