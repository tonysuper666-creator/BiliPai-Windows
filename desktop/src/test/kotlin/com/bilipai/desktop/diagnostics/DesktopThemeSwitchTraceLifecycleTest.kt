package com.bilipai.desktop.diagnostics

import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Each case uses a fresh JVM, the product object, and its real writer and shutdown hook. */
class DesktopThemeSwitchTraceLifecycleTest {
    @Test fun shutdownAccountsForPausedProducer() = runProbe("shutdown")
    @Test fun writerFailureAccountsForPausedProducer() = runProbe("failure")
    @Test fun shutdownCountsEachPausedProducerOnce() = runProbe("many")
    @Test fun formattingFailureIsCountedOnce() = runProbe("format-error")

    private fun runProbe(mode: String) {
        val directory = Files.createTempDirectory("theme-trace-lifecycle-")
        val output = directory.resolve("child.log")
        val classpath = linkedSetOf<String>()
        System.getProperty("java.class.path").split(File.pathSeparator).filter(String::isNotBlank).forEach(classpath::add)
        var loader: ClassLoader? = javaClass.classLoader
        while (loader != null) {
            (loader as? URLClassLoader)?.getURLs()?.filter { it.protocol == "file" }?.forEach {
                classpath.add(Path.of(it.toURI()).toString())
            }
            loader = loader.parent
        }
        val java = Path.of(System.getProperty("java.home"), "bin",
            if (System.getProperty("os.name").startsWith("Windows", true)) "java.exe" else "java")
        val args = listOf("-Djava.awt.headless=true", "-cp", classpath.joinToString(File.pathSeparator),
            DesktopThemeSwitchTraceLifecycleProbe::class.java.name, mode, directory.resolve("events.trace").toString())
        val argumentFile = directory.resolve("java.args")
        Files.writeString(argumentFile, args.joinToString("\n") { "\"" + it.replace("\\", "/").replace("\"", "\\\"") + "\"" })
        val process = ProcessBuilder(java.toString(), "@$argumentFile").redirectErrorStream(true).redirectOutput(output.toFile()).start()
        try {
            assertTrue(process.waitFor(25, TimeUnit.SECONDS), "child timed out: $mode")
            val text = Files.readString(output)
            println("$mode: $text")
            assertEquals(0, process.exitValue(), text)
            assertTrue(text.contains("PASS $mode"), text)
        } finally {
            if (process.isAlive) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
            directory.toFile().deleteRecursively()
        }
    }
}

/** No replacement writer or lifecycle implementation: reflection only observes private product state. */
object DesktopThemeSwitchTraceLifecycleProbe {
    @JvmStatic fun main(args: Array<String>) {
        val (mode, target) = args
        System.setProperty("bilipai.trace.themeSwitch", target)
        val trace = DesktopThemeSwitchTrace
        fun field(name: String) = trace.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(null)
        val writer = field("writer") as Thread
        val queue = field("queue") as java.util.concurrent.BlockingQueue<*>
        val dropped = field("dropped") as AtomicLong
        val close = trace.javaClass.getDeclaredMethod("close").apply { isAccessible = true }
        if (mode == "format-error") {
            try {
                trace.mark("frame.draw", "value" to object {
                    override fun toString(): String = throw IllegalArgumentException("deliberate formatting failure")
                })
                error("formatting should throw")
            } catch (expected: IllegalArgumentException) {
                check(expected.message == "deliberate formatting failure")
            }
            close.invoke(trace)
            check(dropped.get() == 1L)
            check(Files.readAllLines(Path.of(target)).last() == "# end dropped=1")
            println("PASS $mode dropped=${dropped.get()}")
            return
        }
        val count = if (mode == "many") 16 else 1
        val entered = CountDownLatch(count)
        val resume = CountDownLatch(1)
        val tasks = (1..count).map {
            FutureTask {
                trace.mark("frame.draw", "value" to object {
                    override fun toString(): String {
                        entered.countDown()
                        check(resume.await(10, TimeUnit.SECONDS)) { "resume timeout" }
                        return "paused-producer"
                    }
                })
            }.also { Thread(it, "paused-$it").apply { isDaemon = true; start() } }
        }
        check(entered.await(5, TimeUnit.SECONDS)) { "producer never admitted" }
        fun verify() {
            writer.join(5_000)
            check(!writer.isAlive) { "writer did not stop" }
            resume.countDown()
            tasks.forEach { it.get(5, TimeUnit.SECONDS) }
            repeat(100) { trace.mark("after.stop") }
            check(!trace.enabled)
            check(queue.isEmpty()) { "queued_after_stop=${queue.size}" }
            check(dropped.get() == count.toLong()) { "dropped=${dropped.get()}, expected=$count" }
            if (mode != "failure") {
                val text = Files.readString(Path.of(target))
                check(!text.contains("frame.draw"))
                check(text.lineSequence().filter(String::isNotBlank).last() == "# end dropped=$count") { text }
            } else check(trace.failure != null)
            println("PASS $mode queued_after_stop=${queue.size} dropped=${dropped.get()}")
        }
        if (mode == "failure") {
            // Interrupt a real idle writer's blocking take; it enters the same product failure handler as I/O errors.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (writer.state != Thread.State.WAITING && System.nanoTime() < deadline) Thread.yield()
            check(writer.state == Thread.State.WAITING)
            writer.interrupt()
            verify()
        } else {
            Runtime.getRuntime().addShutdownHook(Thread({
                try { verify() } catch (error: Throwable) { error.printStackTrace(); Runtime.getRuntime().halt(2) }
            }, "trace-regression-observer"))
            System.exit(0)
        }
    }
}
