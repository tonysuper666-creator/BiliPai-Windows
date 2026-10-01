package com.bilipai.desktop.ui.textShareProof

import com.bilipai.desktop.ui.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

private class QueueDispatcher: CoroutineDispatcher() {
    val tasks = ConcurrentLinkedQueue<Runnable>()
    override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
    fun drain() { while (true) (tasks.poll() ?: break).run() }
}

fun main(args: Array<String>) {
    val out = Path.of(args[0]);Files.createDirectories(out)
    val actualMain = Path.of(args[1]).toRealPath();val candidate = Path.of(args[2]).toRealPath()
    var assertions = 0
    fun verify(value: Boolean, text: String) { check(value) { text };assertions++ }
    val alive = AtomicBoolean(true);val epoch = AtomicLong(10);val expectedEpoch = 10L
    val owned: () -> Boolean = { alive.get() && epoch.get() == expectedEpoch }
    val dispatcher = QueueDispatcher();val scope = CoroutineScope(SupervisorJob() + dispatcher)
    val messages = mutableListOf<String>();val calls = mutableListOf<Pair<String,String>>()
    val immediate = DesktopTextShareBindings { title, text, guard ->
        verify(guard === owned && guard(), "exact caller page/epoch guard reaches Root callback")
        calls += title to text;true
    }
    try {
        val first = checkNotNull(requestDesktopTextShare(immediate, scope, "分享评论", "immutable text https://www.bilibili.com/video/BVfixture", owned, messages::add))
        dispatcher.drain()
        verify(first.isCompleted && !first.isCancelled, "current owned share dispatched")
        verify(calls.single() == "分享评论" to "immutable text https://www.bilibili.com/video/BVfixture", "original immutable payload/title forwarded")
        verify(messages.isEmpty(), "success has no fallback feedback")
        // Same MID with a different epoch stays retired before the queued callback executes.
        val queued = checkNotNull(requestDesktopTextShare(immediate, scope, "old", "old subject", owned, messages::add))
        epoch.incrementAndGet();dispatcher.drain()
        verify(queued.isCompleted && calls.size == 1 && messages.isEmpty(), "same-account epoch replacement drops queued share")
        epoch.set(expectedEpoch)
        val suspended = CompletableDeferred<Boolean>()
        val waiting = DesktopTextShareBindings { title, text, guard ->
            verify(guard === owned && guard(), "live guard forwarded during wait")
            calls += title to text;suspended.await()
        }
        val cancelled = checkNotNull(requestDesktopTextShare(waiting, scope, "cancel", "cancel text", owned, messages::add))
        dispatcher.drain();verify(!cancelled.isCompleted && calls.size == 2, "existing caller scope owns pending share")
        cancelled.cancel();dispatcher.drain();suspended.complete(false);dispatcher.drain()
        verify(cancelled.isCancelled && messages.isEmpty(), "caller cancellation is not false-result feedback")
        val late = CompletableDeferred<Boolean>()
        val lateCallback = DesktopTextShareBindings { _, _, _ -> late.await() }
        val retired = checkNotNull(requestDesktopTextShare(lateCallback, scope, "retire", "retire text", owned, messages::add))
        dispatcher.drain();alive.set(false);late.complete(false);dispatcher.drain()
        verify(retired.isCompleted && messages.isEmpty(), "late false result cannot callback retired UI")
        verify(requestDesktopTextShare(immediate, scope, "old", "old", owned, messages::add) == null, "retired owner rejects immediate admission")
        alive.set(true)
        val unavailable = requestDesktopTextShare(DesktopTextShareBindings { _, _, _ -> false }, scope, "valid", "valid", owned, messages::add)
        dispatcher.drain()
        verify(unavailable?.isCompleted == true && messages.single() == "无法打开 Windows 系统分享面板", "current failed native dispatch keeps visible feedback")
        messages.clear();scope.cancel()
        verify(requestDesktopTextShare(immediate, scope, "closed", "closed", owned, messages::add) == null && messages.isEmpty(), "closed page scope cannot enqueue Root callback")
        val identities = mutableListOf<JsonObject>()
        for ((name,path) in listOf("com.bilipai.desktop.ui.DesktopTextShareBindings" to candidate,"com.bilipai.desktop.ui.DesktopTextShareBindingsKt" to candidate,
            "com.bilipai.desktop.diagnostics.DesktopNativeTextShare" to actualMain)) {
            val type = Class.forName(name);val location = Path.of(type.protectionDomain.codeSource.location.toURI()).toRealPath()
            verify(location == path, "exact callback/actual Root actor code source: $name")
            val bytes = type.getResourceAsStream("/" + name.replace('.', '/') + ".class")!!.use { it.readBytes() }
            identities += buildJsonObject { put("class",name);put("path",location.toString());put("sha256ClassBytes",MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }) }
        }
        val result = buildJsonObject {
            put("status","PASS");put("caseCount",5);put("assertions",assertions);put("typedCallbackDispatches",calls.size)
            put("actualCodeSources",JsonArray(identities));put("nativeActorInvoked",false);put("nativePaneOpened",false)
            put("noAccountReads",true);put("noHTTP",true);put("noHWND",true);put("notOriginalButtonMountedE2E",true)
        }
        Files.writeString(out.resolve("result.json"),Json{prettyPrint=true}.encodeToString(JsonObject.serializer(),result));println(result)
    } finally { scope.cancel();dispatcher.drain() }
}
