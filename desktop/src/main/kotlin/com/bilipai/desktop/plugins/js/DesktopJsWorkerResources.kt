package com.bilipai.desktop.plugins.js

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest

/** Only the build's fixed, individually verified worker resources can form a guest classpath. */
class DesktopJsWorkerResources(private val resources: Path,
    private val expectedCatalogSha256: String = DESKTOP_JS_WORKER_CATALOG_SHA256) {
    private val root = resources.toAbsolutePath().normalize()
    private val json = Json { ignoreUnknownKeys = false }
    private data class Verified(val java: Path, val classpath: List<Path>)
    private val verified by lazy(::verify)

    fun createProcess(workingDirectory: Path): DesktopJsWorkerProcess = verified.let {
        DesktopJsWorkerProcess(it.java, it.classpath, workingDirectory,
            "com.bilipai.desktop.plugins.js.DesktopJsPluginWorker")
    }
    private fun verify(): Verified {
        require(expectedCatalogSha256.matches(Regex("[0-9a-f]{64}"))) { "JS worker catalog digest is invalid" }
        val file = checked("classpath.json")
        require(Files.size(file) <= 262_144 && sha256(file) == expectedCatalogSha256) { "JS worker resource catalog differs from this application" }
        val catalog = json.parseToJsonElement(Files.readString(file)).jsonObject
        require(catalog.keys == setOf("owner", "schemaVersion", "engineVersion", "jdkVersion", "mainClass", "classpath", "runtimeModules", "resources"))
        require(catalog.string("owner") == "bilipai-js-worker-v1" && catalog["schemaVersion"]?.jsonPrimitive?.int == 1 &&
            catalog.string("engineVersion") == "24.2.2" && catalog.string("jdkVersion") == "21.0.12.1" &&
            catalog.string("mainClass") == "com.bilipai.desktop.plugins.js.DesktopJsPluginWorker") { "JS worker version is unsupported" }
        val modules = listOf("java.base", "java.logging", "java.management", "java.transaction.xa", "java.xml", "java.sql", "jdk.management", "jdk.unsupported")
        require(catalog["runtimeModules"]!!.jsonArray.map { it.jsonPrimitive.content } == modules)
        val classpath = catalog["classpath"]!!.jsonArray.map { it.jsonObject }
        val extras = catalog["resources"]!!.jsonArray.map { it.jsonObject }
        require(classpath.size == 13 && extras.size in 1..512 && classpath.first().string("role") == "worker" &&
            classpath.first().string("file") == "desktop-js-worker.jar" && classpath.drop(1).all {
                it.string("role") == "engine" && it.string("file").startsWith("maven/org/graalvm/") &&
                    it.string("file").endsWith("-24.2.2.jar") }) { "JS worker classpath contains unreviewed artifacts" }
        val entries = classpath + extras
        require(entries.map { it.string("file") }.distinct().size == entries.size)
        val paths = entries.map { entry ->
            require(entry.keys == setOf("file", "sha256", "bytes", "role"))
            val path = checked(entry.string("file"))
            require(Files.size(path) == entry["bytes"]!!.jsonPrimitive.long && sha256(path) == entry.string("sha256")) {
                "JS worker resource checksum differs: ${path.fileName}"
            }
            path
        }
        val expected = (paths + listOf(file)).toSet()
        Files.walk(root).use { walk ->
            val actual = walk.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.limit(1025).toList().toSet()
            require(actual == expected) { "JS worker resources contain extra or missing files" }
        }
        val release = Files.readString(checked("runtime/release"))
        require(release.lineSequence().any { it == "JAVA_VERSION=\"21.0.12.1\"" } &&
            release.lineSequence().any { it == "MODULES=\"${modules.joinToString(" ")}\"" }) { "JS worker runtime differs" }
        return Verified(checked("runtime/bin/javaw.exe"), paths.take(13))
    }
    private fun checked(name: String): Path {
        require(name.isNotBlank() && !name.contains('\\') && !name.contains(':') && !name.contains('\u0000'))
        val relative = Path.of(name)
        require(!relative.isAbsolute && relative.none { it.toString() == ".." || it.toString() == "." })
        val path = root.resolve(relative).normalize()
        require(path.startsWith(root) && Files.isRegularFile(path, NOFOLLOW_LINKS)) { "JS worker resource path is invalid" }
        var cursor = path
        while (cursor != root) {
            require(!Files.isSymbolicLink(cursor) && cursor.toRealPath().startsWith(root.toRealPath())) { "JS worker resource is linked outside the application" }
            cursor = cursor.parent
        }
        return path
    }
    private fun JsonObject.string(key: String) = this[key]!!.jsonPrimitive.content
    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { source ->
            val buffer = ByteArray(65_536)
            while (true) { val count = source.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
