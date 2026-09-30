package com.bilipai.desktop.plugins

import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

class DesktopTodayWatchRefillTest {
    @Test fun `rapid queue opens coalesce and never overlap builds`(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        val calls = AtomicInteger(); val active = AtomicInteger(); val maximum = AtomicInteger()
        val coordinator = DesktopTodayWatchRefillCoordinator(scope, { 7L }) {
            val count = active.incrementAndGet(); maximum.updateAndGet { maxOf(it, count) }
            try {
                if (calls.incrementAndGet() == 1) { entered.complete(Unit); release.await() }
                else second.complete(Unit)
            } finally { active.decrementAndGet() }
        }
        try {
            coordinator.request(7)
            withTimeout(3_000) { entered.await() }
            repeat(10) { coordinator.request(7) }
            release.complete(Unit)
            withTimeout(3_000) { second.await() }
            coordinator.shutdownForRestore()
            assertEquals(2, calls.get())
            assertEquals(1, maximum.get())
            assertEquals(0, active.get())
        } finally { scope.cancel() }
    }

    @Test fun `account replacement cancels old refill and restore rejects late requests`(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val epoch = AtomicLong(1)
        val firstEntered = CompletableDeferred<Unit>(); val oldCancelled = CompletableDeferred<Unit>(); val nextEntered = CompletableDeferred<Unit>()
        val calls = CopyOnWriteArrayList<Long>()
        val coordinator = DesktopTodayWatchRefillCoordinator(scope, { epoch.get() }) {
            val owner = epoch.get(); calls.add(owner)
            if (owner == 1L) try { firstEntered.complete(Unit); awaitCancellation() } finally { oldCancelled.complete(Unit) }
            else nextEntered.complete(Unit)
        }
        try {
            coordinator.request(1)
            withTimeout(3_000) { firstEntered.await() }
            epoch.set(2)
            coordinator.clear()
            coordinator.request(1)
            coordinator.request(2)
            withTimeout(3_000) { oldCancelled.await(); nextEntered.await() }
            coordinator.shutdownForRestore()
            coordinator.request(2)
            assertEquals(listOf(1L, 2L), calls.toList())
            assertFalse(scope.isActive)
        } finally { scope.cancel() }
    }
}
