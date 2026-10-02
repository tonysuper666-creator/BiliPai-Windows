
// Complete original Search/SearchTrending page and VM bodies. Common DIRECT sources stay
// solely with prepareUpstreamSources; no selected duplicate submit/tab-order declarations.
val extractOriginalSearchPages by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-search-pages.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-search-pages").get().asFile.absolutePath)
    inputs.file("tools/extract-upstream-search-pages.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "desktop-full-original-search-root-parity" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-search-pages"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-search-pages")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalSearchPages) }
