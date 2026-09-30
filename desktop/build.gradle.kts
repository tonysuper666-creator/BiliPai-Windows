import groovy.json.JsonSlurper
import java.security.MessageDigest
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.4.0"
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
val upstreamBuildFile = File(repositoryRoot, "app/build.gradle.kts")
val upstreamBuild = upstreamBuildFile.readText()
val upstreamVersionCode = Regex("versionCode\\s*=\\s*(\\d+)")
    .find(upstreamBuild)?.groupValues?.get(1) ?: "406"
val windowsRevision = manifest["windowsRevision"]?.toString() ?: "1"
val windowsVersion = "0.2.$upstreamVersionCode.$windowsRevision"
val generatedUpstream = layout.buildDirectory.dir("generated/upstream")

val prepareUpstreamSources by tasks.registering(Sync::class) {
    from(repositoryRoot) {
        include(sources.filter { (it["mode"] ?: "direct") == "direct" }.map { it["path"].toString() })
    }
    into(generatedUpstream)
    inputs.file(sourceManifest)
    inputs.files(sources.map { File(repositoryRoot, it["path"].toString()) })
    inputs.files(originalResources.map { File(repositoryRoot, it["path"].toString()) })
    doFirst {
        require(sources.isNotEmpty()) { "The upstream source manifest is missing or empty." }
        require(manifest["hashNormalization"] == "lf") { "The upstream source inventory must use LF-normalized hashes." }
        (sources + originalResources).forEach { entry ->
            val source = File(repositoryRoot, entry["path"].toString())
            require(source.isFile) { "Required upstream source is missing: ${entry["path"]}" }
            val normalized = source.readText(Charsets.UTF_8).replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
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
    inputs.file(File(repositoryRoot, "app/src/main/java/com/android/purebilibili/core/network/ApiClient.kt"))
    inputs.file("tools/extract-upstream-api.py")
    inputs.file("tools/sync-upstream.py")
    inputs.files(sources.filter { it["mode"] == "policy-extract" }.map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/api"))
}
val extractUpstreamDanmaku by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-danmaku.py",
        "--repo", repositoryRoot.absolutePath, "--output", layout.buildDirectory.dir("generated/danmaku").get().asFile.absolutePath)
    inputs.file("tools/extract-upstream-danmaku.py")
    inputs.files(sources.filter { it["mode"] == "extracted" }.map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/danmaku"))
}
val extractUpstreamMedia by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-media.py",
        "--repo", repositoryRoot.absolutePath, "--output", layout.buildDirectory.dir("generated/media").get().asFile.absolutePath)
    inputs.file("tools/extract-upstream-media.py")
    inputs.file("tools/sync-upstream.py")
    inputs.files(sources.filter { "media" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/media"))
}
val extractUpstreamAudio by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-audio.py",
        "--repo", repositoryRoot.absolutePath, "--output", layout.buildDirectory.dir("generated/audio").get().asFile.absolutePath)
    inputs.file("tools/extract-upstream-audio.py")
    inputs.file("tools/sync-upstream.py")
    inputs.files(sources.filter { "listen-video" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
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
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/login"))
}
val extractUpstreamPlugins by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-plugins.py",
        "--repo", repositoryRoot.absolutePath, "--output", layout.buildDirectory.dir("generated/plugins").get().asFile.absolutePath)
    inputs.file("tools/extract-upstream-plugins.py")
    inputs.file("tools/extract-upstream-media.py")
    inputs.file("tools/sync-upstream.py")
    inputs.files(sources.filter { "plugins" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    inputs.files(originalResources.map { File(repositoryRoot, it["path"].toString()) })
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
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/discovery"))
}
val extractUpstreamSettings by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-settings.py",
        "--repo", repositoryRoot.absolutePath, "--output", layout.buildDirectory.dir("generated/settings").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-settings.py", "tools/extract-upstream-media.py", "tools/sync-upstream.py")
    inputs.files(sources.filter { "backup" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/settings"))
}
val extractUpstreamPlayback by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-playback-platform.py",
        "--repo", repositoryRoot.absolutePath, "--output", layout.buildDirectory.dir("generated/playback").get().asFile.absolutePath)
    inputs.files("tools/extract-playback-platform.py", "third-party/media3-error-codes.json")
    inputs.files(sources.filter { "recovery" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/playback"))
}
val extractUpstreamSearch by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-search-platform.py",
        "--repo", repositoryRoot.absolutePath, "--output", layout.buildDirectory.dir("generated/search").get().asFile.absolutePath)
    inputs.files("tools/extract-search-platform.py", "tools/extract-upstream-media.py", "tools/sync-upstream.py")
    inputs.files(sources.filter { "search-native" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/search"))
}
val extractUpstreamCast by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-cast-platform.py",
        "--repo", repositoryRoot.absolutePath, "--output", layout.buildDirectory.dir("generated/cast").get().asFile.absolutePath)
    inputs.files("tools/extract-cast-platform.py", "tools/extract-upstream-media.py", "tools/sync-upstream.py")
    inputs.files(sources.filter { "dlna-cast" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/cast"))
}
val extractUpstreamPackages by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-packages.py",
        "--repo", repositoryRoot.absolutePath, "--output", layout.buildDirectory.dir("generated/packages").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-packages.py", "tools/extract-upstream-plugins.py", "tools/extract-upstream-media.py", "tools/extract-upstream-api.py")
    inputs.files(sources.filter { "packages" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    inputs.files(originalResources.filter { "packages" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/packages"))
}
val extractPlaybackWatchdogs by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-playback-watchdogs.py",
        "--repo", repositoryRoot.absolutePath, "--output", layout.buildDirectory.dir("generated/watchdogs").get().asFile.absolutePath)
    inputs.files("tools/extract-playback-watchdogs.py", "third-party/media3-player-state-codes.json")
    inputs.files(sources.filter { "watchdogs" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
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
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/google-cast"))
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
}
tasks.named("compileKotlin") { dependsOn(extractUpstreamApi, extractUpstreamDanmaku, extractUpstreamMedia, extractUpstreamAudio, extractUpstreamLogin, extractUpstreamPlugins, extractUpstreamDiscovery, extractUpstreamSettings, extractUpstreamPlayback, extractUpstreamSearch, extractUpstreamCast, extractUpstreamPackages, extractPlaybackWatchdogs, extractGoogleCastPlatform) }

val prepareOriginalPluginResources by tasks.registering(Sync::class) {
    dependsOn(prepareUpstreamSources)
    from(File(repositoryRoot, "app/src/main/assets/anime4k")) { into("anime4k"); include(originalResources.filter {
        it["path"].toString().startsWith("app/src/main/assets/anime4k/") }.map { File(it["path"].toString()).name }) }
    from(File(repositoryRoot, "app/src/main/res/raw/cdn_region_catalog.json")) { into("plugin") }
    from(File(repositoryRoot, "app/src/main/assets/rovniced-skin-catalog.json"))
    into(layout.buildDirectory.dir("generated/plugin-resources"))
    inputs.file(sourceManifest)
    inputs.files(originalResources.map { File(repositoryRoot, it["path"].toString()) })
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

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
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
    implementation("io.coil-kt.coil3:coil-compose:3.5.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.5.0")
    implementation("com.google.zxing:core:3.5.4")
    implementation("net.java.dev.jna:jna:5.17.0")
    implementation("org.json:json:20240303")
    implementation("org.brotli:dec:0.1.2")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform { excludeTags("packaged-updater", "native-mux") } }
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
