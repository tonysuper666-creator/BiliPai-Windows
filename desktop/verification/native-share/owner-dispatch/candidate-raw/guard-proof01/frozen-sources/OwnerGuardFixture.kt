package com.bilipai.desktop.diagnostics.ownerguardproof
import androidx.compose.ui.awt.ComposeWindow
import com.sun.jna.*
import com.sun.jna.ptr.*
import com.sun.jna.win32.StdCallLibrary
import kotlinx.serialization.json.*
import java.nio.file.*
import java.security.*
import javax.swing.SwingUtilities
private interface Bridge:StdCallLibrary {
 fun BilipaiSharePrepare(path:WString,result:LongByReference):Int
 fun BilipaiShareBind(token:Long,hwnd:Pointer):Int
 fun BilipaiShareState(token:Long,state:IntByReference):Int
 fun BilipaiShareRetire(token:Long,state:IntByReference,supplied:IntByReference):Int
 fun BilipaiShareDispatchStats(a:IntByReference,b:IntByReference,c:IntByReference,d:IntByReference,e:IntByReference,f:IntByReference):Int
 fun TaskGuardRevokeExercise(token:Long,mode:Int,output:Pointer):Int
}
private interface User:StdCallLibrary {fun GetWindowThreadProcessId(hwnd:Pointer,pid:IntByReference):Int;fun IsWindow(hwnd:Pointer):Boolean;fun IsWindowVisible(hwnd:Pointer):Boolean}
private interface Kernel:StdCallLibrary {fun GetCurrentThreadId():Int;fun GetCurrentProcessId():Int}
@Suppress("DEPRECATION") private class Fence:SecurityManager(){var attempts=0;override fun checkPermission(p:Permission){};override fun checkConnect(h:String,p:Int){attempts++;throw SecurityException("no socket")};override fun checkListen(p:Int){attempts++;throw SecurityException("no listen")};override fun checkAccept(h:String,p:Int){attempts++;throw SecurityException("no accept")};override fun checkExec(c:String){attempts++;throw SecurityException("no exec")}}
private fun sha(p:Path)=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)).joinToString(""){"%02x".format(it)}
fun main(args:Array<String>){
 val output=Path.of(args[0]);Files.createDirectories(output);val dll=Path.of(args[1]);check(sha(dll)==args[2])
 val fence=Fence();@Suppress("DEPRECATION") System.setSecurityManager(fence)
 val bridge=Native.load(dll.toAbsolutePath().toString(),Bridge::class.java);val user=Native.load("user32",User::class.java);val kernel=Native.load("kernel32",Kernel::class.java)
 val data=Files.createTempDirectory("bp-owner-guard-data-");val copy=data.resolve("last_crash_log.txt");Files.writeString(copy,"Synthetic native guard/fault fixture only; no real crash/account.\n");val original=sha(copy)
 val cases=mutableListOf<JsonObject>();var assertions=0;fun fact(ok:Boolean,label:String){check(ok){label};assertions++;println("PASS $label")}
 val names=listOf("nested-close","nested-retire","nested-real-window-destroy","checked-abi-revoke-fail-retry")
 for(mode in names.indices){
  var window:ComposeWindow?=null;var hwnd:Pointer?=null;var owner=0;var edt=0
  try{
   SwingUtilities.invokeAndWait{window=ComposeWindow().also{it.setSize(320,240);it.addNotify();hwnd=Native.getWindowPointer(it)};edt=kernel.GetCurrentThreadId();val pid=IntByReference();owner=user.GetWindowThreadProcessId(hwnd!!,pid)
    fact(!window!!.isVisible&&window!!.isDisplayable&&!user.IsWindowVisible(hwnd!!),"$mode exact own ComposeWindow always invisible")
    fact(pid.value==kernel.GetCurrentProcessId()&&owner!=edt,"$mode HWND actual owner differs from Swing EDT")}
   val token=LongByReference();check(bridge.BilipaiSharePrepare(WString(copy.toAbsolutePath().toString()),token)>=0)
   SwingUtilities.invokeAndWait{fact(bridge.BilipaiShareBind(token.value,hwnd!!)>=0,"$mode actual STA/GetForWindow Bind succeeds")}
   val memory=Memory(24*4L);memory.clear();var hr=0
   SwingUtilities.invokeAndWait{hr=bridge.TaskGuardRevokeExercise(token.value,mode,memory)}
   val values=(0 until 24).map{memory.getInt(it*4L)}
   println("NATIVE_CASE ${names[mode]} hr=${hr.toUInt().toString(16)} fields=$values")
   fact(hr>=0&&values[1]==1,"$mode actual candidate Session guard assertions pass")
   fact(values[15]==owner,"$mode helper body and cleanup execute on exact HWND owner")
   fact(values[9]==1&&values[10]==0&&values[11]==0,"$mode only acknowledged final unwind/retry releases apartment/package")
   if(mode<=2){fact(values[3]<0&&values[6]==1&&values[7]==0,"$mode nested close is unknown and keeps real apartment/package/file before outer unwind")
    if(mode!=0)fact(values[4]==-1&&values[5]==1,"$mode nested Retire fail-closed output preserves copy authority")
    if(mode==2)fact(values[16]==0&&!user.IsWindow(hwnd!!),"real DestroyWindow nested in callback scope destroys own HWND")
   }else{fact(values[8]==1&&values[18]==0&&values[19]==1,"actual failed registration and STA are retained after E_FAIL")
    fact(values[12]==2&&values[13]==1,"two ABI remove attempts include exactly one actual successful WinRT revoke")
    fact(values[14]<0&&values[20]==1,"recursive cleanup during revoke is pending and retains actual refs")}
   fact(bridge.BilipaiShareState(token.value,IntByReference())<0,"$mode token removed only after confirmed outer unwind or real retry")
   fact(sha(copy)==original,"$mode immutable synthetic copy is retained")
   val stats=List(6){IntByReference()};check(bridge.BilipaiShareDispatchStats(stats[0],stats[1],stats[2],stats[3],stats[4],stats[5])>=0)
   fact(stats[0].value==stats[1].value&&stats[5].value==0,"$mode product hooks unregistered and callback-drained")
   cases.add(buildJsonObject{put("name",names[mode]);put("nativeHelperFields",JsonArray(values.map(::JsonPrimitive)));put("nativeAssertions",values[2]);put("hwndOwnerTid",owner);put("edtTid",edt);put("passed",true);put("registeredProductHooks",stats[0].value);put("unregisteredProductHooks",stats[1].value);put("liveProductDispatchers",stats[5].value)})
  }catch(t:Throwable){Files.writeString(output.resolve("FAILED-$mode.txt"),t.stackTraceToString());throw t}
  finally{SwingUtilities.invokeAndWait{window?.dispose()};SwingUtilities.invokeAndWait{};println("DISPOSE_DRAIN $mode")}
 }
 fact(fence.attempts==0,"no socket/listener/exec attempts")
 Files.writeString(output.resolve("result.json"),buildJsonObject{
  put("passed",true);put("kotlinAssertions",assertions);put("nativeAssertions",cases.sumOf{it["nativeAssertions"]!!.jsonPrimitive.int});put("cases",JsonArray(cases));put("productionOverrides",0)
  put("MainOrShell",false);put("actualMainActorIntegration",false);put("taskOnlyNativeHelperIncludesExactCandidateSource",true);put("syntheticCallbackScopeAndFaultAbiWrapper",true)
  put("actualComposeHwndAndWinrtManager",true);put("actualNativePackageLocalOnly",true);put("actualWinrtRegistrationRevokeOnRetry",true);put("ShareShow",false);put("DataRequestedBodyInvoked",false);put("SetStorageItems",false);put("receiver",false);put("packageRuntime",false);put("copyPreserved",sha(copy)==original)
 }.toString())
}
