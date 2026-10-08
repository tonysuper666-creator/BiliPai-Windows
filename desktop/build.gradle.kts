import groovy.json.JsonSlurper
import java.security.MessageDigest
import java.nio.file.Paths as JvmPaths
import java.nio.file.Files as JvmFiles
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

val repositoryRoot = projectDir.parentFile
val sourceManifest = file("upstream-sources.json")
val manifest = if (sourceManifest.exists()) {
    JsonSlurper().parse(sourceManifest) as Map<*, *>
} else emptyMap<Any, Any>()
val sources = (manifest["sources"] as? List<*>)?.map { it as Map<*, *> } ?: emptyList()
val originalResources = (manifest["resources"] as? List<*>)?.map { it as Map<*, *> } ?: emptyList()

// Build-time original source identities only; runtime owners are unchanged.
val canonicalOriginalCatalogFile = file("tools/v025-canonical-sources.json")
val canonicalOriginalHelperFile = file("tools/v025_source_paths.py")
val canonicalOriginalCatalogBytes = canonicalOriginalCatalogFile.readBytes().also { bytes ->
    require(MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) } == "2fa53aa78cc27c76a3923cc44750128b3657c340dbcfe44ec13810cbc48becbc") {
        "The v025 canonical original source catalog changed without review."
    }
}
val canonicalOriginalCatalog = (JsonSlurper().parse(canonicalOriginalCatalogBytes) as Map<*, *>).also {
    require(it["upstreamCommit"] == "79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40")
}
val canonicalOriginalRoots = (canonicalOriginalCatalog["sourceRoots"] as List<*>).map { it.toString() }
val canonicalOriginalPrevious = canonicalOriginalCatalog["previousPaths"] as Map<*, *>
val canonicalOriginalPins = canonicalOriginalCatalog["paths"] as Map<*, *>
fun canonicalOriginalIdentity(relative: String): String {
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
fun canonicalOriginalSource(relative: String): File {
    val identity = canonicalOriginalIdentity(relative)
    val destination = File(repositoryRoot, identity)
    require(!JvmFiles.isSymbolicLink(destination.toPath())) { "Original source is a symbolic link: $relative" }
    require(destination.canonicalFile.toPath().startsWith(repositoryRoot.canonicalFile.toPath())) { "Original source escapes repository: $relative" }
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

val prepareNativeDiagnosticShare by tasks.registering(Exec::class) {
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/prepare-native-diagnostic-share.py",
        "--repo", repositoryRoot.absolutePath,
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
}

val prepareUpstreamSources by tasks.registering(Sync::class) {
    from(repositoryRoot) {
        include(sources.filter { (it["mode"] ?: "direct") == "direct" }.map { canonicalOriginalIdentity(it["path"].toString()) })
        // These three complete fixed v031 sources have one producer in the existing detail output.
        exclude(canonicalOriginalIdentity("core-data/src/main/java/com/android/purebilibili/data/model/response/VideoDetailResponse.kt"))
        exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/feature/video/ui/components/CoinDialog.kt"))
        exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/feature/video/ui/feedback/TripleActionVisualStatePolicy.kt"))
        // The fixed v029 special producer owns this one whole source now.
        exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/feature/download/DownloadDanmakuAssetService.kt"))
    }
    into(generatedUpstream)
    inputs.file(sourceManifest)
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
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-api.py",
        "--repo", repositoryRoot.absolutePath, "--output", layout.buildDirectory.dir("generated/api").get().asFile.absolutePath)
    inputs.file(canonicalOriginalSource("app/src/main/java/com/android/purebilibili/core/network/ApiClient.kt"))
    inputs.file(canonicalOriginalSource("core-data/src/main/java/com/android/purebilibili/core/network/CoreNetworkRuntime.kt"))
    inputs.file("tools/extract-upstream-api.py")
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-danmaku.py",
        "--repo", repositoryRoot.absolutePath, "--output", layout.buildDirectory.dir("generated/danmaku").get().asFile.absolutePath)
    inputs.file("tools/extract-upstream-danmaku.py")
    inputs.files(sources.filter { it["mode"] == "extracted" }.map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/danmaku"))
}
val extractUpstreamMedia by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-media.py",
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
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-audio.py",
        "--repo", repositoryRoot.absolutePath, "--output", layout.buildDirectory.dir("generated/audio").get().asFile.absolutePath)
    inputs.file("tools/extract-upstream-audio.py")
    inputs.file("tools/sync-upstream.py")
    inputs.files(sources.filter { "listen-video" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/audio"))
}
val extractUpstreamLogin by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-login-platform.py",
        "--repo", repositoryRoot.absolutePath, "--output", layout.buildDirectory.dir("generated/login").get().asFile.absolutePath)
    inputs.file("tools/extract-login-platform.py")
    inputs.file("tools/sync-upstream.py")
    inputs.files(sources.filter { "auth" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/login"))
}
val extractUpstreamPlugins by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-plugins.py",
        "--repo", repositoryRoot.absolutePath, "--output", layout.buildDirectory.dir("generated/plugins").get().asFile.absolutePath)
    inputs.file("tools/extract-upstream-plugins.py")
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
}
val extractUpstreamDiscovery by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-discovery-platform.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-settings.py",
        "--repo", repositoryRoot.absolutePath, "--output", layout.buildDirectory.dir("generated/settings").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-settings.py", "tools/extract-upstream-media.py", "tools/sync-upstream.py")
    inputs.files(sources.filter { "backup" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/settings"))
}
val extractUpstreamPlayback by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-playback-platform.py",
        "--repo", repositoryRoot.absolutePath, "--output", layout.buildDirectory.dir("generated/playback").get().asFile.absolutePath)
    inputs.files("tools/extract-playback-platform.py", "third-party/media3-error-codes.json")
    inputs.files(sources.filter { "recovery" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/playback"))
}
val extractUpstreamSearch by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-search-platform.py",
        "--repo", repositoryRoot.absolutePath, "--output", layout.buildDirectory.dir("generated/search").get().asFile.absolutePath)
    inputs.files("tools/extract-search-platform.py", "tools/extract-upstream-media.py", "tools/sync-upstream.py")
    inputs.files(sources.filter { "search-native" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/search"))
}
val extractUpstreamCast by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-cast-platform.py",
        "--repo", repositoryRoot.absolutePath, "--output", layout.buildDirectory.dir("generated/cast").get().asFile.absolutePath)
    inputs.files("tools/extract-cast-platform.py", "tools/extract-upstream-media.py", "tools/sync-upstream.py")
    inputs.files(sources.filter { "dlna-cast" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/cast"))
}
val extractUpstreamPackages by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-packages.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-playback-watchdogs.py",
        "--repo", repositoryRoot.absolutePath, "--output", layout.buildDirectory.dir("generated/watchdogs").get().asFile.absolutePath)
    inputs.files("tools/extract-playback-watchdogs.py", "third-party/media3-player-state-codes.json")
    inputs.files(sources.filter { "watchdogs" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/watchdogs"))
}
val verifyGoogleCastSources by tasks.registering(Exec::class) {
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/verify-google-cast-sources.py", "--desktop", projectDir.absolutePath)
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-google-cast-platform.py",
        "--repo", repositoryRoot.absolutePath, "--output", layout.buildDirectory.dir("generated/google-cast").get().asFile.absolutePath)
    inputs.files("tools/extract-google-cast-platform.py", "tools/sync-upstream.py")
    inputs.files(sources.filter { "google-cast-v2" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/google-cast"))
}

val extractUpstreamJs by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-js.py",
        "--repo", repositoryRoot.absolutePath, "--output", layout.buildDirectory.dir("generated/js").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-js.py", "tools/extract-upstream-plugins.py", "tools/extract-upstream-media.py",
        "tools/extract-upstream-api.py", "tools/sync-upstream.py")
    inputs.files(sources.filter { "js-plugins" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    inputs.file("tools/upstream-js-content-adaptations.json")
    outputs.dir(layout.buildDirectory.dir("generated/js"))
}

val generatedAppearance = layout.buildDirectory.dir("generated/appearance")
val generatedAppearanceResources = layout.buildDirectory.dir("generated/appearance-resources")
val extractUpstreamAppearance by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-appearance-platform.py",
        "--repo", repositoryRoot.absolutePath, "--policy-only",
        "--output", generatedAppearance.get().asFile.absolutePath,
        "--resource-output", generatedAppearanceResources.get().asFile.absolutePath)
    inputs.files("tools/extract-appearance-platform.py", "tools/sync-upstream.py")
    inputs.files(sources.filter { "appearance-parity" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    inputs.files(originalResources.filter { "appearance-language" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(generatedAppearance)
    outputs.dir(generatedAppearanceResources)
}

val extractUpstreamComponents by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-components.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/components").get().asFile.absolutePath)
    inputs.file("tools/extract-upstream-components.py")
    inputs.files(sources.filter { "component-parity" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/components"))
}

val extractUpstreamPreferences by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-preferences.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-settings-search.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-settings-categories.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-static-settings-pages.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-settings-home.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-settings-privacy.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-settings-entries.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-navigation-interaction.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-full-navigation.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-settings-storage-entries.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-blocked-up-platform.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-blocked-list-ui.py",
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
    dependsOn(prepareUpstreamSources, extractUpstreamSettingsCategories)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-network-proxy.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/network-proxy").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-network-proxy.py", "tools/extract-upstream-plugins.py",
        "tools/extract-upstream-media.py", "tools/extract-upstream-api.py",
        "tools/extract-upstream-settings-search.py", "tools/sync-upstream.py")
    inputs.files(sources.filter { "settings-network-proxy-parity" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    inputs.files(originalResources.filter { "settings-network-proxy-symbols" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/network-proxy"))
}

val extractUpstreamDiagnostics by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractUpstreamSettingsCategories)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-diagnostics.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-dynamic-settings.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/dynamic-settings").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-dynamic-settings.py", "tools/extract-upstream-plugins.py",
        "tools/extract-upstream-media.py", "tools/extract-appearance-platform.py",
        "tools/extract-upstream-settings-home.py", "tools/sync-upstream.py")
    inputs.files(sources.filter { "settings-home-dynamic-parity" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/dynamic-settings"))
}

val extractUpstreamHomeCards by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractUpstreamSettingsCategories, extractUpstreamSettingsHome)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-home-cards.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-home-page.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/home-page").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-home-page.py", "tools/sync-upstream.py")
    inputs.file("tools/v033_home_refresh_undo.py")
    inputs.dir("upstream-slices/v033-home-undo")
    inputs.file("tools/v029_home_load.py")
    inputs.dir("upstream-slices/v029-home-load")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "home-page" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    inputs.file("tools/v029_brand_consumers.py")
    inputs.dir("upstream-slices/v029-brand-consumers")
    outputs.dir(layout.buildDirectory.dir("generated/home-page"))
}
tasks.named("compileKotlin") { dependsOn(extractOriginalHomePage) }

// Insert after the already-installed extractOriginalHomePage. Merge, do not replace build.gradle.
val extractOriginalHomeViewModel by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-home-viewmodel.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/home-viewmodel").get().asFile.absolutePath,
        "--test-output", layout.buildDirectory.dir("generated/home-load-tests").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-home-viewmodel.py", "tools/extract-upstream-home-viewmodel-adaptations.json",
        "tools/extract-upstream-media.py", "tools/sync-upstream.py")
    inputs.file("tools/v029_home_load.py")
    inputs.dir("upstream-slices/v029-home-load")
    inputs.files(sources.filter { "home-viewmodel" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/home-viewmodel"))
    outputs.dir(layout.buildDirectory.dir("generated/home-load-tests"))
}
val extractOriginalHomeProtocols by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-home-protocols.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-live-list.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-home-partition.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-home-bangumi-page.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-bangumi-pages.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-bangumi-player.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-bangumi-player").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-bangumi-player.py", "tools/sync-upstream.py", "tools/extract-upstream-media.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "original-bangumi-player-full" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-bangumi-player"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-bangumi-player")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalBangumiPlayer) }

val extractOriginalBangumiPlayerUi by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractOriginalBangumiPlayer, extractUpstreamMedia)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-bangumi-player-ui.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-bangumi-player-ui").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-bangumi-player-ui.py", "tools/sync-upstream.py", "tools/extract-upstream-media.py")
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-subscription-page.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/home-subscription-page").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-subscription-page.py", "tools/sync-upstream.py")
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-category-page.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-home-return-navigation.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-profile-main.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-live-navigation.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-video-share-consent.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-download-list.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-download-list").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-download-list.py", "tools/sync-upstream.py",
        "tools/extract-upstream-media.py", "tools/extract-appearance-platform.py")
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-root-home-navigation.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-navigation3-host.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-wallpaper-palette.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-application-image-loader.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-profile-wallpaper-import.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-home-full-card.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/home-full-card").get().asFile.absolutePath)
    inputs.file("tools/v033_home_refresh_undo.py")
    inputs.dir("upstream-slices/v033-home-undo")
    inputs.files("tools/extract-upstream-home-full-card.py", "tools/extract-upstream-home-page.py", "tools/extract-upstream-media.py",
        "tools/extract-appearance-platform.py", "tools/extract-upstream-settings-home.py", "tools/sync-upstream.py")
    inputs.files(sources.filter { "home-full-card" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    inputs.files(sources.filter { "home-page" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    inputs.file("tools/v029_brand_consumers.py")
    inputs.dir("upstream-slices/v029-brand-consumers")
    outputs.dir(layout.buildDirectory.dir("generated/home-full-card"))
}

val extractUpstreamDynamicFullCard by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-dynamic-card.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/dynamic-full-card").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-dynamic-card.py", "tools/extract-dynamic-message-share.py",
        "tools/extract-upstream-plugins.py", "tools/extract-upstream-media.py",
        "tools/extract-upstream-api.py", "tools/extract-appearance-platform.py", "tools/sync-upstream.py")
    inputs.file("tools/v021_comment_renderer.py")
    inputs.files(sources.filter { "settings-dynamic-full-card-parity" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/dynamic-full-card"))
}

val extractUpstreamDynamicStaticImageCodec by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-dynamic-static-image-codec.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/dynamic-static-image-codec").get().asFile.absolutePath)
    inputs.file("tools/extract-upstream-dynamic-static-image-codec.py")
    inputs.files(sources.filter { "dynamic-static-image-save-parity" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/dynamic-static-image-codec"))
}

val extractImageSaveSettingsUi by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-image-save-settings-ui.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-dynamic-gallery-motion-photo.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/verify-upstream-dynamic-media.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-dynamic-editor.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/dynamic-editor").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-dynamic-editor.py", "tools/extract-upstream-dynamic-reply-protocol.py", "upstream-sources.json", "tools/extract-upstream-plugins.py",
        "tools/extract-upstream-media.py", "tools/extract-appearance-platform.py")
    inputs.file("tools/v029_comment_time.py")
    inputs.file("tools/v029_reply_renderer.py")
    inputs.file("tools/v032_comment_media.py")
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/verify-upstream-dynamic-editor-protocol.py",
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
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-dynamic-reply.py",
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
    outputs.dir(layout.buildDirectory.dir("generated/dynamic-reply"))
}

val extractUpstreamDynamicDetail by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-dynamic-detail.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/dynamic-detail").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-dynamic-detail.py", "tools/extract-upstream-plugins.py",
        "tools/extract-upstream-media.py", "tools/extract-appearance-platform.py")
    inputs.files(sources.filter { "dynamic-detail-reply" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/dynamic-detail"))
}

val extractUpstreamDynamicDetailContainer by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-dynamic-detail-container.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/dynamic-detail-container").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-dynamic-detail-container.py", "tools/extract-appearance-platform.py",
        "tools/sync-upstream.py")
    inputs.files(sources.filter { "dynamic-detail-container" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/dynamic-detail-container"))
}

val extractUpstreamDynamicReplyProtocol by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-dynamic-reply-protocol.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-dynamic-detail-protocol.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/dynamic-detail-protocol").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-dynamic-detail-protocol.py", "tools/extract-upstream-dynamic-reply-protocol.py")
    inputs.files(sources.filter { "dynamic-detail-reply" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/dynamic-detail-protocol"))
}

val extractBgmDetail by tasks.registering(Exec::class) {
    inputs.files("tools/v029_comment_composer.py", "tools/v029_comment_search.py")
    inputs.dir("upstream-slices/v029-comment-composer")
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-bgm-detail.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/bgm-detail").get().asFile.absolutePath)
    inputs.file("tools/v033_comment_refresh.py")
    inputs.dir("upstream-slices/v033-comment-refresh")
    inputs.files("tools/extract-upstream-bgm-detail.py", "tools/sync-upstream.py", "tools/extract-upstream-dynamic-reply-protocol.py",
        "tools/extract-appearance-platform.py", "tools/extract-upstream-media.py", "tools/extract-upstream-plugins.py")
    inputs.file(sourceManifest)
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-video-comment-ui.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/video-comment-ui").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-video-comment-ui.py", "tools/extract-upstream-dynamic-reply-protocol.py",
        "tools/extract-upstream-dynamic-reply.py", "tools/sync-upstream.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "stable-video-original-comments" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/video-comment-ui"))
}
tasks.named("compileKotlin") { dependsOn(extractVideoCommentUi) }

