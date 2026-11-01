package com.bilipai.desktop.diagnostics

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import java.io.File
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * TON-1114 diagnostic only: after every test, ask kotlinx-coroutines-test whether an uncaught coroutine failure is waiting
 * for the next runTest, and log which test was running (and which tests ran just before) when it was found.
 */
class Ton1114LeakAttribution : BeforeEachCallback, AfterEachCallback {
    private data class Ran(val name: String, val startMs: Long, var endMs: Long = 0)

    override fun beforeEach(context: ExtensionContext) {
        recent.addLast(Ran(name(context), System.currentTimeMillis()))
        while (recent.size > 12) recent.pollFirst()
    }

    override fun afterEach(context: ExtensionContext) {
        recent.peekLast()?.endMs = System.currentTimeMillis()
        val leak = try { runTest { }; null } catch (e: IllegalStateException) {
            if (e.javaClass.simpleName != "UncaughtExceptionsBeforeTest") throw e
            e
        } ?: return
        val now = System.currentTimeMillis()
        val text = buildString {
            appendLine("LEAK found after ${name(context)} at +${now % 100000}ms")
            recent.toList().forEach { appendLine("  ran ${it.name} start=-${now - it.startMs}ms end=-${now - it.endMs}ms") }
            leak.suppressedExceptions.forEach { appendLine("  suppressed: " + it.stackTraceToString().lines().take(14).joinToString("\n    ")) }
        }
        synchronized(file) { file.appendText(text + "\n") }
        println("TON1114_LEAK $text")
    }

    private fun name(context: ExtensionContext) = context.requiredTestClass.name + "." + context.displayName

    private companion object {
        val recent = ConcurrentLinkedDeque<Ran>()
        val spinners = (System.getenv("TON1114_SPIN")?.toIntOrNull() ?: 0).also { n ->
            repeat(n) { Thread { var x = 0L; while (true) x++ }.also { t -> t.isDaemon = true; t.name = "ton1114-spin-$it"; t.start() } }
        }
        val file = File(System.getProperty("user.dir"), "build/ton1114-leaks.txt").also { it.parentFile.mkdirs() }
    }
}
