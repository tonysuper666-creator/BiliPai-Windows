package com.bilipai.desktop.ui

import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.serialization.json.*

internal fun assertActual44Origins() {
    val expected = Path.of(System.getProperty("fixture.actualJar")).toRealPath()
    val names = System.getProperty("fixture.origins").split(',')
    ZipFile(expected.toFile()).use { jar ->
        names.forEach { name ->
            val type = Class.forName(name, false, Thread.currentThread().contextClassLoader)
            val actual = Path.of(type.protectionDomain.codeSource.location.toURI()).toRealPath()
            check(actual == expected) { "Unexpected actual origin: $name -> $actual" }
            val resource = name.replace('.', '/') + ".class"
            val bytes = type.getResourceAsStream("/" + resource)!!.use { it.readBytes() }
            val expectedBytes = jar.getInputStream(jar.getEntry(resource)).use { it.readBytes() }
            check(bytes.contentEquals(expectedBytes)) { "Loaded class byte mismatch: $name" }
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            println("ACTUAL_ORIGIN " + buildJsonObject {
                put("class", name); put("codeSource", actual.toString()); put("classSha256Bytes", digest)
            })
        }
    }
}
