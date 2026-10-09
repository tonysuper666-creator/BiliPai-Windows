package com.bilipai.desktop.ui

import kotlinx.coroutines.*
import kotlin.test.*

/** Memory-only real draft/hold/original Job lifecycle. No plugin IO, window or updater execution. */
class DesktopPluginJsonEditorUpdateHoldTest {
    private fun runHoldTest(block: suspend CoroutineScope.() -> Unit): Unit = runBlocking {
        withTimeout(5_000) { block(this) }
    }
    private class Harness : AutoCloseable {
        var mainOwned = true
        var startAllowed = true
        val parent = SupervisorJob()
        val scope = CoroutineScope(parent + Dispatchers.Unconfined)
        val hold = DesktopPluginJsonEditorUpdateHold({ mainOwned && parent.isActive }, { startAllowed })
        fun draft(initial: String = "original"): DesktopPluginJsonDraft =
            assertNotNull(openDesktopPluginJsonDraft(hold) { initial })
        override fun close() { hold.close(); scope.cancel() }
    }

    @Test fun draftAdmissionHoldsBeforeInitialContentAndPublication() {
        Harness().use { h ->
            assertFalse(h.hold.blocksUpdateInstallation())
            val draft = assertNotNull(openDesktopPluginJsonDraft(h.hold) {
                // The real open callback can publish state only after this already blocks.
                assertTrue(h.hold.blocksUpdateInstallation())
                "original"
            })
            draft.text = "unsaved revision"
            assertEquals("unsaved revision", draft.text)
            assertEquals(setOf(draft.instance), h.hold.activity.value.keys)
            assertTrue(assertNotNull(h.hold.activity.value[draft.instance]).mounted)
            draft.close()
            assertFalse(h.hold.blocksUpdateInstallation())
        }
    }

    @Test fun failedInitialContentDoesNotStrandAnUnpublishedForm() {
        Harness().use { h ->
            assertFailsWith<IllegalArgumentException> {
                openDesktopPluginJsonDraft(h.hold) { throw IllegalArgumentException("synthetic export failure") }
            }
            assertTrue(h.hold.activity.value.isEmpty())
            h.draft().close()
            assertFalse(h.hold.blocksUpdateInstallation())
        }
    }

    @Test fun sameUiActivationAdmissionRejectsNewDraftAndOriginalImport(): Unit = runHoldTest {
        Harness().use { h ->
            val draft = h.draft()
            h.startAllowed = false // No composition/effect refresh is required by the actual callback.
            assertNull(openDesktopPluginJsonDraft(h.hold) { fail("Rejected open cannot read or publish draft") })
            var rejected = false
            val job = launchTrackedPluginJson(h.scope, draft.instance, onRejected = { rejected = true }) {
                fail("Activating updater must deny original import body")
            }
            job.join()
            assertTrue(rejected)
            assertTrue(job.isCancelled)
            assertTrue(h.hold.blocksUpdateInstallation())
            assertTrue(assertNotNull(h.hold.activity.value[draft.instance]).jobs.isEmpty())
            draft.close()
            h.startAllowed = true
            h.mainOwned = false
            assertNull(h.hold.begin())
            assertFalse(h.hold.blocksUpdateInstallation())
        }
    }

    @Test fun sameOriginalReturnedJobIsRegisteredBeforeSaveBodyStarts(): Unit = runHoldTest {
        Harness().use { h ->
            val draft = h.draft()
            val release = CompletableDeferred<Unit>()
            var entered = false
            val job = launchTrackedPluginJson(h.scope, draft.instance) {
                val actual = assertNotNull(currentCoroutineContext()[Job])
                assertEquals(setOf(actual), assertNotNull(h.hold.activity.value[draft.instance]).jobs)
                entered = true
                release.await()
            }
            try {
                assertTrue(entered)
                assertTrue(h.parent.children.any { it === job })
                assertEquals(setOf(job), assertNotNull(h.hold.activity.value[draft.instance]).jobs)
                draft.close()
                assertTrue(h.hold.blocksUpdateInstallation())
                release.complete(Unit)
                job.join()
                assertFalse(h.hold.blocksUpdateInstallation())
            } finally { release.complete(Unit); job.cancelAndJoin() }
        }
    }

    @Test fun failedSaveRetainsExactUnsavedDraftAndAllowsRetry(): Unit = runHoldTest {
        Harness().use { h ->
            val draft = h.draft()
            draft.text = "unsaved revision"
            var failure: String? = null
            val first = launchTrackedPluginJson(h.scope, draft.instance) {
                try { throw IllegalArgumentException("synthetic JSON validation failure") }
                catch (error: IllegalArgumentException) { failure = error.message }
            }
            first.join()
            assertEquals("synthetic JSON validation failure", failure)
            assertEquals("unsaved revision", draft.text)
            assertTrue(assertNotNull(h.hold.activity.value[draft.instance]).mounted)
            assertTrue(assertNotNull(h.hold.activity.value[draft.instance]).jobs.isEmpty())
            assertTrue(h.hold.blocksUpdateInstallation())
            var imported: String? = null
            val retry = launchTrackedPluginJson(h.scope, draft.instance) { imported = draft.text; draft.close() }
            retry.join()
            assertEquals("unsaved revision", imported)
            assertFalse(h.hold.blocksUpdateInstallation())
        }
    }

