


// Append after the existing original Tablet task. No shared build file replacement.
val extractOriginalTabletOwnerSpace by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractUpstreamSpaceOverview, extractOriginalVideoTabletFull)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-tablet-owner-space.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-tablet-owner-space").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-tablet-owner-space.py", "tools/sync-upstream.py", sourceManifest)
    inputs.files(sources.filter { "stable-tablet-owner-space-original" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-tablet-owner-space"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-tablet-owner-space/com")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalTabletOwnerSpace) }


val extractOriginalVideoPlaylistFull by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-video-playlist.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-video-playlist-full").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-video-playlist.py", sourceManifest)
    inputs.file(File(repositoryRoot, "app/src/main/java/com/android/purebilibili/feature/video/player/PlaylistManager.kt"))
    outputs.dir(layout.buildDirectory.dir("generated/original-video-playlist-full"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-video-playlist-full/com")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalVideoPlaylistFull) }


val extractOriginalVideoOwnerDanmakuSend by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractOriginalDanmakuListMenu)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-video-owner-danmaku-send.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-video-owner-danmaku-send").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-video-owner-danmaku-send.py", "tools/extract-upstream-danmaku-list-menu.py", sourceManifest)
    inputs.file(File(repositoryRoot, "app/src/main/java/com/android/purebilibili/data/repository/DanmakuRepository.kt"))
    outputs.dir(layout.buildDirectory.dir("generated/original-video-owner-danmaku-send"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-video-owner-danmaku-send/com")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalVideoOwnerDanmakuSend) }


val extractOriginalVideoRootStoryFeed by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractOriginalDanmakuListMenu)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-video-root-story-feed.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-video-root-story-feed").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-video-root-story-feed.py", "tools/extract-upstream-danmaku-list-menu.py", sourceManifest)
    inputs.file(File(repositoryRoot, "app/src/main/java/com/android/purebilibili/data/repository/StoryRepository.kt"))
    inputs.file(File(repositoryRoot, "app/src/main/java/com/android/purebilibili/core/network/ApiClient.kt"))
    inputs.file(File(repositoryRoot, "app/src/main/java/com/android/purebilibili/data/model/response/StoryModels.kt"))
    outputs.dir(layout.buildDirectory.dir("generated/original-video-root-story-feed"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-video-root-story-feed/com")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalVideoRootStoryFeed) }
