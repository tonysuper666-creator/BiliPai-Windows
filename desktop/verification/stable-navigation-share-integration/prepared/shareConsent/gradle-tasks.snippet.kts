// Merge only, no dependencies. Same original UI/shared renderer owners stay unique.
val extractOriginalVideoShareConsent by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractOriginalHomeProtocols)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-video-share-consent.py",
        "--source-repo", repositoryRoot.absolutePath,
        "--output-dir", layout.buildDirectory.dir("generated/video-share-consent").get().asFile.absolutePath)
    inputs.file("tools/extract-upstream-video-share-consent.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "video-share-original-windows" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/video-share-consent"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/video-share-consent")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalVideoShareConsent) }
