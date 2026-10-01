package com.bilipai.desktop.ui.assetsintegrationproof.gallery

import com.bilipai.desktop.ui.*
import com.bilipai.desktop.data.*
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
    val mainJar=Path.of(args[2]).toRealPath()
    for(clazz in listOf(DesktopDynamicEditorSelectedImages::class.java,DesktopDynamicGallerySelection::class.java,
        DesktopRepository::class.java,DesktopSessionStore::class.java,
        Class.forName("com.android.purebilibili.core.util.DesktopOriginalGalleryResultPolicyKt"))) {
        prove(Path.of(clazz.protectionDomain.codeSource.location.toURI()).toRealPath()==mainJar,"actual Main gallery/handles/owner/policy codeSource ${clazz.name}")
    }
    val sessions=DesktopSessionStore.temporary()
    val account=AccountSummary(321L,"Declared gallery fixture","")
    sessions.saveAccount(mapOf("SESSDATA" to "declared-gallery-task-01"),account)
    val repository=DesktopRepository(sessions)
    val operations=DesktopDynamicCardOperations(repository)
    val owned=operations::isOwned
    prove(repository.dynamicCacheSessionGuard===sessions,"same actual Main gallery epoch guard")
    DesktopDynamicEditorSelectedImages(owned).use { actual ->
        val selection = DesktopDynamicGallerySelection(actual, owned)
        val multiple = selection.acceptResult(true, a, listOf(a,b,a,c), 2)
        prove(multiple == listOf(a.toUri().toString(),b.toUri().toString()), "original data-first distinct then take before actual Root handle accept")
        prove(actual.read(multiple[0]).third.contentEquals(bytes), "actual new Main selected-image reader returns exact first file")
        prove(actual.read(multiple[1]).third.contentEquals(bytes), "actual new Main selected-image reader returns exact second file")
        prove(selection.acceptResult(true, null, listOf(b,c), 1) == listOf(b.toUri().toString()), "one remaining image uses original single first-clip fallback")
        prove(selection.acceptResult(true, c, listOf(b), 1) == listOf(c.toUri().toString()), "single original data-first result")
        prove(selection.acceptResult(false, a, listOf(b), 9).isEmpty(), "cancelled chooser admits no handles")
        prove(selection.acceptResult(true, null, emptyList(), 9).isEmpty(), "Windows empty list maps to absent clipData")
        prove(runCatching { selection.acceptResult(true, a, null, 0) }.isFailure, "existing dynamic editor capacity rejects zero")
        sessions.saveAccount(mapOf("SESSDATA" to "declared-gallery-task-02"),account) // Actual same MID/new existing Store epoch.
        prove(runCatching { selection.acceptResult(true, a, listOf(b), 2) }.exceptionOrNull() is CancellationException, "same MID owner rotation rejects delayed chooser result")
        prove(runCatching { actual.read(multiple[0]) }.exceptionOrNull() is CancellationException, "actual Main reader also rejects retired owner")
    }
    val currentOperations=DesktopDynamicCardOperations(repository)
    val guard=currentOperations::isOwned
    val actual = DesktopDynamicEditorSelectedImages(guard)
    actual.close()
    val closed = DesktopDynamicGallerySelection(actual,guard)
    prove(runCatching { closed.acceptResult(true, a, null, 1) }.exceptionOrNull() is CancellationException, "actual closed handle owner rejects actual Main adapter")
    prove(runCatching { actual.read(a.toUri().toString()) }.exceptionOrNull() is CancellationException, "actual closed reader drains handle state")
    Files.writeString(root.resolve("result.json"), """{"passed":true,"assertions":$assertions,"cases":4,"actualFrozenMainSelectedImageHandles":true,"originalSingleMultipleResultPolicy":true,"newPersistentListOrCache":false,"realJFileChooserOpened":false,"HTTP":false,"HWND":false,"MainCompiledProductIntegration":true,"MainShellUIExecuted":false,"productionOverrides":false}""")
    repository.httpClient.dispatcher.executorService.shutdown();repository.httpClient.connectionPool.evictAll()
    println("PASS $assertions assertions / 4 cases: original gallery decisions and actual new Main handle owner; no chooser/window")
}
