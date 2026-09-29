package com.bilipai.desktop.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StartupHealthTest {
    @Test
    fun `acknowledged process must survive stabilization period`() = runBlocking {
        val marker = Files.createTempDirectory("bilipai-health-crash").resolve("startup-health.txt")
        Files.writeString(marker, "test-token")
        val process = ControlledProcess()
        launch { delay(20); process.alive = false }
        assertFalse(StartupHealth.await(process, marker, "test-token", timeoutMs = 500, stabilityMs = 100, pollMs = 5))
    }

    @Test
    fun `only current token and live stable process are accepted`() = runBlocking {
        val marker = Files.createTempDirectory("bilipai-health-token").resolve("startup-health.txt")
        Files.writeString(marker, "previous-launch-token")
        val process = ControlledProcess()
        assertFalse(StartupHealth.await(process, marker, "current-token", timeoutMs = 40, stabilityMs = 10, pollMs = 5))
        Files.writeString(marker, "current-token")
        assertTrue(StartupHealth.await(process, marker, "current-token", timeoutMs = 500, stabilityMs = 30, pollMs = 5))
        process.alive = false
        assertFalse(StartupHealth.await(process, marker, "current-token", timeoutMs = 500, stabilityMs = 0, pollMs = 5))
    }

    @Test
    fun `waiting cancellation propagates and failed process cleanup completes`() = runBlocking {
        val marker = Files.createTempDirectory("bilipai-health-cancel").resolve("startup-health.txt")
        val process = ControlledProcess()
        val waiting = async {
            try { StartupHealth.await(process, marker, "token", timeoutMs = 5000) }
            finally { StartupHealth.terminate(process) }
        }
        delay(20)
        waiting.cancel()
        assertFailsWith<CancellationException> { waiting.await() }
        assertFalse(process.alive)
        assertTrue(process.destroyed)
    }

    private class ControlledProcess : Process() {
        @Volatile var alive = true
        @Volatile var destroyed = false
        override fun isAlive() = alive
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun getInputStream(): InputStream = InputStream.nullInputStream()
        override fun getErrorStream(): InputStream = InputStream.nullInputStream()
        override fun waitFor(): Int { alive = false; return 0 }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = !alive
        override fun exitValue(): Int { check(!alive); return 0 }
        override fun destroy() { destroyed = true; alive = false }
        override fun destroyForcibly(): Process { destroy(); return this }
    }
}
