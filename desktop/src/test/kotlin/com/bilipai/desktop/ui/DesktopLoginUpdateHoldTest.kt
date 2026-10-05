package com.bilipai.desktop.ui

import kotlinx.coroutines.*
import kotlin.test.*

/** Actual LoginWork/Job consumption only. No repository, credentials, API, profile or window. */
class DesktopLoginUpdateHoldTest {
    private fun runLoginTest(block: suspend CoroutineScope.() -> Unit): Unit = runBlocking {
        withTimeout(5_000) { block(this) }
    }

    private class Harness : AutoCloseable {
        var mainOwned = true
        var startAllowed = true
        val parent = SupervisorJob()
        val scope = CoroutineScope(parent + Dispatchers.Unconfined)
        val hold = DesktopLoginUpdateHold({ mainOwned && parent.isActive }, { startAllowed })
        fun mounted(): DesktopLoginUpdateHold.Instance = hold.newInstance().also { assertTrue(it.mount()) }
        override fun close() {
            hold.close()
            scope.cancel()
        }
    }

    @Test fun onlyCommittedFormHoldsAndExplicitDisposalDoesNotLeaveHistoricalRouteHold() {
        Harness().use { h ->
            val instance = h.hold.newInstance()
            assertFalse(h.hold.blocksUpdateInstallation())
            assertTrue(instance.mount())
            assertTrue(h.hold.blocksUpdateInstallation())
            instance.close()
            assertFalse(h.hold.blocksUpdateInstallation())
            assertFalse(instance.mount())
            assertTrue(h.hold.activity.value.isEmpty())
        }
    }

    @Test fun actualLoginWorkRegistersSameJobBeforeOriginalBodyAndStart(): Unit = runLoginTest {
        Harness().use { h ->
            val instance = h.mounted()
            val work = LoginWork(h.scope, instance)
            val release = CompletableDeferred<Unit>()
            var entered = false
            val job = assertNotNull(work.run {
                val actual = assertNotNull(currentCoroutineContext()[Job])
                assertEquals(setOf(actual), assertNotNull(h.hold.activity.value[instance]).jobs)
                entered = true
                release.await()
            })
            try {
                assertTrue(entered)
                assertTrue(work.busy)
                assertTrue(h.parent.children.any { it === job })
                assertEquals(setOf(job), assertNotNull(h.hold.activity.value[instance]).jobs)
                assertNull(work.run { fail("Duplicate original work must not start") })
                release.complete(Unit)
                job.join()
                assertFalse(work.busy)
                assertNull(work.error)
                assertTrue(assertNotNull(h.hold.activity.value[instance]).jobs.isEmpty())
                assertTrue(h.hold.blocksUpdateInstallation())
                instance.close()
                assertFalse(h.hold.blocksUpdateInstallation())
            } finally { release.complete(Unit); job.cancelAndJoin() }
        }
    }

    @Test fun originalLoginWorkFailurePreservesErrorAndFormUntilDismiss(): Unit = runLoginTest {
        Harness().use { h ->
            val instance = h.mounted()
            val work = LoginWork(h.scope, instance)
            val job = assertNotNull(work.run { error("synthetic login failure") })
            job.join()
            assertTrue(job.isCompleted)
            assertFalse(work.busy)
            assertEquals("synthetic login failure", work.error)
            assertTrue(assertNotNull(h.hold.activity.value[instance]).jobs.isEmpty())
            assertTrue(h.hold.blocksUpdateInstallation())
            instance.close()
            assertFalse(h.hold.blocksUpdateInstallation())
        }
    }

