package com.bilipai.desktop

import java.nio.file.Files
import kotlin.test.*

class DesktopApplicationRestartTest {
    @Test fun `packaged restart launches the same exe without stale startup health or media args`() = withLaunchers { root ->
        val executable = Files.createFile(root.resolve("BiliPai Windows.exe"))
        val plan = DesktopApplicationRestart.plan(executable.toString(), "unused", mapOf("unrelated" to "unused"), root)
        assertEquals(listOf(executable.toString()), plan.command)
    }

    @Test fun `development restart preserves only its two resource locations and exact classpath`() = withLaunchers { root ->
        val executable = Files.createFile(root.resolve("java.exe"))
        val plan = DesktopApplicationRestart.plan(executable.toString(), "C:/a path/app.jar;C:/dependencies.jar",
            mapOf("compose.application.resources.dir" to "C:/a path/resources", "bilipai.js.workerResources" to "C:/worker",
                "unrelated" to "untrusted"), root)
        assertEquals(listOf(executable.toString(), "-Dcompose.application.resources.dir=C:/a path/resources",
            "-Dbilipai.js.workerResources=C:/worker", "-cp", "C:/a path/app.jar;C:/dependencies.jar", "com.bilipai.desktop.MainKt"), plan.command)
    }

    @Test fun `missing or unknown launcher fails before shutdown`() = withLaunchers { root ->
        assertFailsWith<IllegalArgumentException> { DesktopApplicationRestart.plan(root.resolve("missing.exe").toString(), "app", emptyMap(), root) }
        val executable = Files.createFile(root.resolve("other.exe"))
        assertFailsWith<IllegalStateException> { DesktopApplicationRestart.plan(executable.toString(), "app", emptyMap(), root) }
    }

    private fun withLaunchers(action: (java.nio.file.Path) -> Unit) {
        val root = Files.createTempDirectory("bilipai-restart-")
        try { action(root) }
        finally { Files.list(root).use { it.forEach(Files::deleteIfExists) }; Files.deleteIfExists(root) }
    }
}
