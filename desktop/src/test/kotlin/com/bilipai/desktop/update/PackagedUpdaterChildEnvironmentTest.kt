package com.bilipai.desktop.update

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** No BiliPai EXE, window, network, account or audio process is started.
 * One bounded 64MiB probe JVM verifies the real environment/property boundary. */
class PackagedUpdaterChildEnvironmentTest {
    @Test fun allApplicationAndJvmRootsUseTheFreshFixtureIncludingPathsWithSpaces() = fixture { root ->
        val isolated = PackagedUpdaterChildEnvironment(root)
        val builder = isolated.configure(ProcessBuilder("unused-test-command"))
        val env = builder.environment()
        assertEquals(isolated.localData.toString(), env["LOCALAPPDATA"])
        assertEquals(isolated.roamingData.toString(), env["APPDATA"])
        assertEquals(isolated.home.toString(), env["USERPROFILE"])
        assertEquals(isolated.temporary.toString(), env["TEMP"])
        assertEquals(env["TEMP"], env["TMP"])
        assertTrue(env.getValue("JAVA_TOOL_OPTIONS").contains("-Duser.home=\"${isolated.home}\""))
        assertTrue(env.getValue("JAVA_TOOL_OPTIONS").contains("-Djava.io.tmpdir=\"${isolated.temporary}\""))
        assertEquals(4, listOf(isolated.localData, isolated.roamingData, isolated.home, isolated.temporary).toSet().size)
        listOf(isolated.localData, isolated.roamingData, isolated.home, isolated.temporary).forEach {
            assertTrue(it.startsWith(root) && Files.isDirectory(it))
        }
    }

