package fixture.runtime68

import com.android.purebilibili.core.plugin.*
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit memory-only native-lease/admission input. Not an Account/MPV/Root implementation. */
private class MemoryNativeLease {
    private val monitor = Any()
    val job = SupervisorJob()
    private var alive = true
    var accept = true
    private val depth = ThreadLocal.withInitial { 0 }
    fun owns(): Boolean = synchronized(monitor) { alive && job.isActive }
    fun retire() = synchronized(monitor) { alive = false }
    fun inside(): Boolean = depth.get() != 0
    fun admit(block: () -> Unit): Boolean = synchronized(monitor) {
        if (!alive || !job.isActive || !accept) return@synchronized false
        depth.set(depth.get()+1)
        try { block() } finally { depth.set(depth.get()-1) }
        true
    }
}
/** Registered in the actual PluginManager; only this fixture provider handles video callbacks. */
private class FixturePlayerPlugin : PlayerPlugin {
    override val id = "fixture_runtime68_player"
    override val name = "Memory dispatch fixture"
    override val description = "No HTTP, native or account access"
    override val version = "1"
    val loads = AtomicInteger()
    val ends = AtomicInteger()
    override suspend fun onVideoLoad(bvid: String, cid: Long) { loads.incrementAndGet() }
    override suspend fun onPositionUpdate(positionMs: Long): SkipAction? = null
    override fun onVideoEnd() { ends.incrementAndGet() }
}
private var assertions=0
private fun prove(condition:Boolean, label:String) { check(condition) { label };assertions++;println("ASSERT $assertions $label") }
private fun rejected(result:Result<*>):Boolean = result.exceptionOrNull() is CancellationException
private suspend fun waitUntil(block:()->Boolean) = withTimeout(8000) { while(!block()) delay(5) }
private fun origin(c:Class<*>):JsonObject {
    val path=Path.of(c.protectionDomain.codeSource.location.toURI()).toAbsolutePath()
    val entry=c.name.replace('.','/')+".class"
    val bytes=c.getResourceAsStream("/"+entry)!!.use { it.readBytes() }
    return buildJsonObject { put("class",c.name);put("codeSource",path.toString());put("entry",entry);put("classSHA256Bytes",MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}) }
}
fun main() {
 var exit=1
 runBlocking {
  val out=Path.of(System.getProperty("fixture.output"));Files.createDirectories(out)
  val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
  val store=DesktopPluginStore(out.resolve("private-store"))
  val ids=listOf("sponsor_block","danmaku_enhance","eye_protection","anime4k","bilipai_feed_filter","home_feed_anonymizer","adfilter","subscription_feed","today_watch","cdn_region","dlna_cast","google_cast")
  store.update("plugin_prefs",ids.associate { "plugin_enabled_$it" to JsonPrimitive(false) }+mapOf("plugin_enabled_fixture_runtime68_player" to JsonPrimitive(true)))
  val runtime=DesktopPluginRuntime(store)
  val other=DesktopPluginRuntime(store)
  val plugin=FixturePlayerPlugin()
  val owners=mutableListOf<MemoryNativeLease>()
  fun owner()=MemoryNativeLease().also { owners+=it }
  suspend fun capture(bvid:String,cid:Long,lease:MemoryNativeLease):DesktopPlayerPluginDispatch {
   val gen=runtime.onVideoLoad(bvid,cid)
   return checkNotNull(runtime.capturePlaybackPluginDispatch(bvid,cid,gen,lease::owns,lease::admit))
  }
  var failure:Throwable?=null
  try {
   for(id in ids) PluginManager.awaitPluginReady(id)
   for(info in PluginManager.pluginsFlow.value) if(info.enabled) PluginManager.setEnabled(info.plugin.id,false)
   PluginManager.register(plugin);PluginManager.awaitPluginReady(plugin.id)
   prove(PluginManager.getEnabledPlayerPlugins()==listOf(plugin),"only registered fixture PlayerPlugin enabled; Sponsor disabled")
   val lease=owner()
   prove(runtime.capturePlaybackPluginDispatch("BVfixture",1,0,lease::owns,lease::admit)==null,"capture requires actual loaded video identity")
   val dispatch=capture("BVfixture",1,lease)
   prove(runtime.isPlaybackPluginDispatchCurrent(dispatch),"actual generation/BVID/CID capture current")
   prove(runtime.capturePlaybackPluginDispatch("wrong",1,dispatch.generation,lease::owns,lease::admit)==null,"wrong BVID rejected")
   prove(runtime.capturePlaybackPluginDispatch("BVfixture",2,dispatch.generation,lease::owns,lease::admit)==null,"wrong CID rejected")
   prove(runtime.capturePlaybackPluginDispatch("BVfixture",1,dispatch.generation-1,lease::owns,lease::admit)==null,"old generation rejected")
   prove(!other.isPlaybackPluginDispatchCurrent(dispatch),"foreign Runtime token rejected")
   prove(rejected(runCatching { other.mutatePlaybackPlugin(dispatch,plugin,false) { error("foreign body") } }),"foreign mutation cannot execute")
   prove(rejected(runCatching { runtime.mutatePlaybackPlugin(dispatch,FixturePlayerPlugin(),false) { error("uncaptured body") } }),"provider identity cannot be replaced with same id")
   val writes=AtomicInteger()
   val value=runtime.mutatePlaybackPlugin(dispatch,plugin,false) { prove(lease.inside(),"synchronous mutation inside short admission");writes.incrementAndGet();42 }
   prove(value==42 && writes.get()==1,"accepted mutation returns once")
   val callback=runtime.runPlaybackPluginCallback(dispatch,plugin) {
    prove(!lease.inside(),"suspending callback starts outside admission");yield();prove(!lease.inside(),"suspending callback resumes outside admission");7
   }
   prove(callback==7,"callback result admitted after suspension")
   lease.accept=false
   prove(rejected(runCatching { runtime.mutatePlaybackPlugin(dispatch,plugin,false) { writes.incrementAndGet() } }),"short mutation admission rejection")
   prove(rejected(runCatching { runtime.runPlaybackPluginCallback(dispatch,plugin) { error("rejected callback body") } }),"short callback admission rejection")
   prove(writes.get()==1,"rejection did not run mutation")
   lease.accept=true

   // The held callback owns the actual playerMutex. Waiting work does not enter the lease monitor.
   val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
   val held=scope.async { runCatching { runtime.runPlaybackPluginCallback(dispatch,plugin) { entered.complete(Unit);release.await();99 } } }
   entered.await()
   val queued=scope.async(start=CoroutineStart.UNDISPATCHED) { runCatching { runtime.mutatePlaybackPlugin(dispatch,plugin,false) { writes.incrementAndGet() } } }
   prove(!queued.isCompleted && !lease.inside(),"queued mutation waits outside admission")
   lease.retire();release.complete(Unit)
   prove(rejected(held.await()),"entry retired during callback rejects result")
   prove(rejected(queued.await()) && writes.get()==1,"entry retired during mutex wait rejects queued body")

   val providerOwner=owner();val providerDispatch=capture("BVprovider",3,providerOwner)
   val pe=CompletableDeferred<Unit>();val pr=CompletableDeferred<Unit>()
   val pending=scope.async { runCatching { runtime.runPlaybackPluginCallback(providerDispatch,plugin) { pe.complete(Unit);pr.await();100 } } };pe.await()
   PluginManager.setEnabled(plugin.id,false);pr.complete(Unit)
   prove(rejected(pending.await()),"disabled actual provider rejects late callback result")
   prove(rejected(runCatching { runtime.mutatePlaybackPlugin(providerDispatch,plugin,false) { error("disabled body") } }),"disabled actual provider rejects synchronous mutation")
   PluginManager.setEnabled(plugin.id,true)

   val cancelOwner=owner();val cancelDispatch=capture("BVcancel",4,cancelOwner)
   val ce=CompletableDeferred<Unit>();val cr=CompletableDeferred<Unit>()
   val keeper=scope.async { runtime.runPlaybackPluginCallback(cancelDispatch,plugin) { ce.complete(Unit);cr.await();5 } };ce.await()
   val cancelWrites=AtomicInteger()
   val cancelledWait=scope.async(start=CoroutineStart.UNDISPATCHED) { runtime.mutatePlaybackPlugin(cancelDispatch,plugin,false) { cancelWrites.incrementAndGet() } }
   cancelledWait.cancelAndJoin();cr.complete(Unit)
   prove(keeper.await()==5 && cancelWrites.get()==0,"cancelled caller waiting on mutex does not publish")

   val oldOwner=owner();val old=capture("BVold",5,oldOwner)
   val oe=CompletableDeferred<Unit>();val or=CompletableDeferred<Unit>()
   val oldCallback=scope.async { runCatching { runtime.runPlaybackPluginCallback(old,plugin) { oe.complete(Unit);or.await();8 } } };oe.await()
   val endsBefore=plugin.ends.get()
   oldOwner.job.cancel()
   prove(runtime.retirePlaybackPluginDispatch(old),"retire exact old dispatch after caller lifetime cancellation")
   prove(!runtime.retirePlaybackPluginDispatch(old),"repeat retirement rejected")
   val nextOwner=owner()
   val next=scope.async(start=CoroutineStart.UNDISPATCHED) { runtime.onVideoLoad("BVnew",6) }
   prove(!next.isCompleted,"new load waits behind actual callback mutex after incrementing generation")
   or.complete(Unit)
   prove(rejected(oldCallback.await()),"old callback cannot return after generation retirement")
   val nextGen=next.await()
   val nextDispatch=checkNotNull(runtime.capturePlaybackPluginDispatch("BVnew",6,nextGen,nextOwner::owns,nextOwner::admit))
   runtime.runPlaybackPluginCallback(nextDispatch,plugin) { Unit } // drain earlier cleanup's mutex turn
   prove(runtime.isPlaybackPluginDispatchCurrent(nextDispatch),"later loaded generation remains current after old cleanup")
   prove(plugin.ends.get()==endsBefore,"old queued retirement did not reset later load/provider")
   prove(!runtime.retirePlaybackPluginDispatch(old),"late old retirement cannot overwrite current video identity")
   val lastOwner=owner();val last=capture("BVlast",7,lastOwner)
   val lastEnds=plugin.ends.get();lastOwner.retire()
   prove(runtime.retirePlaybackPluginDispatch(last),"retirement cleanup does not depend on dead entry predicate")
   waitUntil { plugin.ends.get()==lastEnds+1 }
   prove(!runtime.isPlaybackPluginDispatchCurrent(last),"retired exact video remains invalid after actual Runtime-scope cleanup")
  } catch(t:Throwable) { failure=t;t.printStackTrace() }
  finally {
   scope.coroutineContext[Job]!!.cancelAndJoin()
   for(o in owners) o.job.cancelAndJoin()
   withTimeout(12000) { runtime.shutdownForRestore();other.shutdownForRestore() }
  }
  if(failure==null) {
   prove(runCatching { store.update("settings",mapOf("fixture_late" to JsonPrimitive(true))) }.isFailure,"actual Runtime shutdown joined and froze same Store writes")
  }
  val origins=listOf(DesktopPluginRuntime::class.java,DesktopPlayerPluginDispatch::class.java,DesktopPluginStore::class.java,PluginManager::class.java).map(::origin)
  val result=buildJsonObject {
   put("passed",failure==null);put("assertions",assertions);put("productionOverrides",0);put("nativeLease","explicit memory-only fixture predicate/admission")
   put("actualRuntimeShutdownJoined",true);put("httpOrAccountsOrGuiOrNativeRun",false)
   put("origins",JsonArray(origins));put("failure",failure?.toString()?.let(::JsonPrimitive)?:JsonNull)
   put("pending",JsonArray(listOf("full Root/NativeOwner integration","legacy CDN delayed writes/history final write guards").map(::JsonPrimitive)))
  }
  Files.writeString(out.resolve("result.json"),Json { prettyPrint=true }.encodeToString(JsonObject.serializer(),result))
  if(failure==null)exit=0
 }
 kotlin.system.exitProcess(exit)
}
