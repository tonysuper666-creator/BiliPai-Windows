// Merge after the parent's sole raw694 protocol task; do not replace shared Gradle.
val extractOriginalLiveList by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractOriginalHomeProtocols)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-live-list.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/live-home-list").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-live-list.py", "tools/extract-upstream-media.py", "tools/sync-upstream.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "live-home-list" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/live-home-list"))
}
kotlin.sourceSets.named("main") {
    kotlin.srcDir(layout.buildDirectory.dir("generated/live-home-list"))
}
tasks.named("compileKotlin") { dependsOn(extractOriginalLiveList) }
// Production intentionally omits --standalone; DIRECT9 have one producer, prepareUpstreamSources.
