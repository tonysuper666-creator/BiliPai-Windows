package com.bilipai.desktop.diagnostics

import com.android.purebilibili.core.util.*
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.player.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import java.nio.file.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong

fun main(args:Array<String>):Unit = runBlocking {
    if(args[0]=="cold") {
        val store=DesktopPluginStore(Path.of(args[1]));val logs=DesktopDiagnostics(store,"fixture")
        try {
            check(logs.enhancedEnabled.value)
            logs.record("I","Fixture","fresh_jvm_detail");logs.flush()
            check(Files.readString(store.root.resolve("logs/runtime.log")).contains("fresh_jvm_detail"))
            println("Fresh process read same original enhanced settings key before logging.")
        }finally{logs.close()}
        return@runBlocking
    }
    val output=Path.of(args[0]);Files.createDirectories(output)
    val cases=mutableListOf<JsonObject>()
    fun pass(name:String) {cases+=buildJsonObject{put("name",name);put("passed",true)}}
    val root=Files.createTempDirectory("bilipai-diagnostic-fixture-")
    val store=DesktopPluginStore(root);val clock=AtomicLong(10_000)
    val executor=Executors.newSingleThreadExecutor()
    val logs=DesktopDiagnostics(store,"fixture",{clock.getAndAdd(500)},executor)
    try {
        check(!logs.enhancedEnabled.value);logs.record("D","Fixture","not_opted_in")
        logs.record("W","Fixture","basic_warning");logs.flush()
        check(!Files.exists(root.resolve("logs/runtime.log")))
        check(logs.entries().any{it.message.contains("basic_warning")})
        check(logs.entries().none{it.message.contains("not_opted_in")})
        check(Files.readString(root.resolve("logs/basic.log")).contains("stage=diagnostics_initialized"))
        pass("Original false default; W/E and fixed startup preserved; detailed logs absent")
        logs.setEnhancedEnabled(true)
        val disk=Json.parseToJsonElement(Files.readString(root.resolve("plugin-settings.json"))).jsonObject
        check(disk["settings"]!!.jsonObject["enhanced_diagnostic_logging_enabled"]!!.jsonPrimitive.boolean)
        check(logs.enhancedEnabled.value)
        pass("Actual settings namespace atomic disk write and published enabled state")
        val finiteScope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val playback=MutableStateFlow(PlayerState(ready=true,sourceTitle="fixture_title_secret",subtitleText="fixture_subtitle_secret",error="fixture_error_secret",videoCodec="https://fixture.invalid/private"))
        val observer=logs.observePlayback(playback,finiteScope)
        withTimeout(2000){while(logs.entries().none{it.tag=="PlaybackDiagnostics"})delay(5)}
        playback.value=playback.value.copy(hardwareDecodeEnabled=false,hardwareDecoder="no",audioCodec="aac",
            failure=PlayerFailure(PlayerFailureKind.AUDIO_OUTPUT,-14,"fixture_failure_secret",sourceVersion=8,attemptId=2))
        withTimeout(2000){while(logs.entries().none{it.message.contains("kind=AUDIO_OUTPUT")})delay(5)}
        observer.cancelAndJoin();finiteScope.cancel()
        val finiteEvidence=logs.entries().filter{it.tag=="PlaybackDiagnostics"}.joinToString("\n"){it.message}
        check(finiteEvidence.contains("hwIntent=false")&&finiteEvidence.contains("nativeCode=-14"))
        for(secret in listOf("fixture_title_secret","fixture_subtitle_secret","fixture_error_secret","fixture_failure_secret","fixture.invalid"))check(!finiteEvidence.contains(secret))
        pass("Actual PlayerState API observer with offline input: finite state only, no titles/subtitles/URLs/errors")
        DesktopDiagnosticsBridge.install(logs)
        android.util.Log.e("Fixture","SESSDATA=secret_cookie mid=123456 keyword=secret_search BV1AB411c7mD",IllegalStateException("access_token=secret_token"))
        com.android.purebilibili.core.util.Logger.d("Fixture","https://fixture.invalid/private?auth_key=secret_signed C:\\Users\\fixture-user\\private\\media")
        logs.flush()
        val evidence=logs.entries().joinToString("\n"){it.format()}+logs.viewLocal()
        for(secret in listOf("secret_cookie","123456","secret_search","BV1AB411c7mD","secret_token","secret_signed","fixture-user","fixture.invalid/private"))check(!evidence.contains(secret)) {"Sensitive fixture value survived bridge redaction"}
        check(evidence.contains("SESSDATA=***")&&evidence.contains("[network-address]")&&evidence.contains("[user-home]"))
        pass("Both actual bridge shapes sanitize before collector, disk, and local view")
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        executor.execute{entered.countDown();check(release.await(3,TimeUnit.SECONDS))};check(entered.await(1,TimeUnit.SECONDS))
        logs.record("D","Fixture","queued_old_detail")
        val off=async(start=CoroutineStart.UNDISPATCHED){logs.setEnhancedEnabled(false)}
        logs.record("D","Fixture","queued_after_off_detail")
        logs.record("E","Fixture","basic_after_off")
        release.countDown();off.await();logs.flush()
        check(!logs.enhancedEnabled.value&&!Files.exists(root.resolve("logs/runtime.log")))
        check(logs.entries().none{it.message.contains("queued_")||it.level=="D"})
        check(logs.entries().any{it.message.contains("basic_after_off")})
        pass("Task-owned gated disk writer: off clears detail and queued old/new detail cannot resurrect")
        repeat(1500){logs.record("W","Bounded","故障线索-$it "+"汉".repeat(180))}
        logs.record("W","Bounded","异常".repeat(20_000))
        logs.flush();val bounded=Files.readString(root.resolve("logs/basic.log"))
        check(Files.size(root.resolve("logs/basic.log"))<=64*1024&&bounded.contains("[truncated]")&&!bounded.contains('\uFFFD'))
        check(logs.entries().size==1000&&logs.entries().first().message.contains("故障线索-501"))
        pass("Original ring eviction, 16Ki character truncation and UTF-8 64KiB rolling cap")
        val isolated=DesktopDiagnosticCollector({500L}){_,_->}
        repeat(20){isolated.add("W","Duplicate","same")};check(isolated.getCount()==1)
        pass("Original actual duplicate suppression within 250ms")
        logs.setEnhancedEnabled(true)
        logs.record("I","Enabled","detail_survives_until_clear")
        logs.flush();check(Files.size(root.resolve("logs/runtime.log"))<=256*1024)
        val chosen=output.resolve("explicit-local-export-${java.util.UUID.randomUUID()}.txt");logs.exportTo(chosen)
        check(Files.size(chosen)<=512*1024&&!Files.readString(chosen).contains('\uFFFD'))
        check(runCatching{logs.exportTo(chosen)}.isFailure)
        check(runCatching{logs.exportTo(root.resolve("logs/basic.log"))}.isFailure)
        pass("Explicit local export bounded to actual 512KiB; cannot overwrite or target active logs")
        // Existing persisted logs are re-sanitized and bounded on read, never blindly exported.
        Files.writeString(root.resolve("logs/runtime.log"),"旧".repeat(200_000)+"\naccess_token=fixture_export_secret\n")
        val second=logs.viewLocal()
        check(second.toByteArray().size<=512*1024&&!second.contains("fixture_export_secret")&&!second.contains('\uFFFD'))
        pass("Corrupted oversized historical file read bounded and export re-sanitized")
        val handlerCalls=java.util.concurrent.atomic.AtomicInteger()
        val ownedThread=Thread({throw IllegalArgumentException("Authorization: Bearer fixture_crash_secret")},"diagnostic-task-owned-fatal")
        ownedThread.uncaughtExceptionHandler=DesktopDiagnosticUncaughtHandler(logs){_,_->handlerCalls.incrementAndGet()}
        ownedThread.start();ownedThread.join(2500);check(!ownedThread.isAlive&&handlerCalls.get()==1)
        check(logs.hasPendingCrash())
        check(!Files.readString(root.resolve("logs/last_crash_log.txt")).contains("fixture_crash_secret"))
        logs.clearCrashPrompt();check(!logs.hasPendingCrash()&&Files.exists(root.resolve("logs/last_crash_log.txt")))
        pass("Original always-local crash snapshot; dismiss removes only pending marker, keeps evidence")
        val unrelated=root.resolve("keep-user-file.txt");Files.writeString(unrelated,"untouched")
        logs.clearAll();check(logs.entries().isEmpty()&&logs.artifactSize()==0L&&Files.readString(unrelated)=="untouched")
        pass("Serialized clear deletes only application-owned known files and in-memory ring")
        logs.record("W","Shutdown","accepted_before_shutdown");logs.setEnhancedEnabled(true)
        logs.shutdownForRestore();check(!logs.record("E","Shutdown","rejected_after_shutdown"))
        check(Files.readString(root.resolve("logs/basic.log")).contains("accepted_before_shutdown"))
        check(runCatching{logs.clearAll()}.isFailure)
        store.freezeWrites()
        pass("Restore shutdown drains accepted real file writes, retires bridge, rejects old facade")
    }finally{logs.close()}
    val failingRoot=Files.createTempDirectory("bilipai-diagnostic-failure-")
    val failing=DesktopDiagnostics(DesktopPluginStore(failingRoot),"fixture")
    try {
        failing.flush();Files.createDirectory(failingRoot.resolve("plugin-settings.json"))
        check(runCatching{failing.setEnhancedEnabled(true)}.isFailure)
        check(!failing.enhancedEnabled.value&&!Files.exists(failingRoot.resolve("logs/runtime.log")))
        check(failing.error.value?.contains("bilipai-diagnostic-failure-")==false)
        Files.delete(failingRoot.resolve("plugin-settings.json"))
        failing.setEnhancedEnabled(true);failing.record("I","Retry","write_after_failure");failing.flush()
        check(Files.readString(failingRoot.resolve("logs/runtime.log")).contains("write_after_failure"))
        pass("Actual atomic disk failure keeps old consent; error is safe and later write succeeds")
    }finally{failing.close()}
    val pendingRoot=Files.createTempDirectory("bilipai-diagnostic-pending-close-")
    val pendingExecutor=Executors.newSingleThreadExecutor()
    val pending=DesktopDiagnostics(DesktopPluginStore(pendingRoot),"fixture",writer=pendingExecutor)
    pending.flush()
    val closeEntered=CountDownLatch(1);val closeRelease=CountDownLatch(1)
    pendingExecutor.execute {closeEntered.countDown();check(closeRelease.await(3,TimeUnit.SECONDS))}
    check(closeEntered.await(1,TimeUnit.SECONDS))
    pending.record("W","Shutdown","real_pending_write")
    val closing=async(Dispatchers.IO){pending.shutdownForRestore()}
    withTimeout(2000){while(!pendingExecutor.isShutdown)delay(1)}
    check(!pending.record("W","Shutdown","late_after_close"))
    closeRelease.countDown();closing.await()
    val drained=Files.readString(pendingRoot.resolve("logs/basic.log"))
    check(drained.contains("real_pending_write")&&!drained.contains("late_after_close"))
    pass("Blocked task-owned writer: real shutdown waits pending disk job and rejects concurrent late record")
    val coldRoot=Files.createTempDirectory("bilipai-diagnostic-cold-")
    val cold=DesktopDiagnostics(DesktopPluginStore(coldRoot),"fixture");cold.setEnhancedEnabled(true);cold.close()
    Files.writeString(output.resolve("cold-root.txt"),coldRoot.toString())
    Files.writeString(output.resolve("result.json"),buildJsonObject {
        put("passed",true);put("cases",JsonArray(cases));put("sharedGradleInvoked",false);put("networkUsed",false)
        put("userDataRead",false);put("nativeWindowCreated",false);put("firebaseImplemented",false)
    }.toString())
    println("Actual original policies/collector + local disk/bridge/ordering: ${cases.size} passed, cold-start child pending.")
    Unit
}