val extractOriginalFavorites by tasks.registering(Exec::class) {
    inputs.file("tools/v029_history_read_failure.py")
    inputs.file("tools/v029_history_failure_login.py")
    inputs.file("tools/v029_brand_success.py")
    inputs.dir("upstream-slices/v029-brand-success")
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-favorites.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-favorites").get().asFile.absolutePath)
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
}
val extractOriginalFavoriteFolder by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-favorite-folder-sheet.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-favorite-folder").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-favorite-folder-sheet.py", "tools/extract-upstream-dynamic-reply-protocol.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "stable-favorite-folder-sheet" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-favorite-folder"))
}
tasks.named("compileKotlin") { dependsOn(extractOriginalFavorites, extractOriginalFavoriteFolder) }

val extractLinkedDock by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-linked-dock.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/linked-dock").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-linked-dock.py", "tools/sync-upstream.py",
        "tools/extract-appearance-platform.py", "tools/extract-upstream-dynamic-reply-protocol.py")
    inputs.file(sourceManifest)
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-frosted-audio-renderer.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/frosted-audio-renderer").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-frosted-audio-renderer.py", "tools/sync-upstream.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "stable-frosted-audio-renderer" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/frosted-audio-renderer"))
}
tasks.named("compileKotlin") { dependsOn(extractFrostedAudioRenderer) }

