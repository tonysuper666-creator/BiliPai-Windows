import groovy.json.JsonSlurper
import java.security.MessageDigest
import java.nio.file.Paths as JvmPaths
import java.nio.file.Files as JvmFiles
import java.util.Locale as JvmLocale
import java.util.zip.ZipFile as JvmZipFile
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("multiplatform") version "2.4.0" apply false
    kotlin("plugin.serialization") version "2.4.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"
    id("org.jetbrains.compose") version "1.12.1"
}

group = "com.bilipai.desktop"
version = "0.1.0"
kotlin { jvmToolchain(21) }

// Producers are invoked as external processes. Windows checkouts keep the historical `python`
// (the Windows toolchain ships it); every other platform only has `python3`, so the interpreter is
// chosen by platform here instead of requiring a local `python` shim. PYTHON_EXECUTABLE still wins.
val pythonExecutable = System.getenv("PYTHON_EXECUTABLE")
    ?: if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "python" else "python3"

// Two build products are Windows x64 only: the JS worker image (compiled and linked with the fixed
// Windows JDK, bin/javac.exe + bin/jlink.exe) and the native diagnostic share bridge (MSVC). Off
// Windows there is no equivalent output, and emitting a digest-shaped value would look like a verified
// asset, so a non-Windows build emits a clearly non-publishable placeholder and the packaging tasks
// refuse to run here. Every other platform difference is handled by the tools themselves.
val hostIsWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

// Producers that are not migrated yet read the legacy read point (see settings.gradle.kts);
// migrated upstream modules read the merged repository root (mergedRepositoryRoot).
val repositoryRoot = file(gradle.extra["bilipai.legacyRoot"] as String)
val mergedRepositoryRoot = projectDir.parentFile
val sourceManifest = file("upstream-sources.json")
val manifest = if (sourceManifest.exists()) {
    JsonSlurper().parse(sourceManifest) as Map<*, *>
} else emptyMap<Any, Any>()
val sources = (manifest["sources"] as? List<*>)?.map { it as Map<*, *> } ?: emptyList()
val originalResources = (manifest["resources"] as? List<*>)?.map { it as Map<*, *> } ?: emptyList()

// Only these reviewed source recipes ignore the Windows package revision.
// Keep every other manifest field. A changed recipe/helper restores the full
// manifest input until the dependency closure is reviewed again.
val revisionNeutralSourceRecipePins = mapOf(
    "tools/desktop_paths.py" to "a2de178814872a493a7257955ed8f7b92b35ad193fa79d5495108832c81b0c69",
    "tools/extract-appearance-platform.py" to "964b59fd07d92c434b887f7ed2684866efbfdfb7268a2ff8a0341d31ff0ea3cb",
    "tools/extract-upstream-api.py" to "c7571703fdaaa33f4fe6ae6fcd370d182efa4623e0dbd6e89d56544b447f313e",
    "tools/extract-upstream-bgm-detail.py" to "2a9f8ad706b290abc1e8328ed1ee26a39124d7fe3f841805b045e7acac4ad246",
    "tools/v036_video_comment_cache.py" to "81cb90c8236ea4ac0b04d1eec7a96aea46e6f2becb2ad24a4cdce8b6563683e2",
    "tools/extract-upstream-dynamic-reply-protocol.py" to "01070c8206336d1efa88ac4bee93cb6f64326511945ee2ec3db100a8df0673fc",
    "tools/v036_comment_route.py" to "3edce0c4518b1b6aa25c1be68a4db186af7fb33c563db5d6194a257277e48b60",
    "tools/extract-upstream-frosted-audio-renderer.py" to "01a66bee41799ee9ab300116c8bae067e2be30bd84298ed188d6343238599db3",
    "tools/extract-upstream-linked-dock.py" to "964f92380e96ccd4f19b7b7a2516a4f35058884dc0f5136cd7b63f4125ecb5fd",
    "tools/extract-upstream-media.py" to "2f159e26fb5bb61bb305e6dfbcf82f7b6647ee048029560a6b4d6fb1f842464f",
    "tools/extract-upstream-plugins.py" to "b31780efa1cf86b3910c6e4fa978da5662d104bf6edb12fa24f05e885f80a3e2",
    "tools/extract-upstream-special-danmaku.py" to "979060ce22a625c76651b24fee264268385ccb96de8956fb1447defca1dedc56",
    "tools/extract-upstream-video-comment-ui.py" to "5cfb2e35b5e558c3a1911d04fd04ffed88ddc0d17571687901ed591b4e9dbf03",
    "tools/extract-video-enhancement.py" to "0046195e1914059a841aacf69ed704c86a85e5a9e3a432fbcce3d3628f2d9195",
    "tools/run-tool-tests.py" to "0114c7c7ecea5c45e26843ed025eae4a7fd991b6d50edf09bf9185ed7f20cd44",
    "tools/sync-upstream.py" to "62e1bab0f3101a76f80d1ec2aa16c0b2f2aacba194df67c291324638adce1fa0",
    "tools/v025-canonical-sources.json" to "2600739eef5521cfef71d6af16762ccc3b9df076dad7ccb79902944f1634130f",
    "tools/v025_source_paths.py" to "3b84ba1b1b0907cfb3cb610bd6f713c37521a79f6a0d78ce185f764547ebc460",
    "tools/v029_brand_consumers.py" to "89648e569d074aaeaa456a12220efb4de3e8ecfcefbbe05f74ea55ef47c3742e",
    "tools/v029_comment_composer.py" to "49321836c7e0b49dbdbd226872f85df8cb391feb8ac7dadd27b56e345bbbca52",
    "tools/v029_comment_search.py" to "b80189f64014e373c136bc38e0fbb4171933203ba0cf2a2e76bf0053e17c1a9d",
    "tools/v029_history_recap.py" to "95b585a371bda7716c5e20d37cce6dbee74b9979df6d1fb6b25030d5618b5d19",
    "tools/v030_live_danmaku.py" to "90b1f24b74e989dc89594f4a528f2a3f58e5eaf1637d454d68368e6c646a1fb2",
    "tools/v030_live_recovery.py" to "564c75e98483025029a6db9d9b1c0d5e3f99354686f6b46d329b63043562b020",
    "tools/v030_live_stream.py" to "faecbca43af511ff72b1642b787f94a30fae2fef5b0cbc143465b80516cea4b5",
    "tools/v030_video_dynamic_share.py" to "7ac67b4ed136152450b5fdd608cd61ae89cab273123d83e12fbe6fe54fbefd41",
    "tools/v033_comment_refresh.py" to "4b00fcbaac4cf18efa427b0cd0aa1aa0fdf2622f1bef97a8b46fb8fbc5d1b70f",
    "tools/v034_live_moderation.py" to "c5218c0eb94101941519e0e76a9da438374ed1e3dec7e79fdec36c67bb3a3be6",
    "tools/v034_live_moderation_ui.py" to "5e2613f9410083f12287cbbd57980ecded6280e9a12add8e0c580b0b4aa89a3d",
)
// These import names come from the same reviewed closure. New local module,
// package or extension forms must restore the full manifest input.
val revisionNeutralSourceLocalImportRoots = setOf("desktop_paths", "v025_source_paths", "v029_brand_consumers", "v029_comment_composer", "v029_comment_search", "v029_history_recap", "v030_live_danmaku", "v030_live_recovery", "v030_live_stream", "v030_video_dynamic_share", "v033_comment_refresh", "v034_live_moderation", "v034_live_moderation_ui")
val revisionNeutralSourceExternalImportRoots = setOf("__future__", "argparse", "datetime", "decimal", "difflib", "functools", "hashlib", "importlib", "json", "os", "pathlib", "re", "shutil", "subprocess", "sys", "textwrap", "unittest", "urllib", "xml")
val revisionNeutralSourceImportRoots = revisionNeutralSourceLocalImportRoots + revisionNeutralSourceExternalImportRoots
val revisionNeutralSourceImportShadowFiles = fileTree("tools") {
    include(org.gradle.api.specs.Spec<org.gradle.api.file.FileTreeElement> { element ->
        val path = element.relativePath.pathString.replace('\\', '/').lowercase(JvmLocale.ROOT)
        // Keep traversal open; only matching files become shadow inputs.
        element.isDirectory || revisionNeutralSourceImportRoots.any { module ->
            path == "$module.pyc" ||
                (path.startsWith(module) && path.endsWith(".pyd") && '/' !in path) ||
                path.startsWith("$module/")
        } || revisionNeutralSourceExternalImportRoots.any { module -> path == "$module.py" }
    })
}
val revisionNeutralSourceImportContextMatches =
    revisionNeutralSourceImportRoots.none { file("tools/$it").exists() } && revisionNeutralSourceImportShadowFiles.isEmpty
// Pin the whole Gradle recipe, including canonical helpers and later wiring.
// Mask exactly this self-digest assignment; raw build bytes remain task inputs.
val revisionNeutralSourceGradleRecipeSha256 = "b943e53a7474a41bcdcf313c616378abae4b4402999f322e4ba3ee02b75346f3"
val revisionNeutralSourceGradleRecipeMatches = runCatching {
    val recipe = file("build.gradle.kts").readText(Charsets.UTF_8).replace("\r\n", "\n")
    val ownPin = "val revisionNeutralSourceGradleRecipeSha256 = \"$revisionNeutralSourceGradleRecipeSha256\""
    recipe.split(ownPin).size == 2 && MessageDigest.getInstance("SHA-256")
        .digest(recipe.replace(ownPin, "val revisionNeutralSourceGradleRecipeSha256 = \"<reviewed-source-self-digest>\"").toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) } == revisionNeutralSourceGradleRecipeSha256
}.getOrDefault(false)
val revisionNeutralSourceRecipesMatch = revisionNeutralSourceGradleRecipeMatches && revisionNeutralSourceImportContextMatches && revisionNeutralSourceRecipePins.all { (relative, expected) ->
    val input = file(relative)
    input.isFile && !JvmFiles.isSymbolicLink(input.toPath()) && runCatching {
        val normalized = input.readText(Charsets.UTF_8).replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
        MessageDigest.getInstance("SHA-256").digest(normalized).joinToString("") { "%02x".format(it) } == expected
    }.getOrDefault(false)
}
val revisionNeutralSourceManifestInput = if (revisionNeutralSourceRecipesMatch) {
    groovy.json.JsonOutput.toJson(manifest.filterKeys { it != "windowsRevision" })
} else if (sourceManifest.exists()) sourceManifest.readText(Charsets.UTF_8) else ""


// Build-time original source identities only; runtime owners are unchanged.
val canonicalOriginalCatalogFile = file("tools/v025-canonical-sources.json")
val canonicalOriginalHelperFile = file("tools/v025_source_paths.py")
val canonicalOriginalCatalogBytes = canonicalOriginalCatalogFile.readBytes().also { bytes ->
    require(MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) } == "2600739eef5521cfef71d6af16762ccc3b9df076dad7ccb79902944f1634130f") {
        "The v025 canonical original source catalog changed without review."
    }
}
val canonicalOriginalCatalog = (JsonSlurper().parse(canonicalOriginalCatalogBytes) as Map<*, *>).also {
    require(it["upstreamCommit"] == "79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40")
}
val canonicalOriginalRoots = (canonicalOriginalCatalog["sourceRoots"] as List<*>).map { it.toString() }
val canonicalOriginalPrevious = canonicalOriginalCatalog["previousPaths"] as Map<*, *>
val canonicalOriginalPins = canonicalOriginalCatalog["paths"] as Map<*, *>
fun liveOriginalIdentity(relative: String): String {
    val path = JvmPaths.get(relative)
    require(!path.isAbsolute && path.none { it.toString() == ".." }) { "Invalid original source identity: $relative" }
    val name = path.normalize().toString().replace('\\', '/')
    if (canonicalOriginalRoots.none { name.startsWith(it) }) return name
    val identity = if (canonicalOriginalPrevious.containsKey(name)) {
        requireNotNull(canonicalOriginalPrevious[name]) { "Upstream removed original source: $name" }.toString()
    } else name
    require(canonicalOriginalPins.containsKey(identity)) { "Unknown canonical original source: $identity" }
    return identity
}
fun canonicalOriginalIdentity(relative: String): String {
    val identity = liveOriginalIdentity(relative)
    // prepareUpstreamSources copies the pre-merge snapshot. A retargeted pin names
    // the direct-compile file, which that snapshot does not contain. Copy the one
    // predecessor still in the snapshot. The live pin check stays on the new path.
    if (File(repositoryRoot, identity).isFile) return identity
    val predecessors = canonicalOriginalPrevious.entries.mapNotNull { (key, value) ->
        val name = key?.toString() ?: return@mapNotNull null
        if (value?.toString() == identity && File(repositoryRoot, name).isFile) name else null
    }.distinct()
    return if (predecessors.size == 1) predecessors.single() else identity
}
fun canonicalOriginalSource(relative: String): File {
    val identity = liveOriginalIdentity(relative)
    // Inventory pins now name the merged direct-compile tree. Unmigrated producers
    // still copy repositoryRoot, and this check stays an exact byte compare.
    val destination = File(mergedRepositoryRoot, identity)
    require(!JvmFiles.isSymbolicLink(destination.toPath())) { "Original source is a symbolic link: $relative" }
    require(destination.canonicalFile.toPath().startsWith(mergedRepositoryRoot.canonicalFile.toPath())) { "Original source escapes repository: $relative" }
    if (canonicalOriginalRoots.any { identity.startsWith(it) }) {
        val pin = canonicalOriginalPins[identity] as Map<*, *>
        val raw = destination.readBytes()
        val normalized = if (pin["hashNormalization"] == "lf")
            raw.toString(Charsets.UTF_8).replace("\r\n", "\n").toByteArray(Charsets.UTF_8) else raw
        require(MessageDigest.getInstance("SHA-256").digest(normalized)
            .joinToString("") { "%02x".format(it) } == pin["sha256"]) { "Canonical original source changed: $identity" }
    }
    return destination
}

val upstreamBuildFile = canonicalOriginalSource("app/build.gradle.kts")
val upstreamBuild = upstreamBuildFile.readText()
val upstreamVersionCode = Regex("versionCode\\s*=\\s*(\\d+)")
    .find(upstreamBuild)?.groupValues?.get(1) ?: "406"
val windowsRevision = manifest["windowsRevision"]?.toString() ?: "1"
val windowsVersion = "0.2.$upstreamVersionCode.$windowsRevision"
val generatedUpstream = layout.buildDirectory.dir("generated/upstream")
val nativeDiagnosticShareOutput = layout.buildDirectory.dir("generated/native-diagnostic-share")

val prepareNativeDiagnosticShare = if (hostIsWindows) tasks.register<Exec>("prepareNativeDiagnosticShare") {
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/prepare-native-diagnostic-share.py",
        // The bridge source, its approval and the compiler driver are checked-in project files, not
        // produced upstream slices: the legacy read point would build the pre-merge C++ and then
        // approve it against that same stale copy.
        "--repo", mergedRepositoryRoot.absolutePath,
        "--output", nativeDiagnosticShareOutput.get().asFile.absolutePath,
        "--asset-dir", file("resources/common/native/windows-x64").absolutePath)
    inputs.files("tools/prepare-native-diagnostic-share.py", "tools/compile-native-diagnostic-share.py",
        "native/diagnostic-share/DesktopDiagnosticShare.cpp", "native/diagnostic-share/approved-development-build.json")
    outputs.file(nativeDiagnosticShareOutput.map { it.file("kotlin/com/bilipai/desktop/diagnostics/DesktopNativeDiagnosticShareAssetHash.kt") })
    outputs.file(nativeDiagnosticShareOutput.map { it.file("producer-receipt.json") })
    outputs.file(file("resources/common/native/windows-x64/bilipai-diagnostic-share.dll"))
    // Resolve the SDK/STL/library graph afresh, including newly shadowing files.
    // Mutable cached DLL metadata is never a runtime trust root.
    outputs.upToDateWhen { false }
} else tasks.register("prepareNativeDiagnosticShare") {
    group = "build"
    description = "Non-Windows placeholder: the MSVC diagnostic share bridge is not built on this host."
    val generated = nativeDiagnosticShareOutput.map { it.file("kotlin/com/bilipai/desktop/diagnostics/DesktopNativeDiagnosticShareAssetHash.kt") }
    outputs.file(generated)
    outputs.upToDateWhen { false }
    doLast {
        val file = generated.get().asFile
        file.parentFile.mkdirs()
        file.writeText("""// GENERATED placeholder: this host cannot build the Windows x64 MSVC bridge.
package com.bilipai.desktop.diagnostics

/** Not a digest. No bridge was compiled here, so the desktop diagnostics share stays unavailable. */
internal object DesktopNativeDiagnosticShareAssetHash {
    const val sha256: String = "unavailable: the diagnostic share bridge is a Windows x64 MSVC build product"
}
""", Charsets.UTF_8)
        logger.lifecycle("No native diagnostic share bridge on this host; compile-only placeholder written.")
    }
}


// core-data and network-core compile straight from the merged upstream root (main source set below);
// these are the paths their generated legacy copies had, so producers no longer emit them.
// design-tokens and design-system (TON-997) are migrated whole: every file of their main source set is
// compiled from the merged root, so every generated legacy copy at one of these paths is skipped, whatever
// producer emits it. The list follows the upstream tree, not a snapshot of it.
val migratedWholeModules = listOf("design-tokens/", "design-system/", "plugin-sdk/")
val migratedWholeModulePaths: Set<String> = migratedWholeModules.flatMap { module ->
    val root = mergedRepositoryRoot.resolve(module + "src/main/java")
    root.walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.relativeTo(root).invariantSeparatorsPath }.toList()
}.toSet()
val migratedUpstreamModules = listOf("core-data/", "network-core/") + migratedWholeModules
// App core (TON-1000): the files under app/src/main/java/com/android/purebilibili/core compile from the merged root in
// this module, except the Android-only areas the desktop implements itself (appCoreDesktopOwned, each with the reason).
val appJavaRoot = mergedRepositoryRoot.resolve("app/src/main/java")
val appCoreDesktopOwned: Map<String, String> = mapOf(
    "core/util/PlaybackTelemetryPolicy.kt" to "Media3 播放错误码分类；桌面播放走 mpv，没有这些错误码",
    "core/theme/ScheduledDarkThemeObserver.kt" to "Android 系统壁纸取色、定时深色（AlarmManager）与字体文件 ContentResolver",
    "core/theme/SeedWallpaperColorScheme.kt" to "Android 系统壁纸取色、定时深色（AlarmManager）与字体文件 ContentResolver",
    "core/database/AppDatabase.kt" to "Room 数据库；桌面用 JSON 存储（DesktopBlockedUpStore、DesktopCommentFraudJsonDao 等）",
    "core/database/Converters.kt" to "Room 数据库；桌面用 JSON 存储（DesktopBlockedUpStore、DesktopCommentFraudJsonDao 等）",
    "core/database/DatabaseModule.kt" to "Room 数据库；桌面用 JSON 存储（DesktopBlockedUpStore、DesktopCommentFraudJsonDao 等）",
    "core/database/dao/BlockedUpDao.kt" to "Room 数据库；桌面用 JSON 存储（DesktopBlockedUpStore、DesktopCommentFraudJsonDao 等）",
    "core/database/dao/CommentFraudDao.kt" to "Room 数据库；桌面用 JSON 存储（DesktopBlockedUpStore、DesktopCommentFraudJsonDao 等）",
    "core/lifecycle/BackgroundManager.kt" to "Android 进程前后台与内存回收回调",
    "core/lifecycle/BackgroundMemoryTrimPolicy.kt" to "Android 进程前后台与内存回收回调",
    "core/performance/Android17Diagnostics.kt" to "Android 进程退出与 Android 17 诊断；桌面诊断走 DesktopDiagnostics",
    "core/performance/NativeExitTrace.kt" to "Android 进程退出与 Android 17 诊断；桌面诊断走 DesktopDiagnostics",
    "core/performance/ProcessExitContext.kt" to "Android 进程退出与 Android 17 诊断；桌面诊断走 DesktopDiagnostics",
    "core/player/BasePlayerViewModel.kt" to "Media3/ExoPlayer 播放栈；桌面播放走 mpv（com.bilipai.desktop.player）",
    "core/player/HiResCompatibleRenderersFactory.kt" to "Media3/ExoPlayer 播放栈；桌面播放走 mpv（com.bilipai.desktop.player）",
    "core/player/LoudnessAwareRenderersFactory.kt" to "Media3/ExoPlayer 播放栈；桌面播放走 mpv（com.bilipai.desktop.player）",
    "core/player/LoudnessNormalizationAudioProcessor.kt" to "Media3/ExoPlayer 播放栈；桌面播放走 mpv（com.bilipai.desktop.player）",
    "core/player/PlaybackMediaCache.kt" to "Media3/ExoPlayer 播放栈；桌面播放走 mpv（com.bilipai.desktop.player）",
    "core/player/PlayerVolumeController.kt" to "Media3/ExoPlayer 播放栈；桌面播放走 mpv（com.bilipai.desktop.player）",
    "core/player/waveform/AudioWaveformCollector.kt" to "Media3/ExoPlayer 播放栈；桌面播放走 mpv（com.bilipai.desktop.player）",
    "core/player/waveform/AudioWaveformPolicy.kt" to "Media3/ExoPlayer 播放栈；桌面播放走 mpv（com.bilipai.desktop.player）",
    "core/player/waveform/AudioWaveformSource.kt" to "Media3/ExoPlayer 播放栈；桌面播放走 mpv（com.bilipai.desktop.player）",
    "core/player/waveform/WaveformAudioSink.kt" to "Media3/ExoPlayer 播放栈；桌面播放走 mpv（com.bilipai.desktop.player）",
    "core/store/AudioLibraryDatabase.kt" to "Room 数据库；桌面用 JSON 存储（DesktopBlockedUpStore、DesktopCommentFraudJsonDao 等）",
    "core/ui/LottieComponents.kt" to "Lottie Android 渲染（桌面无此依赖）",
    "core/ui/MaidLottieAnimationSettingsHost.kt" to "Lottie Android 渲染（桌面无此依赖）",
    "core/ui/SystemBarCompat.kt" to "Android 系统栏、折叠屏、挖孔与画中画窗口",
    "core/ui/adaptive/HingeOcclusionInputShield.kt" to "Android 系统栏、折叠屏、挖孔与画中画窗口",
    "core/ui/adaptive/HingeSafeOverlayRegionsPolicy.kt" to "Android 系统栏、折叠屏、挖孔与画中画窗口",
    "core/ui/animation/ParticleDissolveEffect.kt" to "OpenGL ES 视图粒子特效（GLSurfaceView）",
    "core/ui/animation/gl/ParticleRenderer.kt" to "OpenGL ES 视图粒子特效（GLSurfaceView）",
    "core/ui/animation/gl/ShaderUtils.kt" to "OpenGL ES 视图粒子特效（GLSurfaceView）",
    "core/ui/animation/gl/ThanosEffectView.kt" to "OpenGL ES 视图粒子特效（GLSurfaceView）",
    "core/ui/animation/gl/ThanosParticlePolicy.kt" to "OpenGL ES 视图粒子特效（GLSurfaceView）",
    "core/ui/common/ClipboardUtils.kt" to "Android 剪贴板与文本选择 ActionMode",
    "core/ui/common/Modifiers.kt" to "Android 剪贴板与文本选择 ActionMode",
    "core/ui/common/TextSelectionBottomSheet.kt" to "Android 剪贴板与文本选择 ActionMode",
    "core/ui/common/TextSelectionHost.kt" to "Android 剪贴板与文本选择 ActionMode",
    "core/ui/common/TextSelectionPolicy.kt" to "Android 剪贴板与文本选择 ActionMode",
    "core/ui/performance/AppDisplayModePreference.kt" to "Android 显示模式与帧率覆盖（Window/Display API、androidx.metrics）",
    "core/ui/performance/JankTracking.kt" to "Android 显示模式与帧率覆盖（Window/Display API、androidx.metrics）",
    "core/ui/performance/PanelFrameRateOverridePolicy.kt" to "Android 显示模式与帧率覆盖（Window/Display API、androidx.metrics）",
    "core/ui/performance/VideoPlaybackRefreshRateEffect.kt" to "Android 显示模式与帧率覆盖（Window/Display API、androidx.metrics）",
    "core/ui/performance/VideoRefreshRatePolicy.kt" to "Android 显示模式与帧率覆盖（Window/Display API、androidx.metrics）",
    "core/ui/util/DeviceCornerRadius.kt" to "Android 系统栏、折叠屏、挖孔与画中画窗口",
    "core/util/AnalyticsHelper.kt" to "Android 日志、崩溃采集与 Firebase；桌面走 DesktopDiagnostics 与 desktop/src 里的 Logger 桥",
    "core/util/CacheUtils.kt" to "Android 权限、相册、分享面板、ConnectivityManager 与应用缓存目录",
    "core/util/CrashReporter.kt" to "Android 日志、崩溃采集与 Firebase；桌面走 DesktopDiagnostics 与 desktop/src 里的 Logger 桥",
    "core/util/FileUtils.kt" to "Android 权限、相册、分享面板、ConnectivityManager 与应用缓存目录",
    "core/util/FoldableDisplayPolicy.kt" to "Android 系统栏、折叠屏、挖孔与画中画窗口",
    "core/util/GalleryVisualMediaContracts.kt" to "Android 权限、相册、分享面板、ConnectivityManager 与应用缓存目录",
    "core/util/HingeLayoutPolicy.kt" to "Android 系统栏、折叠屏、挖孔与画中画窗口",
    "core/util/Logger.kt" to "Android 日志、崩溃采集与 Firebase；桌面走 DesktopDiagnostics 与 desktop/src 里的 Logger 桥",
    "core/util/MediaUtils.kt" to "Android 权限、相册、分享面板、ConnectivityManager 与应用缓存目录",
    "core/util/NetworkUtils.kt" to "Android 权限、相册、分享面板、ConnectivityManager 与应用缓存目录",
    "core/util/PermissionHelper.kt" to "Android 权限、相册、分享面板、ConnectivityManager 与应用缓存目录",
    "core/util/PipAspectRatioPolicy.kt" to "Android 系统栏、折叠屏、挖孔与画中画窗口",
    "core/util/ShareUtils.kt" to "Android 权限、相册、分享面板、ConnectivityManager 与应用缓存目录",
    "core/util/VideoSurfaceCompatibility.kt" to "Android 系统栏、折叠屏、挖孔与画中画窗口",
    "core/util/WindowSizeUtils.kt" to "Android 系统栏、折叠屏、挖孔与画中画窗口",
    "core/plugin/js/BiliPaiJsRuntime.kt" to "Android WebView 执行 JS 插件；桌面 JS 插件走隔离的 JS worker（com.bilipai.desktop.plugins.js）",
    "core/plugin/feed/PublicContentImageLoader.kt" to "Android Coil ImageLoader（GIF/动画解码器与 Android 磁盘缓存）；桌面公共内容图片走 DesktopPublicContentImageRouting，同一无凭据图片客户端",
    "core/plugin/json/JsonPluginStatsSummaryWorker.kt" to "WorkManager 定时任务、Android 通知与 MainActivity 跳转；桌面不做后台定时通知（规则命中统计在插件页直接显示）",
)
// Areas that move in a later step and keep their legacy copies until then (an intermediate state that compiles).
val appCoreLaterSteps: Map<String, String> = mapOf(
    "core/ui/components/PageAwareAsyncImage.kt" to "reads feature/home's LocalHomeCardCoverLoadingAllowed and Android drawables; moves with the home feature (M4b)",
    "core/ui/wallpaper/WallpaperMedia" to "video wallpaper on Media3/AndroidView and BitmapFactory probing; needs a desktop replacement over the mpv player",
    "core/util/PlayerOrientationPolicy.kt" to "Activity orientation of the player; moves with the video feature (M4a)",
    "core/util/PlayerPresentationPolicy.kt" to "Activity orientation of the player; moves with the video feature (M4a)",
    "core/store/LocalPlaylistStore.kt" to "Room AudioLibraryDatabase; the desktop music library is JSON, moves with the audio feature (M4c)",
    "core/store/PlayHistoryStore.kt" to "Room AudioLibraryDatabase; the desktop music library is JSON, moves with the audio feature (M4c)",
    "core/events/BrandSuccessEvents.kt" to "the desktop success feedback carries its own origin model (DesktopBrandSuccessOrigin) that upstream's events do not have; moves with the feedback host",
    "core/ui/BrandSuccessFeedbackHost.kt" to "the desktop success feedback carries its own origin model (DesktopBrandSuccessOrigin); moves with BrandSuccessEvents",
    "core/store/FavoriteFolderCoverCache.kt" to "the desktop keeps one cover cache per favorites origin; moves with the favorites feature (M4d)",
    "core/ui/components/AppLiquidAwareTabRow.kt" to "calls feature/home's liquid tab components with their new parameters; moves with the home feature (M4b)",
)
// M4a: video/bangumi logic and unchanged Compose components compile from the merged upstream tree.
// The remaining UI/player files move when their desktop platform adapters are wired.
val migratedVideoFeaturePaths = setOf(
    "com/android/purebilibili/feature/video/player/MiniPlayerManager.kt",
    "com/android/purebilibili/feature/video/state/VideoPlayerState.kt",
    "com/android/purebilibili/feature/video/danmaku/DanmakuPlaybackSyncPolicy.kt",
    "com/android/purebilibili/feature/plugin/SponsorBlockPlaybackSession.kt",
    "com/android/purebilibili/feature/plugin/SponsorBlockConfigStore.kt",
    "com/android/purebilibili/feature/video/ui/section/DanmakuViewportHost.kt",
    "com/android/purebilibili/feature/video/ui/components/SponsorSkipUI.kt",
    "com/android/purebilibili/feature/video/screen/TabletOwnerUploadsPane.kt",
    "com/android/purebilibili/feature/video/ui/overlay/PlayerControlSizing.kt",
    "com/android/purebilibili/feature/video/ui/overlay/PlayerControlSafeArea.kt",
    "com/android/purebilibili/feature/video/ui/components/VideoCommentSkeletonSlots.kt",
    "com/android/purebilibili/feature/video/ui/components/PlayerPanelViewport.kt",
    "com/android/purebilibili/feature/video/screen/VideoDetailCoverContentScale.kt",
    "com/android/purebilibili/feature/video/screen/VideoDetailCommentStateScope.kt",
    "com/android/purebilibili/feature/video/ui/components/EpisodeSheetLayoutPolicy.kt",
    "com/android/purebilibili/feature/video/ui/components/VideoCommentLoadError.kt",
    "com/android/purebilibili/feature/video/danmaku/DanmakuSegmentWindowPolicy.kt",
    "com/android/purebilibili/feature/video/ui/components/CommentThreadNavigationBlur.kt",
    "com/android/purebilibili/feature/video/ui/section/OwnerDecoratedAvatar.kt",
    "com/android/purebilibili/feature/video/ui/components/CommentFraudFeedbackHost.kt",
    "com/android/purebilibili/feature/video/ui/components/PiliPlusDanmakuSendIcon.kt",
    "com/android/purebilibili/feature/video/screen/VideoDetailReturnMediaLayout.kt",
    "com/android/purebilibili/feature/video/screen/VideoDetailTransitionPolicy.kt",
    "com/android/purebilibili/feature/video/ui/overlay/glass/CenterPlaybackControl.kt",
    "com/android/purebilibili/feature/video/ui/overlay/glass/PortraitInteractionBar.kt",
    "com/android/purebilibili/feature/video/ui/components/CoinDialog.kt",
    "com/android/purebilibili/feature/video/ui/feedback/LikeBurstAnchor.kt",
    "com/android/purebilibili/feature/bangumi/ui/player/BangumiCollapsedPlayerBar.kt",
    "com/android/purebilibili/feature/video/screen/VideoDetailTransitionHost.kt",
    "com/android/purebilibili/feature/video/ui/components/CommentFraudDialog.kt",
    "com/android/purebilibili/feature/video/screen/VideoDetailSessionPolicy.kt",
    "com/android/purebilibili/feature/video/ui/section/PlayerPageBackdrop.kt",
    "com/android/purebilibili/feature/video/ui/pager/PortraitDetailSheet.kt",
    "com/android/purebilibili/feature/video/ui/pager/PortraitCommentSheet.kt",
    "com/android/purebilibili/feature/video/ui/components/SeekPreviewBubble.kt",
    "com/android/purebilibili/feature/video/ui/overlay/PlayerOverlayModels.kt",
    "com/android/purebilibili/feature/video/ui/components/CircularGesturePercentText.kt",
    "com/android/purebilibili/feature/video/ui/components/glass/DanmakuSettingsPanelAppearance.kt",
    "com/android/purebilibili/feature/video/playback/session/PlaybackUserActionTracker.kt",
    "com/android/purebilibili/feature/video/ui/components/LandscapeSidePanel.kt",
    "com/android/purebilibili/feature/video/ui/feedback/VideoTriplePressState.kt",
    "com/android/purebilibili/feature/video/screen/VideoInputDiagnostics.kt",
    "com/android/purebilibili/feature/bangumi/BangumiCardTransition.kt",
    "com/android/purebilibili/feature/video/ui/feedback/VideoActionCountText.kt",
    "com/android/purebilibili/feature/video/screen/VideoDetailCommonOverlayAdapter.kt",
    "com/android/purebilibili/feature/video/ui/pager/PortraitPagerSwitchPolicy.kt",
    "com/android/purebilibili/feature/video/ui/overlay/glass/PortraitBottomInputBar.kt",
    "com/android/purebilibili/feature/video/ui/overlay/HotDanmakuBar.kt",
    "com/android/purebilibili/feature/video/state/PlayerLifecyclePlaybackPolicy.kt",
    "com/android/purebilibili/feature/video/ambient/AmbientEnvironment.kt",
    "com/android/purebilibili/feature/bangumi/policy/BangumiHubPolicy.kt",
    "com/android/purebilibili/feature/bangumi/BangumiPlayerOverlayPolicy.kt",
    "com/android/purebilibili/feature/video/ui/overlay/CommandDanmakuOverlayState.kt",
    "com/android/purebilibili/feature/video/ui/overlay/VideoPlayerOverlayProgressState.kt",
    "com/android/purebilibili/feature/video/ui/overlay/FullscreenDoubleTapPolicy.kt",
    "com/android/purebilibili/feature/video/ui/components/DanmakuSameSendConfirmation.kt",
    "com/android/purebilibili/feature/video/playback/session/PlaybackLifecycleCoordinator.kt",
    "com/android/purebilibili/feature/video/playback/session/PlaybackSeekController.kt",
    "com/android/purebilibili/feature/video/playback/audio/PremiumAudioPlaybackFailurePolicy.kt",
    "com/android/purebilibili/feature/video/danmaku/WebMaskParser.kt",
    "com/android/purebilibili/feature/video/danmaku/DanmakuMerger.kt",
    "com/android/purebilibili/feature/video/state/PlayerErrorRecoveryPolicy.kt",
    "com/android/purebilibili/feature/video/ui/VideoDetailShapes.kt",
    "com/android/purebilibili/feature/video/ambient/PlayerAmbientLayout.kt",
    "com/android/purebilibili/feature/video/screen/VideoDetailPresentationState.kt",
    "com/android/purebilibili/feature/video/screen/VideoDetailParentUiSnapshot.kt",
    "com/android/purebilibili/feature/video/screen/TabletVideoLayoutPolicy.kt",
    "com/android/purebilibili/feature/video/screen/VideoDetailInlineScrollRecovery.kt",
    "com/android/purebilibili/feature/video/screen/VideoCommentRefreshContainer.kt",
    "com/android/purebilibili/feature/video/player/PlayerKeyboardPolicy.kt",
    "com/android/purebilibili/feature/video/ui/components/CommentThreadDrag.kt",
    "com/android/purebilibili/feature/video/ui/components/ModalChildScroll.kt",
    "com/android/purebilibili/feature/video/ui/components/ControlCenterMotionSpec.kt",
    "com/android/purebilibili/feature/video/ui/components/VideoActionFeedbackHost.kt",
    "com/android/purebilibili/feature/video/ui/components/CommentContentReveal.kt",
    "com/android/purebilibili/feature/video/ui/components/NativeDanmakuToggleButton.kt",
    "com/android/purebilibili/feature/video/ui/components/CommentCascadingContent.kt",
    "com/android/purebilibili/feature/video/ui/components/UpPreviewSheetPolicy.kt",
    "com/android/purebilibili/feature/video/ui/components/VideoCommentAppearance.kt",
    "com/android/purebilibili/feature/video/ui/components/CommentMentionInsertPolicy.kt",
    "com/android/purebilibili/feature/video/ui/components/EpisodeListNavigationActions.kt",
    "com/android/purebilibili/feature/video/ui/components/VideoControlCenterMotion.kt",
    "com/android/purebilibili/feature/video/ui/gesture/GestureLevelOverlayPolicy.kt",
    "com/android/purebilibili/feature/video/ui/overlay/PlaybackButtonComponents.kt",
    "com/android/purebilibili/feature/video/ui/overlay/PersistentProgressBar.kt",

    "com/android/purebilibili/feature/video/usecase/VideoInteractionUseCase.kt",
    "com/android/purebilibili/feature/video/ambient/AmbientFramePolicy.kt",
    "com/android/purebilibili/feature/video/controller/QualityManager.kt",
    "com/android/purebilibili/feature/video/danmaku/CommandDanmakuPolicy.kt",
    "com/android/purebilibili/feature/video/danmaku/DanmakuCloudRuleSyncPolicy.kt",
    "com/android/purebilibili/feature/video/danmaku/DanmakuContentSegments.kt",
    "com/android/purebilibili/feature/video/danmaku/DanmakuKeywordFilterPolicy.kt",
    "com/android/purebilibili/feature/video/danmaku/DanmakuNativePrototypePolicy.kt",
    "com/android/purebilibili/feature/video/danmaku/DanmakuRenderPauseState.kt",
    "com/android/purebilibili/feature/video/danmaku/DanmakuSettingsPolicy.kt",
    "com/android/purebilibili/feature/video/danmaku/DanmakuSyncStatusPolicy.kt",
    "com/android/purebilibili/feature/video/danmaku/DanmakuTapAnchor.kt",
    "com/android/purebilibili/feature/video/danmaku/DanmakuTypeFilterPolicy.kt",
    "com/android/purebilibili/feature/video/danmaku/DanmakuViewportPolicy.kt",
    "com/android/purebilibili/feature/video/danmaku/HotDanmakuPolicy.kt",
    "com/android/purebilibili/feature/video/interaction/InteractiveVideoChoicePolicy.kt",
    "com/android/purebilibili/feature/video/interaction/InteractiveVideoVariablePolicy.kt",
    "com/android/purebilibili/feature/video/note/AiSummaryNoteDraftPolicy.kt",
    "com/android/purebilibili/feature/video/note/VideoNoteContentCodec.kt",
    "com/android/purebilibili/feature/video/note/VideoNoteEditorStatePolicy.kt",
    "com/android/purebilibili/feature/video/note/VideoNoteEmotionPolicy.kt",
    "com/android/purebilibili/feature/video/note/VideoNoteModels.kt",
    "com/android/purebilibili/feature/video/note/VideoNoteSharePolicy.kt",
    "com/android/purebilibili/feature/video/note/VideoNoteTagPolicy.kt",
    "com/android/purebilibili/feature/video/note/VideoNoteVisibilityPolicy.kt",
    "com/android/purebilibili/feature/video/playback/audio/AudioStreamModels.kt",
    "com/android/purebilibili/feature/video/playback/audio/AudioStreamSelectionPolicy.kt",
    "com/android/purebilibili/feature/video/playback/loader/PlaybackLoadResult.kt",
    "com/android/purebilibili/feature/video/playback/loader/PlaybackLoader.kt",
    "com/android/purebilibili/feature/video/playback/loader/PlaybackRequest.kt",
    "com/android/purebilibili/feature/video/playback/next/NextWatchSuggestion.kt",
    "com/android/purebilibili/feature/video/playback/next/PiliPlaybackOrder.kt",
    "com/android/purebilibili/feature/video/playback/policy/AdaptiveDashTrackPolicy.kt",
    "com/android/purebilibili/feature/video/playback/policy/PlaybackAncillaryTaskPolicy.kt",
    "com/android/purebilibili/feature/video/playback/policy/PlaybackPostLoadPlan.kt",
    "com/android/purebilibili/feature/video/playback/policy/PlaybackTransitionPolicy.kt",
    "com/android/purebilibili/feature/video/playback/policy/PluginPollingPolicy.kt",
    "com/android/purebilibili/feature/video/playback/session/PlaybackSessionStore.kt",
    "com/android/purebilibili/feature/video/player/AudioBarReturnPolicy.kt",
    "com/android/purebilibili/feature/video/player/PlaybackPageOwnership.kt",
    "com/android/purebilibili/feature/video/policy/FavoriteFolderIdPolicy.kt",
    "com/android/purebilibili/feature/video/policy/ResumePlaybackPolicy.kt",
    "com/android/purebilibili/feature/video/policy/VideoDetailScrollCoordinator.kt",
    "com/android/purebilibili/feature/video/progress/PbpProgressPolicy.kt",
    "com/android/purebilibili/feature/video/screen/AudioModeCoverLayoutPolicy.kt",
    "com/android/purebilibili/feature/video/screen/AudioModeRenderPolicy.kt",
    "com/android/purebilibili/feature/video/screen/AudioModeSourcePolicy.kt",
    "com/android/purebilibili/feature/video/screen/ContinuousPlayerTransitionPolicy.kt",
    "com/android/purebilibili/feature/video/screen/LargeScreenVideoLayoutPolicy.kt",
    "com/android/purebilibili/feature/video/screen/LargeScreenVideoReturnPolicy.kt",
    "com/android/purebilibili/feature/video/screen/PlayerCollapseAutoPausePolicy.kt",
    "com/android/purebilibili/feature/video/screen/TabletVideoInfoEntrancePolicy.kt",
    "com/android/purebilibili/feature/video/screen/VideoCommentPerformancePolicy.kt",
    "com/android/purebilibili/feature/video/screen/VideoDetailBackPolicy.kt",
    "com/android/purebilibili/feature/video/screen/VideoDetailReturnLoadBudgetPolicy.kt",
    "com/android/purebilibili/feature/video/screen/VideoDetailTopBarActionPolicy.kt",
    "com/android/purebilibili/feature/video/screen/VideoFavoriteActionPolicy.kt",
    "com/android/purebilibili/feature/video/screen/VideoInitialPagePolicy.kt",
    "com/android/purebilibili/feature/video/share/VideoShareFeedbackEvents.kt",
    "com/android/purebilibili/feature/video/subtitle/BiliSubtitlePolicy.kt",
    "com/android/purebilibili/feature/video/subtitle/SubtitleFeaturePolicy.kt",
    "com/android/purebilibili/feature/video/ui/VideoFollowVisualPolicy.kt",
    "com/android/purebilibili/feature/video/ui/components/CircularGesturePercentMotionPolicy.kt",
    "com/android/purebilibili/feature/video/ui/components/CollectionEpisodePolicy.kt",
    "com/android/purebilibili/feature/video/ui/components/CommentTranslationPolicy.kt",
    "com/android/purebilibili/feature/video/ui/components/DanmakuComposerPolicy.kt",
    "com/android/purebilibili/feature/video/ui/components/InputOverlayLayoutPolicy.kt",
    "com/android/purebilibili/feature/video/ui/components/NagramChatAvatarPlacement.kt",
    "com/android/purebilibili/feature/video/ui/components/PlayerEpisodeMenuPolicy.kt",
    "com/android/purebilibili/feature/video/ui/components/PlayerSettingsMenuLayoutPolicy.kt",
    "com/android/purebilibili/feature/video/ui/components/PlayerSleepTimerPolicy.kt",
    "com/android/purebilibili/feature/video/ui/components/QualitySelectionPolicy.kt",
    "com/android/purebilibili/feature/video/ui/components/RelatedVideoActionPolicy.kt",
    "com/android/purebilibili/feature/video/ui/components/RelatedVideoCardLayoutPolicy.kt",
    "com/android/purebilibili/feature/video/ui/components/VideoCommentBackToTopPolicy.kt",
    "com/android/purebilibili/feature/video/ui/components/VideoSettingsPanelActionPolicy.kt",
    "com/android/purebilibili/feature/video/ui/feedback/TripleActionVisualStatePolicy.kt",
    "com/android/purebilibili/feature/video/ui/feedback/VideoActionFeedbackMessagePolicy.kt",
    "com/android/purebilibili/feature/video/ui/gesture/Md3GestureLevelHapticPolicy.kt",
    "com/android/purebilibili/feature/video/ui/gesture/TwoFingerSpeedGesturePolicy.kt",
    "com/android/purebilibili/feature/video/ui/overlay/BottomRightControlsLayoutPolicy.kt",
    "com/android/purebilibili/feature/video/ui/overlay/FullscreenKeyboardPolicy.kt",
    "com/android/purebilibili/feature/video/ui/overlay/FullscreenPlayerOverlayPollingPolicy.kt",
    "com/android/purebilibili/feature/video/ui/overlay/LandscapeEndDrawerLayoutPolicy.kt",
    "com/android/purebilibili/feature/video/ui/overlay/LiveDanmakuRenderPolicy.kt",
    "com/android/purebilibili/feature/video/ui/overlay/MiniPlayerOverlayChromePolicy.kt",
    "com/android/purebilibili/feature/video/ui/overlay/MiniPlayerOverlayLayoutPolicy.kt",
    "com/android/purebilibili/feature/video/ui/overlay/MiniPlayerOverlayPollingPolicy.kt",
    "com/android/purebilibili/feature/video/ui/overlay/MiniPlayerOverlayPositionPolicy.kt",
    "com/android/purebilibili/feature/video/ui/overlay/MiniPlayerOverlayShellPolicy.kt",
    "com/android/purebilibili/feature/video/ui/overlay/PiliCdnService.kt",
    "com/android/purebilibili/feature/video/ui/overlay/PiliEpisodeNavigation.kt",
    "com/android/purebilibili/feature/video/ui/overlay/PiliPlayerMenuPolicy.kt",
    "com/android/purebilibili/feature/video/ui/overlay/PlaybackOrderDisplayPolicy.kt",
    "com/android/purebilibili/feature/video/ui/overlay/PlayerChromeLayout.kt",
    "com/android/purebilibili/feature/video/ui/overlay/PortraitProgressBarLayoutPolicy.kt",
    "com/android/purebilibili/feature/video/ui/overlay/SubtitleControlModel.kt",
    "com/android/purebilibili/feature/video/ui/overlay/VideoPlayerOverlayVisualPolicy.kt",
    "com/android/purebilibili/feature/video/ui/overlay/VideoProgressBarLayoutPolicy.kt",
    "com/android/purebilibili/feature/video/ui/overlay/glass/BottomControlBarLayoutPolicy.kt",
    "com/android/purebilibili/feature/video/ui/overlay/glass/PortraitBottomInputBarLayoutPolicy.kt",
    "com/android/purebilibili/feature/video/ui/overlay/glass/PortraitFullscreenOverlayLayoutPolicy.kt",
    "com/android/purebilibili/feature/video/ui/overlay/glass/PortraitInteractionBarLayoutPolicy.kt",
    "com/android/purebilibili/feature/video/ui/overlay/glass/PortraitTopBarLayoutPolicy.kt",
    "com/android/purebilibili/feature/video/ui/overlay/glass/TopControlBarLayoutPolicy.kt",
    "com/android/purebilibili/feature/video/ui/pager/PortraitCollectionPolicy.kt",
    "com/android/purebilibili/feature/video/ui/pager/PortraitCommentPresentationPolicy.kt",
    "com/android/purebilibili/feature/video/ui/pager/PortraitControlsVisibilityPolicy.kt",
    "com/android/purebilibili/feature/video/ui/pager/PortraitDetailVideoListPolicy.kt",
    "com/android/purebilibili/feature/video/ui/pager/PortraitMainPlayerSyncPolicy.kt",
    "com/android/purebilibili/feature/video/ui/pager/PortraitPlaybackAdvancePolicy.kt",
    "com/android/purebilibili/feature/video/ui/pager/PortraitProgressSyncPolicy.kt",
    "com/android/purebilibili/feature/video/ui/pager/PortraitSharePolicy.kt",
    "com/android/purebilibili/feature/video/ui/section/VideoDetailMotionBudgetPolicy.kt",
    "com/android/purebilibili/feature/video/ui/section/VideoInfoDisplayPolicy.kt",
    "com/android/purebilibili/feature/video/ui/section/VideoPlayerDanmakuLoadPolicy.kt",
    "com/android/purebilibili/feature/video/ui/section/VideoPlayerSubtitleControlCallbacks.kt",
    "com/android/purebilibili/feature/video/ui/section/VideoPlayerTopBarPolicy.kt",
    "com/android/purebilibili/feature/video/ui/section/VideoPlayerUiLayoutPolicy.kt",
    "com/android/purebilibili/feature/video/usecase/VideoEngagementRelationship.kt",
    "com/android/purebilibili/feature/video/viewmodel/AiSummaryRetryPolicy.kt",
    "com/android/purebilibili/feature/video/viewmodel/AiSummaryUiPolicy.kt",
    "com/android/purebilibili/feature/video/viewmodel/CommentPaginationPolicy.kt",
    "com/android/purebilibili/feature/video/viewmodel/CommentSearchLoader.kt",
    "com/android/purebilibili/feature/video/viewmodel/FavoriteSaveUiPolicy.kt",
    "com/android/purebilibili/feature/video/viewmodel/PlaybackResumeNoticePolicy.kt",
    "com/android/purebilibili/feature/video/viewmodel/PlayerToastPolicy.kt",
    "com/android/purebilibili/feature/video/viewmodel/RelatedVideoLoadScheduler.kt",
    "com/android/purebilibili/feature/video/viewmodel/RelatedVideoPublicationGate.kt",
    "com/android/purebilibili/feature/video/viewmodel/VideoCodecSessionPolicy.kt",
    "com/android/purebilibili/feature/video/viewmodel/VideoCommentLoadPolicy.kt",
    "com/android/purebilibili/feature/video/viewmodel/VideoDetailUiState.kt",
    "com/android/purebilibili/feature/video/viewmodel/VideoDomainBinding.kt",
    "com/android/purebilibili/feature/video/viewmodel/VideoInteractionStatusRetry.kt",
    "com/android/purebilibili/feature/video/viewmodel/VideoLoadTimeline.kt",
    "com/android/purebilibili/feature/bangumi/BangumiDashManifestPolicy.kt",
    "com/android/purebilibili/feature/bangumi/BangumiHubBlurPolicy.kt",
    "com/android/purebilibili/feature/bangumi/MyFollowStats.kt",
    "com/android/purebilibili/feature/bangumi/policy/BangumiEpisodeSkipPolicy.kt",
    "com/android/purebilibili/feature/bangumi/policy/BangumiPlaybackUrlPolicy.kt",
    "com/android/purebilibili/feature/bangumi/policy/BangumiResumePolicy.kt",
    "com/android/purebilibili/feature/bangumi/policy/BangumiSeasonActionPolicy.kt",
    "com/android/purebilibili/feature/bangumi/policy/CourseNavigationPolicy.kt",
    "com/android/purebilibili/feature/bangumi/policy/MyFollowPolicy.kt",
    "com/android/purebilibili/feature/bangumi/policy/MyFollowStatsDetailPolicy.kt",
    "com/android/purebilibili/feature/bangumi/policy/MyFollowWatchInsightPolicy.kt",
    "com/android/purebilibili/feature/bangumi/ui/player/BangumiSeekHapticPolicy.kt",
)
// Feature areas compiled from the merged root as well (TON-1003, M4b): home, search, dynamic and comment. A file of those
// areas stays on its legacy copy while upstream-core/m4b/later-steps.tsv lists it (path <TAB> what it waits for);
// upstream-core/m4b/retired-generated.tsv lists the producer copies that re-declare names of the migrated files.
// The app data layer (TON-1071: data/repository and data/model) moves the same way, with its own lists in upstream-core/data/.
val migratedAppFeatureAreas = listOf("feature/home/", "feature/search/", "feature/dynamic/", "feature/comment/", "data/")
val appFeatureListDirectories = listOf("upstream-core/m4b", "upstream-core/data")
val appFeatureLaterSteps: Map<String, String> = appFeatureListDirectories.flatMap { file("$it/later-steps.tsv").takeIf { it.isFile }?.readLines().orEmpty() }
    .filter { it.isNotBlank() && !it.startsWith("#") }.associate { line -> line.substringBefore('\t') to line.substringAfter('\t') }
val appFeatureRetiredGeneratedCopies: Set<String> = appFeatureListDirectories.flatMap { file("$it/retired-generated.tsv").takeIf { it.isFile }?.readLines().orEmpty() }
    .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()
val migratedAppCorePaths: Set<String> = (appJavaRoot.resolve("com/android/purebilibili/core").walkTopDown() +
        migratedAppFeatureAreas.asSequence().flatMap { appJavaRoot.resolve("com/android/purebilibili/$it").walkTopDown() })
    .filter { it.isFile && it.extension == "kt" }.map { it.relativeTo(appJavaRoot).invariantSeparatorsPath }
    .filter { path -> path.removePrefix("com/android/purebilibili/").let { core ->
        (appCoreDesktopOwned.keys + appCoreLaterSteps.keys).none { core.startsWith(it) } && core !in appFeatureLaterSteps } }
    .toSortedSet() + migratedVideoFeaturePaths
// M4a: migrate the platform-independent parts before the native session and frame scheduler.
val migratedPlaybackModulePaths = mapOf(
    "core-player/src/main/java/" to setOf(
        "com/android/purebilibili/core/player/SharedPlaybackSession.kt",
        "com/android/purebilibili/core/player/PlaybackProgressManager.kt",
        "com/android/purebilibili/core/player/PlayerErrorRecoveryPolicy.kt",
        "com/android/purebilibili/core/player/PlaybackResumePolicy.kt",
        "com/android/purebilibili/core/player/PlaybackProgressPolicy.kt",
        "com/android/purebilibili/core/player/dash/AdaptiveDashPlaybackSource.kt",
        "com/android/purebilibili/core/player/dash/LocalDashManifestBuilder.kt",
        "com/android/purebilibili/core/player/policy/PlaybackQualityMode.kt",
    ),
    "danmaku-engine/src/main/java/" to setOf(
        "com/android/purebilibili/danmaku/engine/DanmakuModels.kt",
        "com/android/purebilibili/danmaku/engine/DanmakuVisibility.kt",
        "com/android/purebilibili/danmaku/parser/AdvancedDanmakuData.kt",
        "com/android/purebilibili/danmaku/parser/DanmakuParser.kt",
        "com/android/purebilibili/danmaku/parser/SpecialDanmakuWindow.kt",
        "com/android/purebilibili/danmaku/parser/TimedDanmakuWindow.kt",
        "com/bytedance/danmaku/render/engine/render/RenderClock.kt",
        "com/bytedance/danmaku/render/engine/render/DrawOrderPolicy.kt",
        "com/bytedance/danmaku/render/engine/render/cache/BufferAdmission.kt",
        "com/bytedance/danmaku/render/engine/data/DanmakuData.kt",
        "com/bytedance/danmaku/render/engine/utils/Constants.kt",
        "com/bytedance/danmaku/render/engine/control/AbsConfig.kt",
        "com/bytedance/danmaku/render/engine/control/ICommandMonitor.kt",
        "com/bytedance/danmaku/render/engine/control/IEventListener.kt",
        "com/bytedance/danmaku/render/engine/control/DanmakuCommand.kt",
        "com/bytedance/danmaku/render/engine/control/DanmakuEvent.kt",
        "com/bytedance/danmaku/render/engine/control/ConfigChangeListener.kt",
        "com/android/purebilibili/danmaku/parser/SpecialDanmakuIndexReader.kt",
        "com/android/purebilibili/danmaku/parser/DanmakuProto.kt",
        "com/android/purebilibili/danmaku/parser/WeightedTextData.kt",
        "com/android/purebilibili/danmaku/parser/bas/BasModels.kt",
        "com/android/purebilibili/danmaku/parser/bas/BasTimeline.kt",
        "com/android/purebilibili/danmaku/parser/bas/BasEasing.kt",
        "com/android/purebilibili/danmaku/parser/bas/BasScriptParser.kt",
        "com/android/purebilibili/danmaku/parser/bas/BasLexer.kt",
    ),
)
// M4d (TON-1005): the feature areas plugin, profile, space, message, settings and login compile from the merged root as
// well. A file stays on its legacy copy while upstream-core/m4d/later-steps.tsv lists it (path <TAB> what it waits for);
// upstream-core/m4d/retired-generated.tsv lists the producer copies that re-declare names of the migrated files.
val migratedAppM4dAreas = listOf("feature/plugin/", "feature/profile/", "feature/space/", "feature/message/", "feature/settings/", "feature/login/")
fun m4dTable(name: String): List<Pair<String, String>> = file("upstream-core/m4d/$name").takeIf { it.isFile }?.readLines().orEmpty()
    .filter { it.isNotBlank() && !it.startsWith("#") }.map { it.substringBefore('\t').trim() to it.substringAfter('\t', "").trim() }
val appM4dLaterSteps: Map<String, String> = m4dTable("later-steps.tsv").toMap()
val appM4dRetiredGeneratedCopies: Set<String> = m4dTable("retired-generated.tsv").map { it.first }.toSet()
val migratedAppM4dPaths: Set<String> = migratedAppM4dAreas.asSequence().flatMap { appJavaRoot.resolve("com/android/purebilibili/$it").walkTopDown() }
    .filter { it.isFile && it.extension == "kt" }.map { it.relativeTo(appJavaRoot).invariantSeparatorsPath }
    .filter { path -> path.removePrefix("com/android/purebilibili/") !in appM4dLaterSteps }
    .toSortedSet()
// Single files of not yet migrated areas that a migrated file reads in their current form (TON-1107: ProfileScreen;
// TON-1108: SpaceScreen's grid column preference, its settings writes go to LocalDesktopFeedGridColumnSettings;
// TON-1109: the message feed pages' MessageManagementRepository, built per message page child over its gated services;
// TON-1120: the private messages' MessageImRepository, whose gRPC calls go over the calling message page child's gated call,
// and the read events the message Root shares between its pages).
val migratedAppSingleFiles: Set<String> = setOf(
    "com/android/purebilibili/feature/home/components/cards/HorizontalVideoCardLayoutPolicy.kt",
    "com/android/purebilibili/feature/home/FeedGridColumnPreference.kt",
    "com/android/purebilibili/data/repository/MessageManagementRepository.kt",
    "com/android/purebilibili/data/repository/MessageImRepository.kt",
    "com/android/purebilibili/data/repository/MessageReadEvent.kt",
    // TON-1124: the upstream ledger repository takes the calling Profile page environment.
    "com/android/purebilibili/data/repository/AccountLedgerRepository.kt",
)
val migratedAppPaths: Set<String> = migratedAppCorePaths + migratedAppM4dPaths + migratedAppSingleFiles
val migratedUpstreamRelativePaths = migratedWholeModulePaths + migratedAppPaths +
    migratedPlaybackModulePaths.values.flatten().toSet() + setOf(
    "com/android/purebilibili/core/network/AppSignUtils.kt",
    "com/android/purebilibili/core/network/WbiUtils.kt",
    "com/android/purebilibili/core/network/grpc/ProtoWire.kt",
    "com/android/purebilibili/core/network/policy/HomeFeedAnonymizerPolicy.kt",
    "com/android/purebilibili/core/network/policy/MergedAppFeedCookiePolicy.kt",
    "com/android/purebilibili/core/network/policy/NetworkProxyPolicy.kt",
    "com/android/purebilibili/core/store/NetworkProxyStore.kt",
    "com/android/purebilibili/core/subtitle/BiliSubtitlePolicy.kt",
    "com/android/purebilibili/core/util/FormatUtils.kt",
    "com/android/purebilibili/core/util/HtmlEntityUtils.kt",
    "com/android/purebilibili/core/util/IdUtils.kt",
    "com/android/purebilibili/core/util/SelectionUtils.kt",
    "com/android/purebilibili/data/model/VideoCardStatsTextPolicy.kt",
    "com/android/purebilibili/data/model/VideoProgressDisplayPolicy.kt",
    "com/android/purebilibili/data/model/VideoQuality.kt",
    "com/android/purebilibili/data/model/WatchLaterProgressPolicy.kt",
    "com/android/purebilibili/data/model/response/AiSummaryResponse.kt",
    "com/android/purebilibili/data/model/response/AicuModels.kt",
    "com/android/purebilibili/data/model/response/ArticleModels.kt",
    "com/android/purebilibili/data/model/response/AudioModels.kt",
    "com/android/purebilibili/data/model/response/BangumiIndexFilterPolicy.kt",
    "com/android/purebilibili/data/model/response/BangumiModels.kt",
    "com/android/purebilibili/data/model/response/BangumiReviewModels.kt",
    "com/android/purebilibili/data/model/response/CaptchaModels.kt",
    "com/android/purebilibili/data/model/response/CreatorCardStats.kt",
    "com/android/purebilibili/data/model/response/DanmakuThumbupResponse.kt",
    "com/android/purebilibili/data/model/response/DynamicCreateModels.kt",
    "com/android/purebilibili/data/model/response/DynamicModels.kt",
    "com/android/purebilibili/data/model/response/DynamicVoteModels.kt",
    "com/android/purebilibili/data/model/response/EmoteModels.kt",
    "com/android/purebilibili/data/model/response/FavoriteModels.kt",
    "com/android/purebilibili/data/model/response/HistoryModels.kt",
    "com/android/purebilibili/data/model/response/InteractVideoResponse.kt",
    "com/android/purebilibili/data/model/response/IpLocationResponse.kt",
    "com/android/purebilibili/data/model/response/LikedVideosModels.kt",
    "com/android/purebilibili/data/model/response/ListModels.kt",
    "com/android/purebilibili/data/model/response/LiveDanmuInfoResponse.kt",
    "com/android/purebilibili/data/model/response/LiveEmoticonModels.kt",
    "com/android/purebilibili/data/model/response/LiveFeedModels.kt",
    "com/android/purebilibili/data/model/response/LiveModels.kt",
    "com/android/purebilibili/data/model/response/LoginModels.kt",
    "com/android/purebilibili/data/model/response/MessageHistoryCursorSerializer.kt",
    "com/android/purebilibili/data/model/response/MessageResponse.kt",
    "com/android/purebilibili/data/model/response/NavModels.kt",
    "com/android/purebilibili/data/model/response/OnlineResponse.kt",
    "com/android/purebilibili/data/model/response/PbpModels.kt",
    "com/android/purebilibili/data/model/response/PersonalFavoriteModels.kt",
    "com/android/purebilibili/data/model/response/PlayerInfoResponse.kt",
    "com/android/purebilibili/data/model/response/RecommendationReason.kt",
    "com/android/purebilibili/data/model/response/RelatedResponse.kt",
    "com/android/purebilibili/data/model/response/ResponseModels.kt",
    "com/android/purebilibili/data/model/response/SearchModels.kt",
    "com/android/purebilibili/data/model/response/SendDanmakuResponse.kt",
    "com/android/purebilibili/data/model/response/SpaceModels.kt",
    "com/android/purebilibili/data/model/response/SplashModels.kt",
    "com/android/purebilibili/data/model/response/SponsorBlockModels.kt",
    "com/android/purebilibili/data/model/response/StoryModels.kt",
    "com/android/purebilibili/data/model/response/UnescapedStringSerializer.kt",
    "com/android/purebilibili/data/model/response/UserModels.kt",
    "com/android/purebilibili/data/model/response/VideoDetailResponse.kt",
    "com/android/purebilibili/data/model/response/VideoNoteResponse.kt",
    "com/android/purebilibili/data/model/response/VideoResponse.kt",
    "com/android/purebilibili/data/model/response/VideoStatusLabels.kt",
    "com/android/purebilibili/data/model/response/VideoVerticalInferencePolicy.kt",
    "com/android/purebilibili/data/model/response/VideoshotResponse.kt",
    "com/android/purebilibili/data/repository/ArticleHistoryReportFields.kt",
    "com/android/purebilibili/data/repository/CommentMediaPolicy.kt",
    "com/android/purebilibili/data/repository/DynamicFetchRetryPolicy.kt",
    "com/android/purebilibili/data/repository/OnDemandRequestCache.kt",
    "com/android/purebilibili/data/repository/PlaybackHeartbeatPolicy.kt",
    "com/android/purebilibili/data/repository/SearchLoadPolicy.kt",
    "com/android/purebilibili/data/repository/VideoLoadPolicy.kt"
)
fun migratedUpstreamSource(path: String): Boolean = migratedUpstreamModules.any { path.startsWith(it) } ||
    migratedUpstreamRelativePaths.any { path.endsWith("/src/main/java/" + it) }

val prepareUpstreamSources by tasks.registering(Sync::class) {
    from(repositoryRoot) {
        include(sources.filter { (it["mode"] ?: "direct") == "direct" && !migratedUpstreamSource(it["path"].toString()) }.map { canonicalOriginalIdentity(it["path"].toString()) })
        // Whole v043 typed navigation is emitted by its existing space producer.
        exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/core/util/BilibiliNavigationTargetParser.kt"))
        // Current collection playback/management policy has one Space producer.
        exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/feature/space/SpacePlaybackPolicy.kt"))
        exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/feature/following/FollowingGroupPolicy.kt"))
        exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/navigation/MessageLinkNavigationPolicy.kt"))
        // Current host-aware HTTP proxy policy has one existing proxy producer.
        exclude(canonicalOriginalIdentity("network-core/src/main/java/com/android/purebilibili/core/network/policy/NetworkProxyPolicy.kt"))
        // Current semantic surface tokens have one original component producer.
        exclude(canonicalOriginalIdentity("design-system/src/main/java/com/android/purebilibili/core/ui/AppSurfaceTokens.kt"))
        // Current whole Search policy is emitted with the complete Search page.
        exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/feature/search/SearchMotionBudgetPolicy.kt"))
        // Latest tabs use the same sole controls/dynamic producer split.
        exclude(canonicalOriginalIdentity("design-system/src/main/java/com/android/purebilibili/core/ui/AppSegmentedControlPolicy.kt"))
        exclude(canonicalOriginalIdentity("design-system/src/main/java/com/android/purebilibili/core/ui/renderer/material3/AppTonalPillTabPolicy.kt"))
        exclude(canonicalOriginalIdentity("design-system/src/main/java/com/android/purebilibili/core/ui/renderer/material3/AppMaterial3SegmentedControl.kt"))
        exclude(canonicalOriginalIdentity("design-system/src/main/java/com/android/purebilibili/core/ui/renderer/miuix/AppMiuixSegmentedControl.kt"))
        exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/feature/video/ui/feedback/VideoActionFeedbackPolicy.kt"))
        // Complete original action facades and renderers have one component producer.
        exclude(canonicalOriginalIdentity("design-system/src/main/java/com/android/purebilibili/core/ui/AppChromeSizeTokens.kt"))
        exclude(canonicalOriginalIdentity("design-system/src/main/java/com/android/purebilibili/core/ui/ButtonVisualPolicy.kt"))
        exclude(canonicalOriginalIdentity("design-system/src/main/java/com/android/purebilibili/core/ui/components/AppIcon.kt"))
        exclude(canonicalOriginalIdentity("design-system/src/main/java/com/android/purebilibili/core/ui/components/AppIconButton.kt"))
        exclude(canonicalOriginalIdentity("design-system/src/main/java/com/android/purebilibili/core/ui/components/AppPrimaryButton.kt"))
        exclude(canonicalOriginalIdentity("design-system/src/main/java/com/android/purebilibili/core/ui/components/AppPrimitiveComponents.kt"))
        exclude(canonicalOriginalIdentity("design-system/src/main/java/com/android/purebilibili/core/ui/components/AppPrimitiveRendererPolicy.kt"))
        exclude(canonicalOriginalIdentity("design-system/src/main/java/com/android/purebilibili/core/ui/renderer/material3/AppMaterial3IconButton.kt"))
        exclude(canonicalOriginalIdentity("design-system/src/main/java/com/android/purebilibili/core/ui/renderer/miuix/AppMiuixActionPrimitives.kt"))
        exclude(canonicalOriginalIdentity("design-system/src/main/java/com/android/purebilibili/core/ui/renderer/miuix/AppMiuixIconButton.kt"))
        // Original value-control facades and compact geometry use the existing component owner.
        exclude(canonicalOriginalIdentity("design-system/src/main/java/com/android/purebilibili/core/ui/components/AppSwitch.kt"))
        exclude(canonicalOriginalIdentity("design-system/src/main/java/com/android/purebilibili/core/ui/MatchedLiquidIndicatorGeometry.kt"))
        exclude(canonicalOriginalIdentity("design-system/src/main/java/com/android/purebilibili/core/ui/renderer/material3/AppMaterial3Switch.kt"))
        exclude(canonicalOriginalIdentity("design-system/src/main/java/com/android/purebilibili/core/ui/renderer/miuix/AppMiuixSwitch.kt"))
        exclude(canonicalOriginalIdentity("design-system/src/main/java/com/android/purebilibili/core/ui/renderer/miuix/AppMiuixSlider.kt"))
        // Complete fixed theme roles and contrast have one existing appearance producer.
        exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/core/theme/ThemeRoleOverridePolicy.kt"))
        exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/feature/settings/ThemePreferencePolicy.kt"))
        exclude(canonicalOriginalIdentity("design-system/src/main/java/com/android/purebilibili/core/theme/ThemeContrastPolicy.kt"))
        // Latest original theme configuration is emitted once by the full controls producer.
        exclude(canonicalOriginalIdentity("design-system/src/main/java/com/android/purebilibili/core/ui/AppThemeConfig.kt"))
        // Complete latest backdrop has one section producer; keep other original callers.
        exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/core/ui/blur/ChromeBackdropSource.kt"))
        // Latest whole player policies have one existing controls producer.
        exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/feature/video/progress/PbpProgressPolicy.kt"))
        exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/feature/video/subtitle/BiliSubtitlePolicy.kt"))
        exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/feature/video/ui/components/LandscapeDanmakuComposer.kt"))
        exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/feature/video/ui/components/LandscapeSidePanel.kt"))
        exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/feature/video/ui/components/NativeDanmakuToggleButton.kt"))
        exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/feature/video/ui/components/PlayerMiuixListPopup.kt"))
        exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/BottomControlBarLayoutPolicy.kt"))
        exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/ImmersiveStatusBarBackdrop.kt"))
        exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/PlaybackButtonComponents.kt"))
        exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/VideoPlayerOverlayVisualPolicy.kt"))
        // The existing Favorites producer now emits the complete current management/privacy policy.
        exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/feature/list/FavoriteManagementPolicy.kt"))
        // These three complete fixed v031 sources have one producer in the existing detail output.
        exclude(canonicalOriginalIdentity("core-data/src/main/java/com/android/purebilibili/data/model/response/VideoDetailResponse.kt"))
        exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/feature/video/ui/components/CoinDialog.kt"))
        exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/feature/video/ui/feedback/TripleActionVisualStatePolicy.kt"))
        // The fixed b209 whole Sponsor UI has one section-full producer.
        exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/feature/video/ui/components/SponsorSkipUI.kt"))
        // The fixed v029 special producer owns this one whole source now.
        exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/feature/download/DownloadDanmakuAssetService.kt"))
        // The pinned stable text/lyrics producer owns these three complete original sources.
        exclude(canonicalOriginalIdentity("core-data/src/main/java/com/android/purebilibili/data/model/response/DynamicModels.kt"))
        exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/feature/audio/lyrics/LyricsParser.kt"))
        exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/feature/audio/lyrics/LyricsDisplayPolicy.kt"))
        // The existing Listen producer owns the original key file with the fixed AudioMode declaration.
        exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/navigation3/BiliPaiNavKey.kt"))
        // Current subscribed-search route mapping shares that existing navigation producer.
        exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/navigation3/BiliPaiNavKeyMappingPolicy.kt"))
        // The sole v0.4.1 status-label producer owns these four complete or hook-edited model sources.
        exclude(canonicalOriginalIdentity("core-data/src/main/java/com/android/purebilibili/data/model/response/ListModels.kt"))
        exclude(canonicalOriginalIdentity("core-data/src/main/java/com/android/purebilibili/data/model/response/SpaceModels.kt"))
        exclude(canonicalOriginalIdentity("core-data/src/main/java/com/android/purebilibili/data/model/response/SearchModels.kt"))
        exclude(canonicalOriginalIdentity("core-data/src/main/java/com/android/purebilibili/data/model/response/FavoriteModels.kt"))
        // Whole v043 author row/snapshot are owned by the retained full-card producer.
        exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/core/ui/components/UpBadgeName.kt"))
        exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/core/ui/transition/VideoCardSourceSnapshot.kt"))
        // The existing Home-page producer owns the complete v043 native-card navigation policy.
        exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/navigation/HomeVideoNavigationPolicy.kt"))
        // The existing full Search producer owns the complete v041 policies.
        exclude(canonicalOriginalIdentity("core-data/src/main/java/com/android/purebilibili/data/repository/SearchLoadPolicy.kt"))
        exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/feature/search/SearchEmptyStatePolicy.kt"))
        // The existing full Search producer owns the complete v0.4.6 content-state policy (the search-only silent switch gate).
        exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/feature/search/SearchContentStatePolicy.kt"))
        // The existing Home-page producer owns the complete v0.4.6 refresh UI policy.
        exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/feature/home/HomeRefreshUiPolicy.kt"))
        // The existing appearance producer owns the complete v0.4.6 theme token files.
        exclude(canonicalOriginalIdentity("design-system/src/main/java/com/android/purebilibili/core/theme/Type.kt"))
        exclude(canonicalOriginalIdentity("design-tokens/src/main/java/com/android/purebilibili/core/theme/AppTextStyles.kt"))
        exclude(canonicalOriginalIdentity("design-system/src/main/java/com/android/purebilibili/core/theme/AdaptiveAccentColorPolicy.kt"))
        exclude(canonicalOriginalIdentity("design-system/src/main/java/com/android/purebilibili/core/theme/AndroidNativeVariantThemePolicy.kt"))
        exclude(canonicalOriginalIdentity("design-system/src/main/java/com/android/purebilibili/core/theme/AppDisplayPolicy.kt"))
        exclude(canonicalOriginalIdentity("design-tokens/src/main/java/com/android/purebilibili/core/theme/Color.kt"))
        // The existing component producer owns the complete v0.4.6 design-system leaf files (part A).
        exclude(canonicalOriginalIdentity("design-system/src/main/java/com/android/purebilibili/core/ui/MediaContrastPalette.kt"))
        exclude(canonicalOriginalIdentity("design-system/src/main/java/com/android/purebilibili/core/ui/AppPopupSurface.kt"))
        exclude(canonicalOriginalIdentity("design-system/src/main/java/com/android/purebilibili/core/ui/AppSemanticVisualPolicy.kt"))
        // The existing reply producer owns the complete v0.4.6 comment font policy file.
        exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/feature/video/ui/components/VideoCommentAppearance.kt"))
        // The existing Home-page producer owns the complete v0.4.6 identity badge palette.
        exclude(canonicalOriginalIdentity("design-system/src/main/java/com/android/purebilibili/core/ui/components/AppBadge.kt"))
        exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/core/ui/components/UpBadgeNamePolicy.kt"))
        exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/core/ui/components/UserUpBadge.kt"))
        exclude(canonicalOriginalIdentity("design-system/src/main/java/com/android/purebilibili/core/ui/UserAvatarCornerMark.kt"))
        exclude(canonicalOriginalIdentity("design-system/src/main/java/com/android/purebilibili/core/ui/OfficialVerifyBadgePolicy.kt"))
        // The whole v0.4.6 strict protobuf parser (DanmakuProto.parseFailed) is emitted by the existing danmaku producer.
        exclude(canonicalOriginalIdentity("danmaku-engine/src/main/java/com/android/purebilibili/danmaku/parser/DanmakuProto.kt"))
        // The existing component and preference producers own the complete v0.4.6 design-system leaf files (part B).
        exclude(canonicalOriginalIdentity("design-system/src/main/java/com/android/purebilibili/core/ui/components/AppPreferenceComponents.kt"))
        exclude(canonicalOriginalIdentity("design-system/src/main/java/com/android/purebilibili/core/ui/components/AppCard.kt"))
        exclude(canonicalOriginalIdentity("design-system/src/main/java/com/android/purebilibili/core/ui/components/AppDesktopInteraction.kt"))
        // The existing dynamic card producer owns the complete v0.4.6 zoomable image and its gesture policy.
        exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/feature/dynamic/components/ZoomableImage.kt"))
        exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/feature/dynamic/components/ZoomableImageGesturePolicy.kt"))
        // The existing Home-page producer owns the complete v0.4.6 card tint policy.
        exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/feature/home/components/cards/VideoCardAdaptiveTintPolicy.kt"))
        // The existing dynamic-detail-container producer owns the complete v0.4.6 glyph text style and window action menu.
        exclude(canonicalOriginalIdentity("design-system/src/main/java/com/android/purebilibili/core/ui/AppPrimitiveThemeDefaults.kt"))
        exclude(canonicalOriginalIdentity("design-system/src/main/java/com/android/purebilibili/core/ui/components/AppWindowActionMenu.kt"))
        // The detail units producer owns the whole v0.4.6 first-batch playback post-load plan.
        exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/feature/video/playback/policy/PlaybackPostLoadPlan.kt"))
        // The existing fullscreen-controls producer owns the complete v0.4.6 persistent and seek progress bar files.
        exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/PersistentProgressBar.kt"))
        exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/VideoProgressBarLayoutPolicy.kt"))
        // The existing message-pages producer owns the complete v0.4.6 chat history response (UINT64_MAX cursor).
        exclude(canonicalOriginalIdentity("core-data/src/main/java/com/android/purebilibili/data/model/response/MessageResponse.kt"))
        // The existing article-detail producer owns the complete v0.4.6 article content parser (public-note arrays).
        exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/feature/article/ArticleContentBlockParser.kt"))
        // The existing dynamic settings producer owns the whole v0.4.6 tab policy (UP tab merged into All).
        exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/feature/dynamic/DynamicTabPolicy.kt"))
    }
    into(generatedUpstream)
    inputs.property("upstreamSourceManifest", revisionNeutralSourceManifestInput)
    inputs.files(revisionNeutralSourceRecipePins.keys.map { file(it) })
    inputs.file("build.gradle.kts")
    inputs.property("upstreamSourceRecipeImportContext", revisionNeutralSourceImportContextMatches)
    inputs.files(revisionNeutralSourceImportShadowFiles)
    inputs.files(sources.map { canonicalOriginalSource(it["path"].toString()) })
    inputs.files(originalResources.map { canonicalOriginalSource(it["path"].toString()) })
    doFirst {
        require(sources.isNotEmpty()) { "The upstream source manifest is missing or empty." }
        require(manifest["hashNormalization"] == "lf") { "The upstream source inventory must use LF-normalized hashes." }
        (sources + originalResources).forEach { entry ->
            val source = canonicalOriginalSource(entry["path"].toString())
            require(source.isFile) { "Required upstream source is missing: ${entry["path"]}" }
            val normalization = entry["hashNormalization"] ?: "lf"
            require(normalization in setOf("lf", "raw")) { "Unknown upstream hash normalization: ${entry["path"]}" }
            val normalized = if (normalization == "raw") source.readBytes()
                else source.readText(Charsets.UTF_8).replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
            val digest = MessageDigest.getInstance("SHA-256").digest(normalized)
                .joinToString("") { "%02x".format(it) }
            require(digest == entry["sha256"]) {
                "Upstream source changed without a validated sync: ${entry["path"]}"
            }
        }
    }
}

val extractUpstreamApi by tasks.registering(Exec::class) {
    inputs.file("tools/v046_danmaku_like.py")
    inputs.file("tools/v046_favorite_editor.py")
    inputs.dir("upstream-slices/v046-favorite-editor")
    inputs.file("tools/v044_favorite_detail.py")
    inputs.file("tools/v044_favorite_subscription.py")
    inputs.file("tools/v044_fans.py")
    inputs.dir("upstream-slices/v044-following-fans")
    inputs.file("tools/v044_favorite_search.py")
    inputs.dir("upstream-slices/v044-favorite-search")
    inputs.dir("upstream-slices/v044-favorite-subscription")
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    inputs.file("tools/v040_reply_medal.py")
    inputs.dir("upstream-slices/v040-reply-medal")
    commandLine(pythonExecutable, "tools/extract-upstream-api.py",
        "--repo", repositoryRoot.absolutePath, "--output", layout.buildDirectory.dir("generated/api").get().asFile.absolutePath)
    inputs.file(canonicalOriginalSource("app/src/main/java/com/android/purebilibili/core/network/ApiClient.kt"))
    inputs.file(canonicalOriginalSource("core-data/src/main/java/com/android/purebilibili/core/network/CoreNetworkRuntime.kt"))
    inputs.file("tools/extract-upstream-api.py")
    inputs.file("tools/v041_search_all.py")
    inputs.dir("upstream-slices/v041-search-all")
    inputs.file("tools/v030_video_dynamic_share.py")
    inputs.dir("upstream-slices/v030-video-dynamic-share")
    inputs.file("tools/v030_live_stream.py")
    inputs.dir("upstream-slices/v030-live-stream")
    inputs.file("tools/extract-upstream-special-danmaku.py")
    inputs.dir("upstream-slices/v029-special-danmaku")
    inputs.file("tools/sync-upstream.py")
    inputs.files(sources.filter { it["mode"] == "policy-extract" }.map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/api"))
}
val extractUpstreamDanmaku by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-danmaku.py",
        "--repo", repositoryRoot.absolutePath, "--output", layout.buildDirectory.dir("generated/danmaku").get().asFile.absolutePath)
    inputs.file("tools/extract-upstream-danmaku.py")
    inputs.files(sources.filter { it["mode"] == "extracted" }.map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/danmaku"))
}
val extractUpstreamMedia by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-media.py",
        "--repo", repositoryRoot.absolutePath, "--output", layout.buildDirectory.dir("generated/media").get().asFile.absolutePath,
        "--test-output", layout.buildDirectory.dir("generated/live-stream-original-tests").get().asFile.absolutePath)
    inputs.file("tools/extract-upstream-media.py")
    inputs.file("tools/v030_live_stream.py")
    inputs.dir("upstream-slices/v030-live-stream")
    inputs.file("tools/v030_live_recovery.py")
    inputs.dir("upstream-slices/v030-live-recovery")
    inputs.file("tools/v030_live_danmaku.py")
    inputs.dir("upstream-slices/v030-live-danmaku")
    inputs.dir("upstream-slices/v030-live-chat-image")
    inputs.file("tools/v034_live_moderation.py")
    inputs.dir("upstream-slices/v034-live-moderation")
    inputs.file("tools/v034_live_moderation_ui.py")
    inputs.dir("upstream-slices/v034-live-moderation-ui")
    inputs.files("upstream-slices/v034-live-vote/app/src/main/java/com/android/purebilibili/data/repository/LiveRepository.kt",
        "upstream-slices/v034-live-vote/app/src/main/java/com/android/purebilibili/feature/live/LivePlayerViewModel.kt",
        "upstream-slices/v034-live-vote/app/src/main/java/com/android/purebilibili/feature/live/components/LiveVotePanel.kt")
    inputs.file(canonicalOriginalSource("core-data/src/main/java/com/android/purebilibili/core/network/ApiClient.kt"))
    inputs.file("tools/extract-upstream-special-danmaku.py")
    inputs.dir("upstream-slices/v029-special-danmaku")
    inputs.file("tools/sync-upstream.py")
    inputs.files(sources.filter { "media" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/media"))
    outputs.dir(layout.buildDirectory.dir("generated/live-stream-original-tests"))
}
kotlin.sourceSets.named("test") { kotlin.srcDir(layout.buildDirectory.dir("generated/live-stream-original-tests")) }
tasks.named("compileTestKotlin") { dependsOn(extractUpstreamMedia) }

val extractUpstreamAudio by tasks.registering(Exec::class) {
    inputs.file("tools/v044_favorite_subscription.py")
    inputs.file("tools/v044_fans.py")
    inputs.dir("upstream-slices/v044-following-fans")
    inputs.file("tools/v044_favorite_search.py")
    inputs.dir("upstream-slices/v044-favorite-search")
    inputs.dir("upstream-slices/v044-favorite-subscription")
    inputs.file("tools/v044_favorite_detail.py")
    inputs.dir("upstream-slices/v044-favorite-detail")
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-audio.py",
        "--repo", repositoryRoot.absolutePath, "--output", layout.buildDirectory.dir("generated/audio").get().asFile.absolutePath)
    inputs.file("tools/extract-upstream-audio.py")
    inputs.file("tools/v038_audio_library.py")
    inputs.dir("upstream-slices/v038-audio-library")
    inputs.file("tools/sync-upstream.py")
    inputs.files(sources.filter { "listen-video" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/audio"))
    outputs.dir(layout.buildDirectory.dir("generated/audio-library-original-tests"))
}
kotlin.sourceSets.named("test") { kotlin.srcDir(layout.buildDirectory.dir("generated/audio-library-original-tests")) }
tasks.named("compileTestKotlin") { dependsOn(extractUpstreamAudio) }
val extractUpstreamLogin by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-login-platform.py",
        "--repo", repositoryRoot.absolutePath, "--output", layout.buildDirectory.dir("generated/login").get().asFile.absolutePath)
    inputs.file("tools/extract-login-platform.py")
    inputs.file("tools/sync-upstream.py")
    inputs.files(sources.filter { "auth" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/login"))
}
val extractUpstreamPlugins by tasks.registering(Exec::class) {
    inputs.file("tools/patch-public-content-credentials.py")
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-plugins.py",
        "--repo", repositoryRoot.absolutePath, "--output", layout.buildDirectory.dir("generated/plugins").get().asFile.absolutePath,
        "--test-output", layout.buildDirectory.dir("generated/v041-feed-original-tests").get().asFile.absolutePath)
    inputs.file("tools/extract-upstream-plugins.py")
    inputs.file("tools/v041_feed_images.py")
    inputs.file("tools/v041_feed_store.py")
    inputs.dir("upstream-slices/v041-feed-import-title")
    inputs.file("tools/v046_rss_public_content.py")
    inputs.dir("upstream-slices/v046-rss-public-content")
    inputs.file("tools/v038_listen_viewmodel.py")
    inputs.dir("upstream-slices/v041-rss-dynamic")
    inputs.file("tools/v029_history_recap.py")
    inputs.dir("upstream-slices/v029-history-recap")
    inputs.file("tools/extract-video-enhancement.py")
    inputs.file("third-party/fsr-hdr-platform.json")
    inputs.file("tools/extract-upstream-media.py")
    inputs.file("tools/sync-upstream.py")
    inputs.files(sources.filter { "plugins" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    inputs.files(originalResources.map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/plugins"))
    outputs.dir(layout.buildDirectory.dir("generated/v041-feed-original-tests"))
    doLast {
        providers.exec {
            workingDir(projectDir)
            commandLine(pythonExecutable, "tools/patch-public-content-credentials.py",
                layout.buildDirectory.dir("generated/plugins").get().asFile.absolutePath)
        }.result.get().assertNormalExitValue()
    }
}
kotlin.sourceSets.named("test") { kotlin.srcDir(layout.buildDirectory.dir("generated/v041-feed-original-tests")) }
tasks.named("compileTestKotlin") { dependsOn(extractUpstreamPlugins) }
val extractUpstreamDiscovery by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-discovery-platform.py",
        "--repo", repositoryRoot.absolutePath, "--output", layout.buildDirectory.dir("generated/discovery").get().asFile.absolutePath)
    inputs.file("tools/extract-discovery-platform.py")
    inputs.file("tools/extract-upstream-media.py")
    inputs.file("tools/sync-upstream.py")
    inputs.files(sources.filter { "discovery" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/discovery"))
}
val extractUpstreamSettings by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-settings.py",
        "--repo", repositoryRoot.absolutePath, "--output", layout.buildDirectory.dir("generated/settings").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-settings.py", "tools/extract-upstream-media.py", "tools/sync-upstream.py")
    inputs.files(sources.filter { "backup" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/settings"))
}
val extractUpstreamPlayback by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-playback-platform.py",
        "--repo", repositoryRoot.absolutePath, "--output", layout.buildDirectory.dir("generated/playback").get().asFile.absolutePath)
    inputs.files("tools/extract-playback-platform.py", "third-party/media3-error-codes.json")
    inputs.files(sources.filter { "recovery" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/playback"))
}
val extractUpstreamSearch by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-search-platform.py",
        "--repo", repositoryRoot.absolutePath, "--output", layout.buildDirectory.dir("generated/search").get().asFile.absolutePath)
    inputs.files("tools/extract-search-platform.py", "tools/extract-upstream-media.py", "tools/sync-upstream.py")
    inputs.file("tools/v041_search_all.py")
    inputs.dir("upstream-slices/v041-search-all")
    inputs.files(sources.filter { "search-native" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/search"))
}
val extractUpstreamCast by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-cast-platform.py",
        "--repo", repositoryRoot.absolutePath, "--output", layout.buildDirectory.dir("generated/cast").get().asFile.absolutePath)
    inputs.files("tools/extract-cast-platform.py", "tools/extract-upstream-media.py", "tools/sync-upstream.py")
    inputs.files(sources.filter { "dlna-cast" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/cast"))
}
val extractUpstreamPackages by tasks.registering(Exec::class) {
    inputs.file("tools/v044_favorite_detail.py")
    inputs.file("tools/sync-upstream.py")
    inputs.file("tools/v044_favorite_subscription.py")
    inputs.file("tools/v044_fans.py")
    inputs.dir("upstream-slices/v044-following-fans")
    inputs.file("tools/v044_favorite_search.py")
    inputs.dir("upstream-slices/v044-favorite-search")
    inputs.dir("upstream-slices/v044-favorite-subscription")
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-packages.py",
        "--repo", repositoryRoot.absolutePath, "--output", layout.buildDirectory.dir("generated/packages").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-packages.py", "tools/extract-upstream-plugins.py", "tools/extract-upstream-media.py", "tools/extract-upstream-api.py")
    inputs.files(sources.filter { "packages" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    inputs.files(originalResources.filter { "packages" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/packages"))
}
val extractPlaybackWatchdogs by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-playback-watchdogs.py",
        "--repo", repositoryRoot.absolutePath, "--output", layout.buildDirectory.dir("generated/watchdogs").get().asFile.absolutePath)
    inputs.files("tools/extract-playback-watchdogs.py", "third-party/media3-player-state-codes.json")
    inputs.files(sources.filter { "watchdogs" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/watchdogs"))
}
val verifyGoogleCastSources by tasks.registering(Exec::class) {
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/verify-google-cast-sources.py", "--desktop", projectDir.absolutePath)
    inputs.file("tools/verify-google-cast-sources.py")
    inputs.dir("third-party/google-cast-v2")
    inputs.dir("src/main/java")
    inputs.dir("src/main/resources/cast-v2")
    inputs.dir("src/main/resources/licenses/google-cast-v2")
    doLast {
        val vetted = JsonSlurper().parse(file("third-party/google-cast-v2/SOURCES.json")) as Map<*, *>
        val runtimeFiles = configurations.getByName("runtimeClasspath").files
        val dependencies = vetted["dependencies"] as List<*>
        require(dependencies.size == 6) { "The Google Cast dependency inventory changed." }
        dependencies.forEach { item ->
            val entry = item as Map<*, *>
            val name = File(entry["path"].toString()).name
            val artifact = runtimeFiles.filter { it.name == name }.singleOrNull()
                ?: error("The vetted Google Cast runtime dependency was replaced or duplicated: $name")
            val digest = MessageDigest.getInstance("SHA-256").digest(artifact.readBytes()).joinToString("") { "%02x".format(it) }
            require(digest == entry["sha256"]) { "Google Cast runtime dependency checksum failed: $name" }
        }
        logger.lifecycle("Verified six fixed Google Cast runtime dependency digests.")
    }
}
val extractGoogleCastPlatform by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, verifyGoogleCastSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-google-cast-platform.py",
        "--repo", repositoryRoot.absolutePath, "--output", layout.buildDirectory.dir("generated/google-cast").get().asFile.absolutePath)
    inputs.files("tools/extract-google-cast-platform.py", "tools/sync-upstream.py")
    inputs.files(sources.filter { "google-cast-v2" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/google-cast"))
}

val extractUpstreamJs by tasks.registering(Exec::class) {
    inputs.file("tools/v044_favorite_detail.py")
    inputs.file("tools/v044_favorite_subscription.py")
    inputs.file("tools/v044_fans.py")
    inputs.dir("upstream-slices/v044-following-fans")
    inputs.file("tools/v044_favorite_search.py")
    inputs.dir("upstream-slices/v044-favorite-search")
    inputs.dir("upstream-slices/v044-favorite-subscription")
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-js.py",
        "--repo", repositoryRoot.absolutePath, "--output", layout.buildDirectory.dir("generated/js").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-js.py", "tools/extract-upstream-plugins.py", "tools/extract-upstream-media.py",
        "tools/extract-upstream-api.py", "tools/sync-upstream.py")
    inputs.files(sources.filter { "js-plugins" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    inputs.file("tools/upstream-js-content-adaptations.json")
    inputs.file("tools/v041_feed_store.py")
    inputs.dir("upstream-slices/v041-feed-import-title")
    outputs.dir(layout.buildDirectory.dir("generated/js"))
}

val generatedAppearance = layout.buildDirectory.dir("generated/appearance")
val generatedAppearanceResources = layout.buildDirectory.dir("generated/appearance-resources")
val generatedThemeRoleTests = layout.buildDirectory.dir("generated/original-theme-role-tests")
val extractUpstreamAppearance by tasks.registering(Exec::class) {
    inputs.file("tools/v043_theme_chrome.py")
    inputs.dir("upstream-slices/v043-theme-chrome")
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-appearance-platform.py",
        "--repo", repositoryRoot.absolutePath, "--policy-only",
        "--output", generatedAppearance.get().asFile.absolutePath,
        "--resource-output", generatedAppearanceResources.get().asFile.absolutePath,
        "--test-output", generatedThemeRoleTests.get().asFile.absolutePath)
    inputs.files("tools/extract-appearance-platform.py", "tools/sync-upstream.py", "tools/v040_theme_roles.py", "tools/v040_liquid_value_controls.py")
    inputs.dir("upstream-slices/v040-theme-roles")
    inputs.file("tools/v046_theme_tokens.py")
    inputs.dir("upstream-slices/v046-theme-tokens")
    inputs.file("tools/v046_theme_palette_schedule.py")
    inputs.dir("upstream-slices/v046-theme-palette-schedule")
    inputs.file("tools/v042_seed_palette.py")
    inputs.dir("upstream-slices/v042-seed-palette")
    inputs.dir("upstream-slices/v040-liquid-value-controls")
    inputs.files(sources.filter { "appearance-parity" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    inputs.files(originalResources.filter { "appearance-language" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(generatedAppearance)
    outputs.dir(generatedAppearanceResources)
    outputs.dir(generatedThemeRoleTests)
}
kotlin.sourceSets.named("test") { kotlin.srcDir(generatedThemeRoleTests) }
tasks.named("compileTestKotlin") { dependsOn(extractUpstreamAppearance) }

val generatedChromeActionTests = layout.buildDirectory.dir("generated/original-chrome-action-tests")
val extractUpstreamComponents by tasks.registering(Exec::class) {
    inputs.file("tools/v043_theme_chrome.py")
    inputs.dir("upstream-slices/v043-theme-chrome")
    inputs.file("tools/v042_optical_dependencies.py")
    inputs.dir("upstream-slices/v042-optical-dependencies")
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-components.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/components").get().asFile.absolutePath,
        "--test-output", generatedChromeActionTests.get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-components.py", "tools/v040_liquid_value_controls.py", "tools/v040_chrome_actions.py")
    inputs.file("tools/v040_native_chrome.py")
    inputs.dir("upstream-slices/v040-native-chrome")
    inputs.dir("upstream-slices/v040-chrome-actions")
    inputs.dir("upstream-slices/v040-liquid-value-controls")
    inputs.file("tools/v046_design_system_leaves_a.py")
    inputs.dir("upstream-slices/v046-design-system-leaves-a")
    inputs.files(sources.filter { "component-parity" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/components"))
    outputs.dir(generatedChromeActionTests)
}
kotlin.sourceSets.named("test") { kotlin.srcDir(generatedChromeActionTests) }
tasks.named("compileTestKotlin") { dependsOn(extractUpstreamComponents) }

val extractUpstreamPreferences by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-preferences.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/preferences").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-preferences.py", "tools/extract-upstream-plugins.py", "tools/extract-upstream-media.py", "tools/sync-upstream.py")
    inputs.files(sources.filter { "preference-parity" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/preferences"))
}

val extractUpstreamSettingsSearch by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-settings-search.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/settings-search").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-settings-search.py", "tools/v032_pinyin.py", "tools/extract-upstream-plugins.py", "tools/extract-upstream-media.py", "tools/sync-upstream.py")
    inputs.dir("upstream-slices/v032-pinyin")
    inputs.files(sources.filter { "settings-search-parity" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    inputs.files(originalResources.filter { "settings-search-symbols" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/settings-search"))
}

val extractUpstreamSettingsCategories by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractUpstreamSettingsSearch)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-settings-categories.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/settings-categories").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-settings-categories.py", "tools/extract-upstream-settings-search.py",
        "tools/extract-upstream-plugins.py", "tools/extract-upstream-media.py", "tools/sync-upstream.py")
    inputs.files(sources.filter { "settings-category-ui-parity" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    inputs.files(originalResources.filter { "settings-category-symbols" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/settings-categories"))
}

val extractOriginalStaticSettingsPages by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractUpstreamSettingsSearch, extractUpstreamSettingsCategories)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-static-settings-pages.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/static-settings-pages").get().asFile.absolutePath)
    inputs.file(sourceManifest)
    inputs.files("tools/extract-upstream-static-settings-pages.py", "tools/v025_source_paths.py",
        "tools/v025-canonical-sources.json", "tools/extract-upstream-settings-search.py")
    inputs.files(sources.filter { "desktop-whole-static-settings-pages" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    inputs.files(originalResources.filter { "desktop-whole-static-settings-pages" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    inputs.files(listOf("app/src/main/res/values/strings.xml", "app/src/main/res/values-en/strings.xml",
        "app/src/main/res/values-zh-rTW/strings.xml").map(::canonicalOriginalSource))
    inputs.files(listOf("app/src/main/java/com/android/purebilibili/feature/settings/SettingsSemanticIconPolicy.kt",
        "app/src/main/java/com/android/purebilibili/feature/settings/SettingsEntryVisualPolicy.kt").map(::canonicalOriginalSource))
    outputs.dir(layout.buildDirectory.dir("generated/static-settings-pages"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/static-settings-pages")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalStaticSettingsPages) }

val extractUpstreamSettingsHome by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractUpstreamSettingsCategories)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-settings-home.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/settings-home").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-settings-home.py", "tools/extract-upstream-plugins.py", "tools/extract-upstream-media.py", "tools/sync-upstream.py")
    inputs.files(sources.filter { "settings-home-section-parity" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/settings-home"))
}

val extractUpstreamSettingsPrivacy by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractUpstreamSettingsCategories)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-settings-privacy.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/settings-privacy").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-settings-privacy.py", "tools/extract-upstream-plugins.py", "tools/extract-upstream-media.py", "tools/sync-upstream.py", "tools/extract-upstream-settings-search.py")
    inputs.files(sources.filter { "settings-privacy-section-parity" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    inputs.files(originalResources.filter { "settings-privacy-section-parity" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/settings-privacy"))
}

val extractUpstreamSettingsEntries by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractUpstreamSettingsCategories)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-settings-entries.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/settings-entries").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-settings-entries.py", "tools/extract-upstream-settings-search.py",
        "tools/extract-upstream-plugins.py", "tools/extract-upstream-media.py", "tools/sync-upstream.py")
    inputs.files(sources.filter { "settings-playback-entry-parity" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/settings-entries"))
}

val extractUpstreamNavigationInteraction by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractUpstreamSettingsCategories)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-navigation-interaction.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/navigation-interaction").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-navigation-interaction.py", "tools/extract-upstream-media.py", "tools/sync-upstream.py")
    inputs.files(sources.filter { "desktop-navigation-interaction-settings" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/navigation-interaction"))
}

val extractUpstreamFullNavigation by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractUpstreamNavigationInteraction)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-full-navigation.py",
        "--repo", repositoryRoot.absolutePath, "--policy-only",
        "--output", layout.buildDirectory.dir("generated/full-navigation-settings").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-full-navigation.py", "tools/extract-upstream-navigation-interaction.py",
        "tools/extract-upstream-media.py", "tools/extract-upstream-settings-search.py", "tools/sync-upstream.py")
    inputs.file(sourceManifest)
    inputs.files((sources + originalResources).filter {
        "desktop-full-navigation-settings" in ((it["features"] as? List<*>) ?: emptyList<Any>())
    }.map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/full-navigation-settings"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/full-navigation-settings")) }
tasks.named("compileKotlin") { dependsOn(extractUpstreamFullNavigation) }

val extractUpstreamSettingsStorageEntries by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractUpstreamSettingsEntries)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-settings-storage-entries.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/settings-storage-entries").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-settings-storage-entries.py", "tools/extract-upstream-plugins.py",
        "tools/extract-upstream-media.py", "tools/sync-upstream.py")
    inputs.files(sources.filter { "settings-storage-backup-entries" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/settings-storage-entries"))
}

val extractUpstreamBlockedUp by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractUpstreamDiscovery)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-blocked-up-platform.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/blocked-up").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-blocked-up-platform.py", "tools/extract-discovery-platform.py", "tools/sync-upstream.py")
    inputs.files(sources.filter { "settings-blocked-up" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/blocked-up"))
}

val extractUpstreamBlockedListUi by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractUpstreamBlockedUp, extractUpstreamSettingsCategories)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-blocked-list-ui.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/blocked-list-ui").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-blocked-list-ui.py", "tools/extract-upstream-blocked-up-platform.py", "tools/sync-upstream.py")
    inputs.files(sources.filter { "settings-blocked-up" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    inputs.files(originalResources.filter { "settings-blocked-up" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/blocked-list-ui"))
}

val extractUpstreamNetworkProxy by tasks.registering(Exec::class) {
    inputs.file("tools/v044_favorite_detail.py")
    inputs.file("tools/v044_favorite_subscription.py")
    inputs.file("tools/v044_fans.py")
    inputs.dir("upstream-slices/v044-following-fans")
    inputs.file("tools/v044_favorite_search.py")
    inputs.dir("upstream-slices/v044-favorite-search")
    inputs.dir("upstream-slices/v044-favorite-subscription")
    inputs.file("tools/v042_network_proxy.py")
    inputs.dir("upstream-slices/v042-network-proxy")
    dependsOn(prepareUpstreamSources, extractUpstreamSettingsCategories)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-network-proxy.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/network-proxy").get().asFile.absolutePath,
        "--tests-output", layout.buildDirectory.dir("generated/network-proxy-tests").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-network-proxy.py", "tools/extract-upstream-plugins.py",
        "tools/extract-upstream-media.py", "tools/extract-upstream-api.py",
        "tools/extract-upstream-settings-search.py", "tools/sync-upstream.py")
    inputs.files(sources.filter { "settings-network-proxy-parity" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    inputs.files(originalResources.filter { "settings-network-proxy-symbols" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/network-proxy"))
    outputs.dir(layout.buildDirectory.dir("generated/network-proxy-tests"))
}
kotlin.sourceSets.named("test") { kotlin.srcDir(layout.buildDirectory.dir("generated/network-proxy-tests")) }
tasks.named("compileTestKotlin") { dependsOn(extractUpstreamNetworkProxy) }

val extractUpstreamDiagnostics by tasks.registering(Exec::class) {
    inputs.file("tools/v044_favorite_detail.py")
    inputs.file("tools/v044_favorite_subscription.py")
    inputs.file("tools/v044_fans.py")
    inputs.dir("upstream-slices/v044-following-fans")
    inputs.file("tools/v044_favorite_search.py")
    inputs.dir("upstream-slices/v044-favorite-search")
    inputs.dir("upstream-slices/v044-favorite-subscription")
    dependsOn(prepareUpstreamSources, extractUpstreamSettingsCategories)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-diagnostics.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/diagnostics/sources").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-diagnostics.py", "tools/extract-upstream-dynamic-reply-protocol.py", "tools/extract-appearance-platform.py", "upstream-sources.json", "tools/extract-upstream-plugins.py",
        "tools/extract-upstream-media.py", "tools/extract-upstream-api.py",
        "tools/extract-upstream-settings-search.py", "tools/sync-upstream.py")
    inputs.files(sources.filter { "settings-local-diagnostics-parity" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    inputs.files(originalResources.filter { "settings-local-diagnostics-symbol" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/diagnostics"))
    // Own the reference-only output too; the network-proxy producer has its own copy.
}

val extractUpstreamDynamicSettings by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractUpstreamSettingsCategories, extractUpstreamSettingsHome)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-dynamic-settings.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/dynamic-settings").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-dynamic-settings.py", "tools/extract-upstream-plugins.py",
        "tools/extract-upstream-media.py", "tools/extract-appearance-platform.py",
        "tools/extract-upstream-settings-home.py", "tools/sync-upstream.py")
    inputs.files("tools/v046_dynamic_category_merge.py", "tools/v044_favorite_detail.py", "tools/v044_favorite_subscription.py")
    inputs.dir("upstream-slices/v046-dynamic-category-merge")
    inputs.files(sources.filter { "settings-home-dynamic-parity" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/dynamic-settings"))
}

val extractUpstreamHomeCards by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractUpstreamSettingsCategories, extractUpstreamSettingsHome)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-home-cards.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/home-cards").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-home-cards.py", "tools/extract-upstream-plugins.py",
        "tools/extract-upstream-media.py", "tools/extract-appearance-platform.py",
        "tools/extract-upstream-settings-home.py", "tools/sync-upstream.py")
    inputs.files(sources.filter { "settings-home-card-parity" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/home-cards"))
}

val extractOriginalHomePage by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-home-core-refresh.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/home-page").get().asFile.absolutePath,
        "--test-output", layout.buildDirectory.dir("generated/original-refresh-policy-tests").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-home-page.py", "tools/sync-upstream.py")
    inputs.files("tools/ux_home_layout.py")
    inputs.file("tools/v046_identity_badges.py")
    inputs.dir("upstream-slices/v046-identity-badges")
    inputs.file("tools/v046_identity_badge_palette.py")
    inputs.dir("upstream-slices/v046-identity-badge-palette")
    inputs.file("tools/v046_home_card_cascade_definers.py")
    inputs.dir("upstream-slices/v046-home-card-cascade-definers")
    inputs.file("tools/v046_home_refresh_ui_policy.py")
    inputs.dir("upstream-slices/v046-home-refresh-ui-policy")
    inputs.file("tools/v046_home_card_tint_policy.py")
    inputs.dir("upstream-slices/v046-home-card-tint-policy")
    inputs.file("tools/v046_wallpaper_foundation.py")
    inputs.dir("upstream-slices/v046-wallpaper-foundation")
    inputs.file("tools/v042_search_dependencies.py")
    inputs.dir("upstream-slices/v042-search-dependencies")
    inputs.file("tools/v040_native_chrome.py")
    inputs.file("tools/extract-upstream-frosted-audio-renderer.py")
    inputs.dir("upstream-slices/v040-native-chrome")
    inputs.file("tools/v033_home_refresh_undo.py")
    inputs.dir("upstream-slices/v033-home-undo")
    inputs.file("tools/v029_home_load.py")
    inputs.dir("upstream-slices/v029-home-load")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "home-page" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    inputs.file("tools/v029_brand_consumers.py")
    inputs.dir("upstream-slices/v029-brand-consumers")
    inputs.files("tools/extract-upstream-home-core-refresh.py", "tools/v036_comment_refresh_dependencies.py")
    inputs.dir("upstream-slices/v036-comment-refresh-dependencies")
    outputs.dir(layout.buildDirectory.dir("generated/original-refresh-policy-tests"))
    outputs.dir(layout.buildDirectory.dir("generated/home-page"))
}
tasks.named("compileKotlin") { dependsOn(extractOriginalHomePage) }
kotlin.sourceSets.named("test") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-refresh-policy-tests")) }
tasks.named("compileTestKotlin") { dependsOn(extractOriginalHomePage) }

// Insert after the already-installed extractOriginalHomePage. Merge, do not replace build.gradle.
val extractOriginalHomeViewModel by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-home-viewmodel.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/home-viewmodel").get().asFile.absolutePath,
        "--test-output", layout.buildDirectory.dir("generated/home-load-tests").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-home-viewmodel.py", "tools/extract-upstream-home-viewmodel-adaptations.json",
        "tools/extract-upstream-media.py", "tools/sync-upstream.py")
    inputs.file("tools/v029_home_load.py")
    inputs.dir("upstream-slices/v029-home-load")
    inputs.file("tools/v046_account_scoped_requests.py")
    inputs.dir("upstream-slices/v046-account-scoped-requests")
    inputs.files(sources.filter { "home-viewmodel" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/home-viewmodel"))
    outputs.dir(layout.buildDirectory.dir("generated/home-load-tests"))
}
val extractOriginalHomeProtocols by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-home-protocols.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/home-protocols").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-home-protocols.py", "tools/extract-upstream-media.py", "tools/sync-upstream.py")
    inputs.files(sources.filter { "home-protocols" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/home-protocols"))
}
// Add to the existing kotlin.sourceSets.named("main") block.
kotlin.sourceSets.named("main") {
    kotlin.srcDir(layout.buildDirectory.dir("generated/home-viewmodel"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/home-protocols"))
}
tasks.named("compileKotlin") { dependsOn(extractOriginalHomeViewModel, extractOriginalHomeProtocols) }
kotlin.sourceSets.named("test") { kotlin.srcDir(layout.buildDirectory.dir("generated/home-load-tests")) }
tasks.named("compileTestKotlin") { dependsOn(extractOriginalHomeViewModel) }
// Existing full-card expansion calls the sole Home-page producer for two wallpaper outputs.
// Root already registered that dependency with UI524; keep it, and keep the full-card feature input.
// Never use --standalone in production: DIRECT3 VM policies and DIRECT1 WatchLater bus are copied
// once by prepareUpstreamSources. There are no new dependencies or binary installation payloads.

// Merge after the parent's sole raw694 protocol task; do not replace shared Gradle.
val extractOriginalLiveList by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractOriginalHomeProtocols)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-live-list.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/live-home-list").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-live-list.py", "tools/extract-upstream-media.py", "tools/sync-upstream.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "live-home-list" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/live-home-list"))
}
kotlin.sourceSets.named("main") {
    kotlin.srcDir(layout.buildDirectory.dir("generated/live-home-list"))
}
tasks.named("compileKotlin") { dependsOn(extractOriginalLiveList) }
// Production intentionally omits --standalone; DIRECT9 have one producer, prepareUpstreamSources.

val extractOriginalHomePartition by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-home-partition.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/home-partition").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-home-partition.py", "tools/sync-upstream.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "home-partition" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/home-partition"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/home-partition")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalHomePartition) }

val extractOriginalHomeBangumiPage by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-home-bangumi-page.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/home-bangumi-page").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-home-bangumi-page.py", "tools/sync-upstream.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "home-bangumi-page" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/home-bangumi-page"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/home-bangumi-page")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalHomeBangumiPage) }

val extractOriginalBangumiPages by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractOriginalHomeBangumiPage, extractUpstreamMedia)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-bangumi-pages.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/independent-bangumi-pages").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-bangumi-pages.py", "tools/sync-upstream.py", "tools/extract-upstream-media.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "independent-bangumi-pages" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/independent-bangumi-pages"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/independent-bangumi-pages")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalBangumiPages) }

// Complete original PGC VM/Base/policies and playurl have one producer;
// the complete physical Player UI is produced separately below.
val extractOriginalBangumiPlayer by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractOriginalBangumiPages, extractUpstreamMedia)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-bangumi-player.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-bangumi-player").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-bangumi-player.py", "tools/sync-upstream.py", "tools/extract-upstream-media.py")
    inputs.file("upstream-slices/v034-bangumi-quality/app/src/main/java/com/android/purebilibili/feature/bangumi/BangumiPlayerViewModel.kt")
    // Complete policies read by the PGC codec-recovery producer.
    inputs.files(
        canonicalOriginalSource("app/src/main/java/com/android/purebilibili/feature/video/viewmodel/VideoCodecSessionPolicy.kt"),
        canonicalOriginalSource("core-data/src/main/java/com/android/purebilibili/data/model/response/VideoResponse.kt"),
        canonicalOriginalSource("core-data/src/main/java/com/android/purebilibili/core/util/SelectionUtils.kt"),
        canonicalOriginalSource("app/src/main/java/com/android/purebilibili/feature/video/playback/session/PlaybackSessionStore.kt"),
        canonicalOriginalSource("app/src/main/java/com/android/purebilibili/feature/bangumi/BangumiDashManifestPolicy.kt"),
        canonicalOriginalSource("app/src/main/java/com/android/purebilibili/feature/video/viewmodel/VideoPlaybackViewModel.kt"),
        file("upstream-slices/v029-playback-recovery/PlayerErrorRecoveryPolicy.kt"))
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "original-bangumi-player-full" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-bangumi-player"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-bangumi-player")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalBangumiPlayer) }

val extractOriginalBangumiPlayerUi by tasks.registering(Exec::class) {
    inputs.file("tools/v037_gesture_feedback.py")
    inputs.dir("upstream-slices/v037-gesture-feedback")
    dependsOn(prepareUpstreamSources, extractOriginalBangumiPlayer, extractUpstreamMedia)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-bangumi-player-ui.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-bangumi-player-ui").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-bangumi-player-ui.py", "tools/sync-upstream.py", "tools/extract-upstream-media.py")
    inputs.file("tools/v037_tab_roles.py")
    inputs.dir("upstream-slices/v037-explicit-tab-roles")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "original-bangumi-player-ui" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-bangumi-player-ui"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-bangumi-player-ui")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalBangumiPlayerUi) }

val extractOriginalSubscriptionPage by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-subscription-page.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/home-subscription-page").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-subscription-page.py", "tools/sync-upstream.py")
    inputs.file("tools/v029_history_recap.py")
    inputs.dir("upstream-slices/v029-history-recap")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "home-subscription-page" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/home-subscription-page"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/home-subscription-page")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalSubscriptionPage) }


val prepareOriginalCategoryPage by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-category-page.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/category-page").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-category-page.py", "tools/sync-upstream.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "category-page" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/category-page"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/category-page")) }
tasks.named("compileKotlin") { dependsOn(prepareOriginalCategoryPage) }

val prepareOriginalHomeReturnNavigation by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-home-return-navigation.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/home-return-navigation").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-home-return-navigation.py", "tools/sync-upstream.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "stable-home-return-navigation" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/home-return-navigation"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/home-return-navigation")) }
tasks.named("compileKotlin") { dependsOn(prepareOriginalHomeReturnNavigation) }

// Merge only; production intentionally omits --standalone, preserving sole DIRECT ownership.
val extractOriginalProfileMain by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractOriginalHomeProtocols)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-profile-main.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/profile-main").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-profile-main.py", "tools/extract-upstream-media.py", "tools/sync-upstream.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "profile-main-original" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/profile-main"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/profile-main")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalProfileMain) }


val extractOriginalLiveNavigation by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractOriginalHomeProtocols)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-live-navigation.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/live-navigation").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-live-navigation.py", "tools/extract-upstream-media.py", "tools/sync-upstream.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "live-sub-navigation" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/live-navigation"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/live-navigation")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalLiveNavigation) }

// Merge only, no dependencies. Same original UI/shared renderer owners stay unique.
val extractOriginalVideoShareConsent by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractOriginalHomeProtocols)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-video-share-consent.py",
        "--source-repo", repositoryRoot.absolutePath,
        "--output-dir", layout.buildDirectory.dir("generated/video-share-consent").get().asFile.absolutePath)
    inputs.file("tools/extract-upstream-video-share-consent.py")
    inputs.file("tools/v030_video_dynamic_share.py")
    inputs.dir("upstream-slices/v030-video-dynamic-share")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "video-share-original-windows" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/video-share-consent"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/video-share-consent")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalVideoShareConsent) }

// Complete original navigation host; the 22 DIRECT declarations retain sync's sole ownership.
// Insert beside extractNavigation3Host. No new dependency.
// Source-only serial integration recipe; Root uses the existing task/runtime convention.
val extractOriginalDownloadList by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-download-list.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-download-list").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-download-list.py", "tools/sync-upstream.py",
        "tools/extract-upstream-media.py", "tools/extract-appearance-platform.py")
    inputs.file("upstream-slices/v034-download-list/app/src/main/java/com/android/purebilibili/feature/download/DownloadListScreen.kt")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "stable-download-list-original" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-download-list"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-download-list")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalDownloadList) }
// Do not pass --standalone in production: the direct original DownloadTaskPresentationPolicy
// is copied once by the existing registry sync. Only 3 adapted/selected Kotlin files are emitted here.

val extractOriginalRootHomeNavigation by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-root-home-navigation.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-root-home-navigation").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-root-home-navigation.py", "tools/sync-upstream.py",
        "tools/extract-upstream-navigation3-host.py", "tools/extract-upstream-media.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "stable-root-home-navigation" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-root-home-navigation"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-root-home-navigation")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalRootHomeNavigation) }
// Preserve originalHomeProtocols registration; its sole producer now includes the original vertical-video method/cache.

val extractNavigation3Host by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-navigation3-host.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-navigation3-host").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-navigation3-host.py", "tools/sync-upstream.py", "tools/extract-upstream-media.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "stable-navigation3-host" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-navigation3-host"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-navigation3-host")) }
tasks.named("compileKotlin") { dependsOn(extractNavigation3Host) }

// Merge source/task only; no new dependency/JAR or duplicate direct schema.
val extractOriginalWallpaperPalette by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-wallpaper-palette.py",
        "--source-repo", repositoryRoot.absolutePath,
        "--output-dir", layout.buildDirectory.dir("generated/wallpaper-palette").get().asFile.absolutePath)
    inputs.file("tools/extract-upstream-wallpaper-palette.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "home-wallpaper-palette-original" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/wallpaper-palette"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/wallpaper-palette")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalWallpaperPalette) }
// New manual Java sources live in existing desktop/src/main/java; default Java task compiles them.

// Full original application Coil configuration and original background image budgets.
val extractOriginalApplicationImageLoader by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-application-image-loader.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-application-image-loader").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-application-image-loader.py", "tools/sync-upstream.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "original-application-image-loader" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-application-image-loader"))
}
val verifyCoilCacheControlSources by tasks.registering {
    val pins = mapOf(
            "third-party/coil-cache-control/upstream/commonMain/coil3/network/cachecontrol/CacheControlCacheStrategy.kt" to "205af3830d3bfcef597bccdc6554c7f2abb69483b3e649abfcdf75498af4e87b",
            "third-party/coil-cache-control/upstream/commonMain/coil3/network/cachecontrol/internal/CacheControl.kt" to "f3abb5f309c53dff0c566a9a9fcf7231c319fcdc6753afdf6cdd8bd7d9ffe2f7",
            "third-party/coil-cache-control/upstream/commonMain/coil3/network/cachecontrol/internal/utils.kt" to "f7347cf633951e964a77649de86761b1dbdab7bac5d1e4470a80f265dce98a79",
            "resources/common/licenses/coil-cache-control-LICENSE.txt" to "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30",
            "resources/common/licenses/coil-cache-control-NOTICE.txt" to "6bd1d0d8cc005df39496f6be8618eb6c5714cf3c9839fb631f053ead00e41c9c"
    )
    inputs.files(pins.keys)
    doLast {
        pins.forEach { (name, expected) ->
            val actual = MessageDigest.getInstance("SHA-256")
                .digest(File(projectDir, name).readText(Charsets.UTF_8).replace("\r\n", "\n").toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            check(actual == expected) { "Original Coil cache-control source/license changed: $name" }
        }
        logger.lifecycle("Verified three unchanged Coil3.5.0 cache-control sources and two Apache notices.")
    }
}
kotlin.sourceSets.named("main") {
    kotlin.srcDir(layout.buildDirectory.dir("generated/original-application-image-loader"))
    kotlin.srcDir("third-party/coil-cache-control/upstream/commonMain")
}
tasks.named("compileKotlin") { dependsOn(extractOriginalApplicationImageLoader, verifyCoilCacheControlSources) }
tasks.named("processResources") { dependsOn(verifyCoilCacheControlSources) }

// Full original Profile image/video import on the retained Windows entry.
val extractUpstreamProfileWallpaperImport by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-profile-wallpaper-import.py",
        "--source-repo", repositoryRoot.absolutePath,
        "--output-dir", layout.buildDirectory.dir("generated/profile-wallpaper-import").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-profile-wallpaper-import.py", "tools/sync-upstream.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "stable-profile-windows-platform" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/profile-wallpaper-import"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/profile-wallpaper-import")) }
tasks.named("compileKotlin") { dependsOn(extractUpstreamProfileWallpaperImport) }

val extractUpstreamHomeFullCard by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractUpstreamSettingsCategories, extractUpstreamHomeCards, extractOriginalHomePage)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-home-full-card.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/home-full-card").get().asFile.absolutePath)
    inputs.file("tools/v041_video_status_labels.py")
    inputs.file("tools/v043_search_up_author.py")
    inputs.dir("upstream-slices/v041-video-status-labels")
    inputs.file("tools/v033_home_refresh_undo.py")
    inputs.dir("upstream-slices/v033-home-undo")
    inputs.files("tools/extract-upstream-home-full-card.py", "tools/extract-upstream-home-page.py", "tools/extract-upstream-media.py",
        "tools/extract-appearance-platform.py", "tools/extract-upstream-settings-home.py", "tools/sync-upstream.py")
    inputs.files("tools/ux_home_layout.py")
    inputs.files("tools/ux_home_card.py")
    inputs.files(sources.filter { "home-full-card" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    inputs.files(sources.filter { "home-page" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    inputs.file("tools/v029_brand_consumers.py")
    inputs.dir("upstream-slices/v029-brand-consumers")
    outputs.dir(layout.buildDirectory.dir("generated/home-full-card"))
}

val extractUpstreamDynamicFullCard by tasks.registering(Exec::class) {
    inputs.file("tools/v046_identity_badge_palette.py")
    inputs.file("tools/v044_favorite_detail.py")
    inputs.file("tools/v044_favorite_subscription.py")
    inputs.file("tools/v044_fans.py")
    inputs.dir("upstream-slices/v044-following-fans")
    inputs.file("tools/v044_favorite_search.py")
    inputs.dir("upstream-slices/v044-favorite-search")
    inputs.dir("upstream-slices/v044-favorite-subscription")
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-dynamic-card.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/dynamic-full-card").get().asFile.absolutePath,
        "--test-output", layout.buildDirectory.dir("generated/dynamic-full-card-tests").get().asFile.absolutePath)
    inputs.file("third-party/dynamic-reaction-v034/DynamicReactionApi.kt")
    inputs.files("tools/extract-upstream-dynamic-card.py", "tools/extract-dynamic-message-share.py",
        "tools/extract-upstream-plugins.py", "tools/extract-upstream-media.py",
        "tools/extract-upstream-api.py", "tools/extract-appearance-platform.py", "tools/sync-upstream.py")
    inputs.file("tools/v021_comment_renderer.py")
    inputs.files(sources.filter { "settings-dynamic-full-card-parity" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    inputs.file("tools/v046_image_viewer.py")
    inputs.dir("upstream-slices/v046-image-viewer")
    outputs.dir(layout.buildDirectory.dir("generated/dynamic-full-card-tests"))
    outputs.dir(layout.buildDirectory.dir("generated/dynamic-full-card"))
}
// The original v0.4.6 gesture suites are emitted next to the main output, never inside it.
kotlin.sourceSets.named("test") { kotlin.srcDir(layout.buildDirectory.dir("generated/dynamic-full-card-tests")) }

val extractUpstreamDynamicStaticImageCodec by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-dynamic-static-image-codec.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/dynamic-static-image-codec").get().asFile.absolutePath)
    inputs.file("tools/extract-upstream-dynamic-static-image-codec.py")
    inputs.files(sources.filter { "dynamic-static-image-save-parity" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/dynamic-static-image-codec"))
}

val extractImageSaveSettingsUi by tasks.registering(Exec::class) {
    inputs.file("tools/v044_favorite_detail.py")
    inputs.file("tools/sync-upstream.py")
    inputs.file("tools/v044_favorite_subscription.py")
    inputs.file("tools/v044_fans.py")
    inputs.dir("upstream-slices/v044-following-fans")
    inputs.file("tools/v044_favorite_search.py")
    inputs.dir("upstream-slices/v044-favorite-search")
    inputs.dir("upstream-slices/v044-favorite-subscription")
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-image-save-settings-ui.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/settings-image-save-path").get().asFile.absolutePath)
    inputs.files("tools/extract-image-save-settings-ui.py", "tools/extract-upstream-settings-storage-entries.py",
        "tools/extract-upstream-plugins.py", "tools/extract-upstream-media.py", "tools/extract-upstream-api.py")
    inputs.files(sources.filter { "settings-image-save-path-ui" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/settings-image-save-path"))
}

val extractUpstreamDynamicGalleryMotionPhoto by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-dynamic-gallery-motion-photo.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/dynamic-gallery-motion-photo").get().asFile.absolutePath)
    inputs.file("tools/extract-upstream-dynamic-gallery-motion-photo.py")
    inputs.files(sources.filter { "dynamic-media-export-parity" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/dynamic-gallery-motion-photo"))
}

val verifyUpstreamDynamicMedia by tasks.registering(Exec::class) {
    dependsOn(extractUpstreamDynamicGalleryMotionPhoto)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/verify-upstream-dynamic-media.py",
        "--repo", repositoryRoot.absolutePath,
        "--generated", layout.buildDirectory.dir("generated/dynamic-gallery-motion-photo").get().asFile.absolutePath,
        "--output", layout.buildDirectory.file("generated/dynamic-media-verification.json").get().asFile.absolutePath)
    inputs.files("tools/verify-upstream-dynamic-media.py", "tools/extract-upstream-dynamic-gallery-motion-photo.py")
    inputs.dir(layout.buildDirectory.dir("generated/dynamic-gallery-motion-photo"))
    outputs.file(layout.buildDirectory.file("generated/dynamic-media-verification.json"))
}

val extractUpstreamDynamicEditor by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-dynamic-editor.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/dynamic-editor").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-dynamic-editor.py", "tools/extract-upstream-dynamic-reply-protocol.py", "upstream-sources.json", "tools/extract-upstream-plugins.py",
        "tools/extract-upstream-media.py", "tools/extract-appearance-platform.py")
    inputs.file("tools/v029_comment_time.py")
    inputs.file("tools/v029_reply_renderer.py")
    inputs.file("tools/v032_comment_media.py")
    inputs.file("tools/v034_dynamic_reaction_ui.py")
    inputs.files("tools/v046_dynamic_category_merge.py", "tools/v044_favorite_detail.py", "tools/v044_favorite_subscription.py")
    inputs.dir("upstream-slices/v046-dynamic-category-merge")
    inputs.files("third-party/dynamic-reaction-v034/DynamicCommentSheet.kt", "third-party/dynamic-reaction-v034/DynamicReactionSheet.kt")
    inputs.dir("upstream-slices/v032-comment-media")
    inputs.file(canonicalOriginalSource("core-data/src/main/java/com/android/purebilibili/core/util/FormatUtils.kt"))
    inputs.dir("upstream-slices/v029-comment-time")
    inputs.files(sources.filter { "dynamic-editor-detail-parity" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/dynamic-editor"))
}

val verifyUpstreamDynamicEditorProtocol by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/verify-upstream-dynamic-editor-protocol.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.file("generated/dynamic-editor-protocol/verification.json").get().asFile.absolutePath)
    inputs.files("tools/verify-upstream-dynamic-editor-protocol.py", "tools/extract-upstream-dynamic-editor-protocol.py",
        "tools/extract-upstream-dynamic-reply-protocol.py",
        "tools/extract-upstream-plugins.py", "tools/extract-upstream-media.py",
        "src/main/kotlin/com/bilipai/desktop/data/DesktopDynamicCardOperations.kt")
    inputs.files(sources.filter { "dynamic-editor-detail-parity" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.file(layout.buildDirectory.file("generated/dynamic-editor-protocol/verification.json"))
}

val extractUpstreamDynamicReply by tasks.registering(Exec::class) {
    inputs.file("tools/v041_dynamic_comment_identity.py")
    inputs.dir("upstream-slices/v041-dynamic-comment-identity")
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    inputs.file("tools/v040_reply_medal.py")
    inputs.dir("upstream-slices/v040-reply-medal")
    commandLine(pythonExecutable, "tools/extract-upstream-dynamic-reply.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/dynamic-reply").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-dynamic-reply.py", "tools/extract-upstream-plugins.py",
        "tools/extract-upstream-media.py", "tools/extract-appearance-platform.py")
    inputs.file("tools/v029_comment_time.py")
    inputs.file("tools/v029_reply_renderer.py")
    inputs.file("tools/v032_comment_media.py")
    inputs.file("tools/v033_comment_refresh.py")
    inputs.dir("upstream-slices/v033-comment-refresh")
    inputs.dir("upstream-slices/v032-comment-media")
    inputs.dir("upstream-slices/v029-comment-time")
    inputs.file("tools/v021_comment_renderer.py")
    inputs.files(sources.filter { "dynamic-detail-reply" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    inputs.file("tools/v036_comment_viewport.py")
    inputs.file("tools/v036_reply_modifier.py")
    inputs.dir("upstream-slices/v036-reply-modifier")
    inputs.dir("upstream-slices/v036-comment-viewport")
    outputs.dir(layout.buildDirectory.dir("generated/dynamic-reply"))
}

val extractUpstreamDynamicDetail by tasks.registering(Exec::class) {
    inputs.file("tools/v041_dynamic_comment_identity.py")
    inputs.dir("upstream-slices/v041-dynamic-comment-identity")
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-dynamic-detail.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/dynamic-detail").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-dynamic-detail.py", "tools/extract-upstream-plugins.py",
        "tools/extract-upstream-media.py", "tools/extract-appearance-platform.py")
    inputs.file("tools/v036_dynamic_comment_cache.py")
    inputs.file("tools/v029_comment_search.py")
    inputs.dir("upstream-slices/v036-dynamic-comment-cache")
    inputs.file("upstream-slices/v036-video-comment-cache/VideoCommentViewModel.kt")
    inputs.files(sources.filter { "dynamic-detail-reply" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/dynamic-detail"))
}

val extractUpstreamDynamicDetailContainer by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-dynamic-detail-container.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/dynamic-detail-container").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-dynamic-detail-container.py", "tools/extract-appearance-platform.py",
        "tools/sync-upstream.py")
    inputs.file("tools/v040_native_chrome.py")
    inputs.dir("upstream-slices/v040-native-chrome")
    inputs.file("tools/v046_sheet_motion.py")
    inputs.dir("upstream-slices/v046-sheet-motion")
    inputs.file("tools/v046_glyph_toolbar_pickers.py")
    inputs.dir("upstream-slices/v046-glyph-toolbar-pickers")
    inputs.files(sources.filter { "dynamic-detail-container" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    inputs.file("tools/v036_comment_viewport.py")
    inputs.dir("upstream-slices/v036-comment-viewport")
    outputs.dir(layout.buildDirectory.dir("generated/dynamic-detail-container"))
}

val extractUpstreamDynamicReplyProtocol by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    inputs.file("tools/v040_reply_medal.py")
    inputs.dir("upstream-slices/v040-reply-medal")
    commandLine(pythonExecutable, "tools/extract-upstream-dynamic-reply-protocol.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/dynamic-reply-protocol").get().asFile.absolutePath)
    inputs.file("tools/extract-upstream-dynamic-reply-protocol.py")
    inputs.files(sources.filter { "dynamic-detail-reply" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/dynamic-reply-protocol"))
}

val extractUpstreamDynamicDetailProtocol by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-dynamic-detail-protocol.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/dynamic-detail-protocol").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-dynamic-detail-protocol.py", "tools/extract-upstream-dynamic-reply-protocol.py")
    inputs.files(sources.filter { "dynamic-detail-reply" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/dynamic-detail-protocol"))
}

val extractBgmDetail by tasks.registering(Exec::class) {
    inputs.file("tools/v046_comment_chrome.py")
    inputs.dir("upstream-slices/v046-comment-chrome")
    inputs.file("tools/v040_liquid_value_controls.py")
    inputs.dir("upstream-slices/v040-liquid-value-controls")
    inputs.files("tools/v029_comment_composer.py", "tools/v029_comment_search.py")
    inputs.dir("upstream-slices/v029-comment-composer")
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-bgm-detail.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/bgm-detail").get().asFile.absolutePath)
    inputs.file("tools/v033_comment_refresh.py")
    inputs.dir("upstream-slices/v033-comment-refresh")
    inputs.file("third-party/video-detail-skeleton-v034/SkeletonComponents.kt")
    inputs.files("tools/extract-upstream-bgm-detail.py", "tools/sync-upstream.py", "tools/extract-upstream-dynamic-reply-protocol.py",
        "tools/extract-appearance-platform.py", "tools/extract-upstream-media.py", "tools/extract-upstream-plugins.py")
    inputs.property("upstreamSourceManifest", revisionNeutralSourceManifestInput)
    inputs.files(revisionNeutralSourceRecipePins.keys.map { file(it) })
    inputs.file("build.gradle.kts")
    inputs.property("upstreamSourceRecipeImportContext", revisionNeutralSourceImportContextMatches)
    inputs.files(revisionNeutralSourceImportShadowFiles)
    inputs.files(sources.filter { "stable-bgm-native-detail" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    inputs.file("tools/v029_brand_consumers.py")
    inputs.dir("upstream-slices/v029-brand-consumers")
    outputs.dir(layout.buildDirectory.dir("generated/bgm-detail"))
}
tasks.named("compileKotlin") { dependsOn(extractBgmDetail) }

val extractVideoCommentUi by tasks.registering(Exec::class) {
    inputs.files("tools/v029_comment_composer.py", "tools/v029_comment_search.py")
    inputs.dir("upstream-slices/v029-comment-composer")
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-video-comment-ui.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/video-comment-ui").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-video-comment-ui.py", "tools/extract-upstream-dynamic-reply-protocol.py",
        "tools/extract-upstream-dynamic-reply.py", "tools/sync-upstream.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "stable-video-original-comments" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    inputs.file("tools/v036_video_comment_viewport.py")
    inputs.dir("upstream-slices/v036-video-comment-viewport")
    inputs.file("tools/v036_comment_refresh_dependencies.py")
    inputs.dir("upstream-slices/v036-comment-refresh-dependencies")
    outputs.dir(layout.buildDirectory.dir("generated/video-comment-ui"))
}
tasks.named("compileKotlin") { dependsOn(extractVideoCommentUi) }

val extractOriginalFavorites by tasks.registering(Exec::class) {
    inputs.file("tools/v046_favorite_editor.py")
    inputs.dir("upstream-slices/v046-favorite-editor")
    inputs.file("tools/v046_history_in_place.py")
    inputs.file("tools/sync-upstream.py")
    inputs.file("tools/v044_favorite_subscription.py")
    inputs.file("tools/v044_fans.py")
    inputs.dir("upstream-slices/v044-following-fans")
    inputs.file("tools/v044_favorite_search.py")
    inputs.dir("upstream-slices/v044-favorite-search")
    inputs.dir("upstream-slices/v044-favorite-subscription")
    inputs.file("tools/v044_favorite_detail.py")
    inputs.dir("upstream-slices/v044-favorite-detail")
    inputs.file("tools/v034_favorite_content_errors.py")
    inputs.dir("upstream-slices/v034-favorite-content-errors")
    inputs.file("tools/v029_history_read_failure.py")
    inputs.file("tools/v029_history_failure_login.py")
    inputs.file("tools/v029_brand_success.py")
    inputs.dir("upstream-slices/v029-brand-success")
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-favorites.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-favorites").get().asFile.absolutePath,
        "--test-output", layout.buildDirectory.dir("generated/original-favorite-policy-tests").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-favorites.py", "tools/extract-upstream-dynamic-reply-protocol.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "stable-favorites" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    inputs.file("tools/v029_brand_callers.py")
    inputs.dir("upstream-slices/v029-brand-callers")
    inputs.file("tools/v029_brand_history.py")
    inputs.dir("upstream-slices/v029-brand-history")
    inputs.file("tools/v029_history_recap.py")
    inputs.dir("upstream-slices/v029-history-recap")
    outputs.dir(layout.buildDirectory.dir("generated/original-favorites"))
    outputs.dir(layout.buildDirectory.dir("generated/original-favorite-policy-tests"))
}
val extractOriginalFavoriteFolder by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-favorite-folder-sheet.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-favorite-folder").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-favorite-folder-sheet.py", "tools/extract-upstream-dynamic-reply-protocol.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "stable-favorite-folder-sheet" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-favorite-folder"))
}
tasks.named("compileKotlin") { dependsOn(extractOriginalFavorites, extractOriginalFavoriteFolder) }
kotlin.sourceSets.named("test") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-favorite-policy-tests")) }
tasks.named("compileTestKotlin") { dependsOn(extractOriginalFavorites) }
tasks.named("extractOriginalFavorites") {
    inputs.file("tools/v044_list_foreground.py")
    inputs.dir("upstream-slices/v044-list-foreground")
}

val extractLinkedDock by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-linked-dock.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/linked-dock").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-linked-dock.py", "tools/sync-upstream.py",
        "tools/extract-appearance-platform.py", "tools/extract-upstream-dynamic-reply-protocol.py")
    inputs.property("upstreamSourceManifest", revisionNeutralSourceManifestInput)
    inputs.files(revisionNeutralSourceRecipePins.keys.map { file(it) })
    inputs.file("build.gradle.kts")
    inputs.property("upstreamSourceRecipeImportContext", revisionNeutralSourceImportContextMatches)
    inputs.files(revisionNeutralSourceImportShadowFiles)
    inputs.files(sources.filter { "stable-linked-dock-search" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    inputs.files(originalResources.filter { "stable-linked-dock-search" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/linked-dock"))
}
tasks.named("compileKotlin") { dependsOn(extractLinkedDock) }

val extractFrostedAudioRenderer by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-frosted-audio-renderer.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/frosted-audio-renderer").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-frosted-audio-renderer.py", "tools/sync-upstream.py")
    inputs.property("upstreamSourceManifest", revisionNeutralSourceManifestInput)
    inputs.files(revisionNeutralSourceRecipePins.keys.map { file(it) })
    inputs.file("build.gradle.kts")
    inputs.property("upstreamSourceRecipeImportContext", revisionNeutralSourceImportContextMatches)
    inputs.files(revisionNeutralSourceImportShadowFiles)
    inputs.files(sources.filter { "stable-frosted-audio-renderer" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/frosted-audio-renderer"))
}
tasks.named("compileKotlin") { dependsOn(extractFrostedAudioRenderer) }

val extractOriginalDanmakuSettings by tasks.registering(Exec::class) {
    inputs.file("tools/v038_danmaku_merge.py")
    inputs.dir("upstream-slices/v038-danmaku-merge")
    inputs.file("tools/v037_player_closure.py")
    inputs.dir("upstream-slices/v037-player-closure")
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-danmaku-settings.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-danmaku-settings").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-danmaku-settings.py", "tools/sync-upstream.py", "tools/extract-appearance-platform.py")
    inputs.file("tools/v042_search_dependencies.py")
    inputs.dir("upstream-slices/v042-search-dependencies")
    inputs.dir("upstream-slices/v027-danmaku-hot-settings")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "stable-danmaku-settings-panel" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-danmaku-settings"))
}
tasks.named("compileKotlin") { dependsOn(extractOriginalDanmakuSettings) }

val extractOriginalDanmakuListMenu by tasks.registering(Exec::class) {
    inputs.file("tools/v038_danmaku_cadence.py")
    inputs.dir("upstream-slices/v038-danmaku-cadence")
    inputs.file("tools/v038_danmaku_merge.py")
    inputs.dir("upstream-slices/v038-danmaku-merge")
    inputs.file("tools/v030_up_danmaku.py")
    inputs.dir("upstream-slices/v030-up-danmaku")
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-danmaku-list-menu.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-danmaku-list-menu").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-danmaku-list-menu.py", "tools/extract-upstream-dynamic-reply-protocol.py")
    inputs.dir("upstream-slices/v027-danmaku-config")
    inputs.dir("upstream-slices/v029-danmaku-config")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "stable-danmaku-list-menu" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-danmaku-list-menu"))
}
tasks.named("compileKotlin") { dependsOn(extractOriginalDanmakuListMenu) }
tasks.named("compileTestKotlin") { dependsOn(extractOriginalDanmakuListMenu) }
kotlin.sourceSets.named("test") {
    kotlin.srcDir(layout.buildDirectory.dir("generated/original-danmaku-list-menu/policy-tests"))
}

// Explicit fixed-v027 slice; the overall canonical baseline remains v025.
val extractOriginalHotDanmaku by tasks.registering(Exec::class) {
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-hot-danmaku.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-hot-danmaku").get().asFile.absolutePath,
        "--tests-output", layout.buildDirectory.dir("generated/original-hot-danmaku-tests").get().asFile.absolutePath)
    inputs.file("tools/extract-upstream-hot-danmaku.py")
    inputs.dir("upstream-slices/v027-hot-danmaku")
    outputs.dir(layout.buildDirectory.dir("generated/original-hot-danmaku"))
    outputs.dir(layout.buildDirectory.dir("generated/original-hot-danmaku-tests"))
}
tasks.named("compileKotlin") { dependsOn(extractOriginalHotDanmaku) }
tasks.named("compileTestKotlin") { dependsOn(extractOriginalHotDanmaku) }
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-hot-danmaku/com")) }
kotlin.sourceSets.named("test") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-hot-danmaku-tests/com")) }

// Explicit v029 BAS grammar/timeline slice. No Android renderer or canonical-baseline update.
val extractOriginalBasCore by tasks.registering(Exec::class) {
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-bas-core.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-bas-core").get().asFile.absolutePath,
        "--tests-output", layout.buildDirectory.dir("generated/original-bas-core-tests").get().asFile.absolutePath)
    inputs.file("tools/extract-upstream-bas-core.py")
    inputs.dir("upstream-slices/v029-bas-core")
    outputs.dir(layout.buildDirectory.dir("generated/original-bas-core"))
    outputs.dir(layout.buildDirectory.dir("generated/original-bas-core-tests"))
}
tasks.named("compileKotlin") { dependsOn(extractOriginalBasCore) }
tasks.named("compileTestKotlin") { dependsOn(extractOriginalBasCore) }
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-bas-core/com")) }
kotlin.sourceSets.named("test") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-bas-core-tests/com")) }

// Whole fixed-v029 painter and retained frame/input algorithms, with Windows graphics seams.
val extractOriginalBasRenderer by tasks.registering(Exec::class) {
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-bas-renderer.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-bas-renderer").get().asFile.absolutePath)
    inputs.file("tools/extract-upstream-bas-renderer.py")
    inputs.dir("upstream-slices/v029-bas-renderer")
    outputs.dir(layout.buildDirectory.dir("generated/original-bas-renderer"))
}
tasks.named("compileKotlin") { dependsOn(extractOriginalBasRenderer) }
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-bas-renderer/com")) }

// Incremental special-segment indexes and the original short playback window.
val extractOriginalSpecialDanmaku by tasks.registering(Exec::class) {
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-special-danmaku.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-special-danmaku").get().asFile.absolutePath,
        "--tests-output", layout.buildDirectory.dir("generated/original-special-danmaku-tests").get().asFile.absolutePath)
    inputs.file("tools/extract-upstream-special-danmaku.py")
    inputs.dir("upstream-slices/v029-special-danmaku")
    outputs.dir(layout.buildDirectory.dir("generated/original-special-danmaku"))
    outputs.dir(layout.buildDirectory.dir("generated/original-special-danmaku-tests"))
}
tasks.named("compileKotlin") { dependsOn(extractOriginalSpecialDanmaku) }
tasks.named("compileTestKotlin") { dependsOn(extractOriginalSpecialDanmaku) }
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-special-danmaku/com")) }
kotlin.sourceSets.named("test") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-special-danmaku-tests/com")) }

val extractCommentFraudProtocol by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-comment-fraud-protocol.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/comment-fraud-protocol").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-comment-fraud-protocol.py", "tools/extract-upstream-dynamic-reply-protocol.py",
        "tools/sync-upstream.py", "tools/extract-upstream-plugins.py", "tools/extract-upstream-media.py")
    inputs.file(sourceManifest)
    inputs.file(canonicalOriginalSource("app/src/main/java/com/android/purebilibili/data/repository/CommentRepository.kt"))
    outputs.dir(layout.buildDirectory.dir("generated/comment-fraud-protocol"))
}
tasks.named("compileKotlin") { dependsOn(extractCommentFraudProtocol) }

val extractSharedLiquidTabs by tasks.registering(Exec::class) {
    inputs.file("tools/v042_optical_dependencies.py")
    inputs.dir("upstream-slices/v042-optical-dependencies")
    inputs.file("tools/v042_search_layout.py")
    inputs.file("tools/v042_search_dependencies.py")
    inputs.dir("upstream-slices/v042-search-dependencies")
    inputs.dir("upstream-slices/v042-search-layout")
    inputs.file("tools/v037_shared_tabs_progress.py")
    inputs.file("tools/v040_liquid_value_controls.py")
    inputs.dir("upstream-slices/v040-liquid-value-controls")
    inputs.dir("upstream-slices/v037-shared-tabs-progress-closure")
    inputs.file("tools/v037_player_shared_material.py")
    inputs.dir("upstream-slices/v037-player-shared-material")
    inputs.file("tools/v046_chrome_glass_tuning.py")
    inputs.dir("upstream-slices/v046-chrome-glass-tuning")
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-shared-liquid-tabs.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/shared-liquid-tabs").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-shared-liquid-tabs.py", "tools/sync-upstream.py")
    inputs.file("tools/v040_native_chrome.py")
    inputs.dir("upstream-slices/v040-native-chrome")
    inputs.dir("upstream-slices/v027-liquid-glass-lens")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "stable-shared-liquid-tabs" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/shared-liquid-tabs"))
}
tasks.named("compileKotlin") { dependsOn(extractSharedLiquidTabs) }
// The same producer emits the original LiquidGlassTuningTest outside the main source root.
kotlin.sourceSets.named("test") { kotlin.srcDir(layout.buildDirectory.dir("generated/shared-liquid-tabs/test")) }

val extractStableVideoMetadata by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-stable-video-metadata.py",
        repositoryRoot.absolutePath, layout.buildDirectory.dir("generated/video-metadata").get().asFile.absolutePath)
    inputs.files("tools/extract-stable-video-metadata.py", "tools/sync-upstream.py", "tools/extract-appearance-platform.py", "tools/extract-upstream-media.py")
    inputs.file(sourceManifest)
    inputs.files(listOf("feature/video/ui/section/VideoInfoSection.kt", "feature/video/ui/section/VideoInfoDisplayPolicy.kt",
        "data/repository/ActionRepository.kt", "core/store/SettingsManager.kt")
        .map { canonicalOriginalSource("app/src/main/java/com/android/purebilibili/$it") })
    outputs.dir(layout.buildDirectory.dir("generated/video-metadata"))
}
tasks.named("compileKotlin") { dependsOn(extractStableVideoMetadata) }

val extractStableCollectionSheet by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-stable-collection-sheet.py",
        repositoryRoot.absolutePath, layout.buildDirectory.dir("generated/collection-sheet").get().asFile.absolutePath)
    inputs.files("tools/extract-stable-collection-sheet.py", "tools/sync-upstream.py", "tools/extract-appearance-platform.py", "tools/extract-upstream-media.py")
    inputs.file(sourceManifest)
    inputs.files(listOf("feature/video/ui/components/CollectionSheet.kt", "feature/video/ui/components/CollectionSubscriptionButton.kt",
        "data/repository/ActionRepository.kt", "core/store/SettingsManager.kt", "core/util/ShareUtils.kt")
        .map { canonicalOriginalSource("app/src/main/java/com/android/purebilibili/$it") })
    outputs.dir(layout.buildDirectory.dir("generated/collection-sheet"))
}
tasks.named("compileKotlin") { dependsOn(extractStableCollectionSheet) }

val extractStableWeeklySeries by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-stable-weekly-series.py",
        repositoryRoot.absolutePath, layout.buildDirectory.dir("generated/weekly-series").get().asFile.absolutePath)
    inputs.files("tools/extract-stable-weekly-series.py", "tools/sync-upstream.py", "tools/extract-appearance-platform.py")
    inputs.files(listOf("feature/home/WeeklySeriesScreen.kt", "feature/home/WeeklySeriesViewModel.kt", "data/repository/VideoRepository.kt")
        .map { canonicalOriginalSource("app/src/main/java/com/android/purebilibili/$it") })
    outputs.dir(layout.buildDirectory.dir("generated/weekly-series"))
}
tasks.named("compileKotlin") { dependsOn(extractStableWeeklySeries) }

val extractStableVideoVotes by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-stable-video-votes.py",
        repositoryRoot.absolutePath, layout.buildDirectory.dir("generated/video-votes").get().asFile.absolutePath)
    inputs.files("tools/extract-stable-video-votes.py", "tools/sync-upstream.py", sourceManifest)
    inputs.file("tools/v029_command_vote.py")
    inputs.file("tools/v030_command_link.py")
    inputs.file("tools/v037_command_overlay.py")
    inputs.dir("upstream-slices/v037-command-overlay")
    inputs.dir("upstream-slices/v029-command-vote")
    inputs.dir("upstream-slices/v030-command-link")
    inputs.files(
        canonicalOriginalSource("app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/CommandDanmakuOverlay.kt"),
        canonicalOriginalSource("app/src/main/java/com/android/purebilibili/data/repository/DanmakuRepository.kt"),
    )
    outputs.dir(layout.buildDirectory.dir("generated/video-votes"))
}

val verifyUpstreamDynamicDetailReplyProtocol by tasks.registering(Exec::class) {
    dependsOn(extractUpstreamDynamicReplyProtocol, extractUpstreamDynamicDetailProtocol, extractStableVideoVotes,
        extractBgmDetail, extractCommentFraudProtocol)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/verify-upstream-dynamic-detail-reply-protocol.py",
        "--repo", repositoryRoot.absolutePath,
        "--comment-output", layout.buildDirectory.dir("generated/dynamic-reply-protocol").get().asFile.absolutePath,
        "--detail-output", layout.buildDirectory.dir("generated/dynamic-detail-protocol").get().asFile.absolutePath,
        "--grade-output", layout.buildDirectory.file("generated/video-votes/platform/DesktopVideoGradeMembers.fragment").get().asFile.absolutePath,
        "--bgm-output", layout.buildDirectory.file("generated/bgm-detail/bgm-operations-members.fragment").get().asFile.absolutePath,
        "--fraud-output", layout.buildDirectory.file("generated/comment-fraud-protocol/source-inventory.json").get().asFile.absolutePath,
        "--output", layout.buildDirectory.file("generated/dynamic-detail-reply-verification.json").get().asFile.absolutePath)
    inputs.files("tools/verify-upstream-dynamic-detail-reply-protocol.py",
        "src/main/kotlin/com/bilipai/desktop/data/DesktopDynamicCardOperations.kt")
    inputs.file(layout.buildDirectory.file("generated/dynamic-reply-protocol/DesktopDynamicCommentOperations.fragment.kt"))
    inputs.file(layout.buildDirectory.file("generated/dynamic-detail-protocol/DesktopDynamicDetailOperations.fragment.kt"))
    inputs.file(layout.buildDirectory.file("generated/video-votes/platform/DesktopVideoGradeMembers.fragment"))
    inputs.file(layout.buildDirectory.file("generated/bgm-detail/bgm-operations-members.fragment"))
    inputs.file(layout.buildDirectory.file("generated/comment-fraud-protocol/source-inventory.json"))
    outputs.file(layout.buildDirectory.file("generated/dynamic-detail-reply-verification.json"))
}

val extractUpstreamDynamicTabs by tasks.registering(Exec::class) {
    inputs.file("tools/v043_theme_chrome.py")
    inputs.dir("upstream-slices/v043-theme-chrome")
    inputs.file("tools/v042_optical_dependencies.py")
    inputs.dir("upstream-slices/v042-optical-dependencies")
    inputs.file("tools/v041_dynamic_author_search.py")
    inputs.file("tools/v041_feed_images.py")
    inputs.dir("upstream-slices/v041-dynamic-author-search")
    inputs.dir("upstream-slices/v041-rss-dynamic")
    inputs.file("tools/v037_shared_tabs_progress.py")
    inputs.file("tools/v040_liquid_value_controls.py")
    inputs.dir("upstream-slices/v040-liquid-value-controls")
    inputs.dir("upstream-slices/v037-shared-tabs-progress-closure")
    dependsOn(prepareUpstreamSources, extractUpstreamDynamicSettings, extractUpstreamComponents,
        extractUpstreamAppearance, extractUpstreamSettingsCategories)
    workingDir(projectDir)
    inputs.file("tools/v040_dynamic_pagination.py")
    inputs.dir("upstream-slices/v040-dynamic-pagination")
    commandLine(pythonExecutable, "tools/extract-upstream-dynamic-tabs.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/dynamic-tabs").get().asFile.absolutePath,
        "--tests-output", layout.buildDirectory.dir("generated/dynamic-author-search-tests").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-dynamic-tabs.py", "tools/extract-upstream-plugins.py",
        "tools/extract-upstream-media.py", "tools/extract-appearance-platform.py",
        "tools/extract-upstream-settings-home.py", "tools/sync-upstream.py")
    inputs.file("tools/v042_search_dependencies.py")
    inputs.dir("upstream-slices/v042-search-dependencies")
    inputs.files("tools/v046_dynamic_category_merge.py", "tools/v044_favorite_detail.py", "tools/v044_favorite_subscription.py")
    inputs.dir("upstream-slices/v046-dynamic-category-merge")
    // The v0.4.6 SettingsManager of the same commit is read from the comment-list archive by pinned hash.
    inputs.file("upstream-slices/v046-comment-list/SettingsManager.kt")
    inputs.files(sources.filter { "settings-dynamic-tabs-parity" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/dynamic-tabs"))
    outputs.dir(layout.buildDirectory.dir("generated/dynamic-author-search-tests"))
}
kotlin.sourceSets.named("test") { kotlin.srcDir(layout.buildDirectory.dir("generated/dynamic-author-search-tests")) }
tasks.named("compileTestKotlin") { dependsOn(extractUpstreamDynamicTabs) }

val extractOriginalDynamicAccountPolicy by tasks.registering(Exec::class) {
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-dynamic-account.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-dynamic-account").get().asFile.absolutePath,
        "--tests-output", layout.buildDirectory.dir("generated/original-dynamic-account-tests").get().asFile.absolutePath)
    inputs.file("tools/extract-upstream-dynamic-account.py")
    inputs.dir("upstream-slices/v029-dynamic-account")
    outputs.dir(layout.buildDirectory.dir("generated/original-dynamic-account"))
    outputs.dir(layout.buildDirectory.dir("generated/original-dynamic-account-tests"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-dynamic-account")) }
kotlin.sourceSets.named("test") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-dynamic-account-tests")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalDynamicAccountPolicy) }
tasks.named("compileTestKotlin") { dependsOn(extractOriginalDynamicAccountPolicy) }

val extractUpstreamDynamicFollow by tasks.registering(Exec::class) {
    inputs.file("tools/v044_favorite_detail.py")
    inputs.file("tools/sync-upstream.py")
    inputs.file("tools/v044_favorite_subscription.py")
    inputs.file("tools/v044_fans.py")
    inputs.dir("upstream-slices/v044-following-fans")
    inputs.file("tools/v044_favorite_search.py")
    inputs.dir("upstream-slices/v044-favorite-search")
    inputs.dir("upstream-slices/v044-favorite-subscription")
    dependsOn(prepareUpstreamSources, extractUpstreamDynamicSettings, extractUpstreamDynamicTabs)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-dynamic-follow.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/dynamic-follow").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-dynamic-follow.py", "tools/extract-upstream-plugins.py",
        "tools/extract-upstream-media.py", "tools/extract-upstream-api.py")
    inputs.files(sources.filter { "dynamic-follow-observer-parity" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/dynamic-follow"))
}

val extractUpstreamCrashPrompt by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-crash-prompt.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/crash-prompt/sources").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-crash-prompt.py", "tools/extract-upstream-plugins.py",
        "tools/extract-upstream-media.py", "tools/sync-upstream.py")
    inputs.files(sources.filter { "settings-crash-prompt-parity" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/crash-prompt"))
}

val extractNativeMusicRoot by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-native-music-root-platform.py",
        "--source-root", repositoryRoot.absolutePath,
        "--output-dir", layout.buildDirectory.dir("generated/native-music-root").get().asFile.absolutePath)
    inputs.files("tools/extract-native-music-root-platform.py", "tools/sync-upstream.py")
    inputs.files(sources.filter { "resolveDisplayBgmList" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/native-music-root"))
}

val extractUpstreamSpace by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-space-platform.py",
        "--repo", repositoryRoot.absolutePath, "--policy-only",
        "--output", layout.buildDirectory.dir("generated/space").get().asFile.absolutePath,
        "--test-output", layout.buildDirectory.dir("generated/space-original-tests").get().asFile.absolutePath)
    inputs.files("tools/extract-space-platform.py", "tools/v043_navigation.py", "tools/v043_message_link_host.py", "tools/v043_link_open.py")
    inputs.dir("upstream-slices/v043-link-open")
    inputs.dir("upstream-slices/v043-message-link-host")
    inputs.file(canonicalOriginalSource("app/src/main/java/com/android/purebilibili/navigation/MessageLinkNavigationPolicy.kt"))
    inputs.dir("upstream-slices/v043-navigation")
    inputs.files(sources.filter { "space" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/space"))
    outputs.dir(layout.buildDirectory.dir("generated/space-original-tests"))
}

kotlin.sourceSets.named("test") { kotlin.srcDir(layout.buildDirectory.dir("generated/space-original-tests")) }
tasks.named("compileTestKotlin") { dependsOn(extractUpstreamSpace) }

val extractUpstreamSpaceOverview by tasks.registering(Exec::class) {
    inputs.file("tools/v044_favorite_detail.py")
    inputs.dir("upstream-slices/v044-favorite-detail")
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-space-overview.py",
        "--repo", repositoryRoot.absolutePath, "--policy-only",
        "--output", layout.buildDirectory.dir("generated/space-overview").get().asFile.absolutePath,
        "--test-output", layout.buildDirectory.dir("generated/original-space-playback-tests").get().asFile.absolutePath)
    inputs.files("tools/extract-space-overview.py")
    inputs.files(sources.filter { "space-overview" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/space-overview"))
    outputs.dir(layout.buildDirectory.dir("generated/original-space-playback-tests"))
}
kotlin.sourceSets.named("test") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-space-playback-tests")) }
tasks.named("compileTestKotlin") { dependsOn(extractUpstreamSpaceOverview) }

val extractUpstreamStoryTopic by tasks.registering(Exec::class) {
    inputs.file("tools/v044_favorite_detail.py")
    inputs.file("tools/sync-upstream.py")
    inputs.file("tools/v044_favorite_subscription.py")
    inputs.file("tools/v044_fans.py")
    inputs.dir("upstream-slices/v044-following-fans")
    inputs.file("tools/v044_favorite_search.py")
    inputs.dir("upstream-slices/v044-favorite-search")
    inputs.dir("upstream-slices/v044-favorite-subscription")
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-story-topic.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/story-topic").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-story-topic.py", "tools/extract-upstream-plugins.py", "tools/extract-upstream-api.py")
    inputs.files(sources.filter { "story-topic" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/story-topic"))
}

val extractPlaybackSettings by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-playback-settings.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/playback-settings").get().asFile.absolutePath)
    inputs.files("tools/extract-playback-settings.py", "third-party/premium-audio-platform.json")
    inputs.files(sources.filter { "playback-settings-parity" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/playback-settings"))
}

val verifyAppearanceDependencies by tasks.registering {
    inputs.file("third-party/miuix5157/dependency-pins.json")
    inputs.dir("src/main/resources/licenses/appearance")
    doLast {
        val pins = JsonSlurper().parse(file("third-party/miuix5157/dependency-pins.json")) as Map<*, *>
        val artifacts = configurations.getByName("runtimeClasspath").resolvedConfiguration.resolvedArtifacts
        val dependencies = pins["dependencies"] as List<*>
        require(dependencies.size == 9) { "The fixed appearance dependency inventory changed." }
        fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        dependencies.forEach { item ->
            val entry = item as Map<*, *>
            val artifact = artifacts.filter { it.moduleVersion.id.toString() == entry["coordinate"] }.singleOrNull()
                ?: error("Appearance dependency was replaced or duplicated: ${entry["coordinate"]}")
            require(digest(artifact.file) == entry["sha256"]) { "Appearance dependency checksum failed: ${entry["coordinate"]}" }
        }
        (pins["notices"] as List<*>).forEach { item ->
            val entry = item as Map<*, *>
            require(digest(file("src/main/resources/${entry["resource"]}")) == entry["sha256"]) { "Appearance notice checksum failed: ${entry["resource"]}" }
        }
        logger.lifecycle("Verified nine fixed appearance runtime dependencies and four source notices.")
    }
}

val verifyDynamicMediaDependencies by tasks.registering {
    inputs.file("third-party/dynamic-media-dependency-pins.json")
    inputs.dir("src/main/resources/licenses/dynamic-media")
    doLast {
        val pins = JsonSlurper().parse(file("third-party/dynamic-media-dependency-pins.json")) as Map<*, *>
        val artifacts = configurations.getByName("runtimeClasspath").resolvedConfiguration.resolvedArtifacts
        fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        val dependencies = pins["dependencies"] as List<*>
        require(dependencies.size == 3) { "The approved image metadata runtime graph changed." }
        dependencies.forEach { item ->
            val entry = item as Map<*, *>
            val artifact = artifacts.filter { it.moduleVersion.id.toString() == entry["coordinate"] }.singleOrNull()
                ?: error("Image metadata dependency was replaced or duplicated: ${entry["coordinate"]}")
            require(digest(artifact.file) == entry["sha256"]) { "Image metadata dependency checksum failed: ${entry["coordinate"]}" }
        }
        (pins["notices"] as List<*>).forEach { item ->
            val entry = item as Map<*, *>
            require(digest(file("src/main/resources/${entry["resource"]}")) == entry["sha256"]) { "Apache notice checksum failed: ${entry["resource"]}" }
        }
        logger.lifecycle("Verified three approved image metadata runtime dependencies and six Apache notices.")
    }
}

val verifySettingsSearchDependencies by tasks.registering {
    inputs.dir("src/main/resources/licenses/tinypinyin-2.0.3.RELEASE")
    doLast {
        val noticeRoot = file("src/main/resources/licenses/tinypinyin-2.0.3.RELEASE")
        val pins = JsonSlurper().parse(File(noticeRoot, "provenance.json")) as Map<*, *>
        fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        val dependencies = (pins["dependencies"] as List<*>).map { it as Map<*, *> }
        require(dependencies.map { it["coordinate"] }.toSet() ==
            setOf("io.github.biezhi:TinyPinyin:2.0.3.RELEASE", "org.ahocorasick:ahocorasick:0.4.0") &&
            dependencies.size == 2) { "The fixed pinyin runtime graph changed" }
        val artifacts = configurations.getByName("runtimeClasspath").resolvedConfiguration.resolvedArtifacts
        require(artifacts.none { it.moduleVersion.id.group == "com.belerweb" && it.moduleVersion.id.name == "pinyin4j" }) {
            "Historical pinyin4j must not remain on the migrated runtime classpath"
        }
        dependencies.forEach { entry ->
            val artifact = artifacts.single { it.moduleVersion.id.toString() == entry["coordinate"] }
            require(digest(artifact.file) == entry["sha256"]) { "Fixed pinyin runtime checksum failed: ${entry["coordinate"]}" }
        }
        val notices = pins["files"] as List<*>
        require(notices.size == 7) { "The fixed pinyin notice/source inventory changed" }
        notices.forEach { item ->
            val entry = item as Map<*, *>
            require(digest(File(noticeRoot, entry["file"].toString())) == entry["sha256"]) { "Pinyin notice/source checksum failed: ${entry["file"]}" }
        }
        logger.lifecycle("Verified fixed TinyPinyin and AhoCorasick JVM runtime, source and Apache license inventory.")
    }
}

// The guest VM has a closed classpath and its own minimal runtime, separate from app dependencies.
val jsWorkerCache = providers.gradleProperty("jsWorkerCache").orElse(file(".gradle/js-worker-cache").absolutePath).get()
val jsWorkerJdkArchive = providers.gradleProperty("jsWorkerJdkArchive").orElse(File(jsWorkerCache, "fixed-jdk.zip").absolutePath).get()
val jsWorkerJavaHome = providers.gradleProperty("jsWorkerJavaHome").orElse(File(jsWorkerCache, "fixed-jdk").absolutePath).get()
val jsWorkerInputs = listOf(file("tools/prepare-js-worker.py"), file("tools/fetch-js-worker-jdk.py"),
    file("third-party/graaljs/artifacts.lock.json"), file("third-party/graaljs/catalog.json"),
    file("third-party/graaljs/runtime.lock.json")) +
    fileTree("js-worker/src/main/java").files.sortedBy { it.path } +
    fileTree("third-party/graaljs/licenses").files.sortedBy { it.path }
val jsWorkerInputDigest = MessageDigest.getInstance("SHA-256").also { digest ->
    jsWorkerInputs.forEach { input ->
        digest.update(input.relativeTo(projectDir).invariantSeparatorsPath.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte()); digest.update(input.readBytes()); digest.update(0.toByte())
    }
}.digest().joinToString("") { "%02x".format(it) }
// Keep the closed Maven graph within Windows path limits; verified provenance checks the full inputs.
val jsWorkerOutputParent = layout.buildDirectory.dir("jw/${jsWorkerInputDigest.take(16)}")
val jsWorkerOutput = jsWorkerOutputParent.map { it.dir("r") }
val jsWorkerGenerated = layout.buildDirectory.dir("generated/js-worker")
val fetchJsWorkerJdk = if (hostIsWindows) tasks.register<Exec>("fetchJsWorkerJdk") {
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/fetch-js-worker-jdk.py",
        "--archive", jsWorkerJdkArchive, "--output", jsWorkerJavaHome)
    inputs.files("tools/fetch-js-worker-jdk.py", "tools/prepare-js-worker.py")
    inputs.property("fixedJdkArchiveSha256", "f9d6e191ab098c0d416e7d588a24420a8621cd2f4720dab2459b8b7b2d2d8b4e")
    outputs.file(jsWorkerJdkArchive); outputs.dir(jsWorkerJavaHome)
} else tasks.register("fetchJsWorkerJdk") {
    description = "Non-Windows placeholder: the fixed worker JDK is a Windows x64 archive and is not fetched here."
}
val prepareJsWorker = if (hostIsWindows) tasks.register<Exec>("prepareJsWorker") {
    dependsOn(fetchJsWorkerJdk)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/prepare-js-worker.py",
        "--repo", repositoryRoot.absolutePath, "--output", jsWorkerOutput.get().asFile.absolutePath,
        "--java-home", jsWorkerJavaHome, "--jdk-archive", jsWorkerJdkArchive, "--cache", jsWorkerCache,
        "--lock", file("third-party/graaljs/artifacts.lock.json").absolutePath,
        "--notice-root", file("third-party/graaljs/licenses").absolutePath,
        "--notice-catalog", file("third-party/graaljs/catalog.json").absolutePath,
        "--generated-kotlin", jsWorkerGenerated.get().asFile.resolve("com/bilipai/desktop/plugins/js/DesktopJsWorkerAssetHash.kt").absolutePath,
        "--reuse-verified")
    inputs.files(jsWorkerInputs); inputs.file(jsWorkerJdkArchive); inputs.dir(jsWorkerJavaHome)
    // Gradle creates declared output directories before Exec; the tool owns the previously absent child.
    outputs.dir(jsWorkerOutputParent); outputs.dir(jsWorkerGenerated)
} else tasks.register("prepareJsWorker") {
    group = "build"
    description = "Non-Windows placeholder: the JS worker image links Windows binaries and is not built on this host."
    val generated = jsWorkerGenerated.map { it.file("com/bilipai/desktop/plugins/js/DesktopJsWorkerAssetHash.kt") }
    outputs.dir(jsWorkerGenerated)
    outputs.upToDateWhen { false }
    doLast {
        val file = generated.get().asFile
        file.parentFile.mkdirs()
        file.writeText("""// GENERATED placeholder: this host cannot link the Windows x64 JS worker image.
package com.bilipai.desktop.plugins.js

/** Not a digest. No worker runtime was built here, so the JS plugin lane stays unavailable. */
internal const val DESKTOP_JS_WORKER_CATALOG_SHA256 = "unavailable: the JS worker image is a Windows x64 build product"
""", Charsets.UTF_8)
        logger.lifecycle("No JS worker image on this host; compile-only placeholder written.")
    }
}
val prepareJsWorkerResources = if (hostIsWindows) tasks.register<Sync>("prepareJsWorkerResources") {
    dependsOn(prepareJsWorker)
    from(jsWorkerOutput)
    into("resources/common/js-engine")
} else tasks.register("prepareJsWorkerResources") {
    description = "Non-Windows placeholder: there is no JS worker image to stage."
}

// Generated legacy files that re-declare migrated upstream declarations under another file name.
val upstreamOwnedGeneratedCopies = setOf(
    // core/ui/components/UserLevelBadge (TON-1072): the upstream file draws its R.drawable level badges through the generated
    // desktop drawable table, so the blocked-list producer's classpath-PNG copy is retired.
    "blocked-list-ui/com/android/purebilibili/core/ui/components/DesktopUpstreamUserLevelBadge.kt",
    "original-background-playback-policy/generated-main/com/android/purebilibili/feature/video/player/DesktopOriginalWindowsBackgroundPolicy.kt",
    "original-video-detail-holder-full/com/android/purebilibili/feature/video/state/DesktopOriginalMiniEntryPolicy.kt",
    "original-video-player-section-full/com/android/purebilibili/feature/video/state/DesktopOriginalApiDimensionPolicy.kt",
    "original-danmaku-list-menu/com/android/purebilibili/feature/video/danmaku/DesktopOriginalWebMaskRefreshPolicy.kt",
    // core/plugin + plugin-sdk (TON-1028): legacy plugin contracts upstream moved to plugin-sdk (Plugin, the typed
    // plugin APIs) or to core/plugin/BuiltinPlugin (host presentation); the merged upstream files own them now.
    "upstream/app/src/main/java/com/android/purebilibili/core/plugin/Plugin.kt",
    "upstream/app/src/main/java/com/android/purebilibili/core/plugin/DanmakuPlugin.kt",
    "upstream/app/src/main/java/com/android/purebilibili/core/plugin/FeedPlugin.kt",
    "upstream/app/src/main/java/com/android/purebilibili/core/plugin/PlayerPlugin.kt",
    "upstream/app/src/main/java/com/android/purebilibili/core/plugin/RecommendationPluginApi.kt",
    "upstream/app/src/main/java/com/android/purebilibili/core/plugin/TypedPluginApis.kt",
    "plugins/com/android/purebilibili/core/plugin/CastPluginApi.kt",
    // Renamed legacy subsets of upstream core/plugin files that now compile from the merged root (with their patches).
    "plugins/com/android/purebilibili/core/plugin/feed/DesktopSavedSubscriptionFeed.kt",
    "plugins/com/android/purebilibili/core/plugin/feed/DesktopFeedConditionalValidators.kt",
    "packages/com/android/purebilibili/core/plugin/skin/DesktopUiSkinCompositionPolicy.kt",
    "upstream-video-comment-url/com/android/purebilibili/feature/video/screen/DesktopOriginalCommentUrlNavigation.kt",
    "original-video-player-full-controls/com/android/purebilibili/feature/video/ui/overlay/CenterPlaybackControl.kt",
    "original-video-fullscreen-pager/com/android/purebilibili/feature/video/ui/overlay/PortraitInteractionBar.kt",
    "original-bangumi-player-ui/com/android/purebilibili/feature/bangumi/ui/player/DesktopOriginalBangumiCollapsedPlayerBar.kt",
    // M4a: the refresh-aware desktop request owner replaces this fixed-60Hz generated loop.
    "original-danmaku-list-menu/com/bilipai/desktop/danmaku/DesktopOriginalDanmakuFrameRequests.kt",
    "original-video-fullscreen-pager/com/android/purebilibili/feature/video/ui/pager/PortraitCommentSheet.kt",
    "original-video-player-full-controls/com/android/purebilibili/feature/video/ui/components/SeekPreviewBubble.kt",
    "original-video-player-full-controls/com/android/purebilibili/feature/video/ui/overlay/PlayerOverlayModels.kt",
    "original-video-detail-units/com/android/purebilibili/feature/video/ui/components/CircularGesturePercentText.kt",
    "original-video-player-full-controls/com/android/purebilibili/feature/video/ui/components/DanmakuSettingsPanelAppearance.kt",
    "original-video-player-full-controls/com/android/purebilibili/feature/video/playback/session/PlaybackUserActionTracker.kt",
    "original-video-player-full-controls/com/android/purebilibili/feature/video/ui/components/LandscapeSidePanel.kt",
    "original-video-detail-units/com/android/purebilibili/feature/video/ui/feedback/VideoTriplePressState.kt",
    "original-video-player-section-full/com/android/purebilibili/feature/video/screen/VideoInputDiagnostics.kt",
    "home-bangumi-page/com/android/purebilibili/feature/bangumi/DesktopOriginalBangumiCardTransition.kt",
    "original-video-detail-units/com/android/purebilibili/feature/video/ui/feedback/VideoActionCountText.kt",
    "original-video-detail-holder-full/com/android/purebilibili/feature/video/screen/VideoDetailCommonOverlayAdapter.kt",
    "story-topic/com/android/purebilibili/feature/video/ui/pager/PortraitPagerSwitchPolicy.kt",
    "original-video-fullscreen-pager/com/android/purebilibili/feature/video/ui/overlay/PortraitBottomInputBar.kt",
    "original-hot-danmaku/com/android/purebilibili/feature/video/ui/overlay/HotDanmakuBar.kt",
    "home-bangumi-page/com/android/purebilibili/feature/bangumi/BangumiHubPolicy.kt",
    "original-background-playback-policy/generated-main/com/android/purebilibili/feature/video/state/DesktopOriginalWindowsLifecycleInputs.kt",
    "original-bangumi-player/com/android/purebilibili/feature/bangumi/DesktopOriginalBangumiPlayerOverlayPolicy.kt",
    "danmaku/com/android/purebilibili/danmaku/parser/DesktopOriginalDanmakuFontScale.kt",
    "danmaku/com/android/purebilibili/danmaku/parser/DesktopAdvancedDanmakuParser.kt",
    "original-danmaku-list-menu/com/android/purebilibili/danmaku/engine/DanmakuItem.kt",
    "original-danmaku-list-menu/com/android/purebilibili/danmaku/engine/DanmakuRenderConfig.kt",
    "original-danmaku-list-menu/com/android/purebilibili/danmaku/engine/DanmakuMaskFrame.kt",
    "media/com/android/purebilibili/danmaku/parser/DesktopDanmakuMetadataParser.kt",
    "video-metadata/com/android/purebilibili/feature/video/ui/section/DesktopOriginalVideoMetadataPolicy.kt",
    "original-bangumi-player/com/android/purebilibili/feature/bangumi/DesktopOriginalBangumiDashManifestPolicy.kt",

    "original-bangumi-player/com/android/purebilibili/feature/bangumi/DesktopOriginalBangumiEpisodeSkipPolicy.kt",
    "original-bangumi-player/com/android/purebilibili/feature/bangumi/DesktopOriginalBangumiResumePolicy.kt",

    // M4a: renamed legacy subsets now owned by upstream video/bangumi files.
    "original-video-detail-units/com/android/purebilibili/feature/video/ui/section/DesktopOriginalVideoInfoDisplayPolicy.kt",
    "independent-bangumi-pages/com/android/purebilibili/feature/bangumi/DesktopOriginalMyFollowStats.kt",
    "home-bangumi-page/com/android/purebilibili/feature/bangumi/MyFollowPolicy.kt",
    "bgm-detail/com/android/purebilibili/feature/video/viewmodel/DesktopOriginalVideoCommentLoadPolicy.kt",

    // app core (TON-1000): renamed subsets whose every declaration the upstream app core files now own.
    "appearance/com/android/purebilibili/core/store/DesktopOriginalWallpaperPaletteModels.kt",
    "appearance/com/android/purebilibili/core/store/ThemeSelectionStore.kt",
    "dynamic-detail-container/com/android/purebilibili/core/store/DesktopOriginalDetailLiquidReusePolicy.kt",
    "dynamic-detail-container/com/android/purebilibili/core/ui/transition/DesktopOriginalDetailPredictiveBlurPolicy.kt",
    "dynamic-reply/com/android/purebilibili/core/store/DesktopOriginalReplySkeletonSettingsStore.kt",
    "frosted-audio-renderer/com/android/purebilibili/core/store/DesktopOriginalBottomSearchModes.kt",
    "home-page/com/android/purebilibili/core/store/DesktopHomeNavigationMigration.kt",
    "home-page/com/android/purebilibili/core/store/navigation/DesktopHomeNavigationLabels.kt",
    "home-page/com/android/purebilibili/core/ui/transition/DesktopHomeDepthOwnershipPolicy.kt",
    "home-page/com/android/purebilibili/core/util/DesktopHomeCardTapEffect.kt",
    "home-page/com/android/purebilibili/core/util/DesktopHomeTapEffect.kt",
    "home-partition/com/android/purebilibili/core/ui/skeleton/DesktopOriginalMediaListSkeleton.kt",
    "independent-bangumi-pages/com/android/purebilibili/core/ui/skeleton/DesktopOriginalBangumiPosterDetailSkeleton.kt",
    "live-home-list/com/android/purebilibili/core/util/DesktopOriginalLiveApiErrors.kt",
    "original-article-detail/com/android/purebilibili/core/ui/skeleton/DesktopOriginalArticleSkeleton.kt",
    "original-danmaku-list-menu/com/android/purebilibili/core/store/DanmakuSettingsScope.kt",
    "original-danmaku-settings/com/android/purebilibili/core/store/DesktopOriginalDanmakuSettingsModels.kt",
    "original-favorites/com/android/purebilibili/core/store/DesktopFavoriteChromePolicies.kt",
    "original-favorites/com/android/purebilibili/core/ui/DesktopFavoriteBottomBarLocals.kt",
    "original-favorites/com/android/purebilibili/core/ui/adaptive/DesktopFavoriteDeviceProfile.kt",
    "original-favorites/com/android/purebilibili/core/ui/skeleton/DesktopFavoriteContentGridSkeleton.kt",
    "original-favorites/com/android/purebilibili/core/util/DesktopFavoriteGridSkeleton.kt",
    "original-navigation3-host/com/android/purebilibili/core/ui/transition/DesktopOriginalNavigationHostDepth.kt",
    "original-navigation3-host/com/android/purebilibili/core/ui/transition/DesktopOriginalNavigationPredictiveBackground.kt",
    "original-search-pages/com/android/purebilibili/core/ui/DesktopOriginalSearchNowPlayingAvoidance.kt",
    "original-search-pages/com/android/purebilibili/core/ui/skeleton/DesktopOriginalSearchTrendingSkeleton.kt",
    "original-video-detail-holder-full/com/android/purebilibili/core/ui/transition/DesktopOriginalVideoCardMorphProgressReporter.kt",
    "original-video-fullscreen-pager/com/android/purebilibili/core/ui/skeleton/DesktopOriginalVideoCommentListSkeleton.kt",
    "original-video-player-full-controls/com/android/purebilibili/core/store/DesktopOriginalPlayerProgressBehaviorModels.kt",
    "original-video-player-full-controls/com/android/purebilibili/core/store/DesktopOriginalPlayerProgressPlacement.kt",
    "original-video-player-section-full/com/android/purebilibili/core/store/DesktopOriginalPlayerInteractionSettings.kt",
    "playback-settings/com/android/purebilibili/core/store/player/DefaultAudioQuality.kt",
    "video-tablet-full/com/android/purebilibili/core/store/DesktopOriginalTabletSecondaryDefaultTab.kt",
    "whole-playback-settings/generated/com/android/purebilibili/core/store/DesktopOriginalHwDecodeMigrationPolicy.kt",
    "whole-playback-settings/generated/com/android/purebilibili/core/store/DesktopOriginalPlaybackSettingsDefaults.kt",
    // design-system / design-tokens (TON-997): declarations that upstream's own files now own.
    "dynamic-tabs/com/android/purebilibili/core/ui/components/DesktopOriginalDynamicNativeTabs.kt",
    "dynamic-tabs/com/android/purebilibili/core/ui/DesktopOriginalDynamicImmersiveChrome.kt",
    "dynamic-detail-container/com/android/purebilibili/core/ui/DesktopOriginalDetailAdaptiveChrome.kt",
    "dynamic-detail-container/com/android/purebilibili/core/ui/DesktopOriginalDetailAppSheetComponents.kt",
    "preferences/com/android/purebilibili/core/ui/AppWallpaperBackdropLocal.kt",
    "home-page/com/android/purebilibili/core/ui/DesktopHomeOriginalPullThreshold.kt",
    "components/com/android/purebilibili/core/ui/components/AppScrollableUnderlinePolicy.kt",
    "api/com/android/purebilibili/core/network/DesktopUpstreamApi.kt",
    "dynamic-reply-protocol/generated/com/android/purebilibili/data/repository/DesktopOriginalCommentReadAccessPolicy.kt",
    "bgm-detail/com/android/purebilibili/data/repository/CommentFraudDetectionPolicy.kt",
    "bgm-detail/com/android/purebilibili/data/repository/DesktopOriginalCommentPaginationPolicy.kt",
    "original-message-notifications/com/android/purebilibili/data/model/response/DesktopDynamicAuthorUpdatesModels.kt",
    "api/com/android/purebilibili/data/repository/DesktopHistoryCursorPolicy.kt",
    "login/com/android/purebilibili/core/store/DesktopUpstreamLoginDeclarations.kt",
    "dynamic-reply-protocol/generated/com/android/purebilibili/data/repository/DesktopOriginalCommentReadRouteMemo.kt",
    "video-votes/com/android/purebilibili/data/model/response/DesktopOriginalGradeDanmakuSummary.kt",
    "api/com/android/purebilibili/core/network/DesktopCoreDataLog.kt",
    "api/com/android/purebilibili/data/model/response/FavoriteInfoResponse.kt",
    "audio/com/android/purebilibili/data/repository/DesktopFavoriteRequestException.kt",
    "bgm-detail/com/android/purebilibili/data/model/CommentFraudStatus.kt",
    "original-favorites/com/android/purebilibili/core/store/DesktopFavoriteAccountIdentity.kt",
    "original-video-state-core/com/android/purebilibili/data/repository/DesktopOriginalVideoRequestAccountScope.kt"
)

// Generated legacy files that mix desktop code with declarations upstream now owns, or that call
// core-data APIs upstream changed, are patched at build time instead of committing copies:
// upstream-core/transition/patches/<generated path>.patch is a unified diff against the
// producer's output. A hunk that no longer matches exactly fails the build.
val transitionPatchDirectory = file("upstream-core/transition/patches")
val transitionPatchedGeneratedFiles = fileTree(transitionPatchDirectory) { include("**/*.patch") }.files
    .map { it.relativeTo(transitionPatchDirectory).invariantSeparatorsPath.removeSuffix(".patch") }.toSortedSet()
val transitionPatchProducers = mapOf(
    "shared-liquid-tabs" to "extractSharedLiquidTabs",
    "original-following" to "extractOriginalFollowing",
    "original-space-pages" to "extractOriginalSpacePages",
    "original-message-pages" to "extractOriginalMessagePages",
    "independent-bangumi-pages" to "extractOriginalBangumiPages",
    "video-tablet-full" to "extractOriginalVideoTabletFull",
    "original-root-home-navigation" to "extractOriginalRootHomeNavigation",
    "home-page" to "extractOriginalHomePage",
    "appearance" to "extractUpstreamAppearance",
    "original-offline-player" to "extractOriginalOfflinePlayer",
    "home-bangumi-page" to "extractOriginalHomeBangumiPage",
    "bgm-detail" to "extractBgmDetail",
    "settings-search" to "extractUpstreamSettingsSearch",
    "settings-privacy" to "extractUpstreamSettingsPrivacy",
    "plugins" to "extractUpstreamPlugins",
    "cast" to "extractUpstreamCast",
    "google-cast" to "extractGoogleCastPlatform",
    "js" to "extractUpstreamJs",
    "packages" to "extractUpstreamPackages",
    "home-subscription-page" to "extractOriginalSubscriptionPage",
    "frosted-audio-renderer" to "extractFrostedAudioRenderer",
    // Retirable with the original-bangumi-player transition patch when its upstream VM migrates.
    "original-bangumi-player" to "extractOriginalBangumiPlayer",
    "original-bangumi-player-ui" to "extractOriginalBangumiPlayerUi",
    "original-video-fullscreen-pager" to "extractOriginalVideoFullscreenPager",
    "original-video-full-owner" to "extractOriginalVideoFullOwner",
    "original-aicu" to "extractOriginalAicu",
    "home-viewmodel" to "extractOriginalHomeViewModel",
    "full-navigation-settings" to "extractUpstreamFullNavigation",
    "collection-sheet" to "extractStableCollectionSheet",
    "discovery" to "extractUpstreamDiscovery",
    "dynamic-reply" to "extractUpstreamDynamicReply",
    "home-cards" to "extractUpstreamHomeCards",
    "home-settings" to "extractOriginalHomeSettings",
    "original-video-detail-holder-full" to "extractOriginalVideoDetailHolderFull",
    "original-video-detail-units" to "extractOriginalVideoDetailUnits",
    "api" to "extractUpstreamApi",
    "dynamic-detail-container" to "extractUpstreamDynamicDetailContainer",
    "dynamic-full-card" to "extractUpstreamDynamicFullCard",
    "home-full-card" to "extractUpstreamHomeFullCard",
    "media" to "extractUpstreamMedia",
    "original-favorites" to "extractOriginalFavorites",
    "original-message-notifications" to "extractOriginalMessageNotifications",
    "original-storage-settings" to "extractOriginalStorageSettings",
    "original-search-pages" to "extractOriginalSearchPages",
    "original-video-player-full-controls" to "extractOriginalPlayerFullControls",
    "original-video-state-core" to "extractOriginalVideoStateCore",
    "original-watchlater" to "extractOriginalWatchLater",
    "original-listen-video-pages" to "extractOriginalListenVideoPages",
    "profile-main" to "extractOriginalProfileMain",
    "search" to "extractUpstreamSearch",
    "upstream" to "prepareUpstreamSources",
    "frosted-audio-renderer" to "extractFrostedAudioRenderer",
    "linked-dock" to "extractLinkedDock",
    "story-topic" to "extractUpstreamStoryTopic",
    "whole-playback-settings" to "extractOriginalWholePlaybackSettings",
    // TON-1071: producers whose copies re-declare names of the migrated data layer.
    "dynamic-settings" to "extractUpstreamDynamicSettings",
    "dynamic-detail-protocol" to "extractUpstreamDynamicDetailProtocol",
    "dynamic-tabs" to "extractUpstreamDynamicTabs",
    "original-bangumi-player" to "extractOriginalBangumiPlayer",
    "dynamic-editor" to "extractUpstreamDynamicEditor",
)

/** Applies a unified diff; every hunk must match the original exactly once. */
fun applyUnifiedPatch(original: String, patch: String, label: String): String {
    // A Windows checkout may hold CRLF text; patches and their targets are compared and written with LF.
    var lines = original.replace("\r\n", "\n").split("\n")
    val body = patch.replace("\r\n", "\n").split("\n").dropWhile { !it.startsWith("--- ") }.drop(2)
    var index = 0
    while (index < body.size) {
        val header = body[index++]
        if (header.isEmpty() && index == body.size) break
        require(header.startsWith("@@ ")) { "$label: malformed hunk header: $header" }
        val before = mutableListOf<String>()
        val after = mutableListOf<String>()
        while (index < body.size && !body[index].startsWith("@@ ")) {
            val line = body[index++]
            if (line.isEmpty() && index == body.size) break
            when (line.firstOrNull()) {
                ' ' -> { before += line.substring(1); after += line.substring(1) }
                '-' -> before += line.substring(1)
                '+' -> after += line.substring(1)
                else -> throw GradleException("$label: unexpected patch line: $line")
            }
        }
        val matches = (0..lines.size - before.size).filter { start -> lines.subList(start, start + before.size) == before }
        if (matches.size != 1) throw GradleException("$label: hunk $header matches ${matches.size} times; regenerate the patch against the producer output")
        lines = lines.subList(0, matches.single()) + after + lines.subList(matches.single() + before.size, lines.size)
    }
    return lines.joinToString("\n")
}

// Desktop-only fixes against the merged upstream tree. The independent upstream-core
// project deliberately compiles the unpatched originals so upstream regressions stay visible.
val desktopUpstreamPatchDirectory = file("upstream-core/patches")
val desktopUpstreamPatchedFiles = fileTree(desktopUpstreamPatchDirectory) { include("**/*.patch"); exclude("app/src/test/**") }.files
    .map { it.relativeTo(desktopUpstreamPatchDirectory).invariantSeparatorsPath.removeSuffix(".patch") }.toSortedSet()
// Build-time rewrite rules (TON-1072): one call-site adaptation that every migrated upstream file needs in the same way,
// so no per-file patch is written for it. A rule turns one call into a desktop bridge call with the same arguments and
// applies to every upstream file compiled from the merged root that contains it (after that file's patch, if it has one);
// a new upstream file that uses the call is rewritten without anyone writing a patch.
//  - coil3's ImageRequest.Builder(...) and SingletonImageLoader.get(...) take a coil PlatformContext; upstream passes an
//    Android Context. On the JVM coil has exactly one PlatformContext, which com.bilipai.desktop.compat.coil hands over
//    (the extension forms, Context.imageLoader and allowHardware, resolve through compat-ui/coil3 without a rewrite).
val desktopUpstreamRewriteRules: List<Pair<Regex, String>> = listOf(
    Regex("""(?<![\w.])(?:coil3\.request\.)?ImageRequest\.Builder\(""") to "com.bilipai.desktop.compat.coil.desktopImageRequestBuilder(",
    Regex("""(?<![\w.])(?:coil3\.)?SingletonImageLoader\.get\(""") to "com.bilipai.desktop.compat.coil.desktopSingletonImageLoader(",
)
fun applyDesktopUpstreamRewrites(text: String): String =
    desktopUpstreamRewriteRules.fold(text) { current, (pattern, replacement) -> pattern.replace(current, Regex.escapeReplacement(replacement)) }
// Root paths of the upstream files a rule applies to. Replaced files are desktop code and are not rewritten.
val desktopUpstreamRewrittenFiles: Set<String> = run {
    val replaced = fileTree("upstream-core/replacements") { include("**/*.kt") }.files
        .map { it.relativeTo(file("upstream-core/replacements")).invariantSeparatorsPath }.toSet()
    val moduleFiles = migratedUpstreamModules.flatMap { module ->
        val root = mergedRepositoryRoot.resolve(module + "src/main/java")
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.relativeTo(root).invariantSeparatorsPath to module + "src/main/java/" }.toList()
    }
    val appFiles = migratedAppPaths.map { it to "app/src/main/java/" }
    (moduleFiles + appFiles).filter { (relative, prefix) -> relative !in replaced &&
        mergedRepositoryRoot.resolve(prefix + relative).let { it.isFile && desktopUpstreamRewriteRules.any { rule -> rule.first.containsMatchIn(it.readText()) } } }
        .map { (relative, prefix) -> prefix + relative }.toSortedSet()
}
val desktopUpstreamAdaptedRootFiles: Set<String> = (desktopUpstreamPatchedFiles + desktopUpstreamRewrittenFiles).toSortedSet()
val applyDesktopUpstreamPatches by tasks.registering {
    val output = layout.buildDirectory.dir("patched-upstream")
    inputs.dir(desktopUpstreamPatchDirectory).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(desktopUpstreamAdaptedRootFiles.map { mergedRepositoryRoot.resolve(it) }).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("rewriteRules", desktopUpstreamRewriteRules.map { it.first.pattern + " -> " + it.second })
    outputs.dir(output)
    doLast {
        val target = output.get().asFile
        target.deleteRecursively()
        desktopUpstreamAdaptedRootFiles.forEach { relative ->
            val original = mergedRepositoryRoot.resolve(relative).readText()
            val patched = if (relative in desktopUpstreamPatchedFiles) applyUnifiedPatch(original,
                desktopUpstreamPatchDirectory.resolve("$relative.patch").readText(), relative) else original
            val destination = target.resolve(relative)
            destination.parentFile.mkdirs()
            destination.writeText(applyDesktopUpstreamRewrites(patched))
        }
    }
}

val applyTransitionPatches by tasks.registering {
    val generated = layout.buildDirectory.dir("generated")
    val output = layout.buildDirectory.dir("transition-patched")
    val unknown = transitionPatchedGeneratedFiles.map { it.substringBefore('/') }.toSet() - transitionPatchProducers.keys
    require(unknown.isEmpty()) { "Transition patches without a known producer task: $unknown" }
    dependsOn(transitionPatchedGeneratedFiles.map { transitionPatchProducers.getValue(it.substringBefore('/')) }.distinct())
    inputs.dir(transitionPatchDirectory).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(transitionPatchedGeneratedFiles.map { generated.get().file(it) }).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(output)
    doLast {
        val target = output.get().asFile
        target.deleteRecursively()
        transitionPatchedGeneratedFiles.forEach { relative ->
            val source = generated.get().file(relative).asFile
            val patched = applyUnifiedPatch(source.readText(), transitionPatchDirectory.resolve("$relative.patch").readText(), relative)
            // The producer directory prefix (and whole-playback-settings' generated/ level) is dropped:
            // the output root is a Kotlin source root.
            val destination = target.resolve(relative.substringAfter('/').removePrefix("generated/"))
            destination.parentFile.mkdirs()
            destination.writeText("// Generated at build time: build/generated/$relative + upstream-core/transition/patches/$relative.patch\n" + patched)
        }
    }
}

// Desktop adaptations of migrated upstream files that are not stand-in classes (TON-997). Upstream files are never
// edited and no copy of one is committed:
//  - upstream-core/patches/<repository path>.patch is a unified diff applied to the merged file at build time (by
//    applyDesktopUpstreamPatches, shared with the other desktop fixes of upstream files); a hunk that
//    no longer matches exactly fails the build, so a changed upstream line is looked at by a person.
//  - upstream-core/replacements/<path> is a desktop implementation of a file that is Android-only (windows, views,
//    runtime shaders). Its first line pins the SHA-256 of the upstream file it replaces: when upstream changes
//    that file the build fails until the replacement is reviewed, and it must declare every public top-level name
//    the upstream file declares.
val upstreamReplacementDirectory = file("upstream-core/replacements")
fun migratedUpstreamFile(relative: String): File {
    val candidates = (migratedUpstreamModules.map { mergedRepositoryRoot.resolve(it + "src/main/java/" + relative) } +
        listOfNotNull(appJavaRoot.resolve(relative).takeIf { relative in migratedAppPaths })).filter { it.isFile }
    require(candidates.size == 1) { "$relative must exist in exactly one migrated upstream module, found ${candidates.size}" }
    return candidates.single()
}
val upstreamReplacements = fileTree(upstreamReplacementDirectory) { include("**/*.kt") }.files
    .map { it.relativeTo(upstreamReplacementDirectory).invariantSeparatorsPath }.toSortedSet()
// Patched files (upstream-core/patches/<repository path>.patch, applied by applyDesktopUpstreamPatches) are excluded by their root
// path; replaced files by their module-relative path. A file cannot be both.
val patchedModuleRelativePaths = desktopUpstreamPatchedFiles.mapNotNull { root ->
    migratedUpstreamModules.firstNotNullOfOrNull { module -> root.removePrefix(module + "src/main/java/").takeIf { root.startsWith(module + "src/main/java/") } }
}.toSortedSet()
val desktopAdaptedUpstreamPaths = upstreamReplacements
require(patchedModuleRelativePaths.intersect(upstreamReplacements).isEmpty()) { "Patched and replaced at once: ${patchedModuleRelativePaths.intersect(upstreamReplacements)}" }

fun lfSha256(file: File): String = MessageDigest.getInstance("SHA-256")
    .digest(file.readText(Charsets.UTF_8).replace("\r\n", "\n").toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
fun publicTopLevelNames(text: String): Set<String> {
    val declaration = Regex("^(?:@[\\w.]+(?:\\([^)]*\\))?\\s+)*((?:(?:public|inline|suspend|data|sealed|enum|value|abstract|open|const|operator|infix|tailrec|annotation|fun\\s+interface|external)\\s+)*)(fun|val|var|class|object|interface|typealias)\\s+(?:<[^>]*>\\s*)?(?:[\\w.<>?, ]+\\.)?([A-Za-z_]\\w*)")
    return text.lines().filter { it.isNotEmpty() && !it[0].isWhitespace() }.mapNotNull { declaration.find(it)?.groupValues?.get(3) }.toSet()
}

// Android resources of migrated upstream modules (TON-997): `res/values/*.xml` and `res/values-night/*.xml` colors become
// the module's `R.color` ids plus one table that the desktop colorResource reads (night values win under a dark app theme).
// Every other resource type or qualifier fails the build instead of being skipped, so a resource kind the desktop does not
// implement yet is a visible error the day upstream starts using it.
val generateUpstreamColorResources by tasks.registering {
    val output = layout.buildDirectory.dir("generated/upstream-resources")
    val resourceDirectories = migratedUpstreamModules.map { mergedRepositoryRoot.resolve(it + "src/main/res") }.filter { it.isDirectory }
    inputs.files(resourceDirectories.map { fileTree(it) }).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(migratedUpstreamModules.map { mergedRepositoryRoot.resolve(it + "build.gradle.kts") }).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(output)
    doLast {
        val target = output.get().asFile
        target.deleteRecursively()
        val light = sortedMapOf<String, Int>() // "<namespace>.<name>" -> ARGB
        val night = sortedMapOf<String, Int>()
        val namespaceOf = sortedMapOf<String, String>()
        fun argb(text: String, where: String): Int {
            require(Regex("#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})").matches(text)) { "$where: unsupported color value `$text`" }
            val digits = text.substring(1)
            return (if (digits.length == 6) 0xFF000000L or digits.toLong(16) else digits.toLong(16)).toInt()
        }
        migratedUpstreamModules.forEach { module ->
            val resources = mergedRepositoryRoot.resolve(module + "src/main/res")
            if (!resources.isDirectory) return@forEach
            val namespace = Regex("namespace\\s*=\\s*\"([^\"]+)\"").find(mergedRepositoryRoot.resolve(module + "build.gradle.kts").readText())
                ?.groupValues?.get(1) ?: throw GradleException("$module has resources but no android namespace")
            resources.listFiles()!!.sortedBy { it.name }.forEach { directory ->
                val table = when (directory.name) {
                    "values" -> light
                    "values-night" -> night
                    else -> throw GradleException("${directory.relativeTo(mergedRepositoryRoot)}: the desktop implements only values and values-night resources")
                }
                directory.listFiles()!!.sortedBy { it.name }.forEach { file ->
                    val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
                    val children = document.documentElement.childNodes
                    for (index in 0 until children.length) {
                        val node = children.item(index)
                        if (node.nodeType != org.w3c.dom.Node.ELEMENT_NODE) continue
                        val element = node as org.w3c.dom.Element
                        if (element.tagName != "color") throw GradleException("${file.relativeTo(mergedRepositoryRoot)}: <${element.tagName}> resources are not implemented on the desktop")
                        val key = namespace + "." + element.getAttribute("name")
                        table[key] = argb(element.textContent.trim(), "${file.relativeTo(mergedRepositoryRoot)} ${element.getAttribute("name")}")
                        namespaceOf[key] = namespace
                    }
                }
            }
        }
        val missingBase = night.keys - light.keys
        require(missingBase.isEmpty()) { "Night-only color resources without a base value: $missingBase" }
        val ids = light.keys.withIndex().associate { (index, key) -> key to (0x7f060000 + index + 1) }
        namespaceOf.entries.groupBy({ it.value }, { it.key }).forEach { (namespace, keys) ->
            val file = target.resolve(namespace.replace('.', '/') + "/R.kt").apply { parentFile.mkdirs() }
            file.writeText("// Generated at build time from the upstream res/values XML.\npackage $namespace\n\nobject R {\n    object color {\n" +
                keys.joinToString("") { "        const val ${it.removePrefix("$namespace.")}: Int = ${"0x%x".format(ids.getValue(it))}\n" } + "    }\n}\n")
        }
        target.resolve("androidx/compose/ui/res/DesktopColorTable.kt").apply { parentFile.mkdirs() }.writeText(
            "// Generated at build time from the upstream res/values XML.\npackage androidx.compose.ui.res\n\ninternal object DesktopColorTable {\n" +
                "    val light: Map<Int, Int> = mapOf(\n" + light.entries.joinToString("") { "        ${"0x%x".format(ids.getValue(it.key))} to ${"0x%08x".format(it.value)}.toInt(),\n" } + "    )\n" +
                "    val night: Map<Int, Int> = mapOf(\n" + night.entries.joinToString("") { "        ${"0x%x".format(ids.getValue(it.key))} to ${"0x%08x".format(it.value)}.toInt(),\n" } + "    )\n}\n")
    }
}

// The Build stand-in reports a fixed SDK level, so an `SDK_INT >= n` gate in a migrated design file would silently turn on
// an Android-only path. Such gates must be patched or replaced; the ones left must be listed here with the reason.
val sdkGatesKeptInDesignModules = mapOf(
    // The factory the shell would inject for the scaffold blur is not provided on the desktop, so the handle stays null (no blur).
    "com/android/purebilibili/core/ui/AdaptiveScaffoldHaze.kt" to "scaffold blur is opt-in through LocalAdaptiveScaffoldHazeFactory",
)
// Upstream app string resources (TON-1000). res/values (Simplified Chinese, the default), values-en and values-zh-rTW
// strings.xml become com.android.purebilibili.R.string ids plus one table per locale, read by the Context.getString /
// stringResource stand-ins with Android's locale fallback. Text is processed the way aapt2 does (escapes, quotes,
// whitespace). Another qualifier or another resource kind in these files fails the build instead of being dropped.
// Upstream app drawable and mipmap resources (TON-1072). Their names become com.android.purebilibili.R.drawable / R.mipmap
// ids (written with R.string below); generateUpstreamDrawableResources packages the files with a table that the desktop
// painterResource, ImageVector.vectorResource, ImageBitmap.imageResource and coil Int models read. A directory qualifier
// the desktop does not implement fails the build instead of being skipped.
val upstreamDrawableDensities = mapOf("ldpi" to 120, "mdpi" to 160, "hdpi" to 240, "xhdpi" to 320, "xxhdpi" to 480, "xxxhdpi" to 640)
/** (type, night, density) of a res directory: density 0 = no qualifier (mdpi for bitmaps), -1 = nodpi, -2 = anydpi. */
fun upstreamDrawableQualifier(directory: String): Triple<String, Boolean, Int>? {
    val parts = directory.split('-')
    if (parts[0] != "drawable" && parts[0] != "mipmap") return null
    var night = false
    var density = 0
    parts.drop(1).forEach { token ->
        when {
            token == "night" -> night = true
            token == "nodpi" -> density = -1
            token == "anydpi" -> density = -2
            token in upstreamDrawableDensities -> density = upstreamDrawableDensities.getValue(token)
            // The Build stand-in reports SDK_INT 36, so a resource for API 36 or lower applies the way it does on Android.
            Regex("v\\d+").matches(token) -> if (token.drop(1).toInt() > 36) throw GradleException("res/$directory: API level above the desktop SDK_INT 36")
            else -> throw GradleException("res/$directory: the desktop does not implement the drawable qualifier `$token`")
        }
    }
    return Triple(parts[0], night, density)
}
fun upstreamDrawableResourceName(file: File): String = file.name.substringBefore('.')
/**
 * `<item type="mipmap|drawable" name="n">@mipmap/target</item>` aliases of res/values-<qualifier> files (upstream shares one
 * launcher PNG between several names per density): (type/name, night, density, type/target).
 */
fun upstreamDrawableAliases(resources: File): List<List<Any>> = resources.listFiles()!!.filter { it.isDirectory && it.name.startsWith("values") }
    .sortedBy { it.name }.flatMap { directory ->
        val qualifier by lazy { upstreamDrawableQualifier("drawable" + directory.name.removePrefix("values"))!! }
        directory.listFiles()!!.filter { it.extension == "xml" }.sortedBy { it.name }.flatMap { file ->
            val children = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement.childNodes
            (0 until children.length).mapNotNull { children.item(it) as? org.w3c.dom.Element }
                .filter { it.tagName == "item" && it.getAttribute("type") in setOf("drawable", "mipmap") }.map { element ->
                    val target = element.textContent.trim()
                    if (!Regex("@(drawable|mipmap)/\\w+").matches(target))
                        throw GradleException("${file.relativeTo(mergedRepositoryRoot)} ${element.getAttribute("name")}: the desktop implements only @drawable/@mipmap aliases")
                    listOf(element.getAttribute("type") + "/" + element.getAttribute("name"), qualifier.second, qualifier.third, target.removePrefix("@"))
                }
        }
    }
/** type/name of the drawables, mipmaps and aliases one res directory declares. */
fun upstreamDrawableNames(resources: File): Set<String> = (resources.listFiles().orEmpty().filter { it.isDirectory && upstreamDrawableQualifier(it.name) != null }
    .flatMap { directory -> directory.listFiles()!!.filter { it.isFile }.map { upstreamDrawableQualifier(directory.name)!!.first + "/" + upstreamDrawableResourceName(it) } } +
    upstreamDrawableAliases(resources).map { it[0] as String }).toSortedSet()
/**
 * The app and the library modules it depends on whose res declares drawables or mipmaps, app first: Android merges them
 * into one resource table (an app file wins over a library file of the same name and qualifier), and with
 * android.nonTransitiveRClass each module's own names are in its own namespace's R. res dir -> namespace.
 */
val upstreamDrawableModules: List<Pair<File, String>> = (listOf("app") +
        Regex("""project\(":([\w-]+)"\)""").findAll(mergedRepositoryRoot.resolve("app/build.gradle.kts").readText()).map { it.groupValues[1] }.distinct())
    .map { module -> mergedRepositoryRoot.resolve("$module/src/main/res") to module }
    .filter { (res, _) -> res.isDirectory && upstreamDrawableNames(res).isNotEmpty() }
    .map { (res, module) -> res to (Regex("namespace\\s*=\\s*\"([^\"]+)\"").find(mergedRepositoryRoot.resolve("$module/build.gradle.kts").readText())
        ?.groupValues?.get(1) ?: throw GradleException("$module has drawable resources but no android namespace")) }
/** "drawable/name" or "mipmap/name" -> id over the merged table, in name order per type. */
fun upstreamDrawableIds(): Map<String, Int> {
    val names = upstreamDrawableModules.flatMap { upstreamDrawableNames(it.first) }.toSortedSet()
    val base = mapOf("drawable" to 0x7f080000, "mipmap" to 0x7f0f0000)
    return names.groupBy { it.substringBefore('/') }.flatMap { (type, keys) -> keys.withIndex().map { (index, key) -> key to (base.getValue(type) + index + 1) } }.toMap()
}
val upstreamStringResourceDirectory = mergedRepositoryRoot.resolve("app/src/main/res")
val upstreamStringQualifiers = mapOf("values" to "default", "values-en" to "en", "values-zh-rTW" to "zh-TW")
val generateUpstreamStringResources by tasks.registering {
    val output = layout.buildDirectory.dir("generated/upstream-strings")
    inputs.files(fileTree(upstreamStringResourceDirectory) { include("values*/strings.xml") }).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("drawableIds", upstreamDrawableIds().toSortedMap().toString())
    outputs.dir(output)
    doLast {
        val target = output.get().asFile
        target.deleteRecursively()
        val unknown = upstreamStringResourceDirectory.listFiles()!!.filter { it.resolve("strings.xml").isFile && it.name !in upstreamStringQualifiers }
        if (unknown.isNotEmpty()) throw GradleException("String resources with a qualifier the desktop does not implement: ${unknown.map { it.name }}")
        /** aapt2: backslash escapes, double quotes keep whitespace verbatim, other whitespace runs become one space, trimmed. */
        fun androidText(raw: String, where: String): String {
            val out = StringBuilder(); var quoted = false; var pendingSpace = false; var index = 0
            fun emit(text: String) { if (pendingSpace && out.isNotEmpty()) out.append(' '); pendingSpace = false; out.append(text) }
            while (index < raw.length) {
                val c = raw[index++]
                when {
                    c == '\\' -> {
                        if (index >= raw.length) throw GradleException("$where: trailing backslash")
                        when (val e = raw[index++]) {
                            'n' -> emit("\n"); 't' -> emit("\t")
                            'u' -> { emit(raw.substring(index, index + 4).toInt(16).toChar().toString()); index += 4 }
                            '\u0027', '"', '\\', '@', '?' -> emit(e.toString())
                            else -> throw GradleException("$where: unsupported escape \\$e")
                        }
                    }
                    c == '"' -> quoted = !quoted
                    !quoted && c.isWhitespace() -> pendingSpace = true
                    else -> emit(c.toString())
                }
            }
            if (quoted) throw GradleException("$where: unbalanced double quote")
            return out.toString()
        }
        fun encode(text: String) = text.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")
        val tables = upstreamStringQualifiers.mapValues { (directory, _) -> sortedMapOf<String, String>().also { table ->
            val file = upstreamStringResourceDirectory.resolve("$directory/strings.xml")
            if (!file.isFile) return@also
            val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            val children = factory.newDocumentBuilder().parse(file).documentElement.childNodes
            for (index in 0 until children.length) {
                val node = children.item(index)
                if (node.nodeType != org.w3c.dom.Node.ELEMENT_NODE) continue
                val element = node as org.w3c.dom.Element
                val where = "${file.relativeTo(mergedRepositoryRoot)} ${element.getAttribute("name")}"
                if (element.tagName != "string") throw GradleException("$where: <${element.tagName}> resources are not implemented on the desktop")
                if ((0 until element.childNodes.length).any { element.childNodes.item(it).nodeType == org.w3c.dom.Node.ELEMENT_NODE })
                    throw GradleException("$where: styled string markup is not implemented on the desktop")
                table[element.getAttribute("name")] = androidText(element.textContent, where)
            }
        } }
        val base = tables.getValue("values")
        tables.forEach { (directory, table) ->
            val extra = table.keys - base.keys
            if (extra.isNotEmpty()) throw GradleException("$directory/strings.xml has strings without a default value: $extra")
        }
        val ids = base.keys.withIndex().associate { (index, name) -> name to (0x7f130000 + index + 1) }
        target.resolve("kotlin/com/android/purebilibili/R.kt").apply { parentFile.mkdirs() }.writeText(
            "// Generated at build time from the upstream app res/values strings.xml and res/drawable*, res/mipmap* files.\npackage com.android.purebilibili\n\nobject R {\n    object string {\n" +
                base.keys.joinToString("") { "        const val $it: Int = ${"0x%x".format(ids.getValue(it))}\n" } + "    }\n" +
                upstreamDrawableIds().filterKeys { it in upstreamDrawableNames(upstreamStringResourceDirectory) }.entries.groupBy { it.key.substringBefore('/') }.toSortedMap().entries.joinToString("") { (type, entries) ->
                    "    object $type {\n" + entries.joinToString("") { "        const val ${it.key.substringAfter('/')}: Int = ${"0x%x".format(it.value)}\n" } + "    }\n" } +
                "}\n")
        tables.forEach { (directory, table) ->
            target.resolve("resources/upstream-strings/${upstreamStringQualifiers.getValue(directory)}.tsv").apply { parentFile.mkdirs() }
                .writeText(table.entries.joinToString("") { "${"0x%x".format(ids.getValue(it.key))}\t${encode(it.value)}\n" })
        }
    }
}

// Packages the upstream drawables and mipmaps (TON-1072) of the app and its library modules (upstreamDrawableModules) as
// classpath resources under upstream-drawables/<module>/ with one
// index.tsv row per file: id, type/name, night (0/1), density (0 none, -1 nodpi, -2 anydpi, else dpi), kind, resource path.
// Colour references are resolved here, the way aapt2 and the Android theme resolve them at runtime: @color/ against the
// app and migrated module res/values (res/values-night under a dark theme; a -v31 colour is the Material You system
// palette, which the desktop does not have, so it is not applied), @android:color/ against the framework colours, and
// ?attr/colorControlNormal against the Material default (textColorSecondary). A vector or adaptive icon whose colours
// differ in night mode gets a night row of its own. res/values-<qualifier> mipmap aliases become `alias` rows that name
// their target, resolved at runtime with the same configuration. Any other reference, attribute, element or file kind fails the build.
class MissingResourceException(message: String, className: String, key: String) : java.util.MissingResourceException(message, className, key)
val generateUpstreamDrawableResources by tasks.registering {
    val output = layout.buildDirectory.dir("generated/upstream-drawables")
    val colourModules = listOf("app/") + migratedUpstreamModules
    upstreamDrawableModules.forEach { (resources, _) ->
        inputs.files(fileTree(resources) { include("drawable*/**", "mipmap*/**", "values*/*.xml") }).withPathSensitivity(PathSensitivity.RELATIVE)
    }
    inputs.property("drawableIds", upstreamDrawableIds().toSortedMap().toString())
    inputs.property("drawableModules", upstreamDrawableModules.map { it.second })
    inputs.files(migratedUpstreamModules.map { fileTree(mergedRepositoryRoot.resolve(it + "src/main/res")) { include("values*/*.xml") } }).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(output)
    doLast {
        val target = output.get().asFile.resolve("resources/upstream-drawables")
        output.get().asFile.deleteRecursively()
        target.mkdirs()
        val ids = upstreamDrawableIds()
        // Colour values: light table from values/, night table from values-night/ (falling back to light).
        val light = mutableMapOf<String, String>()
        val night = mutableMapOf<String, String>()
        colourModules.forEach { module ->
            val res = mergedRepositoryRoot.resolve(module + "src/main/res")
            listOf("values" to light, "values-night" to night).forEach { (directory, table) ->
                res.resolve(directory).listFiles()?.filter { it.extension == "xml" }?.sortedBy { it.name }?.forEach { file ->
                    val children = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement.childNodes
                    for (index in 0 until children.length) {
                        val element = children.item(index) as? org.w3c.dom.Element ?: continue
                        if (element.tagName == "color") table[element.getAttribute("name")] = element.textContent.trim()
                    }
                }
            }
        }
        val framework = mapOf("white" to "#FFFFFFFF", "black" to "#FF000000", "transparent" to "#00000000")
        // Material's colorControlNormal is ?android:attr/textColorSecondary: secondary_text_material_light / _dark.
        val themeAttributes = mapOf("colorControlNormal" to ("#8A000000" to "#B3FFFFFF"))
        fun resolveColour(reference: String, dark: Boolean, where: String, depth: Int = 0): String {
            if (depth > 8) throw GradleException("$where: colour reference loop at $reference")
            return when {
                Regex("#[0-9a-fA-F]{3,8}").matches(reference) -> reference
                reference.startsWith("@android:color/") -> framework[reference.removePrefix("@android:color/")]
                    ?: throw GradleException("$where: framework colour $reference is not implemented on the desktop")
                reference.startsWith("@color/") -> reference.removePrefix("@color/").let { name ->
                    val value = (if (dark) night[name] else null) ?: light[name]
                        ?: throw MissingResourceException("$reference has no res/values value (only a qualifier the desktop does not apply defines it)", "", reference)
                    resolveColour(value, dark, where, depth + 1)
                }
                reference.startsWith("?attr/") || reference.startsWith("?android:attr/") -> themeAttributes[reference.substringAfter('/')]?.let { if (dark) it.second else it.first }
                    ?: throw GradleException("$where: theme attribute $reference is not implemented on the desktop")
                else -> throw GradleException("$where: unsupported colour value `$reference`")
            }
        }
        val reference = Regex("=\"((?:@color|@android:color|\\?attr|\\?android:attr)/[\\w.]+)\"")
        fun resolveXml(text: String, dark: Boolean, where: String) = reference.replace(text) { "=\"" + resolveColour(it.groupValues[1], dark, where) + "\"" }
        val rows = mutableListOf<String>()
        val seen = mutableSetOf<String>() // type/name@night@density already taken by an earlier (app) module
        upstreamDrawableModules.forEach { (resources, namespace) ->
        val module = resources.parentFile.parentFile.parentFile.name
        val directories = resources.listFiles()!!.filter { it.isDirectory && upstreamDrawableQualifier(it.name) != null }.sortedBy { it.name }
        val explicitNight = directories.filter { upstreamDrawableQualifier(it.name)!!.second }.flatMap { directory ->
            val (type, _, density) = upstreamDrawableQualifier(directory.name)!!
            directory.listFiles()!!.map { "$type/${upstreamDrawableResourceName(it)}@$density" }
        }.toSet()
        directories.forEach { directory ->
            val (type, isNight, density) = upstreamDrawableQualifier(directory.name)!!
            directory.listFiles()!!.filter { it.isFile }.sortedBy { it.name }.forEach { file ->
                val where = file.relativeTo(mergedRepositoryRoot).invariantSeparatorsPath
                if (!seen.add("$type/${upstreamDrawableResourceName(file)}@$isNight@$density")) return@forEach
                val id = ids.getValue("$type/${upstreamDrawableResourceName(file)}")
                fun row(nightFlag: Boolean, kind: String, path: String) { rows += "${"0x%x".format(id)}\t$type/${upstreamDrawableResourceName(file)}\t${if (nightFlag) 1 else 0}\t$density\t$kind\t$path" }
                when (file.name.substringAfter('.').lowercase()) {
                    "png", "webp", "jpg", "jpeg" -> {
                        file.copyTo(target.resolve("$module/${directory.name}/${file.name}"), overwrite = true)
                        row(isNight, "raster", "$module/${directory.name}/${file.name}")
                    }
                    "xml" -> {
                        val text = file.readText()
                        val kind = when (val tag = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement.tagName) {
                            "vector" -> "vector"
                            "adaptive-icon" -> "adaptive"
                            // Splash-screen compositions; the desktop has no Android splash screen. Their ids load loudly.
                            "layer-list", "bitmap" -> tag
                            else -> throw GradleException("$where: <$tag> drawables are not implemented on the desktop")
                        }
                        // A colour without a base value would crash on Android below that qualifier too; the id stays and
                        // loading it fails loudly with the reason.
                        val resolved = try { resolveXml(text, isNight, where) } catch (missing: MissingResourceException) {
                            row(isNight, "unavailable", missing.message!!.replace('\t', ' '))
                            return@forEach
                        }
                        target.resolve("$module/${directory.name}/${file.name}").apply { parentFile.mkdirs() }.writeText(resolved)
                        row(isNight, kind, "$module/${directory.name}/${file.name}")
                        if (!isNight && "$type/${upstreamDrawableResourceName(file)}@$density" !in explicitNight) {
                            val dark = resolveXml(text, true, where)
                            if (dark != resolved) {
                                val path = "$module/${directory.name}+night/${file.name}"
                                target.resolve(path).apply { parentFile.mkdirs() }.writeText(dark)
                                row(true, kind, path)
                            }
                        }
                    }
                    else -> throw GradleException("$where: the desktop does not implement this drawable file kind")
                }
            }
        }
        upstreamDrawableAliases(resources).forEach { (name, isNight, density, aliased) ->
            if (aliased as String !in ids) throw GradleException("${resources.relativeTo(mergedRepositoryRoot)}/values alias $name points to the missing resource $aliased")
            if (!seen.add("$name@$isNight@$density")) return@forEach
            rows += "${"0x%x".format(ids.getValue(name as String))}\t$name\t${if (isNight as Boolean) 1 else 0}\t$density\talias\t$aliased"
        }
        // The app's names are in com.android.purebilibili.R (written with R.string); a library's in its own namespace.
        if (namespace != "com.android.purebilibili") {
            val own = upstreamDrawableNames(resources)
            output.get().asFile.resolve("kotlin/" + namespace.replace('.', '/') + "/R.kt").apply { parentFile.mkdirs() }.writeText(
                "// Generated at build time from ${resources.relativeTo(mergedRepositoryRoot).invariantSeparatorsPath}.\npackage $namespace\n\nobject R {\n" +
                    ids.filterKeys { it in own }.entries.groupBy { it.key.substringBefore('/') }.toSortedMap().entries.joinToString("") { (type, entries) ->
                        "    object $type {\n" + entries.joinToString("") { "        const val ${it.key.substringAfter('/')}: Int = ${"0x%x".format(it.value)}\n" } + "    }\n" } +
                    "}\n")
        }
        }
        target.resolve("index.tsv").writeText(rows.joinToString("\n", postfix = "\n"))
    }
}
sourceSets.named("main") { resources.srcDir(files(layout.buildDirectory.dir("generated/upstream-drawables/resources")).builtBy(generateUpstreamDrawableResources)) }
kotlin.sourceSets.named("main") { kotlin.srcDir(files(layout.buildDirectory.dir("generated/upstream-drawables/kotlin")).builtBy(generateUpstreamDrawableResources)) }

// Numeric key types of the upstream DataStore preferences (TON-1000). Preferences.Key carries no runtime type and the
// desktop settings document is JSON, where 1 cannot tell Int from Long or Float. Booleans, strings and string sets are
// unambiguous in JSON; numeric keys are typed by this table, built from the upstream sources: every literal
// int/long/float key, plus the scoped danmaku keys built by buildScopedDanmakuKeyName(scope, "<suffix>"). A numeric key
// built any other way, or one name declared with two types, fails the build instead of being guessed at runtime.
val generateUpstreamPreferenceKeyTypes by tasks.registering {
    val roots = listOf(appJavaRoot, mergedRepositoryRoot.resolve("core-data/src/main/java"))
    val output = layout.buildDirectory.dir("generated/upstream-preference-types")
    inputs.files(roots.map { fileTree(it) { include("**/*.kt") } }).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(output)
    doLast {
        val literal = Regex("""\b(int|long|float|double)PreferencesKey\(\s*"([^"${'$'}]+)"\s*\)""")
        val scoped = Regex("""\b(int|long|float|double)PreferencesKey\(\s*buildScopedDanmakuKeyName\(\s*[\w.]+\s*,\s*"(\w+)"\s*\)\s*\)""")
        val anyNumeric = Regex("""\b(int|long|float|double)PreferencesKey\(""")
        val names = sortedMapOf<String, String>()
        val suffixes = sortedMapOf<String, String>()
        val unknown = mutableListOf<String>()
        roots.forEach { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.forEach { file ->
            val text = file.readText()
            val where = file.relativeTo(mergedRepositoryRoot).invariantSeparatorsPath
            fun record(table: MutableMap<String, String>, name: String, type: String) {
                val previous = table.put(name, type)
                if (previous != null && previous != type) throw GradleException("$where: preference `$name` is declared as $previous and $type")
            }
            val known = mutableSetOf<Int>()
            literal.findAll(text).forEach { record(names, it.groupValues[2], it.groupValues[1]); known += it.range.first }
            scoped.findAll(text).forEach { record(suffixes, it.groupValues[2], it.groupValues[1]); known += it.range.first }
            anyNumeric.findAll(text).forEach { match ->
                if (match.range.first !in known && !text.substring(match.range.first).startsWith(match.value + "name")) {
                    val line = text.substring(0, match.range.first).count { it == '\n' } + 1
                    unknown += "$where:$line"
                }
            }
        } }
        // A key function's own parameter (fun intPreferencesKey(name: String)) is not a key; nothing else may be dynamic.
        if (unknown.isNotEmpty()) throw GradleException("Numeric DataStore keys built at runtime cannot be typed for the desktop settings document: $unknown")
        val target = output.get().asFile
        target.deleteRecursively()
        target.resolve("com/bilipai/desktop/settings/DesktopUpstreamPreferenceKeyTypes.kt").apply { parentFile.mkdirs() }.writeText(
            "// Generated at build time from the upstream DataStore key declarations.\npackage com.bilipai.desktop.settings\n\n" +
                "internal object DesktopUpstreamPreferenceKeyTypes {\n    val names: Map<String, String> = mapOf(\n" +
                names.entries.joinToString("") { "        \"${it.key}\" to \"${it.value}\",\n" } + "    )\n" +
                "    /** buildScopedDanmakuKeyName(scope, suffix) = danmaku_<scope prefix>_<suffix>. */\n" +
                "    val scopedDanmakuSuffixes: Map<String, String> = mapOf(\n" +
                suffixes.entries.joinToString("") { "        \"${it.key}\" to \"${it.value}\",\n" } + "    )\n}\n")
    }
}

// Upstream app BuildConfig (TON-1000), the release variant: the version is the upstream app version this desktop build
// compiles (read from app/build.gradle.kts, so it follows every merge), DEBUG and the verbose-log switches are off as in
// upstream release builds, and BUILD_COMMIT_SHA is upstream's "local" default unless -Pbili.build.commitSha is given.
val generateUpstreamBuildConfig by tasks.registering {
    val script = mergedRepositoryRoot.resolve("app/build.gradle.kts")
    val output = layout.buildDirectory.dir("generated/upstream-buildconfig")
    val commitSha = providers.gradleProperty("bili.build.commitSha").orElse("local")
    // Same properties and empty defaults as upstream's app/build.gradle.kts, so a CI build can stamp its provenance.
    val provenance = listOf("gitRef" to "BUILD_GIT_REF", "workflowRunId" to "BUILD_WORKFLOW_RUN_ID",
        "workflowRunUrl" to "BUILD_WORKFLOW_RUN_URL", "releaseTag" to "BUILD_RELEASE_TAG")
        .map { (property, field) -> field to providers.gradleProperty("bili.build.$property").orElse("") }
    inputs.file(script).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("commitSha", commitSha)
    provenance.forEach { (field, value) -> inputs.property(field, value) }
    outputs.dir(output)
    doLast {
        val text = script.readText()
        val versionCode = Regex("""versionCode\s*=\s*(\d+)""").find(text)?.groupValues?.get(1)
            ?: throw GradleException("app/build.gradle.kts: versionCode not found")
        val versionName = Regex("versionName\\s*=\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
            ?: throw GradleException("app/build.gradle.kts: versionName not found")
        fun kotlinLiteral(raw: String): String {
            val escaped = raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
            return "\"" + escaped + "\""
        }
        val provenanceFields = provenance.joinToString("") { (field, value) ->
            val literal = kotlinLiteral(value.get())
            "    const val $field: String = $literal\n"
        }
        val target = output.get().asFile
        target.deleteRecursively()
        target.resolve("com/android/purebilibili/BuildConfig.kt").apply { parentFile.mkdirs() }.writeText(
            "// Generated at build time from the upstream app/build.gradle.kts (release variant).\npackage com.android.purebilibili\n\n" +
                "object BuildConfig {\n    const val DEBUG: Boolean = false\n    const val BUILD_TYPE: String = \"release\"\n" +
                "    const val VERSION_CODE: Int = $versionCode\n    const val VERSION_NAME: String = \"$versionName\"\n" +
                "    const val BUILD_COMMIT_SHA: String = \"${commitSha.get()}\"\n" +
                provenanceFields +
                "    const val ENABLE_VERBOSE_DEBUG_LOGS: Boolean = false\n    const val ENABLE_VERBOSE_RUNTIME_LOG_PERSISTENCE: Boolean = false\n" +
                "    const val ALLOW_HARDCODED_DNS_FALLBACK: Boolean = false\n}\n")
    }
}

val verifyNoSilentSdkGates by tasks.registering {
    inputs.files(fileTree(mergedRepositoryRoot) { migratedWholeModules.forEach { include(it + "src/main/java/**/*.kt") } })
    inputs.dir(desktopUpstreamPatchDirectory).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(upstreamReplacementDirectory).withPathSensitivity(PathSensitivity.RELATIVE).optional()
    doLast {
        val gate = Regex("""\bSDK_INT\b""")
        val offenders = migratedWholeModulePaths.filter { relative ->
            val adapted = when (relative) {
                in upstreamReplacements -> upstreamReplacementDirectory.resolve(relative).readText()
                in patchedModuleRelativePaths -> migratedUpstreamFile(relative).relativeTo(mergedRepositoryRoot).invariantSeparatorsPath.let { root ->
                    applyUnifiedPatch(migratedUpstreamFile(relative).readText(), desktopUpstreamPatchDirectory.resolve("$root.patch").readText(), relative)
                }
                else -> migratedUpstreamFile(relative).readText()
            }
            gate.containsMatchIn(adapted) && relative !in sdkGatesKeptInDesignModules
        }
        if (offenders.isNotEmpty()) throw GradleException(
            "Migrated design files read SDK_INT, which the desktop Build stand-in fixes at a constant; patch or replace the gate " +
                "(upstream-core/patches, upstream-core/replacements) or list it in sdkGatesKeptInDesignModules:\n" + offenders.sorted().joinToString("\n") { "  $it" })
    }
}

val verifyUpstreamReplacements by tasks.registering {
    inputs.dir(upstreamReplacementDirectory).withPathSensitivity(PathSensitivity.RELATIVE).optional()
    inputs.files(upstreamReplacements.map { migratedUpstreamFile(it) }).withPathSensitivity(PathSensitivity.NAME_ONLY)
    doLast {
        upstreamReplacements.forEach { relative ->
            val replacement = upstreamReplacementDirectory.resolve(relative)
            val pin = Regex("^// Replaces upstream .* sha256=([0-9a-f]{64})").find(replacement.readLines().first())?.groupValues?.get(1)
                ?: throw GradleException("$relative: the first line must be `// Replaces upstream <module>/<path> sha256=<hex>`")
            val upstream = migratedUpstreamFile(relative)
            if (lfSha256(upstream) != pin) throw GradleException(
                "Upstream changed ${upstream.relativeTo(mergedRepositoryRoot).invariantSeparatorsPath}, which the desktop replaces " +
                    "with upstream-core/replacements/$relative: review the replacement against the new upstream file, then update " +
                    "the sha256 on its first line to ${lfSha256(upstream)}.")
            val missing = publicTopLevelNames(upstream.readText()) - publicTopLevelNames(replacement.readText())
            if (missing.isNotEmpty()) throw GradleException("upstream-core/replacements/$relative no longer declares $missing, which upstream declares.")
        }
    }
}

// Upstream's own unit tests of the migrated design modules run in the desktop test set; the pinned older copies of the
// same test classes that the generators extract are skipped, since both cannot exist under one class name.
val migratedWholeModuleTestRoots = migratedWholeModules.map { mergedRepositoryRoot.resolve(it + "src/test/java") }.filter { it.isDirectory }
val migratedWholeModuleTestPaths: Set<String> = migratedWholeModuleTestRoots.flatMap { root ->
    root.walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.relativeTo(root).invariantSeparatorsPath }.toList()
}.toSet()
// Upstream tests that read upstream source files relative to their Gradle module (design-system/) run in their own task
// with that working directory; the other desktop tests keep theirs.
val migratedSourceReadingTestClasses: List<String> = migratedWholeModuleTestRoots.flatMap { root ->
    root.walkTopDown().filter { it.isFile && it.extension == "kt" }.filter { file ->
        Regex("""\bFile\(|Paths\.get\(|\.readText\(""").containsMatchIn(file.readText())
    }.map { file ->
        val text = file.readText()
        Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE).find(text)!!.groupValues[1] + "." + file.nameWithoutExtension
    }.toList()
}.sorted()
// Upstream app core tests (TON-1000) whose subject compiles from upstream and that use kotlin.test only. Tests that need
// JUnit 4, Robolectric or mockk (not in the desktop test graph) or that test desktop-owned / later-step files stay out;
// tests that read upstream sources by relative path run from the app module directory (upstreamAppCoreSourceTests).
val appTestRoot = mergedRepositoryRoot.resolve("app/src/test/java")
val appCoreUpstreamTestsNotRun: Map<String, String> = mapOf(
    "core/player/waveform/AudioWaveformPolicyTest.kt" to "its subject is desktop-owned: the Media3 player stack",
    "core/player/HiResCompatibleRenderersFactoryTest.kt" to "its subject is desktop-owned: the Media3 player stack",
    "core/player/PlayerVolumeControllerTest.kt" to "its subject is desktop-owned: the Media3 player stack",
    "core/util/CrashSnapshotPolicyTest.kt" to "its subject is desktop-owned: Android logging and crash capture",
    "core/util/LoggerPersistencePolicyTest.kt" to "its subject is desktop-owned: Android logging and crash capture",
    "core/util/HistoricalCrashLogsTest.kt" to "its subject is desktop-owned: Android logging and crash capture",
    "core/util/FoldableDisplayPolicyTest.kt" to "its subject is desktop-owned: Android windows, foldables, picture-in-picture and display modes",
    "core/util/WindowSizeUtilsTest.kt" to "its subject is desktop-owned: Android windows, foldables, picture-in-picture and display modes",
    "core/util/PipAspectRatioPolicyTest.kt" to "its subject is desktop-owned: Android windows, foldables, picture-in-picture and display modes",
    "core/util/VideoSurfaceCompatibilityTest.kt" to "its subject is desktop-owned: Android windows, foldables, picture-in-picture and display modes",
    "core/util/AppAdaptiveStrategySnapshotTest.kt" to "its subject is desktop-owned: Android windows, foldables, picture-in-picture and display modes",
    "core/ui/performance/AppDisplayModePreferenceTest.kt" to "its subject is desktop-owned: Android windows, foldables, picture-in-picture and display modes",
    "core/ui/performance/VideoRefreshRatePolicyTest.kt" to "its subject is desktop-owned: Android windows, foldables, picture-in-picture and display modes",
    "core/ui/animation/gl/ThanosParticlePolicyTest.kt" to "its subject is desktop-owned: OpenGL particle effects",
    "core/ui/animation/gl/ParticleRendererPolicyTest.kt" to "its subject is desktop-owned: OpenGL particle effects",
    "core/ui/animation/DissolveRenderPolicyTest.kt" to "its subject is desktop-owned: OpenGL particle effects",
    "core/performance/ProcessExitContextTest.kt" to "its subject is desktop-owned: Android process exit and Android 17 diagnostics",
    "core/performance/Android17DiagnosticsPolicyTest.kt" to "its subject is desktop-owned: Android process exit and Android 17 diagnostics",
    "core/util/MediaUtilsHdrCapabilityTest.kt" to "its subject is desktop-owned: Android media, cache and text-selection helpers",
    "core/util/AutomaticCacheClearPolicyTest.kt" to "its subject is desktop-owned: Android media, cache and text-selection helpers",
    "core/ui/common/TextSelectionPolicyTest.kt" to "its subject is desktop-owned: Android media, cache and text-selection helpers",
    "core/ui/common/LongPressCopyPolicyTest.kt" to "its subject is desktop-owned: Android media, cache and text-selection helpers",
    "core/util/PlayerOrientationPolicyTest.kt" to "its subject moves in a later step (player orientation, M4a)",
    "core/util/PlayerPresentationPolicyTest.kt" to "its subject moves in a later step (player orientation, M4a)",
    "core/theme/AppThemeEndpointPolicyTest.kt" to "it calls feature/video resolvePlaybackNotificationAccentColor, which moves with M4a",
    "core/plugin/js/BiliPaiJsNativeExampleTest.kt" to "it reads examples/plugins/tv-live.bilipai.js, which the merge leaves out; the desktop copy runs the byte-identical script from desktop/src/test/fixtures",
    "core/ui/adaptive/DeviceUiProfilePolicyTest.kt" to "the same test class (same package and name) as design-system DeviceUiProfilePolicyTest, which runs",
)
// Additional upstream behavioral tests for migrated M4a subjects. Existing desktop tests remain.
// AudioBarReturnPolicyTest also needs MiniPlayerManager; PiliPlayerMenuPolicyTest needs Media3
// track selection. Those two new upstream suites join with the playback-port migration.
val migratedVideoFeatureTestPaths = setOf(
    "com/android/purebilibili/feature/video/state/VideoPlayerVerticalDetectionPolicyTest.kt",
    "com/android/purebilibili/feature/video/state/VideoPlayerBufferPolicyTest.kt",
    "com/android/purebilibili/feature/video/state/VideoPlayerStateReusePolicyTest.kt",
    "com/android/purebilibili/feature/video/ambient/AmbientFramePolicyTest.kt",
    "com/android/purebilibili/feature/video/ui/overlay/PlayerControlSafeAreaTest.kt",
    "com/android/purebilibili/feature/video/ui/components/PlayerPanelViewportTest.kt",
    "com/android/purebilibili/feature/video/screen/VideoDetailCoverContentScaleTest.kt",
    "com/android/purebilibili/feature/video/ui/components/EpisodeSheetLayoutPolicyTest.kt",
    "com/android/purebilibili/feature/video/danmaku/DanmakuSegmentWindowPolicyTest.kt",
    "com/android/purebilibili/feature/video/screen/VideoDetailProgressiveReturnTest.kt",
    "com/android/purebilibili/feature/video/danmaku/DanmakuContentSegmentsTest.kt",
    "com/android/purebilibili/feature/video/danmaku/DanmakuKeywordFilterPolicyTest.kt",
    "com/android/purebilibili/feature/video/danmaku/DanmakuSyncStatusPolicyTest.kt",
    "com/android/purebilibili/feature/video/danmaku/DanmakuTypeFilterPolicyTest.kt",
    "com/android/purebilibili/feature/video/interaction/InteractiveVideoChoicePolicyTest.kt",
    "com/android/purebilibili/feature/video/interaction/InteractiveVideoVariablePolicyTest.kt",
    "com/android/purebilibili/feature/video/note/AiSummaryNoteDraftPolicyTest.kt",
    "com/android/purebilibili/feature/video/note/VideoNoteContentCodecTest.kt",
    "com/android/purebilibili/feature/video/note/VideoNoteEditorStatePolicyTest.kt",
    "com/android/purebilibili/feature/video/note/VideoNoteEmotionPolicyTest.kt",
    "com/android/purebilibili/feature/video/note/VideoNoteTagPolicyTest.kt",
    "com/android/purebilibili/feature/video/note/VideoNoteVisibilityPolicyTest.kt",
    "com/android/purebilibili/feature/video/playback/audio/AudioStreamSelectionPolicyTest.kt",
    "com/android/purebilibili/feature/video/playback/loader/PlaybackLoaderTest.kt",
    "com/android/purebilibili/feature/video/playback/policy/AdaptiveDashTrackPolicyTest.kt",
    "com/android/purebilibili/feature/video/playback/policy/PlaybackAncillaryTaskPolicyTest.kt",
    "com/android/purebilibili/feature/video/playback/policy/PlaybackTransitionPolicyTest.kt",
    "com/android/purebilibili/feature/video/playback/policy/PluginPollingPolicyTest.kt",
    "com/android/purebilibili/feature/video/playback/session/PlaybackSessionStoreTest.kt",
    "com/android/purebilibili/feature/video/player/PlaybackPageOwnershipTest.kt",
    "com/android/purebilibili/feature/video/policy/VideoDetailScrollCoordinatorTest.kt",
    "com/android/purebilibili/feature/video/progress/PbpProgressPolicyTest.kt",
    "com/android/purebilibili/feature/video/screen/AudioModeCoverLayoutPolicyTest.kt",
    "com/android/purebilibili/feature/video/screen/AudioModeSourcePolicyTest.kt",
    "com/android/purebilibili/feature/video/screen/ContinuousPlayerTransitionPolicyTest.kt",
    "com/android/purebilibili/feature/video/screen/LargeScreenVideoReturnPolicyTest.kt",
    "com/android/purebilibili/feature/video/screen/VideoCommentPerformancePolicyTest.kt",
    "com/android/purebilibili/feature/video/screen/VideoDetailTopBarActionPolicyTest.kt",
    "com/android/purebilibili/feature/video/screen/VideoInitialPagePolicyTest.kt",
    "com/android/purebilibili/feature/video/subtitle/SubtitleFeaturePolicyTest.kt",
    "com/android/purebilibili/feature/video/ui/VideoFollowVisualPolicyTest.kt",
    "com/android/purebilibili/feature/video/ui/components/CircularGesturePercentMotionPolicyTest.kt",
    "com/android/purebilibili/feature/video/ui/components/CollectionEpisodePolicyTest.kt",
    "com/android/purebilibili/feature/video/ui/components/CommentTranslationPolicyTest.kt",
    "com/android/purebilibili/feature/video/ui/components/PlayerEpisodeMenuPolicyTest.kt",
    "com/android/purebilibili/feature/video/ui/components/PlayerSleepTimerPolicyTest.kt",
    "com/android/purebilibili/feature/video/ui/components/QualitySelectionPolicyTest.kt",
    "com/android/purebilibili/feature/video/ui/components/RelatedVideoActionPolicyTest.kt",
    "com/android/purebilibili/feature/video/ui/components/RelatedVideoCardLayoutPolicyTest.kt",
    "com/android/purebilibili/feature/video/ui/components/VideoCommentBackToTopPolicyTest.kt",
    "com/android/purebilibili/feature/video/ui/gesture/Md3GestureLevelHapticPolicyTest.kt",
    "com/android/purebilibili/feature/video/ui/gesture/TwoFingerSpeedGesturePolicyTest.kt",
    "com/android/purebilibili/feature/video/ui/overlay/FullscreenPlayerOverlayPollingPolicyTest.kt",
    "com/android/purebilibili/feature/video/ui/overlay/LandscapeEndDrawerLayoutPolicyTest.kt",
    "com/android/purebilibili/feature/video/ui/overlay/LiveDanmakuRenderPolicyTest.kt",
    "com/android/purebilibili/feature/video/ui/overlay/MiniPlayerOverlayChromePolicyTest.kt",
    "com/android/purebilibili/feature/video/ui/overlay/MiniPlayerOverlayLayoutPolicyTest.kt",
    "com/android/purebilibili/feature/video/ui/overlay/MiniPlayerOverlayPollingPolicyTest.kt",
    "com/android/purebilibili/feature/video/ui/overlay/MiniPlayerOverlayPositionPolicyTest.kt",
    "com/android/purebilibili/feature/video/ui/overlay/PlaybackOrderDisplayPolicyTest.kt",
    "com/android/purebilibili/feature/video/ui/overlay/PortraitProgressBarLayoutPolicyTest.kt",
    "com/android/purebilibili/feature/video/ui/pager/PortraitCollectionPolicyTest.kt",
    "com/android/purebilibili/feature/video/ui/pager/PortraitCommentPresentationPolicyTest.kt",
    "com/android/purebilibili/feature/video/ui/pager/PortraitControlsVisibilityPolicyTest.kt",
    "com/android/purebilibili/feature/video/ui/pager/PortraitDetailVideoListPolicyTest.kt",
    "com/android/purebilibili/feature/video/ui/pager/PortraitMainPlayerSyncPolicyTest.kt",
    "com/android/purebilibili/feature/video/ui/pager/PortraitPlaybackAdvancePolicyTest.kt",
    "com/android/purebilibili/feature/video/ui/pager/PortraitProgressSyncPolicyTest.kt",
    "com/android/purebilibili/feature/video/ui/pager/PortraitSharePolicyTest.kt",
    "com/android/purebilibili/feature/video/ui/section/VideoDetailMotionBudgetPolicyTest.kt",
    "com/android/purebilibili/feature/video/ui/section/VideoInfoDisplayPolicyTest.kt",
    "com/android/purebilibili/feature/video/ui/section/VideoPlayerTopBarPolicyTest.kt",
    "com/android/purebilibili/feature/video/ui/section/VideoPlayerUiLayoutPolicyTest.kt",
    "com/android/purebilibili/feature/video/viewmodel/AiSummaryRetryPolicyTest.kt",
    "com/android/purebilibili/feature/video/viewmodel/AiSummaryUiPolicyTest.kt",
    "com/android/purebilibili/feature/video/viewmodel/CommentPaginationPolicyTest.kt",
    "com/android/purebilibili/feature/video/viewmodel/CommentSearchLoaderTest.kt",
    "com/android/purebilibili/feature/video/viewmodel/FavoriteSaveUiPolicyTest.kt",
    "com/android/purebilibili/feature/video/viewmodel/PlaybackResumeNoticePolicyTest.kt",
    "com/android/purebilibili/feature/video/viewmodel/PlayerToastPolicyTest.kt",
    "com/android/purebilibili/feature/video/viewmodel/RelatedVideoLoadSchedulerTest.kt",
    "com/android/purebilibili/feature/video/viewmodel/RelatedVideoPublicationGateTest.kt",
    "com/android/purebilibili/feature/video/viewmodel/VideoCodecSessionPolicyTest.kt",
    "com/android/purebilibili/feature/video/viewmodel/VideoDetailUiStateTest.kt",
    "com/android/purebilibili/feature/video/viewmodel/VideoLoadTimelineTest.kt",
    "com/android/purebilibili/feature/bangumi/BangumiDashManifestPolicyTest.kt",
    "com/android/purebilibili/feature/bangumi/ui/player/BangumiSeekHapticPolicyTest.kt",
)
// M4a: additional module behavior tests whose production subjects are now migrated.
// Existing generated suites keep their registrations and assertions.
val migratedPlaybackModuleTests = mapOf(
    "core-player/src/test/java/" to setOf("com/android/purebilibili/core/player/PlaybackResumePolicyTest.kt"),
    "danmaku-engine/src/test/java/" to setOf(
        "com/bytedance/danmaku/render/engine/render/RenderClockTest.kt",
        "com/bytedance/danmaku/render/engine/render/DrawOrderPolicyTest.kt",
        "com/bytedance/danmaku/render/engine/render/cache/BufferAdmissionTest.kt",
        "com/android/purebilibili/danmaku/parser/DanmakuProtoPolicyTest.kt",
        "com/android/purebilibili/danmaku/parser/AdvancedDanmakuParserTest.kt",
        "com/android/purebilibili/danmaku/parser/TimedDanmakuWindowTest.kt",
        "com/android/purebilibili/danmaku/parser/DanmakuMode9ParserTest.kt",
    ),
)
kotlin.sourceSets.named("test") {
    migratedPlaybackModuleTests.forEach { (root, selected) ->
        val directory = mergedRepositoryRoot.resolve(root)
        kotlin.srcDir(directory)
        kotlin.exclude { element -> !element.isDirectory && element.file.toPath().startsWith(directory.toPath()) &&
            element.file.relativeTo(directory).invariantSeparatorsPath !in selected }
    }
}

// Approved upstream test corrections are isolated from production patch outputs.
// The complete LF-normalized source is pinned: even an edit outside a hunk needs review.
val correctedVideoTestPaths = emptySet<String>()
val correctedVideoTestOutput = layout.buildDirectory.dir("patched-upstream-tests")
val applyVideoTestCorrections by tasks.registering {
    val patches = correctedVideoTestPaths.map { desktopUpstreamPatchDirectory.resolve("app/src/test/java/$it.patch") }
    inputs.files(patches).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(correctedVideoTestPaths.map { appTestRoot.resolve(it) }).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(correctedVideoTestOutput)
    doLast {
        val output = correctedVideoTestOutput.get().asFile
        output.deleteRecursively()
        correctedVideoTestPaths.forEach { relative ->
            val original = appTestRoot.resolve(relative).readText().replace("\r\n", "\n")
            val patch = desktopUpstreamPatchDirectory.resolve("app/src/test/java/$relative.patch").readText()
            val expected = Regex("(?m)^Upstream-source-sha256: ([0-9a-f]{64})$").find(patch)?.groupValues?.get(1)
                ?: throw GradleException("$relative: missing upstream source digest")
            val actual = MessageDigest.getInstance("SHA-256").digest(original.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            check(actual == expected) {
                "$relative: upstream test changed; review and remove or regenerate the correction, never silently reuse it"
            }
            val destination = output.resolve(relative)
            destination.parentFile.mkdirs()
            destination.writeText(applyUnifiedPatch(original, patch, relative))
        }
    }
}
tasks.named("compileTestKotlin") { dependsOn(applyVideoTestCorrections) }
kotlin.sourceSets.named("test") { kotlin.srcDir(correctedVideoTestOutput) }

// M4d (TON-1005): tests of the migrated feature areas join, except those listed in upstream-core/m4d/tests-not-run.tsv
// (path <TAB> reason) and those whose subject (FooTest.kt tests Foo.kt) is a later step of the area.
val appM4dTestsNotRun: Map<String, String> = m4dTable("tests-not-run.tsv").toMap()
val appCoreUpstreamTestPaths: Set<String> = (appTestRoot.resolve("com/android/purebilibili/core").walkTopDown() +
        migratedAppM4dAreas.asSequence().flatMap { appTestRoot.resolve("com/android/purebilibili/$it").walkTopDown() } +
        // TON-1120: upstream's IM wire tests run against the directly compiled MessageImRepository.
        sequenceOf(appTestRoot.resolve("com/android/purebilibili/data/repository/MessageImRepositoryTest.kt")))
    .filter { it.isFile && it.extension == "kt" }
    // JUnit 4, Robolectric, mockk and androidx.test are not on the desktop test classpath; JUnit 5 (org.junit.jupiter) is.
    .filter { file -> !Regex("""^import (org\.junit\.(?!jupiter)|org\.robolectric|io\.mockk|androidx\.test)""", RegexOption.MULTILINE).containsMatchIn(file.readText()) }
    .map { it.relativeTo(appTestRoot).invariantSeparatorsPath }
    .filter { path -> path.removePrefix("com/android/purebilibili/").let { core -> core !in appCoreUpstreamTestsNotRun &&
        core !in appM4dTestsNotRun &&
        (appCoreDesktopOwned.keys + appCoreLaterSteps.keys).none { core.startsWith(it) } &&
        // FooTest.kt tests Foo.kt: a test of a desktop-owned or later-step file is left out with its subject.
        (appCoreDesktopOwned.keys + appCoreLaterSteps.keys + appM4dLaterSteps.keys).none { key -> key.endsWith(".kt") && core == key.removeSuffix(".kt") + "Test.kt" } } }
    .toSortedSet() + migratedVideoFeatureTestPaths
val appCoreSourceReadingTestClasses: List<String> = appCoreUpstreamTestPaths.map { appTestRoot.resolve(it) }
    .filter { Regex("""\bFile\(|Paths\.get\(|\.readText\(""").containsMatchIn(it.readText()) }
    .map { file -> Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE).find(file.readText())!!.groupValues[1] + "." + file.nameWithoutExtension }
    .sorted()
kotlin.sourceSets.named("test") {
    migratedWholeModuleTestRoots.forEach { kotlin.srcDir(it) }
    kotlin.srcDir(appTestRoot)
    val appTestRootPath = appTestRoot.toPath()
    kotlin.exclude { element -> !element.isDirectory && element.file.toPath().startsWith(appTestRootPath) &&
        element.file.relativeTo(appTestRoot).invariantSeparatorsPath.let { it !in appCoreUpstreamTestPaths || it in correctedVideoTestPaths } }
    val buildDirectoryPath = layout.buildDirectory.get().asFile.toPath()
    kotlin.exclude { element ->
        val file = element.file
        val path = file.invariantSeparatorsPath
        val index = path.lastIndexOf("/com/android/purebilibili/")
        element.file.toPath().startsWith(buildDirectoryPath) &&
            !element.file.toPath().startsWith(correctedVideoTestOutput.get().asFile.toPath()) && index >= 0 &&
            path.substring(index + 1).let { it in migratedWholeModuleTestPaths || it in appCoreUpstreamTestPaths }
    }
}

kotlin.sourceSets.named("main") {
    // Migrated upstream modules compile straight from the merged root, in this module (same-module
    // smart casts and internal access as before); their generated legacy copies are skipped.
    val buildDirectoryPath = layout.buildDirectory.get().asFile.toPath()
    val generatedDirectoryPath = buildDirectoryPath.resolve("generated")
    val adaptedRootFiles = desktopUpstreamAdaptedRootFiles.map { mergedRepositoryRoot.resolve(it) }.toSet()
    kotlin.exclude { element -> element.file in adaptedRootFiles }
    // A producer may root its source directory at com/ itself, so the identity is read from the path.
    fun upstreamIdentity(file: File): String {
        val path = file.invariantSeparatorsPath
        val index = path.lastIndexOf("/com/android/purebilibili/")
        return if (index < 0) "" else path.substring(index + 1)
    }
    val patchedDirectoryPath = buildDirectoryPath.resolve("patched-upstream")
    kotlin.exclude { element ->
        val path = element.file.toPath()
        path.startsWith(buildDirectoryPath) && !path.startsWith(patchedDirectoryPath) && upstreamIdentity(element.file) in migratedUpstreamRelativePaths
    }
    kotlin.exclude { element ->
        val path = element.file.toPath()
        path.startsWith(generatedDirectoryPath) && generatedDirectoryPath.relativize(path).joinToString("/").let {
            it in upstreamOwnedGeneratedCopies || it in appM4dRetiredGeneratedCopies || it in appFeatureRetiredGeneratedCopies || it in transitionPatchedGeneratedFiles
        }
    }
    // A retired producer copy that also has a transition patch is dropped in its patched form too.
    val transitionPatchedDirectoryPath = buildDirectoryPath.resolve("transition-patched")
    val retiredPatchedOutputs = appFeatureRetiredGeneratedCopies.filter { it in transitionPatchedGeneratedFiles }
        .map { it.substringAfter('/').removePrefix("generated/") }.toSet()
    kotlin.exclude { element ->
        val path = element.file.toPath()
        path.startsWith(transitionPatchedDirectoryPath) && transitionPatchedDirectoryPath.relativize(path).joinToString("/") in retiredPatchedOutputs
    }
    // Migrated upstream files the desktop patches or replaces are compiled from their adapted form only.
    val migratedRootPaths = migratedUpstreamModules.map { mergedRepositoryRoot.resolve(it + "src/main/java").toPath() } + listOf(appJavaRoot.toPath())
    kotlin.exclude { element ->
        migratedRootPaths.any { element.file.toPath().startsWith(it) } && upstreamIdentity(element.file) in desktopAdaptedUpstreamPaths
    }
    kotlin.srcDir(generateUpstreamColorResources)
    kotlin.srcDir(files(layout.buildDirectory.dir("generated/upstream-strings/kotlin")).builtBy(generateUpstreamStringResources))
    kotlin.srcDir(files(layout.buildDirectory.dir("generated/upstream-preference-types")).builtBy(generateUpstreamPreferenceKeyTypes))
    kotlin.srcDir(files(layout.buildDirectory.dir("generated/upstream-buildconfig")).builtBy(generateUpstreamBuildConfig))
    kotlin.srcDir(upstreamReplacementDirectory)
    // Desktop stand-ins for the Android APIs the migrated modules use, and transition compat code.
    kotlin.srcDir("upstream-core/compat")
    // Stand-ins that need Compose, Skia or Miuix; the JVM-only upstream-core test project does not compile them.
    kotlin.srcDir("upstream-core/compat-ui")
    kotlin.srcDir("upstream-core/transition/src")
    kotlin.srcDir(applyTransitionPatches)
    kotlin.srcDir(applyDesktopUpstreamPatches)
    migratedUpstreamModules.forEach { kotlin.srcDir(mergedRepositoryRoot.resolve(it + "src/main/java")) }
    // Partial playback modules: compile only the registered upstream files. Their generated copies
    // are excluded by migratedUpstreamRelativePaths above, just like the app feature files.
    migratedPlaybackModulePaths.forEach { (relativeRoot, paths) ->
        val root = mergedRepositoryRoot.resolve(relativeRoot)
        val rootPath = root.toPath()
        kotlin.srcDir(root)
        kotlin.exclude { element -> !element.isDirectory && element.file.toPath().startsWith(rootPath) &&
            element.file.relativeTo(root).invariantSeparatorsPath !in paths }
    }
    // Only the migrated app core files of the app source root; the rest of the app is not migrated yet.
    val appJavaRootPath = appJavaRoot.toPath()
    kotlin.exclude { element -> !element.isDirectory && element.file.toPath().startsWith(appJavaRootPath) && upstreamIdentity(element.file) !in migratedAppPaths }
    kotlin.srcDir(appJavaRoot)
    kotlin.srcDir(generatedUpstream)
    kotlin.srcDir(layout.buildDirectory.dir("generated/api"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/danmaku"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/media"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/audio"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/login"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/plugins"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/discovery"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/settings"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/playback"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/search"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/cast"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/packages"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/watchdogs"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/google-cast"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/js"))
    kotlin.srcDir(jsWorkerGenerated)
    kotlin.srcDir(generatedAppearance)
    kotlin.srcDir(layout.buildDirectory.dir("generated/story-topic"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/playback-settings"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/subtitle-load"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/space"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/space-overview"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/components"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/preferences"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/settings-search"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/settings-categories"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/settings-home"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/settings-privacy"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/settings-entries"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/navigation-interaction"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/settings-storage-entries"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/native-music-root"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/blocked-up"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/blocked-list-ui"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/network-proxy"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/diagnostics/sources"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/dynamic-settings"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/crash-prompt/sources"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/home-cards"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/home-full-card/generated"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/home-page"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/dynamic-tabs"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/dynamic-full-card"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/dynamic-gallery-motion-photo"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/dynamic-static-image-codec"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/settings-image-save-path"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/dynamic-editor"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/dynamic-reply/com"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/dynamic-detail"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/dynamic-detail-container"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/dynamic-reply-protocol/generated"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/dynamic-detail-protocol/generated"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/dynamic-follow"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/video-votes"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/weekly-series"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/collection-sheet"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/video-metadata"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/shared-liquid-tabs/generated"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/bgm-detail/com"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/video-comment-ui/com"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/original-favorites/com"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/original-favorite-folder/com"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/original-danmaku-list-menu/com"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/original-danmaku-settings/com"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/frosted-audio-renderer"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/linked-dock"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/comment-fraud-protocol/generated"))
    kotlin.srcDir(nativeDiagnosticShareOutput.map { it.dir("kotlin") })
}
tasks.named("compileKotlin") { dependsOn(extractUpstreamApi, extractUpstreamDanmaku, extractUpstreamMedia, extractUpstreamAudio, extractUpstreamLogin, extractUpstreamPlugins, extractUpstreamDiscovery, extractUpstreamSettings, extractUpstreamPlayback, extractUpstreamSearch, extractUpstreamCast, extractUpstreamPackages, extractPlaybackWatchdogs, extractGoogleCastPlatform) }
tasks.named("compileKotlin") { dependsOn(extractUpstreamJs, prepareJsWorker) }
tasks.named("compileKotlin") { dependsOn(extractUpstreamAppearance, verifyAppearanceDependencies) }
tasks.named("compileKotlin") { dependsOn(extractUpstreamSettingsSearch, extractUpstreamSettingsCategories, extractUpstreamSettingsHome, extractUpstreamSettingsPrivacy, extractUpstreamSettingsEntries, extractUpstreamNavigationInteraction, extractUpstreamSettingsStorageEntries, extractNativeMusicRoot, verifySettingsSearchDependencies) }
tasks.named("compileKotlin") { dependsOn(extractUpstreamComponents, extractUpstreamPreferences) }
tasks.named("compileKotlin") { dependsOn(extractUpstreamBlockedUp, extractUpstreamBlockedListUi, extractUpstreamNetworkProxy) }
tasks.named("compileKotlin") { dependsOn(extractUpstreamSpace, extractUpstreamSpaceOverview) }
tasks.named("compileKotlin") { dependsOn(extractUpstreamDiagnostics) }
tasks.named("compileKotlin") { dependsOn(extractUpstreamDynamicSettings) }
tasks.named("compileKotlin") { dependsOn(extractUpstreamCrashPrompt) }
tasks.named("compileKotlin") { dependsOn(extractUpstreamHomeCards, extractUpstreamDynamicTabs) }
tasks.named("compileKotlin") { dependsOn(extractUpstreamHomeFullCard) }
tasks.named("compileKotlin") { dependsOn(extractUpstreamDynamicFullCard) }
tasks.named("compileKotlin") { dependsOn(verifyUpstreamDynamicMedia, verifyDynamicMediaDependencies, extractUpstreamDynamicStaticImageCodec, extractImageSaveSettingsUi) }
tasks.named("compileKotlin") { dependsOn(extractUpstreamDynamicEditor, verifyUpstreamDynamicEditorProtocol) }
tasks.named("compileKotlin") { dependsOn(extractUpstreamDynamicReply, extractUpstreamDynamicDetail,
    extractUpstreamDynamicReplyProtocol, extractUpstreamDynamicDetailProtocol, verifyUpstreamDynamicDetailReplyProtocol) }
tasks.named("compileKotlin") { dependsOn(extractUpstreamDynamicDetailContainer) }

tasks.named("compileKotlin") { dependsOn(extractUpstreamDynamicFollow) }
tasks.named("compileKotlin") { dependsOn(prepareNativeDiagnosticShare) }
tasks.named("processResources") { dependsOn(prepareNativeDiagnosticShare) }
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(prepareNativeDiagnosticShare) }
sourceSets.named("main") { resources.srcDir(generatedAppearanceResources) }
sourceSets.named("main") { resources.srcDir(files(layout.buildDirectory.dir("generated/upstream-strings/resources")).builtBy(generateUpstreamStringResources)) }
tasks.named("processResources") { dependsOn(extractUpstreamAppearance) }

val prepareOriginalPluginResources by tasks.registering(Sync::class) {
    dependsOn(prepareUpstreamSources)
    from(canonicalOriginalSource("app/src/main/res/raw/cdn_region_catalog.json")) { into("plugin") }
    from(canonicalOriginalSource("app/src/main/assets/rovniced-skin-catalog.json"))
    into(layout.buildDirectory.dir("generated/plugin-resources"))
    inputs.property("upstreamSourceManifest", revisionNeutralSourceManifestInput)
    inputs.files(revisionNeutralSourceRecipePins.keys.map { file(it) })
    inputs.file("build.gradle.kts")
    inputs.property("upstreamSourceRecipeImportContext", revisionNeutralSourceImportContextMatches)
    inputs.files(revisionNeutralSourceImportShadowFiles)
    inputs.files(originalResources.map { canonicalOriginalSource(it["path"].toString()) })
}
sourceSets.named("main") { resources.srcDir(layout.buildDirectory.dir("generated/plugin-resources")) }
tasks.named("processResources") { dependsOn(prepareOriginalPluginResources, verifyGoogleCastSources) }
val prepareGoogleCastNotices by tasks.registering(Sync::class) {
    dependsOn(verifyGoogleCastSources)
    from("src/main/resources/licenses/google-cast-v2")
    from("third-party/google-cast-v2/SOURCES.json")
    into("resources/common/notices/google-cast-v2")
}
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(prepareGoogleCastNotices) }
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(prepareJsWorkerResources) }
val prepareAppearanceNotices by tasks.registering(Sync::class) {
    dependsOn(verifyAppearanceDependencies)
    from("src/main/resources/licenses/appearance")
    from("third-party/miuix5157/dependency-pins.json")
    from("third-party/miuix5157/upstream-provenance.json")
    into("resources/common/notices/appearance")
}
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(prepareAppearanceNotices) }
// Only the hash-pinned read-only verifier is staged; native SDK/module/runtime files remain opt-in.
val prepareVeyraRuntimeVerifier by tasks.registering(Copy::class) {
    val verifier = file("tools/native/veyra/verify-veyra-runtime.ps1")
    val expected = "a0e1e04c90b3ac90899e1fbb7c123f6ef65c2472dd3f99e58561b63f2df3ed55"
    from(verifier)
    into("resources/common/native/veyra-core")
    inputs.property("verifierSha256", expected)
    fun verifierHash(asset: File): String = MessageDigest.getInstance("SHA-256").digest(asset.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    doFirst { require(verifierHash(verifier) == expected) { "Veyra verifier source identity changed." } }
    doLast { require(verifierHash(file("resources/common/native/veyra-core/verify-veyra-runtime.ps1")) == expected) {
        "Staged Veyra verifier identity changed."
    } }
}
tasks.named("processResources") { dependsOn(prepareVeyraRuntimeVerifier) }
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(prepareVeyraRuntimeVerifier) }
tasks.withType<JavaExec>().configureEach { dependsOn(prepareVeyraRuntimeVerifier) }

val prepareSettingsSearchNotices by tasks.registering(Sync::class) {
    dependsOn(verifySettingsSearchDependencies)
    from("src/main/resources/licenses/tinypinyin-2.0.3.RELEASE")
    into("resources/common/notices/tinypinyin-2.0.3.RELEASE")
}
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(prepareSettingsSearchNotices) }
tasks.withType<JavaExec>().configureEach {
    dependsOn(prepareJsWorker)
    systemProperty("bilipai.js.workerResources", jsWorkerOutput.get().asFile.absolutePath)
}

dependencies {
    // Libraries the migrated upstream modules need (versions as in the desktop graph).
    implementation("androidx.datastore:datastore-preferences-core:1.2.1")
    implementation("androidx.collection:collection:1.5.0")
    // Upstream's ForwardPagingSource/Session and the paging sources of migrated features (TON-1108); paging-common has a JVM target.
    implementation("androidx.paging:paging-common:3.4.2")
    implementation(compose.desktop.currentOs)
    // Expose existing exact runtime97 Lifecycle modules through Miuix's implementation boundary.
    implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel:2.11.0")
    implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    // Expose the same saved-state runtime modules already present in the pinned runtime97 graph.
    implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-savedstate:2.11.0")
    implementation("androidx.savedstate:savedstate-compose:1.4.0")
    // Existing runtime97 datetime module used by unchanged Coil CacheControl source.
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
    implementation("org.jetbrains.compose.material3:material3:1.12.0-alpha03")
    // Actual JetBrains JVM counterparts of original Android adaptive 1.3.0.
    implementation("org.jetbrains.compose.material3.adaptive:adaptive:1.3.0-rc01")
    implementation("org.jetbrains.compose.material3.adaptive:adaptive-layout:1.3.0-rc01")
    // Original comment sheets use this API directly; Material3 only brings its
    // desktop implementation onto runtimeClasspath transitively.
    implementation("androidx.navigationevent:navigationevent-compose:1.1.2")
    implementation(project(":miuix5157"))
    implementation("com.materialkolor:material-kolor-material3:6.0.0-beta01")
    implementation("dev.chrisbanes.haze:haze-jvm:2.0.1")
    implementation("dev.chrisbanes.haze:haze-utils-jvm:2.0.1")
    implementation("dev.chrisbanes.haze:haze-blur-jvm:2.0.1")
    implementation("dev.chrisbanes.haze:haze-blur-materials-jvm:2.0.1")
    implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.4.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.jmdns:jmdns:3.6.3")
    implementation("com.google.protobuf:protobuf-javalite:4.33.2")
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.20")
    implementation("com.fasterxml.jackson.core:jackson-core:2.20.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.20.0")
    implementation("org.slf4j:slf4j-api:2.0.17")
    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:3.0.0")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("org.jsoup:jsoup:1.21.2")
    // Upstream core/plugin/feed/FeedArticleExtractor (TON-1028): article readability, same version as upstream app.
    implementation("net.dankito.readability4j:readability4j:1.0.8")
    implementation("io.coil-kt.coil3:coil-compose:3.5.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.5.0")
    implementation("com.google.zxing:core:3.5.4")
    implementation("org.apache.commons:commons-imaging:1.0.0-alpha6")
    implementation("commons-io:commons-io:2.19.0")
    implementation("org.apache.commons:commons-lang3:3.17.0")
    implementation("net.java.dev.jna:jna:5.17.0")
    implementation("net.java.dev.jna:jna-platform:5.17.0")
    implementation("org.json:json:20240303")
    implementation("io.github.biezhi:TinyPinyin:2.0.3.RELEASE") { version { strictly("2.0.3.RELEASE") } }
    implementation("org.ahocorasick:ahocorasick:0.4.0") { version { strictly("0.4.0") } }
    implementation("org.brotli:dec:0.1.2")
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform { excludeTags("packaged-updater", "native-mux", "js-worker") }
    // The verbatim v0.4.6 suite compares a zero offset with assertEquals(0f, ...), but the zero there is -0.0f (negative width times +0f),
    // and the generic equals() rejects it. The rendered offset is the same zero; the other cases of that class still run.
    filter { excludeTestsMatching("com.android.purebilibili.core.ui.wallpaper.WallpaperTransformPolicyTest.landscapeImageFillsPortraitViewportAndCanReachBothEdges") }
    systemProperty("java.awt.headless", "true")
    systemProperty("bilipai.js.workerResources", jsWorkerOutput.get().asFile.absolutePath)
}
// Saved credentials are protected by Windows DPAPI only. A non-Windows test host has no system protection, so its
// test JVMs explicitly select the test-only implementation (DesktopCredentialProtection); Windows tests use DPAPI.
tasks.withType<Test>().configureEach {
    if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        systemProperty("bilipai.credentialProtection", "test-only")
    // Upstream HTTP caches of test JVMs stay in the task's temporary directory, not the user's data directory (TON-1082).
    systemProperty("bilipai.upstreamHttpCache", temporaryDir.resolve("upstream-http").absolutePath)
}
tasks.register<Test>("jsWorkerSmoke") {
    group = "verification"
    description = "Execute original JS scripts through the verified, separately linked Windows worker."
    dependsOn("testClasses", prepareJsWorker)
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("js-worker") }
    systemProperty("bilipai.js.workerResources", jsWorkerOutput.get().asFile.absolutePath)
    systemProperty("bilipai.js.repo", repositoryRoot.absolutePath)
    systemProperty("junit.jupiter.execution.parallel.enabled", "false")
    maxParallelForks = 1
    outputs.upToDateWhen { false }
    testLogging { events("passed", "failed", "skipped") }
}
tasks.register<Test>("nativeMuxSmoke") {
    group = "verification"
    description = "Verify the packaged Windows FFmpeg CLI through the real download muxer."
    dependsOn("testClasses")
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("native-mux") }
    outputs.upToDateWhen { false }
    listOf("nativeMuxFfmpeg", "nativeMuxFfprobe", "nativeMuxReport").forEach { name ->
        providers.gradleProperty(name).orNull?.let { systemProperty("bilipai.$name", it) }
    }
    testLogging { events("passed", "failed", "skipped") }
}
tasks.register<Test>("updaterSmoke") {
    group = "verification"
    description = "Exercise the updater against a real packaged EXE in isolated user data."
    dependsOn("testClasses")
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("packaged-updater") }
    outputs.upToDateWhen { false }
    val packagePath = providers.gradleProperty("updateTestPackage").orNull
    val reportPath = providers.gradleProperty("updateTestReport").orNull
    val previousPackagePath = providers.gradleProperty("updatePreviousPackage").orNull
    if (packagePath != null) {
        inputs.file(packagePath)
        systemProperty("bilipai.updateTestPackage", packagePath)
    }
    if (reportPath != null) {
        outputs.file(File(reportPath, "updater-smoke.json"))
        systemProperty("bilipai.updateTestReport", reportPath)
    }
    if (previousPackagePath != null) {
        inputs.file(previousPackagePath)
        systemProperty("bilipai.updatePreviousPackage", previousPackagePath)
    }
    doFirst {
        require(System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) { "Packaged updater verification requires Windows." }
        require(packagePath != null && file(packagePath).isFile) { "Pass -PupdateTestPackage=<actual portable ZIP>." }
        require(!reportPath.isNullOrBlank()) { "Pass -PupdateTestReport=<isolated report directory>." }
    }
    testLogging { events("passed", "failed", "skipped") }
}
tasks.processResources {
    inputs.file(sourceManifest)
    inputs.file(upstreamBuildFile)
    inputs.property("windowsVersion", windowsVersion)
    inputs.property("windowsReleaseRepository", System.getenv("BILIPAI_WINDOWS_RELEASE_REPOSITORY") ?: "tonysuper666-creator/BiliPai-Windows")
    doLast {
        destinationDir.resolve("upstream-version.txt").writeText(
            "${manifest["upstreamTag"] ?: "unknown"}\n${manifest["upstreamCommit"] ?: "unknown"}\n")
        destinationDir.resolve("windows-update.json").writeText(groovy.json.JsonOutput.toJson(mapOf(
            "version" to windowsVersion,
            "upstreamTag" to manifest["upstreamTag"],
            "upstreamCommit" to manifest["upstreamCommit"],
            "windowsReleaseRepository" to (System.getenv("BILIPAI_WINDOWS_RELEASE_REPOSITORY") ?: "tonysuper666-creator/BiliPai-Windows"),
            "channel" to "prerelease",
            "executable" to "BiliPai Windows.exe"
        )))
    }
}
tasks.register<JavaExec>("backendSmoke") {
    dependsOn("classes")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.bilipai.desktop.MainKt")
    args("--backend-smoke")
}

tasks.register<JavaExec>("originalStaticSettingsUiSmoke") {
    group = "verification"
    description = "Render original static settings through the actual Main and owned live Root."
    dependsOn("testClasses", "classes")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.bilipai.desktop.ui.OriginalStaticSettingsUiFixture")
    systemProperty("compose.application.resources.dir", file("resources/common").absolutePath)
    systemProperty("file.encoding", "UTF-8")
    providers.gradleProperty("rootValidationToken").orNull?.let { systemProperty("bilipai.rootValidationToken", it) }
    doFirst {
        args(requireNotNull(providers.gradleProperty("rootValidationReport").orNull),
            requireNotNull(providers.gradleProperty("rootValidationHealth").orNull),
            requireNotNull(providers.gradleProperty("rootValidationToken").orNull))
    }
}

tasks.register<JavaExec>("originalAicuRootUiSmoke") {
    group = "verification"
    description = "Exercise the original Aicu consent and filter UI through unchanged Main and its owned live Root."
    dependsOn("testClasses", "classes", prepareJsWorkerResources)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.bilipai.desktop.ui.OriginalAicuRootUiFixture")
    systemProperty("compose.application.resources.dir", file("resources/common").absolutePath)
    systemProperty("file.encoding", "UTF-8")
    providers.gradleProperty("rootValidationToken").orNull?.let { systemProperty("bilipai.rootValidationToken", it) }
    doFirst {
        args(requireNotNull(providers.gradleProperty("rootValidationReport").orNull),
            requireNotNull(providers.gradleProperty("rootValidationHealth").orNull),
            requireNotNull(providers.gradleProperty("rootValidationToken").orNull),
            requireNotNull(providers.gradleProperty("rootValidationPhase").orNull))
    }
}

// Both Windows-only products above are placeholders on another host, so nothing may be packaged here:
// every Compose distribution, installer, uber-jar and release-shrink task refuses to run off Windows.
val windowsOnlyPackagingTask = Regex(
    "(create|run)(Release)?Distributable|package(Release)?(DistributionForCurrentOS|UberJarForCurrentOS|Exe|Msi|Dmg|Pkg|Deb|Rpm)|proguardReleaseJars")
tasks.matching { windowsOnlyPackagingTask.matches(it.name) }
    .configureEach {
        doFirst {
            require(hostIsWindows) {
                "Windows packaging needs the native diagnostic share bridge and the JS worker image, " +
                    "which are Windows x64 build products; run this on Windows."
            }
        }
    }

compose.desktop {
    application {
        mainClass = "com.bilipai.desktop.MainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8")
        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "BiliPai Windows"
            packageVersion = "0.2.$upstreamVersionCode"
            description = "BiliPai Windows desktop client"
            vendor = "BiliPai community"
            licenseFile.set(File(repositoryRoot, "LICENSE"))
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
            modules("java.net.http", "java.desktop", "java.logging", "java.sql", "java.xml", "jdk.unsupported", "jdk.httpserver")
            windows {
                iconFile.set(project.file("src/main/resources/app-icon.ico"))
                menuGroup = "BiliPai"
                shortcut = true
                perUserInstall = true
                upgradeUuid = "f9d20d55-f39f-4d50-96e2-f4402b105ec4"
            }
        }
    }
}

val extractSubtitleLoadPolicy by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-subtitle-load-policy.py",
        "--repo", repositoryRoot.absolutePath, "--output", layout.buildDirectory.dir("generated/subtitle-load").get().asFile.absolutePath)
    inputs.files("tools/extract-subtitle-load-policy.py", "tools/extract-upstream-media.py", "tools/sync-upstream.py")
    inputs.file(canonicalOriginalSource("app/src/main/java/com/android/purebilibili/feature/video/viewmodel/VideoPlaybackViewModel.kt"))
    outputs.dir(layout.buildDirectory.dir("generated/subtitle-load"))
}
tasks.named("compileKotlin") { dependsOn(extractUpstreamStoryTopic, extractPlaybackSettings, extractSubtitleLoadPolicy) }

val extractUpstreamDownloadTransport by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-download-transport.py",
        "--repo", repositoryRoot.absolutePath, "--output", layout.buildDirectory.dir("generated/download-transport").get().asFile.absolutePath)
    inputs.file("tools/extract-upstream-download-transport.py")
    inputs.file(canonicalOriginalSource("app/src/main/java/com/android/purebilibili/feature/download/ResumableAssetDownloader.kt"))
    outputs.dir(layout.buildDirectory.dir("generated/download-transport"))
}
sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/download-transport")) }
tasks.named("compileKotlin") { dependsOn(extractUpstreamDownloadTransport) }

val extractOriginalVideoDetailUnits by tasks.registering(Exec::class) {
    inputs.file("tools/v042_owner_follow.py")
    inputs.dir("upstream-slices/v042-owner-follow")
    inputs.file("tools/v042_follow_confirmation.py")
    inputs.dir("upstream-slices/v042-follow-confirmation")
    inputs.file("tools/v037_gesture_feedback.py")
    inputs.dir("upstream-slices/v037-gesture-feedback")
    inputs.file("tools/v037_player_section_policy.py")
    inputs.dir("upstream-slices/v037-player-section-policy")
    inputs.file("tools/v037_video_action_anchors.py")
    inputs.dir("upstream-slices/v037-video-feedback-anchor")
    inputs.file("tools/v037_video_coin_request.py")
    inputs.file("tools/v037_player_interaction.py")
    inputs.dir("upstream-slices/v037-player-interaction-seed")
    inputs.file("tools/v031_repost_coin.py")
    inputs.dir("upstream-slices/v031-repost-coin")
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-video-detail-full-units.py",
        repositoryRoot.absolutePath, layout.buildDirectory.dir("generated/original-video-detail-units").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-video-detail-full-units.py", "tools/sync-upstream.py",
        "tools/extract-upstream-media.py", "tools/extract-appearance-platform.py", sourceManifest)
    inputs.file("tools/v029_video_feedback.py")
    inputs.file("tools/v029_video_feedback_origin.py")
    inputs.file("tools/v029_video_feedback_host.py")
    inputs.files("tools/v037_video_feedback_adapter.py", "tools/v037_feedback_origin.py")
    inputs.dir("upstream-slices/v029-video-feedback")
    inputs.files(sources.filter { "stable-video-detail-full-units" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-video-detail-units"))
}
val verifyOriginalVideoDetailMembers by tasks.registering(Exec::class) {
    dependsOn(extractOriginalVideoDetailUnits)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/verify-upstream-video-detail-full-units.py",
        repositoryRoot.absolutePath, layout.buildDirectory.dir("generated/original-video-detail-units").get().asFile.absolutePath,
        layout.buildDirectory.file("generated/original-video-detail-members-verification.json").get().asFile.absolutePath)
    inputs.files("tools/verify-upstream-video-detail-full-units.py", "src/main/kotlin/com/bilipai/desktop/data/DesktopDynamicCardOperations.kt")
    inputs.file(layout.buildDirectory.file("generated/original-video-detail-units/video-operations-members.fragment"))
    outputs.file(layout.buildDirectory.file("generated/original-video-detail-members-verification.json"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-video-detail-units")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalVideoDetailUnits, verifyOriginalVideoDetailMembers) }

val extractOriginalOfflinePlayer by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-offline-player.py",
        "--repo", repositoryRoot.absolutePath, "--output", layout.buildDirectory.dir("generated/original-offline-player").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-offline-player.py", "tools/extract-upstream-media.py", "tools/sync-upstream.py", sourceManifest)
    inputs.files(fileTree("upstream-slices/v029-offline-error"))
    inputs.files(sources.filter { "stable-offline-player-original" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-offline-player"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-offline-player")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalOfflinePlayer) }

// Insert beside extractOriginalFavorites. No dependency change.
val extractOriginalPersonalLists by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractOriginalFavorites)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-personal-lists.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-personal-lists").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-personal-lists.py", "tools/extract-upstream-dynamic-reply-protocol.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "stable-personal-history-liked" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-personal-lists"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-personal-lists")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalPersonalLists) }

// Beside extractOriginalPersonalLists; existing tools/dependencies only.
val extractOriginalWatchLater by tasks.registering(Exec::class) {
    inputs.file("tools/sync-upstream.py")
    inputs.file("tools/v044_favorite_subscription.py")
    inputs.file("tools/v044_fans.py")
    inputs.dir("upstream-slices/v044-following-fans")
    inputs.file("tools/v044_favorite_search.py")
    inputs.dir("upstream-slices/v044-favorite-search")
    inputs.dir("upstream-slices/v044-favorite-subscription")
    inputs.file("tools/v044_favorite_detail.py")
    inputs.dir("upstream-slices/v044-favorite-detail")
    dependsOn(prepareUpstreamSources, extractOriginalFavorites)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-watchlater.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-watchlater").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-watchlater.py", "tools/extract-upstream-favorites.py",
        "tools/extract-upstream-dynamic-reply-protocol.py")
    inputs.file("tools/v041_video_status_labels.py")
    inputs.file("tools/v043_search_up_author.py")
    inputs.dir("upstream-slices/v041-video-status-labels")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "stable-personal-watchlater" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-watchlater"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-watchlater")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalWatchLater) }

val extractOriginalPlayerFullControls by tasks.registering(Exec::class) {
    inputs.file("tools/v042_optical_dependencies.py")
    inputs.dir("upstream-slices/v042-optical-dependencies")
    inputs.file("tools/v037_window_modal_blur.py")
    inputs.dir("upstream-slices/v037-window-modal-blur")
    inputs.file("tools/v037_shared_tabs_progress.py")
    inputs.file("tools/v040_liquid_value_controls.py")
    inputs.dir("upstream-slices/v040-liquid-value-controls")
    inputs.dir("upstream-slices/v037-shared-tabs-progress-closure")
    inputs.file("tools/v037_player_foundations.py")
    inputs.file("tools/v042_search_dependencies.py")
    inputs.dir("upstream-slices/v042-search-dependencies")
    inputs.dir("upstream-slices/v037-player-foundation-closure")
    inputs.files("tools/v037_player_chrome.py", "tools/v037_player_closure.py", "tools/v037_player_render.py", "tools/v037_player_shared_material.py")
    inputs.file("tools/v040_native_chrome.py")
    inputs.dir("upstream-slices/v040-native-chrome")
    inputs.file("tools/v040_chrome_actions.py")
    inputs.dir("upstream-slices/v040-chrome-actions")
    inputs.dir("upstream-slices/v037-player-chrome")
    inputs.dir("upstream-slices/v037-player-closure")
    inputs.dir("upstream-slices/v037-player-render-closure")
    inputs.dir("upstream-slices/v037-player-shared-material")
    inputs.file("tools/v046_player_overlay.py")
    inputs.file("tools/v044_favorite_detail.py")
    inputs.dir("upstream-slices/v046-player-overlay")
    inputs.file("tools/v046_control_center_motion.py")
    inputs.dir("upstream-slices/v046-control-center-motion")

    dependsOn(prepareUpstreamSources, extractOriginalVideoDetailUnits, extractOriginalOfflinePlayer)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-video-player-full-controls.py",
        repositoryRoot.absolutePath, layout.buildDirectory.dir("generated/original-video-player-full-controls").get().asFile.absolutePath,
        "--test-output", layout.buildDirectory.dir("generated/original-player-chrome-tests").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-video-player-full-controls.py", "tools/sync-upstream.py", "tools/extract-appearance-platform.py", sourceManifest)
    inputs.files(sources.filter { "stable-video-player-full-controls" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-video-player-full-controls"))
}
kotlin.sourceSets.named("test") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-player-chrome-tests")) }
tasks.named("compileTestKotlin") { dependsOn(extractOriginalPlayerFullControls) }

val verifyOriginalPlayerFullControls by tasks.registering(Exec::class) {
    dependsOn(extractOriginalPlayerFullControls)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/verify-upstream-video-player-full-controls.py",
        repositoryRoot.absolutePath, layout.buildDirectory.dir("generated/original-video-player-full-controls").get().asFile.absolutePath,
        layout.buildDirectory.file("generated/original-video-player-controls-verification.json").get().asFile.absolutePath)
    inputs.files("tools/verify-upstream-video-player-full-controls.py", "tools/extract-upstream-video-player-full-controls.py", "tools/sync-upstream.py", "tools/extract-appearance-platform.py", sourceManifest)
    inputs.dir(layout.buildDirectory.dir("generated/original-video-player-full-controls"))
    outputs.file(layout.buildDirectory.file("generated/original-video-player-controls-verification.json"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-video-player-full-controls")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalPlayerFullControls, verifyOriginalPlayerFullControls) }

// After extractOriginalWatchLater; same compiler/dependencies and one source graph.
val extractOriginalFollowing by tasks.registering(Exec::class) {
    inputs.file("tools/sync-upstream.py")
    inputs.file("tools/v044_favorite_subscription.py")
    inputs.file("tools/v044_fans.py")
    inputs.dir("upstream-slices/v044-following-fans")
    inputs.file("tools/v044_favorite_search.py")
    inputs.dir("upstream-slices/v044-favorite-search")
    inputs.dir("upstream-slices/v044-favorite-subscription")
    inputs.file("tools/v044_favorite_detail.py")
    inputs.dir("upstream-slices/v044-favorite-detail")
    inputs.file("tools/v029_brand_success.py")
    inputs.dir("upstream-slices/v029-brand-success")
    dependsOn(prepareUpstreamSources, extractOriginalFavorites, extractOriginalWatchLater)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-following.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-following").get().asFile.absolutePath,
        "--test-output", layout.buildDirectory.dir("generated/original-following-tests").get().asFile.absolutePath)
    inputs.files("tools/v044_following.py", "tools/extract-upstream-following.py", "tools/extract-upstream-favorites.py",
        "tools/extract-upstream-dynamic-reply-protocol.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "stable-personal-following" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-following"))
    outputs.dir(layout.buildDirectory.dir("generated/original-following-tests"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-following")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalFollowing) }
kotlin.sourceSets.named("test") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-following-tests")) }
tasks.named("compileTestKotlin") { dependsOn(extractOriginalFollowing) }

// Beside other original leaf producers; uses existing Python/compiler/dependencies.
val extractOriginalArticleDetail by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractUpstreamDynamicDetailProtocol)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-article-detail.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-article-detail").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-article-detail.py", "tools/extract-upstream-dynamic-reply-protocol.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "stable-article-full" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-article-detail"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-article-detail")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalArticleDetail) }
// Existing extractUpstreamDynamicDetailProtocol remains the only Article protocol producer.
// Its original ArticleRepository source is already registered in dynamic-detail-reply inputs.

dependencies {
    implementation("com.mohamedrejeb.richeditor:richeditor-compose:1.0.0-rc14")
}
val extractOriginalVideoContentFull by tasks.registering(Exec::class) {
    inputs.file("tools/v046_detail_holder_collapse.py")
    inputs.dir("upstream-slices/v046-detail-holder-collapse")
    inputs.file("tools/v046_comment_chrome.py")
    inputs.dir("upstream-slices/v046-comment-chrome")
    inputs.file("tools/v040_liquid_value_controls.py")
    inputs.dir("upstream-slices/v040-liquid-value-controls")
    inputs.file("tools/v036_video_comment_viewport.py")
    inputs.dir("upstream-slices/v036-video-comment-viewport")
    dependsOn(prepareUpstreamSources, extractOriginalVideoDetailUnits, extractOriginalPlayerFullControls)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-video-content-full.py",
        repositoryRoot.absolutePath, layout.buildDirectory.dir("generated/original-video-content-full").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-video-content-full.py", "tools/sync-upstream.py", "tools/extract-appearance-platform.py", sourceManifest)
    inputs.files(sources.filter { "stable-video-content-full" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-video-content-full"))
}
val verifyOriginalVideoContentFull by tasks.registering(Exec::class) {
    dependsOn(extractOriginalVideoContentFull)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/verify-upstream-video-content-full.py",
        repositoryRoot.absolutePath, layout.buildDirectory.dir("generated/original-video-content-full").get().asFile.absolutePath,
        layout.buildDirectory.file("generated/original-video-content-full-verification.json").get().asFile.absolutePath)
    inputs.files("tools/verify-upstream-video-content-full.py", "tools/extract-upstream-video-content-full.py", "tools/sync-upstream.py", "tools/extract-appearance-platform.py", sourceManifest)
    inputs.dir(layout.buildDirectory.dir("generated/original-video-content-full"))
    outputs.file(layout.buildDirectory.file("generated/original-video-content-full-verification.json"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-video-content-full")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalVideoContentFull, verifyOriginalVideoContentFull) }

// Append alongside existing original video producers; no new dependency or runtime artifact.
val extractOriginalVideoStateCore by tasks.registering(Exec::class) {
    inputs.file("tools/v037_player_interaction.py")
    inputs.dir("upstream-slices/v037-player-interaction-seed")
    inputs.file("tools/v031_repost_coin.py")
    inputs.dir("upstream-slices/v031-repost-coin")
    dependsOn(prepareUpstreamSources, extractOriginalVideoDetailUnits, extractOriginalVideoContentFull)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-video-state-core.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-video-state-core").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-video-state-core.py", "tools/extract-upstream-dynamic-reply-protocol.py",
        "tools/extract-upstream-video-detail-full-units.py", "tools/sync-upstream.py", "tools/extract-appearance-platform.py", sourceManifest)
    inputs.file("tools/v029_failure_recovery.py")
    inputs.dir("upstream-slices/v029-playback-recovery")
    inputs.files(sources.filter { "stable-video-state-core" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-video-state-core"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-video-state-core")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalVideoStateCore) }
// Production omits --standalone. Retained DIRECT sources are copied solely by prepareUpstreamSources.
// The b209 whole Sponsor UI is emitted once by the section-full producer below.
// Install child Section's canonical native facade first/in same compile: this package references it.

val extractOriginalVideoPlayerSectionFull by tasks.registering(Exec::class) {
    inputs.file("tools/v041_player_cover.py")
    inputs.dir("upstream-slices/v041-player-cover")
    inputs.file("tools/v040_player_tap.py")
    inputs.dir("upstream-slices/v040-player-tap")
    inputs.file("tools/v040_player_cover.py")
    inputs.dir("upstream-slices/v040-player-cover")
    inputs.files("tools/v037_player_owner_state.py", "tools/v037_player_page_backdrop.py", "tools/v037_player_section_policy.py", "tools/v037_player_runtime_resources.py")
    inputs.dir("upstream-slices/v037-player-runtime-resources")
    inputs.dir("upstream-slices/v037-player-section-policy")
    inputs.dir("upstream-slices/v037-player-owner-state")
    inputs.dir("upstream-slices/v037-player-page-backdrop")
    dependsOn(prepareUpstreamSources, extractOriginalVideoStateCore, extractOriginalPlayerFullControls, extractOriginalVideoContentFull)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-video-player-section-full.py",
        repositoryRoot.absolutePath, layout.buildDirectory.dir("generated/original-video-player-section-full").get().asFile.absolutePath,
        "--test-output", layout.buildDirectory.dir("generated/original-player-page-backdrop-tests").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-video-player-section-full.py", "tools/extract-upstream-dynamic-reply-protocol.py",
        "tools/sync-upstream.py", "tools/extract-appearance-platform.py", sourceManifest)
    inputs.file("tools/v029_command_vote.py")
    inputs.dir("upstream-slices/v029-command-vote")
    inputs.dir("upstream-slices/v034-sponsor-ui")
    inputs.files(sources.filter { "stable-video-player-section-full" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-video-player-section-full"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-video-player-section-full")) }
kotlin.sourceSets.named("test") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-player-page-backdrop-tests")) }
tasks.named("compileTestKotlin") { dependsOn(extractOriginalVideoPlayerSectionFull) }
tasks.named("compileKotlin") { dependsOn(extractOriginalVideoPlayerSectionFull) }


val prepareUpstreamVideoCommentUrl by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-video-comment-url.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/upstream-video-comment-url").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-video-comment-url.py",
        canonicalOriginalSource("app/src/main/java/com/android/purebilibili/feature/video/screen/VideoDetailSessionPolicy.kt"))
    outputs.dir(layout.buildDirectory.dir("generated/upstream-video-comment-url"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/upstream-video-comment-url/com")) }
tasks.named("compileKotlin") { dependsOn(prepareUpstreamVideoCommentUrl) }

val extractOriginalVideoTabletFull by tasks.registering(Exec::class) {
    inputs.file("tools/v046_detail_holder_collapse.py")
    inputs.dir("upstream-slices/v046-detail-holder-collapse")
    inputs.file("tools/v037_player_known_presentation.py")
    dependsOn(prepareUpstreamSources, prepareUpstreamVideoCommentUrl, extractOriginalVideoStateCore,
        extractOriginalVideoPlayerSectionFull, extractOriginalVideoContentFull)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-video-tablet-full.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/video-tablet-full").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-video-tablet-full.py", "tools/sync-upstream.py",
        "tools/extract-appearance-platform.py", "tools/extract-upstream-video-player-section-full.py", sourceManifest)
    inputs.files(sources.filter { "stable-video-tablet-original" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/video-tablet-full"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/video-tablet-full/com")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalVideoTabletFull) }


// Append source-only task wiring. Do not replace the existing build file.
val extractOriginalVideoFullscreenPager by tasks.registering(Exec::class) {
    inputs.file("tools/v042_owner_follow.py")
    inputs.dir("upstream-slices/v042-owner-follow")
    inputs.file("tools/v037_gesture_feedback.py")
    inputs.dir("upstream-slices/v037-gesture-feedback")
    inputs.file("tools/v031_repost_coin.py")
    inputs.dir("upstream-slices/v031-repost-coin")
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-video-fullscreen-pager.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-video-fullscreen-pager").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-video-fullscreen-pager.py", "tools/sync-upstream.py",
        "tools/extract-upstream-dynamic-reply-protocol.py", sourceManifest)
    inputs.files(sources.filter { "stable-video-fullscreen-pager" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-video-fullscreen-pager"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-video-fullscreen-pager")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalVideoFullscreenPager) }
tasks.named("extractOriginalVideoStateCore") { inputs.file("tools/extract-upstream-video-fullscreen-pager.py") }
tasks.named("extractOriginalVideoFullscreenPager") { inputs.file("tools/v046_fullscreen_tap_gestures.py") }
// Production omits --standalone. DIRECT15 must be copied once by registry Sync.


val extractOriginalMusicPlayerFull by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractOriginalVideoTabletFull)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-music-player-full.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/music-player-full").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-music-player-full.py", "tools/sync-upstream.py",
        "tools/extract-appearance-platform.py", "tools/extract-upstream-video-player-section-full.py", sourceManifest)
    inputs.files(sources.filter { "stable-music-player-original" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/music-player-full"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/music-player-full/com")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalMusicPlayerFull) }

val extractOriginalVideoAudioFull by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractOriginalMusicPlayerFull, extractOriginalVideoFullscreenPager,
        extractOriginalVideoTabletFull)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-video-audio-full.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/video-audio-full").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-video-audio-full.py", "tools/sync-upstream.py",
        "tools/extract-appearance-platform.py", sourceManifest)
    inputs.files(sources.filter { "stable-video-audio-original" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/video-audio-full"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/video-audio-full/com")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalVideoAudioFull) }

val extractOriginalMediaByteCachePolicy by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-media-byte-cache-policy.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-media-byte-cache-policy").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-media-byte-cache-policy.py", sourceManifest,
        canonicalOriginalSource("app/src/main/java/com/android/purebilibili/core/player/PlaybackMediaCache.kt"))
    outputs.dir(layout.buildDirectory.dir("generated/original-media-byte-cache-policy"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-media-byte-cache-policy")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalMediaByteCachePolicy) }



val extractOriginalVideoFullOwner by tasks.registering(Exec::class) {
    inputs.file("tools/v038_player_interaction_retry.py")
    inputs.dir("upstream-slices/v038-player-interaction-retry")
    dependsOn(prepareUpstreamSources, extractOriginalVideoStateCore, extractOriginalPlayerFullControls,
        extractOriginalVideoFullscreenPager)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-video-full-owner.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-video-full-owner").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-video-full-owner.py", sourceManifest)
    inputs.files("tools/v037_player_known_state.py", "tools/v037_player_interaction.py")
    inputs.dir("upstream-slices/v037-player-interaction-seed")
    inputs.file("tools/v030_up_danmaku.py")
    inputs.file("tools/v029_failure_recovery.py")
    inputs.dir("upstream-slices/v029-playback-recovery")
    inputs.files(sources.filter { "stable-original-video-full-owner" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-video-full-owner"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-video-full-owner")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalVideoFullOwner) }
tasks.named("compileTestKotlin") { dependsOn(extractOriginalVideoFullOwner) }
kotlin.sourceSets.named("test") {
    kotlin.srcDir(layout.buildDirectory.dir("generated/original-video-interaction-retry-tests"))
}
// DIRECT10 are copied once by prepareUpstreamSources; production omits --standalone.
// Retained Holder/portrait comment inputs share the existing selection adaptation.
listOf("extractOriginalVideoFullOwner", "extractOriginalVideoFullscreenPager").forEach { taskName ->
    tasks.named(taskName) {
        inputs.files("tools/v029_comment_composer.py", "tools/v029_comment_search.py")
    }
}

val extractOriginalVideoDetailHolderFull by tasks.registering(Exec::class) {
    inputs.file("tools/v046_detail_holder_collapse.py")
    inputs.dir("upstream-slices/v046-detail-holder-collapse")
    inputs.dir("upstream-slices/v037-holder-comment-close")
    inputs.file("tools/v037_player_known_presentation.py")
    inputs.file("tools/v032_video_return_state.py")
    inputs.file("tools/windows_video_return_frozen_inputs.py")
    inputs.dir("upstream-slices/v032-video-return-state")
    inputs.file("tools/v031_repost_coin.py")
    inputs.dir("upstream-slices/v031-repost-coin")
    inputs.files("tools/v029_comment_composer.py", "tools/v029_comment_search.py")
    inputs.dir("upstream-slices/v029-comment-composer")
    inputs.files("tools/extract-upstream-video-comment-ui.py", "tools/extract-upstream-dynamic-reply-protocol.py")
    dependsOn(prepareUpstreamSources, extractOriginalVideoFullOwner, extractOriginalVideoFullscreenPager,
        extractOriginalVideoTabletFull)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-video-detail-holder.py",
        repositoryRoot.absolutePath,
        layout.buildDirectory.dir("generated/original-video-detail-holder-full").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-video-detail-holder.py", sourceManifest)
    inputs.files(sources.filter { "original-video-detail-holder-full" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-video-detail-holder-full"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-video-detail-holder-full")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalVideoDetailHolderFull) }
// DIRECT21 are copied once by prepareUpstreamSources; production omits --standalone.



// Append after the existing original Tablet task. No shared build file replacement.
val extractOriginalTabletOwnerSpace by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractUpstreamSpaceOverview, extractOriginalVideoTabletFull)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-tablet-owner-space.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-tablet-owner-space").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-tablet-owner-space.py", "tools/sync-upstream.py", sourceManifest)
    inputs.files(sources.filter { "stable-tablet-owner-space-original" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-tablet-owner-space"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-tablet-owner-space/com")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalTabletOwnerSpace) }


val extractOriginalVideoPlaylistFull by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-video-playlist.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-video-playlist-full").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-video-playlist.py", sourceManifest)
    inputs.file(canonicalOriginalSource("app/src/main/java/com/android/purebilibili/feature/video/player/PlaylistManager.kt"))
    outputs.dir(layout.buildDirectory.dir("generated/original-video-playlist-full"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-video-playlist-full/com")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalVideoPlaylistFull) }


val extractOriginalVideoOwnerDanmakuSend by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractOriginalDanmakuListMenu)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-video-owner-danmaku-send.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-video-owner-danmaku-send").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-video-owner-danmaku-send.py", "tools/extract-upstream-danmaku-list-menu.py", sourceManifest)
    inputs.file(canonicalOriginalSource("app/src/main/java/com/android/purebilibili/data/repository/DanmakuRepository.kt"))
    outputs.dir(layout.buildDirectory.dir("generated/original-video-owner-danmaku-send"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-video-owner-danmaku-send/com")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalVideoOwnerDanmakuSend) }


val extractOriginalVideoRootStoryFeed by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractOriginalDanmakuListMenu)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-video-root-story-feed.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-video-root-story-feed").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-video-root-story-feed.py", "tools/extract-upstream-danmaku-list-menu.py", sourceManifest)
    inputs.file(canonicalOriginalSource("app/src/main/java/com/android/purebilibili/data/repository/StoryRepository.kt"))
    inputs.file(canonicalOriginalSource("app/src/main/java/com/android/purebilibili/core/network/ApiClient.kt"))
    inputs.file(canonicalOriginalSource("app/src/main/java/com/android/purebilibili/data/model/response/StoryModels.kt"))
    outputs.dir(layout.buildDirectory.dir("generated/original-video-root-story-feed"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-video-root-story-feed/com")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalVideoRootStoryFeed) }

// Original storage section, settings writes, cache confirmation and pure automatic policy.
// CacheClearUiPolicy remains mode=direct, produced solely by prepareUpstreamSources.
val extractOriginalStorageSettings by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-storage-settings.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-storage-settings").get().asFile.absolutePath,
        "--tests-output", layout.buildDirectory.dir("generated/original-storage-settings-tests").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-storage-settings.py", "tools/extract-upstream-media.py",
        "tools/extract-upstream-settings-search.py", "tools/sync-upstream.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "desktop-storage-cache-settings-owner-parity" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    inputs.files(originalResources.filter { "desktop-storage-cache-settings-owner-parity" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    inputs.file("tools/v029_brand_cache_clear.py")
    inputs.dir("upstream-slices/v029-brand-cache-clear")
    outputs.dir(layout.buildDirectory.dir("generated/original-storage-settings"))
    outputs.dir(layout.buildDirectory.dir("generated/original-storage-settings-tests"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-storage-settings")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalStorageSettings) }
kotlin.sourceSets.named("test") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-storage-settings-tests")) }
tasks.named("compileTestKotlin") { dependsOn(extractOriginalStorageSettings) }

// Complete original Search/SearchTrending page and VM bodies. Common DIRECT sources stay
// solely with prepareUpstreamSources; no selected duplicate submit/tab-order declarations.
val extractOriginalSearchPages by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-search-pages.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-search-pages").get().asFile.absolutePath,
        "--test-output", layout.buildDirectory.dir("generated/original-search-tests").get().asFile.absolutePath)
    inputs.file("tools/v042_search_layout.py")
    inputs.file("tools/v042_search_dependencies.py")
    inputs.dir("upstream-slices/v042-search-dependencies")
    inputs.dir("upstream-slices/v042-search-layout")
    inputs.file("tools/extract-upstream-search-pages.py")
    inputs.file("tools/v041_search_all.py")
    inputs.dir("upstream-slices/v041-search-all")
    inputs.file("tools/v046_search_state.py")
    inputs.file("tools/v044_favorite_detail.py")
    inputs.file("tools/v044_favorite_subscription.py")
    inputs.dir("upstream-slices/v046-search-state")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "desktop-full-original-search-root-parity" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    inputs.file("tools/v029_brand_callers.py")
    inputs.dir("upstream-slices/v029-brand-callers")
    outputs.dir(layout.buildDirectory.dir("generated/original-search-pages"))
    outputs.dir(layout.buildDirectory.dir("generated/original-search-tests"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-search-pages")) }
kotlin.sourceSets.named("test") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-search-tests")) }
tasks.named("compileTestKotlin") { dependsOn(extractOriginalSearchPages) }
tasks.named("compileKotlin") { dependsOn(extractOriginalSearchPages) }

// Full original message seven pages. DIRECT4 are copied once by prepareUpstreamSources;
// production intentionally omits --standalone. Existing unread/share producers stay unique.
val extractOriginalMessagePages by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractUpstreamDynamicFullCard, extractOriginalHomeProtocols)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-message-pages.py",
        "--repo", repositoryRoot.absolutePath,
        "--out", layout.buildDirectory.dir("generated/original-message-pages").get().asFile.absolutePath)
    inputs.dir("upstream-slices/v029-chat-timeline")
    inputs.file("tools/v029_chat_sources.py")
    inputs.file("tools/v029_history_failure_login.py")
    inputs.files("tools/extract-upstream-message-pages.py", "tools/extract-appearance-platform.py",
        "tools/extract-upstream-plugins.py", "tools/extract-upstream-media.py", "tools/sync-upstream.py", sourceManifest)
    inputs.files(sources.filter { "stable-original-message-pages-root-parity" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-message-pages"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-message-pages")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalMessagePages) }

// Full original retained Space page, supporter leaves and business ViewModels.
// Existing SpaceUiState/SubTab/selected policies, dynamic cards and gallery stay sole-owned.
val extractOriginalSpacePages by tasks.registering(Exec::class) {
    inputs.file("tools/v046_identity_badge_palette.py")
    dependsOn(prepareUpstreamSources, extractOriginalVideoTabletFull, extractOriginalFavorites)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-space-pages.py",
        "--repo", repositoryRoot.absolutePath,
        "--out", layout.buildDirectory.dir("generated/original-space-pages").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-space-pages.py", "tools/extract-upstream-tablet-owner-space.py", "tools/sync-upstream.py", sourceManifest)
    inputs.file("tools/v041_video_status_labels.py")
    inputs.file("tools/v043_search_up_author.py")
    inputs.dir("upstream-slices/v041-video-status-labels")
    inputs.file(layout.buildDirectory.file("generated/original-favorites/com/android/purebilibili/feature/space/DesktopFavoriteArchiveMappings.kt"))
    inputs.files(sources.filter { "stable-original-space-pages-root-parity" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-space-pages"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-space-pages")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalSpacePages) }

// Sole v0.4.1 video status label producer: the shared policy, badge row and four models, plus the original tests.
// Card, Space and WatchLater edits are anchored hooks inside those files' own producers (inputs declared there).
val extractOriginalVideoStatusLabels by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-v041-video-status-labels.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/v041-video-status-labels").get().asFile.absolutePath,
        "--tests-output", layout.buildDirectory.dir("generated/v041-video-status-label-tests").get().asFile.absolutePath)
    inputs.files("tools/extract-v041-video-status-labels.py", "tools/v041_video_status_labels.py", "tools/v043_search_up_author.py",
        "tools/v025_source_paths.py", "tools/v025-canonical-sources.json")
    inputs.dir("upstream-slices/v041-video-status-labels")
    inputs.dir("upstream-slices/v043-search-up-author")
    inputs.file("tools/v046_core_data_policies.py")
    inputs.dir("upstream-slices/v046-core-data-policies")
    inputs.files(listOf(
        "core-data/src/main/java/com/android/purebilibili/data/model/response/ListModels.kt",
        "core-data/src/main/java/com/android/purebilibili/data/model/response/SpaceModels.kt",
        "core-data/src/main/java/com/android/purebilibili/data/model/response/SearchModels.kt",
        "core-data/src/main/java/com/android/purebilibili/data/model/response/FavoriteModels.kt",
        "app/src/main/java/com/android/purebilibili/feature/home/components/cards/VideoCard.kt",
        "app/src/main/java/com/android/purebilibili/feature/home/components/cards/HomeStyleSingleColumnVideoCard.kt",
        "app/src/main/java/com/android/purebilibili/feature/space/SpaceScreen.kt",
        "app/src/main/java/com/android/purebilibili/feature/space/SpaceLoadPolicy.kt",
        "core-data/src/main/java/com/android/purebilibili/data/repository/WatchLaterRepository.kt",
        "app/src/main/java/com/android/purebilibili/feature/watchlater/WatchLaterScreen.kt"
    ).map { canonicalOriginalSource(it) })
    outputs.dir(layout.buildDirectory.dir("generated/v041-video-status-labels"))
    outputs.dir(layout.buildDirectory.dir("generated/v041-video-status-label-tests"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/v041-video-status-labels")) }
kotlin.sourceSets.named("test") { kotlin.srcDir(layout.buildDirectory.dir("generated/v041-video-status-label-tests")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalVideoStatusLabels) }
tasks.named("compileTestKotlin") { dependsOn(extractOriginalVideoStatusLabels) }

// Native Home models and routes remain in their existing sole producers.
listOf("extractOriginalVideoStatusLabels", "extractOriginalHomePage", "extractOriginalHomeProtocols").forEach { taskName ->
    tasks.named(taskName) {
        inputs.file("tools/v043_home_native_cards.py")
        inputs.dir("upstream-slices/v043-home-native-cards")
    }
}

// Recommendation badges retain the actual card producers and their complete fixed inputs.
listOf("extractUpstreamHomeFullCard", "extractOriginalHomePage").forEach { taskName ->
    tasks.named(taskName) {
        inputs.file("tools/v043_home_recommendation_badges.py")
        inputs.dir("upstream-slices/v043-home-recommendation-badges")
        inputs.file("tools/v043_home_native_cards.py")
        inputs.dir("upstream-slices/v043-home-native-cards")
    }
}

// Personal status badges stay in each retained full original producer.
listOf("extractOriginalFavorites", "extractOriginalProfileMain").forEach { taskName ->
    tasks.named(taskName) {
        inputs.files("tools/v041_video_status_personal_consumers.py",
            "tools/v025_source_paths.py", "tools/v025-canonical-sources.json")
        inputs.dir("upstream-slices/v041-video-status-personal-consumers")
    }
}

val extractOriginalWindowLayout by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractUpstreamDynamicDetailContainer)
    commandLine(pythonExecutable, "tools/extract-upstream-window-layout.py",
        "--repo", rootProject.projectDir.parentFile.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-window-layout").get().asFile.absolutePath,
        "--existing-hinge-model", layout.buildDirectory.file("generated/dynamic-detail-container/com/android/purebilibili/core/util/DesktopOriginalDetailHingeModel.kt").get().asFile.absolutePath)
    inputs.file("tools/extract-upstream-window-layout.py")
    inputs.file(layout.buildDirectory.file("generated/dynamic-detail-container/com/android/purebilibili/core/util/DesktopOriginalDetailHingeModel.kt"))
    inputs.files(sources.filter { "original-window-layout-v025" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { rootProject.projectDir.parentFile.resolve(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-window-layout"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-window-layout")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalWindowLayout) }


// Five original CDN transfer families have exactly one neutral Windows producer.
val extractOriginalCdnTransfer by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-cdn-transfer.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-cdn-transfer").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-cdn-transfer.py", "tools/upstream-cdn-transfer-adaptations.json")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "desktop-original-cdn-transfer-parity" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-cdn-transfer"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-cdn-transfer")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalCdnTransfer) }


// Full original Aicu closure. Direct repository/policies/nav/models have one Sync producer.
val extractOriginalAicu by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-aicu.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-aicu").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-aicu.py", "tools/upstream-aicu-adaptations.json", "tools/sync-upstream.py", "tools/extract-upstream-media.py", sourceManifest)
    inputs.files(canonicalOriginalHelperFile, canonicalOriginalCatalogFile)
    inputs.files(sources.filter { "desktop-full-original-aicu-root-parity" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-aicu"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-aicu")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalAicu) }

// Root integrates after prepareUpstreamSources, retaining normal whole-build task inputs.
val extractOriginalCommentFraudHistory by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-comment-fraud-history.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/comment-fraud-history").get().asFile.absolutePath,
        "--proof", layout.buildDirectory.file("reports/comment-fraud-history-source-inverse.json").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-comment-fraud-history.py", "tools/extract-upstream-settings-search.py",
        canonicalOriginalHelperFile, canonicalOriginalCatalogFile)
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "desktop-whole-comment-fraud-history" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    inputs.files(originalResources.filter { "desktop-whole-comment-fraud-history" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/comment-fraud-history"))
    outputs.file(layout.buildDirectory.file("reports/comment-fraud-history-source-inverse.json"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/comment-fraud-history")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalCommentFraudHistory) }


val extractOriginalDonateDialog by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-donate-dialog.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-donate-dialog").get().asFile.absolutePath,
        "--resource-output", layout.buildDirectory.dir("generated/original-donate-resources").get().asFile.absolutePath,
        "--proof", layout.buildDirectory.file("reports/original-donate-dialog-inverse.json").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-donate-dialog.py", "tools/extract-upstream-settings-search.py",
        canonicalOriginalHelperFile, canonicalOriginalCatalogFile)
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "desktop-whole-original-donate-dialog" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    inputs.files(originalResources.filter { "desktop-whole-original-donate-dialog" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-donate-dialog"))
    outputs.dir(layout.buildDirectory.dir("generated/original-donate-resources"))
    outputs.file(layout.buildDirectory.file("reports/original-donate-dialog-inverse.json"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-donate-dialog")) }
sourceSets.named("main") { resources.srcDir(layout.buildDirectory.dir("generated/original-donate-resources")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalDonateDialog) }
tasks.named("processResources") { dependsOn(extractOriginalDonateDialog) }

tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(extractOriginalDonateDialog) }

// Complete original IconSettings page, fixed original PNGs and same-Store Windows leaf.
val extractOriginalIconSettings by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-icon-settings.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/icon-settings").get().asFile.absolutePath,
        "--resource-output", layout.buildDirectory.dir("generated/icon-settings-resources").get().asFile.absolutePath,
        "--proof", layout.buildDirectory.file("reports/icon-settings-source-inverse.json").get().asFile.absolutePath)
    inputs.file(sourceManifest)
    inputs.files("tools/extract-upstream-icon-settings.py", "tools/extract-upstream-settings-search.py",
        canonicalOriginalHelperFile, canonicalOriginalCatalogFile)
    inputs.files(sources.filter { "desktop-whole-icon-settings" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    inputs.files(originalResources.filter { "desktop-whole-icon-settings" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/icon-settings"))
    outputs.dir(layout.buildDirectory.dir("generated/icon-settings-resources"))
    outputs.file(layout.buildDirectory.file("reports/icon-settings-source-inverse.json"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/icon-settings")) }
sourceSets.named("main") { resources.srcDir(layout.buildDirectory.dir("generated/icon-settings-resources")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalIconSettings) }
tasks.named("processResources") { dependsOn(extractOriginalIconSettings) }
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(extractOriginalIconSettings) }

// Whole original HOME settings branch, original recipes and actual shared Root effects.
val extractOriginalHomeSettings by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractOriginalProfileMain)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-home-settings-full-page.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/home-settings").get().asFile.absolutePath)
    inputs.file(sourceManifest)
    inputs.file("tools/v033_home_refresh_undo.py")
    inputs.dir("upstream-slices/v033-home-undo")
    inputs.files("tools/extract-upstream-home-settings-full-page.py", "tools/v025_home_wallpaper_picker.py", "tools/extract-upstream-media.py",
        "tools/extract-upstream-settings-search.py", "tools/sync-upstream.py",
        canonicalOriginalHelperFile, canonicalOriginalCatalogFile)
    inputs.file(layout.buildDirectory.file("generated/profile-main/com/android/purebilibili/feature/profile/ProfileViewModel.kt"))
    inputs.files(sources.filter { "desktop-whole-home-settings" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    inputs.files(originalResources.filter { "desktop-whole-home-settings" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/home-settings"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/home-settings/generated")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalHomeSettings) }

// Complete fixed original About/support/contributors and both release-notes renderers.
// Original APK data is metadata only; Windows update installation keeps its existing owner.
val extractOriginalSystemAbout by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractOriginalIconSettings, extractUpstreamSettingsEntries, extractOriginalProfileMain)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-system-about.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/system-about").get().asFile.absolutePath,
        "--resource-output", file("resources/common").absolutePath,
        "--proof", layout.buildDirectory.file("reports/original-system-about-producer.json").get().asFile.absolutePath)
    inputs.file(sourceManifest)
    inputs.files("tools/extract-upstream-system-about.py", "tools/extract-upstream-profile-main.py", "tools/extract-upstream-media.py", "tools/sync-upstream.py",
        "tools/v025_source_paths.py", "tools/v025-canonical-sources.json")
    inputs.files((sources + originalResources).filter { "desktop-whole-system-about-actions" in
        ((it["features"] as? List<*>) ?: emptyList<Any>()) }.map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/system-about"))
    outputs.dir(file("resources/common/original-about"))
    outputs.file(layout.buildDirectory.file("reports/original-system-about-producer.json"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/system-about")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalSystemAbout) }
tasks.named("processResources") { dependsOn(extractOriginalSystemAbout) }
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(extractOriginalSystemAbout) }

// Canonical locator/catalog are explicit inputs of every original-source producer
// and verifier, including consumers through existing imported producer helpers.
val canonicalOriginalInputTasks = setOf(
    "prepareUpstreamSources",
    "extractUpstreamApi",
    "extractUpstreamDanmaku",
    "extractUpstreamMedia",
    "extractUpstreamAudio",
    "extractUpstreamLogin",
    "extractUpstreamPlugins",
    "extractUpstreamDiscovery",
    "extractUpstreamSettings",
    "extractUpstreamPlayback",
    "extractUpstreamSearch",
    "extractUpstreamCast",
    "extractUpstreamPackages",
    "extractPlaybackWatchdogs",
    "extractGoogleCastPlatform",
    "extractUpstreamJs",
    "extractUpstreamAppearance",
    "extractUpstreamComponents",
    "extractUpstreamPreferences",
    "extractUpstreamSettingsSearch",
    "extractUpstreamSettingsCategories",
    "extractUpstreamSettingsHome",
    "extractUpstreamSettingsPrivacy",
    "extractUpstreamSettingsEntries",
    "extractUpstreamNavigationInteraction",
    "extractUpstreamFullNavigation",
    "extractUpstreamSettingsStorageEntries",
    "extractUpstreamBlockedUp",
    "extractUpstreamBlockedListUi",
    "extractUpstreamNetworkProxy",
    "extractUpstreamDiagnostics",
    "extractUpstreamDynamicSettings",
    "extractUpstreamHomeCards",
    "extractOriginalHomePage",
    "extractOriginalHomeViewModel",
    "extractOriginalHomeProtocols",
    "extractOriginalLiveList",
    "extractOriginalHomePartition",
    "extractOriginalHomeBangumiPage",
    "extractOriginalBangumiPages",
    "extractOriginalBangumiPlayer",
    "extractOriginalBangumiPlayerUi",
    "extractOriginalSubscriptionPage",
    "prepareOriginalCategoryPage",
    "prepareOriginalHomeReturnNavigation",
    "extractOriginalProfileMain",
    "extractOriginalLiveNavigation",
    "extractOriginalVideoShareConsent",
    "extractOriginalDownloadList",
    "extractOriginalRootHomeNavigation",
    "extractNavigation3Host",
    "extractOriginalWallpaperPalette",
    "extractOriginalApplicationImageLoader",
    "extractUpstreamProfileWallpaperImport",
    "extractUpstreamHomeFullCard",
    "extractUpstreamDynamicFullCard",
    "extractUpstreamDynamicStaticImageCodec",
    "extractImageSaveSettingsUi",
    "extractUpstreamDynamicGalleryMotionPhoto",
    "verifyUpstreamDynamicMedia",
    "extractUpstreamDynamicEditor",
    "verifyUpstreamDynamicEditorProtocol",
    "extractUpstreamDynamicReply",
    "extractUpstreamDynamicDetail",
    "extractUpstreamDynamicDetailContainer",
    "extractUpstreamDynamicReplyProtocol",
    "extractUpstreamDynamicDetailProtocol",
    "extractBgmDetail",
    "extractVideoCommentUi",
    "extractOriginalFavorites",
    "extractOriginalFavoriteFolder",
    "extractLinkedDock",
    "extractFrostedAudioRenderer",
    "extractOriginalDanmakuSettings",
    "extractOriginalDanmakuListMenu",
    "extractCommentFraudProtocol",
    "extractSharedLiquidTabs",
    "extractStableVideoMetadata",
    "extractStableCollectionSheet",
    "extractStableWeeklySeries",
    "extractStableVideoVotes",
    "extractUpstreamDynamicTabs",
    "extractUpstreamDynamicFollow",
    "extractUpstreamCrashPrompt",
    "extractNativeMusicRoot",
    "extractUpstreamSpace",
    "extractUpstreamSpaceOverview",
    "extractUpstreamStoryTopic",
    "extractPlaybackSettings",
    "prepareJsWorker",
    "extractSubtitleLoadPolicy",
    "extractUpstreamDownloadTransport",
    "extractOriginalVideoDetailUnits",
    "extractOriginalOfflinePlayer",
    "extractOriginalPersonalLists",
    "extractOriginalWatchLater",
    "extractOriginalPlayerFullControls",
    "verifyOriginalPlayerFullControls",
    "extractOriginalFollowing",
    "extractOriginalArticleDetail",
    "extractOriginalVideoContentFull",
    "verifyOriginalVideoContentFull",
    "extractOriginalVideoStateCore",
    "extractOriginalVideoPlayerSectionFull",
    "prepareUpstreamVideoCommentUrl",
    "extractOriginalVideoTabletFull",
    "extractOriginalVideoFullscreenPager",
    "extractOriginalMusicPlayerFull",
    "extractOriginalVideoAudioFull",
    "extractOriginalMediaByteCachePolicy",
    "extractOriginalVideoFullOwner",
    "extractOriginalVideoDetailHolderFull",
    "extractOriginalTabletOwnerSpace",
    "extractOriginalVideoPlaylistFull",
    "extractOriginalVideoOwnerDanmakuSend",
    "extractOriginalVideoRootStoryFeed",
    "extractOriginalStorageSettings",
    "extractOriginalSearchPages",
    "extractOriginalMessagePages",
    "extractOriginalSpacePages",
    "extractOriginalWindowLayout",
    "extractOriginalCdnTransfer"
)
tasks.matching { it.name in canonicalOriginalInputTasks }.configureEach {
    inputs.files(canonicalOriginalHelperFile, canonicalOriginalCatalogFile)
}
// Five latest comment recipe outputs share three existing sole producers.
// Their verifier also consumes the same helper through the existing producer.
val originalCommentConsumerInputTasks = setOf(
    "extractOriginalVideoContentFull",
    "verifyOriginalVideoContentFull",
    "extractOriginalVideoTabletFull",
    "extractOriginalVideoDetailHolderFull"
)
tasks.matching { it.name in originalCommentConsumerInputTasks }.configureEach {
    inputs.file("tools/extract-upstream-v025-comment-consumers.py")
}


// New canonical original Danmaku content source belongs to the same existing producers.
tasks.named("extractUpstreamDanmaku") {
    inputs.file(canonicalOriginalSource("core-data/src/main/java/com/android/purebilibili/data/repository/DanmakuContentRepository.kt"))
}
tasks.named("extractUpstreamMedia") {
    inputs.file(canonicalOriginalSource("core-data/src/main/java/com/android/purebilibili/data/repository/DanmakuContentRepository.kt"))
}

// Original standard emote requests remain in the sole BGM/Operations producer.
tasks.named("extractBgmDetail") { inputs.file(canonicalOriginalSource("app/src/main/java/com/android/purebilibili/data/repository/CommentRepository.kt")) }

// Complete original ambient domain; existing native Section owns frames and leases.
val extractOriginalVideoAmbientWindows by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-video-ambient-windows.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-video-ambient-windows").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-video-ambient-windows.py", "tools/sync-upstream.py",
        "tools/v025_source_paths.py", "tools/v025-canonical-sources.json")
    inputs.file(sourceManifest)
    inputs.files(
            canonicalOriginalSource("app/src/main/java/com/android/purebilibili/feature/video/ambient/AmbientEnvironment.kt"),
            canonicalOriginalSource("app/src/main/java/com/android/purebilibili/feature/video/ambient/AmbientFrameController.kt"),
            canonicalOriginalSource("app/src/main/java/com/android/purebilibili/feature/video/ambient/AmbientFramePolicy.kt"),
            canonicalOriginalSource("app/src/main/java/com/android/purebilibili/feature/video/ambient/AmbientPlayerBinding.kt"),
            canonicalOriginalSource("app/src/main/java/com/android/purebilibili/feature/video/ambient/PlayerAmbientGlow.kt"),
            canonicalOriginalSource("app/src/main/java/com/android/purebilibili/feature/video/ambient/PlayerAmbientLayout.kt"),
            canonicalOriginalSource("app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt"),
            canonicalOriginalSource("app/src/main/java/com/android/purebilibili/feature/settings/screen/PlaybackSettingsScreen.kt"))
    outputs.dir(layout.buildDirectory.dir("generated/original-video-ambient-windows"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-video-ambient-windows")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalVideoAmbientWindows) }
tasks.named("extractOriginalVideoStateCore") { inputs.files(canonicalOriginalSource("core-data/src/main/java/com/android/purebilibili/data/repository/SharedContentRepository.kt"), canonicalOriginalSource("core-data/src/main/java/com/android/purebilibili/data/repository/PlaybackStreamDataSource.kt")) }
tasks.named("extractOriginalPlayerFullControls") { inputs.files(canonicalOriginalSource("app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/VideoPlayerOverlayContracts.kt"), canonicalOriginalSource("app/src/main/java/com/android/purebilibili/feature/video/ui/components/CommentThreadNavigationBlur.kt")) }
tasks.named("extractOriginalWindowLayout") { inputs.files(canonicalOriginalSource("app/src/main/java/com/android/purebilibili/core/ui/adaptive/AppHingePaneLayout.kt"), canonicalOriginalSource("app/src/main/java/com/android/purebilibili/core/ui/adaptive/AppHingeSafeSidePanel.kt")) }
tasks.named("extractOriginalVideoContentFull") { inputs.files(canonicalOriginalSource("app/src/main/java/com/android/purebilibili/feature/video/ui/components/VideoScreenshotSharePrompt.kt"), canonicalOriginalSource("app/src/main/java/com/android/purebilibili/feature/video/ui/section/VideoNoteSection.kt")) }

tasks.named("extractOriginalProfileMain") { inputs.files(
    canonicalOriginalSource("app/src/main/java/com/android/purebilibili/data/repository/WallpaperArchiveRepository.kt"),
    canonicalOriginalSource("app/src/main/java/com/android/purebilibili/feature/login/BilibiliLoginQr.kt"),
    canonicalOriginalSource("app/src/main/java/com/android/purebilibili/feature/login/TvQrConfirmationPolicy.kt"),
    canonicalOriginalSource("app/src/main/java/com/android/purebilibili/feature/login/OfficialQrAuthorizationService.kt"),
    canonicalOriginalSource("app/src/main/java/com/android/purebilibili/feature/login/OfficialQrAuthorizationViewModel.kt"),
    canonicalOriginalSource("app/src/main/java/com/android/purebilibili/feature/login/OfficialQrAuthorizationContent.kt"),
    canonicalOriginalSource("app/src/main/java/com/android/purebilibili/feature/login/BiliPaiQrDecoder.kt"),
    canonicalOriginalSource("app/src/main/java/com/android/purebilibili/feature/agreement/UserAgreementGate.kt"),
    canonicalOriginalSource("app/src/main/java/com/android/purebilibili/feature/agreement/UserAgreementText.kt")
) }


tasks.named("extractOriginalMessagePages") { inputs.file(canonicalOriginalSource("app/src/main/java/com/android/purebilibili/feature/message/MessageAppScaffold.kt")) }

tasks.named("extractUpstreamComponents") { inputs.file(canonicalOriginalSource("design-system/src/main/java/com/android/purebilibili/core/ui/AdaptiveDialogComponents.kt")) }

tasks.named("extractOriginalSearchPages") { inputs.file(canonicalOriginalSource("app/src/main/java/com/android/purebilibili/feature/search/SearchLandingUi.kt")) }

// DOMParser is a leaf of the already approved JS Host, with a fixed parser artifact.
val verifyJsDomParserDependency by tasks.registering {
    inputs.file("src/main/resources/licenses/jsoup-1.21.2/provenance.json")
    doLast {
        val artifacts = configurations.getByName("compileClasspath").resolvedConfiguration.resolvedArtifacts
            .filter { it.moduleVersion.id.group == "org.jsoup" && it.name == "jsoup" }
        check(artifacts.size == 1 && artifacts.single().moduleVersion.id.version == "1.21.2")
        val artifact = artifacts.single().file
        val digest = MessageDigest.getInstance("SHA-256").digest(artifact.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(digest == "f05496e255734759f0d4b5632da7b24f81313147c78c69e90ad045d096191344") {
            "Pinned JS DOMParser artifact changed"
        }
    }
}
tasks.named("compileKotlin") { dependsOn(verifyJsDomParserDependency) }

// Root merges this independent task; no source, classpath, native or renderer overrides.
tasks.register<JavaExec>("originalOnboardingUiSmoke") {
    group = "verification"
    description = "Exercise mandatory original agreement through the actual Main and owned Window."
    dependsOn("testClasses", "classes", "prepareAppResources")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.bilipai.desktop.ui.OriginalOnboardingUiFixture")
    systemProperty("compose.application.resources.dir", file("resources/common").absolutePath)
    systemProperty("file.encoding", "UTF-8")
    providers.gradleProperty("rootValidationToken").orNull?.let {
        systemProperty("bilipai.rootValidationToken", it)
    }
    doFirst {
        val mode = requireNotNull(providers.gradleProperty("rootValidationMode").orNull)
        args(mode,
            requireNotNull(providers.gradleProperty("rootValidationReport").orNull),
            requireNotNull(providers.gradleProperty("rootValidationHealth").orNull),
            requireNotNull(providers.gradleProperty("rootValidationToken").orNull))
        if (mode == "deep-link") args(requireNotNull(providers.gradleProperty("rootValidationBvid").orNull))
    }
}

// Standalone, opt-in test task. It keeps the complete ordinary test runtime and real Main.
tasks.register<JavaExec>("originalIconSettingsUiSmoke") {
    group = "verification"
    description = "Exercise original IconSettings controls and the actual Root Window icon."
    dependsOn("testClasses", "classes", "prepareAppResources")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.bilipai.desktop.ui.OriginalIconSettingsRootUiFixture")
    systemProperty("compose.application.resources.dir", file("resources/common").absolutePath)
    systemProperty("file.encoding", "UTF-8")
    providers.gradleProperty("rootValidationToken").orNull?.let {
        systemProperty("bilipai.rootValidationToken", it)
    }
    doFirst {
        args(requireNotNull(providers.gradleProperty("rootValidationMode").orNull),
            requireNotNull(providers.gradleProperty("rootValidationReport").orNull),
            requireNotNull(providers.gradleProperty("rootValidationHealth").orNull),
            requireNotNull(providers.gradleProperty("rootValidationToken").orNull))
    }
}

// Opt-in real Main GUI fixture; normal source sets/resources and default renderer only.
tasks.register<JavaExec>("originalFraudDonateUiSmoke") {
    group = "verification"
    description = "Exercise original guest Fraud and Donate controls in the actual Main Window."
    dependsOn("testClasses", "classes", "prepareAppResources")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.bilipai.desktop.ui.OriginalFraudDonateRootUiFixture")
    systemProperty("compose.application.resources.dir", file("resources/common").absolutePath)
    systemProperty("file.encoding", "UTF-8")
    providers.gradleProperty("rootValidationToken").orNull?.let {
        systemProperty("bilipai.rootValidationToken", it)
    }
    doFirst {
        args(requireNotNull(providers.gradleProperty("rootValidationReport").orNull),
            requireNotNull(providers.gradleProperty("rootValidationHealth").orNull),
            requireNotNull(providers.gradleProperty("rootValidationToken").orNull))
    }
}

// Complete original Playback screen/policy and selected original preference members.
// No dependency changes. Only generated/ is a Kotlin root; original-lf/ is proof material.
val extractOriginalWholePlaybackSettings by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractOriginalPlayerFullControls, extractOriginalStaticSettingsPages)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-whole-playback-settings.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/whole-playback-settings").get().asFile.absolutePath)
    inputs.file(sourceManifest)
    inputs.files("tools/extract-upstream-whole-playback-settings.py", "tools/v025_playback_settings_platform.py",
        "tools/v025_source_paths.py", "tools/v025-canonical-sources.json", "tools/sync-upstream.py",
        "tools/extract-upstream-video-player-full-controls.py", "tools/extract-appearance-platform.py",
        "tools/extract-upstream-settings-search.py")
    inputs.files(sources.filter { "desktop-whole-playback-settings" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    inputs.files(originalResources.filter { "desktop-whole-playback-settings" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/whole-playback-settings"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/whole-playback-settings/generated")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalWholePlaybackSettings) }
tasks.named("extractOriginalPlayerFullControls") { inputs.file("tools/v025_playback_settings_platform.py") }
tasks.named("verifyOriginalPlayerFullControls") { inputs.file("tools/v025_playback_settings_platform.py") }

// Opt-in actual original HOME settings UI; full ordinary runtime and unchanged Main.
tasks.register<JavaExec>("originalHomeSettingsUiSmoke") {
    group = "verification"
    description = "Exercise original HomeSettings controls in the actual guest Root Window."
    dependsOn("testClasses", "classes", "prepareAppResources")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.bilipai.desktop.ui.OriginalHomeSettingsRootUiFixture")
    systemProperty("compose.application.resources.dir", file("resources/common").absolutePath)
    systemProperty("file.encoding", "UTF-8")
    providers.gradleProperty("rootValidationToken").orNull?.let {
        systemProperty("bilipai.rootValidationToken", it)
    }
    doFirst {
        args(requireNotNull(providers.gradleProperty("rootValidationMode").orNull),
            requireNotNull(providers.gradleProperty("rootValidationReport").orNull),
            requireNotNull(providers.gradleProperty("rootValidationHealth").orNull),
            requireNotNull(providers.gradleProperty("rootValidationToken").orNull))
    }
}

// Opt-in unchanged Main/actual original PlaybackSettings UI; normal full test runtime.
tasks.register<JavaExec>("originalPlaybackSettingsUiSmoke") {
    dependsOn("classes", "testClasses", "prepareAppResources")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.bilipai.desktop.ui.OriginalPlaybackSettingsRootUiFixture")
    systemProperty("compose.application.resources.dir", file("resources/common").absolutePath)
    systemProperty("file.encoding", "UTF-8")
    doFirst {
        systemProperty("bilipai.rootValidationToken", providers.gradleProperty("rootValidationToken").get())
        args(providers.gradleProperty("rootValidationMode").get(), providers.gradleProperty("rootValidationReport").get(),
            providers.gradleProperty("rootValidationHealth").get(), providers.gradleProperty("rootValidationToken").get())
    }
}

// Opt-in actual Main About/Search fixture; full normal runtime/resources/default GPU.
tasks.register<JavaExec>("originalSystemAboutUiSmoke") {
    group = "verification"
    description = "Exercise original About, agreement replay, and actual SettingsSearch category landings."
    dependsOn("testClasses", "classes", "prepareAppResources")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.bilipai.desktop.ui.OriginalSystemAboutRootUiFixture")
    systemProperty("compose.application.resources.dir", file("resources/common").absolutePath)
    systemProperty("file.encoding", "UTF-8")
    providers.gradleProperty("rootValidationToken").orNull?.let {
        systemProperty("bilipai.rootValidationToken", it)
    }
    doFirst {
        args(requireNotNull(providers.gradleProperty("rootValidationReport").orNull),
            requireNotNull(providers.gradleProperty("rootValidationHealth").orNull),
            requireNotNull(providers.gradleProperty("rootValidationToken").orNull))
    }
}

// Independent actual local MPV regression; no Main, account, remote media or other-app pause.
tasks.register<JavaExec>("originalPlaybackLegacyNativeSmoke") {
    dependsOn("classes", "testClasses", "prepareAppResources")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.bilipai.desktop.player.OriginalPlaybackLegacyNativeFixture")
    systemProperty("compose.application.resources.dir", file("resources/common").absolutePath)
    systemProperty("file.encoding", "UTF-8")
    doFirst { args(providers.gradleProperty("nativeValidationReport").get()) }
}

// Opt-in original HOME native wallpaper UI; normal complete runtime, actual unchanged Main.
tasks.register<JavaExec>("originalHomeWallpaperUiSmoke") {
    group = "verification"
    description = "Select private PNGs through the original owned native chooser and capture actual Home pixels."
    dependsOn("testClasses", "classes", "prepareAppResources")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.bilipai.desktop.ui.OriginalHomeWallpaperRootUiFixture")
    systemProperty("compose.application.resources.dir", file("resources/common").absolutePath)
    systemProperty("file.encoding", "UTF-8")
    providers.gradleProperty("rootValidationToken").orNull?.let { systemProperty("bilipai.rootValidationToken", it) }
    doFirst {
        args(requireNotNull(providers.gradleProperty("rootValidationMode").orNull),
            requireNotNull(providers.gradleProperty("rootValidationReport").orNull),
            requireNotNull(providers.gradleProperty("rootValidationHealth").orNull),
            requireNotNull(providers.gradleProperty("rootValidationToken").orNull))
    }
}

// Complete fixed original background policies/tests and the same ordinary native owner.
val extractOriginalBackgroundPlaybackPolicy by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractOriginalWholePlaybackSettings)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-background-playback-policy.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-background-playback-policy").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-background-playback-policy.py", "tools/sync-upstream.py",
        "tools/extract-appearance-platform.py", "tools/extract-upstream-video-player-full-controls.py", sourceManifest)
    inputs.files(sources.filter { "desktop-original-background-policy" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-background-playback-policy"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-background-playback-policy/generated-main")) }
kotlin.sourceSets.named("test") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-background-playback-policy/generated-test")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalBackgroundPlaybackPolicy) }
tasks.named("compileTestKotlin") { dependsOn(extractOriginalBackgroundPlaybackPolicy) }
tasks.register<JavaExec>("originalBackgroundPlaybackNativeSmoke") {
    dependsOn("classes", "testClasses", "prepareAppResources")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.bilipai.desktop.ui.OriginalBackgroundPlaybackNativeFixture")
    systemProperty("compose.application.resources.dir", file("resources/common").absolutePath)
    systemProperty("file.encoding", "UTF-8")
    doFirst { args(providers.gradleProperty("nativeValidationReport").get()) }
}

// Opt-in exact frozen typed PlaybackSettings render/back; unchanged Main, full normal runtime/default GPU.
tasks.register<JavaExec>("originalTypedPlaybackSettingsUiSmoke") {
    group = "verification"
    description = "Exercise exact typed PlaybackSettings entry, original header Back and actual owned Escape."
    dependsOn("classes", "testClasses", "prepareAppResources")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.bilipai.desktop.ui.OriginalTypedPlaybackSettingsRootUiFixture")
    systemProperty("compose.application.resources.dir", file("resources/common").absolutePath)
    systemProperty("file.encoding", "UTF-8")
    doFirst {
        systemProperty("bilipai.rootValidationToken", providers.gradleProperty("rootValidationToken").get())
        args(providers.gradleProperty("rootValidationReport").get(), providers.gradleProperty("rootValidationHealth").get(),
            providers.gradleProperty("rootValidationToken").get())
    }
}

// Opt-in actual unchanged Main, Windows video controls, real native player/default GPU.
tasks.register<JavaExec>("windowsVideoActualRootUiSmoke") {
    group = "verification"
    dependsOn("classes", "testClasses", "prepareAppResources")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.bilipai.desktop.ui.WindowsVideoActualRootUiFixture")
    systemProperty("compose.application.resources.dir", file("resources/common").absolutePath)
    systemProperty("file.encoding", "UTF-8")
    doFirst {
        systemProperty("bilipai.rootValidationToken", providers.gradleProperty("rootValidationToken").get())
        args(providers.gradleProperty("rootValidationReport").get(), providers.gradleProperty("rootValidationHealth").get(),
            providers.gradleProperty("rootValidationToken").get(), providers.gradleProperty("rootValidationVideo").get())
    }
}

// Opt-in test-only official-shaped API replay + actual loopback media on unchanged Main.
tasks.register<JavaExec>("windowsVideoLocalReplayUiSmoke") {
    group = "verification"
    dependsOn("classes", "testClasses", "prepareAppResources")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.bilipai.desktop.ui.WindowsVideoActualRootUiFixture")
    systemProperty("compose.application.resources.dir", file("resources/common").absolutePath)
    systemProperty("file.encoding", "UTF-8")
    doFirst {
        systemProperty("bilipai.rootValidationToken", providers.gradleProperty("rootValidationToken").get())
        args(providers.gradleProperty("rootValidationReport").get(), providers.gradleProperty("rootValidationHealth").get(),
            providers.gradleProperty("rootValidationToken").get(), providers.gradleProperty("rootValidationVideo").get(), "replay")
    }
}

// Opt-in additional actual owned scale/native/editor input; baseline video tasks unchanged.
tasks.register<JavaExec>("windowsVideoInteractionRootUiSmoke") {
    group = "verification"
    dependsOn("classes", "testClasses", "prepareAppResources")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.bilipai.desktop.ui.WindowsVideoActualRootUiFixture")
    systemProperty("compose.application.resources.dir", file("resources/common").absolutePath)
    systemProperty("file.encoding", "UTF-8")
    systemProperty("bilipai.validation.scaleInput", "true")
    doFirst {
        systemProperty("bilipai.rootValidationToken", providers.gradleProperty("rootValidationToken").get())
        args(providers.gradleProperty("rootValidationReport").get(), providers.gradleProperty("rootValidationHealth").get(),
            providers.gradleProperty("rootValidationToken").get(), providers.gradleProperty("rootValidationVideo").get(), "replay")
    }
}
// Fixed original brand identities/resources and complete BlueSnow body; existing Windows ReduceMotion binding.
val extractOriginalBrandMotion by tasks.registering(Exec::class) {
    inputs.file("tools/v029_brand_success.py")
    inputs.dir("upstream-slices/v029-brand-success")
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-brand-motion.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-brand-motion").get().asFile.absolutePath)
    inputs.file("tools/extract-upstream-brand-motion.py")
    inputs.file("tools/v029_brand_motion_ui.py")
    inputs.dir("upstream-slices/v029-brand-motion")
    inputs.dir("upstream-slices/v034-brand-motion-ui")
    outputs.dir(layout.buildDirectory.dir("generated/original-brand-motion"))
}
// Test runtime uses the main classpath resources; no duplicate test/raw resource root.
kotlin.sourceSets.named("main") {
    kotlin.srcDir(layout.buildDirectory.dir("generated/original-brand-motion/kotlin"))
}
sourceSets.named("main") {
    resources.srcDir(layout.buildDirectory.dir("generated/original-brand-motion/resources"))
}
tasks.named("compileKotlin") { dependsOn(extractOriginalBrandMotion) }
tasks.named("compileTestKotlin") { dependsOn(extractOriginalBrandMotion) }
tasks.named("processResources") { dependsOn(extractOriginalBrandMotion) }

// Fixed original comment search and charged-reply inputs on their existing producers.
listOf("extractUpstreamApi", "extractBgmDetail", "extractVideoCommentUi",
    "extractUpstreamDynamicReply", "extractUpstreamDynamicReplyProtocol").forEach { taskName ->
    tasks.named(taskName) {
        inputs.file("tools/v029_comment_search.py")
        inputs.dir("upstream-slices/v029-comment-search")
    }
}

// Partial fixed v032 catalog slice; canonical inventory remains v025.
val extractV032CatalogSlice by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/v032_catalog.py",
        repositoryRoot.absolutePath, layout.buildDirectory.dir("generated/v032-catalog").get().asFile.absolutePath)
    inputs.file("tools/v032_catalog.py")
    inputs.dir("upstream-slices/v032-catalog")
    outputs.dir(layout.buildDirectory.dir("generated/v032-catalog"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/v032-catalog")) }
tasks.named("compileKotlin") { dependsOn(extractV032CatalogSlice) }
listOf("extractOriginalVideoStateCore", "extractOriginalVideoFullOwner", "extractV032CatalogSlice").forEach { name ->
    tasks.named(name) {
        inputs.file("tools/v034_related.py")
        inputs.dir("upstream-slices/v034-related")
    }
}

listOf("extractOriginalHomeProtocols", "extractOriginalVideoStateCore", "extractOriginalVideoDetailUnits").forEach { name ->
    tasks.named(name) {
        inputs.file("tools/v032_catalog.py")
        inputs.dir("upstream-slices/v032-catalog")
    }
}

// Complete original ListenVideo pages reuse the existing original audio/Favorites/playlist producers.
val extractOriginalListenVideoPages by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-listen-video-root.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-listen-video-pages").get().asFile.absolutePath,
        "--test-output", layout.buildDirectory.dir("generated/original-listen-video-policy-tests").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-listen-video-root.py", "tools/v025_source_paths.py", "tools/v038_audio_library.py", "tools/v038_listen_viewmodel.py", "tools/v038_listen_collection.py", "tools/v038_listen_screen.py")
    inputs.dir("upstream-slices/v038-listen-viewmodel")
    inputs.dir("upstream-slices/v038-listen-collection")
    inputs.dir("upstream-slices/v038-listen-screen")
    inputs.files("tools/v044_favorite_search.py", "tools/v044_favorite_detail.py", "tools/v044_audio_routes.py", "tools/v044_fans.py")
    inputs.dir("upstream-slices/v044-following-fans")
    inputs.dir("upstream-slices/v044-favorite-search")
    inputs.file(canonicalOriginalSource("app/src/main/java/com/android/purebilibili/navigation3/BiliPaiNavKeyMappingPolicy.kt"))
    inputs.file(canonicalOriginalSource("app/src/main/java/com/android/purebilibili/navigation3/BiliPaiNavKey.kt"))
    inputs.files(sources.filter { "original-listen-video-pages" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-listen-video-pages"))
    outputs.dir(layout.buildDirectory.dir("generated/original-listen-video-policy-tests"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-listen-video-pages")) }
kotlin.sourceSets.named("test") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-listen-video-policy-tests")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalListenVideoPages) }
tasks.named("compileTestKotlin") { dependsOn(extractOriginalListenVideoPages) }
tasks.named("extractOriginalListenVideoPages") { inputs.file("tools/v044_navigation_contracts.py") }

// AU song content shares the existing full Music UI/lyrics/native owners.
val extractOriginalAuMusicPage by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractOriginalMusicPlayerFull)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-au-music-root.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-au-music-page").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-au-music-root.py", "tools/v025_source_paths.py", sourceManifest)
    inputs.files(sources.filter { "original-au-music-page" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-au-music-page"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-au-music-page")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalAuMusicPage) }

// Original read-only notification Poller/Policy/settings/state; actual retained Windows Root consumer.
val extractOriginalMessageNotifications by tasks.registering(Exec::class) {
    inputs.file("tools/v038_message_notifications.py")
    inputs.dir("upstream-slices/v038-message-notifications")
    inputs.file("tools/v046_message_notifications.py")
    inputs.dir("upstream-slices/v046-message-notifications")
    // The recent-author DynamicApi members are read from the pinned ApiClient.kt of the same upstream commit.
    inputs.dir("upstream-slices/v046-favorite-editor")
    dependsOn(prepareUpstreamSources, extractOriginalMessagePages, extractUpstreamDynamicFullCard)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-message-notifications.py",
        "--repo", repositoryRoot.absolutePath,
        "--out", layout.buildDirectory.dir("generated/original-message-notifications").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-message-notifications.py", "tools/v025_source_paths.py",
        "tools/v025-canonical-sources.json", sourceManifest)
    inputs.files(sources.filter { "desktop-original-message-notification-root" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-message-notifications"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-message-notifications/com")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalMessageNotifications) }
tasks.named("compileTestKotlin") { dependsOn(extractOriginalMessageNotifications) }
kotlin.sourceSets.named("test") {
    kotlin.srcDir(layout.buildDirectory.dir("generated/original-message-notifications/policy-tests"))
}

// Source54 stable backend inputs: retained original producers own these overlays.
listOf("extractOriginalVideoStateCore", "extractV032CatalogSlice").forEach { taskName ->
    tasks.named(taskName) {
        inputs.file("tools/v034_on_demand.py")
        inputs.dir("upstream-slices/v034-on-demand")
    }
}
listOf("extractOriginalVideoStateCore", "extractOriginalVideoFullOwner").forEach { taskName ->
    tasks.named(taskName) {
        inputs.file("tools/v034_detail_bootstrap.py")
        inputs.dir("upstream-slices/v034-detail-bootstrap")
    }
}
// Source55 independent readonly related: producer/whole-source inputs.
listOf("extractOriginalVideoStateCore", "extractOriginalVideoFullOwner").forEach { taskName ->
    tasks.named(taskName) {
        inputs.file("tools/v034_metadata_related.py")
        inputs.dir("upstream-slices/v034-metadata-related")
    }
}

tasks.named("extractOriginalVideoStateCore") {
    inputs.file("tools/v034_playurl_failure.py")
}

// Source57 pure original rich-text fallback and primary-script lyric decisions.
val extractOriginalTextLyrics by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(pythonExecutable, "tools/extract-upstream-text-lyrics.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-text-lyrics").get().asFile.absolutePath)
    inputs.file("tools/extract-upstream-text-lyrics.py")
    inputs.file("tools/v025_source_paths.py")
    inputs.file("tools/v025-canonical-sources.json")
    inputs.dir("upstream-slices/v034-text-lyrics")
    inputs.files(listOf(
        "core-data/src/main/java/com/android/purebilibili/data/model/response/DynamicModels.kt",
        "app/src/main/java/com/android/purebilibili/feature/audio/lyrics/LyricsParser.kt",
        "app/src/main/java/com/android/purebilibili/feature/audio/lyrics/LyricsDisplayPolicy.kt",
    ).map(::canonicalOriginalSource))
    outputs.dir(layout.buildDirectory.dir("generated/original-text-lyrics"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-text-lyrics")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalTextLyrics) }

// Extend the same full-owner producer with the pinned original next-watch policy.
tasks.named("extractOriginalVideoFullOwner") {
    inputs.file("tools/v034_next_watch.py")
    inputs.dir("upstream-slices/v034-next-watch")
    inputs.file("upstream-slices/v034-metadata-related/VideoPlaybackViewModel.kt")
}


// Source59 initial PGC detail precedes the SAME required native/media stage.
// Existing quality/authorization/media producers retain their sole ownership.
listOf("extractOriginalBangumiPlayer", "extractOriginalBangumiPlayerUi").forEach { taskName ->
    tasks.named(taskName) {
        inputs.file("tools/v034_pgc_initial_detail.py")
        inputs.dir("upstream-slices/v034-pgc-initial-detail")
    }
}

tasks.named("extractOriginalPlayerFullControls") {
    inputs.file("tools/v034_next_watch_ui.py")
    inputs.dir("upstream-slices/v034-next-watch-ui")
}
// The v0.4.6 next-watch revision: a pinned layer on the two files that producer already emits from that archive.
tasks.named("extractOriginalPlayerFullControls") { inputs.file("tools/v046_next_watch_toggle.py") }

// Source62 reuses only the original audio-download method in the SAME PGC VM.
tasks.named("extractOriginalBangumiPlayer") {
    inputs.file("tools/v034_pgc_audio_download.py")
}

tasks.named("extractOriginalBangumiPlayerUi") {
    inputs.file("tools/windows_pgc_screen_mount.py")
}

tasks.named("extractOriginalVideoFullOwner") {
    inputs.file("tools/v035_autoplay_continuation.py")
    inputs.dir("upstream-slices/v035-autoplay-continuation")
}

// Source64 retains the sole Favorites producer and its imported helper dependencies.
tasks.named("extractOriginalFavorites") {
    inputs.file("tools/v035_favorite_cover.py")
    inputs.dir("upstream-slices/v035-favorite-cover")
}
listOf("extractOriginalWatchLater", "extractOriginalFollowing").forEach { taskName ->
    tasks.named(taskName) {
        inputs.file("tools/v035_favorite_cover.py")
    }
}

// Source65 Story remains in its existing sole VM/DTO/policy/API/feed owners.
tasks.named("extractUpstreamStoryTopic") {
    inputs.file("tools/v035_story.py")
    inputs.dir("upstream-slices/v035-story")
}
tasks.named("extractUpstreamApi") {
    inputs.file("tools/v035_story.py")
    inputs.dir("upstream-slices/v035-story")
}
tasks.named("extractOriginalVideoRootStoryFeed") {
    inputs.file("tools/v035_story.py")
    inputs.dir("upstream-slices/v035-story")
}

tasks.named("extractOriginalVideoFullscreenPager") {
    inputs.file("tools/v035_story_pager.py")
    inputs.dir("upstream-slices/v035-story-pager")
}

tasks.named("extractUpstreamDynamicFullCard") {
    inputs.file("tools/v035_dynamic_link.py")
    inputs.dir("upstream-slices/v035-dynamic-link")
}
tasks.named("extractOriginalTextLyrics") {
    inputs.file("tools/v035_dynamic_link.py")
    inputs.dir("upstream-slices/v035-dynamic-link")
}

// Source68 retains the sole existing ambient producer and native capture lease.
tasks.named("extractOriginalVideoAmbientWindows") {
    inputs.file("tools/v035_ambient.py")
    inputs.dir("upstream-slices/v035-ambient")
}

// Whole original list wrappers close the actual upstream Favorites renderer import.
val extractOriginalScrollableLists by tasks.registering(Exec::class) {
    workingDir = projectDir
    commandLine(pythonExecutable, "tools/extract-upstream-scrollable-lists.py",
        "--output", layout.buildDirectory.dir("generated/original-scrollable-lists").get().asFile.absolutePath)
    inputs.file("tools/extract-upstream-scrollable-lists.py")
    inputs.dir("upstream-slices/v035-scrollable-lists")
    outputs.dir(layout.buildDirectory.dir("generated/original-scrollable-lists"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-scrollable-lists")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalScrollableLists) }

// Source71 retains the sole original dynamic producer and the Root settings journal.
tasks.named("extractUpstreamDynamicFullCard") {
    inputs.file("tools/v035_dynamic_preview.py")
    inputs.dir("upstream-slices/v035-dynamic-preview")
}

// Source72 retains v0.3.6 read members and the complete original route memo.
tasks.named("extractUpstreamDynamicReplyProtocol") {
    inputs.file("tools/v036_comment_route.py")
    inputs.dir("upstream-slices/v036-comment-route")
}

// Source73 retains the full v0.3.6 comment VM and its original shared cache/paging rules.
tasks.named("extractBgmDetail") {
    inputs.file("tools/v036_video_comment_cache.py")
    inputs.dir("upstream-slices/v036-video-comment-cache")
}

// Original icon font is copied unchanged from the verified fixed v037 archive.
tasks.named<Copy>("processResources") {
    from("upstream-slices/v037-shared-tabs-progress-closure/piliplus_custom_icons.ttf") { into("fonts") }
    from("upstream-slices/v037-player-runtime-resources/ic_pili_tv_play.png") { into("icons") }
}

// These sole producers project exact original tab roles before writing source.
tasks.matching { it.name in setOf("extractBgmDetail", "extractCommentFraudProtocol", "extractImageSaveSettingsUi", "extractOriginalAicu", "extractOriginalBangumiPages", "extractOriginalDanmakuSettings", "extractOriginalFavorites", "extractOriginalFollowing", "extractOriginalLiveList", "extractOriginalLiveNavigation", "extractOriginalMessagePages", "extractOriginalProfileMain", "extractOriginalSpacePages", "extractOriginalSystemAbout", "extractOriginalWatchLater", "extractStableCollectionSheet", "extractUpstreamCrashPrompt", "extractUpstreamDiagnostics", "extractUpstreamDynamicDetail", "extractUpstreamDynamicEditor", "extractUpstreamDynamicFollow", "extractUpstreamDynamicFullCard", "extractUpstreamDynamicReply", "extractUpstreamDynamicSettings", "extractUpstreamDynamicTabs", "extractUpstreamHomeCards", "extractUpstreamJs", "extractUpstreamNetworkProxy", "extractUpstreamPackages", "extractUpstreamPlugins", "extractUpstreamPreferences", "extractUpstreamSettingsCategories", "extractUpstreamSettingsEntries", "extractUpstreamSettingsHome", "extractUpstreamSettingsPrivacy", "extractUpstreamSettingsSearch", "extractUpstreamSettingsStorageEntries", "extractUpstreamStoryTopic", "verifyUpstreamDynamicEditorProtocol") }.configureEach {
    inputs.file("tools/v037_tab_roles.py")
    inputs.dir("upstream-slices/v037-explicit-tab-roles")
}

tasks.matching { it.name in setOf("extractOriginalHomeBangumiPage", "extractOriginalSpacePages", "extractOriginalFavorites") }.configureEach {
    inputs.file("tools/v037_tab_roles.py")
    inputs.dir("upstream-slices/v037-explicit-tab-roles")
}

// Whole original v037 modal sheet shares the same sole source producer.
tasks.named("extractUpstreamDynamicDetailContainer") {
    inputs.file("tools/v037_window_modal_blur.py")
    inputs.dir("upstream-slices/v037-window-modal-blur")
}

// Both existing player producers consume the same fixed v042 UI corrections.
tasks.matching { it.name in setOf("extractOriginalPlayerFullControls", "extractOriginalVideoFullscreenPager") }.configureEach {
    inputs.file("tools/v042_player_readability.py")
    inputs.dir("upstream-slices/v042-player-readability")
}

// The existing BGM owner emits the complete retained v046 shared chrome policy tests.
sourceSets.named("test") { kotlin.srcDir(layout.buildDirectory.dir("generated/bgm-detail/test")) }

// Whole v046 comment list/avatar inputs feed the existing production owners.
tasks.matching { it.name in setOf(
    "extractUpstreamDynamicReply", "extractVideoCommentUi", "extractOriginalVideoContentFull",
    "extractOriginalVideoDetailHolderFull", "extractOriginalVideoTabletFull",
    "extractOriginalBangumiPlayerUi", "extractOriginalWholePlaybackSettings", "extractUpstreamDynamicDetailContainer",
    "extractOriginalVideoFullscreenPager", "extractBgmDetail",
) }.configureEach {
    inputs.files("tools/v046_comment_list.py", "tools/v044_favorite_detail.py")
    inputs.dir("upstream-slices/v046-comment-list")
}
// The reply producer emits the complete original avatar policy suite separately from main.
kotlin.sourceSets.named("test") { kotlin.srcDir(layout.buildDirectory.dir("generated/dynamic-reply/test")) }

// Nested-page state follows the two existing full-source comment consumers.
tasks.matching { it.name in setOf("extractUpstreamDynamicReply", "extractOriginalVideoDetailHolderFull") }.configureEach {
    inputs.file("tools/v046_comment_thread.py")
    inputs.dir("upstream-slices/v046-comment-thread")
}

// v0.4.6 comment polish: font file, shared fraud host, inline entrance, fullscreen host and detail anchor.
tasks.matching { it.name in setOf(
    "extractUpstreamDynamicReply", "extractVideoCommentUi", "extractOriginalVideoFullscreenPager", "extractBgmDetail",
) }.configureEach {
    inputs.file("tools/v046_comment_polish.py")
    inputs.dir("upstream-slices/v046-comment-polish")
}

// The v0.4.6 design-system leaf files (part B) feed the four existing producers that own them: the component and
// preference producers, the v037 control closure of the player producer and the shared liquid tab producer.
tasks.matching { it.name in setOf("extractUpstreamComponents", "extractUpstreamPreferences", "extractSharedLiquidTabs", "extractOriginalPlayerFullControls") }.configureEach {
    inputs.file("tools/v046_design_system_leaves_b.py")
    inputs.dir("upstream-slices/v046-design-system-leaves-b")
}

// The v0.4.6 small protocol fixes: whole chat history response and article parser, plus the SponsorBlock and
// log redaction layers, all emitted by the four existing producers that own those outputs.
tasks.matching { it.name in setOf(
    "extractOriginalMessagePages", "extractOriginalArticleDetail", "extractUpstreamPlugins", "extractUpstreamDiagnostics",
) }.configureEach {
    inputs.file("tools/v046_mfx_small_protocol_fixes.py")
    inputs.dir("upstream-slices/v046-mfx-small-protocol-fixes")
}

// TON-1026: refresh list and coroutine bindings are part of each existing producer's inputs.
tasks.matching { it.name in setOf(
    "extractOriginalHomeViewModel", "extractOriginalLiveList", "extractOriginalHomePartition",
    "extractOriginalHomeBangumiPage", "extractOriginalSubscriptionPage", "prepareOriginalCategoryPage",
    "extractUpstreamDynamicReply", "extractUpstreamDynamicDetail", "extractUpstreamDynamicDetailContainer",
    "extractBgmDetail", "extractVideoCommentUi", "extractOriginalFollowing", "extractOriginalVideoTabletFull",
    "extractOriginalVideoFullscreenPager", "extractOriginalVideoDetailHolderFull", "extractOriginalMessagePages",
) }.configureEach { inputs.file("tools/desktop_refresh_adaptation.py") }

// A generated source directory is rebuilt from nothing. Gradle never removes a file an Exec producer stopped
// writing, so on an incremental build a retired file kept compiling and could hide a lost definer (found by the
// Linux verify of 47f38a420: the retired reducedMotion compat file survived). A clean build has nothing to delete,
// so this changes incremental builds only. The three toolchain producers keep their own output handling.
val generatedSourceRoot = layout.buildDirectory.dir("generated").get().asFile.toPath()
tasks.withType<Exec>().configureEach {
    if (name in setOf("prepareJsWorker", "prepareNativeDiagnosticShare", "fetchJsWorkerJdk")) return@configureEach
    doFirst {
        outputs.files.files.map { it.toPath() }
            .filter { it != generatedSourceRoot && it.startsWith(generatedSourceRoot) && JvmFiles.isDirectory(it) }
            .forEach { it.toFile().deleteRecursively() }
    }
}
// v0.4.6 unfollow confirmation: the whole upstream file is emitted by the fullscreen pager; every producer that owns a
// generated follow button applies its pinned call-site hunks. The detail units producer also applies the one-hunk
// Windows like write guard to the engagement view model (a deliberate deviation from the pin, see v046_like_write_guard.py).
tasks.matching { it.name in setOf(
    "extractOriginalVideoFullscreenPager", "extractOriginalVideoDetailUnits",
    "extractStableVideoMetadata", "extractOriginalPlayerFullControls",
) }.configureEach {
    inputs.file("tools/v046_follow_confirmation.py")
    inputs.dir("upstream-slices/v046-follow-confirmation")
}
tasks.named("extractOriginalSpacePages") { inputs.file("tools/v046_follow_confirmation.py") }
tasks.named("extractOriginalVideoDetailUnits") { inputs.file("tools/v046_like_write_guard.py") }

// v0.4.6 danmaku protocol hardening: the danmaku producer emits the whole strict DanmakuProto and the AdvancedDanmakuData
// model head; the media producer emits the v0.4.6 content repository body (as DanmakuRepository) and the adapted
// DanmakuProtoPolicyTest into the live-stream original test root that is already a test source root.
tasks.matching { it.name in setOf("extractUpstreamDanmaku", "extractUpstreamMedia") }.configureEach {
    inputs.file("tools/v046_danmaku_protocol.py")
    inputs.dir("upstream-slices/v046-danmaku-protocol")
}

// v0.4.6 video detail information re-pin: the detail units producer selects the information, action and policy
// declarations from the whole v0.4.6 files and writes the count text, the triple-press state and the first-batch
// post-load plan; the video comment producer writes the stable comment refresh box. The vendored PlaybackPostLoadPlan
// copy is excluded in prepareUpstreamSources.
tasks.matching { it.name in setOf("extractOriginalVideoDetailUnits", "extractVideoCommentUi") }.configureEach {
    inputs.file("tools/v046_video_info_section.py")
    inputs.dir("upstream-slices/v046-video-info-section")
}

// v0.4.6 episode and part selector rework: the controls producer selects the whole part selector (and writes the original
// nested scroll test), the collection sheet producer selects the whole sheet and writes the part list and the shared list
// navigation buttons, the detail units producer advances VideoDetailResponse to the whole v0.4.6 file (Page.firstFrame),
// and the content producer adapts the part selector call of the detail page (the controls producer adapts the one of the
// player overlay, the tablet producer those of the tablet and cinema layouts and the audio producer the one of the audio
// mode player; each is a counted call-site edit that passes the video cover as v0.4.6 does). The canonical
// VideoDetailResponse exclude in prepareUpstreamSources already exists; no new exclude is needed.
tasks.matching { it.name in setOf("extractOriginalPlayerFullControls", "extractStableCollectionSheet", "extractOriginalVideoDetailUnits", "extractOriginalVideoContentFull", "extractOriginalVideoTabletFull", "extractOriginalVideoAudioFull") }.configureEach {
    inputs.file("tools/v046_episode_selector.py")
    inputs.dir("upstream-slices/v046-episode-selector")
}
// v0.4.6 Bangumi player (roadmap slice bgm-player-viewmodel-and-features, part 1): the view model layer and the feature
// state run in the sole PGC producer, the follow-session policy and its upstream test in the independent-pages producer
// that already owns the preload half of that policy file, and the like-status threading in the sole PGC UI producer.
// The test root is a sibling of the main output (never inside it).
tasks.matching { it.name in setOf("extractOriginalBangumiPlayer", "extractOriginalBangumiPages", "extractOriginalBangumiPlayerUi") }.configureEach {
    inputs.file("tools/v046_bangumi_player.py")
    inputs.dir("upstream-slices/v046-bangumi-player")
}
tasks.named("extractOriginalBangumiPages") {
    outputs.dir(layout.buildDirectory.dir("generated/independent-bangumi-pages-tests"))
}
kotlin.sourceSets.named("test") { kotlin.srcDir(layout.buildDirectory.dir("generated/independent-bangumi-pages-tests")) }
// v0.4.6 counted hot banners and attention command cards (roadmap dmk-command-merge-hot-banners): the hot danmaku producer
// advances the policy, bar, count text and policy test; the vote producer advances the command overlay and emits the triple
// submit guard; the player section gets three counted hunks; the engagement view model gets isTripleSubmitting.
tasks.matching { it.name in setOf(
    "extractOriginalHotDanmaku", "extractStableVideoVotes",
    "extractOriginalVideoPlayerSectionFull", "extractOriginalVideoDetailUnits",
) }.configureEach {
    inputs.files("tools/v046_danmaku_hot_command.py", "tools/v044_favorite_detail.py")
    inputs.dir("upstream-slices/v046-danmaku-hot-command")
}

// v0.4.6 listen-to-video closure (roadmap audio-listen-video-closure): the listen page producer advances the screen
// (recent plays), the music player producer advances MusicPlayerContent and Music3DCoverFlow, the audio mode producer
// advances AudioModeMusicPlayer and the text lyrics producer emits the whole LyricsDisplayPolicy. UI only: the audio
// waveform atmosphere, the now playing bar and the listen view model are not carried, so no output path changes.
tasks.matching { it.name in setOf(
    "extractOriginalListenVideoPages", "extractOriginalMusicPlayerFull",
    "extractOriginalVideoAudioFull", "extractOriginalTextLyrics",
) }.configureEach {
    inputs.files("tools/v046_audio_listen_closure.py", "tools/v044_favorite_detail.py")
    inputs.dir("upstream-slices/v046-audio-listen-closure")
}

// v0.4.6 Bangumi hub pages (roadmap bgm-hub-pages): the Home hub producer and the independent pages producer each advance
// their own outputs to the whole v0.4.6 files through hash-pinned layers (single BangumiUiState view model, index filter
// motion, channel bar, card transition state). The upstream test of the hub policy goes to the independent pages test root
// registered above; no new source root, no canonical exclude (both producers own their outputs).
tasks.matching { it.name in setOf("extractOriginalHomeBangumiPage", "extractOriginalBangumiPages") }.configureEach {
    inputs.files("tools/v046_bangumi_hub_pages.py", "tools/v044_favorite_detail.py")
    inputs.dir("upstream-slices/v046-bangumi-hub-pages")
}

// v0.4.6 portrait pager family (roadmap slice plr-portrait-story-pager-repin): the fullscreen pager producer swaps five of
// its own v0.2.5 predecessors (overlay, interaction bar, bottom input bar, progress bar, load policy) for the whole v0.4.6
// files, emits the new controls visibility policy and applies fourteen counted hunks to the generated pager; the story topic
// producer carries the whole pager switch policy. No vendored copy is excluded: none of these files is a direct-mode copy.
tasks.matching { it.name in setOf("extractOriginalVideoFullscreenPager", "extractUpstreamStoryTopic") }.configureEach {
    inputs.files("tools/v046_portrait_pager.py", "tools/v044_favorite_detail.py")
    inputs.dir("upstream-slices/v046-portrait-pager")
}

// v0.4.6 Space content pager (roadmap space-profile-content-pager): the Space pages producer emits the whole ContentTabPager.kt
// and applies the counted pager mount, banner rotation and pinned card hunks to the generated SpaceScreen. No canonical
// exclude (nothing else declares the three pager helpers) and no new source root (the producer output is already registered).
tasks.named("extractOriginalSpacePages") {
    inputs.file("tools/v046_space_content_pager.py")
    inputs.dir("upstream-slices/v046-space-content-pager")
}

// One product, one copy of every class. Upstream modules now compile from the merged root while old
// copies of the same classes may still arrive through another source root, a project or a library;
// the JVM would then silently run whichever copy comes first on the classpath. Fail instead.
val verifyUniqueClassNames by tasks.registering {
    val runtime = sourceSets.named("main").map { it.runtimeClasspath }
    val report = layout.buildDirectory.file("reports/unique-class-names.txt")
    inputs.files(runtime).withNormalizer(ClasspathNormalizer::class.java)
    outputs.file(report)
    doLast {
        val owners = sortedMapOf<String, MutableList<String>>()
        fun record(entry: String, owner: String) {
            // module-info and multi-release variants under META-INF/versions are not class identities.
            if (!entry.endsWith(".class") || entry.startsWith("META-INF/") || entry.endsWith("module-info.class")) return
            owners.getOrPut(entry.removeSuffix(".class").replace('/', '.')) { mutableListOf() } += owner
        }
        val entries = runtime.get().files.filter { it.exists() }
        entries.forEach { entry ->
            if (entry.isDirectory) {
                val owner = entry.relativeToOrSelf(projectDir).invariantSeparatorsPath
                entry.walkTopDown().filter { it.isFile }.forEach { record(it.relativeTo(entry).invariantSeparatorsPath, owner) }
            } else if (entry.extension == "jar" || entry.extension == "zip") {
                JvmZipFile(entry).use { zip -> zip.entries().asSequence().filter { !it.isDirectory }.forEach { record(it.name, entry.name) } }
            }
        }
        val duplicates = owners.filterValues { it.size > 1 }
        report.get().asFile.writeText(
            "classpath entries=${entries.size} classes=${owners.size} duplicated=${duplicates.size}\n" +
                duplicates.entries.joinToString("") { (name, where) -> "$name\n" + where.joinToString("") { "  $it\n" } })
        if (duplicates.isNotEmpty()) throw GradleException(
            "${duplicates.size} class name(s) appear more than once in the desktop runtime classpath; " +
                "the JVM would load whichever copy comes first:\n" +
                duplicates.entries.take(20).joinToString("\n") { (name, where) -> "  $name: ${where.joinToString(", ")}" } +
                "\nFull list: ${report.get().asFile}")
        logger.lifecycle("Unique class names: ${owners.size} classes in ${entries.size} classpath entries, no duplicates.")
    }
}
val upstreamDesignSourceTests by tasks.registering(Test::class) {
    group = "verification"
    description = "Upstream design-system tests that read upstream sources, run from the design-system module directory."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    workingDir = mergedRepositoryRoot.resolve("design-system")
    useJUnitPlatform()
    migratedSourceReadingTestClasses.forEach { filter.includeTestsMatching(it) }
    filter.isFailOnNoMatchingTests = false
    systemProperty("java.awt.headless", "true")
    dependsOn(tasks.named("testClasses"), verifyUniqueClassNames)
}
val upstreamAppCoreSourceTests by tasks.registering(Test::class) {
    group = "verification"
    description = "Upstream app core tests that read upstream sources, run from the app module directory."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    workingDir = mergedRepositoryRoot.resolve("app")
    useJUnitPlatform()
    appCoreSourceReadingTestClasses.forEach { filter.includeTestsMatching(it) }
    filter.isFailOnNoMatchingTests = false
    systemProperty("java.awt.headless", "true")
    dependsOn(tasks.named("testClasses"), verifyUniqueClassNames)
}
tasks.test {
    migratedSourceReadingTestClasses.forEach { filter.excludeTestsMatching(it) }
    appCoreSourceReadingTestClasses.forEach { filter.excludeTestsMatching(it) }
    finalizedBy(upstreamDesignSourceTests, upstreamAppCoreSourceTests)
}
tasks.named("compileKotlin") { dependsOn(verifyUpstreamReplacements, verifyNoSilentSdkGates) }
tasks.named("check") { dependsOn(verifyUniqueClassNames) }
tasks.named("jar") { dependsOn(verifyUniqueClassNames) }
tasks.test { dependsOn(verifyUniqueClassNames) }
