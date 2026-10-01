package com.bilipai.desktop.ui

import com.bilipai.desktop.plugins.DesktopTodayWatchRepository
import com.bilipai.desktop.plugins.DesktopTodayWatchState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.file.Path
import java.util.jar.JarFile

private class ScriptedOwner(override val capturedEpoch: Long, private val current: () -> Boolean,
    val drain: CompletableDeferred<Unit> = CompletableDeferred(Unit)) : DesktopOriginalTodayWatchOwner {
    override val todayWatchState = MutableStateFlow(DesktopTodayWatchState())
    var closed = false
    var reloads = 0
    var consumed = mutableListOf<String>()
    val draining = CompletableDeferred<Unit>()
    override fun isCurrentOwner() = !closed && current()
    override suspend fun reloadTodayWatch(forceHistory: Boolean) { check(isCurrentOwner());reloads++ }
    override suspend fun consumeTodayWatchBvid(bvid: String): Boolean { check(isCurrentOwner());consumed += bvid;return true }
    override suspend fun closeAndJoin() { closed = true;draining.complete(Unit);drain.await() }
}

fun main(args: Array<String>) = runBlocking {
    val product = DesktopHomeRetainedEntry::class.java.protectionDomain.codeSource.location
    val candidate = DesktopTodayWatchRepository::class.java.protectionDomain.codeSource.location
    check(product != candidate)
    println("ORIGIN originalEntry=$product")
    println("ORIGIN prospectiveBridge=$candidate")
    // Actual class loading validates factory/media/entry ABI without allocating a native Window.
    var loaded = 0
    JarFile(args[0]).use { jar -> jar.entries().asSequence().filter { it.name.endsWith(".class") }.forEach {
        Class.forName(it.name.removeSuffix(".class").replace('/', '.'), false, DesktopTodayWatchRepository::class.java.classLoader)
        loaded++
    } }
    println("PASS candidate class loading: $loaded classes")
    var epoch = 1L
    val parent = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
    val bridge = DesktopTodayWatchRepository({ epoch }, parent)
    val startup = async(start = CoroutineStart.UNDISPATCHED) { bridge.reload(true) }
    check(!startup.isCompleted)
    var constructed = 0
    val first = ScriptedOwner(1, { epoch == 1L })
    check(bridge.installOwner(1) { constructed++;first } === first)
    startup.await()
    check(first.reloads == 1)
    check(bridge.installOwner(1) { error("same epoch must reuse retained VM") } === first)
    check(bridge.consume("BVactual") && first.consumed == listOf("BVactual"))
    // Route coverage performs no lifecycle operation, so same owner/source persists.
    check(constructed == 1 && !first.closed)
    println("PASS startup defers until original owner; same epoch and covered route retain one owner")
    val drain = CompletableDeferred<Unit>()
    // Change only the epoch, retaining the logical MID. Old close must finish BEFORE new construction.
    epoch = 2
    bridge.accountChanged(2)
    check(first.closed)
    val second = ScriptedOwner(2, { epoch == 2L }, drain)
    bridge.installOwner(2) { constructed++;second }
    epoch = 3
    val replacement = async { bridge.installOwner(3) { constructed++;ScriptedOwner(3, { epoch == 3L }) } }
    second.draining.await()
    check(!replacement.isCompleted && constructed == 2)
    second.todayWatchState.value = DesktopTodayWatchState(error = "stale old source")
    check(bridge.state.value.error != "stale old source")
    drain.complete(Unit)
    val third = replacement.await()
    check(constructed == 3 && third.isCurrentOwner())
    println("PASS same MID epoch retirement drains before successor and rejects late projection")
    val cancelled = Job().apply { cancel() }
    try {
        withContext(cancelled) { bridge.installOwner(3) { error("cancelled admission must not construct") } }
        error("pre-cancelled admission accepted")
    } catch (_: CancellationException) { }
    bridge.shutdownForRestore()
    check(!third.isCurrentOwner())
    parent.cancel()
    println("PASS cancelled admission and shutdown drain original owner")
    val waitingParent = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
    val waitingBridge = DesktopTodayWatchRepository({ epoch }, waitingParent)
    val waitingReload = async(start = CoroutineStart.UNDISPATCHED) { waitingBridge.reload() }
    check(!waitingReload.isCompleted)
    waitingBridge.shutdownForRestore()
    try { waitingReload.await();error("unbound startup should cancel on shutdown") }
    catch (_: CancellationException) { }
    waitingParent.cancel()
    println("PASS shutdown cancels unbound startup without constructing a fallback planner")
    println("SCOPE prospective factory/lifecycle only; no mounted UI, Window, MPV, HTTP or account mutation")
}