val extractOriginalDanmakuSettings by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-danmaku-settings.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-danmaku-settings").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-danmaku-settings.py", "tools/sync-upstream.py", "tools/extract-appearance-platform.py")
    inputs.dir("upstream-slices/v027-danmaku-hot-settings")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "stable-danmaku-settings-panel" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-danmaku-settings"))
}
tasks.named("compileKotlin") { dependsOn(extractOriginalDanmakuSettings) }

val extractOriginalDanmakuListMenu by tasks.registering(Exec::class) {
    inputs.file("tools/v030_up_danmaku.py")
    inputs.dir("upstream-slices/v030-up-danmaku")
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-danmaku-list-menu.py",
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

// Explicit fixed-v027 slice; the overall canonical baseline remains v025.
val extractOriginalHotDanmaku by tasks.registering(Exec::class) {
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-hot-danmaku.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-bas-core.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-bas-renderer.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-special-danmaku.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-comment-fraud-protocol.py",
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
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-shared-liquid-tabs.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/shared-liquid-tabs").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-shared-liquid-tabs.py", "tools/sync-upstream.py")
    inputs.dir("upstream-slices/v027-liquid-glass-lens")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "stable-shared-liquid-tabs" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/shared-liquid-tabs"))
}
tasks.named("compileKotlin") { dependsOn(extractSharedLiquidTabs) }

