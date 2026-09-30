package com.bilipai.desktop.diagnostics.hwndownerprobe

import androidx.compose.ui.awt.ComposeWindow
import com.sun.jna.*
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import kotlinx.serialization.json.*
import java.nio.file.*
import java.security.MessageDigest
import java.security.Permission
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.SwingUtilities

private interface ProbeKernel32:StdCallLibrary {fun GetCurrentThreadId():Int;fun GetCurrentProcessId():Int}
private interface ProbeUser32:StdCallLibrary {fun GetWindowThreadProcessId(hwnd:Pointer,pid:IntByReference):Int;fun IsWindow(hwnd:Pointer):Boolean;fun IsWindowVisible(hwnd:Pointer):Boolean}
@Suppress("DEPRECATION") private class ProbeFence:SecurityManager() {
    val denied=CopyOnWriteArrayList<String>()
    override fun checkPermission(permission:Permission) {}
    override fun checkConnect(host:String,port:Int){denied.add("connect:$host:$port");throw SecurityException("No external sockets")}
    override fun checkListen(port:Int){denied.add("listen:$port");throw SecurityException("No listener")}
    override fun checkAccept(host:String,port:Int){denied.add("accept:$host:$port");throw SecurityException("No accept")}
    override fun checkExec(command:String){denied.add("exec:$command");throw SecurityException("No child process")}
}
fun main(args:Array<String>) {
    println("stage:main-entry");System.out.flush()
    val output=Path.of(args[0]);Files.createDirectories(output)
    val pins=Json.parseToJsonElement(Files.readString(Path.of(args[1]))).jsonArray
    for(row in pins){val p=row.jsonObject;val name=p["class"]!!.jsonPrimitive.content;val type=Class.forName(name,false,Thread.currentThread().contextClassLoader)
        check(Path.of(type.protectionDomain.codeSource.location.toURI()).toRealPath()==Path.of(p["codeSource"]!!.jsonPrimitive.content).toRealPath())
        val bytes=type.getResourceAsStream("/"+name.replace('.','/')+".class")!!.use{it.readAllBytes()}
        val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        check(hash==p["sha256Bytes"]!!.jsonPrimitive.content)
    }
    Files.writeString(output.resolve("actual-loaded-class-identities.json"),pins.toString())
    println("stage:class-pins-verified");System.out.flush()
    val fence=ProbeFence()
    @Suppress("DEPRECATION") System.setSecurityManager(fence)
    println("stage:fence-installed");System.out.flush()
    val kernel=Native.load("kernel32",ProbeKernel32::class.java);val user=Native.load("user32",ProbeUser32::class.java)
    println("stage:kernel-user-libraries-loaded");System.out.flush()
    var window:ComposeWindow?=null;var pointer:Pointer?=null;var failure:Throwable?=null
    var edtThreadId=0;var ownerThreadId=0;var processId=0;var windowProcessId=0;var threadName=""
    var createdInvisible=false;var displayable=false;var nativeVisible=false;var disposed=false;var nativeDestroyed=false
    try {
        SwingUtilities.invokeAndWait {
            println("stage:EDT-entered");System.out.flush()
            check(SwingUtilities.isEventDispatchThread())
            threadName=Thread.currentThread().name;edtThreadId=kernel.GetCurrentThreadId();processId=kernel.GetCurrentProcessId()
            println("stage:before-ComposeWindow-constructor");System.out.flush()
            val owned=ComposeWindow();window=owned;check(!owned.isVisible)
            println("stage:before-addNotify");System.out.flush();owned.addNotify()
            println("stage:after-addNotify");System.out.flush()
            createdInvisible=!owned.isVisible;displayable=owned.isDisplayable;check(displayable&&createdInvisible)
            println("stage:before-native-HWND-read");System.out.flush()
            val hwnd=Native.getWindowPointer(owned);pointer=hwnd;check(Pointer.nativeValue(hwnd)!=0L&&user.IsWindow(hwnd))
            println("stage:after-native-HWND-read");System.out.flush()
            val pid=IntByReference();ownerThreadId=user.GetWindowThreadProcessId(hwnd,pid);windowProcessId=pid.value
            nativeVisible=user.IsWindowVisible(hwnd);check(!nativeVisible)
            check(processId==windowProcessId&&processId.toLong()==ProcessHandle.current().pid())
            println("Own invisible ComposeWindow: Swing EDT=$edtThreadId ($threadName), HWND owner=$ownerThreadId, same process=$processId, visible=$nativeVisible")
        }
    }catch(t:Throwable){failure=t;Files.writeString(output.resolve("FAILED.txt"),t.stackTraceToString())}
    finally {
        println("stage:before-dispose");System.out.flush()
        SwingUtilities.invokeAndWait {window?.let{it.dispose();disposed=!it.isDisplayable};pointer?.let{nativeDestroyed=!user.IsWindow(it)}}
        SwingUtilities.invokeAndWait {} // Drain the task's final EDT disposal work.
        val report=buildJsonObject {
            put("passed",failure==null&&createdInvisible&&displayable&&disposed&&nativeDestroyed&&fence.denied.isEmpty())
            put("actualComposeWindow",true);put("oneTaskOwnedWindow",true);put("addNotifyOnSwingEDT",true)
            put("javaEdtThreadName",threadName);put("nativeEdtThreadId",edtThreadId.toUInt().toLong());put("nativeHwndOwnerThreadId",ownerThreadId.toUInt().toLong())
            put("sameThread",edtThreadId==ownerThreadId);put("currentNativeCppThreadGuardWouldReject",edtThreadId!=ownerThreadId)
            put("currentProcessId",processId.toUInt().toLong());put("windowProcessId",windowProcessId.toUInt().toLong());put("sameProcess",processId==windowProcessId)
            put("windowInitiallyInvisible",createdInvisible);put("windowWasDisplayable",displayable);put("nativeWindowWasVisible",nativeVisible)
            put("windowDisposedOnEDT",disposed);put("nativeHwndDestroyed",nativeDestroyed);put("EDTDrained",true)
            put("windowPointer",pointer?.let{Pointer.nativeValue(it).toULong().toString(16)}?:"")
            put("productionOverrides",0);put("MainOrShellExecuted",false);put("accountOrDiagnosticActorConstructed",false)
            put("SharePrepare",false);put("ShareShow",false);put("ShareUI",false);put("receiver",false);put("packageExecuted",false)
            put("socketAndChildProcessFence",true);put("deniedConnectionsOrProcesses",JsonArray(fence.denied.map(::JsonPrimitive)))
            if(failure!=null)put("failure",failure!!.stackTraceToString())
        }
        Files.writeString(output.resolve("result.json"),report.toString());println(report)
    }
    failure?.let{throw it};check(disposed&&nativeDestroyed&&fence.denied.isEmpty())
}
