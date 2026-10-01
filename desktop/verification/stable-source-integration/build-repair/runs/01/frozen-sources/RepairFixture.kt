package com.bilipai.desktop.stableBuildRepairProof

import com.android.purebilibili.core.performance.*
import com.android.purebilibili.core.util.*
import com.bilipai.desktop.appearance.DesktopMonotonicClock
import java.io.ByteArrayInputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path

fun main(args: Array<String>) {
    val root=Path.of(args[0]).toRealPath();val candidate=Path.of(args[1]).toRealPath();val main=Path.of(args[2]).toRealPath()
    var assertions=0
    fun prove(condition:Boolean,label:String) { check(condition){label};assertions++ }
    prove(Path.of(AbnormalProcessExitException::class.java.protectionDomain.codeSource.location.toURI()).toRealPath()==candidate,"Original exception class comes from declared candidate")
    prove(Path.of(DesktopMonotonicClock::class.java.protectionDomain.codeSource.location.toURI()).toRealPath()==main,"Clock is actual existing Main04 Windows bridge")
    val bytes=byteArrayOf(0,1,2,3,4,5,6,7,8,9)
    val trace=checkNotNull(encodeNativeExitTrace(ByteArrayInputStream(bytes)))
    prove(decodeNativeExitTrace(trace)?.contentEquals(bytes)==true,"Original binary trace round trip remains exact")
    prove(!nativeExitTraceSummary(trace).contains("BEGIN TOMBSTONE") && !nativeExitTraceSummary(trace).contains("AAECAwQFBgcICQ=="),"Original summary excludes the encoded protobuf")
    val failure=AbnormalProcessExitException("declared native exit",trace)
    prove(failure.stackTrace.isEmpty(),"Original exception suppresses synthetic startup Java stack")
    val printed=StringWriter();failure.printStackTrace(PrintWriter(printed))
    prove(printed.toString().contains(trace),"Original exception explicit stack writer retains exact attached trace")
    val snapshot=buildCrashSnapshotContent(failure,emptyList(),0L,"declared version",0,"Windows","JVM","declared OS",21)
    prove(snapshot.contains("系统异常回溯摘要") && snapshot.contains("原始回溯: 不在本文本内"),"Actual generated Logger preserves new native-summary crash branch")
    prove(!snapshot.contains("BEGIN TOMBSTONE") && !snapshot.contains("AAECAwQFBgcICQ=="),"Crash text never embeds raw protobuf payload")
    val limited=checkNotNull(encodeNativeExitTrace(ByteArrayInputStream(bytes),4))
    prove(decodeNativeExitTrace(limited)?.contentEquals(bytes.take(4).toByteArray())==true && limited.contains("truncated=true"),"Original byte cap preserves exact prefix and truncated status")
    prove(encodeNativeExitTrace(ByteArrayInputStream(byteArrayOf()))==null,"Original empty trace remains absent")
    val normal=buildCrashSnapshotContent(IllegalStateException("declared JVM failure"),emptyList(),0L,"declared version",0,"Windows","JVM","declared OS",21)
    prove(normal.contains("IllegalStateException: declared JVM failure") && !normal.contains("系统异常回溯摘要"),"Ordinary JVM throwable keeps original stack path")
    val before=DesktopMonotonicClock.elapsedRealtime();val after=DesktopMonotonicClock.elapsedRealtime()
    prove(after>=before,"Existing actual monotonic Windows bridge supplies nondecreasing milliseconds")
    Files.writeString(root.resolve("result.json"),"""{"passed":true,"cases":3,"assertions":$assertions,"originalNativeTraceSemantics":true,"actualMain04Clock":true,"networkCalls":0,"systemExitCaptureAdded":false,"credentialsSerialized":false,"HWND":false}""")
    println("PASS scoped stable build repair runtime: $assertions assertions, no transport or GUI")
}
