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
    doFirst {
        require(sources.isNotEmpty()) { "The upstream source manifest is missing or empty." }
        require(manifest["hashNormalization"] == "lf") { "The upstream source inventory must use LF-normalized hashes." }
        sources.forEach { entry ->
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
    outputs.dir(layout.buildDirectory.dir("generated/api"))
}

kotlin.sourceSets.named("main") {
    kotlin.srcDir(generatedUpstream)
    kotlin.srcDir(layout.buildDirectory.dir("generated/api"))
}
tasks.named("compileKotlin") { dependsOn(extractUpstreamApi) }

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:3.0.0")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("io.coil-kt.coil3:coil-compose:3.5.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.5.0")
    implementation("com.google.zxing:core:3.5.4")
    implementation("net.java.dev.jna:jna:5.17.0")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform { excludeTags("packaged-updater") } }
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
            modules("java.net.http", "java.desktop", "java.logging", "java.sql", "jdk.unsupported")
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
