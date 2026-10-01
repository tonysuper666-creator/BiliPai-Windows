// Insert after the already-installed extractOriginalHomePage. Merge, do not replace build.gradle.
val extractOriginalHomeViewModel by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-home-viewmodel.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/home-viewmodel").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-home-viewmodel.py", "tools/extract-upstream-home-viewmodel-adaptations.json",
        "tools/extract-upstream-media.py", "tools/sync-upstream.py")
    inputs.files(sources.filter { "home-viewmodel" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/home-viewmodel"))
}
val extractOriginalHomeProtocols by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-home-protocols.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/home-protocols").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-home-protocols.py", "tools/extract-upstream-media.py", "tools/sync-upstream.py")
    inputs.files(sources.filter { "home-protocols" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/home-protocols"))
}
// Add to the existing kotlin.sourceSets.named("main") block.
kotlin.sourceSets.named("main") {
    kotlin.srcDir(layout.buildDirectory.dir("generated/home-viewmodel"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/home-protocols"))
}
tasks.named("compileKotlin") { dependsOn(extractOriginalHomeViewModel, extractOriginalHomeProtocols) }
// Existing full-card expansion calls the sole Home-page producer for two wallpaper outputs.
// Root already registered that dependency with UI524; keep it, and keep the full-card feature input.
// Never use --standalone in production: DIRECT3 VM policies and DIRECT1 WatchLater bus are copied
// once by prepareUpstreamSources. There are no new dependencies or binary installation payloads.