    @Test fun inheritedNativeOverridesAndJvmAgentsCannotReplaceTheFixtureConfiguration() = fixture { root ->
        val builder = ProcessBuilder("unused-test-command")
        builder.environment().apply {
            this["JAVA_TOOL_OPTIONS"] = "-javaagent:inherited-agent -Duser.home=real-home"
            this["_JAVA_OPTIONS"] = "-Djava.io.tmpdir=real-temp"
            this["JDK_JAVA_OPTIONS"] = "-Duser.home=other-real-home"
            this["BILIPAI_MPV_PATH"] = "inherited-native-library"
            this["HOMEDRIVE"] = "inherited-drive"
            this["HOMEPATH"] = "inherited-home"
        }
        PackagedUpdaterChildEnvironment(root).configure(builder)
        val env = builder.environment()
        listOf("_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "BILIPAI_MPV_PATH", "HOMEDRIVE", "HOMEPATH")
            .forEach { assertFalse(env.containsKey(it)) }
        val options = env.getValue("JAVA_TOOL_OPTIONS")
        assertFalse(options.contains("inherited") || options.contains("real-home") || options.contains("real-temp"))
        assertTrue(options.startsWith("-Xmx2g -XX:MaxMetaspaceSize=512m -XX:ReservedCodeCacheSize=128m "))
    }

    @Test fun actualUpdaterHealthCommandAndOwnedOutputRedirectsArePreserved() = fixture { root ->
        val command = listOf("fixture.exe", "--update-health-file", root.resolve("health.txt").toString(),
            "--update-health-token", "caller-owned-token", "--other-original-argument")
        val builder = ProcessBuilder(command).directory(root.toFile())
            .redirectOutput(root.resolve("out.log").toFile())
            .redirectError(root.resolve("err.log").toFile())
            .redirectInput(ProcessBuilder.Redirect.PIPE)
        builder.environment()["CALLER_HEALTH_MARKER"] = "preserve-this-value"
        val output = builder.redirectOutput()
        val error = builder.redirectError()
        assertSame(builder, PackagedUpdaterChildEnvironment(root).configure(builder))
        assertEquals(command, builder.command())
        assertEquals(root.toFile(), builder.directory())
        assertSame(output, builder.redirectOutput())
        assertSame(error, builder.redirectError())
        assertEquals(ProcessBuilder.Redirect.PIPE, builder.redirectInput())
        assertEquals("preserve-this-value", builder.environment()["CALLER_HEALTH_MARKER"])
        assertFalse(Files.exists(root.resolve("health.txt")) || Files.exists(root.resolve("out.log")))
    }

    @Test fun repeatConfigurationDoesNotAccumulateOptionsOrChangeTheParentEnvironment() = fixture { root ->
        val parent = System.getenv().toMap()
        val parentHome = System.getProperty("user.home")
        val parentTemp = System.getProperty("java.io.tmpdir")
        val isolated = PackagedUpdaterChildEnvironment(root)
        val builder = isolated.configure(ProcessBuilder("unused-test-command"))
        val first = builder.environment().toMap()
        isolated.configure(builder)
        assertEquals(first, builder.environment().toMap())
        assertEquals(parent, System.getenv().toMap())
        assertEquals(parentHome, System.getProperty("user.home"))
        assertEquals(parentTemp, System.getProperty("java.io.tmpdir"))
    }

    @Test fun realChildJvmReadsTheIsolatedHomeAndTempWithSpaceContainingPaths() = fixture { root ->
        val isolated = PackagedUpdaterChildEnvironment(root)
        val javaExecutable = Path.of(System.getProperty("java.home"), "bin",
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "java.exe" else "java")
        val classpath = listOf(PackagedUpdaterEnvironmentProbe::class.java, Unit::class.java)
            .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }
            .distinct().joinToString(java.io.File.pathSeparator)
        val output = root.resolve("probe-output.txt")
        val error = root.resolve("probe-error.txt")
        val builder = ProcessBuilder(javaExecutable.toString(), "-Xmx64m", "-XX:MaxMetaspaceSize=96m",
            "-XX:ReservedCodeCacheSize=32m", "-cp", classpath, PackagedUpdaterEnvironmentProbe::class.java.name)
            .directory(root.toFile()).redirectOutput(output.toFile()).redirectError(error.toFile())
        builder.environment()["JAVA_TOOL_OPTIONS"] = "-Duser.home=must-not-survive"
        builder.environment()["_JAVA_OPTIONS"] = "-Djava.io.tmpdir=must-not-survive"
        val child = isolated.configure(builder).start()
        try {
            assertTrue(child.waitFor(20, TimeUnit.SECONDS), "Probe JVM timed out")
            assertEquals(0, child.exitValue(), Files.readString(error))
            val values = Files.readAllLines(output).associate { line ->
                val parts = line.split('=', limit = 2)
                assertEquals(2, parts.size)
                parts[0] to parts[1]
            }
            assertEquals(isolated.home.toString(), values["user.home"])
            assertEquals(isolated.temporary.toString(), values["java.io.tmpdir"])
            assertEquals(isolated.localData.toString(), values["LOCALAPPDATA"])
            assertEquals(isolated.roamingData.toString(), values["APPDATA"])
            assertEquals(isolated.home.toString(), values["USERPROFILE"])
            assertEquals(isolated.temporary.toString(), values["TEMP"])
            assertEquals(isolated.temporary.toString(), values["TMP"])
        } finally {
            if (child.isAlive) {
                child.destroyForcibly()
                assertTrue(child.waitFor(5, TimeUnit.SECONDS), "Owned probe JVM did not exit")
            }
        }
    }

    private fun fixture(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("bp-updater environment-").toRealPath()
        try { block(root) } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        }
    }
}

internal object PackagedUpdaterEnvironmentProbe {
    @JvmStatic fun main(args: Array<String>) {
        listOf("user.home", "java.io.tmpdir").forEach { println("$it=${System.getProperty(it)}") }
        listOf("LOCALAPPDATA", "APPDATA", "USERPROFILE", "TEMP", "TMP")
            .forEach { println("$it=${System.getenv(it)}") }
    }
}
