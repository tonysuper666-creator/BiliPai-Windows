// Beside extractOriginalPersonalLists; existing tools/dependencies only.
val extractOriginalWatchLater by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractOriginalFavorites)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-watchlater.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-watchlater").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-watchlater.py", "tools/extract-upstream-favorites.py",
        "tools/extract-upstream-dynamic-reply-protocol.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "stable-personal-watchlater" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-watchlater"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-watchlater")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalWatchLater) }
