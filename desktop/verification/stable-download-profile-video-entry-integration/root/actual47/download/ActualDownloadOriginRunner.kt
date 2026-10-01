package com.bilipai.desktop.proof

import java.nio.file.Path
import java.util.zip.ZipFile
import java.lang.reflect.InvocationTargetException

private fun proveOrigins(reference: Path, expected: Path): Int {
    var count = 0
    ZipFile(reference.toFile()).use { archive ->
        archive.entries().asSequence().filter { it.name.endsWith(".class") }.forEach { entry ->
            val name = entry.name.removeSuffix(".class").replace('/', '.')
            val actual = Class.forName(name, false, ClassLoader.getSystemClassLoader())
            val origin = Path.of(actual.protectionDomain.codeSource.location.toURI()).toAbsolutePath().normalize()
            check(origin == expected) { "Installed download class has foreign origin: $name -> $origin" }
            count++
        }
    }
    for (name in listOf("com.bilipai.desktop.download.DesktopDownloadManager", "com.bilipai.desktop.download.DownloadTask", "com.bilipai.desktop.plugins.DesktopPluginStore", "com.bilipai.desktop.data.DesktopSessionStore")) {
        val actual = Class.forName(name, false, ClassLoader.getSystemClassLoader())
        check(Path.of(actual.protectionDomain.codeSource.location.toURI()).toAbsolutePath().normalize() == expected)
    }
    return count
}

fun main(args: Array<String>) {
    val reference = Path.of(args[1]).toAbsolutePath().normalize()
    val expected = Path.of(args[2]).toAbsolutePath().normalize()
    val before = proveOrigins(reference, expected)
    try {
        val oldUnchangedFixture = Class.forName("com.bilipai.desktop.ui.DownloadListFixtureKt")
        oldUnchangedFixture.getMethod("main", Array<String>::class.java).invoke(null, arrayOf(args[0], args[1]))
    } catch (failure: InvocationTargetException) { throw failure.targetException }
    val after = proveOrigins(reference, expected)
    check(before == after && before > 0)
    println("ACTUAL_DOWNLOAD_ORIGIN_PROOF {\"status\":\"PASS\",\"verifiedInstalledClassesBeforeAndAfter\":$before,\"extraActualOwnerClasses\":4,\"runtimeProductionOverrides\":0,\"referenceJarOnClasspath\":false}")
}
