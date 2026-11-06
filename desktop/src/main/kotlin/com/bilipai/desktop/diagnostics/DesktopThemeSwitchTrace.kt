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
import java.util.concurrent.LinkedBlockingQueue

/** TON-1210 diagnosis only: absent `-Dbilipai.trace.themeSwitch=<file>` every call is a no-op.
 * Lines are `<System.nanoTime> <event> thread=<name> k=v`; the writer thread never touches the EDT.
 * Offline reader: desktop/tools/diagnostics/theme-switch/theme_timeline_probe.py. */
internal object DesktopThemeSwitchTrace {
    private val target: Path? = System.getProperty("bilipai.trace.themeSwitch")?.takeIf(String::isNotBlank)?.let(Path::of)
    val enabled = target != null
    private val queue = LinkedBlockingQueue<String>()
    /** Read by the root draw probe so the first draw after a palette apply is recorded, not a cached one. */
    val drawEpoch = mutableIntStateOf(0)

    private interface Kernel32 : StdCallLibrary {
        fun QueryPerformanceCounter(value: LongByReference): Boolean
        fun QueryPerformanceFrequency(value: LongByReference): Boolean
    }

    init {
        val file = target
        if (file != null) {
            // Bracket one QPC read with nanoTime so PresentMon/pixel samplers can be joined; absent = exit 4 in the probe.
            val sync = runCatching {
                val kernel = Native.load("kernel32", Kernel32::class.java)
                val frequency = LongByReference(); val ticks = LongByReference()
                check(kernel.QueryPerformanceFrequency(frequency))
                val before = System.nanoTime(); check(kernel.QueryPerformanceCounter(ticks)); val after = System.nanoTime()
                "# clock_sync jvm_nano_before=$before qpc_ticks=${ticks.value} jvm_nano_after=$after qpc_freq=${frequency.value}"
            }.getOrNull()
            queue.add("# ton1210-trace v1 pid=${ProcessHandle.current().pid()} os=${clean(System.getProperty("os.name"))}")
            if (sync != null) queue.add(sync)
            Thread({
                Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use { out ->
                    while (true) {
                        out.write(queue.take()); out.newLine()
                        while (true) { out.write(queue.poll() ?: break); out.newLine() }
                        out.flush()
                    }
                }
            }, "bilipai-theme-trace").apply { isDaemon = true }.start()
        }
    }

    private fun clean(value: Any?) = value.toString().replace(Regex("\\s"), "_")

    fun mark(event: String, vararg fields: Pair<String, Any?>) {
        if (!enabled) return
        val now = System.nanoTime()
        queue.add(buildString {
            append(now).append(' ').append(event).append(" thread=").append(clean(Thread.currentThread().name))
            for ((key, value) in fields) append(' ').append(key).append('=').append(clean(value))
        })
    }

    /** Raw AWT press/release before Compose dispatch; onClick fires on release. */
    fun installInputProbe() {
        if (!enabled) return
        Toolkit.getDefaultToolkit().addAWTEventListener({ event ->
            val mouse = event as MouseEvent
            when (mouse.id) {
                MouseEvent.MOUSE_PRESSED -> mark("input.press", "button" to mouse.button, "when_ms" to mouse.`when`,
                    "now_ms" to System.currentTimeMillis())
                MouseEvent.MOUSE_RELEASED -> mark("input.release", "button" to mouse.button, "when_ms" to mouse.`when`,
                    "now_ms" to System.currentTimeMillis())
            }
        }, AWTEvent.MOUSE_EVENT_MASK)
    }
}

/** Root draw boundary: content recording finished for the frame after a palette apply (not GPU present). */
internal fun Modifier.themeSwitchDrawProbe(): Modifier = if (!DesktopThemeSwitchTrace.enabled) this else drawWithContent {
    drawContent()
    DesktopThemeSwitchTrace.mark("frame.draw", "epoch" to DesktopThemeSwitchTrace.drawEpoch.intValue)
}
