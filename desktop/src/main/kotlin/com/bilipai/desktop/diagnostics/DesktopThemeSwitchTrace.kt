package com.bilipai.desktop.diagnostics

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import com.sun.jna.Native
import com.sun.jna.ptr.LongByReference
import com.sun.jna.win32.StdCallLibrary
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.event.MouseEvent
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/** TON-1210 diagnosis only, opt-in with `-Dbilipai.trace.themeSwitch=<file>`. Call sites check [enabled]
 * before timing or building fields, so without the property no clock is read and nothing is allocated.
 * Lines are `<System.nanoTime> <event> thread=<name> k=v`; the writer thread never touches the EDT.
 * Overflow is counted in `# dropped total=<n>`; a normal JVM exit appends `# end dropped=<n>`.
 * Offline reader: desktop/tools/diagnostics/theme-switch/theme_timeline_probe.py. */
internal object DesktopThemeSwitchTrace {
    private const val CAPACITY = 4096
    /** Never a mark line (those start with the timestamp); tells the writer to append the end line and exit. */
    private const val END = ""

    /** A value that is not a usable path leaves tracing off instead of failing class initialization. */
    private val target: Path? = System.getProperty("bilipai.trace.themeSwitch")?.takeIf(String::isNotBlank)?.let { raw ->
        runCatching { Path.of(raw) }.onFailure { warn("path rejected, tracing off", it) }.getOrNull()
    }
    private val queue = if (target != null) ArrayBlockingQueue<String>(CAPACITY) else null
    private val dropped = AtomicLong()
    /** Orders admission, publication and stop. Never held while formatting or doing file I/O. */
    private val lifecycle = Any()
    private var inFlight = 0L
    /** Published marks not yet covered by a successful flush (headers and END are excluded). */
    private var unflushed = 0L
    @Volatile private var active = target != null
    /** False when off, after the writer failed, or once closed for exit; it never turns back on. */
    val enabled: Boolean get() = active
    /** Why tracing stopped early (unwritable file, disk error); null while healthy or never enabled. */
    @Volatile var failure: String? = null
        private set
    /** Read by the root draw probe so the first draw after a palette apply is recorded, not a cached one. */
    val drawEpoch = mutableIntStateOf(0)

    private interface Kernel32 : StdCallLibrary {
        fun QueryPerformanceCounter(value: LongByReference): Boolean
        fun QueryPerformanceFrequency(value: LongByReference): Boolean
    }

    private val writer: Thread? = if (target == null || queue == null) null else {
        // Bracket one QPC read with nanoTime so PresentMon/pixel samplers can be joined; absent = exit 4 in the probe.
        val sync = runCatching {
            val kernel = Native.load("kernel32", Kernel32::class.java)
            val frequency = LongByReference(); val ticks = LongByReference()
            check(kernel.QueryPerformanceFrequency(frequency))
            val before = System.nanoTime(); check(kernel.QueryPerformanceCounter(ticks)); val after = System.nanoTime()
            "# clock_sync jvm_nano_before=$before qpc_ticks=${ticks.value} jvm_nano_after=$after qpc_freq=${frequency.value}"
        }.getOrNull()
        queue.offer("# ton1210-trace v1 pid=${ProcessHandle.current().pid()} os=${clean(System.getProperty("os.name"))}")
        if (sync != null) queue.offer(sync)
        // Already shutting down: no end line, the probe reports the file as possibly truncated.
        runCatching { Runtime.getRuntime().addShutdownHook(Thread(::close, "bilipai-theme-trace-close")) }
        Thread({ write(target, queue) }, "bilipai-theme-trace").apply { isDaemon = true; start() }
    }

    private fun write(file: Path, lines: ArrayBlockingQueue<String>) = try {
        Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use { out ->
            fun line(text: String) { out.write(text); out.newLine() }
            var batch = 0L
            fun flush() {
                out.flush()
                synchronized(lifecycle) { unflushed -= batch }
                batch = 0
            }
            var reported = 0L
            var next = lines.take()
            while (next != END) {
                line(next)
                if (!next.startsWith("#")) batch++
                next = lines.poll() ?: run {
                    // Visible even if the process is later killed before the end line.
                    dropped.get().takeIf { it != reported }?.let { reported = it; line("# dropped total=$it") }
                    flush()
                    // A full queue can leave no room for END. Once drained, stop is itself the sentinel.
                    synchronized(lifecycle) { lines.poll() ?: if (!active) END else null } ?: lines.take()
                }
            }
            line("# end dropped=${dropped.get()}")
            flush()
        }
    } catch (error: Exception) {
        // Stop producing instead of filling memory for a writer that is gone.
        synchronized(lifecycle) {
            stop()
            dropped.addAndGet(unflushed)
            unflushed = 0
            lines.clear()
            failure = error.toString()
        }
        warn("writer failed, tracing off", error)
    }

    /** Shutdown hook: refuse new marks, let the writer drain and append the end line. */
    private fun close() {
        synchronized(lifecycle) {
            if (!active) return
            stop()
            queue?.offer(END)
        }
        writer?.join(2_000)
    }

    /** Caller holds lifecycle. Retire paused formatters now, before the writer can publish an end count. */
    private fun stop() {
        active = false
        dropped.addAndGet(inFlight)
        inFlight = 0
    }

    private fun warn(what: String, error: Throwable) = System.err.println("bilipai.trace.themeSwitch $what: $error")

    private fun clean(value: Any?) = value.toString().replace(Regex("\\s"), "_")

    fun mark(event: String, vararg fields: Pair<String, Any?>) {
        if (!active) return
        synchronized(lifecycle) {
            if (!active) return
            inFlight++
        }
        var line: String? = null
        try {
            val now = System.nanoTime()
            line = buildString {
                append(now).append(' ').append(event).append(" thread=").append(clean(Thread.currentThread().name))
                for ((key, value) in fields) append(' ').append(key).append('=').append(clean(value))
            }
        } finally {
            synchronized(lifecycle) {
                // Stop already counted every outstanding formatter. Late completion cannot publish or count twice.
                if (active) {
                    inFlight--
                    if (line?.let { queue?.offer(it) } == true) unflushed++ else dropped.incrementAndGet()
                }
            }
        }
    }

    /** Raw AWT press/release before Compose dispatch; onClick fires on release. */
    fun installInputProbe() {
        if (!enabled) return
        Toolkit.getDefaultToolkit().addAWTEventListener({ event ->
            val mouse = event as MouseEvent
            val name = when (mouse.id) {
                MouseEvent.MOUSE_PRESSED -> "input.press"
                MouseEvent.MOUSE_RELEASED -> "input.release"
                else -> null
            }
            if (name != null && enabled) mark(name, "button" to mouse.button, "when_ms" to mouse.`when`,
                "now_ms" to System.currentTimeMillis())
        }, AWTEvent.MOUSE_EVENT_MASK)
    }
}

/** Root draw boundary: content recording finished for the frame after a palette apply (not GPU present). */
internal fun Modifier.themeSwitchDrawProbe(): Modifier = if (!DesktopThemeSwitchTrace.enabled) this else drawWithContent {
    drawContent()
    if (DesktopThemeSwitchTrace.enabled) DesktopThemeSwitchTrace.mark("frame.draw", "epoch" to DesktopThemeSwitchTrace.drawEpoch.intValue)
}
