// Source-only serial integration recipe; Root uses the existing task/runtime convention.
val extractOriginalDownloadList by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-download-list.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-download-list").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-download-list.py", "tools/sync-upstream.py",
        "tools/extract-upstream-media.py", "tools/extract-appearance-platform.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "stable-download-list-original" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-download-list"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-download-list")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalDownloadList) }
// Do not pass --standalone in production: the direct original DownloadTaskPresentationPolicy
// is copied once by the existing registry sync. Only 3 adapted/selected Kotlin files are emitted here.
