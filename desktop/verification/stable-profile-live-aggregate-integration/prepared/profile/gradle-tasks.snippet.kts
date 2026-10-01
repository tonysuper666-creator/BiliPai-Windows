// Merge only; production intentionally omits --standalone, preserving sole DIRECT ownership.
val extractOriginalProfileMain by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractOriginalHomeProtocols)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-profile-main.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/profile-main").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-profile-main.py", "tools/extract-upstream-media.py", "tools/sync-upstream.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "profile-main-original" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/profile-main"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/profile-main")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalProfileMain) }
