pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral(); google() }
}
dependencyResolutionManagement {
    repositories { mavenCentral(); google() }
}
rootProject.name = "bilipai-windows"
include(":miuix5157")
project(":miuix5157").projectDir = file("third-party/miuix5157")
