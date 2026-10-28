// Upstream shared modules compiled straight from the repository root (the merged upstream tree).
// No copies and no per-file pins: the merged upstream commit is the provenance.
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}
kotlin { jvmToolchain(21) }
val upstreamRoot = rootDir.parentFile
val upstreamModules = listOf("core-data", "network-core")
sourceSets {
    main {
        kotlin.srcDir("compat")
        upstreamModules.forEach { kotlin.srcDir(upstreamRoot.resolve("$it/src/main/java")) }
    }
    test { upstreamModules.forEach { kotlin.srcDir(upstreamRoot.resolve("$it/src/test/java")) } }
}
dependencies {
    // Same versions as the desktop runtime graph.
    api("androidx.datastore:datastore-preferences-core:1.2.1")
    api("androidx.collection:collection:1.5.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    api("com.squareup.okhttp3:okhttp:5.3.2")
    api("com.squareup.retrofit2:retrofit:3.0.0")
    api("com.squareup.retrofit2:converter-kotlinx-serialization:3.0.0")
    api("com.google.protobuf:protobuf-javalite:4.33.2")
    api("org.json:json:20240303")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.4.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}
// Upstream structure tests read sources relative to their module root.
tasks.test { useJUnitPlatform(); workingDir = upstreamRoot.resolve("core-data") }
