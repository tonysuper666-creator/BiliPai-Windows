package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.DynamicPublishDraft
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.data.DesktopSessionStore
import kotlinx.coroutines.*
import java.nio.file.Files
import kotlin.test.*

/** Headless current-owner/actual-Job checks. No updater, HTTP, account or window starts. */
class DesktopDynamicEditorUpdateHoldTest {
    private class Harness : AutoCloseable {
        private val directory = Files.createTempDirectory("dynamic-update-hold-")
        private val sessions = DesktopSessionStore(directory.resolve("session.json"), false)
        val repository = DesktopRepository(sessions)
        var accepting = true
        var sessionOwned = true
        val failures = mutableListOf<Throwable>()
        private val parent = SupervisorJob()
        val scope = CoroutineScope(parent + Dispatchers.Unconfined +
            CoroutineExceptionHandler { _, failure -> failures += failure })
        val session = DesktopDynamicCardSession(repository, stillOwned = { sessionOwned })
        val root = DesktopDynamicEditorRoot(repository, session, scope) { accepting }
        fun open(text: String = "private unsaved draft"): DesktopDynamicEditorRequest {
            root.actions.publish(DynamicPublishDraft(text))
            return assertNotNull(root.request)
        }
        fun pending(candidate: DesktopDynamicEditorRequest): Pair<Job, CompletableDeferred<Unit>> {
            val release = CompletableDeferred<Unit>()
            val job = scope.launch(start = CoroutineStart.LAZY) { release.await() }
            assertTrue(root.registerSubmission(candidate, job))
            assertTrue(job.start())
            return job to release
        }
        override fun close() {
            root.close()
            session.close()
            scope.cancel()
            // persistent=false; no default user directory or session file is used.
            Files.deleteIfExists(directory.resolve("session.json"))
            Files.deleteIfExists(directory)
        }
    }

    @Test fun currentOriginalEditorHoldsInstallationUntilExplicitDismiss() {
        Harness().use { h ->
            assertFalse(h.root.blocksUpdateInstallation())
            val request = h.open()
            assertTrue(h.root.blocksUpdateInstallation())
            h.root.dismiss(request)
            assertFalse(h.root.blocksUpdateInstallation())
        }
    }

    @Test fun actualSubmissionStillHoldsAfterDismissUntilItsJobCompletes(): Unit = runBlocking {
        Harness().use { h ->
            val request = h.open()
            val (job, release) = h.pending(request)
            h.root.dismiss(request)
            assertNull(h.root.request)
            assertTrue(job.isActive)
            assertTrue(h.root.blocksUpdateInstallation())
            release.complete(Unit)
            job.join()
            assertTrue(job.isCompleted)
            assertTrue(h.root.submissions.value.isEmpty())
            assertFalse(h.root.blocksUpdateInstallation())
        }
    }

    @Test fun failedActualSubmissionReleasesOnlyItsHoldButKeepsUnsavedEditor(): Unit = runBlocking {
        Harness().use { h ->
            val request = h.open()
            val release = CompletableDeferred<Unit>()
            val job = h.scope.launch(start = CoroutineStart.LAZY) {
                release.await()
                error("synthetic publish failure")
            }
            assertTrue(h.root.registerSubmission(request, job))
            job.start()
            release.complete(Unit)
            job.join()
            assertEquals("synthetic publish failure", h.failures.single().message)
            assertTrue(h.root.submissions.value.isEmpty())
            assertSame(request, h.root.request)
            assertTrue(h.root.blocksUpdateInstallation())
            h.root.dismiss(request)
            assertFalse(h.root.blocksUpdateInstallation())
        }
    }

    @Test fun cancellationBeforeActualBodyStartsCannotLeaveInstallationHeld(): Unit = runBlocking {
        Harness().use { h ->
            val request = h.open()
            var entered = false
            val job = h.scope.launch(start = CoroutineStart.LAZY) { entered = true }
            assertTrue(h.root.registerSubmission(request, job))
            h.root.dismiss(request)
            assertTrue(h.root.blocksUpdateInstallation())
            job.cancelAndJoin()
            assertFalse(entered)
            assertTrue(h.root.submissions.value.isEmpty())
            assertFalse(h.root.blocksUpdateInstallation())
        }
    }

    @Test fun cancelledActiveSubmissionReleasesHoldAfterItsRealJobFinally(): Unit = runBlocking {
        Harness().use { h ->
            val request = h.open()
            val (job, _) = h.pending(request)
            h.root.dismiss(request)
            assertTrue(h.root.blocksUpdateInstallation())
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertFalse(h.root.blocksUpdateInstallation())
        }
    }

    @Test fun oldJobCompletionCannotReleaseReopenedRequestOrNewSubmission(): Unit = runBlocking {
        Harness().use { h ->
            val first = h.open("first")
            val (oldJob, oldRelease) = h.pending(first)
            h.root.dismiss(first)
            val next = h.open("next")
            val (newJob, newRelease) = h.pending(next)
            oldRelease.complete(Unit)
            oldJob.join()
            assertSame(next, h.root.request)
            assertEquals(setOf(newJob), h.root.submissions.value.keys)
            h.root.dismiss(next)
            assertTrue(h.root.blocksUpdateInstallation())
            newRelease.complete(Unit)
            newJob.join()
            assertFalse(h.root.blocksUpdateInstallation())
        }
    }

    @Test fun updateActivationRejectsLateOpenAndNewSubmissionWithoutRetiringSession(): Unit = runBlocking {
        Harness().use { h ->
            h.accepting = false
            h.root.actions.publish(DynamicPublishDraft("late"))
            assertNull(h.root.request)
            assertTrue(h.session.isOwned())
            h.accepting = true
            val request = h.open()
            h.accepting = false
            val denied = h.scope.launch(start = CoroutineStart.LAZY) { fail("Denied API must not run") }
            assertFalse(h.root.registerSubmission(request, denied))
            denied.cancelAndJoin()
            assertSame(request, h.root.request)
            h.root.dismiss(request)
            h.root.actions.publish(DynamicPublishDraft("still late"))
            assertNull(h.root.request)
            assertTrue(h.session.isOwned())
            h.accepting = true
            assertEquals("restored", h.open("restored").draft.text)
        }
    }

    @Test fun retiredOwnerDoesNotHoldOrClearSuccessorOwner(): Unit = runBlocking {
        Harness().use { h ->
            val oldRequest = h.open()
            val (oldJob, oldRelease) = h.pending(oldRequest)
            h.sessionOwned = false
            assertFalse(h.root.blocksUpdateInstallation())
            h.root.close()
            val nextSession = DesktopDynamicCardSession(h.repository)
            val next = DesktopDynamicEditorRoot(h.repository, nextSession, h.scope)
            try {
                next.actions.publish(DynamicPublishDraft("successor"))
                val nextRequest = assertNotNull(next.request)
                oldRelease.complete(Unit)
                oldJob.join()
                assertSame(nextRequest, next.request)
                assertTrue(next.blocksUpdateInstallation())
                next.dismiss(nextRequest)
                assertFalse(next.blocksUpdateInstallation())
            } finally {
                next.close()
                nextSession.close()
            }
        }
    }
}
