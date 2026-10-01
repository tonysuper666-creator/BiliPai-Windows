// Beside other original leaf producers; uses existing Python/compiler/dependencies.
val extractOriginalArticleDetail by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractUpstreamDynamicDetailProtocol)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-article-detail.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-article-detail").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-article-detail.py", "tools/extract-upstream-dynamic-reply-protocol.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "stable-article-full" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-article-detail"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-article-detail")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalArticleDetail) }
// Existing extractUpstreamDynamicDetailProtocol remains the only Article protocol producer.
// Its original ArticleRepository source is already registered in dynamic-detail-reply inputs.
