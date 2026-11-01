package com.bilipai.desktop.platform.files

import com.bilipai.desktop.settings.DesktopCommentFraudHistoryFiles
import com.bilipai.desktop.settings.DesktopSettingsShareService
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext
import java.awt.EventQueue
import java.awt.Toolkit
import java.awt.Window
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/** The production EDT coordinator and file IO run headlessly. Only the OS peer is
 * replaced: native window appearance/overwrite prompts still require GUI acceptance. */
@Timeout(10)
class AwtDesktopFileDialogsTest {
    @TempDir lateinit var directory: Path
    private val owner: () -> Window = { error("The headless peer must not construct a window") }
    private val open = DesktopFileDialogRequest("导入", DesktopFileDialogMode.OPEN, setOf("json"))
    private val save = DesktopFileDialogRequest("导出", DesktopFileDialogMode.SAVE, setOf("json"), "settings.json")

    private open class Peer(private val selection: NativeFileSelection) : NativeFileDialogPeer {
        val closed = CompletableDeferred<Unit>()
        override fun show(): NativeFileSelection {
            assertTrue(EventQueue.isDispatchThread())
            return selection
        }
        override fun close() {
            assertTrue(EventQueue.isDispatchThread())
            closed.complete(Unit)
        }
    }

    private class ModalPeer(selection: NativeFileSelection) : Peer(selection) {
        val shown = CompletableDeferred<Unit>()
        // FileDialog.show enters a nested event loop, so cancellation can dispose it
        // on the same EDT. Use that real event-loop behavior without opening a window.
        private val loop = Toolkit.getDefaultToolkit().systemEventQueue.createSecondaryLoop()
        override fun show(): NativeFileSelection {
            shown.complete(Unit)
            check(loop.enter())
            return super.show()
        }
        override fun close() {
            super.close()
            loop.exit()
        }
    }

    private fun dialogs(peer: NativeFileDialogPeer, checkpoint: () -> Unit = {}) =
        AwtDesktopFileDialogs(checkpoint, NativeFileDialogFactory { _, _ ->
            assertTrue(EventQueue.isDispatchThread())
            peer
        })

