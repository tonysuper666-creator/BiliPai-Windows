// Merge only; never replace the current Root Gradle file.
val extractStableVideoVotes by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-stable-video-votes.py",
        repositoryRoot.absolutePath, layout.buildDirectory.dir("generated/video-votes").get().asFile.absolutePath)
    inputs.files("tools/extract-stable-video-votes.py", "tools/sync-upstream.py")
    inputs.files(
        File(repositoryRoot, "app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/CommandDanmakuOverlay.kt"),
        File(repositoryRoot, "app/src/main/java/com/android/purebilibili/data/repository/DanmakuRepository.kt"),
    )
    outputs.dir(layout.buildDirectory.dir("generated/video-votes"))
}
// Add in the existing kotlin/sourceSets main block:
// kotlin.srcDir(layout.buildDirectory.dir("generated/video-votes"))
tasks.named("compileKotlin") { dependsOn(extractStableVideoVotes) }