    @Test fun unmountAndOriginalScopeCancellationRetainRealFinallyDrain(): Unit = runHoldTest {
        Harness().use { h ->
            val draft = h.draft()
            val cleanupEntered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val job = launchTrackedPluginJson(h.scope, draft.instance) {
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { cleanupEntered.complete(Unit); release.await() } }
            }
            try {
                draft.close()
                h.scope.cancel()
                cleanupEntered.await()
                assertTrue(job.isCancelled)
                assertFalse(job.isCompleted)
                assertFalse(assertNotNull(h.hold.activity.value[draft.instance]).mounted)
                assertTrue(h.hold.blocksUpdateInstallation())
                release.complete(Unit)
                job.join()
                assertFalse(h.hold.blocksUpdateInstallation())
            } finally { release.complete(Unit); job.cancelAndJoin() }
        }
    }

    @Test fun cancelledParentKeepsActualChildUntilChildCleanupCompletes(): Unit = runHoldTest {
        Harness().use { h ->
            val draft = h.draft()
            val childCleanup = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val job = launchTrackedPluginJson(h.scope, draft.instance) {
                launch {
                    try { awaitCancellation() }
                    finally { withContext(NonCancellable) { childCleanup.complete(Unit); release.await() } }
                }
            }
            try {
                draft.close()
                job.cancel()
                childCleanup.await()
                assertFalse(job.isCompleted)
                assertEquals(setOf(job), assertNotNull(h.hold.activity.value[draft.instance]).jobs)
                assertTrue(h.hold.blocksUpdateInstallation())
                release.complete(Unit)
                job.join()
                assertFalse(h.hold.blocksUpdateInstallation())
            } finally { release.complete(Unit); job.cancelAndJoin() }
        }
    }

    @Test fun directUrlImportEphemeralFormRetiresButSameJobStillBlocks(): Unit = runHoldTest {
        Harness().use { h ->
            val instance = assertNotNull(h.hold.begin())
            val release = CompletableDeferred<Unit>()
            val job = launchTrackedPluginJson(h.scope, instance) { release.await() }
            try {
                instance.close() // Exact UI finally after json-import returned the original Job.
                assertFalse(assertNotNull(h.hold.activity.value[instance]).mounted)
                assertEquals(setOf(job), assertNotNull(h.hold.activity.value[instance]).jobs)
                assertTrue(h.parent.children.any { it === job })
                assertTrue(h.hold.blocksUpdateInstallation())
                release.complete(Unit)
                job.join()
                assertTrue(h.hold.activity.value.isEmpty())
            } finally { release.complete(Unit); job.cancelAndJoin() }
        }
    }

    @Test fun oldCompletionAndOldCloseCannotRemoveSuccessorDraftOrItsJob(): Unit = runHoldTest {
        Harness().use { h ->
            val old = h.draft("old")
            val oldRelease = CompletableDeferred<Unit>()
            val oldJob = launchTrackedPluginJson(h.scope, old.instance) { oldRelease.await() }
            old.close()
            val next = h.draft("next")
            val nextRelease = CompletableDeferred<Unit>()
            val nextJob = launchTrackedPluginJson(h.scope, next.instance) { nextRelease.await() }
            try {
                oldRelease.complete(Unit); oldJob.join(); old.close()
                assertEquals(setOf(next.instance), h.hold.activity.value.keys)
                assertEquals(setOf(nextJob), assertNotNull(h.hold.activity.value[next.instance]).jobs)
                assertEquals("next", next.text)
                next.close()
                assertTrue(h.hold.blocksUpdateInstallation())
                nextRelease.complete(Unit); nextJob.join()
                assertFalse(h.hold.blocksUpdateInstallation())
            } finally {
                oldRelease.complete(Unit); nextRelease.complete(Unit)
                oldJob.cancelAndJoin(); nextJob.cancelAndJoin()
            }
        }
    }

    @Test fun mainLedgerCloseRetainsUndrainedWorkAndRefusesFurtherStarts(): Unit = runHoldTest {
        Harness().use { h ->
            val draft = h.draft()
            val cleanupEntered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val job = launchTrackedPluginJson(h.scope, draft.instance) {
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { cleanupEntered.complete(Unit); release.await() } }
            }
            try {
                h.mainOwned = false; h.hold.close(); h.scope.cancel()
                cleanupEntered.await()
                assertNull(h.hold.begin())
                assertTrue(h.hold.blocksUpdateInstallation())
                assertFalse(assertNotNull(h.hold.activity.value[draft.instance]).mounted)
                h.hold.close()
                release.complete(Unit); job.join()
                assertFalse(h.hold.blocksUpdateInstallation())
            } finally { release.complete(Unit); job.cancelAndJoin() }
        }
    }

    @Test fun disposedDraftCannotStartSaveAgainstAReopenedInstance(): Unit = runHoldTest {
        Harness().use { h ->
            val old = h.draft(); old.close()
            val next = h.draft()
            val denied = launchTrackedPluginJson(h.scope, old.instance) { fail("Retired draft cannot save") }
            denied.join()
            assertTrue(denied.isCancelled)
            assertEquals(setOf(next.instance), h.hold.activity.value.keys)
            next.close()
            assertFalse(h.hold.blocksUpdateInstallation())
        }
    }
}
