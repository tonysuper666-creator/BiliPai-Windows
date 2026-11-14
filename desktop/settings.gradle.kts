import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral(); google() }
}
dependencyResolutionManagement {
    repositories { mavenCentral(); google() }
}
rootProject.name = "bilipai-windows"
include(":miuix5157")
project(":miuix5157").projectDir = file("third-party/miuix5157")
include(":upstream-core")

// Legacy read point. Producers that are not migrated yet keep reading the tree of the last
// pre-merge desktop commit (upstream-core/legacy-root.commit), materialized from this
// repository's own Git objects into <repo>/.legacy-root. Its desktop/build is the live build
// directory, because those producers read each other's generated output. Migrated modules
// read the merged repository root directly. -Pbilipai.legacyRoot=<dir> overrides the location.
fun isLink(path: Path): Boolean = Files.exists(path, LinkOption.NOFOLLOW_LINKS) &&
    Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).let { it.isSymbolicLink || it.isOther }

fun run(vararg command: String, directory: File) {
    val process = ProcessBuilder(*command).directory(directory).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { "Legacy read point: `${command.joinToString(" ")}` failed:\n$output" }
}

// Keep extraction independent of the Windows tar pathname failure. The Python
// interpreter selection matches desktop/build.gradle.kts and uses the existing toolchain.
fun extractLegacyArchive(archive: File, root: File, expectedSha256: String, repository: File) {
    check(expectedSha256.matches(Regex("[0-9a-f]{64}"))) { "Legacy read point: invalid archive SHA-256 pin" }
    val digest = MessageDigest.getInstance("SHA-256")
    archive.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            digest.update(buffer, 0, size)
        }
    }
    val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    check(actual == expectedSha256) { "Legacy read point: archive SHA-256 mismatch: expected $expectedSha256, got $actual" }
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        val python = System.getenv("PYTHON_EXECUTABLE") ?: "python"
        run(python, "-I", "-c", """
            import os, sys, tarfile
            destination = os.path.abspath(sys.argv[2])
            # Extended paths work even when Windows LongPathsEnabled is disabled.
            if not destination.startswith('\\\\?\\'):
                if destination.startswith('\\\\'):
                    destination = '\\\\?\\UNC\\' + destination[2:]
                else:
                    destination = '\\\\?\\' + destination
            with tarfile.open(sys.argv[1], encoding='utf-8', errorlevel=2) as archive:
                # Directory attribute restoration also needs native separators with \\?\ paths.
                members = (member.replace(name=member.name.replace('/', os.sep), deep=False) for member in archive)
                archive.extractall(destination, members=members, filter='data')
        """.trimIndent(), archive.absolutePath, root.absolutePath, directory = repository)
    } else {
        run("tar", "-xf", archive.absolutePath, "-C", root.absolutePath, directory = repository)
    }
}

fun materializeLegacyRoot(): File {
    val repository = settingsDir.parentFile
    val commit = settingsDir.resolve("upstream-core/legacy-root.commit").readLines()
        .map { it.trim() }.single { it.isNotEmpty() && !it.startsWith("#") }
    val root = repository.resolve(".legacy-root")
    val marker = root.resolve(".legacy-commit")
    val buildLink = root.toPath().resolve("desktop/build")
    if (marker.isFile && marker.readText() == commit && isLink(buildLink)) return root
    // Remove the build link first so the recursive delete can never reach the live build directory.
    if (isLink(buildLink)) Files.delete(buildLink)
    if (root.exists()) Files.walk(root.toPath()).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
    }
    root.mkdirs()
    val archive = repository.resolve(".legacy-root.tar")
    try {
        run("git", "-c", "core.autocrlf=false", "-c", "core.eol=lf", "archive", "--format=tar", "-o", archive.absolutePath, commit,
            directory = repository)
    } catch (failure: IllegalStateException) {
        throw GradleException("Legacy read point $commit is not in this clone; fetch it (e.g. `git fetch --unshallow`) and retry.", failure)
    }
    val archiveSha256 = settingsDir.resolve("upstream-core/legacy-root.archive.sha256").readText().trim()
    extractLegacyArchive(archive, root, archiveSha256, repository)
    archive.delete()
    val liveBuild = settingsDir.resolve("build").apply { mkdirs() }
    check(!buildLink.toFile().exists()) { "Legacy read point: $commit unexpectedly tracks desktop/build" }
    if (System.getProperty("os.name").startsWith("Windows")) {
        run("cmd", "/c", "mklink", "/J", buildLink.toString(), liveBuild.absolutePath, directory = repository)
    } else {
        Files.createSymbolicLink(buildLink, buildLink.parent.relativize(liveBuild.toPath()))
    }
    marker.writeText(commit)
    return root
}

val legacyRoot = providers.gradleProperty("bilipai.legacyRoot").orNull?.let(::File) ?: materializeLegacyRoot()
gradle.extra["bilipai.legacyRoot"] = legacyRoot.absolutePath
