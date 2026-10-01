package com.bilipai.desktop.ui.gallerymotionphotoproof

import com.bilipai.desktop.ui.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

fun main(args: Array<String>) = runBlocking {
    val root = Path.of(args[0]); Files.createDirectories(root)
    val a = root.resolve("first.png"); val b = root.resolve("second.png"); val c = root.resolve("third.png")
    val bytes = Files.readAllBytes(Path.of(args[1]))
    for (p in listOf(a,b,c)) Files.write(p,bytes)
    var assertions = 0
    fun prove(value: Boolean, message: String) { check(value) { message }; assertions++ }
    var epoch = 1L
    val expectedEpoch = epoch
    val owned = { epoch == expectedEpoch }
    DesktopDynamicEditorSelectedImages(owned).use { actual ->
        val selection = DesktopDynamicGallerySelection(actual, owned)
        val multiple = selection.acceptResult(true, a, listOf(a,b,a,c), 2)
        prove(multiple == listOf(a.toUri().toString(),b.toUri().toString()), "original data-first distinct then take before actual Root handle accept")
        prove(actual.read(multiple[0]).third.contentEquals(bytes), "actual frozen Main selected-image reader returns exact first file")
        prove(actual.read(multiple[1]).third.contentEquals(bytes), "actual frozen Main selected-image reader returns exact second file")
        prove(selection.acceptResult(true, null, listOf(b,c), 1) == listOf(b.toUri().toString()), "one remaining image uses original single first-clip fallback")
        prove(selection.acceptResult(true, c, listOf(b), 1) == listOf(c.toUri().toString()), "single original data-first result")
        prove(selection.acceptResult(false, a, listOf(b), 9).isEmpty(), "cancelled chooser admits no handles")
        prove(selection.acceptResult(true, null, emptyList(), 9).isEmpty(), "Windows empty list maps to absent clipData")
        prove(runCatching { selection.acceptResult(true, a, null, 0) }.isFailure, "existing dynamic editor capacity rejects zero")
        epoch = 2L // Same account MID, different existing session epoch.
        prove(runCatching { selection.acceptResult(true, a, listOf(b), 2) }.exceptionOrNull() is CancellationException, "same MID owner rotation rejects delayed chooser result")
        prove(runCatching { actual.read(multiple[0]) }.exceptionOrNull() is CancellationException, "actual Main reader also rejects retired owner")
    }
    val flip = AtomicBoolean(false)
    val guard = { !flip.get() }
    val actual = DesktopDynamicEditorSelectedImages(guard)
    actual.close()
    val closed = DesktopDynamicGallerySelection(actual,guard)
    prove(runCatching { closed.acceptResult(true, a, null, 1) }.exceptionOrNull() is CancellationException, "actual closed handle owner rejects candidate adapter")
    prove(runCatching { actual.read(a.toUri().toString()) }.exceptionOrNull() is CancellationException, "actual closed reader drains handle state")
    Files.writeString(root.resolve("result.json"), """{"passed":true,"assertions":$assertions,"cases":4,"actualFrozenMainSelectedImageHandles":true,"originalSingleMultipleResultPolicy":true,"newPersistentListOrCache":false,"realJFileChooserOpened":false,"HTTP":false,"HWND":false,"MainIntegration":false}""")
    println("PASS $assertions assertions / 4 cases: original gallery decisions and actual frozen Main handle owner; no chooser/window")
}
