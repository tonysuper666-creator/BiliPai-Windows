package com.bilipai.desktop.update

import java.nio.file.Files
import java.nio.file.Path

/** Test-only process configuration shared by updater activation and the actual
 * previous launcher. Keep the real command, health arguments, working directory
 * and output redirects; only this child's data roots and JVM options change.
 * This does not make Windows Known Folders or native APIs a sandbox. */
internal class PackagedUpdaterChildEnvironment(caseRoot: Path) {
    val root: Path = UpdateStorage.existingPathWithoutLinks(caseRoot)
    val localData: Path = Files.createDirectory(root.resolve("LocalAppData"))
    val roamingData: Path = Files.createDirectory(root.resolve("RoamingAppData"))
    val home: Path = Files.createDirectory(root.resolve("home"))
    val temporary: Path = Files.createDirectory(root.resolve("tmp"))

    private fun property(name: String, path: Path): String {
        val value = path.toString()
        require(value.none { it == '"' || it == '\r' || it == '\n' || it == '\u0000' })
        return "-D$name=\"$value\""
    }

    fun configure(builder: ProcessBuilder): ProcessBuilder = builder.apply {
        environment().apply {
            this["LOCALAPPDATA"] = localData.toString()
            this["APPDATA"] = roamingData.toString()
            this["USERPROFILE"] = home.toString()
            this["TEMP"] = temporary.toString()
            this["TMP"] = temporary.toString()
            listOf("HOMEDRIVE", "HOMEPATH", "BILIPAI_MPV_PATH", "JAVA_TOOL_OPTIONS",
                "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS").forEach(::remove)
            this["JAVA_TOOL_OPTIONS"] = listOf(
                "-Xmx2g", "-XX:MaxMetaspaceSize=512m", "-XX:ReservedCodeCacheSize=128m",
                property("user.home", home), property("java.io.tmpdir", temporary),
            ).joinToString(" ")
        }
    }
}
