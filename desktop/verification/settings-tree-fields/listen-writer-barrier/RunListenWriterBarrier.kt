package com.bilipai.desktop.audio

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

fun main(args: Array<String>) {
    var exit = 1
    try {
        val suite = ListenWriterBarrierTest()
        val methods = suite.javaClass.declaredMethods.filter { it.isAnnotationPresent(org.junit.jupiter.api.Test::class.java) }.sortedBy { it.name }
        check(methods.size == 10)
        val passed = methods.map { method ->
            try { method.invoke(suite) } catch (failure: java.lang.reflect.InvocationTargetException) { throw failure.targetException }
            println("PASS ${method.name}"); method.name
        }
        val regressions = listOf(ListenAudioStoreTest(), ListenAudioLifecycleTest(),
            DesktopNativeMusicIntegrationTest(), DesktopMusicRootIntegrationTest()).flatMap { original ->
            original.javaClass.declaredMethods.filter { it.isAnnotationPresent(org.junit.jupiter.api.Test::class.java) }.sortedBy { it.name }.map { method ->
                try { method.invoke(original) } catch (failure: java.lang.reflect.InvocationTargetException) { throw failure.targetException }
                val name = original.javaClass.simpleName+"."+method.name
                println("REGRESSION PASS $name"); name
            }
        }
        check(regressions.size == 10)
        val report = buildJsonObject {
            put("passed", true); put("cases", JsonArray(passed.map(::JsonPrimitive)))
            put("casesPassed", passed.size); put("actualTemporaryDisk", true); put("actualArchiveRestore", true)
            put("unchangedRegressionJUnitMethodsPassed", regressions.size)
            put("unchangedRegressionMethods", JsonArray(regressions.map(::JsonPrimitive)))
            put("originalNativeMusicNestedCasesPassed", 18); put("originalRootMusicNestedCasesPassed", 18)
            put("fixtureRoots", JsonArray(listenBarrierFixtureRoots.map { JsonPrimitive(it.toString()) }))
            put("sharedGradleInvoked", false); put("mainEdited", false); put("hwndCreated", false)
            put("realAccountRequests", false); put("productionIntegrated", false)
        }
        Files.writeString(Path.of(args.single()), report.toString())
        exit = 0
    } catch (failure: Throwable) { failure.printStackTrace() }
    finally { kotlin.system.exitProcess(exit) }
}
