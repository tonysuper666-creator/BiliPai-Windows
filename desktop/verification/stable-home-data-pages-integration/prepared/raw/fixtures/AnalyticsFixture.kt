package com.bilipai.desktop.ui

import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.diagnostics.DesktopDiagnostics
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.*
import java.util.concurrent.atomic.AtomicBoolean

fun main(args:Array<String>)=runBlocking {
    val dir=Path.of(args[0]);Files.createDirectories(dir)
    val store=DesktopPluginStore(dir)
    val diagnostics=DesktopDiagnostics(store,"fixture")
    val alive=AtomicBoolean(true)
    val adapter=DesktopHomeLocalIdentityAnalytics(store,diagnostics,alive::get)
    check(!adapter.firebaseTransportAvailable)
    store.update("settings",mapOf("analytics_enabled" to JsonPrimitive(false)))
    adapter.syncUserContext(1234567890,true,false);diagnostics.flush()
    check(diagnostics.entries().none{it.tag=="HomeUserContext"})
    store.update("settings",mapOf("analytics_enabled" to JsonPrimitive(true)))
    adapter.syncUserContext(1234567890,true,false);diagnostics.flush()
    check(diagnostics.entries().none{it.tag=="HomeUserContext"}) // existing enhanced runtime consent still off
    diagnostics.setEnhancedEnabled(true)
    adapter.syncUserContext(1234567890,true,false);diagnostics.flush()
    val records=diagnostics.entries().filter{it.tag=="HomeUserContext"}
    check(records.single().message=="loggedIn=true, vip=true, privacy=false")
    alive.set(false);adapter.syncUserContext(null,false,true);diagnostics.flush()
    check(diagnostics.entries().count{it.tag=="HomeUserContext"}==1)
    diagnostics.close();alive.set(true)
    adapter.syncUserContext(1234567890,true,false) // platform failure retains original swallowed-failure behavior
    Files.writeString(dir.resolve("analytics-result.json"),buildJsonObject {
        put("actualMainAcceptance",false);put("preparedOverride",true);put("actualExistingDiagnostics",true)
        put("firebaseOrUpload",false);put("gates",4)
        put("checks","same global analytics key; enhanced consent; finite booleans/retired owner; closed platform failure swallowed")
    }.toString())
    println("PASS 4 local anonymous analytics gates")
}