    private suspend inline fun <reified T : Throwable> failure(crossinline block: suspend () -> Unit): T {
        try { block() } catch (error: Throwable) {
            if (error is T) return error
            throw error
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }

    @Test fun selectedUnicodePathIsExactAndSettingsPayloadReallySavesAndImports(): Unit = runBlocking {
        val folder = Files.createDirectory(directory.resolve("设置 exports"))
        val peer = Peer(NativeFileSelection(folder.toString(), "自选 文件"))
        val requests = mutableListOf<DesktopFileDialogRequest>()
        val selector: DesktopFileDialogs = AwtDesktopFileDialogs({}, NativeFileDialogFactory { _, request ->
            assertTrue(EventQueue.isDispatchThread())
            requests += request
            peer
        })
        selector.use {
            val selected = it.chooseFile(owner, save)!!
            assertEquals(folder.resolve("自选 文件"), selected)
            assertFalse(Files.exists(selected), "Selection must not create/truncate the file")
            val context = DesktopOriginalPlayerSettingsContext(
                DesktopPluginContext(DesktopPluginStore(directory.resolve("store"))),
                { true }, { action -> action(); true },
                largeScreenOrFoldableConfiguration = { false }, isDebugBuild = { false },
            )
            val service = DesktopSettingsShareService(context)
            val artifact = service.buildExportArtifact("设置😀", "0.4.6")
            val payload = artifact.json
            val files = DesktopCommentFraudHistoryFiles({}, { action -> action() })
            files.writeUtf8(files.select(selected, true), payload)
            assertEquals(payload, Files.readString(selected))
            val imported = it.chooseFile(owner, open)!!
            assertEquals(payload, files.readUtf8(files.select(imported, false)))
            val session = service.readImportSession(imported)
            assertEquals(artifact.profile, session.profile)
            assertEquals(listOf(save, open), requests)
            assertEquals(listOf(selected), Files.list(folder).use { paths -> paths.toList() })
        }
    }

    @Test fun existingDestinationIsNotTruncatedBySelectionAndIsReplacedOnlyByExplicitWrite(): Unit = runBlocking {
        val target = directory.resolve("chosen.JSON")
        Files.writeString(target, "original")
        dialogs(Peer(NativeFileSelection(directory.toString(), target.fileName.toString()))).use {
            val selected = it.chooseFile(owner, save)!!
            assertEquals(target, selected)
            assertEquals("original", Files.readString(target))
            val files = DesktopCommentFraudHistoryFiles({}, { action -> action() })
            files.writeUtf8(files.select(selected, true), "replacement😀")
            assertEquals("replacement😀", Files.readString(target))
            assertEquals(listOf(target), Files.list(directory).use { paths -> paths.toList() })
        }
    }

    @Test fun userCancelIsNullAndDoesNotCreateAnyFile(): Unit = runBlocking {
        val peer = Peer(NativeFileSelection(directory.toString(), null))
        dialogs(peer).use {
            assertNull(it.chooseFile(owner, save))
            peer.closed.await()
            assertEquals(0L, Files.list(directory).use { paths -> paths.count() })
        }
    }

    @Test fun cancelledCallerDoesNotAllocateAPeer(): Unit = runBlocking {
        val calls = AtomicInteger()
        val selector = AwtDesktopFileDialogs({}, NativeFileDialogFactory { _, _ ->
            calls.incrementAndGet()
            error("A cancelled caller cannot allocate a peer")
        })
        selector.use {
            failure<CancellationException> {
                withContext(Job().apply { cancel() }) { it.chooseFile(owner, open) }
            }
            assertEquals(0, calls.get())
        }
    }

    @Test fun cancellingVisibleSelectorDisposesItAndReleasesItsSlot(): Unit = runBlocking {
        val peer = ModalPeer(NativeFileSelection(directory.toString(), "stale.json"))
        val calls = AtomicInteger()
        val selector = AwtDesktopFileDialogs({}, NativeFileDialogFactory { _, _ ->
            if (calls.incrementAndGet() == 1) peer else Peer(NativeFileSelection(null, null))
        })
        selector.use {
            val pending = async { it.chooseFile(owner, open) }
            peer.shown.await()
            pending.cancelAndJoin()
            peer.closed.await()
            // Drain the outer modal callback before testing the next selection.
            withContext(Dispatchers.IO) { EventQueue.invokeAndWait {} }
            assertTrue(pending.isCancelled)
            assertNull(it.chooseFile(owner, open))
        }
    }

    @Test fun retirementDisposesVisibleSelectorAndNeverDeliversItsStalePath(): Unit = runBlocking {
        val peer = ModalPeer(NativeFileSelection(directory.toString(), "stale.json"))
        val selector = dialogs(peer)
        val pending = async { selector.chooseFile(owner, save) }
        peer.shown.await()
        selector.close()
        failure<CancellationException> { pending.await() }
        peer.closed.await()
        failure<CancellationException> { selector.chooseFile(owner, save) }
        assertFalse(Files.exists(directory.resolve("stale.json")))
    }

    @Test fun ownerRetiredWhileModalWasVisibleRejectsItsSelection(): Unit = runBlocking {
        val alive = AtomicBoolean(true)
        val peer = ModalPeer(NativeFileSelection(directory.toString(), "stale.json"))
        dialogs(peer) { if (!alive.get()) throw CancellationException("Root retired") }.use {
            val pending = async { it.chooseFile(owner, open) }
            peer.shown.await()
            alive.set(false)
            EventQueue.invokeLater { peer.close() }
            assertEquals("Root retired", failure<CancellationException> { pending.await() }.message)
        }
    }

    @Test fun retirementDuringAllocationClosesThePeerWithoutShowingIt(): Unit = runBlocking {
        val shows = AtomicInteger()
        val peer = object : Peer(NativeFileSelection(directory.toString(), "stale.json")) {
            override fun show(): NativeFileSelection {
                shows.incrementAndGet()
                return super.show()
            }
        }
        lateinit var selector: AwtDesktopFileDialogs
        selector = AwtDesktopFileDialogs({}, NativeFileDialogFactory { _, _ -> selector.close(); peer })
        failure<CancellationException> { selector.chooseFile(owner, open) }
        peer.closed.await()
        assertEquals(0, shows.get())
    }

    @Test fun overlappingSelectionFailsLoudlyAndKeepsTheFirstPeer(): Unit = runBlocking {
        val peer = ModalPeer(NativeFileSelection(null, null))
        dialogs(peer).use {
            val pending = async { it.chooseFile(owner, open) }
            peer.shown.await()
            assertEquals("A file selector is already open",
                failure<IllegalStateException> { it.chooseFile(owner, save) }.message)
            assertFalse(peer.closed.isCompleted)
            EventQueue.invokeLater { peer.close() }
            assertNull(pending.await())
        }
    }

    @Test fun nativeFailureIsPropagatedAndTheNextRequestStillWorks(): Unit = runBlocking {
        val expected = IOException("native selector unavailable")
        val calls = AtomicInteger()
        AwtDesktopFileDialogs({}, NativeFileDialogFactory { _, _ ->
            if (calls.incrementAndGet() == 1) throw expected
            Peer(NativeFileSelection(null, null))
        }).use {
            assertNativeFailure(expected, failure<IOException> { it.chooseFile(owner, open) })
            assertNull(it.chooseFile(owner, open))
        }
        // Also exercise the real factory's owner-resolution failure, without a GUI.
        AwtDesktopFileDialogs().use {
            assertNativeFailure(expected, failure<IOException> { it.chooseFile({ throw expected }, open) })
            val nativeError = UnsatisfiedLinkError("native toolkit unavailable")
            assertNativeFailure(nativeError, failure<UnsatisfiedLinkError> { it.chooseFile({ throw nativeError }, open) })
        }
    }

    private fun assertNativeFailure(expected: Throwable, actual: Throwable) {
        assertEquals(expected.javaClass, actual.javaClass)
        assertEquals(expected.message, actual.message)
        // Coroutine stacktrace recovery may copy the exception, retaining the original as its cause.
        assertSame(expected, if (actual === expected) actual else actual.cause)
    }

    @Test fun invalidNativePathsAndSuggestedTraversalFailRatherThanBecomingCancellation(): Unit = runBlocking {
        for (selection in listOf(NativeFileSelection(null, "file.json"),
            NativeFileSelection("relative", "file.json"))) {
            dialogs(Peer(selection)).use { failure<IllegalStateException> { it.chooseFile(owner, open) } }
        }
        for (leaf in listOf("../file.json", "..\\file.json")) {
            dialogs(Peer(NativeFileSelection(directory.toString(), leaf))).use {
                failure<IllegalArgumentException> { it.chooseFile(owner, open) }
            }
        }
        for (name in listOf("../settings.json", "..\\settings.json", "/settings.json", "C:\\settings.json", "..")) {
            assertThrows(IllegalArgumentException::class.java) { save.copy(suggestedFileName = name) }
        }
        assertThrows(IllegalArgumentException::class.java) { save.copy(extensions = setOf(".json")) }
    }

    @Test fun saveErrorsFromTheRealFileAdapterRemainVisibleAndPreserveExistingData(): Unit = runBlocking {
        val target = directory.resolve("chosen.json")
        Files.writeString(target, "keep")
        dialogs(Peer(NativeFileSelection(directory.toString(), target.fileName.toString()))).use {
            val files = DesktopCommentFraudHistoryFiles({}, { action -> action() })
            val selected = files.select(it.chooseFile(owner, save)!!, true)
            Files.writeString(target, "external change")
            assertEquals("Selected export destination changed",
                failure<IllegalArgumentException> { files.writeUtf8(selected, "do not publish") }.message)
            assertEquals("external change", Files.readString(target))
            assertEquals(listOf(target), Files.list(directory).use { paths -> paths.toList() })
        }
    }
}
