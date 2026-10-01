// Append to existing generator task pattern, no dependencies/libraries change.
val extractUpstreamProfileWallpaperImport by tasks.registering(Exec::class) {
    inputs.file("../app/src/main/java/com/android/purebilibili/feature/profile/WallpaperImageImport.kt")
    inputs.file("upstream-sources.json"); inputs.file("tools/extract-upstream-profile-wallpaper-import.py")
    val output = layout.buildDirectory.dir("generated/profile-wallpaper-import")
    outputs.dir(output)
    commandLine(pythonExecutable, "tools/extract-upstream-profile-wallpaper-import.py", "--source-repo", rootDir.absolutePath, "--output-dir", output.get().asFile.absolutePath)
}
// Register generated/profile-wallpaper-import with same Kotlin sourceSet; compileKotlin dependsOn this task.
// Root supplies its existing pythonExecutable/task convention; this is an integration recipe, never executed here.