    @Test fun formDisposalAndOriginalScopeCancelKeepHoldThroughActualFinallyDrain(): Unit = runLoginTest {
        Harness().use { h ->
            val instance = h.mounted()
            val work = LoginWork(h.scope, instance)
            val cleanupEntered = CompletableDeferred<Unit>()
            val cleanupRelease = CompletableDeferred<Unit>()
            val job = assertNotNull(work.run {
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { cleanupEntered.complete(Unit); cleanupRelease.await() } }
            })
            try {
                instance.close()
                h.scope.cancel()
                cleanupEntered.await()
                assertTrue(job.isCancelled)
                assertFalse(job.isCompleted)
                assertFalse(assertNotNull(h.hold.activity.value[instance]).mounted)
                assertTrue(h.hold.blocksUpdateInstallation())
                assertTrue(work.busy)
                cleanupRelease.complete(Unit)
                job.join()
                assertFalse(work.busy)
                assertNull(work.error)
                assertTrue(h.hold.activity.value.isEmpty())
                assertFalse(h.hold.blocksUpdateInstallation())
            } finally { cleanupRelease.complete(Unit); job.cancelAndJoin() }
        }
    }

    @Test fun cancelledBeforeBodyStartResetsOriginalBusyAndCannotStrandTicket(): Unit = runLoginTest {
        Harness().use { h ->
            val instance = h.mounted()
            val work = LoginWork(h.scope, instance)
            h.parent.cancel()
            val job = assertNotNull(work.run { fail("Cancelled original form scope must not start") })
            job.join()
            assertTrue(job.isCancelled)
            assertFalse(work.busy)
            assertTrue(assertNotNull(h.hold.activity.value[instance]).jobs.isEmpty())
            instance.close()
            assertFalse(h.hold.blocksUpdateInstallation())
        }
    }

    @Test fun oldJobCompletionCannotReleaseReopenedLoginInstanceOrItsActualWork(): Unit = runLoginTest {
        Harness().use { h ->
            val first = h.mounted()
            val oldRelease = CompletableDeferred<Unit>()
            val oldJob = assertNotNull(LoginWork(h.scope, first).run { oldRelease.await() })
            first.close()
            val next = h.mounted()
            val nextRelease = CompletableDeferred<Unit>()
            val nextJob = assertNotNull(LoginWork(h.scope, next).run { nextRelease.await() })
            try {
                oldRelease.complete(Unit)
                oldJob.join()
                assertFalse(first in h.hold.activity.value)
                assertEquals(setOf(nextJob), assertNotNull(h.hold.activity.value[next]).jobs)
                first.close()
                assertTrue(h.hold.blocksUpdateInstallation())
                next.close()
                assertTrue(h.hold.blocksUpdateInstallation())
                nextRelease.complete(Unit)
                nextJob.join()
                assertFalse(h.hold.blocksUpdateInstallation())
            } finally {
                oldRelease.complete(Unit); nextRelease.complete(Unit)
                oldJob.cancelAndJoin(); nextJob.cancelAndJoin()
            }
        }
    }

    @Test fun activationRejectsLateMountAndLateOriginalWorkWithoutDiscardingExistingForm(): Unit = runLoginTest {
        Harness().use { h ->
            val instance = h.mounted()
            val work = LoginWork(h.scope, instance)
            h.startAllowed = false
            assertFalse(h.hold.canBegin())
            assertFalse(h.hold.newInstance().mount())
            val denied = assertNotNull(work.run { fail("Install admission denies late login API") })
            denied.join()
            assertTrue(denied.isCancelled)
            assertFalse(work.busy)
            assertTrue(assertNotNull(h.hold.activity.value[instance]).mounted)
            assertTrue(h.hold.blocksUpdateInstallation())
            instance.close()
            assertFalse(h.hold.blocksUpdateInstallation())
            h.startAllowed = true
            val reopened = h.mounted()
            val accepted = assertNotNull(LoginWork(h.scope, reopened).run { })
            accepted.join()
            reopened.close()
            assertFalse(h.hold.blocksUpdateInstallation())
        }
    }

    @Test fun actualQrAndRegionLaunchPathTracksChildrenUntilParentJobReallyCompletes(): Unit = runLoginTest {
        Harness().use { h ->
            val instance = h.mounted()
            val childCleanup = CompletableDeferred<Unit>()
            val childRelease = CompletableDeferred<Unit>()
            val job = launchTrackedLogin(h.scope, instance) {
                launch {
                    try { awaitCancellation() }
                    finally { withContext(NonCancellable) { childCleanup.complete(Unit); childRelease.await() } }
                }
            }
            try {
                instance.close()
                job.cancel()
                childCleanup.await()
                assertTrue(job.isCancelled)
                assertFalse(job.isCompleted)
                assertEquals(setOf(job), assertNotNull(h.hold.activity.value[instance]).jobs)
                assertTrue(h.hold.blocksUpdateInstallation())
                childRelease.complete(Unit)
                job.join()
                assertFalse(h.hold.blocksUpdateInstallation())
            } finally { childRelease.complete(Unit); job.cancelAndJoin() }
        }
    }

    @Test fun closingMainLedgerRetiresFormsButNeverReleasesUndrainedActualJobs(): Unit = runLoginTest {
        Harness().use { h ->
            val instance = h.mounted()
            val cleanupEntered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val job = assertNotNull(LoginWork(h.scope, instance).run {
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { cleanupEntered.complete(Unit); release.await() } }
            })
            try {
                h.mainOwned = false
                h.hold.close()
                h.parent.cancel()
                cleanupEntered.await()
                assertFalse(h.hold.canBegin())
                assertFalse(h.hold.newInstance().mount())
                assertFalse(assertNotNull(h.hold.activity.value[instance]).mounted)
                assertTrue(h.hold.blocksUpdateInstallation())
                h.hold.close()
                release.complete(Unit)
                job.join()
                assertFalse(h.hold.blocksUpdateInstallation())
            } finally { release.complete(Unit); job.cancelAndJoin() }
        }
    }

    @Test fun disposedOldFormCannotRegisterNewWorkAgainstSuccessorInstance(): Unit = runLoginTest {
        Harness().use { h ->
            val old = h.mounted()
            val staleWork = LoginWork(h.scope, old)
            old.close()
            val next = h.mounted()
            val denied = assertNotNull(staleWork.run { fail("Disposed form callback must not launch API") })
            denied.join()
            assertTrue(denied.isCancelled)
            assertFalse(staleWork.busy)
            assertEquals(setOf(next), h.hold.activity.value.keys)
            next.close()
            assertFalse(h.hold.blocksUpdateInstallation())
        }
    }
}
