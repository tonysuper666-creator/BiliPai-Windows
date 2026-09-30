package com.bilipai.desktop.diagnostics

import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.*
import java.security.MessageDigest

/** Real task-owned Windows junctions; uses the actual retained consumer and product path guard. */
fun main(args:Array<String>):Unit = runBlocking {
    val output=Path.of(args[0]).toAbsolutePath().normalize();Files.createDirectories(output)
    val reviewed=args[1]=="reviewed"
    val cases=mutableListOf<JsonObject>()
    fun junction(link:Path,target:Path) {
        check(link.startsWith(output)&&target.startsWith(output));Files.createDirectories(link.parent)
        val process=ProcessBuilder("cmd.exe","/d","/c","mklink","/J",link.toString(),target.toString()).redirectErrorStream(true).start()
        val report=process.inputStream.bufferedReader().readText()
        check(process.waitFor()==0){"task junction fixture creation failed"}
        Files.writeString(output.resolve("${link.fileName}-junction-command.txt"),report)
        val attributes=Files.readAttributes(link,java.nio.file.attribute.BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
        check(attributes.isOther&&!attributes.isSymbolicLink){"fixture must be a real Windows junction, not a symbolic link"}
    }
    fun hash(path:Path)=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString(""){"%02x".format(it)}
    for(ancestor in listOf(false,true)) {
        val name=if(ancestor)"ancestor-junction" else "logs-junction"
        val fixture=output.resolve(name);Files.createDirectories(fixture)
        val victim=fixture.resolve("owned-victim");Files.createDirectories(victim)
        val root:Path
        val logs:Path
        if(ancestor) {
            val redirected=fixture.resolve("redirected-parent")
            junction(redirected,victim)
            root=redirected.resolve("store");logs=victim.resolve("store/logs")
            Files.createDirectories(logs)
        } else {
            root=fixture.resolve("store");Files.createDirectories(root);logs=victim
            junction(root.resolve("logs"),victim)
        }
        val target=logs.resolve("basic.log");Files.writeString(target,"task_owned_victim_marker\n")
        val originalBytes=Files.readAllBytes(target)
        val diagnostics=DesktopDiagnostics(DesktopPluginStore(root),"offline-boundary-fixture")
        try {
            diagnostics.flush()
            val destination=fixture.resolve("explicit-export.txt")
            val read=runCatching{diagnostics.viewLocal()}
            val export=runCatching{diagnostics.exportTo(destination)}
            val clear=runCatching{diagnostics.clearAll()}
            if(reviewed) {
                check(read.isFailure&&export.isFailure&&clear.isFailure)
                check(!Files.exists(destination)&&Files.readAllBytes(target).contentEquals(originalBytes))
                check(diagnostics.error.value?.contains("诊断文件操作失败")==true)
                check(diagnostics.error.value?.contains(fixture.toString())==false)
            } else {
                check(read.getOrThrow().contains("task_owned_victim_marker"))
                check(export.isSuccess&&Files.readString(destination).contains("task_owned_victim_marker"))
                check(clear.isSuccess&&!Files.exists(target))
            }
            cases+=buildJsonObject {
                put("case",name);put("actualWindowsJunction",true);put("symbolicLink",false)
                put("readRejected",read.isFailure);put("exportRejected",export.isFailure);put("clearRejected",clear.isFailure)
                put("taskVictimUnchanged",Files.exists(target)&&Files.readAllBytes(target).contentEquals(originalBytes))
                put("privateFileErrorSafe",diagnostics.error.value?.contains(fixture.toString())!=true)
                put("passed",true)
            }
        }finally{diagnostics.close()}
    }
    val normal=output.resolve("normal-store");val sameStore=DesktopPluginStore(normal)
    val diagnostics=DesktopDiagnostics(sameStore,"offline-normal-fixture")
    try {
        diagnostics.setEnhancedEnabled(true)
        check(sameStore.preferences("settings")["enhanced_diagnostic_logging_enabled"]?.jsonPrimitive?.booleanOrNull==true)
        diagnostics.record("I","Boundary","actual_normal_private_log cookie=private_value",IllegalStateException("https://example.invalid/private?access_token=secret"))
        diagnostics.flush()
        val view=diagnostics.viewLocal();check(view.contains("actual_normal_private_log")&&!view.contains("private_value")&&!view.contains("example.invalid")&&!view.contains("access_token=secret"))
        val destination=output.resolve("normal-export.txt");diagnostics.exportTo(destination)
        check(Files.readString(destination)==view)
        diagnostics.clearAll();check(diagnostics.artifactSize()==0L&&diagnostics.entries().isEmpty())
        cases+=buildJsonObject{put("case","normal-path-real-file-consent-and-throwable-redaction");put("sameAuthoritativeStoreKey",true);put("readExportClearPassed",true);put("passed",true)}
    }finally{diagnostics.close()}
    val result=buildJsonObject {
        put("passed",true);put("mode",args[1]);put("cases",JsonArray(cases));put("actualJunctionCases",2)
        put("nativeWindowCreated",false);put("accountOrHttpUsed",false);put("temporaryVictimsOwnedByTask",true)
    }
    Files.writeString(output.resolve("result.json"),result.toString());println(result)
    Unit
}