val extractStableVideoMetadata by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-stable-video-metadata.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-stable-collection-sheet.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-stable-weekly-series.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-stable-video-votes.py",
        repositoryRoot.absolutePath, layout.buildDirectory.dir("generated/video-votes").get().asFile.absolutePath)
    inputs.files("tools/extract-stable-video-votes.py", "tools/sync-upstream.py", sourceManifest)
    inputs.file("tools/v029_command_vote.py")
    inputs.file("tools/v030_command_link.py")
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/verify-upstream-dynamic-detail-reply-protocol.py",
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
    dependsOn(prepareUpstreamSources, extractUpstreamDynamicSettings, extractUpstreamComponents,
        extractUpstreamAppearance, extractUpstreamSettingsCategories)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-dynamic-tabs.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/dynamic-tabs").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-dynamic-tabs.py", "tools/extract-upstream-plugins.py",
        "tools/extract-upstream-media.py", "tools/extract-appearance-platform.py",
        "tools/extract-upstream-settings-home.py", "tools/sync-upstream.py")
    inputs.files(sources.filter { "settings-dynamic-tabs-parity" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/dynamic-tabs"))
}

val extractOriginalDynamicAccountPolicy by tasks.registering(Exec::class) {
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-dynamic-account.py",
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
    dependsOn(prepareUpstreamSources, extractUpstreamDynamicSettings, extractUpstreamDynamicTabs)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-dynamic-follow.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-crash-prompt.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-native-music-root-platform.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-space-platform.py",
        "--repo", repositoryRoot.absolutePath, "--policy-only",
        "--output", layout.buildDirectory.dir("generated/space").get().asFile.absolutePath)
    inputs.files("tools/extract-space-platform.py", "tools/sync-upstream.py")
    inputs.files(sources.filter { "space" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/space"))
}

val extractUpstreamSpaceImagePreviews by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-space-image-preview-callers.py",
        repositoryRoot.absolutePath, layout.buildDirectory.dir("generated/space-image-previews").get().asFile.absolutePath)
    inputs.files("tools/extract-space-image-preview-callers.py", "tools/sync-upstream.py")
    inputs.files(sources.filter { "space-image-preview" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/space-image-previews"))
}

val extractUpstreamSpaceContributions by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-space-contributions.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/space-contributions").get().asFile.absolutePath)
    inputs.files("tools/extract-space-contributions.py", "tools/sync-upstream.py")
    inputs.files(sources.filter { "space-contributions" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/space-contributions"))
}

val extractUpstreamSpaceOverview by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-space-overview.py",
        "--repo", repositoryRoot.absolutePath, "--policy-only",
        "--output", layout.buildDirectory.dir("generated/space-overview").get().asFile.absolutePath)
    inputs.files("tools/extract-space-overview.py", "tools/sync-upstream.py")
    inputs.files(sources.filter { "space-overview" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/space-overview"))
}

val extractUpstreamStoryTopic by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-story-topic.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-playback-settings.py",
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
        require(dependencies.size == 8) { "The fixed appearance dependency inventory changed." }
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
        logger.lifecycle("Verified eight fixed appearance runtime dependencies and six source notices.")
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
val fetchJsWorkerJdk by tasks.registering(Exec::class) {
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/fetch-js-worker-jdk.py",
        "--archive", jsWorkerJdkArchive, "--output", jsWorkerJavaHome)
    inputs.files("tools/fetch-js-worker-jdk.py", "tools/prepare-js-worker.py")
    inputs.property("fixedJdkArchiveSha256", "f9d6e191ab098c0d416e7d588a24420a8621cd2f4720dab2459b8b7b2d2d8b4e")
    outputs.file(jsWorkerJdkArchive); outputs.dir(jsWorkerJavaHome)
}
val prepareJsWorker by tasks.registering(Exec::class) {
    dependsOn(fetchJsWorkerJdk)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/prepare-js-worker.py",
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
}
val prepareJsWorkerResources by tasks.registering(Sync::class) {
    dependsOn(prepareJsWorker)
    from(jsWorkerOutput)
    into("resources/common/js-engine")
}

kotlin.sourceSets.named("main") {
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
    kotlin.srcDir(layout.buildDirectory.dir("generated/space-contributions"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/space-overview"))
    kotlin.srcDir(layout.buildDirectory.dir("generated/space-image-previews"))
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
    kotlin.srcDir(layout.buildDirectory.dir("generated/dynamic-reply"))
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
tasks.named("compileKotlin") { dependsOn(extractUpstreamSpace, extractUpstreamSpaceContributions, extractUpstreamSpaceOverview, extractUpstreamSpaceImagePreviews) }
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
tasks.named("processResources") { dependsOn(extractUpstreamAppearance) }

val prepareOriginalPluginResources by tasks.registering(Sync::class) {
    dependsOn(prepareUpstreamSources)
    from(canonicalOriginalSource("app/src/main/res/raw/cdn_region_catalog.json")) { into("plugin") }
    from(canonicalOriginalSource("app/src/main/assets/rovniced-skin-catalog.json"))
    into(layout.buildDirectory.dir("generated/plugin-resources"))
    inputs.file(sourceManifest)
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
    implementation("com.materialkolor:material-kolor:4.1.1")
    implementation("com.materialkolor:material-color-utilities:5.0.1")
    implementation("dev.chrisbanes.haze:haze-jvm:2.0.0-alpha03")
    implementation("dev.chrisbanes.haze:haze-utils-jvm:2.0.0-alpha03")
    implementation("dev.chrisbanes.haze:haze-blur-jvm:2.0.0-alpha03")
    implementation("dev.chrisbanes.haze:haze-blur-materials-jvm:2.0.0-alpha03")
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
    implementation("io.coil-kt.coil3:coil-compose:3.5.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.5.0")
    implementation("com.google.zxing:core:3.5.4")
    implementation("org.apache.commons:commons-imaging:1.0.0-alpha6")
    implementation("commons-io:commons-io:2.19.0")
    implementation("org.apache.commons:commons-lang3:3.17.0")
    implementation("net.java.dev.jna:jna:5.17.0")
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
    systemProperty("java.awt.headless", "true")
    systemProperty("bilipai.js.workerResources", jsWorkerOutput.get().asFile.absolutePath)
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-subtitle-load-policy.py",
        "--repo", repositoryRoot.absolutePath, "--output", layout.buildDirectory.dir("generated/subtitle-load").get().asFile.absolutePath)
    inputs.files("tools/extract-subtitle-load-policy.py", "tools/extract-upstream-media.py", "tools/sync-upstream.py")
    inputs.file(canonicalOriginalSource("app/src/main/java/com/android/purebilibili/feature/video/viewmodel/VideoPlaybackViewModel.kt"))
    outputs.dir(layout.buildDirectory.dir("generated/subtitle-load"))
}
tasks.named("compileKotlin") { dependsOn(extractUpstreamStoryTopic, extractPlaybackSettings, extractSubtitleLoadPolicy) }

val extractUpstreamDownloadTransport by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-download-transport.py",
        "--repo", repositoryRoot.absolutePath, "--output", layout.buildDirectory.dir("generated/download-transport").get().asFile.absolutePath)
    inputs.file("tools/extract-upstream-download-transport.py")
    inputs.file(canonicalOriginalSource("app/src/main/java/com/android/purebilibili/feature/download/ResumableAssetDownloader.kt"))
    outputs.dir(layout.buildDirectory.dir("generated/download-transport"))
}
sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/download-transport")) }
tasks.named("compileKotlin") { dependsOn(extractUpstreamDownloadTransport) }

val extractOriginalVideoDetailUnits by tasks.registering(Exec::class) {
    inputs.file("tools/v031_repost_coin.py")
    inputs.dir("upstream-slices/v031-repost-coin")
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-video-detail-full-units.py",
        repositoryRoot.absolutePath, layout.buildDirectory.dir("generated/original-video-detail-units").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-video-detail-full-units.py", "tools/sync-upstream.py",
        "tools/extract-upstream-media.py", "tools/extract-appearance-platform.py", sourceManifest)
    inputs.file("tools/v029_video_feedback.py")
    inputs.file("tools/v029_video_feedback_origin.py")
    inputs.file("tools/v029_video_feedback_host.py")
    inputs.dir("upstream-slices/v029-video-feedback")
    inputs.files(sources.filter { "stable-video-detail-full-units" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-video-detail-units"))
}
val verifyOriginalVideoDetailMembers by tasks.registering(Exec::class) {
    dependsOn(extractOriginalVideoDetailUnits)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/verify-upstream-video-detail-full-units.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-offline-player.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-personal-lists.py",
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
    dependsOn(prepareUpstreamSources, extractOriginalFavorites)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-watchlater.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-watchlater").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-watchlater.py", "tools/extract-upstream-favorites.py",
        "tools/extract-upstream-dynamic-reply-protocol.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "stable-personal-watchlater" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-watchlater"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-watchlater")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalWatchLater) }

val extractOriginalPlayerFullControls by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractOriginalVideoDetailUnits, extractOriginalOfflinePlayer)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-video-player-full-controls.py",
        repositoryRoot.absolutePath, layout.buildDirectory.dir("generated/original-video-player-full-controls").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-video-player-full-controls.py", "tools/sync-upstream.py", "tools/extract-appearance-platform.py", sourceManifest)
    inputs.files(sources.filter { "stable-video-player-full-controls" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-video-player-full-controls"))
}
val verifyOriginalPlayerFullControls by tasks.registering(Exec::class) {
    dependsOn(extractOriginalPlayerFullControls)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/verify-upstream-video-player-full-controls.py",
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
    inputs.file("tools/v029_brand_success.py")
    inputs.dir("upstream-slices/v029-brand-success")
    dependsOn(prepareUpstreamSources, extractOriginalFavorites, extractOriginalWatchLater)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-following.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-following").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-following.py", "tools/extract-upstream-favorites.py",
        "tools/extract-upstream-dynamic-reply-protocol.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "stable-personal-following" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-following"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-following")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalFollowing) }

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
    dependsOn(prepareUpstreamSources, extractOriginalVideoDetailUnits, extractOriginalPlayerFullControls)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-video-content-full.py",
        repositoryRoot.absolutePath, layout.buildDirectory.dir("generated/original-video-content-full").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-video-content-full.py", "tools/sync-upstream.py", "tools/extract-appearance-platform.py", sourceManifest)
    inputs.files(sources.filter { "stable-video-content-full" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-video-content-full"))
}
val verifyOriginalVideoContentFull by tasks.registering(Exec::class) {
    dependsOn(extractOriginalVideoContentFull)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/verify-upstream-video-content-full.py",
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
    inputs.file("tools/v031_repost_coin.py")
    inputs.dir("upstream-slices/v031-repost-coin")
    dependsOn(prepareUpstreamSources, extractOriginalVideoDetailUnits, extractOriginalVideoContentFull)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-video-state-core.py",
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
// Production omits --standalone. DIRECT10 are copied solely by prepareUpstreamSources.
// Install child Section's canonical native facade first/in same compile: this package references it.

val extractOriginalVideoPlayerSectionFull by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractOriginalVideoStateCore, extractOriginalPlayerFullControls, extractOriginalVideoContentFull)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-video-player-section-full.py",
        repositoryRoot.absolutePath, layout.buildDirectory.dir("generated/original-video-player-section-full").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-video-player-section-full.py", "tools/extract-upstream-dynamic-reply-protocol.py",
        "tools/sync-upstream.py", "tools/extract-appearance-platform.py", sourceManifest)
    inputs.file("tools/v029_command_vote.py")
    inputs.dir("upstream-slices/v029-command-vote")
    inputs.files(sources.filter { "stable-video-player-section-full" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-video-player-section-full"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-video-player-section-full")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalVideoPlayerSectionFull) }


val prepareUpstreamVideoCommentUrl by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-video-comment-url.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/upstream-video-comment-url").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-video-comment-url.py",
        canonicalOriginalSource("app/src/main/java/com/android/purebilibili/feature/video/screen/VideoDetailSessionPolicy.kt"))
    outputs.dir(layout.buildDirectory.dir("generated/upstream-video-comment-url"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/upstream-video-comment-url/com")) }
tasks.named("compileKotlin") { dependsOn(prepareUpstreamVideoCommentUrl) }

val extractOriginalVideoTabletFull by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, prepareUpstreamVideoCommentUrl, extractOriginalVideoStateCore,
        extractOriginalVideoPlayerSectionFull, extractOriginalVideoContentFull)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-video-tablet-full.py",
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
    inputs.file("tools/v031_repost_coin.py")
    inputs.dir("upstream-slices/v031-repost-coin")
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-video-fullscreen-pager.py",
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
// Production omits --standalone. DIRECT15 must be copied once by registry Sync.


val extractOriginalMusicPlayerFull by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractOriginalVideoTabletFull)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-music-player-full.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-video-audio-full.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-media-byte-cache-policy.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-media-byte-cache-policy").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-media-byte-cache-policy.py", sourceManifest,
        canonicalOriginalSource("app/src/main/java/com/android/purebilibili/core/player/PlaybackMediaCache.kt"))
    outputs.dir(layout.buildDirectory.dir("generated/original-media-byte-cache-policy"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-media-byte-cache-policy")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalMediaByteCachePolicy) }



val extractOriginalVideoFullOwner by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractOriginalVideoStateCore, extractOriginalPlayerFullControls,
        extractOriginalVideoFullscreenPager)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-video-full-owner.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-video-full-owner").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-video-full-owner.py", sourceManifest)
    inputs.file("tools/v030_up_danmaku.py")
    inputs.file("tools/v029_failure_recovery.py")
    inputs.dir("upstream-slices/v029-playback-recovery")
    inputs.files(sources.filter { "stable-original-video-full-owner" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-video-full-owner"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-video-full-owner")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalVideoFullOwner) }
// DIRECT10 are copied once by prepareUpstreamSources; production omits --standalone.

val extractOriginalVideoDetailHolderFull by tasks.registering(Exec::class) {
    inputs.file("tools/v032_video_return_state.py")
    inputs.dir("upstream-slices/v032-video-return-state")
    inputs.file("tools/v031_repost_coin.py")
    inputs.dir("upstream-slices/v031-repost-coin")
    inputs.files("tools/v029_comment_composer.py", "tools/v029_comment_search.py")
    inputs.dir("upstream-slices/v029-comment-composer")
    inputs.files("tools/extract-upstream-video-comment-ui.py", "tools/extract-upstream-dynamic-reply-protocol.py")
    dependsOn(prepareUpstreamSources, extractOriginalVideoFullOwner, extractOriginalVideoFullscreenPager,
        extractOriginalVideoTabletFull)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-video-detail-holder.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-tablet-owner-space.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-video-playlist.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-video-owner-danmaku-send.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-video-root-story-feed.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-storage-settings.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-search-pages.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-search-pages").get().asFile.absolutePath)
    inputs.file("tools/extract-upstream-search-pages.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "desktop-full-original-search-root-parity" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    inputs.file("tools/v029_brand_callers.py")
    inputs.dir("upstream-slices/v029-brand-callers")
    outputs.dir(layout.buildDirectory.dir("generated/original-search-pages"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-search-pages")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalSearchPages) }

// Full original message seven pages. DIRECT4 are copied once by prepareUpstreamSources;
// production intentionally omits --standalone. Existing unread/share producers stay unique.
val extractOriginalMessagePages by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractUpstreamDynamicFullCard, extractOriginalHomeProtocols)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-message-pages.py",
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
    dependsOn(prepareUpstreamSources, extractUpstreamSpace, extractUpstreamSpaceOverview, extractUpstreamSpaceContributions,
        extractOriginalVideoTabletFull, extractOriginalFavorites)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-space-pages.py",
        "--repo", repositoryRoot.absolutePath,
        "--out", layout.buildDirectory.dir("generated/original-space-pages").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-space-pages.py", "tools/extract-upstream-tablet-owner-space.py", "tools/sync-upstream.py", sourceManifest)
    inputs.files(layout.buildDirectory.file("generated/space-overview/com/android/purebilibili/feature/space/DesktopOriginalSpaceOverview.kt"),
        layout.buildDirectory.file("generated/space-contributions/com/android/purebilibili/feature/space/DesktopUpstreamSpaceContributionDeclarations.kt"),
        layout.buildDirectory.file("generated/space/com/android/purebilibili/feature/space/DesktopUpstreamSpaceDeclarations.kt"),
        layout.buildDirectory.file("generated/original-favorites/com/android/purebilibili/feature/space/DesktopFavoriteArchiveMappings.kt"))
    inputs.files(sources.filter { "stable-original-space-pages-root-parity" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-space-pages"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-space-pages")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalSpacePages) }

val extractOriginalWindowLayout by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractUpstreamDynamicDetailContainer)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-window-layout.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-cdn-transfer.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-aicu.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-comment-fraud-history.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-donate-dialog.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-icon-settings.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-home-settings-full-page.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-system-about.py",
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
    "extractUpstreamSpaceImagePreviews",
    "extractUpstreamSpaceContributions",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-video-ambient-windows.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-whole-playback-settings.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-background-playback-policy.py",
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-brand-motion.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-brand-motion").get().asFile.absolutePath)
    inputs.file("tools/extract-upstream-brand-motion.py")
    inputs.file("tools/v029_brand_motion_ui.py")
    inputs.dir("upstream-slices/v029-brand-motion")
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/v032_catalog.py",
        repositoryRoot.absolutePath, layout.buildDirectory.dir("generated/v032-catalog").get().asFile.absolutePath)
    inputs.file("tools/v032_catalog.py")
    inputs.dir("upstream-slices/v032-catalog")
    outputs.dir(layout.buildDirectory.dir("generated/v032-catalog"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/v032-catalog")) }
tasks.named("compileKotlin") { dependsOn(extractV032CatalogSlice) }
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
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-listen-video-root.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-listen-video-pages").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-listen-video-root.py", "tools/v025_source_paths.py")
    inputs.files(sources.filter { "original-listen-video-pages" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-listen-video-pages"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-listen-video-pages")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalListenVideoPages) }

// AU song content shares the existing full Music UI/lyrics/native owners.
val extractOriginalAuMusicPage by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources, extractOriginalMusicPlayerFull)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-au-music-root.py",
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
    dependsOn(prepareUpstreamSources, extractOriginalMessagePages, extractUpstreamDynamicFullCard)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-message-notifications.py",
        "--repo", repositoryRoot.absolutePath,
        "--out", layout.buildDirectory.dir("generated/original-message-notifications").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-message-notifications.py", "tools/v025_source_paths.py",
        "tools/v025-canonical-sources.json", sourceManifest)
    inputs.files(sources.filter { "desktop-original-message-notification-root" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { canonicalOriginalSource(it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-message-notifications"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-message-notifications")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalMessageNotifications) }
