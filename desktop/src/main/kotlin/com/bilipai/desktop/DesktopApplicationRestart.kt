package com.bilipai.desktop

import java.nio.file.Files
import java.nio.file.Path

/** Restart the current installation; update health tokens are deliberately single-use. */
internal class DesktopApplicationRestart private constructor(internal val command: List<String>, private val directory: Path) {
    fun launch(): Process = ProcessBuilder(command).directory(directory.toFile()).start()

    companion object {
        fun prepare(): DesktopApplicationRestart = plan(
            ProcessHandle.current().info().command().orElseThrow { IllegalStateException("无法确定客户端启动路径") },
            System.getProperty("java.class.path"),
            listOf("compose.application.resources.dir", "bilipai.js.workerResources").mapNotNull { key ->
                System.getProperty(key)?.let { key to it }
            }.toMap(),
            Path.of(System.getProperty("user.dir")),
        )

        internal fun plan(executable: String, classpath: String, resourceProperties: Map<String, String>, directory: Path): DesktopApplicationRestart {
            val launcher = Path.of(executable).toAbsolutePath().normalize()
            require(Files.isRegularFile(launcher)) { "客户端启动文件已不存在" }
            val name = launcher.fileName.toString()
            val command = when {
                name.equals("BiliPai Windows.exe", ignoreCase = true) -> listOf(launcher.toString())
                name.equals("java.exe", ignoreCase = true) || name.equals("javaw.exe", ignoreCase = true) -> {
                    require(classpath.isNotBlank()) { "客户端类路径不可用" }
                    buildList {
                        add(launcher.toString())
                        for (key in listOf("compose.application.resources.dir", "bilipai.js.workerResources")) {
                            resourceProperties[key]?.let { add("-D$key=$it") }
                        }
                        addAll(listOf("-cp", classpath, "com.bilipai.desktop.MainKt"))
                    }
                }
                else -> error("无法重启未知客户端启动程序")
            }
            require(Files.isDirectory(directory)) { "客户端工作目录不可用" }
            return DesktopApplicationRestart(command, directory)
        }
    }
}
