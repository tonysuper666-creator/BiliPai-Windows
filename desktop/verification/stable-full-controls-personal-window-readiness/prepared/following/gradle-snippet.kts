// After extractOriginalWatchLater; same compiler/dependencies and one source graph.
val extractOriginalFollowing by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractOriginalFavorites, extractOriginalWatchLater)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-following.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-following").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-following.py", "tools/extract-upstream-favorites.py",
        "tools/extract-upstream-dynamic-reply-protocol.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "stable-personal-following" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-following"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-following")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalFollowing) }
