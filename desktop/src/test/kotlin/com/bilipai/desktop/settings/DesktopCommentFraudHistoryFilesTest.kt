package com.bilipai.desktop.settings

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DesktopCommentFraudHistoryFilesTest {
    @TempDir lateinit var directory: Path
    private suspend fun cancelled(block: suspend () -> Unit) {
        try { block();throw AssertionError("Expected cancellation") } catch (_: CancellationException) { }
    }
    private suspend fun rejected(block: suspend () -> Unit) {
        try { block();throw AssertionError("Expected rejected file capability") }
        catch (_: IllegalArgumentException) { }
        catch (_: IllegalStateException) { }
    }
    @Test fun utf8PayloadRoundTripsAndNewTargetUsesOneShotCapability(): Unit = runBlocking {
        val files=DesktopCommentFraudHistoryFiles({},{it()})
        val target=directory.resolve("biliSendCheck_backup_1.json")
        val payload="[{\"message\":\"原文[doge]😀\\n第二行\"}]"
        val export=files.select(target,true)
        files.writeUtf8(export,payload)
        assertEquals(payload,Files.readString(target))
        val imported=files.select(target,false)
        assertEquals(payload,files.readUtf8(imported))
        rejected { files.writeUtf8(export,"[]") }
        rejected { files.readUtf8(imported) }
        assertEquals(payload,Files.readString(target))
        assertEquals(listOf(target),Files.list(directory).use { it.toList() })
    }
    @Test fun changedSelectedDestinationIsNeverOverwritten(): Unit = runBlocking {
        val files=DesktopCommentFraudHistoryFiles({},{it()})
        val target=directory.resolve("selected.json");Files.writeString(target,"before")
        val export=files.select(target,true)
        Files.writeString(target,"changed externally")
        rejected { files.writeUtf8(export,"[]") }
        assertEquals("changed externally",Files.readString(target))
        assertEquals(listOf(target),Files.list(directory).use { it.toList() })
    }
    @Test fun selectedImportRejectsExternalReplacementAndForeignOwner(): Unit = runBlocking {
        val files=DesktopCommentFraudHistoryFiles({},{it()})
        val foreign=DesktopCommentFraudHistoryFiles({},{it()})
        val target=directory.resolve("selected.json");Files.writeString(target,"before")
        val selected=files.select(target,false)
        rejected { foreign.readUtf8(selected) }
        Files.writeString(target,"changed externally")
        rejected { files.readUtf8(selected) }
        assertEquals("changed externally",Files.readString(target))
    }
    @Test fun retirementAtPublicationKeepsChosenExistingFileAndRemovesOnlyOwnedTemporary(): Unit = runBlocking {
        val alive=AtomicBoolean(true)
        val target=directory.resolve("selected.json");Files.writeString(target,"keep")
        val files=DesktopCommentFraudHistoryFiles(
            { if(!alive.get())throw CancellationException("synthetic retirement") },
            { alive.set(false);throw CancellationException("synthetic rejected final admission") },
        )
        val selected=files.select(target,true)
        cancelled { files.writeUtf8(selected,"[]") }
        assertEquals("keep",Files.readString(target))
        assertEquals(listOf(target),Files.list(directory).use { it.toList() })
    }
    @Test fun targetAppearingDuringPublicationIsRetainedAndNewExportIsRejected(): Unit = runBlocking {
        val target=directory.resolve("selected.json")
        val files=DesktopCommentFraudHistoryFiles({}, { action->Files.writeString(target,"external owner");action() })
        val selected=files.select(target,true)
        rejected { files.writeUtf8(selected,"[]") }
        assertEquals("external owner",Files.readString(target))
        assertEquals(listOf(target),Files.list(directory).use { it.toList() })
    }
    @Test fun cancelledCallerCreatesNoDestinationOrTemporaryFile(): Unit = runBlocking {
        val files=DesktopCommentFraudHistoryFiles({},{it()})
        val target=directory.resolve("selected.json")
        val selected=files.select(target,true)
        val cancelledJob=Job().apply { cancel() }
        cancelled { withContext(cancelledJob) { files.writeUtf8(selected,"[]") } }
        assertFalse(Files.exists(target))
        assertEquals(0L,Files.list(directory).use { it.count() })
    }
}
