import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

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
    run("tar", "-xf", archive.absolutePath, "-C", root.absolutePath, directory = repository)
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
