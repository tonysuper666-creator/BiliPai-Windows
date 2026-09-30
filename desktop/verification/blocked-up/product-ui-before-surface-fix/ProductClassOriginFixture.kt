package com.bilipai.desktop.ui

import kotlinx.serialization.json.*
import java.nio.file.*

fun main(args:Array<String>) {
    val expected=Path.of(args[0]).toAbsolutePath().normalize()
    val classes=listOf("com.bilipai.desktop.ui.DesktopDiscoveryStorageBoundaryKt","com.bilipai.desktop.ui.DesktopDiscoveryStorageGuard",
        "com.bilipai.desktop.settings.DesktopSettingsTreeKt","com.bilipai.desktop.ui.DesktopBlockedListScreenKt",
        "com.android.purebilibili.feature.settings.DesktopUpstreamBlockedListContentKt","com.bilipai.desktop.data.DesktopDiscoveryRepository",
        "com.bilipai.desktop.data.DesktopBlockedUpRepository","com.bilipai.desktop.data.DesktopBlockedUpStore","com.bilipai.desktop.plugins.DesktopPluginStore")
    val origins=classes.map {name->
        val type=Class.forName(name,false,Thread.currentThread().contextClassLoader)
        val actual=Path.of(type.protectionDomain.codeSource.location.toURI()).toAbsolutePath().normalize()
        check(actual==expected){"$name loaded from $actual rather than the immutable product"}
        buildJsonObject{put("className",name);put("actualJar",actual.toString());put("initialized",false)}
    }
    Files.writeString(Path.of(args[1]),buildJsonObject{put("passed",true);put("origins",JsonArray(origins));put("sourceOverrides",0)}.toString())
    println("PASS: nine actual production UI/Store/Repository classes loaded only from pinned current product Kotlin JAR")
}
