package com.bilipai.desktop.player

import com.android.purebilibili.core.plugin.PluginStore
import com.bilipai.desktop.plugins.*
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.awt.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.Permission
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

private interface OwnUser32 : StdCallLibrary {
 fun GetForegroundWindow():Pointer?
 fun GetAncestor(window:Pointer?,flags:Int):Pointer?
 fun GetWindowThreadProcessId(window:Pointer?,process:IntByReference):Int
 fun WindowFromPoint(point:Long):Pointer?
 fun GetClassName(window:Pointer?,text:CharArray,capacity:Int):Int
 fun SetForegroundWindow(window:Pointer?):Boolean
 fun BringWindowToTop(window:Pointer?):Boolean
 fun AttachThreadInput(source:Int,target:Int,attach:Boolean):Boolean
}
internal object NativeProofWindowGuard {
 private val api=Native.load("user32",OwnUser32::class.java,W32APIOptions.DEFAULT_OPTIONS)
 lateinit var window:JFrame
 fun <T> edt(block:()->T):T {
  if(SwingUtilities.isEventDispatchThread())return block()
  val task=FutureTask(Callable(block));SwingUtilities.invokeAndWait(task);return task.get()
 }
 fun acquireOwnForeground()=edt {
  val handle=Native.getWindowPointer(window);val process=IntByReference();api.GetWindowThreadProcessId(handle,process)
  check(process.value.toLong()==ProcessHandle.current().pid())
  val current=Kernel32.INSTANCE.GetCurrentThreadId();val target=api.GetWindowThreadProcessId(api.GetForegroundWindow(),IntByReference())
  val attached=target!=0 && current!=target && api.AttachThreadInput(current,target,true)
  try { window.toFront();api.BringWindowToTop(handle);api.SetForegroundWindow(handle);window.requestFocus() }
  finally { if(attached)check(api.AttachThreadInput(current,target,false)) }
 }
 fun check(player:MpvPlayer,bounds:Rectangle?=null):String=edt {
  check(window.isShowing);val handle=Native.getWindowPointer(window);val process=IntByReference();api.GetWindowThreadProcessId(handle,process)
  check(process.value.toLong()==ProcessHandle.current().pid()) {"Foreign proof HWND"}
  check(Pointer.nativeValue(api.GetAncestor(api.GetForegroundWindow(),2))==Pointer.nativeValue(handle)) {"Task window lost foreground; capture refused"}
  fun descendants(c:Component):List<Component> = listOf(c)+(c as? Container)?.components.orEmpty().flatMap(::descendants)
  val canvas=descendants(player.surface).filterIsInstance<Canvas>().single();check(canvas.isShowing)
  val target=Native.getComponentPointer(canvas)
  val rect=bounds ?: player.surface.let { val loc=it.locationOnScreen;Rectangle(loc.x,loc.y,it.width,it.height) }
  val point=Point(rect.x+rect.width/2,rect.y+rect.height/2)
  val packed=(point.x.toLong() and 0xffffffffL) or (point.y.toLong() shl 32)
  check(Pointer.nativeValue(api.WindowFromPoint(packed))==Pointer.nativeValue(target)) {"Sample point is not the actual mpv Canvas HWND"}
  val name=CharArray(256);val count=api.GetClassName(target,name,name.size);String(name,0,count).also { check(it=="SunAwtCanvas") }
 }
}
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
private class NoNetwork:SecurityManager() {
 val rejected=java.util.concurrent.atomic.AtomicInteger()
 override fun checkPermission(permission:Permission) {}
 override fun checkPermission(permission:Permission,context:Any?) {}
 override fun checkConnect(host:String?,port:Int) { rejected.incrementAndGet();throw SecurityException("Native fixture forbids Java network connections") }
 override fun checkConnect(host:String?,port:Int,context:Any?) = checkConnect(host,port)
 override fun checkListen(port:Int) { rejected.incrementAndGet();throw SecurityException("Native fixture forbids Java listeners") }
}
@Suppress("DEPRECATION")
fun main(args:Array<String>) {
 require(args.size==4)
 val videos=args.take(3).map { File(it).absoluteFile };check(videos.all { it.isFile })
 val output=File(args[3]).absoluteFile.apply { mkdirs() };System.setProperty("bilipai.fsr.probe.output",output.absolutePath)
 val guard=NoNetwork();System.setSecurityManager(guard)
 val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
 var runtime:DesktopPluginRuntime?=null;var player:MpvPlayer?=null;var failure:Throwable?=null
 val checks=linkedMapOf<String,String>();val epoch=AtomicLong(71L)
 try {
  val store=DesktopPluginStore(output.toPath().resolve("plugin-root"));Files.createDirectories(store.root)
  val context=DesktopPluginContext(store)
  val ids=listOf("sponsor_block","danmaku_enhance","eye_protection","anime4k","bilipai_feed_filter","home_feed_anonymizer","adfilter","subscription_feed","today_watch","cdn_region","dlna_cast","google_cast")
  runBlocking { ids.forEach { PluginStore.setEnabled(context,it,false) } }
  runtime=DesktopPluginRuntime(store)
  val active=runtime
  runBlocking { withTimeout(15_000) { ids.forEach { com.android.purebilibili.core.plugin.PluginManager.awaitPluginReady(it) } } }
  check(active.plugins.value.size==ids.size && active.plugins.value.none { it.enabled })
  check(active.jsPlugins.state.value.plugins.isEmpty())
  checks["allNonNecessaryProvidersDisabledBeforeRuntimeInitialization"]="passed"
  checks["realRuntimeUsesVerifiedEmptyLocalJsHost"]="passed"
  player=MpvPlayer(useNullAudioOutput=true);val native=player
  NativeProofWindowGuard.edt {
   NativeProofWindowGuard.window=JFrame("BiliPai own Runtime FSR ${UUID.randomUUID()}").apply {
    defaultCloseOperation=JFrame.DO_NOTHING_ON_CLOSE;layout=BorderLayout();add(native.surface,BorderLayout.CENTER)
    setSize(720,440);setLocation(90,75);isAlwaysOnTop=true;isVisible=true
   }
  }
  NativeProofWindowGuard.acquireOwnForeground()
  val until=System.nanoTime()+15_000_000_000L
  while(!native.state.value.ready && System.nanoTime()<until)Thread.sleep(25)
  check(native.state.value.ready);NativeProofWindowGuard.check(native)
  checks.putAll(RuntimeFsrNativeSmoke.run(native,videos[0],videos[1],videos[2],output,active,scope,epoch))
  check(guard.rejected.get()==0) {"Runtime attempted forbidden networking"}
  check(active.plugins.value.filter { it.enabled }.all { it.plugin===active.videoEnhancement })
  checks["noUnexpectedJavaNetworkConnectionsOrListeners"]="passed"
 } catch(error:Throwable) { failure=error;error.printStackTrace() }
 finally {
  try { runtime?.let { runBlocking { withTimeout(20_000) { it.shutdownForRestore() } } } }
  catch(error:Throwable) { error.printStackTrace();if(failure==null)failure=error }
  scope.cancel();player?.close()
  NativeProofWindowGuard.edt { try { NativeProofWindowGuard.window.dispose() } catch(_:UninitializedPropertyAccessException) {} }
  output.resolve("runtime-native-report.json").writeText(buildJsonObject {
   put("passed",failure==null);put("failureType",failure?.javaClass?.simpleName ?: "")
   put("checkCount",checks.size);put("checks",JsonObject(checks.mapValues { JsonPrimitive(it.value) }))
   put("javaNetworkAttempts",guard.rejected.get());put("sessionEpoch",epoch.get());put("pid",ProcessHandle.current().pid())
   put("mainRuntimeProviderUsed",true);put("realAccountRequests",false)
  }.toString())
 }
 exitProcess(if(failure==null)0 else 1)
}
