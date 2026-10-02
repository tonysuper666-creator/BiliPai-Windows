
// Full original retained Space page, supporter leaves and business ViewModels.
// Existing SpaceUiState/SubTab/selected policies, dynamic cards and gallery stay sole-owned.
val extractOriginalSpacePages by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractUpstreamSpaceOverview, extractUpstreamSpaceContributions,
        extractOriginalVideoTabletFull, extractOriginalFavorites)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-space-pages.py",
        "--repo", repositoryRoot.absolutePath,
        "--out", layout.buildDirectory.dir("generated/original-space-pages").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-space-pages.py", "tools/extract-upstream-tablet-owner-space.py", "tools/sync-upstream.py", sourceManifest)
    inputs.files(layout.buildDirectory.file("generated/space-overview/com/android/purebilibili/feature/space/DesktopOriginalSpaceOverview.kt"),
        layout.buildDirectory.file("generated/space-contributions/com/android/purebilibili/feature/space/DesktopUpstreamSpaceContributionDeclarations.kt"))
    inputs.files(sources.filter { "stable-original-space-pages-root-parity" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-space-pages"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-space-pages")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalSpacePages) }
