package com.bilipai.desktop.diagnostics.ownerbindproof

import androidx.compose.ui.awt.ComposeWindow
import com.sun.jna.*
import com.sun.jna.ptr.*
import com.sun.jna.win32.StdCallLibrary
import kotlinx.serialization.json.*
import java.nio.file.*
import java.security.MessageDigest
import java.security.Permission
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

private interface Bridge:StdCallLibrary {
    fun BilipaiShareProbe():Int
    fun BilipaiSharePrepare(path:WString,result:LongByReference):Int
    fun BilipaiShareBind(token:Long,hwnd:Pointer?):Int
    fun BilipaiShareState(token:Long,state:IntByReference):Int
    fun BilipaiShareClose(token:Long):Int
    fun BilipaiShareRetire(token:Long,state:IntByReference,supplied:IntByReference):Int
    fun BilipaiShareOwnerState(token:Long,owner:IntByReference,execution:IntByReference,bound:IntByReference,released:IntByReference):Int
    fun BilipaiShareDispatchStats(registered:IntByReference,unregistered:IntByReference,completed:IntByReference,timedOut:IntByReference,destroyed:IntByReference,live:IntByReference):Int
}
private interface Kernel:StdCallLibrary {fun GetCurrentThreadId():Int;fun GetCurrentProcessId():Int}
private interface HookProc:StdCallLibrary.StdCallCallback {fun callback(code:Int,wparam:Long,lparam:Pointer):Long}
private interface User:StdCallLibrary {
    fun GetWindowThreadProcessId(hwnd:Pointer,pid:IntByReference):Int
    fun IsWindow(hwnd:Pointer):Boolean;fun IsWindowVisible(hwnd:Pointer):Boolean
    fun SetWindowsHookExW(type:Int,callback:HookProc,module:Pointer?,thread:Int):Pointer?
    fun UnhookWindowsHookEx(hook:Pointer):Boolean
    fun CallNextHookEx(hook:Pointer?,code:Int,wparam:Long,lparam:Pointer):Long
    fun RegisterWindowMessageW(name:WString):Int
    fun SendMessageTimeoutW(hwnd:Pointer,message:Int,wparam:Long,lparam:Long,flags:Int,timeout:Int,result:LongByReference):Long
}
@Suppress("DEPRECATION") private class Fence:SecurityManager() {
    val denied=CopyOnWriteArrayList<String>();override fun checkPermission(permission:Permission) {}
    override fun checkConnect(host:String,port:Int){denied.add("connect");throw SecurityException("No sockets")}
    override fun checkListen(port:Int){denied.add("listen");throw SecurityException("No listener")}
    override fun checkAccept(host:String,port:Int){denied.add("accept");throw SecurityException("No accept")}
    override fun checkExec(command:String){denied.add("exec");throw SecurityException("No process")}
}
private fun sha(p:Path)=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)).joinToString(""){"%02x".format(it)}
fun main(args:Array<String>) {
    val output=Path.of(args[0]);Files.createDirectories(output);val dll=Path.of(args[1]);check(sha(dll)==args[2])
    val fence=Fence();@Suppress("DEPRECATION") System.setSecurityManager(fence)
    val bridge=Native.load(dll.toAbsolutePath().toString(),Bridge::class.java)
    val kernel=Native.load("kernel32",Kernel::class.java);val user=Native.load("user32",User::class.java)
    check(bridge.BilipaiShareProbe()>=0)
    val root=Files.createTempDirectory("bp-owner-bind-data-");val copy=root.resolve("last_crash_log.txt")
    Files.writeString(copy,"Task-only synthetic native owner Bind acceptance; no account or real crash.\n")
    val original=sha(copy);val events=mutableListOf<JsonObject>();var window:ComposeWindow?=null;var hwnd:Pointer?=null
    var edt=0;var owner=0;var pid=0;var assertions=0
    fun requireFact(condition:Boolean,label:String){check(condition){label};assertions++;println("PASS $label")}
    fun stats(label:String):JsonObject {
        val r=List(6){IntByReference()};check(bridge.BilipaiShareDispatchStats(r[0],r[1],r[2],r[3],r[4],r[5])>=0)
        return buildJsonObject {put("label",label);put("hooksRegistered",r[0].value);put("hooksUnregistered",r[1].value);put("commandsCompleted",r[2].value);put("timeouts",r[3].value);put("windowDestroyDrains",r[4].value);put("liveDispatchers",r[5].value)}.also{events.add(it)}
    }
    fun prepare():Long {val token=LongByReference();check(bridge.BilipaiSharePrepare(WString(copy.toAbsolutePath().toString()),token)>=0&&token.value!=0L);return token.value}
    fun verifyOwner(token:Long){val r=List(4){IntByReference()};check(bridge.BilipaiShareOwnerState(token,r[0],r[1],r[2],r[3])>=0)
        requireFact(r[0].value==owner&&r[1].value==owner&&owner!=edt,"actual WinRT binding executed on HWND owner, distinct from Swing EDT")
        requireFact(r[2].value==1&&r[3].value==0,"actual bound owner has not been released before explicit Retire")
        val state=IntByReference();check(bridge.BilipaiShareState(token,state)>=0);requireFact(state.value==0,"Bind stays prepared; no pane requested and no storage supplied")
    }
    try {
        SwingUtilities.invokeAndWait {val value=ComposeWindow();window=value;value.setSize(320,240);value.addNotify();hwnd=Native.getWindowPointer(value)
            edt=kernel.GetCurrentThreadId();val p=IntByReference();owner=user.GetWindowThreadProcessId(hwnd!!,p);pid=p.value
            requireFact(value.isDisplayable&&!value.isVisible&&!user.IsWindowVisible(hwnd!!),"own actual ComposeWindow is displayable and always invisible")
            requireFact(pid==kernel.GetCurrentProcessId()&&owner!=edt,"same-process HWND owner differs from caller EDT")
        }
        repeat(8){round->
            val token=prepare();SwingUtilities.invokeAndWait {val hr=bridge.BilipaiShareBind(token,hwnd);requireFact(hr>=0,"Bind HRESULT success round$round: ${hr.toUInt().toString(16)}");verifyOwner(token)}
            val s=stats("bound-$round");requireFact(s["liveDispatchers"]!!.jsonPrimitive.int==1,"only one exact-window dispatcher while bound")
            if(round==0){val other=prepare();SwingUtilities.invokeAndWait {requireFact(bridge.BilipaiShareBind(other,hwnd)<0,"second source cannot bind same HWND before first Retire");check(bridge.BilipaiShareClose(other)>=0)} }
            val state=IntByReference(-1);val supplied=IntByReference(1)
            SwingUtilities.invokeAndWait {check(bridge.BilipaiShareRetire(token,state,supplied)>=0)}
            requireFact(state.value==0&&supplied.value==0,"post-owner-drain Retire explicitly confirms no supply")
            requireFact(bridge.BilipaiShareState(token,IntByReference())<0,"retired token is removed only after drain")
            val end=stats("retired-$round");requireFact(end["hooksRegistered"]==end["hooksUnregistered"]&&end["liveDispatchers"]!!.jsonPrimitive.int==0,"hook unregister and dispatcher drain round$round")
            requireFact(sha(copy)==original,"original task copy bytes remain immutable")
        }
        // Task-only exact-own-window hook blocks the real HWND pump. The product
        // dispatcher must report unknown, keep its token/copy, and later accept
        // an explicit owner acknowledgement. No pane or data request is made.
        val stalled=prepare();SwingUtilities.invokeAndWait {check(bridge.BilipaiShareBind(stalled,hwnd)>=0);verifyOwner(stalled)}
        val blockMessage=user.RegisterWindowMessageW(WString("BiliPai.Task.OwnerBindProof.Block.48d0635b-cc7c-4be4-85e6-bd4e8f4d53a5"));check(blockMessage!=0)
        val entered=CountDownLatch(1);val release=CountDownLatch(1);var blockOwner=0
        val callback=object:HookProc {override fun callback(code:Int,wparam:Long,lparam:Pointer):Long {
            if(code>=0&&lparam.getInt(16)==blockMessage&&lparam.getPointer(24)==hwnd) {
                blockOwner=kernel.GetCurrentThreadId();entered.countDown();release.await(25,TimeUnit.SECONDS)
            }
            return user.CallNextHookEx(null,code,wparam,lparam)
        }}
        val taskHook=user.SetWindowsHookExW(4,callback,null,owner);requireFact(taskHook!=null,"task-only exact owner-thread blocker hook installed")
        val sender=Thread({user.SendMessageTimeoutW(hwnd!!,blockMessage,0,0,3,28000,LongByReference())},"task-own-window-blocker")
        sender.start()
        try {
            requireFact(entered.await(3,TimeUnit.SECONDS)&&blockOwner==owner,"task blocker holds actual HWND owner pump")
            val failedState=IntByReference(88);val failedSupply=IntByReference(88);var hr=0
            SwingUtilities.invokeAndWait {hr=bridge.BilipaiShareRetire(stalled,failedState,failedSupply)}
            requireFact(hr<0&&failedState.value==-1&&failedSupply.value==1,"timed-out Retire is unknown, never no-data or terminal success")
            val pending=IntByReference(-1);requireFact(bridge.BilipaiShareState(stalled,pending)>=0&&pending.value==0,"unacknowledged Retire retains actual prepared token")
            requireFact(sha(copy)==original,"unknown native outcome retains task file copy")
            val before=stats("blocked-owner-unknown");requireFact(before["timeouts"]!!.jsonPrimitive.int>=1&&before["liveDispatchers"]!!.jsonPrimitive.int==1,"timeout telemetry preserves live owner dispatcher")
            repeat(2){retry->
                val repeatedState=IntByReference();val repeatedSupply=IntByReference();var repeated=0
                // Invoke directly on this existing caller thread to isolate the
                // native dispatcher from a second Swing EventQueue enqueue while
                // the native AWT toolkit is intentionally stopped in our hook.
                val start=System.nanoTime();repeated=bridge.BilipaiShareRetire(stalled,repeatedState,repeatedSupply)
                println("NATIVE_RETRY elapsedMs=${(System.nanoTime()-start)/1_000_000} callerTid=${kernel.GetCurrentThreadId()} ownerTid=$owner")
                requireFact(repeated<0&&repeatedState.value==-1&&repeatedSupply.value==1,"repeated blocked Close retry $retry remains unknown")
            }
        }finally {release.countDown();sender.join(4000);check(!sender.isAlive);check(user.UnhookWindowsHookEx(taskHook!!))}
        val retryState=IntByReference(-1);val retrySupply=IntByReference(1)
        SwingUtilities.invokeAndWait {check(bridge.BilipaiShareRetire(stalled,retryState,retrySupply)>=0)}
        requireFact(retryState.value==0&&retrySupply.value==0&&bridge.BilipaiShareState(stalled,IntByReference())<0,"explicit owner Retire retry confirms no-data and removes token")
        val after=stats("blocked-owner-retry-drained");requireFact(after["hooksRegistered"]==after["hooksUnregistered"]&&after["liveDispatchers"]!!.jsonPrimitive.int==0,"retry acknowledges canceled queued commands and unhooks dispatcher")
        requireFact(after["commandsCompleted"]!!.jsonPrimitive.int==18,"three blocked Retire retries share one actual owner Close command")
        // Separate Bind admission while the native owner has not processed its
        // command. No WinRT apartment or data authority can be claimed from it.
        val pendingBind=prepare();val enteredBind=CountDownLatch(1);val releaseBind=CountDownLatch(1)
        val bindCallback=object:HookProc {override fun callback(code:Int,wparam:Long,lparam:Pointer):Long {
            if(code>=0&&lparam.getInt(16)==blockMessage&&lparam.getPointer(24)==hwnd){enteredBind.countDown();check(releaseBind.await(12,TimeUnit.SECONDS))}
            return user.CallNextHookEx(null,code,wparam,lparam)
        }}
        val bindTaskHook=user.SetWindowsHookExW(4,bindCallback,null,owner);check(bindTaskHook!=null)
        val bindSender=Thread({user.SendMessageTimeoutW(hwnd!!,blockMessage,0,0,3,15000,LongByReference())},"task-own-window-bind-blocker");bindSender.start()
        try {
            requireFact(enteredBind.await(3,TimeUnit.SECONDS),"second task blocker holds HWND before Bind")
            var bindHr=0;SwingUtilities.invokeAndWait {bindHr=bridge.BilipaiShareBind(pendingBind,hwnd)}
            requireFact(bindHr<0,"blocked Bind returns unacknowledged failure")
            val state=IntByReference();requireFact(bridge.BilipaiShareState(pendingBind,state)>=0&&state.value==0,"Bind timeout leaves real token prepared, not pane or terminal")
            val ownership=List(4){IntByReference()};check(bridge.BilipaiShareOwnerState(pendingBind,ownership[0],ownership[1],ownership[2],ownership[3])>=0)
            requireFact(ownership[1].value==0&&ownership[2].value==0&&ownership[3].value==0,"no owner execution or bound apartment invented for queued Bind")
            val failureState=IntByReference();val failureSupply=IntByReference();var retireHr=0
            SwingUtilities.invokeAndWait {retireHr=bridge.BilipaiShareRetire(pendingBind,failureState,failureSupply)}
            requireFact(retireHr<0&&failureState.value==-1&&failureSupply.value==1,"queued Bind and Retire remain unknown while owner cannot acknowledge")
            requireFact(sha(copy)==original,"blocked Bind retains immutable task copy")
        }finally{releaseBind.countDown();bindSender.join(4000);check(!bindSender.isAlive);check(user.UnhookWindowsHookEx(bindTaskHook!!))}
        SwingUtilities.invokeAndWait {requireFact(bridge.BilipaiShareClose(pendingBind)>=0,"explicit Close after Bind timeout drains owner queued commands")}
        requireFact(bridge.BilipaiShareState(pendingBind,IntByReference())<0,"Close erases token after real acknowledgement")
        val bindEnd=stats("queued-bind-close-drained");requireFact(bindEnd["hooksRegistered"]==bindEnd["hooksUnregistered"]&&bindEnd["liveDispatchers"]!!.jsonPrimitive.int==0,"Bind timeout and Close retry leave no installed native hooks")
        val token=prepare();SwingUtilities.invokeAndWait {check(bridge.BilipaiShareBind(token,hwnd)>=0);verifyOwner(token);window!!.dispose()}
        requireFact(!user.IsWindow(hwnd!!)&&!window!!.isDisplayable,"own HWND destroyed after EDT dispose")
        val r=List(4){IntByReference()};check(bridge.BilipaiShareOwnerState(token,r[0],r[1],r[2],r[3])>=0)
        requireFact(r[3].value==1&&r[1].value==owner,"WM_NCDESTROY revoked and drained native source on owner thread")
        val state=IntByReference(-1);val supplied=IntByReference(1)
        check(bridge.BilipaiShareRetire(token,state,supplied)>=0)
        requireFact(state.value==0&&supplied.value==0,"Retire after destroyed HWND preserves truthful no-data outcome")
        val end=stats("window-destroy-retired");requireFact(end["hooksRegistered"]==end["hooksUnregistered"]&&end["liveDispatchers"]!!.jsonPrimitive.int==0&&end["windowDestroyDrains"]!!.jsonPrimitive.int>=1,"destroyed-window dispatcher unregistered and drained")
        requireFact(fence.denied.isEmpty(),"no external socket/listener/process or share receiver")
        Files.writeString(output.resolve("result.json"),buildJsonObject {
            put("passed",true);put("assertions",assertions);put("actualHiddenComposeWindow",true);put("nativeEdtThreadId",edt);put("nativeHwndOwnerThreadId",owner);put("nativeProcessId",pid)
            put("ownerBindActuallyExecuted",true);put("retireOwnerActuallyExecuted",true);put("repeatBindRetireCycles",8);put("explicitWindowDestroyDrain",true)
            put("actualOwnerPumpBlocked",true);put("retireTimeoutUnknownTokenRetained",true);put("bindTimeoutNeverAcquiresApartment",true);put("explicitCloseRetryDrainsCanceledCommands",true)
            put("repeatedCloseRetryHasBoundedCommandAdmission",true)
            put("nativeSourceAndDllTaskOnly",true);put("productionOverrides",0);put("MainOrShell",false);put("accountOrActor",false);put("ShareShow",false);put("ShareUI",false);put("receiver",false);put("package",false)
            put("events",JsonArray(events));put("copySha256Bytes",original);put("copyPreserved",sha(copy)==original)
        }.toString())
    }catch(t:Throwable){Files.writeString(output.resolve("FAILED.txt"),t.stackTraceToString());throw t}
    finally{SwingUtilities.invokeAndWait {window?.dispose()};SwingUtilities.invokeAndWait {};Files.writeString(output.resolve("stats-final.json"),stats("finally").toString())}
}
